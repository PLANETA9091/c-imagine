package dev.cim.hud;

import java.util.function.Predicate;

/**
 * Pure rules for the display family's migration and reverse safety (S3b), selftested without a server.
 *
 * <p>A block that moved from a note carrier to the display family keeps its placed state text
 * ({@code bs_x_y_z}); the mod block id is the text before the {@code [} of that state. A note block standing
 * where such a block was placed converts to a barrier with its per-state display. A barrier with a known placed
 * state but no display key (the key was lost) gets its display back. Anything else is left alone.
 */
final class StateMigration {

    /** What a chunk-load pass does to one placed cell. */
    enum Action {
        /** Nothing: not a layer-placed display block, or already consistent. */
        NONE,
        /** A note carrier whose block is on the display family: barrier plus per-state display. */
        NOTE_TO_DISPLAY,
        /** A barrier with its placed state but no display key: spawn the display again. */
        RESPAWN_DISPLAY
    }

    private StateMigration() {
    }

    /** The mod block id of a placed state text ({@code ns:id[props]} to {@code ns:id}), or null when absent. */
    static String stateId(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        int bracket = state.indexOf('[');
        String id = (bracket < 0 ? state : state.substring(0, bracket)).trim();
        return id.isEmpty() ? null : id;
    }

    /**
     * The migration decision for one cell of a loaded chunk.
     *
     * @param noteBlock      the cell is a note block (a note carrier)
     * @param barrier        the cell is a barrier
     * @param hasDisplayKey  the chunk keeps a display key for the cell
     * @param placedState    the cell's placed state text ({@code bs_x_y_z}), or null
     * @param knownDisplay   true for a mod id on the display family in this build
     */
    static Action decide(boolean noteBlock, boolean barrier, boolean hasDisplayKey, String placedState,
                         Predicate<String> knownDisplay) {
        String id = stateId(placedState);
        if (id == null || !knownDisplay.test(id)) {
            return Action.NONE;
        }
        if (noteBlock) {
            return Action.NOTE_TO_DISPLAY;
        }
        if (barrier && !hasDisplayKey) {
            return Action.RESPAWN_DISPLAY;
        }
        return Action.NONE;
    }

    // ---------------------------------------------------------------- selftest

    public static void main(String[] args) {
        int bad = 0;
        bad += check("ns:a".equals(stateId("ns:a[facing=north,lit=true]")), "the id is the text before the bracket");
        bad += check("ns:a".equals(stateId("ns:a")), "a bare id is its own id");
        bad += check(stateId(null) == null && stateId(" ") == null && stateId("[x=1]") == null,
                "no text, a blank or a bracket-only text has no id");
        Predicate<String> known = id -> id.startsWith("ns:disp");
        bad += check(decide(true, false, false, "ns:disp[a=1]", known) == Action.NOTE_TO_DISPLAY,
                "a note carrier of a display block converts");
        bad += check(decide(true, false, false, null, known) == Action.NONE,
                "a note carrier with no placed state is left alone (no id to convert to)");
        bad += check(decide(true, false, false, "ns:note[a=1]", known) == Action.NONE,
                "a note carrier of a block that is not on the display family stays a note carrier");
        bad += check(decide(false, true, false, "ns:disp", known) == Action.RESPAWN_DISPLAY,
                "a barrier with a known placed state and no display key gets its display back");
        bad += check(decide(false, true, true, "ns:disp", known) == Action.NONE,
                "a barrier that has its display key is left alone (idempotent)");
        bad += check(decide(false, true, false, null, known) == Action.NONE,
                "a plain barrier with no placed state is not a layer block: left alone");
        bad += check(decide(false, false, false, "ns:disp", known) == Action.NONE,
                "a cell that is neither a note nor a barrier is left alone");
        System.out.println(bad == 0 ? "StateMigration selftest: ALL PASS" : "StateMigration selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    private static int check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
        }
        return ok ? 0 : 1;
    }
}
