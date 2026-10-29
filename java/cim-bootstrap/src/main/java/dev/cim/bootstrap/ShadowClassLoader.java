package dev.cim.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Child-first classloader hosting the relocated 1.21.1 layer
 * ({@code docs/ARCHITECTURE.md §4.2}).
 *
 * Classes in the shadow space never leak into Paper's class space and vice
 * versa. JDK classes resolve from the platform loader; everything else must
 * come from the loader's own jar set (bootstrap jar + the NeoForge 1.21.1
 * library set) — there is deliberately <b>no</b> fallback to the host
 * application loader, so a missing dependency fails loudly instead of
 * silently binding a 1.21.1 class to a Paper 26.3 lookalike.
 *
 * Every class is routed through the native relocation stage
 * ({@link NativeReloc}) before being defined; classes outside the relocation
 * space pass through byte-identical.
 */
public final class ShadowClassLoader extends ClassLoader {

    static {
        // Parallel-capable: per-classname load locks instead of one monitor
        // for the whole loader. Without this every class load across all
        // threads serialized on a single lock, and the classic
        // cross-loader deadlock setup (shadow lock held while the platform
        // loader defines a JDK class, and vice versa) was live.
        ClassLoader.registerAsParallelCapable();
    }

    private static volatile ShadowClassLoader instance;

