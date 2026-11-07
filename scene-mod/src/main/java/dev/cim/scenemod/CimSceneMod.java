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
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
            ScreenArt.init();
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
        if (GUI_JOINED != null && !(event.getEntity() instanceof net.minecraft.world.entity.player.Player)) {
            GUI_JOINED.add(event.getEntity());
        }
        if (!(event.getEntity() instanceof ItemEntity item)) return;
        // A cleared rig drops whatever its menus held, and the viewer two blocks away would
        // pick it up: the next page's inventory would no longer be the one the harness set.
        if (GUI_CLEARING) {
            event.setCanceled(true);
            return;
        }
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
                        .then(Commands.literal("gui-scan")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(ctx -> galleryGuiScan(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "namespace")))))
                        // Closes the viewer's menu, so whatever it held is back in the inventory
                        // before the harness resets that inventory for the next page.
                        .then(Commands.literal("gui-settle")
                                .then(Commands.argument("namespace", StringArgumentType.word())
                                        .executes(ctx -> galleryGuiSettle(ctx.getSource().getLevel(),
                                                StringArgumentType.getString(ctx, "namespace")))))
                        .then(Commands.literal("gui-close")
                                .executes(ctx -> {
                                    ServerPlayer viewer = ctx.getSource().getServer().getPlayerList()
                                            .getPlayerByName("cimviewer");
                                    if (viewer != null) {
                                        viewer.closeContainer();
                                    }
                                    return viewer == null ? 0 : 1;
                                }))
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
     * </ol>
     */
    private static ResolvedOptions resolveParticleOptions(ParticleType<?> type) {
        if (type instanceof ParticleOptions simple) {
            return new ResolvedOptions(simple, null);
        }
        try {
            DataResult<? extends ParticleOptions> res = type.codec().codec()
                    .parse(NbtOps.INSTANCE, new CompoundTag());
            ParticleOptions opts = res.result().orElse(null);
            if (opts != null) {
                return new ResolvedOptions(opts, null);
            }
            String err = res.error().map(e -> e.message()).orElse("empty-decode");
            return new ResolvedOptions(null, "no-simple-options:" + err);
        } catch (Throwable t) {
            return new ResolvedOptions(null,
                    "no-simple-options:" + t.getClass().getName());
        }
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
     *       PLACED, its CONTENTS ARE NOT FILLED (the fill API is
     *       mod-specific and unverified from this workspace — the manifest
     *       note says so; an open question, not a claimed surface);</li>
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
        String manifest = "gallery-fluid-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-fluid manifest write failed {}: {}", manifest, ioe.toString());
        }
        LOGGER.info("[cimscene] gallery-fluid done ns={} page={} cells={} failed={} first={} last={}",
                namespace, page, placed, failed, slice.get(0), slice.get(slice.size() - 1));
        writeDoneMarkerFile(fluidStem, false);
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
     * rides the manifest's extra column — deterministic on the truth
     * server (create is loaded there); the ours leg renders the tank BLOCK
     * through the pack channel only (the level is BE-drawn — dyn class,
     * no red verdict), so the note is the honest pair: truth shows a filled
     * tank, ours shows the same tank's static model.
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

    /** Per namespace: which block or item opens each menu, found by trying them all. */
    private static final Map<String, Map<ResourceLocation, String>> GUI_OPENERS = new HashMap<>();
    /** Every block and item seen opening a menu: dyed variants share one menu. */
    private static final Map<ResourceLocation, List<String>> GUI_ALL_OPENERS = new HashMap<>();
    private static final int GUI_SETTLE_TICKS = 10;
    private static final int GUI_PROBE_XZ = 20000;
    private static net.minecraft.world.entity.Entity guiEntity;

    /** Inside menu reach, in front of the viewer, so {@code stillValid} keeps the screen up. */
    private static BlockPos guiRigPos(ServerPlayer viewer) {
        return viewer.blockPosition().relative(viewer.getDirection(), 2);
    }

    private static ResourceLocation guiOpenMenu(ServerPlayer viewer) {
        if (viewer.containerMenu == viewer.inventoryMenu) {
            return null;
        }
        try {
            return BuiltInRegistries.MENU.getKey(viewer.containerMenu.getType());
        } catch (UnsupportedOperationException noType) {
            return null;
        }
    }

    /** Records what would open without telling any client, whose block is about to vanish. */
    private static final class GuiProbe extends ServerPlayer {
        ResourceLocation seen;
        String candidate;
        String seenClass;
        String seenTitle;
        List<String> seenSlots;

        /**
         * A real ServerPlayer, not a FakePlayer: mods refuse fake players on purpose, to
         * stop automation opening menus. Only its network handler is borrowed.
         */
        GuiProbe(ServerLevel level) {
            super(level.getServer(), level, PROFILE, net.minecraft.server.level.ClientInformation.createDefault());
            this.connection = new net.neoforged.neoforge.common.util.FakePlayer(level, PROFILE).connection;
        }

        private static final com.mojang.authlib.GameProfile PROFILE = new com.mojang.authlib.GameProfile(
                java.util.UUID.nameUUIDFromBytes("cimscene-gui".getBytes(StandardCharsets.UTF_8)), "cimscene-gui");

        private java.util.OptionalInt record(net.minecraft.world.MenuProvider provider) {
            try {
                net.minecraft.world.inventory.AbstractContainerMenu menu = provider.createMenu(1, getInventory(), this);
                seen = BuiltInRegistries.MENU.getKey(menu.getType());
                seenClass = menu.getClass().getName();
                seenTitle = provider.getDisplayName().getString();
                seenSlots = new ArrayList<>();
                for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                    seenSlots.add(slot.x + "\t" + slot.y + "\t"
                            + (slot.container == getInventory() ? "player" : "menu") + "\t"
                            + slot.getContainerSlot());
                }
            } catch (Throwable t) {
                LOGGER.info("[cimscene] gallery-gui probe createMenu threw {}", t.toString());
            }
            return java.util.OptionalInt.empty();
        }

        @Override
        public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider) {
            return record(provider);
        }

        @Override
        public java.util.OptionalInt openMenu(net.minecraft.world.MenuProvider provider,
                java.util.function.Consumer<net.minecraft.network.RegistryFriendlyByteBuf> extra) {
            return record(provider);
        }
    }

    /** Prepares what the opener acts on. Openers read {@code [sneak+][place+]kind:id}. */
    private static void guiPlace(ServerLevel level, ServerPlayer player, BlockPos rig, String opener) {
        String plain = opener.replace("sneak+", "");
        if (plain.startsWith("cube")) {
            guiBuildCube(level, player, rig, plain);
        } else if (plain.startsWith("block:")) {
            ResourceLocation id = ResourceLocation.parse(plain.substring("block:".length()));
            level.setBlock(rig, BuiltInRegistries.BLOCK.get(id).defaultBlockState(), 3);
        } else if (plain.startsWith("place+item:") || plain.startsWith("hang+item:")) {
            // Parts and placed entities exist only through their item's own placement code.
            boolean hang = plain.startsWith("hang+item:");
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(
                    ResourceLocation.parse(plain.substring(plain.indexOf(':') + 1))));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockPos against = hang ? rig.north() : rig.below();
            Direction face = hang ? Direction.SOUTH : Direction.UP;
            if (hang) {
                level.setBlock(against, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(against.above(), Blocks.STONE.defaultBlockState(), 3);
            }
            GUI_JOINED = new ArrayList<>();
            try {
                stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(against).relative(face, 0.5), face, against, false)));
            } finally {
                guiEntity = guiJoined(guiEntity);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } else if (plain.startsWith("use+item:")) {
            String[] pair = plain.substring("use+item:".length()).split("@", 2);
            level.setBlock(rig, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(pair[1])).defaultBlockState(), 3);
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(pair[0])));
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            BlockHitResult hit = new BlockHitResult(new Vec3(rig.getX() + 0.5, rig.getY() + 1, rig.getZ() + 0.5),
                    Direction.UP, rig, false);
            BlockState state = level.getBlockState(rig);
            GUI_JOINED = new ArrayList<>();
            net.minecraft.world.ItemInteractionResult result;
            net.minecraft.world.InteractionResult used = null;
            try {
                result = state.useItemOn(stack, level, player, InteractionHand.MAIN_HAND, hit);
                if (!result.consumesAction()) {
                    used = stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));
                }
            } finally {
                guiEntity = guiJoined(guiEntity);
            }
            if (PAIR_USES_LOGGED < 400 && (result.consumesAction() || (used != null && used.consumesAction()))) {
                PAIR_USES_LOGGED++;
                LOGGER.info("[cimscene] gallery-gui pair use {} block={} item={}", plain, result, used);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            // Whatever the use put into the world is what the player clicks next.
        } else if (plain.startsWith("entity:")) {
            net.minecraft.world.entity.Entity entity = BuiltInRegistries.ENTITY_TYPE.get(
                    ResourceLocation.parse(plain.substring("entity:".length()))).create(level);
            if (entity != null) {
                entity.moveTo(rig.getX() + 0.5, rig.getY(), rig.getZ() + 0.5, 0f, 0f);
                level.addFreshEntity(entity);
                // One tick of its own first: an entity that cannot live where a bare spawn
                // puts it (a robit with no charge pad to call home) removes itself, and its
                // menu has to come from the way a player really places it, the item pairs.
                entity.tick();
                GUI_SPAWNED.add(entity);
                guiEntity = entity.isRemoved() ? null : entity;
            }
        }
    }

    /** Entities that joined the level while a use ran; null when no use is running. */
    private static List<net.minecraft.world.entity.Entity> GUI_JOINED;
    /** Everything a use spawned, so the rig can be cleared of it. */
    private static final List<net.minecraft.world.entity.Entity> GUI_SPAWNED = new ArrayList<>();

    /**
     * What the use just put into the world, from the join event. A box query cannot see it:
     * {@code getEntities} reads only sections whose entity data finished loading, and the rig's
     * chunk is forced in the same tick, so a robit the item just placed is in the level but
     * not in any visible section.
     */
    private static net.minecraft.world.entity.Entity guiJoined(net.minecraft.world.entity.Entity fallback) {
        List<net.minecraft.world.entity.Entity> joined = GUI_JOINED;
        GUI_JOINED = null;
        if (joined == null || joined.isEmpty()) {
            return fallback;
        }
        GUI_SPAWNED.addAll(joined);
        return joined.get(joined.size() - 1);
    }

    private static boolean guiOpened(ServerPlayer player) {
        return player instanceof GuiProbe probe ? probe.seen != null : guiOpenMenu(player) != null;
    }

    /**
     * Clicks the way a persistent player would: every face of a block, because some open
     * only from one side, and twice, because some items claim an owner on the first use.
     */
    private static void guiAct(ServerLevel level, ServerPlayer player, BlockPos rig, String opener) {
        boolean block = opener.contains("block:") || opener.contains("place+item:") || opener.contains("use+item:")
                || opener.contains("cube");
        Direction[] faces = block
                ? new Direction[]{Direction.UP, Direction.SOUTH, Direction.NORTH, Direction.EAST, Direction.WEST}
                : new Direction[]{Direction.UP};
        for (int pass = 0; pass < 2; pass++) {
            for (Direction face : faces) {
                guiActOnce(level, player, rig, opener, face);
                if (guiOpened(player)) {
                    return;
                }
            }
        }
    }

    /** One right click, in the same order the game tries things. */
    private static void guiActOnce(ServerLevel level, ServerPlayer player, BlockPos rig, String opener,
                                   Direction face) {
        boolean sneak = opener.startsWith("sneak+");
        String plain = opener.replace("sneak+", "");
        player.setShiftKeyDown(sneak);
        try {
            if (guiEntity != null && plain.contains("+item:")) {
                if (!guiEntity.interactAt(player, Vec3.ZERO, InteractionHand.MAIN_HAND).consumesAction()) {
                    guiEntity.interact(player, InteractionHand.MAIN_HAND);
                }
            } else if (plain.startsWith("block:") || plain.startsWith("place+item:") || plain.startsWith("use+item:")
                    || plain.startsWith("cube")) {
                // A structure is clicked on the middle of the face the player looks at.
                BlockPos on = plain.startsWith("cube") && guiCubeClick != null ? guiCubeClick : rig;
                // Low in the cell: a part placed on the floor sits there, a full block does not care.
                Vec3 at = face == Direction.UP ? new Vec3(on.getX() + 0.5, on.getY() + 0.06, on.getZ() + 0.5)
                        : Vec3.atCenterOf(on).relative(face, 0.5);
                BlockHitResult hit = new BlockHitResult(at, face, on, false);
                BlockState state = level.getBlockState(on);
                net.minecraft.world.ItemInteractionResult result = state.useItemOn(
                        player.getItemInHand(InteractionHand.MAIN_HAND), level, player, InteractionHand.MAIN_HAND, hit);
                if (result == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                    state.useWithoutItem(level, player, hit);
                }
            } else if (plain.startsWith("entity:")) {
                if (guiEntity != null) {
                    // The order a client's click takes: the exact spot first, then the entity.
                    if (!guiEntity.interactAt(player, Vec3.ZERO, InteractionHand.MAIN_HAND).consumesAction()) {
                        guiEntity.interact(player, InteractionHand.MAIN_HAND);
                    }
                }
            } else if (plain.startsWith("item:")) {
                ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
                ResourceLocation id = ResourceLocation.parse(plain.substring("item:".length()));
                if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id)) {
                    stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
                    player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                }
                stack.use(level, player, InteractionHand.MAIN_HAND);
            }
        } catch (Throwable t) {
            LOGGER.info("[cimscene] gallery-gui opener {} threw {}", opener, t.toString());
        } finally {
            player.setShiftKeyDown(false);
        }
    }

    /** What a namespace offers a player's hand: every block, item and entity, plain and sneaking. */
    private static List<String> guiCandidates(String namespace) {
        List<String> out = new ArrayList<>();
        for (ResourceLocation b : BuiltInRegistries.BLOCK.keySet()) {
            if (b.getNamespace().equals(namespace)) {
                out.add("block:" + b);
            }
        }
        for (ResourceLocation i : BuiltInRegistries.ITEM.keySet()) {
            if (i.getNamespace().equals(namespace)) {
                out.add("item:" + i);
                if (!(BuiltInRegistries.ITEM.get(i) instanceof net.minecraft.world.item.BlockItem)) {
                    out.add("place+item:" + i);
                    out.add("hang+item:" + i);
                }
            }
        }
        for (ResourceLocation e : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (e.getNamespace().equals(namespace)) {
                out.add("entity:" + e);
            }
        }
        // The plainest action first: the first thing that opens a menu is its opener, and a
        // held-item menu reached through a placement path would lose the item in the frame.
        List<String> ranks = List.of("block:", "item:", "place+", "hang+", "entity:");
        out.sort(Comparator.<String>comparingInt(c -> {
            for (int i = 0; i < ranks.size(); i++) {
                if (c.startsWith(ranks.get(i))) {
                    return i;
                }
            }
            return ranks.size();
        }).thenComparing(Comparator.naturalOrder()));
        List<String> both = new ArrayList<>(out);
        for (String each : out) {
            both.add("sneak+" + each);
        }
        return both;
    }

    /** Where a built structure is clicked; set by guiBuildCube. */
    private static BlockPos guiCubeClick;
    /** Every block a structure put down, so the rig can be cleared of it. */
    private static final List<BlockPos> GUI_CUBE_BLOCKS = new ArrayList<>();
    /** How many times a built structure's block entities tick before the click. */
    private static final int GUI_CUBE_TICKS = 25;

    /**
     * {@code cube<n>:<block>}: an n-wide hollow cube of one block, its near face centred on
     * the rig and the rest stretching away from the player, the way a player builds a tank
     * or a matrix out of casing. The mod's own formation code decides whether it is a
     * structure: its block entities tick here, so a multiblock that forms on its tick has
     * formed before the click, with no knowledge of any mod.
     */
    private static void guiBuildCube(ServerLevel level, ServerPlayer player, BlockPos rig, String plain) {
        int n = Integer.parseInt(plain.substring("cube".length(), plain.indexOf(':')));
        BlockState state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(plain.substring(plain.indexOf(':') + 1)))
                .defaultBlockState();
        Direction away = Direction.getNearest(rig.getX() + 0.5 - player.getX(), 0, rig.getZ() + 0.5 - player.getZ());
        if (away.getAxis() == Direction.Axis.Y) {
            away = Direction.NORTH;
        }
        Direction side = away.getClockWise();
        int half = n / 2;
        List<BlockPos> placed = new ArrayList<>();
        for (int depth = 0; depth < n; depth++) {
            for (int across = -half; across < n - half; across++) {
                for (int up = 0; up < n; up++) {
                    boolean shell = depth == 0 || depth == n - 1 || across == -half || across == n - half - 1
                            || up == 0 || up == n - 1;
                    if (shell) {
                        BlockPos p = rig.relative(away, depth).relative(side, across).above(up);
                        level.setBlock(p, state, 3);
                        placed.add(p.immutable());
                    }
                }
            }
        }
        GUI_CUBE_BLOCKS.addAll(placed);
        guiCubeClick = rig.above(half);
        // A block entity's onLoad (where a mod joins it to its structure) runs at the start of
        // the next level tick; the probe clicks in this one, so the load happens here.
        for (BlockPos p : placed) {
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(p);
            if (be != null) {
                be.onLoad();
            }
        }
        for (int t = 0; t < GUI_CUBE_TICKS; t++) {
            for (BlockPos p : placed) {
                guiTickBlockEntity(level, p);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends net.minecraft.world.level.block.entity.BlockEntity> void guiTickBlockEntity(
            ServerLevel level, BlockPos p) {
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(p);
        if (be == null) {
            return;
        }
        BlockState state = level.getBlockState(p);
        net.minecraft.world.level.block.entity.BlockEntityTicker<T> ticker = state.getTicker(level,
                (net.minecraft.world.level.block.entity.BlockEntityType<T>) be.getType());
        if (ticker != null) {
            ticker.tick(level, p, state, (T) be);
        }
    }

    /** Hollow cubes of every block of the namespace that holds a block entity: the casing guess. */
    private static List<String> guiCubeCandidates(String namespace) {
        List<String> out = new ArrayList<>();
        for (ResourceLocation b : BuiltInRegistries.BLOCK.keySet()) {
            if (b.getNamespace().equals(namespace)
                    && BuiltInRegistries.BLOCK.get(b) instanceof net.minecraft.world.level.block.EntityBlock) {
                out.add(b.toString());
            }
        }
        out.sort(Comparator.naturalOrder());
        List<String> cubes = new ArrayList<>();
        for (int n : new int[]{3, 5}) {
            for (String b : out) {
                cubes.add("cube" + n + ":" + b);
            }
        }
        return cubes;
    }

    /** Every non-block item of the namespace on every block of it that holds a block entity. */
    private static int PAIR_USES_LOGGED;

    private static final java.util.Set<net.minecraft.world.entity.EntityType<?>> PAIR_ENTITIES_LOGGED =
            new java.util.HashSet<>();

    private static List<String> guiPairCandidates(String namespace) {
        List<String> items = new ArrayList<>();
        for (ResourceLocation i : BuiltInRegistries.ITEM.keySet()) {
            if (i.getNamespace().equals(namespace)
                    && !(BuiltInRegistries.ITEM.get(i) instanceof net.minecraft.world.item.BlockItem)) {
                items.add(i.toString());
            }
        }
        List<String> blocks = new ArrayList<>();
        for (ResourceLocation b : BuiltInRegistries.BLOCK.keySet()) {
            if (b.getNamespace().equals(namespace)
                    && BuiltInRegistries.BLOCK.get(b) instanceof net.minecraft.world.level.block.EntityBlock) {
                blocks.add(b.toString());
            }
        }
        items.sort(Comparator.naturalOrder());
        blocks.sort(Comparator.naturalOrder());
        List<String> out = new ArrayList<>(items.size() * blocks.size());
        for (String block : blocks) {
            for (String item : items) {
                out.add("use+item:" + item + "@" + block);
            }
        }
        return out;
    }

    private static boolean GUI_CLEARING;

    private static void guiClearRig(ServerLevel level, ServerPlayer player, BlockPos rig) {
        GUI_CLEARING = true;
        try {
            guiClearRigBlocks(level, player, rig);
        } finally {
            GUI_CLEARING = false;
        }
    }

    private static void guiClearRigBlocks(ServerLevel level, ServerPlayer player, BlockPos rig) {
        for (BlockPos p : BlockPos.betweenClosed(rig.offset(-1, 0, -1), rig.offset(1, 2, 1))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        List<net.minecraft.world.entity.Entity> near = new ArrayList<>(level.getEntities(
                (net.minecraft.world.entity.Entity) null, new net.minecraft.world.phys.AABB(rig).inflate(6),
                e -> !(e instanceof net.minecraft.world.entity.player.Player)));
        // A spawn in a section not yet visible is missing from the box query: it is known by its join.
        near.addAll(GUI_SPAWNED);
        GUI_SPAWNED.clear();
        near.forEach(net.minecraft.world.entity.Entity::discard);
        for (BlockPos p : GUI_CUBE_BLOCKS) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
        GUI_CUBE_BLOCKS.clear();
        guiCubeClick = null;
        guiEntity = null;
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    /** One probe pass; every result lands in {@code onFound}. */
    private static int guiProbeAll(ServerLevel level, String namespace,
                                   java.util.function.Consumer<GuiProbe> onFound) {
        // Far from every player: no client tracks this spot, so an entity whose spawn
        // data only works in context cannot reach one and disconnect it.
        BlockPos rig = new BlockPos(GUI_PROBE_XZ, 100, GUI_PROBE_XZ);
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(rig);
        level.setChunkForced(chunk.x, chunk.z, true);
        level.getChunk(chunk.x, chunk.z);
        level.setBlock(rig.below(), Blocks.STONE.defaultBlockState(), 3);
        GuiProbe probe = new GuiProbe(level);
        probe.setPos(rig.getX() + 0.5, rig.getY(), rig.getZ() + 2.5);
        List<String> candidates = new ArrayList<>(guiCandidates(namespace));
        java.util.Set<ResourceLocation> wanted = new java.util.HashSet<>();
        for (ResourceLocation id : BuiltInRegistries.MENU.keySet()) {
            if (id.getNamespace().equals(namespace)) {
                wanted.add(id);
            }
        }
        int singles = candidates.size();
        boolean cubesAdded = false;
        for (int c = 0; ; c++) {
            // Checked before the bound: the pairs start exactly where the singles end, so
            // a loop bounded by the singles' count would never reach them.
            if (c == singles && !wanted.isEmpty()) {
                // What no single click opened may need a tool used on a machine, the way a
                // player tries a card, a cell or a robit on a block.
                List<String> pairs = guiPairCandidates(namespace);
                LOGGER.info("[cimscene] gallery-gui pairs ns={} missing={} pairs={}", namespace, wanted.size(), pairs.size());
                candidates.addAll(pairs);
            }
            if (c == candidates.size() && !cubesAdded && !wanted.isEmpty()) {
                // Still missing: menus a structure opens once it has formed.
                cubesAdded = true;
                List<String> cubes = guiCubeCandidates(namespace);
                LOGGER.info("[cimscene] gallery-gui cubes ns={} missing={} cubes={}", namespace, wanted.size(), cubes.size());
                candidates.addAll(cubes);
            }
            if (c >= candidates.size()) {
                break;
            }
            String candidate = candidates.get(c);
            probe.seen = null;
            probe.candidate = candidate;
            try {
                guiPlace(level, probe, rig, candidate);
                guiAct(level, probe, rig, candidate);
            } catch (Throwable t) {
                LOGGER.info("[cimscene] gallery-gui candidate {} threw {}", candidate, t.toString());
            }
            if (probe.seen != null) {
                wanted.remove(probe.seen);
                onFound.accept(probe);
            }
            // A use that put an entity into the world is the rare, telling case: say what
            // came of clicking it, so a menu that still does not open can be read from the log.
            if (c >= singles && guiEntity != null && PAIR_ENTITIES_LOGGED.add(guiEntity.getType())) {
                LOGGER.info("[cimscene] gallery-gui pair {} made {} alive={} opened={}", candidate,
                        BuiltInRegistries.ENTITY_TYPE.getKey(guiEntity.getType()), guiEntity.isAlive(), probe.seen);
            }
            guiClearRig(level, probe, rig);
            if (c >= singles && wanted.isEmpty()) {
                break;
            }
        }
        level.setBlock(rig.below(), Blocks.AIR.defaultBlockState(), 3);
        level.setChunkForced(chunk.x, chunk.z, false);
        return candidates.size();
    }

    /**
     * Writes every menu's manifest with no client involved: the probe's server-side
     * menu object already knows its class and slots. Both gallery legs and a real
     * server can use this, and no disconnect can cut it short.
     */
    private static int galleryGuiScan(CommandSourceStack src, String namespace) {
        ServerLevel level = src.getLevel();
        Map<ResourceLocation, List<String>> openers = new HashMap<>();
        Map<ResourceLocation, String> classes = new HashMap<>();
        Map<ResourceLocation, String> titles = new HashMap<>();
        Map<ResourceLocation, List<String>> slots = new HashMap<>();
        int tried = guiProbeAll(level, namespace, probe -> {
            openers.computeIfAbsent(probe.seen, k -> new ArrayList<>()).add(probe.candidate);
            classes.putIfAbsent(probe.seen, probe.seenClass);
            titles.putIfAbsent(probe.seen, probe.seenTitle);
            slots.putIfAbsent(probe.seen, probe.seenSlots);
        });
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.MENU.keySet());
        ids.removeIf(id -> !id.getNamespace().equals(namespace));
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        int found = 0;
        for (int page = 0; page < ids.size(); page++) {
            ResourceLocation id = ids.get(page);
            StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tmenu\tnote\n");
            if (classes.containsKey(id)) {
                found++;
                tsv.append("cell\t0\t0\t0\t").append(id).append("\topened-by:")
                        .append(openers.get(id).get(0)).append('\t').append(classes.get(id)).append('\n');
                tsv.append("title\t-\t-\t-\t").append(id).append('\t')
                        .append(titles.get(id).replace('\t', ' ')).append('\n');
                for (String each : openers.get(id)) {
                    tsv.append("opener\t-\t-\t-\t").append(id).append('\t').append(each).append('\n');
                }
                int index = 0;
                for (String slot : slots.get(id)) {
                    String[] p = slot.split("\t");
                    tsv.append("slot\t").append(index++).append('\t').append(p[0]).append('\t').append(p[1])
                            .append('\t').append(id).append('\t').append(p[2]).append('\t').append(p[3]).append('\n');
                }
            } else {
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\tno-opener\n");
            }
            try {
                Path parent = Path.of("c-imagine", "scenes");
                Files.createDirectories(parent);
                Files.writeString(parent.resolve("gallery-gui-" + namespace + "-p" + page + ".tsv"),
                        tsv.toString(), StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                LOGGER.error("[cimscene] gallery-gui-scan write failed {}: {}", id, ioe.toString());
            }
        }
        LOGGER.info("[cimscene] gallery-gui-scan ns={} tried={} menus={} found={}",
                namespace, tried, ids.size(), found);
        return found;
    }

    private static String guiManifestOpener(String namespace, int page) {
        Path file = Path.of("c-imagine", "scenes", "gallery-gui-" + namespace + "-p" + page + ".tsv");
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length > 5 && p[0].equals("opener")) {
                    return p[5];
                }
            }
        } catch (IOException ignored) {
            // no manifest: no opener
        }
        return null;
    }

    /**
     * The settled pass, for menus the scan's one-tick clicks did not open. Every structure
     * guess (a hollow cube of each block entity block, 3 and 5 wide) is built at once on its
     * own spot, the server ticks for real (a mod forms structures in its level tick, not in
     * the block entity's), and then each is clicked. A page that opens is rewritten with its
     * opener; c-imagine/scenes/settle-NS.done marks the end.
     */
    private static int galleryGuiSettle(ServerLevel level, String namespace) {
        java.util.Set<ResourceLocation> wanted = new java.util.TreeSet<>();
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.MENU.keySet());
        ids.removeIf(id -> !id.getNamespace().equals(namespace));
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (int page = 0; page < ids.size(); page++) {
            if (guiManifestOpener(namespace, page) == null) {
                wanted.add(ids.get(page));
            }
        }
        List<String> cubes = wanted.isEmpty() ? List.of() : guiCubeCandidates(namespace);
        BlockPos base = new BlockPos(GUI_PROBE_XZ + 64, 100, GUI_PROBE_XZ);
        GuiProbe probe = new GuiProbe(level);
        List<BlockPos> rigs = new ArrayList<>();
        java.util.Set<Long> chunks = new java.util.HashSet<>();
        for (int i = 0; i < cubes.size(); i++) {
            BlockPos rig = base.offset((i % 16) * 8, 0, (i / 16) * 10);
            rigs.add(rig);
            for (int dx = -3; dx <= 3; dx += 3) {
                for (int dz = -6; dz <= 3; dz += 3) {
                    net.minecraft.world.level.ChunkPos c = new net.minecraft.world.level.ChunkPos(rig.offset(dx, 0, dz));
                    if (chunks.add(c.toLong())) {
                        level.setChunkForced(c.x, c.z, true);
                        level.getChunk(c.x, c.z);
                    }
                }
            }
        }
        List<BlockPos> clicks = new ArrayList<>();
        for (int i = 0; i < cubes.size(); i++) {
            probe.setPos(rigs.get(i).getX() + 0.5, rigs.get(i).getY(), rigs.get(i).getZ() + 2.5);
            try {
                guiBuildCube(level, probe, rigs.get(i), cubes.get(i));
            } catch (Throwable t) {
                LOGGER.info("[cimscene] gallery-gui settle build {} threw {}", cubes.get(i), t.toString());
            }
            clicks.add(guiCubeClick);
        }
        List<BlockPos> built = new ArrayList<>(GUI_CUBE_BLOCKS);
        GUI_CUBE_BLOCKS.clear();
        LOGGER.info("[cimscene] gallery-gui settle ns={} missing={} structures={}", namespace, wanted.size(), cubes.size());
        runAfterTicks(level.getServer(), 60, () -> {
            Map<ResourceLocation, List<String>> openers = new java.util.TreeMap<>();
            Map<ResourceLocation, GuiProbe> seen = new HashMap<>();
            for (int i = 0; i < cubes.size(); i++) {
                if (clicks.get(i) == null) {
                    continue;
                }
                GuiProbe one = new GuiProbe(level);
                one.setPos(rigs.get(i).getX() + 0.5, rigs.get(i).getY(), rigs.get(i).getZ() + 2.5);
                one.candidate = cubes.get(i);
                guiCubeClick = clicks.get(i);
                try {
                    guiAct(level, one, rigs.get(i), cubes.get(i));
                } catch (Throwable t) {
                    LOGGER.info("[cimscene] gallery-gui settle click {} threw {}", cubes.get(i), t.toString());
                }
                if (one.seen != null && wanted.contains(one.seen)) {
                    openers.computeIfAbsent(one.seen, k -> new ArrayList<>()).add(one.candidate);
                    seen.putIfAbsent(one.seen, one);
                }
            }
            guiCubeClick = null;
            for (BlockPos p : built) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
            for (Map.Entry<ResourceLocation, GuiProbe> e : seen.entrySet()) {
                GuiProbe one = e.getValue();
                int page = ids.indexOf(e.getKey());
                StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tmenu\tnote\n");
                tsv.append("cell\t0\t0\t0\t").append(e.getKey()).append("\topened-by:")
                        .append(openers.get(e.getKey()).get(0)).append('\t').append(one.seenClass).append('\n');
                tsv.append("title\t-\t-\t-\t").append(e.getKey()).append('\t')
                        .append(one.seenTitle.replace('\t', ' ')).append('\n');
                for (String each : openers.get(e.getKey())) {
                    tsv.append("opener\t-\t-\t-\t").append(e.getKey()).append('\t').append(each).append('\n');
                }
                int index = 0;
                for (String slot : one.seenSlots) {
                    String[] p = slot.split("\t");
                    tsv.append("slot\t").append(index++).append('\t').append(p[0]).append('\t').append(p[1])
                            .append('\t').append(e.getKey()).append('\t').append(p[2]).append('\t').append(p[3]).append('\n');
                }
                try {
                    Files.writeString(Path.of("c-imagine", "scenes", "gallery-gui-" + namespace + "-p" + page + ".tsv"),
                            tsv.toString(), StandardCharsets.UTF_8);
                } catch (IOException ioe) {
                    LOGGER.error("[cimscene] gallery-gui settle write failed {}: {}", e.getKey(), ioe.toString());
                }
            }
            LOGGER.info("[cimscene] gallery-gui settle ns={} opened={}", namespace, seen.keySet());
            try {
                Files.createDirectories(Path.of("c-imagine", "scenes"));
                Files.writeString(Path.of("c-imagine", "scenes", "settle-" + namespace + ".done"),
                        String.valueOf(seen.size()), StandardCharsets.UTF_8);
            } catch (IOException ioe) {
                LOGGER.error("[cimscene] gallery-gui settle marker failed: {}", ioe.toString());
            }
        });
        return cubes.size();
    }

    /** Tries every block and item of the namespace once and keeps what opened a menu. */
    private static Map<ResourceLocation, String> guiOpeners(ServerLevel level, ServerPlayer viewer, String namespace) {
        Map<ResourceLocation, String> known = GUI_OPENERS.get(namespace);
        if (known != null) {
            return known;
        }
        Map<ResourceLocation, String> found = new HashMap<>();
        int tried = guiProbeAll(level, namespace, probe -> {
            found.putIfAbsent(probe.seen, probe.candidate);
            GUI_ALL_OPENERS.computeIfAbsent(probe.seen, k -> new ArrayList<>()).add(probe.candidate);
        });
        LOGGER.info("[cimscene] gallery-gui openers ns={} tried={} found={}", namespace, tried, found.size());
        GUI_OPENERS.put(namespace, found);
        return found;
    }

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
            BlockPos rig = guiRigPos(viewer);
            guiClearRig(level, viewer, rig);
            // A real menu needs the context its own code supplies, so the walk does
            // what a player does. The block entity must reach the client first, or
            // the screen reads a null holder and the client disconnects.
            String opener = guiOpeners(level, viewer, namespace).get(id);
            if (opener == null) {
                // The plan's settled pass found what a click in one tick cannot: a structure that
                // forms over real ticks. Its opener is in the page manifest the plan wrote.
                opener = guiManifestOpener(namespace, page);
            }
            if (opener != null) {
                guiPlace(level, viewer, rig, opener);
                // The same race for items: a held-item menu looks up its host in the
                // client's inventory, so the stack has to arrive first.
                String plain = opener.replace("sneak+", "");
                if (plain.startsWith("item:")) {
                    viewer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(
                            ResourceLocation.parse(plain.substring("item:".length())))));
                    viewer.inventoryMenu.broadcastChanges();
                }
            }
            String chosen = opener;
            runAfterTicks(level.getServer(), chosen != null && (chosen.contains("entity:") || chosen.startsWith("cube"))
                            ? 40 : GUI_SETTLE_TICKS,
                    () -> finishGuiPage(level, viewer, namespace, page, id, chosen, rig));
            return 1;
        }
        writeGuiManifest(namespace, page, id, guiStem, tsv, placed, failed);
        return placed;
    }

    private static void finishGuiPage(ServerLevel level, ServerPlayer viewer, String namespace, int page,
                                      ResourceLocation id, String opener, BlockPos rig) {
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tmenu\tnote\n");
        int placed = 0;
        int failed = 0;
        String how = null;
        boolean gone = opener != null && opener.contains("entity:") && (guiEntity == null || !guiEntity.isAlive());
        if (opener != null && !gone) {
            try {
                guiAct(level, viewer, rig, opener);
            } catch (Throwable t) {
                // The page still gets its manifest, so a throwing opener is a named RED row.
                LOGGER.info("[cimscene] gallery-gui opener {} threw for the viewer: {}", opener, t.toString());
            }
            if (guiOpenMenu(viewer) != null) {
                how = "opened-by:" + opener;
            }
        }
        if (how == null) {
            try {
                MenuType<?> type = BuiltInRegistries.MENU.get(id);
                net.minecraft.network.RegistryFriendlyByteBuf extra = new net.minecraft.network.RegistryFriendlyByteBuf(
                        Unpooled.buffer(), viewer.server.registryAccess());
                viewer.openMenu(new SimpleMenuProvider(
                        (windowId, inv, player) -> type.create(windowId, inv, extra),
                        Component.literal(id.toString())));
                how = "opened-empty";
            } catch (Throwable t) {
                failed++;
                tsv.append("skip\t-1\t-1\t-1\t").append(id).append("\topen-failed:")
                        .append(opener == null ? "no-opener:" : "opener-failed:")
                        .append(t.getClass().getName()).append('\n');
                LOGGER.info("[cimscene] gallery-gui skip ns={} page={} reason=open-failed menu={} opener={} err={}",
                        namespace, page, id, opener, t.toString());
            }
        }
        if (how != null) {
            placed++;
            tsv.append("cell\t").append(rig.getX()).append('\t').append(rig.getY()).append('\t')
                    .append(rig.getZ()).append('\t').append(id).append('\t').append(how).append('\t')
                    .append(viewer.containerMenu.getClass().getName()).append('\n');
            for (String each : GUI_ALL_OPENERS.getOrDefault(id, List.of())) {
                tsv.append("opener\t-\t-\t-\t").append(id).append('\t').append(each).append('\n');
            }
            // Slot geometry is server state, so every opened menu documents itself.
            int slotIndex = 0;
            for (net.minecraft.world.inventory.Slot slot : viewer.containerMenu.slots) {
                tsv.append("slot\t").append(slotIndex++).append('\t').append(slot.x).append('\t')
                        .append(slot.y).append('\t').append(id).append('\t')
                        .append(slot.container == viewer.getInventory() ? "player" : "menu").append('\t').append(slot.getContainerSlot()).append('\n');
            }
        }
        writeGuiManifest(namespace, page, id, "gallery-gui-" + namespace + "-p" + page, tsv, placed, failed);
    }

    private static void writeGuiManifest(String namespace, int page, ResourceLocation id, String guiStem,
                                         StringBuilder tsv, int placed, int failed) {
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
            } catch (Throwable ex) {
                // A mod's Error (a NoSuchMethodError from its own bytecode) must not hide the page.
                LOGGER.error("[cimscene] delayed task failed: {}", ex.toString(), ex);
            }
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
