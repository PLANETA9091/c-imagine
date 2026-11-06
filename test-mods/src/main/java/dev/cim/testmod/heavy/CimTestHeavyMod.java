package dev.cim.testmod.heavy;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tier-1 cimtest-heavy (MODPACK.md "Planned additions"): the allocation and
 * tick-load generator the profiling budget gate measures against. A command
 * arms a deterministic bounded load for N server ticks; the tick handler
 * burns the configured budget every tick while armed and logs the
 * deterministic markers the CI greps.
 *
 * <p>Determinism contract: the load is pure work (an integer mix loop plus a
 * bounded allocation the JIT cannot elide — the mix result feeds a
 * volatile sink), no world/random access, so the profile deltas measure the
 * load itself, never environment noise. Markers:
 *
 * <pre>
 * [cimtest_heavy] load armed: msPerTick=12 ticks=200
 * [cimtest_heavy] load running: tick=100/200 overMs=12.4
 * [cimtest_heavy] load done: ticks=200 overMsTotal=2480.0 avgMsPerTick=12.4
 * </pre>
 *
 * <p>Server-side only (no client classes). The heavy mod must behave
 * identically on a real NeoForge 1.21.1 server and inside the c-imagine
 * layer — the tick handler rides NeoForge's server-tick event on both.
 */
@Mod(CimTestHeavyMod.MOD_ID)
public final class CimTestHeavyMod {

    public static final String MOD_ID = "cimtest_heavy";
    public static final Logger LOGGER = LoggerFactory.getLogger("cimtest_heavy");

    /** The workload result sink: volatile so the JIT cannot elide the mix. */
    private static volatile long SINK;

    private int remainingTicks;
    private long budgetNanosPerTick;
    private int totalTicks;
    private long totalOverNanos;
    private int overTicks;
    private String profile = "cpu";
    // the entity churn state (the entity profile: the subjects the module's
    // sync path carries — the cimtest TestEntity rides the layer's entity
    // bridge, so its tick+sync charge IS the module's charge)
    private final java.util.List<net.minecraft.world.entity.Entity> churn =
            new java.util.ArrayList<>();
    private int churnIdx;

