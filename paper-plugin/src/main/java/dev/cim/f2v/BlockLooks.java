package dev.cim.f2v;

import org.joml.Quaternionf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime side of the per-state look table ({@link F2vPack#BLOCK_LOOKS}): which look a placed
 * multi-look block shows in a given state, and the display transform that shows it.
 *
 * <p>Vanilla 1.21.1 semantics (client jar, javap-verified):
 * <ul>
 * <li>Variant matching: {@code BlockStateModelLoader.predicate} splits the variant key on ',' then
 * '=' and matches a state when the block is the owner and every listed property equals the state's
 * value ({@code lambda$predicate$12}). A key lists a subset of the properties; the empty key matches
 * every state. Each state must match exactly one key: {@code loadBlockStateDefinitions} lambda 7
 * throws "Overlapping definition" on a second match. This port does not throw; on an overlap it
 * takes the first key in table order and reports it through {@link Look#overlap()}.</li>
 * <li>Weighted alternatives: a key with one alternative bakes that model (no randomness). Two or more
 * bake a {@code WeightedBakedModel} ({@code MultiVariant.bake}, {@code WeightedBakedModel.Builder.build}).
 * {@code ModelBlockRenderer.tesselateWithAO/WithoutAO} calls {@code random.setSeed(seed)} before every
 * {@code getQuads}, with {@code seed = state.getSeed(pos)} = {@code BlockBehaviour.getSeed} =
 * {@code Mth.getSeed(Vec3i)}. {@code WeightedBakedModel.getQuads} then picks
 * {@code WeightedRandom.getWeightedItem(list, Math.abs((int) random.nextLong()) % totalWeight)}.
 * {@code getWeightedItem} walks the list subtracting each weight and returns the first entry that
 * drives the index below zero.</li>
 * <li>{@code RandomSource.create()} is a {@code LegacyRandomSource}; its {@code setSeed} is
 * {@code (seed ^ 0x5DEECE66D) & (2^48-1)}, its {@code next(bits)} is the java.util.Random LCG, and
 * {@code BitRandomSource.nextLong()} is {@code ((long) next(32) << 32) + next(32)}.</li>
 * </ul>
 */
public final class BlockLooks {
    private BlockLooks() {
    }

    /**
     * One look alternative of a block: the variant key it belongs to, its weight, the item_model the
     * display uses, and the runtime rotation (0/0 for baked looks, whose geometry is already rotated).
     */
    public record Look(String variantKey, int weight, String itemModel, int rotX, int rotY, boolean overlap) {
        Look withOverlap(boolean o) {
            return new Look(variantKey, weight, itemModel, rotX, rotY, o);
        }
    }

    /** block_id -> the look rows of that block, in table order (rows of one variant key are contiguous). */
    public static Map<String, List<Look>> load(Path f2vDir) throws IOException {
        Path file = f2vDir.resolve(F2vPack.BLOCK_LOOKS);
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** Parses the look TSV text (header {@link F2vPack#BLOCK_LOOKS_HEADER}); malformed rows are skipped. */
    public static Map<String, List<Look>> parse(String tsv) {
        Map<String, List<Look>> out = new HashMap<>();
        boolean first = true;
        for (String line : tsv.split("\n")) {
            if (first) {
                first = false;
                continue; // header
            }
            String[] c = line.split("\t", -1);
            if (c.length != 6) {
                continue;
            }
            try {
                Look look = new Look(c[1], Integer.parseInt(c[2]), c[3], Integer.parseInt(c[4]),
                        Integer.parseInt(c[5]), false);
                out.computeIfAbsent(c[0], k -> new ArrayList<>()).add(look);
            } catch (NumberFormatException malformed) {
                // skipped: the look table is advisory, the default display stays
            }
        }
        return out;
    }

    /** A variant key ({@code "a=b,c=d"}, or "") as its property map; the empty key is the empty map. */
    public static Map<String, String> variantProps(String variantKey) {
        Map<String, String> props = new HashMap<>();
        if (variantKey.isEmpty()) {
            return props;
        }
        for (String pair : variantKey.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("bad variant key: " + variantKey);
            }
            props.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return props;
    }

    /** A placed block state as the layer and Paper keep it ({@code ns:id[a=b,c=d]}): its property map. */
    public static Map<String, String> stateProps(String stateText) {
        int open = stateText == null ? -1 : stateText.indexOf('[');
        if (open < 0 || !stateText.endsWith("]")) {
            return new HashMap<>();
        }
        return variantProps(stateText.substring(open + 1, stateText.length() - 1));
    }

    /** Vanilla's per-state key match: every property the key lists equals the state's value. */
    static boolean matches(Map<String, String> key, Map<String, String> state) {
        for (Map.Entry<String, String> e : key.entrySet()) {
            if (!e.getValue().equals(state.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The look a block at {@code (x, y, z)} shows in {@code stateText}, or null when the table has no
     * row for the block or no key matches the state (the caller keeps the default display then).
     */
    public static Look resolve(Map<String, List<Look>> table, String blockId, String stateText, int x, int y, int z) {
        List<Look> rows = table.get(blockId);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, String> state = stateProps(stateText);
        // group the rows by variant key, in table order (LinkedHashMap keeps the first-seen order)
        Map<String, List<Look>> byKey = new LinkedHashMap<>();
        for (Look row : rows) {
            byKey.computeIfAbsent(row.variantKey(), k -> new ArrayList<>()).add(row);
        }
        List<Look> chosen = null;
        boolean overlap = false;
        for (Map.Entry<String, List<Look>> k : byKey.entrySet()) {
            if (matches(variantProps(k.getKey()), state)) {
                if (chosen == null) {
                    chosen = k.getValue();
                } else {
                    overlap = true; // vanilla throws here; the first key stands
                }
            }
        }
        if (chosen == null) {
            return null;
        }
        Look picked = chosen.size() == 1 ? chosen.get(0) : chosen.get(pick(seed(x, y, z), chosen));
        return overlap ? picked.withOverlap(true) : picked;
    }

    /** {@code Mth.getSeed(int, int, int)} (javap: the 3129871 / 116129781 mix, then v*(v*42317861+11) >> 16). */
    public static long seed(int x, int y, int z) {
        long v = (long) (x * 3129871) ^ (long) z * 116129781L ^ (long) y;
        v = v * (v * 42317861L + 11L);
        return v >> 16;
    }

    /**
     * The index of the alternative {@code WeightedBakedModel.getQuads} picks after
     * {@code random.setSeed(seed)}: {@code getWeightedItem(list, abs((int) nextLong()) % total)}.
     * A zero total is a vanilla exception; here the first alternative stands.
     */
    static int pick(long seed, List<Look> alternatives) {
        int total = 0;
        for (Look l : alternatives) {
            total += l.weight();
        }
        if (total == 0) {
            return 0;
        }
        LegacyRandom random = new LegacyRandom(seed);
        int index = Math.abs((int) random.nextLong()) % total;
        for (int i = 0; i < alternatives.size(); i++) {
            index -= alternatives.get(i).weight();
            if (index < 0) {
                return i;
            }
        }
        return 0; // unreachable for total > 0: the index is below total
    }

    /**
     * The LegacyRandomSource 1.21.1 uses (javap: setSeed scrambles with 0x5DEECE66D and masks to 48
     * bits; next(bits) is the LCG; nextLong = ((long) next(32) << 32) + next(32)).
     */
    static final class LegacyRandom {
        private static final long MULT = 0x5DEECE66DL;
        private static final long MASK = (1L << 48) - 1;
        private long state;

        LegacyRandom(long seed) {
            this.state = (seed ^ MULT) & MASK;
        }

        int next(int bits) {
            state = (state * MULT + 0xBL) & MASK;
            return (int) (state >>> (48 - bits));
        }

        long nextLong() {
            long hi = next(32);
            long lo = next(32);
            return (hi << 32) + lo;
        }
    }

    /**
     * The display transform rotation of a look: {@code rotateYXZ(-rotY, -rotX, 0)} in radians. It is the
     * vanilla BlockModelRotation for x then y (GalleryPack.bakeVec). Identity for 0/0.
     */
    public static Quaternionf rotation(int rotX, int rotY) {
        if (rotX == 0 && rotY == 0) {
            return new Quaternionf();
        }
        return new Quaternionf().rotateYXZ((float) Math.toRadians(-rotY), (float) Math.toRadians(-rotX), 0f);
    }

    // ---------------------------------------------------------------- selftest

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--selftest")) {
            System.exit(selftest() ? 0 : 1);
        }
        System.err.println("usage: BlockLooks --selftest");
        System.exit(2);
    }

    private static Look row(String key, int w, String item, int x, int y) {
        return new Look(key, w, item, x, y, false);
    }

    static boolean selftest() throws Exception {
        List<String> failures = new ArrayList<>();
        // (1) rotation block, axis x/y/z: one key per axis, the rot of the axis look
        Map<String, List<Look>> t = new HashMap<>();
        t.put("fake:pillar", List.of(
                row("axis=x", 1, "c-imagine:_display/fake/pillar_x", 90, 90),
                row("axis=y", 1, "c-imagine:_display/fake/pillar", 0, 0),
                row("axis=z", 1, "c-imagine:_display/fake/pillar", 90, 0)));
        expect(failures, "axis x", look(t, "fake:pillar", "fake:pillar[axis=x]", 0, 0, 0, "c-imagine:_display/fake/pillar_x", 90, 90));
        expect(failures, "axis y", look(t, "fake:pillar", "fake:pillar[axis=y]", 0, 0, 0, "c-imagine:_display/fake/pillar", 0, 0));
        expect(failures, "axis z", look(t, "fake:pillar", "fake:pillar[axis=z]", 0, 0, 0, "c-imagine:_display/fake/pillar", 90, 0));
        expect(failures, "no axis prop -> no look", BlockLooks.resolve(t, "fake:pillar", "fake:pillar", 0, 0, 0) == null);
        expect(failures, "unknown block -> no look", BlockLooks.resolve(t, "fake:other", "fake:other[axis=x]", 0, 0, 0) == null);

        // (2) multi-key block: subset keys, exact keys, and the empty key
        t.put("fake:stairs", List.of(
                row("facing=east", 1, "m/east", 0, 90),
                row("facing=north,half=bottom", 1, "m/nb", 0, 0),
                row("facing=north,half=top", 1, "m/nt", 180, 0)));
        expect(failures, "subset key facing=east matches any half", look(t, "fake:stairs",
                "fake:stairs[facing=east,half=top,shape=straight]", 0, 0, 0, "m/east", 0, 90));
        expect(failures, "exact key north/top", look(t, "fake:stairs", "fake:stairs[facing=north,half=top]", 0, 0, 0, "m/nt", 180, 0));
        expect(failures, "exact key north/bottom", look(t, "fake:stairs", "fake:stairs[half=bottom,facing=north]", 0, 0, 0, "m/nb", 0, 0));
        expect(failures, "south matches no key", BlockLooks.resolve(t, "fake:stairs", "fake:stairs[facing=south,half=top]", 0, 0, 0) == null);
        t.put("fake:any", List.of(row("", 1, "m/any", 0, 0)));
        expect(failures, "empty key matches every state", look(t, "fake:any", "fake:any[lit=true]", 0, 0, 0, "m/any", 0, 0));
        expect(failures, "empty key matches the bare state", look(t, "fake:any", "fake:any", 0, 0, 0, "m/any", 0, 0));

        // (3) weighted alternatives: the pick is WeightedBakedModel's, from the seeded LegacyRandom
        List<Look> three = List.of(row("", 1, "w/a", 0, 0), row("", 2, "w/b", 0, 90), row("", 3, "w/c", 90, 0));
        t.put("fake:weighted", three);
        // oracle values: java.util.Random(seed) (the same LCG) run in the scratch oracle, total 6:
        //  (0,0,0): abs((int) nextLong)=723955400 -> %6=2 -> index 2: 2-1=1, 1-2<0 -> entry b (index 1)
        //  (1,0,0): 136055449 %6=1 -> 1-1=0, not <0 -> 0-2<0 -> entry b
        //  (0,1,0): 2082945864 %6=0 -> 0-1<0 -> entry a
        //  (0,0,1): 1231388227 %6=1 -> entry b
        //  (5,64,-9): 331551319 %6=1 -> entry b
        //  (-3,70,12): 1223126658 %6=0 -> entry a
        expect(failures, "weighted (0,0,0)", look(t, "fake:weighted", "fake:weighted", 0, 0, 0, "w/b", 0, 90));
        expect(failures, "weighted (1,0,0)", look(t, "fake:weighted", "fake:weighted", 1, 0, 0, "w/b", 0, 90));
        expect(failures, "weighted (0,1,0)", look(t, "fake:weighted", "fake:weighted", 0, 1, 0, "w/a", 0, 0));
        expect(failures, "weighted (0,0,1)", look(t, "fake:weighted", "fake:weighted", 0, 0, 1, "w/b", 0, 90));
        expect(failures, "weighted (5,64,-9)", look(t, "fake:weighted", "fake:weighted", 5, 64, -9, "w/b", 0, 90));
        expect(failures, "weighted (-3,70,12)", look(t, "fake:weighted", "fake:weighted", -3, 70, 12, "w/a", 0, 0));
        // a sweep: the port agrees with the JDK oracle at every point of a 9x9x9 cube
        int sweepMismatch = 0;
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -4; z <= 4; z++) {
                    long s = seed(x, y, z);
                    int idx = Math.abs((int) new java.util.Random(s).nextLong()) % 6;
                    int expectIdx = idx < 1 ? 0 : idx < 3 ? 1 : 2;
                    if (pick(s, three) != expectIdx) {
                        sweepMismatch++;
                    }
                }
            }
        }
        expect(failures, "weighted sweep 9^3 vs JDK LCG oracle (" + sweepMismatch + " mismatches)", sweepMismatch == 0);
        expect(failures, "seed(0,0,0)=0 (Mth.getSeed)", seed(0, 0, 0) == 0L);
        expect(failures, "seed(1,0,0)=133076631897947", seed(1, 0, 0) == 133076631897947L);
        expect(failures, "seed(0,1,0)=645", seed(0, 1, 0) == 645L);
        // a single alternative never draws (vanilla bakes the model directly)
        expect(failures, "single alternative picks index 0", pick(seed(9, 9, 9), List.of(row("", 5, "s", 0, 0))) == 0);

        // (4) the quaternion equals the S2 bake (GalleryPack.bakeVec, 16-unit, centre 8) for every
        // rotation pair vanilla uses
        var bake = GalleryPack.class.getDeclaredMethod("bakeVec", com.google.gson.JsonArray.class, int.class, int.class);
        bake.setAccessible(true);
        int[] angles = {0, 90, 180, 270};
        int checked = 0;
        int bad = 0;
        int[][] pts = {{0, 0, 0}, {16, 0, 0}, {3, 5, 7}, {16, 16, 16}, {1, 2, 15}, {12, 0, 4}};
        for (int rx : angles) {
            for (int ry : angles) {
                org.joml.Quaternionf q = rotation(rx, ry);
                for (int[] p : pts) {
                    com.google.gson.JsonArray in = new com.google.gson.JsonArray();
                    in.add(p[0]);
                    in.add(p[1]);
                    in.add(p[2]);
                    com.google.gson.JsonArray want = (com.google.gson.JsonArray) bake.invoke(null, in, rx, ry);
                    org.joml.Vector3f v = new org.joml.Vector3f(p[0] / 16f - 0.5f, p[1] / 16f - 0.5f, p[2] / 16f - 0.5f);
                    q.transform(v);
                    double gx = (v.x + 0.5) * 16, gy = (v.y + 0.5) * 16, gz = (v.z + 0.5) * 16;
                    checked++;
                    if (Math.abs(gx - want.get(0).getAsDouble()) > 1e-3
                            || Math.abs(gy - want.get(1).getAsDouble()) > 1e-3
                            || Math.abs(gz - want.get(2).getAsDouble()) > 1e-3) {
                        bad++;
                    }
                }
            }
        }
        expect(failures, "quaternion == bakeVec for 16 rot pairs x 6 points (" + checked + " checked, " + bad + " bad)",
                bad == 0 && checked == 96);
        // the 90/90 unit vector explicitly: x-rot leaves +X alone, then y-rot(-90) takes it to +Z
        // (bakeVec: (16,8,8) -> x-rot (16,8,8) -> y-rot (8,8,16), i.e. relative (0,0,+8))
        org.joml.Vector3f ux = new org.joml.Vector3f(1, 0, 0);
        rotation(90, 90).transform(ux);
        expect(failures, "rot(90,90) of +X is +Z: " + ux,
                Math.abs(ux.x) < 1e-5 && Math.abs(ux.y) < 1e-5 && Math.abs(ux.z - 1f) < 1e-5);

        // (5) the table parser: header, a row with a bad int, a key with several props
        Map<String, List<Look>> parsed = parse(F2vPack.BLOCK_LOOKS_HEADER
                + "fake:x\tfacing=north,half=top\t1\tc-imagine:_display/fake/x\t180\t0\n"
                + "fake:x\tbad\tnot-a-number\tm\t0\t0\n");
        expect(failures, "parse keeps good rows, skips a bad one", parsed.get("fake:x") != null
                && parsed.get("fake:x").size() == 1 && parsed.get("fake:x").get(0).rotX() == 180);
        expect(failures, "state text parses to a map", stateProps("a:b[k=v,n=m]").equals(Map.of("k", "v", "n", "m")));

        for (String f : failures) {
            System.err.println("FAIL " + f);
        }
        System.out.println(failures.isEmpty() ? "BlockLooks selftest ALL OK" : "BlockLooks selftest FAILED: " + failures.size());
        return failures.isEmpty();
    }

    /** True when the resolved look is the one expected (id, rot). */
    private static boolean look(Map<String, List<Look>> t, String block, String state, int x, int y, int z,
                                String item, int rx, int ry) {
        Look l = BlockLooks.resolve(t, block, state, x, y, z);
        return l != null && l.itemModel().equals(item) && l.rotX() == rx && l.rotY() == ry;
    }

    private static void expect(List<String> failures, String what, boolean ok) {
        if (!ok) {
            failures.add(what);
        }
    }
}
