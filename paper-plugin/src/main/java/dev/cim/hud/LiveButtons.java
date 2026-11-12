package dev.cim.hud;

import dev.f2v.vm.discovery.ClientSurfaceScanner;
import dev.f2v.vm.discovery.ModScanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What each mod screen's buttons send, as F2V's ClientSurfaceScanner proves it from the
 * mod jars (no mod class is loaded): per button, the menu its screen belongs to, the
 * payload class and constructor, and every constructor argument. The live session builds
 * exactly that payload when the button is pressed and hands it to the mod's own handler.
 *
 * <p>One line per button: {@code menuClass TAB screenClass TAB ordinal TAB label TAB payload TAB
 * constructorDesc TAB arg...}, each argument a spec the layer reads (see cimlive's
 * LivePayloads). A button whose arguments are not all proven is left out.
 */
public final class LiveButtons {

    public record Button(String menuClass, String screenClass, int ordinal, String label, String payload,
                         String constructorDesc, List<String> args) {
        String line() {
            return String.join("\t", menuClass, screenClass, String.valueOf(ordinal),
                    label == null ? "" : label.replace('\t', ' '), payload, constructorDesc, String.join("\t", args));
        }
    }

    /** A widget whose look the scan ties to live server state, for the menus its screen serves. */
    public record Stateful(String menuClass, int ordinal, String label, String active, String visible) {
    }

    private LiveButtons() {
    }

    /** Every stateful widget, per concrete menu class of its screen. */
    public static List<Stateful> stateful(Path modsDir) throws IOException {
        List<Path> jars;
        try (Stream<Path> st = Files.list(modsDir)) {
            jars = st.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        List<Stateful> out = new ArrayList<>();
        for (Path jar : jars) {
            ModScanner.Profile profile;
            try {
                profile = ModScanner.scan(jar);
            } catch (Exception e) {
                continue;
            }
            Map<String, List<String>> menuOf = menus(profile);
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                for (ClientSurfaceScanner.Action a : surface.actions) {
                    if ((a.widgetActivePredicate == null && a.widgetVisiblePredicate == null) || a.sourceScreen == null) {
                        continue;
                    }
                    for (String menu : menuOf.getOrDefault(a.sourceScreen.replace('.', '/'), List.of())) {
                        out.add(new Stateful(menu, a.widgetOrdinal, a.widgetLabel,
                                a.widgetActivePredicate == null ? "" : a.widgetActivePredicate.toString(),
                                a.widgetVisiblePredicate == null ? "" : a.widgetVisiblePredicate.toString()));
                    }
                }
            }
        }
        return out;
    }

    private static Map<String, List<String>> menus(ModScanner.Profile profile) {
        Map<String, List<String>> menuOf = new HashMap<>();
        for (ModScanner.ScreenInfo i : profile.screens) {
            List<String> menus = new ArrayList<>();
            if (i.menuClass != null) {
                menus.add(i.menuClass.replace('.', '/'));
            }
            for (String m : i.menuClasses) {
                String slashed = m.replace('.', '/');
                if (!menus.contains(slashed)) {
                    menus.add(slashed);
                }
            }
            if (!menus.isEmpty()) {
                menuOf.put(i.screenClass.replace('.', '/'), menus);
            }
        }
        return menuOf;
    }

