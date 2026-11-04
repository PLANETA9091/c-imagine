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
    static final String WRAPPER_PREFIX = "assets/" + WRAPPER_NS + "/models/gallery/g";
    static final String WRAPPER_REF_PREFIX = WRAPPER_NS + ":gallery/g";

    // ---- manifest ------------------------------------------------------------

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
                StateRef s = parseState(state);
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
                String ref = synthesizeComposite(jars, state, parts,
                        compositeIdx, entries, warnings);
                if (ref == null) {
                    warnings.add("gallery: composite failed for " + state + " — row dropped");
                    continue;
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
    private record Part(String modelRef, int xRot, int yRot, boolean uvlock) {
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
                JsonObject entry = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject()
                        : v.getAsJsonObject();
                String model = entry.get("model").getAsString();
                int x = entry.has("x") ? entry.get("x").getAsInt() : 0;
                int y = entry.has("y") ? entry.get("y").getAsInt() : 0;
                boolean uvlock = entry.has("uvlock") && entry.get("uvlock").getAsBoolean();
                String ref = copyModelGraph(jars, s.ns(), model, entries, warnings);
                if (ref != null) {
                    parts.add(new Part(ref, normRot(x), normRot(y), uvlock));
                } else {
                    warnings.add("gallery: variant model missing for " + s.ns() + ":" + s.block()
                            + " -> " + model);
                }
            }
            return parts;
        }
        if (bs.has("multipart")) {
            JsonArray mp = bs.getAsJsonArray("multipart");
            for (JsonElement pe : mp) {
                JsonObject part = pe.getAsJsonObject();
                if (part.has("when") && !matchesWhen(part.getAsJsonObject("when"), s.props())) {
                    continue;
                }
                JsonElement apply = part.get("apply");
                JsonObject entry = apply.isJsonArray()
                        ? apply.getAsJsonArray().get(0).getAsJsonObject() : apply.getAsJsonObject();
                String model = entry.get("model").getAsString();
                int x = entry.has("x") ? entry.get("x").getAsInt() : 0;
                int y = entry.has("y") ? entry.get("y").getAsInt() : 0;
                boolean uvlock = entry.has("uvlock") && entry.get("uvlock").getAsBoolean();
                String ref = copyModelGraph(jars, s.ns(), model, entries, warnings);
                if (ref != null) {
                    parts.add(new Part(ref, normRot(x), normRot(y), uvlock));
                }
                // a missing multipart part model: recorded, the composite
                // synthesizes from what resolved (honest partial render)
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
     */
    static String copyModelGraph(List<Path> jars, String stateNs, String modelRef,
                                 TreeMap<String, byte[]> entries,
                                 List<String> warnings) throws IOException {
        String ref = modelRef.contains(":") ? modelRef : stateNs + ":" + modelRef;
        int colon = ref.indexOf(':');
        String ns = ref.substring(0, colon);
        String path = ref.substring(colon + 1);
        String[] candidates = {"assets/" + ns + "/models/" + path + ".json"};
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
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
                    return ns + ":" + path;
                }
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
            if (json == null) {
                return false;
            }
            // NEOFORGE:COMPOSITE (14-o): the loader shape carries the geometry
            // in "children" sub-models, not in a top-level "elements" array —
            // create 6.0 ships nixie_tube / display_link / stock_link this
            // way, and the naive elements-only predicate resolved those chains
            // as element-less (37 nixie gallery states never placed). A
            // children level counts as geometry; resolveModelGraph flattens it.
            if (json.has("elements") || json.has("children")) {
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
                entries.put("assets/" + WRAPPER_NS + "/textures/block/" + key + ".png", bytes);
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
        int c = sprite.indexOf(':');
        String ns = c > 0 ? sprite.substring(0, c) : "minecraft";
        String path = c > 0 ? sprite.substring(c + 1) : sprite;
        return "assets/" + ns + "/textures/" + path + ".png";
    }

    /** Read one PNG from the jars (the client jar carries vanilla textures). */
    private static byte[] readPngFromJars(List<Path> jars, String file) throws IOException {
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry e = zf.getEntry(file);
                if (e != null) {
                    return zf.getInputStream(e).readAllBytes();
                }
            }
        }
        return null;
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
                                              List<String> warnings) throws IOException {
        JsonArray mergedElements = new JsonArray();
        Map<String, String> textures = new TreeMap<>();
        int partNo = 0;
        for (Part p : parts) {
            ModelGraph mg = resolveModelGraph(jars, p.modelRef(), warnings);
            if (mg == null || mg.elements == null || mg.elements.size() == 0) {
                warnings.add("gallery: composite part without elements: "
                        + p.modelRef() + " (" + state + ")");
                partNo++;
                continue;
            }
            String prefix = "p" + partNo + "_";
            for (Map.Entry<String, String> t : mg.textures.entrySet()) {
                textures.put(prefix + t.getKey(), t.getValue());
            }
            JsonArray baked = bakeRotation(mg.elements, p.xRot(), p.yRot(), p.uvlock());
            for (JsonElement el : baked) {
                mergedElements.add(renameTextureRefs(
                        inflateDegenerate(el.getAsJsonObject()), prefix));
            }
            partNo++;
        }
        if (mergedElements.size() == 0) {
            return null;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("parent", "minecraft:block/block");
        JsonObject texObj = new JsonObject();
        for (Map.Entry<String, String> t : textures.entrySet()) {
            texObj.addProperty(t.getKey(), t.getValue());
        }
        wrapper.add("textures", texObj);
        wrapper.add("elements", mergedElements);
        String file = WRAPPER_PREFIX + idx + ".json";
        String json = wrapper.toString();
        entries.put(file, json.getBytes(StandardCharsets.UTF_8));
        return WRAPPER_REF_PREFIX + idx;
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
    private static JsonObject inflateDegenerate(JsonObject element) {
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
                from.set(i, new com.google.gson.JsonPrimitive(a - 0.02));
                to.set(i, new com.google.gson.JsonPrimitive(b + 0.02));
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
    private record ModelGraph(Map<String, String> textures, JsonArray elements) {
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
                elements = mergeModelGeometry(json, textures, warnings);
            }
            cur = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        if (elements == null) {
            return new ModelGraph(textures, null);
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
        return new ModelGraph(resolved, elements);
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
     * @return the merged element array, or null when the level carries no
     *         geometry at all (the parent walk continues)
     */
    private static JsonArray mergeModelGeometry(JsonObject json, Map<String, String> textures,
                                                List<String> warnings) {
        JsonArray out = new JsonArray();
        if (json.has("elements")) {
            for (JsonElement el : json.getAsJsonArray("elements")) {
                out.add(el.deepCopy());
            }
        }
        if (json.has("children")) {
            for (Map.Entry<String, JsonElement> c : json.getAsJsonObject("children").entrySet()) {
                if (!c.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject child = c.getValue().getAsJsonObject();
                if (!child.has("elements") || !child.getAsJsonArray("elements")
                        .isJsonArray() || child.getAsJsonArray("elements").size() == 0) {
                    continue;
                }
                String prefix = c.getKey() + "_";
                if (child.has("textures") && child.getAsJsonObject("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> t : child.getAsJsonObject("textures")
                            .entrySet()) {
                        textures.putIfAbsent(prefix + t.getKey(), t.getValue().getAsString());
                    }
                }
                for (JsonElement el : child.getAsJsonArray("elements")) {
                    out.add(renameTextureRefs(el.getAsJsonObject(), prefix));
                }
            }
        }
        return out.size() == 0 ? null : out;
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
            applyFaceUvs(e, xRot, yRot, uvlock);
            out.add(bakeElement(e, xRot, yRot));
        }
        return out;
    }

    /**
     * 14-ae: write the DEFAULT uv (decompiled 26.3 defaultFaceUV) explicitly
     * into every null-uv face of the UNROTATED element, before the rotation
     * bake. The 1.21.1 block bake (truth channel) derives a null-uv face's uv
     * from the ORIGINAL geometry + ORIGINAL face direction and transports it
     * rigidly with the rotated quad, while the 26.3 item channel derives it
     * from the ROTATED geometry + REMAPPED direction. An explicit uv rides
     * the rotation verbatim on BOTH channels = the truth semantics.
     *
     * 14-ag V-MIRROR: the 1.21.1 recomputeUVs runs ONLY for uvlocked variants
     * (measured from the real srg jar: bakeQuad gates it on
     * ModelState.isUvLocked, NOT on hasRotation) — a non-uvlocked variant's
     * uv rides the ROTATED quad's own winding, while this pack's geometry is
     * canonicalised (sortBoxPair: the rotated box pair re-sorted per axis),
     * which LOSES the rotated winding. Measured on #80 (run 41792830144):
     * faces remapped by the X rotation (up/north/down/south under x=90/270)
     * render V-MIRRORED vs the truth — flipping the ours crop vertically
     * HALVES their ae (p391 axis=z rows: 116944->58337, 127766->57966 — both
     * under the 60k gate) while the y-rotation remapped faces (which preserve
     * the v axis) measure clean. The fix: for the x-remapped faces emit the
     * default uv with the V PAIR SWAPPED (v1<->v2), which mirrors the texture
     * vertically on that face and restores the truth's winding.
     */
    /** 14-ag: the face directions the X-rotation table actually moves
     * (up/north/down/south; east/west are x-invariant). */
    private static boolean xRemapped(String face) {
        return switch (face) {
            case "up", "north", "down", "south" -> true;
            default -> false;
        };
    }

    /**
     * 14-ah: the uv processing for one baked element. Non-uvlock variants
     * keep the 14-ae/14-ag default-uv + v-mirror path. UVLOCKED variants run
     * the truth's full chain (UvLock): the 1.21.1 block bake recomputes EVERY
     * face uv of a uvlocked variant (default OR explicit) through
     * recomputeUVs(uv, face, getUVLockTransform) — the stair/slab/wall pool —
     * then the measured winding transport relabels the rect corners so the
     * composite (unrotated, canonical corners) renders like the truth's
     * rotated quad per physical corner (verified by UvLockVerify, layers B-E
     * ALL OK against the real classes).
     */
    private static void applyFaceUvs(JsonObject el, int xRot, int yRot, boolean uvlock) {
        if (!uvlock) {
            writeDefaultUvs(el, xRot);
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
            UvLock.bakeUvlockFace(inRect, inRot, f.getKey(), xRot, yRot, out);
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

    private static void writeDefaultUvs(JsonObject el, int xRot) {
        // 14-ag: the faces the X rotation remaps lose their winding to the
        // geometry canonicalisation — their default uv needs the V mirror.
        boolean xFlipsV = xRot == 90 || xRot == 270;
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
            if (face.has("uv") || !face.has("texture")) {
                continue;
            }
            double[] uv = defaultFaceUv(f.getKey(), x1, y1, z1, x2, y2, z2);
            if (uv == null) {
                continue;
            }
            if (xFlipsV && xRemapped(f.getKey())) {
                // the V pair swap: the emitted rect (u1,v2,u2,v1) renders the
                // texture vertically mirrored on this face
                double t = uv[1];
                uv[1] = uv[3];
                uv[3] = t;
            }
            JsonArray arr = new JsonArray();
            for (double d : uv) {
                arr.add(d);
            }
            face.add("uv", arr);
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
        for (Path jar : jars) {
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                ZipEntry e = zf.getEntry(file);
                if (e != null) {
                    return JsonParser.parseString(
                            new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                }
            }
        }
        return null;
    }
}
