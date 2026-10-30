package dev.cim.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Java-side entry point driven by the c-imagine native module (CRUSSTY).
 *
 * The native module deploys this jar to a temp dir and calls
 * {@link #init(String)} through JNI on a loader thread. This class must stay
 * dependency-free (JDK only) and bytecode-compatible with Java 21 (compiled
 * with {@code -release 21}) so it loads on any modern host JVM including the
 * Java 25 runtime Paper 26.3 requires.
 *
 * M1 contract: verify the embedded jar, arm the native relocation stage,
 * prepare the isolated {@link ShadowClassLoader} over the 1.21.1 layer jars,
 * and (when a layer dir is configured) boot the relocated vanilla stack
 * through {@code dev.cim.layer.LayerMain}, reporting status as JSON.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /**
     * Called via JNI by the native module.
     *
     * @param optionsJson {"jar": "<bootstrap jar>", "mods": "<mods dir>",
     *                    "kernel": "<paper jar>", "moduleLib": "<libimagine.so>",
     *                    "layerDir": "<1.21.1 libraries dir>"}
     * @return JSON status string; the native side logs it verbatim.
     */
    /** The layer manifest handed to {@link #init} (pre-scan by the native
     *  loader thread); consumed by {@link #loadMods} after the layer boot. */
    private static volatile String pendingManifest;

    public static String init(String optionsJson, String manifestJson) {
        long started = System.nanoTime();
        String jarPath = JsonKit.str(optionsJson, "jar");
        String modsDir = JsonKit.str(optionsJson, "mods");
        String kernel = JsonKit.str(optionsJson, "kernel");
        String moduleLib = JsonKit.str(optionsJson, "moduleLib");
        String layerDir = JsonKit.str(optionsJson, "layerDir");
        pendingManifest = manifestJson;

        int jarEntries = -1;
        if (jarPath != null) {
            try (JarFile jar = new JarFile(jarPath)) {
                jarEntries = jar.size();
            } catch (Exception e) {
                return status("error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                        jarEntries, 0, null, started);
            }
        }

        try {
            // 1) relocation stage must be armed before any layer class loads
            NativeReloc.setup(moduleLib);

            // 2) shadow class space: bootstrap jar + 1.21.1 library set
            ShadowClassLoader.prepare(jarPath, layerDir);

            // 2b) seed FMLLoader.dist BEFORE anything touches FMLEnvironment:
            // its <clinit> reads FMLLoader.getDist() once (static final) — the
            // M1 layer boot already runs vanilla classes that trip it.
            seedFmlDist();

            // 2c) M2 pre-boot: arm mod relocation rules, extend the loader
            // with mod jars and define the JPMS module layer BEFORE any layer
            // class loads — once the unnamed module owns a package
            // (cim.shadow.neoforge.** from the M1 boot), defineModules can no
            // longer claim it for the neoforge module
            // (LayerInstantiationException).
            if (manifestJson != null && !manifestJson.isEmpty()) {
                NativeReloc.registerMods(manifestJson);
                List<String> modJars = new ArrayList<>();
                Map<String, Object> manifest = MiniJson.parseObject(manifestJson);
                for (Object m : (List<?>) manifest.getOrDefault("mods", List.of())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mod = (Map<String, Object>) m;
                    String jar = String.valueOf(mod.getOrDefault("jar", ""));
                    boolean layerMod = Boolean.TRUE.equals(mod.get("layer"));
                    if (!jar.isEmpty() && !layerMod) {
                        modJars.add(jar);
                    }
                }
                // jarjar libraries (manifest `classpath`): no mod ids, so they
                // are NOT FML mod containers — but their classes must resolve
                // (create's constructor instantiates the jarjar'd Registrate).
                // Appended AFTER the mod jars so they land in the mod section
                // of the shadow index (refs-only relocation, original names).
                Object classpath = manifest.get("classpath");
                if (classpath instanceof List<?> libs) {
                    for (Object lib : libs) {
                        String jar = String.valueOf(lib);
                        if (!jar.isEmpty()) {
                            modJars.add(jar);
                        }
                    }
                }
                if (!modJars.isEmpty()) {
                    ShadowClassLoader.addModJars(modJars);
                }
                // §8 mixin gate: sponge-mixin initializes AFTER the shadow
                // loader + mod jars exist and BEFORE the first game class
                // loads (LayerMain.boot below) — every game class then passes
                // the transformer before definition, exactly like the real
                // server's transforming loader. Configs come from the
                // manifest (mods[].mixin_configs — universal, any mod) plus
                // the layer's own neoforge.mixins.json.
                Class<?> mixinGate = Class.forName("dev.cim.layer.MixinGate", true,
                        ShadowClassLoader.get());
                mixinGate.getMethod("init", String.class).invoke(null, manifestJson);
                Class<?> fmlHost = Class.forName("dev.cim.layer.FmlHost", true,
                        ShadowClassLoader.get());
                fmlHost.getMethod("defineLayerEarly", Map.class)
                        .invoke(null, manifest);
                // §8 mixin gate stage 2: the mod module packages are claimed
                // (defineLayerEarly above) and the game classes have not
                // loaded yet (LayerMain.boot below) — the only window where
                // the mixin phase may advance: the processor's config
                // promotion resolves every mixin class (must land in its
                // module, not the unnamed one) and the hook then transforms
                // every boot class (BlockBehaviour must carry its accessors
                // when the registries bootstrap).
                mixinGate.getMethod("activate").invoke(null);
            }

            // 3) M1: boot the relocated vanilla stack (registries bootstrap)
            String layerResult = null;
            if (layerDir != null && !layerDir.isEmpty()) {
                Class<?> layer = Class.forName("dev.cim.layer.LayerMain", true,
                        ShadowClassLoader.get());
                layerResult = (String) layer.getMethod("boot", int.class)
                        .invoke(null, ShadowClassLoader.loadedClassCount());
            }

            int loaded = ShadowClassLoader.loadedClassCount();
            int jars = ShadowClassLoader.jarCount();
            System.out.println("[c-imagine] bootstrap init: jar=" + jarPath
                    + " entries=" + jarEntries
                    + " mods=" + modsDir
                    + " kernel=" + kernel
                    + " shadowJars=" + jars);
            return status("ok", null, jarEntries, loaded, layerResult, started);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            Throwable c = t.getCause();
            if (c != null) {
                msg += " <- " + c.getClass().getSimpleName()
                        + (c.getMessage() != null ? ": " + c.getMessage() : "");
            }
            return status("error", msg, jarEntries, ShadowClassLoader.loadedClassCount(),
                    null, started);
        }
    }

    /**
     * Seed the shadow-space {@code FMLLoader.dist} = DEDICATED_SERVER before
     * {@code FMLEnvironment.<clinit>} snapshots it. Fail-soft: FML 1.21.1 is
     * on the classpath only when a layer dir is configured.
     */
    @SuppressWarnings("unchecked")
    private static void seedFmlDist() {
        try {
            ClassLoader shadow = ShadowClassLoader.get();
            Class<?> fmlLoader = Class.forName(
                    "cim.shadow.neoforge.net.neoforged.fml.loading.FMLLoader", false, shadow);
            Class<?> dist = Class.forName(
                    "cim.shadow.neoforge.net.neoforged.api.distmarker.Dist", false, shadow);
            Object dedicated = Enum.valueOf(
                    (Class<? extends Enum>) dist.asSubclass(Enum.class), "DEDICATED_SERVER");
            java.lang.reflect.Field f = fmlLoader.getDeclaredField("dist");
            f.setAccessible(true);
            f.set(null, dedicated);
            System.out.println("[c-imagine] fml dist seeded: DEDICATED_SERVER");

            // gamedir: FMLLoader.gamePath + FMLPaths absolute paths (the
            // real FML launch target does this before any mod class loads;
            // ConfigTracker.<clinit> needs FMLPaths.CONFIGDIR).
            Path gamePath = Path.of(System.getProperty("cim.gamedir", ".")).toAbsolutePath();
            java.lang.reflect.Field gp = fmlLoader.getDeclaredField("gamePath");
            gp.setAccessible(true);
            gp.set(null, gamePath);
            Class<?> fmlPaths = Class.forName(
                    "cim.shadow.neoforge.net.neoforged.fml.loading.FMLPaths", false, shadow);
            fmlPaths.getMethod("loadAbsolutePaths", Path.class)
                    .invoke(null, gamePath);

            // version info: NeoForgeMod's ctor reads FMLLoader.versionInfo()
            Class<?> versionInfo = Class.forName(
                    "cim.shadow.neoforge.net.neoforged.fml.loading.VersionInfo", false, shadow);
            Object vi = versionInfo.getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance("1.21.1", "4.0.45", "1.21.1", "1.21.1");
            java.lang.reflect.Field vf = fmlLoader.getDeclaredField("versionInfo");
            vf.setAccessible(true);
            vf.set(null, vi);
            System.out.println("[c-imagine] fml paths + versionInfo seeded: " + gamePath);
        } catch (Throwable t) {
            System.out.println("[c-imagine] fml env seed skipped: " + t);
        }
    }

    private static String status(String state, String error, int jarEntries,
                                 int shadowClasses, String layer, long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000L;
        return "{\"state\":\"" + state + "\""
                + (error != null ? ",\"error\":\"" + JsonKit.escape(error) + "\"" : "")
                + ",\"jarEntries\":" + jarEntries
                + ",\"shadowClasses\":" + shadowClasses
                + (layer != null ? ",\"layer\":" + layer : "")
                + ",\"tookMs\":" + ms
                + "}";
    }

    /**
     * M2: mod execution over the layer manifest produced by the native
     * scanner (cim-modscan). Called via JNI after {@link #init} succeeds.
     *
     * <p>Sequence:
     * <ol>
     *   <li>arm the native mod-space relocation rules (must precede any mod
     *       class definition);</li>
     *   <li>extend the shadow loader with the mod jars and rebuild the
     *       index (mod classes resolve under relocated paths);</li>
     *   <li>pre-relocate the manifest (entrypoint binary names, annotation
     *       descriptors, module package set) for the shadow-side host;</li>
     *   <li>run {@code dev.cim.layer.FmlHost} through the shadow loader —
     *       the real FML pipeline (construct + register + setup).</li>
     * </ol>
     *
     * @param manifestJson the {@code cim-modscan} layer manifest
     * @return JSON status string (logged by the native module)
     */
    public static String loadMods(String manifestJson) {
        long started = System.nanoTime();
        try {
            // The manifest arrived with init (pre-boot): relocation rules and
            // the JPMS layer were already armed there (order matters — see
            // init). Here: pre-relocate for the shadow-side host and run the
            // FML pipeline.
            if ((manifestJson == null || manifestJson.isEmpty())) {
                manifestJson = pendingManifest;
                pendingManifest = null;
            }
            if (manifestJson == null || manifestJson.isEmpty()) {
                return "{\"state\":\"skipped\",\"reason\":\"no manifest\"}";
            }
            Map<String, Object> manifest = MiniJson.parseObject(manifestJson);

            // pre-relocate the manifest for the shadow-side host
            relocateManifest(manifest);

            // 4) run the real FML pipeline inside the shadow space
            Class<?> fmlHost = Class.forName("dev.cim.layer.FmlHost", true,
                    ShadowClassLoader.get());
            Object result = fmlHost.getMethod("run", Map.class)
                    .invoke(null, manifest);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("[c-imagine] loadMods done in " + ms + " ms");
            return String.valueOf(result);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            Throwable c = t.getCause();
            if (c != null) {
                msg += " <- " + c.getClass().getSimpleName()
                        + (c.getMessage() != null ? ": " + c.getMessage() : "");
            }
            System.out.println("[c-imagine] loadMods FAILED: " + msg);
            t.printStackTrace();
            return "{\"state\":\"error\",\"error\":\"" + JsonKit.escape(msg) + "\"}";
        }
    }

    /**
     * Rewrite the manifest in place for shadow-space consumption:
     * entrypoint binary names, annotation class names and {@code L…;}
     * descriptors, and the module package set (original + relocated).
     */
    @SuppressWarnings("unchecked")
    private static void relocateManifest(Map<String, Object> manifest) {
        for (Object mo : (List<Object>) manifest.getOrDefault("mods", List.of())) {
            Map<String, Object> mod = (Map<String, Object>) mo;
            String id = String.valueOf(mod.get("id"));
            boolean layerMod = Boolean.TRUE.equals(mod.get("layer"));

            if (layerMod) {
                // Layer-flavoured mod (the neoforge universal jar): its
                // classes were FULLY shadow-relocated at M1 — entrypoints and
                // module packages are the RELOCATED names.
                List<String> relocatedEntry = new ArrayList<>();
                for (Object o : (List<Object>) mod.getOrDefault("entrypoints", List.of())) {
                    relocatedEntry.add(relocateName(String.valueOf(o)).replace('/', '.'));
                }
                mod.put("entrypoints", relocatedEntry);
                Set<String> modulePackages = new java.util.LinkedHashSet<>();
                for (Object o : (List<Object>) mod.getOrDefault("classes", List.of())) {
                    String mapped = relocateName(String.valueOf(o));
                    int slash = mapped.lastIndexOf('/');
                    if (slash > 0) {
                        modulePackages.add(mapped.substring(0, slash));
                    }
                }
                mod.put("modulePackages", new ArrayList<>(modulePackages));
                for (Object ao : (List<Object>) mod.getOrDefault("annotations", List.of())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> a = (Map<String, Object>) ao;
                    a.put("class", relocateName(String.valueOf(a.get("class"))));
                    a.put("descriptor", relocateDescriptor(String.valueOf(a.get("descriptor"))));
                    for (Object vo : (List<Object>) a.getOrDefault("values", List.of())) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> pair = (Map<String, Object>) vo;
                        relocateAnnValue(pair.get("value"));
                    }
                }
                continue;
            }

            // M2 refs-only design: mod classes keep their ORIGINAL binary
            // names (real-FML parity for ServiceLoader/reflection/resources) —
            // entrypoints stay as scanned.
            List<String> entrypoints = new ArrayList<>();
            for (Object o : (List<Object>) mod.getOrDefault("entrypoints", List.of())) {
                entrypoints.add(String.valueOf(o).replace('/', '.'));
            }
            mod.put("entrypoints", entrypoints);

            // modulePackages arrive from the Rust scan (full-depth original
            // packages) — no Java-side computation needed for regular mods.

            List<Object> relocatedAnnotations = new ArrayList<>();
            for (Object ao : (List<Object>) mod.getOrDefault("annotations", List.of())) {
                Map<String, Object> a = (Map<String, Object>) ao;
                // annotation CLASS stays original (the mod class keeps its
                // name); the annotation TYPE descriptor is relocated (compared
                // against relocated FML classes by AutomaticEventSubscriber).
                a.put("descriptor", relocateDescriptor(String.valueOf(a.get("descriptor"))));
                for (Object vo : (List<Object>) a.getOrDefault("values", List.of())) {
                    Map<String, Object> pair = (Map<String, Object>) vo;
                    relocateAnnValue(pair.get("value"));
                }
                relocatedAnnotations.add(a);
            }
            mod.put("annotations", relocatedAnnotations);
        }
    }

    @SuppressWarnings("unchecked")
    private static void relocateAnnValue(Object value) {
        if (!(value instanceof Map)) {
            return;
        }
        Map<String, Object> v = (Map<String, Object>) value;
        switch (String.valueOf(v.get("t"))) {
            case "Enum" -> {
                Map<String, Object> ev = (Map<String, Object>) v.get("v");
                ev.put("desc", relocateDescriptor(String.valueOf(ev.get("desc"))));
            }
            case "Class" -> v.put("v", relocateDescriptor(String.valueOf(v.get("v"))));
            case "List" -> {
                for (Object item : (List<Object>) v.get("v")) {
                    relocateAnnValue(item);
                }
            }
            default -> {
            }
        }
    }

    /** Map an internal (slash) name into the shadow space; passthrough on miss. */
    private static String relocateName(String internal) {
        String mapped = NativeReloc.available() ? NativeReloc.mapInternal(internal) : null;
        return mapped != null ? mapped : internal;
    }

    /** Relocate an {@code L…;} type descriptor (or passthrough on miss). */
    private static String relocateDescriptor(String descriptor) {
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            String internal = descriptor.substring(1, descriptor.length() - 1);
            return "L" + relocateName(internal) + ";";
        }
        return descriptor;
    }
}