    public static List<Button> scan(Path modsDir) throws IOException {
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        List<Button> out = new ArrayList<>();
        for (Path jar : jars) {
            ModScanner.Profile profile;
            try {
                profile = ModScanner.scan(jar);
            } catch (Exception e) {
                continue;
            }
            Map<String, List<String>> menuOf = new HashMap<>();
            for (ModScanner.ScreenInfo i : profile.screens) {
                List<String> menus = new ArrayList<>();
                if (i.menuClass != null) {
                    menus.add(i.menuClass.replace('.', '/'));
                }
                for (String m : i.menuClasses) {
                    String slashed = m.replace('.', '/');
                    if (!menus.contains(slashed)) {
                        menus.add(slashed);
                    }
                }
                if (!menus.isEmpty()) {
                    menuOf.put(i.screenClass.replace('.', '/'), menus);
                }
            }
            inheritMenus(jar, menuOf);
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                for (ClientSurfaceScanner.Action a : surface.actions) {
                    if (a.type != ClientSurfaceScanner.ActionType.SEND_PACKET || a.target == null
                            || a.constructorDesc == null || a.sourceScreen == null) {
                        continue;
                    }
                    List<String> menus = menuOf.get(a.sourceScreen.replace('.', '/'));
                    List<String> args = specs(a);
                    if (menus == null || args == null) {
                        continue;
                    }
                    for (String menu : menus) {
                        out.add(new Button(menu, a.sourceScreen.replace('.', '/'), a.widgetOrdinal, a.widgetLabel,
                                a.target.replace('.', '/'), a.constructorDesc, args));
                    }
                }
            }
        }
        return out;
    }

    /** The proven arguments in constructor order, or null when one is not replayable. */
    /**
     * A button a superclass screen adds (Create's AbstractFilterScreen: the trash and the
     * confirm button of every filter) belongs to every menu its concrete screens show.
     */
    static void inheritMenus(Path jar, Map<String, List<String>> menuOf) {
        Map<String, String> superOf = new HashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            for (String screen : new ArrayList<>(menuOf.keySet())) {
                String current = screen;
                for (int depth = 0; depth < 8 && current != null; depth++) {
                    String parent = superOf.computeIfAbsent(current, c -> superName(zip, c));
                    if (parent == null || parent.isEmpty()) {
                        break;
                    }
                    List<String> into = menuOf.computeIfAbsent(parent, k -> new ArrayList<>());
                    for (String m : menuOf.get(screen)) {
                        if (!into.contains(m)) {
                            into.add(m);
                        }
                    }
                    current = parent;
                }
            }
        } catch (IOException e) {
            // no inheritance known: the concrete screens keep their own buttons
        }
    }

    private static String superName(java.util.zip.ZipFile zip, String internal) {
        java.util.zip.ZipEntry entry = zip.getEntry(internal + ".class");
        if (entry == null) {
            return "";
        }
        try (java.io.InputStream in = zip.getInputStream(entry)) {
            return new org.objectweb.asm.ClassReader(in.readAllBytes()).getSuperName();
        } catch (IOException e) {
            return "";
        }
    }

    static List<String> specs(ClientSurfaceScanner.Action a) {
        if (a.constructorDesc.startsWith("static:")) {
            return List.of();   // the singleton itself, nothing to build
        }
        int count = org.objectweb.asm.Type.getArgumentTypes(a.constructorDesc).length;
        String[] specs = new String[count];
        for (ClientSurfaceScanner.PacketArgument p : a.packetArguments) {
            if (p.index < 0 || p.index >= count || p.kind == null) {
                return null;
            }
            String spec = switch (p.kind) {
                case BOOLEAN_LITERAL -> p.booleanValue == null ? null : "bool:" + p.booleanValue;
                case INT_LITERAL -> p.intValue == null ? null : "int:" + p.intValue;
                case LONG_LITERAL -> p.longValue == null ? null : "long:" + p.longValue;
                case FLOAT_LITERAL -> p.doubleValue == null ? null : "float:" + p.doubleValue;
                case DOUBLE_LITERAL -> p.doubleValue == null ? null : "double:" + p.doubleValue;
                case STRING_LITERAL -> p.stringValue == null ? null : "string:" + p.stringValue;
                case UUID_LITERAL -> p.uuidValue == null ? null : "uuid:" + p.uuidValue;
                case STATIC_FIELD -> p.fieldOwner == null ? null : "static:" + p.fieldOwner + "." + p.fieldName;
                case SERVER_PLAYER -> "player";
                case SERVER_PLAYER_ENTITY_ID -> "player-id";
                case SERVER_PLAYER_UUID -> "player-uuid";
                case MENU_BLOCK_POS, MENU_ENTITY_UUID -> p.menuOwner == null ? null
                        : (p.menuMemberMethod ? "menu-method:" : "menu-field:") + p.menuOwner + "." + p.menuMemberName;
                case SCREEN_FIELD, SCREEN_FIELD_GETTER -> null;
            };
            if (spec == null) {
                return null;
            }
            specs[p.index] = spec;
        }
        for (String s : specs) {
            if (s == null) {
                return null;
            }
        }
        return List.of(specs);
    }

    /** Every widget whose look the scan ties to live server state: screen, ordinal, label, predicates. */
    public static List<String> statefulWidgets(Path modsDir) throws IOException {
        List<Path> jars;
        try (Stream<Path> st = Files.list(modsDir)) {
            jars = st.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        List<String> out = new ArrayList<>();
        for (Path jar : jars) {
            ModScanner.Profile profile;
            try {
                profile = ModScanner.scan(jar);
            } catch (Exception e) {
                continue;
            }
            for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                for (ClientSurfaceScanner.Action a : surface.actions) {
                    if (a.widgetActivePredicate != null || a.widgetVisiblePredicate != null) {
                        out.add(a.sourceScreen + "\t" + a.widgetOrdinal + "\t" + a.widgetLabel + "\tactive="
                                + a.widgetActivePredicate + "\tvisible=" + a.widgetVisiblePredicate);
                    }
                }
            }
        }
        return out;
    }

    /** {@code LiveButtons <modsDir> <out.tsv>}: the button table the live self-test presses. */
    public static void main(String[] args) throws IOException {
        if (args.length > 2 && args[2].equals("--stateful")) {
            List<String> rows = new ArrayList<>();
            for (Stateful w : stateful(Path.of(args[0]))) {
                rows.add(String.join("\t", w.menuClass(), String.valueOf(w.ordinal()),
                        w.label() == null ? "" : w.label().replace('\t', ' '), w.active(), w.visible()));
            }
            Files.write(Path.of(args[1]), rows, StandardCharsets.UTF_8);
            System.out.println("stateful widgets: " + rows.size());
            return;
        }
        List<Button> buttons = scan(Path.of(args[0]));
        List<String> lines = new ArrayList<>();
        for (Button b : buttons) {
            lines.add(b.line());
        }
        Files.write(Path.of(args[1]), lines, StandardCharsets.UTF_8);
        System.out.println("live buttons: " + buttons.size() + " proven payload buttons written to " + args[1]);
    }
}
