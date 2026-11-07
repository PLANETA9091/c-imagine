package dev.cim.f2v;

import com.google.gson.JsonArray;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * F2V 2.0 visual pack builder (26.3 surface).
 *
 * <p>Turns modded item content (models + textures riding the mod jars in
 * {@code mods/}) into a vanilla 26.3 resource pack so a STOCK client renders
 * modded items identically to a modded client — the F2F v1 idea
 * (ItemsAdder/Oraxen-style {@code custom_model_data} hooks) expressed through
 * the new registry-based {@code items/} definition system instead of legacy
 * {@code models/item} overrides.
 *
 * <p>Every emitted shape is measured from the 26.3 client jar
 * (docs/runtime-analysis/f2v2-pack-synthesis-26-3.md):
 * <ul>
 *   <li>{@code pack.mcmeta} — {@code min_format}/{@code max_format} pairs,
 *       resource 97.1 (client {@code version.json}); legacy
 *       {@code pack_format} kept for parser tolerance (DFU ignores unknown
 *       keys);</li>
 *   <li>{@code assets/<host>/items/<host>.json} —
 *       {@code range_dispatch} over the {@code minecraft:custom_model_data}
 *       property (bytecode-proven optional {@code index} field, default 0),
 *       vanilla def preserved as the {@code fallback} branch;</li>
 *   <li>{@code custom_model_data} is referenced by ZERO vanilla defs — the
 *       hook is free;</li>
 *   <li>item textures under {@code textures/item/} or {@code textures/block/}
 *       of ANY namespace are auto-atlased by the vanilla
 *       {@code atlases/items.json} directory source — no atlas merge needed.</li>
 * </ul>
 *
 * <p>v1 constraints (deliberate, documented):
 * <ul>
 *   <li>manifest-driven mappings only ({@code visual-items.tsv}) — no
 *       auto-assign sweep over all mod jars (JEI-scale asset trees would
 *       bloat the pack);</li>
 *   <li>hosts must be simple vanilla items ({@code minecraft:model} defs) —
 *       the fallback branch is a plain model ref back to the host's own
 *       model, which is only correct for flat item defs;</li>
 *   <li>modded textures outside {@code textures/item|block/} are skipped
 *       with a warning (they would need a merged atlas override).</li>
 * </ul>
 *
 * <p>The zip is built deterministically (sorted entries, fixed timestamps,
 * fixed deflate level) so the sha1 is stable across builds — the CI
 * pre-boot build and the plugin's boot-time rebuild must agree, and the
 * server properties carry that sha1.
 */
public final class F2vPack {

    /** One manifest row: modded item id -> host vanilla item + CMD float. */
    public record VisualItem(String moddedId, String hostItem, float cmd,
                             String selectValue) {
        /** Float-dispatch row (range_dispatch on custom_model_data.floats). */
        public boolean isFloatDispatch() {
            return selectValue == null || selectValue.isBlank();
        }
    }

    /** Build outcome: resolved rows, sha1 hex, emitted entry count, warnings. */
    public record Result(List<VisualItem> items, List<VisualBlock> blocks,
                         String sha1, int entryCount, List<String> warnings,
                         List<GalleryPack.Resolved> gallery) {
    }

    /**
     * One FLUID TEXTURE row (round 2): the still texture a mod ships for a
     * fluid, synthesized into a flat item model on the 400000+i CMD space.
     * The plugin's fluid replay resolves the fluid walk's texture-model
     * rows through this manifest (fluidId -> cmd).
     */
    public record FluidTexture(String fluidId, float cmd, String modelRef) {
    }

    /** One FLUID CUBE row (14-cd): the ours leg's visible surface for the
     * fluid walk's channel=block SOURCE rows — a 14/16-tall box model on the
     * 410000+i CMD space carrying the fluid's still surface (a real texture
     * frame, or the calibrated flat color for the modded chemicals whose
     * appearance is code-tinted). The truth leg renders the real liquid; the
     * cube replays the same block space (the glass case frames both). */
    public record FluidCube(String fluidId, float cmd, String modelRef) {
    }

    /** fluid id derivation from the texture file name: strip .png, then a
     *  trailing {@code _still} or {@code _flow} — the SAME convention the
     *  scene-mod walk probes (both sides agree by construction). */
    static String fluidIdFromTexture(String ns, String fileName) {
        String name = fileName.endsWith(".png")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        if (name.endsWith("_still")) {
            name = name.substring(0, name.length() - "_still".length());
        } else if (name.endsWith("_flow")) {
            name = name.substring(0, name.length() - "_flow".length());
        }
        return ns + ":" + name;
    }

