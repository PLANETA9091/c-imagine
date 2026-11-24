package dev.cim.live;

/**
 * The player's side of a right click on a block, as Paper sends it on the first line of a
 * {@code use-on}: the exact hit point on the clicked face (absolute world coordinates), the
 * player's feet position, yaw and pitch. Vanilla's BlockPlaceContext reads all of these (a stair's
 * or slab's half from the hit's y, a door's hinge from the hit, the look for facing blocks and
 * shafts without neighbours), so a placement without them faces and halves wrongly.
 *
 * <p>Fields 9-16 of the line (after the sneaking flag) are: hitX hitY hitZ feetX feetY feetZ yaw pitch.
 * A line without them (an older Paper, or the layer's own tests) gives {@code null}, and the caller
 * keeps its previous behaviour. No Minecraft types here, so the parser runs under plain java.
 */
public record UseContext(double hitX, double hitY, double hitZ,
                         double feetX, double feetY, double feetZ,
                         float yaw, float pitch) {

    /** Number of tab-separated fields on the use-on line before the context fields begin. */
    public static final int FIELDS_BEFORE = 9;

    /** Parses the context from the split first line, or returns null when it carries none. */
    public static UseContext parse(String[] head) {
        if (head == null || head.length < FIELDS_BEFORE + 8) {
            return null;
        }
        int at = FIELDS_BEFORE;
        return new UseContext(
                Double.parseDouble(head[at]), Double.parseDouble(head[at + 1]), Double.parseDouble(head[at + 2]),
                Double.parseDouble(head[at + 3]), Double.parseDouble(head[at + 4]), Double.parseDouble(head[at + 5]),
                Float.parseFloat(head[at + 6]), Float.parseFloat(head[at + 7]));
    }

    /** Self-test: the old 9-field line gives null, the new one gives its values. Run with java. */
    public static void main(String[] args) {
        String[] old = "use-on\tu\tn\t1\t2\t3\tup\t-\t0".split("\t");
        check(parse(old) == null, "old line has no context");
        String[] full = "use-on\tu\tn\t1\t2\t3\teast\t-\t1\t1.25\t2.5\t3.75\t1.5\t2.0\t-3.5\t-90.0\t-22.5"
                .split("\t");
        UseContext c = parse(full);
        check(c != null, "new line parses");
        check(c.hitX() == 1.25 && c.hitY() == 2.5 && c.hitZ() == 3.75, "hit point");
        check(c.feetX() == 1.5 && c.feetY() == 2.0 && c.feetZ() == -3.5, "feet");
        check(c.yaw() == -90.0f && c.pitch() == -22.5f, "yaw and pitch");
        check(parse("use-on\tu\tn\t1\t2\t3\tup\t-\t0\t1".split("\t")) == null, "partial context is ignored");
        System.out.println("UseContext selftest OK");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("UseContext selftest failed: " + what);
        }
    }
}
