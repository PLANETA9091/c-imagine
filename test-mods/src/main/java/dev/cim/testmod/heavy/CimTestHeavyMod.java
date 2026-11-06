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

    public CimTestHeavyMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[cimtest_heavy] mod constructed (load generator armed, no load by default)");
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cimheavy")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("load")
                        .then(Commands.argument("msPerTick", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 50))
                                .then(Commands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 12000))
                                        .executes(ctx -> {
                                            int ms = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "msPerTick");
                                            int ticks = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "ticks");
                                            arm(ms, ticks,
                                                    ctx.getSource().getServer());
                                            return Command.SINGLE_SUCCESS;
                                        })))));
    }

    /** Arm the load: msPerTick of burn per server tick, for ticks ticks. */
    private void arm(int msPerTick, int ticks, MinecraftServer server) {
        this.remainingTicks = ticks;
        this.totalTicks = ticks;
        this.budgetNanosPerTick = msPerTick * 1_000_000L;
        this.totalOverNanos = 0;
        this.overTicks = 0;
        LOGGER.info("[cimtest_heavy] load armed: msPerTick={} ticks={}", msPerTick, ticks);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.displayClientMessage(Component.literal(
                    "[cimtest_heavy] load armed: msPerTick=" + msPerTick + " ticks=" + ticks), false);
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (remainingTicks <= 0) {
            return;
        }
        int tick = totalTicks - remainingTicks + 1;
        long start = System.nanoTime();
        burn(budgetNanosPerTick);
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
            LOGGER.info("[cimtest_heavy] load done: ticks={} overMsTotal={} avgOverMsPerTick={}",
                    totalTicks, totalOverNanos / 1_000_000, String.format("%.2f", avgMs));
        }
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
