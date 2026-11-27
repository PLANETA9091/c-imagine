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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * happen. The registration still sits in the jar as data. Three shapes carry it:
 * <ul>
 *   <li>a constructor call on KeyMapping or on any class that extends it, with the
 *       translation key as the name argument, the GLFW default after it, and the
 *       category last (Create, Combat Roll, Sophisticated Backpacks);</li>
 *   <li>a builder chain whose class is not a KeyMapping itself but names one in
 *       its {@code build()} descriptor, with the translation key passed as a
 *       constant (Mekanism's {@code MekKeyBindingBuilder});</li>
 *   <li>a constant table that builds the translation key from a prefix, as in
 *       Create's {@code AllKeys} enum.</li>
 * </ul>
 * A name passed as an enum constant ({@code MekanismLang.KEY_BOOST}) is resolved
 * through the constant's own static initializer, which pushes the prefix and the
 * path of the lang key ("key", "key_boost") that Util.makeDescriptionId joins.
 *
 * <p>A lang key only counts when such a class names it. Category keys
 * ({@code key.categories.*}, {@code key.category.*}, {@code *.category}) name the
 * group a binding sits in, so they never count as a binding themselves.
 */
public final class KeyCensus {

    private static final String KEY_MAPPING_NAME = "net/minecraft/client/KeyMapping";
    private static final Pattern LANG_PATH =
            Pattern.compile("assets/([^/]+)/lang/en_us\\.json");
    private static final Pattern LANG_ENTRY =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final int MAX_NESTED_DEPTH = 2;
    private static final Pattern CTOR_NAME = Pattern.compile("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+");
    /** Stream marker for {@code new} of a KeyMapping class: the next string pushed is the constructor's name argument. */
    static final Object NEW_KEY_MAPPING = new Object();

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

    /**
     * Scans one jar's bytes, including jar-in-jar companions. The KeyMapping
     * hierarchy is resolved within this jar and its nested jars only.
     */
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
                    try {
                        scan.addClass(ClassTokens.read(body));
                    } catch (IOException | RuntimeException notAClass) {
                        // A truncated or unusual class file cannot register a key; skip it.
                    }
                } else if (isLang) {
                    scan.readLang(lang.group(1), new String(body, StandardCharsets.UTF_8));
                } else if (depth < MAX_NESTED_DEPTH) {
                    collect(body, scan, depth + 1);
                }
            }
        }
    }

    /** Lang tables and decoded classes gathered from one jar. */
    private static final class Scan {
        final Map<String, String> lang = new HashMap<>();
        final Map<String, String> langOwner = new HashMap<>();
        final Map<String, ClassTokens.Decoded> classes = new HashMap<>();
        private final Map<String, String> fieldKeys = new HashMap<>();

        void addClass(ClassTokens.Decoded d) {
            if (d.name() != null) {
                classes.putIfAbsent(d.name(), d);
            }
        }

        void readLang(String ns, String json) {
            Matcher m = LANG_ENTRY.matcher(json);
            while (m.find()) {
                lang.put(m.group(1), m.group(2));
                langOwner.put(m.group(1), ns);
            }
        }

        /** KeyMapping plus every class in this scan that extends it, transitively. */
        Set<String> family() {
            Set<String> fam = new HashSet<>();
            fam.add(KEY_MAPPING_NAME);
            for (boolean grew = true; grew; ) {
                grew = false;
                for (ClassTokens.Decoded d : classes.values()) {
                    if (d.superName() != null && fam.contains(d.superName()) && fam.add(d.name())) {
                        grew = true;
                    }
                }
            }
            return fam;
        }

        List<ModKey> resolve() {
            Set<String> family = family();
            Map<String, ModKey> found = new LinkedHashMap<>();
            Map<String, String> bySuffix = new HashMap<>();
            for (String key : lang.keySet()) {
                if (isBindingKey(key)) {
                    bySuffix.put(key.substring(key.lastIndexOf('.') + 1), key);
                }
            }
            // Sorted by class name so the first hit for a key is the same on every run.
            for (ClassTokens.Decoded cf : new TreeMap<>(classes).values()) {
                boolean relevant = cf.mentionsKeyMapping() || cf.classRefs().stream().anyMatch(family::contains);
                if (!relevant) {
                    continue;
                }
                List<Object> stream = normalize(cf.tokens(), family);
                String category = "";
                for (Object t : stream) {
                    if (t instanceof String s && isCategoryKey(s)) {
                        category = lang.getOrDefault(s, s);
                    }
                }
                boolean ctorName = false;
                for (int i = 0; i < stream.size(); i++) {
                    Object token = stream.get(i);
                    if (token == NEW_KEY_MAPPING) {
                        ctorName = true;
                        continue;
                    }
                    if (token instanceof Integer) {
                        // The key code follows the name, so a constructor without a literal name ends here.
                        ctorName = false;
                    }
                    if (!(token instanceof String s)) {
                        continue;
                    }
                    // The first string a KeyMapping constructor takes is its name, whatever the prefix.
                    boolean asCtorName = ctorName && isCtorName(s);
                    ctorName = false;
                    String id = asCtorName ? s
                            : lang.containsKey(s) && isBindingKey(s) ? s : bySuffix.get(s);
                    if (id == null && s.startsWith("key.") && !isCategoryKey(s) && s.indexOf('.', 4) > 0) {
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

        /**
         * Maps a class's raw tokens onto what a registration reads: a constructor
         * of a KeyMapping class becomes the marker, a static field becomes the
         * lang key its constant builds (or nothing), and every other class
         * reference disappears.
         */
        private List<Object> normalize(List<Object> raw, Set<String> family) {
            List<Object> out = new ArrayList<>(raw.size());
            for (Object t : raw) {
                if (t instanceof ClassTokens.NewRef r) {
                    if (family.contains(r.cls())) {
                        out.add(NEW_KEY_MAPPING);
                    }
                } else if (t instanceof ClassTokens.FieldRef f) {
                    String key = fieldKey(f);
                    if (key != null) {
                        out.add(key);
                    }
                } else {
                    out.add(t);
                }
            }
            return out;
        }

        /**
         * The lang key an enum-style constant stands for. Its static initializer
         * pushes the constant's own name, then the prefix and the path that
         * Util.makeDescriptionId joins into "prefix.namespace.path".
         */
        private String fieldKey(ClassTokens.FieldRef f) {
            String cacheKey = f.owner() + "#" + f.name();
            if (fieldKeys.containsKey(cacheKey)) {
                return fieldKeys.get(cacheKey);
            }
            String out = null;
            ClassTokens.Decoded owner = classes.get(f.owner());
            if (owner != null) {
                List<Object> t = owner.tokens();
                for (int i = 0; i < t.size() && out == null; i++) {
                    if (!f.name().equals(t.get(i))) {
                        continue;
                    }
                    // The constant's ordinal (an int) may sit between its name and the two strings.
                    List<String> strs = new ArrayList<>();
                    for (int j = i + 1; j < t.size() && j <= i + 4 && strs.size() < 2; j++) {
                        if (t.get(j) instanceof String s) {
                            strs.add(s);
                        } else if (!(t.get(j) instanceof Integer)) {
                            break;
                        }
                    }
                    if (strs.size() == 2) {
                        out = lookupPathKey(strs.get(0), strs.get(1));
                    }
                }
            }
            fieldKeys.put(cacheKey, out);
            return out;
        }

        /** The one binding key shaped {@code prefix.<namespace>.path}; ambiguous or absent gives null. */
        private String lookupPathKey(String prefix, String path) {
            String only = null;
            for (String k : lang.keySet()) {
                if (k.startsWith(prefix + ".") && k.endsWith("." + path) && isBindingKey(k)) {
                    if (only != null) {
                        return null;
                    }
                    only = k;
                }
            }
            return only;
        }

        /** Category keys name a group of bindings; they are never bindings. */
        static boolean isCategoryKey(String key) {
            return key.startsWith("key.categories.") || key.startsWith("key.category.")
                    || key.endsWith(".category") || key.endsWith(".categories");
        }

        private static boolean isBindingKey(String key) {
            return !isCategoryKey(key) && ((key.startsWith("key.") || key.startsWith("keybinds."))
                    || key.contains(".keyinfo."));
        }

        /** A dotted, lower-case name that is not a category; any prefix counts. */
        private static boolean isCtorName(String s) {
            return s.length() <= 160 && CTOR_NAME.matcher(s).matches() && !isCategoryKey(s);
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

    /** Linear decoder: yields the String, int, class-instantiation and static-field references a class pushes, in code order. */
    static final class ClassTokens {
        private static final int LOOKUPSWITCH = 0xab;
        private static final int TABLESWITCH = 0xaa;
        private static final int WIDE = 0xc4;

        /** {@code new X} in code: the class's internal name. */
        record NewRef(String cls) {
        }

        /** {@code getstatic owner.name} in code. */
        record FieldRef(String owner, String name) {
        }

        /**
         * One class file's header and code tokens. {@code classRefs} holds every
         * class the constant pool names; {@code mentionsKeyMapping} is true when a
         * descriptor or string in the pool names KeyMapping.
         */
        record Decoded(String name, String superName, Set<String> classRefs, boolean mentionsKeyMapping,
                       List<Object> tokens) {
        }

        /** Constant-pool lookups the code decoder needs. */
        private record Pool(Object[] v, int[] stringRef, int[] classRef, int[] fieldClass, int[] fieldNat,
                            int[] natName) {
        }

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

        static Decoded read(byte[] cls) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(cls));
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("not a class file");
            }
            in.readInt();
            int count = in.readUnsignedShort();
            Object[] pool = new Object[count];
            int[] stringRef = new int[count];
            int[] classRef = new int[count];
            int[] fieldClass = new int[count];
            int[] fieldNat = new int[count];
            int[] natName = new int[count];
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
                    case 7 -> classRef[i] = in.readUnsignedShort();
                    case 16, 19, 20 -> in.readUnsignedShort();
                    case 8 -> stringRef[i] = in.readUnsignedShort();
                    case 9 -> {
                        fieldClass[i] = in.readUnsignedShort();
                        fieldNat[i] = in.readUnsignedShort();
                    }
                    case 10, 11 -> in.readInt();
                    case 12 -> {
                        natName[i] = in.readUnsignedShort();
                        in.readUnsignedShort();
                    }
                    case 17, 18 -> in.readInt();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    default -> throw new IOException("bad constant tag " + tag);
                }
            }
            Pool p = new Pool(pool, stringRef, classRef, fieldClass, fieldNat, natName);
            Set<String> refs = new HashSet<>();
            boolean mentions = false;
            for (int i = 1; i < count; i++) {
                if (classRef[i] != 0 && pool[classRef[i]] instanceof String n) {
                    refs.add(n);
                }
                if (pool[i] instanceof String s && s.contains(KEY_MAPPING_NAME)) {
                    mentions = true;
                }
            }
            in.readUnsignedShort(); // access flags
            String self = className(p, in.readUnsignedShort());
            String sup = className(p, in.readUnsignedShort());
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
                            decode(body, p, tokens);
                        }
                    }
                }
            }
            return new Decoded(self, sup, refs, mentions, tokens);
        }

        private static String className(Pool p, int idx) {
            if (idx == 0 || p.classRef()[idx] == 0) {
                return null;
            }
            return p.v()[p.classRef()[idx]] instanceof String s ? s : null;
        }

        private static void decode(byte[] attr, Pool p, List<Object> out) {
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
                } else if (op == 0xbb) {
                    int idx = ((attr[pc + 1] & 255) << 8) | (attr[pc + 2] & 255);
                    String cls = className(p, idx);
                    if (cls != null) {
                        out.add(new NewRef(cls));
                    }
                } else if (op == 0xb2) {
                    int idx = ((attr[pc + 1] & 255) << 8) | (attr[pc + 2] & 255);
                    String owner = className(p, p.fieldClass()[idx]);
                    if (owner != null && p.v()[p.natName()[p.fieldNat()[idx]]] instanceof String name) {
                        out.add(new FieldRef(owner, name));
                    }
                } else if (op == 0x12 || op == 0x13) {
                    int idx = op == 0x12 ? attr[pc + 1] & 255
                            : ((attr[pc + 1] & 255) << 8) | (attr[pc + 2] & 255);
                    if (p.stringRef()[idx] != 0) {
                        out.add(p.v()[p.stringRef()[idx]]);
                    } else if (p.v()[idx] instanceof Integer v) {
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

    /** Selftest marker: a {@code new net/minecraft/client/KeyMapping} in the fixture class. */
    private static final Object NEW_KM = new Object();

    /** Selftest: {@code new cls} in the fixture's static initializer. */
    private record NewOf(String cls) {
    }

    /** Selftest: {@code getstatic owner.name} in the fixture's static initializer. */
    private record StaticOf(String owner, String name) {
    }

    /** Selftest: a hand-assembled constant pool, entries appended in the order they are first requested. */
    private static final class Asm {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream pool = new DataOutputStream(bytes);
        private final Map<String, Integer> refs = new HashMap<>();
        private int next = 1;

        int utf(String s) throws IOException {
            Integer have = refs.get("U" + s);
            if (have != null) {
                return have;
            }
            pool.writeByte(1);
            pool.writeUTF(s);
            refs.put("U" + s, next);
            return next++;
        }

        int cls(String name) throws IOException {
            Integer have = refs.get("C" + name);
            if (have != null) {
                return have;
            }
            int u = utf(name);
            pool.writeByte(7);
            pool.writeShort(u);
            refs.put("C" + name, next);
            return next++;
        }

        int str(String s) throws IOException {
            Integer have = refs.get("S" + s);
            if (have != null) {
                return have;
            }
            int u = utf(s);
            pool.writeByte(8);
            pool.writeShort(u);
            refs.put("S" + s, next);
            return next++;
        }

        int field(String owner, String name) throws IOException {
            Integer have = refs.get("F" + owner + "." + name);
            if (have != null) {
                return have;
            }
            int c = cls(owner);
            int n = utf(name);
            int d = utf("I");
            pool.writeByte(12);
            pool.writeShort(n);
            pool.writeShort(d);
            int nat = next++;
            pool.writeByte(9);
            pool.writeShort(c);
            pool.writeShort(nat);
            refs.put("F" + owner + "." + name, next);
            return next++;
        }
    }

    /** Hand-assembled class: {@code <clinit>} pushes the given strings, ints, instantiations and static reads. */
    static byte[] classFile(String name, String superName, Object... pushes) throws IOException {
        Asm a = new Asm();
        int thisIdx = a.cls(name);
        int superIdx = a.cls(superName);
        int clinit = a.utf("<clinit>");
        int desc = a.utf("()V");
        int codeName = a.utf("Code");
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        for (Object p : pushes) {
            if (p instanceof String s) {
                int idx = a.str(s);
                code.write(0x13);
                code.write(idx >> 8);
                code.write(idx);
            } else if (p instanceof Integer v) {
                code.write(0x11);
                code.write(v >> 8);
                code.write(v);
            } else if (p instanceof NewOf n) {
                int idx = a.cls(n.cls());
                code.write(0xbb);
                code.write(idx >> 8);
                code.write(idx);
            } else if (p instanceof StaticOf f) {
                int idx = a.field(f.owner(), f.name());
                code.write(0xb2);
                code.write(idx >> 8);
                code.write(idx);
            }
            code.write(0x57);
        }
        code.write(0xb1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(0xCAFEBABE);
        d.writeShort(0);
        d.writeShort(65);
        d.writeShort(a.next);
        a.pool.flush();
        a.bytes.writeTo(d);
        d.writeShort(0x21);
        d.writeShort(thisIdx);
        d.writeShort(superIdx);
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

    /** Selftest fixture extending Object: {@code NEW_KM} stands for {@code new KeyMapping}. */
    static byte[] fixtureClass(Object... pushes) throws IOException {
        Object[] mapped = new Object[pushes.length];
        for (int i = 0; i < pushes.length; i++) {
            mapped[i] = pushes[i] == NEW_KM ? new NewOf(KEY_MAPPING_NAME) : pushes[i];
        }
        return classFile("Fix", "java/lang/Object", mapped);
    }

    /** Selftest jar: the lang table as assets/fixmod, then the (entry name, class bytes) pairs. */
    private static byte[] fixtureJar(String lang, Object... entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i + 1 < entries.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) entries[i]));
                z.write((byte[]) entries[i + 1]);
                z.closeEntry();
            }
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

    /** {@code KeyCensus --selftest [real-create.jar [real-combat-roll.jar]]}. */
    public static void main(String[] args) throws IOException {
        int bad = 0;
        byte[] jar = fixtureJar("{\"key.fixmod.dash\":\"Dash\",\"fixmod.keyinfo.enum_style\":\"Enum Style\","
                        + "\"fixmod.keyinfo.unbound_one\":\"Unbound\",\"key.fixmod.unused\":\"Never registered\","
                        + "\"key.categories.fixmod\":\"Fix Mod\",\"keybinds.fixmod.roll\":\"Roll\"}",
                "fix/Keys.class", fixtureClass(
                        "key.fixmod.dash", 82, "key.categories.fixmod",
                        "enum_style", 342, "Some description",
                        "unbound_one", -1,
                        "key.fixmod.no_lang", 70,
                        NEW_KM, "keybinds.fixmod.roll", 82, "key.categories.fixmod",
                        NEW_KM, "fixmod.custom.spin", 90, "key.categories.fixmod",
                        NEW_KM, "keybinds.fixmod.nolang", -1,
                        NEW_KM, 70, "key.categories.fixmod"));
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
        ModKey roll = find(keys, "keybinds.fixmod.roll");
        bad += check(roll != null && roll.defaultKey() == 82 && roll.label().equals("Roll")
                && roll.category().equals("Fix Mod"), "keybinds.* name after new KeyMapping, with lang and category");
        ModKey spin = find(keys, "fixmod.custom.spin");
        bad += check(spin != null && spin.defaultKey() == 90, "constructor name with any prefix (no lang entry)");
        ModKey nolang = find(keys, "keybinds.fixmod.nolang");
        bad += check(nolang != null && nolang.defaultKey() == -1 && nolang.label().equals("keybinds.fixmod.nolang"),
                "keybinds.* unbound constructor name, label falls back to the id");
        bad += check(find(keys, "key.categories.fixmod") == null,
                "a category after a computed name (no literal name) is not a binding");

        // A subclass of a subclass of KeyMapping registers like KeyMapping itself (Mekanism's MekKeyBinding shape).
        byte[] sub = fixtureJar("{\"keybinds.fixmod.sub\":\"Sub Key\",\"key.categories.fixmod\":\"Fix Mod\"}",
                "fix/Mid.class", classFile("fix/Mid", KEY_MAPPING_NAME),
                "fix/Sub.class", classFile("fix/Sub", "fix/Mid"),
                "fix/Reg.class", classFile("fix/Reg", "java/lang/Object",
                        new NewOf("fix/Sub"), "keybinds.fixmod.sub", 77, "key.categories.fixmod"));
        ModKey subKey = find(scanJar(sub), "keybinds.fixmod.sub");
        bad += check(subKey != null && subKey.defaultKey() == 77 && subKey.label().equals("Sub Key")
                && subKey.category().equals("Fix Mod"), "transitive KeyMapping subclass registers its name and code");

        // A builder passes an enum constant as the name; the constant's static initializer builds the lang key.
        byte[] enumConst = fixtureJar("{\"key.fixmod.key_boost\":\"Boost\",\"key.fixmod.mode\":\"Mode\"}",
                "fix/Lang.class", classFile("fix/Lang", "java/lang/Object",
                        "KEY_BOOST", 4, "key", "key_boost", "KEY_MODE", 5, "key", "mode"),
                "fix/Build.class", classFile("fix/Build", KEY_MAPPING_NAME,
                        new StaticOf("fix/Lang", "KEY_BOOST"), 341,
                        new StaticOf("fix/Lang", "KEY_MODE"), 340));
        List<ModKey> enumKeys = scanJar(enumConst);
        ModKey boost = find(enumKeys, "key.fixmod.key_boost");
        bad += check(boost != null && boost.defaultKey() == 341 && boost.label().equals("Boost"),
                "enum constant name resolves through its static initializer");
        ModKey mode = find(enumKeys, "key.fixmod.mode");
        bad += check(mode != null && mode.defaultKey() == 340, "a second enum constant resolves to its own key");

        // Category keys are groups, never bindings; a binding in the group takes the group's label.
        byte[] cats = fixtureJar("{\"key.fixmod.category\":\"Fix Cat\",\"key.category.fixmod.main\":\"Fix Main\","
                        + "\"key.fixmod.dash2\":\"Dash Two\"}",
                "fix/Cat.class", fixtureClass(
                        NEW_KM, "key.fixmod.dash2", 82, "key.fixmod.category"),
                "fix/Group.class", classFile("fix/Group", "java/lang/Object",
                        "key.category.fixmod.main"));
        List<ModKey> catKeys = scanJar(cats);
        bad += check(find(catKeys, "key.fixmod.category") == null, "a *.category lang key is not a binding");
        bad += check(find(catKeys, "key.category.fixmod.main") == null, "a key.category.* lang key is not a binding");
        ModKey dash2 = find(catKeys, "key.fixmod.dash2");
        bad += check(dash2 != null && dash2.defaultKey() == 82 && dash2.category().equals("Fix Cat"),
                "a binding takes its *.category label as category");
        if (args.length > 2) {
            ModKey combatRoll = find(scanJar(Files.readAllBytes(Path.of(args[2]))), "keybinds.combat_roll.roll");
            bad += check(combatRoll != null && combatRoll.defaultKey() == 82, "real Combat Roll jar: keybinds.combat_roll.roll defaults to R");
        }
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
