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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
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
                        .then(Commands.literal("gallery")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(ctx -> gallery(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "namespace"), 0)))
                                .then(Commands.literal("page")
                                        .then(Commands.argument("namespace", StringArgumentType.word())
                                                .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> gallery(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "namespace"),
                                                                IntegerArgumentType.getInteger(ctx, "page")))))))
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

    // --- gallery (the universal mod-content wall, roadmap M5.5) ------------

    /** Gallery wall geometry (camera-lock-26-3.md rig: camera feet y=-60,
     *  eyes -58.38, yaw 0 / pitch 0; superflat floor top face y=-60). The
     *  wall stands ON the floor 9 blocks ahead, 11 columns x 7 rows = 77
     *  cells; at the 1280x720 default FOV 70 every cell is fully inside the
     *  frame (checked against the measured vertical half-angle 35 deg:
     *  top face atan(5.38/8.5)=32.3, bottom atan(1.62/8.5)=10.8). */
    static final int GALLERY_COLS = 11;
    static final int GALLERY_ROWS = 7;
    static final int GALLERY_CELLS = GALLERY_COLS * GALLERY_ROWS; // 77
    static final int GALLERY_X0 = -5;
    static final int GALLERY_Y0 = -60;   // bottom row sits on the floor
    static final int GALLERY_Z = 9;

    /**
     * The universal content gallery: EVERY block (and then EVERY blockstate
     * of every block) registered by the namespace, placed on a deterministic
     * absolute wall grid in front of the locked camera — zero per-mod code.
     * Page 0 = the first {@value #GALLERY_CELLS} states in
     * (sorted-id, getPossibleStates) order; page N continues; an empty page
     * marker closes the sweep. Skips are honest and identical on both parity
     * stacks: fluid states would flow (time-varying pixels), RenderShape.
     * INVISIBLE states render nothing on ANY client (barrier, structure_void,
     * air) — both are recorded in the page manifest, not silently dropped.
     *
     * <p>Manifest per page: {@code c-imagine/scenes/gallery-<ns>-p<p>.tsv}
     * rows {@code cell|x|y|z|state} ({@code cell} = col,row on the wall) plus
     * {@code skip|...|state} rows for skipped states — the diff job joins
     * per-cell crops from the two legs' screenshots through these
     * coordinates, so one bad block names itself.
     */
    private static int gallery(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();

        // Deterministic entry list: sorted ids of the namespace, each block's
        // states in getPossibleStates() order.
        List<BlockState> entries = new ArrayList<>();
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.BLOCK.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : ids) {
            if (!id.getNamespace().equals(namespace)) continue;
            entries.addAll(BuiltInRegistries.BLOCK.get(id).getStateDefinition().getPossibleStates());
        }

        LOGGER.info("[cimscene] gallery begin ns={} page={} states={} cellsPerPage={}",
                namespace, page, entries.size(), GALLERY_CELLS);

        int from = page * GALLERY_CELLS;
        if (from >= entries.size()) {
            LOGGER.info("[cimscene] gallery done ns={} page={} cells=0 skipped_fluid=0 "
                    + "skipped_invisible=0 exhausted=1", namespace, page);
            return 0;
        }
        int to = Math.min(from + GALLERY_CELLS, entries.size());
        List<BlockState> slice = entries.subList(from, to);

        // sweep the wall region (starts AT GALLERY_Y0: the superflat floor
        // top block lives at y=-61 (measured: feet rest at y=-60) — filling
        // one lower would punch a hole in the floor; the wall's own bottom
        // row y=-60 is still cleared, the floor under it stays intact)
        for (int x = GALLERY_X0 - 1; x <= GALLERY_X0 + GALLERY_COLS; x++) {
            for (int y = GALLERY_Y0; y <= GALLERY_Y0 + GALLERY_ROWS; y++) {
                for (int z = GALLERY_Z - 1; z <= GALLERY_Z + 1; z++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }

        // place + manifest. THE MANIFEST IS THE SETTLED CONTRACT: blocks
        // whose models depend on their NEIGHBOURS (panes, bars — PaneBlock/
        // CrossCollisionBlock families) recompute their connection
        // properties SYNCHRONOUSLY inside the placement loop (setBlock flag
        // 3 = UPDATE_NEIGHBORS: each later placement re-updates the
        // already-placed neighbours), so the state the world holds at the
        // end of the loop is NOT always the state that was placed. The
        // pack channel renders the manifest state faithfully on the ours
        // leg — comparing placed-vs-settled produced a wall of phantom
        // reds (worklog 12-ac: the truth leg showed the settled east-arm
        // pane while ours showed the placed full cross). The manifest
        // therefore carries the RE-READ states; mutated cells are logged.
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tstate\tdyn\n");
        int placed = 0, skipFluid = 0, skipInvisible = 0, mutated = 0;
        List<BlockPos> placedPos = new ArrayList<>();
        List<Integer> placedIndex = new ArrayList<>();
        String[] rowStr = new String[slice.size()];
        for (int i = 0; i < slice.size(); i++) {
            BlockState st = slice.get(i);
            ResourceLocation bid = BuiltInRegistries.BLOCK.getKey(st.getBlock());
            String stateStr = stateString(st, bid);
            int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
            if (!st.getFluidState().isEmpty()) {
                skipFluid++;
                rowStr[i] = "skip\t-1\t-1\t-1\t" + stateStr;
                LOGGER.info("[cimscene] gallery skip ns={} page={} index={} reason=fluid state={}",
                        namespace, page, from + i, stateStr);
                continue;
            }
            if (st.getRenderShape() == RenderShape.INVISIBLE) {
                skipInvisible++;
                rowStr[i] = "skip\t-1\t-1\t-1\t" + stateStr;
                LOGGER.info("[cimscene] gallery skip ns={} page={} index={} reason=invisible state={}",
                        namespace, page, from + i, stateStr);
                continue;
            }
            BlockPos pos = new BlockPos(GALLERY_X0 + col, GALLERY_Y0 + row, GALLERY_Z);
            level.setBlock(pos, st, 3);
            placed++;
            placedPos.add(pos);
            placedIndex.add(i);
        }

        // SETTLE PASS: re-read every placed cell and write the settled state
        for (int j = 0; j < placedPos.size(); j++) {
            BlockPos pos = placedPos.get(j);
            int i = placedIndex.get(j);
            BlockState now = level.getBlockState(pos);
            BlockState was = slice.get(i);
            ResourceLocation bid = BuiltInRegistries.BLOCK.getKey(now.getBlock());
            String settled = stateString(now, bid);
            String placedStr = stateString(was, BuiltInRegistries.BLOCK.getKey(was.getBlock()));
            if (!settled.equals(placedStr)) {
                mutated++;
                LOGGER.info("[cimscene] gallery settle ns={} page={} cell={} {},{} from={} to={}",
                        namespace, page, pos.getX(), pos.getY(), pos.getZ(), placedStr, settled);
            }
            // dyn=1: the block carries a BlockEntity — part of its picture is
            // drawn by the mod's BlockEntityRenderer (chain-drive shafts,
            // kinetic animation...), which the vanilla-client pack channel
            // STRUCTURALLY cannot reproduce. The gate diffs the static model
            // surface for these cells and reports the AE, but they carry no
            // red verdict — their gate is the real-client leg (roadmap M5.6).
            // Universal signal (the EntityBlock interface), zero hardcode.
            rowStr[i] = "cell\t" + pos.getX() + '\t' + pos.getY() + '\t' + pos.getZ() + '\t'
                    + settled + '\t'
                    + (now.getBlock() instanceof EntityBlock ? 1 : 0);
        }
        for (int i = 0; i < slice.size(); i++) {
            if (rowStr[i] != null) {
                tsv.append(rowStr[i]).append('\n');
            }
        }

        String manifest = "gallery-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery manifest write failed {}: {}", manifest, ioe.toString());
        }

        LOGGER.info("[cimscene] gallery done ns={} page={} cells={} mutated={} skipped_fluid={} "
                        + "skipped_invisible={} first={} last={}",
                namespace, page, placed, mutated, skipFluid, skipInvisible,
                stateString(slice.get(0), BuiltInRegistries.BLOCK.getKey(slice.get(0).getBlock())),
                stateString(slice.get(slice.size() - 1),
                        BuiltInRegistries.BLOCK.getKey(slice.get(slice.size() - 1).getBlock())));
        return placed;
    }

    /** Canonical state string {@code ns:path[k=v,...]} (properties in
     *  declaration order — deterministic on both stacks, same code). */
    private static String stateString(BlockState st, ResourceLocation id) {
        StringBuilder sb = new StringBuilder(id.getNamespace()).append(':').append(id.getPath());
        if (!st.getProperties().isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Property<?> p : st.getProperties()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(p.getName()).append('=').append(valueOf(st, p));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static <T extends Comparable<T>> String valueOf(BlockState st, Property<T> p) {
        return st.getValue(p).toString();
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
