package dev.cim.scenemod;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * sprite-local 0..16 UVs, colour = ambient occlusion x tint);
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
    private static int berFailed;
    private static int berNoLevel;
    private static int waited;

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
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
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
            if (lines.isEmpty() || !lines.get(0).startsWith("cell\tx\ty\tz\tstate")) {
                continue;
            }
            Page page = new Page();
            Map<BlockPos, String> names = new HashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] c = line.split("\t");
                if (c.length < 5 || !c[0].equals("cell")) {
                    continue;
                }
                BlockPos pos = new BlockPos(Integer.parseInt(c[1]), Integer.parseInt(c[2]), Integer.parseInt(c[3]));
                try {
                    // manifests print some enum values by constant name (part=PULLEY);
                    // the parser takes the serialized name, and ids are lowercase anyway
                    page.cells.put(pos, BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                                    c[4].toLowerCase(Locale.ROOT), false)
                            .blockState());
                    names.put(pos, c[4]);
                } catch (Exception bad) {
                    failed++;
                    LOGGER.info("[cimscene] quad dump skip {}: {}", c[4], bad.toString());
                }
            }
            StringBuilder ctx = new StringBuilder();
            for (Map.Entry<BlockPos, BlockState> e : page.cells.entrySet()) {
                cells++;
                try {
                    String mesh = meshFor(page, e.getKey(), e.getValue(), sprites);
                    if (mesh == null) {
                        continue;
                    }
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
        LOGGER.info("[cimscene] quad dump done cells={} dumped={} distinct={} failed={} "
                        + "block_entities drawn={} failed={} no_level={}",
                cells, dumped, rows.size(), failed, berDrawn, berFailed, berNoLevel);
    }

    /**
     * The cell through the real block renderer: its checkSides culling, its
     * ambient occlusion, its tint and offset, all captured at the one point
     * they reach the vertex buffer. Null when the renderer draws nothing.
     */
    private static String meshFor(Page page, BlockPos pos, BlockState state, List<TextureAtlasSprite> sprites) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        ModelData data = model.getModelData(page, pos, state, ModelData.EMPTY);
        RandomSource random = RandomSource.create();
        long seed = state.getSeed(pos);
        random.setSeed(seed);
        Capture capture = new Capture(sprites, state.getOffset(page, pos));
        PoseStack pose = new PoseStack();
        for (RenderType type : model.getRenderTypes(state, random, data)) {
            mc.getBlockRenderer().getModelRenderer().tesselateBlock(page, model, state, pos, pose, capture, true,
                    random, seed, OverlayTexture.NO_OVERLAY, data, type);
        }
        if (state.hasBlockEntity()) {
            appendBlockEntity(pos, state, sprites, capture.out);
        }
        return capture.out.length() == 0 ? null : capture.out.toString();
    }

    /**
     * What the cell's BlockEntityRenderer draws on top of its block model: for
     * a Create door that is the whole door. A fresh block entity stands in for
     * the wall's, so state a mod only sets at runtime is not reflected. Mod
     * code can fail here in any way; that costs this cell its renderer, never
     * the dump, and the final log line counts it.
     */
    private static void appendBlockEntity(BlockPos pos, BlockState state, List<TextureAtlasSprite> sprites,
                                          StringBuilder out) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            berNoLevel++;
            return;
        }
        try {
            if (!(state.getBlock() instanceof EntityBlock block)) {
                return;
            }
            BlockEntity be = block.newBlockEntity(pos, state);
            if (be == null) {
                return;
            }
            be.setLevel(mc.level);
            BlockEntityRenderer<BlockEntity> renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
            if (renderer == null) {
                return;
            }
            ItemCapture capture = new ItemCapture(sprites, 0);
            renderer.render(be, 0, new PoseStack(), capture, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            capture.flush();
            out.append(capture.out);
            if (capture.out.length() > 0) {
                berDrawn++;
            }
        } catch (Throwable t) {
            berFailed++;
            LOGGER.info("[cimscene] quad dump block entity skip {}: {}", state.getBlock(), t.toString());
        }
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
            appendQuad(out, q, sprites, offset, brightness, r, g, b);
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

    private static void appendQuad(StringBuilder sb, BakedQuad q, List<TextureAtlasSprite> sprites, Vec3 off,
                                   float[] brightness, float r, float g, float b) {
        int[] v = q.getVertices();
        float[][] pos = new float[4][3];
        float[][] uv = new float[4][2];
        float[][] rgb = new float[4][3];
        for (int i = 0; i < 4; i++) {
            int o = i * STRIDE;
            pos[i][0] = Float.intBitsToFloat(v[o]) + (float) off.x;
            pos[i][1] = Float.intBitsToFloat(v[o + 1]) + (float) off.y;
            pos[i][2] = Float.intBitsToFloat(v[o + 2]) + (float) off.z;
            uv[i][0] = Float.intBitsToFloat(v[o + UV]);
            uv[i][1] = Float.intBitsToFloat(v[o + UV + 1]);
            rgb[i][0] = brightness[i] * r;
            rgb[i][1] = brightness[i] * g;
            rgb[i][2] = brightness[i] * b;
        }
        writeQuad(sb, q.getSprite(), sprites, q.isShade(), q.getTintIndex(), pos, uv, rgb);
    }

    /** Positions in 0..1 block space, UVs in atlas space; a null sprite is looked up by UV. */
    private static boolean writeQuad(StringBuilder sb, TextureAtlasSprite hint, List<TextureAtlasSprite> sprites,
                                     boolean shade, int tint, float[][] pos, float[][] uv, float[][] rgb) {
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
        appendRow(sb, sprite.contents().name().toString(), shade, tint, pos, local, rgb);
        return true;
    }

    /** One quad row; {@code uv} is 0..1 across the named sprite or texture. */
    private static void appendRow(StringBuilder sb, String sprite, boolean shade, int tint,
                                  float[][] pos, float[][] uv, float[][] rgb) {
        sb.append(sprite).append('\t').append(shade ? 1 : 0).append('\t').append(tint);
        for (int i = 0; i < 4; i++) {
            sb.append(String.format(Locale.ROOT, "\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f",
                    pos[i][0] * 16, pos[i][1] * 16, pos[i][2] * 16, uv[i][0] * 16, uv[i][1] * 16));
        }
        for (int i = 0; i < 4; i++) {
            sb.append(String.format(Locale.ROOT, "\t%.3f\t%.3f\t%.3f", rgb[i][0], rgb[i][1], rgb[i][2]));
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
                if (c.length < 5 || !c[0].equals("cell")) {
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

    /** A buffer source whose every buffer records: baked quads whole, hand-drawn geometry vertex by vertex. */
    private static final class ItemCapture implements MultiBufferSource, VertexConsumer {
        /** A RenderType prints its texture shard as {@code Optional[ns:textures/path.png]}. */
        private static final Pattern TEXTURE =
                Pattern.compile("Optional\\[([a-z0-9_.-]+):textures/([a-z0-9_./-]+)\\.png\\]");

        final StringBuilder out = new StringBuilder();
        final List<TextureAtlasSprite> sprites;
        final float[][] pos = new float[4][3];
        final float[][] uv = new float[4][2];
        final float[][] rgb = new float[4][3];
        int n = -1;
        long foreign;
        /** The bound buffer's texture when it is not the block atlas. */
        String texture;

        /** Item renderers centre the model on the origin; block entities draw from the corner. */
        final float origin;

        ItemCapture(List<TextureAtlasSprite> sprites, float origin) {
            this.sprites = sprites;
            this.origin = origin;
        }

        /** Foil wraps two buffers and rejects a duplicate; the glint is an overlay, not geometry. */
        private final VertexConsumer discard = new Discard();

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            String state = type.toString();
            if (state.contains("glint")) {
                return discard;
            }
            flush(); // a pending quad belongs to the previous buffer's texture
            Matcher m = TEXTURE.matcher(state);
            texture = m.find() && !m.group(2).equals("atlas/blocks") ? m.group(1) + ":" + m.group(2) : null;
            return this;
        }

        @Override
        public void putBulkData(PoseStack.Pose pose, BakedQuad q, float[] brightness, float r, float g, float b,
                                float a, int[] light, int overlay, boolean readAlpha) {
            int[] v = q.getVertices();
            float[][] p = new float[4][3];
            float[][] t = new float[4][2];
            float[][] c = new float[4][3];
            Vector3f at = new Vector3f();
            for (int i = 0; i < 4; i++) {
                int o = i * STRIDE;
                pose.pose().transformPosition(Float.intBitsToFloat(v[o]), Float.intBitsToFloat(v[o + 1]),
                        Float.intBitsToFloat(v[o + 2]), at);
                p[i][0] = at.x + origin;
                p[i][1] = at.y + origin;
                p[i][2] = at.z + origin;
                t[i][0] = Float.intBitsToFloat(v[o + UV]);
                t[i][1] = Float.intBitsToFloat(v[o + UV + 1]);
                c[i][0] = brightness[i] * r;
                c[i][1] = brightness[i] * g;
                c[i][2] = brightness[i] * b;
            }
            if (texture != null) {
                appendRow(out, texture, q.isShade(), q.getTintIndex(), p, t, c);
            } else {
                writeQuad(out, q.getSprite(), sprites, q.isShade(), q.getTintIndex(), p, t, c);
            }
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            flush();
            n++;
            pos[n][0] = x + origin;
            pos[n][1] = y + origin;
            pos[n][2] = z + origin;
            rgb[n][0] = 1;
            rgb[n][1] = 1;
            rgb[n][2] = 1;
            return this;
        }

        /** Vertices only complete a quad on the next one, so the last needs a push. */
        void flush() {
            if (n == 3) {
                if (texture != null) {
                    appendRow(out, texture, true, -1, pos, uv, rgb);
                } else if (!writeQuad(out, null, sprites, true, -1, pos, uv, rgb)) {
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
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
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
            return null;
        }

        @Override
        public float getShade(Direction side, boolean shade) {
            return 1; // the ours client applies its own directional light; only AO + tint are baked
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int darkening) {
            return 15;
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
