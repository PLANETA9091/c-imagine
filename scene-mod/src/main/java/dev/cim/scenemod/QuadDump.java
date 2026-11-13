package dev.cim.scenemod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.util.FastColor;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Ask the real client what each wall cell draws. A static pack model cannot
 * know a dynamic model's answer (connected textures, neighbour-aware meshes),
 * so {@code -Dcim.quaddump=<scenes dir>} reads every block manifest at the
 * first tick after loading, rebuilds each page's neighbourhood and dumps the real baked
 * of every cell through the real block renderer. No mod is special-cased:
 * whatever a model does, this captures what it finally draws.
 *
 * <p>Writes, beside the manifests: {@code gallery-<ns>-p<p>.ctx.tsv} rows
 * {@code x y z q<hash>}; {@code quads/<hash>.tsv}, one quad per line (sprite,
 * shade, tint, x y z u v per vertex, then r g b per vertex; model units,
 * sprite-local 0..16 UVs, colour = native diffuse x ambient occlusion x tint x baked color; a sprite prefixed
 * with @ is an exact PNG resource such as @mekanism:render/jetpack.png;
 * ~hash names a decoded custom-atlas sprite under quads/textures/hash.png;
 * optional four vertex-alpha multipliers follow the twelve RGB fields, and
 * native liquid rows append their registered homogeneous chunk material);
 * {@code quads/rows.tsv}, the distinct {@code state#q<hash>} manifest rows.
 */
final class QuadDump {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern MANIFEST = Pattern.compile("gallery-(.+)-p(\\d+)\\.tsv");
    /** The bottom wall row stands on this floor (GALLERY_Y0 - 1). */
    private static final int FLOOR_Y = -61;
    /** DefaultVertexFormat.BLOCK: 8 ints per vertex; position at 0, uv at 4. */
    private static final int STRIDE = 8;
    private static final int UV = 4;

    private static final int JOIN_TICKS = 600;
    private static boolean started;
    private static int berDrawn;
    private static int berNotRendered;
    private static int berFailed;
    private static int berForeign;
    private static int berNoLevel;
    private static int waited;
    private static Path capturedTextures;
    private static final Map<TextureAtlasSprite, String> capturedSpriteNames = new IdentityHashMap<>();
    private static final Map<String, String> spriteAliases = new java.util.TreeMap<>();
    // 14-dg: the runtime-registered textures the replay cannot know (the font
    // glyph atlases — the nixie text quads' texture) read back once to a
    // captured png and every consumer row references it by the ~hash name.
    private static final Map<AbstractTexture, String> capturedTextureNames = new IdentityHashMap<>();

    private QuadDump() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(QuadDump::onTick);
    }

    /**
     * 14-bj GATED DUMP: when {@code cim.quaddump.waitFor} names a file, the
     * dump stands by until that file EXISTS. The seed walk needs this
     * client's player IN the world BEFORE it runs — the player ticket keeps
     * the wall chunks entity-ticking, exactly like the truth walk's client
     * does — so BlockEntity-driven blocks (Create doors, pipes, shafts)
     * settle the wall identically on both legs. Measured #158 without the
     * player: the seed's doors never ticked (page 16: mutated=0 dissolved=0)
     * while the truth's did (10 dissolved + silent powered/open flips) — the
     * two legs' manifests diverged on 43 cells in shard 0 alone and every
     * such cell compared DIFFERENT states. The client boots before the walk;
     * the workflow touches the file when the manifests are final.
     */
    private static Path waitForFile() {
        String p = System.getProperty("cim.quaddump.waitFor");
        return p == null || p.isEmpty() ? null : Path.of(p);
    }

    /** Models are ready once the loading overlay is gone, whichever screen follows it. */
    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (started || mc.getOverlay() != null) {
            return;
        }
        Path trigger = waitForFile();
        if (trigger != null) {
            // gated mode: the dump MUST ride a joined client (the player
            // ticket is the point) and MUST NOT run before the walk touched
            // the trigger — no JOIN_TICKS fall-through, the client just waits.
            if (mc.player == null || !Files.exists(trigger)) {
                return;
            }
        }
        // some item renderers read the local player: give a reachable server time to admit it
        if (mc.player == null && ++waited < JOIN_TICKS) {
            return;
        }
        started = true;
        Path scenes = Path.of(System.getProperty("cim.quaddump"));
        try {
            run(scenes);
            Files.writeString(scenes.resolve("quads").resolve("done"), "ok\n");
        } catch (Throwable t) {
            LOGGER.error("[cimscene] quad dump failed", t);
        }
        Runtime.getRuntime().halt(0);
    }

    private static void run(Path scenes) throws Exception {
        // Instanced geometry never reaches our VertexConsumer. Require the
        // optional Flywheel backend's supported ordinary-renderer fallback.
        try {
            Class<?> backend = Class.forName("dev.engine_room.flywheel.api.backend.BackendManager");
            boolean active = (boolean) backend.getMethod("isBackendOn").invoke(null);
            LOGGER.info("[cimscene] quad dump flywheel backend active={}", active);
            if (active) {
                throw new IllegalStateException("Quad capture requires Flywheel backend=flywheel:off");
            }
        } catch (ClassNotFoundException absent) {
            LOGGER.info("[cimscene] quad dump flywheel absent");
        }
        Path out = scenes.resolve("quads");
        Files.createDirectories(out);
        capturedTextures = out.resolve("textures");
        capturedSpriteNames.clear();
        spriteAliases.clear();
        Minecraft mc = Minecraft.getInstance();
        // Like LevelRenderer immediately before entities/BERs. Tick capture
        // otherwise inherits the last GUI pass's CPU diffuse-light state.
        setupCaptureLighting(mc.level != null && mc.level.effects().constantAmbientLight());
        TextureAtlas atlas = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        List<TextureAtlasSprite> sprites = new ArrayList<>(atlas.getTextures().values());
        TreeSet<String> rows = new TreeSet<>();
        int cells = 0;
        int dumped = 0;
        int failed = 0;
        List<Path> manifests;
        try (Stream<Path> s = Files.list(scenes)) {
            manifests = s.filter(p -> MANIFEST.matcher(p.getFileName().toString()).matches()).sorted().toList();
        }
        for (Path mf : manifests) {
            List<String> lines = Files.readAllLines(mf, StandardCharsets.UTF_8);
            boolean fluidPage = !lines.isEmpty() && lines.get(0).startsWith("cell\tx\ty\tz\tfluid");
            if (lines.isEmpty() || (!fluidPage && !lines.get(0).startsWith("cell\tx\ty\tz\tstate"))) {
                continue;
            }
            Page page = new Page();
            Path lightFile = mf.resolveSibling(mf.getFileName().toString().replaceAll("\\.tsv$", ".light"));
            if (Files.exists(lightFile)) {
                page.light = CaptureLightData.parse(Files.readString(lightFile, StandardCharsets.UTF_8));
            } else {
                LOGGER.warn("[cimscene] quad dump legacy page without settled light {}", mf.getFileName());
            }
            page.requireSnapshot = fluidPage;
            Map<BlockPos, String> names = new HashMap<>();
            Map<BlockPos, BlockState> targets = new HashMap<>();
            java.util.Set<BlockPos> liquids = new java.util.HashSet<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split("\t");
                if (c.length < 5 || !c[0].equals("cell") || c[1].equals("x")) {
                    continue;
                }
                if (fluidPage) {
                    if (c.length < 8 || !(c[6].equals("mod-tank") || c[6].equals("block"))) continue;
                    c[4] = c[7].split("\\|", 2)[0];
                }
                BlockPos pos = new BlockPos(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]));
                try {
                    // The manifest uses Property.getName, exactly as the native
                    // parser expects. Mod-defined serialized values can be case-sensitive.
                    page.cells.put(pos, BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                                    c[4], false)
                            .blockState());
                    names.put(pos, c[4]);
                    targets.put(pos, page.cells.get(pos));
                    if (fluidPage && c[6].equals("block")) {
                        liquids.add(pos);
                    }
                } catch (Exception bad) {
                    failed++;
                    LOGGER.info("[cimscene] quad dump skip {}: {}", c[4], bad.toString());
                }
            }
            StringBuilder ctx = new StringBuilder();
            // 14-bn: the walk's settled BE NBT (the sibling .benbt file — the
            // manifest's own schema stays 7-column). Loaded into the capture
            // BE so runtime-only state (the gearshift's shifted flag, the
            // fluid pipe's flow data) renders as the wall settled it, not as
            // a fresh BE's defaults.
            Map<BlockPos, String> beTags = new HashMap<>();
            Path benbt = mf.resolveSibling(
                    mf.getFileName().toString().replaceAll("\\.tsv$", ".benbt"));
            if (Files.exists(benbt)) {
                // never let a sibling file kill the dump: a malformed .benbt
                // costs its cells' BE fidelity (fresh-BE fallback), not the run
                try {
                    for (String line : Files.readAllLines(benbt, StandardCharsets.UTF_8)) {
                        String[] c = line.split("\t");
                        // the header row ("cell x y z nbt") rides the same shape
                        // as a data row's prefix — skip it like the manifest
                        // parser skips its own
                        if (c.length >= 5 && c[0].equals("cell") && !c[1].equals("x")) {
                            beTags.put(new BlockPos(Integer.parseInt(c[1]), Integer.parseInt(c[2]),
                                    Integer.parseInt(c[3])), c[4]);
                        }
                    }
                    LOGGER.info("[cimscene] quad dump be nbt rows {}: {}", benbt.getFileName(), beTags.size());
                } catch (Throwable bad) {
                    LOGGER.info("[cimscene] quad dump be nbt unusable {}: {}",
                            benbt.getFileName(), bad.toString());
                    beTags.clear();
                }
            }
            page.beTags.putAll(beTags);
            // Rebuild the same sealed six-face cases as CimSceneMod; support
            // blocks are context, not additional capture targets.
            for (BlockPos pos : liquids) {
                for (Direction face : Direction.values()) {
                    BlockPos shell = pos.relative(face);
                    if (shell.getY() >= FLOOR_Y + 1) {
                        page.cells.putIfAbsent(shell, Blocks.GLASS.defaultBlockState());
                    }
                }
            }
            for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) {
                cells++;
                if (fluidPage && !liquids.contains(e.getKey()) && !page.beTags.containsKey(e.getKey())) {
                    failed++;
                    LOGGER.info("[cimscene] quad dump fluid tank missing real BE snapshot {} at {}",
                            names.get(e.getKey()), e.getKey());
                    continue;
                }
                try {
                    if (fluidPage && !liquids.contains(e.getKey()) && page.getBlockEntity(e.getKey()) == null) {
                        throw new IllegalStateException("native tank BE unavailable");
                    }
                    long priorBerFailures = berFailed;
                    long priorBerForeign = berForeign;
                    String mesh = liquids.contains(e.getKey())
                            ? liquidMeshFor(page, e.getKey(), e.getValue(), sprites)
                            : meshFor(page, e.getKey(), e.getValue(), sprites);
                    if (berFailed != priorBerFailures || berForeign != priorBerForeign) {
                        throw new IllegalStateException("native block entity renderer failed or lost geometry");
                    }
                    if (mesh == null) {
                        continue;
                    }
                    if (mesh.isEmpty()) mesh = "# cimscene native empty mesh v1\n";
                    String hash = sha(mesh);
                    Path file = out.resolve(hash + ".tsv");
                    if (!Files.exists(file)) {
                        Files.writeString(file, mesh, StandardCharsets.UTF_8);
                    }
                    BlockPos p = e.getKey();
                    ctx.append(p.getX()).append('\t').append(p.getY()).append('\t').append(p.getZ())
                            .append("\tq").append(hash).append('\n');
                    rows.add(names.get(p) + "#q" + hash);
                    dumped++;
                } catch (Throwable t) {
                    failed++;
                    LOGGER.info("[cimscene] quad dump skip {}: {}", names.get(e.getKey()), t.toString());
                }
            }
            if (ctx.length() > 0) {
                String name = mf.getFileName().toString();
                Files.writeString(mf.resolveSibling(name.substring(0, name.length() - 4) + ".ctx.tsv"),
                        ctx.toString(), StandardCharsets.UTF_8);
            }
        }
        Files.write(out.resolve("rows.tsv"), rows, StandardCharsets.UTF_8);
        dumpItems(scenes, out, sprites);
        StringBuilder aliases = new StringBuilder("# native sprite\tdecoded capture\n");
        spriteAliases.forEach((name, capture) -> aliases.append(name).append('\t').append(capture).append('\n'));
        Files.writeString(out.resolve("sprite-aliases.tsv"), aliases, StandardCharsets.UTF_8);
        LOGGER.info("[cimscene] quad dump captured_atlas_sprites={} captured_textures={}",
                capturedSpriteNames.size(), capturedTextureNames.size());
        LOGGER.info("[cimscene] quad dump done cells={} dumped={} distinct={} failed={} "
                        + "block_entities drawn={} failed={} foreign={} no_level={} not_rendered={}",
                cells, dumped, rows.size(), failed, berDrawn, berFailed, berForeign, berNoLevel, berNotRendered);
    }

    /** Native liquid vertices use chunk-local coordinates on each axis. */
    static Vec3 liquidCaptureOrigin(BlockPos pos) {
        return new Vec3(-(pos.getX() & 15), -(pos.getY() & 15), -(pos.getZ() & 15));
    }

    private static String liquidMeshFor(Page page, BlockPos pos, BlockState state,
                                        List<TextureAtlasSprite> sprites) {
        return liquidMeshFor(page, pos, state, sprites, true);
    }

    private static String liquidMeshFor(Page page, BlockPos pos, BlockState state,
                                        List<TextureAtlasSprite> sprites, boolean requireGeometry) {
        FluidState fluid = state.getFluidState();
        // A flowing registry entry may genuinely settle to air in the sealed
        // rig. The settled manifest replays that exact vanilla air state.
        if (fluid.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        ItemCapture capture = new ItemCapture(sprites, liquidCaptureOrigin(pos));
        VertexConsumer consumer = capture.bufferFor("liquid",
                "texture[Optional[minecraft:textures/atlas/blocks.png]]", false);
        mc.getBlockRenderer().renderLiquid(pos, page, consumer, state, fluid);
        capture.flush();
        if (capture.foreign != 0 || (requireGeometry && capture.out.length() == 0)) {
            throw new IllegalStateException("native liquid renderer produced missing/unbound geometry");
        }
        // Preserve the real registered chunk material, not a guessed alpha mode.
        RenderType layer = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(fluid);
        String material;
        if (layer == RenderType.translucent()) material = "translucent";
        else if (layer == RenderType.solid()) material = "solid";
        else if (layer == RenderType.cutout()) material = "cutout";
        else throw new IllegalStateException("unsupported native liquid layer: " + layer);
        return withLiquidMaterial(capture.out.toString(), material);
    }

    static String withLiquidMaterial(String mesh, String material) {
        StringBuilder out = new StringBuilder();
        for (String line : mesh.lines().toList()) {
            String[] row = line.split("\t", -1);
            if (row.length != 39 && row.length != 41) {
                throw new IllegalArgumentException("unexpected liquid vertex row: " + row.length);
            }
            // Material precedes the optional BLOCK/SKY tail in both producer and reader.
            out.append(String.join("\t", java.util.Arrays.copyOf(row, 39))).append('\t').append(material);
            if (row.length == 41) out.append('\t').append(row[39]).append('\t').append(row[40]);
            out.append('\n');
        }
        return out.toString();
    }

    /** Keep the native chunk alpha mode for the layers the replay can name. */
    static <T> String withBlockMaterial(String mesh, T layer, T solid, T cutout, T translucent) {
        if (layer == solid) return withLiquidMaterial(mesh, "solid");
        if (layer == translucent) return withLiquidMaterial(mesh, "translucent");
        if (layer == cutout) return withLiquidMaterial(mesh, "cutout");
        // Mipped cutout and custom layers retain the existing legacy encoding:
        // the replay format cannot express their complete pipeline state yet.
        return mesh;
    }

    static void setupCaptureLighting(boolean constantAmbientLight) {
        if (constantAmbientLight) Lighting.setupNetherLevel();
        else Lighting.setupLevel();
    }

    /**
     * The cell through the real block renderer: its checkSides culling, its
     * ambient occlusion, its tint and offset, all captured at the one point
     * they reach the vertex buffer. Null when the renderer draws nothing.
     */
    private static String meshFor(Page page, BlockPos pos, BlockState state, List<TextureAtlasSprite> sprites) {
        Minecraft mc = Minecraft.getInstance();
        Capture capture = new Capture(sprites, state.getOffset(page, pos));
        // SectionCompiler renders fluid independently of RenderShape, including
        // waterlogged MODEL blocks and liquid blocks with no static model.
        // Fully occluded fluid can legitimately emit no faces on a block page.
        if (!state.getFluidState().isEmpty()) {
            capture.out.append(liquidMeshFor(page, pos, state, sprites, false));
        }
        // Native SectionCompiler only submits static geometry for MODEL states.
        // ENTITYBLOCK_ANIMATED still reaches its BER, without a phantom static panel.
        if (state.getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL) {
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            ModelData data = captureModelData(model, page, pos, state);
            RandomSource random = RandomSource.create();
            long seed = state.getSeed(pos);
            random.setSeed(seed);
            PoseStack pose = new PoseStack();
            for (RenderType type : model.getRenderTypes(state, random, data)) {
                Capture layer = new Capture(sprites, state.getOffset(page, pos));
                mc.getBlockRenderer().getModelRenderer().tesselateBlock(page, model, state, pos, pose, layer, true,
                        random, seed, OverlayTexture.NO_OVERLAY, data, type);
                capture.out.append(withBlockMaterial(layer.out.toString(), type,
                        RenderType.solid(), RenderType.cutout(), RenderType.translucent()));
            }
        }
        if (state.hasBlockEntity()) {
            appendBlockEntity(page, pos, state, sprites, capture.out);
        }
        return capture.out.toString();
    }

    static ModelData captureModelData(BakedModel model, BlockAndTintGetter page,
                                      BlockPos pos, BlockState state) {
        return model.getModelData(page, pos, state, page.getModelData(pos));
    }

    /**
     * What the cell's BlockEntityRenderer draws on top of its block model: for
     * a Create door that is the whole door. The capture BE is the wall's OWN
     * settled state when the manifest's sibling .benbt file carries it (14-bn:
     * the walk saves each settled tile's real NBT server-side); only a cell
     * without a row falls back to a fresh block entity, so state a mod only
     * sets at runtime renders as the wall holds it, not as the type default.
     * Mod code can fail here in any way; that costs this cell its renderer,
     * never the dump, and the final log line counts it.
     */
    private static void appendBlockEntity(Page page, BlockPos pos, BlockState state,
                                          List<TextureAtlasSprite> sprites, StringBuilder out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            berNoLevel++;
            return;
        }
        try {
            // Static model data and the BER observe the same restored BE.
            BlockEntity be = page.getBlockEntity(pos);
            if (be == null) {
                return;
            }
            BlockEntityRenderer<BlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer == null) {
                if (isNixieRenderer(be)) {
                    LOGGER.info("[cimscene] 14-dn text-quads diag {}: no renderer registered", state.getBlock());
                }
                return;
            }
            // Match BlockEntityRenderDispatcher's eligibility checks. Some
            // renderers require a formed structure, nonempty fluid, or active
            // portal before their render method can safely be called.
            if (!be.getType().isValid(state)
                    || !renderer.shouldRender(be, mc.gameRenderer.getMainCamera().getPosition())) {
                berNotRendered++;
                if (isNixieRenderer(be)) {
                    LOGGER.info("[cimscene] 14-dn text-quads diag {}: not rendered (typeValid={} shouldRender={})",
                            state.getBlock(), be.getType().isValid(state),
                            renderer.shouldRender(be, mc.gameRenderer.getMainCamera().getPosition()));
                }
                return;
            }
            ItemCapture capture = new ItemCapture(sprites, 0);
            renderer.render(be, 0, new PoseStack(), capture,
                    net.minecraft.client.renderer.LevelRenderer.getLightColor(page, pos), OverlayTexture.NO_OVERLAY);
            capture.flush();
            out.append(capture.out);
            berForeign += capture.foreign;
            if (capture.out.length() > 0) {
                berDrawn++;
            }
            if (isNixieRenderer(be)) {
                LOGGER.info("[cimscene] 14-dn text-quads diag {}: renderer={} buffers={} outLen={} foreign={}",
                        state.getBlock(), renderer.getClass().getName(), capture.bufferStates(),
                        capture.out.length(), capture.foreign);
            }
        } catch (Throwable t) {
            berFailed++;
            LOGGER.info("[cimscene] quad dump block entity skip {}: {}", state.getBlock(), t.toString());
        }
    }

    /** 14-dn: the where-do-text-quads-go diagnostic targets the nixie text-gap
     *  class — every nixie-family BER's capture stream lands in the boot log
     *  (the renderer class, the RenderType buffer stream, the emitted length
     *  and the foreign count), so the next run answers whether the capture rig
     *  ever sees the glyph quads and in which RenderType. */
    private static boolean isNixieRenderer(BlockEntity be) {
        String type = String.valueOf(be.getType());
        String cls = be.getClass().getName().toLowerCase();
        return cls.contains("nixie") || type.toLowerCase().contains("nixie");
    }

    /** Records each quad the renderer emits; everything per-vertex arrives in putBulkData. */
    private static final class Capture implements VertexConsumer {
        final StringBuilder out = new StringBuilder();
        final List<TextureAtlasSprite> sprites;
        final Vec3 offset;

        Capture(List<TextureAtlasSprite> sprites, Vec3 offset) {
            this.sprites = sprites;
            this.offset = offset;
        }

        @Override
        public void putBulkData(PoseStack.Pose pose, BakedQuad q, float[] brightness, float r, float g, float b,
                                float a, int[] light, int overlay, boolean readAlpha) {
            // 14-cy: the quad's packed light (block<<4|sky<<20 — LevelRenderer
            // getLightColor layout) rides the row so the pack can split the
            // composite into the scene-lit and the fullbright (mod-forced
            // light override) quad groups, and the replay can pin each
            // group's display to the light the truth actually rendered with.
            int packed = light != null && light.length > 0
                    ? mergePackedLight(light[0], q.getVertices()[6]) : -1;
            appendQuad(out, q, sprites, offset, brightness, r, g, b, a, readAlpha, packed);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /** Native applyBakedLighting takes the unsigned maximum of each UV2 channel. */
    private static int mergePackedLight(int scene, int baked) {
        return Math.max(scene & 0xffff, baked & 0xffff)
                | (Math.max((scene >>> 16) & 0xffff, (baked >>> 16) & 0xffff) << 16);
    }

    private static void appendQuad(StringBuilder sb, BakedQuad q, List<TextureAtlasSprite> sprites, Vec3 off,
                                   float[] brightness, float r, float g, float b, float a, boolean readColor,
                                   int packedLight) {
        int[] v = q.getVertices();
        float[][] pos = new float[4][3];
        float[][] uv = new float[4][2];
        float[][] rgb = new float[4][4];
        for (int i = 0; i < 4; i++) {
            int o = i * STRIDE;
            pos[i][0] = Float.intBitsToFloat(v[o]) + (float) off.x;
            pos[i][1] = Float.intBitsToFloat(v[o + 1]) + (float) off.y;
            pos[i][2] = Float.intBitsToFloat(v[o + 2]) + (float) off.z;
            uv[i][0] = Float.intBitsToFloat(v[o + UV]);
            uv[i][1] = Float.intBitsToFloat(v[o + UV + 1]);
            bakedColor(rgb[i], v[o + 3], brightness[i], r, g, b, a, readColor);
        }
        // Chunk shaders consume the renderer's already-shaded RGB directly.
        // Replaying q.isShade here would add the item shader's unrelated light.
        if (!writeQuad(sb, q.getSprite(), sprites, false, q.getTintIndex(), pos, uv, rgb, false, packedLight)) {
            throw new IllegalStateException("native block quad texture unavailable");
        }
    }

    /** Match the native BLOCK byte layout and VertexConsumer's final byte quantization. */
    private static void bakedColor(float[] out, int packed, float brightness,
                                   float r, float g, float b, float a, boolean readColor) {
        // Native VertexConsumer reads bytes from a native-order IntBuffer.
        int rgba = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN
                ? packed : Integer.reverseBytes(packed);
        out[0] = (int) (readColor ? (rgba & 255) * brightness * r : brightness * r * 255f) / 255f;
        out[1] = (int) (readColor ? ((rgba >>> 8) & 255) * brightness * g : brightness * g * 255f) / 255f;
        out[2] = (int) (readColor ? ((rgba >>> 16) & 255) * brightness * b : brightness * b * 255f) / 255f;
        out[3] = (readColor ? (int) ((a * ((rgba >>> 24) & 255) / 255f) * 255)
                : (int) (a * 255)) / 255f;
    }

    /** Positions in 0..1 block space, UVs in atlas space; a null sprite is looked up by UV. */
    private static boolean writeQuad(StringBuilder sb, TextureAtlasSprite hint, List<TextureAtlasSprite> sprites,
                                     boolean shade, int tint, float[][] pos, float[][] uv, float[][] rgb) {
        return writeQuad(sb, hint, sprites, shade, tint, pos, uv, rgb, false, -1);
    }

    private static boolean writeQuad(StringBuilder sb, TextureAtlasSprite hint, List<TextureAtlasSprite> sprites,
                                     boolean shade, int tint, float[][] pos, float[][] uv, float[][] rgb,
                                     boolean capturePixels) {
        return writeQuad(sb, hint, sprites, shade, tint, pos, uv, rgb, capturePixels, -1);
    }

    private static boolean writeQuad(StringBuilder sb, TextureAtlasSprite hint, List<TextureAtlasSprite> sprites,
                                     boolean shade, int tint, float[][] pos, float[][] uv, float[][] rgb,
                                     boolean capturePixels, int packedLight) {
        return writeQuad(sb, hint, sprites, shade, tint, pos, uv, rgb, capturePixels, packedLight, null);
    }

    private static boolean writeQuad(StringBuilder sb, TextureAtlasSprite hint, List<TextureAtlasSprite> sprites,
                                     boolean shade, int tint, float[][] pos, float[][] uv, float[][] rgb,
                                     boolean capturePixels, int packedLight, String material) {
        float cu = (uv[0][0] + uv[1][0] + uv[2][0] + uv[3][0]) / 4;
        float cv = (uv[0][1] + uv[1][1] + uv[2][1] + uv[3][1]) / 4;
        // a sprite-shifted quad keeps its original sprite field: find the real owner of its UVs
        TextureAtlasSprite sprite = hint;
        if (sprite == null || !contains(sprite, cu, cv)) {
            for (TextureAtlasSprite s : sprites) {
                if (contains(s, cu, cv)) {
                    sprite = s;
                    break;
                }
            }
        }
        if (sprite == null) {
            return false;
        }
        float[][] local = new float[4][2];
        for (int i = 0; i < 4; i++) {
            local[i][0] = (uv[i][0] - sprite.getU0()) / (sprite.getU1() - sprite.getU0());
            local[i][1] = (uv[i][1] - sprite.getV0()) / (sprite.getV1() - sprite.getV0());
        }
        String name = sprite.contents().name().toString();
        String decoded = capturedSprite(sprite);
        // A main-atlas sprite may be generated by a mod's runtime resource pack.
        // Preserve its semantic name; the replay can use these native pixels
        // when no physical PNG exists in the supplied jars.
        if (!capturePixels) spriteAliases.put(name, decoded);
        appendRow(sb, capturePixels ? decoded : name, shade, tint, pos, local, rgb, packedLight, material);
        return true;
    }

    /** Persist the decoded pixels, including generated atlas sources, without guessing a jar path. */
    private static String capturedSprite(TextureAtlasSprite sprite) {
        String existing = capturedSpriteNames.get(sprite);
        if (existing != null) {
            return existing;
        }
        try {
            var contents = sprite.contents();
            byte[] png = contents.getOriginalImage().asByteArray();
            JsonObject metadata = new JsonObject();
            contents.metadata().getSection(AnimationMetadataSection.SERIALIZER).ifPresent(animation -> {
                JsonObject a = new JsonObject();
                a.addProperty("width", contents.width());
                a.addProperty("height", contents.height());
                a.addProperty("frametime", animation.getDefaultFrameTime());
                a.addProperty("interpolate", animation.isInterpolatedFrames());
                JsonArray frames = new JsonArray();
                animation.forEachFrame((index, time) -> {
                    JsonObject frame = new JsonObject();
                    frame.addProperty("index", index);
                    frame.addProperty("time", time);
                    frames.add(frame);
                });
                if (!frames.isEmpty()) {
                    a.add("frames", frames);
                }
                metadata.add("animation", a);
            });
            String meta = metadata.toString();
            String hash = sha(Base64.getEncoder().encodeToString(png) + meta);
            Files.createDirectories(capturedTextures);
            Files.write(capturedTextures.resolve(hash + ".png"), png);
            if (!metadata.isEmpty()) {
                Files.writeString(capturedTextures.resolve(hash + ".png.mcmeta"), meta, StandardCharsets.UTF_8);
            }
            String name = "~" + hash;
            capturedSpriteNames.put(sprite, name);
            return name;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot capture atlas sprite " + sprite.contents().name(), failure);
        }
    }

    /**
     * 14-dg: read a runtime-registered texture back from the GL — the font
     * glyph atlases exist ONLY on the GPU (FontTexture uploads each glyph via
     * SheetGlyphInfo and keeps no NativeImage), so no object graph can reach
     * their pixels. The readback lands as a captured png under the ~hash name
     * (the capturedSprite convention the pack already hosts). An intensity
     * (single-channel) atlas carries the glyph COVERAGE in red: it ships as a
     * white RGBA texture with the coverage in alpha, so the replay's shader
     * (texture x vertexColor) reproduces the font shader's coverage alpha —
     * the nixie text renders the per-character color the renderer recorded.
     * Colored atlases pass through unchanged. Null (the honest drop, counted
     * foreign) when the readback fails for any reason.
     */
    private static String capturedTexture(AbstractTexture texture) {
        String existing = capturedTextureNames.get(texture);
        if (existing != null) {
            return existing;
        }
        if (texture == MissingTextureAtlasSprite.getTexture()) {
            return null;
        }
        try {
            boolean colored = coloredTexture(texture);
            texture.bind();
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
                throw new IllegalStateException("bad texture size " + width + "x" + height);
            }
            java.nio.ByteBuffer pixels = java.nio.ByteBuffer.allocateDirect(
                    width * height * (colored ? 4 : 1)).order(java.nio.ByteOrder.nativeOrder());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0,
                    colored ? GL11.GL_RGBA : GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, pixels);
            pixels.rewind();
            NativeImage image = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (colored) {
                        int r = pixels.get() & 255;
                        int g = pixels.get() & 255;
                        int b = pixels.get() & 255;
                        int a = pixels.get() & 255;
                        image.setPixelRGBA(x, y, FastColor.ABGR32.color(a, b, g, r));
                    } else {
                        int coverage = pixels.get() & 255;
                        image.setPixelRGBA(x, y, FastColor.ABGR32.color(coverage, 255, 255, 255));
                    }
                }
            }
            // 14-ds forensics: one line per captured runtime texture — the
            // channel decision, the texture's real class and its mean RGBA,
            // so a white-mask regression names itself in the seed log
            // (the globe's mask read [255,255,255,106] with 0 non-white px).
            long rs = 0, gs = 0, bs = 0, as = 0, n = (long) width * height;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int p = image.getPixelRGBA(x, y);
                    rs += FastColor.ABGR32.red(p);
                    gs += FastColor.ABGR32.green(p);
                    bs += FastColor.ABGR32.blue(p);
                    as += FastColor.ABGR32.alpha(p);
                }
            }
            LOGGER.info("[cimscene] 14-ds runtime texture {}x{} class={} colored={} meanRGBA=[{},{},{},{}]",
                    width, height, texture.getClass().getName(), colored,
                    n == 0 ? -1 : rs / n, n == 0 ? -1 : gs / n,
                    n == 0 ? -1 : bs / n, n == 0 ? -1 : as / n);
            byte[] png = image.asByteArray();
            image.close();
            String hash = sha(Base64.getEncoder().encodeToString(png));
            Files.createDirectories(capturedTextures);
            Files.write(capturedTextures.resolve(hash + ".png"), png);
            String name = "~" + hash;
            capturedTextureNames.put(texture, name);
            LOGGER.info("[cimscene] captured runtime texture {}x{} colored={} -> {}",
                    width, height, colored, name);
            return name;
        } catch (Throwable t) {
            LOGGER.info("[cimscene] texture capture failed {}: {}", texture, t.toString());
            return null;
        }
    }

    /**
     * 14-ds: the readback channel — a WHITE COVERAGE MASK (RED channel)
     * only for the glyph atlases (the {@code colored} marker, false on
     * fonts), FULL RGBA for every other runtime texture. The old
     * default-false painted every marker-less runtime texture white:
     * the supplementaries globe's DynamicTexture map captured as
     * mean RGBA [255,255,255,106] and its 75 cells measured 18.45M ae
     * (41% of the whole red mass) while every other capture stage was
     * correct. The law lives in CaptureTexture (headless-testable).
     */
    private static boolean coloredTexture(AbstractTexture texture) {
        return !CaptureTexture.isCoverageMaskReadback(texture);
    }

    /** One quad row; {@code uv} is 0..1 across the named sprite or texture. */
    private static void appendRow(StringBuilder sb, String sprite, boolean shade, int tint,
                                  float[][] pos, float[][] uv, float[][] rgb) {
        appendRow(sb, sprite, shade, tint, pos, uv, rgb, -1);
    }

    private static void appendRow(StringBuilder sb, String sprite, boolean shade, int tint,
                                  float[][] pos, float[][] uv, float[][] rgb, int packedLight) {
        appendRow(sb, sprite, shade, tint, pos, uv, rgb, packedLight, null);
    }

    /**
     * One quad row with the 14-dv material tag: {@code material} != null writes
     * the named-chunk-shape column BEFORE the optional light tail (the native
     * 40/42 layout — the pack reader reads the material at c[39] and the light
     * pair at the last two columns on every shape). The tag rides AT EMIT TIME
     * — the streaming contract keeps every row landing in its stream in order.
     */
    private static void appendRow(StringBuilder sb, String sprite, boolean shade, int tint,
                                  float[][] pos, float[][] uv, float[][] rgb, int packedLight,
                                  String material) {
        sb.append(sprite).append('\t').append(shade ? 1 : 0).append('\t').append(tint);
        for (int i = 0; i < 4; i++) {
            sb.append(String.format(Locale.ROOT, "\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f",
                    pos[i][0] * 16, pos[i][1] * 16, pos[i][2] * 16, uv[i][0] * 16, uv[i][1] * 16));
        }
        for (int i = 0; i < 4; i++) {
            sb.append(String.format(Locale.ROOT, "\t%.3f\t%.3f\t%.3f", rgb[i][0], rgb[i][1], rgb[i][2]));
        }
        if (rgb[0].length >= 4) {
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, "\t%.6f", rgb[i][3]));
            }
        }
        if (material != null) {
            sb.append('\t').append(material);
        }
        if (packedLight >= 0) {
            // 14-cy: the captured scene light — block and sky nibbles of the
            // renderer's packed light (the LevelRenderer getLightColor layout:
            // block = bits 4-7, sky = bits 20-23). Absent (-1) = the legacy
            // row shape; every consumer treats the tail as optional.
            sb.append('\t').append((packedLight >> 4) & 15).append('\t').append((packedLight >> 20) & 15);
        }
        sb.append('\n');
    }

    /**
     * Every item of every item page through the real ItemRenderer. Item colours,
     * layered models and whatever a custom renderer draws all arrive here.
     * Geometry on a texture outside the block atlas (entity model parts) is
     * recorded against that texture with whole-texture UVs: the pack hosts any
     * readable PNG. Its UVs mean nothing in block-atlas space — looked up there
     * they land on unrelated sprites (measured: mekanism:flamethrower came out
     * wearing ae2 monitors and a shulker box).
     */
    private static void dumpItems(Path scenes, Path out, List<TextureAtlasSprite> sprites) throws Exception {
        List<Path> manifests;
        try (Stream<Path> s = Files.list(scenes)) {
            manifests = s.filter(p -> p.getFileName().toString().matches("gallery-item-.+-p\\d+\\.tsv")).sorted().toList();
        }
        StringBuilder rows = new StringBuilder();
        int items = 0;
        int empty = 0;
        int failed = 0;
        long foreign = 0;
        for (Path mf : manifests) {
            for (String line : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
                String[] c = line.split("\t");
                if (c.length < 5 || !c[0].equals("cell") || c[1].equals("x")) {
                    continue;
                }
                items++;
                try {
                    ResourceLocation id = ResourceLocation.parse(c[4]);
                    ItemCapture capture = new ItemCapture(sprites, 0.5f);
                    Minecraft.getInstance().getItemRenderer().renderStatic(
                            new ItemStack(BuiltInRegistries.ITEM.get(id)), ItemDisplayContext.NONE,
                            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, new PoseStack(), capture, null, 0);
                    capture.flush();
                    foreign += capture.foreign;
                    if (capture.out.length() == 0) {
                        empty++;
                        continue;
                    }
                    String mesh = capture.out.toString();
                    String hash = sha(mesh);
                    Path file = out.resolve(hash + ".tsv");
                    if (!Files.exists(file)) {
                        Files.writeString(file, mesh, StandardCharsets.UTF_8);
                    }
                    rows.append(c[4]).append('\t').append(hash).append('\n');
                } catch (Throwable t) {
                    failed++;
                    LOGGER.info("[cimscene] quad dump item skip {}: {}", c[4], t.toString());
                }
            }
        }
        Files.writeString(out.resolve("items.tsv"), rows.toString(), StandardCharsets.UTF_8);
        LOGGER.info("[cimscene] quad dump items={} empty={} failed={} foreign_quads={}", items, empty, failed, foreign);
    }

    private static final class Discard implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /** Independent buffers retain their own material and partially recorded quad. */
    private static final class ItemCapture implements MultiBufferSource {
        final StringBuilder out = new StringBuilder();
        final List<TextureAtlasSprite> sprites;
        final Vec3 origin;
        long foreign;
        private final Map<Object, RecordingBuffer> buffers = new java.util.LinkedHashMap<>();
        private final VertexConsumer discard = new Discard();
        ItemCapture(List<TextureAtlasSprite> sprites, float origin) {
            this(sprites, new Vec3(origin, origin, origin));
        }

        ItemCapture(List<TextureAtlasSprite> sprites, Vec3 origin) {
            this.sprites = sprites;
            this.origin = origin;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            // Standard chunk materials consume already-lit vertex RGB;
            // their native shaders do not call minecraft_mix_light.
            boolean shaderDiffuse = type != RenderType.solid() && type != RenderType.cutout()
                    && type != RenderType.cutoutMipped() && type != RenderType.translucent();
            return bufferFor(type, type.toString(), shaderDiffuse);
        }

        private VertexConsumer bufferFor(Object key, String state, boolean shaderDiffuse) {
            if (state.contains("glint")) {
                return discard;
            }
            return buffers.computeIfAbsent(key, ignored -> new RecordingBuffer(state, shaderDiffuse));
        }

        void flush() {
            for (RecordingBuffer buffer : buffers.values()) {
                buffer.flush();
            }
        }

        /** The replay material for the entity layers the pack can name; null = legacy encoding.
         *  14-dv: minecraft:entity_translucent_cull / minecraft:entity_translucent name the
         *  translucent layer — the 40/42 material shapes the pack reader already honors (the
         *  14-dr law's own reading: an explicit captured material proves the alpha semantics).
         *  The measured class: the supplementaries hourglass sand (renderSand draws through
         *  ModMaterials buffer(RenderType::entityTranslucentCull)) rode the legacy encoding,
         *  the replay defaulted it on the BLOCK channel and never read the per-vertex alpha
         *  — the ghost-glass fidelity red. Text/glyph buffers, custom mod layers and the
         *  liquid probe's fake state keep the legacy encoding byte-for-byte. */
        static String entityMaterial(String state) {
            if ("minecraft:entity_translucent_cull".equals(state)
                    || "minecraft:entity_translucent".equals(state)) {
                return "translucent";
            }
            return null;
        }

        /** 14-dn: the RenderType stream a BER asked for (one string per
         *  RecordingBuffer, in request order) — the text-quads diagnostic's
         *  payload for the where-do-text-quads-go question. */
        java.util.List<String> bufferStates() {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (RecordingBuffer buffer : buffers.values()) {
                out.add(buffer.state);
            }
            return out;
        }

        private final class RecordingBuffer implements VertexConsumer {
            final List<TextureAtlasSprite> boundSprites;
            final boolean captureAtlasPixels;
            final String texture;
            final boolean rawShade;
            /** The RenderType's toString (14-dn: the text-quads diagnostic names
             *  every buffer the BER asked for, so the where-do-text-quads-go
             *  question is answered per page in the boot log). */
            final String state;
            /** 14-dv: the buffer's replay material, tagged AT EMIT TIME (the
             *  streaming contract: rows land in {@code out} as they are
             *  emitted — the CaptureBufferTest pins a putBulkData after a
             *  flush landing in the same stream; a per-buffer re-merge would
             *  lose it and reorder interleaved batches). */
            final String material;
            final float[][] pos = new float[4][3];
            final float[][] uv = new float[4][2];
            final float[][] rgb = new float[4][4];
            int n = -1;
            int packedLight = -1;

            RecordingBuffer(String state, boolean shaderDiffuse) {
                this.state = state;
                this.material = entityMaterial(state);
                rawShade = shaderDiffuse;
                String direct = CaptureTexture.fromRenderState(state);
                List<TextureAtlasSprite> atlasSprites = sprites;
                boolean customAtlas = false;
                String resource = CaptureTexture.resourceFromRenderState(state);
                if (resource != null && !resource.equals(InventoryMenu.BLOCK_ATLAS.toString())) {
                    var bound = Minecraft.getInstance().getTextureManager()
                            .getTexture(ResourceLocation.parse(resource), null);
                    if (bound instanceof TextureAtlas atlas) {
                        direct = null;
                        atlasSprites = new ArrayList<>(atlas.getTextures().values());
                        customAtlas = true;
                    }
                } else if (direct == null) {
                    // 14-dg: the text render types name their glyph atlas WITHOUT a
                    // .png suffix (FontSet.stitch registers minecraft:<font>/<n>),
                    // the TEXTURE regex misses it, and the incremental glyph quads
                    // dropped at the sprite search (the nixie text-gap class). The
                    // atlas exists only on the GPU — read it back once to a ~png.
                    String loc = CaptureTexture.optionalLocation(state);
                    if (loc != null && !loc.endsWith(".png")) {
                        var bound = Minecraft.getInstance().getTextureManager()
                                .getTexture(ResourceLocation.parse(loc), null);
                        if (bound instanceof TextureAtlas atlas) {
                            direct = null;
                            atlasSprites = new ArrayList<>(atlas.getTextures().values());
                            customAtlas = true;
                        } else {
                            direct = capturedTexture(bound);
                        }
                    }
                }
                texture = direct;
                boundSprites = atlasSprites;
                captureAtlasPixels = customAtlas;
            }

            @Override
            public void putBulkData(PoseStack.Pose pose, BakedQuad q, float[] brightness, float r, float g, float b,
                                    float a, int[] light, int overlay, boolean readAlpha) {
                int[] v = q.getVertices();
                // Match native applyBakedLighting's unsigned channel maxima.
                // The quad format carries one pair, as for static block capture.
                int quadLight = -1;
                if (light != null && light.length > 0) {
                    quadLight = mergePackedLight(light[0], v[6]);
                }
                float[][] p = new float[4][3];
                float[][] t = new float[4][2];
                float[][] c = new float[4][4];
                Vector3f at = new Vector3f();
                for (int i = 0; i < 4; i++) {
                    int o = i * STRIDE;
                    pose.pose().transformPosition(Float.intBitsToFloat(v[o]), Float.intBitsToFloat(v[o + 1]),
                            Float.intBitsToFloat(v[o + 2]), at);
                    p[i][0] = at.x + (float) origin.x;
                    p[i][1] = at.y + (float) origin.y;
                    p[i][2] = at.z + (float) origin.z;
                    t[i][0] = Float.intBitsToFloat(v[o + UV]);
                    t[i][1] = Float.intBitsToFloat(v[o + UV + 1]);
                    bakedColor(c[i], v[o + 3], brightness[i], r, g, b, a, readAlpha);
                }
                if (texture != null) {
                    appendRow(out, texture, rawShade && q.isShade(), q.getTintIndex(), p, t, c,
                            texture.startsWith("~") ? LightTexture.FULL_BRIGHT : quadLight, material);
                } else {
                    if (!writeQuad(out, captureAtlasPixels ? null : q.getSprite(), boundSprites,
                            rawShade && q.isShade(), q.getTintIndex(), p, t, c, captureAtlasPixels, quadLight,
                            material)) {
                        foreign++;
                    }
                }
            }

            @Override
            public VertexConsumer addVertex(float x, float y, float z) {
                flush();
                n++;
                if (n == 0) packedLight = -1;
                pos[n][0] = x + (float) origin.x;
                pos[n][1] = y + (float) origin.y;
                pos[n][2] = z + (float) origin.z;
                rgb[n][0] = 1;
                rgb[n][1] = 1;
                rgb[n][2] = 1;
                rgb[n][3] = 1;
                return this;
            }

            /** Vertices only complete a quad on the next one, so the last needs a push. */
            void flush() {
                if (n == 3) {
                    if (texture != null) {
                        // 14-dg: the readback textures (the font glyph atlases —
                        // the ~ names) serve ONLY the text render types, and the
                        // font shader ignores the lightmap (flat color x coverage).
                        // The (15,15) tail routes the rows into the pack's
                        // fullbright companion group — the replay pins them
                        // Brightness(15,15) and the text renders flat-bright like
                        // the truth, at the per-character color the renderer set.
                        appendRow(out, texture, rawShade, -1, pos, uv, rgb,
                                texture.startsWith("~") ? LightTexture.FULL_BRIGHT : packedLight, material);
                    } else if (!writeQuad(out, null, boundSprites, rawShade, -1, pos, uv, rgb,
                            captureAtlasPixels, packedLight, material)) {
                        foreign++;
                    }
                    n = -1;
                }
            }

            @Override
            public VertexConsumer setColor(int r, int g, int b, int a) {
                if (n >= 0) {
                    rgb[n][0] = r / 255f;
                    rgb[n][1] = g / 255f;
                    rgb[n][2] = b / 255f;
                    rgb[n][3] = a / 255f;
                }
                return this;
            }

            @Override
            public VertexConsumer setUv(float u, float v) {
                if (n >= 0) {
                    uv[n][0] = u;
                    uv[n][1] = v;
                }
                return this;
            }

            @Override
            public VertexConsumer setUv1(int u, int v) {
                return this;
            }

            @Override
            public VertexConsumer setUv2(int u, int v) {
                if (n == 0) packedLight = (u & 0xffff) | ((v & 0xffff) << 16);
                return this;
            }

            @Override
            public VertexConsumer setNormal(float x, float y, float z) {
                return this;
            }
        }
    }

    private static boolean contains(TextureAtlasSprite s, float u, float v) {
        return u >= s.getU0() && u <= s.getU1() && v >= s.getV0() && v <= s.getV1();
    }

    private static String sha(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            out.append(String.format("%02x", d[i]));
        }
        return out.toString();
    }

    /** A page's wall on its floor; the truth world holds nothing else near it. */
    private static final class Page implements BlockAndTintGetter {
        final Map<BlockPos, BlockState> cells = new HashMap<>();
        final Map<BlockPos, String> beTags = new HashMap<>();
        boolean requireSnapshot;
        CaptureLightData light;
        final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState s = cells.get(pos);
            if (s != null) {
                return s;
            }
            return pos.getY() == FLOOR_Y ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            BlockEntity cached = blockEntities.get(pos);
            if (cached != null) {
                return cached;
            }
            Minecraft mc = Minecraft.getInstance();
            BlockState state = cells.get(pos);
            // Keep worldless static capture's original fallback. Joined
            // capture supplies real BEs to model.getModelData as well as BERs.
            if (mc.level == null || state == null || !(state.getBlock() instanceof EntityBlock block)) {
                return null;
            }
            BlockEntity be = block.newBlockEntity(pos, state);
            if (be == null) {
                return null;
            }
            be.setLevel(mc.level);
            blockEntities.put(pos.immutable(), be);
            String b64 = beTags.get(pos);
            if (b64 != null) {
                try {
                    CaptureBlockEntityData.restore(NbtIo.readCompressed(
                            new ByteArrayInputStream(Base64.getDecoder().decode(b64)),
                            NbtAccounter.unlimitedHeap()),
                            tag -> be.loadWithComponents(tag, mc.level.registryAccess()),
                            tag -> be.handleUpdateTag(tag, mc.level.registryAccess()));
                } catch (Throwable bad) {
                    if (requireSnapshot) throw new IllegalStateException("native tank snapshot could not load", bad);
                    LOGGER.info("[cimscene] quad dump be nbt load skip {}: {}",
                            state.getBlock(), bad.toString());
                }
            }
            return be;
        }

        @Override
        public ModelData getModelData(BlockPos pos) {
            // ModelDataManager snapshots the actual BE's data for SectionCompiler.
            // Use the restored scene BE here rather than the joined client's
            // unrelated last-page snapshot. Null data is a native contract error.
            BlockEntity be = getBlockEntity(pos);
            if (be == null || be.isRemoved()) return ModelData.EMPTY;
            ModelData data = be.getModelData();
            if (data == null) throw new NullPointerException("Null scene ModelData at " + pos);
            return data;
        }

        @Override
        public float getShade(Direction side, boolean shade) {
            return Minecraft.getInstance().level.getShade(side, shade);
        }

        @Override
        public float getShade(float normalX, float normalY, float normalZ, boolean shade) {
            // NeoForge's lighter supports non-axis normals; the real level
            // applies its dimension's shading rather than nearest-face rounding.
            return Minecraft.getInstance().level.getShade(normalX, normalY, normalZ, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        // The live world holds the last walked page when the dump runs.
        // Native AO and BER lighting must read this page's settled snapshot.
        // Older captures without a sidecar retain their explicit legacy path.
        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            if (light != null) return light.brightness(layer, pos);
            Minecraft mc = Minecraft.getInstance();
            return mc.level != null ? mc.level.getBrightness(layer, pos) : 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            if (light != null) return light.rawBrightness(pos, darkening);
            Minecraft mc = Minecraft.getInstance();
            return mc.level != null ? mc.level.getRawBrightness(pos, darkening) : 15;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            // the gallery world is flat plains: temperature 0.8, downfall 0.4
            if (resolver == BiomeColors.GRASS_COLOR_RESOLVER) {
                return GrassColor.get(0.8, 0.4);
            }
            if (resolver == BiomeColors.FOLIAGE_COLOR_RESOLVER) {
                return FoliageColor.get(0.8, 0.4);
            }
            return resolver == BiomeColors.WATER_COLOR_RESOLVER ? 0x3F76E4 : -1;
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }
}
