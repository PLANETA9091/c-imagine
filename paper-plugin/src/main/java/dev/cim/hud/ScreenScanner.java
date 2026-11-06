package dev.cim.hud;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Finds every Screen a mod ships and what it draws, from bytecode alone.
 *
 * <p>No client code runs. A Screen is found by walking superclasses to
 * {@code net/minecraft/client/gui/screens/Screen}. Its menu comes from the
 * generic signature of {@code AbstractContainerScreen<M>}. Its background comes
 * either from a {@code textures/gui} string in the class, or from an enum of
 * texture rectangles it reads with getstatic, which is how Create does it.
 */
public final class ScreenScanner {

    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String MENU_BASE = "net/minecraft/world/inventory/AbstractContainerMenu";
    private static final Pattern TYPE_ARG = Pattern.compile("[<;]L([^;<>]+)[;<]");
    /** A rectangle this many screens read is shared chrome, like the inventory panel. */
    private static final int SHARED_RECT_SCREENS = 3;
    private static final Pattern TEXTURE = Pattern.compile("(?:([a-z0-9_.-]+):)?(textures/gui/[a-z0-9_/.-]+)");

    /** A rectangle of a texture; {@code u, v} default to 0 in enums that omit them. */
    public record Rect(String texture, int u, int v, int w, int h) {
    }

    /**
     * @param background the largest rectangle the screen references, or null
     * @param menu       bound menu class, null for a client-only screen
     */
    public record ScreenInfo(String className, String menu, Rect background, List<String> textures) {
    }

    private record ClassData(String name, String superName, String signature, List<Object> tokens,
                             Set<String> enumReads) {
    }

    private ScreenScanner() {
    }

    public static List<ScreenInfo> scanJar(byte[] jar) throws IOException {
        Map<String, ClassData> classes = new HashMap<>();
        read(jar, classes, 0);
        Map<String, Rect> rects = new HashMap<>();
        for (ClassData c : classes.values()) {
            collectEnumRects(c, rects);
        }
        Map<String, Integer> readers = new HashMap<>();
        for (ClassData c : classes.values()) {
            if (isScreen(c, classes)) {
                c.enumReads().forEach(read -> readers.merge(read, 1, Integer::sum));
            }
        }
        List<ScreenInfo> out = new ArrayList<>();
        for (ClassData c : classes.values()) {
            if (isScreen(c, classes)) {
                out.add(describe(c, classes, rects, readers));
            }
        }
        out.sort(Comparator.comparing(ScreenInfo::className));
        return out;
    }