    /**
     * Scan the jars for still fluid textures and emit the synthesized
     * sprite models + the copied textures into the pack. Deterministic
     * (sorted keys, first jar wins per path). The minecraft namespace is
     * SKIPPED by construction: every vanilla fluid has a legacy block, so
     * surface (a) covers it and the texture channel never triggers — the
     * scan only feeds modded fluids.
     */
    static List<FluidTexture> scanFluidTextures(List<Path> jars,
                                                TreeMap<String, byte[]> entries,
                                                List<String> warnings) throws IOException {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (e.isDirectory() || !n.startsWith("assets/") || !n.endsWith(".png")) {
                        continue;
                    }
                    String rest = n.substring("assets/".length());
                    int slash = rest.indexOf('/');
                    if (slash <= 0) {
                        continue;
                    }
                    String ns = rest.substring(0, slash);
                    if (ns.equals("minecraft")) {
                        continue;
                    }
                    String file = rest.substring(slash + 1);
                    // 14-cd the scan conventions widened: create ships
                    // textures/fluid/<p>_still.png, biomesoplenty ships
                    // textures/block/<p>_still.png, mekanism ships
                    // textures/liquid/<p>.png (the tinted chemical canvas —
                    // the color table overrides those ids below). The
                    // _still suffix keys the LIQUID surface textures; other
                    // block textures stay out.
                    boolean fluidPath = file.startsWith("textures/fluid/")
                            || (file.startsWith("textures/block/")
                                && file.endsWith("_still.png"))
                            || file.startsWith("textures/liquid/");
                    if (!fluidPath) {
                        continue;
                    }
                    paths.add(ns + ":" + file);
                    if (!entries.containsKey(n)) {
                        // 14-cf: the strip NEVER enters the atlas raw. The
                        // still textures are animated strips (16xN: the
                        // mekanism liquid canvas 16x512, the create fluids
                        // 16x320) — hosting the raw strip grew the atlas
                        // layout and the mip generation bled the neighbours'
                        // pixels into the high-frequency faces (measured on
                        // run #238: the ae2 chiseled quartz pages, solid
                        // green on #231, corrupted into per-pixel RGB noise
                        // across 162 cells the moment the 16x512 canvases
                        // joined the atlas). The hosted sprite = the static
                        // 16x16 frame 0 under the SAME sprite id — the
                        // deterministic surface, the atlas keeps its size.
                        entries.put(n, stillFramePng(zf.getInputStream(e).readAllBytes()));
                    }
                }
            }
        }
        List<FluidTexture> out = new ArrayList<>();
        int i = 0;
        for (String key : paths) {
            String ns = key.substring(0, key.indexOf(':'));
            String file = key.substring(key.indexOf(':') + 1);   // textures/<dir>/<name>.png
            String rest2 = file.substring("textures/".length()); // <dir>/<name>.png
            int slash2 = rest2.indexOf('/');
            String dir = rest2.substring(0, slash2);             // fluid | block | liquid
            String base = rest2.substring(slash2 + 1);           // <name>.png
            String sprite = base.substring(0, base.length() - 4); // <name> (no .png)
            // Model identifiers are relative to models/: native
            // FileToIdConverter prepends that directory during lookup.
            String modelPath = "assets/c-imagine/models/fluid/f" + i + ".json";
            if (entries.containsKey(modelPath)) {
                continue;
            }
            // the texture ref keeps the file's OWN directory (fluid/, block/,
            // liquid/ — the hosted path mirrors the jar path verbatim)
            String texRef = ns + ":" + dir + "/" + sprite;
            String json = "{\"parent\": \"minecraft:item/generated\", "
                    + "\"textures\": {\"layer0\": \"" + texRef + "\"}}";
            entries.put(modelPath, json.getBytes(StandardCharsets.UTF_8));
            out.add(new FluidTexture(fluidIdFromTexture(ns, base),
                    400000f + i, "c-imagine:fluid/f" + i));
            i++;
        }
        return out;
    }

    /** The static 16x16 frame-0 crop of a still-texture strip, re-encoded:
     *  the atlas-safe hosting of the fluid sprites (14-cf — the raw strips
     *  grew the atlas and bled the neighbours, see the scan comment). */
    private static byte[] stillFramePng(byte[] raw) throws IOException {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(raw));
            if (img == null) {
                return raw;
            }
            int w = Math.min(16, img.getWidth());
            int h = Math.min(16, img.getHeight());
            java.awt.image.BufferedImage frame = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = frame.createGraphics();
            g.drawImage(img, 0, 0, w, h, 0, 0, w, h, null);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(frame, "png", bos);
            return bos.toByteArray();
        } catch (Exception bad) {
            return raw; // an unreadable png keeps its bytes (the loader will name it)
        }
    }

    /** Read the fluid-color manifest (14-cd): fluidId -> {r,g,b} 0..255.
     *  Absent file = an empty table (the chemical cube channel stays off —
     *  the pack stays byte-identical to today's build). */
    public static java.util.Map<String, int[]> readFluidColorTable(Path tsv)
            throws IOException {
        java.util.Map<String, int[]> map = new java.util.HashMap<>();
        if (!Files.isRegularFile(tsv)) {
            return map;
        }
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length >= 4) {
                try {
                    map.putIfAbsent(parts[0].trim(), new int[]{
                            Integer.parseInt(parts[1].trim()),
                            Integer.parseInt(parts[2].trim()),
                            Integer.parseInt(parts[3].trim())});
                } catch (NumberFormatException nfe) {
                    // a malformed row is skipped, never fatal
                }
            }
        }
        return map;
    }

    /**
     * The 14-cd CUBE channel: for every walked fluid id (the modded
     * namespace fluids the manifests name) synthesize the 14/16 box model
     * the channel=block SOURCE rows replay through. Two texture sources:
     * <ul>
     *   <li>the color-table ids (the modded chemicals whose appearance is
     *       code-tinted — mekanism): a uniform 16x16 PNG of the calibrated
     *       color (the L1-optimal flat surface);</li>
     *   <li>everything else with a scanned still texture (create,
     *       biomesoplenty): the texture's STATIC frame 0 (16x16) — the
     *       animation phase is exactly what the legs cannot share, so the
     *       cube ships deterministic pixels.</li>
     * </ul>
     * Writes {@code visual-fluid-cubes.tsv} (fluidId -> cmd, the 410000+i
     * space) beside the flat manifest. Deterministic (sorted ids).
     */
    static List<FluidCube> synthesizeFluidCubes(java.util.Set<String> fluidIds,
                                                java.util.Map<String, int[]> colorTable,
                                                TreeMap<String, byte[]> entries,
                                                List<String> warnings) throws IOException {
        List<FluidCube> out = new ArrayList<>();
        int i = 0;
        for (String fluidId : new java.util.TreeSet<>(fluidIds)) {
            String texPath;
            int[] color = colorTable.get(fluidId);
            if (color != null) {
                // 14-ch THE SHADED SURFACE SET: the truth's liquid renders
                // through the chunk pipeline's DIRECTIONAL face shading (the
                // top 1.0, the north/south 0.8, the east/west 0.6, the
                // bottom 0.5) while the item_display pipeline renders the
                // model FLAT — the #250 measured the unshaded cube at ae
                // 246-421K against the 240K gate with IDENTICAL ae on the
                // run before the gate flip (the structure, not the pixels).
                // The shading bakes into four per-face textures; the model
                // picks them per face and the fit absorbs the rest.
                String id = fluidId.replace(':', '_');
                texPath = "assets/c-imagine/textures/fluidcube/" + id + ".png";
                if (!entries.containsKey(texPath)) {
                    entries.put(texPath, uniformPng(color[0], color[1], color[2]));
                }
                String nsPath = "assets/c-imagine/textures/fluidcube/" + id + "_ns.png";
                if (!entries.containsKey(nsPath)) {
                    int[] ns = shade(color, 0.8);
                    entries.put(nsPath, uniformPng(ns[0], ns[1], ns[2]));
                }
                String ewPath = "assets/c-imagine/textures/fluidcube/" + id + "_ew.png";
                if (!entries.containsKey(ewPath)) {
                    int[] ew = shade(color, 0.6);
                    entries.put(ewPath, uniformPng(ew[0], ew[1], ew[2]));
                }
                String bPath = "assets/c-imagine/textures/fluidcube/" + id + "_b.png";
                if (!entries.containsKey(bPath)) {
                    int[] bb = shade(color, 0.5);
                    entries.put(bPath, uniformPng(bb[0], bb[1], bb[2]));
                }
            } else {
                // the real still texture's static frame 0, re-hosted under
                // the cube namespace (the frame extraction keeps the cube
                // deterministic — the strip's later frames never enter)
                byte[] frame = stillFrameZero(entries, fluidId);
                if (frame == null) {
                    continue;
                }
                texPath = "assets/c-imagine/textures/fluidcube/"
                        + fluidId.replace(':', '_') + ".png";
                entries.put(texPath, frame);
            }
            String texRef = "c-imagine:fluidcube/"
                    + fluidId.replace(':', '_');
            // the 14/16 liquid surface box: the vanilla source block with a
            // non-fluid above renders its top at 14/16 — the sides show the
            // texture's lower 14 rows (uv v 2..16). Model and sprite ids
            // omit the models/ and textures/ archive roots, respectively.
            // 14-ch: the color-route faces carry the per-face shaded
            // textures (the item_display pipeline applies no directional
            // shade of its own — the #250 structure gap).
            String textures = color != null
                    ? "\"all\": \"" + texRef + "\", \"ns\": \"" + texRef + "_ns\", "
                      + "\"ew\": \"" + texRef + "_ew\", \"b\": \"" + texRef + "_b\""
                    : "\"all\": \"" + texRef + "\"";
            String sideTex = color != null ? "#ns" : "#all";
            String ewTex = color != null ? "#ew" : "#all";
            String downTex = color != null ? "#b" : "#all";
            String faces = "\"north\": {\"texture\": \"" + sideTex + "\", \"uv\": [0, 2, 16, 16]},"
                    + "\"south\": {\"texture\": \"" + sideTex + "\", \"uv\": [0, 2, 16, 16]},"
                    + "\"west\": {\"texture\": \"" + ewTex + "\", \"uv\": [0, 2, 16, 16]},"
                    + "\"east\": {\"texture\": \"" + ewTex + "\", \"uv\": [0, 2, 16, 16]},"
                    + "\"up\": {\"texture\": \"#all\", \"uv\": [0, 0, 16, 16]},"
                    + "\"down\": {\"texture\": \"" + downTex + "\", \"uv\": [0, 0, 16, 16]}";
            String modelPath = "assets/c-imagine/models/fluidcube/f" + i + ".json";
            if (entries.containsKey(modelPath)) {
                continue;
            }
            String json = "{\"parent\": \"minecraft:block/block\", "
                    + "\"textures\": {" + textures + "}, "
                    + "\"elements\": [{\"from\": [0, 0, 0], \"to\": [16, 14, 16], "
                    + "\"faces\": {" + faces + "}}]}";
            entries.put(modelPath, json.getBytes(StandardCharsets.UTF_8));
            out.add(new FluidCube(fluidId, 410000f + i,
                    "c-imagine:fluidcube/f" + i));
            i++;
        }
        return out;
    }

    /** The static frame texture for a fluid id: the scan already hosts the
     *  16x16 frame-0 crops under the jar paths (14-cf), so this just looks
     *  the id up among the hosted entries under the three scan conventions.
     *  Null when the id has no hosted still texture. */
    private static byte[] stillFrameZero(TreeMap<String, byte[]> entries,
                                         String fluidId) {
        int colon = fluidId.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String ns = fluidId.substring(0, colon);
        String name = fluidId.substring(colon + 1);
        for (String dir : new String[]{"fluid/", "block/", "liquid/"}) {
            for (String suffix : new String[]{"_still", ""}) {
                byte[] raw = entries.get("assets/" + ns + "/textures/" + dir
                        + name + suffix + ".png");
                if (raw != null) {
                    return raw;
                }
            }
        }
        return null;
    }

    /** A uniform opaque 16x16 PNG of one RGB color (the calibrated chemical
     *  surface). */
    private static byte[] uniformPng(int r, int g, int b) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** One color's channel-wise shade multiply, rounded and clamped — the
     *  14-ch per-face shading bake (the liquid renderer's directional
     *  shades: top 1.0, north/south 0.8, east/west 0.6, bottom 0.5). */
    private static int[] shade(int[] color, double k) {
        return new int[]{
                (int) Math.round(Math.min(255, color[0] * k)),
                (int) Math.round(Math.min(255, color[1] * k)),
                (int) Math.round(Math.min(255, color[2] * k))};
    }

    /** Read the fluid-texture manifest the build wrote (fluidId -> cmd). */
    public static java.util.Map<String, Float> readFluidManifest(Path tsv) throws IOException {
        java.util.Map<String, Float> map = new java.util.HashMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length >= 2) {
                try {
                    map.putIfAbsent(parts[0], Float.parseFloat(parts[1]));
                } catch (NumberFormatException nfe) {
                    // a malformed row is skipped, never fatal
                }
            }
        }
        return map;
    }

    /**
     * One equipment-asset manifest row (M6 pull-forward — the armor channel
     * of the parity matrix): a 1.21.1-style mod armor texture pair mapped to
     * the 26.3 equipment surface.
     *
     * <p>Legacy locations (measured in the 1.21.1 client/NeoForge era):
     * {@code assets/<ns>/textures/models/armor/<name>_layer_1.png} (helmet /
     * chestplate / boots) and {@code ..._<name>_layer_2.png} (leggings).
     * 26.3 surface (javap + vanilla asset schema):
     * {@code assets/<ns>/equipment/<name>.json} with
     * {@code layers.humanoid[].texture = <ns>:<name>} resolving to
     * {@code textures/entity/equipment/humanoid/<ns>/<name>.png}, and
     * {@code layers.humanoid_leggings} to {@code .../humanoid_leggings/...}.
     * The item side points at the asset via the {@code equippable} component
     * ({@code asset_id} / paper-api {@code EquippableComponent#setModel}).
     */
    public record EquipmentAsset(String assetId, String legacyLayer1,
                                 String legacyLayer2) {
    }

    /**
     * One block-standin manifest row (the parity matrix "blocks" channel):
     * a modded block id -> the note_block slot its IN-WORLD form renders
     * through on a stock client. Measured surface:
     * docs/runtime-analysis/block-channel-26-3.md (structure_void/barrier
     * are RenderShape.INVISIBLE in CODE — note_block is the measured
     * stand-in: MODEL-rendered full cube, 27-instrument x 25-note slot
     * space, partial variant keys are wildcards, graceful vanilla
     * degradation). The slot rides vanilla blockstate ids — no CMD, no
     * component codec, nothing per-instance.
     */
    public record VisualBlock(String moddedBlockId, String instrument, int note) {
    }

    /**
     * One palette-manifest row (M6 palette channel — the 26.3-native
     * replacement for F2F v1's ModTintScanner/ColorAtlas family): recolor =
     * two small PNGs + one atlas source, ZERO code. Measured 26.3 shape
     * (f2v2-pack-synthesis-26-3.md §12): {@code paletted_permutations} is an
     * ATLAS SOURCE now (the standalone file format is gone) — pack
     * {@code atlases/items.json} files APPEND their sources to the vanilla
     * stack ({@code SpriteSourceList.load} = getResourceStack + addAll), so
     * the pack carries ONLY its own sources and vanilla trims keep working.
     *
     * <p>Semantics: every pixel of the base sprite matching a palette-key
     * color (exact ARGB) is remapped through the permutation PNG's colors;
     * the output sprite id is {@code <baseSprite><separator><suffix>} and a
     * generated variant item model references exactly that sprite.
     *
     * @param moddedItem the base manifest item id ({@code ns:path}) whose
     *                   variant rows ({@code ns:path@<suffix>}) ride the same
     *                   host's range_dispatch
     * @param baseSprite the TEXTURE sprite id the permutations recolor
     *                   ({@code ns:item/test_gem})
     * @param paletteKey full jar resource path of the palette-key PNG
     *                   ({@code ns:textures/palettes/gem_key.png})
     * @param separator  permutation sprite-name separator (vanilla default "_")
     * @param variants   suffix -&gt; permutation PNG jar path (sorted —
     *                   deterministic emit order)
     */
    public record PaletteRow(String moddedItem, String baseSprite, String paletteKey,
                             String separator, Map<String, String> variants) {
    }

    /** Resource pack format for 26.3 (client version.json — measured). */
    public static final int PACK_FORMAT_MAJOR = 97;
    public static final int PACK_FORMAT_MINOR = 1;
    /** First auto-assigned custom_model_data float (manifest may override). */
    public static final float CMD_BASE = 1000.0f;

    /**
     * THE GALLERY-ITEMS CMD SPACE (scripts/f2v/gallery-items-manifest.py
     * CMD_BASE): that generator assigns 300000+i over each namespace's OWN
     * sorted rows, and the CI pack step CONCATENATES the per-ns files into
     * one visual-items.tsv — every namespace's i-th item then collides on
     * the same cmd (measured #114 forensics, reproduced by a local
     * F2vPack build over the real jars: create+bop+dynamictrees produced 77
     * duplicated thresholds in one range_dispatch, and the client renders
     * ONE arbitrary winner per cmd — create:analog_lever measured rendering
     * dynamictrees:item/azalea_seed, create:acacia_window_pane rendering
     * dynamictrees:item/acacia_seed — the item wall was a per-index
     * lottery). The space is OWNED by the generated rows: {@link #assignCmds}
     * re-assigns it sequentially over the concatenated (moddedId-sorted)
     * rows; pinned rows below the base keep their explicit cmds, and the
     * fluid channel's space (400000+i) sits above the range.
     */
    public static final float GALLERY_ITEM_CMD_BASE = 300_000f;
    /** Exclusive end of the re-assigned gallery space (the fluid channel's base). */
    public static final float GALLERY_ITEM_CMD_END = 400_000f;
    /** DOS/zip epoch (1980-01-01) — the earliest representable zip time. */
    private static final long FIXED_EPOCH = 315532800000L;

    /**
     * The modded post-effect chain the pack contributes (F2V 2.0 "render
     * through post effects"): a sepia tint composed PURELY from vanilla
     * programs (color_convolve + blit) — zero shader files shipped, exactly
     * the measured vanilla composition vocabulary (post-effect-schema-26-3.md
     * §2 creeper/invert shapes). The client resolves
     * {@code assets/<ns>/post_effect/<id>.json} from the pack when the server
     * requests the id over ClientboundPostEffectsPacket.
     */
    public static final String MODDED_CHAIN_ID = "c-imagine:cimsepia";
    static final String MODDED_CHAIN_JSON = "{\n"
            + "  \"targets\": {\"swap\": {}},\n"
            + "  \"passes\": [\n"
            + "    {\n"
            + "      \"vertex_shader\": \"minecraft:core/screenquad\",\n"
            + "      \"fragment_shader\": \"minecraft:post/color_convolve\",\n"
            + "      \"inputs\": [{\"sampler_name\": \"In\", \"target\": \"minecraft:main\"}],\n"
            + "      \"output\": \"swap\",\n"
            + "      \"uniforms\": {\n"
            + "        \"ColorConfig\": [\n"
            + "          {\"name\": \"RedMatrix\",   \"type\": \"vec3\", \"value\": [0.393, 0.769, 0.189]},\n"
            + "          {\"name\": \"GreenMatrix\", \"type\": \"vec3\", \"value\": [0.349, 0.686, 0.168]},\n"
            + "          {\"name\": \"BlueMatrix\",  \"type\": \"vec3\", \"value\": [0.272, 0.534, 0.131]}\n"
            + "        ]\n"
            + "      }\n"
            + "    },\n"
            + "    {\n"
            + "      \"vertex_shader\": \"minecraft:core/screenquad\",\n"
            + "      \"fragment_shader\": \"minecraft:post/blit\",\n"
            + "      \"inputs\": [{\"sampler_name\": \"In\", \"target\": \"swap\"}],\n"
            + "      \"output\": \"minecraft:main\",\n"
            + "      \"uniforms\": {\n"
            + "        \"BlitConfig\": [\n"
            + "          {\"name\": \"ColorModulate\", \"type\": \"vec4\", \"value\": [1.0, 1.0, 1.0, 1.0]}\n"
            + "        ]\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}\n";

    private F2vPack() {
    }

    // ---- manifest ------------------------------------------------------------

    /**
     * Read {@code visual-items.tsv}: {@code moddedId<TAB>host<TAB>cmd[
     * <TAB>select:value]} — cmd blank/-1 for auto-assignment (1000 + index,
     * sorted-id order); an optional 4th column {@code select:<string>}
     * switches the row to the 26.3 select-dispatch surface (javap-proven
     * {@code select.CustomModelDataProperty}: index optional, when = STRING)
     * riding {@code custom_model_data.strings} instead of floats. {@code #}
     * comments and blank lines are ignored.
     */
    public static List<VisualItem> readManifest(Path tsv) throws IOException {
        List<VisualItem> rows = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            float cmd = -1f;
            if (parts.length >= 3 && !parts[2].isBlank()) {
                try {
                    cmd = Float.parseFloat(parts[2].trim());
                } catch (NumberFormatException ignored) {
                    cmd = -1f;
                }
            }
            String select = null;
            if (parts.length >= 4 && !parts[3].isBlank()) {
                String v = parts[3].trim();
                select = v.startsWith("select:") ? v.substring("select:".length()) : v;
            }
            // HOST NORMALIZATION (measured #49 forensics): the gallery-items
            // manifest writes the host BARE ("paper") — a bare host skipped
            // the WHOLE group at the minecraft:-guard ("host paper is not
            // vanilla"), and every item cell rendered the fallback paper
            // sprite instead of its model. Bare = minecraft namespace.
            String host = parts[1].trim();
            if (!host.isEmpty() && !host.contains(":")) {
                host = "minecraft:" + host;
            }
            rows.add(new VisualItem(parts[0].trim(), host, cmd, select));
        }
        rows.sort(Comparator.comparing(VisualItem::moddedId));
        return rows;
    }

    /** Write the RESOLVED manifest (final cmds) for the plugin's give leg. */
    public static void writeResolvedManifest(Path tsv, List<VisualItem> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder("# resolved by F2vPack — final "
                + "custom_model_data floats / select strings (do not edit; regenerate instead)\n");
        for (VisualItem r : rows) {
            sb.append(r.moddedId()).append('\t').append(r.hostItem())
              .append('\t').append(r.cmd());
            if (!r.isFloatDispatch()) {
                sb.append("\tselect:").append(r.selectValue());
            }
            sb.append('\n');
        }
        Files.createDirectories(tsv.getParent());
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Assign commands: explicit values pass through EXCEPT the gallery-items
     * space [{@link #GALLERY_ITEM_CMD_BASE}, {@link #GALLERY_ITEM_CMD_END})
     * which is re-assigned sequentially over the concatenated rows (see the
     * base constant's javadoc — the per-ns manifests each start at 300000
     * and collide when concatenated, #114); the rest get 1000+i. Rows arrive
     * moddedId-sorted ({@link #readManifest}), so the re-assignment is
     * deterministic and the icon legs' pinned cmds (&lt; 300000) never shift.
     */
    static List<VisualItem> assignCmds(List<VisualItem> rows) {
        List<VisualItem> out = new ArrayList<>();
        int next = 0;
        int galleryNext = 0;
        for (VisualItem r : rows) {
            float cmd;
            if (r.cmd() >= GALLERY_ITEM_CMD_BASE && r.cmd() < GALLERY_ITEM_CMD_END) {
                cmd = GALLERY_ITEM_CMD_BASE + galleryNext;
                galleryNext++;
            } else {
                cmd = r.cmd() >= 0f ? r.cmd() : CMD_BASE + next;
            }
            next++;
            out.add(new VisualItem(r.moddedId(), r.hostItem(), cmd, r.selectValue()));
        }
        return out;
    }

    // ---- build -----------------------------------------------------------------

    /**
     * Build the pack zip from the mod jars in {@code modsDir} for the given
     * manifest rows. Writes {@code pack.zip} + {@code pack.sha1} +
     * {@code build-report.txt} + {@code visual-items.resolved.tsv} into
     * {@code outDir} and returns the result.
     */
    public static Result build(Path modsDir, Path outDir, List<VisualItem> manifestRows)
            throws IOException {
        return build(modsDir, outDir, manifestRows,
                readEquipmentManifest(outDir.resolve("visual-equipment.tsv")),
                readBlockManifest(outDir.resolve("visual-blocks.tsv")));
    }

    /**
     * Extra ASSETS jars for the pack builder: the VANILLA client jar — the
     * gallery pack builder resolves VANILLA parents ({@code block/cube_all},
     * {@code block/cube_column}, ...) from the SAME client that will render
     * the pack, so a self-contained composite can be synthesized for the
     * most common mod model shape (own textures + a vanilla element parent).
     * Measured (run 41126658261 + selftest 12-af): without it every
     * vanilla-parent model has no in-jar elements and its row DROPS.
     *
     * <p>Read from {@code -Dcim.f2v.assetsJars="a.jar:b.jar"} when no
     * explicit list is threaded (the server-side rebuild path; CI pins the
     * property on the Paper boot line). Mod jars keep precedence: the
     * client jar is appended LAST and only fills gaps (first-match-wins).
     */
    static List<Path> extraAssets() {
        List<Path> out = new ArrayList<>();
        String prop = System.getProperty("cim.f2v.assetsJars", "");
        for (String s : prop.split(":")) {
            if (!s.isBlank()) {
                out.add(Path.of(s));
            }
        }
        return out;
    }

    /** Rebuild all source inputs in scratch without modifying the served pack. */
    public static Result rebuildForVerification(Path mods, Path base) throws IOException {
        return rebuildForVerification(mods, base, extraAssets());
    }

    private static Result rebuildForVerification(Path mods, Path base, List<Path> assets) throws IOException {
        Path scratch = Files.createTempDirectory(base, "verify-");
        try {
            for (String name : List.of("visual-gallery.tsv", "visual-tints.tsv",
                    "visual-emissive.tsv", "quads", "overlay")) {
                Path source = base.resolve(name);
                if (!Files.exists(source)) {
                    continue;
                }
                try (var paths = Files.walk(source)) {
                    for (Path path : (Iterable<Path>) paths::iterator) {
                        Path target = scratch.resolve(base.relativize(path));
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(target);
                        } else {
                            Files.copy(path, target);
                        }
                    }
                }
            }
            Path items = base.resolve("visual-items.tsv");
            return build(mods, scratch, Files.isRegularFile(items) ? readManifest(items) : List.of(),
                    readEquipmentManifest(base.resolve("visual-equipment.tsv")),
                    readBlockManifest(base.resolve("visual-blocks.tsv")),
                    readPaletteManifest(base.resolve("visual-palettes.tsv")), assets);
        } finally {
            deleteRecursively(scratch);
        }
    }

    /**
     * Read {@code visual-blocks.tsv}: {@code moddedBlockId<TAB>instrument[
     * <TAB>note]} — one note_block stand-in slot per row (measured surface:
     * block-channel-26-3.md). Optional; missing file = no block overrides.
     * Note defaults to 13 (a deliberate non-default pick so a vanilla
     * note=0 note block elsewhere can never collide with the slot).
     */
    public static List<VisualBlock> readBlockManifest(Path tsv) throws IOException {
        List<VisualBlock> rows = new ArrayList<>();
        if (!Files.isRegularFile(tsv)) {
            return rows;
        }
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            int note = 13;
            if (parts.length >= 3 && !parts[2].isBlank()) {
                try {
                    note = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException e) {
                    continue; // malformed note — row dropped
                }
            }
            rows.add(new VisualBlock(parts[0].trim(), parts[1].trim(), note));
        }
        rows.sort(Comparator.comparing(VisualBlock::moddedBlockId)
                .thenComparing(VisualBlock::instrument)
                .thenComparingInt(VisualBlock::note));
        return rows;
    }

    /**
     * Read {@code visual-equipment.tsv}: {@code assetId<TAB>layer1Path[
     * <TAB>layer2Path]} where the paths are relative to {@code assets/<ns>/}
     * inside a mod jar (legacy 1.21.1 armor texture locations). Optional;
     * missing file = no equipment assets.
     */
    public static List<EquipmentAsset> readEquipmentManifest(Path tsv) throws IOException {
        List<EquipmentAsset> rows = new ArrayList<>();
        if (!Files.isRegularFile(tsv)) {
            return rows;
        }
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            rows.add(new EquipmentAsset(parts[0].trim(), parts[1].trim(),
                    parts.length >= 3 && !parts[2].isBlank()
                            ? parts[2].trim() : null));
        }
        rows.sort(Comparator.comparing(EquipmentAsset::assetId));
        return rows;
    }

    /**
     * Read {@code visual-palettes.tsv}: {@code moddedItem<TAB>baseSprite<TAB>
     * paletteKeyPng<TAB>separator<TAB>variants} where {@code variants} is
     * {@code suffix=permPng[;suffix2=permPng2]}. Optional; missing file = no
     * palette recolors. The PNG paths are FULL jar resource paths
     * ({@code ns:textures/palettes/<name>.png}); in the atlas json they are
     * emitted as sprite-space ids ({@code ns:palettes/<name>}).
     */
    public static List<PaletteRow> readPaletteManifest(Path tsv) throws IOException {
        List<PaletteRow> rows = new ArrayList<>();
        if (!Files.isRegularFile(tsv)) {
            return rows;
        }
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 5 || parts[3].isBlank()) {
                continue;
            }
            Map<String, String> variants = new TreeMap<>();
            for (String v : parts[4].split(";", -1)) {
                int eq = v.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                variants.put(v.substring(0, eq).trim(), v.substring(eq + 1).trim());
            }
            if (variants.isEmpty()) {
                continue;
            }
            rows.add(new PaletteRow(parts[0].trim(), parts[1].trim(), parts[2].trim(),
                    parts[3].trim(), variants));
        }
        rows.sort(Comparator.comparing(PaletteRow::moddedItem)
                .thenComparing(PaletteRow::paletteKey));
        return rows;
    }

    /**
     * Read {@code visual-tints.tsv} (the TINT-BAKE channel, produced by
     * scripts/f2v/tint-calibrate.py): {@code texRef<TAB>r<TAB>g<TAB>b[
     * <TAB>evidence]} — one calibrated per-channel MULTIPLY per modded
     * TEXTURE ref (the pack can only apply a tint at texture granularity,
     * so the calibrator keys the table by texture, with the source
     * block/item state carried in the ignored evidence column). Optional;
     * MISSING FILE = empty table = today's behavior byte-for-byte (no bake,
     * no pack delta). Malformed rows are skipped. {@code #} comments blank.
     */
    public static Map<String, double[]> readTintManifest(Path tsv) throws IOException {
        Map<String, double[]> map = new TreeMap<>();
        if (!Files.isRegularFile(tsv)) {
            return map;
        }
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 4 || parts[0].isBlank()) {
                continue;
            }
            try {
                double[] tint = new double[]{
                        Double.parseDouble(parts[1].trim()),
                        Double.parseDouble(parts[2].trim()),
                        Double.parseDouble(parts[3].trim())};
                boolean sane = true;
                for (double v : tint) {
                    if (!(v >= 0.0) || v > 4.0) {
                        sane = false;
                        break;
                    }
                }
                if (sane) {
                    map.putIfAbsent(parts[0].trim(), tint);
                }
            } catch (NumberFormatException ignored) {
                // a malformed row is skipped, never fatal
            }
        }
        return map;
    }

    /**
     * Read {@code visual-emissive.tsv} (the EMISSIVE-BAKE channel, the gallery
     * LIGHTMAP class): {@code stateId<TAB>level[<TAB>evidence]} — one
     * {@code light_emission} level 0-15 per measured-RED blockstate string
     * (the 1.21.2+ block-model ELEMENT property, "defines the minimum light
     * level that the element can receive" — purely visual). The composite
     * emit bakes the level onto EVERY element of that state's emitted model
     * JSON. Optional; MISSING FILE = empty table = today's behavior
     * byte-for-byte (the mirror contract of {@link #readTintManifest}).
     * Malformed rows are skipped. {@code #} comments blank.
     */
    public static Map<String, Integer> readEmissiveManifest(Path tsv) throws IOException {
        Map<String, Integer> map = new TreeMap<>();
        if (!Files.isRegularFile(tsv)) {
            return map;
        }
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 2 || parts[0].isBlank()) {
                continue;
            }
            try {
                int level = Integer.parseInt(parts[1].trim());
                if (level >= 0 && level <= 15) {
                    String key = parts[0].trim();
                    map.putIfAbsent(key, level);
                    // the CANONICAL spelling binds as an alias: the manifest
                    // walk and the table may order the same properties
                    // differently. The raw key keeps priority (putIfAbsent),
                    // first row wins — deterministic file order.
                    map.putIfAbsent(GalleryPack.canonicalState(key), level);
                }
            } catch (NumberFormatException ignored) {
                // a malformed row is skipped, never fatal
            }
        }
        return map;
    }

    /**
     * Emit the 26.3 equipment surface for one legacy armor texture pair:
     * the {@code equipment/<ns>/<name>.json} definition (humanoid +
     * humanoid_leggings layers) plus the textures at the NEW canonical
     * locations. Returns the entries added (also mutates {@code entries}).
     */
    static int emitEquipment(List<Path> jars, EquipmentAsset asset,
                             TreeMap<String, byte[]> entries,
                             List<String> warnings) throws IOException {
        int colon = asset.assetId().indexOf(':');
        if (colon <= 0) {
            warnings.add("malformed equipment asset id: " + asset.assetId());
            return 0;
        }
        String ns = asset.assetId().substring(0, colon);
        String name = asset.assetId().substring(colon + 1);
        int added = 0;
        // MEASURED (EquipmentClientInfo$Layer bootstrap): the client resolves
        // the layer texture id by KEEPING ITS NAMESPACE and rewriting the
        // path to "textures/entity/equipment/<layer-dir>/<path>.png" —
        // "cimtest:test_armor" lands at
        // assets/cimtest/textures/entity/equipment/humanoid/test_armor.png
        // (NO namespace subdirectory inside the texture tree).
        added += copyEquipmentTexture(jars, ns, asset.legacyLayer1(),
                "assets/" + ns + "/textures/entity/equipment/humanoid/"
                        + name + ".png", entries, warnings);
        if (asset.legacyLayer2() != null) {
            added += copyEquipmentTexture(jars, ns, asset.legacyLayer2(),
                    "assets/" + ns + "/textures/entity/equipment/humanoid_leggings/"
                            + name + ".png", entries, warnings);
        }
        if (added == 0) {
            warnings.add("no armor textures found for " + asset.assetId()
                    + " — equipment asset dropped");
            return 0;
        }
        String json = "{\n"
                + "  \"layers\": {\n"
                + "    \"humanoid\": [\n"
                + "      {\"texture\": \"" + ns + ":" + name + "\"}\n"
                + "    ],\n"
                + "    \"humanoid_leggings\": [\n"
                + "      {\"texture\": \"" + ns + ":" + name + "\"}\n"
                + "    ]\n"
                + "  }\n"
                + "}\n";
        entries.put("assets/" + ns + "/equipment/" + name + ".json",
                json.getBytes(StandardCharsets.UTF_8));
        return added + 1;
    }

    /** Copy one legacy armor texture to a 26.3 equipment texture location. */
    private static int copyEquipmentTexture(List<Path> jars, String ns,
                                            String legacyRelPath, String targetPath,
                                            TreeMap<String, byte[]> entries,
                                            List<String> warnings) throws IOException {
        String source = "assets/" + ns + "/" + legacyRelPath;
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry e = zf.getEntry(source);
                if (e == null) {
                    continue;
                }
                entries.putIfAbsent(targetPath,
                        zf.getInputStream(e).readAllBytes());
                return 1;
            }
        }
        warnings.add("equipment texture missing: " + source);
        return 0;
    }

    /** Full build with explicit equipment rows (selftest seam). */
    public static Result build(Path modsDir, Path outDir, List<VisualItem> manifestRows,
                               List<EquipmentAsset> equipmentRows)
            throws IOException {
        return build(modsDir, outDir, manifestRows, equipmentRows,
                readBlockManifest(outDir.resolve("visual-blocks.tsv")));
    }

    /** Full build with explicit equipment + block rows (selftest seam). */
    public static Result build(Path modsDir, Path outDir, List<VisualItem> manifestRows,
                               List<EquipmentAsset> equipmentRows,
                               List<VisualBlock> blockRows)
            throws IOException {
        return build(modsDir, outDir, manifestRows, equipmentRows, blockRows,
                readPaletteManifest(outDir.resolve("visual-palettes.tsv")));
    }

    /** Full build with explicit equipment + block + palette rows (selftest
     *  seam; extra ASSETS jars come from {@code -Dcim.f2v.assetsJars}). */
    public static Result build(Path modsDir, Path outDir, List<VisualItem> manifestRows,
                               List<EquipmentAsset> equipmentRows,
                               List<VisualBlock> blockRows,
                               List<PaletteRow> paletteRows)
            throws IOException {
        return build(modsDir, outDir, manifestRows, equipmentRows, blockRows,
                paletteRows, extraAssets());
    }

    /** CORE build: explicit equipment + block + palette rows + the extra
     *  ASSETS jars (the vanilla client jar in CI). */
    public static Result build(Path modsDir, Path outDir, List<VisualItem> manifestRows,
                               List<EquipmentAsset> equipmentRows,
                               List<VisualBlock> blockRows,
                               List<PaletteRow> paletteRows,
                               List<Path> extraAssets)
            throws IOException {
        Files.createDirectories(outDir);
        List<String> warnings = new ArrayList<>();
        GalleryPack.resetModelScales();
        GalleryPack.resetJarCache();
        ObjMesh.resetBaker();
        GalleryPack.setQuadDir(outDir.resolve("quads"));
        List<VisualItem> baseResolved = assignCmds(manifestRows);

        // PALETTE variant rows (M6 palette channel): one synthetic VisualItem
        // per suffix, riding the BASE row's host in the SAME range_dispatch.
        // Variant cmd = base cmd + 1 + sorted-suffix index (deterministic,
        // collision-checked — the icon legs' pinned cmds never shift). A row
        // with a missing base or ANY cmd collision is dropped WHOLE (a
        // half-emitted palette source would poison the client atlas).
        Map<String, PaletteRow> paletteByVariantId = new HashMap<>();
        List<VisualItem> rows = new ArrayList<>(baseResolved);
        List<Float> usedCmds = new ArrayList<>();
        for (VisualItem r : baseResolved) {
            usedCmds.add(r.cmd());
        }
        List<PaletteRow> validPalettes = new ArrayList<>();
        for (PaletteRow pr : paletteRows) {
            VisualItem base = null;
            for (VisualItem r : baseResolved) {
                if (r.moddedId().equals(pr.moddedItem())) {
                    base = r;
                    break;
                }
            }
            if (base == null) {
                warnings.add("palette row base item missing in items manifest: "
                        + pr.moddedItem());
                continue;
            }
            int vi = 0;
            List<VisualItem> variantRows = new ArrayList<>();
            boolean clean = true;
            for (Map.Entry<String, String> v : pr.variants().entrySet()) {
                float vcmd = base.cmd() + 1f + vi;
                vi++;
                if (usedCmds.contains(vcmd)) {
                    warnings.add("palette variant cmd collision " + vcmd
                            + " — palette row dropped: " + pr.moddedItem());
                    clean = false;
                    break;
                }
                usedCmds.add(vcmd);
                String vid = pr.moddedItem() + "@" + v.getKey();
                paletteByVariantId.put(vid, pr);
                variantRows.add(new VisualItem(vid, base.hostItem(), vcmd, null));
            }
        if (clean) {
            rows.addAll(variantRows);
            validPalettes.add(pr);
        }
    }

    // CMD-COLLISION GUARD (#114 forensics): a cmd claimed by TWO rows renders
    // ONE arbitrary winner on the client — never both, and the loser's item
    // shows the winner's model (the cross-wired item wall). assignCmds
    // re-owns the gallery-items space uniquely; any REMAINING duplicate (a
    // pinned row landing in another row's space, a palette variant riding a
    // gallery base, a hand-written overlap) drops the LATER row WHOLE — the
    // honest backlog (the join names the empty cell) instead of a
    // cross-wired render.
    Map<String, java.util.Set<Float>> seenCmds = new HashMap<>();
    List<VisualItem> deduped = new ArrayList<>(rows.size());
    for (VisualItem r : rows) {
        String key = r.hostItem() + "\t" + (r.isFloatDispatch() ? "float" : "select");
        java.util.Set<Float> used = seenCmds.computeIfAbsent(key, k -> new java.util.HashSet<>());
        if (!used.add(r.cmd())) {
            warnings.add("cmd collision on host " + r.hostItem() + ": " + r.cmd()
                    + " — later row dropped: " + r.moddedId());
            continue;
        }
        deduped.add(r);
    }
    rows = deduped;

        TreeMap<String, byte[]> entries = new TreeMap<>();
        entries.put("pack.mcmeta", packMcmeta().getBytes(StandardCharsets.UTF_8));
        if (!rows.isEmpty()) {
            // the F2V 2.0 modded post-effect chain rides every non-empty pack
        dev.cim.hud.HudPack.contribute(entries);
            entries.put("assets/c-imagine/post_effect/cimsepia.json",
                    MODDED_CHAIN_JSON.getBytes(StandardCharsets.UTF_8));
        }

        // group rows by host, SPLIT by dispatch kind (one items/ def per host;
        // a mixed host would need a composite select-over-range tree — v2
        // keeps the kinds apart and warns)
        Map<String, List<VisualItem>> byHost = new TreeMap<>();
        for (VisualItem r : rows) {
            String key = r.hostItem() + "\t" + (r.isFloatDispatch() ? "float" : "select");
            byHost.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }

        List<Path> jars = listJars(modsDir);
        // vanilla parents resolve from the ASSETS jars (client jar), never
        // shadowing mod jars (mods first, client last, first-match-wins)
        jars.addAll(extraAssets);
        // THE EMISSIVE TABLE loads here (before the item section): the
        // ITEM channel needs it too — the item composite for a glowing
        // modded item (measured: create:refined_radiance, the item-channel
        // join ae=63497) must carry light_emission on its elements the same
        // way the gallery block composite does. Absent file = empty table.
        Map<String, Integer> emissiveTable =
                readEmissiveManifest(outDir.resolve("visual-emissive.tsv"));
        List<String> itemEmissiveBakeLog = new ArrayList<>();
        List<Map.Entry<Float, String>> paperFloatEntries = new ArrayList<>();
        int itemCompositeNo = 0;
        for (Map.Entry<String, List<VisualItem>> host : byHost.entrySet()) {
            String[] keyParts = host.getKey().split("\t");
            String hostItem = keyParts[0];
            boolean floatKind = "float".equals(keyParts[1]);
            if (!hostItem.startsWith("minecraft:")) {
                warnings.add("host " + hostItem + " is not vanilla (minecraft:) — skipped");
                continue;
            }
            List<Map.Entry<Float, String>> entriesForHost = new ArrayList<>();
            List<Map.Entry<String, String>> casesForHost = new ArrayList<>();
            for (VisualItem row : host.getValue()) {
                String quadModel = GalleryPack.synthesizeItemQuads(jars, row.moddedId(), itemCompositeNo,
                        entries, warnings);
                if (quadModel != null) {
                    itemCompositeNo++;
                }
                String found = quadModel != null ? quadModel
                        : resolveAndCopyModelGraph(jars, row.moddedId(), entries, warnings, paletteByVariantId);
                // THE 26.3 ITEM-CHANNEL SHAPE (measured #49/#50, see
                // GalleryPack.synthesizeItemComposite): block-geometry item
                // models ride a standalone composite (inline elements, all
                // sprites pack-side) — a raw block-parented model or a
                // vanilla-sprite reference aborts the whole host def bake at
                // CuboidItemModelWrapper.validateAtlasUsage. Sprite-driven
                // item models (item/generated chains) stay vanilla-shaped.
                if (quadModel == null && found != null && GalleryPack.chainHasElements(jars, found, warnings)) {
                    String comp = GalleryPack.synthesizeItemComposite(
                            jars, found, itemCompositeNo++, entries, warnings);
                    if (comp != null) {
                        // THE ITEM EMISSIVE-BAKE: a tabled modded item's
                        // standalone composite carries light_emission on
                        // every element (the same contract the gallery
                        // block composite honors) — the measured driver:
                        // create:refined_radiance's item-cell red (ae
                        // 63497) whose truth render is fullbright.
                        comp = GalleryPack.applyItemEmissive(
                                row.moddedId(), comp, emissiveTable, itemEmissiveBakeLog);
                        found = comp;
                    }
                } else if (quadModel == null && found != null) {
                    // THE SPRITE EMISSIVE UPGRADE: a tabled item whose model
                    // is an item/generated SPRITE (no elements — the element
                    // light_emission lever does not exist there) upgrades to
                    // a flat-quad composite carrying the sprite texture on
                    // the faces + light_emission. Geometry-compatible with
                    // the sprite (a flat quad), but fullbright — measured:
                    // create:refined_radiance, the truth item renders
                    // fullbright while ours renders the dim sprite.
                    String upgraded = GalleryPack.synthesizeSpriteEmissive(
                            jars, found, row.moddedId(), itemCompositeNo,
                            emissiveTable, entries, warnings);
                    if (upgraded != null) {
                        itemCompositeNo++;
                        found = upgraded;
                    }
                }
                if (found == null) {
                    warnings.add("no item model found for " + row.moddedId()
                            + " in any mod jar — row dropped");
                    continue;
                }
                if (floatKind) {
                    entriesForHost.add(Map.entry(row.cmd(), found));
                    if (hostItem.equals("minecraft:paper")) {
                        paperFloatEntries.add(Map.entry(row.cmd(), found));
                    }
                } else {
                    casesForHost.add(Map.entry(row.selectValue(), found));
                }
            }
            String hostPath = hostItem.substring("minecraft:".length());
            if (floatKind) {
                entriesForHost.sort(Map.Entry.comparingByKey());
                if (!entriesForHost.isEmpty()) {
                    entries.put("assets/minecraft/items/" + hostPath + ".json",
                            itemDef(hostPath, entriesForHost).getBytes(StandardCharsets.UTF_8));
                }
            } else {
                casesForHost.sort(Map.Entry.comparingByKey());
                if (!casesForHost.isEmpty()) {
                    entries.put("assets/minecraft/items/" + hostPath + ".json",
                            selectDef(hostPath, casesForHost).getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        // equipment assets (M6 pull-forward): legacy armor textures -> the
        // 26.3 equipment surface, manifest-driven, zero vanilla overrides
        List<Path> equipJars = jars.isEmpty() ? listJars(modsDir) : jars;
        for (EquipmentAsset asset : equipmentRows) {
            emitEquipment(equipJars, asset, entries, warnings);
        }

        // GALLERY channel (M5.5, the main pixel gate's ours leg): per-state
        // rows ride the SAME host (paper) range_dispatch as float entries.
        // The model refs resolve INSIDE GalleryPack (direct rows at original
        for (String bake : itemEmissiveBakeLog) {
            warnings.add("item emissive-bake applied: " + bake);
        }
        // paths, multipart composites under c-imagine:models/gallery/).
        List<GalleryPack.Resolved> gallery = List.of();
        Path galleryManifest = outDir.resolve("visual-gallery.tsv");
        if (Files.isRegularFile(galleryManifest)) {
            List<GalleryPack.Input> galleryInputs =
                    GalleryPack.readManifest(galleryManifest);
            if (!galleryInputs.isEmpty()) {
                // TINT-BAKE channel (scripts/f2v/tint-calibrate.py): the
                // gallery composite emit rewrites tinted sprites to a baked
                // sibling (_cimtint) with the calibrated multiply applied.
                // Absent visual-tints.tsv = empty table = no rewrite, the
                // pack stays byte-identical to today's build.
                Map<String, double[]> tintTable =
                        readTintManifest(outDir.resolve("visual-tints.tsv"));
                List<String> tintBakeLog = new ArrayList<>();
                // EMISSIVE-BAKE channel: the table loaded ABOVE (the item
                // section needs it too) — this block only carries the logs.
                List<String> emissiveBakeLog = new ArrayList<>();
                gallery = GalleryPack.build(jars, galleryInputs, entries, warnings,
                        tintTable, tintBakeLog, emissiveTable, emissiveBakeLog);
                for (String bake : tintBakeLog) {
                    warnings.add("tint-bake applied: " + bake);
                }
                for (String bake : emissiveBakeLog) {
                    warnings.add("emissive-bake applied: " + bake);
                }
                GalleryPack.writeResolvedManifest(
                        outDir.resolve("visual-gallery.resolved.tsv"), gallery);
                if (!gallery.isEmpty()) {
                    List<Map.Entry<Float, String>> paperEntries =
                            new ArrayList<>(paperFloatEntries);
                    for (GalleryPack.Resolved r : gallery) {
                        paperEntries.add(Map.entry(r.cmd(), r.modelRef()));
                    }
                    paperEntries.sort(Map.Entry.comparingByKey());
                    entries.put("assets/minecraft/items/paper.json",
                            itemDef("paper", paperEntries).getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        // FLUID TEXTURE channel (round 2, 2026-11-04 — the fluid ladder's
        // surface (d)): scan the mod jars for still fluid textures
        // (assets/<ns>/textures/fluid/*.png) and synthesize ONE flat
        // item-model per texture (item/generated + layer0) riding the SAME
        // paper range_dispatch as the item/gallery channels, on the fresh
        // 400000+i CMD space. The scene-mod fluid walk records
        // channel=texture-model for exactly the fluids whose still texture
        // exists here (both sides probe the same resource tree, same
        // suffix-stripping convention) — the ours replay resolves the
        // manifest's fluid id through THIS manifest. The truth side has no
        // native surface for such fluids (its cell stays empty; the join
        // compares honestly, dyn class).
        List<FluidTexture> fluidTextures =
                scanFluidTextures(jars, entries, warnings);
        if (!fluidTextures.isEmpty()) {
            // re-emit paper.json with items + gallery + fluid rows merged
            List<Map.Entry<Float, String>> paperEntries =
                    new ArrayList<>(paperFloatEntries);
            for (GalleryPack.Resolved r : gallery) {
                paperEntries.add(Map.entry(r.cmd(), r.modelRef()));
            }
            for (FluidTexture ft : fluidTextures) {
                paperEntries.add(Map.entry(ft.cmd(), ft.modelRef()));
            }
            paperEntries.sort(Map.Entry.comparingByKey());
            entries.put("assets/minecraft/items/paper.json",
                    itemDef("paper", paperEntries).getBytes(StandardCharsets.UTF_8));
            StringBuilder fl = new StringBuilder();
            for (FluidTexture ft : fluidTextures) {
                fl.append(ft.fluidId()).append('\t').append(ft.cmd()).append('\n');
            }
            Files.writeString(outDir.resolve("visual-gallery-fluids.tsv"),
                    fl.toString(), StandardCharsets.UTF_8);
        }

        // 14-cd FLUID CUBE channel: the ours leg's visible surface for the
        // fluid walk's channel=block SOURCE rows (the modded liquids the
        // truth leg renders as real blocks — the stock 26.3 client cannot
        // draw those states, so the replay rides the item_display rig
        // grammar). The cube ids: the color table (the calibrated chemicals)
        // UNION the scanned still ids (create/biomesoplenty real textures).
        // The 410000+i CMD space joins the SAME paper.json range dispatch —
        // re-emitted when any cube landed.
        Map<String, int[]> fluidColors =
                readFluidColorTable(outDir.resolve("visual-fluid-colors.tsv"));
        java.util.Set<String> cubeIds = new java.util.HashSet<>(fluidColors.keySet());
        for (FluidTexture ft : fluidTextures) {
            cubeIds.add(ft.fluidId());
        }
        List<FluidCube> fluidCubes =
                synthesizeFluidCubes(cubeIds, fluidColors, entries, warnings);
        if (!fluidCubes.isEmpty()) {
            List<Map.Entry<Float, String>> paperEntries =
                    new ArrayList<>(paperFloatEntries);
            for (GalleryPack.Resolved r : gallery) {
                paperEntries.add(Map.entry(r.cmd(), r.modelRef()));
            }
            for (FluidTexture ft : fluidTextures) {
                paperEntries.add(Map.entry(ft.cmd(), ft.modelRef()));
            }
            for (FluidCube fc : fluidCubes) {
                paperEntries.add(Map.entry(fc.cmd(), fc.modelRef()));
            }
            paperEntries.sort(Map.Entry.comparingByKey());
            entries.put("assets/minecraft/items/paper.json",
                    itemDef("paper", paperEntries).getBytes(StandardCharsets.UTF_8));
            StringBuilder cb = new StringBuilder();
            for (FluidCube fc : fluidCubes) {
                cb.append(fc.fluidId()).append('\t').append(fc.cmd()).append('\n');
            }
            Files.writeString(outDir.resolve("visual-fluid-cubes.tsv"),
                    cb.toString(), StandardCharsets.UTF_8);
        }

        // block channel (parity matrix "blocks", block-channel-26-3.md):
        // note_block stand-in slots -> the modded block models, ONE merged
        // blockstates override, manifest-driven slot allocation
        List<Map.Entry<String, String>> blockVariants = new ArrayList<>();
        for (VisualBlock block : blockRows) {
            String ref = resolveAndCopyBlockGraph(jars, block.moddedBlockId(),
                    entries, warnings);
            if (ref != null) {
                blockVariants.add(Map.entry(
                        "instrument=" + block.instrument() + ",note=" + block.note(),
                        ref));
            } else {
                warnings.add("no block model found for " + block.moddedBlockId()
                        + " in any mod jar — block row dropped");
            }
        }
        blockVariants.sort(Map.Entry.comparingByKey());
        if (!blockVariants.isEmpty()) {
            entries.put("assets/minecraft/blockstates/note_block.json",
                    blockStateDef(blockVariants).getBytes(StandardCharsets.UTF_8));
        }

        // palette channel (M6): the paletted_permutations ATLAS SOURCE + the
        // palette PNGs — the 26.3-native recolor surface (zero code, two
        // small PNGs per permutation; ModTintScanner/ColorAtlas family
        // replaced). The pack's atlases/items.json APPENDS its sources to the
        // vanilla stack (measured: SpriteSourceList.load = getResourceStack
        // + addAll) — only OUR sources live here, vanilla trims keep working.
        emitPaletteAtlas(jars, validPalettes, entries, warnings);

        // 14-cd THE FLUID ATLAS SOURCES: the vanilla block-atlas stack scans
        // textures/block/ only — the fluid/liquid/fluidcube directories the
        // fluid channels host would never stitch and the model renders the
        // magenta checker. The directory sources APPEND to whatever the
        // palette channel put in items.json (the measured SpriteSourceList
        // merge — this emit runs AFTER emitPaletteAtlas for exactly that);
        // the same three ride BOTH atlas files, each model's single sprite
        // resolves in whichever atlas bakes it.
        if (!fluidTextures.isEmpty() || !fluidCubes.isEmpty()) {
            String fluidSources = "{\"sources\": ["
                    + "{\"type\": \"minecraft:directory\", \"source\": \"fluid\", \"prefix\": \"fluid/\"}, "
                    + "{\"type\": \"minecraft:directory\", \"source\": \"liquid\", \"prefix\": \"liquid/\"}, "
                    + "{\"type\": \"minecraft:directory\", \"source\": \"fluidcube\", \"prefix\": \"fluidcube/\"}"
                    + "]}";
            appendAtlasSources("assets/minecraft/atlases/blocks.json", fluidSources, entries);
            appendAtlasSources("assets/minecraft/atlases/items.json", fluidSources, entries);
        }

        // 26.3 MODEL UV SANITIZER (run #46 forensics, measured): create-1.21.1
        // models carry element-face uv rects extending PAST the sprite
        // (creative_motor/block.json north face uv [14,4,20,10] — a legal
        // 1.21.1 texture-bleed). 26.3's FaceBakery.computeMaterialTransparency
        // samples that rect INSIDE the sprite image and THROWS
        // ("Cannot compute translucency out of bounds") — the exception aborts
        // the ENTIRE host item def bake (minecraft:paper: 21939 range
        // entries), and every gallery ItemDisplay then renders the missing
        // item model = the #46 universal missingno wall. Vanilla 26.3 models
        // never exceed the sprite; clamp every emitted model's face uv into
        // [0,16]. String-level surgery: models without an offending uv array
        // stay byte-identical (the pack sha1 moves only with real fixes).
        sanitizeFaceUvs(entries, warnings);

        ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, entries);
        // files generated beside the pack (scripts/f2v/entity-remap.py) ride it verbatim
        Path overlay = outDir.resolve("overlay");
        if (Files.isDirectory(overlay)) {
            try (java.util.stream.Stream<Path> files = Files.walk(overlay)) {
                for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    entries.put(overlay.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
                }
            }
        }
        byte[] zip = zip(entries);
        String sha1 = sha1Hex(zip);
        Files.write(outDir.resolve("pack.zip"), zip);
        Files.writeString(outDir.resolve("pack.sha1"), sha1 + "\n", StandardCharsets.UTF_8);
        writeResolvedManifest(outDir.resolve("visual-items.resolved.tsv"), rows);
        StringBuilder report = new StringBuilder("f2v pack build report\n")
                .append("items requested: ").append(rows.size()).append('\n')
                .append("zip entries:     ").append(entries.size()).append('\n')
                .append("sha1:            ").append(sha1).append('\n')
                .append("warnings:        ").append(warnings.size()).append('\n');
        for (String w : warnings) {
            report.append("  warn: ").append(w).append('\n');
        }
        Files.writeString(outDir.resolve("build-report.txt"), report.toString(),
                StandardCharsets.UTF_8);
        return new Result(rows, blockRows, sha1, entries.size(), warnings, gallery);
    }

    /** Any element-face uv array in a model json, 4 numbers. */
    private static final java.util.regex.Pattern FACE_UV = java.util.regex.Pattern.compile(
            "\"uv\"\\s*:\\s*\\[\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,"
            + "\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*,"
            + "\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*\\]");

    /**
     * Clamp every emitted model's element-face uv rects into [0,16] (see the
     * build-site comment for the measured #46 failure). Only offending
     * arrays are rewritten — the byte image of every other model (and every
     * clean coordinate inside a fixed array) is preserved verbatim.
     */
    static void sanitizeFaceUvs(TreeMap<String, byte[]> entries, List<String> warnings) {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith("assets/") || !name.contains("/models/")
                    || !name.endsWith(".json")) {
                continue;
            }
            String json = new String(e.getValue(), StandardCharsets.UTF_8);
            if (!json.contains("\"uv\"")) {
                continue;
            }
            java.util.regex.Matcher m = FACE_UV.matcher(json);
            StringBuilder out = new StringBuilder(json.length());
            boolean changed = false;
            int last = 0;
            while (m.find()) {
                out.append(json, last, m.start());
                StringBuilder repl = new StringBuilder("\"uv\": [");
                for (int i = 1; i <= 4; i++) {
                    double v = Double.parseDouble(m.group(i));
                    double c = Math.max(0.0, Math.min(16.0, v));
                    if (c != v) {
                        changed = true;
                    }
                    if (i > 1) {
                        repl.append(", ");
                    }
                    if (c == Math.rint(c) && Math.abs(c) < 1e9) {
                        repl.append((long) c);
                    } else {
                        repl.append(c);
                    }
                }
                repl.append(']');
                out.append(repl);
                last = m.end();
            }
            out.append(json, last, json.length());
            if (changed) {
                warnings.add("face uv clamped to [0,16] (26.3 bake-safety) in " + name);
                e.setValue(out.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static List<Path> listJars(Path modsDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return jars;
        }
        try (var stream = Files.list(modsDir)) {
            for (Path p : stream.sorted().toList()) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    jars.add(p);
                }
            }
        }
        return jars;
    }

    /**
     * Resolve the item model for {@code moddedId} (ns:path) inside the mod
     * jars and copy its whole modded parent graph + modded textures into the
     * pack. Returns the model reference the items/ def must point at
     * ("ns:item/path" or "ns:path"), or null when nothing was found.
     */
    private static String resolveAndCopyModelGraph(List<Path> jars, String moddedId,
                                                   TreeMap<String, byte[]> entries,
                                                   List<String> warnings,
                                                   Map<String, PaletteRow> paletteByVariantId) throws IOException {
        // palette variant id form: "ns:path@<suffix>" — the base model is
        // resolved/copied normally, then a VARIANT model is emitted next to
        // it whose texture values that match the palette row's baseSprite
        // point at the permutated sprite (<sprite><separator><suffix>)
        PaletteRow paletteRow = null;
        String paletteSuffix = null;
        int at = moddedId.indexOf('@');
        if (at > 0) {
            paletteRow = paletteByVariantId.get(moddedId);
            if (paletteRow == null) {
                warnings.add("palette variant without a manifest row: " + moddedId);
                return null;
            }
            paletteSuffix = moddedId.substring(at + 1);
            moddedId = moddedId.substring(0, at);
        }
        int colon = moddedId.indexOf(':');
        if (colon <= 0) {
            warnings.add("malformed modded id: " + moddedId);
            return null;
        }
        String ns = moddedId.substring(0, colon);
        String path = moddedId.substring(colon + 1);
        // candidates: models/item/<path>.json (item model location), then
        // models/<path>.json (bare model)
        String[] candidates = {"assets/" + ns + "/models/item/" + path + ".json",
                "assets/" + ns + "/models/" + path + ".json"};
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                for (String cand : candidates) {
                    ZipEntry e = zf.getEntry(cand);
                    if (e == null) {
                        continue;
                    }
                    byte[] modelBytes = zf.getInputStream(e).readAllBytes();
                    entries.putIfAbsent(cand, modelBytes);
                    copyParentGraph(zf, cand, modelBytes, entries, warnings, 0);
                    // the model ref matches the found location
                    String baseRef;
                    if (cand.startsWith("assets/" + ns + "/models/item/")) {
                        baseRef = ns + ":item/" + path;
                    } else {
                        baseRef = ns + ":" + path;
                    }
                    if (paletteRow != null) {
                        // variant model next to the base, textures retinted to
                        // the permutated sprite ids
                        String variantFile = cand.substring(0, cand.length() - ".json".length())
                                + paletteRow.separator() + paletteSuffix + ".json";
                        String variantJson = retintModelJson(
                                new String(modelBytes, StandardCharsets.UTF_8),
                                paletteRow.baseSprite(), paletteRow.separator(), paletteSuffix);
                        entries.put(variantFile, variantJson.getBytes(StandardCharsets.UTF_8));
                        return baseRef + paletteRow.separator() + paletteSuffix;
                    }
                    return baseRef;
                }
            }
        }
        return null;
    }

    /**
     * Resolve the BLOCK model for {@code moddedId} (ns:path) inside the mod
     * jars and copy its whole parent graph + textures into the pack.
     * Candidates: models/block/&lt;path&gt;.json (block model location),
     * then models/&lt;path&gt;.json (bare model). Returns the model
     * reference the blockstate variant must point at ("ns:block/path" or
     * "ns:path"), or null when nothing was found.
     */
    private static String resolveAndCopyBlockGraph(List<Path> jars, String moddedId,
                                                   TreeMap<String, byte[]> entries,
                                                   List<String> warnings) throws IOException {
        int colon = moddedId.indexOf(':');
        if (colon <= 0) {
            warnings.add("malformed modded id: " + moddedId);
            return null;
        }
        String ns = moddedId.substring(0, colon);
        String path = moddedId.substring(colon + 1);
        String[] candidates = {"assets/" + ns + "/models/block/" + path + ".json",
                "assets/" + ns + "/models/" + path + ".json"};
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                for (String cand : candidates) {
                    ZipEntry e = zf.getEntry(cand);
                    if (e == null) {
                        continue;
                    }
                    byte[] modelBytes = zf.getInputStream(e).readAllBytes();
                    entries.putIfAbsent(cand, modelBytes);
                    copyParentGraph(zf, cand, modelBytes, entries, warnings, 0);
                    if (cand.startsWith("assets/" + ns + "/models/block/")) {
                        return ns + ":block/" + path;
                    }
                    return ns + ":" + path;
                }
            }
        }
        return null;
    }

    /**
     * The merged {@code assets/minecraft/blockstates/note_block.json}
     * override: one variant per manifest row, keys sorted (deterministic
     * zip). Partial selector keys are wildcards for unspecified properties
     * (VariantSelector measured, block-channel-26-3.md §3) — the note
     * property pins the slot, powered stays unspecified.
     */
    static String blockStateDef(List<Map.Entry<String, String>> variants) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"variants\": {\n");
        for (int i = 0; i < variants.size(); i++) {
            Map.Entry<String, String> e = variants.get(i);
            sb.append("    \"").append(e.getKey())
              .append("\": {\"model\": \"").append(e.getValue())
              .append("\"}");
            sb.append(i + 1 < variants.size() ? ",\n" : "\n");
        }
        sb.append("  }\n}\n");
        return sb.toString();
    }

    /**
     * The M6 palette channel atlas emit: ONE
     * {@code assets/minecraft/atlases/items.json} carrying every palette
     * row's {@code paletted_permutations} source (the pack file APPENDS to
     * the vanilla source stack — measured), plus the palette-key and
     * permutation PNGs copied from the mod jars at their original
     * {@code textures/palettes/} paths.
     */
    private static void emitPaletteAtlas(List<Path> jars, List<PaletteRow> paletteRows,
                                         TreeMap<String, byte[]> entries,
                                         List<String> warnings) throws IOException {
        if (paletteRows.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"sources\": [\n");
        for (int i = 0; i < paletteRows.size(); i++) {
            PaletteRow pr = paletteRows.get(i);
            copyResourcePng(jars, pr.paletteKey(), entries, warnings);
            for (String perm : pr.variants().values()) {
                copyResourcePng(jars, perm, entries, warnings);
            }
            sb.append("    {\"type\": \"minecraft:paletted_permutations\", ")
              .append("\"textures\": [\"").append(pr.baseSprite()).append("\"], ")
              .append("\"palette_key\": \"").append(spritePath(pr.paletteKey())).append("\", ")
              .append("\"permutations\": {");
            boolean first = true;
            for (Map.Entry<String, String> v : pr.variants().entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("\"").append(v.getKey()).append("\": \"")
                  .append(spritePath(v.getValue())).append("\"");
            }
            sb.append("}, \"separator\": \"").append(pr.separator()).append("\"}");
            sb.append(i + 1 < paletteRows.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        entries.put("assets/minecraft/atlases/items.json",
                sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Full jar resource path ({@code ns:textures/palettes/x.png}) -> sprite id. */
    static String spritePath(String fullResourcePath) {
        int c = fullResourcePath.indexOf(':');
        if (c <= 0) {
            return fullResourcePath;
        }
        String rest = fullResourcePath.substring(c + 1);
        if (rest.startsWith("textures/")) {
            rest = rest.substring("textures/".length());
        }
        if (rest.endsWith(".png")) {
            rest = rest.substring(0, rest.length() - ".png".length());
        }
        return fullResourcePath.substring(0, c) + ":" + rest;
    }

    /** Append one atlas-source JSON object's sources into the pack's existing
     *  atlas file (the SpriteSourceList merge is additive — two emitters may
     *  own sources in one file, the palette channel and the 14-cd fluid
     *  channel do). Absent file = create it. */
    private static void appendAtlasSources(String atlasPath, String sourcesJson,
                                           TreeMap<String, byte[]> entries) {
        JsonObject merged = new JsonObject();
        JsonArray sources = new JsonArray();
        byte[] existing = entries.get(atlasPath);
        if (existing != null) {
            try {
                JsonObject prev = JsonParser.parseString(
                        new String(existing, StandardCharsets.UTF_8)).getAsJsonObject();
                if (prev.has("sources") && prev.get("sources").isJsonArray()) {
                    for (JsonElement s : prev.getAsJsonArray("sources")) {
                        sources.add(s);
                    }
                }
            } catch (RuntimeException bad) {
                // an unparseable predecessor: the file is machine-built, the
                // fresh sources array replaces it (never fatal)
            }
        }
        JsonObject add = JsonParser.parseString(sourcesJson).getAsJsonObject();
        for (JsonElement s : add.getAsJsonArray("sources")) {
            sources.add(s);
        }
        merged.add("sources", sources);
        entries.put(atlasPath, merged.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Copy one raw PNG resource (full {@code ns:textures/...} path) from the jars. */
    private static void copyResourcePng(List<Path> jars, String resource,
                                        TreeMap<String, byte[]> entries,
                                        List<String> warnings) throws IOException {
        int c = resource.indexOf(':');
        if (c <= 0) {
            warnings.add("malformed palette resource path: " + resource);
            return;
        }
        String file = "assets/" + resource.substring(0, c) + "/" + resource.substring(c + 1);
        if (entries.containsKey(file)) {
            return;
        }
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry e = zf.getEntry(file);
                if (e != null) {
                    entries.put(file, zf.getInputStream(e).readAllBytes());
                    return;
                }
            }
        }
        warnings.add("palette resource missing in jars: " + resource);
    }

    /**
     * Variant model json: the base model with every texture value EQUAL to
     * {@code baseSprite} re-pointed at the permutated sprite
     * ({@code <baseSprite><separator><suffix>}); quoted-value-exact
     * replacement keeps #refs and vanilla textures untouched.
     */
    static String retintModelJson(String baseJson, String baseSprite,
                                  String separator, String suffix) {
        String from = "\"" + baseSprite + "\"";
        String to = "\"" + baseSprite + separator + suffix + "\"";
        return baseJson.replace(from, to);
    }

    /** Depth-limited parent walk within the SAME jar as the entry model
     *  (package-visible: GalleryPack's direct-model rows reuse it). */
    static void copyParentGraph(ZipFile zf, String modelPath, byte[] modelBytes,
                                        TreeMap<String, byte[]> entries,
                                        List<String> warnings, int depth) throws IOException {
        if (depth > 16) {
            warnings.add("parent chain deeper than 16 at " + modelPath + " — stopped");
            return;
        }
        String json = new String(modelBytes, StandardCharsets.UTF_8);
        String parent = extractStringField(json, "parent");
        if (parent != null && !parent.startsWith("minecraft:")) {
            String parentNs = parent.contains(":")
                    ? parent.substring(0, parent.indexOf(':')) : "minecraft";
            String parentPath = parent.contains(":")
                    ? parent.substring(parent.indexOf(':') + 1) : parent;
            String parentFile = "assets/" + parentNs + "/models/" + parentPath + ".json";
            ZipEntry pe = zf.getEntry(parentFile);
            if (pe != null && !entries.containsKey(parentFile)) {
                byte[] pb = zf.getInputStream(pe).readAllBytes();
                entries.put(parentFile, pb);
                copyParentGraph(zf, parentFile, pb, entries, warnings, depth + 1);
            } else if (pe == null) {
                warnings.add("parent model missing in jar: " + parent
                        + " (referenced by " + modelPath + ")");
            }
        } else if (parent == null) {
            warnings.add("model without parent: " + modelPath
                    + " (must extend a vanilla or in-jar model)");
        }
        // textures owned by a modded namespace get copied into the pack
        Map<String, String> textures = extractTextures(json);
        for (String ref : textures.values()) {
            copyTextureRef(zf, ref, modelPath, entries, warnings);
        }
        // NEOFORGE:COMPOSITE children (14-o): create 6.0 ships nixie_tube /
        // display_link / stock_link as composite models — their texture refs
        // hide in "children" sub-models, and the gallery composite flattens
        // those children (GalleryPack.mergeModelGeometry), so the child
        // sprites must ride the pack exactly like the top-level ones.
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("children") && obj.getAsJsonObject("children").isJsonObject()) {
                for (Map.Entry<String, JsonElement> ch : obj.getAsJsonObject("children")
                        .entrySet()) {
                    if (!ch.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject child = ch.getValue().getAsJsonObject();
                    if (!child.has("textures") || !child.getAsJsonObject("textures")
                            .isJsonObject()) {
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> t : child.getAsJsonObject("textures")
                            .entrySet()) {
                        copyTextureRef(zf, t.getValue().getAsString(),
                                modelPath + " child " + ch.getKey(), entries, warnings);
                    }
                }
            }
        } catch (Exception bad) {
            // not a JSON-object model — the string extractors above already
            // did their best; nothing to add for children
        }
    }

    /** Copy ONE concrete modded sprite (the PNG behind a texture ref) into
     *  the pack — the per-ref body shared by the top-level texture walk and
     *  the neoforge:composite children walk. Local ({@code #key}) and
     *  vanilla refs carry nothing to copy; sprites outside the auto-atlased
     *  {@code item|block} directories stay skipped (v1 constraint). */
    private static void copyTextureRef(ZipFile zf, String ref, String modelPath,
                                       TreeMap<String, byte[]> entries,
                                       List<String> warnings) throws IOException {
        if (ref.startsWith("#") || ref.startsWith("minecraft:")) {
            return; // local key or vanilla texture — nothing to copy
        }
        int c = ref.indexOf(':');
        if (c <= 0) {
            return;
        }
        String texNs = ref.substring(0, c);
        String texPath = ref.substring(c + 1);
        String texFile = "assets/" + texNs + "/textures/" + texPath + ".png";
        if (entries.containsKey(texFile)) {
            return;
        }
        ZipEntry te = zf.getEntry(texFile);
        if (te == null) {
            warnings.add("texture missing in jar: " + ref
                    + " (referenced by " + modelPath + ")");
            return;
        }
        if (!texPath.startsWith("item/") && !texPath.startsWith("block/")) {
            // outside the auto-atlased directories — a merged atlas
            // override would be needed (v1 constraint). Skip the copy:
            // without an atlas entry the client cannot resolve the
            // texture either way, and dead bytes would mask the warning.
            warnings.add("texture outside item|block dirs not atlased: " + ref);
            return;
        }
        entries.put(texFile, zf.getInputStream(te).readAllBytes());
    }

    // ---- emitters ---------------------------------------------------------------

    static String packMcmeta() {
        return "{\n"
                + "  \"pack\": {\n"
                + "    \"description\": \"c-imagine F2V 2.0 visual pack (generated)\",\n"
                + "    \"pack_format\": " + PACK_FORMAT_MAJOR + ",\n"
                + "    \"min_format\": [" + PACK_FORMAT_MAJOR + ", " + PACK_FORMAT_MINOR + "],\n"
                + "    \"max_format\": [" + PACK_FORMAT_MAJOR + ", " + PACK_FORMAT_MINOR + "]\n"
                + "  }\n"
                + "}\n";
    }

    /**
     * One {@code items/<host>.json} definition: a range_dispatch over the
     * custom_model_data float property with the vanilla look as fallback.
     * Entries arrive sorted by threshold (vanilla defs list ascending).
     */
    /** The row's transformation (a row-major matrix) when its mesh shrank to fit. */
    private static String scaleField(String modelRef) {
        double k = GalleryPack.modelScale(modelRef);
        if (k == 1) {
            return "";
        }
        // the layer's local transform works on 0..1 model space: scale about the
        // block centre, not its corner (measured: a bare scale lands k=2 meshes half a block off)
        double t = (1 - k) / 2;
        return ", \"transformation\": [" + k + ",0,0," + t + ", 0," + k + ",0," + t + ", 0,0," + k + "," + t
                + ", 0,0,0,1]";
    }

    static String itemDef(String hostPath, List<Map.Entry<Float, String>> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"model\": {\n")
          .append("    \"type\": \"minecraft:range_dispatch\",\n")
          .append("    \"property\": \"minecraft:custom_model_data\",\n")
          .append("    \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Float, String> e = entries.get(i);
            sb.append("      {\"threshold\": ").append(e.getKey()).append(", \"model\": ");
            JsonObject override = GalleryPack.itemNode(e.getValue());
            if (override != null) {
                sb.append(override).append('}');
            } else {
                sb.append("{\"type\": \"minecraft:model\", \"model\": \"")
                  .append(e.getValue()).append('"').append(scaleField(e.getValue())).append("}}");
            }
            sb.append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        sb.append("    ],\n")
          .append("    \"fallback\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/")
          .append(hostPath).append("\"}\n")
          .append("  }\n}\n");
        return sb.toString();
    }

    static JsonObject itemModelNode(String ref) {
        return JsonParser.parseString("{\"type\":\"minecraft:model\",\"model\":\"" + ref
                + "\"" + scaleField(ref) + "}").getAsJsonObject();
    }

    /**
     * One {@code items/<host>.json} SELECT definition: the 26.3
     * select-dispatch surface (javap-proven
     * {@code renderer.item.properties.select.CustomModelDataProperty}: the
     * property reads {@code custom_model_data.strings} at {@code index},
     * default 0; {@code when} is a plain STRING). The vanilla look stays the
     * fallback branch, exactly like the range_dispatch variant.
     */
    static String selectDef(String hostPath, List<Map.Entry<String, String>> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"model\": {\n")
          .append("    \"type\": \"minecraft:select\",\n")
          .append("    \"property\": \"minecraft:custom_model_data\",\n")
          .append("    \"index\": 0,\n")
          .append("    \"cases\": [\n");
        for (int i = 0; i < cases.size(); i++) {
            Map.Entry<String, String> e = cases.get(i);
            sb.append("      {\"when\": \"").append(e.getKey())
              .append("\", \"model\": {\"type\": \"minecraft:model\", \"model\": \"")
              .append(e.getValue()).append('"').append(scaleField(e.getValue())).append("}}");
            sb.append(i + 1 < cases.size() ? ",\n" : "\n");
        }
        sb.append("    ],\n")
          .append("    \"fallback\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/")
          .append(hostPath).append("\"}\n")
          .append("  }\n}\n");
        return sb.toString();
    }

    // ---- deterministic zip ------------------------------------------------------

    /** Deterministic zip: sorted entries, fixed timestamps, fixed level. */
    static byte[] zip(TreeMap<String, byte[]> entries) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.setLevel(9);
            zos.setMethod(ZipOutputStream.DEFLATED);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(e.getKey());
                // a constant is what matters for byte-identical builds
                ze.setTime(FIXED_EPOCH);
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return String.format("%040x", new BigInteger(1, md.digest(data)));
        } catch (Exception e) {
            throw new IllegalStateException("sha1 unavailable", e);
        }
    }

    // ---- minimal model-json scanning (no JSON library on the plugin side) -------

    /** First top-level {@code "field": "value"} string in a flat JSON body. */
    static String extractStringField(String json, String field) {
        String needle = "\"" + field + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return null;
        }
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    /**
     * The {@code "textures": {...}} object of a model json as key->value
     * strings (brace-balanced; values are plain resource strings).
     */
    static Map<String, String> extractTextures(String json) {
        Map<String, String> out = new HashMap<>();
        String needle = "\"textures\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return out;
        }
        int open = json.indexOf('{', k);
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int i = open;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        if (depth != 0) {
            return out;
        }
        String body = json.substring(open + 1, i);
        // parse "key": "value" pairs
        int p = 0;
        while (true) {
            int keyOpen = body.indexOf('"', p);
            if (keyOpen < 0) {
                break;
            }
            int keyClose = body.indexOf('"', keyOpen + 1);
            if (keyClose < 0) {
                break;
            }
            int colon = body.indexOf(':', keyClose);
            if (colon < 0) {
                break;
            }
            int valOpen = body.indexOf('"', colon);
            if (valOpen < 0) {
                break;
            }
            int valClose = body.indexOf('"', valOpen + 1);
            if (valClose < 0) {
                break;
            }
            out.put(body.substring(keyOpen + 1, keyClose),
                    body.substring(valOpen + 1, valClose));
            p = valClose + 1;
        }
        return out;
    }

    // ---- CLI --------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--selftest")) {
            System.exit(selftest() ? 0 : 1);
            return;
        }
        if (args.length >= 3 && args[0].equals("--build")) {
            Path modsDir = Path.of(args[1]);
            Path outDir = Path.of(args[2]);
            Path manifest = outDir.resolve("visual-items.tsv");
            List<Path> extraAssets = new ArrayList<>();
            for (int i = 3; i < args.length - 1; i++) {
                if (args[i].equals("--manifest")) {
                    manifest = Path.of(args[i + 1]);
                }
                if (args[i].equals("--assets")) {
                    extraAssets.add(Path.of(args[i + 1]));
                }
            }
            List<VisualItem> rows = Files.isRegularFile(manifest)
                    ? readManifest(manifest) : List.of();
            Result r = build(modsDir, outDir, rows,
                    readEquipmentManifest(outDir.resolve("visual-equipment.tsv")),
                    readBlockManifest(outDir.resolve("visual-blocks.tsv")),
                    readPaletteManifest(outDir.resolve("visual-palettes.tsv")),
                    extraAssets);
            System.out.println("f2v pack: built (" + r.items().size() + " item(s), "
                    + r.gallery().size() + " gallery state(s), "
                    + r.entryCount() + " zip entries, sha1=" + r.sha1() + ", "
                    + r.warnings().size() + " warning(s))");
            return;
        }
        System.err.println("usage: F2vPack --build <modsDir> <outDir> [--manifest <tsv>]"
                + " [--assets <vanillaClientJar>]");
        System.err.println("       F2vPack --selftest");
        System.exit(2);
    }

    // ---- selftest -----------------------------------------------------------------

    /** Fixture model jsons (fakemod) — parent chain + modded textures. */
    private static final String MODEL_COOL_SWORD =
            "{\"parent\": \"fakemod:item/parent_model\", \"textures\": {\"layer0\": \"fakemod:item/cool_sword_tex\"}}";
    private static final String MODEL_PARENT =
            "{\"parent\": \"minecraft:item/handheld\", \"textures\": {\"layer0\": \"fakemod:item/parent_tex\"}}";
    private static final String MODEL_DEEP =
            "{\"parent\": \"minecraft:item/handheld\", \"textures\": {\"layer0\": \"fakemod:deep/custom_tex\"}}";
    private static final String MODEL_WAND =
            "{\"parent\": \"minecraft:item/handheld\", \"textures\": {\"layer0\": \"fakemod:item/wand_tex\"}}";
    private static final String MODEL_BLOCK =
            "{\"parent\": \"minecraft:block/cube_all\", \"textures\": {\"all\": \"fakemod:block/rock_tex\"}}";
    private static final String MODEL_GEM =
            "{\"parent\": \"minecraft:item/generated\", \"textures\": {\"layer0\": \"fakemod:item/gem_tex\"}}";

    /** Gallery fixtures: element models + blockstates (variants/multipart). */
    private static final String MODEL_PANE_POST =
            "{\"textures\": {\"tex\": \"fakemod:block/post_tex\"}, "
            + "\"elements\": [{\"from\": [7,0,7], \"to\": [9,16,9], "
            + "\"faces\": {\"north\": {\"uv\": [0,0,2,16], \"texture\": \"#tex\"}}}]}";
    private static final String MODEL_PANE_SIDE =
            "{\"textures\": {\"tex\": \"fakemod:block/side_tex\"}, "
            + "\"elements\": [{\"from\": [0,0,7], \"to\": [16,16,9], "
            + "\"faces\": {\"up\": {\"uv\": [0,0,16,2], \"texture\": \"#tex\"}}}]}";
    private static final String MODEL_ROCK_ARROW =
            "{\"parent\": \"fakemod:block/rock\", \"textures\": {\"all\": \"fakemod:block/arrow_tex\"}}";
    /** TINT-BAKE fixture: an own-elements block over a KNOWN grey texture —
     *  the visual-tints.tsv multiply must land on the baked sibling sprite,
     *  leaving the raw texture byte-identical for every other consumer. */
    private static final String MODEL_TINTED_BLOOM =
            "{\"textures\": {\"tex\": \"fakemod:block/bloom_tex\"}, "
            + "\"elements\": [{\"from\": [4,0,4], \"to\": [12,16,12], "
            + "\"faces\": {\"north\": {\"uv\": [0,0,8,16], \"texture\": \"#tex\"}}}]}";
    private static final String BS_TINTED_BLOOM =
            "{\"variants\": {\"\": {\"model\": \"fakemod:block/tinted_bloom\"}}}";
    /** The EMISSIVE-BAKE fixture: two elements so the "every element" contract
     *  is observable; the state carries its properties in a DIFFERENT order
     *  than the emissive table row keys it, so the canonical-state fallback
     *  binds. */
    private static final String MODEL_GLOW_BLOOM =
            "{\"textures\": {\"tex\": \"fakemod:block/glow_tex\"}, "
            + "\"elements\": ["
            + "{\"from\": [2,0,2], \"to\": [8,16,8], "
            + "\"faces\": {\"north\": {\"uv\": [0,0,6,16], \"texture\": \"#tex\"}}}, "
            + "{\"from\": [8,0,8], \"to\": [14,16,14], "
            + "\"faces\": {\"north\": {\"uv\": [0,0,6,16], \"texture\": \"#tex\"}}}]}";
    private static final String BS_GLOW_BLOOM =
            "{\"variants\": {\"\": {\"model\": \"fakemod:block/glow_bloom\"}}}";
    private static final String GALLERY_EMISSIVE_TSV =
            "fakemod:glow_bloom[lit=true,power=7]\n"
            + "fakemod:rock\n";
    /** The MEASURED #46 killer: create-1.21.1's creative_motor carries a face
     *  uv rect [14,4,20,10] — past the 16px sprite (a legal 1.21.1 texture
     *  bleed). 26.3's FaceBakery.computeMaterialTransparency samples the rect
     *  in the sprite image and THROWS, aborting the whole host item def bake
     *  (minecraft:paper) — every gallery display then renders the missing
     *  model = the #46 universal missingno. The sanitizer must clamp it. */
    private static final String MODEL_ZMOTOR =
            "{\"textures\": {\"tex\": \"fakemod:block/post_tex\"}, "
            + "\"elements\": [{\"from\": [0,0,0], \"to\": [16,16,16], "
            + "\"faces\": {\"north\": {\"uv\": [14,4,20,10], \"texture\": \"#tex\"}, "
            + "\"up\": {\"uv\": [0,0,16,16], \"texture\": \"#tex\"}}}]}";
    private static final String BS_ZMOTOR =
            "{\"variants\": {\"\": {\"model\": \"fakemod:block/zmotor\"}}}";
    /** The 14-o fixture: the {@code neoforge:composite} loader shape (create
     *  6.0 ships nixie_tube / display_link / stock_link this way) — geometry
     *  hides in "children" sub-models, the naive elements-only walk resolved
     *  the chain element-less and DROPPED the row (37 nixie gallery states
     *  never placed). The flatten must merge BOTH children, namespace their
     *  texture keys per child (both reference #0), and pull the child sprite
     *  PNGs into the pack. */
    private static final String MODEL_COMPOSITE =
            "{\"loader\": \"neoforge:composite\", \"parent\": \"block/block\", "
            + "\"textures\": {\"particle\": \"fakemod:block/comp_tex\"}, "
            + "\"children\": {"
            + "\"feet\": {\"render_type\": \"minecraft:solid\", "
            + "\"textures\": {\"0\": \"fakemod:block/feet_tex\"}, "
            + "\"elements\": [{\"from\": [5,0,9], \"to\": [11,3,15], "
            + "\"faces\": {\"north\": {\"uv\": [0,10,6,13], \"texture\": \"#0\"}}}]}, "
            + "\"tube\": {\"render_type\": \"minecraft:translucent\", "
            + "\"textures\": {\"0\": \"fakemod:block/comp_tex\"}, "
            + "\"elements\": [{\"from\": [5,3,9], \"to\": [11,12,15], "
            + "\"faces\": {\"north\": {\"uv\": [0,1,6,10], \"texture\": \"#0\"}}}]}}}";
    private static final String BS_COMPOSITE =
            "{\"variants\": {\"\": {\"model\": \"fakemod:block/comp\"}}}";
    private static final String BS_ROCK =
            "{\"variants\": {\"\": {\"model\": \"fakemod:block/rock\"}, "
            + "\"facing=north\": {\"model\": \"fakemod:block/rock_arrow\", \"y\": 90}}}";
    private static final String BS_PANE =
            "{\"multipart\": [{\"apply\": {\"model\": \"fakemod:block/pane_post\"}}, "
            + "{\"apply\": {\"model\": \"fakemod:block/pane_side\"}, \"when\": {\"north\": \"true\"}}, "
            // rotated unconditional part: covers the composite bake path
            // (y:90 element coords MUST come out sorted, from<=to per axis —
            // unsorted boxes render INVISIBLE on the stock client, measured
            // on the acacia_window_pane / chain-gearshift ours-leg run)
            + "{\"apply\": {\"model\": \"fakemod:block/pane_side\", \"y\": 90}}]}";
    private static final String GALLERY_TSV =
            "fakemod:pane[north=true]\n"
            + "fakemod:pane[north=false]\n"
            + "fakemod:rock\n"
            + "fakemod:rock[facing=north]\n"
            + "fakemod:zmotor\n"
            + "fakemod:zcomp\n"
            // 14-ak: the CT row — a synthetic create-namespace pillar state
            // (the fixtures below ship its palette sprites + connected
            // sheets); the ctx = the isolated-pillar indices (caps 0, the
            // RECTANGLE isolated tile 0+3*4=12)
            // the STATE rides the fakemod namespace (so the sorted order
            // of the existing index-based assertions holds); the CT remap
            // matches the SPRITE path shape (create:block/palettes/...)
            + "fakemod:stone_pillar[axis=y,east=false,north=false,south=false,"
            + "west=false]#0,0,12,12,12,12\n"
            // tint-bake fixture row: the composite must reference the BAKED
            // sibling sprite while the raw texture stays untouched
            + "fakemod:tinted_bloom\n";

    static boolean selftest() throws Exception {
        List<String> failures = new ArrayList<>();
        Path tmp = Files.createTempDirectory("f2v-selftest");
        try {
            // -- fixture mod jar ------------------------------------------------
            Path mods = tmp.resolve("mods");
            Files.createDirectories(mods);
            Path jar = mods.resolve("fakemod-1.0.0.jar");
            TreeMap<String, byte[]> jarEntries = new TreeMap<>();
            jarEntries.put("assets/fakemod/models/item/cool_sword.json",
                    MODEL_COOL_SWORD.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/item/parent_model.json",
                    MODEL_PARENT.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/item/deep_thing.json",
                    MODEL_DEEP.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/item/wand_thing.json",
                    MODEL_WAND.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/item/gem.json",
                    MODEL_GEM.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/rock.json",
                    MODEL_BLOCK.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/textures/item/wand_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/item/gem_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/palettes/gem_key.png", testPng());
            jarEntries.put("assets/fakemod/textures/palettes/gem_red.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/rock_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/models/armor/shiny_layer_1.png", testPng());
            jarEntries.put("assets/fakemod/textures/models/armor/shiny_layer_2.png", testPng());
            jarEntries.put("assets/fakemod/textures/item/cool_sword_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/deep/custom_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/item/parent_tex.png", testPng());
            jarEntries.put("assets/fakemod/models/block/pane_post.json", MODEL_PANE_POST.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/pane_side.json", MODEL_PANE_SIDE.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/rock_arrow.json", MODEL_ROCK_ARROW.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/blockstates/rock.json", BS_ROCK.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/blockstates/tinted_bloom.json",
                    BS_TINTED_BLOOM.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/tinted_bloom.json",
                    MODEL_TINTED_BLOOM.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/blockstates/glow_bloom.json",
                    BS_GLOW_BLOOM.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/glow_bloom.json",
                    MODEL_GLOW_BLOOM.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/textures/block/glow_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/bloom_tex.png", testGreyPng());
            jarEntries.put("assets/fakemod/blockstates/pane.json", BS_PANE.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/blockstates/zmotor.json", BS_ZMOTOR.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/zmotor.json", MODEL_ZMOTOR.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/blockstates/zcomp.json", BS_COMPOSITE.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/comp.json", MODEL_COMPOSITE.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/textures/block/comp_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/feet_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/post_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/side_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/arrow_tex.png", testPng());
            // 14-ak CT fixture: a synthetic create-namespace pillar — the
            // blockstate + the cube_column model + the palette sprites AND
            // the connected sheets (the remap reads them from the jars)
            jarEntries.put("assets/fakemod/blockstates/stone_pillar.json",
                    "{\"variants\": {\"axis=y\": {\"model\": \"fakemod:block/stone_pillar\"}}}"
                            .getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/stone_pillar.json",
                    ("{\"parent\": \"minecraft:block/cube_column\", "
                    + "\"textures\": {\"end\": \"create:block/palettes/stone_types/cap/tuff_cut_cap\", "
                    + "\"side\": \"create:block/palettes/stone_types/pillar/tuff_cut_pillar\", "
                    + "\"particle\": \"#side\"}}")
                            .getBytes(StandardCharsets.UTF_8));
            for (String p : new String[]{
                    "assets/create/textures/block/palettes/stone_types/cap/tuff_cut_cap.png",
                    "assets/create/textures/block/palettes/stone_types/cap/tuff_cut_cap_connected.png",
                    "assets/create/textures/block/palettes/stone_types/pillar/tuff_cut_pillar.png",
                    "assets/create/textures/block/palettes/stone_types/pillar/tuff_cut_pillar_connected.png"}) {
                jarEntries.put(p, testPng());
            }
            writeJar(jar, jarEntries);

            // -- vanilla stub jar (stands in for the 26.3 client jar) -----------
            // The gallery's vanilla-parent resolution reads ASSETS jars: the
            // most common mod model shape (own textures + a vanilla element
            // parent like block/cube_all) synthesizes ONLY when the parent
            // chain reaches the client's vanilla models. The stub mirrors the
            // MEASURED 26.3 vanilla assets — including the BARE parent refs
            // (cube_all -> "block/cube" -> "block/block"): the bare parent
            // inherits the namespace of the model that references it, which
            // is exactly what the per-hop namespace tracking in
            // resolveModelGraph exists for. NOTE: the stub lives OUTSIDE the
            // mods dir so the test exercises the extraAssets seam (the client
            // jar is never a mod jar).
            Path vanillaJar = tmp.resolve("client-stub.jar");
            TreeMap<String, byte[]> vanillaEntries = new TreeMap<>();
            vanillaEntries.put("assets/minecraft/models/block/cube_all.json",
                    ("{\"parent\": \"block/cube\", "
                    + "\"textures\": {\"particle\": \"#all\", \"down\": \"#all\", "
                    + "\"up\": \"#all\", \"north\": \"#all\", \"east\": \"#all\", "
                    + "\"south\": \"#all\", \"west\": \"#all\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            vanillaEntries.put("assets/minecraft/models/block/cube.json",
                    ("{\"parent\": \"block/block\", "
                    + "\"textures\": {\"particle\": \"#down\"}, "
                    + "\"elements\": [{\"from\": [0,0,0], \"to\": [16,16,16], "
                    // 14-al: the faces carry NO explicit uv — the MEASURED
                    // 26.3 client's cube.json shape (#91 pack bytes, g11061:
                    // the granite axis=y composite rode null-uv faces). The
                    // CT remap must derive + write the translated default
                    // itself; the asserted tile uvs below lock exactly that.
                    + "\"faces\": {\"down\": {\"texture\": \"#down\", \"cullface\": \"down\"}, "
                    + "\"up\": {\"texture\": \"#up\", \"cullface\": \"up\"}, "
                    + "\"north\": {\"texture\": \"#north\", \"cullface\": \"north\"}, "
                    + "\"south\": {\"texture\": \"#south\", \"cullface\": \"south\"}, "
                    + "\"west\": {\"texture\": \"#west\", \"cullface\": \"west\"}, "
                    + "\"east\": {\"texture\": \"#east\", \"cullface\": \"east\"}}}]}")
                            .getBytes(StandardCharsets.UTF_8));
            vanillaEntries.put("assets/minecraft/models/block/block.json",
                    "{\"gui_light\": \"side\"}".getBytes(StandardCharsets.UTF_8));
            // 14-ak: the cube_column parent the CT fixture's chain rides
            vanillaEntries.put("assets/minecraft/models/block/cube_column.json",
                    ("{\"parent\": \"block/cube\", "
                    + "\"textures\": {\"particle\": \"#side\", \"down\": \"#end\", "
                    + "\"up\": \"#end\", \"north\": \"#side\", \"south\": \"#side\", "
                    + "\"west\": \"#side\", \"east\": \"#side\"}}")
                            .getBytes(StandardCharsets.UTF_8));
            writeJar(vanillaJar, vanillaEntries);

            // -- manifest ---------------------------------------------------------
            Path out = tmp.resolve("out");
            Files.createDirectories(out);
            Files.writeString(out.resolve("visual-items.tsv"),
                    "fakemod:cool_sword\tminecraft:paper\n"
                    + "fakemod:deep_thing\tminecraft:paper\t2000.0\n"
                    + "fakemod:gem\tminecraft:paper\t3000.0\n"
                    + "fakemod:wand_thing\tbrick\t\tselect:wand\n",  // BARE host: readManifest normalizes to minecraft:brick (measured #49: a bare host skipped the whole group)
                    StandardCharsets.UTF_8);
            Files.writeString(out.resolve("visual-blocks.tsv"),
                    "fakemod:rock\tcreeper\t13\n",
                    StandardCharsets.UTF_8);
            Files.writeString(out.resolve("visual-palettes.tsv"),
                    "fakemod:gem\tfakemod:item/gem_tex\tfakemod:textures/palettes/gem_key.png\t_"
                    + "\tred=fakemod:textures/palettes/gem_red.png\n",
                    StandardCharsets.UTF_8);
            Files.writeString(out.resolve("visual-gallery.tsv"), GALLERY_TSV,
                    StandardCharsets.UTF_8);
            // TINT-BAKE manifest: a KNOWN multiply for the bloom fixture —
            // exercises exact multiply, half-up rounding, 255 clamp, alpha
            // preservation, and the raw-texture-untouched guarantee
            Files.writeString(out.resolve("visual-tints.tsv"),
                    "# calibrated by scripts/f2v/tint-calibrate.py\n"
                    + "fakemod:block/bloom_tex\t0.5\t1.0\t2.0\t"
                    + "fakemod:tinted_bloom@gallery-fake:p1:c0,r0|n=9|class=luma-uniform\n",
                    StandardCharsets.UTF_8);

            // -- build twice, must be byte-identical ------------------------------
            List<EquipmentAsset> equipment = List.of(new EquipmentAsset(
                    "fakemod:shiny",
                    "textures/models/armor/shiny_layer_1.png",
                    "textures/models/armor/shiny_layer_2.png"));
            Files.writeString(out.resolve("visual-equipment.tsv"), "fakemod:shiny\t"
                    + "textures/models/armor/shiny_layer_1.png\ttextures/models/armor/shiny_layer_2.png\n");
            Result r1 = build(mods, out, readManifest(out.resolve("visual-items.tsv")),
                    equipment, readBlockManifest(out.resolve("visual-blocks.tsv")),
                    readPaletteManifest(out.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            Result r2 = build(mods, out, readManifest(out.resolve("visual-items.tsv")),
                    equipment, readBlockManifest(out.resolve("visual-blocks.tsv")),
                    readPaletteManifest(out.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            if (!r1.sha1().equals(r2.sha1())) {
                failures.add("sha1 not deterministic: " + r1.sha1() + " vs " + r2.sha1());
            }
            Result verified = rebuildForVerification(mods, out, List.of(vanillaJar));
            expect(failures, verified.sha1().equals(r1.sha1()),
                    "verification: items/equipment/blocks/palettes/gallery/tints/assets match pre-boot build");
            if (r1.items().size() != 5) {
                failures.add("expected 5 resolved rows (4 manifest + 1 palette variant), got "
                        + r1.items().size() + ": " + r1.items());
            }
            if (r1.blocks().size() != 1
                    || !"fakemod:rock".equals(r1.blocks().get(0).moddedBlockId())
                    || !"creeper".equals(r1.blocks().get(0).instrument())
                    || r1.blocks().get(0).note() != 13) {
                failures.add("block row not resolved: " + r1.blocks());
            }
            // auto cmd must be CMD_BASE for the first sorted row
            if (r1.items().get(0).cmd() != CMD_BASE
                    || r1.items().get(1).cmd() != 2000.0f) {
                failures.add("cmd assignment wrong: " + r1.items());
            }
            // #114 ITEM-CHANNEL CMD COLLISION: the per-ns gallery-items
            // manifests each assign 300000+i; CONCATENATED, every ns's i-th
            // row collides on one cmd and the client renders ONE arbitrary
            // winner (measured: create:analog_lever rendering
            // dynamictrees:item/azalea_seed). assignCmds re-owns the space
            // sequentially over the concatenated rows; pinned rows and the
            // legacy 1000+i auto space never shift.
            List<VisualItem> collided = List.of(
                    new VisualItem("fakemod:alpha", "minecraft:paper", 300000f, null),
                    new VisualItem("fakemod:bravo", "minecraft:paper", 300000f, null),
                    new VisualItem("fakemod:charlie", "minecraft:paper", 300001f, null),
                    new VisualItem("fakemod:pinned", "minecraft:paper", 1100f, null),
                    new VisualItem("fakemod:auto", "minecraft:paper", -1f, null));
            List<VisualItem> owned = assignCmds(collided);
            expect(failures,
                    owned.get(0).cmd() == 300000f && owned.get(1).cmd() == 300001f
                            && owned.get(2).cmd() == 300002f,
                    "gallery-item cmd space re-assigned uniquely over colliding rows: "
                            + owned);
            expect(failures, owned.get(3).cmd() == 1100f,
                    "pinned cmd below the gallery space never shifts");
            expect(failures, owned.get(4).cmd() == CMD_BASE + 4,
                    "implicit row keeps the legacy 1000+i assignment");
            // PALETTE channel (M6): the variant row rides the base host at
            // cmd = base + 1 (zip-content asserts live below, after readZip)
            expect(failures, r1.items().stream()
                    .anyMatch(i -> i.moddedId().equals("fakemod:gem@red")
                            && i.cmd() == 3001.0f
                            && i.hostItem().equals("minecraft:paper")),
                    "palette variant row resolved (gem@red cmd 3001 on paper)");

            // GALLERY channel (M5.5): EVERY row is a self-contained
            // composite — including the vanilla-parent shape (own textures,
            // elements from the client jar's block/cube_all), which the old
            // direct path rendered as NOTHING (measured run 41126658261)
            expect(failures, r1.gallery().size() == 8,
                    "gallery: 8 state rows resolved, got " + r1.gallery().size());
            if (r1.gallery().size() == 8) {
                List<GalleryPack.Resolved> g = r1.gallery();
                // pane[north=false] = composite of the unconditional parts
                // (post + the y90 side) — multipart rows are ALWAYS composites
                // even when no "when" matches (vanilla renders the
                // unconditional parts the same way)
                expect(failures, g.get(0).stateId().equals("fakemod:pane[north=false]")
                                && g.get(0).cmd() == GalleryPack.CMD_BASE
                                && g.get(0).composite()
                                && g.get(0).modelRef().startsWith("c-imagine:gallery/g"),
                        "gallery: pane[north=false] = composite (unconditional parts), cmd base");
                expect(failures, g.get(1).stateId().equals("fakemod:pane[north=true]")
                                && g.get(1).composite(),
                        "gallery: pane[north=true] = multipart composite");
                // rock = OWN textures + VANILLA cube_all elements — the
                // vanilla-parent class the client jar seam exists for
                expect(failures, g.get(2).stateId().equals("fakemod:rock")
                                && g.get(2).composite()
                                && g.get(2).modelRef().equals("c-imagine:gallery/g2"),
                        "gallery: rock = composite synthesized from the vanilla parent");
                expect(failures, g.get(3).stateId().equals("fakemod:rock[facing=north]")
                                && g.get(3).composite()
                                && g.get(3).modelRef().equals("c-imagine:gallery/g3")
                                && g.get(3).yRot() == 0 && g.get(3).xRot() == 0,
                        "gallery: rock[facing=north] = composite, y90 baked into elements");
                // zcomp = the neoforge:composite fixture: the children
                // geometry must flatten into the composite instead of the row
                // dropping (the 14-o nixie kill); located by state, not slot
                GalleryPack.Resolved zrow = g.stream()
                        .filter(r -> r.stateId().equals("fakemod:zcomp"))
                        .findFirst().orElse(null);
                expect(failures, zrow != null && zrow.composite(),
                        "gallery: zcomp = composite flattened from the neoforge:composite children");
                // 14-ak: the CT row — the suffixed stateId rides Resolved,
                // the composite remaps into the connected sheets: the
                // side faces carry the isolated RECTANGLE tile (0,3) —
                // uv [(0+0)/4, (0+48)/4, (16+0)/4, (16+48)/4] = [0,12,4,16]
                // — and the caps the isolated OMNIDIRECTIONAL tile (0,0) —
                // uv [0,0,2,2] (/8); the connected png ships in the pack.
                // 14-al: the fixture chain rides the MEASURED uv-less 26.3
                // cube stub, so these uvs come out through the null-uv
                // default derivation (defaultFaceUv on the post-bake box) —
                // a remap that only repointed the texture would render the
                // whole sheet and emit NO uv array, failing these asserts.
                GalleryPack.Resolved ctRow = g.stream()
                        .filter(r -> r.stateId().endsWith("#0,0,12,12,12,12"))
                        .findFirst().orElse(null);
                expect(failures, ctRow != null && ctRow.composite()
                                && ctRow.modelRef().startsWith("c-imagine:gallery/g"),
                        "gallery: the CT row resolves with its ctx suffix");
                if (ctRow != null) {
                    String ctModel = ctRow.modelRef().substring(
                            ctRow.modelRef().indexOf(':') + 1);
                    TreeMap<String, byte[]> packZip = readZip(
                            out.resolve("pack.zip"));
                    String ctJson = str(packZip,
                            "assets/c-imagine/models/" + ctModel + ".json");
                    expect(failures, ctJson.contains("tuff_cut_pillar_connected")
                                    && ctJson.contains("tuff_cut_cap_connected")
                                    && ctJson.contains("[0.0,12.0,4.0,16.0]")
                                    && ctJson.contains("[0.0,0.0,2.0,2.0]"),
                            "gallery: the CT composite remaps into the connected sheets");
                    expect(failures, packZip.containsKey(
                            "assets/create/textures/block/palettes/stone_types/"
                            + "pillar/tuff_cut_pillar_connected.png"),
                            "gallery: the connected sheet ships in the pack");
                }
            }
            expect(failures, r1.gallery().stream()
                    .mapToDouble(GalleryPack.Resolved::cmd).distinct().count() == 8,
                    "gallery: cmds unique (100000..100007)");

            // -- TINT-BAKE channel asserts -----------------------------------------
            // the bake log lands in the build report's warnings (the pack
            // build log) — one honest line per baked texture
            expect(failures, r1.warnings().stream()
                            .anyMatch(w -> w.startsWith("tint-bake applied: fakemod:block/bloom_tex")),
                    "tint bake recorded in the build log");
            TreeMap<String, byte[]> tintZip = readZip(out.resolve("pack.zip"));
            String bakedKey = "assets/fakemod/textures/block/bloom_tex_cimtint.png";
            expect(failures, tintZip.containsKey(bakedKey),
                    "tint bake: the baked sibling PNG rides the pack");
            if (tintZip.containsKey(bakedKey)) {
                java.awt.image.BufferedImage baked = decodePng(tintZip.get(bakedKey));
                int[][] expected = {
                        {100, 100, 100, 255}, {70, 140, 255, 255},
                        {100, 10, 20, 255}, {5, 20, 60, 0},
                        {128, 255, 255, 255}, {0, 0, 0, 255},
                        {32, 128, 255, 255}, {1, 2, 6, 255}};
                boolean pixelsOk = baked.getWidth() == 4 && baked.getHeight() == 2;
                for (int y = 0; y < 2 && pixelsOk; y++) {
                    for (int x = 0; x < 4 && pixelsOk; x++) {
                        int argb = baked.getRGB(x, y);
                        int[] p = expected[y * 4 + x];
                        int want = (p[3] << 24) | (p[0] << 16) | (p[1] << 8) | p[2];
                        pixelsOk &= argb == want;
                        if (argb != want) {
                            failures.add("tint bake pixel (" + x + "," + y + "): want "
                                    + Integer.toHexString(want) + " got "
                                    + Integer.toHexString(argb));
                        }
                    }
                }
                expect(failures, pixelsOk,
                        "tint bake: baked pixels = raw x (0.5,1.0,2.0) with "
                        + "half-up rounding, 255 clamp, alpha preserved");
            }
            // the RAW texture rides UNCHANGED — the item channel and the
            // note_block block channel keep rendering it exactly as before
            expect(failures, java.util.Arrays.equals(
                            tintZip.get("assets/fakemod/textures/block/bloom_tex.png"),
                            jarEntries.get("assets/fakemod/textures/block/bloom_tex.png")),
                    "tint bake: the raw texture is untouched in the pack");
            String bloomComp = r1.gallery().stream()
                    .filter(r -> r.stateId().equals("fakemod:tinted_bloom"))
                    .findFirst()
                    .map(r -> "assets/c-imagine/models/gallery/"
                            + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1) + ".json")
                    .orElse("");
            expect(failures, !bloomComp.isEmpty(), "tint bake: composite located");
            if (!bloomComp.isEmpty()) {
                String bloomJson = str(tintZip, bloomComp);
                expect(failures, bloomJson.contains("fakemod:block/bloom_tex_cimtint"),
                        "tint bake: the composite references the BAKED sprite");
            }
            // ABSENT visual-tints.tsv = today's behavior byte-for-byte: no
            // baked siblings anywhere, the composite references the raw sprite
            Path out2 = tmp.resolve("out2");
            Files.createDirectories(out2);
            Files.writeString(out2.resolve("visual-items.tsv"),
                    Files.readString(out.resolve("visual-items.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out2.resolve("visual-palettes.tsv"),
                    Files.readString(out.resolve("visual-palettes.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out2.resolve("visual-gallery.tsv"), GALLERY_TSV,
                    StandardCharsets.UTF_8);
            build(mods, out2, readManifest(out2.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out2.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            TreeMap<String, byte[]> noTintZip = readZip(out2.resolve("pack.zip"));
            expect(failures, noTintZip.keySet().stream()
                            .noneMatch(k -> k.contains(GalleryPack.TINT_SUFFIX)),
                    "tint bake: absent visual-tints.tsv = no baked sprites (today's pack)");
            // tinted_bloom sorts 6th of 8 (after the stone_pillar CT row) = g5
            String noTintComp = "assets/c-imagine/models/gallery/g5.json";
            expect(failures, str(noTintZip, noTintComp).contains("\"fakemod:block/bloom_tex\""),
                    "tint bake: absent visual-tints.tsv = composite references the raw sprite");

            // -- EMISSIVE-BAKE channel asserts ---------------------------------------
            // Own fixture dir: appending a state to GALLERY_TSV would churn every
            // index-based gallery assert above; the emissive bake needs only a
            // minimal manifest. The table keys the state with its properties in
            // a DIFFERENT order than the manifest row (canonical fallback), plus
            // the malformed-row contracts: an out-of-range level and a
            // non-numeric level are skipped, never fatal.
            Path out3 = tmp.resolve("out3");
            Files.createDirectories(out3);
            Files.writeString(out3.resolve("visual-items.tsv"),
                    Files.readString(out.resolve("visual-items.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out3.resolve("visual-palettes.tsv"),
                    Files.readString(out.resolve("visual-palettes.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out3.resolve("visual-gallery.tsv"), GALLERY_EMISSIVE_TSV,
                    StandardCharsets.UTF_8);
            Files.writeString(out3.resolve("visual-emissive.tsv"),
                    "# visual-emissive.tsv — the LIGHTMAP class (run 42085254497 join REDs)\n"
                    + "fakemod:glow_bloom[power=7,lit=true]\t15\t"
                    + "fakemod:glow_bloom[lit=true,power=7]@gallery-fake:p1:c0,r0|n=9"
                    + "|class=lightmap-fullbright\n"
                    + "fakemod:glow_bloom[power=7,lit=true]\t15\n"
                    + "fakemod:bad\t99\tout-of-range levels are skipped\n"
                    + "fakemod:bad2\tabc\tnon-numeric levels are skipped\n",
                    StandardCharsets.UTF_8);
            Map<String, Integer> emissiveParsed =
                    readEmissiveManifest(out3.resolve("visual-emissive.tsv"));
            expect(failures, emissiveParsed.size() == 2
                            && Integer.valueOf(15).equals(emissiveParsed.get(
                                    "fakemod:glow_bloom[power=7,lit=true]"))
                            && Integer.valueOf(15).equals(emissiveParsed.get(
                                    "fakemod:glow_bloom[lit=true,power=7]")),
                    "emissive manifest: raw + canonical alias survive (malformed/out-of-range skipped)");
            Result r3 = build(mods, out3, readManifest(out3.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out3.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            Result r4 = build(mods, out3, readManifest(out3.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out3.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            expect(failures, r3.sha1().equals(r4.sha1()),
                    "emissive bake: deterministic (rebuild byte-identical)");
            expect(failures, r3.warnings().stream().anyMatch(w -> w.startsWith(
                            "emissive-bake applied: fakemod:glow_bloom[lit=true,power=7]"
                            + " light_emission=15 on 2 elements")),
                    "emissive bake recorded in the build log");
            TreeMap<String, byte[]> emissiveZip = readZip(out3.resolve("pack.zip"));
            java.util.function.Function<String, String> compositeOf = stateId ->
                    r3.gallery().stream()
                            .filter(r -> r.stateId().equals(stateId))
                            .findFirst()
                            .map(r -> "assets/c-imagine/models/gallery/"
                                    + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1)
                                    + ".json")
                            .orElse("");
            String glowComp = compositeOf.apply("fakemod:glow_bloom[lit=true,power=7]");
            String rockComp = compositeOf.apply("fakemod:rock");
            expect(failures, !glowComp.isEmpty() && !rockComp.isEmpty(),
                    "emissive bake: both fixture composites located");
            if (!glowComp.isEmpty()) {
                JsonArray glowEls = JsonParser.parseString(str(emissiveZip, glowComp))
                        .getAsJsonObject().getAsJsonArray("elements");
                boolean allEmissive = glowEls.size() == 2;
                for (JsonElement el : glowEls) {
                    allEmissive &= el.getAsJsonObject().has("light_emission")
                            && el.getAsJsonObject().get("light_emission").getAsInt() == 15;
                }
                expect(failures, allEmissive,
                        "emissive bake: EVERY element of the tabled state carries light_emission 15");
            }
            if (!rockComp.isEmpty()) {
                JsonArray rockEls = JsonParser.parseString(str(emissiveZip, rockComp))
                        .getAsJsonObject().getAsJsonArray("elements");
                boolean noneEmissive = true;
                for (JsonElement el : rockEls) {
                    noneEmissive &= !el.getAsJsonObject().has("light_emission");
                }
                expect(failures, noneEmissive,
                        "emissive bake: a state not in the table emits untouched");
            }
            // ABSENT visual-emissive.tsv = today's behavior byte-for-byte
            Path out5 = tmp.resolve("out5");
            Files.createDirectories(out5);
            Files.writeString(out5.resolve("visual-items.tsv"),
                    Files.readString(out.resolve("visual-items.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out5.resolve("visual-palettes.tsv"),
                    Files.readString(out.resolve("visual-palettes.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out5.resolve("visual-gallery.tsv"), GALLERY_EMISSIVE_TSV,
                    StandardCharsets.UTF_8);
            build(mods, out5, readManifest(out5.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out5.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            TreeMap<String, byte[]> noEmissiveZip = readZip(out5.resolve("pack.zip"));
            JsonArray noEls = JsonParser.parseString(str(noEmissiveZip,
                    "assets/c-imagine/models/gallery/g0.json"))
                    .getAsJsonObject().getAsJsonArray("elements");
            boolean absentClean = true;
            for (JsonElement el : noEls) {
                absentClean &= !el.getAsJsonObject().has("light_emission");
            }
            expect(failures, absentClean,
                    "emissive bake: absent visual-emissive.tsv = no light_emission anywhere");

            // -- zip contents -------------------------------------------------------
            TreeMap<String, byte[]> entries = readZip(out.resolve("pack.zip"));
            // 26.3 UV SANITIZER (run #46): the zmotor fixture rides the bad
            // creative_motor uv [14,4,20,10]; the emitted model MUST come out
            // clamped in [0,16] — an unclamped rect kills the 26.3 host def
            // bake (computeMaterialTransparency) and turns the whole gallery
            // into the missing model
            String zmotor = str(entries, "assets/fakemod/models/block/zmotor.json");
            expect(failures, zmotor.contains("\"uv\": [14, 4, 16, 10]"),
                    "uv sanitizer clamped the out-of-bounds face rect: " + zmotor);
            expect(failures, !zmotor.contains("20"),
                    "uv sanitizer removed the out-of-bounds coordinate");
            expect(failures, r1.warnings().stream()
                            .anyMatch(w -> w.contains("zmotor") && w.contains("uv clamped")),
                    "uv sanitizer warning recorded for the clamped model");
            // 14-O COMPOSITE FLATTEN: the emitted composite merges the
            // neoforge:composite children — per-child texture keys, per-child
            // face refs, both children's boxes, and the child sprite PNG in
            // the pack (the file located through the resolved row's modelRef)
            String compFile = r1.gallery().stream()
                    .filter(r -> r.stateId().equals("fakemod:zcomp"))
                    .findFirst()
                    .map(r -> "assets/c-imagine/models/gallery/"
                            + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1) + ".json")
                    .orElse("");
            expect(failures, !compFile.isEmpty(), "composite: emit slot resolvable");
            String zcompjson = compFile.isEmpty() ? "" : str(entries, compFile);
            String zcompflat = zcompjson.replace(" ", "");
            expect(failures, zcompflat.contains("\"p0_feet_0\"") && zcompflat.contains("\"p0_tube_0\""),
                    "composite: child textures merged under the part prefix: " + zcompjson);
            expect(failures, zcompflat.contains("\"texture\":\"#p0_feet_0\"")
                            && zcompflat.contains("\"texture\":\"#p0_tube_0\""),
                    "composite: child face refs renamed per child");
            expect(failures, zcompflat.contains("\"from\":[5,0,9]") && zcompflat.contains("\"from\":[5,3,9]"),
                    "composite: both children's elements present");
            expect(failures, entries.containsKey("assets/fakemod/textures/block/feet_tex.png"),
                    "composite: the child sprite PNG rides the pack");
            String mcmeta = str(entries, "pack.mcmeta");
            expect(failures, mcmeta.contains("\"min_format\": [" + PACK_FORMAT_MAJOR
                    + ", " + PACK_FORMAT_MINOR + "]"), "pack.mcmeta min_format pair");
            expect(failures, mcmeta.contains("\"max_format\": [" + PACK_FORMAT_MAJOR
                    + ", " + PACK_FORMAT_MINOR + "]"), "pack.mcmeta max_format pair");
            String paper = str(entries, "assets/minecraft/items/paper.json");
            expect(failures, paper.contains("\"minecraft:range_dispatch\""),
                    "paper def uses range_dispatch");
            expect(failures, paper.contains("\"property\": \"minecraft:custom_model_data\""),
                    "paper def dispatches on custom_model_data");
            expect(failures, paper.contains("\"threshold\": " + CMD_BASE),
                    "paper def first threshold = CMD_BASE");
            expect(failures, paper.contains("\"threshold\": 2000.0"),
                    "paper def second threshold = 2000.0");
            expect(failures, paper.contains("\"model\": \"fakemod:item/cool_sword\""),
                    "paper def references the modded item model");
            expect(failures, paper.contains(
                    "\"fallback\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/paper\"}"),
                    "paper def fallback keeps the vanilla look");
            // PALETTE channel zip contents: atlas source + palette PNGs + the
            // retinted variant model + both thresholds in the host def
            String atlas = str(entries, "assets/minecraft/atlases/items.json");
            expect(failures, atlas.contains("\"minecraft:paletted_permutations\""),
                    "atlas carries the paletted_permutations source");
            expect(failures, atlas.contains("\"textures\": [\"fakemod:item/gem_tex\"]"),
                    "atlas source recolors the base sprite");
            expect(failures, atlas.contains("\"palette_key\": \"fakemod:palettes/gem_key\""),
                    "atlas source palette_key in sprite space");
            expect(failures, atlas.contains("\"red\": \"fakemod:palettes/gem_red\""),
                    "atlas source permutation entry");
            expect(failures, atlas.contains("\"separator\": \"_\""),
                    "atlas source separator");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/palettes/gem_key.png"), "palette key png copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/palettes/gem_red.png"), "permutation png copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/models/item/gem.json"), "base gem model copied");
            String gemRed = str(entries, "assets/fakemod/models/item/gem_red.json");
            Map<String, String> gemRedTex = extractTextures(gemRed);
            expect(failures, "fakemod:item/gem_tex_red".equals(gemRedTex.get("layer0")),
                    "variant model retinted to the permutated sprite");
            expect(failures, gemRed.contains("minecraft:item/generated"),
                    "variant model keeps the base parent");
            expect(failures, paper.contains("\"threshold\": 3001.0"),
                    "paper def third threshold = the variant cmd");
            expect(failures, paper.contains("\"model\": \"fakemod:item/gem_red\""),
                    "paper def references the retinted variant model");
            // GALLERY zip contents: composite file + host-def thresholds
            String comp = str(entries, "assets/c-imagine/models/gallery/g0.json");
            expect(failures, comp.contains("\"parent\":\"minecraft:block/block\""),
                    "gallery composite keeps the block/block display context");
            expect(failures, comp.contains("\"p0_tex\":\"fakemod:block/post_tex\"")
                            && comp.contains("\"p1_tex\":\"fakemod:block/side_tex\""),
                    "gallery composite namespaces part texture keys (p0_/p1_)");
            expect(failures, comp.contains("\"texture\":\"#p0_tex\"")
                            && comp.contains("\"texture\":\"#p1_tex\""),
                    "gallery composite rewires element face refs");
            expect(failures, comp.contains("fakemod:block/pane_post") == false
                            && comp.contains("\"elements\":["),
                    "gallery composite is standalone (no modded parent, elements inline)");
            // the y:90 rotated part bakes SORTED element coords (from<=to per
            // axis); unsorted boxes render invisible on the stock client
            com.google.gson.JsonObject g0 = com.google.gson.JsonParser.parseString(comp)
                    .getAsJsonObject();
            com.google.gson.JsonArray els = g0.getAsJsonArray("elements");
            // pane[north=false]: the unconditional parts only — post + y90 side
            expect(failures, els.size() == 2,
                    "gallery composite g0 = post + y90 side, got " + els.size());
            if (els.size() == 2) {
                com.google.gson.JsonArray f = els.get(1).getAsJsonObject().getAsJsonArray("from");
                com.google.gson.JsonArray t = els.get(1).getAsJsonObject().getAsJsonArray("to");
                boolean sorted = true;
                for (int i = 0; i < 3; i++) {
                    sorted &= f.get(i).getAsDouble() <= t.get(i).getAsDouble();
                }
                expect(failures, sorted,
                        "gallery composite y90 part coords sorted (from<=to), from="
                                + f + " to=" + t);
            }
            // g2 = rock: ONE cube element synthesized from the VANILLA
            // cube_all parent, the mod's own texture riding the namespaced key
            String g2 = str(entries, "assets/c-imagine/models/gallery/g2.json");
            expect(failures, g2.contains("\"p0_all\":\"fakemod:block/rock_tex\""),
                    "gallery vanilla-parent composite carries the mod texture (p0_all)");
            com.google.gson.JsonObject g2o = com.google.gson.JsonParser.parseString(g2)
                    .getAsJsonObject();
            expect(failures, g2o.getAsJsonArray("elements").size() == 1,
                    "gallery vanilla-parent composite = one cube element");
            // g3 = rock[facing=north]: the y90 variant baked into the
            // composite (a full cube is rotation-invariant — uniform UVs make
            // the key permutation unobservable in JSON; the observable is the
            // synthesized file itself + the entity carrying 0/0, asserted
            // above). The NON-invariant bake path is covered by g0's y90 side
            // (sorted-coords assert below).
            String g3 = str(entries, "assets/c-imagine/models/gallery/g3.json");
            expect(failures, g3.contains("\"p0_all\":\"fakemod:block/arrow_tex\""),
                    "gallery y90 bake keeps the VARIANT's texture override (arrow_tex)");
            expect(failures, paper.contains("\"threshold\": " + GalleryPack.CMD_BASE),
                    "paper def carries the gallery cmd base");
            expect(failures, paper.contains("c-imagine:gallery/g0"),
                    "paper def references the gallery composite");
            // the DIRECT gallery rows copy their model files at original paths
            expect(failures, entries.containsKey(
                    "assets/fakemod/models/block/pane_post.json"),
                    "gallery direct row model file copied (pane_post)");
            expect(failures, entries.containsKey(
                    "assets/fakemod/models/block/rock_arrow.json"),
                    "gallery direct row model file copied (rock_arrow)");
            // model graph copied at original paths
            expect(failures, entries.containsKey(
                    "assets/fakemod/models/item/cool_sword.json"), "entry model copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/models/item/parent_model.json"), "parent model copied");
            // textures: item/ auto-atlased copied; deep/ skipped with warning
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/item/cool_sword_tex.png"), "item texture copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/item/parent_tex.png"), "parent texture copied");
            expect(failures, !entries.containsKey(
                    "assets/fakemod/textures/deep/custom_tex.png"), "deep texture NOT atlased");
            boolean warned = r1.warnings().stream()
                    .anyMatch(w -> w.contains("outside item|block dirs"));
            expect(failures, warned, "deep texture produced a warning");
            // no vanilla-namespace assets leaked into the pack
            boolean vanillaLeak = entries.keySet().stream()
                    .anyMatch(k -> k.startsWith("assets/minecraft/models/"));
            expect(failures, !vanillaLeak, "no vanilla model copies");
            // both rows resolve at the item/ location and reach the host def
            expect(failures, paper.contains("fakemod:item/deep_thing"),
                    "second row model ref present");
            // SELECT dispatch (v2): the brick host def dispatches on
            // custom_model_data STRINGS with the vanilla look as fallback
            String brick = str(entries, "assets/minecraft/items/brick.json");
            expect(failures, brick.contains("\"minecraft:select\""),
                    "brick def uses select");
            expect(failures, brick.contains("\"property\": \"minecraft:custom_model_data\""),
                    "brick def dispatches on custom_model_data");
            expect(failures, brick.contains("\"when\": \"wand\""),
                    "brick def case when=wand");
            expect(failures, brick.contains("\"index\": 0"),
                    "brick def strings index 0");
            expect(failures, brick.contains("fakemod:item/wand_thing"),
                    "brick def references the modded wand model");
            expect(failures, brick.contains(
                    "\"fallback\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/brick\"}"),
                    "brick def fallback keeps the vanilla look");
            expect(failures, r1.items().stream()
                    .filter(i -> i.moddedId().equals("fakemod:wand_thing"))
                    .allMatch(i -> !i.isFloatDispatch() && "wand".equals(i.selectValue())),
                    "wand row resolved as select:wand");
            // BLOCK channel (parity matrix "blocks"): the note_block
            // blockstate override maps the stand-in slot to the modded model
            String nb = str(entries, "assets/minecraft/blockstates/note_block.json");
            expect(failures, nb.contains("\"instrument=creeper,note=13\""),
                    "note_block variant key instrument=creeper,note=13");
            expect(failures, nb.contains("\"model\": \"fakemod:block/rock\""),
                    "note_block variant points at the modded block model");
            expect(failures, entries.containsKey(
                    "assets/fakemod/models/block/rock.json"), "block model copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/block/rock_tex.png"),
                    "block texture copied (block/ is auto-atlased)");
            // determinism with the block channel active
            if (!r1.sha1().equals(r2.sha1())) {
                failures.add("sha1 not deterministic with blocks: "
                        + r1.sha1() + " vs " + r2.sha1());
            }
            // EQUIPMENT surface (M6 pull-forward): legacy armor textures land
            // at the 26.3 canonical locations + the asset json
            String equipJson = str(entries, "assets/fakemod/equipment/shiny.json");
            expect(failures, equipJson.contains("\"humanoid\": ["),
                    "equipment json humanoid layer");
            expect(failures, equipJson.contains("\"texture\": \"fakemod:shiny\""),
                    "equipment json texture ref");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/entity/equipment/humanoid/shiny.png"),
                    "layer_1 mapped to humanoid location (path-only formula)");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/entity/equipment/humanoid_leggings/shiny.png"),
                    "layer_2 mapped to humanoid_leggings location (path-only formula)");
            // the modded post-effect chain rides every non-empty pack
            String chain = str(entries, "assets/c-imagine/post_effect/cimsepia.json");
            expect(failures, chain.contains("\"minecraft:post/color_convolve\""),
                    "modded chain composes a vanilla fragment program");
            expect(failures, chain.contains("cimsepia") == false,
                    "chain json needs no self-id (registry entry name is the path)");
            expect(failures, chain.contains("\"output\": \"minecraft:main\""),
                    "modded chain ends on minecraft:main (measured invariant)");

            // -- json scanning helpers ------------------------------------------
            expect(failures, extractStringField(MODEL_COOL_SWORD, "parent")
                    .equals("fakemod:item/parent_model"), "parent field extracted");
            Map<String, String> tex = extractTextures(MODEL_COOL_SWORD);
            expect(failures, tex.get("layer0").equals("fakemod:item/cool_sword_tex"),
                    "textures map extracted");
        } finally {
            deleteRecursively(tmp);
        }
        // neoforge:obj — a quad becomes one element, a triangle pair rejoins
        // into one, a lone triangle waits for its cutout sprite
        var quad = ObjMesh.parse("v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n"
                + "usemtl m\nf 1/1 2/2 3/3 4/4\n", Map.of("m", "#t"), false);
        var quadEls = ObjMesh.toElements(quad, ObjMesh.fitScale(quad));
        expect(failures, quadEls.size() == 1 && quadEls.get(0).getAsJsonObject().has("faces"),
                "obj: a unit quad bakes to one element");
        var pair = ObjMesh.parse("v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n"
                + "usemtl m\nf 1/1 2/2 3/3\nf 1/1 3/3 4/4\n", Map.of("m", "#t"), false);
        expect(failures, ObjMesh.toElements(pair, 1).size() == 1, "obj: a triangle pair rejoins");
        var tri = ObjMesh.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nvt 0 0\nvt 1 0\nvt 0 1\n"
                + "usemtl m\nf 1/1 2/2 3/3\n", Map.of("m", "#t"), false);
        expect(failures, ObjMesh.toElements(tri, 1).get(0).getAsJsonObject().has(ObjMesh.POLY),
                "obj: a lone triangle is a cutout polygon");
        var big = ObjMesh.parse("v -3 0 0\nv 4 0 0\nv 4 1 0\nv -3 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\n"
                + "usemtl m\nf 1/1 2/2 3/3 4/4\n", Map.of("m", "#t"), false);
        expect(failures, ObjMesh.fitScale(big) == 4, "obj: an oversized mesh shrinks to the element limits");
        double[] angles = ObjMesh.eulerOf(ObjMesh.columnsOf(30, 40, 50));
        expect(failures, Math.abs(angles[0] - 30) < 1e-6 && Math.abs(angles[1] - 40) < 1e-6
                && Math.abs(angles[2] - 50) < 1e-6, "obj: euler decomposition round-trips");
        // a sprite no jar can supply must never leave the blocks atlas: the
        // generated trims/items/* sprites live in the items atlas, and ONE
        // mixed quad aborts the bake of the whole paper definition
        TreeMap<String, byte[]> hostEntries = new TreeMap<>();
        List<String> hostWarnings = new ArrayList<>();
        String trim = "minecraft:trims/items/leggings_trim_quartz";
        String hosted = GalleryPack.hostSprite(List.of(), trim, hostEntries, hostWarnings);
        expect(failures, hosted.equals(GalleryPack.PLACEHOLDER_SPRITE),
                "gallery: an unhostable item-atlas sprite takes the placeholder");
        expect(failures, hostEntries.containsKey(
                "assets/c-imagine/textures/block/items/unhosted.png"),
                "gallery: the placeholder sprite ships in the pack");
        expect(failures, hostWarnings.size() == 1 && hostWarnings.get(0).contains(trim),
                "gallery: the unhostable sprite stays loud in the build report");
        expect(failures, GalleryPack.hostSprite(List.of(), "mod:block/gone", hostEntries,
                hostWarnings).equals("mod:block/gone"),
                "gallery: a missing block-path sprite keeps its blocks-atlas ref");
        // a re-hosted sprite keeps animation metadata the client can honour;
        // a frames list resolving to at most ONE valid frame on a multi-frame
        // strip flips the sprite static with frame-sized contents and crashes
        // the atlas upload — the client then DROPS the whole pack (measured
        // run 42099936223: three re-hosted "frames": [] mcmeta turned every
        // gallery cell into the paper fallback)
        byte[] square16 = f2vSelftestPng(16, 16);
        byte[] strip32 = f2vSelftestPng(16, 32);
        byte[] strip48 = f2vSelftestPng(16, 48);
        Path animJar = Files.createTempFile("f2v-selftest-anim", ".jar");
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(animJar))) {
            zo.putNextEntry(new ZipEntry("assets/mod/textures/item/glow.png"));
            zo.write(square16);
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("assets/mod/textures/item/glow.png.mcmeta"));
            zo.write("{\"animation\":{\"frametime\":2}}".getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("assets/mod/textures/item/dead_strip.png"));
            zo.write(strip32);
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("assets/mod/textures/item/dead_strip.png.mcmeta"));
            zo.write("{\"animation\":{\"frametime\":6,\"frames\":[]}}".getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("assets/mod/textures/item/live_strip.png"));
            zo.write(strip48);
            zo.closeEntry();
            zo.putNextEntry(new ZipEntry("assets/mod/textures/item/live_strip.png.mcmeta"));
            zo.write("{\"animation\":{\"frametime\":2,\"frames\":[0,2]}}".getBytes(StandardCharsets.UTF_8));
            zo.closeEntry();
        }
        GalleryPack.resetJarCache();
        String animated = GalleryPack.hostSprite(List.of(animJar), "mod:item/glow", hostEntries, hostWarnings);
        expect(failures, animated.equals("c-imagine:block/items/mod_item_glow")
                && hostEntries.containsKey("assets/c-imagine/textures/block/items/mod_item_glow.png.mcmeta"),
                "gallery: a re-hosted sprite keeps its animation metadata");
        GalleryPack.hostSprite(List.of(animJar), "mod:item/dead_strip", hostEntries, hostWarnings);
        byte[] deadRaw = hostEntries.get("assets/c-imagine/textures/block/items/mod_item_dead_strip.png.mcmeta");
        String deadMeta = deadRaw == null ? null : new String(deadRaw, StandardCharsets.UTF_8);
        expect(failures, deadMeta != null && !deadMeta.contains("\"frames\""),
                "gallery: a frames list with no valid entries animates sequentially");
        GalleryPack.hostSprite(List.of(animJar), "mod:item/live_strip", hostEntries, hostWarnings);
        byte[] liveRaw = hostEntries.get("assets/c-imagine/textures/block/items/mod_item_live_strip.png.mcmeta");
        String liveMeta = liveRaw == null ? null : new String(liveRaw, StandardCharsets.UTF_8);
        expect(failures, liveMeta != null && liveMeta.contains("[0,2]"),
                "gallery: a valid frames list ships verbatim");
        GalleryPack.resetJarCache();
        Files.delete(animJar);
        JsonObject onLimit = new JsonObject();
        onLimit.add("from", JsonParser.parseString("[-16, 0, 32]"));
        onLimit.add("to", JsonParser.parseString("[-16, 4, 32]"));
        JsonObject inflated = GalleryPack.inflateDegenerate(onLimit);
        expect(failures, inflated.getAsJsonArray("from").get(0).getAsDouble() == -16
                && inflated.getAsJsonArray("to").get(0).getAsDouble() > -16
                && inflated.getAsJsonArray("to").get(2).getAsDouble() == 32
                && inflated.getAsJsonArray("from").get(2).getAsDouble() < 32,
                "gallery: a degenerate plane on the limit inflates inward only");
        unlitBlockSelftest(failures);
        rawCaptureTextureSelftest(failures);
        capturedAlphaSelftest(failures);
        capturedSpriteSelftest(failures);
        freshOutputSelftest(failures);
        exactFaceTileSelftest(failures);
        if (failures.isEmpty()) {
            System.out.println("f2v pack selftest: ALL OK");
            return true;
        }
        System.out.println("f2v pack selftest: FAILED (" + failures.size() + ")");
        for (String f : failures) {
            System.out.println("  fail: " + f);
        }
        return false;
    }

    private static void expect(List<String> failures, boolean cond, String what) {
        if (!cond) {
            failures.add(what);
        }
    }

    private static void rawCaptureTextureSelftest(List<String> failures) throws IOException {
        Path jar = Files.createTempFile("f2v-raw-binding", ".jar");
        try {
            byte[] png = f2vSelftestPng(4, 4);
            writeJar(jar, new TreeMap<>(Map.of("assets/fixture/render/gear.png", png,
                    "assets/fixture/block/custom.png", png)));
            GalleryPack.resetJarCache();
            TreeMap<String, byte[]> entries = new TreeMap<>();
            List<String> warnings = new ArrayList<>();
            for (String path : List.of("render/gear.png", "block/custom.png")) {
                String ref = GalleryPack.hostSprite(List.of(jar), "@fixture:" + path, entries, warnings);
                String hostedPng = "assets/c-imagine/textures/" + ref.split(":", 2)[1] + ".png";
                expect(failures, ref.startsWith("c-imagine:block/items/raw_")
                                && java.util.Arrays.equals(png, entries.get(hostedPng)),
                        "raw capture: exact resource bytes re-hosted on blocks atlas: " + path);
            }
            expect(failures, warnings.isEmpty(), "raw capture: no missing-texture fallback");
        } finally {
            GalleryPack.resetJarCache();
            Files.deleteIfExists(jar);
        }
    }

    private static void capturedAlphaSelftest(List<String> failures) throws IOException {
        Path root = Files.createTempDirectory("f2v-captured-alpha");
        try {
            var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) image.setRGB(x, y, 0x80ABCDEF);
            }
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            Path jar = root.resolve("fixture.jar");
            writeJar(jar, new TreeMap<>(Map.of("assets/fixture/textures/block/glass.png", png.toByteArray())));
            String face = "fixture:block/glass\t1\t-1"
                    + "\t0\t0\t0\t0\t0\t16\t0\t0\t16\t0"
                    + "\t16\t16\t0\t16\t16\t0\t16\t0\t0\t16";
            String rgb = "\t1\t1\t1".repeat(4);
            String[] rows = {face, face + rgb, face + rgb + "\t0.3".repeat(4)};
            int[] expected = {128, 128, 38};
            for (int i = 0; i < rows.length; i++) {
                ObjMesh.resetBaker();
                GalleryPack.resetJarCache();
                GalleryPack.setQuadDir(root);
                Files.writeString(root.resolve("items.tsv"), "fixture:item\talpha\n");
                Files.writeString(root.resolve("alpha.tsv"), rows[i] + "\n");
                TreeMap<String, byte[]> entries = new TreeMap<>();
                String ref = GalleryPack.synthesizeItemQuads(List.of(jar), "fixture:item", 0,
                        entries, new ArrayList<>());
                expect(failures, ref != null, "alpha capture: fixture resolves " + i);
                ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, entries);
                JsonObject model = JsonParser.parseString(new String(entries.get(
                        "assets/c-imagine/models/items/i0.json"), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject f = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                        .getAsJsonObject("faces").entrySet().iterator().next().getValue().getAsJsonObject();
                String texture = model.getAsJsonObject("textures")
                        .get(f.get("texture").getAsString().substring(1)).getAsString();
                var sheet = decodePng(entries.get("assets/" + texture.replace(":", "/textures/") + ".png"));
                JsonArray uv = f.getAsJsonArray("uv");
                int x = (int) ((uv.get(0).getAsDouble() + uv.get(2).getAsDouble()) / 32 * sheet.getWidth());
                int y = (int) ((uv.get(1).getAsDouble() + uv.get(3).getAsDouble()) / 32 * sheet.getHeight());
                int pixel = sheet.getRGB(x, y);
                expect(failures, pixel >>> 24 == expected[i],
                        "alpha capture: legacy opacity or texture-times-vertex opacity " + i);
                expect(failures, (pixel & 0xFFFFFF) == 0xABCDEF,
                        "alpha capture: RGB stays unpremultiplied " + i);
            }
        } finally {
            GalleryPack.setQuadDir(null);
            GalleryPack.resetJarCache();
            ObjMesh.resetBaker();
            deleteRecursively(root);
        }
    }

    private static void capturedSpriteSelftest(List<String> failures) throws IOException {
        Path root = Files.createTempDirectory("f2v-captured-sprite");
        String hash = "0123456789abcdef";
        try {
            Path textures = Files.createDirectory(root.resolve("textures"));
            byte[] png = f2vSelftestPng(2, 4);
            Files.write(textures.resolve(hash + ".png"), png);
            Files.writeString(textures.resolve(hash + ".png.mcmeta"),
                    "{\"animation\":{\"width\":2,\"height\":2,\"frametime\":3,\"frames\":[1,0]}}");
            GalleryPack.setQuadDir(root);
            TreeMap<String, byte[]> entries = new TreeMap<>();
            List<String> warnings = new ArrayList<>();
            String ref = GalleryPack.hostSprite(List.of(), "~" + hash, entries, warnings);
            String hosted = "assets/c-imagine/textures/block/items/captured_" + hash + ".png";
            expect(failures, ref.equals("c-imagine:block/items/captured_" + hash)
                            && java.util.Arrays.equals(png, entries.get(hosted)),
                    "captured sprite: generated pixels re-host without any source jar");
            String meta = new String(entries.get(hosted + ".mcmeta"), StandardCharsets.UTF_8);
            expect(failures, meta.contains("[1,0]") && meta.contains("\"frametime\":3"),
                    "captured sprite: animation order and timing survive");
            expect(failures, warnings.isEmpty(), "captured sprite: no missing-texture fallback");
            for (String missing : List.of("~fedcba9876543210", "~../../outside")) {
                boolean refused = false;
                try {
                    GalleryPack.hostSprite(List.of(), missing, entries, warnings);
                } catch (IOException expected) {
                    refused = true;
                }
                expect(failures, refused, "captured sprite: absent/invalid artifact fails: " + missing);
            }
        } finally {
            GalleryPack.setQuadDir(null);
            deleteRecursively(root);
        }
    }

    private static void unlitBlockSelftest(List<String> failures) throws IOException {
        unlitPlaneSelftest(failures);
        Path quads = Files.createTempDirectory("f2v-unlit-selftest");
        try {
            String face = "minecraft:block/stone\t0\t-1"
                    + "\t0\t0\t0\t0\t16\t1\t0\t0\t16\t16"
                    + "\t1\t1\t0\t16\t0\t0\t1\t0\t0\t0\n";
            Path scene = quads.resolve("gallery-item-fixture-p0.tsv");
            Files.writeString(scene, "cell\tx\ty\tz\titem\tdyn\tnote\n"
                    + "skip\t-1\t-60\t9\tfixture:missing\t0\tnochannel\n"
                    + "cell\t-5\t-60\t9\tfixture:first\t0\tok\n"
                    + "cell\t-4\t-60\t9\tfixture:second\t0\tok\n");
            expect(failures, "fixture:first".equals(GalleryPack.firstSceneItem(scene)),
                    "scene: held replay selects the first real item, not header or skip");
            expect(failures, GalleryPack.readSceneLines(scene).size() == 3
                            && GalleryPack.readSceneLines(scene).getFirst().startsWith("skip\t"),
                    "scene: schema header is removed; every data/skip row remains");
            // One unlit and one ordinary source face must remain distinct.
            Files.writeString(quads.resolve("fixture.tsv"), face + face.replace("\t0\t-1", "\t1\t-1"));
            Files.writeString(quads.resolve("items.tsv"), "fixture:item\tfixture\n");
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(quads);
            TreeMap<String, byte[]> entries = new TreeMap<>();
            List<String> warnings = new ArrayList<>();
            List<GalleryPack.Resolved> rows = GalleryPack.build(List.of(),
                    List.of(new GalleryPack.Input("fixture:block#qfixture")), entries, warnings,
                    Map.of(), new ArrayList<>());
            expect(failures, rows.size() == 1, "unlit: block capture resolves");
            if (!rows.isEmpty()) {
                String ref = rows.getFirst().modelRef();
                JsonObject node = GalleryPack.itemNode(ref);
                expect(failures, node != null && node.get("type").getAsString().equals("minecraft:composite")
                                && node.getAsJsonArray("models").size() == 2,
                        "unlit: explicit false block face gets its layer; shaded face retained");
                String dispatch = itemDef("paper", List.of(Map.entry(100000f, ref)));
                expect(failures, dispatch.contains("minecraft:composite") && dispatch.contains("transformation"),
                        "unlit: range dispatch carries the native composite and matrix");
                String itemRef = GalleryPack.synthesizeItemQuads(List.of(), "fixture:item", 0, entries, warnings);
                expect(failures, itemRef != null && GalleryPack.itemNode(itemRef) == null,
                        "unlit: identical ITEM capture retains native item lighting");
                GalleryPack.resetModelScales();
                expect(failures, GalleryPack.itemNode(ref) == null,
                        "unlit: repeated builds cannot inherit previous item layers");
            }
            // A non-planar first quad splits in two. Its output count must
            // never shift the second quad's explicit shade:false metadata.
            String nonplanar = face.replace("\t0\t-1", "\t1\t-1")
                    .replace("\t0\t1\t0\t0\t0\n", "\t0\t1\t0.5\t0\t0\n");
            Files.writeString(quads.resolve("fixture.tsv"), nonplanar + face);
            entries.clear();
            entries.put("assets/minecraft/textures/block/stone.png", f2vSelftestPng(2, 2));
            GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:block#qfixture")),
                    entries, warnings, Map.of(), new ArrayList<>());
            JsonArray split = JsonParser.parseString(new String(entries.get(
                    "assets/c-imagine/models/gallery/g0.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("elements");
            expect(failures, split.size() == 3
                            && !split.get(0).getAsJsonObject().has("shade")
                            && !split.get(1).getAsJsonObject().has("shade")
                            && !split.get(2).getAsJsonObject().get("shade").getAsBoolean(),
                    "unlit: split source pieces retain flags without shifting subsequent faces");
        } finally {
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(null);
            deleteRecursively(quads);
        }
    }

    private static void exactFaceTileSelftest(List<String> failures) throws IOException {
        Path jar = Files.createTempFile("f2v-face-tile", ".jar");
        ObjMesh.resetBaker();
        GalleryPack.resetJarCache();
        try {
            var source = new java.awt.image.BufferedImage(16, 16,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) source.setRGB(x, y, 0xFF000080 | x << 20 | y << 12);
            }
            var bytes = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(source, "png", bytes);
            TreeMap<String, byte[]> files = new TreeMap<>();
            files.put("assets/ae2/textures/block/chiseled_quartz_test.png", bytes.toByteArray());
            files.put("assets/ae2/blockstates/tile.json", ("{\"variants\":{\"\":{"
                    + "\"model\":\"ae2:block/tile\"}}}").getBytes(StandardCharsets.UTF_8));
            double[][] windows = {{2,4,14,12}, {14,4,2,12}, {2,12,14,4}, {14,12,2,4}};
            JsonArray elements = new JsonArray();
            for (double[] window : windows) {
                JsonObject element = JsonParser.parseString("{\"from\":[0,0,0],\"to\":[16,16,16],"
                        + "\"faces\":{\"south\":{\"texture\":\"#all\",\"rotation\":90}}}")
                        .getAsJsonObject();
                JsonArray uv = new JsonArray();
                for (double value : window) uv.add(value);
                element.getAsJsonObject("faces").getAsJsonObject("south").add("uv", uv);
                elements.add(element);
            }
            JsonObject model = JsonParser.parseString("{\"textures\":{\"all\":"
                    + "\"ae2:block/chiseled_quartz_test\"}}").getAsJsonObject();
            model.add("elements", elements);
            files.put("assets/ae2/models/block/tile.json", model.toString().getBytes(StandardCharsets.UTF_8));
            writeJar(jar, files);
            ObjMesh.placeExactTile(new java.awt.image.BufferedImage(7, 9,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB)); // occupy an earlier tile
            TreeMap<String, byte[]> entries = new TreeMap<>();
            var built = GalleryPack.build(List.of(jar), List.of(new GalleryPack.Input("ae2:tile")),
                    entries, new ArrayList<>(), Map.of(), null);
            expect(failures, built.size() == 1, "tile: composite fixture resolves");
            ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, entries);
            JsonObject output = JsonParser.parseString(new String(
                    entries.get("assets/c-imagine/models/gallery/g0.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (int i = 0; i < windows.length; i++) {
                JsonObject face = output.getAsJsonArray("elements").get(i).getAsJsonObject()
                        .getAsJsonObject("faces").getAsJsonObject("south");
                JsonArray uv = face.getAsJsonArray("uv");
                String texture = output.getAsJsonObject("textures")
                        .get(face.get("texture").getAsString().substring(1)).getAsString();
                var sheet = decodePng(entries.get("assets/" + texture.replace(":", "/textures/") + ".png"));
                double x0 = uv.get(0).getAsDouble() * sheet.getWidth() / 16;
                double y0 = uv.get(1).getAsDouble() * sheet.getHeight() / 16;
                double x1 = uv.get(2).getAsDouble() * sheet.getWidth() / 16;
                double y1 = uv.get(3).getAsDouble() * sheet.getHeight() / 16;
                expect(failures, Math.abs(x1 - x0) == 12 && Math.abs(y1 - y0) == 8,
                        "tile: face samples allocated window instead of entire sheet " + i);
                boolean pixelsMatch = true;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 12; x++) {
                        int sx = (int) Math.floor(x0 + (x + .5) / 12 * (x1 - x0));
                        int sy = (int) Math.floor(y0 + (y + .5) / 8 * (y1 - y0));
                        int tx = windows[i][0] < windows[i][2] ? 2 + x : 13 - x;
                        int ty = windows[i][1] < windows[i][3] ? 4 + y : 11 - y;
                        pixelsMatch &= sheet.getRGB(sx, sy) == source.getRGB(tx, ty);
                    }
                }
                expect(failures, pixelsMatch, "tile: source texels and mirrored orientation preserved " + i);
                expect(failures, face.get("rotation").getAsInt() == 90, "tile: face rotation preserved");
            }
        } finally {
            ObjMesh.resetBaker();
            GalleryPack.resetJarCache();
            Files.deleteIfExists(jar);
        }
    }

    private static void freshOutputSelftest(List<String> failures) throws IOException {
        Path root = Files.createTempDirectory("f2v-fresh-selftest");
        try {
            Path mods = Files.createDirectory(root.resolve("mods"));
            TreeMap<String, byte[]> files = new TreeMap<>();
            files.put("assets/fixture/textures/fluid/water_still.png", f2vSelftestPng(2, 2));
            writeJar(mods.resolve("fluid.jar"), files);
            Path out = root.resolve("missing/pack");
            build(mods, out, List.of(), List.of(), List.of(), List.of(), List.of());
            expect(failures, Files.isRegularFile(out.resolve("visual-gallery-fluids.tsv")),
                    "pack: fluid manifest can be emitted into a fresh nested output directory");
            // 14-cd: the widened scan (fluid/ + block/*_still + liquid/) feeds
            // the cube channel — the fixture's water_still must land BOTH the
            // flat row and a 14/16 cube row in its own manifest
            expect(failures, Files.isRegularFile(out.resolve("visual-fluid-cubes.tsv")),
                    "pack: fluid cube manifest emitted beside the flat one");
            java.util.Map<String, Float> selfCubes =
                    readFluidManifest(out.resolve("visual-fluid-cubes.tsv"));
            expect(failures, selfCubes.containsKey("fixture:water"),
                    "pack: fixture water_still derives the fixture:water cube row");
            // Native FileToIdConverter adds models/ and textures/ itself.
            // Verify the references consumed by the client, not just manifests.
            try (ZipFile pack = new ZipFile(out.resolve("pack.zip").toFile())) {
                JsonArray dispatch = JsonParser.parseString(new String(pack.getInputStream(
                        pack.getEntry("assets/minecraft/items/paper.json")).readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("model")
                        .getAsJsonArray("entries");
                int fluidModels = 0;
                for (var entry : dispatch) {
                    JsonObject row = entry.getAsJsonObject();
                    float threshold = row.get("threshold").getAsFloat();
                    if (threshold < 400000 || threshold >= 420000) continue;
                    fluidModels++;
                    String ref = row.getAsJsonObject("model").get("model").getAsString();
                    String modelPath = "assets/" + ref.replace(":", "/models/") + ".json";
                    ZipEntry model = pack.getEntry(modelPath);
                    expect(failures, model != null, "pack: fluid dispatch resolves " + ref);
                    if (model == null) continue;
                    JsonObject textures = JsonParser.parseString(new String(
                            pack.getInputStream(model).readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject().getAsJsonObject("textures");
                    for (var texture : textures.entrySet()) {
                        String sprite = texture.getValue().getAsString();
                        String spritePath = "assets/" + sprite.replace(":", "/textures/") + ".png";
                        expect(failures, pack.getEntry(spritePath) != null,
                                "pack: fluid sprite resolves " + sprite);
                    }
                }
                expect(failures, fluidModels == 2, "pack: flat and cube fluid models exercised");
            }
            Files.createDirectories(out.resolve("overlay/assets/fixture"));
            Files.writeString(out.resolve("overlay/assets/fixture/evidence.txt"), "overlay input");
            Files.createDirectory(out.resolve("quads"));
            Files.writeString(out.resolve("quads/fixture.tsv"), "minecraft:block/stone\t0\t-1"
                    + "\t0\t0\t0\t0\t16\t1\t0\t0\t16\t16"
                    + "\t1\t1\t0\t16\t0\t0\t1\t0\t0\t0\n");
            Files.writeString(out.resolve("visual-gallery.tsv"), "fixture:block#qfixture\n");
            Result original = build(mods, out, List.of(), List.of(), List.of(), List.of(), List.of());
            byte[] stored = Files.readAllBytes(out.resolve("pack.zip"));
            Result verified = rebuildForVerification(mods, out, List.of());
            expect(failures, original.sha1().equals(verified.sha1()),
                    "verification: captured quads and overlay participate in the same deterministic build");
            expect(failures, java.util.Arrays.equals(stored, Files.readAllBytes(out.resolve("pack.zip"))),
                    "verification: served pack bytes are untouched");
            try (var paths = Files.list(out)) {
                expect(failures, paths.noneMatch(p -> p.getFileName().toString().startsWith("verify-")),
                        "verification: scratch input/output tree is cleaned");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    private static void unlitPlaneSelftest(List<String> failures) {
        java.util.Random random = new java.util.Random(9);
        double maxCorner = 0;
        double maxNormal = 0;
        for (int i = 0; i < 256; i++) {
            JsonObject source = JsonParser.parseString("{\"from\":[2,3,4],\"to\":[14,15,4.02],"
                    + "\"shade\":false,\"faces\":{\"south\":{\"texture\":\"#q0\",\"uv\":[0,0,16,16]}},"
                    + "\"rotation\":{\"origin\":[8,8,8],\"x\":0,\"y\":0,\"z\":0}}")
                    .getAsJsonObject();
            JsonObject rotation = source.getAsJsonObject("rotation");
            double[] angles = {random.nextDouble() * 360, random.nextDouble() * 360,
                    random.nextDouble() * 360};
            rotation.addProperty("x", angles[0]);
            rotation.addProperty("y", angles[1]);
            rotation.addProperty("z", angles[2]);
            double scale = i % 2 + 1;
            UnlitBlockModel.Plane plane = UnlitBlockModel.plane(source, scale);
            expect(failures, source.get("faces").equals(plane.element().get("faces")),
                    "unlit: source UVs/material unchanged");
            JsonObject baked = plane.element().getAsJsonObject("rotation");
            double[][] before = ObjMesh.columnsOf(angles[0], angles[1], angles[2]);
            double[][] after = ObjMesh.columnsOf(baked.get("x").getAsDouble(),
                    baked.get("y").getAsDouble(), baked.get("z").getAsDouble());
            expect(failures, after[2][2] >= .86, "unlit: baked normal remains cardinal south");
            double[] pivot = {8.0 / 16, 9.0 / 16, 4.02 / 16};
            JsonArray matrix = plane.transformation();
            for (double x : new double[]{2.0 / 16, 14.0 / 16}) {
                for (double y : new double[]{3.0 / 16, 15.0 / 16}) {
                    double[] point = {x, y, 4.02 / 16};
                    double[] want = {.5, .5, .5};
                    double[] pre = pivot.clone();
                    for (int r = 0; r < 3; r++) {
                        for (int c = 0; c < 3; c++) {
                            want[r] += scale * before[c][r] * (point[c] - .5);
                            pre[r] += after[c][r] * (point[c] - pivot[c]);
                        }
                    }
                    for (int r = 0; r < 3; r++) {
                        double value = matrix.get(r * 4 + 3).getAsDouble();
                        for (int c = 0; c < 3; c++) {
                            value += matrix.get(r * 4 + c).getAsDouble() * pre[c];
                        }
                        maxCorner = Math.max(maxCorner, Math.abs(value - want[r]));
                    }
                }
            }
            // Inverse-transpose Z is the cross of the first two columns,
            // divided by the determinant. Check the sign as well as direction.
            double[] normal = new double[3];
            for (int r = 0; r < 3; r++) {
                int a = (r + 1) % 3;
                int b = (r + 2) % 3;
                normal[r] = matrix.get(a * 4).getAsDouble() * matrix.get(b * 4 + 1).getAsDouble()
                        - matrix.get(b * 4).getAsDouble() * matrix.get(a * 4 + 1).getAsDouble();
            }
            double det = 0;
            double length = 0;
            for (int r = 0; r < 3; r++) {
                det += normal[r] * matrix.get(r * 4 + 2).getAsDouble();
                length += normal[r] * normal[r];
            }
            double[] light = {.2, 1, -.7};
            for (int r = 0; r < 3; r++) {
                maxNormal = Math.max(maxNormal, Math.abs(normal[r] / Math.sqrt(length)
                        * Math.signum(det) - light[r] / Math.sqrt(1.53)));
            }
        }
        expect(failures, maxCorner < 1e-9, "unlit: arbitrary planes/oversized scale preserve all corners");
        expect(failures, maxNormal < 1e-9, "unlit: native layer normal preserves fully lit source pass");
    }

    /** A real w x h PNG (opaque, one gray shade) — the animation sanitizer reads IHDR. */
    private static byte[] f2vSelftestPng(int w, int h) throws IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, ((x + y) & 1) == 0 ? 0xFF404040 : 0xFF808080);
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static String str(TreeMap<String, byte[]> entries, String key) {
        byte[] b = entries.get(key);
        return b == null ? "" : new String(b, StandardCharsets.UTF_8);
    }

    private static TreeMap<String, byte[]> readZip(Path zip) throws IOException {
        TreeMap<String, byte[]> out = new TreeMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                try (InputStream is = zf.getInputStream(e)) {
                    out.put(e.getName(), is.readAllBytes());
                }
            }
        }
        return out;
    }

    private static void writeJar(Path jar, TreeMap<String, byte[]> entries)
            throws IOException {
        try (OutputStream fos = Files.newOutputStream(jar);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    /** A tiny valid PNG via ImageIO (headless-safe) for texture fixtures. */
    private static byte[] testPng() throws IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                img.setRGB(x, y, (((x * 31 + y * 17) & 0xFF) << 24) | 0x4080C0);
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** TINT-BAKE fixture texture: 4x2 ARGB with per-pixel KNOWN values —
     *  exact multiply, clamped multiply, half-up rounding (127.5 -> 128),
     *  alpha-0 preservation, and the 0/255 gamut ends. */
    private static byte[] testGreyPng() throws IOException {
        int[][] px = {
                {200, 100, 50, 255}, {140, 140, 140, 255},
                {200, 10, 10, 255}, {10, 20, 30, 0},
                {255, 255, 255, 255}, {0, 0, 0, 255},
                {64, 128, 192, 255}, {1, 2, 3, 255}};
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(4, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 4; x++) {
                int[] p = px[y * 4 + x];
                img.setRGB(x, y, (p[3] << 24) | (p[0] << 16) | (p[1] << 8) | p[2]);
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** Decode a PNG byte array to ARGB pixels (selftest pixel asserts). */
    private static java.awt.image.BufferedImage decodePng(byte[] png) throws IOException {
        return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var s = Files.list(p)) {
                for (Path c : s.toList()) {
                    deleteRecursively(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}
