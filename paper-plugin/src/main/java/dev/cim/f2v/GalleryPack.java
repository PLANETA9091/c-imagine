package dev.cim.f2v;

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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * GALLERY per-state model synthesis — the M5.5 ours-leg ITEM/DISPLAY channel
 * (owner directive 2026-10-30: the note_block stand-in is REJECTED for the
 * gallery; f2v v1's display-entity path is the required surface).
 *
 * <p>Input: one modded BLOCKSTATE string per row ({@code ns:block[k=v,...]},
 * exactly the {@code state} column of the {@code gallery-<ns>-p<p>.tsv} page
 * manifests the truth leg emits). Output: one CMD float per state in the
 * host item's {@code items/<host>.json} {@code range_dispatch}, rendering the
 * state's ACTUAL model on any stock 26.3 client through an ItemDisplay
 * entity the plugin spawns per gallery cell.
 *
 * <p>Resolution rules (vanilla block-model semantics, measured sources):
 * <ul>
 *   <li>{@code blockstates/<block>.json} of shape {@code variants}: the MOST
 *       SPECIFIC matching key wins (every listed property must equal the
 *       state's; unlisted properties are wildcards). The variant's model ref
 *       is copied at its ORIGINAL path (parent graph + textures), and the
 *       variant's {@code x}/{@code y} rotations ride the SPAWNING ENTITY's
 *       transformation — no model surgery for the single-part case.</li>
 *   <li>shape {@code multipart}: ALL matching parts render together. Their
 *       elements are merged into ONE composite model
 *       ({@code assets/c-imagine/models/gallery/g<N>.json}) with per-part
 *       rotations baked into the elements (90-degree steps, measured axis
 *       tables), texture keys namespaced per part to survive collisions, and
 *       {@code "parent": "minecraft:block/block"} restored so the composite
 *       carries the same display-context transforms as direct models — the
 *       entity transform stays uniform across all rows.</li>
 * </ul>
 *
 * <p>Determinism: rows sort by state id, cmds assign {@link #CMD_BASE}+i in
 * that order, composite files number in emit order, the zip writer is the
 * deterministic one from F2vPack. Same jars + same manifest = same sha1.
 *
 * <p>JSON parsing rides gson (Paper ships it in its libraries; the CLI build
 * path puts the gson jar on the classpath next to the plugin jar).
 */
public final class GalleryPack {

    private GalleryPack() {
    }

    /** One manifest row: the modded blockstate string to render. */
    record Input(String stateId) {
    }

    /**
     * One resolved row: state -> cmd + the model ref the pack def points at
     * + the X/Y rotations the SPAWNING ENTITY must carry (0/0 for composite
     * rows — their rotations are baked into the merged elements).
     */
    public record Resolved(String stateId, float cmd, String modelRef,
                           int xRot, int yRot, boolean composite) {
    }

    /** First auto-assigned custom_model_data float for gallery rows. */
    public static final float CMD_BASE = 100_000.0f;
    /** Namespace + dir the composite models emit under. */
    static final String WRAPPER_NS = "c-imagine";

    /** The model element bounds CuboidModelElement$Deserializer enforces. */
    private static final double ELEMENT_MIN = -16.0;
    private static final double ELEMENT_MAX = 32.0;
    static final String WRAPPER_PREFIX = "assets/" + WRAPPER_NS + "/models/gallery/g";
    static final String WRAPPER_REF_PREFIX = WRAPPER_NS + ":gallery/g";

    // ---- manifest ------------------------------------------------------------

    /** Scene headers begin with cell too; they are schema, never registry content. */
    public static List<String> readSceneLines(Path manifest) throws IOException {
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        lines.removeIf(line -> line.startsWith("cell\tx\ty\tz\t"));
        return lines;
    }

    /** Resolve per-cell native meshes, including distinct BE contents of the same block state. */
    public static Map<String, Resolved> capturedSceneRows(Path manifest, List<Resolved> gallery) throws IOException {
        String name = manifest.getFileName().toString();
        Path ctx = manifest.resolveSibling(name.substring(0, name.length() - 4) + ".ctx.tsv");
        if (!Files.isRegularFile(ctx)) return Map.of();
        Map<String, Resolved> byMesh = new LinkedHashMap<>();
        for (Resolved row : gallery) {
            int marker = row.stateId().indexOf("#q");
            if (marker >= 0) byMesh.putIfAbsent(row.stateId().substring(marker + 1), row);
        }
        Map<String, Resolved> cells = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ctx, StandardCharsets.UTF_8)) {
            String[] row = line.split("\t", -1);
            if (row.length < 4) continue;
            Resolved captured = byMesh.get(row[3]);
            if (captured != null) cells.put(row[0] + "," + row[1] + "," + row[2], captured);
        }
        return cells;
    }

    /** The first actual item row, in the same order the real scene walk emitted it. */
    public static String firstSceneItem(Path manifest) throws IOException {
        for (String line : readSceneLines(manifest)) {
            String[] row = line.split("\t", -1);
            if (row.length >= 5 && row[0].equals("cell")) {
                return row[4];
            }
        }
        return null;
    }

    /**
     * Read {@code visual-gallery.tsv}: one modded blockstate string per line
     * ({@code #} comments and blank lines ignored). An optional 2nd column
     * (explicit cmd float) is accepted and passed through.
     */
    static List<Input> readManifest(Path tsv) throws IOException {
        List<Input> rows = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            int tab = t.indexOf('\t');
            String state = tab >= 0 ? t.substring(0, tab).trim() : t;
            if (!state.isEmpty()) {
                rows.add(new Input(state));
            }
        }
        return rows;
    }

    /**
     * Write the RESOLVED manifest. Columns: {@code state<TAB>cmd[<TAB>ref
     * <TAB>xrot<TAB>yrot<TAB>composite]}. The first two columns are the
     * historical format (humans + the workflow grep it); the trailing
     * columns carry the full {@link Resolved} state so a boot can LOAD the
     * resolved rows instead of re-running the (CPU-bound) model resolution
     * — the {@code cim.f2v.fastBoot} contract (CimPlugin#f2vInit). The
     * reader tolerates both widths.
     */
    static void writeResolvedManifest(Path tsv, List<Resolved> rows) throws IOException {
        StringBuilder sb = new StringBuilder("# resolved by GalleryPack — final "
                + "custom_model_data floats (do not edit; regenerate instead)\n");
        for (Resolved r : rows) {
            sb.append(r.stateId()).append('\t').append(r.cmd())
              .append('\t').append(r.modelRef())
              .append('\t').append(r.xRot())
              .append('\t').append(r.yRot())
              .append('\t').append(r.composite()).append('\n');
        }
        Files.createDirectories(tsv.getParent());
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Read a resolved manifest written by {@link #writeResolvedManifest}.
     * Two columns (the historical width) resolve to the defaults a
     * composite row always carries (modelRef empty, rotations 0/0,
     * composite=true) — every row GalleryPack emits IS a self-contained
     * composite with baked rotations, so a legacy file loses nothing the
     * replay uses (the replay reads cmd + xRot + yRot only).
     */
    public static List<Resolved> readResolvedManifest(Path tsv) throws IOException {
        List<Resolved> rows = new ArrayList<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] parts = t.split("\t", -1);
            if (parts.length < 2) {
                continue;
            }
            float cmd;
            try {
                cmd = Float.parseFloat(parts[1].trim());
            } catch (NumberFormatException bad) {
                continue;
            }
            String ref = parts.length >= 3 ? parts[2].trim() : "";
            int xRot = 0, yRot = 0;
            boolean composite = true;
            try {
                if (parts.length >= 4) {
                    xRot = Integer.parseInt(parts[3].trim());
                }
                if (parts.length >= 5) {
                    yRot = Integer.parseInt(parts[4].trim());
                }
                if (parts.length >= 6) {
                    composite = Boolean.parseBoolean(parts[5].trim());
                }
            } catch (NumberFormatException bad) {
                // rotations stay 0/0 — the composite defaults
            }
            rows.add(new Resolved(parts[0].trim(), cmd, ref, xRot, yRot, composite));
        }
        return rows;
    }

    // ---- build -----------------------------------------------------------------

    /**
     * Resolve every input state to a CMD'd model, emitting model/texture
     * entries into {@code entries} (the shared deterministic pack zip map).
     * Returns the resolved rows in emission order (sorted by state id).
     */
    static List<Resolved> build(List<Path> jars, List<Input> inputs,
                                TreeMap<String, byte[]> entries, List<String> warnings)
            throws IOException {
        return build(jars, inputs, entries, warnings, Map.of(), null, Map.of(), null);
    }

    /**
     * Build with the TINT-BAKE channel: {@code tintTable} maps a modded
     * texture ref ({@code ns:block/foo}) to the calibrated per-channel
     * multiply (scripts/f2v/tint-calibrate.py). The composite emit rewrites
     * every tinted sprite to a BAKED sibling ({@code ..._cimtint}) carrying
     * the multiplied pixels; the raw texture stays untouched at its original
     * path so every other consumer (item icons, the note_block block
     * channel) renders it exactly as before. {@code bakeLog} (nullable)
     * collects one line per applied bake for the pack build report.
     */
    static List<Resolved> build(List<Path> jars, List<Input> inputs,
                                TreeMap<String, byte[]> entries, List<String> warnings,
                                Map<String, double[]> tintTable,
                                List<String> bakeLog)
            throws IOException {
        return build(jars, inputs, entries, warnings, tintTable, bakeLog, Map.of(), null);
    }

    /**
     * Build with the TINT-BAKE and EMISSIVE-BAKE channels. {@code
     * emissiveTable} maps a blockstate string to a {@code light_emission}
     * level 0-15 (visual-emissive.tsv, the gallery LIGHTMAP class): the
     * composite emit sets the ELEMENT property on every element of that
     * state's emitted model JSON — the real client renders those states
     * fullbright (the mod registers a lightLevel / emissiveRendering in
     * code), ours renders the same geometry dim. Absent/empty table = no-op
     * (byte-identical output). {@code emissiveLog} (nullable) collects one
     * line per applied bake for the pack build report.
     */
    static List<Resolved> build(List<Path> jars, List<Input> inputs,
                                TreeMap<String, byte[]> entries, List<String> warnings,
                                Map<String, double[]> tintTable,
                                List<String> bakeLog,
                                Map<String, Integer> emissiveTable,
                                List<String> emissiveLog)
            throws IOException {
        // deterministic order + dedupe (a repeated state reuses the first cmd)
        List<Input> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(Input::stateId));
        Map<String, Input> seen = new LinkedHashMap<>();
        for (Input in : sorted) {
            seen.putIfAbsent(in.stateId(), in);
        }

        List<Resolved> out = new ArrayList<>();
        int compositeIdx = 0;
        float cmd = CMD_BASE;
        for (Input in : seen.values()) {
            String state = in.stateId();
            try {
                // 14-ak CT rows: {@code state#i0,i1,...,i5} — the per-face
                // CT tile indices the seed computed (gallery_ct.py, verified
                // against the real create classes by CTProbe 515/515). The
                // suffix NEVER reaches parseState — it rides the Resolved
                // row so the replay's suffixed lookup matches.
                String ctCtx = null;
                String bareState = state;
                int hash = state.indexOf('#');
                if (hash >= 0) {
                    ctCtx = state.substring(hash + 1).trim();
                    bareState = state.substring(0, hash);
                    // q<hash> = the quad capture channel; p<codes> = the
                    // 14-bo pipe attachment channel (validated below);
                    // i0,...,i5 = the 14-ak CT channel.
                    if (!ctCtx.startsWith("q") && !ctCtx.startsWith("p")) {
                        parseCtCtx(ctCtx); // validate early — a bad ctx drops the row
                    }
                }
                if (ctCtx != null && ctCtx.startsWith("q")) {
                    String quadRef = synthesizeQuadComposite(jars, ctCtx.substring(1), bareState,
                            compositeIdx, entries, warnings, tintTable);
                    if (quadRef == null) {
                        warnings.add("gallery: quad mesh failed for " + state + " — row dropped");
                        continue;
                    }
                    compositeIdx++;
                    out.add(new Resolved(state, cmd, quadRef, 0, 0, true));
                    cmd += 1.0f;
                    continue;
                }
                // 14-ck THE SLIDING-DOOR BER TRUTH (decompiled
                // SlidingDoorRenderer + SlidingDoorBlockEntity, re-read after
                // the 14-bq «upper renders NOTHING» reading was refuted by
                // the full-page evidence): shouldRenderSpecial returns TRUE
                // for EVERY visible=false state (isVisible ? bridgeTicks !=
                // 0 : true), so the BER panel draws in EVERY visible=false
                // cell — upper ones included. The BER path resets
                // open=false and renders BOTH DoubleBlockHalf halves of the
                // closed blockstate model anchored AT THE CELL: lower half
                // at y+0, upper half at y+0.998 (a full two-block panel
                // rising from the cell, spilling into the neighbour above),
                // and the BE constructor starts the animation at
                // startWithValue(isOpen ? 1 : 0) — the settled gallery cell
                // with open=true rides the FULL slide (value²·13/16 along
                // movementDirection + value2/32 along facing). The folding
                // copper/andesite branch is a separate 91° rotation class:
                // its settled open=true panels fold, so only the open=false
                // flat state rides this route.
                if (isHiddenSlidingPanel(bareState)) {
                    boolean open = bareState.contains("open=true");
                    String closed = bareState.replace("open=true", "open=false");
                    StateRef sLower = parseState(closed.replace("half=upper", "half=lower"));
                    StateRef sUpper = parseState(closed.replace("half=lower", "half=upper"));
                    List<Part> parts = new ArrayList<>();
                    parts.addAll(resolveStateParts(jars, sLower, entries, warnings));
                    for (Part p : resolveStateParts(jars, sUpper, entries, warnings)) {
                        parts.add(new Part(p.modelRef(), p.xRot(), p.yRot(), p.uvlock(), 0.998));
                    }
                    if (parts.isEmpty()) {
                        warnings.add("gallery: no matching model for " + state + " — row dropped");
                        continue;
                    }
                    String ref = synthesizeComposite(jars, bareState, parts,
                            compositeIdx, entries, warnings, ctCtx, tintTable, bakeLog,
                            emissiveTable, emissiveLog);
                    if (ref == null) {
                        warnings.add("gallery: composite failed for " + state + " — row dropped");
                        continue;
                    }
                    if (open) {
                        double[] slide = slideDoorOffset(bareState);
                        if (slide != null) {
                            translateCompositeElements(ref, slide, entries);
                        }
                    }
                    compositeIdx++;
                    out.add(new Resolved(state, cmd, ref, 0, 0, true));
                    cmd += 1.0f;
                    continue;
                }
                double[] slide = slideDoorOffset(bareState);
                StateRef s = parseState(slide == null ? bareState
                        : bareState.replace("open=true", "open=false"));
                List<Part> parts = resolveStateParts(jars, s, entries, warnings);
                if (parts.isEmpty()) {
                    warnings.add("gallery: no matching model for " + state + " — row dropped");
                    continue;
                }
                // EVERY row emits a SELF-CONTAINED model (explicit elements +
                // resolved textures, parent minecraft:block/block). The old
                // direct path (the model riding at its original path, the
                // client resolving the parent chain) renders INVISIBLE on
                // the 26.3 item-model surface whenever the elements come
                // from a parent outside the pack — measured run
                // 41126658261: create:block/andesite_casing (parent
                // minecraft:block/cube_all, no own elements) spawned as a
                // display and drew NOTHING while the door models (own
                // elements) rendered fine. The composite emit path is the
                // proven renderer — single-part rows ride it too (their
                // blockstate rotation bakes into the elements, exactly the
                // measured composite behaviour; the spawning entity carries
                // 0/0).
                String ref = synthesizeComposite(jars, bareState, parts,
                        compositeIdx, entries, warnings, ctCtx, tintTable, bakeLog,
                        emissiveTable, emissiveLog);
                if (ref == null) {
                    warnings.add("gallery: composite failed for " + state + " — row dropped");
                    continue;
                }
                if (slide != null) {
                    translateCompositeElements(ref, slide, entries);
                }
                compositeIdx++;
                out.add(new Resolved(state, cmd, ref, 0, 0, true));
                cmd += 1.0f;
            } catch (Exception e) {
                warnings.add("gallery: " + state + " resolution failed: " + e);
            }
        }
        return out;
    }

    // ---- state parsing -----------------------------------------------------------

    /** A parsed blockstate string: {@code ns:block} + property map. */
    private record StateRef(String ns, String block, Map<String, String> props) {
    }

    /** Parse {@code ns:block[k=v,k2=v2]} (properties optional). */
    static StateRef parseState(String state) {
        String ns = state.substring(0, state.indexOf(':'));
        String rest = state.substring(state.indexOf(':') + 1);
        String block = rest;
        Map<String, String> props = new LinkedHashMap<>();
        int br = rest.indexOf('[');
        if (br >= 0) {
            if (!rest.endsWith("]")) {
                throw new IllegalArgumentException("unterminated property list: " + state);
            }
            block = rest.substring(0, br);
            String body = rest.substring(br + 1, rest.length() - 1);
            for (String pair : body.split(",", -1)) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return new StateRef(ns, block, props);
    }

    // ---- blockstate resolution -----------------------------------------------------

    /** One resolved render part: model ref + the rotation it must carry
     * (+ the blockstate's uvlock flag, 14-ah). */
    private record Part(String modelRef, int xRot, int yRot, boolean uvlock, double yOff) {
    }

    /**
     * 14-ck: the visible=false door states whose BER panel our pack must
     * reproduce as the full two-block closed panel (both halves anchored at
     * the cell). Sliding doors (brass/framed_glass/train) always; the
     * folding copper/andesite doors only in the flat open=false settled
     * state (their open=true panels fold 91° — a separate class).
     */
    private static boolean isHiddenSlidingPanel(String state) {
        if (!state.startsWith("create:") || !state.contains("visible=false")) {
            return false;
        }
        if (state.startsWith("create:copper_door[")
                || state.startsWith("create:andesite_door[")) {
            return !state.contains("open=true");
        }
        return state.startsWith("create:brass_door[")
                || state.startsWith("create:framed_glass_door[")
                || state.startsWith("create:train_door[");
    }

    /**
     * 14-cm: the VANILLA MISSING MODEL stand-in (the magenta/black cube the
     * real client renders for a blockstate variant whose model file is
     * absent). Synthesized once per pack build; the texture is the classic
     * missingno checkerboard (two magenta quadrants).
     */
    private static String missingCubeRef(TreeMap<String, byte[]> entries) {
        String texPath = "assets/c-imagine/textures/block/missingno.png";
        if (!entries.containsKey(texPath)) {
            // measured on the real 1.21.1 missing-model render (ae2-p7 c0_r4
            // truth crop): a 10-unit cube whose texture carries a dark-violet
            // frame around a light-magenta center; frame (85,66,148),
            // center (211,153,212) raw RGB.
            int w = 16, h = 16;
            byte[] px = new byte[w * h * 3];
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    boolean frame = xx < 3 || yy < 3 || xx > 12 || yy > 12;
                    int i = (yy * w + xx) * 3;
                    px[i] = (byte) (frame ? 85 : 211);
                    px[i + 1] = (byte) (frame ? 66 : 153);
                    px[i + 2] = (byte) (frame ? 148 : 212);
                }
            }
            entries.put(texPath, minimalPng(w, h, px));
        }
        String modelPath = "assets/c-imagine/models/gallery/missingno.json";
        if (!entries.containsKey(modelPath)) {
            JsonObject faces = new JsonObject();
            for (String f : new String[]{"north", "south", "east", "west", "up", "down"}) {
                JsonObject face = new JsonObject();
                JsonArray uv = new JsonArray();
                uv.add(0);
                uv.add(0);
                uv.add(16);
                uv.add(16);
                face.add("uv", uv);
                face.addProperty("texture", "#missing");
                faces.add(f, face);
            }
            JsonObject el = new JsonObject();
            JsonArray from = new JsonArray();
            JsonArray to = new JsonArray();
            for (double v : new double[]{3, 3, 3}) {
                from.add(v);
            }
            for (double v : new double[]{13, 13, 13}) {
                to.add(v);
            }
            el.add("from", from);
            el.add("to", to);
            el.add("faces", faces);
            JsonArray elements = new JsonArray();
            elements.add(el);
            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:block/block");
            JsonObject textures = new JsonObject();
            textures.addProperty("missing", "c-imagine:block/missingno");
            model.add("textures", textures);
            model.add("elements", elements);
            entries.put(modelPath, model.toString().getBytes(StandardCharsets.UTF_8));
        }
        return "c-imagine:gallery/missingno";
    }

    /** Minimal RGB PNG encoder (8-bit, no filtering) for synthesized textures. */
    private static byte[] minimalPng(int w, int h, byte[] rgb) {
        try {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 3;
                    int r = rgb[i] & 0xFF;
                    int g = rgb[i + 1] & 0xFF;
                    int b = rgb[i + 2] & 0xFF;
                    img.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("missingno png encode failed", e);
        }
    }

    /** Shifts every element of a baked array by {@code dy} model units on Y. */
    private static JsonArray shiftElementsY(JsonArray elements, double dy) {
        JsonArray out = new JsonArray();
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject().deepCopy();
            if (e.has("from") && e.has("to")) {
                JsonArray from = e.getAsJsonArray("from").deepCopy();
                JsonArray to = e.getAsJsonArray("to").deepCopy();
                from.set(1, new com.google.gson.JsonPrimitive(
                        from.get(1).getAsDouble() + dy));
                to.set(1, new com.google.gson.JsonPrimitive(
                        to.get(1).getAsDouble() + dy));
                e.add("from", from);
                e.add("to", to);
            }
            out.add(e);
        }
        return out;
    }

    /**
     * Resolve {@code blockstates/<block>.json} from the jars and match the
     * state against its variants / multipart cases. Returns the render parts
     * in file order (deterministic); empty when nothing matched.
     */
    private static List<Part> resolveStateParts(List<Path> jars, StateRef s,
                                                TreeMap<String, byte[]> entries,
                                                List<String> warnings) throws IOException {
        String bsFile = "assets/" + s.ns() + "/blockstates/" + s.block() + ".json";
        JsonObject bs = readJsonFromJars(jars, bsFile);
        if (bs == null) {
            warnings.add("gallery: blockstate missing in jars: " + bsFile);
            return List.of();
        }
        List<Part> parts = new ArrayList<>();
        if (bs.has("variants")) {
            JsonObject variants = bs.getAsJsonObject("variants");
            String bestKey = null;
            int bestLen = -1;
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                if (!matchesKey(e.getKey(), s.props())) {
                    continue;
                }
                int len = keyLen(e.getKey());
                if (len > bestLen) {
                    bestLen = len;
                    bestKey = e.getKey();
                }
            }
            if (bestKey != null) {
                JsonElement v = variants.get(bestKey);
                // 14-cl: a variant may carry MULTIPLE models (an ARRAY) — the
                // real client renders every entry (BOP null_leaves[""] =
                // null_leaves + null_leaves_alt; taking only get(0) dropped
                // the alt layer and measured ae 313–336K on the p66 crops).
                List<JsonElement> entriesToApply = new ArrayList<>();
                if (v.isJsonArray()) {
                    v.getAsJsonArray().forEach(entriesToApply::add);
                } else {
                    entriesToApply.add(v);
                }
                for (JsonElement ve : entriesToApply) {
                    JsonObject entry = ve.getAsJsonObject();
                    String model = entry.get("model").getAsString();
                    int x = entry.has("x") ? entry.get("x").getAsInt() : 0;
                    int y = entry.has("y") ? entry.get("y").getAsInt() : 0;
                    boolean uvlock = entry.has("uvlock") && entry.get("uvlock").getAsBoolean();
                    String ref = copyModelGraph(jars, s.ns(), model, entries, warnings);
                    if (ref == null) {
                        // 14-cm: the MODEL FILE itself is absent from the jars
                        // (ae2:block/crafting/monitor_formed — an empty/absent
                        // model the real client resolves to the VANILLA MISSING
                        // MODEL, the magenta/black cube). Our old route dropped
                        // the row (empty cell, ae 152–178K on 8 monitor cells);
                        // the pack now emits the same missing-model cube.
                        ref = missingCubeRef(entries);
                        warnings.add("gallery: missing model -> vanilla missingno cube for "
                                + s.ns() + ":" + s.block() + " -> " + model);
                    }
                    parts.add(new Part(ref, normRot(x), normRot(y), uvlock, 0));
                }
            }
            return parts;
        }
        if (bs.has("multipart")) {
            JsonArray mp = bs.getAsJsonArray("multipart");
            // THE WALL POST RULE (measured run 42085254497, p109 c1,r1 —
            // white_sandstone_wall[east=tall, rest=none, up=false]): the
            // real 1.21.1 client renders the CENTER POST for a
            // single-connection wall — the wall renderer derives the post
            // from the CONNECTIVITY (one arm = the post rises), not from
            // the static multipart's up-keyed post case (BOP's post case
            // keys on up=true; the state said up=false; the composite came
            // out arm-only while the truth frame carried the post). After
            // the static evaluation: exactly ONE matched side arm and no
            // matched post → inject the blockstate's own wall_post case.
            boolean matchedPost = false;
            int matchedArms = 0;
            JsonObject postCase = null;
            for (JsonElement pe : mp) {
                JsonObject part = pe.getAsJsonObject();
                boolean matches = !part.has("when")
                        || matchesWhen(part.getAsJsonObject("when"), s.props());
                JsonElement apply = part.has("apply") ? part.get("apply") : null;
                JsonObject entry = apply == null ? null
                        : apply.isJsonArray()
                        ? apply.getAsJsonArray().get(0).getAsJsonObject()
                        : apply.getAsJsonObject();
                String model = entry == null ? null
                        : entry.has("model") ? entry.get("model").getAsString() : null;
                if (matches) {
                    if (model != null && model.contains("wall_post")) {
                        matchedPost = true;
                    } else if (model != null && model.contains("wall_side")) {
                        matchedArms++;
                    }
                } else if (model != null && model.contains("wall_post") && postCase == null) {
                    postCase = part;
                }
            }
            for (JsonElement pe : mp) {
                JsonObject part = pe.getAsJsonObject();
                if (part.has("when") && !matchesWhen(part.getAsJsonObject("when"), s.props())) {
                    continue;
                }
                JsonElement apply = part.get("apply");
                List<JsonElement> applyList = new ArrayList<>();
                if (apply.isJsonArray()) {
                    apply.getAsJsonArray().forEach(applyList::add);
                } else {
                    applyList.add(apply);
                }
                for (JsonElement apEl : applyList) {
                    JsonObject entry = apEl.getAsJsonObject();
                    String model = entry.get("model").getAsString();
                    int x = entry.has("x") ? entry.get("x").getAsInt() : 0;
                    int y = entry.has("y") ? entry.get("y").getAsInt() : 0;
                    boolean uvlock = entry.has("uvlock") && entry.get("uvlock").getAsBoolean();
                    String ref = copyModelGraph(jars, s.ns(), model, entries, warnings);
                    if (ref != null) {
                        parts.add(new Part(ref, normRot(x), normRot(y), uvlock, 0));
                    }
                    // a missing multipart part model: recorded, the composite
                    // synthesizes from what resolved (honest partial render)
                }
            }
            if (!matchedPost && matchedArms == 1 && postCase != null) {
                // inject the blockstate's own post case (its model + its
                // rotation), so the single-connection wall carries the post
                // the real client renders
                JsonElement applyEl = postCase.get("apply");
                JsonObject entry = applyEl.isJsonArray()
                        ? applyEl.getAsJsonArray().get(0).getAsJsonObject()
                        : applyEl.getAsJsonObject();
                String model = entry.get("model").getAsString();
                int x = entry.has("x") ? entry.get("x").getAsInt() : 0;
                int y = entry.has("y") ? entry.get("y").getAsInt() : 0;
                boolean uvlock = entry.has("uvlock") && entry.get("uvlock").getAsBoolean();
                String ref = copyModelGraph(jars, s.ns(), model, entries, warnings);
                if (ref != null) {
                    parts.add(new Part(ref, normRot(x), normRot(y), uvlock, 0));
                    warnings.add("wall-post injected for " + s.ns() + ":"
                            + " one-connection wall (the connectivity rule)");
                }
            }
        }
        return parts;
    }

    /** Normalize a blockstate rotation to the 0/90/180/270 family. */
    private static int normRot(int r) {
        return ((r % 360) + 360) % 360;
    }

    /** Number of property pairs in a variant selector key (specificity). */
    private static int keyLen(String key) {
        if (key.isEmpty()) {
            return 0;
        }
        return key.split(",", -1).length;
    }

    /** Every property in the selector key must equal the state's value.
     *  Value comparison is CASE-INSENSITIVE: EnumProperty values serialize
     *  lowercase into blockstate json keys (part=end) while the server-side
     *  state string carries the enum name (part=END) — same property value.
     */
    private static boolean matchesKey(String key, Map<String, String> props) {
        if (key.isEmpty()) {
            return true;
        }
        for (String pair : key.split(",", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                return false;
            }
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            String actual = props.get(k);
            if (actual == null || !actual.equalsIgnoreCase(v)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Multipart {@code when} match: an object of property = value|list, or
     * nested {@code OR}/{@code AND} lists of such objects (recursive).
     */
    private static boolean matchesWhen(JsonObject when, Map<String, String> props) {
        for (Map.Entry<String, JsonElement> e : when.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (k.equals("OR")) {
                boolean any = false;
                for (JsonElement alt : v.getAsJsonArray()) {
                    if (matchesWhen(alt.getAsJsonObject(), props)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    return false;
                }
            } else if (k.equals("AND")) {
                for (JsonElement all : v.getAsJsonArray()) {
                    if (!matchesWhen(all.getAsJsonObject(), props)) {
                        return false;
                    }
                }
            } else {
                String actual = props.get(k);
                if (actual == null) {
                    return false;
                }
                if (v.isJsonArray()) {
                    boolean any = false;
                    for (JsonElement alt : v.getAsJsonArray()) {
                        if (alt.getAsString().equalsIgnoreCase(actual)) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        return false;
                    }
                } else if (!v.getAsString().equalsIgnoreCase(actual)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- model graph copying (single-part rows) --------------------------------

    /**
     * Copy one model (by its blockstate-ref form, {@code ns:path} or bare
     * {@code path} — namespace defaults to the BLOCKSTATE's namespace) plus
     * its parent graph and textures into the pack at original paths. Returns
     * the fully-qualified model ref, or null when missing.
     *
     * <p>14-ay MINECRAFT FALLBACK: a bare ref resolves under the state's
     * namespace FIRST (the measured create chain rule) and under
     * {@code minecraft:} SECOND (the vanilla resource-location rule). The
     * single-namespace lookups split the ladder: create's bare parents
     * (create-side jsons) resolved, but BOP's bare parents point at VANILLA
     * templates ({@code block/cube_all}, {@code block/template_fence_gate},
     * {@code block/cross}...) — under {@code assets/biomesoplenty/models/}
     * they miss and 5609 of 7174 BOP states dropped out of the pack (the
     * #114 fence/fence-gate/stairs/trapdoor/door/leaves/plant walls rendered
     * EMPTY; the same census the 14-ax worklog parked: "no pack CMD for 103
     * BOP stairs, 55 branches, 51 fences, 47 leaves").
     */
    static String copyModelGraph(List<Path> jars, String stateNs, String modelRef,
                                 TreeMap<String, byte[]> entries,
                                 List<String> warnings) throws IOException {
        String ref = modelRef.contains(":") ? modelRef : stateNs + ":" + modelRef;
        int colon = ref.indexOf(':');
        String ns = ref.substring(0, colon);
        String path = ref.substring(colon + 1);
        // candidates in priority order: the ref's own namespace, then the
        // minecraft fallback for a bare ref (vanilla semantics own it)
        List<String> cands = new ArrayList<>(List.of("assets/" + ns + "/models/" + path + ".json"));
        if (!ns.equals("minecraft") && !modelRef.contains(":")) {
            cands.add("assets/minecraft/models/" + path + ".json");
        }
        String[] candidates = cands.toArray(String[]::new);
        for (Path jar : jars) {
            ZipFile zf = jarCache(jars).zip(jar);
            for (String cand : candidates) {
                ZipEntry e = zf.getEntry(cand);
                if (e == null) {
                    continue;
                }
                byte[] modelBytes = zf.getInputStream(e).readAllBytes();
                // the ENTRY MODEL itself rides at its original path
                // (copyParentGraph only walks parents + textures — the
                // F2vPack item path does the same putIfAbsent dance)
                entries.putIfAbsent(cand, modelBytes);
                F2vPack.copyParentGraph(zf, cand, modelBytes, entries, warnings, 0);
                // report the namespace the model was actually FOUND under
                // (a minecraft fallback hit re-anchors the returned ref so
                // the composite walk starts at the right chain)
                String foundNs = cand.startsWith("assets/minecraft/")
                        ? "minecraft" : ns;
                return foundNs + ":" + path;
            }
        }
        return null;
    }

    // ---- composite synthesis (multipart rows) -----------------------------------

    /**
     * TRUE when the model's parent chain carries explicit {@code elements}
     * (block-shaped geometry). The 26.3 item channel routes those models
     * through {@link #synthesizeItemComposite} — see the F2vPack call site
     * for the measured atlas failure this avoids.
     */
    static boolean chainHasElements(List<Path> jars, String modelRef,
                                    List<String> warnings) throws IOException {
        // namespace inheritance mirrors resolveModelGraph: a BARE parent
        // ("block/lever_x") rides the namespace of the model that references
        // it — defaulting it to minecraft: (the naive walk) loses create:
        // chains mid-way and leaves raw cuboid models in the item def
        String cur = modelRef;
        String ns = null;
        int depth = 0;
        while (cur != null && depth++ <= 16) {
            int hc = cur.indexOf(':');
            String hopNs;
            String hopPath;
            if (hc > 0) {
                hopNs = cur.substring(0, hc);
                hopPath = cur.substring(hc + 1);
            } else {
                hopNs = ns != null ? ns : "minecraft";
                hopPath = cur;
            }
            ns = hopNs;
            String file = "assets/" + ns + "/models/" + hopPath + ".json";
            JsonObject json = readJsonFromJars(jars, file);
            if (json == null && !ns.equals("minecraft")) {
                // 14-ay MINECRAFT FALLBACK (see copyModelGraph)
                json = readJsonFromJars(jars, "assets/minecraft/models/" + hopPath + ".json");
                if (json != null) {
                    ns = "minecraft";
                }
            }
            if (json == null) {
                return false;
            }
            // NEOFORGE:COMPOSITE (14-o): the loader shape carries the geometry
            // in "children" sub-models, not in a top-level "elements" array —
            // create 6.0 ships nixie_tube / display_link / stock_link this
            // way, and the naive elements-only predicate resolved those chains
            // as element-less (37 nixie gallery states never placed). A
            // children level counts as geometry; resolveModelGraph flattens it.
            if (json.has("elements") || json.has("children") || isObjModel(json)) {
                return true;
            }
            cur = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        return false;
    }

    /**
     * The 26.3 ITEM-CHANNEL shape (measured #49/#50): an item model whose
     * chain carries block geometry bakes sprites from the BLOCKS atlas while
     * the items/*.json wrapper expects the ITEMS atlas — a mixed or
     * vanilla-sprite set aborts the ENTIRE host def bake ("Multiple atlases
     * used in model, expected items.png, but also got blocks.png"). The
     * PROVEN shape (the #49 wall composites rendered textured end to end) is
     * a standalone composite: inline elements + the model's own textures,
     * every sprite PACK-SIDE (vanilla sprites are re-hosted under
     * c-imagine:block/items/ from the client jar). Sprite-driven item models
     * (item/generated chains) stay vanilla-shaped — items-atlas only.
     *
     * @param itemNo deterministic emit index (the file name + sprite keys)
     * @return the composite model ref, or null when the chain has no elements
     */
    static String synthesizeItemComposite(List<Path> jars, String modelRef, int itemNo,
                                          TreeMap<String, byte[]> entries,
                                          List<String> warnings) throws IOException {
        ModelGraph mg = resolveModelGraph(jars, modelRef, warnings);
        if (mg == null || mg.elements == null || mg.elements.size() == 0) {
            return null;
        }
        materializeCutouts(jars, mg, entries, warnings);
        Map<String, String> textures = new TreeMap<>();
        int texNo = 0;
        for (Map.Entry<String, String> t : mg.textures.entrySet()) {
            String sprite = t.getValue();
            if (sprite.startsWith("#") || sprite.isEmpty()) {
                continue;
            }
            // THE ATLAS OWNERSHIP RULE (measured #50, then decompiled —
            // CuboidItemModelWrapper.validateAtlasUsage): every quad of ONE
            // model must come from ONE atlas. The composite's quads bake
            // against the BLOCKS atlas (its sprites live under textures/
            // block/ of the pack stack) — so EVERY sprite must be a
            // blocks-atlas sprite: vanilla (namespaced OR BARE) sprites AND
            // item/-path sprites (create block models reference item-path
            // textures too — measured: those landed in items.png and the
            // mixed quad set aborted the whole host def bake) are re-hosted
            // under c-imagine:block/items/ from the jars (the client jar
            // carries the vanilla PNGs).
            boolean vanillaSprite = sprite.startsWith("minecraft:") || !sprite.contains(":");
            String spritePath = spritePathOf(sprite);
            boolean itemPathSprite = !vanillaSprite && spritePath.startsWith("item/");
            if (vanillaSprite || itemPathSprite) {
                String key = "items/t" + itemNo + "_" + texNo;
                String newSprite = WRAPPER_NS + ":block/" + key;
                String png = vanillaSprite
                        ? "assets/minecraft/textures/" + spritePath + ".png"
                        : spritePathPng(sprite);
                byte[] bytes = readPngFromJars(jars, png);
                if (bytes == null) {
                    warnings.add("item composite: texture missing: " + sprite
                            + " (" + modelRef + ")");
                    continue;
                }
                putHosted(jars, key, png, bytes, entries, warnings);
                textures.put(t.getKey(), newSprite);
                texNo++;
            } else {
                // pack block-path sprite: its PNG was already copied by copyModelGraph
                textures.put(t.getKey(), sprite);
            }
        }
        if (textures.isEmpty()) {
            warnings.add("item composite without textures: " + modelRef);
            return null;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("parent", "minecraft:block/block");
        JsonObject texObj = new JsonObject();
        for (Map.Entry<String, String> t : textures.entrySet()) {
            texObj.addProperty(t.getKey(), t.getValue());
        }
        wrapper.add("textures", texObj);
        wrapper.add("elements", mg.elements.deepCopy());
        recordScale(WRAPPER_NS + ":items/i" + itemNo, mg.displayScale());
        String file = "assets/" + WRAPPER_NS + "/models/items/i" + itemNo + ".json";
        entries.put(file, wrapper.toString().getBytes(StandardCharsets.UTF_8));
        return WRAPPER_NS + ":items/i" + itemNo;
    }

    /** {@code ns:block/foo} -> {@code block/foo} (the textures/ sub path). */
    private static String spritePathOf(String sprite) {
        int c = sprite.indexOf(':');
        return c > 0 ? sprite.substring(c + 1) : sprite;
    }

    /** {@code ns:path} -> {@code assets/ns/textures/path.png}. */
    private static String spritePathPng(String sprite) {
        // Captured direct bindings can live anywhere in assets/, e.g.
        // Mekanism's render/jetpack.png. @ distinguishes exact resources
        // from atlas sprite IDs, which omit textures/ and .png.
        if (sprite.startsWith("@")) {
            return "assets/" + sprite.substring(1).replace(':', '/');
        }
        int c = sprite.indexOf(':');
        String ns = c > 0 ? sprite.substring(0, c) : "minecraft";
        String path = c > 0 ? sprite.substring(c + 1) : sprite;
        return "assets/" + ns + "/textures/" + path + ".png";
    }

    /** Read one PNG from the jars (the client jar carries vanilla textures). */
    private static byte[] readPngFromJars(List<Path> jars, String file) throws IOException {
        return jarCache(jars).read(file);
    }

    private static JarCache jars;
    private static List<Path> jarsKey;

    /** Open-once reads: every lookup reopening 9 jars was most of the pack build (see JarCache). */
    private static synchronized JarCache jarCache(List<Path> paths) {
        if (!paths.equals(jarsKey)) {
            resetJarCache();
            jars = new JarCache(paths);
            jarsKey = List.copyOf(paths);
        }
        return jars;
    }

    /** Fresh reads for a new build; a selftest fixture may rewrite a jar at the same path. */
    static synchronized void resetJarCache() {
        if (jars != null) {
            jars.close();
        }
        jars = null;
        jarsKey = null;
    }

    /**
     * Merge the matching parts into ONE composite model file:
     * elements concatenated (part rotations baked, texture keys namespaced
     * per part), concrete texture map merged, parent minecraft:block/block
     * restores the standard display-context transforms. Returns the model
     * ref, or null when no part contributed elements.
     */
    private static String synthesizeComposite(List<Path> jars, String state, List<Part> parts,
                                              int idx, TreeMap<String, byte[]> entries,
                                              List<String> warnings, String ctCtx,
                                              Map<String, double[]> tintTable,
                                              List<String> bakeLog,
                                              Map<String, Integer> emissiveTable,
                                              List<String> emissiveLog) throws IOException {
        JsonArray mergedElements = new JsonArray();
        Map<String, String> textures = new TreeMap<>();
        double displayScale = 1;
        int partNo = 0;
        for (Part p : parts) {
            ModelGraph mg = resolveModelGraph(jars, p.modelRef(), warnings);
            if (mg == null || mg.elements == null || mg.elements.size() == 0) {
                warnings.add("gallery: composite part without elements: "
                        + p.modelRef() + " (" + state + ")");
                partNo++;
                continue;
            }
            materializeCutouts(jars, mg, entries, warnings);
            displayScale = Math.max(displayScale, mg.displayScale());
            String prefix = "p" + partNo + "_";
            for (Map.Entry<String, String> t : mg.textures.entrySet()) {
                textures.put(prefix + t.getKey(), t.getValue());
            }
            JsonArray baked = bakeRotation(mg.elements, p.xRot(), p.yRot(), p.uvlock());
            if (p.yOff() != 0.0) {
                baked = shiftElementsY(baked, p.yOff() * 16.0);
            }
            for (JsonElement el : baked) {
                mergedElements.add(renameTextureRefs(
                        inflateDegenerate(el.getAsJsonObject()), prefix));
            }
            partNo++;
        }
        if (mergedElements.size() == 0) {
            return null;
        }
        int[] ctIndices = ctCtx == null ? null : parseCtCtx(ctCtx);
        if (ctIndices != null) {
            applyCtRemap(jars, mergedElements, textures, ctIndices, entries, warnings,
                    state);
        }
        // TINT-BAKE (scripts/f2v/tint-calibrate.py): rewrite calibrated
        // sprites to baked siblings BEFORE the wrapper is emitted. The
        // elements reference texture KEYS (#p0_cross...), so only this map's
        // VALUES change — the raw texture at its original path keeps serving
        // every untinted consumer.
        bakeCompositeTextures(jars, textures, tintTable, entries, bakeLog);
        // STATE-KEYED tint route (the visual-tints.tsv rows keyed by the
        // STATE): rewrite this composite's texture refs to per-state baked
        // siblings — the shared vanilla texture keeps serving the vanilla
        // block (the per-texture pass above never touches vanilla-namespace
        // sprites, the calibrator routes them here instead).
        applyStateTint(jars, state, textures, tintTable, entries, bakeLog);
        // EMISSIVE-BAKE (visual-emissive.tsv): set the tabled state's
        // light_emission on EVERY merged element BEFORE the wrapper is
        // emitted — the element property rides the emitted JSON verbatim.
        applyEmissive(state, mergedElements, emissiveTable, emissiveLog);
        // 14-bs: the door sprites sample their own exact tiles (the atlas
        // layouts/mip chains differ between the legs)
        bakeFaceTiles(mergedElements, textures, jars, entries, warnings);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("parent", "minecraft:block/block");
        wrapper.addProperty("render_type", "minecraft:cutout");
        // 14-bv: the cutout render type — the composites inherit the block/block
        // SOLID layer where the transparent PNG pixels render their RGB (the
        // framed-glass panes, the window glass, the scaffold and DT-leaf holes
        // all measured BLACK panes at ae 60K-693K); the cutout discards the
        // alpha<0.1 pixels and the see-through classes render the wall behind
        JsonObject texObj = new JsonObject();
        for (Map.Entry<String, String> t : textures.entrySet()) {
            texObj.addProperty(t.getKey(), t.getValue());
        }
        wrapper.add("textures", texObj);
        wrapper.add("elements", mergedElements);
        recordScale(WRAPPER_REF_PREFIX + idx, displayScale);
        String file = WRAPPER_PREFIX + idx + ".json";
        String json = wrapper.toString();
        entries.put(file, json.getBytes(StandardCharsets.UTF_8));
        return WRAPPER_REF_PREFIX + idx;
    }

    // ---- tint-bake (visual-tints.tsv) --------------------------------------------

    /** The baked-sprite name suffix (also the PNG file suffix). A DISTINCT
     *  path keeps the raw texture intact for the item channel and the
     *  note_block block channel — only gallery composites reference it. */
    static final String TINT_SUFFIX = "_cimtint";

    /**
     * Rewrite every tinted sprite ref in the composite's texture map to its
     * baked sibling. Deterministic: the map is sorted, one bake per UNIQUE
     * sprite (repeated part keys share it), and the multiply is pure integer
     * math on the decoded pixels.
     */
    private static void bakeCompositeTextures(List<Path> jars,
                                              Map<String, String> textures,
                                              Map<String, double[]> tintTable,
                                              TreeMap<String, byte[]> entries,
                                              List<String> bakeLog) throws IOException {
        if (tintTable == null || tintTable.isEmpty() || textures.isEmpty()) {
            return;
        }
        Map<String, String> bakedRefs = new TreeMap<>();
        for (Map.Entry<String, String> e : textures.entrySet()) {
            String sprite = e.getValue();
            if (!sprite.contains(":") || sprite.endsWith(TINT_SUFFIX)) {
                continue;
            }
            double[] tint = tintTable.get(sprite);
            if (tint == null) {
                continue;
            }
            String baked = bakedRefs.computeIfAbsent(sprite,
                    s -> bakeTintedSprite(jars, s, tint, entries, bakeLog));
            if (baked != null) {
                e.setValue(baked);
            }
        }
    }

    /**
     * Multiply one raw texture's RGB by the calibrated tint and put the PNG
     * at the sibling path ({@code textures/<dir>/<name>_cimtint.png}).
     * Returns the baked sprite ref, or null when the raw PNG is unavailable
     * (the composite keeps the raw sprite then — an honest partial render).
     * Alpha is preserved; rounding is Math.round (floor(v+0.5)) on the
     * double product, clamped to [0,255] — the SAME documented rounding the
     * calibrator's evidence assumes.
     */
    static String bakeTintedSprite(List<Path> jars, String sprite, double[] tint,
                                   TreeMap<String, byte[]> entries, List<String> bakeLog) {
        return bakeTintedSprite(jars, sprite, tint, TINT_SUFFIX, entries, bakeLog);
    }

    /**
     * Same bake at a caller-chosen sibling suffix — the STATE-KEYED route
     * re-hosts the shared vanilla texture per state ({@code _cimtint_s<hash>})
     * so two states of one texture never collide and the raw path keeps
     * serving the vanilla block.
     */
    static String bakeTintedSprite(List<Path> jars, String sprite, double[] tint,
                                   String suffix, TreeMap<String, byte[]> entries,
                                   List<String> bakeLog) {
        String rawPng = spritePathPng(sprite);
        String bakedPng = rawPng.substring(0, rawPng.length() - 4) + suffix + ".png";
        try {
            byte[] raw = readPngFromJars(jars, rawPng);
            if (raw == null) {
                return null;
            }
            byte[] baked = multiplyPng(raw, tint);
            entries.put(bakedPng, baked);
            if (bakeLog != null) {
                bakeLog.add(sprite + " <- (" + tint[0] + ", " + tint[1] + ", "
                        + tint[2] + ") baked at " + sprite + suffix);
            }
            return sprite + suffix;
        } catch (Exception bad) {
            return null; // the composite falls back to the raw sprite
        }
    }

    /**
     * THE STATE-KEYED tint route (visual-tints.tsv rows keyed by the STATE,
     * not the texture): the modded states whose models resolve to
     * minecraft-namespace textures (the DT roots render vanilla log textures
     * under the mod's IBlockColor tint) SHARE those PNGs with the vanilla
     * blocks — a per-TEXTURE bake would tint the vanilla consumer too. This
     * rewrites EVERY texture ref of the STATE's own composite to a
     * state-suffixed sibling ({@code ..._cimtint_s<hash8>}, hash of the state
     * string) and bakes the multiply there; the raw path keeps serving the
     * vanilla block and every untinted consumer. Lookup: exact manifest state
     * string, then the canonical form (the same rule applyEmissive carries).
     * No row for the state = no-op (byte-identical output). Values already
     * re-hosted by the per-texture bake ({@code _cimtint}) are skipped —
     * one multiply per pixel, never two.
     */
    /** The state-keyed tint row for a composite's state (the exact manifest
     *  string first, then the canonical property order — the same rule
     *  applyEmissive carries), or null. */
    private static double[] stateTintFor(String state, Map<String, double[]> tintTable) {
        if (tintTable == null || tintTable.isEmpty() || state == null) {
            return null;
        }
        double[] tint = tintTable.get(state);
        return tint != null ? tint : tintTable.get(canonicalState(state));
    }

    private static void applyStateTint(List<Path> jars, String state,
                                       Map<String, String> textures,
                                       Map<String, double[]> tintTable,
                                       TreeMap<String, byte[]> entries,
                                       List<String> bakeLog) {
        if (tintTable == null || tintTable.isEmpty() || textures.isEmpty()) {
            return;
        }
        double[] tint = stateTintFor(state, tintTable);
        if (tint == null) {
            return;
        }
        String suffix = TINT_SUFFIX + "_s" + stateHash(state);
        final double[] ftint = tint; // the bake lambda's capture
        Map<String, String> bakedRefs = new TreeMap<>();
        for (Map.Entry<String, String> e : textures.entrySet()) {
            String sprite = e.getValue();
            if (!sprite.contains(":") || sprite.contains(TINT_SUFFIX)) {
                continue;
            }
            String baked = bakedRefs.computeIfAbsent(sprite,
                    s -> bakeTintedSprite(jars, s, ftint, suffix, entries, bakeLog));
            if (baked != null) {
                e.setValue(baked);
            }
        }
    }

    /** 8 hex chars of the state string's SHA-1 — a deterministic,
     *  filesystem-safe discriminator between states sharing one texture. */
    private static String stateHash(String state) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(state.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return String.format("%08x", state.hashCode());
        }
    }

    /**
     * THE multiply, byte-deterministic: decode via ImageIO, per-pixel
     * channel multiply with Math.round + clamp, re-encode via ImageIO. The
     * same inputs on the same JDK produce the same bytes — the same
     * constraint the pack's sha1 story already carries (CI pre-boot build
     * and the plugin's boot-time rebuild pin one Paper/JDK).
     */
    static byte[] multiplyPng(byte[] png, double[] tint) throws IOException {
        java.awt.image.BufferedImage in =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        if (in == null) {
            throw new IOException("not a decodable PNG");
        }
        int w = in.getWidth();
        int h = in.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = in.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                out.setRGB(x, y, (a << 24)
                        | (clamp255(r * tint[0]) << 16)
                        | (clamp255(g * tint[1]) << 8)
                        | clamp255(b * tint[2]));
            }
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }

    /** Multiply + Math.round + clamp into the 8-bit channel range. */
    private static int clamp255(double v) {
        long r = Math.round(v);
        return (int) Math.max(0, Math.min(255, r));
    }

    // ---- emissive-bake (visual-emissive.tsv) ------------------------------------

    /**
     * Set {@code light_emission} (the 1.21.2+ block-model ELEMENT property,
     * "defines the minimum light level that the element can receive" — purely
     * visual) on EVERY element of the emitted composite for a state in the
     * emissive table. The LIGHTMAP class: the real client renders these
     * states fullbright (the mod registers a lightLevel / emissiveRendering
     * in code), ours renders the same geometry dim (measured 0.43-0.77x
     * truth on the glow pixels, run 42085254497). Absent/empty table = no-op
     * (byte-identical output); a state not in the table = no-op. An element
     * that already carries the property is OVERWRITTEN — the table is the
     * measured calibration. The lookup matches the manifest state string
     * exactly, then the canonical form (re-sorted properties), so a table
     * row still binds when two writers order the same properties
     * differently. CT quad-suffix rows never reach here with the suffix
     * (build strips {@code #...} before this call), so the bare state is
     * what binds.
     */
    private static void applyEmissive(String state, JsonArray elements,
                                      Map<String, Integer> emissiveTable,
                                      List<String> emissiveLog) {
        if (emissiveTable == null || emissiveTable.isEmpty()) {
            return;
        }
        Integer level = emissiveTable.get(state);
        if (level == null) {
            level = emissiveTable.get(canonicalState(state));
        }
        if (level == null) {
            return;
        }
        for (JsonElement el : elements) {
            el.getAsJsonObject().addProperty("light_emission", level);
        }
        if (emissiveLog != null) {
            emissiveLog.add(state + " light_emission=" + level
                    + " on " + elements.size() + " elements");
        }
    }

    /** THE ITEM EMISSIVE-BAKE: the item channel's standalone composites
     *  (synthesizeItemComposite) get the same light_emission contract the
     *  gallery block composite honors — keyed by the modded ITEM id (an
     *  id without state props canonicalizes to itself). Returns the
     *  re-emitted model JSON; a non-tabled id returns the input unchanged
     *  (byte-identical). */
    static String applyItemEmissive(String itemId, String compJson,
                                    Map<String, Integer> emissiveTable,
                                    List<String> emissiveLog) {
        if (emissiveTable == null || emissiveTable.isEmpty()
                || compJson == null || !compJson.contains("\"elements\"")) {
            return compJson;
        }
        Integer level = emissiveTable.get(itemId);
        if (level == null) {
            level = emissiveTable.get(canonicalState(itemId));
        }
        if (level == null) {
            return compJson;
        }
        try {
            JsonObject obj = JsonParser.parseString(compJson).getAsJsonObject();
            if (!obj.has("elements") || !obj.get("elements").isJsonArray()) {
                return compJson;
            }
            for (JsonElement el : obj.getAsJsonArray("elements")) {
                el.getAsJsonObject().addProperty("light_emission", level);
            }
            if (emissiveLog != null) {
                emissiveLog.add(itemId + " light_emission=" + level
                        + " (item channel, "
                        + obj.getAsJsonArray("elements").size() + " elements)");
            }
            return obj.toString();
        } catch (Exception bad) {
            // a non-object model JSON cannot carry elements — unchanged
            return compJson;
        }
    }

    /** THE SPRITE EMISSIVE UPGRADE: a tabled item whose resolved model is a
     *  vanilla-shaped sprite (item/generated — no elements, so the element
     *  light_emission lever does not exist) upgrades to a flat-quad
     *  composite: one 16x16 plane carrying the sprite texture on both faces
     *  + light_emission from the table. Geometry-compatible with the sprite
     *  (the rig renders both as a flat quad), fullbright like the truth.
     *  Returns the composite model ref, or null when the item is not
     *  tabled / the sprite cannot resolve (the caller keeps the original
     *  model — byte-identical behavior). */
    static String synthesizeSpriteEmissive(List<Path> jars, String modelRef,
                                           String itemId, int itemNo,
                                           Map<String, Integer> emissiveTable,
                                           TreeMap<String, byte[]> entries,
                                           List<String> warnings) throws IOException {
        if (emissiveTable == null || emissiveTable.isEmpty()) {
            return null;
        }
        Integer level = emissiveTable.get(itemId);
        if (level == null) {
            level = emissiveTable.get(canonicalState(itemId));
        }
        if (level == null) {
            return null;
        }
        ModelGraph mg = resolveModelGraph(jars, modelRef, warnings);
        if (mg == null) {
            return null;
        }
        String sprite = mg.textures.get("layer0");
        if (sprite == null || sprite.isEmpty() || sprite.startsWith("#")) {
            // not a single-sprite model — the upgrade does not apply
            return null;
        }
        String texRef = sprite.contains(":") ? sprite : "minecraft:" + sprite;
        String spritePath = texRef.replace(':', '/') + ".png";
        byte[] png = null;
        for (Path jar : jars) {
            ZipEntry e = jarCache(jars).zip(jar).getEntry("assets/"
                    + texRef.split(":")[0] + "/textures/"
                    + texRef.split(":", 2)[1] + ".png");
            if (e != null) {
                png = jarCache(jars).zip(jar).getInputStream(e).readAllBytes();
                break;
            }
        }
        if (png == null) {
            warnings.add("sprite emissive: texture missing for " + itemId
                    + " (" + texRef + ") — kept the sprite model");
            return null;
        }
        entries.put("assets/" + texRef.split(":")[0] + "/textures/"
                + texRef.split(":", 2)[1] + ".png", png);
        String compRef = "c-imagine:gallery/sprite_emissive_" + itemNo;
        JsonObject el = new JsonObject();
        el.add("from", com.google.gson.JsonParser.parseString("[0.01,0.01,7.99]"));
        el.add("to", com.google.gson.JsonParser.parseString("[15.99,15.99,8.01]"));
        el.addProperty("shade_direction_override", "up");
        el.addProperty("light_emission", level);
        JsonObject faces = new JsonObject();
        for (String face : new String[]{"north", "south"}) {
            JsonObject f = new JsonObject();
            f.add("uv", com.google.gson.JsonParser.parseString("[0.0,0.0,16.0,16.0]"));
            f.addProperty("texture", "#p0");
            faces.add(face, f);
        }
        el.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(el);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("parent", "minecraft:block/block");
        wrapper.addProperty("render_type", "minecraft:cutout");
        // 14-bv: the cutout render type — the composites inherit the block/block
        // SOLID layer where the transparent PNG pixels render their RGB (the
        // framed-glass panes, the window glass, the scaffold and DT-leaf holes
        // all measured BLACK panes at ae 60K-693K); the cutout discards the
        // alpha<0.1 pixels and the see-through classes render the wall behind
        JsonObject texObj = new JsonObject();
        texObj.addProperty("p0", texRef);
        wrapper.add("textures", texObj);
        wrapper.add("elements", elements);
        entries.put("assets/c-imagine/models/"
                + compRef.split(":")[1] + ".json", wrapper.toString().getBytes(StandardCharsets.UTF_8));
        return compRef;
    }

    /** Block id + re-sorted {@code prop=val} pairs — one canonical spelling
     *  of a blockstate string ({@code ns:block[a=1,b=2]} and
     *  {@code ns:block[b=2,a=1]} both land here). */
    static String canonicalState(String state) {
        int br = state.indexOf('[');
        if (br < 0) {
            return state;
        }
        String base = state.substring(0, br);
        int close = state.endsWith("]") ? state.length() - 1 : state.length();
        String[] props = state.substring(br + 1, close).split(",");
        java.util.Arrays.sort(props);
        return base + '[' + String.join(",", props) + ']';
    }

    // ---- the real-client quad channel ---------------------------------------

    private static Path quadDir;

    /** Where the seed's QuadDump meshes live ({@code quads/<hash>.tsv}). */
    static void setQuadDir(Path dir) {
        quadDir = dir;
        itemQuadMap = null;
    }

    // ------------------------------------------------------- 14-bq doors --

    /**
     * The five Create sliding doors whose truth rendering the #188 crops
     * measured: an UPPER visible=false cell shows NOTHING (the upper BE's
     * bridgeTicks keep the BER out and getRenderShape hides the static),
     * while ours rode the full static panel at ae 129K–483K.
     */
    private static final java.util.regex.Pattern UPPER_INVISIBLE_DOOR =
            java.util.regex.Pattern.compile(
                    "create:(brass|copper|andesite|framed_glass|train)_door"
                            + "\\[.*half=upper.*visible=false.*\\]");

    /**
     * The settled slide of a lower visible=false open=true REGULAR door
     * (brass/framed_glass/train — the non-folding BER branch): the CLOSED
     * panel translated value²·13/16 along movementDirection + value2/32
     * along facing, in MODEL units (1/16 block). Copper/andesite fold
     * instead (the 91° panel rotation) — not a translation, untouched here.
     *
     * @return {dx, dz} in model units, or null when the state is not a
     *         slid lower door.
     */
    private static double[] slideDoorOffset(String state) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "create:(brass|framed_glass|train)_door\\[([^\\]]*)\\]").matcher(state);
        if (!m.matches()) {
            return null;
        }
        String props = m.group(2);
        // 14-ck: both halves ride the same slide (the BER anchors both
        // DoubleBlockHalf halves at the cell) — the half check is gone.
        if (!props.contains("visible=false") || !props.contains("open=true")) {
            return null;
        }
        java.util.regex.Matcher fm = java.util.regex.Pattern.compile("facing=(\\w+)")
                .matcher(props);
        java.util.regex.Matcher hm = java.util.regex.Pattern.compile("hinge=(\\w+)")
                .matcher(props);
        if (!fm.find() || !hm.find()) {
            return null;
        }
        // movementDirection = facing.clockWise, opposite when hinge=left
        int fx, fz; // facing vector in model units
        switch (fm.group(1)) {
            case "north" -> { fx = 0; fz = -1; }
            case "south" -> { fx = 0; fz = 1; }
            case "east" -> { fx = 1; fz = 0; }
            case "west" -> { fx = -1; fz = 0; }
            default -> { return null; }
        }
        int mvx, mvz;
        switch (fm.group(1)) {
            case "north" -> { mvx = 1; mvz = 0; }   // north.clockWise = east
            case "east" -> { mvx = 0; mvz = 1; }    // east.clockWise = south
            case "south" -> { mvx = -1; mvz = 0; }  // south.clockWise = west
            default -> { mvx = 0; mvz = -1; }       // west.clockWise = north
        }
        if (hm.group(1).equals("left")) {
            mvx = -mvx;
            mvz = -mvz;
        }
        // value=1 settled: 13/16 block along movement + 1/32 along facing
        return new double[]{mvx * 13.0 + fx * 0.5, mvz * 13.0 + fz * 0.5};
    }

    /** A composite that joins the wall as an intentionally blank cell. */
    private static String synthesizeEmptyComposite(int idx, TreeMap<String, byte[]> entries) {
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("parent", "minecraft:block/block");
        wrapper.addProperty("render_type", "minecraft:cutout");
        // 14-bv: the cutout render type — the composites inherit the block/block
        // SOLID layer where the transparent PNG pixels render their RGB (the
        // framed-glass panes, the window glass, the scaffold and DT-leaf holes
        // all measured BLACK panes at ae 60K-693K); the cutout discards the
        // alpha<0.1 pixels and the see-through classes render the wall behind
        wrapper.add("textures", new JsonObject());
        wrapper.add("elements", new JsonArray());
        recordScale(WRAPPER_REF_PREFIX + idx, 1.0);
        entries.put(WRAPPER_PREFIX + idx + ".json", wrapper.toString()
                .getBytes(StandardCharsets.UTF_8));
        return WRAPPER_REF_PREFIX + idx;
    }

    /** Shifts every element of an emitted composite by {@code off} = {dx, dz}. */
    private static void translateCompositeElements(String ref, double[] off,
                                                   TreeMap<String, byte[]> entries) {
        String ns, rest;
        String[] parts = ref.split(":", 2);
        if (parts.length != 2) {
            return;
        }
        ns = parts[0];
        rest = parts[1];
        String file = "assets/" + ns + "/models/" + rest + ".json";
        byte[] raw = entries.get(file);
        if (raw == null) {
            return;
        }
        JsonObject model = com.google.gson.JsonParser.parseString(new String(raw,
                StandardCharsets.UTF_8)).getAsJsonObject();
        if (!model.has("elements")) {
            return;
        }
        for (JsonElement el : model.getAsJsonArray("elements")) {
            JsonObject e = el.getAsJsonObject();
            if (!e.has("from") || !e.has("to")) {
                continue;
            }
            e.add("from", shiftVec(e.getAsJsonArray("from"), off));
            e.add("to", shiftVec(e.getAsJsonArray("to"), off));
        }
        entries.put(file, model.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonArray shiftVec(JsonArray v, double[] off) {
        JsonArray out = new JsonArray();
        out.add(v.get(0).getAsDouble() + off[0]);
        out.add(v.get(1).getAsDouble());
        out.add(v.get(2).getAsDouble() + off[1]);
        return out;
    }

    /**
     * The near-miss sampling classes — the sprites whose face windows bake
     * into exact-pixel objcut tiles (14-bs doors, 14-cb the ae2 chiseled
     * quartz: the highest-spatial-frequency texture in the gallery, the only
     * family where the minified wall sampling blurred the carved lines into
     * ae ~60-66K across the stairs/walls cells while every low-frequency
     * neighbour stayed green).
     */
    private static final java.util.regex.Pattern DOOR_SPRITE =
            java.util.regex.Pattern.compile(
                    "create:block/(brass|copper|andesite|framed_glass|train)_door_.*"
                    + "|ae2:block/chiseled_quartz.*");

    /**
     * 14-bs: bake every door-sprite face window into an exact-pixel objcut
     * tile. The near-miss door cells (55 at ae 63K–151K, shift [0,0], luma
     * ~1) sample the direct sprites through each leg's atlas; the atlas
     * layouts and mip chains differ (1.21.1 vs 26.3) and the minified wall
     * sampling blends neighbours differently. With the face sampling ITS OWN
     * pixels 1:1 from a guttered sheet tile, the atlas layout stops
     * mattering. Mirrored windows keep their orientation (the linear uv
     * remap preserves the corner order); the per-face vertex rotation is
     * untouched (it composes linearly with the remap).
     */
    private static void bakeFaceTiles(JsonArray elements, Map<String, String> textures,
                                      List<Path> jars, TreeMap<String, byte[]> entries,
                                      List<String> warnings) {
        int bakeNo = 0;
        for (JsonElement elE : elements) {
            JsonObject el = elE.getAsJsonObject();
            if (!el.has("faces")) {
                continue;
            }
            JsonObject faces = el.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
                JsonObject face = fe.getValue().getAsJsonObject();
                if (!face.has("texture") || !face.has("uv")) {
                    continue;
                }
                String texKey = face.get("texture").getAsString();
                String mapKey = texKey.startsWith("#") ? texKey.substring(1) : texKey;
                String sprite = textures.get(mapKey);
                if (sprite == null || !DOOR_SPRITE.matcher(sprite).matches()) {
                    continue;
                }
                JsonArray uvA = face.getAsJsonArray("uv");
                double u0 = uvA.get(0).getAsDouble();
                double v0 = uvA.get(1).getAsDouble();
                double u1 = uvA.get(2).getAsDouble();
                double v1 = uvA.get(3).getAsDouble();
                double spanU = Math.abs(u1 - u0);
                double spanV = Math.abs(v1 - v0);
                if (spanU < 0.01 || spanV < 0.01) {
                    continue;
                }
                byte[] png = entries.get(spritePathPng(sprite));
                if (png == null) {
                    try {
                        png = readPngFromJars(jars, spritePathPng(sprite));
                    } catch (IOException e) {
                        png = null;
                    }
                }
                if (png == null) {
                    warnings.add("14-bs: door sprite missing: " + sprite);
                    continue;
                }
                java.awt.image.BufferedImage src;
                try {
                    src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
                } catch (IOException e) {
                    warnings.add("14-bs: door sprite unreadable: " + sprite);
                    continue;
                }
                if (src == null) {
                    continue;
                }
                int w = src.getWidth();
                int h = src.getHeight();
                int px = (int) Math.round(Math.min(u0, u1) / 16.0 * w);
                int py = (int) Math.round(Math.min(v0, v1) / 16.0 * h);
                int pw = Math.max(1, (int) Math.round(spanU / 16.0 * w));
                int ph = Math.max(1, (int) Math.round(spanV / 16.0 * h));
                px = Math.max(0, Math.min(w - 1, px));
                py = Math.max(0, Math.min(h - 1, py));
                pw = Math.max(1, Math.min(w - px, pw));
                ph = Math.max(1, Math.min(h - py, ph));
                int[] at = ObjMesh.placeExactTile(src.getSubimage(px, py, pw, ph));
                String key = "b" + (bakeNo++);
                textures.put(key, WRAPPER_NS + ":block/objcut/sheet" + at[0]);
                face.addProperty("texture", "#" + key);
                // A sheet sprite contains many tiles. Sample only this
                // allocation, preserving each source axis's orientation.
                JsonArray nuv = new JsonArray();
                nuv.add(ObjMesh.sheetUv(at[1] + (u0 > u1 ? pw : 0)));
                nuv.add(ObjMesh.sheetUv(at[2] + (v0 > v1 ? ph : 0)));
                nuv.add(ObjMesh.sheetUv(at[1] + (u1 > u0 ? pw : 0)));
                nuv.add(ObjMesh.sheetUv(at[2] + (v1 > v0 ? ph : 0)));
                face.add("uv", nuv);
            }
        }
    }

    /**
     * A cell's model from the quads the real client baked for it: connected
     * textures, neighbour-aware meshes and culled see-through faces arrive
     * already resolved, whichever mod produced them.
     */
    private static String synthesizeQuadComposite(List<Path> jars, String hash, String stateKey, int idx,
                                                  TreeMap<String, byte[]> entries,
                                                  List<String> warnings,
                                                  Map<String, double[]> tintTable) throws IOException {
        return synthesizeQuadModel(jars, hash, WRAPPER_PREFIX + idx + ".json", WRAPPER_REF_PREFIX + idx,
                entries, warnings, true, tintTable, stateKey);
    }

    /** The item's mesh from the real ItemRenderer, when the dump captured one. */
    static String synthesizeItemQuads(List<Path> jars, String itemId, int itemNo,
                                      TreeMap<String, byte[]> entries,
                                      List<String> warnings) throws IOException {
        String hash = itemQuads().get(itemId);
        if (hash == null) {
            return null;
        }
        return synthesizeQuadModel(jars, hash, "assets/" + WRAPPER_NS + "/models/items/i" + itemNo + ".json",
                WRAPPER_NS + ":items/i" + itemNo, entries, warnings, false, null, null);
    }

    private static Map<String, String> itemQuadMap;

    private static Map<String, String> itemQuads() throws IOException {
        if (itemQuadMap == null) {
            itemQuadMap = new java.util.HashMap<>();
            Path file = quadDir == null ? null : quadDir.resolve("items.tsv");
            if (file != null && java.nio.file.Files.isRegularFile(file)) {
                for (String line : java.nio.file.Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] c = line.split("\\t");
                    if (c.length == 2) {
                        itemQuadMap.put(c[0], c[1]);
                    }
                }
            }
        }
        return itemQuadMap;
    }

    private static String synthesizeQuadModel(List<Path> jars, String hash, String modelFile, String ref,
                                              TreeMap<String, byte[]> entries,
                                              List<String> warnings, boolean block,
                                              Map<String, double[]> tintTable,
                                              String stateKey) throws IOException {
        Path file = quadDir == null ? null : quadDir.resolve(hash + ".tsv");
        if (file == null || !java.nio.file.Files.isRegularFile(file)) {
            warnings.add("gallery: quad mesh missing: " + hash);
            return null;
        }
        List<ObjMesh.Face> faces = new ArrayList<>();
        List<int[]> flags = new ArrayList<>();
        String material = null;
        Map<String, String> textures = new LinkedHashMap<>();
        Map<String, String> keyOf = new LinkedHashMap<>();
        int vertex = 0;
        for (String line : java.nio.file.Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] c = line.split("\t");
            if (c.length < 23) {
                continue;
            }
            String faceMaterial = c.length >= 40 ? c[39]
                    : (block ? "cutout" : "translucent");
            if (!(faceMaterial.equals("solid") || faceMaterial.equals("cutout") || faceMaterial.equals("translucent"))) {
                throw new IOException("gallery: unsupported captured material " + faceMaterial);
            }
            if (material != null && !material.equals(faceMaterial)) {
                throw new IOException("gallery: mixed captured materials need separate model layers");
            }
            material = faceMaterial;
            String key = keyOf.get(c[0]);
            if (key == null) {
                key = "q" + keyOf.size();
                keyOf.put(c[0], key);
                // TINT-BAKE (scripts/f2v/tint-calibrate.py) reaches the QUAD
                // channel through the same calibrated-sibling rule the static
                // composites use (bakeCompositeTextures): a tabled sprite is
                // re-hosted as its ..._cimtint sibling and THIS composite's
                // key points at the sibling — the raw sprite stays intact
                // for every untinted consumer. The block channel only: the
                // item quad path passes no table (the tint rows are measured
                // on block-state cells; an item icon's lighting is its own
                // surface).
                String hosted = hostSprite(jars, c[0], entries, warnings);
                double[] tint = tintTable == null ? null : tintTable.get(c[0]);
                if (tint != null) {
                    String sibling = bakeTintedSprite(jars, c[0], tint, entries, warnings);
                    if (sibling != null) {
                        hosted = sibling;
                    }
                }
                // STATE-KEYED tint route (the rows keyed by the STATE): the
                // quad meshes the DT/BOP states share with vanilla blocks —
                // the per-tex table never carries minecraft-namespace rows,
                // so this is the only bake that reaches them. Each state
                // re-hosts its own suffixed sibling (_cimtint_s<hash>), the
                // shared raw texture keeps serving the vanilla consumer.
                if (!hosted.contains(TINT_SUFFIX)) {
                    double[] stint = stateTintFor(stateKey, tintTable);
                    if (stint != null) {
                        String sib = bakeTintedSprite(jars, c[0], stint,
                                TINT_SUFFIX + "_s" + stateHash(stateKey), entries, warnings);
                        if (sib != null) {
                            hosted = sib;
                        }
                    }
                }
                textures.put(key, hosted);
            }
            List<ObjMesh.Vert> verts = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int o = 3 + i * 5;
                double[] p = {Double.parseDouble(c[o]), Double.parseDouble(c[o + 1]), Double.parseDouble(c[o + 2])};
                double[] uv = {Double.parseDouble(c[o + 3]), Double.parseDouble(c[o + 4])};
                verts.add(new ObjMesh.Vert(p, uv, vertex, vertex));
                vertex++;
            }
            float[][] colors = null;
            if (c.length >= 35) {
                colors = new float[4][c.length >= 39 ? 4 : 3];
                for (int i = 0; i < 12; i++) {
                    colors[i / 3][i % 3] = Float.parseFloat(c[23 + i]);
                }
                if (c.length >= 39) {
                    for (int i = 0; i < 4; i++) {
                        colors[i][3] = Float.parseFloat(c[35 + i]);
                    }
                }
            }
            faces.add(new ObjMesh.Face(verts, "#" + key, colors));
            flags.add(new int[]{Integer.parseInt(c[1]), Integer.parseInt(c[2])});
        }
        if (faces.isEmpty()) {
            return null;
        }
        double k = ObjMesh.fitElements(faces);
        JsonArray elements = new JsonArray();
        for (int i = 0; i < faces.size(); i++) {
            // A non-planar source quad emits multiple triangles. All pieces
            // inherit this face's metadata; output indices are not face IDs.
            for (var value : ObjMesh.toElements(List.of(faces.get(i)), k)) {
                JsonObject el = value.getAsJsonObject();
                if (flags.get(i)[0] == 0) {
                    // These fields alone do not disable ITEM shader diffuse.
                    el.addProperty("shade", false);
                    el.addProperty("shade_direction_override", "up");
                }
                if (flags.get(i)[1] >= 0 && el.has("faces")) {
                    el.getAsJsonObject("faces").getAsJsonObject("south")
                            .addProperty("tintindex", flags.get(i)[1]);
                }
                elements.add(el);
            }
        }
        // a re-hosted sprite exists only in the pack, not in any jar
        ObjMesh.materialize(elements, textures, sprite -> {
            byte[] hosted = entries.get(spritePathPng(sprite));
            return hosted != null ? hosted : readPngFromJars(jars, spritePathPng(sprite));
        }, WRAPPER_NS, entries, warnings);
        // 14-bs: the door sprites sample their own exact tiles (the atlas
        // layouts/mip chains differ between the legs) — the q-path composites
        // carry the same direct sprite windows
        bakeFaceTiles(elements, textures, jars, entries, warnings);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("parent", "minecraft:block/block");
        // 14-bv: the cutout render type — the composites inherit the block/block
        // SOLID layer where the transparent PNG pixels render their RGB (the
        // framed-glass panes, the window glass, the scaffold and DT-leaf holes
        // all measured BLACK panes at ae 60K-693K); the cutout discards the
        // alpha<0.1 pixels and the see-through classes render the wall behind.
        // 14-bz CHANNEL SPLIT (measured #230): the ITEM quads must ride the
        // TRANSLUCENT sheet, not cutout — the truth's item pages render real
        // items through the vanilla item sheet (alpha BLEND, soft pixels mix
        // with the backdrop), while a cutout composite BINARY-discards the
        // faint texels and every soft-alpha item fell off the gate the moment
        // the 14-by tile alpha landed (create:experience_block 63K,
        // experience_nugget 65K — green on the #229 opaque-black tiles by
        // accident: the dark wall made the black-mix look like the blend).
        // The block channel keeps cutout: the truth renders its blocks on the
        // chunk cutout layer (binary), the alpha=0 holes discard on BOTH legs.
        wrapper.addProperty("render_type", "minecraft:" + material);
        JsonObject texObj = new JsonObject();
        for (Map.Entry<String, String> t : textures.entrySet()) {
            texObj.addProperty(t.getKey(), t.getValue());
        }
        wrapper.add("textures", texObj);
        wrapper.add("elements", elements);
        recordScale(ref, k);
        entries.put(modelFile, wrapper.toString().getBytes(StandardCharsets.UTF_8));
        if (block) {
            registerUnlitBlockLayers(wrapper, ref, k, entries);
        }
        return ref;
    }

    private static void registerUnlitBlockLayers(JsonObject wrapper, String ref, double scale,
                                                 TreeMap<String, byte[]> entries) {
        JsonArray ordinary = new JsonArray();
        JsonArray children = new JsonArray();
        for (var value : wrapper.getAsJsonArray("elements")) {
            JsonObject element = value.getAsJsonObject();
            if (!element.has("shade") || element.get("shade").getAsBoolean()) {
                ordinary.add(element);
                continue;
            }
            UnlitBlockModel.Plane unlit = UnlitBlockModel.plane(element, scale);
            JsonObject child = wrapper.deepCopy();
            JsonArray one = new JsonArray();
            one.add(unlit.element());
            child.add("elements", one);
            String childRef = sharedUnlitModel(child, entries);
            JsonObject node = modelNode(childRef);
            node.add("transformation", unlit.transformation());
            children.add(node);
        }
        if (children.isEmpty()) {
            return;
        }
        if (!ordinary.isEmpty()) {
            String childRef = ref + "_shaded";
            JsonObject child = wrapper.deepCopy();
            child.add("elements", ordinary);
            entries.put(modelPath(childRef), child.toString().getBytes(StandardCharsets.UTF_8));
            recordScale(childRef, scale);
            children.add(F2vPack.itemModelNode(childRef));
        }
        JsonObject composite = new JsonObject();
        composite.addProperty("type", "minecraft:composite");
        composite.add("models", children);
        ITEM_NODES.put(ref, composite);
    }

    /** Share identical native planes without changing draw order or node transforms. */
    private static String sharedUnlitModel(JsonObject child, TreeMap<String, byte[]> entries) {
        JsonObject oldTextures = child.getAsJsonObject("textures");
        JsonObject textures = new JsonObject();
        Map<String, String> keys = new LinkedHashMap<>();
        for (var element : child.getAsJsonArray("elements")) {
            for (var value : element.getAsJsonObject().getAsJsonObject("faces").entrySet()) {
                JsonObject face = value.getValue().getAsJsonObject();
                String sprite = unlitTexture(oldTextures, face.get("texture").getAsString());
                String key = keys.get(sprite);
                if (key == null) {
                    key = "t" + keys.size();
                    keys.put(sprite, key);
                    textures.addProperty(key, sprite);
                }
                face.addProperty("texture", "#" + key);
            }
        }
        // Generated native planes have no particle binding; preserve one if
        // a future producer adds it rather than copying all unused textures.
        if (oldTextures.has("particle")) {
            textures.addProperty("particle", unlitTexture(oldTextures, oldTextures.get("particle").getAsString()));
        }
        child.add("textures", textures);
        byte[] bytes = child.toString().getBytes(StandardCharsets.UTF_8);
        final String hash;
        try {
            hash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String ref = WRAPPER_NS + ":gallery/unlit/" + hash;
        byte[] previous = entries.putIfAbsent(modelPath(ref), bytes);
        if (previous != null && !java.util.Arrays.equals(previous, bytes)) {
            throw new IllegalStateException("unlit model content hash collision");
        }
        return ref;
    }

    private static String unlitTexture(JsonObject textures, String sprite) {
        int hops = 0;
        while (sprite.startsWith("#") && hops++ < 32) {
            var next = textures.get(sprite.substring(1));
            if (next == null) throw new IllegalArgumentException("unlit texture missing: " + sprite);
            sprite = next.getAsString();
        }
        if (sprite.startsWith("#")) throw new IllegalArgumentException("unlit texture cycle: " + sprite);
        return sprite;
    }

    private static String modelPath(String ref) {
        return "assets/" + ref.replace(":", "/models/") + ".json";
    }

    private static JsonObject modelNode(String ref) {
        JsonObject node = new JsonObject();
        node.addProperty("type", "minecraft:model");
        node.addProperty("model", ref);
        return node;
    }

    /**
     * A sprite the pack can bake against the blocks atlas: mod block/ sprites
     * ship at their own path (with any animation metadata), everything else is
     * re-hosted under block/ (see THE ATLAS OWNERSHIP RULE).
     */
    static String hostSprite(List<Path> jars, String sprite, TreeMap<String, byte[]> entries,
                             List<String> warnings) throws IOException {
        if (sprite.startsWith("~")) {
            // A real-client decoded sprite, including generated atlas sources.
            // Missing artifacts must fail the build rather than fabricate a texture.
            if (!sprite.matches("~[0-9a-f]{16}") || quadDir == null) {
                throw new IOException("gallery: invalid captured sprite reference: " + sprite);
            }
            String hash = sprite.substring(1);
            Path png = quadDir.resolve("textures").resolve(hash + ".png");
            byte[] pixels = java.nio.file.Files.readAllBytes(png);
            String key = "items/captured_" + hash;
            String hosted = "assets/" + WRAPPER_NS + "/textures/block/" + key + ".png";
            entries.put(hosted, pixels);
            Path metaFile = png.resolveSibling(hash + ".png.mcmeta");
            if (java.nio.file.Files.isRegularFile(metaFile)) {
                byte[] meta = sanitizeAnimationMetadata(sprite, pixels,
                        java.nio.file.Files.readAllBytes(metaFile), warnings);
                if (meta != null) {
                    entries.put(hosted + ".mcmeta", meta);
                }
            }
            return WRAPPER_NS + ":block/" + key;
        }
        if (sprite.startsWith("minecraft:block/")) {
            return sprite;
        }
        String png = spritePathPng(sprite);
        byte[] bytes = readPngFromJars(jars, png);
        boolean blockPath = !sprite.startsWith("@") && spritePathOf(sprite).startsWith("block/");
        if (bytes == null) {
            warnings.add("gallery: quad sprite missing: " + sprite);
            // A sprite with no PNG can still be a LIVE item-atlas sprite: the
            // trims/items/* family is generated by the items atlas's
            // paletted_permutations source. Passed through, it mixes atlases
            // and validateAtlasUsage aborts the bake of the WHOLE host
            // definition — measured: one such quad on mekanism:scuba_mask
            // turned all 74,711 paper entries into the missing-model cube.
            return blockPath ? sprite : placeholderSprite(entries);
        }
        if (blockPath) {
            entries.putIfAbsent(png, bytes);
            byte[] meta = readPngFromJars(jars, png + ".mcmeta");
            if (meta != null) {
                meta = sanitizeAnimationMetadata(sprite, bytes, meta, warnings);
                if (meta != null) {
                    entries.putIfAbsent(png + ".mcmeta", meta);
                }
            }
            return sprite;
        }
        String key = "items/" + (sprite.startsWith("@") ? "raw_" + sprite.substring(1) : sprite)
                .replace(':', '_').replace('/', '_');
        putHosted(jars, key, png, bytes, entries, warnings);
        return WRAPPER_NS + ":block/" + key;
    }

    /**
     * Ship a re-hosted PNG with its animation metadata: without the .mcmeta an
     * animated strip stops being frames and squashes into one smeared sprite
     * (measured: 18 re-hosted strips, none animated — ae2 crystals, mekanism
     * alloys and liquids).
     */
    private static void putHosted(List<Path> jars, String key, String sourcePng, byte[] bytes,
                                  TreeMap<String, byte[]> entries, List<String> warnings) throws IOException {
        String hosted = "assets/" + WRAPPER_NS + "/textures/block/" + key + ".png";
        entries.put(hosted, bytes);
        byte[] meta = readPngFromJars(jars, sourcePng + ".mcmeta");
        if (meta != null) {
            meta = sanitizeAnimationMetadata(sourcePng, bytes, meta, warnings);
            if (meta != null) {
                entries.put(hosted + ".mcmeta", meta);
            }
        }
    }

    /**
     * The 26.3 client treats an animation that resolves to at most ONE valid
     * frame as STATIC while keeping the FRAME-sized contents: the atlas then
     * allocates one frame slot and uploadFirstFrame writes the WHOLE strip
     * into it — IllegalArgumentException, the reload dies and the client
     * DROPS THE SERVER PACK (measured run 42099936223: three re-hosted
     * mekanism mcmeta with "frames": [] turned every gallery cell into the
     * paper fallback). Sanitize: an explicit frames list that carries fewer
     * than two valid entries on a multi-frame strip loses the list and
     * animates sequentially instead; an unparseable PNG or mcmeta ships
     * without metadata (a static sprite always fits its own atlas slot).
     */
    static byte[] sanitizeAnimationMetadata(String sprite, byte[] png, byte[] meta,
                                            List<String> warnings) {
        int[] dims = pngDims(png);
        if (dims == null) {
            warnings.add("gallery: animation metadata dropped (unreadable PNG): " + sprite);
            return null;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException bad) {
            warnings.add("gallery: animation metadata dropped (unparseable mcmeta): " + sprite);
            return null;
        }
        if (!root.has("animation") || !root.get("animation").isJsonObject()) {
            return meta;
        }
        JsonObject anim = root.getAsJsonObject("animation");
        int frameW = anim.has("width") ? anim.get("width").getAsInt() : dims[0];
        int frameH = anim.has("height") ? anim.get("height").getAsInt() : dims[0];
        if (frameW <= 0 || frameH <= 0 || dims[0] % frameW != 0 || dims[1] % frameH != 0) {
            warnings.add("gallery: animation metadata dropped (strip " + dims[0] + "x" + dims[1]
                    + " does not tile " + frameW + "x" + frameH + "): " + sprite);
            return null;
        }
        int total = (dims[0] / frameW) * (dims[1] / frameH);
        if (total <= 1 || !anim.has("frames") || !anim.get("frames").isJsonArray()) {
            return meta;
        }
        int defaultTime = anim.has("frametime") ? anim.get("frametime").getAsInt() : 1;
        int valid = 0;
        for (JsonElement e : anim.getAsJsonArray("frames")) {
            int index;
            int time;
            if (e.isJsonObject()) {
                JsonObject f = e.getAsJsonObject();
                index = f.has("index") ? f.get("index").getAsInt() : -1;
                time = f.has("time") ? f.get("time").getAsInt() : defaultTime;
            } else {
                index = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : -1;
                time = defaultTime;
            }
            if (index >= 0 && index < total && time > 0) {
                valid++;
            }
        }
        if (valid >= 2) {
            return meta;
        }
        warnings.add("gallery: animation frames list (" + valid + " valid of " + total
                + ") would render the strip static — animating sequentially: " + sprite);
        anim.remove("frames");
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** IHDR width/height of a PNG byte array, or null when it is not a PNG. */
    private static int[] pngDims(byte[] png) {
        if (png == null || png.length < 24 || png[0] != (byte) 0x89 || png[1] != 0x50
                || png[2] != 0x4E || png[3] != 0x47) {
            return null;
        }
        return new int[]{
                (png[16] & 0xFF) << 24 | (png[17] & 0xFF) << 16 | (png[18] & 0xFF) << 8 | (png[19] & 0xFF),
                (png[20] & 0xFF) << 24 | (png[21] & 0xFF) << 16 | (png[22] & 0xFF) << 8 | (png[23] & 0xFF)
        };
    }

    /** Stands in for a sprite the pack cannot host; visible on purpose. */
    static final String PLACEHOLDER_SPRITE = WRAPPER_NS + ":block/items/unhosted";

    private static String placeholderSprite(TreeMap<String, byte[]> entries) throws IOException {
        String png = spritePathPng(PLACEHOLDER_SPRITE);
        if (!entries.containsKey(png)) {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    image.setRGB(x, y, ((x / 8 + y / 8) & 1) == 0 ? 0xF800F8 : 0x000000);
                }
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            entries.put(png, out.toByteArray());
        }
        return PLACEHOLDER_SPRITE;
    }

    // ---- 14-ak: the Create connected-texture channel -------------------------

    /** The per-face CT tile indices, in the vanilla Direction.values()
     *  order (down,up,north,south,west,east) — the ctx string the seed's
     *  gallery_ct.py emits (computed per WORLD face of the REAL block;
     *  the composite's post-bake face keys are the same world directions). */
    private static final String[] CT_FACE_ORDER = {
            "down", "up", "north", "south", "west", "east"};

    /** Parse {@code i0,i1,...,i5} — side faces carry 0..15 (RECTANGLE 4x4),
     *  cap faces 0..63 (OMNIDIRECTIONAL 8x8); a loose 0..63 bound here. */
    private static int[] parseCtCtx(String ctx) {
        String[] parts = ctx.split(",");
        if (parts.length != 6) {
            throw new IllegalArgumentException("bad CT ctx: " + ctx);
        }
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
            if (out[i] < 0 || out[i] > 63) {
                throw new IllegalArgumentException("CT index out of range: " + ctx);
            }
        }
        return out;
    }

    /**
     * Remap the composite's faces into the Create CT sheets (14-ak).
     * MEASURED chain: the real client's CTModel.getQuads remaps every quad
     * whose sprite == the shift entry's original; catnip un-interpolates
     * the vertex uv to a FRACTION (getUnInterpolatedU = (u-u0)/(u1-u0)) and
     * 1.21.1 TextureAtlasSprite.getU (real bytecode: u0 + (u1-u0)*u) takes
     * that fraction — the tile offset lands at off*16 px of the sheet. In
     * model uv units ([0,16] over the whole texture) the remap is
     * {@code u' = (u + tileX*16)/sheetSize}, v likewise — an affine that
     * commutes with the face rotation's corner permutation (verified:
     * the isolated side tile (0,3) of the pillar sheet is BYTE-IDENTICAL
     * to the plain texture; the index semantics verified 515/515 against
     * the real RotatedPillarCTBehaviour by CTProbe).
     *
     * <p>The connected sheets ship from the create jar on first use (the
     * block/ path auto-atlases; the sheets are referenced by NO model json
     * — only the runtime shifter — so no existing pack walk copies them).
     */
    private static void applyCtRemap(List<Path> jars, JsonArray elements,
                                     Map<String, String> textures, int[] ctIndices,
                                     TreeMap<String, byte[]> entries,
                                     List<String> warnings, String state) throws IOException {
        int ctKey = 0;
        for (JsonElement elE : elements) {
            JsonObject el = elE.getAsJsonObject();
            if (!el.has("faces")) {
                continue;
            }
            JsonObject faces = el.getAsJsonObject("faces").deepCopy();
            boolean changed = false;
            for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
                int faceIdx = ctFaceIndex(f.getKey());
                if (faceIdx < 0 || !f.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject face = f.getValue().getAsJsonObject();
                if (!face.has("texture") || !face.get("texture").getAsString().startsWith("#")) {
                    continue;
                }
                String texKey = face.get("texture").getAsString().substring(1);
                String sprite = textures.get(texKey);
                if (sprite == null) {
                    continue;
                }
                int sheet = ctSheetSize(sprite);
                if (sheet < 0) {
                    continue; // not a CT-textured face
                }
                int tileIdx = ctIndices[faceIdx];
                int tileX = tileIdx % sheet;
                int tileY = tileIdx / sheet;
                String connected = sprite + "_connected";
                String png = spritePathPng(connected);
                if (!entries.containsKey(png) && readPngFromJars(jars, png) == null) {
                    warnings.add("gallery ct: connected sheet missing: " + connected
                            + " (" + state + ")");
                    continue;
                }
                if (!entries.containsKey(png)) {
                    entries.put(png, readPngFromJars(jars, png));
                }
                // 14-al: the remap MUST land an explicit uv on the face. The
                // 26.3 item channel derives a null-uv face's rect from the
                // geometry ACROSS THE WHOLE FACE SPRITE — repointing the
                // texture at the 64x64/128x128 _connected sheet without
                // writing the uv would spread the ENTIRE sheet grid over the
                // face (the unrotated axis=y pillar composites carry
                // null-uv faces: the vanilla cube parent of the 26.3 client
                // ships no explicit uvs, and applyFaceUvs early-returns at
                // 0/0 — measured on the #91 pack bytes, g11061). The default
                // rect rides defaultFaceUv on the POST-BAKE element box +
                // the POST-BAKE face key (exact for the unrotated case,
                // where original == baked; the rotated variants already
                // carry explicit uvs from applyFaceUvs).
                double[] inRect = null;
                if (face.has("uv") && face.getAsJsonArray("uv").size() == 4) {
                    JsonArray uv = face.getAsJsonArray("uv");
                    inRect = new double[4];
                    for (int i = 0; i < 4; i++) {
                        inRect[i] = uv.get(i).getAsDouble();
                    }
                } else if (el.has("from") && el.has("to")
                        && el.getAsJsonArray("from").size() == 3
                        && el.getAsJsonArray("to").size() == 3) {
                    JsonArray from = el.getAsJsonArray("from");
                    JsonArray to = el.getAsJsonArray("to");
                    inRect = defaultFaceUv(f.getKey(),
                            from.get(0).getAsDouble(), from.get(1).getAsDouble(),
                            from.get(2).getAsDouble(),
                            to.get(0).getAsDouble(), to.get(1).getAsDouble(),
                            to.get(2).getAsDouble());
                }
                if (inRect == null) {
                    // no explicit uv and no derivable default — repointing
                    // would render the whole sheet; the face stays verbatim
                    warnings.add("gallery ct: no uv derivable for face " + f.getKey()
                            + " (" + state + ") — plain texture stays");
                    continue;
                }
                String newKey = texKey + "Ct" + (ctKey++);
                textures.put(newKey, connected);
                face.addProperty("texture", "#" + newKey);
                JsonArray out = new JsonArray();
                for (int i = 0; i < 4; i++) {
                    double u = inRect[i];
                    double off = (i % 2 == 0 ? tileX : tileY) * 16.0;
                    out.add((u + off) / sheet);
                }
                face.add("uv", out);
                // the rotation field survives: the affine is uniform over
                // the tile box and commutes with the corner permutation
                changed = true;
            }
            if (changed) {
                el.add("faces", faces);
            }
        }
    }

    /** The sheet size for a CT-textured sprite, or -1: the pillar side
     *  sprite (RECTANGLE 4x4) or the cap sprite (OMNIDIRECTIONAL 8x8) of
     *  the create palette family. The match is by PATH SHAPE, not by a
     *  stone list — a stone without a connected sheet fails the png read
     *  above and stays verbatim (fail-safe). */
    private static int ctSheetSize(String sprite) {
        if (sprite.startsWith("create:block/palettes/stone_types/pillar/")
                && sprite.endsWith("_cut_pillar")) {
            return 4;
        }
        if (sprite.startsWith("create:block/palettes/stone_types/cap/")
                && sprite.endsWith("_cut_cap")) {
            return 8;
        }
        return -1;
    }

    private static int ctFaceIndex(String face) {
        for (int i = 0; i < CT_FACE_ORDER.length; i++) {
            if (CT_FACE_ORDER[i].equals(face)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Expand DEGENERATE (zero-thickness) element boxes to a sub-pixel
     * thickness. Create's bars/cap models draw their posts as FLAT PLANES
     * (from.x == to.x = 8); the chunk renderer draws them fine, but the 26.3
     * item-model surface bakes the degenerate box into a fat block (measured
     * run 41126658261: the all-false andesite_bars post rendered as a full
     * cube). A 0.04-unit inflation (0.25% of a block, ~0.15 px on the wall)
     * gives the baker a real box while staying visually identical.
     */
    static JsonObject inflateDegenerate(JsonObject element) {
        if (!element.has("from") || !element.has("to")) {
            return element;
        }
        JsonArray from = element.getAsJsonArray("from").deepCopy();
        JsonArray to = element.getAsJsonArray("to").deepCopy();
        if (from.size() != 3 || to.size() != 3) {
            return element;
        }
        boolean changed = false;
        for (int i = 0; i < 3; i++) {
            double a = from.get(i).getAsDouble();
            double b = to.get(i).getAsDouble();
            if (a == b) {
                // clamped: a plane ON the element limit must stay loadable —
                // the client rejects the whole model past -16/32 (measured:
                // the 3x3x3 mekanism:digital_miner at -16.02/32.02)
                from.set(i, new com.google.gson.JsonPrimitive(Math.max(a - 0.02, ELEMENT_MIN)));
                to.set(i, new com.google.gson.JsonPrimitive(Math.min(b + 0.02, ELEMENT_MAX)));
                changed = true;
            }
        }
        if (!changed) {
            return element;
        }
        JsonObject out = element.deepCopy();
        out.add("from", from);
        out.add("to", to);
        return out;
    }

    /** Rewrite element face texture refs {@code #key} -> {@code #prefix_key}. */
    private static JsonObject renameTextureRefs(JsonObject element, String prefix) {
        JsonObject out = element.deepCopy();
        if (!out.has("faces")) {
            return out;
        }
        JsonObject faces = out.getAsJsonObject("faces").deepCopy();
        JsonObject renamed = new JsonObject();
        for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
            JsonObject face = f.getValue().getAsJsonObject().deepCopy();
            if (face.has("texture")) {
                String t = face.get("texture").getAsString();
                if (t.startsWith("#")) {
                    face.addProperty("texture", "#" + prefix + t.substring(1));
                }
            }
            renamed.add(f.getKey(), face);
        }
        out.add("faces", renamed);
        return out;
    }

    // ---- model graph resolution (composite parts) --------------------------------

    /** A resolved model: concrete texture map + the nearest elements array. */
    /** {@code displayScale}: the shrink an oversized OBJ mesh took to fit the
     *  element limits, undone by the item definition's transformation. */
    private record ModelGraph(Map<String, String> textures, JsonArray elements,
                              double displayScale) {
    }

    private static boolean isObjModel(JsonObject json) {
        return json.has("loader") && json.has("model")
                && "neoforge:obj".equals(json.get("loader").getAsString());
    }

    /** The {@code neoforge:obj} geometry as elements, or null when its files are missing. */
    private static ObjGeometry readObjGeometry(List<Path> jars, JsonObject json,
                                               List<String> warnings) throws IOException {
        String ref = json.get("model").getAsString();
        int c = ref.indexOf(':');
        String file = "assets/" + ref.substring(0, c) + "/" + ref.substring(c + 1);
        byte[] obj = readPngFromJars(jars, file);
        if (obj == null) {
            warnings.add("gallery: obj missing: " + file);
            return null;
        }
        String text = new String(obj, StandardCharsets.UTF_8);
        Map<String, String> materials = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("mtllib ")) {
                String mtl = file.substring(0, file.lastIndexOf('/') + 1) + line.substring(7).trim();
                byte[] bytes = readPngFromJars(jars, mtl);
                if (bytes != null) {
                    materials.putAll(ObjMesh.parseMtl(new String(bytes, StandardCharsets.UTF_8)));
                }
            }
        }
        boolean flipV = json.has("flip_v") && json.get("flip_v").getAsBoolean();
        List<ObjMesh.Face> faces = ObjMesh.parse(text, materials, flipV);
        double k = ObjMesh.fitElements(faces);
        return new ObjGeometry(ObjMesh.toElements(faces, k), k);
    }

    private record ObjGeometry(JsonArray elements, double scale) {
    }

    /** Turn the mesh's polygon markers into rectangles on a generated cutout sprite. */
    private static void materializeCutouts(List<Path> jars, ModelGraph mg,
                                           TreeMap<String, byte[]> entries,
                                           List<String> warnings) throws IOException {
        ObjMesh.materialize(mg.elements(), mg.textures(),
                sprite -> readPngFromJars(jars, spritePathPng(sprite)),
                WRAPPER_NS, entries, warnings);
    }

    /**
     * Model refs whose OBJ mesh shrank to fit, and by how much. The wrapper's
     * display transforms cannot undo it: the item_display NONE context reads
     * ItemTransform.NO_TRANSFORM (ItemTransforms.getTransform has no NONE case),
     * so {@link F2vPack#itemDef} restores it in the item definition instead.
     */
    private static final Map<String, Double> MODEL_SCALE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, JsonObject> ITEM_NODES = new java.util.concurrent.ConcurrentHashMap<>();

    static void resetModelScales() {
        MODEL_SCALE.clear();
        ITEM_NODES.clear();
    }

    static JsonObject itemNode(String ref) {
        JsonObject node = ITEM_NODES.get(ref);
        return node == null ? null : node.deepCopy();
    }

    /**
     * Native block wrappers replaced by item composites are build intermediates.
     * Their geometry remains in the composite's child models. Vanilla eagerly
     * parses every model file, including these otherwise unreferenced copies.
     */
    static int removeReplacedBlockWrappers(TreeMap<String, byte[]> entries) {
        int removed = 0;
        for (String ref : ITEM_NODES.keySet()) {
            if (entries.remove(modelPath(ref)) != null) removed++;
        }
        return removed;
    }

    /** The item definition's scale for a model ref (1 for all but oversized meshes). */
    static double modelScale(String ref) {
        return MODEL_SCALE.getOrDefault(ref, 1.0);
    }

    private static void recordScale(String ref, double k) {
        if (k != 1) {
            MODEL_SCALE.put(ref, k);
        }
    }

    /**
     * Walk the parent chain across ALL jars (mods first, the vanilla client
     * jar last): textures merge child-over-parent, elements come from the
     * nearest level that has them. Texture values are fully resolved
     * ({@code #ref} chains followed to concrete sprite ids).
     *
     * <p>NAMESPACE RULE (measured on the real 26.3 client jar): vanilla
     * models reference their parents BARE — {@code cube_all.json} says
     * {@code "parent": "block/cube"} — and a bare parent inherits the
     * NAMESPACE OF THE MODEL THAT REFERENCES IT, not the original ref's.
     * The walk therefore tracks the current hop's namespace: a namespaced
     * parent resets it, a bare parent keeps it (create:andesite_casing ->
     * minecraft:block/cube_all -> minecraft:block/cube -> minecraft:block/
     * block — the vanilla chain resolves only with per-hop tracking).
     */
    static ModelGraph resolveModelGraph(List<Path> jars, String ref,
                                        List<String> warnings) throws IOException {
        int colon = ref.indexOf(':');
        String ns = colon > 0 ? ref.substring(0, colon) : "minecraft";
        Map<String, String> textures = new LinkedHashMap<>();
        JsonArray elements = null;
        double displayScale = 1;
        String cur = ref;
        int depth = 0;
        while (cur != null && depth <= 16) {
            depth++;
            // resolve THIS hop: a namespaced ref wins, a bare ref rides the
            // namespace of the model we just came from
            String hopNs;
            String hopPath;
            int hc = cur.indexOf(':');
            if (hc > 0) {
                hopNs = cur.substring(0, hc);
                hopPath = cur.substring(hc + 1);
            } else {
                hopNs = ns;
                hopPath = cur;
            }
            ns = hopNs;
            String file = "assets/" + hopNs + "/models/" + hopPath + ".json";
            JsonObject json = readJsonFromJars(jars, file);
            if (json == null && !hopNs.equals("minecraft")) {
                // 14-ay MINECRAFT FALLBACK (see copyModelGraph): a bare hop
                // tries the carried namespace first, then minecraft: — BOP's
                // bare vanilla parents (block/cube_all, block/template_fence_gate,
                // block/cross...) only exist under assets/minecraft/models/.
                String vfile = "assets/minecraft/models/" + hopPath + ".json";
                json = readJsonFromJars(jars, vfile);
                if (json != null) {
                    ns = "minecraft"; // the found level re-anchors bare hops below it
                }
            }
            if (json == null) {
                warnings.add("gallery: model missing in jar chain: " + file);
                break;
            }
            if (json.has("textures")) {
                for (Map.Entry<String, JsonElement> t : json.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(t.getKey(), t.getValue().getAsString());
                }
            }
            if (elements == null && (json.has("elements") || json.has("children"))) {
                elements = mergeModelGeometry(jars, json, "", textures, warnings, 0);
            }
            if (elements == null && isObjModel(json)) {
                ObjGeometry g = readObjGeometry(jars, json, warnings);
                if (g != null) {
                    elements = g.elements();
                    displayScale = g.scale();
                }
            }
            cur = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        if (elements == null) {
            return new ModelGraph(textures, null, 1);
        }
        // resolve #ref chains to concrete sprite ids
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> t : textures.entrySet()) {
            String v = t.getValue();
            int hops = 0;
            while (v.startsWith("#") && hops++ < 8) {
                String next = textures.get(v.substring(1));
                if (next == null) {
                    break;
                }
                v = next;
            }
            resolved.put(t.getKey(), v);
        }
        return new ModelGraph(resolved, elements, displayScale);
    }

    /**
     * Collect one model level's geometry: the own {@code elements} array (if
     * any) PLUS every {@code children} sub-model flattened — the
     * {@code neoforge:composite} loader shape (create 6.0 ships
     * nixie_tube / display_link / stock_link this way). The naive
     * elements-only walk resolved those chains as element-less and DROPPED
     * the rows (measured 14-o: 37 nixie gallery states never placed, the
     * whole white nixie wall empty on the truth side).
     *
     * <p>Child texture keys are namespaced with the child's key
     * (child {@code "connectors"} referencing {@code #0} ->
     * {@code #connectors_0}) so sibling children cannot collide, and each
     * child's own texture map rides along under the same prefix — the
     * {@code #ref} resolution below then sees concrete sprite ids exactly
     * like a plain model's. Child {@code render_type} hints (translucent
     * tubes) are dropped: the emitted composite renders on the solid layer,
     * an alpha-carrying sprite shows opaque.
     *
     * <p>14-bp CHILD PARENT CHAINS: a composite child that is a bare
     * {@code {"parent": ...}} ref carries NO inline geometry — the real
     * machine model rides the chain (mekanism's factory tiers: the
     * composite child {@code "base"} points at
     * {@code mekanism:block/factory/sawing/base}, whose elements ARE the
     * machine; measured run 42243329692: every factory tier's composite
     * came out element-less and the whole tier family dropped out of the
     * static channel). The chain merges each level's textures under the
     * child's prefix and takes the FIRST level carrying geometry; the
     * vanilla parent semantics hold (the child's own textures override the
     * chain's via putIfAbsent order).
     *
     * @return the merged element array, or null when the level carries no
     *         geometry at all (the parent walk continues)
     */
    private static JsonArray mergeModelGeometry(List<Path> jars, JsonObject json, String prefix,
                                                Map<String, String> textures, List<String> warnings,
                                                int depth) {
        JsonArray out = new JsonArray();
        if (json.has("elements")) {
            for (JsonElement el : json.getAsJsonArray("elements")) {
                out.add(prefix.isEmpty() ? el.deepCopy()
                        : renameTextureRefs(el.getAsJsonObject(), prefix));
            }
        }
        if (json.has("children")) {
            for (Map.Entry<String, JsonElement> c : json.getAsJsonObject("children").entrySet()) {
                if (!c.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject child = c.getValue().getAsJsonObject();
                String childPrefix = prefix + c.getKey() + "_";
                if (child.has("elements") && child.getAsJsonArray("elements")
                        .isJsonArray() && child.getAsJsonArray("elements").size() > 0) {
                    if (child.has("textures") && child.getAsJsonObject("textures").isJsonObject()) {
                        for (Map.Entry<String, JsonElement> t : child.getAsJsonObject("textures")
                                .entrySet()) {
                            textures.putIfAbsent(childPrefix + t.getKey(), t.getValue().getAsString());
                        }
                    }
                    for (JsonElement el : child.getAsJsonArray("elements")) {
                        out.add(renameTextureRefs(el.getAsJsonObject(), childPrefix));
                    }
                } else if (child.has("parent") && child.get("parent").isJsonPrimitive() && jars != null) {
                    JsonArray chained = mergeChildChain(jars, child, childPrefix, textures,
                            warnings, depth);
                    if (chained != null) {
                        out.addAll(chained);
                    }
                }
            }
        }
        return out.size() == 0 ? null : out;
    }

    /**
     * One composite child's PARENT chain (14-bp): merge the child's own
     * textures under its prefix, then walk {@code parent} refs (each
     * level's textures join under the same prefix, the first level that
     * carries geometry — inline elements or nested children — wins; the
     * walk continues through geometry-less levels exactly like
     * {@code resolveModelGraph}'s parent walk). Bounded: 8 chain levels,
     * depth-guarded against cycles.
     */
    private static JsonArray mergeChildChain(List<Path> jars, JsonObject child, String prefix,
                                             Map<String, String> textures, List<String> warnings,
                                             int depth) {
        if (depth > 8) {
            warnings.add("gallery: composite child chain deeper than 8 at " + prefix
                    + " — stopped");
            return null;
        }
        if (child.has("textures") && child.getAsJsonObject("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> t : child.getAsJsonObject("textures").entrySet()) {
                textures.putIfAbsent(prefix + t.getKey(), t.getValue().getAsString());
            }
        }
        String cur = child.get("parent").getAsString();
        int hops = 0;
        while (cur != null && !cur.isEmpty() && hops++ <= 8) {
            JsonObject pj = readParentModelJson(jars, cur, warnings);
            if (pj == null) {
                warnings.add("gallery: composite child parent missing in jars: " + cur
                        + " (child " + prefix + ")");
                break;
            }
            if (pj.has("textures") && pj.getAsJsonObject("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> t : pj.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(prefix + t.getKey(), t.getValue().getAsString());
                }
            }
            JsonArray geo = mergeModelGeometry(jars, pj, prefix, textures, warnings, depth + 1);
            if (geo != null) {
                return geo;
            }
            cur = pj.has("parent") && pj.get("parent").isJsonPrimitive()
                    ? pj.get("parent").getAsString() : null;
        }
        return null;
    }

    /**
     * One parent ref -> the model json. A namespaced ref resolves in its
     * namespace; a bare ref is a vanilla parent (the 14-ay rule) — the
     * minecraft assets carry it.
     */
    private static JsonObject readParentModelJson(List<Path> jars, String ref, List<String> warnings) {
        String file;
        if (ref.contains(":")) {
            int colon = ref.indexOf(':');
            file = "assets/" + ref.substring(0, colon) + "/models/" + ref.substring(colon + 1) + ".json";
        } else {
            file = "assets/minecraft/models/" + ref + ".json";
        }
        try {
            JsonObject json = readJsonFromJars(jars, file);
            if (json == null && !file.startsWith("assets/minecraft/")) {
                int slash = file.indexOf("/models/");
                String bare = "assets/minecraft/models/" + file.substring(slash + 8);
                json = readJsonFromJars(jars, bare);
            }
            return json;
        } catch (IOException bad) {
            warnings.add("gallery: composite child parent read failed: " + ref + " — " + bad);
            return null;
        }
    }

    // ---- rotation bake ------------------------------------------------------------

    /**
     * Bake variant x/y rotations into elements (vanilla applies x first,
     * then y). 90-degree steps only; per-element "rotation" origins rotate
     * with the element, axes remap, angles pass through.
     */
    static JsonArray bakeRotation(JsonArray elements, int xRot, int yRot, boolean uvlock) {
        if (xRot == 0 && yRot == 0 && !uvlock) {
            return elements;
        }
        JsonArray out = new JsonArray();
        for (JsonElement el : elements) {
            JsonObject e = el.getAsJsonObject().deepCopy();
            if (isEulerElement(e)) {
                out.add(bakeEulerElement(e, xRot, yRot));
                continue;
            }
            applyFaceUvs(e, xRot, yRot, uvlock);
            out.add(bakeElement(e, xRot, yRot));
        }
        return out;
    }

    /**
     * 14-ae/14-ag/14-ai history of the non-uvlock uv path: 14-ae wrote the
     * DEFAULT uv explicitly into every null-uv face (the 26.3 item channel
     * derives it from the ROTATED geometry while the 1.21.1 truth derives it
     * from the ORIGINAL geometry + direction). 14-ag added the V-mirror on
     * the x-remapped faces (the canonicalisation loses the rotated winding) —
     * validated pixel-wise on #84. 14-ai REPLACES the v-swap with the EXACT
     * winding transport (UvLock.bakePlainFace): the truth's position-rematch
     * (recalculateWinding carrying the uv with the vertex) puts the raw walk
     * slot perm[j] at the mapped canonical corner j for EVERY face with a
     * non-identity transport permutation — the v-swap was exact only for the
     * reflection-class perms and a U-mirror approximation for the cycle-class
     * ones (X90_Y0 up, X180_Y0 north, X0_Y90 down, ...) — see
     * scripts/f2v/uvlock/UvLockVerify.java layer F/G.
     */
    /**
     * 14-ah/14-ai: the uv processing for one baked element. BOTH branches run
     * the measured transport machinery (UvLock): UVLOCKED variants recompute
     * EVERY face uv through the truth's recomputeUVs chain first
     * (bakeUvlockFace); NON-uvlocked variants ride the raw rect
     * (default or explicit, with its rotation field) through the same
     * k-fitted winding relabel (bakePlainFace). Identity-permutation faces
     * stay verbatim. Verified per-corner-exact by UvLockVerify (layers
     * B-E for uvlock, G for the plain path — ALL OK vs the real classes).
     */
    private static void applyFaceUvs(JsonObject el, int xRot, int yRot, boolean uvlock) {
        if (xRot == 0 && yRot == 0 && !uvlock) {
            return;
        }
        if (!el.has("from") || !el.has("to") || !el.has("faces")) {
            return;
        }
        JsonArray from = el.getAsJsonArray("from");
        JsonArray to = el.getAsJsonArray("to");
        if (from.size() != 3 || to.size() != 3) {
            return;
        }
        double x1 = from.get(0).getAsDouble();
        double y1 = from.get(1).getAsDouble();
        double z1 = from.get(2).getAsDouble();
        double x2 = to.get(0).getAsDouble();
        double y2 = to.get(1).getAsDouble();
        double z2 = to.get(2).getAsDouble();
        JsonObject faces = el.getAsJsonObject("faces").deepCopy();
        boolean changed = false;
        for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
            JsonObject face = f.getValue().getAsJsonObject();
            if (!face.has("texture")) {
                continue;
            }
            float[] inRect = new float[4];
            int inRot = 0;
            if (face.has("uv")) {
                JsonArray uv = face.getAsJsonArray("uv");
                if (uv.size() != 4) {
                    continue;
                }
                for (int i = 0; i < 4; i++) {
                    inRect[i] = uv.get(i).getAsFloat();
                }
            } else {
                double[] def = defaultFaceUv(f.getKey(), x1, y1, z1, x2, y2, z2);
                if (def == null) {
                    continue;
                }
                for (int i = 0; i < 4; i++) {
                    inRect[i] = (float) def[i];
                }
            }
            if (face.has("rotation")) {
                inRot = ((face.get("rotation").getAsInt() % 360) + 360) % 360;
            }
            float[] out = new float[5];
            if (uvlock) {
                UvLock.bakeUvlockFace(inRect, inRot, f.getKey(), xRot, yRot, out);
            } else {
                UvLock.bakePlainFace(inRect, inRot, f.getKey(), xRot, yRot, out);
            }
            JsonArray arr = new JsonArray();
            for (double d : out) {
                arr.add(d);
            }
            arr.remove(arr.size() - 1);
            face.add("uv", arr);
            int rotOut = (int) out[4];
            if (rotOut != 0) {
                face.addProperty("rotation", rotOut);
            } else if (face.has("rotation")) {
                face.remove("rotation");
            }
            changed = true;
        }
        if (changed) {
            el.add("faces", faces);
        }
    }

    /**
     * Decompiled 26.3 FaceBakery.defaultFaceUV (uv units are texels 0..16,
     * x1..z2 = the UNROTATED from/to):
     * DOWN [x1,16-z2,x2,16-z1]; UP [x1,z1,x2,z2];
     * NORTH [16-x2,16-y2,16-x1,16-y1]; SOUTH [x1,16-y2,x2,16-y1];
     * WEST [z1,16-y2,z2,16-y1]; EAST [16-z2,16-y2,16-z1,16-y1].
     */
    private static double[] defaultFaceUv(String face, double x1, double y1, double z1,
                                          double x2, double y2, double z2) {
        return switch (face) {
            case "down" -> new double[]{x1, 16 - z2, x2, 16 - z1};
            case "up" -> new double[]{x1, z1, x2, z2};
            case "north" -> new double[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case "south" -> new double[]{x1, 16 - y2, x2, 16 - y1};
            case "west" -> new double[]{z1, 16 - y2, z2, 16 - y1};
            case "east" -> new double[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
            default -> null;
        };
    }

    private static boolean isEulerElement(JsonObject e) {
        return e.has("rotation") && e.getAsJsonObject("rotation").has("x");
    }

    /**
     * A mesh element keeps its local box, UVs and face key: the variant
     * rotation composes into its Euler rotation and moves its origin.
     */
    private static JsonObject bakeEulerElement(JsonObject e, int xRot, int yRot) {
        JsonObject rot = e.getAsJsonObject("rotation");
        JsonArray origin = rot.getAsJsonArray("origin");
        double[] o = {origin.get(0).getAsDouble(), origin.get(1).getAsDouble(),
                origin.get(2).getAsDouble()};
        JsonArray movedOrigin = bakeVec(origin, xRot, yRot);
        double[] moved = {movedOrigin.get(0).getAsDouble(), movedOrigin.get(1).getAsDouble(),
                movedOrigin.get(2).getAsDouble()};
        double[][] cols = ObjMesh.columnsOf(rot.get("x").getAsDouble(),
                rot.get("y").getAsDouble(), rot.get("z").getAsDouble());
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            JsonArray tip = new JsonArray();
            for (int k = 0; k < 3; k++) {
                tip.add(o[k] + cols[i][k]);
            }
            JsonArray t = bakeVec(tip, xRot, yRot);
            for (int k = 0; k < 3; k++) {
                out[i][k] = t.get(k).getAsDouble() - moved[k];
            }
        }
        double[] angles = ObjMesh.eulerOf(out);
        JsonObject newRot = new JsonObject();
        newRot.add("origin", movedOrigin);
        newRot.addProperty("x", angles[0]);
        newRot.addProperty("y", angles[1]);
        newRot.addProperty("z", angles[2]);
        e.add("rotation", newRot);
        for (String key : List.of("from", "to")) {
            JsonArray v = e.getAsJsonArray(key);
            JsonArray shifted = new JsonArray();
            for (int k = 0; k < 3; k++) {
                shifted.add(v.get(k).getAsDouble() + moved[k] - o[k]);
            }
            e.add(key, shifted);
        }
        return e;
    }

    private static JsonObject bakeElement(JsonObject el, int xRot, int yRot) {
        JsonObject e = el.deepCopy();
        JsonArray from = e.has("from") ? bakeVec(e.getAsJsonArray("from"), xRot, yRot) : null;
        JsonArray to = e.has("to") ? bakeVec(e.getAsJsonArray("to"), xRot, yRot) : null;
        if (from != null && to != null) {
            sortBoxPair(from, to);
            e.add("from", from);
            e.add("to", to);
        }
        if (e.has("rotation") && e.getAsJsonObject("rotation").has("origin")) {
            JsonObject rot = e.getAsJsonObject("rotation").deepCopy();
            rot.add("origin", bakeVec(rot.getAsJsonArray("origin"), xRot, yRot));
            if (rot.has("axis")) {
                rot.addProperty("axis", remapAxis(rot.get("axis").getAsString(), xRot, yRot));
            }
            e.add("rotation", rot);
        }
        if (e.has("faces")) {
            JsonObject faces = e.getAsJsonObject("faces").deepCopy();
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
                out.add(remapFace(f.getKey(), xRot, yRot), bakeFace(f.getValue().getAsJsonObject(), xRot, yRot));
            }
            e.add("faces", out);
        }
        return e;
    }

    private static JsonElement bakeFace(JsonObject face, int xRot, int yRot) {
        JsonObject f = face.deepCopy();
        if (f.has("cullface")) {
            f.addProperty("cullface", remapFace(f.get("cullface").getAsString(), xRot, yRot));
        }
        return f;
    }

    /** Rotate one XYZ point (block space 0..16, center 8) by x then y. */
    private static JsonArray bakeVec(JsonArray v, int xRot, int yRot) {
        double x = v.get(0).getAsDouble();
        double y = v.get(1).getAsDouble();
        double z = v.get(2).getAsDouble();
        double ny = y, nz = z;
        switch (xRot) {
            case 90 -> { ny = z; nz = 16 - y; }
            case 180 -> { ny = 16 - y; nz = 16 - z; }
            case 270 -> { ny = 16 - z; nz = y; }
            default -> { }
        }
        double nx2 = x, nz2 = nz;
        switch (yRot) {
            case 90 -> { nx2 = 16 - nz; nz2 = x; }
            case 180 -> { nx2 = 16 - x; nz2 = 16 - nz; }
            case 270 -> { nx2 = nz; nz2 = 16 - x; }
            default -> { }
        }
        JsonArray out = new JsonArray();
        out.add(nx2);
        out.add(ny);
        out.add(nz2);
        return out;
    }

    /**
     * Per-axis min/max of the baked from/to PAIR. A 90-degree rotation maps
     * a box's min corner into a max-corner slot (e.g. y=90 sends from.x=7
     * to x=9), so the raw baked from/to come out UNSORTED (from.x > to.x).
     * Vanilla renders through quaternions and never sees inverted boxes;
     * the stock geometry builder draws them INVISIBLE (measured on the
     * acacia_window_pane / chain-gearshift composites: every rotated part
     * missing from the ours-leg render). The box REGION is invariant under
     * the rotation, so sorting the pair per axis recovers the vanilla
     * geometry exactly; face directions were already remapped to the rotated
     * directions, and each face's plane sits on the sorted box's matching
     * side (zero-thickness faces keep their single plane).
     */
    private static void sortBoxPair(JsonArray from, JsonArray to) {
        for (int i = 0; i < 3; i++) {
            double a = from.get(i).getAsDouble();
            double b = to.get(i).getAsDouble();
            from.set(i, new com.google.gson.JsonPrimitive(Math.min(a, b)));
            to.set(i, new com.google.gson.JsonPrimitive(Math.max(a, b)));
        }
    }

    /** Face-direction remap under x-then-y rotation. */
    private static String remapFace(String face, int xRot, int yRot) {
        String f = face;
        switch (xRot) {
            case 90 -> f = switch (f) {
                case "up" -> "north"; case "north" -> "down"; case "down" -> "south";
                case "south" -> "up"; default -> f;
            };
            case 180 -> f = switch (f) {
                case "up" -> "down"; case "down" -> "up"; case "north" -> "south";
                case "south" -> "north"; default -> f;
            };
            case 270 -> f = switch (f) {
                case "up" -> "south"; case "south" -> "down"; case "down" -> "north";
                case "north" -> "up"; default -> f;
            };
            default -> { }
        }
        switch (yRot) {
            case 90 -> f = switch (f) {
                case "north" -> "east"; case "east" -> "south"; case "south" -> "west";
                case "west" -> "north"; default -> f;
            };
            case 180 -> f = switch (f) {
                case "north" -> "south"; case "south" -> "north"; case "east" -> "west";
                case "west" -> "east"; default -> f;
            };
            case 270 -> f = switch (f) {
                case "north" -> "west"; case "west" -> "south"; case "south" -> "east";
                case "east" -> "north"; default -> f;
            };
            default -> { }
        }
        return f;
    }

    /** Rotation-axis remap under x-then-y rotation (unsigned axes). */
    private static String remapAxis(String axis, int xRot, int yRot) {
        String a = axis;
        if (xRot == 90 || xRot == 270) {
            a = switch (a) { case "y" -> "z"; case "z" -> "y"; default -> a; };
        }
        if (yRot == 90 || yRot == 270) {
            a = switch (a) { case "x" -> "z"; case "z" -> "x"; default -> a; };
        }
        return a;
    }

    // ---- jar json -----------------------------------------------------------------

    /** Read one JSON resource from the first jar that carries it. */
    static JsonObject readJsonFromJars(List<Path> jars, String file) throws IOException {
        byte[] bytes = jarCache(jars).read(file);
        return bytes == null ? null
                : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