    private static void read(byte[] jar, Map<String, ClassData> classes, int depth) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.endsWith(".class")) {
                    ClassData c = parse(zip.readAllBytes());
                    classes.put(c.name(), c);
                } else if (depth < 2 && name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    read(zip.readAllBytes(), classes, depth + 1);
                }
            }
        }
    }

    private static boolean isScreen(ClassData c, Map<String, ClassData> classes) {
        Set<String> seen = new HashSet<>();
        String at = c.superName();
        while (at != null && seen.add(at)) {
            if (at.equals(SCREEN) || at.equals("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen")) {
                return true;
            }
            ClassData next = classes.get(at);
            at = next == null ? null : next.superName();
        }
        return false;
    }

    private static boolean isMenu(String name, Map<String, ClassData> classes) {
        Set<String> seen = new HashSet<>();
        for (String at = name; at != null && seen.add(at); ) {
            if (at.equals(MENU_BASE)) {
                return true;
            }
            ClassData next = classes.get(at);
            at = next == null ? null : next.superName();
        }
        return false;
    }

    private static ScreenInfo describe(ClassData c, Map<String, ClassData> classes, Map<String, Rect> rects,
                                       Map<String, Integer> readers) {
        String menu = null;
        List<String> textures = new ArrayList<>();
        Rect best = null;
        Set<String> seen = new HashSet<>();
        for (ClassData at = c; at != null && seen.add(at.name()); at = classes.get(at.superName())) {
            if (menu == null && at.signature() != null) {
                Matcher m = TYPE_ARG.matcher(at.signature());
                while (menu == null && m.find()) {
                    if (isMenu(m.group(1), classes)) {
                        menu = m.group(1);
                    }
                }
            }
            for (Object t : at.tokens()) {
                if (t instanceof String s) {
                    Matcher m = TEXTURE.matcher(s);
                    if (m.find()) {
                        textures.add((m.group(1) == null ? "" : m.group(1) + ":") + m.group(2));
                    }
                }
            }
            for (String read : at.enumReads()) {
                Rect r = rects.get(read);
                if (r != null && readers.getOrDefault(read, 0) < SHARED_RECT_SCREENS
                        && (best == null || r.w() * r.h() > best.w() * best.h())) {
                    best = r;
                }
            }
            if (best != null) {
                break;
            }
        }
        return new ScreenInfo(c.name(), menu, best, textures);
    }

    /**
     * An enum constant holding a texture rectangle: its name, ordinal, then a
     * texture string, then (u, v,) w, h as the next two or four ints.
     */
    private static void collectEnumRects(ClassData c, Map<String, Rect> rects) {
        List<Object> t = c.tokens();
        for (int i = 0; i + 3 < t.size(); i++) {
            if (!(t.get(i) instanceof String constant) || !constant.matches("[A-Z][A-Z0-9_]*")
                    || !(t.get(i + 1) instanceof Integer)) {
                continue;
            }
            int at = i + 2;
            String texture = null;
            while (at < t.size() && t.get(at) instanceof String s && !s.matches("[A-Z][A-Z0-9_]*")) {
                texture = s;
                at++;
            }
            if (texture == null) {
                continue;
            }
            List<Integer> ints = new ArrayList<>(4);
            while (at < t.size() && ints.size() < 4 && t.get(at) instanceof Integer v) {
                ints.add(v);
                at++;
            }
            if (ints.size() == 4) {
                rects.put(c.name() + "." + constant, new Rect(texture, ints.get(0), ints.get(1), ints.get(2), ints.get(3)));
            } else if (ints.size() == 2) {
                rects.put(c.name() + "." + constant, new Rect(texture, 0, 0, ints.get(0), ints.get(1)));
            }
        }
    }

    private static ClassData parse(byte[] cls) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
        in.readInt();
        in.readInt();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[] ref = new int[count];
        int[] second = new int[count];
        int[] tag = new int[count];
        for (int i = 1; i < count; i++) {
            tag[i] = in.readUnsignedByte();
            switch (tag[i]) {
                case 1 -> pool[i] = in.readUTF();
                case 3 -> pool[i] = in.readInt();
                case 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7, 8, 16, 19, 20 -> ref[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> {
                    ref[i] = in.readUnsignedShort();
                    second[i] = in.readUnsignedShort();
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("bad constant tag " + tag[i]);
            }
        }
        in.readUnsignedShort();
        String name = (String) pool[ref[in.readUnsignedShort()]];
        int superIndex = in.readUnsignedShort();
        String superName = superIndex == 0 ? null : (String) pool[ref[superIndex]];
        in.skipBytes(in.readUnsignedShort() * 2);
        List<Object> tokens = new ArrayList<>();
        Set<String> enumReads = new HashSet<>();
        for (int section = 0; section < 2; section++) {
            int members = in.readUnsignedShort();
            for (int m = 0; m < members; m++) {
                in.skipBytes(6);
                int attrs = in.readUnsignedShort();
                for (int a = 0; a < attrs; a++) {
                    Object attr = pool[in.readUnsignedShort()];
                    byte[] body = in.readNBytes(in.readInt());
                    if ("Code".equals(attr)) {
                        decode(body, pool, ref, second, tag, tokens, enumReads);
                    }
                }
            }
        }
        String signature = null;
        int attrs = in.readUnsignedShort();
        for (int a = 0; a < attrs; a++) {
            Object attr = pool[in.readUnsignedShort()];
            byte[] body = in.readNBytes(in.readInt());
            if ("Signature".equals(attr)) {
                signature = (String) pool[((body[0] & 255) << 8) | (body[1] & 255)];
            }
        }
        return new ClassData(name, superName, signature, tokens, enumReads);
    }

    private static void decode(byte[] attr, Object[] pool, int[] ref, int[] second, int[] tag,
                               List<Object> out, Set<String> enumReads) {
        int start = 8;
        int end = start + int32(attr, 4);
        for (int pc = start; pc < end; ) {
            int op = attr[pc] & 255;
            if (op >= 0x02 && op <= 0x08) {
                out.add(op - 3);
            } else if (op == 0x10) {
                out.add((int) attr[pc + 1]);
            } else if (op == 0x11) {
                out.add((int) (short) u16(attr, pc + 1));
            } else if (op == 0x12 || op == 0x13) {
                int idx = op == 0x12 ? attr[pc + 1] & 255 : u16(attr, pc + 1);
                if (tag[idx] == 8) {
                    out.add(pool[ref[idx]]);
                } else if (pool[idx] instanceof Integer v) {
                    out.add(v);
                }
            } else if (op == 0xb2) {
                int field = u16(attr, pc + 1);
                String owner = (String) pool[ref[ref[field]]];
                String fieldName = (String) pool[ref[second[field]]];
                enumReads.add(owner + "." + fieldName);
            }
            pc = next(attr, pc, start, op);
        }
    }

    private static int next(byte[] attr, int pc, int start, int op) {
        if (op == 0xaa) {
            int base = start + (((pc - start) + 4) & ~3);
            return base + 12 + (int32(attr, base + 8) - int32(attr, base + 4) + 1) * 4;
        }
        if (op == 0xab) {
            int base = start + (((pc - start) + 4) & ~3);
            return base + 8 + int32(attr, base + 4) * 8;
        }
        if (op == 0xc4) {
            return pc + ((attr[pc + 1] & 255) == 0x84 ? 6 : 4);
        }
        return pc + 1 + KeyCensus.ClassTokens.operands(op);
    }

    private static int u16(byte[] b, int i) {
        return ((b[i] & 255) << 8) | (b[i + 1] & 255);
    }

    private static int int32(byte[] b, int i) {
        return (u16(b, i) << 16) | u16(b, i + 2);
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code ScreenScanner --selftest <create.jar> [more.jar...]}: hand-read values are the oracle. */
    public static void main(String[] args) throws IOException {
        int bad = 0;
        Map<String, ScreenInfo> create = new LinkedHashMap<>();
        for (ScreenInfo s : scanJar(Files.readAllBytes(Path.of(args[1])))) {
            create.put(s.className().substring(s.className().lastIndexOf('/') + 1), s);
        }
        long menus = create.values().stream().filter(s -> s.menu() != null).count();
        long art = create.values().stream().filter(s -> s.background() != null || !s.textures().isEmpty()).count();
        System.out.printf("     create: %d screens, %d menu-bound, %d with art%n", create.size(), menus, art);
        Map<String, int[]> oracle = Map.of("FilterScreen", new int[]{214, 99},
                "AttributeFilterScreen", new int[]{241, 85}, "ToolboxScreen", new int[]{188, 171},
                "LinkedControllerScreen", new int[]{179, 109}, "BlueprintScreen", new int[]{179, 109},
                "SchematicTableScreen", new int[]{214, 85});
        for (var e : oracle.entrySet()) {
            ScreenInfo s = create.get(e.getKey());
            Rect r = s == null ? null : s.background();
            bad += check(r != null && r.w() == e.getValue()[0] && r.h() == e.getValue()[1],
                    e.getKey() + " background found automatically: " + r);
        }
        ScreenInfo filter = create.get("FilterScreen");
        bad += check(filter != null && filter.menu() != null && filter.menu().endsWith("FilterMenu"),
                "FilterScreen bound to its menu: " + (filter == null ? null : filter.menu()));
        for (int i = 2; i < args.length; i++) {
            List<ScreenInfo> other = scanJar(Files.readAllBytes(Path.of(args[i])));
            System.out.printf("     %s: %d screens%n", Path.of(args[i]).getFileName(), other.size());
        }
        create.values().stream().filter(s -> s.background() == null && s.textures().isEmpty()).limit(12)
                .forEach(s -> System.out.println("     no art: " + s.className()));
        System.out.println(bad == 0 ? "ScreenScanner selftest: ALL PASS" : "ScreenScanner selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
