package dev.cim.live;

import java.util.ArrayList;
import java.util.List;

/**
 * One block of the real neighbourhood of a {@code use-on}, as Paper sends it after the first line:
 * {@code nb TAB x TAB y TAB z TAB state}, absolute world coordinates and the block's state text in
 * the 1.21.1 layer's form (the same text {@link BlockStates#parse} reads). Paper sends the 3x3x3 box
 * around the target cell, air left out, so the facade holds what a placement reads (the ground under
 * a sapling, the stairs or fences beside a stair, the cogwheel beside a shaft) rather than stone.
 *
 * <p>A use-on without {@code nb} lines (an older Paper) gives an empty list, and the caller keeps its
 * previous behaviour. No Minecraft types here, so the parser runs under plain java.
 */
public record Neighbour(int x, int y, int z, String state) {

    /** The line prefix; the fields are tab-separated and the state is the last field. */
    public static final String PREFIX = "nb\t";

    /** The neighbourhood lines among the request lines (index 0 is the use-on line itself). */
    public static List<Neighbour> parse(String[] lines) {
        List<Neighbour> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].startsWith(PREFIX)) {
                continue;
            }
            String[] p = lines[i].split("\t", 5);
            if (p.length != 5 || p[4].isEmpty()) {
                continue;
            }
            try {
                out.add(new Neighbour(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                        Integer.parseInt(p[3]), p[4]));
            } catch (NumberFormatException e) {
                // A malformed line is no neighbour: the facade keeps its default there.
            }
        }
        return out;
    }

    private static volatile boolean tableLooked;
    private static Object table;
    private static java.lang.reflect.Method toLayer;

    /**
     * The layer's text for a state Paper sent. A vanilla state arrives as Paper's 26.3 text and goes through
     * the M3.2 drift table ({@code dev.cim.bridge.StateMapper}, the bootstrap's one table for this, reached by
     * reflection in the layer's class space: the bootstrap jar is there, Paper's plugin loader is not). The
     * table leaves other namespaces as they are, so a modded state passes unchanged. Without the table the
     * text is returned as sent.
     */
    static String layerState(String sent) {
        if (!tableLooked) {
            synchronized (Neighbour.class) {
                if (!tableLooked) {
                    try {
                        Class<?> mapper = Class.forName("dev.cim.bridge.StateMapper", true,
                                Neighbour.class.getClassLoader());
                        table = mapper.getMethod("get").invoke(null);
                        toLayer = mapper.getMethod("kernelToLayerState", String.class);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        toLayer = null;
                    }
                    tableLooked = true;
                }
            }
        }
        if (toLayer == null || sent == null) {
            return sent;
        }
        try {
            String out = (String) toLayer.invoke(table, sent);
            return out == null ? sent : out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return sent;
        }
    }

    /** Self-test: no lines gives none, the lines come back in order, bad lines are skipped. Run with java. */
    public static void main(String[] args) {
        String[] old = "use-on\tu\tn\t1\t2\t3\tup\t-\t0".split("\t");
        check(parse(old).isEmpty(), "old line has no neighbours");
        String[] full = ("use-on\tu\tn\t1\t2\t3\tup\t-\t0\n"
                + "nb\t1\t1\t3\tminecraft:grass_block[snowy=false]\n"
                + "nb\t0\t1\t3\tminecraft:iron_chain[axis=y]\n"
                + "nb\tbad\t1\t3\tminecraft:stone\n"
                + "nb\t2\t2\t2\t\n"
                + "nb\t-1\t0\t4\tcimtest:test_block[foo=bar]\n"
                + "inv\t0\tminecraft:paper").split("\n");
        List<Neighbour> got = parse(full);
        check(got.size() == 3, "three valid lines, got " + got.size());
        check(got.get(0).equals(new Neighbour(1, 1, 3, "minecraft:grass_block[snowy=false]")), "first");
        check(got.get(1).equals(new Neighbour(0, 1, 3, "minecraft:iron_chain[axis=y]")), "second");
        check(got.get(2).equals(new Neighbour(-1, 0, 4, "cimtest:test_block[foo=bar]")), "modded state kept");
        check(parse(null).isEmpty(), "null lines");
        check("minecraft:stone".equals(layerState("minecraft:stone")), "no table: text as sent");
        check("cimtest:x[a=b]".equals(layerState("cimtest:x[a=b]")), "modded text as sent");
        System.out.println("Neighbour selftest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("Neighbour selftest failed: " + what);
        }
    }
}
