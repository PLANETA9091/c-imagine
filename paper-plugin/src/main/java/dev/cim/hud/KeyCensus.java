package dev.cim.hud;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Finds the keybindings mods register, by reading their jars.
 *
 * <p>KeyMapping is a client class, so a server never sees a registration
 * happen. The registration still sits in the jar as data: every class that
 * touches {@code net/minecraft/client/KeyMapping} carries the translation key
 * and the GLFW default in its bytecode, either at the constructor call or, as
 * in Create's {@code AllKeys} enum, in a constant table that builds the
 * translation key from a prefix. Both shapes reduce to "a string, then the
 * next int before another string".
 *
 * <p>A lang key only counts when such a class names it. That keeps ordinary
 * {@code key.*} lang entries out of the keyboard.
 */
public final class KeyCensus {

    private static final byte[] KEY_MAPPING =
            "net/minecraft/client/KeyMapping".getBytes(StandardCharsets.UTF_8);
    private static final Pattern LANG_PATH =
            Pattern.compile("assets/([^/]+)/lang/en_us\\.json");
    private static final Pattern LANG_ENTRY =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final int MAX_NESTED_DEPTH = 2;

    /** One registered binding. {@code defaultKey} is a GLFW code, -1 when unbound. */
    public record ModKey(String modId, String id, String label, String category, int defaultKey) {
    }

    private KeyCensus() {
    }

