package dev.cim.f2v;

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
    public record VisualItem(String moddedId, String hostItem, float cmd) {
    }

    /** Build outcome: resolved rows, sha1 hex, emitted entry count, warnings. */
    public record Result(List<VisualItem> items, String sha1, int entryCount,
                         List<String> warnings) {
    }

    /** Resource pack format for 26.3 (client version.json — measured). */
    public static final int PACK_FORMAT_MAJOR = 97;
    public static final int PACK_FORMAT_MINOR = 1;
    /** First auto-assigned custom_model_data float (manifest may override). */
    public static final float CMD_BASE = 1000.0f;
    /** DOS/zip epoch (1980-01-01) — the earliest representable zip time. */
    private static final long FIXED_EPOCH = 315532800000L;

    private F2vPack() {
    }

    // ---- manifest ------------------------------------------------------------

    /**
     * Read {@code visual-items.tsv}: {@code moddedId<TAB>host<TAB>cmd} with
     * cmd blank/-1 for auto-assignment (1000 + index, sorted-id order).
     * {@code #} comments and blank lines are ignored.
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
            rows.add(new VisualItem(parts[0].trim(), parts[1].trim(), cmd));
        }
        rows.sort(Comparator.comparing(VisualItem::moddedId));
        return rows;
    }

    /** Write the RESOLVED manifest (final cmds) for the plugin's give leg. */
    public static void writeResolvedManifest(Path tsv, List<VisualItem> rows)
            throws IOException {
        StringBuilder sb = new StringBuilder("# resolved by F2vPack — final "
                + "custom_model_data floats (do not edit; regenerate instead)\n");
        for (VisualItem r : rows) {
            sb.append(r.moddedId()).append('\t').append(r.hostItem())
              .append('\t').append(r.cmd()).append('\n');
        }
        Files.createDirectories(tsv.getParent());
        Files.writeString(tsv, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Assign commands: explicit values pass through; the rest get 1000+i. */
    static List<VisualItem> assignCmds(List<VisualItem> rows) {
        List<VisualItem> out = new ArrayList<>();
        int next = 0;
        for (VisualItem r : rows) {
            float cmd = r.cmd() >= 0f ? r.cmd() : CMD_BASE + next;
            next++;
            out.add(new VisualItem(r.moddedId(), r.hostItem(), cmd));
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
        List<String> warnings = new ArrayList<>();
        List<VisualItem> rows = assignCmds(manifestRows);
        TreeMap<String, byte[]> entries = new TreeMap<>();
        entries.put("pack.mcmeta", packMcmeta().getBytes(StandardCharsets.UTF_8));

        // group rows by host (one items/ def per host, entries sorted by cmd)
        Map<String, List<VisualItem>> byHost = new TreeMap<>();
        for (VisualItem r : rows) {
            byHost.computeIfAbsent(r.hostItem(), k -> new ArrayList<>()).add(r);
        }

        List<Path> jars = listJars(modsDir);
        for (Map.Entry<String, List<VisualItem>> host : byHost.entrySet()) {
            String hostItem = host.getKey();
            if (!hostItem.startsWith("minecraft:")) {
                warnings.add("host " + hostItem + " is not vanilla (minecraft:) — skipped");
                continue;
            }
            List<Map.Entry<Float, String>> entriesForHost = new ArrayList<>();
            for (VisualItem row : host.getValue()) {
                String found = resolveAndCopyModelGraph(jars, row.moddedId(), entries, warnings);
                if (found == null) {
                    warnings.add("no item model found for " + row.moddedId()
                            + " in any mod jar — row dropped");
                    continue;
                }
                entriesForHost.add(Map.entry(row.cmd(), found));
            }
            entriesForHost.sort(Map.Entry.comparingByKey());
            if (!entriesForHost.isEmpty()) {
                String hostPath = hostItem.substring("minecraft:".length());
                entries.put("assets/minecraft/items/" + hostPath + ".json",
                        itemDef(hostPath, entriesForHost).getBytes(StandardCharsets.UTF_8));
            }
        }

        byte[] zip = zip(entries);
        String sha1 = sha1Hex(zip);
        Files.createDirectories(outDir);
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
        return new Result(rows, sha1, entries.size(), warnings);
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
                                                   List<String> warnings) throws IOException {
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
                    if (cand.startsWith("assets/" + ns + "/models/item/")) {
                        return ns + ":item/" + path;
                    }
                    return ns + ":" + path;
                }
            }
        }
        return null;
    }

    /** Depth-limited parent walk within the SAME jar as the entry model. */
    private static void copyParentGraph(ZipFile zf, String modelPath, byte[] modelBytes,
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
            if (ref.startsWith("#") || ref.startsWith("minecraft:")) {
                continue; // local key or vanilla texture — nothing to copy
            }
            int c = ref.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String texNs = ref.substring(0, c);
            String texPath = ref.substring(c + 1);
            String texFile = "assets/" + texNs + "/textures/" + texPath + ".png";
            if (entries.containsKey(texFile)) {
                continue;
            }
            ZipEntry te = zf.getEntry(texFile);
            if (te == null) {
                warnings.add("texture missing in jar: " + ref
                        + " (referenced by " + modelPath + ")");
                continue;
            }
            if (!texPath.startsWith("item/") && !texPath.startsWith("block/")) {
                // outside the auto-atlased directories — a merged atlas
                // override would be needed (v1 constraint). Skip the copy:
                // without an atlas entry the client cannot resolve the
                // texture either way, and dead bytes would mask the warning.
                warnings.add("texture outside item|block dirs not atlased: " + ref);
                continue;
            }
            entries.put(texFile, zf.getInputStream(te).readAllBytes());
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
    static String itemDef(String hostPath, List<Map.Entry<Float, String>> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"model\": {\n")
          .append("    \"type\": \"minecraft:range_dispatch\",\n")
          .append("    \"property\": \"minecraft:custom_model_data\",\n")
          .append("    \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<Float, String> e = entries.get(i);
            sb.append("      {\"threshold\": ").append(e.getKey())
              .append(", \"model\": {\"type\": \"minecraft:model\", \"model\": \"")
              .append(e.getValue()).append("\"}}");
            sb.append(i + 1 < entries.size() ? ",\n" : "\n");
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
            for (int i = 3; i < args.length - 1; i++) {
                if (args[i].equals("--manifest")) {
                    manifest = Path.of(args[i + 1]);
                }
            }
            List<VisualItem> rows = Files.isRegularFile(manifest)
                    ? readManifest(manifest) : List.of();
            Result r = build(modsDir, outDir, rows);
            System.out.println("f2v pack: built (" + r.items().size() + " item(s), "
                    + r.entryCount() + " zip entries, sha1=" + r.sha1() + ", "
                    + r.warnings().size() + " warning(s))");
            return;
        }
        System.err.println("usage: F2vPack --build <modsDir> <outDir> [--manifest <tsv>]");
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
            jarEntries.put("assets/fakemod/textures/item/cool_sword_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/deep/custom_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/item/parent_tex.png", testPng());
            writeJar(jar, jarEntries);

            // -- manifest ---------------------------------------------------------
            Path out = tmp.resolve("out");
            Files.createDirectories(out);
            Files.writeString(out.resolve("visual-items.tsv"),
                    "fakemod:cool_sword\tminecraft:paper\n"
                    + "fakemod:deep_thing\tminecraft:paper\t2000.0\n",
                    StandardCharsets.UTF_8);

            // -- build twice, must be byte-identical ------------------------------
            Result r1 = build(mods, out, readManifest(out.resolve("visual-items.tsv")));
            Result r2 = build(mods, out, readManifest(out.resolve("visual-items.tsv")));
            if (!r1.sha1().equals(r2.sha1())) {
                failures.add("sha1 not deterministic: " + r1.sha1() + " vs " + r2.sha1());
            }
            if (r1.items().size() != 2) {
                failures.add("expected 2 resolved rows, got " + r1.items().size());
            }
            // auto cmd must be CMD_BASE for the first sorted row
            if (r1.items().get(0).cmd() != CMD_BASE
                    || r1.items().get(1).cmd() != 2000.0f) {
                failures.add("cmd assignment wrong: " + r1.items());
            }

            // -- zip contents -------------------------------------------------------
            TreeMap<String, byte[]> entries = readZip(out.resolve("pack.zip"));
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

            // -- json scanning helpers ------------------------------------------
            expect(failures, extractStringField(MODEL_COOL_SWORD, "parent")
                    .equals("fakemod:item/parent_model"), "parent field extracted");
            Map<String, String> tex = extractTextures(MODEL_COOL_SWORD);
            expect(failures, tex.get("layer0").equals("fakemod:item/cool_sword_tex"),
                    "textures map extracted");
        } finally {
            deleteRecursively(tmp);
        }
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
