package dev.cim.hud;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where every keycap of the floating keyboard sits, in GUI pixels from the
 * screen centre. Pure, so drawing and clicking read the same list and the
 * geometry tests without a server.
 */
public final class KeyboardLayout {

    public static final int CLOSE = -4;
    public static final int NEXT = -5;
    /** Binding {@code i} has the action {@code BINDING - i}. */
    public static final int BINDING = -100;

    public static final int KEY_W = 25;
    public static final int WIDE_W = 190;
    public static final int CAP_H = 14;
    private static final int PITCH_X = KEY_W + 1;
    private static final int PITCH_Y = CAP_H + 1;
    private static final int LEFT = -(KeyboardState.COLUMNS * PITCH_X) / 2;
    private static final int TOP = -8;
    private static final int ROWS_Y = TOP + PITCH_Y + 1;
    private static final int WIDE_ROWS = 6;
    public static final int BINDINGS_PER_PAGE = WIDE_ROWS * 2;

    /** One keycap. {@code action} is a GLFW key, or one of the negative controls. */
    public record Cap(int x, int y, int w, String label, int action) {
        public boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + CAP_H;
        }
    }

    private KeyboardLayout() {
    }

    public static List<Cap> keys() {
        List<Cap> out = topBar(false, false);
        for (int i = 0; i < KeyboardState.LAYOUT.size(); i++) {
            KeyboardState.Cell c = KeyboardState.LAYOUT.get(i);
            out.add(new Cap(LEFT + (i % KeyboardState.COLUMNS) * PITCH_X,
                    ROWS_Y + (i / KeyboardState.COLUMNS) * PITCH_Y, KEY_W, c.label(), c.key()));
        }
        return out;
    }

    /** The mod bindings, a page at a time, because a modpack can register hundreds. */
    public static List<Cap> bindings(List<KeyCensus.ModKey> census, int page) {
        boolean more = census.size() > BINDINGS_PER_PAGE;
        List<Cap> out = topBar(true, more);
        int first = page * BINDINGS_PER_PAGE;
        for (int n = 0; n < BINDINGS_PER_PAGE && first + n < census.size(); n++) {
            KeyCensus.ModKey k = census.get(first + n);
            String label = fit(k.label() + " [" + KeyboardState.keyName(k.defaultKey()) + "]", WIDE_W - 6);
            out.add(new Cap(LEFT + (n % 2) * (WIDE_W + 3), ROWS_Y + (n / 2) * PITCH_Y, WIDE_W, label,
                    BINDING - (first + n)));
        }
        return out;
    }

    public static int pages(int bindings) {
        return Math.max(1, (bindings + BINDINGS_PER_PAGE - 1) / BINDINGS_PER_PAGE);
    }

    private static List<Cap> topBar(boolean onBindings, boolean more) {
        List<Cap> out = new ArrayList<>();
        int right = LEFT + (KeyboardState.COLUMNS - 1) * PITCH_X;
        out.add(new Cap(right, TOP, KEY_W, "X", CLOSE));
        if (onBindings) {
            out.add(new Cap(right - PITCH_X, TOP, KEY_W, "Key", KeyboardState.CELL_PAGE));
            if (more) {
                out.add(new Cap(right - 2 * PITCH_X, TOP, KEY_W, ">", NEXT));
            }
        }
        return out;
    }

    private static String fit(String label, int width) {
        String s = HudText.sanitize(label);
        while (HudText.width(s) > width) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static Cap at(List<Cap> caps, int x, int y) {
        for (Cap c : caps) {
            if (c.contains(x, y)) {
                return c;
            }
        }
        return null;
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private static boolean overlap(List<Cap> caps) {
        for (int i = 0; i < caps.size(); i++) {
            for (int j = i + 1; j < caps.size(); j++) {
                Cap a = caps.get(i);
                Cap b = caps.get(j);
                if (a.x() < b.x() + b.w() && b.x() < a.x() + a.w()
                        && a.y() < b.y() + CAP_H && b.y() < a.y() + CAP_H) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code KeyboardLayout --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        List<Cap> keys = keys();
        bad += check(!overlap(keys), "no two keys overlap");
        Set<Integer> actions = new HashSet<>();
        keys.forEach(c -> actions.add(c.action()));
        bad += check(actions.size() == keys.size(), "every cap does its own thing");
        bad += check(actions.contains(CLOSE), "the keyboard has a close key");
        // 1280x720 at GUI scale 3 is 426x240, the smallest common screen.
        bad += check(keys.stream().allMatch(c -> c.x() >= -213 && c.x() + c.w() <= 213), "every key fits 426 wide");
        bad += check(keys.stream().allMatch(c -> c.y() + CAP_H <= 98), "every key clears the hotbar at 240 tall");
        bad += check(keys.stream().allMatch(c -> at(keys, c.x() + c.w() / 2, c.y() + CAP_H / 2) == c),
                "the centre of every key hits that key");
        bad += check(at(keys, -300, 0) == null, "a miss hits nothing");
        List<KeyCensus.ModKey> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new KeyCensus.ModKey("m", "key.m." + i, "Some very long binding name number " + i
                    + " that cannot fit on one key at all", "", 82));
        }
        bad += check(pages(30) == 3 && pages(0) == 1 && pages(12) == 1, "bindings page by twelve");
        Set<Integer> seen = new HashSet<>();
        for (int p = 0; p < pages(30); p++) {
            List<Cap> caps = bindings(many, p);
            bad += check(!overlap(caps), "binding page " + p + " has no overlap");
            bad += check(caps.stream().allMatch(c -> HudText.width(c.label()) <= c.w()), "labels fit their caps");
            caps.stream().filter(c -> c.action() <= BINDING).forEach(c -> seen.add(BINDING - c.action()));
        }
        bad += check(seen.size() == 30, "every binding appears on a page");
        bad += check(bindings(many, 0).stream().anyMatch(c -> c.action() == NEXT), "many bindings get a next key");
        bad += check(bindings(List.of(), 0).stream().noneMatch(c -> c.action() == NEXT), "few bindings do not");
        System.out.println(bad == 0 ? "KeyboardLayout selftest: ALL PASS" : "KeyboardLayout selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
