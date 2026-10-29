package dev.cim.scenemod;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Blocks that cannot carry the pixel gallery (measured:
     * docs/runtime-analysis/block-channel-26-3.md — barrier/structure_void
     * are RenderShape.INVISIBLE in CODE, air family has no model,
     * moving_piston/light are technical). Skipping them is NOT a fudge:
     * they are skipped on BOTH stacks, so parity is still exact.
     */
    private static final java.util.Set<String> GALLERY_BLACKLIST = java.util.Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:barrier", "minecraft:structure_void", "minecraft:light",
            "minecraft:moving_piston");

    /**
     * Vanilla control cells: the drift datum group. These render identically
     * on both clients when the transport is honest — their AE defines the
     * cross-version camera/lighting drift floor the gate is calibrated on
     * (MODPACK.md "calibrated identity datum").
     */
    private static final List<String> GALLERY_VANILLA_CONTROLS = List.of(
            "minecraft:stone", "minecraft:iron_block", "minecraft:gold_block",
            "minecraft:diamond_block", "minecraft:oak_log", "minecraft:oak_leaves",
            "minecraft:glass", "minecraft:bricks", "minecraft:red_wool",
            "minecraft:oak_planks", "minecraft:crafting_table", "minecraft:furnace");

    /** Gallery geometry (px math lives in scripts/cimscene/gallery-diff.py). */
    private static final int GALLERY_COLS = 12;
    private static final int GALLERY_ROWS = 6;
    private static final int GALLERY_CELL_STRIDE = 2;
    private static final int GALLERY_WALL_STRIDE = 9;
    private static final int GALLERY_WALL1_Z = 8;
    private static final int GALLERY_VANTAGE_DIST = 4;

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
                        .then(Commands.literal("gallery").executes(ctx ->
                                buildGallery(ctx.getSource(), 8, 1440, false))
                            .then(Commands.argument("maxStates", IntegerArgumentType.integer(0))
                                .executes(ctx -> buildGallery(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "maxStates"), 1440, false))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                    .executes(ctx -> buildGallery(ctx.getSource(),
                                            IntegerArgumentType.getInteger(ctx, "maxStates"),
                                            IntegerArgumentType.getInteger(ctx, "limit"), false)))))
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
        LOGGER.info("[cimscene] commands registered (/cimscene census|selftest|gallery|probe-class)");
    }

    // --- gallery ------------------------------------------------------------

    /**
     * The UNIVERSAL block-state gallery (owner directive 2026-10-30): every
     * registered block from every loaded mod (namespace != minecraft) plus a
     * fixed vanilla control group, ALL of its states, placed as real blocks
     * in a corridor of walls in front of the executor. No mod is named on
     * the path — whatever is registered at command time IS the gallery, so
     * the same command serves cimtest calibration and the full Create/BOP
     * runs on both stacks.
     *
     * <p>Determinism: blocks sorted by registry id, states in the block's
     * own definition order, absolute coordinates derived from the executor
     * position (the CI console executor sits at world spawn of a pinned
     * flat seed). The manifest {@code c-imagine/scenes/gallery.tsv} records
     * wall/col/row, absolute x/y/z, block id and the exact state string —
     * gallery-diff.py crops per-cell from the wall screenshots using the
     * SAME manifest from each stack, so coordinates never need to match
     * across stacks, only cell indices do.
     *
     * @param maxStates states per block (0 = all)
     * @param limit     hard cell cap (walls stop mid-block, manifest says so)
     */
    private static int buildGallery(CommandSourceStack src, int maxStates, int limit, boolean includeVanilla) {
        ServerLevel level = src.getLevel();
        BlockPos base = BlockPos.containing(src.getPosition());

        // --- enumerate: vanilla controls first, then every modded block sorted
        record Cell(BlockState st, String ns, String id, String stateStr) {}
        List<Cell> cells = new ArrayList<>();
        for (String controlId : GALLERY_VANILLA_CONTROLS) {
            Block b = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(controlId));
            for (BlockState st : b.getStateDefinition().getPossibleStates()) {
                cells.add(new Cell(st, "minecraft", controlId, st.toString()));
            }
        }
        List<Map.Entry<ResourceLocation, Block>> modded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
            String key = e.getKey().getNamespace() + ":" + e.getKey().getPath();
            if (GALLERY_BLACKLIST.contains(key)) continue;
            if (e.getKey().getNamespace().equals("minecraft")) continue;
            modded.add(e);
        }
        modded.sort(Comparator.comparing(e -> e.getKey().toString()));
        for (Map.Entry<ResourceLocation, Block> e : modded) {
            List<BlockState> states = new ArrayList<>(e.getValue().getStateDefinition().getPossibleStates());
            if (maxStates > 0 && states.size() > maxStates) states = states.subList(0, maxStates);
            for (BlockState st : states) {
                cells.add(new Cell(st, e.getKey().getNamespace(), e.getKey().getPath(), st.toString()));
            }
        }
        if (cells.size() > limit) cells = cells.subList(0, limit);

        int walls = (cells.size() + GALLERY_COLS * GALLERY_ROWS - 1) / (GALLERY_COLS * GALLERY_ROWS);
        StringBuilder tsv = new StringBuilder("wall\tcol\trow\tx\ty\tz\tns\tid\tstate\n");
        int placed = 0;
        int placedBlocks = 0;
        for (int wall = 0; wall < walls; wall++) {
            int wallZ = base.getZ() + GALLERY_WALL1_Z + wall * GALLERY_WALL_STRIDE;
            int minX = base.getX() - (GALLERY_COLS / 2) * GALLERY_CELL_STRIDE;
            int maxX = base.getX() + (GALLERY_COLS / 2) * GALLERY_CELL_STRIDE;
            // platform (double layer) + air sweep — uniform ground/light on
            // every cell, no terrain interference, same on both stacks
            for (int x = minX - 2; x <= maxX + 2; x++) {
                for (int z = wallZ - 2; z <= wallZ + 1; z++) {
                    level.setBlock(new BlockPos(x, base.getY(), z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    level.setBlock(new BlockPos(x, base.getY() - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    for (int y = base.getY() + 1; y <= base.getY() + GALLERY_ROWS + 3; y++) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
            for (int row = 0; row < GALLERY_ROWS; row++) {
                for (int col = 0; col < GALLERY_COLS; col++) {
                    int idx = wall * GALLERY_COLS * GALLERY_ROWS + row * GALLERY_COLS + col;
                    if (idx >= cells.size()) break;
                    Cell c = cells.get(idx);
                    BlockPos pos = new BlockPos(
                            base.getX() - (GALLERY_COLS / 2) * GALLERY_CELL_STRIDE + col * GALLERY_CELL_STRIDE,
                            base.getY() + 1 + row,
                            wallZ);
                    if (level.setBlock(pos, c.st(), 3)) placed++;
                    tsv.append(wall).append('\t').append(col).append('\t').append(row).append('\t')
                            .append(pos.getX()).append('\t').append(pos.getY()).append('\t').append(pos.getZ()).append('\t')
                            .append(c.ns()).append('\t').append(c.id()).append('\t').append(c.stateStr()).append('\n');
                    placedBlocks++;
                }
            }
            LOGGER.info("[cimscene] gallery wall {} built at z={} (vantage z={}) cells={}",
                    wall, wallZ, wallZ - GALLERY_VANTAGE_DIST, Math.min(cells.size() - wall * GALLERY_COLS * GALLERY_ROWS, GALLERY_COLS * GALLERY_ROWS));
        }
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve("gallery.tsv"), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery manifest write failed: {}", ioe.toString());
            return 0;
        }
        final int fPlaced = placed;
        final int fWalls = walls;
        final int fCells = cells.size();
        LOGGER.info("[cimscene] gallery built cells={} placed={} walls={} blocks={} base={},{},{} manifest=c-imagine/scenes/gallery.tsv",
                fCells, fPlaced, fWalls, placedBlocks, base.getX(), base.getY(), base.getZ());
        src.sendSuccess(() -> Component.literal("[cimscene] gallery built cells=" + fCells + " walls=" + fWalls), false);
        return 1;
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
