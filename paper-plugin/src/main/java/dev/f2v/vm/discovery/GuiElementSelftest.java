package dev.f2v.vm.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Oracle for GuiGameElement layers: runs the GUI scanner over Create's own classes and checks the
 * toolbox (renderToolbox seeded from renderBg's leftPos + imageWidth + 50) and the schematicannon
 * (leftPos + BG_TOP.width, topPos + BG_TOP.height + BG_BOTTOM.height - 48, scale 5). The jar is
 * taken from argument 1 or the CIM_CREATE_JAR variable; without either the check is skipped.
 */
public final class GuiElementSelftest {

    private GuiElementSelftest() {
    }

    public static void main(String[] args) throws IOException {
        String jar = args.length > 0 ? args[0] : System.getenv("CIM_CREATE_JAR");
        if (jar == null || !Files.isRegularFile(Path.of(jar))) {
            System.out.println("GuiElementSelftest: SKIP (no Create jar given)");
            System.exit(0);
        }
        Map<String, ClassNode> nodes = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(e).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
                if (node.name != null) nodes.put(node.name, node);
            }
        }
        GuiSpriteBytecodeScanner.Result result = GuiSpriteBytecodeScanner.scan(nodes, "create");
        int bad = 0;
        bad += check(result.layers.stream().anyMatch(l -> l.guiElement && l.elementItem == null
                && l.screenClass.endsWith("/ToolboxScreen") && l.x == 238 && l.y == 183 && l.scale == 50),
                "ToolboxScreen: dynamic toolbox element at (238,183), scale 50");
        bad += check(result.layers.stream().anyMatch(l -> l.guiElement && l.elementItem == null
                && l.screenClass.endsWith("/SchematicannonScreen") && l.x == 213 && l.y == 93 && l.scale == 5),
                "SchematicannonScreen: dynamic cannon element at (213,93), scale 5");
        // Oracles hand-derived from the bytecode and Create's sprite sizes (AllGuiTextures w x h, read
        // from its static init): the element sits at leftPos + w + k, topPos + h - k, where w/h are the
        // screen's background sprite and k the constant in renderAdditional/renderBg.
        bad += check(has(result, "SequencedGearshiftScreen", null, 179, 105, 5),
                "SequencedGearshiftScreen: SEQUENCER 173x161 -> (173+6, 161-56) at scale 5");
        bad += check(has(result, "SchematicTableScreen", null, 214, 45, 3),
                "SchematicTableScreen: SCHEMATIC_TABLE 214x85 -> (214, 85-40) at scale 3");
        bad += check(has(result, "SchematicEditScreen", "create:schematic", 198, 83, 3),
                "SchematicEditScreen: SCHEMATIC 192x123 -> (192+6, 123-40), item create:schematic, scale 3");
        bad += check(has(result, "LinkedControllerScreen", null, 175, 53, 5),
                "LinkedControllerScreen: LINKED_CONTROLLER 179x109 -> (179-4, 109-56) at scale 5");
        bad += check(has(result, "PackagePortScreen", null, 226, 26, 4),
                "PackagePortScreen: FROGPORT_BG 220x82 -> (220+6, 82-56) at scale 4");
        bad += check(has(result, "ElevatorContactScreen", "create:elevator_contact", 239, 26, 5),
                "ElevatorContactScreen: ELEVATOR_CONTACT 233x82 -> (233+6, 82-56), id create:elevator_contact");
        bad += check(has(result, "StockKeeperCategoryScreen", "create:stock_ticker", 204, 92, 3),
                "StockKeeperCategoryScreen: STOCK_KEEPER_CATEGORY 192x20 -> (192+12, imageHeight-39)");
        bad += check(has(result, "FilterScreen", null, 222, 47, 4),
                "FilterScreen (inherits AbstractFilterScreen): FILTER 214x99 -> (214+8, 99-52) at scale 4");
        bad += check(has(result, "AttributeFilterScreen", null, 249, 33, 4),
                "AttributeFilterScreen: ATTRIBUTE_FILTER 241x85 -> (241+8, 85-52) at scale 4");
        bad += check(has(result, "PackageFilterScreen", null, 226, 27, 4),
                "PackageFilterScreen: PACKAGE_FILTER 218x79 -> (218+8, 79-52) at scale 4");
        bad += check(result.layers.stream().noneMatch(l -> l.guiElement && l.elementItem != null
                && l.elementItem.endsWith(":_")),
                "no GuiGameElement subject keeps the placeholder id create:_");
        bad += check(result.layers.stream().anyMatch(l -> l.guiElement && l.elementItem == null
                && l.screenClass.endsWith("/StationScreen") && l.x >= 0 && l.scale == 40),
                "StationScreen (subclass of AbstractStationScreen): its 40-scale element resolves");
        System.out.println(bad == 0 ? "GuiElementSelftest: ALL PASS" : "GuiElementSelftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    /** A guiElement layer on the named screen (suffix match) with the given subject, position and scale. */
    private static boolean has(GuiSpriteBytecodeScanner.Result result, String screen, String item,
                               int x, int y, int scale) {
        return result.layers.stream().anyMatch(l -> l.guiElement && l.screenClass.endsWith("/" + screen)
                && java.util.Objects.equals(l.elementItem, item) && l.x == x && l.y == y && l.scale == scale);
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }
}
