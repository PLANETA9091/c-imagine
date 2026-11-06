package dev.cim.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One player's virtual keyboard: what is held, what a click means, and which
 * mod bindings a key drives. Free of Bukkit so it tests without a server.
 *
 * <p>A dialog button has no key-up, so a click is a tap unless the key latches.
 * Modifiers always latch, like sticky keys. Everything else latches only while
 * hold mode is on, for bindings a mod reads as "held".
 */
public final class KeyboardState {

    public static final int MOD_SHIFT = 1;
    public static final int MOD_CONTROL = 2;
    public static final int MOD_ALT = 4;

    private static final Map<Integer, Integer> MODIFIERS = Map.of(
            340, MOD_SHIFT, 344, MOD_SHIFT,
            341, MOD_CONTROL, 345, MOD_CONTROL,
            342, MOD_ALT, 346, MOD_ALT);

    /** What the server should deliver for one click. */
    public record Transition(int key, boolean down, int modifiers, List<KeyCensus.ModKey> bindings) {
    }

    private final List<KeyCensus.ModKey> census;
    private final Set<Integer> held = new TreeSet<>();
    private boolean holdMode;

    public KeyboardState(List<KeyCensus.ModKey> census) {
        this.census = census;
    }

    public boolean isHeld(int key) {
        return held.contains(key);
    }

    public Set<Integer> heldKeys() {
        return held;
    }

    public boolean holdMode() {
        return holdMode;
    }

    public void toggleHoldMode() {
        holdMode = !holdMode;
    }

    public int modifiers() {
        int mask = 0;
        for (int key : held) {
            mask |= MODIFIERS.getOrDefault(key, 0);
        }
        return mask;
    }

    public List<KeyCensus.ModKey> bindingsFor(int key) {
        return census.stream().filter(k -> k.defaultKey() == key).toList();
    }

    /** Applies a click and returns the transitions in order. */
    public List<Transition> click(int key) {
        List<Transition> out = new ArrayList<>(2);
        if (held.remove(key)) {
            out.add(transition(key, false));
        } else if (MODIFIERS.containsKey(key) || holdMode) {
            held.add(key);
            out.add(transition(key, true));
        } else {
            out.add(transition(key, true));
            out.add(transition(key, false));
        }
        return out;
    }

    /** Lets go of everything; used on quit so no key stays down forever. */
    public List<Transition> releaseAll() {
        List<Transition> out = new ArrayList<>(held.size());
        for (int key : List.copyOf(held)) {
            held.remove(key);
            out.add(transition(key, false));
        }
        return out;
    }

    private Transition transition(int key, boolean down) {
        return new Transition(key, down, modifiers(), bindingsFor(key));
    }

    /** One keyboard cell: a printed label and a GLFW code. */
    public record Cell(String label, int key) {
    }

    /** Fifteen columns; the two negative codes are the page and hold controls. */
    public static final int CELL_HOLD = -2;
    public static final int CELL_PAGE = -3;
    public static final int COLUMNS = 15;

    public static final List<Cell> LAYOUT = List.of(
            c("Esc", 256), c("F1", 290), c("F2", 291), c("F3", 292), c("F4", 293), c("F5", 294), c("F6", 295),
            c("F7", 296), c("F8", 297), c("F9", 298), c("F10", 299), c("F11", 300), c("F12", 301), c("Ins", 260), c("Del", 261),
            c("`", 96), c("1", 49), c("2", 50), c("3", 51), c("4", 52), c("5", 53), c("6", 54),
            c("7", 55), c("8", 56), c("9", 57), c("0", 48), c("-", 45), c("=", 61), c("Bsp", 259), c("Hom", 268),
            c("Tab", 258), c("Q", 81), c("W", 87), c("E", 69), c("R", 82), c("T", 84), c("Y", 89),
            c("U", 85), c("I", 73), c("O", 79), c("P", 80), c("[", 91), c("]", 93), c("\\", 92), c("End", 269),
            c("Cap", 280), c("A", 65), c("S", 83), c("D", 68), c("F", 70), c("G", 71), c("H", 72),
            c("J", 74), c("K", 75), c("L", 76), c(";", 59), c("'", 39), c("Ent", 257), c("PgU", 266), c("PgD", 267),
            c("LSh", 340), c("Z", 90), c("X", 88), c("C", 67), c("V", 86), c("B", 66), c("N", 78),
            c("M", 77), c(",", 44), c(".", 46), c("/", 47), c("RSh", 344), c("Up", 265), c("Hold", CELL_HOLD), c("Mod", CELL_PAGE),
            c("LCt", 341), c("LAl", 342), c("Spc", 32), c("RAl", 346), c("RCt", 345), c("Lft", 263), c("Dwn", 264),
            c("Rgt", 262), c("Prt", 283), c("Pau", 284), c("Scr", 281), c("NLk", 282), c("N*", 332), c("N-", 333), c("N+", 334));