    /** Scans every jar directly under {@code modsDir}; a missing directory is an empty census. */
    public static List<ModKey> scan(Path modsDir) throws IOException {
        List<ModKey> out = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return out;
        }
        try (Stream<Path> jars = Files.list(modsDir)) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                out.addAll(scanJar(Files.readAllBytes(jar)));
            }
        }
        out.sort(Comparator.comparing(ModKey::modId).thenComparing(ModKey::id));
        return out;
    }

    /** Scans one jar's bytes, including jar-in-jar companions. */
    public static List<ModKey> scanJar(byte[] jar) throws IOException {
        Scan scan = new Scan();
        collect(jar, scan, 0);
        return scan.resolve();
    }

    private static void collect(byte[] jar, Scan scan, int depth) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                boolean cls = name.endsWith(".class");
                Matcher lang = LANG_PATH.matcher(name);
                boolean isLang = lang.matches();
                boolean nested = name.startsWith("META-INF/jarjar/") && name.endsWith(".jar");
                if (!cls && !isLang && !nested) {
                    continue;
                }
                byte[] body = zip.readAllBytes();
                if (cls) {
                    if (contains(body, KEY_MAPPING)) {
                        scan.tokens.add(ClassTokens.read(body));
                    }
                } else if (isLang) {
                    scan.readLang(lang.group(1), new String(body, StandardCharsets.UTF_8));
                } else if (depth < MAX_NESTED_DEPTH) {
                    collect(body, scan, depth + 1);
                }
            }
        }
    }

    private static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0, end = hay.length - needle.length; i <= end; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** Lang tables and bytecode tokens gathered from one jar. */
    private static final class Scan {
        final Map<String, String> lang = new HashMap<>();
        final Map<String, String> langOwner = new HashMap<>();
        final List<List<Object>> tokens = new ArrayList<>();

        void readLang(String ns, String json) {
            Matcher m = LANG_ENTRY.matcher(json);
            while (m.find()) {
                lang.put(m.group(1), m.group(2));
                langOwner.put(m.group(1), ns);
            }
        }

        List<ModKey> resolve() {
            Map<String, ModKey> found = new LinkedHashMap<>();
            Map<String, String> bySuffix = new HashMap<>();
            for (String key : lang.keySet()) {
                if (isBindingKey(key)) {
                    bySuffix.put(key.substring(key.lastIndexOf('.') + 1), key);
                }
            }
            for (List<Object> stream : tokens) {
                String category = "";
                for (Object t : stream) {
                    if (t instanceof String s && s.startsWith("key.categories.")) {
                        category = lang.getOrDefault(s, s);
                    }
                }
                for (int i = 0; i < stream.size(); i++) {
                    if (!(stream.get(i) instanceof String s)) {
                        continue;
                    }
                    String id = lang.containsKey(s) && isBindingKey(s) ? s : bySuffix.get(s);
                    if (id == null && s.startsWith("key.") && !s.startsWith("key.categories.")
                            && s.indexOf('.', 4) > 0) {
                        id = s;
                    }
                    if (id == null) {
                        continue;
                    }
                    // A short suffix like "ponder" also occurs as an unrelated
                    // string, so a later hit may be the one carrying the code.
                    ModKey seen = found.get(id);
                    int code = nextKeyCode(stream, i);
                    if (seen == null || (seen.defaultKey() == -1 && code != -1)) {
                        String mod = langOwner.getOrDefault(id, id.split("\\.")[1]);
                        found.put(id, new ModKey(mod, id, lang.getOrDefault(id, id), category, code));
                    }
                }
            }
            return new ArrayList<>(found.values());
        }

        private static boolean isBindingKey(String key) {
            return (key.startsWith("key.") && !key.startsWith("key.categories."))
                    || key.contains(".keyinfo.");
        }

        private static int nextKeyCode(List<Object> stream, int from) {
            for (int i = from + 1; i < stream.size(); i++) {
                Object t = stream.get(i);
                if (t instanceof Integer v) {
                    if (v == -1 || (v >= 32 && v <= 348)) {
                        return v;
                    }
                } else if (!(t instanceof Boolean)) {
                    return -1;
                }
            }
            return -1;
        }
    }

    /** Linear decoder: yields the String and int constants a class pushes, in code order. */
    static final class ClassTokens {
        private static final int LOOKUPSWITCH = 0xab;
        private static final int TABLESWITCH = 0xaa;
        private static final int WIDE = 0xc4;

        /** Operand bytes after each opcode, for every fixed-length instruction. */
        private static final byte[] OPERANDS = new byte[256];

        static {
            int[][] spans = {
                    {0x10, 1}, {0x11, 2}, {0x12, 1}, {0x13, 2}, {0x14, 2},
                    {0x15, 1}, {0x16, 1}, {0x17, 1}, {0x18, 1}, {0x19, 1},
                    {0x36, 1}, {0x37, 1}, {0x38, 1}, {0x39, 1}, {0x3a, 1},
                    {0x84, 2}, {0xa9, 1}, {0xb9, 4}, {0xba, 4}, {0xbc, 1},
                    {0xc5, 3}, {0xc8, 4}, {0xc9, 4},
            };
            for (int[] s : spans) {
                OPERANDS[s[0]] = (byte) s[1];
            }
            for (int op = 0x99; op <= 0xa8; op++) {
                OPERANDS[op] = 2;
            }
            for (int op = 0xb2; op <= 0xb8; op++) {
                OPERANDS[op] = 2;
            }
            for (int op : new int[]{0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7}) {
                OPERANDS[op] = 2;
            }
        }

        static List<Object> read(byte[] cls) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            in.readInt();
            int count = in.readUnsignedShort();
            Object[] pool = new Object[count];
            int[] stringRef = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 3 -> pool[i] = in.readInt();
                    case 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 16, 19, 20 -> in.readUnsignedShort();
                    case 8 -> stringRef[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("bad constant tag " + tag);
                }
            }
            in.skipBytes(6);
            in.skipBytes(in.readUnsignedShort() * 2);
            List<Object> tokens = new ArrayList<>();
            for (int section = 0; section < 2; section++) {
                int members = in.readUnsignedShort();
                for (int m = 0; m < members; m++) {
                    in.skipBytes(6);
                    int attrs = in.readUnsignedShort();
                    for (int a = 0; a < attrs; a++) {
                        Object name = pool[in.readUnsignedShort()];
                        int len = in.readInt();
                        byte[] body = in.readNBytes(len);
                        if ("Code".equals(name)) {
                            decode(body, pool, stringRef, tokens);
                        }
                    }
                }
            }
            return tokens;
        }

        private static void decode(byte[] attr, Object[] pool, int[] stringRef, List<Object> out) {
            int start = 8;
            int end = start + (((attr[4] & 255) << 24) | ((attr[5] & 255) << 16)
                    | ((attr[6] & 255) << 8) | (attr[7] & 255));
            for (int pc = start; pc < end; ) {
                int op = attr[pc] & 255;
                int at = pc - start;
                if (op >= 0x02 && op <= 0x08) {
                    out.add(op - 3);
                } else if (op == 0x10) {
                    out.add((int) attr[pc + 1]);
                } else if (op == 0x11) {
                    out.add((int) (short) (((attr[pc + 1] & 255) << 8) | (attr[pc + 2] & 255)));
                } else if (op == 0x12 || op == 0x13) {
                    int idx = op == 0x12 ? attr[pc + 1] & 255
                            : ((attr[pc + 1] & 255) << 8) | (attr[pc + 2] & 255);
                    if (stringRef[idx] != 0) {
                        out.add(pool[stringRef[idx]]);
                    } else if (pool[idx] instanceof Integer v) {
                        out.add(v);
                    }
                }
                if (op == TABLESWITCH) {
                    int base = start + ((at + 4) & ~3);
                    int low = int32(attr, base + 4);
                    int high = int32(attr, base + 8);
                    pc = base + 12 + (high - low + 1) * 4;
                } else if (op == LOOKUPSWITCH) {
                    int base = start + ((at + 4) & ~3);
                    pc = base + 8 + int32(attr, base + 4) * 8;
                } else if (op == WIDE) {
                    pc += (attr[pc + 1] & 255) == 0x84 ? 6 : 4;
                } else {
                    pc += 1 + OPERANDS[op];
                }
            }
        }

        private static int int32(byte[] b, int i) {
            return ((b[i] & 255) << 24) | ((b[i + 1] & 255) << 16)
                    | ((b[i + 2] & 255) << 8) | (b[i + 3] & 255);
        }
    }

    // ---- selftest ----------------------------------------------------------

    /** Hand-assembled class: {@code <clinit>} pushes the given ldc strings and sipush ints. */
    static byte[] fixtureClass(Object... pushes) throws IOException {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        ByteArrayOutputStream cp = new ByteArrayOutputStream();
        DataOutputStream pool = new DataOutputStream(cp);
        int next = 1;
        pool.writeByte(1);
        pool.writeUTF("Fix");
        int utfName = next++;
        pool.writeByte(7);
        pool.writeShort(utfName);
        int thisClass = next++;
        pool.writeByte(1);
        pool.writeUTF("java/lang/Object");
        int utfObj = next++;
        pool.writeByte(7);
        pool.writeShort(utfObj);
        int superClass = next++;
        pool.writeByte(1);
        pool.writeUTF("net/minecraft/client/KeyMapping");
        next++;
        pool.writeByte(1);
        pool.writeUTF("<clinit>");
        int clinit = next++;
        pool.writeByte(1);
        pool.writeUTF("()V");
        int desc = next++;
        pool.writeByte(1);
        pool.writeUTF("Code");
        int codeName = next++;
        for (Object p : pushes) {
            if (p instanceof String s) {
                pool.writeByte(1);
                pool.writeUTF(s);
                int utf = next++;
                pool.writeByte(8);
                pool.writeShort(utf);
                int str = next++;
                code.write(0x13);
                code.write(str >> 8);
                code.write(str);
            } else {
                int v = (Integer) p;
                code.write(0x11);
                code.write(v >> 8);
                code.write(v);
            }
            code.write(0x57);
        }
        code.write(0xb1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(0xCAFEBABE);
        d.writeInt(65);
        d.writeShort(next);
        cp.writeTo(d);
        d.writeShort(0x21);
        d.writeShort(thisClass);
        d.writeShort(superClass);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(1);
        d.writeShort(8);
        d.writeShort(clinit);
        d.writeShort(desc);
        d.writeShort(1);
        d.writeShort(codeName);
        d.writeInt(12 + code.size());
        d.writeShort(2);
        d.writeShort(0);
        d.writeInt(code.size());
        code.writeTo(d);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        return out.toByteArray();
    }

    private static byte[] fixtureJar(byte[] cls, String lang) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("fix/Keys.class"));
            z.write(cls);
            z.closeEntry();
            z.putNextEntry(new ZipEntry("assets/fixmod/lang/en_us.json"));
            z.write(lang.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return bos.toByteArray();
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private static ModKey find(List<ModKey> keys, String id) {
        return keys.stream().filter(k -> k.id().equals(id)).findFirst().orElse(null);
    }

    /** {@code KeyCensus --selftest [real-create.jar]}. */
    public static void main(String[] args) throws IOException {
        int bad = 0;
        byte[] jar = fixtureJar(fixtureClass(
                        "key.fixmod.dash", 82, "key.categories.fixmod",
                        "enum_style", 342, "Some description",
                        "unbound_one", -1,
                        "key.fixmod.no_lang", 70),
                "{\"key.fixmod.dash\":\"Dash\",\"fixmod.keyinfo.enum_style\":\"Enum Style\","
                        + "\"fixmod.keyinfo.unbound_one\":\"Unbound\",\"key.fixmod.unused\":\"Never registered\","
                        + "\"key.categories.fixmod\":\"Fix Mod\"}");
        List<ModKey> keys = scanJar(jar);
        ModKey dash = find(keys, "key.fixmod.dash");
        bad += check(dash != null && dash.defaultKey() == 82 && dash.label().equals("Dash"),
                "constructor shape: lang key then key code");
        bad += check(dash != null && dash.category().equals("Fix Mod"), "category resolved through lang");
        ModKey en = find(keys, "fixmod.keyinfo.enum_style");
        bad += check(en != null && en.defaultKey() == 342, "enum shape: suffix then key code");
        ModKey ub = find(keys, "fixmod.keyinfo.unbound_one");
        bad += check(ub != null && ub.defaultKey() == -1, "unbound default stays -1");
        ModKey nl = find(keys, "key.fixmod.no_lang");
        bad += check(nl != null && nl.defaultKey() == 70, "binding without a lang entry survives");
        bad += check(find(keys, "key.fixmod.unused") == null, "lang key no class names is not a binding");
        if (args.length > 1) {
            List<ModKey> real = scanJar(Files.readAllBytes(Path.of(args[1])));
            real.forEach(k -> System.out.println("     " + k));
            Map<String, Integer> want = Map.of("create.keyinfo.toolmenu", 342, "create.keyinfo.toolbelt", 342,
                    "create.keyinfo.rotate_menu", -1, "create.keyinfo.shift_modifier", 340,
                    "create.keyinfo.ctrl_modifier", 341, "create.keyinfo.alt_modifier", 342);
            for (var w : want.entrySet()) {
                ModKey k = find(real, w.getKey());
                bad += check(k != null && k.defaultKey() == w.getValue(), "real jar: " + w.getKey());
            }
            ModKey ponder = find(real, "key.ponder.ponder");
            bad += check(ponder != null && ponder.defaultKey() == 87, "real jar: jar-in-jar Ponder key defaults to W");
            bad += check(real.size() == want.size() + 1, "real jar: exactly the registered set, got " + real.size());
        }
        System.out.println(bad == 0 ? "KeyCensus selftest: ALL PASS" : "KeyCensus selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
