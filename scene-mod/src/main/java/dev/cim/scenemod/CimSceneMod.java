package dev.cim.scenemod;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * cimscene — the real-mod parity harness (roadmap M5.5,
 * docs/runtime-analysis/mod-parity-harness.md, MODPACK.md Tier 0).
 *
 * An ORDINARY NeoForge mod on purpose: zero c-imagine APIs, standard
 * vanilla/NeoForge server surface only, identical behavior on a real
 * NeoForge 21.1.265 server and inside the c-imagine layer. Any divergence
 * between the two stacks is by construction a c-imagine defect — this mod
 * cannot absorb the blame.
 *
 * Skeleton stage: registry census emitter (the FIRST parity gate — both
 * stacks must register identical sets before any pixel work), the
 * camera-relative selftest scene, and the control command. The census-driven
 * per-mod sweep (per block, per sound, per particle, per everything a mod
 * ships) grows from this core.
 */
@Mod(CimSceneMod.MOD_ID)
public final class CimSceneMod {

    public static final String MOD_ID = "cimscene";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Censused registries, iteration order fixed for a stable TSV. */
    private static final Map<String, Registry<?>> CENSUSED = new LinkedHashMap<>();
    static {
        CENSUSED.put("block", BuiltInRegistries.BLOCK);
        CENSUSED.put("item", BuiltInRegistries.ITEM);
        CENSUSED.put("entity", BuiltInRegistries.ENTITY_TYPE);
        CENSUSED.put("particle", BuiltInRegistries.PARTICLE_TYPE);
        CENSUSED.put("sound", BuiltInRegistries.SOUND_EVENT);
        CENSUSED.put("block_entity", BuiltInRegistries.BLOCK_ENTITY_TYPE);
        CENSUSED.put("menu", BuiltInRegistries.MENU);
    }

    public CimSceneMod(IEventBus modBus) {
        modBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        LOGGER.info("[cimscene] mod constructed (ordinary-mod harness, no c-imagine APIs)");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[cimscene] common setup ok");
        // Census at common setup: the registration stage is a barrier BEFORE
        // common setup on both stacks (real FML staging), so the registries
        // are complete here even in the layer, where ServerStartedEvent is
        // not (yet) proven to fire. serverStarted re-emits the same file —
        // last-wins, identical content.
        writeCensus("scene-manifest.tsv");
    }

    private void serverStarted(ServerStartedEvent event) {
        writeCensus("scene-manifest.tsv");
        LOGGER.info("[cimscene] server started ok (census emitter armed)");
    }

    // --- census -----------------------------------------------------------