    public CimTestHeavyMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[cimtest_heavy] mod constructed (load generator armed, no load by default)");
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cimheavy")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("load")
                        .then(Commands.argument("profile", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .then(Commands.argument("msPerTick", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
                                        .then(Commands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 12000))
                                                .executes(ctx -> {
                                                    String profile = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "profile");
                                                    int ms = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "msPerTick");
                                                    int ticks = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ticks");
                                                    arm(profile, ms, ticks,
                                                            ctx.getSource().getServer(), ctx.getSource().getLevel());
                                                    return Command.SINGLE_SUCCESS;
                                                }))))));
    }

    /** Arm the load: the profile's work per server tick, for ticks ticks.
     *  Profiles (the profiling budget measures the MODULE's charge on each
     *  server path it hooks — a bare CPU spin charges nothing the module
     *  touches, so the real subjects are the server paths the layer
     *  intercepts):
     *    cpu     — the integer-mix spin (the scheduler baseline)
     *    entity  — move/rotate the cimtest TestEntity churn pool (the
     *              entity tick + the sync packets ride the layer's bridge)
     *    packet  — send an action-bar packet to every player per tick
     *              (the net path through the F2V translation)
     *    alloc   — the bounded allocation churn (the GC pressure surface)
     */
    private void arm(String profile, int msPerTick, int ticks, MinecraftServer server,
                     net.minecraft.server.level.ServerLevel level) {
        this.profile = profile;
        this.remainingTicks = ticks;
        this.totalTicks = ticks;
        this.budgetNanosPerTick = msPerTick * 1_000_000L;
        this.totalOverNanos = 0;
        this.overTicks = 0;
        if ("entity".equals(profile)) {
            setupEntityChurn(level);
        }
        LOGGER.info("[cimtest_heavy] load armed: profile={} msPerTick={} ticks={}",
                profile, msPerTick, ticks);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.displayClientMessage(Component.literal(
                    "[cimtest_heavy] load armed: profile=" + profile + " msPerTick=" + msPerTick
                            + " ticks=" + ticks), false);
        }
    }

    /** The entity churn pool: 40 TestEntities near the world spawn — the
     *  subjects move every tick while armed; their sync rides the module. */
    private void setupEntityChurn(net.minecraft.server.level.ServerLevel level) {
        churn.clear();
        churnIdx = 0;
        var type = dev.cim.testmod.CimEntities.TEST_ENTITY.get();
        var spawn = level.getSharedSpawnPos();
        for (int i = 0; i < 40; i++) {
            try {
                var e = type.create(level);
                if (e == null) {
                    continue;
                }
                e.moveTo(spawn.getX() + (i % 8) * 2.0, spawn.getY() + 1.0,
                        spawn.getZ() + (i / 8) * 2.0, 0.0f, 0.0f);
                e.setNoGravity(true);
                level.addFreshEntity(e);
                churn.add(e);
            } catch (Throwable t) {
                LOGGER.info("[cimtest_heavy] churn spawn failed at {}: {}",
                        i, t.toString());
            }
        }
        LOGGER.info("[cimtest_heavy] entity churn pool: {} subject(s)", churn.size());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (remainingTicks <= 0) {
            return;
        }
        int tick = totalTicks - remainingTicks + 1;
        long start = System.nanoTime();
        switch (profile) {
            case "entity" -> churnEntities();
            case "packet" -> packetLoad(event.getServer());
            case "alloc" -> allocLoad();
            default -> burn(budgetNanosPerTick);
        }
        long spent = System.nanoTime() - start;
        // overMs: the tick's own wall cost of the burn loop (the burn self-
        // paces to the budget; the overshoot measures the allocator/JIT
        // overhead the profiling budget cares about)
        long overNanos = Math.max(0, spent - budgetNanosPerTick);
        totalOverNanos += overNanos;
        if (overNanos > 0) {
            overTicks++;
        }
        remainingTicks--;
        if (tick % 100 == 0 || remainingTicks == 0) {
            LOGGER.info("[cimtest_heavy] load running: tick={}/{} spentMs={}.{}",
                    tick, totalTicks, spent / 1_000_000, (spent / 100_000) % 10);
        }
        if (remainingTicks == 0) {
            double avgMs = totalTicks > 0 ? (totalOverNanos / (double) totalTicks) / 1_000_000.0 : 0.0;
            LOGGER.info("[cimtest_heavy] load done: profile={} ticks={} overMsTotal={} avgOverMsPerTick={}",
                    profile, totalTicks, totalOverNanos / 1_000_000, String.format("%.2f", avgMs));
            for (var e : churn) {
                try { e.discard(); } catch (Throwable ignore) { }
            }
            churn.clear();
        }
    }

    /** The entity churn: move + rotate every pool subject — the entity
     *  tick and the ClientboundEntityPositionSync packets ride the module's
     *  bridge, so the profile measures the layer's entity-path charge. */
    private void churnEntities() {
        long deadline = System.nanoTime() + budgetNanosPerTick;
        int moved = 0;
        while (System.nanoTime() < deadline) {
            var e = churn.get(churnIdx % Math.max(1, churn.size()));
            churnIdx++;
            if (e.isAlive()) {
                e.moveTo(e.getX() + 0.05, e.getY(), e.getZ(), (churnIdx % 360), 0.0f);
                moved++;
            }
        }
        SINK = moved;
    }

    /** The packet churn: an action-bar broadcast per tick — the packets
     *  ride the net path the F2V translation hooks. */
    private void packetLoad(MinecraftServer server) {
        long deadline = System.nanoTime() + budgetNanosPerTick;
        int sent = 0;
        while (System.nanoTime() < deadline) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.displayClientMessage(Component.literal(
                        "[cimtest_heavy] probe " + (sent++)), true);
            }
        }
        SINK = sent;
    }

    /** The allocation churn: bounded byte[] allocation at the budget rate —
     *  the GC pressure surface (young-gen collection rides the tick). */
    private void allocLoad() {
        long deadline = System.nanoTime() + budgetNanosPerTick;
        Object keep = null;
        int n = 0;
        while (System.nanoTime() < deadline) {
            keep = new byte[4096];
            keep.hashCode();
            n++;
        }
        SINK = n;
    }

    /** Burn the budget: an integer mix loop (cheap, predictable) until the
     *  deadline passes, allocating one small object per 4096 iterations —
     *  the allocation rate rides the loop, bounded by the same deadline. */
    private static void burn(long budgetNanos) {
        long deadline = System.nanoTime() + budgetNanos;
        long mix = 0x9E3779B97F4A7C15L;
        Object keep = null;
        int sinceAlloc = 0;
        while (System.nanoTime() < deadline) {
            mix ^= mix << 13;
            mix ^= mix >>> 7;
            mix ^= mix << 17;
            if (++sinceAlloc >= 4096) {
                sinceAlloc = 0;
                keep = new byte[256];
                keep.hashCode();
            }
        }
        SINK = mix + (keep == null ? 0 : 1);
    }
}