    private static Cell c(String label, int key) {
        return new Cell(label, key);
    }

    /** The printed name of a GLFW code, or "Unbound". */
    public static String keyName(int key) {
        return LAYOUT.stream().filter(c -> c.key() == key).map(Cell::label).findFirst().orElse("Unbound");
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code KeyboardState --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        KeyCensus.ModKey alt = new KeyCensus.ModKey("create", "create.keyinfo.toolbelt", "Toolbelt", "", 342);
        KeyCensus.ModKey dash = new KeyCensus.ModKey("fix", "key.fix.dash", "Dash", "", 82);
        KeyCensus.ModKey none = new KeyCensus.ModKey("fix", "key.fix.none", "None", "", -1);
        KeyboardState s = new KeyboardState(List.of(alt, dash, none));

        List<Transition> tap = s.click(82);
        bad += check(tap.size() == 2 && tap.get(0).down() && !tap.get(1).down(), "plain key taps down then up");
        bad += check(tap.get(0).bindings().equals(List.of(dash)), "tap reaches the mod binding on that key");
        bad += check(!s.isHeld(82), "a tap leaves nothing held");

        List<Transition> shift = s.click(340);
        bad += check(shift.size() == 1 && shift.get(0).down() && s.isHeld(340), "modifier latches");
        List<Transition> shifted = s.click(65);
        bad += check(shifted.get(0).modifiers() == MOD_SHIFT, "latched shift rides the next key");
        List<Transition> un = s.click(340);
        bad += check(un.size() == 1 && !un.get(0).down() && !s.isHeld(340), "second click releases");
        bad += check(un.get(0).modifiers() == 0, "the release reports the mask after letting go");

        bad += check(s.click(342).get(0).bindings().equals(List.of(alt)), "latched alt drives Create's binding");
        s.toggleHoldMode();
        bad += check(s.click(82).size() == 1 && s.isHeld(82), "hold mode latches a plain key");
        s.toggleHoldMode();
        bad += check(s.click(82).size() == 1 && !s.isHeld(82), "held key still releases with hold mode off");

        s.click(340);
        List<Transition> quit = s.releaseAll();
        bad += check(quit.size() == 2 && quit.stream().noneMatch(Transition::down), "quit releases everything");
        bad += check(s.modifiers() == 0, "nothing held after quit");
        bad += check(s.bindingsFor(-1).contains(none) && LAYOUT.stream().noneMatch(c -> c.key() == -1),
                "unbound bindings have no key on the board");

        bad += check(LAYOUT.stream().allMatch(c -> HudText.width(c.label()) <= 21),
                "every label fits a 25-wide button");
        bad += check(LAYOUT.size() % COLUMNS == 0, "layout fills whole rows: " + LAYOUT.size());
        bad += check(LAYOUT.stream().map(Cell::key).distinct().count() == LAYOUT.size(), "no key printed twice");
        for (int key : List.of(342, 340, 341, 87, 82)) {
            bad += check(!keyName(key).equals("Unbound"), "default of a real binding is on the board: " + key);
        }
        System.out.println(bad == 0 ? "KeyboardState selftest: ALL PASS" : "KeyboardState selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
