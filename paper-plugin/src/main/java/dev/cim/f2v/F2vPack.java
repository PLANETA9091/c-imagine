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
            rows.add(new VisualItem(parts[0].trim(), parts[1].trim(), cmd, select));
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

    /** Assign commands: explicit values pass through; the rest get 1000+i. */
    static List<VisualItem> assignCmds(List<VisualItem> rows) {
        List<VisualItem> out = new ArrayList<>();
        int next = 0;
        for (VisualItem r : rows) {
            float cmd = r.cmd() >= 0f ? r.cmd() : CMD_BASE + next;
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
        List<String> warnings = new ArrayList<>();
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

        TreeMap<String, byte[]> entries = new TreeMap<>();
        entries.put("pack.mcmeta", packMcmeta().getBytes(StandardCharsets.UTF_8));
        if (!rows.isEmpty()) {
            // the F2V 2.0 modded post-effect chain rides every non-empty pack
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
        List<Map.Entry<Float, String>> paperFloatEntries = new ArrayList<>();
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
                String found = resolveAndCopyModelGraph(jars, row.moddedId(), entries, warnings,
                        paletteByVariantId);
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
        // paths, multipart composites under c-imagine:models/gallery/).
        List<GalleryPack.Resolved> gallery = List.of();
        Path galleryManifest = outDir.resolve("visual-gallery.tsv");
        if (Files.isRegularFile(galleryManifest)) {
            List<GalleryPack.Input> galleryInputs =
                    GalleryPack.readManifest(galleryManifest);
            if (!galleryInputs.isEmpty()) {
                gallery = GalleryPack.build(jars, galleryInputs, entries, warnings);
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
        return new Result(rows, blockRows, sha1, entries.size(), warnings, gallery);
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
              .append(e.getValue()).append("\"}}");
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
            + "fakemod:rock[facing=north]\n";

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
            jarEntries.put("assets/fakemod/blockstates/pane.json", BS_PANE.getBytes(StandardCharsets.UTF_8));
            jarEntries.put("assets/fakemod/textures/block/post_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/side_tex.png", testPng());
            jarEntries.put("assets/fakemod/textures/block/arrow_tex.png", testPng());
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
                    + "\"faces\": {\"down\": {\"uv\": [0,0,16,16], \"texture\": \"#down\", \"cullface\": \"down\"}, "
                    + "\"up\": {\"uv\": [0,0,16,16], \"texture\": \"#up\", \"cullface\": \"up\"}, "
                    + "\"north\": {\"uv\": [0,0,16,16], \"texture\": \"#north\", \"cullface\": \"north\"}, "
                    + "\"south\": {\"uv\": [0,0,16,16], \"texture\": \"#south\", \"cullface\": \"south\"}, "
                    + "\"west\": {\"uv\": [0,0,16,16], \"texture\": \"#west\", \"cullface\": \"west\"}, "
                    + "\"east\": {\"uv\": [0,0,16,16], \"texture\": \"#east\", \"cullface\": \"east\"}}}]}")
                    .getBytes(StandardCharsets.UTF_8));
            vanillaEntries.put("assets/minecraft/models/block/block.json",
                    "{\"gui_light\": \"side\"}".getBytes(StandardCharsets.UTF_8));
            writeJar(vanillaJar, vanillaEntries);

            // -- manifest ---------------------------------------------------------
            Path out = tmp.resolve("out");
            Files.createDirectories(out);
            Files.writeString(out.resolve("visual-items.tsv"),
                    "fakemod:cool_sword\tminecraft:paper\n"
                    + "fakemod:deep_thing\tminecraft:paper\t2000.0\n"
                    + "fakemod:gem\tminecraft:paper\t3000.0\n"
                    + "fakemod:wand_thing\tminecraft:brick\t\tselect:wand\n",
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

            // -- build twice, must be byte-identical ------------------------------
            List<EquipmentAsset> equipment = List.of(new EquipmentAsset(
                    "fakemod:shiny",
                    "textures/models/armor/shiny_layer_1.png",
                    "textures/models/armor/shiny_layer_2.png"));
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
            expect(failures, r1.gallery().size() == 4,
                    "gallery: 4 state rows resolved, got " + r1.gallery().size());
            if (r1.gallery().size() == 4) {
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
            }
            expect(failures, r1.gallery().stream()
                    .mapToDouble(GalleryPack.Resolved::cmd).distinct().count() == 4,
                    "gallery: cmds unique (100000..100003)");

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
