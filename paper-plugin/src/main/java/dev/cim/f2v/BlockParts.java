package dev.cim.f2v;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime side of the multipart look table ({@link F2vPack#BLOCK_PARTS}, S5): which parts of a
 * multipart blockstate are active in a placed state, and the one alternative each active part shows.
 *
 * <p>Vanilla 1.21.1 semantics (javap-verified like {@link BlockLooks}):
 * <ul>
 * <li>{@code MultiPartBakedModel.getQuads} walks the selectors in order. A selector whose
 * {@code when} matches the state contributes its model; several parts can match at once (a fence's
 * post and its arms). A {@code when} is either empty (always), {@code {"OR":[..]}}, {@code {"AND":[..]}}
 * or a map of property to value, where a value may list alternatives joined by {@code |}.</li>
 * <li>An apply with several weighted alternatives is a {@code WeightedBakedModel}. All the parts of one
 * block are drawn with ONE random, seeded once per block by {@code Mth.getSeed} ({@code setSeed} runs
 * before the block's getQuads call), so the parts consume it in order: each multi-alternative active
 * part takes one {@code nextLong} ({@link BlockLooks#pick} with a shared stream).</li>
 * </ul>
 * One part is one ItemDisplay in the display family (see {@code DisplayCarriers}); the part index is
 * the row's {@code part} column, the look's variant key.
 */
public final class BlockParts {
    private BlockParts() {
    }

    /** One alternative of one part: the part index, the when JSON ("" unconditional), weight, item and rotation. */
    public record Row(int part, String when, int weight, String itemModel, int rotX, int rotY) {
    }

    /** block_id -> the part rows of that block, in table order (rows of one part are contiguous). */
    public static Map<String, List<Row>> load(Path f2vDir) throws IOException {
        Path file = f2vDir.resolve(F2vPack.BLOCK_PARTS);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Parses the part TSV (header {@link F2vPack#BLOCK_PARTS_HEADER}); malformed rows are skipped. */
    public static Map<String, List<Row>> parse(String tsv) {
        Map<String, List<Row>> out = new HashMap<>();
        boolean first = true;
        for (String line : tsv.split("\n")) {
            if (first) {
                first = false;
                continue; // header
            }
            String[] c = line.split("\t", -1);
            if (c.length != 7) {
                continue;
            }
            try {
                Row row = new Row(Integer.parseInt(c[1]), c[2], Integer.parseInt(c[3]), c[4],
                        Integer.parseInt(c[5]), Integer.parseInt(c[6]));
                out.computeIfAbsent(c[0], k -> new ArrayList<>()).add(row);
            } catch (NumberFormatException malformed) {
                // skipped: the part table is advisory, the default display stays
            }
        }
        return out;
    }

    /** True when the table names {@code blockId} as a multipart block. */
    public static boolean isMultipart(Map<String, List<Row>> table, String blockId) {
        List<Row> rows = table.get(blockId);
        return rows != null && !rows.isEmpty();
    }

    /**
     * The when condition {@code when} holds for a state's properties (vanilla Multipart selector
     * predicates). An empty when always holds; a value with {@code |} alternatives holds on any of them.
     */
    static boolean holds(String when, Map<String, String> state) {
        if (when == null || when.isEmpty()) {
            return true;
        }
        JsonElement e = JsonParser.parseString(when);
        return e.isJsonObject() && condition(e.getAsJsonObject(), state);
    }

    private static boolean condition(JsonObject o, Map<String, String> state) {
        if (o.has("OR")) {
            for (JsonElement c : o.getAsJsonArray("OR")) {
                if (c.isJsonObject() && condition(c.getAsJsonObject(), state)) {
                    return true;
                }
            }
            return false;
        }
        if (o.has("AND")) {
            for (JsonElement c : o.getAsJsonArray("AND")) {
                if (!c.isJsonObject() || !condition(c.getAsJsonObject(), state)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> p : o.entrySet()) {
            String have = state.get(p.getKey());
            if (have == null) {
                return false; // the block has no such property: the selector cannot match
            }
            boolean any = false;
            for (String alt : p.getValue().getAsString().split("\\|")) {
                any |= alt.equals(have);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * The looks a multipart block shows in {@code stateText}: one {@link BlockLooks.Look} per active
     * part, its variant key the part index. Empty when the table has no rows for the block or no part
     * is active (the caller keeps a default display then). Positions seed the weighted choice.
     */
    public static List<BlockLooks.Look> active(Map<String, List<Row>> table, String blockId, String stateText,
                                               int x, int y, int z) {
        List<Row> rows = table.get(blockId);
        List<BlockLooks.Look> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        Map<String, String> state = BlockLooks.stateProps(stateText);
        // rows grouped by part, in table order
        Map<Integer, List<Row>> byPart = new LinkedHashMap<>();
        for (Row r : rows) {
            byPart.computeIfAbsent(r.part(), k -> new ArrayList<>()).add(r);
        }
        BlockLooks.LegacyRandom random = new BlockLooks.LegacyRandom(BlockLooks.seed(x, y, z));
        for (Map.Entry<Integer, List<Row>> part : byPart.entrySet()) {
            List<Row> alternatives = part.getValue();
            if (!holds(alternatives.get(0).when(), state)) {
                continue;
            }
            Row picked = alternatives.get(0);
            if (alternatives.size() > 1) {
                picked = weighted(random, alternatives);
            }
            out.add(new BlockLooks.Look(String.valueOf(part.getKey()), picked.weight(), picked.itemModel(),
                    picked.rotX(), picked.rotY(), false));
        }
        return out;
    }

    /** The alternative a WeightedBakedModel draws from the shared stream ({@link BlockLooks#pick} semantics). */
    private static Row weighted(BlockLooks.LegacyRandom random, List<Row> alternatives) {
        int total = 0;
        for (Row r : alternatives) {
            total += r.weight();
        }
        if (total <= 0) {
            return alternatives.get(0);
        }
        int index = Math.abs((int) random.nextLong()) % total;
        for (Row r : alternatives) {
            index -= r.weight();
            if (index < 0) {
                return r;
            }
        }
        return alternatives.get(0);
    }

    /** The display rotation of a part look (identity for the baked looks, whose geometry is rotated). */
    public static Quaternionf rotation(BlockLooks.Look look) {
        return BlockLooks.rotation(look.rotX(), look.rotY());
    }

    // ---------------------------------------------------------------- selftest

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--selftest")) {
            System.exit(selftest() ? 0 : 1);
        }
        System.err.println("usage: BlockParts --selftest");
        System.exit(2);
    }

    private static Row row(int part, String when, int w, String item, int x, int y) {
        return new Row(part, when, w, item, x, y);
    }

    static boolean selftest() throws Exception {
        List<String> failures = new ArrayList<>();
        Map<String, List<Row>> t = new HashMap<>();
        // a fence-like block: post (unconditional) + one arm per side, all parts as the blockstate lists them
        t.put("fake:fence", List.of(
                row(0, "", 1, "m/post", 0, 0),
                row(1, "{\"north\":\"true\"}", 1, "m/side", 0, 0),
                row(2, "{\"east\":\"true\"}", 1, "m/side", 0, 90),
                row(3, "{\"south\":\"true\"}", 1, "m/side", 0, 180),
                row(4, "{\"west\":\"true\"}", 1, "m/side", 0, 270)));
        expect(failures, "fence: post only when no connection", parts(t, "fake:fence",
                "fake:fence[north=false,east=false,south=false,west=false]", 0, 0, 0).equals("0"));
        expect(failures, "fence: post + north + south", parts(t, "fake:fence",
                "fake:fence[north=true,east=false,south=true,west=false]", 0, 0, 0).equals("0,1,3"));
        expect(failures, "fence: rotation of the east arm", active(t, "fake:fence",
                "fake:fence[north=false,east=true,south=false,west=false]", 0, 0, 0).get(1).rotY() == 90);
        expect(failures, "fence: a block without the property never matches its arm", parts(t, "fake:fence",
                "fake:fence", 0, 0, 0).equals("0"));
        expect(failures, "unknown block: no parts", active(t, "fake:other", "fake:other[north=true]", 0, 0, 0).isEmpty());

        // OR / AND / value alternatives (vanilla Multipart conditions)
        t.put("fake:wall", List.of(
                row(0, "{\"OR\":[{\"north\":\"true\"},{\"south\":\"true\"}]}", 1, "m/ns", 0, 0),
                row(1, "{\"AND\":[{\"east\":\"true\"},{\"west\":\"false\"}]}", 1, "m/ew", 0, 90),
                row(2, "{\"up\":\"low|tall\"}", 1, "m/up", 0, 0)));
        expect(failures, "OR: north alone holds", parts(t, "fake:wall", "fake:wall[north=true,south=false]", 0, 0, 0).equals("0"));
        expect(failures, "OR: neither holds", parts(t, "fake:wall", "fake:wall[north=false,south=false]", 0, 0, 0).isEmpty());
        expect(failures, "AND: east and not west", parts(t, "fake:wall",
                "fake:wall[east=true,west=false]", 0, 0, 0).equals("1"));
        expect(failures, "AND: east and west fails", parts(t, "fake:wall",
                "fake:wall[east=true,west=true]", 0, 0, 0).isEmpty());
        expect(failures, "value alternatives: low", parts(t, "fake:wall", "fake:wall[up=low]", 0, 0, 0).equals("2"));
        expect(failures, "value alternatives: none", parts(t, "fake:wall", "fake:wall[up=none]", 0, 0, 0).isEmpty());

        // weighted alternatives in one part: one draw per multi-alternative active part, in part order
        t.put("fake:weighted", List.of(
                row(0, "", 1, "m/a", 0, 0), row(0, "", 2, "m/b", 0, 0),
                row(1, "", 1, "m/c", 0, 0), row(1, "", 1, "m/d", 0, 0)));
        List<BlockLooks.Look> w = active(t, "fake:weighted", "fake:weighted[]", 5, 64, -3);
        expect(failures, "weighted: two parts, two looks", w.size() == 2);
        expect(failures, "weighted: deterministic per position", active(t, "fake:weighted", "fake:weighted[]", 5, 64, -3)
                .equals(w));
        // the first part draws from the same stream the lone BlockLooks.pick would see
        BlockLooks.LegacyRandom first = new BlockLooks.LegacyRandom(BlockLooks.seed(5, 64, -3));
        int idx = Math.abs((int) first.nextLong()) % 3;
        expect(failures, "weighted: first part draws like WeightedBakedModel", w.get(0).itemModel()
                .equals(idx < 1 ? "m/a" : "m/b"));
        // a single-alternative part draws nothing: the second part's draw is the stream's first draw
        t.put("fake:single", List.of(row(0, "", 1, "m/x", 0, 0), row(1, "", 1, "m/y", 0, 0), row(1, "", 1, "m/z", 0, 0)));
        int sIdx = Math.abs((int) new BlockLooks.LegacyRandom(BlockLooks.seed(1, 2, 3)).nextLong()) % 2;
        expect(failures, "single part draws nothing first", active(t, "fake:single", "fake:single[]", 1, 2, 3)
                .get(1).itemModel().equals(sIdx < 1 ? "m/y" : "m/z"));

        // the TSV round trip
        String tsv = F2vPack.BLOCK_PARTS_HEADER + "fake:fence\t0\t\t1\tm/post\t0\t0\n"
                + "fake:fence\t1\t{\"north\":\"true\"}\t1\tm/side\t0\t0\n"
                + "fake:bad\tnope\t\t1\tm/x\t0\t0\n";
        Map<String, List<Row>> parsed = parse(tsv);
        expect(failures, "parse: two fence rows, bad row skipped", parsed.get("fake:fence").size() == 2
                && !parsed.containsKey("fake:bad") && isMultipart(parsed, "fake:fence") && !isMultipart(parsed, "fake:bad"));
        expect(failures, "parse: when kept verbatim", parsed.get("fake:fence").get(1).when().equals("{\"north\":\"true\"}"));

        System.out.println(failures.isEmpty() ? "BlockParts selftest ALL OK" : "BlockParts selftest FAILED: " + failures);
        return failures.isEmpty();
    }

    /** The active part indices of a state, comma separated (a test helper). */
    private static String parts(Map<String, List<Row>> t, String block, String state, int x, int y, int z) {
        StringBuilder sb = new StringBuilder();
        for (BlockLooks.Look l : active(t, block, state, x, y, z)) {
            sb.append(sb.isEmpty() ? "" : ",").append(l.variantKey());
        }
        return sb.toString();
    }

    private static void expect(List<String> failures, String what, boolean ok) {
        if (!ok) {
            failures.add(what);
        }
    }
}
