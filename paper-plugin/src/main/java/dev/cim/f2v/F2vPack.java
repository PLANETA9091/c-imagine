package dev.cim.f2v;

import com.google.gson.GsonBuilder;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 *   <li>referenced textures outside {@code textures/item|block/} use explicit
 *       single sources appended to the block and item atlas stacks.</li>
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

    /**
     * The item channel's tally for the build log: manifest rows that resolved to a model,
     * mod items allocated a definition of their own (no manifest row), and the candidates
     * or rows that found no model in any jar.
     */
    public record ItemTally(int manifest, int allocated, int failed) {
        public int resolved() {
            return manifest + allocated;
        }
    }

    /** Build outcome: resolved rows, sha1 hex, emitted entry count, warnings, item tally. */
    public record Result(List<VisualItem> items, List<VisualBlock> blocks,
                         String sha1, int entryCount, List<String> warnings,
                         List<GalleryPack.Resolved> gallery,
                         List<String[]> placeholders, ItemTally tally) {
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
                    boolean animatedStrip = false;
                    int frames = 1;
                    // 14-dz2 THE FLUID ANIMATED RECEIPT: the still strips are
                    // animated (the 14-cf crop ships frame 0) and the fluid
                    // joins read the animation tsv (the 14-dx-f law) — but
                    // the fluid scan never recorded, so the bop/create/
                    // mekanism free-running-clock reds (liquid_null 439702
                    // on run 43395239790: above the gate this boot, 166594
                    // below it the boot before — the frame phase, not
                    // fidelity) stayed gated. A sibling .mcmeta IS the
                    // animation proof (zero pixel heuristics); the frame
                    // count rides the strip's own layout (16px frames).
                    try {
                        if (zf.getEntry(n + ".mcmeta") != null) {
                            java.awt.image.BufferedImage strip = javax.imageio.ImageIO.read(
                                    new java.io.ByteArrayInputStream(zf.getInputStream(e).readAllBytes()));
                            if (strip != null) {
                                int across = strip.getWidth() / 16;
                                int down = strip.getHeight() / 16;
                                frames = Math.max(across, down);
                                animatedStrip = frames >= 2;
                            }
                        }
                    } catch (Exception badStrip) {
                        animatedStrip = false; // an unreadable strip keeps the silent crop
                    }
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
                    if (animatedStrip) {
                        String fluidId = fluidIdFromTexture(
                                ns, file.substring(file.lastIndexOf('/') + 1));
                        GalleryPack.recordAnimatedState(fluidId, frames);
                        // 14-e116 THE FLOWING-VARIANT LAW: the join's manifest
                        // rows carry the REGISTRY state id — the flowing
                        // variants read "flowing_" + the still's name
                        // (mekanism:flowing_sulfur_dioxide vs the recorded
                        // "mekanism:sulfur_dioxide"), so the 14-e16 bare-id
                        // match missed the whole flowing family and the
                        // flowing rows red-gated forever (the 525 read:
                        // 24 flowing REDs, ae 603K-930K, while every still
                        // row demoted to ANIMATED — the same free-running
                        // clock, the 14-bw law "animated by construction").
                        // The alias rides the SAME mcmeta proof (the fluid's
                        // animation exists; the flowing variant samples the
                        // same per-tick clock), no pixel heuristics; where a
                        // dedicated _flow strip is scanned, its own frame
                        // count merges max over this proxy.
                        String base = fluidId.substring(fluidId.indexOf(':') + 1);
                        GalleryPack.recordAnimatedState(ns + ":flowing_" + base, frames);
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
                                                List<String> warnings,
                                                java.nio.file.Path sampledFaces,
                                                java.util.Map<String, String> sampledIndex) throws IOException {
        List<FluidCube> out = new ArrayList<>();
        int i = 0;
        for (String fluidId : new java.util.TreeSet<>(fluidIds)) {
            String texPath;
            String texRef = "c-imagine:fluidcube/"
                    + fluidId.replace(':', '_');
            int[] color = colorTable.get(fluidId);
            // 14-ea THE SAMPLED FACE: when the truth-measured surface sample
            // exists (fluidfaces/<id>.png, written by fluid-face-sample.py
            // from the truth leg's own fluid page shots), the cube's
            // south/north faces draw it — the truth renders the real fluid
            // through the translucent pipeline (the backdrop shows through)
            // and the opaque tinted canvas cannot reproduce that residual;
            // the head-on camera makes the south face L1-optimal by
            // construction. The remaining faces keep the shaded canvas.
            // A sampled face ALSO rescues a fluid whose still texture the
            // jar scan cannot host (supplementaries' code-tinted lumisene
            // rendered the ours cell EMPTY — the 723K ae red): the sampled
            // surface becomes the cube's whole texture set.
            // 14-ea THE SAMPLED FACE: when the truth-measured surface sample
            // exists (fluidfaces/<id>.png, written by fluid-face-sample.py
            // from the truth leg's own fluid page shots), the cube's
            // south/north faces draw it - the truth renders the real fluid
            // through the translucent pipeline (the backdrop shows through)
            // and the opaque tinted canvas cannot reproduce that residual;
            // the head-on camera makes the south face L1-optimal by
            // construction. The remaining faces keep the shaded canvas.
            // A sampled face ALSO rescues a fluid whose still texture the
            // jar scan cannot host (supplementaries' code-tinted lumisene
            // rendered the ours cell EMPTY - the 723K ae red): the sampled
            // surface becomes the cube's whole texture set.
            String faceTexRef = null;
            byte[] faceBytes = null;
            String facePath = null;
            String faceRel = sampledIndex.get(fluidId);
            if (faceRel != null) {
                // the index paths are relative to the OUT dir (the f2v dir),
                // not to the fluidfaces subdir
                java.nio.file.Path fp = sampledFaces.getParent() == null
                        ? sampledFaces.resolve(faceRel)
                        : sampledFaces.getParent().resolve(faceRel);
                if (java.nio.file.Files.isRegularFile(fp)) {
                    facePath = "assets/c-imagine/textures/fluidcube/"
                            + fluidId.replace(':', '_') + "_face.png";
                    faceBytes = java.nio.file.Files.readAllBytes(fp);
                    faceTexRef = "c-imagine:fluidcube/"
                            + fluidId.replace(':', '_') + "_face";
                }
            }
            if (color == null) {
                // the real still texture's static frame 0, re-hosted under
                // the cube namespace (the frame extraction keeps the cube
                // deterministic - the strip's later frames never enter)
                byte[] frame = stillFrameZero(entries, fluidId);
                if (frame == null) {
                    if (faceBytes == null) {
                        continue;
                    }
                    // no hostable still canvas: the sampled face IS the
                    // cube's texture (all faces draw it; the head-on crop
                    // sees only the south face anyway)
                    texPath = facePath;
                    entries.put(texPath, faceBytes);
                    texRef = faceTexRef;
                } else {
                    texPath = "assets/c-imagine/textures/fluidcube/"
                            + fluidId.replace(':', '_') + ".png";
                    entries.put(texPath, frame);
                }
            } else {
                // 14-ch THE SHADED SURFACE SET: the truth's liquid renders
                // through the chunk pipeline's DIRECTIONAL face shading (the
                // top 1.0, the north/south 0.8, the east/west 0.6, the
                // bottom 0.5) while the item_display pipeline renders the
                // model FLAT - the #250 measured the unshaded cube at ae
                // 246-421K against the 240K gate with IDENTICAL ae on the
                // run before the gate flip (the structure, not the pixels).
                // The shading bakes into four per-face textures; the model
                // picks them per face and the fit absorbs the rest.
                // 14-dm THE CANVAS SURFACE: the uniform flat fill measured
                // 249-484K against the pinned truth (the #310 chemical
                // giants' self_ae measured 0.6-4K - the truth surface is
                // STATIC once the 14-dl pin holds, and the flat fill's
                // residual is the canvas's spatial detail, not phase). The
                // mod's still canvas is hosted frame-0 under its own sprite
                // path (mekanism ships textures/liquid/liquid.png for the
                // chemicals, steam.png for steam; create/bop ship
                // _still.png), so the cube texture becomes the REAL canvas
                // pixels scaled per channel by (calibrated color / canvas
                // mean) - the L1-optimal tint is preserved as the scale, the
                // spatial detail survives, and truth (pinned frame 0 of the
                // same canvas, tinted by the mod's code) meets ours at the
                // pipeline gap. The uniform fill stays the fallback when no
                // canvas is hosted.
                String id = fluidId.replace(':', '_');
                byte[] canvas = canvasFrameZero(entries, fluidId);
                texPath = "assets/c-imagine/textures/fluidcube/" + id + ".png";
                if (!entries.containsKey(texPath)) {
                    entries.put(texPath, canvas != null
                            ? tintedCanvasPng(canvas, color)
                            : uniformPng(color[0], color[1], color[2]));
                }
                String nsPath = "assets/c-imagine/textures/fluidcube/" + id + "_ns.png";
                if (!entries.containsKey(nsPath)) {
                    int[] ns = shade(color, 0.8);
                    entries.put(nsPath, canvas != null
                            ? tintedCanvasPng(canvas, ns)
                            : uniformPng(ns[0], ns[1], ns[2]));
                }
                String ewPath = "assets/c-imagine/textures/fluidcube/" + id + "_ew.png";
                if (!entries.containsKey(ewPath)) {
                    int[] ew = shade(color, 0.6);
                    entries.put(ewPath, canvas != null
                            ? tintedCanvasPng(canvas, ew)
                            : uniformPng(ew[0], ew[1], ew[2]));
                }
                String bPath = "assets/c-imagine/textures/fluidcube/" + id + "_b.png";
                if (!entries.containsKey(bPath)) {
                    int[] bb = shade(color, 0.5);
                    entries.put(bPath, canvas != null
                            ? tintedCanvasPng(canvas, bb)
                            : uniformPng(bb[0], bb[1], bb[2]));
                }
            }
            if (faceTexRef != null) {
                entries.put(facePath, faceBytes);
            }
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
            if (faceTexRef != null) {
                textures += ", \"face\": \"" + faceTexRef + "\"";
            }
            String sideTex = color != null ? "#ns" : "#all";
            String ewTex = color != null ? "#ew" : "#all";
            String downTex = color != null ? "#b" : "#all";
            String frontTex = faceTexRef != null ? "#face" : sideTex;
            String faces = "\"north\": {\"texture\": \"" + frontTex + "\", \"uv\": [0, 2, 16, 16]},"
                    + "\"south\": {\"texture\": \"" + frontTex + "\", \"uv\": [0, 2, 16, 16]},"
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

    /** The still canvas for a color-route fluid id: the direct-name lookups
     *  (the three scan conventions) first, then the mod's GENERIC liquid
     *  canvas — mekanism ships one shared textures/liquid/liquid.png that
     *  its chemical fluids render tinted by code (the 14-cd derivation's
     *  "generic gray canvas"; steam rides its own steam.png via the direct
     *  lookup). Null when neither exists. */
    private static byte[] canvasFrameZero(TreeMap<String, byte[]> entries,
                                          String fluidId) {
        byte[] direct = stillFrameZero(entries, fluidId);
        if (direct != null) {
            return direct;
        }
        int colon = fluidId.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String ns = fluidId.substring(0, colon);
        return entries.get("assets/" + ns + "/textures/liquid/liquid.png");
    }

    /** The canvas surface scaled per channel by (target color / canvas
     *  mean): the 14-dm chemical cube texture. The spatial detail of the
     *  real canvas survives; the calibrated L1-optimal tint rides as the
     *  scale so the measured calibration (the 14-cd derivation) is kept
     *  exactly. Alpha is preserved; unreadable PNGs fall back to the raw
     *  bytes (the loader names it). Deterministic: ImageIO's PNG encode is
     *  byte-stable for the same input pixels. */
    private static byte[] tintedCanvasPng(byte[] canvas, int[] color)
            throws IOException {
        java.awt.image.BufferedImage img;
        try {
            img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(canvas));
        } catch (Exception bad) {
            return canvas;
        }
        if (img == null) {
            return canvas;
        }
        int w = Math.min(16, img.getWidth());
        int h = Math.min(16, img.getHeight());
        double[] sum = new double[3];
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                sum[0] += (argb >> 16) & 0xFF;
                sum[1] += (argb >> 8) & 0xFF;
                sum[2] += argb & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return canvas;
        }
        double[] scale = new double[3];
        for (int c = 0; c < 3; c++) {
            double mean = sum[c] / n;
            scale[c] = mean < 1.0 ? 1.0 : color[c] / mean;
        }
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                int r = (int) Math.max(0, Math.min(255,
                        Math.round(((argb >> 16) & 0xFF) * scale[0])));
                int g = (int) Math.max(0, Math.min(255,
                        Math.round(((argb >> 8) & 0xFF) * scale[1])));
                int b = (int) Math.max(0, Math.min(255,
                        Math.round((argb & 0xFF) * scale[2])));
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "png", bos);
        return bos.toByteArray();
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

        /**
         * True for a mushroom-family carrier: {@code instrument} then names the host block
         * ({@link #MUSHROOM_HOSTS}) and {@code note} carries the six face bits (bit i =
         * {@link #MUSHROOM_FACES} i). Note-family rows (the default) keep their meaning.
         */
        public boolean isMushroom() {
            return MUSHROOM_HOSTS.contains(instrument);
        }

        /**
         * True for a display-family row: no slot at all. The block stands in Paper's world as a
         * barrier with an ItemDisplay of its model on top (see {@link #DISPLAY_FAMILY}); {@code note}
         * is unused and 0.
         */
        public boolean isDisplay() {
            return DISPLAY_FAMILY.equals(instrument);
        }

        /**
         * The carrier's allocation key: {@code harp:5} for a note slot, {@code brown_mushroom_block:101010}
         * for a mushroom slot, {@code display:<id>} for a display row (one per block, never a slot).
         */
        public String key() {
            if (isDisplay()) {
                return DISPLAY_FAMILY + ":" + moddedBlockId;
            }
            return isMushroom() ? instrument + ":" + mushroomBits(note) : instrument + ":" + note;
        }
    }

    /**
     * The display family: the unbounded overflow after the note and mushroom slots. A display block
     * is a barrier (the collision and the invisible host) with an {@code ItemDisplay} at its centre
     * whose item_model names the block's own model ({@link #displayItemModel}). Ledger and resolved
     * rows spell it {@code <id> TAB display TAB 0}; the third column is unused for this family.
     */
    public static final String DISPLAY_FAMILY = "display";
    /** The display family's host block in Paper's world: invisible, full collision, unbreakable by a survival dig (see DisplayCarriers). */
    public static final String DISPLAY_HOST = "barrier";
    /** The namespace of the display items' item_model keys: {@code c-imagine:_display/<ns>/<path>}; the underscore keeps it out of every mod's namespace. */
    static final String DISPLAY_PATH = "_display";

    /** The item_model key of a display block's item (its items/ definition in the pack). */
    public static String displayItemModel(String moddedId) {
        int colon = moddedId.indexOf(':');
        return ALLOCATED_NS + ":" + DISPLAY_PATH + "/" + moddedId.substring(0, colon) + "/" + moddedId.substring(colon + 1);
    }

    /** The mod block id a display item_model key names, or null when it is not a display key (the inverse of {@link #displayItemModel}). */
    public static String moddedIdOfDisplayItemModel(String key) {
        String prefix = ALLOCATED_NS + ":" + DISPLAY_PATH + "/";
        if (key == null || !key.startsWith(prefix)) {
            return null;
        }
        String rest = key.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null;
        }
        return rest.substring(0, slash) + ":" + rest.substring(slash + 1);
    }

    /** The items/ definition path of a display block's item. */
    static String displayItemDefPath(String moddedId) {
        return "assets/" + displayItemModel(moddedId).replaceFirst(":", "/items/") + ".json";
    }

    /**
     * The mushroom-block carrier family (overflow capacity, after the note slots). Three host
     * blocks, each a six-boolean blockstate (64 states). Vanilla's placed default (all faces
     * true) and its all-false interior are never handed out.
     */
    public static final List<String> MUSHROOM_HOSTS = List.of("brown_mushroom_block", "red_mushroom_block",
            "mushroom_stem");
    /** The six face properties, in the bit order of a mushroom slot's mask and bit string. */
    public static final List<String> MUSHROOM_FACES = List.of("north", "east", "south", "west", "up", "down");
    static final int MUSHROOM_MASK_MIN = 1;
    static final int MUSHROOM_MASK_MAX = 62;
    static final int MUSHROOM_CAPACITY = MUSHROOM_HOSTS.size() * (MUSHROOM_MASK_MAX - MUSHROOM_MASK_MIN + 1);

    /** The six bits of a mushroom mask as the blockstate string {@code 101010} (face order of {@link #MUSHROOM_FACES}). */
    public static String mushroomBits(int mask) {
        StringBuilder sb = new StringBuilder(MUSHROOM_FACES.size());
        for (int i = 0; i < MUSHROOM_FACES.size(); i++) {
            sb.append((mask >> i & 1) == 1 ? '1' : '0');
        }
        return sb.toString();
    }

    /** The mask of a six-character bit string, or -1 when it is not one. */
    static int parseMushroomBits(String bits) {
        if (bits.length() != MUSHROOM_FACES.size()) {
            return -1;
        }
        int mask = 0;
        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c != '0' && c != '1') {
                return -1;
            }
            mask |= (c - '0') << i;
        }
        return mask;
    }

    /**
     * The block state a carrier of this row is placed as in Paper's world: the note block
     * ({@code minecraft:note_block[instrument=..,note=..]}) or the mushroom host with its six
     * faces ({@code minecraft:brown_mushroom_block[down=true,east=false,...]}).
     */
    public static String carrierState(VisualBlock row) {
        if (row.isDisplay()) {
            return "minecraft:" + DISPLAY_HOST;
        }
        if (!row.isMushroom()) {
            return "minecraft:note_block[instrument=" + row.instrument() + ",note=" + row.note() + "]";
        }
        StringBuilder sb = new StringBuilder("minecraft:").append(row.instrument()).append('[');
        for (int i = 0; i < MUSHROOM_FACES.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(MUSHROOM_FACES.get(i)).append('=')
                    .append((row.note() >> i & 1) == 1 ? "true" : "false");
        }
        return sb.append(']').toString();
    }

    /**
     * The carrier key of a placed block's state string: {@code harp:5} for a note block,
     * {@code brown_mushroom_block:101010} for a mushroom host, null for anything else (or a
     * state that does not parse).
     */
    public static String carrierKeyOf(String state) {
        int open = state.indexOf('[');
        String name = open < 0 ? state : state.substring(0, open);
        name = name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
        if (name.equals("note_block")) {
            Map<String, String> props = stateProperties(state);
            return props.get("instrument") + ":" + props.get("note");
        }
        if (MUSHROOM_HOSTS.contains(name)) {
            Map<String, String> props = stateProperties(state);
            StringBuilder bits = new StringBuilder();
            for (String face : MUSHROOM_FACES) {
                String v = props.get(face);
                if (v == null) {
                    return null;
                }
                bits.append(v.equals("true") ? '1' : '0');
            }
            return name + ":" + bits;
        }
        return null;
    }

    private static Map<String, String> stateProperties(String state) {
        Map<String, String> props = new HashMap<>();
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return props;
        }
        for (String pair : state.substring(open + 1, close).split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return props;
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
        CarrierPlan plan = carrierPlan(outDir, modsDir,
                readBlockManifest(outDir.resolve("visual-blocks.tsv")), true);
        Result r = withNotes(build(modsDir, outDir, manifestRows,
                readEquipmentManifest(outDir.resolve("visual-equipment.tsv")),
                plan.effective()), plan.notes());
        writeResolvedBlocks(outDir, r.blocks());
        return r;
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
                    "visual-emissive.tsv", "visual-rings.tsv", "quads", "overlay")) {
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
            // the same carrier plan the pre-boot build persisted (read-only here)
            return build(mods, scratch, Files.isRegularFile(items) ? readManifest(items) : List.of(),
                    readEquipmentManifest(base.resolve("visual-equipment.tsv")),
                    carrierPlan(base, mods, readBlockManifest(base.resolve("visual-blocks.tsv")), false).effective(),
                    readPaletteManifest(base.resolve("visual-palettes.tsv")), assets);
        } finally {
            deleteRecursively(scratch);
        }
    }

    /**
     * Read {@code visual-blocks.tsv}: {@code moddedBlockId<TAB>instrument[
     * <TAB>note]} — one note_block stand-in slot per row, or a mushroom row
     * {@code moddedBlockId<TAB>host<TAB>NESWUD bits} (measured surface:
     * block-channel-26-3.md). Optional; missing file = no block overrides.
     * Note defaults to 13 (a deliberate non-default pick so a vanilla
     * note=0 note block elsewhere can never collide with the slot).
     */
    public static List<VisualBlock> readBlockManifest(Path tsv) throws IOException {
        if (!Files.isRegularFile(tsv)) {
            return new ArrayList<>();
        }
        return parseBlockRows(Files.readAllLines(tsv, StandardCharsets.UTF_8));
    }

    /** The rows of a block manifest or ledger text (see {@link #readBlockManifest}). */
    static List<VisualBlock> parseBlockRows(List<String> lines) {
        List<VisualBlock> rows = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            String host = parts[1].trim();
            if (DISPLAY_FAMILY.equals(host)) {
                // display family row: no slot, the third column is unused
                if (parts[0].trim().indexOf(':') > 0) {
                    rows.add(new VisualBlock(parts[0].trim(), DISPLAY_FAMILY, 0));
                }
                continue;
            }
            if (MUSHROOM_HOSTS.contains(host)) {
                // mushroom family row: the third column is the six face bits (NESWUD order)
                int mask = parts.length >= 3 ? parseMushroomBits(parts[2].trim()) : -1;
                if (mask >= MUSHROOM_MASK_MIN && mask <= MUSHROOM_MASK_MAX) {
                    rows.add(new VisualBlock(parts[0].trim(), host, mask));
                }
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
            rows.add(new VisualBlock(parts[0].trim(), host, note));
        }
        rows.sort(Comparator.comparing(VisualBlock::moddedBlockId)
                .thenComparing(VisualBlock::instrument)
                .thenComparingInt(VisualBlock::note));
        return rows;
    }

    /** Default item foil from the native capture, read by Paper when the live layer is absent. */
    public static final String RESOLVED_ITEM_FOIL = "visual-item-foil.resolved.tsv";

    public static Set<String> readItemFoil(Path file) throws IOException {
        Set<String> foil = new TreeSet<>();
        if (Files.isRegularFile(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String id = line.strip();
                if (ALLOCATABLE.matcher(id).matches() && !id.startsWith("minecraft:")) {
                    foil.add(id);
                }
            }
        }
        return foil;
    }

    // ---- allocated mod items (boot-time, no manifest row) ------------------------

    /** The item_model namespace of an allocated mod item: {@code c-imagine:<ns>/<path>}. */
    public static final String ALLOCATED_NS = "c-imagine";
    /** The allocated items the last build emitted (moddedId TAB item_model); the HUD reads them. */
    public static final String ALLOCATED_ITEMS = "visual-items.allocated.tsv";
    private static final String ALLOCATED_HEADER = "# c-imagine F2V allocated mod items (no manifest row)\n"
            + "# moddedId <TAB> item_model (c-imagine:<ns>/<path>) — written by the pack build, do not edit\n";
    /** A plain mod id: lowercase segments, no empty or dot-only segment, so the item def path stays inside its folder. */
    private static final java.util.regex.Pattern ALLOCATABLE = java.util.regex.Pattern.compile(
            "[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*:[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*(?:/[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*)*");

    /** The item_model key of an allocated mod item (its items/ definition, namespace-less path). */
    public static String allocatedItemModel(String moddedId) {
        int colon = moddedId.indexOf(':');
        return ALLOCATED_NS + ":" + moddedId.substring(0, colon) + "/" + moddedId.substring(colon + 1);
    }

    /** The mod id an item_model key names when it is an allocated key, else null (the inverse of {@link #allocatedItemModel}). */
    public static String moddedIdOfItemModel(String key) {
        String prefix = ALLOCATED_NS + ":";
        if (key == null || !key.startsWith(prefix) || key.startsWith(prefix + DISPLAY_PATH + "/")) {
            return null; // a display key is not an allocated item
        }
        String rest = key.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null;
        }
        return rest.substring(0, slash) + ":" + rest.substring(slash + 1);
    }

    /** The name a Paper stack of an allocated item shows: the id's leaf, title-cased ("brass_ingot" -> "Brass Ingot"). */
    public static String allocatedItemName(String moddedId) {
        String path = moddedId.substring(moddedId.indexOf(':') + 1);
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder sb = new StringBuilder();
        for (String word : leaf.split("[_-]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.isEmpty() ? moddedId : sb.toString();
    }

    /** The candidates: discovered mod ids that are plain, not vanilla, not ours, and not a manifest row; sorted. */
    static List<String> itemCandidates(Collection<String> discovered, Collection<String> manifestIds) {
        Set<String> manifest = new HashSet<>(manifestIds);
        TreeSet<String> out = new TreeSet<>();
        for (String id : discovered) {
            if (id.startsWith("minecraft:") || id.startsWith(ALLOCATED_NS + ":") || manifest.contains(id)) {
                continue;
            }
            if (ALLOCATABLE.matcher(id).matches()) {
                out.add(id);
            }
        }
        return new ArrayList<>(out);
    }

    /** Every mod item a jar ships an item model for ({@code assets/<ns>/models/item/<path>.json}), as {@code ns:path}. */
    static Set<String> discoverItemIds(List<Path> jars) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith("assets/") || !name.endsWith(".json")) {
                        continue;
                    }
                    int slash = name.indexOf('/', "assets/".length());
                    if (slash < 0) {
                        continue;
                    }
                    String ns = name.substring("assets/".length(), slash);
                    String prefix = "assets/" + ns + "/models/item/";
                    if (ns.equals("minecraft") || !name.startsWith(prefix)) {
                        continue;
                    }
                    String path = name.substring(prefix.length(), name.length() - ".json".length());
                    if (!path.isEmpty()) {
                        out.add(ns + ":" + path);
                    }
                }
            }
        }
        return out;
    }

    /** The items/ definition path of an allocated item: {@code assets/c-imagine/items/<ns>/<path>.json}. */
    static String allocatedItemDefPath(String moddedId) {
        return "assets/" + allocatedItemModel(moddedId).replaceFirst(":", "/items/") + ".json";
    }

    /** The items/ definition of an allocated item: the mod's own model (or its composite), as the model node. */
    static String allocatedItemDef(String ref) {
        JsonObject node = GalleryPack.itemNode(ref);
        return "{\n  \"model\": " + (node != null ? node : itemModelNode(ref)) + "\n}\n";
    }

    private static String allocatedText(List<String> ids) {
        StringBuilder sb = new StringBuilder(ALLOCATED_HEADER);
        for (String id : ids) {
            sb.append(id).append('\t').append(allocatedItemModel(id)).append('\n');
        }
        return sb.toString();
    }

    /** The allocated items the last build emitted (the HUD's stand-in for the rows it has no CMD for). */
    public static List<String> readAllocatedItems(Path f2vDir) throws IOException {
        List<String> out = new ArrayList<>();
        Path file = f2vDir.resolve(ALLOCATED_ITEMS);
        if (!Files.isRegularFile(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String id = t.split("\t", -1)[0].trim();
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    // ---- carrier allocation (boot-time fallback for every mod block) --------------

    /**
     * The 27 NoteBlockInstrument serialized names in the vanilla 26.3 enum order
     * (the 23 1.21.x instruments plus the trumpet family). The slot order is this
     * list, instrument-major, so the allocation is a pure function of the inputs.
     */
    static final List<String> CARRIER_INSTRUMENTS = List.of("harp", "basedrum", "snare", "hat", "bass",
            "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo",
            "bit", "banjo", "pling", "trumpet", "trumpet_exposed", "trumpet_oxidized", "trumpet_weathered",
            "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head");
    /**
     * Note 0 is vanilla's placed default: never handed out, so a freshly placed vanilla
     * note block can never read as a carrier. The pack variant key pins instrument +
     * note and leaves powered a wildcard, so 27 x 24 = 648 slots is the render space.
     */
    static final int CARRIER_NOTE_MIN = 1;
    static final int CARRIER_NOTE_MAX = 24;
    /** The allocation ledger: every auto-allocated carrier, kept across restarts. */
    static final String CARRIER_LEDGER = "carriers.tsv";
    /** The rows the last build used (manifest + allocation); the HUD reads these. */
    static final String RESOLVED_BLOCKS = "visual-blocks.resolved.tsv";
    static final int CARRIER_CAPACITY = CARRIER_INSTRUMENTS.size()
            * (CARRIER_NOTE_MAX - CARRIER_NOTE_MIN + 1);
    private static final String LEDGER_HEADER = "# c-imagine F2V carrier ledger (auto-allocated; never reassigned)\n"
            + "# modded_block_id <TAB> instrument <TAB> note — written by the pack build, do not edit\n";
    private static final String RESOLVED_HEADER = "# c-imagine F2V resolved block carriers (manifest + allocation)\n"
            + "# written by the pack build; the HUD and the pack read the same rows\n";

    /** The planner's output: the rows the pack emits, the ledger to keep, notes for the build log. */
    public record CarrierPlan(List<VisualBlock> effective, List<VisualBlock> ledger, List<String> notes) {
    }

    /**
     * Pure carrier planner. The manifest is authoritative: its rows keep their slots and
     * block their ids from auto allocation. A ledger row keeps its slot across restarts;
     * it only moves when a manifest row now holds that slot (logged). Every other
     * discovered mod block takes the lowest free slot in sorted id order; once the slot
     * families are full it joins the display family (unbounded, see {@link #DISPLAY_FAMILY}).
     * A display row never moves (it holds no slot). Ledger rows
     * of blocks no jar carries any more are reserved, never reissued, and not emitted.
     */
    static CarrierPlan planCarriers(Collection<String> discovered, List<VisualBlock> manifest,
                                    List<VisualBlock> ledgerRows) {
        return planCarriers(discovered, manifest, ledgerRows, false);
    }

    /**
     * The planner with the mushroom family switched on or off. Ledger rows are honoured either
     * way (a slot once issued is never taken back); the switch only gates NEW mushroom slots,
     * which open after the note slots are full.
     */
    static CarrierPlan planCarriers(Collection<String> discovered, List<VisualBlock> manifest,
                                    List<VisualBlock> ledgerRows, boolean mushroom) {
        final int capacity = mushroom ? CARRIER_CAPACITY + MUSHROOM_CAPACITY : CARRIER_CAPACITY;
        Set<String> found = new TreeSet<>(discovered);
        List<String> notes = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<String> placed = new HashSet<>();
        Set<String> manifestIds = new HashSet<>();
        List<VisualBlock> effective = new ArrayList<>();
        for (VisualBlock row : manifest) {
            if (row.isDisplay()) {
                notes.add("carrier manifest: display rows are automatic, dropped " + row.moddedBlockId());
                continue;
            }
            effective.add(row);
            used.add(slotKey(row));
            placed.add(row.moddedBlockId());
            manifestIds.add(row.moddedBlockId());
        }
        List<VisualBlock> ledger = new ArrayList<>();
        Set<String> ledgerIds = new HashSet<>();
        for (VisualBlock row : ledgerRows) {
            String id = row.moddedBlockId();
            if (!validSlot(row)) {
                notes.add("carrier ledger: dropped malformed row " + id + " " + slotKey(row));
                continue;
            }
            if (!ledgerIds.add(id)) {
                continue; // duplicate ledger row: the first one owns the id
            }
            if (manifestIds.contains(id)) {
                ledger.add(row); // reserved while the manifest owns the id
                if (!row.isDisplay()) {
                    used.add(slotKey(row));
                }
                continue;
            }
            if (row.isDisplay()) {
                // a display row holds no slot: it stays on the family while the block is still here
                ledger.add(row);
                if (found.contains(id)) {
                    effective.add(row);
                    placed.add(id);
                }
                continue;
            }
            if (used.contains(slotKey(row))) {
                notes.add("carrier " + id + " lost " + slotKey(row) + " to another row: reallocated");
                continue;
            }
            used.add(slotKey(row));
            ledger.add(row);
            if (found.contains(id)) {
                effective.add(row);
                placed.add(id);
            }
        }
        int displayed = 0;
        int cursor = 0;
        List<String> order = new ArrayList<>(found);
        order.sort(Comparator.comparingInt(F2vPack::carrierRank).thenComparing(Comparator.naturalOrder()));
        for (String id : order) {
            if (placed.contains(id)) {
                continue;
            }
            while (cursor < capacity && used.contains(slotKey(slotAt(cursor)))) {
                cursor++;
            }
            VisualBlock row;
            if (cursor >= capacity) {
                // the slot families are full: the display family takes it, unbounded
                row = new VisualBlock(id, DISPLAY_FAMILY, 0);
                displayed++;
            } else {
                VisualBlock slot = slotAt(cursor);
                row = new VisualBlock(id, slot.instrument(), slot.note());
                used.add(slotKey(slot));
            }
            effective.add(row);
            ledger.add(row);
            placed.add(id);
        }
        effective.sort(BLOCK_ORDER);
        ledger.sort(BLOCK_ORDER);
        return new CarrierPlan(effective, ledger, notes);
    }

    /**
     * Fresh allocations favour the owner's Tier 0 mods (docs/MODPACK.md, the parity law:
     * Create first), then the rest by id. Plain alphabetical order would starve create:
     * behind ae2 and alexsmobs in a full pack. Ledger rows are never re-ranked.
     */
    static final List<String> CARRIER_PRIORITY = List.of("create", "biomesoplenty", "dynamictrees", "combat_roll");

    static int carrierRank(String moddedId) {
        int colon = moddedId.indexOf(':');
        int rank = colon > 0 ? CARRIER_PRIORITY.indexOf(moddedId.substring(0, colon)) : -1;
        return rank < 0 ? CARRIER_PRIORITY.size() : rank;
    }

    private static final Comparator<VisualBlock> BLOCK_ORDER = Comparator
            .comparing(VisualBlock::moddedBlockId)
            .thenComparing(VisualBlock::instrument)
            .thenComparingInt(VisualBlock::note);

    /** The slot at a dense index: instrument-major over CARRIER_INSTRUMENTS, notes 1..24. */
    static VisualBlock slotAt(int index) {
        if (index >= CARRIER_CAPACITY) {
            int m = index - CARRIER_CAPACITY;
            int masks = MUSHROOM_MASK_MAX - MUSHROOM_MASK_MIN + 1;
            return new VisualBlock("", MUSHROOM_HOSTS.get(m / masks), MUSHROOM_MASK_MIN + m % masks);
        }
        int notes = CARRIER_NOTE_MAX - CARRIER_NOTE_MIN + 1;
        return new VisualBlock("", CARRIER_INSTRUMENTS.get(index / notes), CARRIER_NOTE_MIN + index % notes);
    }

    private static String slotKey(VisualBlock row) {
        return row.key();
    }

    private static boolean validSlot(VisualBlock row) {
        if (row.isDisplay()) {
            return row.moddedBlockId().indexOf(':') > 0;
        }
        if (row.isMushroom()) {
            return row.note() >= MUSHROOM_MASK_MIN && row.note() <= MUSHROOM_MASK_MAX;
        }
        return CARRIER_INSTRUMENTS.contains(row.instrument()) && row.note() >= 0 && row.note() <= CARRIER_NOTE_MAX;
    }

    /**
     * Every mod block a jar ships a blockstate for, as {@code ns:path}. The minecraft
     * namespace is vanilla, never a carrier candidate. A block with no blockstate JSON
     * cannot be discovered this way (documented limit).
     */
    static Set<String> discoverBlockIds(List<Path> jars) throws IOException {
        Set<String> out = new TreeSet<>();
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith("assets/") || !name.endsWith(".json")) {
                        continue;
                    }
                    int slash = name.indexOf('/', "assets/".length());
                    if (slash < 0) {
                        continue;
                    }
                    String ns = name.substring("assets/".length(), slash);
                    String prefix = "assets/" + ns + "/blockstates/";
                    if (ns.equals("minecraft") || !name.startsWith(prefix)) {
                        continue;
                    }
                    String path = name.substring(prefix.length(), name.length() - ".json".length());
                    if (!path.isEmpty()) {
                        out.add(ns + ":" + path);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Plans the carriers over the mod jars in {@code modsDir}. With {@code persist}, writes
     * the ledger ({@code carriers.tsv}, kept across restarts) into {@code f2vDir}; it is
     * rewritten only when its content changes.
     */
    /**
     * The mushroom family is OFF unless {@code -Dcim.f2v.mushroomCarriers=true}: vanilla
     * placement and neighbour updates can reach all 64 face states, and worldgen (huge
     * mushrooms, mushroom islands) produces some of them, so a mushroom carrier would re-draw
     * vanilla terrain that shares its state. Enabling it is blocked on a worldgen state census.
     */
    static boolean mushroomCarriersEnabled() {
        return Boolean.getBoolean("cim.f2v.mushroomCarriers");
    }

    static CarrierPlan carrierPlan(Path f2vDir, Path modsDir, List<VisualBlock> manifest,
                                   boolean persist) throws IOException {
        Path ledgerFile = f2vDir.resolve(CARRIER_LEDGER);
        CarrierPlan plan = planCarriers(discoverBlockIds(listJars(modsDir)), manifest,
                readBlockManifest(ledgerFile), mushroomCarriersEnabled());
        if (persist) {
            Files.createDirectories(f2vDir);
            writeIfChanged(ledgerFile, blockRowsText(LEDGER_HEADER, plan.ledger()));
        }
        return plan;
    }

    /** Writes the rows a build emitted ({@link Result#blocks()}) for the HUD to read. */
    static void writeResolvedBlocks(Path f2vDir, List<VisualBlock> emitted) throws IOException {
        Files.createDirectories(f2vDir);
        writeIfChanged(f2vDir.resolve(RESOLVED_BLOCKS), blockRowsText(RESOLVED_HEADER, emitted));
    }

    /**
     * The block rows the running pack was built from: the resolved plan when a build wrote
     * it, else the manifest. The HUD and the boot fast path read through this.
     */
    public static List<VisualBlock> readEffectiveBlocks(Path f2vDir) throws IOException {
        Path resolved = f2vDir.resolve(RESOLVED_BLOCKS);
        return Files.isRegularFile(resolved)
                ? readBlockManifest(resolved)
                : readBlockManifest(f2vDir.resolve("visual-blocks.tsv"));
    }

    private static String blockRowsText(String header, List<VisualBlock> rows) {
        StringBuilder sb = new StringBuilder(header);
        for (VisualBlock r : rows) {
            sb.append(r.moddedBlockId()).append('\t').append(r.instrument())
                    .append('\t').append(r.isMushroom() ? mushroomBits(r.note()) : Integer.toString(r.note()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static void writeIfChanged(Path file, String text) throws IOException {
        if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
            return;
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Result withNotes(Result r, List<String> notes) {
        if (notes.isEmpty()) {
            return r;
        }
        List<String> warnings = new ArrayList<>(r.warnings());
        warnings.addAll(notes);
        return new Result(r.items(), r.blocks(), r.sha1(), r.entryCount(), warnings, r.gallery(), r.placeholders(), r.tally());
    }

    private record CarrierModel(String ref, int xRot, int yRot, boolean uvlock) {
        CarrierModel(String ref) {
            this(ref, 0, 0, false);
        }

        JsonObject application() {
            JsonObject apply = new JsonObject();
            apply.addProperty("model", ref);
            if (xRot != 0) apply.addProperty("x", xRot);
            if (yRot != 0) apply.addProperty("y", yRot);
            if (uvlock) apply.addProperty("uvlock", true);
            return apply;
        }
    }

    /**
     * Resolves the carrier's representative blockstate application and copies its model graph.
     * A missing model falls back to the direct models/block/&lt;path&gt; probe.
     */
    private static CarrierModel resolveCarrierModel(JarCache cache, List<Path> jars, String moddedId,
                                                    TreeMap<String, byte[]> entries, List<String> warnings)
            throws IOException {
        int colon = moddedId.indexOf(':');
        if (colon > 0) {
            byte[] bs = cache.read("assets/" + moddedId.substring(0, colon) + "/blockstates/"
                    + moddedId.substring(colon + 1) + ".json");
            CarrierModel model = bs == null ? null : defaultBlockstateModel(new String(bs, StandardCharsets.UTF_8));
            if (model != null) {
                String ref = copyModelRef(cache, model.ref(), entries, warnings);
                if (ref != null) {
                    return model;
                }
                warnings.add("blockstate model missing in jars: " + model.ref() + " (" + moddedId + ")");
            }
        }
        String ref = resolveAndCopyBlockGraph(jars, moddedId, entries, warnings);
        return ref == null ? null : new CarrierModel(ref);
    }

    /** Copies a {@code ns:path} model's parent graph from the first jar carrying it; vanilla refs pass through. */
    private static String copyModelRef(JarCache cache, String ref, TreeMap<String, byte[]> entries,
                                       List<String> warnings) throws IOException {
        int colon = ref.indexOf(':');
        String ns = colon > 0 ? ref.substring(0, colon) : "minecraft";
        String path = colon > 0 ? ref.substring(colon + 1) : ref;
        if (ns.equals("minecraft")) {
            return ref; // vanilla model: the client carries it
        }
        String file = "assets/" + ns + "/models/" + path + ".json";
        for (Path jar : cache.paths()) {
            ZipFile zf = cache.zip(jar);
            ZipEntry e = zf.getEntry(file);
            if (e != null) {
                byte[] bytes = zf.getInputStream(e).readAllBytes();
                entries.putIfAbsent(file, bytes);
                copyParentGraph(zf, file, bytes, entries, warnings, 0);
                return ref;
            }
        }
        return null;
    }

    /** Selects the empty variant, first sorted variant, or first usable multipart application. */
    private static CarrierModel defaultBlockstateModel(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("variants") && obj.get("variants").isJsonObject()) {
                JsonObject variants = obj.getAsJsonObject("variants");
                JsonElement pick = variants.get("");
                if (pick == null) {
                    List<String> keys = new ArrayList<>(variants.keySet());
                    if (keys.isEmpty()) {
                        return null;
                    }
                    Collections.sort(keys);
                    pick = variants.get(keys.get(0));
                }
                return modelOf(pick);
            }
            if (obj.has("multipart") && obj.get("multipart").isJsonArray()) {
                for (JsonElement part : obj.getAsJsonArray("multipart")) {
                    if (part.isJsonObject() && part.getAsJsonObject().has("apply")) {
                        CarrierModel model = modelOf(part.getAsJsonObject().get("apply"));
                        if (model != null) {
                            return model;
                        }
                    }
                }
            }
            return null;
        } catch (RuntimeException e) {
            return null; // malformed JSON or wrong shape: the caller falls back
        }
    }

    private static CarrierModel modelOf(JsonElement e) {
        if (e == null) {
            return null;
        }
        if (e.isJsonArray()) {
            return e.getAsJsonArray().isEmpty() ? null : modelOf(e.getAsJsonArray().get(0));
        }
        if (e.isJsonObject()) {
            JsonObject model = e.getAsJsonObject();
            JsonElement ref = model.get("model");
            if (ref != null && ref.isJsonPrimitive()) {
                JsonElement uvlock = model.get("uvlock");
                return new CarrierModel(ref.getAsString(), carrierRotation(model.get("x")),
                        carrierRotation(model.get("y")), uvlock != null && uvlock.isJsonPrimitive()
                                && uvlock.getAsBoolean());
            }
        }
        return null;
    }

    /** Both clients interpret quarter-turn angles modulo 360. Keep the model if metadata is malformed. */
    private static int carrierRotation(JsonElement angle) {
        if (angle != null && angle.isJsonPrimitive() && angle.getAsJsonPrimitive().isNumber()) {
            int degrees = angle.getAsInt();
            if (angle.getAsDouble() == degrees && degrees % 90 == 0) {
                return Math.floorMod(degrees, 360);
            }
        }
        return 0;
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
     * block/item state carried in the ignored evidence column). 14-db: the
     * table also carries STATE-keyed rows ({@code ns:block[props]} — the
     * state-keyed fallback for the multi/unresolved-texture states whose
     * per-texture fold was refused); a state row's canonical property-order
     * spelling binds as an alias (the same rule readEmissiveManifest
     * carries), the raw key keeps priority. Optional;
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
                    String key = parts[0].trim();
                    map.putIfAbsent(key, tint);
                    // the CANONICAL spelling binds as an alias — the same rule
                    // readEmissiveManifest carries (14-db): the manifest walk
                    // and the table may order the same state's properties
                    // differently, and applyStateTint's lookup (exact, then
                    // canonical) needs both spellings present. The raw key
                    // keeps priority (putIfAbsent, first row wins —
                    // deterministic file order); a texture ref (no '[') maps
                    // to itself, so the texture rows are unaffected.
                    map.putIfAbsent(GalleryPack.canonicalState(key), tint);
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
        CarrierPlan plan = carrierPlan(outDir, modsDir,
                readBlockManifest(outDir.resolve("visual-blocks.tsv")), true);
        Result r = withNotes(build(modsDir, outDir, manifestRows, equipmentRows, plan.effective()), plan.notes());
        writeResolvedBlocks(outDir, r.blocks());
        return r;
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
        // 14-dz2: the animated-sprite ledger's ONE reset — the build start.
        // The item quad section records before the gallery build runs, the
        // fluid scan records after it; the old clear inside the gallery
        // build wiped both (the animation tsv carried gallery states only).
        GalleryPack.resetAnimatedStates();
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
        dev.cim.hud.ScreenPack.contribute(entries, modsDir, rows.stream().map(VisualItem::moddedId).toList());
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
        int[] compositeNo = {0};
        Set<String> authoredObjItems = new TreeSet<>();
        int manifestResolved = 0;
        int manifestFailed = 0;
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
                String found = itemModelRef(row.moddedId(), jars, entries, warnings,
                        paletteByVariantId, emissiveTable, itemEmissiveBakeLog, compositeNo, authoredObjItems);
                if (found == null) {
                    warnings.add("no item model found for " + row.moddedId()
                            + " in any mod jar — row dropped");
                    manifestFailed++;
                    continue;
                }
                manifestResolved++;
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

        // ALLOCATED ITEMS (the item channel, no slot budget): every mod item a mod jar ships an
        // item model for, that no manifest row covers, gets its own items/ definition under
        // assets/c-imagine/items/<ns>/<path>.json (the same model chain a manifest row runs). A Paper
        // stack names that definition through item_model on a vanilla host material, so capacity is
        // the item count, not the custom_model_data float space. Sorted candidates keep the bytes and
        // the allocated ledger a pure function of the jars.
        Set<String> manifestIds = new HashSet<>();
        for (VisualItem r : baseResolved) {
            manifestIds.add(r.moddedId());
        }
        List<String> allocatedIds = new ArrayList<>();
        int allocFailed = 0;
        for (String id : itemCandidates(discoverItemIds(listJars(modsDir)), manifestIds)) {
            String found = itemModelRef(id, jars, entries, warnings, Map.of(),
                    emissiveTable, itemEmissiveBakeLog, compositeNo, authoredObjItems);
            if (found == null) {
                allocFailed++;
                continue;
            }
            entries.put(allocatedItemDefPath(id), allocatedItemDef(found).getBytes(StandardCharsets.UTF_8));
            allocatedIds.add(id);
        }
        ItemTally tally = new ItemTally(manifestResolved, allocatedIds.size(), manifestFailed + allocFailed);

        // equipment assets (M6 pull-forward): legacy armor textures -> the
        // 26.3 equipment surface, manifest-driven, zero vanilla overrides
        List<Path> equipJars = jars.isEmpty() ? listJars(modsDir) : jars;
        for (EquipmentAsset asset : equipmentRows) {
            // 14-dy: the placeholder ledger attributes equipment fallbacks
            // to the asset id
            GalleryPack.placeholderContext(asset.assetId());
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
                // 14-e167 THE PER-CELL MEASURED CALIBRATION (the R-a
                // delivery): the calibrator's per-cell rows
                // (visual-tints-cells.tsv, keyed state@page:c,r) become the
                // per-cell wrapper variants. Absent file = empty table = the
                // byte-identical build (the honest absent-input contract).
                GalleryPack.resetPerCellTints();
                GalleryPack.setPerCellTints(
                        readTintManifest(outDir.resolve("visual-tints-cells.tsv")));
                // 14-df RING channel: the same row parser, a separate table
                // (the ring rows key the STATE to a corridor factor, not a
                // texture multiply — the ring never rewrites a sprite).
                // Absent visual-rings.tsv = empty table = byte-identical.
                Map<String, double[]> ringTable =
                        readTintManifest(outDir.resolve("visual-rings.tsv"));
                List<String> tintBakeLog = new ArrayList<>();
                // EMISSIVE-BAKE channel: the table loaded ABOVE (the item
                // section needs it too) — this block only carries the logs.
                List<String> emissiveBakeLog = new ArrayList<>();
                gallery = GalleryPack.build(jars, galleryInputs, entries, warnings,
                        tintTable, tintBakeLog, emissiveTable, emissiveBakeLog, ringTable);
                for (String bake : tintBakeLog) {
                    warnings.add("tint-bake applied: " + bake);
                }
                for (String bake : emissiveBakeLog) {
                    warnings.add("emissive-bake applied: " + bake);
                }
                GalleryPack.writeResolvedManifest(
                        outDir.resolve("visual-gallery.resolved.tsv"), gallery);
                // 14-e67: the page-composite overlay's unscale table (the
                // k>1 normalize compensations; the overlay JVM has no
                // MODEL_SCALE map and the composite-node refs never appear
                // in paper.json — this tsv is the only k source there)
                GalleryPack.writeScaleManifest(
                        outDir.resolve("visual-gallery-scales.tsv"));
                // 14-dx-f: the animated-sprite states the build baked (the
                // 14-dr static-frame law's missing demotion half) — the join
                // reads this file and demotes the matching rows to ANIMATED
                // (the free-running animation clocks can never phase-match
                // across boots; the delta is the frame phase, not fidelity).
                // 14-dz2: the DRAIN moved below the fluid scan — the fluid
                // strip receipts (and any later producer) join the same
                // tsv; draining here lost them (the scan runs after this
                // line and the gallery build's own clear ran before the
                // item section's records ever landed).
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

        // 14-dx-f + 14-dz2: the animated-sprite states — the gallery build's
        // model-graph bakes, the item quad section's item meshes (the item
        // id context rides placeholderContext) and the fluid scan's strips
        // (the mcmeta-proven frame counts) — ONE ledger drained ONCE at the
        // end. The join reads this file and demotes the matching rows to
        // ANIMATED (the free-running animation clocks can never phase-match
        // across boots; the delta is the frame phase, not fidelity).
        java.util.List<String[]> animated = GalleryPack.drainAnimatedStates();
        if (!animated.isEmpty()) {
            StringBuilder sb = new StringBuilder("state\tframes\n");
            for (String[] row : animated) {
                sb.append(row[0]).append('\t').append(row[1]).append('\n');
            }
            Files.writeString(outDir.resolve("visual-gallery-animation.tsv"),
                    sb.toString(), StandardCharsets.UTF_8);
            warnings.add("14-dx-f: " + animated.size()
                    + " state(s) sample animated atlas sprites — the join"
                    + " demotes their rows to ANIMATED (visual-gallery-animation.tsv)");
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
        java.nio.file.Path sampledFaces = outDir.resolve("fluidfaces");
        java.util.Map<String, String> sampledIndex = new java.util.LinkedHashMap<>();
        java.nio.file.Path sampledIdx = sampledFaces.resolve("index.tsv");
        if (java.nio.file.Files.isRegularFile(sampledIdx)) {
            for (String l : java.nio.file.Files.readAllLines(sampledIdx)) {
                String[] parts = l.split("\t");
                if (parts.length == 2 && !parts[0].equals("fluid")) {
                    sampledIndex.put(parts[0], parts[1]);
                }
            }
        }
        // 14-ea: a sampled face rescues fluids the jar scan cannot host
        // (lumisene's code-tinted surface) — they enter the cube set here
        cubeIds.addAll(sampledIndex.keySet());
        List<FluidCube> fluidCubes =
                synthesizeFluidCubes(cubeIds, fluidColors, entries, warnings,
                        sampledFaces, sampledIndex);
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
        List<Map.Entry<String, CarrierModel>> blockVariants = new ArrayList<>();
        Map<String, Map<Integer, CarrierModel>> mushroomModels = new TreeMap<>();
        List<VisualBlock> emittedBlocks = new ArrayList<>();
        try (JarCache blockJars = new JarCache(jars)) {
            for (VisualBlock block : blockRows) {
                CarrierModel model = resolveCarrierModel(blockJars, jars, block.moddedBlockId(), entries, warnings);
                if (block.isDisplay()) {
                    // display family: no slot, an items/ definition of the block's own model (the
                    // ItemDisplay on the barrier host renders it; see DisplayCarriers in the HUD)
                    String ref = model == null ? null : model.ref();
                    if (ref != null && GalleryPack.chainHasElements(jars, ref, warnings)) {
                        // Bake the blockstate transform into the item composite's geometry and UVs.
                        String comp = safeItemComposite(jars, ref, model.xRot(), model.yRot(), model.uvlock(),
                                compositeNo[0]++, entries, warnings);
                        if (comp != null) {
                            ref = comp;
                        }
                    }
                    if (ref != null) {
                        entries.put(displayItemDefPath(block.moddedBlockId()),
                                allocatedItemDef(ref).getBytes(StandardCharsets.UTF_8));
                        emittedBlocks.add(block);
                    } else {
                        warnings.add("no block model found for " + block.moddedBlockId()
                                + " in any mod jar — display row dropped");
                    }
                    continue;
                }
                if (model != null && block.isMushroom()) {
                    mushroomModels.computeIfAbsent(block.instrument(), h -> new TreeMap<>())
                            .put(block.note(), model);
                    emittedBlocks.add(block);
                } else if (model != null) {
                    blockVariants.add(Map.entry(
                            "instrument=" + block.instrument() + ",note=" + block.note(),
                            model));
                    emittedBlocks.add(block);
                } else {
                    warnings.add("no block model found for " + block.moddedBlockId()
                            + " in any mod jar — block row dropped");
                }
            }
            for (Map.Entry<String, Map<Integer, CarrierModel>> host : mushroomModels.entrySet()) {
                byte[] vanilla = blockJars.read("assets/minecraft/blockstates/" + host.getKey() + ".json");
                String state = vanilla == null ? null
                        : mushroomBlockState(new String(vanilla, StandardCharsets.UTF_8), host.getValue());
                if (state == null) {
                    warnings.add("mushroom carriers on " + host.getKey() + " dropped: its vanilla blockstate"
                            + " is missing or not a multipart (the pack cannot keep vanilla states)");
                    emittedBlocks.removeIf(b -> b.isMushroom() && b.instrument().equals(host.getKey()));
                    continue;
                }
                entries.put("assets/minecraft/blockstates/" + host.getKey() + ".json",
                        state.getBytes(StandardCharsets.UTF_8));
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
        validateDownloadSize(zip.length);
        String sha1 = sha1Hex(zip);
        Files.write(outDir.resolve("pack.zip"), zip);
        Files.writeString(outDir.resolve("pack.sha1"), sha1 + "\n", StandardCharsets.UTF_8);
        writeResolvedManifest(outDir.resolve("visual-items.resolved.tsv"), rows);
        writeIfChanged(outDir.resolve(ALLOCATED_ITEMS), allocatedText(allocatedIds));
        Set<String> foil = readItemFoil(outDir.resolve("quads/item-foil.tsv"));
        writeIfChanged(outDir.resolve(RESOLVED_ITEM_FOIL), String.join("\n", foil) + (foil.isEmpty() ? "" : "\n"));
        // 14-dy THE PLACEHOLDER LEDGER: the build's placeholder fallbacks
        // (sprite, context, uses) — the texture-fidelity audit's named
        // backlog, written next to the manifest and summarized in the
        // report; the pack zip itself carries NO ledger entry (assets only)
        java.util.List<String[]> placeholders = GalleryPack.drainPlaceholderUses();
        GalleryPack.writePlaceholderLedger(placeholders, outDir.resolve("placeholders.tsv"));
        StringBuilder report = new StringBuilder("f2v pack build report\n")
                .append("items requested: ").append(rows.size()).append('\n')
                .append("zip entries:     ").append(entries.size()).append('\n')
                .append("zip bytes:       ").append(zip.length).append('\n')
                .append("mod items:       ").append(tally.resolved()).append(" resolved (")
                .append(tally.manifest()).append(" manifest, ").append(tally.allocated())
                .append(" allocated, ").append(tally.failed()).append(" failed)\n")
                .append("tile hash collisions resolved: ").append(ObjMesh.tileHashCollisions()).append('\n')
                .append("sha1:            ").append(sha1).append('\n')
                .append("warnings:        ").append(warnings.size()).append('\n')
                .append("placeholders:    ").append(placeholders.size())
                .append(" (see placeholders.tsv — a placeholder is NOT supported content)\n");
        for (String item : authoredObjItems) {
            report.append("  authored OBJ item normals: ").append(item).append('\n');
        }
        for (String[] ph : placeholders) {
            report.append("  placeholder: ").append(ph[0]).append(" for ").append(ph[1])
                    .append(" x").append(ph[2]).append('\n');
        }
        for (String w : warnings) {
            report.append("  warn: ").append(w).append('\n');
        }
        Files.writeString(outDir.resolve("build-report.txt"), report.toString(),
                StandardCharsets.UTF_8);
        // only rows the pack emits: a carrier without a model would render as a plain note block
        return new Result(rows, emittedBlocks, sha1, entries.size(), warnings, gallery, placeholders, tally);
    }

    /** Any element-face uv array in a model json, 4 numbers. */
    /**
     * The item-channel model of one modded item ({@code ns:path}), copied into the pack: block-geometry
     * models ride a standalone composite, sprite models keep their vanilla shape (the emissive upgrade
     * when the table carries them). Shared by the manifest rows (host dispatch) and the allocated items
     * (their own definition). Null when no jar carries a model.
     */
    private static String itemModelRef(String moddedId, List<Path> jars, TreeMap<String, byte[]> entries,
                                       List<String> warnings, Map<String, PaletteRow> paletteByVariantId,
                                       Map<String, Integer> emissiveTable, List<String> emissiveBakeLog,
                                       int[] compositeNo, Set<String> authoredObjItems) throws IOException {
        // 14-dy: the placeholder ledger attributes item-model fallbacks to the modded item id
        GalleryPack.placeholderContext(moddedId);
        String found = null;
        // These Create items were checked against their native item renderers. An OBJ
        // parent alone cannot rule out a Java-side renderer or a model event wrapper.
        if ((moddedId.equals("create:empty_blaze_burner") || moddedId.equals("create:flywheel"))
                && !emissiveTable.containsKey(moddedId)) {
            found = resolveAndCopyModelGraph(jars, moddedId, entries, warnings, paletteByVariantId);
            if (found != null) {
                try {
                    String authored = GalleryPack.synthesizeAuthoredObjItem(
                            jars, found, compositeNo[0], entries, warnings);
                    if (authored != null) {
                        authoredObjItems.add(moddedId);
                        compositeNo[0]++;
                        return authored;
                    }
                } catch (RuntimeException bad) {
                    warnings.add("authored OBJ item skipped for " + moddedId + ": " + bad);
                }
            }
        }
        String quadModel = GalleryPack.synthesizeItemQuads(jars, moddedId, compositeNo[0], entries, warnings);
        if (quadModel != null) {
            compositeNo[0]++;
        }
        if (quadModel != null) {
            return quadModel;
        }
        if (found == null) {
            found = resolveAndCopyModelGraph(jars, moddedId, entries, warnings, paletteByVariantId);
        }
        // THE 26.3 ITEM-CHANNEL SHAPE (measured #49/#50, see GalleryPack.synthesizeItemComposite):
        // block-geometry item models ride a standalone composite; sprite-driven models stay vanilla-shaped.
        if (found != null && GalleryPack.chainHasElements(jars, found, warnings)) {
            String comp = safeItemComposite(jars, found, compositeNo[0]++, entries, warnings);
            if (comp != null) {
                // THE ITEM EMISSIVE-BAKE: a tabled modded item's composite carries light_emission
                found = GalleryPack.applyItemEmissive(moddedId, comp, emissiveTable, emissiveBakeLog);
            }
        } else if (found != null) {
            // THE SPRITE EMISSIVE UPGRADE: a tabled sprite model becomes a flat-quad composite + light_emission
            String upgraded = GalleryPack.synthesizeSpriteEmissive(
                    jars, found, moddedId, compositeNo[0], emissiveTable, entries, warnings);
            if (upgraded != null) {
                compositeNo[0]++;
                found = upgraded;
            }
        }
        return found;
    }

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

    /** The six face states of a mushroom mask as a blockstate {@code when} map (NESWUD order). */
    private static JsonObject mushroomWhen(int mask) {
        JsonObject when = new JsonObject();
        for (int i = 0; i < MUSHROOM_FACES.size(); i++) {
            when.addProperty(MUSHROOM_FACES.get(i), (mask >> i & 1) == 1 ? "true" : "false");
        }
        return when;
    }

    /**
     * The mushroom host's blockstate with its carrier states added, keeping every vanilla state
     * on its vanilla parts. Vanilla is multipart: each part matches a set of masks. A part that
     * meets a carrier mask is rewritten as an OR over its remaining masks (never the carriers),
     * and each carrier gets one part of its own. Null when the vanilla JSON is not a multipart
     * of plain property maps (the caller then drops the carriers instead).
     */
    private static String mushroomBlockState(String vanillaJson, Map<Integer, CarrierModel> carriers) {
        JsonObject root;
        try {
            root = JsonParser.parseString(vanillaJson).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
        if (!root.has("multipart") || !root.get("multipart").isJsonArray()) {
            return null;
        }
        JsonArray out = new JsonArray();
        for (JsonElement el : root.getAsJsonArray("multipart")) {
            if (!el.isJsonObject() || !el.getAsJsonObject().has("apply")) {
                return null;
            }
            JsonObject part = el.getAsJsonObject();
            Map<String, String> want = new TreeMap<>();
            if (part.has("when")) {
                if (!part.get("when").isJsonObject()) {
                    return null; // combined conditions are not rewritten here
                }
                for (Map.Entry<String, JsonElement> c : part.getAsJsonObject("when").entrySet()) {
                    if (!MUSHROOM_FACES.contains(c.getKey()) || !c.getValue().isJsonPrimitive()) {
                        return null;
                    }
                    want.put(c.getKey(), c.getValue().getAsString());
                }
            }
            List<Integer> matches = new ArrayList<>();
            for (int mask = 0; mask < 64; mask++) {
                if (matchesWhen(mask, want) && !carriers.containsKey(mask)) {
                    matches.add(mask);
                }
            }
            boolean touched = false;
            for (int mask = 0; mask < 64; mask++) {
                if (carriers.containsKey(mask) && matchesWhen(mask, want)) {
                    touched = true;
                    break;
                }
            }
            if (!touched) {
                out.add(part);
                continue;
            }
            if (matches.isEmpty()) {
                continue;
            }
            JsonArray alts = new JsonArray();
            for (int mask : matches) {
                alts.add(mushroomWhen(mask));
            }
            JsonObject combined = new JsonObject();
            combined.add("OR", alts);
            JsonObject rewritten = new JsonObject();
            rewritten.add("when", combined);
            rewritten.add("apply", part.get("apply"));
            out.add(rewritten);
        }
        for (Map.Entry<Integer, CarrierModel> carrier : new TreeMap<>(carriers).entrySet()) {
            JsonObject part = new JsonObject();
            part.add("when", mushroomWhen(carrier.getKey()));
            part.add("apply", carrier.getValue().application());
            out.add(part);
        }
        JsonObject root2 = new JsonObject();
        root2.add("multipart", out);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root2) + "\n";
    }

    private static boolean matchesWhen(int mask, Map<String, String> want) {
        for (Map.Entry<String, String> w : want.entrySet()) {
            boolean bit = (mask >> MUSHROOM_FACES.indexOf(w.getKey()) & 1) == 1;
            if (bit != w.getValue().equals("true")) {
                return false;
            }
        }
        return true;
    }

    /**
     * The merged {@code assets/minecraft/blockstates/note_block.json}
     * override: one variant per manifest row, keys sorted (deterministic
     * zip). Partial selector keys are wildcards for unspecified properties
     * (VariantSelector measured, block-channel-26-3.md §3) — the note
     * property pins the slot, powered stays unspecified.
     */
    private static String blockStateDef(List<Map.Entry<String, CarrierModel>> variants) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"variants\": {\n");
        for (int i = 0; i < variants.size(); i++) {
            Map.Entry<String, CarrierModel> e = variants.get(i);
            sb.append("    \"").append(e.getKey())
              .append("\": ").append(e.getValue().application());
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
              .append("\"palette_key\": \"").append(paletteSpritePath(pr.paletteKey())).append("\", ")
              .append("\"permutations\": {");
            boolean first = true;
            for (Map.Entry<String, String> v : pr.variants().entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append("\"").append(v.getKey()).append("\": \"")
                  .append(paletteSpritePath(v.getValue())).append("\"");
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

    /**
     * The 26.3 palette ATLAS reference ({@code ns:<name>}) from the manifest's
     * full jar path ({@code ns:textures/palettes/x.png}). 14-e26: the 26.3
     * client resolves paletted_permutations palette ids through the NEW
     * {@code Palette.ID_CONVERTER} — prefix {@code textures/palettes} — so the
     * reference must carry the path WITHOUT the palettes/ segment (the
     * vanilla 26.3 convention: {@code minecraft:trim_base}, file
     * textures/palettes/trim_base.png). The old 1.21.1 readImage used the
     * generic textures/ prefix and wanted {@code ns:palettes/x}; the old emit
     * produced that form, the 26.3 converter doubled it to
     * textures/palettes/palettes/x.png = the resource never found =
     * "Failed to load palette image" on every run since M6 (measured 26.3
     * client.jar bytecode + the vanilla items.json reference shape).
     */
    static String paletteSpritePath(String fullResourcePath) {
        String s = spritePath(fullResourcePath);
        int c = s.indexOf(':');
        if (c <= 0) {
            return s;
        }
        String rest = s.substring(c + 1);
        if (rest.startsWith("palettes/")) {
            return s.substring(0, c + 1) + rest.substring("palettes/".length());
        }
        return s;
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
            boolean present = false;
            for (JsonElement previous : sources) {
                if (previous.equals(s)) { present = true; break; }
            }
            if (!present) sources.add(s);
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
     *  vanilla refs carry nothing to copy; referenced sprites outside the
     *  {@code item|block} directories get explicit atlas sources. */
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
        boolean automatic = texPath.startsWith("item/") || texPath.startsWith("block/");
        if (entries.containsKey(texFile)) {
            if (!automatic) atlasReferencedTexture(ref, entries);
            return;
        }
        ZipEntry te = zf.getEntry(texFile);
        if (te == null) {
            warnings.add("texture missing in jar: " + ref
                    + " (referenced by " + modelPath + ")");
            return;
        }
        byte[] bytes = zf.getInputStream(te).readAllBytes();
        // 14-dr THE STATIC-FRAME RULE: an animated source ships as its first
        // animation frame (cropped to the frame box, NO mcmeta). The item
        // sprite route previously shipped the raw strip with NO mcmeta at
        // all — the strip squashed into one smeared sprite on the client
        // (the measured 18-strip bug); the re-host routes shipped it WITH
        // the mcmeta and animated against the truth's pinned frame (run
        // 42839844310: mysterious_cube/singularity/anomaly crops).
        // GalleryPack.stillFrameCrop owns the rationale.
        byte[] meta = null;
        ZipEntry me = zf.getEntry(texFile + ".mcmeta");
        if (me != null) {
            meta = zf.getInputStream(me).readAllBytes();
        }
        entries.put(texFile, GalleryPack.stillFrameCrop(ref, bytes, meta, warnings));
        if (!automatic) atlasReferencedTexture(ref, entries);
    }

    /** Vanilla 26.3 uses single sources for model sprites outside directory scans. */
    private static void atlasReferencedTexture(String ref, TreeMap<String, byte[]> entries) {
        JsonObject source = new JsonObject();
        source.addProperty("type", "minecraft:single");
        source.addProperty("resource", ref);
        JsonArray sources = new JsonArray();
        sources.add(source);
        JsonObject definition = new JsonObject();
        definition.add("sources", sources);
        for (String atlas : List.of("blocks", "items")) {
            appendAtlasSources("assets/minecraft/atlases/" + atlas + ".json", definition.toString(), entries);
        }
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

    /**
     * 14-e61 THE PAGE-COMPOSITE OVERLAY MERGE: append the page rows to the
     * SERVED pack's {@code paper.json} in place. The merge is TEXTUAL on
     * purpose: the seeded definition carries ~96k rows (315 MB measured on
     * the run-496 seed) and a gson DOM would inflate it past every boot
     * heap — while the emitter's layout is byte-stable (the same writer
     * produced it), so the additions insert before the entries array's
     * closing marker. The page thresholds (900000+) sit ABOVE every
     * existing row (the gallery 100000+, the items 1798+, the fluids
     * 400000+, the fluid cubes 410000+), so appending keeps the ascending
     * order the range_dispatch semantics need. The emitter's per-entry model
     * nodes are untouched verbatim (the 14-cz tint nodes and the
     * transformation-carrying nodes keep their seed-baked shapes). A layout
     * mismatch = a warning, no merge (the page lane then never spawns — the
     * honest fallback to the per-cell path).
     */
    public static void mergeItemDefNodes(TreeMap<String, byte[]> entries,
                                    List<Map.Entry<Float, String>> nodeAdditions,
                                    List<String> warnings) throws IOException {
        if (nodeAdditions.isEmpty()) {
            return;
        }
        String host = "assets/minecraft/items/paper.json";
        byte[] raw = entries.get(host);
        if (raw == null) {
            warnings.add("gallerypage: no paper.json in the served pack — page rows dropped");
            return;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < nodeAdditions.size(); i++) {
            Map.Entry<Float, String> a = nodeAdditions.get(i);
            rows.append("      {\"threshold\": ").append(a.getKey())
                .append(", \"model\": ").append(a.getValue()).append('}');
            if (i + 1 < nodeAdditions.size()) {
                rows.append(",\n");
            }
        }
        String marker = "\n    ],\n    \"fallback\"";
        int cut = body.lastIndexOf(marker);
        if (cut < 0 || !body.contains("\"entries\": [")) {
            warnings.add("gallerypage: paper.json is not the emitter's layout — "
                    + "the page rows dropped (the per-cell path rides)");
            return;
        }
        String merged = body.substring(0, cut) + ",\n" + rows + body.substring(cut);
        entries.put(host, merged.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 14-e71 THE NODE TABLE FROM THE SERVED PACK: ONE pass over the seeded
     * {@code paper.json} extracts EVERY entry's model node verbatim
     * (ref -> the node JSON substring, the 14-cz tint nodes and the
     * minecraft:composite wrappers included). The page-composite assembly
     * composes the page entry from THESE nodes — the per-cell tint/complex
     * node structure survives byte-for-byte (the element-merge path could
     * not carry the per-mesh node tints: the 500 run's 37 door reds rode
     * that hole). The node key = the ref the entry dispatches to; a
     * composite entry's key = the threshold's OWN ref only if it appears as
     * the entry's "model" — composite refs (the node carries sub-models)
     * are keyed by their ref too (the entry text names the threshold, the
     * node carries the children — the caller detects them by the node
     * shape, not the key).
     */
    public static java.util.Map<Float, String> readEntryNodes(
            TreeMap<String, byte[]> entries) {
        java.util.Map<Float, String> out = new java.util.HashMap<>();
        String host = "assets/minecraft/items/paper.json";
        byte[] raw = entries.get(host);
        if (raw == null) {
            return out;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        int i = body.indexOf("\"entries\": [");
        if (i < 0) {
            return out;
        }
        int end = body.indexOf("\"fallback\"", i);
        if (end < 0) {
            end = body.length();
        }
        // walk entry by entry: {"threshold": X, "model": NODE}
        int pos = i;
        while (true) {
            int entryAt = body.indexOf("{\"threshold\":", pos);
            if (entryAt < 0 || entryAt >= end) {
                break;
            }
            int modelKey = body.indexOf("\"model\":", entryAt);
            if (modelKey < 0 || modelKey >= end) {
                break;
            }
            int objAt = body.indexOf('{', modelKey);
            if (objAt < 0) {
                break;
            }
            // the balanced object scan
            int depth = 0, objEnd = objAt, scan = objAt;
            boolean inStr = false, esc = false;
            while (scan < end) {
                char c = body.charAt(scan);
                if (inStr) {
                    if (esc) esc = false;
                    else if (c == '\\') esc = true;
                    else if (c == '"') inStr = false;
                } else if (c == '"') inStr = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { objEnd = scan; break; }
                }
                scan++;
            }
            String node = body.substring(objAt, objEnd + 1);
            // 14-e71: THE KEY = THE ENTRY'S THRESHOLD (the cmd float) — the
            // composite/tint nodes never name their parent ref (the first
            // "model" field inside = the first CHILD's ref), so the ref-keyed
            // table missed the whole quad-route class (the 500's 37 door
            // reds). The cmd = the universal key: the resolved rows carry it,
            // the entries dispatch by it.
            int tStart = body.indexOf(':', entryAt) + 1;
            int tEnd = body.indexOf(',', tStart);
            try {
                float threshold = Float.parseFloat(body.substring(tStart, tEnd).trim());
                out.putIfAbsent(threshold, node);
            } catch (NumberFormatException bad) {
                // the malformed entry rides
            }
            pos = objEnd + 1;
        }
        return out;
    }

    /**
     * 14-e67 THE REF-SCALE TABLE FROM THE SERVED PACK: extract the
     * normalize-compensation factor per model ref from the seeded
     * {@code paper.json}'s per-entry transformation matrices. The overlay
     * JVM never ran the pack build, so GalleryPack's MODEL_SCALE map is
     * EMPTY there and the page merge cannot ask it — the served definition
     * IS the client's own k table (the seed build baked the matrix into
     * every oversized ref's entry), so the overlay reads it back textually.
     * A ref with no entry or no transformation = 1.0 (the plain composite).
     * The needle carries the closing quote so a prefix ref (g21) never
     * matches g210's entry.
     */
    public static java.util.Map<String, Double> readRefScales(
            TreeMap<String, byte[]> entries, java.util.Collection<String> refs) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        String host = "assets/minecraft/items/paper.json";
        byte[] raw = entries.get(host);
        if (raw == null || refs.isEmpty()) {
            return out;
        }
        String body = new String(raw, StandardCharsets.UTF_8);
        for (String ref : refs) {
            // the seeded entries mix TWO emitter formats: the plain branch
            // writes spaced JSON, the itemNode branch writes compact JSON —
            // match both or the gallery refs (the compact node entries, the
            // bulk of the picks) are missed (the 14-e67 lesson: the first
            // parse found 13/43 refs, k>1: 0, and the harness's own cell
            // check stayed empty)
            int at = body.indexOf("\"model\": \"" + ref + "\"");
            if (at < 0) {
                at = body.indexOf("\"model\":\"" + ref + "\"");
            }
            if (at < 0) {
                continue;
            }
            int tAt = body.indexOf("\"transformation\": [", at);
            if (tAt < 0) {
                tAt = body.indexOf("\"transformation\":[", at);
            }
            int entryEnd = body.indexOf("}}", at);
            if (tAt < 0 || (entryEnd >= 0 && tAt > entryEnd)) {
                out.put(ref, 1.0);
                continue;
            }
            int start = body.indexOf('[', tAt) + 1;
            int end = body.indexOf(']', start);
            if (end < 0) {
                out.put(ref, 1.0);
                continue;
            }
            try {
                String[] nums = body.substring(start, end).split(",");
                out.put(ref, Double.parseDouble(nums[0].trim()));
            } catch (NumberFormatException bad) {
                out.put(ref, 1.0);
            }
        }
        return out;
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
            sb.append("      {\"when\": \"").append(e.getKey()).append("\", \"model\": ");
            JsonObject override = GalleryPack.itemNode(e.getValue());
            if (override != null) {
                sb.append(override).append('}');
            } else {
                sb.append("{\"type\": \"minecraft:model\", \"model\": \"")
                  .append(e.getValue()).append('"').append(scaleField(e.getValue())).append("}}");
            }
            sb.append(i + 1 < cases.size() ? ",\n" : "\n");
        }
        sb.append("    ],\n")
          .append("    \"fallback\": {\"type\": \"minecraft:model\", \"model\": \"minecraft:item/")
          .append(hostPath).append("\"}\n")
          .append("  }\n}\n");
        return sb.toString();
    }

    // ---- deterministic zip ------------------------------------------------------

    /** The stock client rejects even required server packs above 250 MiB. */
    public static void validateDownloadSize(long bytes) throws IOException {
        if (bytes > 262_144_000L) {
            throw new IOException("resource pack exceeds vanilla's 250 MiB download limit: " + bytes);
        }
    }

    /** Deterministic zip: sorted entries, fixed timestamps, fixed level. */
    public static byte[] zip(TreeMap<String, byte[]> entries) throws IOException {
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

    public static String sha1Hex(byte[] data) {
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
            CarrierPlan plan = carrierPlan(outDir, modsDir,
                    readBlockManifest(outDir.resolve("visual-blocks.tsv")), true);
            Result r = withNotes(build(modsDir, outDir, rows,
                    readEquipmentManifest(outDir.resolve("visual-equipment.tsv")),
                    plan.effective(),
                    readPaletteManifest(outDir.resolve("visual-palettes.tsv")),
                    extraAssets), plan.notes());
            writeResolvedBlocks(outDir, r.blocks());
            System.out.println("f2v pack: built (" + r.items().size() + " item(s), "
                    + r.gallery().size() + " gallery state(s), "
                    + r.entryCount() + " zip entries, sha1=" + r.sha1() + ", "
                    + r.warnings().size() + " warning(s), "
                    + r.placeholders().size() + " placeholder sprite(s) — see placeholders.tsv)");
            // 14-e162 THE BUILD-RECEIPT CONTRACT: the warnings = the bake
            // provenance (the tint-bake/emissive/ao-tint receipts, the
            // placeholder attributions) — the CLI build = the CI's only
            // build path (the fast boot shards never rebuild), so the
            // warnings MUST land in pack-build.log or the provenance hole
            // eats the arbiter's evidence (the e161 landing's own lesson:
            // the bake fired, the receipts were invisible, the read went
            // through the pack pixels instead).
            for (String w : r.warnings()) {
                System.out.println("f2v pack: warn " + w);
            }
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
            // the carrier plan rides both builds exactly as the verification rebuild does
            List<VisualBlock> blockPlan = carrierPlan(out, mods,
                    readBlockManifest(out.resolve("visual-blocks.tsv")), true).effective();
            Result r1 = build(mods, out, readManifest(out.resolve("visual-items.tsv")),
                    equipment, blockPlan,
                    readPaletteManifest(out.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            Result r2 = build(mods, out, readManifest(out.resolve("visual-items.tsv")),
                    equipment, blockPlan,
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
            // the manifest row keeps its slot; every other fixture blockstate gets a distinct carrier
            boolean rockKept = r1.blocks().stream().anyMatch(b -> "fakemod:rock".equals(b.moddedBlockId())
                    && "creeper".equals(b.instrument()) && b.note() == 13);
            long distinctSlots = r1.blocks().stream().map(F2vPack::slotKey).distinct().count();
            if (!rockKept || distinctSlots != r1.blocks().size() || r1.blocks().isEmpty()) {
                failures.add("block rows not resolved: " + r1.blocks());
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

            // -- 14-db STATE-KEYED TINT route asserts --------------------------------
            // The visual-tints.tsv rows keyed by the STATE now also serve the
            // MODDED-texture states (the qio/sorter machine class — the texture
            // route refuses their folds: multi-texture models, two lightings on
            // one sprite). The contract: the state's composite re-hosts EVERY
            // texture ref to the state-suffixed sibling (_cimtint_s<hash8>),
            // the raw sprite keeps serving every other consumer, and a
            // per-TEXTURE row wins over a state row for the same sprite
            // (one multiply per pixel, never two — applyStateTint skips the
            // refs the texture route already baked).
            Path out6 = tmp.resolve("out6");
            Files.createDirectories(out6);
            Files.writeString(out6.resolve("visual-items.tsv"),
                    Files.readString(out.resolve("visual-items.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out6.resolve("visual-palettes.tsv"),
                    Files.readString(out.resolve("visual-palettes.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out6.resolve("visual-gallery.tsv"), GALLERY_EMISSIVE_TSV,
                    StandardCharsets.UTF_8);
            // the CANONICAL property order in the row (the walk emits
            // [lit=true,power=7]) — the stateTintFor canonical fallback
            Files.writeString(out6.resolve("visual-tints.tsv"),
                    "# 14-db state-keyed fixture\n"
                    + "fakemod:glow_bloom[power=7,lit=true]\t0.5\t1.0\t2.0\t"
                    + "fakemod:glow_bloom[lit=true,power=7]@gallery-fake:p1:c0,r0"
                    + "|n=64|class=luma-uniform\n",
                    StandardCharsets.UTF_8);
            Result r6 = build(mods, out6, readManifest(out6.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out6.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            String glow6 = r6.gallery().stream()
                    .filter(r -> r.stateId().equals("fakemod:glow_bloom[lit=true,power=7]"))
                    .findFirst()
                    .map(r -> "assets/c-imagine/models/gallery/"
                            + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1) + ".json")
                    .orElse("");
            expect(failures, !glow6.isEmpty(), "state tint: glow composite located");
            TreeMap<String, byte[]> stateTintZip = readZip(out6.resolve("pack.zip"));
            expect(failures, !glow6.isEmpty()
                            && java.util.regex.Pattern
                                    .compile("fakemod:block/glow_tex_cimtint_s[0-9a-f]{8}")
                                    .matcher(str(stateTintZip, glow6)).find(),
                    "state tint: the composite references the state-suffixed baked sprite");
            String stateSprite = null;
            if (!glow6.isEmpty()) {
                java.util.regex.Matcher m2 = java.util.regex.Pattern
                        .compile("fakemod:block/glow_tex_cimtint_s[0-9a-f]{8}")
                        .matcher(str(stateTintZip, glow6));
                if (m2.find()) {
                    stateSprite = m2.group();
                }
            }
            expect(failures, stateSprite != null,
                    "state tint: the state-suffixed sprite ref is recoverable");
            if (stateSprite != null) {
                String bakedKey6 = "assets/fakemod/textures/block/"
                        + stateSprite.substring(stateSprite.lastIndexOf('/') + 1) + ".png";
                expect(failures, stateTintZip.containsKey(bakedKey6),
                        "state tint: the baked sibling PNG rides the pack");
                if (stateTintZip.containsKey(bakedKey6)) {
                    java.awt.image.BufferedImage baked6 = decodePng(stateTintZip.get(bakedKey6));
                    java.awt.image.BufferedImage raw6 = decodePng(
                            jarEntries.get("assets/fakemod/textures/block/glow_tex.png"));
                    boolean px6 = baked6.getWidth() == raw6.getWidth()
                            && baked6.getHeight() == raw6.getHeight();
                    for (int y = 0; y < raw6.getHeight() && px6; y++) {
                        for (int x = 0; x < raw6.getWidth() && px6; x++) {
                            int ra = raw6.getRGB(x, y);
                            int ba = baked6.getRGB(x, y);
                            // raw RGB is the fixture's flat 0x4080C0: x (0.5,1.0,2.0)
                            // = (32,128,255-clamped), alpha preserved per pixel
                            int want = (ra & 0xFF000000) | (32 << 16) | (128 << 8) | 255;
                            px6 &= ba == want;
                            if (ba != want) {
                                failures.add("state tint pixel (" + x + "," + y
                                        + "): want " + Integer.toHexString(want)
                                        + " got " + Integer.toHexString(ba));
                            }
                        }
                    }
                    expect(failures, px6,
                            "state tint: baked pixels = raw x (0.5,1.0,2.0), alpha preserved");
                }
                expect(failures, java.util.Arrays.equals(
                                stateTintZip.get("assets/fakemod/textures/block/glow_tex.png"),
                                jarEntries.get("assets/fakemod/textures/block/glow_tex.png")),
                        "state tint: the RAW texture is untouched in the pack");
            }
            String rock6 = r6.gallery().stream()
                    .filter(r -> r.stateId().equals("fakemod:rock"))
                    .findFirst()
                    .map(r -> "assets/c-imagine/models/gallery/"
                            + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1) + ".json")
                    .orElse("");
            expect(failures, !rock6.isEmpty() && !str(stateTintZip, rock6).contains("_cimtint"),
                    "state tint: a state not in the table keeps its raw sprite");

            // -- 14-e167 PER-CELL MEASURED CALIBRATION asserts -------------------
            // The per-cell rows (visual-tints-cells.tsv) become per-cell
            // wrapper variants: the compound stateId = state@page:c,r, the
            // sibling sprite = _cimtint_s<hash>_p<cellkey> premultiplied by
            // THAT cell's ratio, the plain composite = untouched.
            Path out6c = tmp.resolve("out6c");
            Files.createDirectories(out6c);
            Files.writeString(out6c.resolve("visual-items.tsv"),
                    Files.readString(out.resolve("visual-items.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out6c.resolve("visual-palettes.tsv"),
                    Files.readString(out.resolve("visual-palettes.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out6c.resolve("visual-gallery.tsv"), GALLERY_EMISSIVE_TSV,
                    StandardCharsets.UTF_8);
            Files.writeString(out6c.resolve("visual-tints.tsv"),
                    Files.readString(out6.resolve("visual-tints.tsv")), StandardCharsets.UTF_8);
            // the per-cell rows: the glow state at TWO cells — the cell 1
            // ratio = (0.5,1.0,2.0) x (0.7,0.7,0.7) = (0.35,0.7,1.4), the
            // cell 2 = (0.4,0.9,1.2); both carry the same guard bars the
            // per-state fold runs.
            Files.writeString(out6c.resolve("visual-tints-cells.tsv"),
                    "# 14-e167 fixture\n"
                    + "fakemod:glow_bloom[power=7,lit=true]@gallery-fake-p0:c0,r3\t0.35\t0.7\t1.4\tn=900|class=luma+chroma\n"
                    + "fakemod:glow_bloom[power=7,lit=true]@gallery-fake-p0:c4,r7\t0.4\t0.9\t1.2\tn=800|class=luma+chroma\n",
                    StandardCharsets.UTF_8);
            Result r6c = build(mods, out6c, readManifest(out6c.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out6c.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            String glowPlain6c = r6c.gallery().stream()
                    .filter(r -> r.stateId().equals("fakemod:glow_bloom[lit=true,power=7]"))
                    .findFirst()
                    .map(r -> "assets/c-imagine/models/gallery/"
                            + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1) + ".json")
                    .orElse("");
            expect(failures, !glowPlain6c.isEmpty(), "per-cell: the plain composite rides");
            // the compound rows = the CALIBRATOR's own spelling (the
            // fixture deliberately writes a different property order than
            // the manifest — the canonical fallback binds and the rows
            // carry the row's own spelling)
            long perCellRows = r6c.gallery().stream()
                    .filter(r -> r.stateId().contains("glow_bloom")
                            && r.stateId().contains("@gallery-fake-p0:"))
                    .count();
            expect(failures, perCellRows == 2,
                    "per-cell: both compound rows ride the resolved manifest");
            TreeMap<String, byte[]> pcZip = readZip(out6c.resolve("pack.zip"));
            String pcSprite1 = null;
            String pcSprite2 = null;
            if (!glowPlain6c.isEmpty()) {
                String plainJson = str(pcZip, glowPlain6c);
                expect(failures, java.util.regex.Pattern
                                .compile("fakemod:block/glow_tex_cimtint_s[0-9a-f]{8}")
                                .matcher(plainJson).find()
                                && !plainJson.contains("_p"),
                        "per-cell: the plain composite keeps the bare suffix");
                java.util.regex.Matcher m1 = java.util.regex.Pattern
                        .compile("fakemod:block/glow_tex_cimtint_s[0-9a-f]{8}_pgallery-fake-p0_c0_r3")
                        .matcher(str(pcZip, glowPlain6c));
                // the plain wrapper must NOT reference the per-cell siblings
                expect(failures, !m1.find(),
                        "per-cell: the plain wrapper carries no per-cell refs");
            }
            for (GalleryPack.Resolved rr : r6c.gallery()) {
                if (!rr.stateId().endsWith("@gallery-fake-p0:c0,r3")) continue;
                String ref = "assets/c-imagine/models/gallery/"
                        + rr.modelRef().substring(rr.modelRef().lastIndexOf('/') + 1) + ".json";
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("fakemod:block/glow_tex_cimtint_s[0-9a-f]{8}_pgallery-fake-p0_c0_r3")
                        .matcher(str(pcZip, ref));
                if (m.find()) pcSprite1 = m.group();
            }
            for (GalleryPack.Resolved rr : r6c.gallery()) {
                if (!rr.stateId().endsWith("@gallery-fake-p0:c4,r7")) continue;
                String ref = "assets/c-imagine/models/gallery/"
                        + rr.modelRef().substring(rr.modelRef().lastIndexOf('/') + 1) + ".json";
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("fakemod:block/glow_tex_cimtint_s[0-9a-f]{8}_pgallery-fake-p0_c4_r7")
                        .matcher(str(pcZip, ref));
                if (m.find()) pcSprite2 = m.group();
            }
            expect(failures, pcSprite1 != null && pcSprite2 != null,
                    "per-cell: both variants reference their own cell-discriminated siblings");
            if (pcSprite1 != null && pcSprite2 != null) {
                java.awt.image.BufferedImage s1 = decodePng(pcZip.get("assets/fakemod/textures/block/"
                        + pcSprite1.substring(pcSprite1.lastIndexOf('/') + 1) + ".png"));
                java.awt.image.BufferedImage s2 = decodePng(pcZip.get("assets/fakemod/textures/block/"
                        + pcSprite2.substring(pcSprite2.lastIndexOf('/') + 1) + ".png"));
                java.awt.image.BufferedImage raw6c = decodePng(
                        jarEntries.get("assets/fakemod/textures/block/glow_tex.png"));
                boolean px = s1.getWidth() == raw6c.getWidth()
                        && s2.getWidth() == raw6c.getWidth();
                for (int y = 0; y < raw6c.getHeight() && px; y++) {
                    for (int x = 0; x < raw6c.getWidth() && px; x++) {
                        int ra = raw6c.getRGB(x, y);
                        int c1 = s1.getRGB(x, y);
                        int c2 = s2.getRGB(x, y);
                        int w1 = (ra & 0xFF000000)
                                | (Math.min(255, (int) Math.round(64 * 0.35)) << 16)
                                | (Math.min(255, (int) Math.round(128 * 0.7)) << 8)
                                | Math.min(255, (int) Math.round(192 * 1.4));
                        int w2 = (ra & 0xFF000000)
                                | (Math.min(255, (int) Math.round(64 * 0.4)) << 16)
                                | (Math.min(255, (int) Math.round(128 * 0.9)) << 8)
                                | Math.min(255, (int) Math.round(192 * 1.2));
                        px &= c1 == w1 && c2 == w2;
                        if (c1 != w1 || c2 != w2) {
                            failures.add("per-cell pixel (" + x + "," + y
                                    + "): want " + Integer.toHexString(w1) + "/"
                                    + Integer.toHexString(w2)
                                    + " got " + Integer.toHexString(c1) + "/"
                                    + Integer.toHexString(c2));
                        }
                    }
                }
                expect(failures, px,
                        "per-cell: each sibling = the raw x ITS OWN cell's measured ratio");
            }

            // the texture row WINS over a state row for the same sprite: the
            // composite references the plain _cimtint sibling and NO state
            // sibling ships
            Path out7 = tmp.resolve("out7");
            Files.createDirectories(out7);
            Files.writeString(out7.resolve("visual-items.tsv"),
                    Files.readString(out.resolve("visual-items.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out7.resolve("visual-palettes.tsv"),
                    Files.readString(out.resolve("visual-palettes.tsv")), StandardCharsets.UTF_8);
            Files.writeString(out7.resolve("visual-gallery.tsv"), GALLERY_EMISSIVE_TSV,
                    StandardCharsets.UTF_8);
            Files.writeString(out7.resolve("visual-tints.tsv"),
                    "fakemod:block/glow_tex\t0.5\t1.0\t2.0\tthe texture row\n"
                    + "fakemod:glow_bloom[power=7,lit=true]\t0.5\t1.0\t2.0\tthe state row\n",
                    StandardCharsets.UTF_8);
            Result r7 = build(mods, out7, readManifest(out7.resolve("visual-items.tsv")),
                    equipment, List.of(), readPaletteManifest(out7.resolve("visual-palettes.tsv")),
                    List.of(vanillaJar));
            String glow7 = r7.gallery().stream()
                    .filter(r -> r.stateId().equals("fakemod:glow_bloom[lit=true,power=7]"))
                    .findFirst()
                    .map(r -> "assets/c-imagine/models/gallery/"
                            + r.modelRef().substring(r.modelRef().lastIndexOf('/') + 1) + ".json")
                    .orElse("");
            TreeMap<String, byte[]> precZip = readZip(out7.resolve("pack.zip"));
            expect(failures, !glow7.isEmpty()
                            && str(precZip, glow7).contains("\"fakemod:block/glow_tex_cimtint\"")
                            && !str(precZip, glow7).contains("_cimtint_s"),
                    "state tint: a texture row wins — the composite keeps the plain "
                    + "_cimtint sibling, the state route skips the baked refs");
            expect(failures, precZip.keySet().stream().noneMatch(k -> k.contains("_cimtint_s")),
                    "state tint: no state-suffixed sibling when the texture row covers the sprite");

            // -- 14-df CORRIDOR RING route asserts -----------------------------------
            // Ring compensation is independent of captured vertex RGB. The
            // latter is already in tile pixels; only the additional ratio
            // belongs in a node tint. No ratio file means no node multiplier.
            Path outRing = tmp.resolve("out-ring");
            Files.createDirectories(outRing);
            Files.createDirectories(outRing.resolve("quads"));
            Files.writeString(outRing.resolve("visual-gallery.tsv"),
                    "fixture:ringblock#qringfixture\n", StandardCharsets.UTF_8);
            // 4 verts x (x y z u v): a full-face quad on the z=0 plane, uv 0..16
            String quadGeom = "\t0\t0\t0\t0\t0\t16\t0\t0\t16\t0\t16\t16\t0\t16\t16\t0\t16\t0\t0\t16";
            String ringQuadTsv = ""
                    // row 1: shaded flat (the casing class) — gate tints 0.6/0.6/0.7
                    + "fakemod:block/glow_tex\t0\t-1" + quadGeom
                    + "\t0.600\t0.600\t0.700\t0.600\t0.600\t0.700\t0.600\t0.600\t0.700\t0.600\t0.600\t0.700"
                    + "\t1.0\t1.0\t1.0\t1.0\t8\t15\n"
                    // row 2: identity flat (the fullbright frame art) — never tinted
                    + "fakemod:block/glow_tex\t0\t-1" + quadGeom
                    + "\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000\t1.000"
                    + "\t1.0\t1.0\t1.0\t1.0\t8\t15\n"
                    // row 3: shaded flat under the (15,15) mod-light override — never tinted
                    + "fakemod:block/glow_tex\t0\t-1" + quadGeom
                    + "\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800"
                    + "\t1.0\t1.0\t1.0\t1.0\t15\t15\n"
                    // row 4: shaded NON-flat (the legacy gradient refusal class) —
                    // the gate keeps it raw, the ring dims it by k
                    + "fakemod:block/glow_tex\t0\t-1" + quadGeom
                    + "\t0.600\t0.600\t0.600\t0.600\t0.600\t0.600\t0.800\t0.800\t0.800\t0.800\t0.800\t0.800"
                    + "\t1.0\t1.0\t1.0\t1.0\t8\t15\n";
            Files.writeString(outRing.resolve("quads/ringfixture.tsv"), ringQuadTsv,
                    StandardCharsets.UTF_8);
            Result rRingPlain = build(mods, outRing, List.of(), List.of(), List.of(),
                    List.of(), List.of());
            String ringRef = rRingPlain.gallery().stream()
                    .filter(r -> r.stateId().startsWith("fixture:ringblock"))
                    .findFirst().map(r -> r.modelRef()).orElse("");
            expect(failures, !ringRef.isEmpty(), "ring: the quad composite located; warnings="
                    + rRingPlain.warnings());
            JsonObject plainComposite = ringRef.isEmpty() ? null
                    : GalleryPack.itemNode(ringRef);
            java.util.function.Function<JsonObject, java.util.List<Integer>> tintValues =
                    comp -> {
                        java.util.List<Integer> vals = new ArrayList<>();
                        if (comp != null && comp.has("models")) {
                            for (var child : comp.getAsJsonArray("models")) {
                                JsonObject node = child.getAsJsonObject();
                                if (node.has("tints")) {
                                    for (var t : node.getAsJsonArray("tints")) {
                                        vals.add(t.getAsJsonObject().get("value").getAsInt());
                                    }
                                }
                            }
                        }
                        return vals;
                    };
            expect(failures, tintValues.apply(plainComposite).isEmpty(),
                    "ring: absent compensation leaves captured RGB in pixels only");
            Files.writeString(outRing.resolve("visual-rings.tsv"),
                    "# 14-df ring fixture\n"
                    + "fixture:ringblock\t0.5\t0.5\t0.5\tfixture@gallery-fake-p0:c0,r0"
                    + "|n_dim=90|n_match=120|mad=0.03|match=1.00\n",
                    StandardCharsets.UTF_8);
            Result rRing = build(mods, outRing, List.of(), List.of(), List.of(),
                    List.of(), List.of());
            JsonObject ringComposite = GalleryPack.itemNode(ringRef);
            int ringRawArgb = (255 << 24) | (128 << 16) | (128 << 8) | 128;
            java.util.List<Integer> ringTints = tintValues.apply(ringComposite);
            expect(failures, ringTints.size() == 2
                            && ringTints.stream().allMatch(value -> value == ringRawArgb),
                    "ring: flat and gradient pixels receive only the independent 0.5 ratio, got " + ringTints);

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
            expect(failures, atlas.contains("\"palette_key\": \"fakemod:gem_key\""),
                    "atlas source palette_key in the 26.3 converter space (no palettes/ segment)");
            expect(failures, atlas.contains("\"red\": \"fakemod:gem_red\""),
                    "atlas source permutation entry (26.3 converter space)");
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
            // Referenced custom directories need explicit sources on both atlases.
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/item/cool_sword_tex.png"), "item texture copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/item/parent_tex.png"), "parent texture copied");
            expect(failures, entries.containsKey(
                    "assets/fakemod/textures/deep/custom_tex.png"), "referenced deep texture retained");
            boolean warned = r1.warnings().stream()
                    .anyMatch(w -> w.contains("outside item|block dirs"));
            expect(failures, !warned, "referenced deep texture is supported");
            JsonObject single = new JsonObject();
            single.addProperty("type", "minecraft:single");
            single.addProperty("resource", "fakemod:deep/custom_tex");
            for (String referencedAtlas : List.of("blocks", "items")) {
                String atlasPath = "assets/minecraft/atlases/" + referencedAtlas + ".json";
                byte[] before = entries.get(atlasPath);
                atlasReferencedTexture("fakemod:deep/custom_tex", entries);
                expect(failures, java.util.Arrays.equals(before, entries.get(atlasPath)),
                        "repeated texture reference keeps atlas stable: " + referencedAtlas);
                int count = 0;
                for (JsonElement source : JsonParser.parseString(str(entries, atlasPath))
                        .getAsJsonObject().getAsJsonArray("sources")) {
                    if (source.equals(single)) count++;
                }
                expect(failures, count == 1, "deep texture stitched once: " + referencedAtlas);
            }
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
            JsonObject noteVariants = JsonParser.parseString(str(entries, "assets/minecraft/blockstates/note_block.json"))
                    .getAsJsonObject().getAsJsonObject("variants");
            expect(failures, new CarrierModel("fakemod:block/rock").application()
                            .equals(noteVariants.get("instrument=creeper,note=13")),
                    "note_block creeper note 13 points at the modded block model");
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
        // 14-dr: the placeholder phase = the decompiled vanilla missing
        // sprite (TL black, TR/BL pink, BR black) — the old stand-in had it
        // inverted and every missing-sprite quad rendered the opposite color
        // (the ae2:facade + fe_p2p_tunnel measured crops).
        var ph = decodePng(hostEntries.get("assets/c-imagine/textures/block/items/unhosted.png"));
        expect(failures, ph.getWidth() == 16 && ph.getHeight() == 16
                        && (ph.getRGB(0, 0) & 0xFFFFFF) == 0x000000
                        && (ph.getRGB(15, 0) & 0xFFFFFF) == 0xF800F8
                        && (ph.getRGB(0, 15) & 0xFFFFFF) == 0xF800F8
                        && (ph.getRGB(15, 15) & 0xFFFFFF) == 0x000000,
                "gallery: the placeholder matches the vanilla missing-sprite phase");
        expect(failures, hostWarnings.size() == 1 && hostWarnings.get(0).contains(trim),
                "gallery: the unhostable sprite stays loud in the build report");
        expect(failures, GalleryPack.hostSprite(List.of(), "mod:block/gone", hostEntries,
                hostWarnings).equals("mod:block/gone"),
                "gallery: a missing block-path sprite keeps its blocks-atlas ref");
        // 14-dr: an animated source ships as its FIRST ANIMATION FRAME,
        // cropped to the frame box, with NO mcmeta at all — the truth side
        // pins every animated sprite to its first frame (14-dl) while the
        // ours client's sprite clocks free-run through the walk's unfreeze
        // windows (run 42839844310 measured the singularity/mysterious-cube/
        // anomaly REDs on shipped strips). Dropping the mcmeta alone would
        // squash the strip into one smeared sprite (the measured 18-strip
        // bug), so the crop is mandatory: glow (a 16x16 with a mcmeta) stays
        // 16x16; the 16x32 dead strip (frames []) and the 16x48 live strip
        // (frames [0,2]) both crop to their first frame block at 16x16.
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
                && !hostEntries.containsKey("assets/c-imagine/textures/block/items/mod_item_glow.png.mcmeta"),
                "gallery: a re-hosted static frame ships without mcmeta");
        GalleryPack.hostSprite(List.of(animJar), "mod:item/dead_strip", hostEntries, hostWarnings);
        var deadImage = decodePng(hostEntries.get(
                "assets/c-imagine/textures/block/items/mod_item_dead_strip.png"));
        expect(failures, deadImage.getWidth() == 16 && deadImage.getHeight() == 16,
                "gallery: an invalid-frames strip crops to its 16x16 first frame");
        GalleryPack.hostSprite(List.of(animJar), "mod:item/live_strip", hostEntries, hostWarnings);
        var liveImage = decodePng(hostEntries.get(
                "assets/c-imagine/textures/block/items/mod_item_live_strip.png"));
        expect(failures, liveImage.getWidth() == 16 && liveImage.getHeight() == 16
                && !hostEntries.containsKey(
                        "assets/c-imagine/textures/block/items/mod_item_live_strip.png.mcmeta"),
                "gallery: a live strip crops to its first listed frame (0) and drops the mcmeta");
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
        itemNormalSelftest(failures);
        authoredObjItemSelftest(failures);
        carrierSelftest(failures);
        itemAllocationSelftest(failures);
        rawCaptureTextureSelftest(failures);
        capturedAlphaSelftest(failures);
        capturedEmptyMeshSelftest(failures);
        capturedSpriteSelftest(failures);
        compositeBoundsSelftest(failures);
        captureFirstPickSelftest(failures);
        capturedLightSelftest(failures);
        fluidAnimatedReceiptSelftest(failures);
        placeholderLedgerSelftest(failures);
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
            // 14-dr: the legacy (39/41-col) rows let the TEXTURE's own alpha
            // rule — the captured vertex alpha rides only the explicit
            // translucent material rows (the huge_lily_pad measured the
            // legacy zero-alpha trap: the ours cell EMPTY at ae 211987).
            // rows = {RGB-only, RGBA opaque, RGBA 0.3} -> the tile alpha is
            // the texture's 128 in every case.
            String[] rows = {face, face + rgb, face + rgb + "\t0.3".repeat(4)};
            int[] expected = {128, 128, 128};
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
            String rgba = face + rgb + "\t0.3".repeat(4);
            String zero = face + rgb + "\t0.0".repeat(4);
            String[] blockRows = {rgba, rgba + "\t15\t15", rgba + "\tsolid",
                    rgba + "\tcutout\t15\t15", rgba + "\ttranslucent", zero};
            // 14-dr: only the explicit translucent material honors the vertex
            // alpha (0.3 x the texture's 128 = 38); every legacy row and the
            // explicit solid/cutout rows now also preserve native chunk texture
            // alpha semantics: 128 is opaque in both materials. Legacy rows
            // retain their known behavior without guessing a source material.
            int[] blockAlpha = {128, 128, 255, 255, 38, 128};
            for (int i = 0; i < blockRows.length; i++) {
                ObjMesh.resetBaker();
                GalleryPack.resetModelScales();
                Files.writeString(root.resolve("alpha.tsv"), blockRows[i] + "\n");
                TreeMap<String, byte[]> entries = new TreeMap<>();
                var resolved = GalleryPack.build(List.of(jar),
                        List.of(new GalleryPack.Input("fixture:block#qalpha")), entries, new ArrayList<>());
                expect(failures, resolved.size() == 1, "block alpha: capture resolves " + i);
                ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, entries);
                JsonObject model = JsonParser.parseString(new String(entries.get(
                        "assets/c-imagine/models/gallery/g0.json"), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject f = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                        .getAsJsonObject("faces").entrySet().iterator().next().getValue().getAsJsonObject();
                String texture = model.getAsJsonObject("textures")
                        .get(f.get("texture").getAsString().substring(1)).getAsString();
                var sheet = decodePng(entries.get("assets/" + texture.replace(":", "/textures/") + ".png"));
                JsonArray uv = f.getAsJsonArray("uv");
                int x = (int) ((uv.get(0).getAsDouble() + uv.get(2).getAsDouble()) / 32 * sheet.getWidth());
                int y = (int) ((uv.get(1).getAsDouble() + uv.get(3).getAsDouble()) / 32 * sheet.getHeight());
                expect(failures, sheet.getRGB(x, y) >>> 24 == blockAlpha[i],
                        "block alpha: only explicit opaque chunk materials ignore vertex opacity " + i);
            }
        } finally {
            GalleryPack.setQuadDir(null);
            GalleryPack.resetJarCache();
            ObjMesh.resetBaker();
            deleteRecursively(root);
        }
    }

    private static void capturedEmptyMeshSelftest(List<String> failures) throws IOException {
        Path root = Files.createTempDirectory("f2v-native-empty");
        try {
            GalleryPack.setQuadDir(root);
            String[] cases = {"# cimscene native empty mesh v1\n", "",
                    "# cimscene native empty mesh v1\ncorrupt\n"};
            for (int i = 0; i < cases.length; i++) {
                GalleryPack.resetModelScales();
                Files.writeString(root.resolve("empty.tsv"), cases[i]);
                TreeMap<String, byte[]> entries = new TreeMap<>();
                List<String> warnings = new ArrayList<>();
                var rows = GalleryPack.build(List.of(), List.of(new GalleryPack.Input(
                        "fixture:door[half=upper,visible=false]#qempty")), entries, warnings);
                if (i == 0) {
                    expect(failures, rows.size() == 1 && warnings.isEmpty(),
                            "native empty: observed no-draw retains its captured context");
                    byte[] bytes = entries.get("assets/c-imagine/models/gallery/g0.json");
                    expect(failures, bytes != null, "native empty: an actual model is emitted");
                    if (bytes != null) {
                        JsonObject model = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                        expect(failures, model.getAsJsonArray("elements").isEmpty()
                                        && model.getAsJsonObject("textures").isEmpty(),
                                "native empty: no fallback geometry or placeholder texture");
                    }
                } else {
                    expect(failures, rows.isEmpty() && !warnings.isEmpty(),
                            "native empty: unmarked/truncated capture remains a failure " + i);
                }
            }
            Files.delete(root.resolve("empty.tsv"));
            List<String> warnings = new ArrayList<>();
            var missing = GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:door#qempty")),
                    new TreeMap<>(), warnings);
            expect(failures, missing.isEmpty() && !warnings.isEmpty(),
                    "native empty: missing capture never becomes an observed no-draw");
        } finally {
            GalleryPack.setQuadDir(null);
            GalleryPack.resetModelScales();
            deleteRecursively(root);
        }
    }

    private static void compositeBoundsSelftest(List<String> failures) {
        JsonArray elements = JsonParser.parseString("""
                [{"from":[-18.95,7.65807,6.28294],"to":[1.1,11.85807,6.30294],
                  "rotation":{"origin":[-7.45,9.75807,6.30294],"x":0,"y":-45,"z":-90},
                  "faces":{"south":{"uv":[0,8.5,11.5,10.5],"texture":"#rail"}}},
                 {"from":[0,0,0],"to":[16,16,16],"faces":{"north":{"texture":"#base"}}}]
                """).getAsJsonArray();
        JsonArray original = elements.deepCopy();
        double scale = GalleryPack.normalizeCompositeBounds(elements);
        expect(failures, scale == 2, "composite bounds: real track encoding fits with binary scale");
        for (int i = 0; i < elements.size(); i++) {
            JsonObject before = original.get(i).getAsJsonObject(), after = elements.get(i).getAsJsonObject();
            List<String> fields = new ArrayList<>(List.of("from", "to"));
            if (before.has("rotation")) fields.add("origin");
            for (String field : fields) {
                JsonArray a = (field.equals("origin") ? before.getAsJsonObject("rotation") : before).getAsJsonArray(field);
                JsonArray b = (field.equals("origin") ? after.getAsJsonObject("rotation") : after).getAsJsonArray(field);
                for (int axis = 0; axis < 3; axis++) {
                    double value = b.get(axis).getAsDouble();
                    expect(failures, Math.abs(8 + (value - 8) * scale - a.get(axis).getAsDouble()) < 1e-10,
                            "composite bounds: inverse transform preserves coordinate/origin");
                    if (!field.equals("origin")) expect(failures, value >= -16 && value <= 32,
                            "composite bounds: legal native from/to");
                }
            }
        }
        expect(failures, elements.get(0).getAsJsonObject().getAsJsonObject("faces").equals(
                        original.get(0).getAsJsonObject().getAsJsonObject("faces")),
                "composite bounds: explicit UV and material retained");
        expect(failures, elements.get(1).getAsJsonObject().getAsJsonObject("faces").getAsJsonObject("north")
                        .getAsJsonArray("uv").equals(JsonParser.parseString("[0.0,0.0,16.0,16.0]")),
                "composite bounds: implicit UV frozen before scaling");
        JsonArray stable = elements.deepCopy();
        expect(failures, GalleryPack.normalizeCompositeBounds(elements) == 1 && stable.equals(elements),
                "composite bounds: legal geometry stays byte-stable");
    }

    /** 14-dh THE CAPTURE-FIRST PICK: the one resolution law, held end-to-end. */
    private static void captureFirstPickSelftest(List<String> failures) {
        java.util.Map<String, GalleryPack.Resolved> byState = new java.util.HashMap<>();
        java.util.Map<String, GalleryPack.Resolved> byQuadState = new java.util.HashMap<>();
        java.util.Map<String, GalleryPack.Resolved> byQuadHash = new java.util.HashMap<>();
        GalleryPack.Resolved composite = new GalleryPack.Resolved(
                "fakemod:block/globe", CMD_BASE, "g0", 0, 0, true);
        GalleryPack.Resolved capture = new GalleryPack.Resolved(
                "fakemod:block/globe#qabc123", CMD_BASE + 1, "g1", 0, 0, true, 3, 15, 0f);
        GalleryPack.Resolved doorCapture = new GalleryPack.Resolved(
                "fakemod:block/door#qdef456", CMD_BASE + 4, "g4", 0, 0, true, 3, 15, 0f);
        GalleryPack.Resolved ct = new GalleryPack.Resolved(
                "fakemod:block/globe#i0,i1", CMD_BASE + 2, "g2", 0, 0, true);
        byState.put(composite.stateId(), composite);
        byState.put(capture.stateId(), capture);
        byState.put(ct.stateId(), ct);
        byQuadState.put("fakemod:block/globe", capture);
        byQuadHash.put("abc123", capture);
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/globe", null) == capture,
                "14-dh: the exact state's captured mesh outranks the static composite");
        GalleryPack.Resolved picked = GalleryPack.pickResolved(
                byState, byQuadState, byQuadHash, "fakemod:block/globe", null);
        expect(failures, picked.lightBlock() == 3 && picked.lightSky() == 15,
                "14-dh: the capture pick carries the 14-cy captured light pin");
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/globe", "i0,i1") == ct,
                "14-dh: the per-position CT ctx still outranks the capture (14-ak stands)");
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/other", "abc123") == capture,
                "14-dh: a ctx hash resolves through the captured mesh itself (14-bt stands)");
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/plain", null) == null,
                "14-dh: a state with no rows resolves null (the caller names the skip)");
        GalleryPack.Resolved plain = new GalleryPack.Resolved(
                "fakemod:block/plain", CMD_BASE + 3, "g3", 0, 0, true);
        byState.put("fakemod:block/plain", plain);
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/plain", null) == plain,
                "14-dh: a sibling state without its own capture rides the plain composite");

        // Native .ctx.tsv encodes q<hash>, including when one complete mesh
        // serves a different state alias. It must beat a plain-model fallback.
        byQuadHash.put("def456", doorCapture);
        byState.put("fakemod:block/door_alias", plain);
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/door_alias", "qdef456") == doorCapture,
                "native capture: q-prefixed context selects the complete mesh for a state alias");
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fakemod:block/globe", "qabc123") == capture,
                "native capture: context may select the canonical object without adding alternatives");
        GalleryPack.Resolved emptyCapture = new GalleryPack.Resolved(
                "fixture:door[half=upper,visible=false]#qa6b018eb65daa308", CMD_BASE + 5, "g5", 0, 0, true);
        byQuadHash.put("a6b018eb65daa308", emptyCapture);
        byState.put("fixture:other_empty_door", plain);
        expect(failures,
                GalleryPack.pickResolved(byState, byQuadState, byQuadHash,
                        "fixture:other_empty_door", "qa6b018eb65daa308") == emptyCapture,
                "native capture: observed empty context outranks a phantom plain model");
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
            // 14-dr: the captured strip ships CROPPED to its first listed
            // frame (frames [1,0] -> index 1 = the strip's SECOND 2x2 block)
            // with NO mcmeta at all — the truth side pins animated sprites to
            // their first listed frame (14-dl), the ours side's sprite clocks
            // free-run; a shipped strip measured RED on the singularity/
            // mysterious-cube/anomaly crops.
            var cropped = decodePng(entries.get(hosted));
            expect(failures, ref.equals("c-imagine:block/items/captured_" + hash)
                            && cropped.getWidth() == 2 && cropped.getHeight() == 2
                            && !entries.containsKey(hosted + ".mcmeta"),
                    "captured sprite: strip re-hosts as its first listed frame, mcmeta dropped");
            expect(failures, warnings.size() == 1 && warnings.get(0).contains("shipped static"),
                    "captured sprite: the crop stays loud in the build report");
            // Atlas generators can produce a real sprite with no PNG in any jar.
            Files.writeString(root.resolve("sprite-aliases.tsv"),
                    "# native sprite\tdecoded capture\nfixture:generated\t~" + hash
                            + "\nminecraft:block/chain\t~" + hash
                            + "\nfixture:block/stale\t~" + hash + "\n");
            GalleryPack.setQuadDir(root);
            TreeMap<String, byte[]> generated = new TreeMap<>();
            List<String> generatedWarnings = new ArrayList<>();
            String generatedRef = GalleryPack.hostSprite(List.of(), "fixture:generated", generated, generatedWarnings);
            expect(failures, generatedRef.equals(ref) && java.util.Arrays.equals(entries.get(hosted), generated.get(hosted))
                            && generatedWarnings.stream().noneMatch(w -> w.contains("sprite missing")),
                    "native sprite alias: generated atlas pixels and frame metadata survive absent jar PNG");
            String nativeVanilla = GalleryPack.hostSprite(List.of(), "minecraft:block/chain", generated, generatedWarnings);
            expect(failures, nativeVanilla.equals(ref) && java.util.Arrays.equals(entries.get(hosted), generated.get(hosted))
                            && generated.keySet().stream().noneMatch(path -> path.startsWith("assets/minecraft/")),
                    "native vanilla alias: source pixels retained without overriding the target client's assets");
            expect(failures, GalleryPack.hostSprite(List.of(), "minecraft:block/uncaptured", generated, generatedWarnings)
                            .equals("minecraft:block/uncaptured"),
                    "native vanilla alias: legacy captures retain their existing asset lookup");
            Path staleJar = root.resolve("stale.jar");
            writeJar(staleJar, new TreeMap<>(Map.of("assets/fixture/textures/block/stale.png", f2vSelftestPng(16, 16))));
            var observed = new TreeMap<String, byte[]>();
            String observedRef = GalleryPack.hostSprite(List.of(staleJar), "fixture:block/stale", observed, new ArrayList<>());
            expect(failures, observedRef.equals(ref) && java.util.Arrays.equals(entries.get(hosted), observed.get(hosted)),
                    "native sprite alias: observed runtime pixels win over a stale physical jar texture");
            Files.delete(textures.resolve(hash + ".png"));
            boolean missingPixelsRefused = false;
            try {
                GalleryPack.hostSprite(List.of(), "fixture:generated", generated, new ArrayList<>());
            } catch (IOException expected) {
                missingPixelsRefused = true;
            }
            expect(failures, missingPixelsRefused, "native sprite alias: missing decoded pixels never become a placeholder");
            Files.write(textures.resolve(hash + ".png"), png);
            Files.writeString(root.resolve("sprite-aliases.tsv"), "fixture:generated\t~../../outside\n");
            GalleryPack.setQuadDir(root);
            boolean malformedRefused = false;
            try {
                GalleryPack.hostSprite(List.of(), "fixture:generated", generated, new ArrayList<>());
            } catch (IOException expected) {
                malformedRefused = true;
            }
            expect(failures, malformedRefused, "native sprite alias: malformed ledger refused");
            GalleryPack.setQuadDir(root);
            for (String missing : List.of("~fedcba9876543210", "~../../outside")) {
                boolean refused = false;
                try {
                    GalleryPack.hostSprite(List.of(), missing, entries, warnings);
                } catch (IOException expected) {
                    refused = true;
                }
                expect(failures, refused, "captured sprite: absent/invalid artifact fails: " + missing);
            }
            // A single native constant pixel previously disabled mips for
            // every other block sprite (real 26.3 client dump, gallery099).
            String pixelHash = "0000000000000001";
            Path pixelFile = textures.resolve(pixelHash + ".png");
            for (int color : new int[]{0xffffffff, 0x8133aa77, 0x00ffffff}) {
                var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, color);
                var bytes = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", bytes);
                Files.write(pixelFile, bytes.toByteArray());
                var pixelEntries = new TreeMap<String, byte[]>();
                String pixelRef = GalleryPack.hostSprite(List.of(), "~" + pixelHash, pixelEntries, new ArrayList<>());
                var tile = decodePng(pixelEntries.get("assets/c-imagine/textures/block/items/captured_" + pixelHash + ".png"));
                expect(failures, tile.getWidth() == 16 && tile.getHeight() == 16,
                        "captured pixel: atlas retains mip level 4");
                boolean identical = true;
                for (int y = 0; y < tile.getHeight(); y++) {
                    for (int x = 0; x < tile.getWidth(); x++) identical &= tile.getRGB(x, y) == color;
                }
                expect(failures, identical && pixelRef.endsWith(pixelHash),
                        "captured pixel: constant native RGBA and sprite identity preserved");
            }
        } finally {
            GalleryPack.setQuadDir(null);
            deleteRecursively(root);
        }
    }

    /** True when any composite child carries a tint node (the 14-da gate checks). */
    private static boolean nodeHasConstantTint(JsonObject node) {
        if (node == null || !node.has("models")) {
            return false;
        }
        for (var m : node.getAsJsonArray("models")) {
            if (m.getAsJsonObject().has("tints")) {
                return true;
            }
        }
        return false;
    }

    /** 14-dz THE CAPTURED-LIGHT RIDE-THROUGH: build -> manifest -> read, end to end. */
    private static void capturedLightSelftest(List<String> failures) throws IOException {
        Path root = Files.createTempDirectory("f2v-captured-light");
        try {
            var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) image.setRGB(x, y, 0xFFC8C8C8);
            }
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            Path jar = root.resolve("fixture.jar");
            writeJar(jar, new TreeMap<>(Map.of("assets/fixture/textures/block/glass.png", png.toByteArray())));
            String face = "fixture:block/glass\t1\t-1"
                    + "\t0\t0\t0\t0\t0\t16\t0\t0\t16\t0"
                    + "\t16\t16\t0\t16\t16\t0\t16\t0\t0\t16";
            String rgb = "\t1\t1\t1".repeat(4);
            String alpha = "\t1".repeat(4);
            // The 42-col shape the hourglass capture emits: material tag +
            // the packed light tail (block=13, sky=15 — the .light field's
            // own pair, never (15,15) so the fullbright census stays off).
            String lit = face + rgb + alpha + "\tcutout\t13\t15";
            // A legacy 39-col row (no material, no light) keeps the old pin.
            String legacy = face + rgb + alpha;
            ObjMesh.resetBaker();
            GalleryPack.resetJarCache();
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(root);
            Files.writeString(root.resolve("litmesh.tsv"), lit + "\n" + legacy + "\n");
            TreeMap<String, byte[]> entries = new TreeMap<>();
            List<String> warnings = new ArrayList<>();
            var rows = GalleryPack.build(List.of(jar),
                    List.of(new GalleryPack.Input("fixture:lit#qlitmesh")), entries, warnings);
            expect(failures, rows.size() == 1, "captured light: the mesh resolves one row");
            expect(failures, rows.get(0).lightBlock() == 13 && rows.get(0).lightSky() == 15,
                    "captured light: the build passes the mesh's dominant pair to the row (was dropped)");
            Path manifest = root.resolve("visual-gallery.resolved.tsv");
            GalleryPack.writeResolvedManifest(manifest, rows);
            List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.startsWith("#")).toList();
            expect(failures, lines.size() == 1 && lines.get(0).split("\t", -1).length == 8,
                    "captured light: the manifest row carries the 8-column shape");
            var back = GalleryPack.readResolvedManifest(manifest);
            expect(failures, back.size() == 1
                            && back.get(0).lightBlock() == 13 && back.get(0).lightSky() == 15,
                    "captured light: the manifest round-trips the pair (the 14-cy pin fires)");
            // A light-free mesh keeps the legacy 6-column manifest byte shape.
            Files.writeString(root.resolve("darkmesh.tsv"), legacy + "\n");
            ObjMesh.resetBaker();
            GalleryPack.resetModelScales();
            var dark = GalleryPack.build(List.of(jar),
                    List.of(new GalleryPack.Input("fixture:dark#qdarkmesh")), new TreeMap<>(), new ArrayList<>());
            expect(failures, dark.size() == 1
                            && dark.get(0).lightBlock() == -1 && dark.get(0).lightSky() == -1,
                    "captured light: a mesh without lit rows keeps the legacy pin");
            GalleryPack.writeResolvedManifest(manifest, dark);
            List<String> darkLines = Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                    .filter(l -> !l.startsWith("#")).toList();
            expect(failures, darkLines.size() == 1 && darkLines.get(0).split("\t", -1).length == 6,
                    "captured light: the legacy manifest stays 6 columns");
            expect(failures, GalleryPack.readResolvedManifest(manifest).get(0).lightBlock() == -1,
                    "captured light: the legacy manifest reads back the legacy pin");
        } finally {
            GalleryPack.setQuadDir(null);
            GalleryPack.resetModelScales();
            deleteRecursively(root);
        }
    }

    /** 14-dz2 THE FLUID ANIMATED RECEIPT: the scan records the still strips
     *  the fluid channel crops (the mcmeta proves the animation), the drain
     *  carries the fluid ids the join's 14-dx-f law matches. */
    private static void fluidAnimatedReceiptSelftest(List<String> failures) throws IOException {
        Path root = Files.createTempDirectory("f2v-fluid-anim");
        try {
            // A 16x48 vertical strip = 3 frames; the mcmeta sibling IS the
            // animation proof. A second 16x48 strip WITHOUT the mcmeta must
            // stay unrecorded (zero pixel heuristics).
            var strip = new java.awt.image.BufferedImage(16, 48,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 16; x++) strip.setRGB(x, y, 0xFF204080);
            }
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(strip, "png", png);
            Path jar = root.resolve("fixture.jar");
            writeJar(jar, new TreeMap<>(Map.of(
                    "assets/fixture/textures/fluid/goo_still.png", png.toByteArray(),
                    "assets/fixture/textures/fluid/goo_still.png.mcmeta",
                    "{\"animation\":{\"frametime\":4}}".getBytes(StandardCharsets.UTF_8),
                    "assets/fixture/textures/fluid/calm_still.png", png.toByteArray())));
            GalleryPack.resetAnimatedStates();
            TreeMap<String, byte[]> entries = new TreeMap<>();
            List<FluidTexture> textures = scanFluidTextures(List.of(jar), entries, new ArrayList<>());
            expect(failures, textures.stream().anyMatch(t -> t.fluidId().equals("fixture:goo")),
                    "fluid anim: the strip's fluid id joins the texture channel");
            var drained = new java.util.LinkedHashMap<String, Integer>();
            for (String[] row : GalleryPack.drainAnimatedStates()) {
                drained.put(row[0], Integer.parseInt(row[1]));
            }
            expect(failures, Integer.valueOf(3).equals(drained.get("fixture:goo")),
                    "fluid anim: the mcmeta-proven strip records 3 frames (was silent)");
            expect(failures, !drained.containsKey("fixture:calm"),
                    "fluid anim: a strip without the mcmeta stays unrecorded");
        } finally {
            GalleryPack.resetAnimatedStates();
            deleteRecursively(root);
        }
    }

    private static void placeholderLedgerSelftest(List<String> failures) throws IOException {
        Path quads = Files.createTempDirectory("f2v-ph-selftest");
        try {
            // a captured quad whose sprite has NO png in the jars and a
            // non-block path (the items-atlas family): the placeholder branch
            String ghostFace = "fakemod:items/ghost\t0\t-1"
                    + "\t0\t0\t0\t0\t16\t1\t0\t0\t16\t16"
                    + "\t1\t1\t0\t16\t0\t0\t1\t0\t0\t0\n";
            Files.writeString(quads.resolve("ghost.tsv"), ghostFace);
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(quads);
            TreeMap<String, byte[]> entries = new TreeMap<>();
            List<String> warnings = new ArrayList<>();
            List<GalleryPack.Resolved> rows = GalleryPack.build(List.of(),
                    List.of(new GalleryPack.Input("fixture:ghosted#qghost")), entries, warnings,
                    Map.of(), new ArrayList<>());
            expect(failures, rows.size() == 1,
                    "placeholder: the ghost-sprite capture resolves to the placeholder composite");
            expect(failures, warnings.stream().anyMatch(w ->
                            w.equals("gallery: quad sprite missing: fakemod:items/ghost")),
                    "placeholder: the warning stays (the diagnostic is visible)");
            var drained = GalleryPack.drainPlaceholderUses();
            expect(failures, drained.size() == 1
                            && "fakemod:items/ghost".equals(drained.getFirst()[0])
                            && "fixture:ghosted#qghost".equals(drained.getFirst()[1])
                            && "1".equals(drained.getFirst()[2]),
                    "placeholder: the ledger names the sprite + the state context");
            Path tsv = quads.resolve("placeholders.tsv");
            GalleryPack.writePlaceholderLedger(drained, tsv);
            List<String> lines = Files.readAllLines(tsv);
            expect(failures, lines.size() == 2
                            && lines.getFirst().equals("sprite\tcontext\tuses")
                            && lines.get(1).equals("fakemod:items/ghost\tfixture:ghosted#qghost\t1"),
                    "placeholder: the ledger TSV carries the header + the named row");
            expect(failures, GalleryPack.drainPlaceholderUses().isEmpty(),
                    "placeholder: the drain clears the accumulator");
        } finally {
            GalleryPack.resetModelScales();
            deleteRecursively(quads);
        }
    }

    private static void capturedMaterialAlphaSelftest(List<String> failures) throws IOException {
        Path quads = Files.createTempDirectory("f2v-material-alpha");
        try {
            ObjMesh.resetBaker();
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(quads);
            var entries = new TreeMap<String, byte[]>();
            var image = new java.awt.image.BufferedImage(16, 16,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            int[] alphas = {0, 25, 26, 128, 255};
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    image.setRGB(x, y, (alphas[Math.min(4, x / 3)] << 24) | 0x123456);
                }
            }
            var png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            String sprite = "fixture:block/shared_alpha";
            entries.put(GalleryPack.spritePathPng(sprite), png.toByteArray());
            String geometry = sprite + "\t0\t-1"
                    + "\t0\t0\t0\t0\t16\t1\t0\t0\t16\t16"
                    + "\t1\t1\t0\t16\t0\t0\t1\t0\t0\t0"
                    + "\t1".repeat(12) + "\t1".repeat(4);
            StringBuilder mesh = new StringBuilder();
            String[] materials = {"solid", "cutout", "translucent"};
            for (String material : materials) mesh.append(geometry).append('\t').append(material)
                    .append("\t0\t15\n");
            Files.writeString(quads.resolve("alpha.tsv"), mesh.toString());
            var rows = GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:alpha#qalpha")),
                    entries, new ArrayList<>(), Map.of(), new ArrayList<>());
            expect(failures, rows.size() == 1, "material alpha: shared-sprite fixture resolves");
            if (rows.isEmpty()) return;
            ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, entries);
            JsonObject node = GalleryPack.itemNode(rows.getFirst().modelRef());
            expect(failures, node.getAsJsonArray("models").size() == 3,
                    "material alpha: each native material retains its face");
            for (int i = 0; i < materials.length; i++) {
                JsonObject draw = node.getAsJsonArray("models").get(i).getAsJsonObject();
                JsonObject model = JsonParser.parseString(new String(entries.get("assets/"
                        + draw.get("model").getAsString().replace(":", "/models/") + ".json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject face = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                        .getAsJsonObject("faces").getAsJsonObject("south");
                String texture = model.getAsJsonObject("textures")
                        .get(face.get("texture").getAsString().substring(1)).getAsString();
                var sheet = decodePng(entries.get(GalleryPack.spritePathPng(texture)));
                JsonArray uv = face.getAsJsonArray("uv");
                double u0 = uv.get(0).getAsDouble(), u1 = uv.get(2).getAsDouble();
                double v0 = uv.get(1).getAsDouble(), v1 = uv.get(3).getAsDouble();
                for (int j = 0; j < alphas.length; j++) {
                    int x = (int) Math.floor((u0 + (j * 3 + 1.5) / 16 * (u1 - u0)) * sheet.getWidth() / 16);
                    int y = (int) Math.floor((v0 + v1) * .5 * sheet.getHeight() / 16);
                    int pixel = sheet.getRGB(x, y);
                    int expected = i == 0 ? 255 : i == 1 ? (alphas[j] >= 26 ? 255 : 0) : alphas[j];
                    expect(failures, (pixel >>> 24) == expected && (pixel & 0x00ffffff) == 0x123456,
                            "material alpha: native " + materials[i] + " alpha=" + alphas[j]
                                    + " preserves RGB and effective opacity, got " + Integer.toHexString(pixel));
                }
            }
            expect(failures, java.util.Arrays.equals(entries.get(GalleryPack.spritePathPng(sprite)), png.toByteArray()),
                    "material alpha: shared raw sprite is not modified");
        } finally {
            ObjMesh.resetBaker();
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(null);
            deleteRecursively(quads);
        }
    }

    /**
     * The item-channel composite for one model, or null (the raw model stays) when the composite
     * builder cannot read it. Allocated items and display blocks reach every model a modpack
     * ships, including NeoForge forms the gallery manifest never fed it (measured: a texture
     * entry given as an object aborted the whole gallery seed build); one model must not.
     */
    private static String safeItemComposite(List<Path> jars, String ref, int no,
                                            TreeMap<String, byte[]> entries, List<String> warnings) throws IOException {
        return safeItemComposite(jars, ref, 0, 0, false, no, entries, warnings);
    }

    private static String safeItemComposite(List<Path> jars, String ref, int xRot, int yRot, boolean uvlock, int no,
                                            TreeMap<String, byte[]> entries, List<String> warnings) throws IOException {
        try {
            return GalleryPack.synthesizeItemComposite(jars, ref, xRot, yRot, uvlock, no, entries, warnings);
        } catch (RuntimeException e) {
            warnings.add("item composite skipped for " + ref + ": " + e);
            return null;
        }
    }

    /**
     * The mushroom family and the back-compatible note keys: the key/state round trip, the
     * ledger text, the opt-in planner, and the multipart rewrite evaluated mask by mask.
     */
    private static void mushroomSelftest(List<String> failures) {
        VisualBlock note = new VisualBlock("fakemod:n", "harp", 5);
        expect(failures, "harp:5".equals(note.key()) && "harp:5".equals(carrierKeyOf(carrierState(note)))
                        && carrierState(note).equals("minecraft:note_block[instrument=harp,note=5]"),
                "note key and state keep the note_block form");
        expect(failures, "harp:5".equals(carrierKeyOf("minecraft:note_block[instrument=harp,note=5,powered=true]")),
                "a powered note block reads the same note key");
        expect(failures, carrierKeyOf("minecraft:stone") == null, "a plain block is no carrier");
        VisualBlock north = new VisualBlock("fakemod:m", "brown_mushroom_block", 1);
        expect(failures, "brown_mushroom_block:100000".equals(north.key())
                        && north.isMushroom() && !note.isMushroom(),
                "a mushroom key carries its six face bits, NESWUD order");
        expect(failures, "minecraft:brown_mushroom_block[north=true,east=false,south=false,west=false,up=false,down=false]"
                        .equals(carrierState(north)), "a mushroom carrier is placed with its six faces");
        expect(failures, "brown_mushroom_block:100000".equals(carrierKeyOf(carrierState(north))),
                "a placed mushroom reads back to its key");
        expect(failures, parseBlockRows(List.of("fakemod:n\tharp\t5")).equals(List.of(note)),
                "a three-column note ledger row parses as before");
        List<VisualBlock> rows = parseBlockRows(List.of("fakemod:m\tbrown_mushroom_block\t100000",
                "fakemod:bad\tbrown_mushroom_block\t10000", "fakemod:x\tred_mushroom_block\t1x0000"));
        expect(failures, rows.equals(List.of(north)), "a mushroom row needs six bits; bad rows drop");
        List<VisualBlock> round = parseBlockRows(List.of(blockRowsText("", List.of(note, north)).split("\n")));
        expect(failures, round.equals(List.of(north, note)), "the ledger text round-trips note and mushroom rows");
        expect(failures, parseBlockRows(List.of("fakemod:def\tbrown_mushroom_block\t111111")).isEmpty(),
                "a manifest mushroom row on the placed default is refused");
        expect(failures, MUSHROOM_CAPACITY == 186, "three hosts, sixty-two masks each");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CARRIER_CAPACITY + 3; i++) {
            many.add(String.format("fakemod:b%04d", i));
        }
        CarrierPlan off = planCarriers(many, List.of(), List.of(), false);
        expect(failures, off.effective().size() == CARRIER_CAPACITY + 3
                        && off.effective().stream().noneMatch(VisualBlock::isMushroom)
                        && off.effective().stream().filter(VisualBlock::isDisplay).count() == 3,
                "the switch off: the note slots, then the display family takes the three overflow blocks");
        CarrierPlan over = planCarriers(many, List.of(), List.of(), true);
        expect(failures, over.effective().size() == CARRIER_CAPACITY + 3
                        && over.effective().stream().filter(VisualBlock::isMushroom).count() == 3
                        && over.effective().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:b0648")
                        && b.key().equals("brown_mushroom_block:100000")),
                "the first mushroom slot is brown mask 1, after every note slot");
        expect(failures, over.effective().stream().filter(VisualBlock::isMushroom)
                        .allMatch(b -> b.note() >= MUSHROOM_MASK_MIN && b.note() <= MUSHROOM_MASK_MAX),
                "no mushroom slot is ever the placed default (63) or the all-false interior (0)");
        CarrierPlan again = planCarriers(many, List.of(), over.ledger(), true);
        expect(failures, again.effective().equals(over.effective()), "a mushroom slot survives a restart");
        CarrierPlan dropped = planCarriers(List.of("fakemod:z"), List.of(),
                List.of(new VisualBlock("fakemod:z", "brown_mushroom_block", 0)), false);
        expect(failures, dropped.effective().stream().noneMatch(VisualBlock::isMushroom)
                        && dropped.notes().stream().anyMatch(n -> n.contains("malformed")),
                "a ledger mushroom row on the default state is malformed, not kept");

        // the rewrite: every mask, evaluated against the output parts, matches vanilla except the carriers
        StringBuilder vanilla = new StringBuilder("{\"multipart\":[");
        for (int i = 0; i < MUSHROOM_FACES.size(); i++) {
            String f = MUSHROOM_FACES.get(i);
            vanilla.append(i == 0 ? "" : ",")
                    .append("{\"apply\":{\"model\":\"minecraft:block/skin_").append(f).append("\"},\"when\":{\"")
                    .append(f).append("\":\"true\"}},")
                    .append("{\"apply\":{\"model\":\"minecraft:block/inside_").append(f).append("\"},\"when\":{\"")
                    .append(f).append("\":\"false\"}}");
        }
        vanilla.append("]}");
        Map<Integer, CarrierModel> carriers = Map.of(1, new CarrierModel("fakemod:block/c1"),
                42, new CarrierModel("fakemod:block/c42", 90, 270, true),
                62, new CarrierModel("fakemod:block/c62"));
        String out = mushroomBlockState(vanilla.toString(), carriers);
        expect(failures, out != null, "the vanilla multipart rewrites");
        if (out != null) {
            JsonObject root = JsonParser.parseString(out).getAsJsonObject();
            boolean ok = true;
            for (int mask = 0; mask < 64; mask++) {
                List<String> got = new ArrayList<>();
                for (JsonElement el : root.getAsJsonArray("multipart")) {
                    JsonObject part = el.getAsJsonObject();
                    if (partMatches(part.get("when"), mask)) {
                        got.add(part.getAsJsonObject("apply").get("model").getAsString());
                    }
                }
                List<String> want = new ArrayList<>();
                if (carriers.containsKey(mask)) {
                    want.add(carriers.get(mask).ref());
                } else {
                    for (int i = 0; i < MUSHROOM_FACES.size(); i++) {
                        boolean on2 = (mask >> i & 1) == 1;
                        want.add("minecraft:block/" + (on2 ? "skin_" : "inside_") + MUSHROOM_FACES.get(i));
                    }
                }
                Collections.sort(got);
                Collections.sort(want);
                if (!got.equals(want)) {
                    ok = false;
                }
            }
            expect(failures, ok, "the rewritten blockstate renders vanilla on every non-carrier mask and the carrier alone on its own");
        }
        expect(failures, mushroomBlockState("{\"variants\":{}}", carriers) == null,
                "a variants blockstate is not rewritten (the carriers drop instead)");
    }

    /** Evaluates a multipart {@code when} (absent, one property map, or {"OR": [maps]}) against a mushroom mask. */
    private static boolean partMatches(JsonElement when, int mask) {
        if (when == null) {
            return true;
        }
        JsonObject w = when.getAsJsonObject();
        if (w.has("OR")) {
            for (JsonElement alt : w.getAsJsonArray("OR")) {
                if (partMatches(alt, mask)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonElement> c : w.entrySet()) {
            boolean bit = (mask >> MUSHROOM_FACES.indexOf(c.getKey()) & 1) == 1;
            if (bit != c.getValue().getAsString().equals("true")) {
                return false;
            }
        }
        return true;
    }

    /** Carrier allocation: the pure planner, stability, manifest precedence, overflow, blockstate default. */
    /** The allocated-item channel: keys, candidates, the jar scan and the ledger, all pure over fixtures. */
    private static void itemAllocationSelftest(List<String> failures) throws IOException {
        expect(failures, allocatedItemModel("create:brass/ingot").equals("c-imagine:create/brass/ingot"),
                "allocated item_model key is c-imagine:<ns>/<path>");
        expect(failures, "create:brass/ingot".equals(moddedIdOfItemModel("c-imagine:create/brass/ingot")),
                "the item_model key inverts to the mod id");
        expect(failures, moddedIdOfItemModel("minecraft:paper") == null,
                "a vanilla item_model is not an allocated id");
        expect(failures, moddedIdOfItemModel("c-imagine:create") == null,
                "a c-imagine key without a path is not an allocated id");
        expect(failures, allocatedItemDefPath("create:brass_ingot").equals("assets/c-imagine/items/create/brass_ingot.json"),
                "the allocated items/ definition path");
        expect(failures, allocatedItemName("create:brass/ingot_block").equals("Ingot Block"),
                "the allocated item name is the title-cased leaf of the id");
        List<String> candidates = itemCandidates(
                List.of("create:wrench", "fakemod:gem", "minecraft:paper", "c-imagine:x/y", "create:manual_row",
                        "Bad:Id", "create:../x", "nocolon", "fakemod:gem"),
                List.of("create:manual_row"));
        expect(failures, candidates.equals(List.of("create:wrench", "fakemod:gem")),
                "candidates are plain, unowned, not vanilla, not ours, sorted and unique");
        expect(failures, new ItemTally(3, 2, 1).resolved() == 5, "the tally resolves manifest plus allocated");
        Path tmp = Files.createTempDirectory("f2v-items-selftest");
        try {
            TreeMap<String, byte[]> jarEntries = new TreeMap<>();
            jarEntries.put("assets/fakemod/models/item/gem.json", "{}".getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/item/sub/deep.json", "{}".getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/models/block/rock.json", "{}".getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/textures/item/gem.png", "x".getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/minecraft/models/item/stone.json", "{}".getBytes(StandardCharsets.UTF_8));
            Path jar = tmp.resolve("items-fixture-1.0.jar");
            writeJar(jar, jarEntries);
            expect(failures, discoverItemIds(List.of(jar)).equals(Set.of("fakemod:gem", "fakemod:sub/deep")),
                    "the jar scan finds item models only: no block, texture or vanilla model");
            List<String> ids = List.of("fakemod:gem", "fakemod:sub/deep");
            Files.writeString(tmp.resolve(ALLOCATED_ITEMS), allocatedText(ids), StandardCharsets.UTF_8);
            expect(failures, readAllocatedItems(tmp).equals(ids),
                    "the allocated ledger round-trips through its text form");
            expect(failures, readAllocatedItems(tmp.resolve("absent")).isEmpty(),
                    "a missing ledger reads as no allocated items");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void carrierSelftest(List<String> failures) throws IOException {
        mushroomSelftest(failures);
        carrierTransformSelftest(failures);
        VisualBlock manifestRock = new VisualBlock("fakemod:rock", "creeper", 13);
        // an earlier boot's ledger: old keeps harp:5; gone is in no jar any more
        List<VisualBlock> ledger = List.of(new VisualBlock("fakemod:old", "harp", 5),
                new VisualBlock("fakemod:gone", "trumpet", 3));
        List<String> found = List.of("fakemod:rock", "fakemod:new", "fakemod:old");
        CarrierPlan p = planCarriers(found, List.of(manifestRock), ledger);
        expect(failures, p.effective().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:rock")
                && slotKey(b).equals("creeper:13")), "manifest row keeps its slot");
        expect(failures, p.effective().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:old")
                && slotKey(b).equals("harp:5")), "ledger slot survives a restart");
        expect(failures, p.effective().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:new")
                && slotKey(b).equals("harp:1")), "a new block takes the lowest free slot");
        expect(failures, p.effective().size() == 3, "a block no jar carries is reserved, not emitted");
        expect(failures, p.ledger().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:gone")
                && slotKey(b).equals("trumpet:3")), "a vanished block keeps its reservation");
        CarrierPlan again = planCarriers(found, List.of(manifestRock), p.ledger());
        expect(failures, again.effective().equals(p.effective()) && again.ledger().equals(p.ledger()),
                "a second plan from the written ledger is identical");
        CarrierPlan taken = planCarriers(List.of("fakemod:y"), List.of(new VisualBlock("fakemod:x", "harp", 1)),
                List.of(new VisualBlock("fakemod:y", "harp", 1)));
        expect(failures, taken.effective().size() == 2 && taken.effective().stream()
                .filter(b -> b.moddedBlockId().equals("fakemod:y")).allMatch(b -> !slotKey(b).equals("harp:1")),
                "a manifest row takes a ledger slot and the ledger block moves");
        expect(failures, taken.notes().stream().anyMatch(n -> n.contains("reallocated")), "the move is logged");
        CarrierPlan tier0 = planCarriers(List.of("ae2:a_block", "create:z_block"), List.of(), List.of());
        expect(failures, tier0.effective().stream().anyMatch(b -> b.moddedBlockId().equals("create:z_block")
                && slotKey(b).equals("harp:1")), "a Tier 0 mod takes the first slot ahead of ae2");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < CARRIER_CAPACITY + 5; i++) {
            many.add(String.format("fakemod:b%04d", i));
        }
        CarrierPlan full = planCarriers(many, List.of(), List.of());
        expect(failures, full.effective().stream().filter(b -> !b.isDisplay()).count() == CARRIER_CAPACITY,
                "the note family stops at the render capacity");
        expect(failures, full.effective().stream().noneMatch(b -> b.note() == 0 && !b.isDisplay()),
                "note 0 is never handed out");
        expect(failures, full.effective().size() == CARRIER_CAPACITY + 5,
                "the overflow joins the display family: every discovered block is emitted, none unplaceable");
        expect(failures, full.effective().stream().filter(VisualBlock::isDisplay).count() == 5,
                "exactly the five overflow blocks are display rows");
        expect(failures, full.notes().stream().noneMatch(n -> n.contains("no free")),
                "a full note family no longer leaves a warning behind");
        expect(failures, planCarriers(many, List.of(), List.of()).equals(full),
                "the display family plan is deterministic");
        // display rows: ledger round trip, a display row never takes a slot, a vanished one is reserved
        List<VisualBlock> rows = parseBlockRows(List.of("fakemod:disp\tdisplay\t0", "fakemod:harpy\tharp\t7",
                "bad_id\tdisplay\t0", "# comment"));
        expect(failures, rows.size() == 2 && rows.get(0).isDisplay() && rows.get(0).key().equals("display:fakemod:disp"),
                "a display row parses to the display family; a row without a namespace is dropped");
        expect(failures, blockRowsText("", List.of(new VisualBlock("fakemod:disp", DISPLAY_FAMILY, 0)))
                .equals("fakemod:disp\tdisplay\t0\n"), "a display row is written as id, display, 0");
        expect(failures, blockRowsText("", List.of(new VisualBlock("fakemod:harp", "harp", 5)))
                .equals("fakemod:harp\tharp\t5\n"), "a note row is written byte-identical to before");
        CarrierPlan disp = planCarriers(List.of("fakemod:disp", "fakemod:harp"), List.of(), rows);
        expect(failures, disp.effective().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:disp") && b.isDisplay())
                && disp.effective().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:harp") && slotKey(b).equals("harp:1")),
                "a ledger display row stays on the display family and holds no note slot (the first free slot is still harp:1)");
        CarrierPlan gone = planCarriers(List.of("fakemod:harp"), List.of(), rows);
        expect(failures, gone.ledger().stream().anyMatch(b -> b.moddedBlockId().equals("fakemod:disp") && b.isDisplay())
                && gone.effective().stream().noneMatch(b -> b.moddedBlockId().equals("fakemod:disp")),
                "a display block no jar carries any more is reserved, not emitted");
        expect(failures, carrierState(new VisualBlock("fakemod:disp", DISPLAY_FAMILY, 0)).equals("minecraft:barrier"),
                "a display block stands as a barrier host");
        expect(failures, displayItemModel("create:andesite/casing").equals("c-imagine:_display/create/andesite/casing")
                        && "create:andesite/casing".equals(moddedIdOfDisplayItemModel(displayItemModel("create:andesite/casing")))
                        && moddedIdOfDisplayItemModel("c-imagine:create/andesite") == null
                        && moddedIdOfItemModel(displayItemModel("create:x")) == null,
                "the display item_model key inverts to its mod id and is not an allocated item key");
        expect(failures, displayItemDefPath("create:depot").equals("assets/c-imagine/items/_display/create/depot.json"),
                "the display items/ definition path");
        expect(failures, new CarrierModel("c:d").equals(defaultBlockstateModel(
                "{\"variants\":{\"facing=north\":{\"model\":\"a:b\"},\"\":{\"model\":\"c:d\"}}}")),
                "the empty variant key supplies the representative model");
        expect(failures, new CarrierModel("e:f").equals(defaultBlockstateModel(
                "{\"variants\":{\"facing=west\":[{\"model\":\"a:b\"}],\"facing=north\":{\"model\":\"e:f\"}}}")),
                "without an empty key the first sorted variant wins");
        expect(failures, new CarrierModel("x:y", 270, 0, true).equals(defaultBlockstateModel(
                "{\"multipart\":[{\"apply\":[{\"model\":\"x:y\",\"x\":-90,\"uvlock\":true},{\"model\":\"z:w\"}]}]}")),
                "multipart takes the first model application and normalizes signed rotation");
        expect(failures, new CarrierModel("x:y", 0, 90, false).equals(defaultBlockstateModel(
                "{\"variants\":{\"\":{\"model\":\"x:y\",\"x\":{},\"y\":90}}}")),
                "malformed rotation metadata leaves the usable model and valid angle in place");
        expect(failures, defaultBlockstateModel("{") == null && defaultBlockstateModel("[]") == null,
                "malformed blockstates resolve to no model");
    }

    private static void carrierTransformSelftest(List<String> failures) throws IOException {
        Path tmp = Files.createTempDirectory("f2v-carrier-transform");
        try {
            Path mods = Files.createDirectory(tmp.resolve("mods"));
            Path out = Files.createDirectory(tmp.resolve("out"));
            var jarEntries = new TreeMap<String, byte[]>();
            String model = """
                    {"textures":{"top":"fixture:block/top"},
                     "elements":[{"from":[1,2,3],"to":[4,6,8],"faces":{"up":{"texture":"#top"}}}]}
                    """;
            String app = "{\"model\":\"fixture:block/shape\",\"x\":90,\"y\":90,\"uvlock\":true}";
            String state = "{\"variants\":{\"axis=x\":" + app + ",\"axis=y\":{\"model\":\"fixture:block/shape\"}}}";
            for (String id : List.of("note", "mushroom", "display")) {
                jarEntries.put("assets/fixture/blockstates/" + id + ".json", state.getBytes(StandardCharsets.UTF_8));
            }
            jarEntries.put("assets/fixture/models/block/shape.json", model.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fixture/models/block/fallback.json", model.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fixture/textures/block/top.png", testPng());
            jarEntries.put("assets/fixture/blockstates/fallback.json", """
                    {"variants":{"":{"model":"fixture:block/missing","x":90,"uvlock":true}}}
                    """.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/minecraft/blockstates/brown_mushroom_block.json", """
                    {"multipart":[{"when":{"north":"true"},"apply":{"model":"minecraft:block/brown_mushroom_block"}}]}
                    """.getBytes(StandardCharsets.UTF_8));
            writeJar(mods.resolve("fixture.jar"), jarEntries);
            Files.writeString(out.resolve("visual-blocks.tsv"),
                    "fixture:note\tharp\t1\nfixture:fallback\tharp\t2\nfixture:mushroom\tbrown_mushroom_block\t100000\n");
            Files.writeString(out.resolve(CARRIER_LEDGER), "fixture:display\tdisplay\t0\n");

            Result built = build(mods, out, List.of());
            TreeMap<String, byte[]> pack = readZip(out.resolve("pack.zip"));
            JsonObject variants = JsonParser.parseString(str(pack, "assets/minecraft/blockstates/note_block.json"))
                    .getAsJsonObject().getAsJsonObject("variants");
            JsonElement expected = JsonParser.parseString(app);
            expect(failures, expected.equals(variants.get("instrument=harp,note=1")),
                    "note carrier emits the selected x/y/uvlock application");
            expect(failures, new CarrierModel("fixture:block/fallback").application()
                            .equals(variants.get("instrument=harp,note=2")),
                    "the direct-model fallback has no transform from the missing model");
            JsonArray mushroom = JsonParser.parseString(str(pack, "assets/minecraft/blockstates/brown_mushroom_block.json"))
                    .getAsJsonObject().getAsJsonArray("multipart");
            expect(failures, expected.equals(mushroom.get(mushroom.size() - 1).getAsJsonObject().get("apply")),
                    "mushroom carrier emits the selected x/y/uvlock application");
            String displayRef = JsonParser.parseString(str(pack, displayItemDefPath("fixture:display")))
                    .getAsJsonObject().getAsJsonObject("model").get("model").getAsString();
            JsonObject baked = JsonParser.parseString(str(pack, "assets/"
                            + displayRef.replace(":", "/models/") + ".json"))
                    .getAsJsonObject().getAsJsonArray("elements").get(0).getAsJsonObject();
            expect(failures, JsonParser.parseString("[2,3,1]").equals(baked.get("from"))
                            && JsonParser.parseString("[6,8,4]").equals(baked.get("to"))
                            && baked.getAsJsonObject("faces").has("east"),
                    "display carrier bakes x then y around the block center");
            Result verified = rebuildForVerification(mods, out);
            expect(failures, built.sha1().equals(verified.sha1()) && built.blocks().equals(verified.blocks()),
                    "carrier transforms yield the same pack and rows on verification rebuild");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void unlitBlockSelftest(List<String> failures) throws IOException {
        unlitPlaneSelftest(failures);
        capturedMaterialAlphaSelftest(failures);
        Path quads = Files.createTempDirectory("f2v-unlit-selftest");
        try {
            String face = "fakemod:block/glow_tex\t0\t-1"
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
            Path fluidScene = quads.resolve("gallery-fluid-fixture-p0.tsv");
            Files.writeString(fluidScene, "cell\tx\ty\tz\tfluid\tdyn\tchannel\textra\n");
            var firstTank = new GalleryPack.Resolved("fixture:tank#qfirst", 123, "fixture:first", 0, 0, true);
            var secondTank = new GalleryPack.Resolved("fixture:tank#qsecond", 456, "fixture:second", 0, 0, true);
            expect(failures, GalleryPack.capturedSceneRows(fluidScene, List.of(firstTank)).isEmpty(),
                    "tank: missing context cannot substitute an empty/static tank");
            Files.writeString(quads.resolve("gallery-fluid-fixture-p0.ctx.tsv"),
                    "-5\t-60\t9\tqfirst\n-3\t-60\t9\tqsecond\n-1\t-60\t9\tqabsent\n");
            var tanks = GalleryPack.capturedSceneRows(fluidScene, List.of(firstTank, secondTank));
            expect(failures, tanks.get("-5,-60,9").cmd() == 123 && tanks.get("-3,-60,9").cmd() == 456,
                    "tank: same block state replays distinct per-cell captured contents");
            expect(failures, tanks.size() == 2 && !tanks.containsKey("-1,-60,9"),
                    "tank: unknown captured mesh stays missing rather than substituting another fluid");
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
            // Native liquid chunk materials and alpha survive the unlit
            // child split; legacy captures retain their existing material.
            for (String material : List.of("solid", "cutout", "translucent")) {
                GalleryPack.resetModelScales();
                String nativeFace = face.strip() + "\t1\t1\t1".repeat(4)
                        + "\t0.3".repeat(4) + "\t" + material + "\n";
                Files.writeString(quads.resolve("fixture.tsv"), nativeFace);
                entries.clear();
                rows = GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:fluid#qfixture")),
                        entries, warnings, Map.of(), new ArrayList<>());
                expect(failures, rows.size() == 1, "native liquid: captured row resolves " + material);
                int models = 0;
                for (var entry : entries.entrySet()) {
                    if (!entry.getKey().startsWith("assets/c-imagine/models/gallery/")) continue;
                    JsonObject model = JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    expect(failures, model.get("render_type").getAsString().equals("minecraft:" + material),
                            "native liquid: material retained through child model " + material);
                    models++;
                }
                expect(failures, models >= 2, "native liquid: unlit child checked " + material);
            }
            // Waterlogged meshes combine fluid with solid/cutout geometry.
            // Inspect emitted dispatch children, including fully shaded meshes.
            for (int mode = 0; mode < 3; mode++) {
                GalleryPack.resetModelScales();
                StringBuilder mixed = new StringBuilder();
                List<String> materials = List.of("translucent", "cutout", "solid");
                for (int i = 0; i < materials.size(); i++) {
                    boolean shaded = mode == 1 || (mode == 2 && i != 0);
                    String source = shaded ? face.replace("\t0\t-1", "\t1\t-1") : face;
                    mixed.append(source.strip()).append("\t1\t1\t1".repeat(4))
                            .append("\t0.3".repeat(4)).append('\t').append(materials.get(i))
                            .append("\t8\t15\n");
                }
                Files.writeString(quads.resolve("fixture.tsv"), mixed);
                entries.clear();
                rows = GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:waterlogged#qfixture")),
                        entries, warnings, Map.of(), new ArrayList<>());
                expect(failures, rows.size() == 1, "mixed block materials: capture resolves mode=" + mode);
                if (rows.isEmpty()) continue;
                JsonObject node = GalleryPack.itemNode(rows.getFirst().modelRef());
                expect(failures, node != null && node.get("type").getAsString().equals("minecraft:composite"),
                        "mixed block materials: dispatch uses material children mode=" + mode);
                if (node == null) continue;
                java.util.Set<String> seen = new java.util.HashSet<>();
                int elements = 0;
                for (var value : node.getAsJsonArray("models")) {
                    String ref = value.getAsJsonObject().get("model").getAsString();
                    JsonObject child = JsonParser.parseString(new String(entries.get(
                            "assets/" + ref.replace(":", "/models/") + ".json"), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    seen.add(child.get("render_type").getAsString());
                    elements += child.getAsJsonArray("elements").size();
                }
                expect(failures, seen.equals(java.util.Set.of("minecraft:solid", "minecraft:cutout",
                                "minecraft:translucent")) && elements == 3,
                        "mixed block materials: every face retained once on its material mode=" + mode);
            }
            // Real native run43712291665 proves composite node tints are active.
            // Captured RGB belongs in the polygon tile exactly once, regardless
            // of light-pair policy. Opaque pixels detect squared flat shading and
            // the old extra mean bake, which transparent fixtures could not.
            String colorFace = face.replace("fakemod:block/glow_tex", "fixture:block/captured_rgb");
            String flatRgb = "\t0.6".repeat(12);
            String gradientRgb = "\t0.8\t0.8\t0.8\t0.4\t0.4\t0.4"
                    + "\t0.8\t0.8\t0.8\t0.4\t0.4\t0.4";
            for (int mode = 0; mode < 6; mode++) {
                GalleryPack.resetModelScales();
                ObjMesh.resetBaker();
                entries.clear();
                var opaque = new java.awt.image.BufferedImage(16, 16,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) opaque.setRGB(x, y, 0xffc8a064);
                }
                var png = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(opaque, "png", png);
                entries.put(GalleryPack.spritePathPng("fixture:block/captured_rgb"), png.toByteArray());
                String rgb = mode == 4 ? gradientRgb : flatRgb;
                int block = mode == 1 ? 5 : mode == 2 ? 8 : mode == 3 ? 15 : 0;
                String base = mode == 5 ? colorFace.replace("\t0\t-1", "\t0\t0") : colorFace;
                Files.writeString(quads.resolve("rgb.tsv"), base.strip() + rgb
                        + "\t1".repeat(4) + "\t" + block + "\t15\n");
                rows = GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:rgb#qrgb")),
                        entries, warnings, Map.of(), new ArrayList<>());
                expect(failures, rows.size() == 1, "captured RGB: fixture resolves mode=" + mode);
                if (rows.isEmpty()) continue;
                JsonObject node = GalleryPack.itemNode(rows.getFirst().modelRef());
                expect(failures, !nodeHasConstantTint(node),
                        "captured RGB: no second node multiplier mode=" + mode);
                expect(failures, entries.keySet().stream().noneMatch(k -> k.contains("_cimtint_m")),
                        "captured RGB: no second mean bake mode=" + mode);
                ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, entries);
                JsonObject draw = node.getAsJsonArray("models").get(0).getAsJsonObject();
                JsonObject model = JsonParser.parseString(new String(entries.get("assets/"
                        + draw.get("model").getAsString().replace(":", "/models/") + ".json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject sampled = model.getAsJsonArray("elements").get(0).getAsJsonObject()
                        .getAsJsonObject("faces").getAsJsonObject("south");
                String texture = model.getAsJsonObject("textures")
                        .get(sampled.get("texture").getAsString().substring(1)).getAsString();
                var sheet = decodePng(entries.get(GalleryPack.spritePathPng(texture)));
                JsonArray uv = sampled.getAsJsonArray("uv");
                int x0 = (int) Math.round(uv.get(0).getAsDouble() * sheet.getWidth() / 16);
                int y0 = (int) Math.round(uv.get(1).getAsDouble() * sheet.getHeight() / 16);
                int x1 = (int) Math.round(uv.get(2).getAsDouble() * sheet.getWidth() / 16);
                int y1 = (int) Math.round(uv.get(3).getAsDouble() * sheet.getHeight() / 16);
                int min = 255, max = 0;
                boolean correct = true;
                for (int y = Math.min(y0, y1); y < Math.max(y0, y1); y++) {
                    for (int x = Math.min(x0, x1); x < Math.max(x0, x1); x++) {
                        int pixel = sheet.getRGB(x, y);
                        int red = (pixel >>> 16) & 255;
                        min = Math.min(min, red);
                        max = Math.max(max, red);
                        correct &= (pixel >>> 24) == 255;
                        if (mode != 4) {
                            correct &= Math.abs(red - 120) <= 1
                                    && Math.abs(((pixel >>> 8) & 255) - 96) <= 1
                                    && Math.abs((pixel & 255) - 60) <= 1;
                        }
                    }
                }
                if (mode == 4) correct &= min >= 79 && max <= 161 && max - min > 60;
                expect(failures, correct && min <= max,
                        "captured RGB: opaque source times captured color exactly once mode=" + mode);
            }
            ObjMesh.resetBaker();
            // Equal planes across different meshes share model bytes while
            // both draw nodes and their exact transforms remain present.
            GalleryPack.resetModelScales();
            String shaded = face.replace("\t0\t-1", "\t1\t-1");
            Files.writeString(quads.resolve("first.tsv"), face
                    + shaded.replace("minecraft:block/stone", "minecraft:block/dirt"));
            Files.writeString(quads.resolve("second.tsv"),
                    shaded.replace("minecraft:block/stone", "minecraft:block/gravel") + face);
            entries.clear();
            rows = GalleryPack.build(List.of(), List.of(new GalleryPack.Input("fixture:a#qfirst"),
                    new GalleryPack.Input("fixture:b#qsecond")), entries, warnings, Map.of(), new ArrayList<>());
            expect(failures, rows.size() == 2, "unlit sharing: every registry row retained");
            if (rows.size() == 2) {
                JsonArray first = GalleryPack.itemNode(rows.get(0).modelRef()).getAsJsonArray("models");
                JsonArray second = GalleryPack.itemNode(rows.get(1).modelRef()).getAsJsonArray("models");
                expect(failures, first.size() == 2 && second.size() == 2,
                        "unlit sharing: no source draw removed");
                expect(failures, first.get(0).equals(second.get(0)),
                        "unlit sharing: unused textures/key names cannot duplicate an identical plane or transform");
                expect(failures, entries.keySet().stream().filter(k -> k.contains("/gallery/unlit/")).count() == 1,
                        "unlit sharing: identical plane stored exactly once");
            }
            validateDownloadSize(262_144_000L);
            boolean oversized = false;
            try { validateDownloadSize(262_144_001L); } catch (IOException expected) { oversized = true; }
            expect(failures, oversized, "pack: vanilla download ceiling enforced before publication");
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
            int[] pixelsA = {0xff808080, 0xff808080, 0xff808080, 0xff808080};
            int[] pixelsB = pixelsA.clone();
            pixelsB[0] += 0x10000;
            pixelsB[1] -= 31 * 0x10000;
            expect(failures, java.util.Arrays.hashCode(pixelsA) == java.util.Arrays.hashCode(pixelsB)
                            && java.util.Arrays.hashCode(java.util.Arrays.copyOf(pixelsA, 2))
                            == java.util.Arrays.hashCode(java.util.Arrays.copyOf(pixelsB, 2)),
                    "tile: regression fixture collides in both legacy hashes");
            var tileA = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            var tileB = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            tileA.setRGB(0, 0, 2, 2, pixelsA, 0, 2);
            tileB.setRGB(0, 0, 2, 2, pixelsB, 0, 2);
            int[] atA = ObjMesh.placeExactTile(tileA), atB = ObjMesh.placeExactTile(tileB);
            expect(failures, !java.util.Arrays.equals(atA, atB), "tile: hash collision cannot substitute pixels");
            expect(failures, java.util.Arrays.equals(atA, ObjMesh.placeExactTile(tileA))
                            && java.util.Arrays.equals(atB, ObjMesh.placeExactTile(tileB)),
                    "tile: each colliding tile still reuses its own allocation");
            expect(failures, ObjMesh.tileHashCollisions() == 1, "tile: collision report counts new distinct tiles once");
            TreeMap<String, byte[]> collisionEntries = new TreeMap<>();
            ObjMesh.flushBaker(GalleryPack.WRAPPER_NS, collisionEntries);
            var collisionSheet = decodePng(collisionEntries.get("assets/c-imagine/textures/block/objcut/sheet0.png"));
            expect(failures, java.util.Arrays.equals(pixelsA, collisionSheet.getRGB(atA[1], atA[2], 2, 2, null, 0, 2))
                            && java.util.Arrays.equals(pixelsB, collisionSheet.getRGB(atB[1], atB[2], 2, 2, null, 0, 2)),
                    "tile: colliding source pixels survive PNG publication exactly");
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
            files.put("assets/fixture/models/item/a.json", "{}".getBytes(StandardCharsets.UTF_8));
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
            Files.writeString(out.resolve("quads/fixture.tsv"), "fakemod:block/glow_tex\t0\t-1"
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
            String allocated = Files.readString(out.resolve(ALLOCATED_ITEMS));
            expect(failures, readAllocatedItems(out).equals(List.of("fixture:a")),
                    "foil: fixture exercises a real allocated item");
            Files.writeString(out.resolve("quads/item-foil.tsv"),
                    "fixture:z\nfixture:a\nfixture:z\nminecraft:paper\ninvalid\n");
            Result withFoil = build(mods, out, List.of(), List.of(), List.of(), List.of(), List.of());
            expect(failures, Files.readString(out.resolve(RESOLVED_ITEM_FOIL)).equals("fixture:a\nfixture:z\n"),
                    "foil: captured mod item ids are normalized and sorted");
            expect(failures, withFoil.sha1().equals(original.sha1())
                            && Files.readString(out.resolve(ALLOCATED_ITEMS)).equals(allocated),
                    "foil: adding capture metadata preserves the pack SHA and allocated item ids");
            Files.delete(out.resolve("quads/item-foil.tsv"));
            build(mods, out, List.of(), List.of(), List.of(), List.of(), List.of());
            expect(failures, readItemFoil(out.resolve(RESOLVED_ITEM_FOIL)).isEmpty(),
                    "foil: a rebuild without input clears stale captured foil");
            try (var paths = Files.list(out)) {
                expect(failures, paths.noneMatch(p -> p.getFileName().toString().startsWith("verify-")),
                        "verification: scratch input/output tree is cleaned");
            }
        } finally {
            deleteRecursively(root);
        }
    }

    private static void authoredObjItemSelftest(List<String> failures) throws IOException {
        String diagonal = """
                mtllib test.mtl
                v 0 0 0
                v 1 0 0
                v 1 1 1
                v 0 1 1
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                vn 0 -.707107 .707107
                vn 0 -.706900 .707314
                vn 0 .707107 -.707107
                usemtl material
                """;
        var materials = Map.of("material", "#face");
        for (String invalid : List.of("f 1/1 2/2 3/3 4/4", "f 1/1/1 2/2/1 3/3/1 4/4",
                "f 1/1/1 2/2/2 3/3/1 4/4/1", "f 1/1/3 2/2/3 3/3/3 4/4/3")) {
            expect(failures, !ObjMesh.parse(diagonal + invalid, materials, true, true).getFirst().flatNormal(),
                    "authored OBJ normals: missing, smooth and inverted normals retain the ordinary layer");
        }
        Path root = Files.createTempDirectory("f2v-authored-obj");
        try {
            String id = "create:flywheel";
            Path mods = Files.createDirectory(root.resolve("mods"));
            Path out = root.resolve("out");
            TreeMap<String, byte[]> files = new TreeMap<>();
            files.put("assets/create/models/item/flywheel.json", """
                    {"parent":"create:item/test_obj","display":{"gui":{"scale":[0.4,0.4,0.4]}}}
                    """.getBytes(StandardCharsets.UTF_8));
            files.put("assets/create/models/item/test_obj.json", """
                    {"parent":"minecraft:block/block","loader":"neoforge:obj","model":"create:models/item/test.obj",
                     "flip_v":true,"gui_light":"side","textures":{"face":"create:block/test"},
                     "display":{"fixed":{"rotation":[90,0,0]},"gui":{"scale":[0.5,0.5,0.5]}}}
                    """.getBytes(StandardCharsets.UTF_8));
            files.put("assets/create/models/item/test.obj",
                    (diagonal + "f 1/1/-3 2/2/-3 3/3/-3 4/4/-3\n").getBytes(StandardCharsets.UTF_8));
            files.put("assets/create/models/item/test.mtl", "newmtl material\nmap_Kd #face\n".getBytes(StandardCharsets.UTF_8));
            files.put("assets/create/textures/block/test.png", f2vSelftestPng(16, 16));
            writeJar(mods.resolve("fixture.jar"), files);
            Result fresh = build(mods, out, List.of(), List.of(), List.of(), List.of(), List.of());
            expect(failures, Files.readString(out.resolve("build-report.txt")).contains("authored OBJ item normals: " + id),
                    "authored OBJ normals: a fresh build selects the mod jar model");
            try (ZipFile pack = new ZipFile(out.resolve("pack.zip").toFile())) {
                JsonObject composite = JsonParser.parseString(new String(pack.getInputStream(
                        pack.getEntry(allocatedItemDefPath(id))).readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("model");
                JsonArray children = composite.getAsJsonArray("models");
                expect(failures, children != null && children.size() == 1, "authored OBJ normals: one layer for one face");
                if (children != null && children.size() == 1) {
                    JsonObject node = children.get(0).getAsJsonObject();
                    expect(failures, node.has("transformation"), "authored OBJ normals: negative indices resolve to a diagonal layer");
                    String ref = node.get("model").getAsString();
                    JsonObject model = JsonParser.parseString(new String(pack.getInputStream(pack.getEntry(
                            "assets/" + ref.replace(":", "/models/") + ".json")).readAllBytes(),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject display = model.getAsJsonObject("display");
                    expect(failures, display.getAsJsonObject("gui").getAsJsonArray("scale").get(0).getAsDouble() == 0.4
                                    && display.getAsJsonObject("fixed").getAsJsonArray("rotation").get(0).getAsInt() == 90
                                    && "side".equals(model.get("gui_light").getAsString()),
                            "authored OBJ normals: child and inherited display settings reach the diagonal layer");
                }
            }
            Files.createDirectory(out.resolve("quads"));
            Files.writeString(out.resolve("quads/items.tsv"), id + "\tmocked_capture\n");
            Files.writeString(out.resolve("quads/mocked_capture.tsv"), "# cimscene native empty mesh v1\n");
            Result staged = build(mods, out, List.of(), List.of(), List.of(), List.of(), List.of());
            expect(failures, fresh.sha1().equals(staged.sha1()),
                    "authored OBJ normals: a staged capture cannot shadow the asset-derived model");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void itemNormalSelftest(List<String> failures) throws IOException {
        Path quads = Files.createTempDirectory("f2v-item-normal-selftest");
        try {
            // A diagonal edge from the native Create empty blaze burner OBJ.
            String row = "minecraft:block/stone\t1\t-1"
                    + "\t15\t5\t15\t0.5\t8\t1\t5\t15\t7.5\t8"
                    + "\t1\t4\t14\t7.5\t7.5\t15\t4\t14\t0.5\t7.5"
                    + "\t1".repeat(16) + "\ttranslucent\t15\t15";
            Files.writeString(quads.resolve("native.tsv"), row + "\n" + row + "\tflat_normal\n" + row + "\n");
            Files.writeString(quads.resolve("legacy.tsv"), row + "\n");
            Files.writeString(quads.resolve("items.tsv"), "fixture:item\tnative\nfixture:legacy\tlegacy\n");
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(quads);
            TreeMap<String, byte[]> entries = new TreeMap<>();
            entries.put("assets/minecraft/textures/block/stone.png", f2vSelftestPng(16, 16));
            String ref = GalleryPack.synthesizeItemQuads(List.of(), "fixture:item", 0, entries, new ArrayList<>());
            String legacy = GalleryPack.synthesizeItemQuads(List.of(), "fixture:legacy", 1, entries, new ArrayList<>());
            expect(failures, legacy != null && GalleryPack.itemNode(legacy) == null,
                    "item normal: older captures retain their single-layer rendering");
            JsonObject composite = ref == null ? null : GalleryPack.itemNode(ref);
            expect(failures, composite != null, "item normal: marked capture has a composite");
            if (composite == null) return;
            JsonObject selected = JsonParser.parseString(selectDef("paper", List.of(Map.entry("native", ref))))
                    .getAsJsonObject().getAsJsonObject("model").getAsJsonArray("cases")
                    .get(0).getAsJsonObject().getAsJsonObject("model");
            expect(failures, composite.equals(selected), "item normal: string-selected carriers use the native layers");
            JsonArray children = composite.getAsJsonArray("models");
            expect(failures, children.size() == 3 && !children.get(0).getAsJsonObject().has("transformation")
                            && !children.get(2).getAsJsonObject().has("transformation"),
                    "item normal: rotation affects only the marked face and preserves draw order");
            if (children.size() != 3) return;
            JsonObject node = children.get(1).getAsJsonObject();
            JsonObject child = JsonParser.parseString(new String(entries.get("assets/"
                    + node.get("model").getAsString().replace(":", "/models/") + ".json"),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            expect(failures, child.has("display") && "minecraft:translucent".equals(child.get("render_type").getAsString()),
                    "item normal: GUI transform and explicit translucent material survive the light tail");
            JsonObject element = child.getAsJsonArray("elements").get(0).getAsJsonObject();
            expect(failures, !element.has("rotation"), "item normal: the child bakes a cardinal south face");
            JsonArray matrix = node.getAsJsonArray("transformation");
            double diagonal = Math.sqrt(.5);
            expect(failures, Math.abs(matrix.get(2).getAsDouble()) < 1e-6
                            && Math.abs(matrix.get(6).getAsDouble() + diagonal) < 1e-6
                            && Math.abs(matrix.get(10).getAsDouble() - diagonal) < 1e-6,
                    "item normal: the layer rotates SOUTH to the verified native diagonal");
            double[] expectedCenter = {.5, 4.5 / 16, 14.5 / 16};
            for (int i = 0; i < 3; i++) {
                double center = matrix.get(i * 4 + 3).getAsDouble();
                for (int col = 0; col < 3; col++) {
                    double from = element.getAsJsonArray("from").get(col).getAsDouble();
                    double to = element.getAsJsonArray("to").get(col).getAsDouble();
                    double coordinate = (col == 2 ? to : (from + to) / 2) / 16;
                    center += matrix.get(i * 4 + col).getAsDouble() * coordinate;
                }
                expect(failures, Math.abs(center - expectedCenter[i]) < .002,
                        "item normal: the visible plane center remains at the native capture position");
            }
        } finally {
            GalleryPack.resetModelScales();
            GalleryPack.setQuadDir(null);
            deleteRecursively(quads);
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
            if (i < 6) {
                angles = new double[][]{{0, 0, 0}, {90, 0, 0}, {0, 90, 0},
                        {180, 0, 0}, {0, 180, 0}, {0, 0, 90}}[i];
            } else if (i < 9) {
                // The native light lies in the source plane; include both
                // sides of the determinant-sign boundary as well.
                angles = new double[]{Math.toDegrees(Math.atan(-.7)) + (i - 7) * 1e-9, 0, 0};
            }
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
            expect(failures, Math.abs(det) > 1e-9 && length > 1e-9,
                    "unlit: restoration remains invertible at cardinal and arbitrary angles");
            // Native item.vsh applies directional light independently of
            // the display's packed lightmap override. Captured block shade
            // is already baked into these tiles, so the item normal must
            // receive a unit diffuse multiplier while all corners stay put.
            for (int r = 0; r < 3; r++) {
                maxNormal = Math.max(maxNormal, Math.abs(normal[r] / Math.sqrt(length)
                        * Math.signum(det) - new double[]{.2, 1, -.7}[r] / Math.sqrt(1.53)));
            }
        }
        expect(failures, maxCorner < 1e-9, "unlit: arbitrary planes/oversized scale preserve all corners");
        expect(failures, maxNormal < 1e-9, "unlit: native item diffuse is neutral independently of the lightmap pin");
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
