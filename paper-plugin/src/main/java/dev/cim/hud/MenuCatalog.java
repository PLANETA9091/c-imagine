package dev.cim.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometry of mod menus, in the mod screen's own coordinates.
 *
 * <p>A Screen is client code, so its layout cannot be asked for at runtime. The
 * numbers below are read from Create 6.0.10's menu and texture classes
 * (addSlots, AllGuiTextures). Menus whose screen is mostly widgets rather than
 * slots are absent on purpose; {@link #UNSUPPORTED} names them.
 */
public final class MenuCatalog {

    /** A rectangle of a mod texture drawn at (x, y). */
    public record Panel(String texture, int u, int v, int w, int h, int x, int y) {
    }

    public record Slot(int x, int y) {
    }

    /**
     * @param titleX title x from the panel's left edge, or {@link #CENTERED}
     * @param titleY title y from the panel's top edge
     * @param titleColor the colour the screen draws its title in
     * @param ghost clicks copy the cursor item instead of moving it, as Create's filters do
     * @param invX  x of the first player inventory slot
     * @param invY  y of the first player inventory slot
     */
    public record Menu(String id, String title, int titleX, int titleY, int titleColor, boolean ghost,
                       List<Panel> panels, List<Slot> slots, int invX, int invY) {
    }

    /** Title centred the way Create does it: on {@code (panelWidth - 8) / 2}. */
    public static final int CENTERED = -1;

    /** Create menus with no fit here, and why. */
    public static final List<String> UNSUPPORTED = List.of(
            "create:schematicannon: progress, options and checklist widgets have no vanilla form",
            "create:package_filter: address text box, no item slots",
            "create:schedule: scrolling card editor, widgets rather than slots",
            "create:stock_keeper_category: list editor, widgets rather than slots",
            "create:stock_keeper_request: live stock browser, widgets rather than slots",
            "create:redstone_requester: amount scrolling on each slot has no vanilla form",
            "create:factory_panel_set_item: needs live factory gauge state");

    public static final List<Menu> MENUS = List.of(
            new Menu("create:filter", "List Filter", CENTERED, 4, 0x303030, true,
                    List.of(new Panel("create:gui/filters", 0, 0, 214, 99, 0, 0)),
                    grid(23, 25, 9, 2, 18, 18), 38, 121),
            new Menu("create:attribute_filter", "Attribute Filter", CENTERED, 4, 0x592424, true,
                    List.of(new Panel("create:gui/filters", 0, 99, 241, 85, 0, 0)),
                    List.of(new Slot(16, 27), new Slot(16, 62)), 51, 107),
            new Menu("create:crafting_blueprint", "Recipe Blueprint", 15, 4, 0xFFFFFF, true,
                    List.of(new Panel("create:gui/curiosities_2", 0, 109, 179, 109, 0, 0)),
                    blueprint(), 8, 131),
            new Menu("create:linked_controller", "Linked Controller", 15, 4, 0x592424, true,
                    List.of(new Panel("create:gui/curiosities_2", 0, 0, 179, 109, 0, 0)),
                    controller(), 8, 131),
            new Menu("create:toolbox", "Toolbox", 15, 4, 0x592424, false,
                    List.of(new Panel("create:gui/toolbox", 0, 0, 188, 171, 0, 0)),
                    toolbox(), 8, 165),
            new Menu("create:schematic_table", "Schematic Table", CENTERED, 4, 0x505050, false,
                    List.of(new Panel("create:gui/schematics", 10, 139, 214, 85, 0, 0)),
                    List.of(new Slot(21, 59), new Slot(166, 59)), 38, 107),
            new Menu("create:package_port", "Package Port", CENTERED, 4, 0x3D3C48, false,
                    List.of(new Panel("create:gui/frogport_and_mailbox", 0, 0, 214, 17, 0, 0),
                            new Panel("create:gui/frogport_and_mailbox", 0, 47, 220, 82, 0, 17)),
                    grid(27, 9 + 17, 9, 2, 18, 18), 38, 125));

    private MenuCatalog() {
    }

    public static Menu find(String id) {
        return MENUS.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    private static List<Slot> grid(int x, int y, int cols, int rows, int dx, int dy) {
        List<Slot> out = new ArrayList<>(cols * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                out.add(new Slot(x + c * dx, y + r * dy));
            }
        }
        return out;
    }

    private static List<Slot> blueprint() {
        List<Slot> out = new ArrayList<>(grid(29, 21, 3, 3, 18, 18));
        out.add(new Slot(123, 40));
        out.add(new Slot(135, 57));
        return out;
    }

    /** Two rows, six columns 24 apart, with an extra 11 after the fourth. */
    private static List<Slot> controller() {
        List<Slot> out = new ArrayList<>(12);
        int x = 12;
        for (int col = 0; col < 6; col++) {
            out.add(new Slot(x, 34));
            out.add(new Slot(x, 52));
            x += col == 3 ? 35 : 24;
        }
        return out;
    }

    /** The eight compartments, on an octagon. */
    private static List<Slot> toolbox() {
        int x = 79;
        int y = 37;
        int[] dx = {0, 33, 66, 72, 66, 33, 0, -6};
        int[] dy = {-6, -6, 0, 33, 66, 72, 66, 33};
        List<Slot> out = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            out.add(new Slot(x + dx[i], y + dy[i]));
        }
        return out;
    }
}