    private static final String[] JDK_PREFIXES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.dom.",
        "org.xml.sax.", "org.ietf.jgss.",
    };

    /** Jar name fragments excluded from the layer walk: launch intermediates
     * the real server's game classpath never serves classes from.
     *
     * THE GAME CLASSPATH MODEL (verified against the installer profile + a
     * green create-truth run, 2026-10-29): the installer produces the game
     * content as a DELTA UNION —
     * <ul>
     *   <li>{@code server-…-srg.jar} = the vanilla base (6148 classes, obf→srg
     *       renamed, ZERO client classes), and</li>
     *   <li>{@code neoforge-&lt;ver&gt;-server.jar} = the PATCHED DELTA
     *       (binarypatcher {@code --clean srg --apply server.lzma}), ~1613
     *       classes whose bytes NeoForge's binpatches change.</li>
     * </ul>
     * The binpatches are generated against the MERGED client∪server jar, so
     * the delta legitimately carries exactly the client classes NeoForge
     * patches (Screen, BakedModel, ClientLevel, … — witness:
     * {@code patches/net/minecraft/client/…/Screen.java.patch} exists,
     * {@code Renderable.java.patch} does not). The real dedicated server
     * resolves those delta client classes (Create's {@code <clinit>} touches
     * BakedModel there and constructs fine) and cleanly fails every OTHER
     * client class — our index must reproduce BOTH properties. The delta must
     * also WIN over the base for overlapping entries: the relocated-key index
     * is last-wins and the walk orders the base before the delta, which
     * mirrors the real union filesystem (patched bytes served).
     *
     * Excluded here: the unpatched SLIM jar (would shadow the patched bytes),
     * the unpacked jar, the vanilla {@code server-1.21.1.jar} launcher stub
     * and build-time {@code -sources/-javadoc} artifacts. */
    private static final String[] LAYER_EXCLUDES = {
        "-unpacked.jar", "-sources.jar", "-javadoc.jar", "server-1.21.1.jar",
        "-slim.jar",
    };

    private final AtomicLong defined = new AtomicLong();
    /** The mixin-gate hook (wired by MixinGate.init — never referenced by
     *  class here: this class is host-defined and dev.cim.layer.** stays
     *  shadow-defined, so a static reference would NCDFE on the host). */
    private static volatile ClassBytecodeHook bytecodeHook;
    /** Per-jar manifests: package implementation metadata carried into
     *  definePackage (the real server's SecureJarHandler exposes each jar's
     *  Implementation-Version through the package of every class it defines;
     *  sponge-mixin's ASM version detection READS it —
     *  {@code Opcodes.class.getPackage().getImplementationVersion()} — and
     *  without it the JAVA_21 compatibility level fails its
     *  {@code ASM.isAtLeastVersion(9,5)} check, killing every JAVA_21
     *  mixin config). */
    private final List<java.util.jar.Manifest> jarManifests = new ArrayList<>();
    /** Index (into {@link #jars}) of the first mod jar — earlier jars are the
     * 1.21.1 layer set and keep M1 full-relocation semantics. */
    private volatile int modJarStart = Integer.MAX_VALUE;
    private final List<JarFile> jars = new ArrayList<>();
    private final List<URL> jarUrls = new ArrayList<>();
    /** requested entry path (relocated or original) → (jar index, on-disk entry path) */
    private volatile Map<String, IndexEntry> index;

    private record IndexEntry(int jarIdx, String entryPath, String internalName) {
        IndexEntry(int jarIdx, String entryPath) {
            this(jarIdx, entryPath, "");
        }
    }

    /** Diagnostic: log original-space name requests (-Dcim.debug.origNames=true). */
    private static final boolean DEBUG_ORIG = Boolean.getBoolean("cim.debug.origNames");

    private ShadowClassLoader(URL[] urls) {
        super("cim-shadow", null);
        for (URL url : urls) {
            try {
                // Multi-release jars must resolve their versioned entries
                // (log4j-api's Java-9+ StackLocator lives under
                // META-INF/versions/9 — without the version flag the base
                // Java-7 class loads and caller lookup breaks on modern JVMs).
                JarFile jar = new JarFile(new java.io.File(url.toURI()), true,
                        java.util.zip.ZipFile.OPEN_READ, Runtime.version());
                jars.add(jar);
                jarManifests.add(jar.getManifest());
                jarUrls.add(url);
            } catch (Exception e) {
                throw new IllegalStateException("layer jar open: " + url + ": " + e, e);
            }
        }
    }

    /**
     * Create (or reset) the shared shadow loader: the bootstrap jar plus every
     * usable jar under {@code layerDir} (recursive). {@code layerDir} may be
     * null/empty for M0-style boots without a layer.
     *
     * Jar ordering matters: the NeoForge library set contains multiple
     * versions of some artifacts (e.g. guava 20.0 pulled transitively next to
     * guava 32.1.2). FML resolves them through its module layer in the order
     * of the installer's {@code unix_args.txt} {@code -DlegacyClassPath} —
     * we mirror that: jars listed there load first (in list order), then the
     * remainder alphabetically.
     */
    public static void prepare(String bootstrapJar, String layerDir) {
        List<URL> urls = new ArrayList<>();
        try {
            if (bootstrapJar != null) {
                urls.add(Path.of(bootstrapJar).toUri().toURL());
            }
            if (layerDir != null && !layerDir.isEmpty()) {
                Path root = Path.of(layerDir);
                if (!Files.isDirectory(root)) {
                    throw new IllegalStateException("layer dir missing: " + root);
                }
                List<Path> prioritized = legacyClassPathOrder(root);
                List<Path> all = new ArrayList<>();
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(p -> p.toString().endsWith(".jar"))
                            .filter(p -> !excluded(p.getFileName().toString()))
                            .forEach(all::add);
                }
                all.sort(Comparator.naturalOrder());
                List<Path> ordered = new ArrayList<>(all.size());
                List<Path> remaining = new ArrayList<>(all);
                for (Path p : prioritized) {
                    if (remaining.remove(p)) {
                        ordered.add(p);
                    }
                }
                ordered.addAll(remaining);
                ordered.forEach(p -> urls.add(toUrl(p)));
                // Autopsy telemetry: the exact jar set the shadow index is
                // built from. CI logs are unrestricted — never guess which
                // jars the walk saw.
                ordered.forEach(p -> System.out.println("[c-imagine] shadow loader jar: " + p));
            }
        } catch (Exception e) {
            throw new IllegalStateException("shadow loader urls: " + e, e);
        }
        instance = new ShadowClassLoader(urls.toArray(new URL[0]));
        System.out.println("[c-imagine] shadow loader: " + urls.size() + " jar(s)"
                + (layerDir != null && !layerDir.isEmpty() ? " (layer: " + layerDir + ")" : ""));
    }

    /**
     * Parse {@code -DlegacyClassPath=} from the NeoForge args file and return
     * the listed jar paths (existing files under the layer dir), in order.
     */
    private static List<Path> legacyClassPathOrder(Path layerRoot) {
        List<Path> out = new ArrayList<>();
        Path args = findArgsFile(layerRoot);
        if (args == null) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(args)) {
                line = line.trim();
                String marker = "-DlegacyClassPath=";
                if (!line.startsWith(marker)) {
                    continue;
                }
                for (String entry : line.substring(marker.length()).split(":")) {
                    entry = entry.trim();
                    if (entry.isEmpty()) {
                        continue;
                    }
                    Path resolved = resolveAgainstLayer(layerRoot, entry);
                    if (resolved != null) {
                        out.add(resolved);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[c-imagine] WARN: could not parse " + args + ": " + e);
        }
        return out;
    }

    /** unix_args.txt lives at {@code <root>/net/neoforged/neoforge/<ver>/unix_args.txt}. */
    private static Path findArgsFile(Path layerRoot) {
        Path base = layerRoot.resolve("net").resolve("neoforged").resolve("neoforge");
        if (!Files.isDirectory(base)) {
            return null;
        }
        try (Stream<Path> versions = Files.list(base)) {
            return versions.sorted()
                    .map(d -> d.resolve("unix_args.txt"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Args-file entries are relative to the install root ({@code libraries/…}). */
    private static Path resolveAgainstLayer(Path layerRoot, String entry) {
        Path p = Path.of(entry);
        if (p.isAbsolute()) {
            return Files.isRegularFile(p) ? p.normalize() : null;
        }
        // strip a leading "libraries/" — layerRoot IS the libraries dir
        if (p.startsWith("libraries/")) {
            p = p.subpath(1, p.getNameCount());
        }
        Path resolved = layerRoot.resolve(p).normalize();
        return Files.isRegularFile(resolved) ? resolved : null;
    }

    private static boolean excluded(String fileName) {
        for (String ex : LAYER_EXCLUDES) {
            if (fileName.endsWith(ex)) {
                return true;
            }
        }
        return false;
    }

    private static URL toUrl(Path p) {
        try {
            return p.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException("layer jar url: " + p + ": " + e, e);
        }
    }

    /**
     * Extend the shadow space with mod jars (M2). Must be called after
     * {@link NativeReloc#registerMods} so the index resolves mod classes to
     * their relocated paths. Resets the index; the next class load rebuilds
     * it over the full jar set (layer jars keep their earlier order — mod
     * jars are appended, in manifest order).
     */
    public static synchronized void addModJars(java.util.List<String> jarPaths) {
        ShadowClassLoader loader = instance;
        if (loader == null) {
            throw new IllegalStateException("ShadowClassLoader not prepared");
        }
        int added = 0;
        for (String p : jarPaths) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            try {
                JarFile jar = new JarFile(new java.io.File(p), true,
                        java.util.zip.ZipFile.OPEN_READ, Runtime.version());
                loader.jars.add(jar);
                loader.jarManifests.add(jar.getManifest());
                loader.jarUrls.add(Path.of(p).toUri().toURL());
                added++;
            } catch (Exception e) {
                throw new IllegalStateException("mod jar open: " + p + ": " + e, e);
            }
        }
        if (loader.modJarStart == Integer.MAX_VALUE) {
            // Base the boundary on the jars ACTUALLY added, not on the
            // requested path list: skipped (null/empty) paths previously
            // shifted the boundary into layer-jar territory and misclassified
            // a layer jar under mod-relocation semantics.
            loader.modJarStart = loader.jars.size() - added;
        }
        loader.index = null; // rebuilt lazily over the extended set
        System.out.println("[c-imagine] shadow loader: +" + jarPaths.size()
                + " mod jar(s), total " + loader.jars.size());
    }

    public static ShadowClassLoader get() {
        ShadowClassLoader l = instance;
        if (l == null) {
            throw new IllegalStateException("ShadowClassLoader not prepared");
        }
        return l;
    }

    /** Wire the mixin-gate hook (MixinGate.init — shadow side). */
    public static void setBytecodeHook(ClassBytecodeHook hook) {
        bytecodeHook = hook;
    }

    /**
     * Root {@code *.mixins.json} resource names across the MOD-SPACE jars
     * (mods + jarjar classpath libraries, {@code jarIdx >= modJarStart}).
     * Library bootstrap configs — mixinextras' {@code mixinextras.init.mixins.json}
     * is the canonical case — never reach the manifest (the scanner reports
     * library jars as classpath entries only), and their plugin classes are
     * the de-facto extension bootstrap (the real platform loads them exactly
     * the same way). Universal: whatever the mod space carries, boots.
     */
    public static List<String> modSpaceRootMixinConfigs() {
        ShadowClassLoader loader = instance;
        if (loader == null) {
            throw new IllegalStateException("ShadowClassLoader not prepared");
        }
        List<String> out = new ArrayList<>();
        for (int i = Math.max(0, loader.modJarStart); i < loader.jars.size(); i++) {
            var entries = loader.jars.get(i).entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(".mixins.json") && !name.contains("/")) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** Number of classes defined inside the shadow space so far. */
    public static int loadedClassCount() {
        ShadowClassLoader l = instance;
        return l == null ? 0 : (int) l.defined.get();
    }

    /**
     * Whether this name is already defined by THIS loader instance (mixin
     * class tracker). Instance method on purpose: the shadow copy's statics
     * are separate from the APP copy (see MixinGate.shadowLoader).
     */
    public boolean isLoadedHere(String name) {
        synchronized (getClassLoadingLock(name)) {
            return findLoadedClass(name) != null;
        }
    }

    /**
     * Public window into the relocation table for resource-space translation
     * (mixin configs, §8 point 4). Takes an internal (slash) name; returns
     * the relocated name or null when the name is outside the relocation
     * space.
     */
    public static String mapInternalName(String internalName) {
        return NativeReloc.mapInternal(internalName);
    }

    /** Number of jars under management (diagnostics). */
    public static int jarCount() {
        ShadowClassLoader l = instance;
        return l == null ? 0 : l.jars.size();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] relocated = classBytesFor(name);
        definePackageWithMetadata(name);
        Class<?> c;
        try {
            c = defineClass(name, relocated, 0, relocated.length);
        } catch (Throwable t) {
            // The supertype chain that failed to resolve is invisible in the
            // caller's stack (the NCDFE surfaces at the TRIGGERING frame) —
            // log the class whose definition actually failed so autopsies
            // don't need bytecode archaeology.
            System.out.println("[c-imagine] shadow loader: define FAILED for "
                    + name + ": " + t);
            throw t;
        }
        defined.incrementAndGet();
        return c;
    }

    /**
     * Full class-bytes pipeline for one binary name: index → dist guard →
     * raw read → relocation → mixin gate. This is the byte stream the loader
     * would define for {@code name}; the mixin subsystem's bytecode provider
     * consumes the same path (§8: provider bytes = defined bytes).
     */
    public byte[] classBytesFor(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        IndexEntry source = locate(path);
        if (source == null) {
            throw new ClassNotFoundException(name);
        }
        // Dist parity (12-q): the real dedicated server's classloader REJECTS
        // game client classes outright — the cimscene probe-class on the
        // truth leg measured the exact contract:
        //   Class.forName("net.minecraft.client.gui.screens.Screen") on the
        //   REAL server → RuntimeException "Attempted to load class
        //   net/minecraft/client/gui/screens/Screen for invalid dist
        //   DEDICATED_SERVER", while Renderable (no patch → not even in the
        //   delta) → plain ClassNotFoundException.
        // The index still carries the delta's client entries (the union
        // model), but serving them is forbidden exactly as the real dist
        // checker forbids them. The rejection text names the ORIGINAL class
        // like the real server does.
        if (source.internalName().startsWith("net/minecraft/client/")) {
            String origDots = source.internalName().replace('/', '.');
            throw new RuntimeException("Attempted to load class " + origDots
                    + " for invalid dist DEDICATED_SERVER");
        }
        if (DEBUG_ORIG) {
            System.out.println("[c-imagine][dbg] findClass: " + name);
            if (name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")
                    || name.startsWith("net.neoforged.")) {
                for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                    System.out.println("[c-imagine][dbg]   at " + ste);
                }
            }
        }
        byte[] bytes = readBytes(source);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        // Relocation must be requested by the ON-DISK (original) internal
        // name — the requested name is already in the shadow space, and the
        // native stage would passthrough it (the guard against double
        // relocation would skip the rewrite entirely).
        byte[] relocated;
        try {
            if (source.jarIdx() >= modJarStart) {
                // M2 mod class: keep the original binary name; rewrite only
                // the references into the layer namespaces. Mixin never
                // transforms mod classes here (their @Mixin targets are
                // layer classes and arrive through the branch below).
                relocated = NativeReloc.relocateMod(source.internalName(), bytes);
            } else {
                relocated = NativeReloc.relocate(source.internalName(), bytes);
            }
        } catch (Throwable t) {
            throw new ClassNotFoundException(name + " (relocation failed: " + t + ")", t);
        }
        if (source.jarIdx() < modJarStart) {
            // §8 point 3: the mixin transformer sees every LAYER (game)
            // class before definition — the same point where the real
            // modlauncher chain applies transformations. The processor was
            // initialized before the first game class load (Bootstrap.init
            // order), and its config targets were translated through the
            // SAME relocation table, so names match.
            ClassBytecodeHook hook = bytecodeHook;
            if (hook != null) {
                relocated = hook.transform(name, relocated);
            }
        }
        return relocated;
    }

    /**
     * Mirror the real server's package metadata: the SecureJarHandler defines
     * every package WITH its jar's manifest attributes, so
     * {@code Class.getPackage().getImplementationVersion()} works there.
     * Our plain {@code defineClass} would create an EMPTY package — and the
     * first consumer of implementation versions in the shadow space is the
     * mixin subsystem's ASM version check (JAVA_21 configs).
     */
    private void definePackageWithMetadata(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        String pkg = name.substring(0, dot);
        if (getDefinedPackage(pkg) != null) {
            return;
        }
        // Manifest lookup needs the SOURCE jar (the index entry the bytes
        // came from) — re-locate; the index is built and hot.
        IndexEntry source;
        try {
            source = locate(name.replace('.', '/') + ".class");
        } catch (ClassNotFoundException e) {
            return;
        }
        java.util.jar.Manifest mf = source != null ? jarManifests.get(source.jarIdx()) : null;
        java.util.jar.Attributes main = mf == null ? null : mf.getMainAttributes();
        try {
            definePackage(pkg,
                    main == null ? null : main.getValue(java.util.jar.Attributes.Name.SPECIFICATION_TITLE),
                    main == null ? null : main.getValue(java.util.jar.Attributes.Name.SPECIFICATION_VERSION),
                    main == null ? null : main.getValue(java.util.jar.Attributes.Name.SPECIFICATION_VENDOR),
                    main == null ? null : main.getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_TITLE),
                    main == null ? null : main.getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION),
                    main == null ? null : main.getValue(java.util.jar.Attributes.Name.IMPLEMENTATION_VENDOR),
                    null);
        } catch (IllegalArgumentException raced) {
            // another thread defined the package first — fine
        }
    }

    private IndexEntry locate(String entryPath) throws ClassNotFoundException {
        Map<String, IndexEntry> idx = this.index;
        if (idx == null) {
            synchronized (this) {
                idx = this.index;
                if (idx == null) {
                    this.index = idx = buildIndex();
                }
            }
        }
        return idx.get(entryPath);
    }

    private byte[] readBytes(IndexEntry hit) throws ClassNotFoundException {
        JarFile jar = jars.get(hit.jarIdx());
        JarEntry entry = jar.getJarEntry(hit.entryPath());
        if (entry == null) {
            return null; // stale index entry
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new ClassNotFoundException(hit.entryPath() + " (read failed: " + e + ")", e);
        }
    }

    /** One-time central-directory index across every layer jar.
     *
     * Every entry (classes AND resources) is indexed under its original path;
     * class entries inside the relocation space additionally under their
     * <b>relocated</b> path (the name the shadow loader is asked for). */
    private Map<String, IndexEntry> buildIndex() {
        Map<String, IndexEntry> map = new HashMap<>(1 << 18);
        for (int i = 0; i < jars.size(); i++) {
            // The neoforge-<ver>-server.jar PATCHED DELTA is indexed in FULL,
            // client entries included: the binpatches it applies were cut
            // against the merged client∪server jar, so its client classes
            // (Screen, BakedModel, …) exist on the real dedicated server too.
            // Filtering them here diverged from the truth leg — Create.<clinit>
            // resolved BakedModel there and died on our side (CNFE at
            // Create.java:91, run 76d37e5). Dist-guard parity is preserved by
            // construction: classes NOT in the delta (Renderable) stay absent
            // from this index exactly as they are absent on the real server.
            var entries = jars.get(i).entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (name.endsWith("/") || name.endsWith("module-info.class")) {
                    continue;
                }
                if (name.endsWith(".class")) {
                    String internal = name.substring(0, name.length() - ".class".length());
                    map.putIfAbsent(name, new IndexEntry(i, name, internal));
                } else {
                    map.putIfAbsent(name, new IndexEntry(i, name));
                }
                if (name.endsWith(".class")) {
                    String internal = name.substring(0, name.length() - ".class".length());
                    String relocated = NativeReloc.mapInternal(internal);
                    if (relocated != null && !relocated.equals(internal)) {
                        // The relocated key must carry the ORIGINAL internal
                        // name: the native relocation stage rewrites by the
                        // on-disk name (the requested name is already shadow).
                        map.put(relocated + ".class", new IndexEntry(i, name, internal));
                    }
                }
            }
        }
        return map;
    }

    @Override
    protected URL findResource(String name) {
        try {
            Map<String, IndexEntry> idx = this.index;
            if (idx == null) {
                synchronized (this) {
                    idx = this.index;
                    if (idx == null) {
                        this.index = idx = buildIndex();
                    }
                }
            }
            IndexEntry hit = idx.get(name);
            if (hit == null) {
                return null;
            }
            // Accept only entries that really exist in the target jar (MR
            // resolution can redirect a logical name elsewhere).
            if (jars.get(hit.jarIdx()).getJarEntry(hit.entryPath()) == null) {
                return null;
            }
            String base = jarUrls.get(hit.jarIdx()).toString();
            if (!base.endsWith("/")) {
                base = base + "!/";
            }
            return new URL("jar:" + base + hit.entryPath());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public java.util.Enumeration<URL> getResources(String name) throws IOException {
        // FULL multi-jar enumeration: log4j-core aggregates its plugin caches
        // (Log4j2Plugins.dat — one per jar: core + FML) and ServiceLoader
        // providers (META-INF/services/*) through getResources. Returning only
        // the first hit silently broke the shadow-space log pipeline (0 core
        // converters → layout parse errors → dropped log lines) and would
        // break any multi-provider service lookup.
        List<URL> out = new ArrayList<>();
        for (int i = 0; i < jars.size(); i++) {
            if (jars.get(i).getJarEntry(name) == null) {
                continue;
            }
            String base = jarUrls.get(i).toString();
            if (!base.endsWith("/")) {
                base = base + "!/";
            }
            out.add(new URL("jar:" + base + name));
        }
        if (out.isEmpty() && name.startsWith("META-INF/services/")) {
            System.out.println("[c-imagine][dbg] getResources MISS: " + name);
        }
        return java.util.Collections.enumeration(out);
    }

    /** Child-first: JDK from the platform loader, everything else from here. */
    /**
     * Two-arg module-aware findClass: {@code Class.forName(Module, String)}
     * routes here through the final {@code loadClass(Module, String)} and the
     * DEFAULT implementation returns null for any named module. We load by
     * binary name through our own index; the caller's
     * {@code c.getModule() == module} attribution check stays authoritative
     * (modules are attributed by the package sets in the descriptors).
     */
    @Override
    protected Class<?> findClass(String moduleName, String name) {
        try {
            // Route through loadClass: dev.cim.** must hit the host-delegation
            // branch, not a fresh definition through the index.
            return loadClass(name, false);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Single-definition contract for the bootstrap classes: the bootstrap
        // jar rides BOTH the host classpath and this loader's jar set, and
        // defining it twice forks every static — the shadow copies of
        // NativeReloc (libPath never armed) and ShadowClassLoader (instance
        // never prepared) broke the mixin gate on exactly that (12-u).
        // dev.cim.bootstrap.** is the same jar on both sides by construction
        // — no version conflict is possible — so it delegates to the host
        // loader and every space shares one set of statics. dev.cim.layer.**
        // STAYS shadow-defined (the mixin classes bind the shadow space's
        // sponge-mixin types — a host definition would NCDFE there), and the
        // LAYER namespaces (relocated game/neoforge classes) stay hermetic
        // as designed.
        if (name.startsWith("dev.cim.bootstrap.")) {
            return HOST_LOADER.loadClass(name);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isJdkClass(name)) {
                    c = getPlatformClassLoader().loadClass(name);
                } else {
                    try {
                        c = findClass(name); // no host fallback: hermetic by design
                    } catch (ClassNotFoundException cnfe) {
                        // Autopsy telemetry: WHO requested the missing class?
                        // The NCDFE at the trigger frame hides the requesting
                        // chain; name the FULL chain here (loading call stacks
                        // through define-time supertype resolution are deep —
                        // e.g. Screen's Renderable superinterface fires from
                        // inside defineClass1 with only its caller visible
                        // one frame up). CI logs are unrestricted.
                        StackTraceElement[] st = new Throwable().getStackTrace();
                        StringBuilder callers = new StringBuilder();
                        for (int i = 2; i < Math.min(st.length, 30); i++) {
                            callers.append(" | ").append(st[i]);
                        }
                        System.out.println("[c-imagine] shadow loader: CNFE " + name
                                + " requested by" + callers);
                        throw cnfe;
                    }
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    /**
     * The host (bootstrap classpath) loader — dev.cim.** classes delegate
     * here (see loadClass). Referenced through whatever copy of THIS class
     * is in scope; both copies resolve it to the same host loader.
     */
    private static final ClassLoader HOST_LOADER = ShadowClassLoader.class.getClassLoader();

    private static boolean isJdkClass(String name) {
        for (String p : JDK_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
