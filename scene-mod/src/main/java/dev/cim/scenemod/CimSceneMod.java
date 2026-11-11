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
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.component.DataComponents;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotPatterns;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
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
            NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
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
        if (!ACTIVE_GANTRY_RIGS.isEmpty()) {
            for (GantryRig rig : List.copyOf(ACTIVE_GANTRY_RIGS)) rig.observe(event.getEntity());
        }
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
                        .then(Commands.literal("selftest-package").executes(ctx -> itemEntitySpawnSelftest(ctx.getSource(), false)))
                        .then(Commands.literal("selftest-blueprint").executes(ctx -> itemEntitySpawnSelftest(ctx.getSource(), true)))
                        .then(Commands.literal("selftest-potato").executes(ctx -> potatoSpawnSelftest(ctx.getSource())))
                        .then(Commands.literal("selftest-bearing").executes(ctx -> contraptionSpawnSelftest(ctx.getSource(), false)))
                        .then(Commands.literal("selftest-cart").executes(ctx -> contraptionSpawnSelftest(ctx.getSource(), true)))
                        .then(Commands.literal("selftest-bearing-live").executes(ctx -> entityLifetimeSelftest(ctx.getSource(), "bearing")))
                        .then(Commands.literal("selftest-glue-live").executes(ctx -> entityLifetimeSelftest(ctx.getSource(), "glue")))
                        .then(Commands.literal("selftest-seat-live").executes(ctx -> entityLifetimeSelftest(ctx.getSource(), "seat")))
                        .then(Commands.literal("selftest-gantry-live").executes(ctx -> gantryLifetimeSelftest(ctx.getSource())))
                        .then(Commands.literal("selftest-carriage-live").executes(ctx -> carriageLifetimeSelftest(ctx.getSource())))
                        .then(Commands.literal("selftest-carriage-page-live").executes(ctx -> carriagePageSelftest(ctx.getSource())))
                        .then(Commands.literal("cleanup-assemblies").executes(ctx -> {
                            ++entityPageGen;
                            closeGalleryAssemblies();
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
                                                        IntegerArgumentType.getInteger(ctx, "page"))))))
                        .then(Commands.literal("variant")
                                .then(Commands.argument("kind", StringArgumentType.word())
                                        .then(Commands.argument("namespace", StringArgumentType.word())
                                                .then(Commands.argument("page", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> galleryVariant(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "kind"),
                                                                StringArgumentType.getString(ctx, "namespace"),
                                                                IntegerArgumentType.getInteger(ctx, "page"))))))))
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
        LOGGER.info("[cimscene] commands registered (/cimscene census|selftest|gallery[page|entity|item|particle|fluid|sound|gui|biome|inv|variant]|probe-class)");
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

    /** Sparse pitch-2 coordinates for small entity variants. The base entity
     * census uses isolated pages: native assembly rigs can own neighboring
     * blocks even when their bare entity type reports a small bounding box. */
    static final int ENTITY_COLS = 6;
    static final int ENTITY_ROWS = 3;
    // Native assembly rigs own neighboring blocks and can exceed a bare type's
    // bounding box. Give every registry entry an isolated page on both stacks.
    static final int ENTITY_CELLS = 1;
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
            Files.writeString(parent.resolve(manifest.replace(".tsv", ".light")),
                    CaptureLightData.snapshot(level, tsv.toString()), StandardCharsets.UTF_8);
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

    // --- variant gallery (the no-walk classes' real scenes, 14-dx) --------

    /** Entity-grid variant kinds: the sparse pitch-2 6x3 wall (18 cells,
     *  rows y=-60/-58/-56). Every subject on this grid is a live ENTITY
     *  (the painting, the frozen mobs, the trim/enchant armor stands) — the
     *  measured entity-grid law (no neighbour overlap by construction)
     *  applies to them exactly as it applies to the entity walk. */
    static final int VARIANT_CELLS_ENTITY = ENTITY_COLS * ENTITY_ROWS; // 18
    /** Block-grid variant kinds: the block wall itself (11x7 = 77 cells,
     *  pitch 1) — banner patterns, pot sherds and jukebox songs are real
     *  BLOCKS placed like the block wall's states. */
    static final int VARIANT_CELLS_BLOCK = GALLERY_CELLS; // 77

    /** The variant kinds that render on the entity grid (everything else
     *  is block-grid). Keep in lockstep with the ledger rows above. */
    private static boolean variantEntityGrid(String kind) {
        return switch (kind) {
            case "painting", "wolf", "cat", "frog", "villager", "effect",
                 "trimmaterial", "trimpattern", "enchant" -> true;
            case "banner", "pot", "jukebox", "blockentity" -> false;
            default -> false;
        };
    }

    /**
     * The no-walk classes' real scenes (roadmap audit "No-walk visual
     * classes": "add real scenes and runtime assertions instead of excluding
     * these registrations"). ONE command family, TWELVE kinds, every kind a
     * REAL vanilla-mechanics rendering of the registry entry — zero per-mod
     * code, the sweep is the registry walk, identical on both parity stacks
     * (the same ordinary-mod code over the same registries):
     *
     * <ul>
     *   <li>{@code painting} — a real {@link Painting} hung on a smooth
     *       support wall behind the wall plane, facing the camera; variants
     *       wider/taller than 2 blocks are named {@code oversized} skips
     *       (the pitch-2 grid cannot isolate a 3x3+ painting from its
     *       neighbours without a per-subject page geometry).</li>
     *   <li>{@code wolf} / {@code cat} / {@code frog} — the real mob spawned
     *       frozen (the entity walk's NoAI+NoGravity+Invulnerable contract)
     *       with the variant set through the entity's own public API.</li>
     *   <li>{@code villager} — a frozen villager with
     *       {@code setVillagerData(new VillagerData(type, NONE, 1))}.</li>
     *   <li>{@code effect} — a frozen pig carrying the real
     *       {@link MobEffectInstance} (visible, so the client draws the
     *       ambient effect particles) — the settle pass asserts the effect
     *       is STILL ACTIVE, not just that the entity lives.</li>
     *   <li>{@code trimmaterial} / {@code trimpattern} — an armor stand
     *       wearing an iron chestplate whose {@code minecraft:trim}
     *       component pairs every material with a fixed pattern (first
     *       sorted id, deterministic) / every pattern with a fixed
     *       material.</li>
     *   <li>{@code enchant} — an armor stand HOLDING an iron sword with the
     *       real {@code minecraft:enchantments} component (the glint is the
     *       client-side proof; dyn=1, the glint shimmers).</li>
     *   <li>{@code banner} — a real white standing banner whose
     *       {@code minecraft:banner_patterns} BE state carries the pattern
     *       (built through {@code BannerBlockEntity#fromItem}, the vanilla
     *       item->BE path).</li>
     *   <li>{@code pot} — a real decorated pot whose sherds resolve the
     *       pattern through {@code DecoratedPotPatterns#getPatternFromItem}
     *       (the vanilla item->pattern link, scanned once and cached; a
     *       pattern with no sherd is a named {@code no-sherd} skip).</li>
     *   <li>{@code jukebox} — a real jukebox holding the disc whose
     *       {@code minecraft:jukebox_playable} component resolves to the
     *       song, PLAYING (the runtime assertion is
     *       {@code getSongPlayer().isPlaying()} at settle); the song stops
     *       when the next page's clear discards the BE.</li>
     * </ul>
     *
     * <p>Manifest per page {@code c-imagine/scenes/gallery-variant-<kind>-<ns>-p<p>.tsv}
     * rows {@code cell|x|y|z|subject|dyn|note} — the SAME 7-column shape the
     * entity manifests use, so the gate's absence-note join works unchanged.
     * Every failure is a named skip row ({@code no-instance}, {@code
     * failed:<t>}, {@code oversized}, {@code no-sherd}, {@code no-disc},
     * {@code dissolved}, ...); the settle pass (40 ticks, then the 12-as
     * final rewrite 40 ticks later) re-reads what the world HOLDS — the
     * 14-di law (the manifest carries the settled state) applies. The world
     * is NOT frozen by this walk (the subjects are entity-flavored; the next
     * block page re-arms the freeze at its own settle, per the 14-di
     * unfreeze contract every gallery command entry follows).
     *
     * <p>THE OURS LEG (named lane, intentionally not replayed yet): the
     * classes ride registries the vanilla 26.3 client does not carry — the
     * F2V projection per kind is the named next lane (the same
     * diagnostic-first shape the sound/gui/biome walks shipped in). The
     * coverage gate runs these classes DIAGNOSTIC-first; the missing ours
     * shots are the named backlog, reported loudly, never red.
     */
    private static int galleryVariant(CommandSourceStack src, String kind, String namespace, int page) {
        if (!variantKindKnown(kind)) {
            LOGGER.info("[cimscene] gallery-variant unknown kind={} (known: painting, wolf, cat, frog, "
                    + "villager, effect, trimmaterial, trimpattern, banner, pot, enchant, jukebox)", kind);
            src.sendFailure(Component.literal("[cimscene] unknown variant kind: " + kind));
            return 0;
        }
        ServerLevel level = src.getLevel();
        setGalleryFrozen(level.getServer(), false, "variant page entry");
        // shared supersession channel: a NEW page command (any series) must
        // supersede this series' pending settle passes the same way the
        // entity walk's passes supersede each other
        final int gen = ++entityPageGen;
        writeGalleryIndex(level, namespace);
        int swept = sweepGalleryDrops(level);
        if (swept > 0) {
            LOGGER.info("[cimscene] gallery drop-sweep ns={} page={} removed={}", namespace, page, swept);
        }
        String stem = "gallery-variant-" + kind + "-" + namespace + "-p" + page;
        boolean entityGrid = variantEntityGrid(kind);
        int cellsPerPage = entityGrid ? VARIANT_CELLS_ENTITY : VARIANT_CELLS_BLOCK;

        List<ResourceLocation> entries = variantEntries(level, kind, namespace);
        if (entries == null) {
            // the dynamic registry itself is unreadable: terminate the walk
            // honestly (the ledger's -1 row already reds the coverage) — a
            // hang here would burn the CI wait for nothing
            writeDoneMarkerFile(stem, true);
            LOGGER.info("[cimscene] gallery-variant done kind={} ns={} page={} cells=0 failed=0 "
                    + "skipped=0 exhausted=1 registry-unreadable=1", kind, namespace, page);
            return 0;
        }

        LOGGER.info("[cimscene] gallery-variant begin kind={} ns={} page={} entries={} cellsPerPage={}",
                kind, namespace, page, entries.size(), cellsPerPage);

        int from = page * cellsPerPage;
        if (from >= entries.size()) {
            writeDoneMarkerFile(stem, true);
            LOGGER.info("[cimscene] gallery-variant done kind={} ns={} page={} cells=0 failed=0 "
                    + "skipped=0 exhausted=1", kind, namespace, page);
            return 0;
        }
        List<ResourceLocation> slice = entries.subList(from, Math.min(from + cellsPerPage, entries.size()));

        // the wall region: blocks swept (both grids live inside the shared
        // clearWall box) and stale gallery entities killed — the same hygiene
        // the entity walk runs, because a prior page's subjects photobomb the
        // crops exactly the same way
        clearWall(level);
        killStaleGalleryEntities(level);

        final List<Entity> spawned = new ArrayList<>();
        final List<Integer> spawnedIdx = new ArrayList<>();
        final java.util.Map<Integer, String> skipNotes = new java.util.HashMap<>();
        int failed = 0;
        int kindBlocks = 0; // block-grid kinds: the placed block cells

        switch (kind) {
            case "painting" -> {
                fillPaintingSupport(level);
                for (int i = 0; i < slice.size(); i++) {
                    ResourceLocation id = slice.get(i);
                    int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
                    BlockPos cell = new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                            GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z);
                    try {
                        Holder<PaintingVariant> holder = dynamicHolder(level, Registries.PAINTING_VARIANT, id);
                        PaintingVariant variant = holder.value();
                        if (variant.width() > 2 || variant.height() > 2) {
                            skipNotes.put(i, "oversized");
                            LOGGER.info("[cimscene] gallery-variant skip kind=painting ns={} page={} index={} "
                                    + "reason=oversized {}x{} subject={}", namespace, page, from + i,
                                    variant.width(), variant.height(), id);
                            continue;
                        }
                        // the painting FACES the camera (north = -z, the camera
                        // sits at z<9 looking +z) and hangs on the support wall
                        // at z+1 (direction.getOpposite() — the vanilla
                        // HangingEntity.survives() contract)
                        Painting p = new Painting(level, cell, Direction.NORTH, holder);
                        if (!p.survives()) {
                            skipNotes.put(i, "no-support");
                            LOGGER.info("[cimscene] gallery-variant skip kind=painting ns={} page={} index={} "
                                    + "reason=no-support subject={}", namespace, page, from + i, id);
                            continue;
                        }
                        p.setInvulnerable(true);
                        p.addTag(GALLERY_TAG);
                        level.addFreshEntity(p);
                        spawned.add(p);
                        spawnedIdx.add(i);
                    } catch (Throwable t) {
                        failed++;
                        skipNotes.put(i, "failed:" + t.getClass().getName());
                        LOGGER.info("[cimscene] gallery-variant skip kind=painting ns={} page={} index={} "
                                + "reason=failed subject={} err={}", namespace, page, from + i, id, t.toString());
                    }
                }
            }
            case "wolf", "cat", "frog", "villager", "effect" -> {
                for (int i = 0; i < slice.size(); i++) {
                    ResourceLocation id = slice.get(i);
                    int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
                    double cx = GALLERY_X0 + col * ENTITY_PITCH + 0.5;
                    double cy = GALLERY_Y0 + row * ENTITY_PITCH;
                    double cz = GALLERY_Z + 0.5;
                    try {
                        Entity e = switch (kind) {
                            case "wolf" -> {
                                Wolf w = new Wolf(net.minecraft.world.entity.EntityType.WOLF, level);
                                w.setVariant(dynamicHolder(level, Registries.WOLF_VARIANT, id));
                                yield w;
                            }
                            case "cat" -> {
                                Cat c = new Cat(net.minecraft.world.entity.EntityType.CAT, level);
                                c.setVariant(staticHolder(BuiltInRegistries.CAT_VARIANT, id));
                                yield c;
                            }
                            case "frog" -> {
                                Frog f = new Frog(net.minecraft.world.entity.EntityType.FROG, level);
                                f.setVariant(staticHolder(BuiltInRegistries.FROG_VARIANT, id));
                                yield f;
                            }
                            case "villager" -> {
                                VillagerType type = BuiltInRegistries.VILLAGER_TYPE.get(id);
                                if (type == null) {
                                    skipNotes.put(i, "no-instance");
                                    LOGGER.info("[cimscene] gallery-variant skip kind=villager ns={} page={} "
                                            + "index={} reason=no-instance subject={}", namespace, page, from + i, id);
                                    yield null;
                                }
                                Villager v = new Villager(net.minecraft.world.entity.EntityType.VILLAGER, level, type);
                                v.setVillagerData(new VillagerData(type, VillagerProfession.NONE, 1));
                                yield v;
                            }
                            case "effect" -> {
                                Pig pig = new Pig(net.minecraft.world.entity.EntityType.PIG, level);
                                pig.addEffect(new MobEffectInstance(
                                        staticHolder(BuiltInRegistries.MOB_EFFECT, id), EFFECT_DURATION, 0, false, true));
                                yield pig;
                            }
                            default -> null;
                        };
                        if (e == null) {
                            continue;
                        }
                        if (!freezeVariantSubject(e)) {
                            failed++;
                            skipNotes.put(i, "failed:configure");
                            LOGGER.info("[cimscene] gallery-variant skip kind={} ns={} page={} index={} "
                                    + "reason=failed:configure subject={}", kind, namespace, page, from + i, id);
                            continue;
                        }
                        e.moveTo(cx, cy, cz, 180.0f, 0.0f);
                        e.setYHeadRot(180.0f);
                        e.setYBodyRot(180.0f);
                        e.addTag(GALLERY_TAG);
                        level.addFreshEntity(e);
                        spawned.add(e);
                        spawnedIdx.add(i);
                    } catch (Throwable t) {
                        failed++;
                        skipNotes.put(i, "failed:" + t.getClass().getName());
                        LOGGER.info("[cimscene] gallery-variant skip kind={} ns={} page={} index={} "
                                + "reason=failed subject={} err={}", kind, namespace, page, from + i, id, t.toString());
                    }
                }
            }
            case "trimmaterial", "trimpattern" -> {
                Holder<net.minecraft.world.item.armortrim.TrimPattern> fixedPattern = null;
                Holder<net.minecraft.world.item.armortrim.TrimMaterial> fixedMaterial = null;
                try {
                    if ("trimmaterial".equals(kind)) {
                        fixedPattern = dynamicHolder(level, Registries.TRIM_PATTERN, firstIdOrThrow(level, Registries.TRIM_PATTERN));
                    } else {
                        fixedMaterial = dynamicHolder(level, Registries.TRIM_MATERIAL, firstIdOrThrow(level, Registries.TRIM_MATERIAL));
                    }
                } catch (Throwable t) {
                    LOGGER.error("[cimscene] gallery-variant {} fixed-half resolve failed: {}", kind, t.toString());
                }
                final Holder<net.minecraft.world.item.armortrim.TrimPattern> fp = fixedPattern;
                final Holder<net.minecraft.world.item.armortrim.TrimMaterial> fm = fixedMaterial;
                for (int i = 0; i < slice.size(); i++) {
                    ResourceLocation id = slice.get(i);
                    int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
                    double cx = GALLERY_X0 + col * ENTITY_PITCH + 0.5;
                    double cy = GALLERY_Y0 + row * ENTITY_PITCH;
                    double cz = GALLERY_Z + 0.5;
                    try {
                        ArmorStand stand = new ArmorStand(net.minecraft.world.entity.EntityType.ARMOR_STAND, level);
                        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
                        if ("trimmaterial".equals(kind)) {
                            chest.set(DataComponents.TRIM, new ArmorTrim(
                                    dynamicHolder(level, Registries.TRIM_MATERIAL, id), fp));
                        } else {
                            chest.set(DataComponents.TRIM, new ArmorTrim(fm,
                                    dynamicHolder(level, Registries.TRIM_PATTERN, id)));
                        }
                        if (!freezeVariantSubject(stand)) {
                            failed++;
                            skipNotes.put(i, "failed:configure");
                            continue;
                        }
                        stand.moveTo(cx, cy, cz, 180.0f, 0.0f);
                        stand.setYHeadRot(180.0f);
                        stand.setYBodyRot(180.0f);
                        stand.addTag(GALLERY_TAG);
                        level.addFreshEntity(stand);
                        // wear AFTER the join: the equipment broadcast rides the
                        // spawn + equipment packets in order
                        stand.setItemSlot(EquipmentSlot.CHEST, chest);
                        spawned.add(stand);
                        spawnedIdx.add(i);
                    } catch (Throwable t) {
                        failed++;
                        skipNotes.put(i, "failed:" + t.getClass().getName());
                        LOGGER.info("[cimscene] gallery-variant skip kind={} ns={} page={} index={} "
                                + "reason=failed subject={} err={}", kind, namespace, page, from + i, id, t.toString());
                    }
                }
            }
            case "enchant" -> {
                for (int i = 0; i < slice.size(); i++) {
                    ResourceLocation id = slice.get(i);
                    int col = i % ENTITY_COLS, row = i / ENTITY_COLS;
                    double cx = GALLERY_X0 + col * ENTITY_PITCH + 0.5;
                    double cy = GALLERY_Y0 + row * ENTITY_PITCH;
                    double cz = GALLERY_Z + 0.5;
                    try {
                        ArmorStand stand = new ArmorStand(net.minecraft.world.entity.EntityType.ARMOR_STAND, level);
                        ItemStack sword = new ItemStack(Items.IRON_SWORD);
                        // ItemEnchantments.Mutable.set returns void — the
                        // chain goes through a local
                        ItemEnchantments.Mutable ench = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                        ench.set(dynamicHolder(level, Registries.ENCHANTMENT, id), 1);
                        sword.set(DataComponents.ENCHANTMENTS, ench.toImmutable());
                        if (!freezeVariantSubject(stand)) {
                            failed++;
                            skipNotes.put(i, "failed:configure");
                            continue;
                        }
                        stand.moveTo(cx, cy, cz, 180.0f, 0.0f);
                        stand.setYHeadRot(180.0f);
                        stand.setYBodyRot(180.0f);
                        stand.addTag(GALLERY_TAG);
                        level.addFreshEntity(stand);
                        stand.setItemSlot(EquipmentSlot.MAINHAND, sword);
                        spawned.add(stand);
                        spawnedIdx.add(i);
                    } catch (Throwable t) {
                        failed++;
                        skipNotes.put(i, "failed:" + t.getClass().getName());
                        LOGGER.info("[cimscene] gallery-variant skip kind=enchant ns={} page={} index={} "
                                + "reason=failed subject={} err={}", namespace, page, from + i, id, t.toString());
                    }
                }
            }
            case "banner", "pot", "jukebox" -> {
                for (int i = 0; i < slice.size(); i++) {
                    ResourceLocation id = slice.get(i);
                    int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
                    BlockPos pos = new BlockPos(GALLERY_X0 + col, GALLERY_Y0 + row, GALLERY_Z);
                    try {
                        boolean placed = switch (kind) {
                            case "banner" -> {
                                level.setBlock(pos, Blocks.WHITE_BANNER.defaultBlockState(), Block.UPDATE_CLIENTS);
                                if (level.getBlockEntity(pos) instanceof BannerBlockEntity be) {
                                    ItemStack item = new ItemStack(Items.WHITE_BANNER);
                                    item.set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(java.util.List.of(
                                            new BannerPatternLayers.Layer(
                                                    dynamicHolder(level, Registries.BANNER_PATTERN, id), DyeColor.BLACK))));
                                    be.fromItem(item, DyeColor.WHITE);
                                    be.setChanged();
                                    yield true;
                                }
                                yield false;
                            }
                            case "pot" -> {
                                Item sherd = potSherdForPattern(id);
                                if (sherd == null) {
                                    skipNotes.put(i, "no-sherd");
                                    LOGGER.info("[cimscene] gallery-variant skip kind=pot ns={} page={} index={} "
                                            + "reason=no-sherd subject={}", namespace, page, from + i, id);
                                    yield true; // the row is already named; no block
                                }
                                level.setBlock(pos, Blocks.DECORATED_POT.defaultBlockState(), Block.UPDATE_CLIENTS);
                                if (level.getBlockEntity(pos) instanceof DecoratedPotBlockEntity be) {
                                    be.setFromItem(DecoratedPotBlockEntity.createDecoratedPotItem(
                                            new PotDecorations(sherd, sherd, sherd, sherd)));
                                    be.setChanged();
                                    yield true;
                                }
                                yield false;
                            }
                            case "jukebox" -> {
                                ItemStack disc = discForSong(level, id);
                                if (disc == null) {
                                    skipNotes.put(i, "no-disc");
                                    LOGGER.info("[cimscene] gallery-variant skip kind=jukebox ns={} page={} index={} "
                                            + "reason=no-disc subject={}", namespace, page, from + i, id);
                                    yield true; // the row is already named; no block
                                }
                                level.setBlock(pos, Blocks.JUKEBOX.defaultBlockState(), Block.UPDATE_CLIENTS);
                                if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity be) {
                                    be.setTheItem(disc);
                                    be.getSongPlayer().play(level, dynamicHolder(level, Registries.JUKEBOX_SONG, id));
                                    be.setChanged();
                                    yield true;
                                }
                                yield false;
                            }
                            default -> false;
                        };
                        if (placed && !skipNotes.containsKey(i)) {
                            kindBlocks++;
                        }
                    } catch (Throwable t) {
                        failed++;
                        skipNotes.putIfAbsent(i, "failed:" + t.getClass().getName());
                        LOGGER.info("[cimscene] gallery-variant skip kind={} ns={} page={} index={} "
                                + "reason=failed subject={} err={}", kind, namespace, page, from + i, id, t.toString());
                    }
                }
            }
            case "blockentity" -> {
                // 14-ec THE BLOCKENTITY LANE (the coverage audit's named lane
                // for the special-BE class): the reconciliation predicate
                // names every BE type whose every valid state is
                // fluid-carried or RenderShape.INVISIBLE — the types the
                // BLOCK WALL structurally cannot carry (the sign/canvas
                // families render wholly through their BlockEntityRenderer,
                // the skull/head families attach only through their items,
                // the multiblock/moving internals never exist as bare wall
                // states). The variant lane instantiates EACH type at its
                // own cell through the subject's REAL placement surface:
                // the BlockItem used against the z+1 support plane (the same
                // measured item route the gui lanes ride — the placement
                // that produces the real BER content), falling back to the
                // direct setBlock of a valid state when no item exists. The
                // settle pass asserts the BE TYPE the world HOLDS at the
                // cell (the coverage contract: the type instantiated, not a
                // lookalike block); a type the lane cannot instantiate is a
                // named skip row the gate keeps red.
                fillPaintingSupport(level);
                net.neoforged.neoforge.common.util.FakePlayer placer =
                        new net.neoforged.neoforge.common.util.FakePlayer(level, VARIANT_BE_PLACER);
                placer.moveTo(GALLERY_X0 + 5.0, GALLERY_Y0 + 3.0, GALLERY_Z - 2.0, 180.0f, 0.0f);
                for (int i = 0; i < slice.size(); i++) {
                    ResourceLocation id = slice.get(i);
                    int col = i % GALLERY_COLS, row = i / GALLERY_COLS;
                    BlockPos pos = new BlockPos(GALLERY_X0 + col, GALLERY_Y0 + row, GALLERY_Z);
                    try {
                        BePlacement plan = bePlacementPlan(id);
                        if (plan == null) {
                            skipNotes.put(i, "no-valid-state");
                            LOGGER.info("[cimscene] gallery-variant skip kind=blockentity ns={} page={} "
                                            + "index={} reason=no-valid-state subject={}",
                                    namespace, page, from + i, id);
                            continue;
                        }
                        boolean placed = false;
                        String route = "setblock";
                        if (plan.item() != null) {
                            route = "item";
                            ItemStack stack = new ItemStack(plan.item());
                            placer.setItemInHand(InteractionHand.MAIN_HAND, stack);
                            BlockPos support = pos.south();
                            BlockHitResult hit = new BlockHitResult(
                                    Vec3.atCenterOf(support).relative(Direction.NORTH, 0.5),
                                    Direction.NORTH, support, false);
                            try {
                                placed = stack.useOn(new net.minecraft.world.item.context.UseOnContext(
                                        placer, InteractionHand.MAIN_HAND, hit)).consumesAction();
                            } finally {
                                placer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                            }
                        }
                        if (!placed) {
                            route = "setblock";
                            level.setBlock(pos, plan.state(), Block.UPDATE_CLIENTS);
                            placed = !level.getBlockState(pos).isAir();
                        }
                        if (placed) {
                            kindBlocks++;
                            LOGGER.info("[cimscene] gallery-variant blockentity ns={} page={} index={} "
                                            + "subject={} route={}", namespace, page, from + i, id, route);
                        } else {
                            skipNotes.put(i, "failed:place");
                            LOGGER.info("[cimscene] gallery-variant skip kind=blockentity ns={} page={} "
                                            + "index={} reason=failed:place subject={}",
                                    namespace, page, from + i, id);
                        }
                    } catch (Throwable t) {
                        failed++;
                        skipNotes.putIfAbsent(i, "failed:" + t.getClass().getName());
                        LOGGER.info("[cimscene] gallery-variant skip kind=blockentity ns={} page={} "
                                        + "index={} reason=failed subject={} err={}",
                                namespace, page, from + i, id, t.toString());
                    }
                }
            }
            default -> {
                // variantKindKnown guards this; unreachable
            }
        }

        final ServerLevel fLevel = level;
        final String fNs = namespace;
        final int fPage = page;
        final List<ResourceLocation> fSlice = slice;
        final int fFrom = from;
        final int fFailed = failed;
        final String fKind = kind;
        final int fKindBlocks = kindBlocks;
        runAfterTicks(level.getServer(), ENTITY_SETTLE_TICKS, () -> settleVariantPage(
                fLevel, fKind, fNs, fPage, fSlice, spawned, spawnedIdx, skipNotes, fFrom, gen,
                fFailed, fKindBlocks, false));
        return spawned.size() + kindBlocks;
    }

    /** The known variant kinds (the dispatcher's kind argument is free-form
     *  text — an unknown kind must fail LOUDLY, not walk nothing). */
    private static boolean variantKindKnown(String kind) {
        return switch (kind) {
            case "painting", "wolf", "cat", "frog", "villager", "effect",
                 "trimmaterial", "trimpattern", "banner", "pot", "enchant",
                 "jukebox", "blockentity" -> true;
            default -> false;
        };
    }

    /** 10 minutes: the effect/jukebox assertions must outlive the settle
     *  window and the shot, never the walk (pages are killed per page). */
    private static final int EFFECT_DURATION = 20 * 60 * 10;

    /** Sorted ids of the namespace from the kind's registry; null = the
     *  dynamic registry itself is unreadable (the honest walk termination). */
    private static List<ResourceLocation> variantEntries(ServerLevel level, String kind, String namespace) {
        return switch (kind) {
            // dynamic (datapack) registries — read through the level's access
            case "painting" -> dynamicIds(level, Registries.PAINTING_VARIANT, namespace);
            case "wolf" -> dynamicIds(level, Registries.WOLF_VARIANT, namespace);
            case "trimmaterial" -> dynamicIds(level, Registries.TRIM_MATERIAL, namespace);
            case "trimpattern" -> dynamicIds(level, Registries.TRIM_PATTERN, namespace);
            case "banner" -> dynamicIds(level, Registries.BANNER_PATTERN, namespace);
            case "enchant" -> dynamicIds(level, Registries.ENCHANTMENT, namespace);
            case "jukebox" -> dynamicIds(level, Registries.JUKEBOX_SONG, namespace);
            // static BuiltIn registries
            case "cat" -> sortedIdsOf(BuiltInRegistries.CAT_VARIANT, namespace);
            case "frog" -> sortedIdsOf(BuiltInRegistries.FROG_VARIANT, namespace);
            case "villager" -> sortedIdsOf(BuiltInRegistries.VILLAGER_TYPE, namespace);
            case "effect" -> sortedIdsOf(BuiltInRegistries.MOB_EFFECT, namespace);
            case "pot" -> sortedIdsOf(BuiltInRegistries.DECORATED_POT_PATTERN, namespace);
            case "blockentity" -> sortedIdsOf(BuiltInRegistries.BLOCK_ENTITY_TYPE, namespace);
            default -> List.of();
        };
    }

    /** Sorted ids of a static (BuiltIn) registry for one namespace. */
    private static List<ResourceLocation> sortedIdsOf(Registry<?> reg, String namespace) {
        List<ResourceLocation> ids = new ArrayList<>(reg.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(namespace)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Sorted ids of a namespace from a DATAPACK (dynamic) registry; null
     *  when the registry itself cannot be read — the walk terminates
     *  honestly (the ledger's -1 row already reds the coverage). */
    @SuppressWarnings("unchecked")
    private static List<ResourceLocation> dynamicIds(ServerLevel level,
                                                     ResourceKey<? extends Registry<?>> key,
                                                     String namespace) {
        try {
            Registry<?> reg = level.registryAccess().registryOrThrow((ResourceKey) key);
            return sortedIdsOf(reg, namespace);
        } catch (Throwable t) {
            LOGGER.error("[cimscene] gallery-variant dynamic registry unreadable {}: {}",
                    key.location(), t.toString());
            return null;
        }
    }

    /** Holder from a static (BuiltIn) registry by id. */
    private static <T> Holder<T> staticHolder(Registry<T> reg, ResourceLocation id) {
        return reg.getHolderOrThrow(ResourceKey.create(reg.key(), id));
    }

    /** Holder from a dynamic (datapack) registry through the level's access. */
    @SuppressWarnings("unchecked")
    private static <T> Holder<T> dynamicHolder(ServerLevel level,
                                               ResourceKey<? extends Registry<T>> key,
                                               ResourceLocation id) {
        return level.registryAccess().registryOrThrow(key)
                .getHolderOrThrow(ResourceKey.create(key, id));
    }

    /** First sorted id of a dynamic registry (the fixed half of the
     *  trim pairing — deterministic on both stacks, same registries). */
    private static ResourceLocation firstIdOrThrow(ServerLevel level,
                                                   ResourceKey<? extends Registry<?>> key) {
        List<ResourceLocation> ids = dynamicIds(level, key, "");
        // dynamicIds never filters the minecraft ns away; the full sorted
        // list's first entry is the pairing constant
        if (ids == null || ids.isEmpty()) {
            throw new IllegalStateException("empty dynamic registry " + key.location());
        }
        return ids.get(0);
    }

    /**
     * Freeze contract for the variant subjects (the entity walk's measured
     * rules): Mobs get NoAI + persistence (a still picture), everything gets
     * NoGravity + Invulnerable. Returns false when the subject is not a
     * creature this walk knows how to freeze (never expected — the kinds
     * spawn Wolf/Cat/Frog/Villager/Pig/ArmorStand by construction).
     */
    private static boolean freezeVariantSubject(Entity e) {
        e.setNoGravity(true);
        e.setInvulnerable(true);
        if (e instanceof Mob m) {
            m_setNoAi(m);
        } else if (!(e instanceof ArmorStand)) {
            return false;
        }
        return true;
    }

    /**
     * The painting support wall: smooth stone filling the plane BEHIND the
     * wall grid (z = GALLERY_Z + 1) so every hung painting passes the vanilla
     * {@code HangingEntity#survives()} check (the attached block is
     * {@code direction.getOpposite()} = south = z+1 for the north-facing
     * paintings). Cleared by every page's clearWall first — the fill is the
     * painting kind's own addition.
     */
    private static void fillPaintingSupport(ServerLevel level) {
        for (int x = GALLERY_X0 - 1; x <= GALLERY_X0 + GALLERY_COLS; x++) {
            for (int y = GALLERY_Y0 - 1; y <= GALLERY_Y0 + GALLERY_ROWS; y++) {
                level.setBlock(new BlockPos(x, y, GALLERY_Z + 1),
                        Blocks.SMOOTH_STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Shared stale-subject sweep (the entity walk's box and tag contract). */
    private static void killStaleGalleryEntities(ServerLevel level) {
        AABB sweep = new AABB(GALLERY_X0 - 1, GALLERY_Y0, GALLERY_Z - 1,
                GALLERY_X0 + GALLERY_COLS + 1, GALLERY_Y0 + GALLERY_ROWS + 3, GALLERY_Z + 2);
        List<Entity> stale = level.getEntitiesOfClass(Entity.class, sweep,
                e -> e.getTags().contains(GALLERY_TAG));
        for (Entity e : stale) {
            e.discard();
        }
        if (!stale.isEmpty()) {
            LOGGER.info("[cimscene] gallery stale-entity sweep removed={}", stale.size());
        }
    }

    // --- 14-ea/14-eb/14-ec: the dissolve-class and special-BE instruments --

    /**
     * 14-ea THE MOTION PIN — the frozen-subject law applied to the entity
     * walk's projectile rows (the measured #42899212217 verdict: supplementaries
     * bomb / brick_projectile / cannonball / falling_ash / falling_sack /
     * falling_urn / pearl_marker / slimeball_projectile and dynamictrees
     * falling_tree / lingering_effector all settled as {@code dissolved}).
     * The spawn path already writes NoGravity+Invulnerable, but a projectile's
     * OWN tick re-applies its hardcoded gravity (FallingBlockEntity.tick and
     * ThrowableProjectile.tick both {@code setDeltaMovement(+g)} INSIDE the
     * tick body, ungated by NoGravity) and self-animating subjects fly, fall
     * and explode before the settle pass re-reads them 40 ticks later — every
     * such subject settled as {@code dissolved} (gone or drift &gt; 1.0).
     * The pin re-zeros each spawn's delta movement EVERY server tick from the
     * spawn through the settle and the shot window ({@link
     * #MOTION_PIN_GRACE_TICKS}), so the cell position the manifest carries is
     * the position the shot draws. The subjects' own tick timers still run
     * (no fabricated state, no per-mod code): a fuse that expires inside the
     * window still dies, and the manifest honestly names it — the pin buys
     * the physics window, not immortality.
     */
    private static final java.util.Map<Entity, Integer> MOTION_PINNED =
            new java.util.IdentityHashMap<>();
    /** The settle (40 ticks) plus the shot window the pin must outlive. */
    static final int MOTION_PIN_GRACE_TICKS = 160;

    private static void drainMotionPin(int now) {
        if (MOTION_PINNED.isEmpty()) {
            return;
        }
        MOTION_PINNED.entrySet().removeIf(en -> {
            Entity e = en.getKey();
            if (now >= en.getValue() || e.isRemoved()) {
                return true;
            }
            try {
                e.setDeltaMovement(Vec3.ZERO);
            } catch (Throwable ignored) {
                // a pin failure must never kill the tick loop — the settle
                // pass re-reads the real state and names the drifted subject
            }
            return false;
        });
    }

    /**
     * 14-eb THE SPAWN-SUPPORT TABLE: the subjects that only live on their
     * own support block, placed at the cell's ground BEFORE the spawn (the
     * same shape the painting kind's support wall uses — the support rides
     * the cell's picture on both legs because both stacks run this same
     * code). mekanism:robit removes itself when it has no charge pad to call
     * home (the measured #42899212217 entity-mobs verdict named
     * {@code mekanism:robit(dissolved)} while every other frozen mob held
     * its cell; the gui lane's bare-spawn comment already recorded the same
     * removal) — the pad-first placement is the named fix. A subject without
     * a table entry places nothing (zero behavior change); a table entry
     * whose block is absent from the registry is a named {@code no-support}
     * skip row (honest, never a crash).
     */
    private static ResourceLocation spawnSupportFor(ResourceLocation id) {
        if ("mekanism:robit".equals(id.toString())) {
            return ResourceLocation.parse("mekanism:chargepad");
        }
        return null;
    }

    /** The 14-ec blockentity lane's placer identity (the same shape the
     *  GuiProbe profile uses — a fixed UUID so the placement is
     *  deterministic run to run). */
    private static final com.mojang.authlib.GameProfile VARIANT_BE_PLACER =
            new com.mojang.authlib.GameProfile(
                    java.util.UUID.nameUUIDFromBytes("cimscene-vbe".getBytes(StandardCharsets.UTF_8)),
                    "cimscene-vbe");

    /** The 14-ec per-type placement plan: a valid placeable state plus the
     *  item that places it (null when the block ships no BlockItem). */
    private record BePlacement(BlockState state, Item item) {}

    private static volatile Map<String, BePlacement> BE_PLACEMENTS;
    private static final Object BE_PLACEMENT_LOCK = new Object();

    /** The 14-ec plan for one BE type id; null when the type has no valid
     *  non-fluid block state at all (nothing in the world can instantiate
     *  it — the honest {@code no-valid-state} skip). */
    private static BePlacement bePlacementPlan(ResourceLocation beTypeId) {
        Map<String, BePlacement> m = BE_PLACEMENTS;
        if (m == null) {
            synchronized (BE_PLACEMENT_LOCK) {
                if (BE_PLACEMENTS == null) {
                    BE_PLACEMENTS = buildBePlacements();
                }
                m = BE_PLACEMENTS;
            }
        }
        return m.get(beTypeId.toString());
    }

    /**
     * The 14-ec plan builder (once per server, the reconciliation's own
     * O(states x BE-types) cost): for each BE type, the first valid
     * fluid-empty state with a VISIBLE render shape (the wall-reachable
     * case — the item route produces the real placement), else the first
     * fluid-empty valid state (the INVISIBLE families the block wall skips
     * — the exact class the lane exists for), plus the block's BlockItem
     * when one exists. Types with no valid non-fluid state map to no plan.
     */
    private static Map<String, BePlacement> buildBePlacements() {
        Map<Block, Item> blockItems = new java.util.HashMap<>();
        for (ResourceLocation iid : BuiltInRegistries.ITEM.keySet()) {
            Item it = BuiltInRegistries.ITEM.get(iid);
            if (it instanceof net.minecraft.world.item.BlockItem bi) {
                blockItems.putIfAbsent(bi.getBlock(), it);
            }
        }
        Map<String, BePlacement> out = new java.util.HashMap<>();
        for (ResourceLocation beId : BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()) {
            BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(beId);
            if (type == null) {
                continue;
            }
            BlockState chosen = null;
            BlockState fallback = null;
            for (ResourceLocation bid : BuiltInRegistries.BLOCK.keySet()) {
                for (BlockState st : BuiltInRegistries.BLOCK.get(bid)
                        .getStateDefinition().getPossibleStates()) {
                    if (!type.isValid(st) || !st.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (st.getRenderShape() != RenderShape.INVISIBLE) {
                        chosen = st;
                        break;
                    }
                    if (fallback == null) {
                        fallback = st;
                    }
                }
                if (chosen != null) {
                    break;
                }
            }
            if (chosen == null) {
                chosen = fallback;
            }
            if (chosen == null) {
                continue;
            }
            out.put(beId.toString(), new BePlacement(chosen, blockItems.get(chosen.getBlock())));
        }
        return java.util.Collections.unmodifiableMap(out);
    }


    /** Sherd item for a decorated-pot pattern id (the vanilla item->pattern
     *  link scanned once per boot and cached; BLANK never resolves — the
     *  blank pattern has no sherd by vanilla's own construction). */
    private static Item potSherdForPattern(ResourceLocation patternId) {
        Map<ResourceLocation, Item> sherds = potSherds();
        return sherds.get(patternId);
    }

    private static volatile Map<ResourceLocation, Item> POT_SHERDS;
    private static final Object POT_SHERD_LOCK = new Object();

    private static Map<ResourceLocation, Item> potSherds() {
        Map<ResourceLocation, Item> m = POT_SHERDS;
        if (m != null) {
            return m;
        }
        synchronized (POT_SHERD_LOCK) {
            if (POT_SHERDS == null) {
                Map<ResourceLocation, Item> built = new java.util.HashMap<>();
                for (ResourceLocation itemId : BuiltInRegistries.ITEM.keySet()) {
                    Item item = BuiltInRegistries.ITEM.get(itemId);
                    if (item == null) {
                        continue;
                    }
                    ResourceKey<net.minecraft.world.level.block.entity.DecoratedPotPattern> key =
                            DecoratedPotPatterns.getPatternFromItem(item);
                    if (key == null || DecoratedPotPatterns.BLANK.equals(key)) {
                        continue;
                    }
                    built.putIfAbsent(key.location(), item);
                }
                POT_SHERDS = java.util.Collections.unmodifiableMap(built);
                LOGGER.info("[cimscene] gallery-variant sherd index built: {} pattern->sherd mappings", built.size());
            }
            return POT_SHERDS;
        }
    }

    /**
     * The disc whose {@code minecraft:jukebox_playable} component resolves
     * to the song id (the vanilla item->song link scanned once per boot and
     * cached; a song with no disc is a named {@code no-disc} skip — real
     * content the wall cannot host by vanilla's own mapping).
     */
    private static ItemStack discForSong(ServerLevel level, ResourceLocation songId) {
        Map<ResourceLocation, ResourceLocation> discs = jukeboxDiscs(level);
        ResourceLocation itemId = discs.get(songId);
        if (itemId == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        return item == null ? null : new ItemStack(item);
    }

    private static volatile Map<ResourceLocation, ResourceLocation> JUKEBOX_DISCS;
    private static final Object JUKEBOX_DISC_LOCK = new Object();

    private static Map<ResourceLocation, ResourceLocation> jukeboxDiscs(ServerLevel level) {
        Map<ResourceLocation, ResourceLocation> m = JUKEBOX_DISCS;
        if (m != null) {
            return m;
        }
        synchronized (JUKEBOX_DISC_LOCK) {
            if (JUKEBOX_DISCS == null) {
                Map<ResourceLocation, ResourceLocation> built = new java.util.HashMap<>();
                for (ResourceLocation itemId : BuiltInRegistries.ITEM.keySet()) {
                    Item item = BuiltInRegistries.ITEM.get(itemId);
                    if (item == null) {
                        continue;
                    }
                    try {
                        // the disc's DEFAULT components carry the playable
                        // song — a fresh stack resolves it
                        var song = JukeboxSong.fromStack(level.registryAccess(), new ItemStack(item));
                        if (song.isEmpty()) {
                            continue;
                        }
                        song.get().unwrapKey().ifPresent(k ->
                                built.putIfAbsent(k.location(), itemId));
                    } catch (Throwable ignored) {
                        // an item whose components cannot parse is not a disc
                    }
                }
                JUKEBOX_DISCS = java.util.Collections.unmodifiableMap(built);
                LOGGER.info("[cimscene] gallery-variant disc index built: {} song->disc mappings", built.size());
            }
            return JUKEBOX_DISCS;
        }
    }

    /**
     * The variant settle pass (the 14-di law: the manifest carries what the
     * world HOLDS, not what was placed). Pass 1 writes the manifest + the
     * done marker; the 12-as final rewrite runs {@link #ENTITY_FINAL_TICKS}
     * later, rewrites the manifest, writes no marker. Per kind the
     * assertions are the RUNTIME proofs the audit item asks for:
     * <ul>
     *   <li>entity-grid kinds: alive + within 1.0 of the cell (the entity
     *       walk's dissolved/moved contract), then the kind's own state —
     *       the effect is STILL ACTIVE, the worn trim STILL PRESENT, the
     *       glint STILL ENCHANTED;</li>
     *   <li>block-grid kinds: the block still the kind's block (a popped
     *       banner/pot/jukebox is a named dissolved), then the BE state —
     *       the banner patterns non-empty, the pot decorations non-empty,
     *       the jukebox song STILL PLAYING.</li>
     * </ul>
     */
    private static void settleVariantPage(ServerLevel level, String kind, String namespace, int page,
                                          List<ResourceLocation> slice,
                                          List<Entity> spawned, List<Integer> spawnedIdx,
                                          java.util.Map<Integer, String> skipNotes, int from, int gen,
                                          int failed, int kindBlocks, boolean finalPass) {
        if (gen != entityPageGen) {
            LOGGER.info("[cimscene] gallery-variant settle superseded kind={} ns={} page={} finalPass={}",
                    kind, namespace, page, finalPass);
            return;
        }
        boolean entityGrid = variantEntityGrid(kind);
        int cols = entityGrid ? ENTITY_COLS : GALLERY_COLS;
        int pitch = entityGrid ? ENTITY_PITCH : 1;
        String[] rowStr = new String[slice.size()];
        int cells = 0, dissolved = 0, absent = 0;
        for (int j = 0; j < spawned.size(); j++) {
            Entity e = spawned.get(j);
            int i = spawnedIdx.get(j);
            ResourceLocation id = slice.get(i);
            int col = i % cols, row = i / cols;
            if (skipNotes.containsKey(i)) {
                rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t" + variantDyn(kind) + "\t" + skipNotes.get(i);
                continue;
            }
            double cx = GALLERY_X0 + col * pitch + (entityGrid ? 0.5 : 0.0);
            double cy = GALLERY_Y0 + row * pitch;
            double cz = GALLERY_Z + (entityGrid ? 0.5 : 0.0);
            boolean alive = e.isAlive() && !e.isRemoved();
            double drift = alive ? e.position().distanceTo(new Vec3(cx, cy, cz)) : -1.0;
            if (!alive || drift > 1.0) {
                dissolved++;
                rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t" + variantDyn(kind) + "\tdissolved";
                LOGGER.info("[cimscene] gallery-variant settle kind={} ns={} page={} index={} reason={} "
                        + "subject={} drift={}", kind, namespace, page, from + i,
                        alive ? "moved" : "dissolved", id, drift);
                continue;
            }
            String note = variantRuntimeNote(level, kind, id, e);
            if (note != null) {
                absent++;
                rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t" + variantDyn(kind) + "\t" + note;
                LOGGER.info("[cimscene] gallery-variant settle kind={} ns={} page={} index={} reason={} "
                        + "subject={}", kind, namespace, page, from + i, note, id);
                continue;
            }
            cells++;
            rowStr[i] = "cell\t" + (GALLERY_X0 + col * pitch) + '\t' + (GALLERY_Y0 + row * pitch)
                    + '\t' + GALLERY_Z + '\t' + id + '\t' + variantDyn(kind) + "\tok";
        }
        if (!entityGrid) {
            // block-grid kinds: every cell re-read from the world (the block
            // walk's settle law) — the placed state can pop, and the BE can
            // lose its pattern/sherd/song only if the block itself went away
            for (int i = 0; i < slice.size(); i++) {
                ResourceLocation id = slice.get(i);
                int col = i % cols, row = i / cols;
                BlockPos pos = new BlockPos(GALLERY_X0 + col, GALLERY_Y0 + row, GALLERY_Z);
                if (skipNotes.containsKey(i)) {
                    rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t" + variantDyn(kind) + "\t" + skipNotes.get(i);
                    continue;
                }
                if (level.getBlockState(pos).isAir()) {
                    dissolved++;
                    rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t" + variantDyn(kind) + "\tdissolved";
                    LOGGER.info("[cimscene] gallery-variant settle kind={} ns={} page={} index={} "
                            + "reason=dissolved subject={}", kind, namespace, page, from + i, id);
                    continue;
                }
                String note = variantBlockNote(level, kind, pos, id);
                if (note != null) {
                    absent++;
                    rowStr[i] = "skip\t-1\t-1\t-1\t" + id + "\t" + variantDyn(kind) + "\t" + note;
                    LOGGER.info("[cimscene] gallery-variant settle kind={} ns={} page={} index={} reason={} "
                            + "subject={}", kind, namespace, page, from + i, note, id);
                    continue;
                }
                cells++;
                rowStr[i] = "cell\t" + pos.getX() + '\t' + pos.getY() + '\t' + pos.getZ()
                        + '\t' + id + '\t' + variantDyn(kind) + "\tok";
            }
        }
        StringBuilder tsv = new StringBuilder("cell\tx\ty\tz\tsubject\tdyn\tnote\n");
        for (int i = 0; i < slice.size(); i++) {
            if (rowStr[i] == null && skipNotes.containsKey(i)) {
                rowStr[i] = "skip\t-1\t-1\t-1\t" + slice.get(i) + "\t" + variantDyn(kind) + "\t"
                        + skipNotes.get(i);
            }
            if (rowStr[i] != null) {
                tsv.append(rowStr[i]).append('\n');
            }
        }
        String manifest = "gallery-variant-" + kind + "-" + namespace + "-p" + page + ".tsv";
        try {
            Path parent = Path.of("c-imagine", "scenes");
            Files.createDirectories(parent);
            Files.writeString(parent.resolve(manifest), tsv.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            LOGGER.error("[cimscene] gallery-variant manifest write failed {}: {}", manifest, ioe.toString());
        }
        if (finalPass) {
            LOGGER.info("[cimscene] gallery-variant settle-final kind={} ns={} page={} cells={} absent={} "
                            + "dissolved={} (manifest rewritten inside the shot window; no done marker)",
                    kind, namespace, page, cells, absent, dissolved);
            writeMarkerFile("gallery-variant-" + kind + "-" + namespace + "-p" + page + ".settled");
            return;
        }
        LOGGER.info("[cimscene] gallery-variant done kind={} ns={} page={} cells={} absent={} dissolved={} "
                        + "failed={} skipped={} first={} last={}",
                kind, namespace, page, cells, absent, dissolved, failed,
                slice.get(0), slice.get(slice.size() - 1));
        writeDoneMarkerFile("gallery-variant-" + kind + "-" + namespace + "-p" + page, false);
        runAfterTicks(level.getServer(), ENTITY_FINAL_TICKS, () -> settleVariantPage(
                level, kind, namespace, page, slice, spawned, spawnedIdx, skipNotes, from, gen,
                failed, kindBlocks, true));
    }

    /** The dyn flag per kind (the join's diagnostic demotion channel): the
     *  ambient effect particles and the glint shimmer are time-varying by
     *  vanilla's own rendering, the jukebox note particles likewise; every
     *  other kind renders a still. */
    private static int variantDyn(String kind) {
        return switch (kind) {
            case "effect", "enchant", "jukebox", "blockentity" -> 1;
            default -> 0;
        };
    }

    /**
     * The kind's runtime state assertion on a live entity subject; null =
     * the state HOLDS. A non-null return is a named absence note (the gate's
     * honesty channel — content that proves itself absent is not green).
     */
    private static String variantRuntimeNote(ServerLevel level, String kind, ResourceLocation id, Entity e) {
        try {
            return switch (kind) {
                case "effect" -> {
                    if (!(e instanceof net.minecraft.world.entity.LivingEntity le)) {
                        yield "effect-expired";
                    }
                    Holder<net.minecraft.world.effect.MobEffect> holder =
                            staticHolder(BuiltInRegistries.MOB_EFFECT, id);
                    yield le.getEffect(holder) != null ? null : "effect-expired";
                }
                case "trimmaterial", "trimpattern" -> {
                    if (!(e instanceof ArmorStand stand)) {
                        yield "trim-missing";
                    }
                    ItemStack chest = stand.getItemBySlot(EquipmentSlot.CHEST);
                    yield chest.get(DataComponents.TRIM) != null ? null : "trim-missing";
                }
                case "enchant" -> {
                    if (!(e instanceof ArmorStand stand)) {
                        yield "glint-missing";
                    }
                    ItemStack held = stand.getItemBySlot(EquipmentSlot.MAINHAND);
                    ItemEnchantments ench = held.get(DataComponents.ENCHANTMENTS);
                    yield (ench != null && ench.size() > 0) ? null : "glint-missing";
                }
                default -> null;
            };
        } catch (Throwable t) {
            return "assert-failed:" + t.getClass().getName();
        }
    }

    /** The kind's runtime state assertion on a block-grid subject; null =
     *  the state HOLDS. */
    private static String variantBlockNote(ServerLevel level, String kind, BlockPos pos, ResourceLocation id) {
        try {
            return switch (kind) {
                case "banner" -> {
                    if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) {
                        yield "pattern-missing";
                    }
                    yield be.getPatterns().layers().isEmpty() ? "pattern-missing" : null;
                }
                case "pot" -> {
                    if (!(level.getBlockEntity(pos) instanceof DecoratedPotBlockEntity be)) {
                        yield "sherd-missing";
                    }
                    PotDecorations dec = be.getDecorations();
                    yield (dec.back().isEmpty() && dec.left().isEmpty()
                            && dec.right().isEmpty() && dec.front().isEmpty())
                            ? "sherd-missing" : null;
                }
                case "jukebox" -> {
                    if (!(level.getBlockEntity(pos) instanceof JukeboxBlockEntity be)) {
                        yield "play-failed";
                    }
                    yield be.getSongPlayer().isPlaying() ? null : "stopped";
                }
                case "blockentity" -> {
                    // the coverage contract: the BE TYPE the world HOLDS at
                    // the cell is the subject id (a lookalike block without
                    // the type's BE is not coverage)
                    BlockEntity be = level.getBlockEntity(pos);
                    ResourceLocation actual = be == null ? null
                            : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                    yield id.equals(actual) ? null : "be-missing";
                }
                default -> null;
            };
        } catch (Throwable t) {
            return "assert-failed:" + t.getClass().getName();
        }
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
        // (sky/fog). 14-dx: the no-walk backlog (the roadmap's "No-walk
        // visual classes" audit item) SHIPPED ITS REAL SCENES — the
        // `gallery variant <kind>` walk below places/spawns one entry per
        // cell and the gate walks the classes DIAGNOSTIC-first (the
        // 2026-11-04 sound/gui/biome pattern). dimension_type has no
        // per-entry surface inside ONE world frame (its picture is the
        // whole dimension's sky/fog) and stays the documented no-walk line.
        // (The biome row graduated 2026-11-04: the fillbiome tint walk
        // exists, DIAGNOSTIC-first.) Worldgen plumbing (features, loot,
        // predicates, attributes...) shapes the world, not a gallery cell,
        // and stays outside the world-frame ledger (documented line,
        // challengeable).
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
                // no pages of its own UNTIL 14-ec: the BE class's picture is
                // drawn by the block wall (real EntityBlock placement) — the
                // variant-blockentity lane now WALKS the class at 77 cells
                // per page (the item/setBlock instantiation through the
                // subject's own placement surface); the reconciliation file
                // below stays the static wall-skip receipt and the gate
                // crosses the walked names off it
                .append("blockentity\t").append(blockEntities).append('\t').append(VARIANT_CELLS_BLOCK).append('\n')
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
                // the no-walk classes (12-aq) GRADUATED (14-dx): every class
                // but dimension_type now has a real `gallery variant` walk —
                // entity-grid kinds at 18 cells (pitch 2: the subjects and
                // the 2x2 paintings never overlap their neighbours, the
                // measured entity-grid law), block-grid kinds at 77. A
                // negative total means the dynamic registry itself could not
                // be READ at index time (the gate reds that honestly)
                .append("painting_variant\t").append(paintingVariants).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("wolf_variant\t").append(wolfVariants).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("cat_variant\t").append(catVariants).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("frog_variant\t").append(frogVariants).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("villager_type\t").append(villagerTypes).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("mob_effect\t").append(mobEffects).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("trim_material\t").append(trimMaterials).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("trim_pattern\t").append(trimPatterns).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("enchantment\t").append(enchantments).append("\t").append(VARIANT_CELLS_ENTITY).append('\n')
                .append("banner_pattern\t").append(bannerPatterns).append("\t").append(GALLERY_CELLS).append('\n')
                .append("decorated_pot_pattern\t").append(potPatterns).append("\t").append(GALLERY_CELLS).append('\n')
                .append("jukebox_song\t").append(jukeboxSongs).append("\t").append(GALLERY_CELLS).append('\n')
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
        closeGalleryAssemblies();
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
                Files.writeString(parent.resolve(fluidStem + ".light"),
                        CaptureLightData.snapshot(level, settled.toString()), StandardCharsets.UTF_8);
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
        if (plain.startsWith("specialized:")) {
            SpecializedOpeners.place(level, player, rig, plain.substring("specialized:".length()), e -> {
                guiEntity = e;
                GUI_SPAWNED.add(e);
            });
        } else if (plain.startsWith("cube")) {
            guiBuildCube(level, player, rig, plain);
        } else if (plain.startsWith("settle+use+item:")) {
            // The block first; the item is used once it has ticked (see guiActOnce).
            String block = plain.substring(plain.indexOf('@') + 1);
            level.setBlock(rig, BuiltInRegistries.BLOCK.get(ResourceLocation.parse(block)).defaultBlockState(), 3);
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

    /** Native station/item assembly owns the train, carriage, graph and entity. */
    private static final class TrainRig {
        final ServerLevel level;
        final BlockPos cell;
        final BlockPos target;
        final BlockPos stationPos;
        final ServerPlayer player;
        final Map<BlockPos, BlockState> previous = new java.util.LinkedHashMap<>();
        final java.util.List<Object> trains = new ArrayList<>();
        final java.util.Set<java.util.UUID> previousGlue = new java.util.HashSet<>();
        Object station;
        Direction direction;
        Entity entity;
        Entity glue;
        net.minecraft.world.level.block.Block bogey;
        boolean closed;

        TrainRig(ServerLevel level, BlockPos cell) {
            this.level = level;
            this.cell = cell;
            target = cell.below().north();
            stationPos = cell.east(2);
            player = new GuiProbe(level);
            // Station targeting faces the assembly arrow toward the clicker.
            player.moveTo(cell.getX() + .5, cell.getY() + 2, cell.getZ() - 3, 180, 0);
            for (Entity found : level.getEntitiesOfClass(Entity.class, new AABB(cell).inflate(3))) {
                previousGlue.add(found.getUUID());
            }
        }

        @SuppressWarnings("unchecked")
        Map<java.util.UUID, Object> trainRegistry() throws ReflectiveOperationException {
            Object manager = Class.forName("com.simibubi.create.Create").getField("RAILWAYS").get(null);
            return (Map<java.util.UUID, Object>) manager.getClass().getField("trains").get(manager);
        }

        void put(BlockPos pos, BlockState state) {
            if (!previous.containsKey(pos) && level.getBlockEntity(pos) != null) {
                throw new IllegalStateException("Train rig cannot replace a foreign block entity at " + pos);
            }
            previous.putIfAbsent(pos.immutable(), level.getBlockState(pos));
            level.setBlock(pos, state, 3);
        }

        net.minecraft.world.item.context.UseOnContext context(BlockPos support) {
            return new net.minecraft.world.item.context.UseOnContext(player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(support).add(0, .5, 0),
                            Direction.UP, support, false));
        }

        void click(BlockPos support) {
            if (!player.getMainHandItem().getItem().useOn(context(support)).consumesAction()) {
                throw new IllegalStateException("Native train item refused " + support + ": " + player.getMainHandItem());
            }
        }

        void start(java.util.function.Consumer<Entity> ready, java.util.function.Consumer<Throwable> failed) {
            try {
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                    level.getChunkAt(cell.offset(x * 16, 0, z * 16));
                }
                var track = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:track"));
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(track.asItem()));
                // TWO PASSES (the fb29eb57 diagnosis named it: the walk's own
                // support block sits ON the track line at the gallery cell's z,
                // and TrackBlock.getStateForPlacement's native slope heuristic
                // turns the target rail ASCENDING (ZO -> AS) when its +z
                // neighbour is sturdy-top over open air — the track axes then
                // span two axes and enterAssemblyMode refuses (tryEnterAssembly
                // Mode's single-axis law). The lifetime cell has a clean line,
                // so its rails stay flat — the ONLY contextual difference.
                // Pass 1 clears the whole line (stone under, AIR on it), pass 2
                // lays the tracks against an already-rail-or-air neighbourhood,
                // exactly the state the passing lifetime context presents.
                for (int z = -12; z <= 12; z++) {
                    BlockPos rail = cell.below().offset(0, 0, z);
                    put(rail.below(), Blocks.STONE.defaultBlockState());
                    put(rail, Blocks.AIR.defaultBlockState());
                }
                for (int z = -12; z <= 12; z++) {
                    BlockPos rail = cell.below().offset(0, 0, z);
                    BlockState state = track.getStateForPlacement(new net.minecraft.world.item.context.BlockPlaceContext(context(rail.below())));
                    if (state == null) throw new IllegalStateException("Native track placement unavailable at " + rail);
                    put(rail, state);
                }
                put(stationPos.below(), Blocks.STONE.defaultBlockState());
                put(stationPos, Blocks.AIR.defaultBlockState());
                for (int y = 0; y <= 2; y++) put(cell.above(y), Blocks.AIR.defaultBlockState());
                runAfterTicks(level.getServer(), 20, () -> {
                    if (closed) return;
                    try {
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                                new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:track_station"))));
                        click(target);
                        click(stationPos.below());
                        station = level.getBlockEntity(stationPos);
                        if (station == null) throw new IllegalStateException("Native station item produced no station");
                        runAfterTicks(level.getServer(), 20, () -> assemble(ready, failed));
                    } catch (Throwable failure) {
                        failed.accept(failure);
                    }
                });
            } catch (Throwable failure) {
                failed.accept(failure);
            }
        }

        void assemble(java.util.function.Consumer<Entity> ready, java.util.function.Consumer<Throwable> failed) {
            if (closed) return;
            try {
                if (station.getClass().getMethod("getStation").invoke(station) == null) {
                    throw new IllegalStateException("Native station did not bind to its track graph");
                }
                if (!(boolean) station.getClass().getMethod("enterAssemblyMode", ServerPlayer.class).invoke(station, player)) {
                    // THE REFUSAL IS DIAGNOSED, NOT GUESSED (the 14-dg read:
                    // an opaque boolean sent the page red with no named
                    // condition). enterAssemblyMode refuses on exactly two
                    // native conditions: the station block is already in
                    // assembling state, or tryEnterAssemblyMode rejected
                    // (hasValidTrack false / the track axes span more than
                    // one axis). Name the one that fired.
                    Object assembling = station.getClass().getMethod("isAssembling").invoke(station);
                    Object edgePoint = station.getClass().getField("edgePoint").get(station);
                    boolean validTrack = false;
                    String axes = "unavailable";
                    BlockPos targetPos = null;
                    BlockState trackState = null;
                    try {
                        validTrack = (boolean) edgePoint.getClass().getMethod("hasValidTrack").invoke(edgePoint);
                        targetPos = (BlockPos) edgePoint.getClass().getMethod("getGlobalPosition").invoke(edgePoint);
                        trackState = (BlockState) edgePoint.getClass().getMethod("getTrackBlockState").invoke(edgePoint);
                        Object track = edgePoint.getClass().getMethod("getTrack").invoke(edgePoint);
                        axes = String.valueOf(track.getClass()
                                .getMethod("getTrackAxes", net.minecraft.world.level.Level.class, BlockPos.class, BlockState.class)
                                .invoke(track, level, targetPos, trackState));
                    } catch (Throwable diag) {
                        axes = "diag-failed:" + diag;
                    }
                    throw new IllegalStateException("Native station refused assembly mode; isAssembling=" + assembling
                            + ", hasValidTrack=" + validTrack
                            + ", trackPos=" + targetPos + ", trackState=" + trackState
                            + ", trackAxes=" + axes);
                }
                direction = (Direction) station.getClass().getMethod("getAssemblyDirection").invoke(station);
                if (!target.relative(direction).equals(cell.below())) {
                    throw new IllegalStateException("Native station selected unexpected assembly direction " + direction);
                }
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                        new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("create:railway_casing"))));
                BlockPos rail = cell.below();
                BlockState trackState = level.getBlockState(rail);
                Class<?> trackType = Class.forName("com.simibubi.create.content.trains.track.ITrackBlock");
                if (!(boolean) station.getClass().getMethod("trackClicked", net.minecraft.world.entity.player.Player.class,
                        net.minecraft.world.InteractionHand.class, trackType, BlockState.class, BlockPos.class)
                        .invoke(station, player, net.minecraft.world.InteractionHand.MAIN_HAND, trackState.getBlock(), trackState, rail)) {
                    throw new IllegalStateException("Native station refused bogey placement");
                }
                bogey = level.getBlockState(cell).getBlock();
                if (!Class.forName("com.simibubi.create.content.trains.bogey.AbstractBogeyBlock").isInstance(bogey)) {
                    throw new IllegalStateException("Native station did not place an actual bogey: " + level.getBlockState(cell));
                }
                put(cell.above(), Blocks.OAK_PLANKS.defaultBlockState());
                put(cell.above(2), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:controls"))
                        .defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, direction));
                Class<?> glueType = Class.forName("com.simibubi.create.content.contraptions.glue.SuperGlueEntity");
                AABB volume = (AABB) glueType.getMethod("span", BlockPos.class, BlockPos.class).invoke(null, cell, cell.above(2));
                glue = (Entity) glueType.getConstructor(net.minecraft.world.level.Level.class, AABB.class).newInstance(level, volume);
                if (!level.addFreshEntity(glue)) throw new IllegalStateException("Native train glue insertion rejected");
                var registry = trainRegistry();
                var before = new java.util.HashSet<>(registry.keySet());
                try {
                    station.getClass().getMethod("assemble", java.util.UUID.class).invoke(station, player.getUUID());
                } finally {
                    registry.forEach((id, train) -> { if (!before.contains(id)) trains.add(train); });
                }
                if (trains.size() != 1) {
                    // Read the native reported error, without altering controller fields.
                    var error = station.getClass().getDeclaredField("lastException");
                    error.setAccessible(true);
                    throw new IllegalStateException("Native station registered " + trains.size() + " trains; error=" + error.get(station));
                }
                runAfterTicks(level.getServer(), 10, () -> {
                    if (closed) return;
                    try {
                        var carriages = (java.util.List<?>) trains.getFirst().getClass().getField("carriages").get(trains.getFirst());
                        if (carriages.size() != 1) throw new IllegalStateException("Native train carriage count=" + carriages.size());
                        Object carriage = carriages.getFirst();
                        entity = (Entity) carriage.getClass().getMethod("anyAvailableEntity").invoke(carriage);
                        if (entity == null || !entity.isAlive() || !entity.isAddedToLevel()) {
                            throw new IllegalStateException("Native train did not retain its carriage entity");
                        }
                        ready.accept(entity);
                    } catch (Throwable failure) {
                        failed.accept(failure);
                    }
                });
            } catch (Throwable failure) {
                failed.accept(failure);
            }
        }

        void check(Entity subject) throws ReflectiveOperationException {
            Object contraption = subject.getClass().getMethod("getContraption").invoke(subject);
            var blocks = (Map<?, ?>) contraption.getClass().getMethod("getBlocks").invoke(contraption);
            if (blocks.size() != 3 || !(blocks.get(BlockPos.ZERO) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo origin)
                    || !origin.state().is(bogey)
                    || !(blocks.get(BlockPos.ZERO.above()) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo plank)
                    || !plank.state().is(Blocks.OAK_PLANKS)
                    || !(blocks.get(BlockPos.ZERO.above(2)) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo controls)
                    || !controls.state().is(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:controls")))) {
                throw new IllegalStateException("Native carriage lost bogey/plank/controls: " + blocks);
            }
        }

        void close() throws ReflectiveOperationException {
            if (closed) return;
            closed = true;
            try {
                for (Object train : trains) {
                    if (!(boolean) train.getClass().getMethod("disassemble", Direction.class, BlockPos.class)
                            .invoke(train, direction, target.above())) {
                        throw new IllegalStateException("Native train cleanup refused disassembly");
                    }
                    Object id = train.getClass().getField("id").get(train);
                    if (trainRegistry().containsKey(id)) throw new IllegalStateException("Native train cleanup left registry ownership");
                }
                if (station != null) station.getClass().getMethod("exitAssemblyMode").invoke(station);
            } finally {
                if (glue != null) glue.discard();
                // Disassembly can recreate the train's real glue volume.
                for (Entity found : level.getEntitiesOfClass(Entity.class, new AABB(cell).inflate(3),
                        e -> !previousGlue.contains(e.getUUID())
                                && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("create:super_glue")
                                && e.getBoundingBox().minX >= cell.getX() && e.getBoundingBox().maxX <= cell.getX() + 1
                                && e.getBoundingBox().minY >= cell.getY() && e.getBoundingBox().maxY <= cell.getY() + 3
                                && e.getBoundingBox().minZ >= cell.getZ() && e.getBoundingBox().maxZ <= cell.getZ() + 1)) found.discard();
                var restore = new ArrayList<>(previous.entrySet());
                java.util.Collections.reverse(restore);
                for (var entry : restore) level.setBlock(entry.getKey(), entry.getValue(), 3);
            }
        }
    }

    private static int carriageLifetimeSelftest(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        BlockPos cell = new BlockPos(104, 100, 104);
        TrainRig rig = new TrainRig(level, cell);
        var tickets = new ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int z = 92; z <= 116; z += 8) {
            var chunk = new net.minecraft.world.level.ChunkPos(new BlockPos(cell.getX(), cell.getY(), z));
            if (!level.getForcedChunks().contains(chunk.toLong())) {
                level.setChunkForced(chunk.x, chunk.z, true);
                tickets.add(chunk);
            }
        }
        Runnable cleanup = () -> {
            try { rig.close(); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            finally { for (var chunk : tickets) level.setChunkForced(chunk.x, chunk.z, false); }
        };
        runAfterTicks(level.getServer(), 5, () -> rig.start(entity -> {
            int started = level.getServer().getTickCount();
            runAfterTicks(level.getServer(), ENTITY_SETTLE_TICKS + ENTITY_FINAL_TICKS, () -> {
                boolean passed = false;
                try {
                    if (!entity.isAlive() || !entity.isAddedToLevel() || entity.tickCount < ENTITY_SETTLE_TICKS) {
                        throw new IllegalStateException("Native carriage lifecycle mismatch: alive=" + entity.isAlive() + ", ticks=" + entity.tickCount);
                    }
                    Object train = rig.trains.getFirst();
                    Object id = train.getClass().getField("id").get(train);
                    if (rig.trainRegistry().get(id) != train || !id.equals(entity.getClass().getField("trainId").get(entity))) {
                        throw new IllegalStateException("Native carriage lost real train registry ownership");
                    }
                    rig.check(entity);
                    var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                    try {
                        ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) entity).writeSpawnData(buffer);
                        Entity received = entity.getType().create(level);
                        received.setPos(entity.getX(), entity.getY(), entity.getZ());
                        ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) received).readSpawnData(buffer);
                        rig.check(received);
                        if (buffer.isReadable() || !id.equals(received.getClass().getField("trainId").get(received))) {
                            throw new IllegalStateException("Native carriage spawn lost train identity or payload bytes");
                        }
                        buffer.clear();
                        boolean rejected = false;
                        try {
                            Entity bare = entity.getType().create(level);
                            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) bare).writeSpawnData(buffer);
                            Entity target = entity.getType().create(level);
                            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) target).readSpawnData(buffer);
                        } catch (RuntimeException expected) { rejected = true; }
                        if (!rejected) throw new IllegalStateException("Bare carriage negative control unexpectedly decoded");
                    } finally { buffer.release(); }
                    passed = true;
                } catch (Throwable failure) {
                    LOGGER.error("[cimscene] selftest carriage live FAILED", failure);
                } finally {
                    try { cleanup.run(); }
                    catch (Throwable failure) { passed = false; LOGGER.error("[cimscene] selftest carriage live FAILED during cleanup", failure); }
                }
                if (passed) LOGGER.info("[cimscene] selftest carriage live PASS serverTicks={} entityTicks={} bare-rejected=true",
                        level.getServer().getTickCount() - started, entity.tickCount);
            });
        }, failure -> {
            LOGGER.error("[cimscene] selftest carriage live FAILED during setup", failure);
            cleanup.run();
        }));
        return 1;
    }

    /** Exercise the actual asynchronous gallery page and exhausted-page cleanup. */
    private static int carriagePageSelftest(CommandSourceStack src) {
        Path manifest = Path.of("c-imagine", "scenes", "gallery-entitydyn-create-p0.tsv");
        try {
            Files.deleteIfExists(manifest);
            galleryEntity(src, "dyn", "create", 0);
            if (GALLERY_TRAINS.size() != 1) throw new IllegalStateException("Carriage page did not start native train setup");
            TrainRig rig = GALLERY_TRAINS.getFirst();
            runAfterTicks(src.getServer(), 200, () -> {
                boolean passed = false;
                try {
                    var lines = Files.readAllLines(manifest);
                    if (lines.size() != 2 || !lines.get(1).endsWith("\tcreate:carriage_contraption\t1\tok")
                            || rig.entity == null || !rig.entity.isAlive() || rig.entity.tickCount < 80) {
                        throw new IllegalStateException("Native carriage gallery did not settle: " + lines);
                    }
                    rig.check(rig.entity);
                    int exhausted = (int) BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                            .filter(id -> id.getNamespace().equals("create")).count();
                    galleryEntity(src, "dyn", "create", exhausted);
                    if (!rig.closed || !rig.entity.isRemoved() || !GALLERY_TRAINS.isEmpty()) {
                        throw new IllegalStateException("Exhausted page did not release its native train");
                    }
                    for (var entry : rig.previous.entrySet()) {
                        if (!src.getLevel().getBlockState(entry.getKey()).equals(entry.getValue())) {
                            throw new IllegalStateException("Native carriage page leaked support state at " + entry.getKey());
                        }
                    }
                    passed = true;
                } catch (Throwable failure) {
                    LOGGER.error("[cimscene] selftest carriage-page live FAILED", failure);
                } finally {
                    try { ++entityPageGen; closeGalleryAssemblies(); }
                    catch (Throwable failure) { passed = false; LOGGER.error("[cimscene] selftest carriage-page live FAILED during cleanup", failure); }
                }
                if (passed) LOGGER.info("[cimscene] selftest carriage-page live PASS serverTicks=200 native-page=true exhausted-cleanup=true");
            });
            return 1;
        } catch (Throwable failure) {
            LOGGER.error("[cimscene] selftest carriage-page live FAILED during setup", failure);
            ++entityPageGen;
            closeGalleryAssemblies();
            return 0;
        }
    }

    /** A real motor/shaft/controller assembly; every delayed action runs on normal server ticks. */
    private static final class GantryRig {
        final ServerLevel level;
        final BlockPos cell;
        final java.util.Map<BlockPos, BlockState> previous = new java.util.LinkedHashMap<>();
        final List<Entity> joined = new ArrayList<>();
        Entity entity;
        boolean closed;

        GantryRig(ServerLevel level, BlockPos cell) {
            this.level = level;
            this.cell = cell;
        }

        private void put(BlockPos pos, BlockState state) {
            previous.putIfAbsent(pos, level.getBlockState(pos));
            level.setBlock(pos, state, 3);
        }

        void observe(Entity candidate) {
            if (closed || candidate.level() != level
                    || !BuiltInRegistries.ENTITY_TYPE.getKey(candidate.getType()).toString().equals("create:gantry_contraption")) return;
            try {
                Object contraption = candidate.getClass().getMethod("getContraption").invoke(candidate);
                Object anchor = contraption == null ? null : contraption.getClass().getField("anchor").get(contraption);
                LOGGER.info("[cimscene] native gantry join anchor={} position={}", anchor, candidate.position());
                if (cell.equals(anchor)) joined.add(candidate);
            } catch (ReflectiveOperationException failure) {
                LOGGER.error("[cimscene] native gantry join identification failed", failure);
            }
        }

        private Entity find() {
            List<Entity> found = joined.stream().filter(e -> e.isAlive() && e.isAddedToLevel()).toList();
            if (found.size() > 1) throw new IllegalStateException("Ambiguous native gantry assembly");
            return found.isEmpty() ? null : found.get(0);
        }

        private String diagnose() throws ReflectiveOperationException {
            ClassLoader loader = CimSceneMod.class.getClassLoader();
            Class<?> type = Class.forName("com.simibubi.create.content.contraptions.gantry.GantryContraption", true, loader);
            Object probe = type.getConstructor(Direction.class).newInstance(Direction.UP);
            boolean assembled = (boolean) type.getMethod("assemble", net.minecraft.world.level.Level.class, BlockPos.class)
                    .invoke(probe, level, cell);
            Object blocks = type.getMethod("getBlocks").invoke(probe);
            Class<?> translating = Class.forName("com.simibubi.create.content.contraptions.TranslatingContraption", true, loader);
            Class<?> collider = Class.forName("com.simibubi.create.content.contraptions.ContraptionCollider", true, loader);
            boolean collides = assembled && (boolean) collider.getMethod("isCollidingWithWorld",
                    net.minecraft.world.level.Level.class, translating, BlockPos.class, Direction.class)
                    .invoke(null, level, probe, cell.east(), Direction.EAST);
            return "assembled=" + assembled + ", blocks=" + blocks + ", collides=" + collides
                    + ", joined=" + joined.stream().map(e -> e.getId() + ":" + e.getRemovalReason()).toList();
        }

        void start(java.util.function.Consumer<Entity> ready, java.util.function.Consumer<Throwable> failed) {
            try {
                ACTIVE_GANTRY_RIGS.add(this);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) level.getChunkAt(cell.offset(dx, 0, dz));
                }
                BlockPos motorPos = cell.below().west();
                put(motorPos, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:creative_motor"))
                        .defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.EAST));
                BlockEntity motor = level.getBlockEntity(motorPos);
                // This is the real motor's public scroll setting, not a rewritten kinetic speed field.
                Object speed = motor.getClass().getField("generatedSpeed").get(motor);
                speed.getClass().getMethod("setValue", int.class).invoke(speed, -1);
                put(cell.below(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:gantry_shaft"))
                        .defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.EAST));
                // A SINGLE segment cannot host native gantry movement. Its real neighbor
                // updates derive START/END; do not forge the shaft's PART property.
                put(cell.below().east(), BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:gantry_shaft"))
                        .defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.EAST));
                BlockState carriage = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:gantry_carriage"))
                        .defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, Direction.UP);
                var alongFirst = (net.minecraft.world.level.block.state.properties.BooleanProperty)
                        carriage.getBlock().getClass().getField("AXIS_ALONG_FIRST_COORDINATE").get(null);
                carriage = carriage.setValue(alongFirst, false);
                Object shaftAxis = carriage.getBlock().getClass().getMethod("getValidGantryShaftAxis", BlockState.class)
                        .invoke(null, carriage);
                if (shaftAxis != Direction.Axis.X) throw new IllegalStateException("Native carriage does not match its shaft axis");
                put(cell, carriage);
                put(cell.above(), Blocks.OAK_PLANKS.defaultBlockState());
                runAfterTicks(level.getServer(), 5, () -> {
                    if (closed) return;
                    try {
                        entity = find();
                        if (entity != null) {
                            ready.accept(entity);
                            return;
                        }
                        BlockEntity shaft = level.getBlockEntity(cell.below());
                        float movement = (float) shaft.getClass().getMethod("getPinionMovementSpeed").invoke(shaft);
                        if (movement == 0) throw new IllegalStateException("Native motor did not drive its gantry shaft");
                        if (!(boolean) shaft.getClass().getMethod("canAssembleOn").invoke(shaft)) {
                            throw new IllegalStateException("Native shaft track cannot host its gantry: " + shaft.getBlockState());
                        }
                        BlockEntity controller = level.getBlockEntity(cell);
                        controller.getClass().getMethod("queueAssembly").invoke(controller);
                        runAfterTicks(level.getServer(), 5, () -> {
                            if (closed) return;
                            try {
                                entity = find();
                                if (entity == null) throw new IllegalStateException("Native gantry did not assemble: "
                                        + controller.getClass().getMethod("getLastAssemblyException").invoke(controller)
                                        + ", carriage=" + level.getBlockState(cell) + ", movement=" + movement + ", " + diagnose());
                                ready.accept(entity);
                            } catch (Throwable failure) {
                                failed.accept(failure);
                            }
                        });
                    } catch (Throwable failure) {
                        failed.accept(failure);
                    }
                });
            } catch (Throwable failure) {
                failed.accept(failure);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            ACTIVE_GANTRY_RIGS.remove(this);
            try {
                if (entity == null) entity = find();
                if (entity != null) entity.getClass().getMethod("disassemble").invoke(entity);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native gantry cleanup failed", failure);
            } finally {
                if (entity != null) entity.discard();
                previous.forEach((pos, state) -> level.setBlock(pos, state, 3));
            }
        }
    }

    private static final List<GantryRig> ACTIVE_GANTRY_RIGS = new ArrayList<>();
    private static final List<GantryRig> GALLERY_GANTRIES = new ArrayList<>();
    private static final List<TrainRig> GALLERY_TRAINS = new ArrayList<>();

    private static void closeGalleryAssemblies() {
        RuntimeException failure = null;
        for (GantryRig rig : GALLERY_GANTRIES) {
            try { rig.close(); }
            catch (Exception bad) {
                if (failure == null) failure = new IllegalStateException("Gallery assembly cleanup failed", bad);
                else failure.addSuppressed(bad);
            }
        }
        for (TrainRig rig : GALLERY_TRAINS) {
            try { rig.close(); }
            catch (Exception bad) {
                if (failure == null) failure = new IllegalStateException("Gallery assembly cleanup failed", bad);
                else failure.addSuppressed(bad);
            }
        }
        GALLERY_GANTRIES.clear();
        GALLERY_TRAINS.clear();
        if (failure != null) throw failure;
    }

    private static void checkGantryContraption(Entity entity) throws ReflectiveOperationException {
        Object contraption = entity.getClass().getMethod("getContraption").invoke(entity);
        Object value = contraption.getClass().getMethod("getBlocks").invoke(contraption);
        if (!(value instanceof java.util.Map<?, ?> blocks) || blocks.size() != 2
                || !(blocks.get(BlockPos.ZERO) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo base)
                || !BuiltInRegistries.BLOCK.getKey(base.state().getBlock()).toString().equals("create:gantry_carriage")
                || !(blocks.get(BlockPos.ZERO.above()) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo top)
                || !top.state().is(Blocks.OAK_PLANKS)) {
            throw new IllegalStateException("Native gantry lost its assembled carriage or plank");
        }
    }

    private static int gantryLifetimeSelftest(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        BlockPos cell = new BlockPos(72, 100, 72);
        var chunk = new net.minecraft.world.level.ChunkPos(cell);
        boolean wasForced = level.getForcedChunks().contains(chunk.toLong());
        level.setChunkForced(chunk.x, chunk.z, true);
        GantryRig rig = new GantryRig(level, cell);
        Runnable cleanup = () -> {
            try {
                rig.close();
            } finally {
                if (!wasForced) level.setChunkForced(chunk.x, chunk.z, false);
            }
        };
        runAfterTicks(level.getServer(), 5, () -> rig.start(entity -> {
            int started = level.getServer().getTickCount();
            Vec3 start = entity.position();
            runAfterTicks(level.getServer(), ENTITY_SETTLE_TICKS + ENTITY_FINAL_TICKS, () -> {
                boolean passed = false;
                int ticks = entity.tickCount;
                double distance = entity.position().distanceTo(start);
                try {
                    if (!entity.isAlive() || !entity.isAddedToLevel() || ticks < ENTITY_SETTLE_TICKS
                            || distance <= 0 || distance >= 1) {
                        throw new IllegalStateException("Native powered gantry lifecycle mismatch: alive=" + entity.isAlive()
                                + ", entityTicks=" + ticks + ", distance=" + distance);
                    }
                    checkGantryContraption(entity);
                    var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
                    try {
                        ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) entity).writeSpawnData(buffer);
                        Entity received = entity.getType().create(level);
                        received.setPos(entity.getX(), entity.getY(), entity.getZ());
                        ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) received).readSpawnData(buffer);
                        checkGantryContraption(received);
                        if (buffer.isReadable()) throw new IllegalStateException("Unconsumed native gantry spawn data");
                        buffer.clear();
                        boolean refused = false;
                        try {
                            Entity bare = entity.getType().create(level);
                            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) bare).writeSpawnData(buffer);
                            Entity bareReceiver = entity.getType().create(level);
                            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) bareReceiver).readSpawnData(buffer);
                        } catch (RuntimeException expected) {
                            refused = true;
                        }
                        if (!refused) throw new IllegalStateException("Bare gantry negative control unexpectedly decoded");
                    } finally {
                        buffer.release();
                    }
                    passed = true;
                } catch (Throwable failure) {
                    LOGGER.error("[cimscene] selftest gantry live FAILED", failure);
                } finally {
                    try {
                        cleanup.run();
                    } catch (Throwable failure) {
                        passed = false;
                        LOGGER.error("[cimscene] selftest gantry live FAILED during cleanup", failure);
                    }
                }
                if (passed) LOGGER.info("[cimscene] selftest gantry live PASS serverTicks={} entityTicks={} distance={} bare-rejected=true",
                        level.getServer().getTickCount() - started, ticks, distance);
            });
        }, failure -> {
            LOGGER.error("[cimscene] selftest gantry live FAILED during setup", failure);
            cleanup.run();
        }));
        return 1;
    }

    /** Assemble real world blocks through Create's controller, which owns entity and contraption initialization. */
    private static Entity assembleGalleryBearing(ServerLevel level, BlockPos cell) throws ReflectiveOperationException {
        return assembleGalleryBearing(level, cell, true);
    }

    private static Entity assembleGalleryBearing(ServerLevel level, BlockPos cell, boolean retainWhenStopped)
            throws ReflectiveOperationException {
        // Create checks adjacent blocks during assembly and rejects unloaded chunks,
        // including at a chunk corner where the controller and body alone load only two.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunkAt(cell.offset(dx, 0, dz));
        }
        BlockPos controller = cell.north();
        BlockState bearing = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:mechanical_bearing"))
                .defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING,
                        Direction.SOUTH);
        level.setBlock(controller, bearing, 3);
        level.setBlock(cell, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        BlockEntity be = level.getBlockEntity(controller);
        if (retainWhenStopped) {
            ClassLoader loader = be.getClass().getClassLoader();
            Class<?> scroll = Class.forName("com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour", true, loader);
            Class<?> behaviourType = Class.forName("com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType", true, loader);
            Object mode = be.getClass().getMethod("getBehaviour", behaviourType).invoke(be, scroll.getField("TYPE").get(null));
            Class<?> modes = Class.forName("com.simibubi.create.content.contraptions.IControlContraption$RotationMode", true, loader);
            Object neverPlace = modes.getField("ROTATE_NEVER_PLACE").get(null);
            mode.getClass().getMethod("setValue", int.class).invoke(mode, ((Enum<?>) neverPlace).ordinal());
            if (mode.getClass().getMethod("get").invoke(mode) != neverPlace) {
                throw new IllegalStateException("Native bearing did not accept Never Place mode");
            }
        }
        be.getClass().getMethod("assemble").invoke(be);
        Entity entity = (Entity) be.getClass().getMethod("getMovedContraption").invoke(be);
        // NeoForge sets this after successful entity-manager insertion. The UUID
        // lookup only exposes tracked entities and can lag insertion in a new chunk.
        if (entity == null || !entity.isAlive() || !entity.isAddedToLevel()
                || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("create:stationary_contraption")) {
            throw new IllegalStateException("Native bearing assembly did not add its stationary contraption: entity="
                    + entity + ", assembly=" + be.getClass().getMethod("getLastAssemblyException").invoke(be));
        }
        return entity;
    }

    /** Use the same native span and volume constructor as SuperGlueSelectionPacket. */
    private static Entity createGalleryGlue(ServerLevel level, BlockPos cell) throws ReflectiveOperationException {
        level.setBlock(cell.north(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(cell, Blocks.OAK_PLANKS.defaultBlockState(), 3);
        Class<?> glue = Class.forName("com.simibubi.create.content.contraptions.glue.SuperGlueEntity",
                true, CimSceneMod.class.getClassLoader());
        AABB span = (AABB) glue.getMethod("span", BlockPos.class, BlockPos.class).invoke(null, cell.north(), cell);
        Entity entity = (Entity) glue.getConstructor(net.minecraft.world.level.Level.class, AABB.class).newInstance(level, span);
        if (!level.addFreshEntity(entity) || !entity.isAddedToLevel()) {
            throw new IllegalStateException("Native glue volume insertion refused");
        }
        return entity;
    }

    /** Verify the actual connection and native additional-spawn bounds, rather than guessed NBT. */
    private static void checkGalleryGlue(Entity entity, BlockPos cell) throws ReflectiveOperationException {
        Class<?> glue = entity.getClass();
        AABB span = (AABB) glue.getMethod("span", BlockPos.class, BlockPos.class).invoke(null, cell.north(), cell);
        if (!entity.getBoundingBox().equals(span)
                || !(boolean) glue.getMethod("contains", BlockPos.class).invoke(entity, cell)
                || !(boolean) glue.getMethod("contains", BlockPos.class).invoke(entity, cell.north())) {
            throw new IllegalStateException("Native glue lost its selected volume");
        }
        var isGlued = glue.getMethod("isGlued", net.minecraft.world.level.LevelAccessor.class,
                BlockPos.class, Direction.class, java.util.Set.class);
        if (!(boolean) isGlued.invoke(null, entity.level(), cell.north(), Direction.SOUTH, null)
                || (boolean) isGlued.invoke(null, entity.level(), cell, Direction.EAST, null)) {
            throw new IllegalStateException("Native glue connection lookup differs from its selected blocks");
        }
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), entity.registryAccess());
        try {
            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) entity).writeSpawnData(buffer);
            Entity received = entity.getType().create(entity.level());
            received.setPos(entity.getX(), entity.getY(), entity.getZ());
            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) received).readSpawnData(buffer);
            if (!received.getBoundingBox().equals(span) || buffer.isReadable()) {
                throw new IllegalStateException("Native glue spawn round trip lost its selected bounds");
            }
        } finally {
            buffer.release();
        }
    }

    /** Native SeatBlock.sitDown creates and mounts its own seat entity. */
    private static Entity createGallerySeat(ServerLevel level, BlockPos cell) throws ReflectiveOperationException {
        level.setBlock(cell, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:white_seat")).defaultBlockState(), 3);
        var rider = net.minecraft.world.entity.EntityType.PIG.create(level);
        boolean seated = false;
        try {
            m_setNoAi(rider);
            rider.setNoGravity(true);
            rider.setInvulnerable(true);
            rider.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 180, 0);
            if (!level.addFreshEntity(rider)) throw new IllegalStateException("Native seat rider insertion refused");
            Class<?> seat = level.getBlockState(cell).getBlock().getClass();
            seat.getMethod("sitDown", net.minecraft.world.level.Level.class, BlockPos.class, Entity.class)
                    .invoke(null, level, cell, rider);
            Entity entity = rider.getVehicle();
            if (entity == null || !entity.isAlive() || !entity.isAddedToLevel()
                    || !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("create:seat")) {
                throw new IllegalStateException("Native seat did not mount its real rider");
            }
            seated = true;
            return entity;
        } finally {
            if (!seated && rider != null) {
                Entity vehicle = rider.getVehicle();
                rider.discard();
                if (vehicle != null) vehicle.discard();
            }
        }
    }

    /** Real ticking regressions: configured subjects survive; their unconfigured controls self-remove. */
    private static int entityLifetimeSelftest(CommandSourceStack src, String subjectName) {
        boolean glue = subjectName.equals("glue");
        boolean seat = subjectName.equals("seat");
        ServerLevel level = src.getLevel();
        BlockPos retainedCell = new BlockPos(72, 100, 72);
        BlockPos defaultCell = retainedCell.east(4);
        var chunk = new net.minecraft.world.level.ChunkPos(retainedCell);
        boolean wasForced = level.getForcedChunks().contains(chunk.toLong());
        var previous = new java.util.LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos cell : List.of(retainedCell, defaultCell)) {
            previous.put(cell.north(), level.getBlockState(cell.north()));
            previous.put(cell, level.getBlockState(cell));
        }
        Entity[] subjects = new Entity[2];
        Runnable cleanup = () -> {
            try {
                if (!glue && !seat) {
                    for (BlockPos cell : List.of(retainedCell, defaultCell)) {
                        BlockEntity be = level.getBlockEntity(cell.north());
                        if (be != null) be.getClass().getMethod("disassemble").invoke(be);
                    }
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Native " + subjectName + " live-test cleanup failed", failure);
            } finally {
                for (Entity subject : subjects) {
                    if (subject == null) continue;
                    for (Entity passenger : List.copyOf(subject.getPassengers())) passenger.discard();
                    subject.discard();
                }
                previous.forEach((pos, state) -> level.setBlock(pos, state, 3));
                if (!wasForced) level.setChunkForced(chunk.x, chunk.z, false);
            }
        };
        level.setChunkForced(chunk.x, chunk.z, true);
        // Let the native ticket make this chunk entity-ticking before assembly.
        runAfterTicks(level.getServer(), 5, () -> {
            try {
                if (glue || seat) {
                    subjects[0] = seat ? createGallerySeat(level, retainedCell) : createGalleryGlue(level, retainedCell);
                    AABB nativeBounds = subjects[0].getBoundingBox();
                    alignGalleryEntity(subjects[0], retainedCell, true);
                    if (!subjects[0].getBoundingBox().equals(nativeBounds)) {
                        throw new IllegalStateException("Gallery alignment moved native glue bounds");
                    }
                    subjects[1] = subjects[0].getType().create(level);
                    subjects[1].setPos(defaultCell.getX() + 0.5, defaultCell.getY(), defaultCell.getZ() + 0.5);
                    if ((glue && subjects[1].getBoundingBox().getXsize() != 0) || !level.addFreshEntity(subjects[1])) {
                        throw new IllegalStateException("Native bare " + subjectName + " control setup failed");
                    }
                } else {
                    subjects[0] = assembleGalleryBearing(level, retainedCell, true);
                    subjects[1] = assembleGalleryBearing(level, defaultCell, false);
                }
                int started = level.getServer().getTickCount();
                runAfterTicks(level.getServer(), ENTITY_SETTLE_TICKS + ENTITY_FINAL_TICKS, () -> {
                    boolean passed = false;
                    int elapsed = level.getServer().getTickCount() - started;
                    int ticks = subjects[0].tickCount;
                    try {
                        if (!subjects[0].isAlive() || !subjects[0].isAddedToLevel()
                                || ticks < ENTITY_SETTLE_TICKS || !subjects[1].isRemoved()) {
                            throw new IllegalStateException("Native " + subjectName + " lifecycle mismatch: retained="
                                    + subjects[0].isAlive() + ", entityTicks=" + ticks
                                    + ", defaultRemoved=" + subjects[1].isRemoved());
                        }
                        if (seat) {
                            Entity rider = subjects[0].getFirstPassenger();
                            Class<?> seatBlock = level.getBlockState(retainedCell).getBlock().getClass();
                            boolean occupied = (boolean) seatBlock.getMethod("isSeatOccupied",
                                    net.minecraft.world.level.Level.class, BlockPos.class).invoke(null, level, retainedCell);
                            if (!(rider instanceof net.minecraft.world.entity.animal.Pig) || !rider.isAlive()
                                    || !rider.isAddedToLevel() || rider.getVehicle() != subjects[0] || !occupied
                                    || rider.tickCount < ENTITY_SETTLE_TICKS) {
                                throw new IllegalStateException("Native seat lost its ticking rider or seat block");
                            }
                        } else if (glue) {
                            // ServerLevel owns tickCount, including for native entities
                            // whose tick override does not call Entity.baseTick.
                            checkGalleryGlue(subjects[0], retainedCell);
                        } else {
                            checkGalleryContraption(subjects[0], false);
                            BlockEntity be = level.getBlockEntity(retainedCell.north());
                            if (be.getClass().getMethod("getMovedContraption").invoke(be) != subjects[0]) {
                                throw new IllegalStateException("Native retained bearing lost its controller attachment");
                            }
                        }
                        passed = true;
                    } catch (Throwable failure) {
                        LOGGER.error("[cimscene] selftest " + subjectName + " live FAILED", failure);
                    } finally {
                        try {
                            cleanup.run();
                        } catch (Throwable failure) {
                            passed = false;
                            LOGGER.error("[cimscene] selftest " + subjectName + " live FAILED during cleanup", failure);
                        }
                    }
                    if (passed) LOGGER.info("[cimscene] selftest {} live PASS serverTicks={} entityTicks={} default-removed=true", subjectName, elapsed, ticks);
                });
            } catch (Throwable failure) {
                LOGGER.error("[cimscene] selftest " + subjectName + " live FAILED during setup", failure);
                cleanup.run();
            }
        });
        return 1;
    }

    /** Use the real powered assembler and minecart; the native controller assembles and mounts its payload. */
    private static Entity assembleGalleryCart(ServerLevel level, BlockPos cell) throws ReflectiveOperationException {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.getChunkAt(cell.offset(dx, 0, dz));
        }
        level.setBlock(cell.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(cell, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:cart_assembler"))
                .defaultBlockState(), 3);
        level.setBlock(cell.above(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        // A real neighboring power source drives the assembler's native POWERED update.
        level.setBlock(cell.north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        var cart = net.minecraft.world.entity.EntityType.MINECART.create(level);
        boolean assembled = false;
        try {
            cart.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
            if (!level.addFreshEntity(cart)) throw new IllegalStateException("Native minecart insertion refused");
            BlockEntity be = level.getBlockEntity(cell);
            be.getClass().getMethod("tryAssemble", net.minecraft.world.entity.vehicle.AbstractMinecart.class)
                    .invoke(be, cart);
            for (Entity passenger : cart.getPassengers()) {
                if (BuiltInRegistries.ENTITY_TYPE.getKey(passenger.getType()).toString().equals("create:contraption")
                        && passenger.isAlive() && passenger.isAddedToLevel()) {
                    assembled = true;
                    return passenger;
                }
            }
            throw new IllegalStateException("Native cart assembly did not mount its contraption: "
                    + be.getClass().getMethod("getLastAssemblyException").invoke(be));
        } finally {
            if (!assembled && cart != null) {
                for (Entity passenger : List.copyOf(cart.getPassengers())) passenger.discard();
                cart.discard();
            }
        }
    }

    private static void checkGalleryContraption(Entity entity, boolean cart) throws ReflectiveOperationException {
        Object contraption = entity.getClass().getMethod("getContraption").invoke(entity);
        if (contraption == null) throw new IllegalStateException("Native entity has no contraption");
        Object blocks = contraption.getClass().getMethod("getBlocks").invoke(contraption);
        if (!(blocks instanceof java.util.Map<?, ?> captured) || captured.size() != (cart ? 2 : 1)
                || !(captured.get(cart ? BlockPos.ZERO.above() : BlockPos.ZERO) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo block)
                || !block.state().is(Blocks.OAK_PLANKS)) {
            throw new IllegalStateException("Native assembly lost its actual world block");
        }
        if (cart && (!(captured.get(BlockPos.ZERO) instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo anchor)
                || !BuiltInRegistries.BLOCK.getKey(anchor.state().getBlock()).toString().equals("create:minecart_anchor"))) {
            throw new IllegalStateException("Native mounted contraption lost its minecart anchor");
        }
    }

    /** Real assembly and native additional-spawn decoding, including the formerly null contraption. */
    private static int contraptionSpawnSelftest(CommandSourceStack src, boolean cart) {
        String subject = cart ? "cart" : "bearing";
        ServerLevel level = src.getLevel();
        BlockPos cell = new BlockPos(64, 100, 64);
        level.getChunkAt(cell);
        var previous = new java.util.LinkedHashMap<BlockPos, BlockState>();
        for (BlockPos pos : List.of(cell.below(), cell.north(), cell, cell.above())) previous.put(pos, level.getBlockState(pos));
        Entity assembled = null;
        Entity vehicle = null;
        int bytes = 0;
        boolean passed = false;
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            assembled = cart ? assembleGalleryCart(level, cell) : assembleGalleryBearing(level, cell);
            vehicle = assembled.getVehicle();
            if (cart && !(vehicle instanceof net.minecraft.world.entity.vehicle.AbstractMinecart)) {
                throw new IllegalStateException("Native cart contraption has no minecart vehicle");
            }
            checkGalleryContraption(assembled, cart);
            Vec3 nativePosition = assembled.position();
            float nativeYaw = assembled.getYRot();
            alignGalleryEntity(assembled, cell, true);
            if (!assembled.position().equals(nativePosition) || assembled.getYRot() != nativeYaw) {
                throw new IllegalStateException("Gallery alignment moved the native bearing attachment");
            }
            var codec = (net.neoforged.neoforge.entity.IEntityWithComplexSpawn) assembled;
            codec.writeSpawnData(buffer);
            bytes = buffer.readableBytes();
            Entity received = assembled.getType().create(level);
            received.moveTo(assembled.getX(), assembled.getY(), assembled.getZ(), assembled.getYRot(), assembled.getXRot());
            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) received).readSpawnData(buffer);
            checkGalleryContraption(received, cart);
            if (buffer.isReadable()) throw new IllegalStateException("Unconsumed native bearing spawn data");
            buffer.clear();
            Entity bare = assembled.getType().create(level);
            boolean refused = false;
            try {
                ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) bare).writeSpawnData(buffer);
                Entity bareReceiver = assembled.getType().create(level);
                ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) bareReceiver).readSpawnData(buffer);
            } catch (RuntimeException expected) {
                refused = true;
            }
            if (!refused) throw new IllegalStateException("Bare bearing negative control unexpectedly encoded");
            passed = true;
        } catch (Throwable failure) {
            LOGGER.error("[cimscene] selftest " + subject + " native-spawn FAILED", failure);
        } finally {
            buffer.release();
            try {
                if (cart) {
                    if (assembled != null) assembled.getClass().getMethod("disassemble").invoke(assembled);
                } else {
                    BlockEntity be = level.getBlockEntity(cell.north());
                    if (be != null) be.getClass().getMethod("disassemble").invoke(be);
                }
            } catch (ReflectiveOperationException failure) {
                passed = false;
                LOGGER.error("[cimscene] selftest " + subject + " native-spawn FAILED during cleanup", failure);
            } finally {
                if (assembled != null) assembled.discard();
                if (vehicle != null) vehicle.discard();
                previous.forEach((pos, state) -> level.setBlock(pos, state, 3));
            }
        }
        if (passed) LOGGER.info("[cimscene] selftest {} native-spawn PASS bytes={} bare-rejected=true", subject, bytes);
        return passed ? 1 : 0;
    }

    /** Match PotatoCannonItem: setItem resolves the data-driven projectile type before spawning. */
    private static void configurePotatoProjectile(Entity entity) throws ReflectiveOperationException {
        entity.getClass().getMethod("setItem", ItemStack.class).invoke(entity, new ItemStack(Items.POTATO));
    }

    /** Native render-mode lookup and additional-spawn round trip; no client or synthetic ticks. */
    private static int potatoSpawnSelftest(CommandSourceStack src) {
        var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("create:potato_projectile"));
        ServerLevel level = src.getLevel();
        var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            Entity projectile = type.create(level);
            var renderMode = projectile.getClass().getMethod("getRenderMode");
            boolean refused = false;
            try {
                renderMode.invoke(projectile);
            } catch (java.lang.reflect.InvocationTargetException expected) {
                if (!(expected.getCause() instanceof NullPointerException)) throw expected;
                refused = true;
            }
            if (!refused) throw new IllegalStateException("Bare projectile negative control unexpectedly rendered");
            configurePotatoProjectile(projectile);
            Object projectileType = projectile.getClass().getMethod("getProjectileType").invoke(projectile);
            if (projectileType == null || renderMode.invoke(projectile) == null) {
                throw new IllegalStateException("Configured projectile has no native type or render mode");
            }
            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) projectile).writeSpawnData(buffer);
            int bytes = buffer.readableBytes();
            Entity received = type.create(level);
            ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) received).readSpawnData(buffer);
            ItemStack receivedItem = (ItemStack) received.getClass().getMethod("getItem").invoke(received);
            Object receivedType = received.getClass().getMethod("getProjectileType").invoke(received);
            if (!receivedItem.is(Items.POTATO) || receivedItem.getCount() != 1 || receivedType != projectileType
                    || renderMode.invoke(received) == null || buffer.isReadable()) {
                throw new IllegalStateException("Native projectile spawn round trip lost ammo, type or render mode");
            }
            LOGGER.info("[cimscene] selftest potato native-spawn PASS bytes={} bare-rejected=true", bytes);
            return 1;
        } catch (Throwable failure) {
            LOGGER.error("[cimscene] selftest potato native-spawn FAILED", failure);
            return 0;
        } finally {
            buffer.release();
        }
    }

    /** Real Create item placement and its native additional-spawn codec, exercised by CI. */
    private static int itemEntitySpawnSelftest(CommandSourceStack src, boolean blueprint) {
        String subject = blueprint ? "blueprint" : "package";
        String entityId = blueprint ? "create:crafting_blueprint" : "create:package";
        String itemId = blueprint ? "create:crafting_blueprint" : "create:cardboard_package_12x12";
        ServerLevel level = src.getLevel();
        BlockPos support = new BlockPos(64, 100, 64);
        level.getChunkAt(support);
        BlockState previous = level.getBlockState(support);
        Entity placed = null;
        try {
            GuiProbe player = new GuiProbe(level);
            ItemStack held = new ItemStack(Items.STICK);
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            player.setShiftKeyDown(true);
            level.setBlock(support, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            placed = placeEntityItem(level, player, support,
                    blueprint ? Direction.SOUTH : Direction.UP, itemId, entityId);
            if (placed == null || player.getItemInHand(InteractionHand.MAIN_HAND) != held
                    || !player.isShiftKeyDown()) {
                throw new IllegalStateException(subject + " item placement or player state restoration failed");
            }
            Vec3 nativePosition = placed.position();
            float nativeYaw = placed.getYRot();
            alignGalleryEntity(placed, support.relative(blueprint ? Direction.SOUTH : Direction.UP), true);
            if (blueprint && (!placed.position().equals(nativePosition) || placed.getYRot() != nativeYaw)) {
                throw new IllegalStateException("Gallery alignment changed the native hanging attachment");
            }
            if (placed instanceof net.minecraft.world.entity.decoration.HangingEntity hanging && !hanging.survives()) {
                throw new IllegalStateException("Item-placed hanging entity does not survive on its support");
            }
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) placed).writeSpawnData(buffer);
                if (buffer.readableBytes() <= (blueprint ? 0 : 12)) {
                    throw new IllegalStateException(subject + " spawn payload has no item data");
                }
                int bytes = buffer.readableBytes();
                Entity bare = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(entityId)).create(level);
                boolean refused = false;
                buffer.clear();
                try {
                    ((net.neoforged.neoforge.entity.IEntityWithComplexSpawn) bare).writeSpawnData(buffer);
                } catch (RuntimeException expected) {
                    refused = true;
                }
                if (!refused) throw new IllegalStateException("Bare " + subject + " negative control unexpectedly encoded");
                LOGGER.info("[cimscene] selftest {} native-spawn PASS bytes={} bare-rejected=true", subject, bytes);
            } finally {
                buffer.release();
            }
            return 1;
        } catch (Throwable failure) {
            LOGGER.error("[cimscene] selftest " + subject + " native-spawn FAILED", failure);
            return 0;
        } finally {
            if (placed != null) placed.discard();
            level.setBlock(support, previous, 3);
        }
    }

    /** Place through Mekanism's item: it assigns the real chargepad home and owner. */
    static Entity placeRobit(ServerLevel level, ServerPlayer player, BlockPos pad) {
        level.setBlock(pad.below(), Blocks.STONE.defaultBlockState(), 3);
        // A previous menu may have left this pad active; create a fresh placement context.
        level.setBlock(pad, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(pad, BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("mekanism:chargepad")).defaultBlockState(), 3);
        return placeEntityItem(level, player, pad, "mekanism:robit", "mekanism:robit");
    }

    /** Native hanging items and assembled bearings retain their actual attachment. */
    private static void alignGalleryEntity(Entity entity, BlockPos cell, boolean nativePlaced) {
        if (nativePlaced && (entity instanceof net.minecraft.world.entity.decoration.HangingEntity || entity.isPassenger() || entity.isVehicle()
                || BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("create:stationary_contraption")
                || BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("create:super_glue"))) return;
        entity.moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 180.0f, 0.0f);
        // Frozen mobs must send the same head/body direction as their entity yaw.
        entity.setYHeadRot(180.0f);
        entity.setYBodyRot(180.0f);
    }

    /** The item's native placement supplies entity state required by its spawn payload. */
    private static Entity placeEntityItem(ServerLevel level, ServerPlayer player, BlockPos against,
                                          String itemId, String entityId) {
        return placeEntityItem(level, player, against, Direction.UP, itemId, entityId);
    }

    private static Entity placeEntityItem(ServerLevel level, ServerPlayer player, BlockPos against,
                                          Direction face, String itemId, String entityId) {
        ItemStack previous = player.getItemInHand(InteractionHand.MAIN_HAND);
        boolean shifted = player.isShiftKeyDown();
        List<Entity> previousJoined = GUI_JOINED;
        List<Entity> joined = new ArrayList<>();
        GUI_JOINED = joined;
        try {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)));
            player.setShiftKeyDown(false);
            player.setItemInHand(InteractionHand.MAIN_HAND, stack);
            stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(against).relative(face, 0.5), face, against, false)));
            return joined.stream().filter(e -> e.isAlive() && BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())
                    .equals(ResourceLocation.parse(entityId))).findFirst().orElse(null);
        } finally {
            GUI_JOINED = previousJoined;
            if (previousJoined != null) previousJoined.addAll(joined);
            player.setItemInHand(InteractionHand.MAIN_HAND, previous);
            player.setShiftKeyDown(shifted);
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
        if (opener.startsWith("specialized:") || opener.startsWith("sneak+specialized:")) {
            String spec = opener.replace("sneak+", "").substring("specialized:".length());
            SpecializedOpeners.act(level, player, rig, spec, guiEntity);
            if (player instanceof GuiProbe probe && probe.seen == null) {
                probe.seen = ResourceLocation.parse(spec);
            }
            return;
        }
        boolean block = opener.contains("block:") || opener.contains("place+item:") || opener.contains("use+item:")
                || opener.contains("cube") || opener.contains("settle+use+item:");
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
            if (plain.startsWith("settle+use+item:")) {
                // A tool on a block that has ticked: the way a player uses a network tool on a
                // node, after the mod's own first-tick setup made it one.
                String[] pair = plain.substring("settle+use+item:".length()).split("@", 2);
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(pair[0])));
                player.setItemInHand(InteractionHand.MAIN_HAND, stack);
                Vec3 at = Vec3.atCenterOf(rig).relative(face, 0.5);
                BlockHitResult hit = new BlockHitResult(at, face, rig, false);
                BlockState state = level.getBlockState(rig);
                net.minecraft.world.ItemInteractionResult result = state.useItemOn(stack, level, player,
                        InteractionHand.MAIN_HAND, hit);
                if (result == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                        && !state.useWithoutItem(level, player, hit).consumesAction()) {
                    stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));
                } else if (!result.consumesAction()
                        && result != net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
                    stack.useOn(new net.minecraft.world.item.context.UseOnContext(player, InteractionHand.MAIN_HAND, hit));
                }
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } else if (guiEntity != null && plain.contains("+item:")) {
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
        for (String spec : SpecializedOpeners.MENUS) {
            if (spec.startsWith(namespace + ":")) {
                both.add("specialized:" + spec);
            }
        }
        return both;
    }

    private static int SETTLE_SLOT;

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
        guiBuildCube(level, player, rig, plain, true);
    }

    private static void guiBuildCube(ServerLevel level, ServerPlayer player, BlockPos rig, String plain,
                                     boolean tickNow) {
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
        if (!tickNow) {
            return;   // the level ticks it for real: onLoad and the mod's own structure pass
        }
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
        // 14-dj: the probe builds and tool sweeps need real BE/world ticking —
        // a freeze left behind by the last gui page's terminal would park them.
        setGalleryFrozen(level.getServer(), false, "gui scan entry");
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
        // 14-dj: the settle pass builds rigs, spawns entities and sweeps tools —
        // it needs the ticking world, so it unfreezes like every page entry.
        setGalleryFrozen(level.getServer(), false, "gui settle entry");
        java.util.Set<ResourceLocation> wanted = new java.util.TreeSet<>();
        List<ResourceLocation> ids = new ArrayList<>(BuiltInRegistries.MENU.keySet());
        ids.removeIf(id -> !id.getNamespace().equals(namespace));
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (int page = 0; page < ids.size(); page++) {
            if (guiManifestOpener(namespace, page) == null) {
                wanted.add(ids.get(page));
            }
        }
        List<String> cubes = wanted.isEmpty() ? new ArrayList<>() : new ArrayList<>(guiCubeCandidates(namespace));
        // Single blocks too, for the tools used on them once they have ticked.
        List<String> tools = new ArrayList<>();
        if (!wanted.isEmpty()) {
            for (ResourceLocation b : BuiltInRegistries.BLOCK.keySet()) {
                if (b.getNamespace().equals(namespace)
                        && BuiltInRegistries.BLOCK.get(b) instanceof net.minecraft.world.level.block.EntityBlock) {
                    cubes.add("single:" + b);
                }
            }
            for (ResourceLocation i : BuiltInRegistries.ITEM.keySet()) {
                if (i.getNamespace().equals(namespace)
                        && !(BuiltInRegistries.ITEM.get(i) instanceof net.minecraft.world.item.BlockItem)) {
                    tools.add(i.toString());
                }
            }
            tools.sort(Comparator.naturalOrder());
        }
        // Each mod's pass gets its own ground: the passes overlap in time, and one clearing
        // its cubes must not take down another's before that one is clicked.
        BlockPos base = new BlockPos(GUI_PROBE_XZ + 64, 100, GUI_PROBE_XZ + 400 * SETTLE_SLOT++);
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
                if (cubes.get(i).startsWith("single:")) {
                    level.setBlock(rigs.get(i), BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                            cubes.get(i).substring("single:".length()))).defaultBlockState(), 3);
                    GUI_CUBE_BLOCKS.add(rigs.get(i));
                    guiCubeClick = rigs.get(i);
                } else {
                    guiBuildCube(level, probe, rigs.get(i), cubes.get(i), false);
                }
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
                if (cubes.get(i).startsWith("single:")) {
                    String block = cubes.get(i).substring("single:".length());
                    for (String tool : tools) {
                        String opener = "settle+use+item:" + tool + "@" + block;
                        one.seen = null;
                        one.candidate = opener;
                        try {
                            guiActOnce(level, one, rigs.get(i), opener, Direction.UP);
                        } catch (Throwable t) {
                            // a tool that throws on this block is simply not its opener
                        }
                        if (one.seen != null && wanted.contains(one.seen)) {
                            openers.computeIfAbsent(one.seen, k -> new ArrayList<>()).add(opener);
                            seen.putIfAbsent(one.seen, one);
                            one = new GuiProbe(level);
                            one.setPos(rigs.get(i).getX() + 0.5, rigs.get(i).getY(), rigs.get(i).getZ() + 2.5);
                        }
                    }
                    continue;
                }
                try {
                    guiAct(level, one, rigs.get(i), cubes.get(i));
                } catch (Throwable t) {
                    LOGGER.info("[cimscene] gallery-gui settle click {} threw {}", cubes.get(i), t.toString());
                }
                if (one.seen == null && cubes.get(i).startsWith("cube3:")) {
                    BlockPos at = clicks.get(i);
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(at);
                    LOGGER.info("[cimscene] gallery-gui settle {} at {} block={} be={} ticks={}", cubes.get(i), at.toShortString(),
                            BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()),
                            be == null ? null : be.getClass().getSimpleName(), level.getServer().getTickCount());
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
            BlockPos rig = guiRigPos(viewer);
            guiClearRig(level, viewer, rig);
            // A real menu needs the context its own code supplies, so the walk does
            // what a player does. The block entity must reach the client first, or
            // the screen reads a null holder and the client disconnects.
            // The plan already probed every mod, settled pass included, and wrote each page's
            // opener into its manifest; a walk run on its own probes for itself.
            boolean planned = Files.isRegularFile(Path.of("c-imagine", "scenes",
                    "gallery-gui-" + namespace + "-p" + page + ".tsv"));
            String opener = planned ? guiManifestOpener(namespace, page) : guiOpeners(level, viewer, namespace).get(id);
            if (opener == null && SpecializedOpeners.isSpecialized(id.toString())) {
                opener = "specialized:" + id.toString();
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
            boolean slowSettle = chosen != null && (chosen.contains("entity:") || chosen.contains("robit")
                    || chosen.startsWith("cube") || chosen.contains("settle+"));
            if (id.toString().contains("robit")) {
                slowSettle = true;
            }
            runAfterTicks(level.getServer(), slowSettle ? 40 : GUI_SETTLE_TICKS,
                    () -> finishGuiPage(level, viewer, namespace, page, id, chosen, rig, src));
            return 1;
        }
        writeGuiManifest(namespace, page, id, guiStem, tsv, placed, failed, src);
        return placed;
    }

    private static void finishGuiPage(ServerLevel level, ServerPlayer viewer, String namespace, int page,
                                      ResourceLocation id, String opener, BlockPos rig, CommandSourceStack src) {
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
            String spec = id.toString();
            if (SpecializedOpeners.isSpecialized(spec)) {
                try {
                    SpecializedOpeners.place(level, viewer, rig, spec, e -> {
                        guiEntity = e;
                        GUI_SPAWNED.add(e);
                    });
                    if (SpecializedOpeners.act(level, viewer, rig, spec, guiEntity) && guiOpenMenu(viewer) != null) {
                        how = "opened-by:specialized:" + spec;
                    }
                } catch (Throwable t) {
                    LOGGER.info("[cimscene] finishGuiPage specialized fallback failed for {}: {}", spec, t.toString());
                }
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
                if (opener != null) {
                    // Kept, so the crawl's reopen of this page tries the same opener again.
                    tsv.append("opener\t-\t-\t-\t").append(id).append('\t').append(opener).append('\n');
                }
                LOGGER.info("[cimscene] gallery-gui skip ns={} page={} reason=open-failed menu={} opener={} err={}",
                        namespace, page, id, opener, t.toString());
            }
        }
        if (how != null) {
            placed++;
            tsv.append("cell\t").append(rig.getX()).append('\t').append(rig.getY()).append('\t')
                    .append(rig.getZ()).append('\t').append(id).append('\t').append(how).append('\t')
                    .append(viewer.containerMenu.getClass().getName()).append('\n');
            List<String> all = GUI_ALL_OPENERS.getOrDefault(id, opener == null ? List.of() : List.of(opener));
            for (String each : all) {
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
        writeGuiManifest(namespace, page, id, "gallery-gui-" + namespace + "-p" + page, tsv, placed, failed, src);
    }

    private static void writeGuiManifest(String namespace, int page, ResourceLocation id, String guiStem,
                                         StringBuilder tsv, int placed, int failed, CommandSourceStack src) {
        // 14-dj THE GUI SETTLE PIN + FREEZE — the menus lane's half of the
        // settle-freeze instrument (the block half graduated in 14-di). The
        // menus crawl's shots were framed against a LIVE world: the day clock
        // ran the sky gradient wherever each leg's boot/settle timing had
        // brought it (the measured robit-class backdrop: the truth leg stood
        // in morning light, the ours leg in near-darkness — 627984 ae across
        // the whole backdrop on every robit press page, while the GUI panel
        // itself matched pixel for pixel), and the weather timer dimmed the
        // sky whenever its own clock said so. A menu can only be honest-red
        // or honest-green against a backdrop the next run can reproduce: the
        // terminal point pins the day clock at noon and clears the weather
        // through the proven quiet dispatcher (runQuiet — the same surface
        // the biome walk uses for fillbiome, zero new mojmap), THEN freezes
        // the world at that exact frame — the same vanilla tickRateManager
        // freeze 14-di verified on the 1.21.1 decompile: the server's day
        // clock, BE tickers and scheduled ticks gate on runsNormally, and
        // the broadcast ClientboundTickingStatePacket stops the joined
        // client's sky/particle/texture clocks on the same flag. Every page
        // therefore shoots the identical noon sky on both legs and across
        // runs; the widget crawl's press pages inherit the frozen frame (the
        // press packets ride the network stack, which never freezes). Every
        // gui command entry (page, scan, settle) unfreezes first — the
        // openers' settles and the probe builds need the ticking world.
        runQuiet(src, "time set 6000");
        runQuiet(src, "weather clear");
        setGalleryFrozen(src.getServer(), true, "gui " + guiStem);
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
            // THE VANILLA SLOT SEMANTICS (measured: run #302's ae2 p0 pair
            // shifted the truth's items down one row item-for-item — the
            // writer emitted CONTAINER numbering (Bukkit 9..35) under the
            // vanilla-looking inventory.N name, and the truth's rcon
            // `item replace entity cimviewer $slot` passes the name RAW,
            // where vanilla inventory.N indexes the 27 MAIN slots 0..26:
            // manifest inventory.9 filled the truth's middle row while ours
            // filled the top row). 0..8 = hotbar.N; 9..35 = inventory.(i-9)
            // — the manifest names the slots the vanilla command fills, so
            // BOTH legs place the same item in the same slot.
            String slot = i < 9 ? ("hotbar." + i) : ("inventory." + (i - 9));
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
        closeGalleryAssemblies();
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
        final int[] asyncFailures = {0};
        final SceneSetupBarrier setup = new SceneSetupBarrier();
        // 14-ea: the pin deadline — spawn tick through the settle and the
        // shot window (every spawned subject's delta movement is re-zeroed
        // on every tick until then; see MOTION_PINNED)
        final int pinDeadline = level.getServer().getTickCount()
                + ENTITY_SETTLE_TICKS + MOTION_PIN_GRACE_TICKS;
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
            // 14-eb THE SPAWN-SUPPORT TABLE: a subject that only lives on its
            // own support block (the robit's charge pad) gets it placed at
            // the cell's ground BEFORE the spawn — the support rides the
            // cell's picture on both legs (both stacks run this same code).
            ResourceLocation supportId = spawnSupportFor(id);
            if (supportId != null) {
                Block supBlock = BuiltInRegistries.BLOCK.get(supportId);
                if (supBlock == null || supBlock.defaultBlockState().isAir()) {
                    skipNotes.put(i, "no-support");
                    LOGGER.info("[cimscene] gallery-entity skip ns={} page={} index={} "
                            + "reason=no-support entity={}", namespace, page, from + i, id);
                    continue;
                }
                level.setBlock(new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                                GALLERY_Y0 + row * ENTITY_PITCH - 1, GALLERY_Z),
                        supBlock.defaultBlockState(), Block.UPDATE_CLIENTS);
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
                if (id.toString().equals("create:carriage_contraption")) {
                    BlockPos cell = new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                            GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z);
                    TrainRig rig = new TrainRig(level, cell);
                    GALLERY_TRAINS.add(rig);
                    Runnable complete = setup.begin();
                    final int index = i;
                    // THE LIFETIME RIG'S PROVEN SEQUENCING (carriage-page 43027709622
                    // red vs the passing lifetime context on the same server): the
                    // lifetime rig starts via runAfterTicks(5) after its chunk forcing,
                    // the page rig started synchronously inside the walk's setBlock
                    // loop tick. Give the page rig the same settling gap before its
                    // track/station clicks; the gate (complete) still closes from the
                    // rig's own ready/failure callbacks.
                    runAfterTicks(level.getServer(), 5, () -> rig.start(ready -> {
                        try {
                            if (gen != entityPageGen) { rig.close(); return; }
                            ready.addTag(GALLERY_TAG);
                            // The actual registered train owns position, motion and lifetime.
                            spawned.add(ready);
                            spawnedIdx.add(index);
                        } catch (ReflectiveOperationException bad) {
                            throw new IllegalStateException(bad);
                        } finally { complete.run(); }
                    }, failure -> {
                        try {
                            if (gen == entityPageGen) {
                                asyncFailures[0]++;
                                skipNotes.put(index, "failed:" + failure.getClass().getName());
                                LOGGER.error("[cimscene] gallery native train assembly failed", failure);
                            }
                            rig.close();
                        } catch (ReflectiveOperationException bad) {
                            throw new IllegalStateException(bad);
                        } finally { complete.run(); }
                    }));
                    continue;
                }
                if (id.toString().equals("create:gantry_contraption")) {
                    BlockPos cell = new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                            GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z);
                    GantryRig rig = new GantryRig(level, cell);
                    GALLERY_GANTRIES.add(rig);
                    Runnable complete = setup.begin();
                    final int index = i;
                    // Same proven sequencing as the TrainRig page start above.
                    runAfterTicks(level.getServer(), 5, () -> rig.start(ready -> {
                        try {
                            if (gen != entityPageGen) { rig.close(); return; }
                            ready.setNoGravity(true);
                            ready.setInvulnerable(true);
                            ready.addTag(GALLERY_TAG);
                            // Keep the real controller/shaft attachment and native motion.
                            spawned.add(ready);
                            spawnedIdx.add(index);
                        } finally { complete.run(); }
                    }, failure -> {
                        try {
                            if (gen == entityPageGen) {
                                asyncFailures[0]++;
                                skipNotes.put(index, "failed:" + failure.getClass().getName());
                                LOGGER.error("[cimscene] gallery native gantry assembly failed", failure);
                            }
                            rig.close();
                        } finally { complete.run(); }
                    }));
                    continue;
                }
                if (id.toString().equals("create:potato_projectile")) configurePotatoProjectile(e);
                boolean robit = id.toString().equals("mekanism:robit");
                boolean parcel = id.toString().equals("create:package");
                boolean blueprint = id.toString().equals("create:crafting_blueprint");
                boolean bearing = id.toString().equals("create:stationary_contraption");
                boolean cart = id.toString().equals("create:contraption");
                boolean glue = id.toString().equals("create:super_glue");
                boolean seat = id.toString().equals("create:seat");
                boolean itemPlaced = robit || parcel || blueprint;
                if (bearing || cart || glue || seat) {
                    BlockPos cell = new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                            GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z);
                    e = seat ? createGallerySeat(level, cell) : glue ? createGalleryGlue(level, cell)
                            : cart ? assembleGalleryCart(level, cell) : assembleGalleryBearing(level, cell);
                    if (cart) {
                        Entity vehicle = e.getVehicle();
                        vehicle.addTag(GALLERY_TAG);
                        vehicle.setNoGravity(true);
                        vehicle.setInvulnerable(true);
                        vehicle.setDeltaMovement(Vec3.ZERO);
                        MOTION_PINNED.put(vehicle, pinDeadline);
                    } else if (seat) {
                        Entity rider = e.getFirstPassenger();
                        rider.addTag(GALLERY_TAG);
                        rider.setDeltaMovement(Vec3.ZERO);
                        MOTION_PINNED.put(rider, pinDeadline);
                    }
                } else if (itemPlaced) {
                    ServerPlayer owner = level.getServer().getPlayerList().getPlayerByName("cimviewer");
                    if (owner == null) owner = new GuiProbe(level);
                    BlockPos cell = new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                            GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z);
                    BlockPos support = blueprint ? cell.north() : cell.below();
                    if (robit) {
                        e = placeRobit(level, owner, support);
                    } else if (blueprint) {
                        level.setBlock(support, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                        e = placeEntityItem(level, owner, support, Direction.SOUTH,
                                "create:crafting_blueprint", "create:crafting_blueprint");
                    } else {
                        level.setBlock(support, Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                        e = placeEntityItem(level, owner, support, "create:cardboard_package_12x12", "create:package");
                    }
                    if (e == null) throw new IllegalStateException("Item placement did not spawn " + id);
                }
                e.setNoGravity(true);
                e.setInvulnerable(true);
                alignGalleryEntity(e, new BlockPos(GALLERY_X0 + col * ENTITY_PITCH,
                        GALLERY_Y0 + row * ENTITY_PITCH, GALLERY_Z), itemPlaced || bearing || cart || glue || seat);
                e.addTag(GALLERY_TAG);
                if (isMob) {
                    // NoAI: no movement, no look-at, no AI despawn logic — a
                    // still picture. Persistence: nothing despawns the subject
                    // between the settle pass and the shot.
                    m_setNoAi(e);
                }
                if (!itemPlaced && !bearing && !cart && !glue && !seat) level.addFreshEntity(e);
                // 14-ea THE MOTION PIN: a projectile's own tick re-applies
                // its hardcoded gravity (FallingBlockEntity / ThrowableProjectile
                // both add it INSIDE tick(), ungated by NoGravity) — one
                // zeroed velocity at spawn survives exactly one tick. The
                // per-tick drain (onServerTickPost) re-zeros every pinned
                // subject until the deadline, so the cell position the
                // settle pass manifests is the position the shot draws.
                e.setDeltaMovement(Vec3.ZERO);
                MOTION_PINNED.put(e, pinDeadline);
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
        setup.finish(() -> runAfterTicks(level.getServer(), ENTITY_SETTLE_TICKS, () -> settleGalleryEntity(
                fLevel, fNs, fPage, fSlice, spawned, spawnedIdx,
                fNoInstance, fWrongSeries, fFailed + asyncFailures[0], fSkipNotes,
                fFrom, fSeries, gen, false)));
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
        int now = event.getServer().getTickCount();
        if (!DELAYED_TASKS.isEmpty()) {
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
        // 14-ea: the motion pin rides the same tick drain — it must re-zero
        // EVERY tick between the spawn and the pin deadline, independent of
        // the delayed-task schedule (the projectiles' hardcoded per-tick
        // gravity re-applies every tick; the pin answers every tick)
        drainMotionPin(now);
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
            if (!isMob && !id.toString().equals("create:gantry_contraption")
                    && !id.toString().equals("create:carriage_contraption")) {
                // 14-ea: the settle-frame pin — the manifest carries THIS
                // frame's position and the shot reads it right after; the
                // velocity zero is re-asserted here so the per-tick gravity
                // the subject's own tick applied between the last drain and
                // this pass cannot move it inside the shot window
                try {
                    e.setDeltaMovement(Vec3.ZERO);
                } catch (Throwable ignored) {
                    // the drift check already measured the real position
                }
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
        return SceneStateNames.value(p, st.getValue(p));
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
