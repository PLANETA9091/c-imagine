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
        System.out.println(bad == 0 ? "GuiElementSelftest: ALL PASS" : "GuiElementSelftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }
}