    /**
     * Counts registry entries per namespace and emits the TSV. The per-namespace
     * split is the shape the diff job joins across stacks: real NeoForge vs the
     * c-imagine layer must produce IDENTICAL rows (registration parity gate).
     *
     * <p>Alongside the counts, the FULL entry listing
     * ({@code scene-manifest-entries.tsv}: registry/namespace/id per row) is
     * emitted — per-namespace COUNTS can match while the underlying sets differ
     * (one block missing, one extra sound). The exact set join in
     * census-diff.py is the "every block, every sound, every particle" gate
     * the parity program is built on.
     */
    private static void writeCensus(String fileName) {
        Map<String, Map<String, Integer>> perRegistry = new TreeMap<>();
        StringBuilder entries = new StringBuilder("registry\tnamespace\tid\n");
        int total = 0;
        for (Map.Entry<String, Registry<?>> e : CENSUSED.entrySet()) {
            for (ResourceLocation id : e.getValue().keySet()) {
                perRegistry.computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                        .merge(id.getNamespace(), 1, Integer::sum);
                entries.append(e.getKey()).append('\t')
                        .append(id.getNamespace()).append('\t')
                        .append(id.getPath()).append('\n');
                total++;
            }
        }
        StringBuilder tsv = new StringBuilder("registry\tnamespace\tcount\n");
        for (Map.Entry<String, Map<String, Integer>> e : perRegistry.entrySet()) {
            for (Map.Entry<String, Integer> ns : e.getValue().entrySet()) {
                tsv.append(e.getKey()).append('\t')
                        .append(ns.getKey()).append('\t')
                        .append(ns.getValue()).append('\n');
            }
        }
        String entriesName = fileName.replaceAll("\\.tsv$", "-entries.tsv");
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(fileName), tsv.toString(), StandardCharsets.UTF_8);
            Files.writeString(parent.resolve(entriesName), entries.toString(), StandardCharsets.UTF_8);
            LOGGER.info("[cimscene] census written {} + {} registries={} entries={}",
                    fileName, entriesName, perRegistry.size(), total);
            LOGGER.info("[cimscene] census totals: {}", perRegistry.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue().values().stream().mapToInt(Integer::intValue).sum())
                    .collect(Collectors.joining(",")));
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] census write failed {}: {}", fileName, ioe.toString());
        }
    }

    // --- commands -----------------------------------------------------------

    private void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cimscene")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("census").executes(ctx -> {
                            writeCensus("scene-manifest.tsv");
                            ctx.getSource().sendSuccess(
                                    () -> Component.literal("[cimscene] census re-emitted (c-imagine/scenes/scene-manifest.tsv)"),
                                    false);
                            return 1;
                        }))
                        .then(Commands.literal("selftest").executes(ctx -> {
                            CommandSourceStack src = ctx.getSource();
                            ServerLevel level = src.getLevel();
                            BlockPos pos = ahead(src);
                            BlockState state = Blocks.IRON_BLOCK.defaultBlockState();
                            boolean placed = level.setBlock(pos, state, 3);
                            LOGGER.info("[cimscene] selftest iron_block at {} placed={} executor={}",
                                    pos.toShortString(), placed, src.getTextName());
                            src.sendSuccess(
                                    () -> Component.literal("[cimscene] selftest iron_block at " + pos.toShortString()),
                                    false);
                            return placed ? 1 : 0;
                        }))
                        .then(Commands.literal("probe-class").then(Commands.argument("fqn", StringArgumentType.string())
                                .executes(ctx -> {
                                    String fqn = StringArgumentType.getString(ctx, "fqn");
                                    CommandSourceStack src = ctx.getSource();
                                    try {
                                        // Resolve through the GAME classloader context
                                        // (a vanilla class's defining loader) — this
                                        // replicates exactly the delegation a game class
                                        // like Screen experiences when the JVM resolves
                                        // its superinterface chain at define time.
                                        ClassLoader gameLoader = Block.class.getClassLoader();
                                        Class<?> c = Class.forName(fqn, false, gameLoader);
                                        Object cs = null;
                                        try {
                                            cs = c.getProtectionDomain().getCodeSource().getLocation();
                                        } catch (Throwable ignored) {
                                            // some loaders refuse PD access; the name alone
                                            // already answers the question
                                        }
                                        LOGGER.info("[cimscene] probe-class {} = OK loader={} code={}",
                                                fqn, c.getClassLoader(), cs);
                                    } catch (Throwable t) {
                                        LOGGER.info("[cimscene] probe-class {} = FAIL {}", fqn, t.toString());
                                    }
                                    src.sendSuccess(() -> Component.literal("[cimscene] probe-class " + fqn + " logged"), false);
                                    return 1;
                                })))
        );
        LOGGER.info("[cimscene] commands registered (/cimscene census|selftest|probe-class)");
    }

    /**
     * The camera-lock "floor cell 3 ahead" (camera-lock-26-3.md): one block
     * below the executor's feet, 3 blocks along the executor's yaw. Vanilla
     * yaw convention: 0 = +Z, degrees clockwise.
     */
    private static BlockPos ahead(CommandSourceStack src) {
        Vec3 p = src.getPosition();
        Vec2 rot = src.getRotation();
        double rad = Math.toRadians(rot.x);
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        return BlockPos.containing(p.x + fx * 3.0, p.y - 1.0, p.z + fz * 3.0);
    }
}
