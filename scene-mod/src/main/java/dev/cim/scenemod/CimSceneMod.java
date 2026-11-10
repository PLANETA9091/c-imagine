package dev.cim.scenemod;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        NeoForge.EVENT_BUS.addListener(this::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        }
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()
                && System.getProperty("cim.quaddump") != null) {
            QuadDump.register();
        }
        LOGGER.info("[cimscene] mod constructed (ordinary-mod harness, no c-imagine APIs)");
    }

    /**
     * Gallery-region drop guard (2026-11-03, #74 forensics): blocks that pop
     * after placement (funnels without a belt, unsupported states — the
     * settled-air cells) drop ItemEntities that land on the floor and stay
     * in frame for every later page shot. The truth leg accumulated them
     * (its walk never kills entities between pages) while the ours leg
     * spawns display rigs and kills @e per page — the asymmetry polluted
     * the per-cell crops with content no manifest row describes. Cancel the
     * spawn itself (no race with scheduled ticks or the shot window): an
     * ItemEntity joining the level inside the gallery box never exists.
     * Quarantine kills on the entity pages drop loot the same way — eaten
     * here too, deterministically on both stacks.
     */
    private void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        Vec3 pos = item.position();
        if (GALLERY_DROP_BOX.contains(pos.x, pos.y, pos.z)) {
            event.setCanceled(true);
        }
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
        // THE PARTICLE-FILLER SELFARM (the individual test contract): a
        // marker file "c-imagine/scenes/particle-selfarm" carrying
        // "<namespace> <page>" runs the gallery-particle spawn once from the
        // server's own console source and HALTS the server — the test boots,
        // the manifest + the [cimscene] log lines land, the process exits.
        // The CI gallery boots never carry the marker (the same contract the
        // cimtest-heavy selfarm rides).
        try {
            java.nio.file.Path marker = Path.of("c-imagine", "scenes", "particle-selfarm");
            if (Files.isRegularFile(marker)) {
                String[] parts = Files.readString(marker).trim().split("\\s+");
                MinecraftServer server = event.getServer();
                int page = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                LOGGER.info("[cimscene] particle selfarm: ns={} page={} (halting after)",
                        parts[0], page);
                galleryParticle(server.createCommandSourceStack(), parts[0], page);
                server.execute(() -> server.halt(false));
            }
        } catch (Throwable t) {
            LOGGER.info("[cimscene] particle selfarm failed (stays disarmed): {}", t.toString());
        }
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
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("fluid")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> galleryFluid(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "namespace"),
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("sound")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> gallerySound(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "namespace"),
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("gui")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> galleryGui(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "namespace"),
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("biome")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> galleryBiome(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "namespace"),
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("inv")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                .executes(ctx -> galleryInv(ctx.getSource(),
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
        LOGGER.info("[cimscene] commands registered (/cimscene census|selftest|gallery[page|entity|item|particle|fluid|sound|gui|biome|inv]|probe-class)");
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

    /** FLUID grid: the SAME sparse pitch-2 geometry as the entities — a
     *  liquid needs a SEALED glass case (a fluid at the 1-block block-wall
     *  pitch would flow into its neighbours' cells and rearrange the wall;
     *  measured lesson from the corridor design), and two cases need one
     *  block of glass between them, i.e. pitch 2. 6 cols x 3 rows = 18
     *  cells per fluid page; rows y = -60/-58/-56. */
    static final int FLUID_COLS = 6;
    static final int FLUID_ROWS = 3;
    static final int FLUID_CELLS = FLUID_COLS * FLUID_ROWS; // 18
    static final int FLUID_PITCH = 2;

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
        setGalleryFrozen(level.getServer(), false, "block page entry");
        writeGalleryIndex(level, namespace);
        String blockStem = "gallery-" + namespace + "-p" + page;
        int swept = sweepGalleryDrops(level);
        if (swept > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep ns={} page={} removed={}", namespace, page, swept);
        }

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
            writeDoneMarkerFile(blockStem, true);
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
            // no shape updates: a door half, a belt or a link has no support here and
            // would pop, leaving a cell nothing compares. The wall shows every state as given
            level.setBlock(pos, st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
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
            // 14-bk: the 7th column carries the state's LIGHT EMISSION — the
            // ours leg pins the spawn display's brightness to (emission, 15)
            // so an emissive block (the lit lumaloop, the blaze burner, the
            // nixie tubes) renders at its own block light exactly like the
            // truth leg does; the display's default local light is (15, 0)
            // and the truth-vs-ours crop measured the glow ~0.85x dimmer with
            // the warm R/G shift of the missing block-light component.
            rowStr[i] = "cell\t" + pos.getX() + '\t' + pos.getY() + '\t' + pos.getZ() + '\t'
                    + settled + '\t'
                    + blockDynFlag(now) + '\t'
                    + now.getLightEmission();
        }
        // the world keeps moving after this tick: the manifest is written by the
        // delayed pass, which reads what the shot will actually show
        final int fPlaced = placed;
        final int fMutated = mutated;
        final int fSkipFluid = skipFluid;
        final int fSkipInvisible = skipInvisible;
        runAfterTicks(level.getServer(), BLOCK_SETTLE_TICKS, () -> settleBlockPage(level, namespace, page, slice,
                rowStr, blockStem, fPlaced, fMutated, fSkipFluid, fSkipInvisible, 0));
        return placed;
    }

    /** One real settle before the manifest; falling blocks extend it until they land. */
    static final int BLOCK_SETTLE_TICKS = 10;
    private static final int BLOCK_SETTLE_RETRIES = 8;

    /**
     * The tile's settled state, gzipped NBT in base64 — tab/newline-free for
     * the manifest-adjacent file. Never throws: a tile whose serializer fails
     * loses its row (the dump falls back to the fresh BE), not its page.
     */
    private static String benbtB64(ServerLevel level, BlockEntity tile) {
        try {
            CompoundTag tag = CaptureBlockEntityData.snapshot(
                    tile.saveWithoutMetadata(level.registryAccess()),
                    tile.getUpdateTag(level.registryAccess()));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable t) {
            LOGGER.info("[cimscene] gallery be nbt skip {}: {}",
                    BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(tile.getType()), t.toString());
            return null;
        }
    }

    /**
     * Rewrite the page from what the wall holds now. Placed blocks fall, pop off
     * unsupported or turn into something else over the following ticks, and a
     * falling block can land on a cell that was empty. A cell the world emptied
     * becomes a named skip; a cell the world filled becomes a cell.
     */
    private static void settleBlockPage(ServerLevel level, String namespace, int page, List<BlockState> slice,
                                        String[] rowStr, String blockStem, int placed, int mutated,
                                        int skipFluid, int skipInvisible, int tries) {
        AABB wall = new AABB(GALLERY_X0 - 1, GALLERY_Y0 - 1, GALLERY_Z - 1,
                GALLERY_X0 + GALLERY_COLS + 1, GALLERY_Y0 + GALLERY_ROWS + 3, GALLERY_Z + 2);
        if (tries < BLOCK_SETTLE_RETRIES
                && !level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, wall).isEmpty()) {
            runAfterTicks(level.getServer(), BLOCK_SETTLE_TICKS, () -> settleBlockPage(level, namespace, page, slice,
                    rowStr, blockStem, placed, mutated, skipFluid, skipInvisible, tries + 1));
            return;
        }
        int dissolved = 0;
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tstate\tdyn\tlight\n");
        // 14-bn: the settled BEs' real NBT rides a SIBLING file (".benbt" — no
        // ".tsv" suffix, so every manifest glob that counts pages stays
        // blind to it): one row per BE cell — cell x y z nbt(base64 gzip).
        // The quad dump loads it into its capture BE, so runtime state a mod
        // only sets at runtime (the gearshift's shifted flag, the fluid
        // pipe's flow data, the whistle's pressure) renders EXACTLY as the
        // wall settled instead of the fresh BE's defaults; and the truth leg
        // writes the same file from its own world, so the legs' BE-state
        // convergence becomes byte-comparable like the manifests already are.
        StringBuilder benbt = new StringBuilder("cell\tx\ty\tz\tnbt\n");
        int benbtRows = 0;
        for (int i = 0; i < GALLERY_CELLS; i++) {
            int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
            BlockPos pos = new BlockPos(GALLERY_X0 + col, GALLERY_Y0 + row, GALLERY_Z);
            BlockState now = level.getBlockState(pos);
            String was = i < rowStr.length ? rowStr[i] : null;
            boolean cell = was != null && was.startsWith("cell\t");
            if (now.isAir()) {
                if (cell) {
                    dissolved++;
                    // the state as PLACED: the settle pass may already have read air.
                    // A static block keeps its cell: what dissolves here can survive
                    // the truth walk (measured: 5 dynamictrees leaves), and the ours
                    // leg replays this manifest while the join gates on truth's. A
                    // block entity stays out; its mesh can spill into live neighbours.
                    BlockState placedState = slice.get(i);
                    String gone = stateString(placedState,
                            BuiltInRegistries.BLOCK.getKey(placedState.getBlock()));
                    tsv.append(placedState.hasBlockEntity()
                                    ? "skip\t-1\t-1\t-1\t"
                                    : "skip\t" + pos.getX() + '\t' + pos.getY() + '\t' + pos.getZ() + '\t')
                            .append(gone).append("\t0\tdissolved\n");
                    LOGGER.info("[cimscene] gallery settle ns={} page={} reason=dissolved state={}",
                            namespace, page, gone);
                } else if (was != null) {
                    tsv.append(was).append('\n');
                }
                continue;
            }
            tsv.append("cell\t").append(pos.getX()).append('\t').append(pos.getY()).append('\t')
                    .append(pos.getZ()).append('\t')
                    .append(stateString(now, BuiltInRegistries.BLOCK.getKey(now.getBlock()))).append('\t')
                    .append(blockDynFlag(now)).append('\t')
                    .append(now.getLightEmission()).append('\n');
            if (now.hasBlockEntity()) {
                BlockEntity tile = level.getBlockEntity(pos);
                String nbt = tile == null ? null : benbtB64(level, tile);
                if (nbt != null) {
                    benbt.append("cell\t").append(pos.getX()).append('\t').append(pos.getY()).append('\t')
                            .append(pos.getZ()).append('\t').append(nbt).append('\n');
                    benbtRows++;
                }
            }
        }

        // drops from the settle window are not page content
        int sweptEnd = sweepGalleryDrops(level);
        if (sweptEnd > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep end ns={} page={} removed={}",
                    namespace, page, sweptEnd);
        }

        String manifest = "gallery-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
            if (benbtRows > 0) {
                String benbtFile = "gallery-" + namespace + "-p" + page + ".benbt";
                Files.writeString(parent.resolve(benbtFile), benbt.toString(), StandardCharsets.UTF_8);
            }
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery manifest write failed {}: {}", manifest, ioe.toString());
        }

        LOGGER.info("[cimscene] gallery done ns={} page={} cells={} mutated={} dissolved={} skipped_fluid={} "
                        + "skipped_invisible={} first={} last={}",
                namespace, page, placed, mutated, dissolved, skipFluid, skipInvisible,
                stateString(slice.get(0), BuiltInRegistries.BLOCK.getKey(slice.get(0).getBlock())),
                stateString(slice.get(slice.size() - 1),
                        BuiltInRegistries.BLOCK.getKey(slice.get(slice.size() - 1).getBlock())));
        writeDoneMarkerFile(blockStem, false);
        // 14-di THE SETTLE FREEZE: the world stops HERE, at the settle frame,
        // before the walk's settle-shot loop fires a single md5 attempt — the
        // javadoc on setGalleryFrozen is the law. The block series is the
        // 436-RED universe (machines, doors, nixies, walls); every BE-ticker
        // animation phase (the door slide/fold progress, the machine active
        // states, the nixie tickers) now pins at this pass, the same relative
        // tick every run, on both legs (the freeze broadcasts to the joined
        // client and stops its mcmeta/particle/gameTime clocks too).
        setGalleryFrozen(level.getServer(), true, "settle " + blockStem);
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
    /** One ledger write per namespace per boot (the walks re-call it on
     *  every page; the registry walk + the BlockEntity reconciliation are
     *  O(states x BE-types) — once per server is the right cost). */
    private static final Set<String> LEDGER_WRITTEN = new HashSet<>();

    private static void writeGalleryIndex(ServerLevel level, String namespace) {
        if (!LEDGER_WRITTEN.add(namespace)) {
            return;
        }
        long states = 0;
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            if (id.getNamespace().equals(namespace)) {
                states += BuiltInRegistries.BLOCK.get(id)
                        .getStateDefinition().getPossibleStates().size();
            }
        }
        long entities = 0, items = 0, particles = 0, fluids = 0, blockEntities = 0;
        long sounds = 0, menus = 0;
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (id.getNamespace().equals(namespace)) entities++;
        }
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getNamespace().equals(namespace)) items++;
        }
        for (ResourceLocation id : BuiltInRegistries.PARTICLE_TYPE.keySet()) {
            if (id.getNamespace().equals(namespace)) particles++;
        }
        for (ResourceLocation id : BuiltInRegistries.FLUID.keySet()) {
            if (id.getNamespace().equals(namespace)) fluids++;
        }
        for (ResourceLocation id : BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()) {
            if (id.getNamespace().equals(namespace)) blockEntities++;
        }
        for (ResourceLocation id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            if (id.getNamespace().equals(namespace)) sounds++;
        }
        for (ResourceLocation id : BuiltInRegistries.MENU.keySet()) {
            if (id.getNamespace().equals(namespace)) menus++;
        }
        // THE REST OF THE VISUAL SURFACE (12-aq): the world-frame ledger
        // counts EVERY registry whose entries alter a rendered frame —
        // paintings, entity texture variants, mob effects (particle
        // overlays), armor trims, banner patterns, decorated-pot patterns,
        // enchantments (item glint), jukebox songs, dimension types
        // (sky/fog). These classes have NO gallery walk yet — the gate
        // reports them COVERAGE-INCOMPLETE reason=no-walk-yet: the ledger
        // SEES the hole, the red is the named backlog. (The biome row
        // graduated 2026-11-04: the fillbiome tint walk exists,
        // DIAGNOSTIC-first.) Worldgen plumbing (features, loot, predicates,
        // attributes...) shapes the world, not a gallery cell, and stays
        // outside the world-frame ledger (documented line, challengeable).
        RegistryAccess access = level.registryAccess();
        long biomes = dynCount(access, Registries.BIOME, namespace);
        long paintingVariants = dynCount(access, Registries.PAINTING_VARIANT, namespace);
        long wolfVariants = dynCount(access, Registries.WOLF_VARIANT, namespace);
        long trimMaterials = dynCount(access, Registries.TRIM_MATERIAL, namespace);
        long trimPatterns = dynCount(access, Registries.TRIM_PATTERN, namespace);
        long bannerPatterns = dynCount(access, Registries.BANNER_PATTERN, namespace);
        long enchantments = dynCount(access, Registries.ENCHANTMENT, namespace);
        long jukeboxSongs = dynCount(access, Registries.JUKEBOX_SONG, namespace);
        long dimensionTypes = dynCount(access, Registries.DIMENSION_TYPE, namespace);
        long mobEffects = staticCount(BuiltInRegistries.MOB_EFFECT, namespace);
        long catVariants = staticCount(BuiltInRegistries.CAT_VARIANT, namespace);
        long frogVariants = staticCount(BuiltInRegistries.FROG_VARIANT, namespace);
        long villagerTypes = staticCount(BuiltInRegistries.VILLAGER_TYPE, namespace);
        long potPatterns = staticCount(BuiltInRegistries.DECORATED_POT_PATTERN, namespace);
        StringBuilder tsv = new StringBuilder("class\ttotal\tcellsPerPage\n")
                .append("block\t").append(states).append('\t').append(GALLERY_CELLS).append('\n')
                .append("entity\t").append(entities).append('\t').append(ENTITY_CELLS).append('\n')
                .append("item\t").append(items).append('\t').append(GALLERY_CELLS).append('\n')
                .append("particle\t").append(particles).append('\t').append(GALLERY_CELLS).append('\n')
                .append("fluid\t").append(fluids).append('\t').append(FLUID_CELLS).append('\n')
                // no pages of its own: the BE class's picture is drawn by the
                // block wall (real EntityBlock placement); the gate checks the
                // reconciliation file below instead of page counts
                .append("blockentity\t").append(blockEntities).append('\t').append(0).append('\n')
                // the 2026-11-04 classes: sound (the packet-stream channel,
                // one page = 77 events played in order), gui (one MenuType
                // per page), biome (one biome per page — the tint channel).
                // All three walk DIAGNOSTIC-first; the gate flips each when
                // its channel is measured stable (coverage-gate.py
                // DIAGNOSTIC_CLASSES)
                .append("sound\t").append(sounds).append('\t').append(GALLERY_CELLS).append('\n')
                .append("gui\t").append(menus).append("\t1\n")
                // the inventory view (round 2): 36 items per page, the same
                // sorted item list the item walk paginates at 77 — its OWN
                // page count, hence its own ledger row
                .append("inv\t").append(items).append('\t').append(INV_CELLS).append('\n')
                // biome LEFT the no-walk set (the fillbiome walk exists): one
                // biome per page
                .append("biome\t").append(biomes).append("\t1\n")
                // the no-walk classes (12-aq): counted, not walked yet — a
                // negative total means the dynamic registry itself could not
                // be READ at index time (the gate reds that honestly)
                .append("painting_variant\t").append(paintingVariants).append("\t0\n")
                .append("wolf_variant\t").append(wolfVariants).append("\t0\n")
                .append("cat_variant\t").append(catVariants).append("\t0\n")
                .append("frog_variant\t").append(frogVariants).append("\t0\n")
                .append("villager_type\t").append(villagerTypes).append("\t0\n")
                .append("mob_effect\t").append(mobEffects).append("\t0\n")
                .append("trim_material\t").append(trimMaterials).append("\t0\n")
                .append("trim_pattern\t").append(trimPatterns).append("\t0\n")
                .append("banner_pattern\t").append(bannerPatterns).append("\t0\n")
                .append("decorated_pot_pattern\t").append(potPatterns).append("\t0\n")
                .append("enchantment\t").append(enchantments).append("\t0\n")
                .append("jukebox_song\t").append(jukeboxSongs).append("\t0\n")
                .append("dimension_type\t").append(dimensionTypes).append("\t0\n");
        // BLOCK ENTITY RECONCILIATION: a BlockEntityType whose every valid
        // state is fluid-carried or RenderShape.INVISIBLE would never reach
        // the block wall — its renderer would silently vanish from the
        // verdict. Reconcile statically (the same predicate the block wall
        // skips on) and NAME every uncovered type; the gate turns a
        // non-empty file into COVERAGE red.
        List<String> beUncovered = new ArrayList<>();
        if (blockEntities > 0) {
            for (ResourceLocation id : BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()) {
                if (!id.getNamespace().equals(namespace)) continue;
                BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
                boolean covered = false;
                for (ResourceLocation bid : BuiltInRegistries.BLOCK.keySet()) {
                    for (BlockState st : BuiltInRegistries.BLOCK.get(bid)
                            .getStateDefinition().getPossibleStates()) {
                        if (type.isValid(st) && st.getFluidState().isEmpty()
                                && st.getRenderShape() != RenderShape.INVISIBLE) {
                            covered = true;
                            break;
                        }
                    }
                    if (covered) break;
                }
                if (!covered) {
                    beUncovered.add(id.toString());
                }
            }
        }
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve("gallery-" + namespace + "-index.tsv"),
                    tsv.toString(), StandardCharsets.UTF_8);
            Files.writeString(parent.resolve("gallery-" + namespace + "-be-uncovered.tsv"),
                    beUncovered.isEmpty() ? "" : String.join("\n", beUncovered) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-index write failed ns={}: {}", namespace, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-index ns={} block-states={} entities={} items={} "
                        + "particles={} fluids={} block-entities={} sounds={} menus={} "
                        + "be-uncovered={} biomes={} paintings={} effects={} enchantments={} "
                        + "trims={}/{} banners={} pot-patterns={} variants={}/{}/{} "
                        + "villager={} songs={} dimension-types={}",
                namespace, states, entities, items, particles, fluids, blockEntities,
                sounds, menus, beUncovered.size(), biomes, paintingVariants, mobEffects,
                enchantments, trimMaterials, trimPatterns, bannerPatterns, potPatterns,
                wolfVariants, catVariants, frogVariants, villagerTypes, jukeboxSongs,
                dimensionTypes);
    }

    /** Namespace count from a STATIC (BuiltIn) registry. */
    private static long staticCount(Registry<?> reg, String namespace) {
        long n = 0;
        for (ResourceLocation id : reg.keySet()) {
            if (id.getNamespace().equals(namespace)) n++;
        }
        return n;
    }

    /** Namespace count from a DATAPACK (dynamic) registry; -1 when the
     *  registry itself cannot be read — the gate reds that honestly
     *  (unverifiable coverage is not green). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long dynCount(RegistryAccess access,
                                 ResourceKey<? extends Registry<?>> key,
                                 String namespace) {
        try {
            Registry<?> reg = access.registryOrThrow((ResourceKey) key);
            long n = 0;
            for (ResourceLocation id : reg.keySet()) {
                if (id.getNamespace().equals(namespace)) n++;
            }
            return n;
        } catch (Throwable t) {
            LOGGER.error("[cimscene] gallery-index dynamic registry unreadable {}: {}",
                    key.location(), t.toString());
            return -1;
        }
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


    /**
     * 14-bh: the universal dyn signal gains LIGHT EMISSION. A block whose
     * settled state emits light (state.getLightEmission() > 0 — lit lumaloop,
     * glowworm_silk, glowstone, ...) shapes the light field BEYOND its cell:
     * the glow halo on the neighbours and the floor cannot be reproduced by
     * the display channel (the display's brightness is pinned per-cell; no
     * light propagates from it). Measured #114-era: EVERY lit=true lumaloop
     * cell RED (ae 52-125k, the halo), EVERY lit=false cell GREEN — the
     * discriminator is the emission, not the model. Same class as the
     * EntityBlock signal: mechanical, zero hardcode, symmetric on both
     * stacks; the cell's own surface is diffed and reported, the gate is the
     * real-client leg (M5.6). The neighbour halo cells ride the existing
     * SPILL rule (a dyn neighbour within reach demotes their REDs).
     */
    private static int blockDynFlag(net.minecraft.world.level.block.state.BlockState state) {
        return (state.getBlock() instanceof EntityBlock || state.getLightEmission() > 0) ? 1 : 0;
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

    /**
     * Where dropped items may exist for the pixel gate: the wall itself plus
     * the floor strip in front of it where pops land (the wall bottom row
     * sits on the superflat floor at y=-61; item entities rest there). The
     * entity pages also spawn subjects inside this box — their loot is
     * cancelled by {@link #onEntityJoinLevel} with the same contract.
     */
    private static final AABB GALLERY_DROP_BOX =
            new AABB(GALLERY_X0 - 3, GALLERY_Y0 - 2, GALLERY_Z - 5,
                     GALLERY_X0 + GALLERY_COLS + 3, GALLERY_Y0 + GALLERY_ROWS + 2, GALLERY_Z + 5);

    /**
     * Discard ItemEntities inside {@link #GALLERY_DROP_BOX}. The join event
     * guard cancels NEW drops; this sweep removes anything that predates it
     * (earlier walk stages, a guard registered after some pop) — belt and
     * braces for the same asymmetry.
     *
     * @return the number of entities discarded (logged by the caller)
     */
    private static int sweepGalleryDrops(ServerLevel level) {
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, GALLERY_DROP_BOX);
        for (ItemEntity item : items) {
            item.discard();
        }
        return items.size();
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
        setGalleryFrozen(level.getServer(), false, "item page entry");
        writeGalleryIndex(level, namespace);
        int swept = sweepGalleryDrops(level);
        if (swept > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep ns={} page={} removed={}", namespace, page, swept);
        }
        String itemStem = "gallery-item-" + namespace + "-p" + page;

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
            writeDoneMarkerFile(itemStem, true);
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
        writeDoneMarkerFile(itemStem, false);
        return placed;
    }

    // --- particle gallery (every registered PARTICLE of the namespace) ------

    /**
     * The universal PARTICLE gallery: EVERY {@link ParticleType} registered
     * by the namespace, sorted by id, one particle per wall cell.
     *
     * <p>THE PROGRAMMATIC SPAWN LADDER (round 2, 2026-11-04 — replaces the
     * vanilla {@code /particle} console surface, whose ARGUMENT parser
     * rejected every modded particle whose options are not
     * string-constructible: measured #30, ALL 13 create particles). The
     * spawn now goes server-side via {@link ServerLevel#sendParticles}
     * with options built programmatically, no string parsing:
     * <ol>
     *   <li>a {@link SimpleParticleType} IS its own {@link ParticleOptions}
     *       — spawned directly;</li>
     *   <li>a data-carrying type's options are decoded from an EMPTY
     *       compound through the type's own codec
     *       ({@code type.codec().parse(NbtOps.INSTANCE, new CompoundTag())})
     *       — a type whose defaults are fully derivable this way now
     *       spawns; whether a specific modded codec accepts empty data is
     *       FIRST-RUN-measured, not asserted;</li>
     *   <li>both fail → the recorded skip row KEEPS the
     *       {@code no-simple-options} note (now carrying the reason) — the
     *       programmatic spawn itself threw / produced nothing.</li>
     * </ol>
     *
     * <p>Honesty rules, identical on both stacks (the same options object
     * semantics; the ours leg replays the manifest and its join names what
     * its channel cannot express — there is NO ClientboundLevelParticles
     * channel in the pack/plugin today, verified by grep before wiring):
     * every spawned particle is {@code dyn=1} — particles are time-varying
     * by nature, so the join reports them without a red verdict until the
     * fixed-shot-window protocol is measured stable (the entity dyn
     * pattern); the walk still SHOWS every type to the test player.
     *
     * <p>The manifest is written synchronously (a particle is a spawn spec,
     * not a settled world state) and the done marker carries the exhausted
     * flag for the coverage gate. The SHOT WINDOW (fixed sleep after this
     * command on both legs) is pinned by the walk, not by the scene.
     */
    private static int galleryParticle(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "particle page entry");
        writeGalleryIndex(level, namespace);
        int swept = sweepGalleryDrops(level);
        if (swept > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep ns={} page={} removed={}", namespace, page, swept);
        }
        String particleStem = "gallery-particle-" + namespace + "-p" + page;

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
            writeDoneMarkerFile(particleStem, true);
            LOGGER.info("[cimscene] gallery-particle done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        int to = Math.min(from + GALLERY_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        clearWall(level);

        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tparticle\tdyn\tnote\n");
        int spawned = 0, failed = 0;
        // THE CAPTURE WINDOW (2026-11-05, the PARTICLE-STREAM channel —
        // DIAGNOSTIC-first): armed before the first spawn, disarmed after
        // the last — sendParticles broadcasts synchronously on the server
        // thread, so the window holds exactly this page's outgoing packets
        // (the sound-packet capture mixin's particle branch records them)
        List<String> heard = ParticleCapture.begin();
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
            int x = GALLERY_X0 + col, y = GALLERY_Y0 + row, z = GALLERY_Z;
            ResolvedOptions resolved = resolveParticleOptions(
                    BuiltInRegistries.PARTICLE_TYPE.get(id));
            if (resolved.options() != null) {
                // options resolved (the ladder's step 1 or 2) — send exactly
                // ONE particle, delta 0/0/0, speed 0: a STATIC particle at
                // the cell, the same spec the old /particle surface spelled
                int n = level.sendParticles(resolved.options(), x + 0.5, y + 0.5,
                        z + 0.5, 1, 0.0, 0.0, 0.0, 0.0);
                if (n > 0) {
                    spawned++;
                    tsv.append("cell\t").append(x).append('\t').append(y).append('\t')
                            .append(z).append('\t').append(id).append("\t1\tok\n");
                } else {
                    failed++;
                    tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\t1\t")
                            .append("no-simple-options:empty-broadcast\n");
                    LOGGER.info("[cimscene] gallery-particle skip ns={} page={} index={} "
                                    + "reason=no-simple-options detail=empty-broadcast particle={}",
                            namespace, page, from + i, id);
                }
            } else {
                String fail = resolved.failure() == null
                        ? "no-simple-options:unknown" : resolved.failure();
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\t1\t").append(fail).append('\n');
                LOGGER.info("[cimscene] gallery-particle skip ns={} page={} index={} "
                                + "reason={} particle={}",
                        namespace, page, from + i, fail, id);
            }
        }
        List<String> stream = ParticleCapture.end(heard);
        String manifest = "gallery-particle-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
            // the ordered particle-type stream (one id per line) — the
            // truth leg's artifact of the particle-stream channel join
            Files.writeString(parent.resolve("gallery-particle-" + namespace
                            + "-p" + page + ".log"),
                    stream.isEmpty() ? "" : String.join("\n", stream) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-particle manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-particle done ns={} page={} cells={} failed={} "
                        + "captured={} first={} last={}",
                namespace, page, spawned, failed, stream.size(), slice.get(0),
                slice.get(slice.size() - 1));
        writeDoneMarkerFile(particleStem, false);
        return spawned;
    }

    /** The programmatic particle ladder's outcome: exactly one of the two. */
    private record ResolvedOptions(ParticleOptions options, String failure) {
    }

    /**
     * THE PROGRAMMATIC PARTICLE-OPTIONS LADDER (no string parsing, no
     * per-mod code):
     * <ol>
     *   <li>a simple particle type (SimpleParticleType) implements
     *       {@link ParticleOptions} — the type instance IS the options;</li>
     *   <li>a data-carrying type's options are decoded from an EMPTY
     *       compound through the type's OWN codec — a codec whose fields
     *       all carry defaults accepts it; one with required fields
     *       refuses (the DataResult carries the error, surfaced in the
     *       note). Whether a given modded codec accepts empty data is
     *       FIRST-RUN-measured, not asserted.</li>
     *   <li>THE ERROR-DRIVEN DEFAULT FILLER (rung 3): the refusal names the
     *       MISSING keys ("No key speed in MapLike[{}]"), so fill every
     *       missing key with the current candidate and re-parse; when
     *       nothing is missing but the parse still refuses, advance the
     *       candidate (a typed value was rejected) and rebuild. Deterministic
     *       BY CONSTRUCTION — both legs run the same codec over the same
     *       candidate ladder and land on the SAME options, the cell compares
     *       like with like. Measured #30: 8/13 create particles refuse the
     *       empty compound (fluid refs, rgb triples, speed/drag floats) —
     *       the generic filler is the answer, zero per-mod code anywhere.</li>
     * </ol>
     */
    private static ResolvedOptions resolveParticleOptions(ParticleType<?> type) {
        if (type instanceof ParticleOptions simple) {
            return new ResolvedOptions(simple, null);
        }
        try {
            var codec = type.codec().codec();
            DataResult<? extends ParticleOptions> res =
                    codec.parse(NbtOps.INSTANCE, new CompoundTag());
            if (res.result().isPresent()) {
                return new ResolvedOptions(res.result().get(), null);
            }
            String firstErr = res.error().map(e -> e.message()).orElse("empty-decode");
            // RUNG 3 — the error-driven default filler (see the ladder doc).
            // PER-KEY cursors with key-name hints: a mixed-type codec (a
            // string id beside numeric fields) has NO uniform candidate —
            // measured: the scalar-only ladder left (string + 5 numerics)
            // and the fluid trio unsolved. Each key starts at its hint
            // index; a type-error round advances every key's cursor.
            CompoundTag probe = new CompoundTag();
            Map<String, Integer> cursors = new LinkedHashMap<>();
            List<String> trace = new ArrayList<>();
            for (int round = 0; round < OPTION_FILL_CANDIDATES_LENGTH + 2; round++) {
                DataResult<? extends ParticleOptions> r =
                        codec.parse(NbtOps.INSTANCE, probe);
                if (r.result().isPresent()) {
                    return new ResolvedOptions(r.result().get(), null);
                }
                String err = r.error().map(e -> e.message()).orElse("");
                trace.add("r" + round + "[" + probe + "]:" + err);
                List<String> missing = missingOptionKeys(err);
                if (!missing.isEmpty()) {
                    // THE FILL LANDS ON THE NODE THE REFUSAL NAMED: the fluid
                    // holder's keys live INSIDE the nested {Name:...} map —
                    // measured r1-r4: a root-level fill the codec ignores,
                    // the error repeating verbatim. The target = the deepest
                    // compound whose serialized form the error quotes.
                    FillTarget target = findFillTarget(probe, err);
                    for (String key : missing) {
                        String cpath = target.path().isEmpty()
                                ? key : target.path() + "." + key;
                        target.node().put(key, fillCandidate(cursors
                                .computeIfAbsent(cpath,
                                        k -> hintIndex(key)).intValue()));
                    }
                    continue;
                }
                // a type-error round: THE ZERO-VALUED KEYS MOVE FIRST — a
                // "Value must be positive: 0" refusal targets the zeros, and
                // bumping the keys the codec already ACCEPTED would destroy
                // the solved structure (measured: the lockstep walk replaced
                // the accepted {Name, amount, id} map with "x"). A bare type
                // mismatch (Not a string / Not a map) still advances every
                // key — the hints missed for it.
                boolean zeroComplaint = err.contains("positive")
                        || err.contains("must be") || err.contains("range")
                        || err.contains("smaller than")
                        || err.contains("bigger than");
                boolean advanced = false;
                List<String> bumped = new ArrayList<>();
                for (Map.Entry<String, Integer> e : cursors.entrySet()) {
                    Tag cur = tagAtPath(probe, e.getKey());
                    boolean zeroLike = cur instanceof net.minecraft.nbt.NumericTag n
                            && n.getAsDouble() == 0.0;
                    if ((zeroLike || !zeroComplaint)
                            && e.getValue() + 1 < OPTION_FILL_CANDIDATES_LENGTH) {
                        cursors.put(e.getKey(), e.getValue() + 1);
                        bumped.add(e.getKey());
                        advanced = true;
                    }
                }
                if (!advanced) {
                    LOGGER.info("[cimscene] particle filler trace: {}",
                            String.join(" | ", trace));
                    return new ResolvedOptions(null,
                            "no-simple-options:filler-cursor-exhausted:" + err);
                }
                for (String path : bumped) {
                    setAtPath(probe, path, fillCandidate(cursors.get(path)));
                }
                firstErr = err; // the last refusal rides the exhausted note
            }
            LOGGER.info("[cimscene] particle filler trace: {}",
                    String.join(" | ", trace));
            return new ResolvedOptions(null,
                    "no-simple-options:filler-rounds-exhausted:" + firstErr);
        } catch (Throwable t) {
            return new ResolvedOptions(null,
                    "no-simple-options:" + t.getClass().getName());
        }
    }

    /**
     * The filler candidates, mildest first: zero for numerics (speed, xyz,
     * scale, ages — a NumericTag decodes as any numeric), false for flags
     * (NbtOps reads a boolean from any number), unit-scale numerics (rgb,
     * unit scales), then the registry-ref string ids (fluids, block refs),
     * the empty string, and the MAP shapes a registry-entry codec reads on
     * NbtOps ({@code {Name: ...}} the vanilla form, {@code {id: ...}} the
     * NeoForge holder form) — measured: the create fluid-particle keys
     * refuse every scalar and accept only a map. A value that renders
     * nothing on BOTH legs identically is the honest floor — the join
     * compares like with like.
     */
    private static final int OPTION_FILL_CANDIDATES_LENGTH = 13;

    /** Fresh tag per use (tags are shared-mutable; the probe owns its copy). */
    private static Tag fillCandidate(int i) {
        return switch (Math.floorMod(i, OPTION_FILL_CANDIDATES_LENGTH)) {
            case 0 -> IntTag.valueOf(0);
            case 1 -> ByteTag.valueOf(false);
            case 2 -> FloatTag.valueOf(1.0f);
            case 3 -> StringTag.valueOf("minecraft:water");
            case 4 -> StringTag.valueOf("minecraft:air");
            case 5 -> StringTag.valueOf("minecraft:block");
            case 6 -> StringTag.valueOf("");
            case 7 -> ByteTag.valueOf(true);
            case 8 -> FloatTag.valueOf(0.5f);
            case 9 -> mapRef("Name", "minecraft:water");
            case 10 -> mapRef("id", "minecraft:water");
            case 11 -> StringTag.valueOf("x"); // the axis enum's first member
            default -> StringTag.valueOf("north"); // the Direction enum's member
        };
    }

    /** A one-entry registry-reference map ({@code {Name: "minecraft:water"}}). */
    private static CompoundTag mapRef(String key, String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString(key, id);
        return tag;
    }

    /** Where a missing key lands: the node + its cursor path. */
    private record FillTarget(CompoundTag node, String path) {
    }

    /** The tag at a dotted cursor path ({@code fluid.amount} → the nested). */
    private static Tag tagAtPath(CompoundTag root, String path) {
        CompoundTag node = root;
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            node = node.getCompound(path.substring(0, dot));
        }
        return node.get(path.substring(dot + 1));
    }

    /** In-place write at a dotted cursor path (the parent maps exist). */
    private static void setAtPath(CompoundTag root, String path, Tag value) {
        CompoundTag node = root;
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            node = node.getCompound(path.substring(0, dot));
        }
        node.put(path.substring(dot + 1), value);
    }

    /**
     * The deepest compound in the probe whose serialized form the codec
     * refusal quotes (the DFU MapLike[...] renders the parsed node's NBT) —
     * the root always matches its own {@code MapLike[{}]}, a nested holder
     * matches only when the refusal descends into it, so the longest match
     * is the node the missing keys belong to.
     */
    private static FillTarget findFillTarget(CompoundTag probe, String err) {
        String[] best = {"", ""}; // path, serialization (the root is the fallback)
        findFillTargetWalk(probe, "", err, best);
        if (best[0].isEmpty()) {
            return new FillTarget(probe, "");
        }
        CompoundTag node = probe;
        for (String part : best[0].split("\\.")) {
            node = node.getCompound(part);
        }
        return new FillTarget(node, best[0]);
    }

    private static void findFillTargetWalk(CompoundTag node, String path,
                                           String err, String[] best) {
        String ser = node.getAsString();
        // the refusal quotes the node it parsed (DFU's MapLike[...] renders
        // the node's NBT verbatim) — the LONGEST quoted compound is the
        // innermost failing map the missing keys belong to
        if (err.contains(ser) && ser.length() > best[1].length()) {
            best[0] = path;
            best[1] = ser;
        }
        for (String k : node.getAllKeys()) {
            if (node.get(k) instanceof CompoundTag child) {
                findFillTargetWalk(child, path.isEmpty() ? k : path + "." + k,
                        err, best);
            }
        }
    }

    /**
     * The per-key STARTING candidate from the key's own name: fluid keys
     * begin at the registry-map form, id/name/type keys at the string form,
     * boolean-looking keys at false, everything else at the zero numeric.
     * Deterministic — the same key name starts at the same candidate on
     * both legs.
     */
    private static int hintIndex(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (k.equals("axis") || k.endsWith("_axis")) {
            return 11; // the axis enum's "x" member (measured: create's
                       // axis/color options codec wants a valid enum name,
                       // the numeric fields beside it accept the zero)
        }
        if (k.equals("direction") || k.equals("facing")) {
            return 12; // a Direction member (measured: mekanism's jetpack
                       // flame options want direction=north beside numerics)
        }
        if (k.equals("fluid") || k.endsWith("_fluid")) {
            return 9; // {Name: minecraft:water}
        }
        if (k.equals("id") || k.equals("name") || k.equals("type")
                || k.equals("target") || k.equals("block") || k.equals("item")) {
            return 3; // a string registry id
        }
        if (k.equals("hot") || k.equals("glow") || k.startsWith("is_")
                || k.endsWith("_flag") || k.equals("gravity")) {
            return 1; // false
        }
        return 0; // the zero numeric
    }

    /**
     * The missing keys a codec refusal named: the DataResult message shape
     * "No key &lt;name&gt; in MapLike[...]" (one per absent field, "; "
     * separated when several are absent — the #30 notes carry them).
     */
    private static List<String> missingOptionKeys(String error) {
        List<String> keys = new ArrayList<>();
        if (error == null || error.isEmpty()) {
            return keys;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("No key ([A-Za-z0-9_]+) in MapLike")
                .matcher(error);
        while (m.find()) {
            if (!keys.contains(m.group(1))) {
                keys.add(m.group(1));
            }
        }
        return keys;
    }

    // --- fluid gallery (every registered FLUID of the namespace) -----------

    /**
     * The universal FLUID gallery: EVERY {@link Fluid} registered by the
     * namespace, sorted by id, shown one per SEALED glass case on the sparse
     * pitch-2 grid. THE MULTI-SURFACE FALLBACK LADDER (round 2, 2026-11-04
     * — a fluid with no legacy block is no longer a bare skip; EVERY fluid
     * gets a manifest row and an ATTEMPTED surface, in order):
     * <ol>
     *   <li>{@code block} — the fluid state has a legacy block (every
     *       vanilla + modded liquid does): placed into the sealed case, the
     *       manifest row carries the state string so the ours leg replays
     *       it;</li>
     *   <li>{@code bucket} — no legacy block: the fluid's registered
     *       {@code <path>_bucket} item shown as an item_display (the item
     *       rig grammar);</li>
     *   <li>{@code mod-tank} — the mod registers its own fluid-tank block
     *       ({@code <ns>:fluid_tank} — create's vessel): the block is
     *       placed and filled through NeoForge's fluid capability; the
     *       settled BE contents are saved for native model/BER capture;</li>
     *   <li>{@code texture-model} — the server resource manager finds the
     *       fluid's still texture ({@code textures/fluid/<path>.png} or
     *       {@code <path>_still.png}): the OURS pack builder synthesizes an
     *       item model from that same texture (same jar-asset walk as the
     *       pack's block/item synthesis); the TRUTH side has NO native
     *       surface for such a fluid — the cell stays empty and the row's
     *       extra names the fluid id for the ours rig; the join compares
     *       truth-empty vs ours-model honestly (dyn class, no red
     *       verdict);</li>
     *   <li>nothing renderable on EITHER side → the recorded skip row
     *       carries {@code no-truth-surface:<reason>} — the honest-RED
     *       backlog, named, never silent.</li>
     * </ol>
     *
     * <p>All cell rows carry {@code dyn=1}: liquid textures are ANIMATED
     * (mcmeta frametime), and the two legs' clients are not tick-locked to
     * each other — the same lesson the particle walk measured (fixed shot
     * window, dyn class). The COVERAGE of the class (every fluid walked to
     * exhaustion) is still gate-hard via the ledger.
     *
     * <p>Manifest {@code gallery-fluid-<ns>-p<p>.tsv}, rows
     * {@code cell|x|y|z|fluid|dyn|channel|extra} plus skip rows.
     */
    private static int galleryFluid(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "fluid page entry");
        writeGalleryIndex(level, namespace);
        int swept = sweepGalleryDrops(level);
        if (swept > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep ns={} page={} removed={}", namespace, page, swept);
        }
        String fluidStem = "gallery-fluid-" + namespace + "-p" + page;

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.FLUID.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) entries.add(id);
        }

        LOGGER.info("[cimscene] gallery-fluid begin ns={} page={} fluids={} cellsPerPage={}",
                namespace, page, entries.size(), FLUID_CELLS);
        int from = page * FLUID_CELLS;
        if (from >= entries.size()) {
            writeDoneMarkerFile(fluidStem, true);
            LOGGER.info("[cimscene] gallery-fluid done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        int to = Math.min(from + FLUID_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        clearWall(level);
        // page hygiene: stale gallery displays from the previous page
        runQuiet(src, "kill @e[type=minecraft:item_display,tag=" + GALLERY_TAG + "]");

        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tfluid\tdyn\tchannel\textra\n");
        int placed = 0, failed = 0;
        int settleTicks = BLOCK_SETTLE_TICKS;
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % FLUID_COLS, row = i / FLUID_COLS;
            int x = GALLERY_X0 + col * FLUID_PITCH, y = GALLERY_Y0 + row * FLUID_PITCH, z = GALLERY_Z;
            String channel = null;
            String extra = "";
            String reason = "unknown";
            try {
                Fluid fluid = BuiltInRegistries.FLUID.get(id);
                FluidState fs = fluid.defaultFluidState();
                BlockState legacy = fs.createLegacyBlock();
                // (a) the placeable liquid in the sealed case
                if (!legacy.isAir()) {
                    // Let a falling default state execute its actual scheduled
                    // fluid tick before naming the captured settled surface.
                    settleTicks = Math.max(settleTicks, Math.multiplyExact(2, fluid.getTickDelay(level)));
                    buildFluidShell(level, x, y, z);
                    level.setBlock(new BlockPos(x, y, z), legacy, 3);
                    channel = "block";
                    extra = stateString(legacy, BuiltInRegistries.BLOCK.getKey(legacy.getBlock()));
                } else {
                    // (b) the bucket item as an item_display rig
                    ResourceLocation bucket = ResourceLocation.fromNamespaceAndPath(
                            id.getNamespace(), id.getPath() + "_bucket");
                    if (BuiltInRegistries.ITEM.containsKey(bucket)) {
                        String nbt = "{Tags:[\"" + GALLERY_TAG + "\"],billboard:\"fixed\","
                                + "brightness:{sky:15b,block:15b},"
                                + "item:{id:\"" + bucket + "\",count:1}}";
                        int rc = runQuiet(src, "summon minecraft:item_display "
                                + (x + 0.5) + " " + (y + 0.5) + " " + (z + 0.5) + " " + nbt);
                        if (rc > 0) {
                            channel = "bucket";
                            extra = bucket.toString();
                        } else {
                            reason = "bucket-summon-failed";
                        }
                    } else {
                        reason = "no-bucket";
                    }
                }
                // (c) the mod's own fluid-tank block, if it registers one
                // (create's vessel) — the block is placed AND its contents
                // filled through NeoForge's BLOCK fluid capability (round
                // 3b): zero mod imports, the capability surface is the one
                // create's own tank BE registers; the note records exactly
                // what the fill accepted.
                if (channel == null) {
                    ResourceLocation tank = ResourceLocation.fromNamespaceAndPath(
                            id.getNamespace(), "fluid_tank");
                    if (BuiltInRegistries.BLOCK.containsKey(tank)) {
                        BlockState tankState = BuiltInRegistries.BLOCK.get(tank)
                                .defaultBlockState();
                        buildFluidShell(level, x, y, z);
                        level.setBlock(new BlockPos(x, y, z), tankState, 3);
                        channel = "mod-tank";
                        extra = stateString(tankState, tank) + "|"
                                + fillTankContents(level, id, x, y, z);
                    } else {
                        reason = reason.equals("no-bucket") ? "no-bucket-and-no-tank" : reason;
                    }
                }
                // (d) the fluid's still texture: the ours pack synthesizes
                // an item model from it; the truth side has no native
                // surface (the cell stays empty; the row IS the attempt and
                // the join names the truth absence)
                if (channel == null) {
                    if (fluidTextureExists(src, id)) {
                        channel = "texture-model";
                        extra = id.toString();
                    } else {
                        reason = reason.equals("unknown") ? "no-fluid-texture" : reason;
                    }
                }
            } catch (Throwable t) {
                LOGGER.info("[cimscene] gallery-fluid fail ns={} page={} index={} fluid={} err={}",
                        namespace, page, from + i, id, t.toString());
                reason = "threw:" + t.getClass().getName();
            }
            if (channel == null) {
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\t1\tno-truth-surface:")
                        .append(reason).append('\n');
                LOGGER.info("[cimscene] gallery-fluid skip ns={} page={} index={} "
                                + "reason=no-truth-surface detail={} fluid={}",
                        namespace, page, from + i, reason, id);
            } else {
                placed++;
                tsv.append("cell\t").append(x).append('\t').append(y).append('\t').append(z)
                        .append('\t').append(id).append("\t1\t").append(channel)
                        .append('\t').append(extra).append('\n');
            }
        }
        final int filledCells = placed, failedCells = failed;
        // Snapshot real contents after native connectivity/client updates settle.
        // The done marker follows the files, so seed capture cannot race them.
        runAfterTicks(level.getServer(), settleTicks, () -> {
            StringBuilder settled = new StringBuilder();
            StringBuilder tags = new StringBuilder("cell\tx\ty\tz\tnbt\n");
            for (String line : tsv.toString().split("\n")) {
                String[] row = line.split("\t", -1);
                if (row.length >= 8 && row[0].equals("cell") && !row[1].equals("x")
                        && row[6].equals("block")) {
                    BlockPos pos = new BlockPos(Integer.parseInt(row[1]),
                            Integer.parseInt(row[2]), Integer.parseInt(row[3]));
                    BlockState now = level.getBlockState(pos);
                    row[7] = stateString(now, BuiltInRegistries.BLOCK.getKey(now.getBlock()));
                    line = String.join("\t", row);
                }
                if (row.length >= 8 && row[0].equals("cell") && row[6].equals("mod-tank")) {
                    BlockPos pos = new BlockPos(Integer.parseInt(row[1]),
                            Integer.parseInt(row[2]), Integer.parseInt(row[3]));
                    BlockState now = level.getBlockState(pos);
                    int bar = row[7].indexOf('|');
                    row[7] = stateString(now, BuiltInRegistries.BLOCK.getKey(now.getBlock()))
                            + (bar >= 0 ? row[7].substring(bar) : "");
                    line = String.join("\t", row);
                    BlockEntity tile = level.getBlockEntity(pos);
                    String nbt = tile == null ? null : benbtB64(level, tile);
                    if (nbt != null) {
                        tags.append("cell\t").append(row[1]).append('\t').append(row[2])
                                .append('\t').append(row[3]).append('\t').append(nbt).append('\n');
                    }
                }
                settled.append(line).append('\n');
            }
            try {
                Path parent = Path.of("c-imagine", "scenes");
                Files.createDirectories(parent);
                Files.writeString(parent.resolve(fluidStem + ".tsv"), settled, StandardCharsets.UTF_8);
                Files.writeString(parent.resolve(fluidStem + ".benbt"), tags, StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                LOGGER.error("[cimscene] gallery-fluid manifest write failed {}: {}", fluidStem, ioe.toString());
                return; // missing done marker is a hard capture failure
            }
            LOGGER.info("[cimscene] gallery-fluid done ns={} page={} cells={} failed={} first={} last={}",
                    namespace, page, filledCells, failedCells, slice.get(0), slice.get(slice.size() - 1));
            writeDoneMarkerFile(fluidStem, false);
        });
        return placed;
    }

    /**
     * The glass case around one fluid cell: six glass faces so the placed
     * liquid can never flow (static pixels). Adjacent pitch-2 cases share
     * their face blocks — the same glass set twice, harmless. The floor is
     * never replaced: the bottom-row interior sits ON the superflat floor
     * exactly like the block wall's bottom row (the fill-one-lower floor
     * lesson).
     */
    private static void buildFluidShell(ServerLevel level, int x, int y, int z) {
        BlockState glass = Blocks.GLASS.defaultBlockState();
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            int bx = x + f[0], by = y + f[1], bz = z + f[2];
            if (by < GALLERY_Y0) {
                continue; // the floor stays
            }
            level.setBlock(new BlockPos(bx, by, bz), glass, 3);
        }
    }

    /**
     * The texture-model ladder's probe: does the mod ship a still texture
     * for this fluid? Probed through the SERVER resource manager (a vanilla
     * surface — the same resource tree the pack builder scans, so both
     * sides agree on the convention). Probes {@code textures/fluid/<path>.png}
     * then {@code textures/fluid/<path>_still.png}; the pack builder scans
     * the same tree and strips the same suffixes when deriving the fluid id.
     */
    /**
     * THE MOD-TANK CONTENTS FILL (round 3b, 2026-11-04): fill the just
     * placed tank with the page's fluid through NeoForge's BLOCK fluid
     * CAPABILITY — the same surface create's own FluidTankBlockEntity
     * registers (verified against the pinned create 6.0.10 jar's constant
     * pool: getCapability(BlockCapability, BlockPos, Direction) over
     * Capabilities$FluidHandler). ZERO create imports: javac needs only
     * the NeoForge API, so the build needs no ladder jar on its classpath
     * and no build.gradle change. A lone tank block is its own controller;
     * the capability resolves (or the note says so). The quota is a fixed
     * generous 16000 mB; the tank takes what fits and the accepted amount
     * rides the manifest's extra column. The delayed snapshot saves the
     * real settled BE inventory; the native capture renders both the tank
     * and its contents for the ours replay. Pixel parity remains gate-hard.
     */
    private static String fillTankContents(ServerLevel level, ResourceLocation fluidId,
                                           int x, int y, int z) {
        try {
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK,
                    new BlockPos(x, y, z), null);
            if (handler == null) {
                LOGGER.info("[cimscene] gallery-fluid tank fill: no fluid capability at {},{},{}",
                        x, y, z);
                return "contents-no-capability";
            }
            int filled = handler.fill(new FluidStack(
                    BuiltInRegistries.FLUID.get(fluidId), 16000),
                    IFluidHandler.FluidAction.EXECUTE);
            LOGGER.info("[cimscene] gallery-fluid tank fill ns-fluid={} at {},{},{} accepted={}",
                    fluidId, x, y, z, filled);
            return filled > 0 ? ("contents-filled:" + filled) : "contents-refused";
        } catch (Throwable t) {
            LOGGER.info("[cimscene] gallery-fluid tank fill failed {}: {}",
                    fluidId, t.toString());
            return "contents-threw:" + t.getClass().getName();
        }
    }

    private static boolean fluidTextureExists(CommandSourceStack src, ResourceLocation fluidId) {
        try {
            var rm = src.getServer().getResourceManager();
            for (String name : new String[]{fluidId.getPath() + ".png",
                    fluidId.getPath() + "_still.png"}) {
                if (rm.getResource(ResourceLocation.fromNamespaceAndPath(
                        fluidId.getNamespace(), "textures/fluid/" + name)).isPresent()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            LOGGER.info("[cimscene] gallery-fluid texture probe failed {}: {}",
                    fluidId, t.toString());
            return false;
        }
    }

    // --- sound gallery (the SOUND channel census, 2026-11-04 — DIAGNOSTIC
    // --- first, like the entity dyn series: reported, never silently green)

    /**
     * The universal SOUND gallery: EVERY {@link SoundEvent} registered by
     * the namespace, sorted by id, PLAYED in one deterministic sequence
     * inside one page window ({@code ServerLevel.playSound} at the wall
     * cell, MASTER, volume 1, pitch 1). The picture is not pixels — it is
     * the ordered event-id STREAM the client receives: while the page plays,
     * the sound-packet capture mixin records every outgoing
     * {@code ClientboundSoundPacket}/{@code ClientboundSoundEntityPacket}
     * into {@code SoundCapture}, and the page writes that stream to
     * {@code gallery-sound-<ns>-p<p>.log} next to the manifest. The ours leg
     * (Paper + plugin) replays the SAME manifest through its own surface and
     * writes its own log; sound-diff.py joins the two streams per page
     * (exact ordered equality, census-style).
     *
     * <p>HONESTY: there is no sound translation in the c-imagine pack
     * channel (verified 2026-11-04: the paper-plugin has no
     * ClientboundSound handling), so the ours log is expected to record
     * nochannel per row until the channel is implemented — the join reports
     * that, DIAGNOSTIC-first (never a red verdict, never a silent green).
     * A play that throws on the server (an event id without a valid sound
     * registration) is a recorded skip row (note=play-failed), identical on
     * both stacks — the same commands run on both.
     */
    private static int gallerySound(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "sound page entry");
        writeGalleryIndex(level, namespace);
        String soundStem = "gallery-sound-" + namespace + "-p" + page;

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.SOUND_EVENT.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) entries.add(id);
        }

        LOGGER.info("[cimscene] gallery-sound begin ns={} page={} sounds={} cellsPerPage={}",
                namespace, page, entries.size(), GALLERY_CELLS);
        int from = page * GALLERY_CELLS;
        if (from >= entries.size()) {
            writeDoneMarkerFile(soundStem, true);
            LOGGER.info("[cimscene] gallery-sound done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        int to = Math.min(from + GALLERY_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        clearWall(level);

        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tsound\tnote\n");
        int played = 0, failed = 0;
        // THE CAPTURE WINDOW: armed before the first play, disarmed after
        // the last — playSound broadcasts synchronously on the server
        // thread, so the window holds exactly this page's outgoing packets
        List<String> heard = SoundCapture.begin();
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
            int x = GALLERY_X0 + col, y = GALLERY_Y0 + row, z = GALLERY_Z;
            try {
                SoundEvent ev = BuiltInRegistries.SOUND_EVENT.get(id);
                level.playSound(null, x + 0.5, y + 0.5, z + 0.5, ev,
                        SoundSource.MASTER, 1.0f, 1.0f);
                played++;
                tsv.append("cell\t").append(x).append('\t').append(y).append('\t')
                        .append(z).append('\t').append(id).append("\tok\n");
            } catch (Throwable t) {
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\tplay-failed")
                        .append(':').append(t.getClass().getName()).append('\n');
                LOGGER.info("[cimscene] gallery-sound skip ns={} page={} index={} "
                                + "reason=play-failed sound={} err={}",
                        namespace, page, from + i, id, t.toString());
            }
        }
        List<String> stream = SoundCapture.end(heard);
        String manifest = "gallery-sound-" + namespace + "-p" + page + ".tsv";
        String capture = "gallery-sound-" + namespace + "-p" + page + ".log";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
            Files.writeString(parent.resolve(capture),
                    stream.isEmpty() ? "" : String.join("\n", stream) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-sound write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-sound done ns={} page={} cells={} failed={} "
                        + "captured={} first={} last={}",
                namespace, page, played, failed, stream.size(), slice.get(0),
                slice.get(slice.size() - 1));
        writeDoneMarkerFile(soundStem, false);
        return played;
    }

    /**
     * THE SOUND-CAPTURE WINDOW (the static holder the mixin writes into —
     * an ORDINARY vanilla-surface probe, zero c-imagine APIs). Armed by the
     * sound gallery command, recorded by the sound-packet capture mixin
     * (every outgoing clientbound sound packet's event id, in arrival
     * order), disarmed by the command which then owns the collected stream.
     * Server-thread confined: the RCON command and the packet broadcast run
     * on the same thread, so a plain static field is the whole contract.
     */
    public static final class SoundCapture {
        private static List<String> stream = null;

        /** Arms the capture and returns the list to pass back to end(). */
        public static List<String> begin() {
            List<String> fresh = new ArrayList<>();
            stream = fresh;
            return fresh;
        }

        /** Disarms and returns the recorded stream (ordered event ids). */
        public static List<String> end(List<String> expected) {
            stream = null;
            return expected;
        }

        /** Called by the sound-packet mixin; no-op while disarmed. */
        public static void record(String soundId) {
            List<String> s = stream;
            if (s != null) {
                s.add(soundId);
            }
        }
    }

    /**
     * THE PARTICLE-CAPTURE WINDOW (2026-11-05 — the PARTICLE-STREAM channel,
     * DIAGNOSTIC-first, rides the same packet-capture mixin as the sound
     * window): armed by the particle gallery command, recorded by the
     * sound-packet capture mixin's particle branch (every outgoing
     * ClientboundLevelParticlesPacket's particle-type id, in arrival order),
     * disarmed by the command which then owns the collected stream.
     * Server-thread confined: the RCON command and the packet broadcast run
     * on the same thread, so a plain static field is the whole contract.
     */
    public static final class ParticleCapture {
        private static List<String> stream = null;

        /** Arms the capture and returns the list to pass back to end(). */
        public static List<String> begin() {
            List<String> fresh = new ArrayList<>();
            stream = fresh;
            return fresh;
        }

        /** Disarms and returns the recorded stream (ordered particle ids). */
        public static List<String> end(List<String> expected) {
            stream = null;
            return expected;
        }

        /** Called by the sound-packet mixin's particle branch; no-op while disarmed. */
        public static void record(String particleId) {
            List<String> s = stream;
            if (s != null) {
                s.add(particleId);
            }
        }
    }

    // --- gui gallery (the MENU channel census, 2026-11-04 — DIAGNOSTIC
    // --- first: one menu per page, the open attempt IS the measurement)

    /**
     * The universal GUI gallery: EVERY {@link MenuType} registered by the
     * namespace, sorted by id, ONE per page — the server opens the menu for
     * the gallery viewer ({@code cimviewer}, the rig player both legs name)
     * through the vanilla {@code openMenu} surface with an empty extra-data
     * buffer, and the client screenshot shows whatever rendered. Menus that
     * need their block context (machines read the BE from the extra data)
     * throw on the empty buffer — a recorded note (open-failed:&lt;class&gt;),
     * identical on both stacks, never a silent skip. Menus that open without
     * context render on the truth client (which HAS the mod) — the pixel
     * surface the ours leg must match.
     *
     * <p>HONESTY: the c-imagine pack channel has no menu translation
     * (verified 2026-11-04), so the ours replay records nochannel per row
     * until the channel exists — the join reports it, DIAGNOSTIC-first.
     */
    private static int galleryGui(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "gui page entry");
        writeGalleryIndex(level, namespace);
        String guiStem = "gallery-gui-" + namespace + "-p" + page;

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.MENU.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) entries.add(id);
        }

        LOGGER.info("[cimscene] gallery-gui begin ns={} page={} menus={} cellsPerPage=1",
                namespace, page, entries.size());
        if (page >= entries.size()) {
            writeDoneMarkerFile(guiStem, true);
            LOGGER.info("[cimscene] gallery-gui done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        ResourceLocation id = entries.get(page);

        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tmenu\tnote\n");
        int placed = 0, failed = 0;
        ServerPlayer viewer = level.getServer().getPlayerList().getPlayerByName("cimviewer");
        if (viewer == null) {
            // the rig player is the contract of the CI legs (both name it
            // cimviewer); a no-viewer page is a NAMED row, not a silence
            failed++;
            tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\tno-viewer\n");
            LOGGER.info("[cimscene] gallery-gui skip ns={} page={} reason=no-viewer menu={}",
                    namespace, page, id);
        } else {
            viewer.closeContainer();   // the previous page's screen, if any
            try {
                MenuType<?> type = BuiltInRegistries.MENU.get(id);
                // empty extra data: factories that read block/menu context
                // throw here (recorded honestly); context-free menus open
                net.minecraft.network.RegistryFriendlyByteBuf extra = new net.minecraft.network.RegistryFriendlyByteBuf(
                        Unpooled.buffer(), viewer.server.registryAccess());
                viewer.openMenu(new SimpleMenuProvider(
                        (windowId, inv, player) -> type.create(windowId, inv, extra),
                        Component.literal(id.toString())));
                placed++;
                tsv.append("cell\t").append(GALLERY_X0).append('\t')
                        .append(GALLERY_Y0).append('\t').append(GALLERY_Z)
                        .append('\t').append(id).append("\tok\n");
            } catch (Throwable t) {
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id)
                        .append("\topen-failed:").append(t.getClass().getName()).append('\n');
                LOGGER.info("[cimscene] gallery-gui skip ns={} page={} reason=open-failed "
                                + "menu={} err={}", namespace, page, id, t.toString());
            }
        }
        String manifest = "gallery-gui-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-gui manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-gui done ns={} page={} cells={} failed={} menu={}",
                namespace, page, placed, failed, id);
        writeDoneMarkerFile(guiStem, false);
        return placed;
    }

    // --- biome-tint gallery (the BOP P1 channel proof surface, 2026-11-04
    // --- — DIAGNOSTIC first; one biome per page, the vanilla /fillbiome
    // --- surface, zero per-mod code)

    /**
     * The universal BIOME-TINT gallery: EVERY biome registered by the
     * namespace in the server's datapack biome registry, sorted by id, ONE
     * per page. The page places a fixed tint-sample rig on the wall (a
     * grass_block — the GRASS color channel — and an oak_leaves — the
     * FOLIAGE color channel) and re-biomes the wall region through the
     * vanilla {@code /fillbiome} command via the proven quiet dispatcher:
     * the biome at the camera applies its tint to those blocks on BOTH
     * stacks through the same client surface. Deterministic in the flat
     * seed world (no biome locating — the region IS the wall's own).
     *
     * <p>HONESTY: the ours leg does not load mods, so a modded biome id
     * fails the fillbiome replay (nochannel in the plugin's replay log) and
     * the shot shows the honestly untinted wall — the join names it.
     * DIAGNOSTIC-first: the P1 BOP tint channel is the proof surface, the
     * gate flips when measured stable.
     */
    private static int galleryBiome(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "biome page entry");
        writeGalleryIndex(level, namespace);
        String biomeStem = "gallery-biome-" + namespace + "-p" + page;

        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        List<ResourceLocation> ids = new ArrayList<>(biomes.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) entries.add(id);
        }

        LOGGER.info("[cimscene] gallery-biome begin ns={} page={} biomes={} cellsPerPage=1",
                namespace, page, entries.size());
        if (page >= entries.size()) {
            writeDoneMarkerFile(biomeStem, true);
            LOGGER.info("[cimscene] gallery-biome done ns={} page={} cells=0 failed=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        ResourceLocation id = entries.get(page);

        clearWall(level);
        // the tint sample rig: grass_block (grass color) + oak_leaves
        // (foliage color) on the wall's first cells — the fixed surface the
        // camera already frames
        level.setBlock(new BlockPos(GALLERY_X0, GALLERY_Y0, GALLERY_Z),
                Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        level.setBlock(new BlockPos(GALLERY_X0 + 1, GALLERY_Y0, GALLERY_Z),
                Blocks.OAK_LEAVES.defaultBlockState(), 3);
        // re-biome the wall region through the vanilla surface (the SAME
        // command grammar both stacks run; y values are accepted but the
        // biome applies column-wide)
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tbiome\tnote\n");
        int placed = 0, failed = 0;
        int rc = runQuiet(src, "fillbiome " + GALLERY_X0 + " " + GALLERY_Y0 + " "
                + (GALLERY_Z - 1) + " " + (GALLERY_X0 + GALLERY_COLS) + " "
                + (GALLERY_Y0 + GALLERY_ROWS) + " " + (GALLERY_Z + 1) + " " + id);
        if (rc > 0) {
            placed++;
            tsv.append("cell\t").append(GALLERY_X0).append('\t')
                    .append(GALLERY_Y0).append('\t').append(GALLERY_Z)
                    .append('\t').append(id).append("\tok\n");
        } else {
            failed++;
            tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\tfillbiome-failed\n");
            LOGGER.info("[cimscene] gallery-biome skip ns={} page={} reason=fillbiome-failed biome={}",
                    namespace, page, id);
        }
        String manifest = "gallery-biome-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-biome manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-biome done ns={} page={} cells={} failed={} biome={}",
                namespace, page, placed, failed, id);
        writeDoneMarkerFile(biomeStem, false);
        return placed;
    }

    // --- inventory gallery (round 2, 2026-11-04 — the GUI ITEMS surface,
    // --- DIAGNOSTIC-first: the open-inventory screen shot on both legs)

    /** The player inventory grid: 9 hotbar + 27 main = 36 items per page. */
    static final int INV_CELLS = 36;

    /**
     * The universal INVENTORY gallery: EVERY item of the namespace (the
     * same sorted registry walk the item gallery runs, {@value #INV_CELLS}
     * per page) staged into the RIG VIEWER's real player inventory —
     * hotbar.0..8 then inventory.9..35 — and SCREENSHOTTED as the open
     * inventory screen on BOTH legs. The scene writes ONLY the manifest
     * ({@code slot -> ns:id}; the same contract both legs read): the FILL
     * itself rides plain vanilla surfaces per leg, zero scene-mod code —
     * truth: {@code /item replace entity cimviewer <slot> with <real item>}
     * over RCON; ours: the plugin replay resolves the same manifest rows
     * to the pack's CMD carriers (the item channel's inventory view).
     * The client harness presses E (open) / ESC (close) around the shot.
     *
     * <p>What the join compares: the truth client renders the REAL modded
     * item models inside the vanilla inventory grid; the ours client
     * renders the pack's paper+CMD carriers in the same slots — the GUI
     * ITEMS channel proof surface. DIAGNOSTIC-first: the crop geometry is
     * computed from scale-3 GUI math and is FIRST-RUN-UNMEASURED
     * (scripts/cimscene/inv-diff.py takes the geometry as parameters so
     * the first run calibrates without code edits).
     */
    private static int galleryInv(CommandSourceStack src, String namespace, int page) {
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "inv page entry");
        writeGalleryIndex(level, namespace);

        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.ITEM.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> entries = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace) && !id.getPath().equals("air")) {
                entries.add(id);
            }
        }

        LOGGER.info("[cimscene] gallery-inv begin ns={} page={} items={} cellsPerPage={}",
                namespace, page, entries.size(), INV_CELLS);
        int from = page * INV_CELLS;
        if (from >= entries.size()) {
            writeDoneMarkerFile("gallery-inv-" + namespace + "-p" + page, true);
            LOGGER.info("[cimscene] gallery-inv done ns={} page={} cells=0 exhausted=1",
                    namespace, page);
            return 0;
        }
        int to = Math.min(from + INV_CELLS, entries.size());
        List<ResourceLocation> slice = entries.subList(from, to);

        StringBuilder tsv = new StringBuilder("slot\titem\n");
        for (int i = 0; i < slice.size(); i++) {
            // vanilla slot naming: 0..8 = hotbar.N, 9..35 = inventory.N
            String slot = i < 9 ? ("hotbar." + i) : ("inventory." + i);
            tsv.append(slot).append('\t').append(slice.get(i)).append('\n');
        }
        String manifest = "gallery-inv-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-inv manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-inv done ns={} page={} cells={} first={} last={}",
                namespace, page, slice.size(), slice.get(0), slice.get(slice.size() - 1));
        writeDoneMarkerFile("gallery-inv-" + namespace + "-p" + page, false);
        return slice.size();
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
     * The FINAL-REWRITE pass delay (12-as): a second settle runs this many
     * ticks AFTER pass 1 and rewrites the manifest with the final subject
     * states. The quarantine (and a subject's own self-discard) can fire
     * AFTER pass 1 — measured gallery #30: create:potato_projectile was
     * alive at pass 1 (row ok) and quarantined at ~+48 ticks, so the
     * manifest claimed content the shot no longer shows and the gate was
     * blind (empty-vs-empty). +40 ticks after pass 1 = ~+80 ticks after the
     * command — still inside the CI shot window (done marker + 4 s) but
     * after the measured first-bad-tick deaths. No done marker on pass 2:
     * the page's readiness was already signalled.
     */
    static final int ENTITY_FINAL_TICKS = 40;

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
     *   <li>SERIES OWNERSHIP (measured #114): an entry whose classify-kind
     *       (mob / non-Mob=dyn / large Mob) belongs to ANOTHER series is a
     *       named {@code skip} row ({@code note=wrong-series:<kind>}) in the
     *       CURRENT series' manifest — a namespace whose every entity is
     *       non-Mob (create) must never produce a silent header-only mobs
     *       manifest. Not an absence note: the owning series carries the
     *       coverage;</li>
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
        setGalleryFrozen(level.getServer(), false, "entity page entry");
        // Every command (the exhausted early return included) supersedes the
        // previous page's pending settle: its subjects are about to be
        // discarded, and a late pass must not relabel them dissolved.
        final int gen = ++entityPageGen;
        writeGalleryIndex(level, namespace);
        int swept = sweepGalleryDrops(level);
        if (swept > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep ns={} page={} removed={}", namespace, page, swept);
        }
        String entityStem = "gallery-entity" + seriesTag(series) + "-" + namespace + "-p" + page;

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
            writeDoneMarkerFile(entityStem, true);
            LOGGER.info("[cimscene] gallery-entity{} done ns={} page={} cells=0 dyn=0 "
                    + "skipped_no_instance=0 skipped_wrong_series=0 failed=0 dissolved=0 exhausted=1",
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
        // SKIPPED rows (measured run 41188197904 / gallery #25: a modded
        // entity threw inside the configure+addFreshEntity body — the whole
        // command died, the settle TickTask was never scheduled, the page
        // never got its done marker and the walk burned). Two contracts:
        // 1) one bad subject must never kill the page — it becomes a NAMED
        // skip row in the manifest (note=no-instance | failed-create:<t> |
        // failed:<t>) and the walk continues;
        // 2) the row is the honesty channel — the pixel join alone would
        // see empty-vs-empty and stay silent; the coverage gate turns the
        // named notes RED (content absent from the picture is not green).
        final java.util.Map<Integer, String> skipNotes = new java.util.HashMap<>();
        int noInstance = 0, wrongSeries = 0, failed = 0;
        // 14-ak CLIENT-CRASH BAN READ (at the start of every entity command):
        // a type on the ban list never spawns — a named skip row
        // (note=client-crash-banned) mirrors the no-instance machinery
        // exactly, so a re-run of a crashed page is idempotent and the
        // killed page stays honest on BOTH parity legs.
        final java.util.Map<String, String> crashBan = readEntityCrashBan();
        int bannedPre = 0;
        for (int i = 0; i < slice.size(); i++) {
            ResourceLocation id = slice.get(i);
            int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
            if (crashBan.containsKey(id.toString())) {
                bannedPre++;
                skipNotes.put(i, "client-crash-banned");
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                                + "reason=client-crash-banned entity={}",
                        namespace, page, from + i, id);
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            Entity e = null;
            try {
                e = type.create(level);
            } catch (Throwable t) {
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                                + "reason=failed-create entity={} err={}",
                        namespace, page, from + i, id, t.toString());
                skipNotes.put(i, "failed-create:" + t.getClass().getName());
            }
            if (e == null) {
                noInstance++;
                // a type that cannot be instantiated on the truth stack is
                // content that never reaches ANY series' picture — the named
                // row (not a bare counter) is what the gate reads
                skipNotes.putIfAbsent(i, "no-instance");
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                        + "reason=no-instance entity={}", namespace, page, from + i, id);
                continue;
            }
            boolean isMob = e instanceof Mob;
            String kind = !isMob ? "dyn"
                    : (e.getBbWidth() <= 2.0f && e.getBbHeight() <= 2.0f ? "mobs" : "large");
            if (!kind.equals(series)) {
                wrongSeries++;
                // THE ROW IS THE HONESTY CHANNEL (measured gallery #114): a
                // wrong-series subject belongs to ANOTHER series' gate, but
                // the CURRENT series' manifest must still NAME it — the
                // coverage-gate contract ("wrong-series entries are recorded
                // skip rows, not coverage") said so and the walk never did.
                // create registers nine entities, every one non-Mob, so its
                // mobs manifest was written header-only and the mod-ns mobs
                // joins read as silent cells=0 GREENs. note=wrong-series:<kind>
                // is NOT an absence note (the gate keeps it green — the
                // owning series carries the coverage); the pixel join, fed
                // by the same manifest, surfaces the named rows.
                skipNotes.put(i, "wrong-series:" + kind);
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                        + "reason=wrong-series kind={} entity={}", namespace, page,
                        from + i, kind, id);
                continue;
            }
            try {
                e.setNoGravity(true);
                e.setInvulnerable(true);
                e.moveTo(GALLERY_X0 + col * ENTITY_PITCH + 0.5,
                        GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z + 0.5,
                        180.0f, 0.0f);
                // moveTo only sets yRot; the client takes body and head from
                // yHeadRot, so a frozen mob would stand with its back to the
                // camera while the ours leg's setRotation turns it around
                e.setYHeadRot(180.0f);
                e.setYBodyRot(180.0f);
                e.addTag(GALLERY_TAG);
                if (isMob) {
                    // NoAI: no movement, no look-at, no AI despawn logic — a
                    // still picture. Persistence: nothing despawns the subject
                    // between the settle pass and the shot.
                    m_setNoAi(e);
                }
                level.addFreshEntity(e);
            } catch (Throwable t) {
                failed++;
                skipNotes.put(i, "failed:" + t.getClass().getName());
                LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                                + "reason=failed entity={} err={}",
                        namespace, page, from + i, id, t.toString());
                // best-effort: if the entity half-entered the level before
                // the throw, discard it — the cell stays empty and NAMED
                try {
                    e.discard();
                } catch (Throwable ignore) {
                    // a discard failure cannot matter — the row is already named
                }
                continue;
            }
            spawned.add(e);
            spawnedIdx.add(i);
        }

        if (bannedPre > 0) {
            LOGGER.info("[cimscene] gallery-entity{} ban-skip ns={} page={} count={} (the entity-crash-ban "
                            + "file holds types that killed the real client — they are named skip rows this run)",
                    seriesTag(series), namespace, page, bannedPre);
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
        final int fFailed = failed;
        final java.util.Map<Integer, String> fSkipNotes = skipNotes;
        final int fFrom = from;
        final String fSeries = series;
        runAfterTicks(level.getServer(), ENTITY_SETTLE_TICKS, () -> settleGalleryEntity(
                fLevel, fNs, fPage, fSlice, spawned, spawnedIdx,
                fNoInstance, fWrongSeries, fFailed, fSkipNotes,
                fFrom, fSeries, gen, false));
        return spawned.size();
    }

    /** Bumped by every entity gallery command; a settle pass holding an older value is superseded. */
    private static int entityPageGen;

    private record DelayedTask(int dueTick, Runnable task) {}

    /** Server-thread only, like every gallery command and settle pass. */
    private static final List<DelayedTask> DELAYED_TASKS = new ArrayList<>();

    /**
     * Runs {@code task} on the server thread after {@code ticks} REAL ticks.
     * {@code server.tell(new TickTask(now + n, ...))} is NOT a delay:
     * MinecraftServer.shouldRun is {@code getTick() + 3 < tickCount || haveTime()},
     * so on an idle server the task runs at once (measured gallery 41880239869:
     * begin, done and settle-final in the same second, the client death one
     * tick later and never seen by the settle pass).
     */
    private static void runAfterTicks(net.minecraft.server.MinecraftServer server, int ticks, Runnable task) {
        DELAYED_TASKS.add(new DelayedTask(server.getTickCount() + ticks, task));
    }

    private void onServerTickPost(ServerTickEvent.Post event) {
        if (DELAYED_TASKS.isEmpty()) {
            return;
        }
        int now = event.getServer().getTickCount();
        List<DelayedTask> due = new ArrayList<>();
        DELAYED_TASKS.removeIf(t -> t.dueTick() <= now && due.add(t));
        for (DelayedTask t : due) {
            try {
                t.task().run();
            } catch (RuntimeException ex) {
                LOGGER.error("[cimscene] delayed task failed: {}", ex.toString());
            }
        }
    }

    /** Receipt flags: the first client-side unfreeze logs once per join. */
    private static boolean CLIENT_UNFREEZE_SEEN = false;
    private static boolean MCMETA_PIN_LOGGED = false;
    private static boolean MCMETA_PIN_BROKEN = false;
    /** Reflection caches (resolved on the first pin attempt, reused after). */
    private static java.lang.reflect.Field TM_BY_PATH;
    private static java.lang.reflect.Field ATLAS_ANIMATED;

    /**
     * 14-dk THE CLIENT KEEPS TICKING — the freeze stays server-authoritative.
     * The vanilla freeze broadcast ({@code ClientboundTickingStatePacket})
     * gates the JOINED CLIENT's whole tick on the runsNormally flag — and the
     * frozen client destroyed the walk's shots (measured run #308, the 14-di
     * freeze run: NET -303 = FIXED 22 / NEW 325, 747 RED / 28044 states =
     * 97.34% vs #307's 98.42%): the frozen client never finished the page's
     * mesh catch-up (llvmpipe needs seconds per page; the freeze landed ~50ms
     * after placement at tick rate 1000 — the shots carry half-built sections),
     * the placement/break particles NEVER died (particleEngine.tick gated —
     * they hang in the shots as frozen black squares over the wall, the
     * biomesoplenty p43 fence-gate page measured 22/25 RED on them alone),
     * the client-side render-state lerps (the sliding doors' 13/16 slide, the
     * nixie text fades) froze mid-animation against the pack's settled
     * composites, and the gate's t0/t1 self-pairs became identical so the
     * ANIMATED excuse died for the dyn rows (the fluid class lost its escape).
     *
     * <p>The instrument is the SERVER's animation state only: BE tickers,
     * scheduled block/fluid ticks, entity AI and the day clock gate on the
     * SERVER's runsNormally and stay pinned (the churn band stays dead — the
     * fluix_wall family's 12 states measured FIXED on #308). The client's own
     * tick-rate manager is forced unfrozen every tick, so meshing, particles,
     * the render lerps and the mcmeta residual behave exactly as they did in
     * the measured-good pre-freeze walks. The client's manager never
     * broadcasts, so the local flip cannot leak back to the server, and the
     * broadcast packet fires only on a server-side flip (once per page) — the
     * override re-arms every tick it is received.
     */
    private void onClientTickPost(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) {
            CLIENT_UNFREEZE_SEEN = false;
            MCMETA_PIN_LOGGED = false;
            return;
        }
        if (mc.level.tickRateManager().isFrozen()) {
            mc.level.tickRateManager().setFrozen(false);
            if (!CLIENT_UNFREEZE_SEEN) {
                CLIENT_UNFREEZE_SEEN = true;
                LOGGER.info("[cimscene] 14-dk client keeps ticking (the freeze stays server-authoritative)");
            }
        }
        pinMcmetaFrameZero(mc);
    }

    /**
     * 14-dl THE MCMETA FRAME-0 PIN — the client half of the animation
     * instrument. The 14-di server freeze stopped the BE tickers and the
     * 14-dk client-unfreeze kept the client's meshing/particles/lerps alive,
     * but the client's mcmeta sprite clocks still free-run: the sprite
     * tickers advance per client tick (TextureManager.tick ->
     * TextureAtlas.tick -> cycleAnimationFrames -> SpriteContents$Ticker
     * .tickAndUpload, verified on the 1.21.1 decompile), so every
     * mcmeta-animated surface (the fluid stills, the fluix_wall family, the
     * blaze flames, water/lava) shipped a DIFFERENT frame every run — the
     * named 14-di mcmeta residual, measured live as the 8 OURS-STATIC
     * fluix_wall pages (14-dk.1 receipts: truth_delta &gt; 0.35 while ours
     * stands) and the fluid-series giants whose self-pairs stay below the
     * gate while the truth-vs-ours ae measures 249K-1168K.
     *
     * <p>The pin walks the texture manager's atlas registry EVERY client
     * tick (post the textureManager.tick that cycleAnimationFrames runs in)
     * and, for every atlas whose animatedTextures list is still populated:
     * for each animated sprite, zeroes the inner ticker's frame/subFrame
     * counters and re-uploads the sprite's FIRST frame ({@code
     * TextureAtlasSprite#uploadFirstFrame}, the same call the stitch path
     * uses), then empties the atlas's animatedTextures list so
     * cycleAnimationFrames iterates nothing. The empty-list check makes the
     * steady state O(atlas count) per tick and the pin re-fires
     * automatically after a resource reload (which replaces the atlas
     * objects with fresh lists). The atlas content thereafter is frame 0,
     * bit-deterministic, matching what the pack composites carry BY
     * CONSTRUCTION (the 14-cf hosting ships the static frame-0 crop under
     * the sprite's own path, and the dump client's captures ride the same
     * pinned clocks) — the phase residual dies at the source instead of
     * being excused post-hoc. The ANIMATED self-pair excuse mostly
     * evaporates with it: a cell whose only motion was the mcmeta phase
     * measures a zero t0/t1 delta AND a near-zero truth-vs-ours ae.
     *
     * <p>Resource reloads replace the atlas objects (fresh lists) — the
     * per-tick empty-list check re-pins the new generation automatically.
     * Reflection targets (all verified on the 1.21.1 client-named jar):
     * TextureManager.byPath (private final Map), TextureAtlas.animatedTextures
     * (private List), the anonymous TextureAtlasSprite$Ticker wrapper's
     * captured fields (val$ticker: SpriteTicker, this$0: TextureAtlasSprite),
     * SpriteContents$Ticker.frame/subFrame (package-private ints). Any
     * reflection miss disables the pin once with a warn — the walk never
     * crashes on it. The ours (Paper/26.3) leg runs no scenemod and needs no
     * pin: its composites are static by construction.
     */
    private static void pinMcmetaFrameZero(net.minecraft.client.Minecraft mc) {
        if (MCMETA_PIN_BROKEN) {
            return;
        }
        try {
            if (TM_BY_PATH == null) {
                TM_BY_PATH = net.minecraft.client.renderer.texture.TextureManager.class
                        .getDeclaredField("byPath");
                TM_BY_PATH.setAccessible(true);
                ATLAS_ANIMATED = net.minecraft.client.renderer.texture.TextureAtlas.class
                        .getDeclaredField("animatedTextures");
                ATLAS_ANIMATED.setAccessible(true);
            }
            Object byPath = TM_BY_PATH.get(mc.getTextureManager());
            int atlases = 0;
            int sprites = 0;
            for (Object tex : ((java.util.Map<?, ?>) byPath).values()) {
                if (!(tex instanceof net.minecraft.client.renderer.texture.TextureAtlas atlas)) {
                    continue;
                }
                java.util.List<?> tickers = (java.util.List<?>) ATLAS_ANIMATED.get(atlas);
                if (tickers.isEmpty()) {
                    continue;
                }
                for (Object wrapper : tickers) {
                    // the anonymous TextureAtlasSprite.Ticker captures the
                    // inner SpriteContents$Ticker (val$ticker) and the sprite
                    // (this$0) as synthetic fields
                    Object inner = null;
                    net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = null;
                    for (java.lang.reflect.Field f : wrapper.getClass().getDeclaredFields()) {
                        f.setAccessible(true);
                        Object v = f.get(wrapper);
                        if (v instanceof net.minecraft.client.renderer.texture.SpriteTicker) {
                            inner = v;
                        } else if (v instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite s) {
                            sprite = s;
                        }
                    }
                    if (inner != null && sprite != null) {
                        java.lang.reflect.Field frame = inner.getClass().getDeclaredField("frame");
                        frame.setAccessible(true);
                        java.lang.reflect.Field subFrame = inner.getClass().getDeclaredField("subFrame");
                        subFrame.setAccessible(true);
                        frame.setInt(inner, 0);
                        subFrame.setInt(inner, 0);
                        sprite.uploadFirstFrame();
                        sprites++;
                    }
                }
                // the atlas stops ticking its sprites: the pinned frame-0
                // content stays on the GPU until the atlas object is replaced
                // (resource reload — the empty-list check re-pins it then)
                ATLAS_ANIMATED.set(atlas, java.util.List.of());
                atlases++;
            }
            if (atlases > 0 && !MCMETA_PIN_LOGGED) {
                MCMETA_PIN_LOGGED = true;
                LOGGER.info("[cimscene] 14-dl mcmeta frame-0 pin: {} atlases / {} animated sprites pinned (the sprite ticker stops at frame 0)", atlases, sprites);
            }
        } catch (Exception ex) {
            MCMETA_PIN_BROKEN = true;
            LOGGER.warn("[cimscene] 14-dl mcmeta frame-0 pin disabled (reflection miss: {})", ex.toString());
        }
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

    /**
     * THE DONE-MARKER FILE CHANNEL (13-c): the console/boot.log pipeline of
     * a quiet server can STALL — measured gallery #38: a quiet seed server
     * executed page 0 in 0.5s (the line's own timestamp) but the bytes sat
     * in the console pipeline for 30s and landed exactly when the CI wait
     * gave up; a busy server (a joined client) masks it with log volume.
     * The manifests were always FILES written synchronously by the walk —
     * the done marker gains the same channel: {@code <stem>.done} (or
     * {@code <stem>.exhausted} for the terminal page) written by
     * Files.writeString AT THE SAME CODE POINT as the LOGGER line. The CI
     * walks poll the file CONTENT (a wall-clock nonce, no mtime-granularity
     * races on re-walks). The boot.log lines stay: the coverage gate joins
     * their terminal markers, and humans grep them.
     */
    private static void writeDoneMarkerFile(String stem, boolean exhausted) {
        writeMarkerFile(stem + (exhausted ? ".exhausted" : ".done"));
    }

    private static void writeMarkerFile(String name) {
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(name),
                    String.valueOf(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] marker file write failed {}: {}", name, ioe.toString());
        }
    }

    /**
     * 14-di THE SETTLE FREEZE — the universal wobble instrument. The walk's
     * settle-shot loop demands two identical consecutive md5s ("the frame is
     * settled"), but the world kept ticking through it: the block walk runs at
     * tick rate 1000 (the packet-backlog drain), so between the done marker
     * and the client's shot the server advanced ~1000 ticks of BE-ticker
     * animation (the door slide/fold phase, the machine active states, the
     * nixie tickers), the scheduled block/fluid ticks kept running, and the
     * CLIENT's own clocks (the mcmeta frame counters, the particle engine,
     * the ambient animateTick, the client gameTime behind the scrolling
     * text) never stopped — the shipped frame's animation phase was whatever
     * the llvmpipe/poll timing produced, and the SAME page settled at a
     * DIFFERENT phase the next run (the measured churn band: gantry
     * END/START, fluix_wall, the chargepad pair; the #306→#307 pixel proof:
     * truth moved ONLY inside the animated machine cells while the pack
     * stood still). A state can only be honest-red or honest-green against a
     * frame the next run can reproduce.
     *
     * <p>The instrument is the VANILLA freeze, flipped server-side at the
     * settle pass's terminal point: {@code server.tickRateManager()}
     * .setFrozen(...). Verified against the 1.21.1 sources, one call pins
     * BOTH legs bit-deterministically — the server (BE tickers gate on
     * runsNormally in Level.tickBlockEntities, as do the scheduled block/
     * fluid ticks, block events, entity ticks and tickTime in
     * ServerLevel.tick) and every CLIENT joined to it (ServerTickRateManager
     * .setFrozen broadcasts ClientboundTickingStatePacket; the client gates
     * textureManager.tick — the mcmeta frame counters stop advancing —
     * particleEngine.tick, animateTick and tickTime on the same
     * runsNormally flag in Minecraft.tick). The walk's own machinery keeps
     * running while frozen: the server tick loop, RCON, connections and the
     * chunk source are ungated (chunk sending continues), the settle-shot
     * loop's {@code tp cimviewer} re-pin is a player teleport and players
     * never freeze (TickRateManager.isEntityFrozen). The settle pass itself
     * runs BEFORE the freeze (the fluid settle needs its ticking; the
     * falling-block retries finish first — the terminal pass freezes, a
     * superseded pass does not).
     *
     * <p>Scope of this slice: the BLOCK settle freeze only, and every
     * gallery command entry UNFREEZES first — the seed walk and the run
     * shards run blocks → entities → items → particles → fluids → sound →
     * biome → gui → inv on ONE server, and a freeze left behind by the last
     * block page would otherwise park the whole rest of the walk (its
     * settles and mob/entity spawns need ticking). Exhaustion writes no
     * settle pass and keeps the freeze — the next series' first command
     * lifts it. The run-side receipts: the ANIM liveness probe's t0/t1
     * frames on dyn pages now measure a zero delta on a frozen walk, and
     * the determinism probe's re-shot of page 0 must md5-match the original
     * (the same page frozen at the same settle phase).
     */
    private static void setGalleryFrozen(net.minecraft.server.MinecraftServer server, boolean frozen, String why) {
        try {
            if (server.tickRateManager().isFrozen() == frozen) {
                return;
            }
            server.tickRateManager().setFrozen(frozen);
            LOGGER.info("[cimscene] gallery freeze {} ({})", frozen ? "on" : "off", why);
        } catch (Throwable t) {
            LOGGER.error("[cimscene] gallery freeze switch failed ({}): {}", why, t.toString());
        }
    }

    // --- entity crash ban (14-ak client-crash containment) -----------------

    /**
     * The RUN-SCOPED client-crash ban file:
     * {@code c-imagine/scenes/entity-crash-ban.tsv}, one type per line,
     * {@code <entity id> TAB <reason>} (reason format:
     * {@code client-crash page=<p>}).
     *
     * <p>MEASURED (#84, run 41848055305, truth shard 0): during the
     * create-entity DYN walk the REAL client DIED — a bare modded (Create)
     * entity spawn carries spawn data the client cannot process
     * ({@code Contraption.readNBT} NPE: "contraption" is null on the
     * {@code create:contraption} spawn) and the frame became the
     * "Connection Lost" screen. EVERY truth shot after that point on the
     * shard was the disconnect screen — the two item pages joined 77/77
     * garbage RED each and gated the gallery. The ban is the containment:
     * a type that killed the client never spawns again (this run); the
     * coverage it loses is recorded as NAMED skip rows, never silent.
     *
     * <p>Vanilla-namespace types stay unbanned by the death detector — they
     * render fine on the stock client; the crash comes from modded spawn
     * data. Rerun safety: the ban-file read at the start of
     * {@link #galleryEntity} makes re-running the same page idempotent (the
     * killer types become skip rows on the retry and cannot kill again).
     * The file rides the meta artifact ({@code srv-wall/c-imagine/scenes/})
     * — it IS the audit trail for every page the walk refused to spawn.
     */
    static final String ENTITY_CRASH_BAN_FILE = "entity-crash-ban.tsv";

    private static Path entityCrashBanPath() {
        return Path.of("c-imagine", "scenes", ENTITY_CRASH_BAN_FILE);
    }

    /** Reads the ban file: id -> reason. Missing file = empty map. */
    private static java.util.Map<String, String> readEntityCrashBan() {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        Path file = entityCrashBanPath();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                int tab = line.indexOf('\t');
                String id = (tab >= 0 ? line.substring(0, tab) : line).trim();
                if (id.isEmpty()) continue;
                String reason = tab >= 0 ? line.substring(tab + 1).trim() : "";
                out.putIfAbsent(id, reason);
            }
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] entity-crash-ban read failed {}: {}", file, ioe.toString());
        }
        return out;
    }

    /**
     * Appends types to the ban file (id TAB reason), deduplicated against
     * the existing content — re-detection of the same killer type on a
     * later pass/page must not grow the file. Server-thread only (the
     * gallery commands and the settle TickTasks all run there).
     */
    private static void appendEntityCrashBan(List<String> ids, String reason) {
        Path file = entityCrashBanPath();
        try {
            Files.createDirectories(file.getParent());
            java.util.Set<String> existing = new HashSet<>();
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    int tab = line.indexOf('\t');
                    existing.add((tab >= 0 ? line.substring(0, tab) : line).trim());
                }
            }
            StringBuilder out = new StringBuilder();
            int added = 0;
            for (String id : ids) {
                if (!existing.add(id)) continue;
                out.append(id).append('\t').append(reason).append('\n');
                added++;
            }
            if (added == 0) {
                return;
            }
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            LOGGER.info("[cimscene] entity-crash-ban appended {} type(s) reason={} : {}",
                    added, reason, ids);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] entity-crash-ban write failed {}: {}", file, ioe.toString());
        }
    }

    /** Settle pass body: re-read spawns, emit the settled manifest + marker.
     *  Pass 2 ({@code finalPass}) rewrites the manifest with the final
     *  subject states (quarantines/self-discards after pass 1 reclassify to
     *  dissolved) and writes NO done marker. */
    private static void settleGalleryEntity(ServerLevel level, String namespace, int page,
                                            List<ResourceLocation> slice,
                                            List<Entity> spawned, List<Integer> spawnedIdx,
                                            int noInstance, int wrongSeries, int failed,
                                            java.util.Map<Integer, String> skipNotes,
                                            int from, String series, int gen, boolean finalPass) {
        String tag = seriesTag(series);
        if (gen != entityPageGen) {
            LOGGER.info("[cimscene] gallery-entity{} settle superseded ns={} page={} finalPass={}",
                    tag, namespace, page, finalPass);
            return;
        }
        String[] rowStr = new String[slice.size()];
        int cells = 0, dyn = 0, dissolved = 0;
        // 14-ak SERVER-SIDE DEATH DETECTION: the settle pass runs 40 ticks
        // after the spawn command. If the server has ZERO players online,
        // the truth client just crashed mid-page (measured #84: a modded
        // Create entity spawn kills the real client — Contraption.readNBT
        // NPE on bare ContraptionEntity spawn data; the frame becomes the
        // "Connection Lost" screen and every later shot on the shard is
        // garbage). Append EVERY non-vanilla-namespace type PLACED on this
        // page to the ban file (reason client-crash page=<p>) and write
        // skip rows for them in the CURRENT manifest; vanilla-namespace
        // types stay unbanned (they render fine on the stock client — the
        // crash comes from modded spawn data). Both settle passes run this
        // (a death the network thread noticed only between them is still
        // caught); already-banned indices dedup to an empty append and no
        // second log. NOTE: a deliberately client-less walk (the seed
        // manifest-generation pass) trips this too — by design its only
        // effect is the same honest skip-row accounting, and the ours-leg
        // replay skips non-cell rows identically, so the join loses nothing.
        if (level.getServer().getPlayerCount() == 0) {
            List<String> banned = new ArrayList<>();
            for (int j = 0; j < spawnedIdx.size(); j++) {
                int idx = spawnedIdx.get(j);
                ResourceLocation id = slice.get(idx);
                if (id.getNamespace().equals("minecraft")) continue;
                if ("client-crash-banned".equals(skipNotes.get(idx))) continue;
                skipNotes.put(idx, "client-crash-banned");
                banned.add(id.toString());
            }
            if (!banned.isEmpty()) {
                appendEntityCrashBan(banned, "client-crash page=" + page);
                LOGGER.info("[cimscene] gallery-entity{} client death detected ns={} page={} banned={}",
                        tag, namespace, page, banned);
            }
        }
        for (int j = 0; j < spawned.size(); j++) {
            Entity e = spawned.get(j);
            int i = spawnedIdx.get(j);
            ResourceLocation id = slice.get(i);
            int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
            // 14-ak: a death-banned cell is NAMED even though the entity is
            // (still) alive server-side — the client that would have shot it
            // is dead, and a re-run of the page must re-read it as a skip.
            if (skipNotes.containsKey(i)) {
                rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t0\t" + skipNotes.get(i);
                continue;
            }
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
            if (isMob) {
                // THE AMBIENT-HOPPER RE-PIN (measured: run 42255431005's
                // rabbit cell — NoAI freezes AI but the spawn-packet yaw
                // race left the truth rabbit facing AWAY from the locked
                // camera while ours faced it; one cell, ae 436k). Re-assert
                // the pose HERE, inside the settle pass that runs right
                // before the page's done marker + shot — no mob can turn
                // between this and the picture.
                Mob m = (Mob) e;
                m.setYRot(180.0f);
                m.setYHeadRot(180.0f);
                m.setYBodyRot(180.0f);
            }
            if (!isMob) dyn++;
            cells++;
            rowStr[i] = "cell\t" + (GALLERY_X0 + col * ENTITY_PITCH) + '\t'
                    + (GALLERY_Y0 + row * ENTITY_PITCH)
                    + '\t' + GALLERY_Z + '\t' + id + '\t' + (isMob ? 0 : 1) + "\tok";
        }
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tentity\tdyn\tnote\n");
        for (int i = 0; i < slice.size(); i++) {
            if (rowStr[i] == null && skipNotes.containsKey(i)) {
                // the spawn failed BEFORE or DURING placement — the row must
                // be VISIBLE in the manifest (named), not silent: the join
                // sees a skip, the coverage gate turns it into a named RED
                rowStr[i] = "skip\t-1\t-1\t-1\t" + slice.get(i) + "\t0\t"
                        + skipNotes.get(i);
            }
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
        if (finalPass) {
            // pass 2: the manifest is rewritten, readiness was already
            // signalled — no done marker, no further scheduling (a chain
            // of passes would never terminate and would pollute the CI's
            // count-based wait with phantom markers)
            LOGGER.info("[cimscene] gallery-entity{} settle-final ns={} page={} cells={} dyn={} "
                            + "dissolved={} (manifest rewritten inside the shot window; "
                            + "no done marker)",
                    tag, namespace, page, cells, dyn, dissolved);
            // not a readiness signal: only for a walk that must not touch the
            // subjects until this rewrite has read them (the determinism probe)
            writeMarkerFile("gallery-entity" + tag + "-" + namespace + "-p" + page + ".settled");
            return;
        }
        LOGGER.info("[cimscene] gallery-entity{} done ns={} page={} cells={} dyn={} "
                        + "skipped_no_instance={} skipped_wrong_series={} failed={} dissolved={} first={} last={}",
                tag, namespace, page, cells, dyn, noInstance, wrongSeries,
                failed, dissolved, slice.get(0), slice.get(slice.size() - 1));
        // THE FILE CHANNEL of the done marker (same code point as the
        // LOGGER line): the settle pass runs 40 ticks AFTER the RCON command
        // returned, so the console/boot.log line is the only pass-1 signal
        // — unless the file exists. The CI walk polls this file.
        writeDoneMarkerFile("gallery-entity" + tag + "-" + namespace + "-p" + page, false);
        // FINAL REWRITE PASS (12-as): catch the deaths that happen after
        // pass 1 — the tick quarantine, a modded subject's own
        // self-discard — while still inside the CI shot window, and rewrite
        // the manifest so the gate reads the state the SHOT will show.
        runAfterTicks(level.getServer(), ENTITY_FINAL_TICKS, () -> settleGalleryEntity(
                level, namespace, page, slice, spawned, spawnedIdx,
                noInstance, wrongSeries, failed, skipNotes,
                from, series, gen, true));
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
