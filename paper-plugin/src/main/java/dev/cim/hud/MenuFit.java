package dev.cim.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fits a mod menu onto a vanilla chest screen.
 *
 * <p>A server cannot move slots on a vanilla client: a chest's cells sit at
 * {@code (8 + 18c, 18 + 18r)} and the player inventory directly below. So the
 * art moves instead. The mod panel shifts as a whole until the slots land on
 * cells, the inventory panel goes where vanilla puts it, and a slot that still
 * misses takes the nearest free cell with its art repainted there. What could
 * not be matched is kept in the result, so a menu's error is reported rather
 * than hidden.
 */
public final class MenuFit {

    public static final int WIDTH = 176;
    public static final int COLUMNS = 9;
    private static final int INV_PANEL_SLOT_X = 8;
    private static final int INV_PANEL_SLOT_Y = 17;

    /**
     * @param rows        chest rows
     * @param shiftX      added to the mod panel's x
     * @param shiftY      added to the mod panel's y
     * @param cells       chest cell for each mod slot, in slot order
     * @param slotError   total pixels the slots moved beyond the panel shift
     * @param panelError  pixels the two panels moved relative to each other
     */
    public record Fit(int rows, int shiftX, int shiftY, int[] cells, int slotError, int panelError) {
        public int height() {
            return chestHeight(rows);
        }

        public int invPanelY() {
            return 14 + 18 * rows;
        }
    }

    private MenuFit() {
    }

    public static int cellX(int cell) {
        return 8 + 18 * (cell % COLUMNS);
    }

    public static int cellY(int cell) {
        return 18 + 18 * (cell / COLUMNS);
    }

    public static int chestHeight(int rows) {
        return 114 + 18 * rows;
    }

    public static Fit fit(MenuCatalog.Menu menu) {
        int deltaX = menu.invX() - INV_PANEL_SLOT_X;
        int deltaY = menu.invY() - INV_PANEL_SLOT_Y;
        Fit best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int rows = 1; rows <= 6; rows++) {
            if (rows * COLUMNS < menu.slots().size()) {
                continue;
            }
            for (int shiftX : candidates(menu, true)) {
                for (int shiftY : candidates(menu, false)) {
                    int[] cells = new int[menu.slots().size()];
                    int slotError = assign(menu.slots(), shiftX, shiftY, rows, cells);
                    if (slotError < 0) {
                        continue;
                    }
                    int invY = 14 + 18 * rows;
                    // The inventory panel sits at x = 0 here, deltaX from the mod panel there.
                    int panelError = Math.abs(-shiftX - deltaX) + Math.abs(invY - shiftY - deltaY);
                    // A moved slot breaks the picture more than a moved panel does.
                    int cost = slotError * 3 + panelError + rows;
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = new Fit(rows, shiftX, shiftY, cells, slotError, panelError);
                    }
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("no chest fits " + menu.id());
        }
        return best;
    }

    /** Shifts that put at least one slot exactly on a cell, plus no shift for slotless menus. */
    private static int[] candidates(MenuCatalog.Menu menu, boolean horizontal) {
        List<Integer> out = new ArrayList<>();
        for (MenuCatalog.Slot s : menu.slots()) {
            int at = horizontal ? s.x() : s.y();
            int count = horizontal ? COLUMNS : 6;
            for (int i = 0; i < count; i++) {
                int cell = horizontal ? 8 + 18 * i : 18 + 18 * i;
                out.add(cell - at);
            }
        }
        if (out.isEmpty()) {
            out.add(0);
        }
        return out.stream().mapToInt(Integer::intValue).distinct().toArray();
    }

    private static int assign(List<MenuCatalog.Slot> slots, int shiftX, int shiftY, int rows, int[] cells) {
        boolean[] used = new boolean[rows * COLUMNS];
        Arrays.fill(cells, -1);
        int error = 0;
        for (int i = 0; i < slots.size(); i++) {
            int x = slots.get(i).x() + shiftX;
            int y = slots.get(i).y() + shiftY;
            int found = -1;
            int nearest = Integer.MAX_VALUE;
            for (int cell = 0; cell < used.length; cell++) {
                int d = Math.abs(cellX(cell) - x) + Math.abs(cellY(cell) - y);
                if (!used[cell] && d < nearest) {
                    nearest = d;
                    found = cell;
                }
            }
            if (found < 0) {
                return -1;
            }
            used[found] = true;
            cells[i] = found;
            error += nearest;
        }
        return error;
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code MenuFit --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        for (MenuCatalog.Menu m : MenuCatalog.MENUS) {
            Fit f = fit(m);
            System.out.printf("     %-26s rows=%d shift=(%d,%d) slotError=%d panelError=%d%n",
                    m.id(), f.rows(), f.shiftX(), f.shiftY(), f.slotError(), f.panelError());
            bad += check(Arrays.stream(f.cells()).distinct().count() == m.slots().size(),
                    m.id() + ": no two slots share a cell");
            bad += check(Arrays.stream(f.cells()).allMatch(c -> c >= 0 && c < f.rows() * COLUMNS),
                    m.id() + ": every cell exists");
        }
        Fit filter = fit(MenuCatalog.find("create:filter"));
        bad += check(filter.slotError() == 0, "filter: the 9x2 grid lands exactly");
        Fit table = fit(MenuCatalog.find("create:schematic_table"));
        bad += check(table.slotError() <= 9, "schematic table: two distant slots stay close");
        bad += check(cellX(0) == 8 && cellY(0) == 18 && cellX(10) == 26 && cellY(10) == 36,
                "cell geometry matches the vanilla chest");
        bad += check(chestHeight(6) == 222 && chestHeight(3) == 168, "chest height matches vanilla");
        System.out.println(bad == 0 ? "MenuFit selftest: ALL PASS" : "MenuFit selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
