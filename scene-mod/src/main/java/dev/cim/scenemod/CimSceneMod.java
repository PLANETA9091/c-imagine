package dev.cim.scenemod;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
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
                                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                                .then(Commands.literal("entity")
                                        .then(Commands.literal("mobs")
                                                .then(Commands.argument("namespace", StringArgumentType.word())
                                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                                .executes(ctx -> galleryEntity(ctx.getSource(),
                                                                        "mobs",
                                                                        StringArgumentType.getString(ctx, "namespace"),
                                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                                        .then(Commands.literal("large")
                                                .then(Commands.argument("namespace", StringArgumentType.word())
                                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                                .executes(ctx -> galleryEntity(ctx.getSource(),
                                                                        "large",
                                                                        StringArgumentType.getString(ctx, "namespace"),
                                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                                        .then(Commands.literal("dyn")
                                                .then(Commands.argument("namespace", StringArgumentType.word())
                                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                                .executes(ctx -> galleryEntity(ctx.getSource(),
                                                                        "dyn",
                                                                        StringArgumentType.getString(ctx, "namespace"),
                                                                        IntegerArgumentType.getInteger(ctx, "page")))))))
                        .then(Commands.literal("item")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> galleryItem(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "namespace"),
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("particle")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> galleryParticle(ctx.getSource(),
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
        LOGGER.info("[cimscene] commands registered (/cimscene census|selftest|gallery[page|entity|item|particle]|probe-class)");
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

    /** ENTITY grid: SPARSE 6x3 with a 2-block pitch BOTH axes (measured
     *  16d62f0: at the 1-block block-wall pitch the subjects WIDER than one
     *  block (ravager 1.95, horse 1.4, llama 1.2...) overlap their
     *  neighbours' bounding boxes and the collision push SCRAMBLES the wall
     *  differently on the two legs AFTER the settle pass — every cell red
     *  with the alignment search saturated). Pitch 2 means zero overlap by
     *  construction for the gateable class (bb <= 2.0 x 2.0), so no subject
     *  can ever push another. 6 cols x 3 rows = 18 cells per entity page;
     *  rows y = -60/-58/-56 keep every 2-tall subject fully in frame. */
    static final int ENTITY_COLS = 6;
    static final int ENTITY_ROWS = 3;
    static final int ENTITY_CELLS = ENTITY_COLS * ENTITY_ROWS; // 18
    static final int ENTITY_PITCH = 2;

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
        writeGalleryIndex(namespace);

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
        clearWall(level);

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

    // --- coverage ledger (owner directive 2026-10-31: the verdict must SEE
    // --- everything — content that never reaches a page is COVERAGE red,
    // --- not a silent green) ----------------------------------------------

    /**
     * The coverage ledger: how many entries of each gallery class the
     * namespace registers and how many cells one page holds. Written by
     * EVERY gallery command (idempotent, registry-derived — identical on
     * both stacks because it is the same code over the same registries).
     *
     * <p>The CI coverage gate joins this file against the manifests +
     * screenshots actually present: a class whose page count falls short of
     * {@code ceil(total / cellsPerPage)} fails the run COVERAGE-INCOMPLETE —
     * a partial walk can never green the verdict again (the measured gap
     * that motivated this: create registers {@value #GALLERY_CELLS} * 408
     * ≈ 31,389 blockstates while the gate walked 3 pages = 231 = 0.74%).
     */
    private static void writeGalleryIndex(String namespace) {
        long states = 0;
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals(namespace)) {
                states += BuiltInRegistries.BLOCK.get(id)
                        .getStateDefinition().getPossibleStates().size();
            }
        }
        long entities = 0, items = 0, particles = 0;
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id.getNamespace().equals(namespace)) entities++;
        }
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(namespace)) items++;
        }
        for (ResourceLocation id : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
            if (id.getNamespace().equals(namespace)) particles++;
        }
        StringBuilder tsv = new StringBuilder("class\ttotal\tcellsPerPage\n")
                .append("block\t").append(states).append('\t').append(GALLERY_CELLS).append('\n')
                .append("entity\t").append(entities).append('\t').append(ENTITY_CELLS).append('\n')
                .append("item\t").append(items).append('\t').append(GALLERY_CELLS).append('\n')
                .append("particle\t").append(particles).append('\t').append(GALLERY_CELLS).append('\n');
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve("gallery-" + namespace + "-index.tsv"),
                    tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-index write failed ns={}: {}", namespace, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-index ns={} block-states={} entities={} items={} particles={}",
                namespace, states, entities, items, particles);
    }

    /**
     * Silent command execution surface: the proven vanilla summon/particle
     * grammar (the SAME strings the ours leg runs over RCON in gallery.yml)
     * executed through the dispatcher with a NULL source — no RCON response
     * pollution, no 31k-line boot.log echo (the walk greps that log for the
     * done markers). Returns the command result (0 = failed → the caller
     * records an honest skip row; a failure is identical on both stacks
     * because both run the same command strings).
     */
    private static int runQuiet(CommandSourceStack src, String cmd) {
        try {
            CommandSourceStack quiet = src.withSource(CommandSource.NULL)
                    .withSuppressedOutput().withPermission(2);
            ParseResults<CommandSourceStack> parsed = src.getServer().getCommands()
                    .getDispatcher().parse(cmd, quiet);
            return src.getServer().getCommands().getDispatcher().execute(parsed);
        } catch (Throwable t) {
            LOGGER.info("[cimscene] gallery cmd failed: {} -> {}", cmd, t.toString());
            return 0;
        }
    }

    /** The shared wall sweep (identical coordinates on every gallery leg). */
    private static void clearWall(ServerLevel level) {
        for (int x = GALLERY_X0 - 1; x <= GALLERY_X0 + GALLERY_COLS; x++) {
            for (int y = GALLERY_Y0; y <= GALLERY_Y0 + GALLERY_ROWS; y++) {
                for (int z = GALLERY_Z - 1; z <= GALLERY_Z + 1; z++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    // --- item gallery (every registered ITEM of the namespace, shown to the
    // --- test player under the same wall grid + pixel gate) ----------------

    /**
     * The universal ITEM gallery: EVERY {@link net.minecraft.world.item.Item}
     * registered by the namespace (the {@code air} sentinel excluded), sorted
     * by id, rendered one per wall cell as a REAL item_display carrying the
     * REAL modded item — the truth client renders the true item model (incl.
     * the mod's own item renderers). Billboard FIXED, light PINNED 15/15, at
     * the cell center: the same summon grammar the ours leg proved for its
     * CMD carriers (gallery.yml isolated-pane step), so the two stacks spawn
     * byte-identical rigs.
     *
     * <p>Manifest {@code gallery-item-<ns>-p<p>.tsv}, rows
     * {@code cell|x|y|z|item|dyn|note} (dyn=0 — item models are static;
     * the animated vanilla clocks/compasses live in the minecraft namespace,
     * whose item pages are diagnostics, never the gate). A summon that
     * fails becomes a recorded skip row (reason=summon-failed) — identical
     * on both stacks, never silently dropped. Zero per-mod code: the sweep
     * is the registry walk.
     */
    private static int galleryItem(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        writeGalleryIndex(namespace);

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace) && !id.getPath().equals("air")) {
                entries.add(id);
            }
        }

        LOGGER.info("[cimscene] gallery-item begin ns={} page={} items={} cellsPerPage={}",
                namespace, page, entries.size(), GALLERY_CELLS);
        int from = page * GALLERY_CELLS;
        if (from >= entries.size()) {
            LOGGER.info("[cimscene] gallery-item done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        int to = Math.min(from + GALLERY_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        clearWall(level);
        // page hygiene: stale gallery displays from the previous page (the
        // walk also kills all entities before each page; this is self-contained)
        runQuiet(src, "kill @e[type=minecraft:item_display,tag=" + GALLERY_TAG + "]");

        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\titem\tdyn\tnote\n");
        int placed = 0, failed = 0;
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
            int x = GALLERY_X0 + col, y = GALLERY_Y0 + row, z = GALLERY_Z;
            String nbt = "{Tags:[\"" + GALLERY_TAG + "\"],billboard:\"fixed\","
                    + "brightness:{sky:15b,block:15b},"
                    + "item:{id:\"" + id + "\",count:1}}";
            int rc = runQuiet(src, "summon minecraft:item_display "
                    + (x + 0.5) + " " + (y + 0.5) + " " + (z + 0.5) + " " + nbt);
            if (rc > 0) {
                placed++;
                tsv.append("cell\t").append(x).append('\t').append(y).append('\t').append(z)
                        .append('\t').append(id).append("\t0\tok\n");
            } else {
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\t0\tsummon-failed\n");
                LOGGER.info("[cimscene] gallery-item skip ns={} page={} index={} reason=summon-failed item={}",
                        namespace, page, from + i, id);
            }
        }
        String manifest = "gallery-item-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-item manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-item done ns={} page={} cells={} failed={} first={} last={}",
                namespace, page, placed, failed, slice.get(0), slice.get(slice.size() - 1));
        return placed;
    }

    // --- particle gallery (every registered PARTICLE of the namespace) ------

    /**
     * The universal PARTICLE gallery: EVERY {@link net.minecraft.core.particles.ParticleType}
     * registered by the namespace, sorted by id, one particle per wall cell
     * spawned through the vanilla {@code /particle} surface (count=1,
     * delta 0/0/0, speed 0 → a STATIC particle exactly at the cell;
     * {@code force} so no view-distance rule eats it).
     *
     * <p>Honesty rules, identical on both stacks (same command strings):
     * a particle type whose options need DATA (dust colors, item particles,
     * entity_effect...) fails the argument parse → a recorded skip row
     * (reason=no-simple-options), never silently dropped. Every spawned
     * particle is {@code dyn=1} — particles are time-varying by nature
     * (fade, drift), so the join reports them without a red verdict until
     * the fixed-shot-window protocol is measured stable (the entity dyn
     * pattern); the walk still SHOWS every type to the test player.
     *
     * <p>The manifest is written synchronously (a particle is a spawn spec,
     * not a settled world state) and the done marker carries the exhausted
     * flag for the coverage gate. The SHOT WINDOW (fixed sleep after this
     * command on both legs) is pinned by the walk, not by the scene.
     */
    private static int galleryParticle(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        writeGalleryIndex(namespace);

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.PARTICLE_TYPE.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) entries.add(id);
        }

        LOGGER.info("[cimscene] gallery-particle begin ns={} page={} particles={} cellsPerPage={}",
                namespace, page, entries.size(), GALLERY_CELLS);
        int from = page * GALLERY_CELLS;
        if (from >= entries.size()) {
            LOGGER.info("[cimscene] gallery-particle done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        int to = Math.min(from + GALLERY_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        clearWall(level);

        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tparticle\tdyn\tnote\n");
        int spawned = 0, failed = 0;
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
            int x = GALLERY_X0 + col, y = GALLERY_Y0 + row, z = GALLERY_Z;
            int rc = runQuiet(src, "particle " + id + " " + (x + 0.5) + " "
                    + (y + 0.5) + " " + (z + 0.5) + " 0 0 0 0 1 force");
            if (rc > 0) {
                spawned++;
                tsv.append("cell\t").append(x).append('\t').append(y).append('\t').append(z)
                        .append('\t').append(id).append("\t1\tok\n");
            } else {
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\t1\tno-simple-options\n");
                LOGGER.info("[cimscene] gallery-particle skip ns={} page={} index={} "
                                + "reason=no-simple-options particle={}",
                        namespace, page, from + i, id);
            }
        }
        String manifest = "gallery-particle-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-particle manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-particle done ns={} page={} cells={} failed={} first={} last={}",
                namespace, page, spawned, failed, slice.get(0), slice.get(slice.size() - 1));
        return spawned;
    }

    // --- entity gallery (owner directive 2026-10-31: mobs 100% identical,
    // --- and ALL content under the pixel gate) -----------------------------

    /** Scoreboard tag shared by BOTH parity legs for gallery-entity cleanup. */
    static final String GALLERY_TAG = "cimgallery";

    /**
     * Settle delay (server ticks) before the entity-page manifest is
     * written. Entities whose existence is bounded (lightning_bolt lives
     * ~2 ticks, hanging entities die without a wall, evoker fangs...) are
     * DEAD by the settle pass and become recorded skip rows — identical on
     * both stacks, never compared as content. Frozen mobs survive it.
     */
    static final int ENTITY_SETTLE_TICKS = 40;

    /**
     * The universal ENTITY gallery: EVERY {@link EntityType} registered by
     * the namespace, sorted by id, spawned one per wall cell (feet at the
     * cell position, yaw 180 = facing the locked camera, pitch 0) and
     * FROZEN — Mobs carry NoAI + NoGravity + Invulnerable + persistence
     * (NoAI mobs never move or look around; the picture is a still),
     * non-Mobs carry NoGravity + Invulnerable and are marked {@code dyn=1}
     * (their pixels are time-varying: fuse flashes, client spin phase, orb
     * drift — diffed and reported, never a red verdict). Zero per-mod code:
     * the sweep is the registry walk.
     *
     * <p>Universal honesty rules, identical on both stacks:
     * <ul>
     *   <li>{@code create()} returned null / threw → {@code skip} row
     *       (reason=no-instance) — the type cannot exist as an entity on
     *       ANY stack;</li>
     *   <li>SETTLE PASS (after {@link #ENTITY_SETTLE_TICKS} server ticks):
     *       every spawn is re-read — dead or drifted &gt; 1.0 from its cell →
     *       {@code skip} row (reason=dissolved/moved, logged). The manifest
     *       carries what the world HOLDS, not what was placed (the 12-ac
     *       law, same as the block wall's settle pass);</li>
     *   <li>the manifest + the done marker are written by the settle pass,
     *       so the page is complete before any screenshot can be taken.</li>
     * </ul>
     *
     * <p>Manifest per page:
     * {@code c-imagine/scenes/gallery-entity-<ns>-p<p>.tsv} rows
     * {@code cell|x|y|z|entity|dyn|note} ({@code note}: {@code ok} for
     * compared cells) plus {@code skip|...|entity|0|<reason>} rows. Same
     * wall geometry as the block gallery — the diff job's projection is
     * shared unchanged.
     */
    private static int galleryEntity(CommandSourceStack src, String series,
                                     String namespace, int page) {
        ServerLevel level = src.getLevel();
        writeGalleryIndex(namespace);

        // Deterministic entry list: sorted entity ids of the namespace.
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.ENTITY_TYPE.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) entries.add(id);
        }

        LOGGER.info("[cimscene] gallery-entity begin ns={} page={} series={} types={} cellsPerPage={}",
                namespace, page, series, entries.size(), ENTITY_CELLS);

        int from = page * ENTITY_CELLS;
        if (from >= entries.size()) {
            LOGGER.info("[cimscene] gallery-entity{} done ns={} page={} cells=0 dyn=0 "
                    + "skipped_no_instance=0 dissolved=0 exhausted=1",
                    seriesTag(series), namespace, page);
            return 0;
        }
        int to = Math.min(from + ENTITY_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        // Clear the wall region (blocks) + every previously spawned gallery
        // entity (the shared tag; both parity legs clean the same way).
        for (int x = GALLERY_X0 - 1; x <= GALLERY_X0 + GALLERY_COLS; x++) {
            for (int y = GALLERY_Y0; y <= GALLERY_Y0 + GALLERY_ROWS; y++) {
                for (int z = GALLERY_Z - 1; z <= GALLERY_Z + 1; z++) {
                    level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        AABB sweep = new AABB(GALLERY_X0 - 1, GALLERY_Y0, GALLERY_Z - 1,
                GALLERY_X0 + GALLERY_COLS + 1, GALLERY_Y0 + GALLERY_ROWS + 3, GALLERY_Z + 2);
        List<Entity> stale = level.getEntitiesOfClass(Entity.class, sweep,
                e -> e.getTags().contains(GALLERY_TAG));
        for (Entity e : stale) {
            e.discard();
        }
        if (!stale.isEmpty()) {
            LOGGER.info("[cimscene] gallery-entity cleaned stale={} ns={} page={}",
                    stale.size(), namespace, page);
        }

        // Spawn loop: freeze every entity, keep the refs for the settle pass.
        // SERIES SPLIT (measured run 283ad61: every gateable cell went red
        // from ASYMMETRIC spill — boats slide, the dragon flies, orbs drift;
        // non-Mobs have no NoAI, so their pictures are time-varying AND they
        // pollute the gateable cells' crops): the mobs series spawns ONLY
        // Mob instances (frozen, deterministic spill among frozen subjects
        // is symmetric and diffable); the dyn series owns everything else
        // and its joins are diagnostics, never the gate.
        final List<Entity> spawned = new ArrayList<>();
        final List<Integer> spawnedIdx = new ArrayList<>();
        int noInstance = 0, wrongSeries = 0;
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            Entity e = null;
            try {
                e = type.create(level);
            } catch (Throwable t) {
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                        + "reason=no-instance entity={} err={}",
                        namespace, page, from + i, id, t.toString());
            }
            if (e == null) {
                noInstance++;
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                        + "reason=no-instance entity={}", namespace, page, from + i, id);
                continue;
            }
            boolean isMob = e instanceof Mob;
            String kind = !isMob ? "dyn"
                    : (e.getBbWidth() <= 2.0f && e.getBbHeight() <= 2.0f ? "mobs" : "large");
            if (!kind.equals(series)) {
                wrongSeries++;
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                        + "reason=wrong-series kind={} entity={}", namespace, page,
                        from + i, kind, id);
                continue;
            }
            e.setNoGravity(true);
            e.setInvulnerable(true);
            e.moveTo(GALLERY_X0 + col * ENTITY_PITCH + 0.5,
                    GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z + 0.5,
                    180.0f, 0.0f);
            e.addTag(GALLERY_TAG);
            if (isMob) {
                // NoAI: no movement, no look-at, no AI despawn logic — a
                // still picture. Persistence: nothing despawns the subject
                // between the settle pass and the shot.
                m_setNoAi(e);
            }
            level.addFreshEntity(e);
            spawned.add(e);
            spawnedIdx.add(i);
        }

        // SETTLE PASS (delayed, server thread): re-read every spawn, write
        // the manifest + the done marker. The CI waits for the done marker,
        // so the page is complete before any screenshot fires.
        final ServerLevel fLevel = level;
        final String fNs = namespace;
        final int fPage = page;
        final List<ResourceLocation> fSlice = slice;
        final int fNoInstance = noInstance;
        final int fWrongSeries = wrongSeries;
        final int fFrom = from;
        final String fSeries = series;
        level.getServer().tell(new TickTask(level.getServer().getTickCount()
                + ENTITY_SETTLE_TICKS, () -> settleGalleryEntity(
                        fLevel, fNs, fPage, fSlice, spawned, spawnedIdx,
                        fNoInstance, fWrongSeries, fFrom, fSeries)));
        return spawned.size();
    }

    /** Mob freeze without shadowing the pattern variable. */
    private static void m_setNoAi(Entity e) {
        Mob m = (Mob) e;
        m.setNoAi(true);
        m.setPersistenceRequired();
    }

    /** Manifest/marker tag per series: mobs ride the bare name. */
    private static String seriesTag(String series) {
        return "mobs".equals(series) ? "" : series;
    }

    /** Settle pass body: re-read spawns, emit the settled manifest + marker. */
    private static void settleGalleryEntity(ServerLevel level, String namespace, int page,
                                            List<ResourceLocation> slice,
                                            List<Entity> spawned, List<Integer> spawnedIdx,
                                            int noInstance, int wrongSeries, int from,
                                            String series) {
        String tag = seriesTag(series);
        String[] rowStr = new String[slice.size()];
        int cells = 0, dyn = 0, dissolved = 0;
        for (int j = 0; j < spawned.size(); j++) {
            Entity e = spawned.get(j);
            int i = spawnedIdx.get(j);
            ResourceLocation id = slice.get(i);
            int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
            double cx = GALLERY_X0 + col * ENTITY_PITCH + 0.5;
            double cy = GALLERY_Y0 + row * ENTITY_PITCH;
            double cz = GALLERY_Z + 0.5;
            boolean alive = e.isAlive() && !e.isRemoved();
            double drift = alive ? e.position().distanceTo(new Vec3(cx, cy, cz)) : -1.0;
            if (!alive || drift > 1.0) {
                dissolved++;
                rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t0\tdissolved";
                LOGGER.info("[cimscene] gallery-entity{} settle ns={} page={} index={} "
                                + "reason={} entity={} drift={}", tag,
                        namespace, page, from + i, alive ? "moved" : "dissolved",
                        id, drift);
                continue;
            }
            boolean isMob = e instanceof Mob;
            if (!isMob) dyn++;
            cells++;
            rowStr[i] = "cell\t" + (GALLERY_X0 + col * ENTITY_PITCH) + '\t'
                    + (GALLERY_Y0 + row * ENTITY_PITCH)
                    + '\t' + GALLERY_Z + '\t' + id + '\t' + (isMob ? 0 : 1) + "\tok";
        }
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tentity\tdyn\tnote\n");
        for (int i = 0; i < slice.size(); i++) {
            if (rowStr[i] != null) {
                tsv.append(rowStr[i]).append('\n');
            }
        }
        String manifest = "gallery-entity" + ("mobs".equals(series) ? "" : series)
                + "-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-entity manifest write failed {}: {}",
                    manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-entity{} done ns={} page={} cells={} dyn={} "
                        + "skipped_no_instance={} skipped_wrong_series={} dissolved={} first={} last={}",
                tag, namespace, page, cells, dyn, noInstance, wrongSeries,
                dissolved, slice.get(0), slice.get(slice.size() - 1));
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
