package dev.cim.layer;

import dev.cim.bootstrap.MiniJson;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M2 mini-FML host: drives the <b>real</b> FML 1.21.1 pipeline inside the
 * shadow space on top of the embedded 1.21.1 layer.
 *
 * <p>Design (docs/worklog.md Task 3.1): instead of re-implementing FML, this
 * class seeds the few statics FML expects from its own launcher environment
 * (dist, game layer, bindings), builds a real JPMS {@link ModuleLayer} whose
 * modules are backed by the {@code ShadowClassLoader}, then runs the same
 * building blocks a real NeoForge dedicated server runs:
 *
 * <ol>
 *   <li>{@code ModFile(SecureJar.from(jar), parser, attrs)} — real mods.toml
 *       parsing (NightConfig) → real {@code ModFileInfo}/{@code ModInfo};</li>
 *   <li>{@code ModList.of(...)} + {@code LoadingModList.of(...)} — real state;</li>
 *   <li>{@code FMLModContainer(info, entrypoints, scanData, layer)} — the real
 *       container: mod class loading with the real constructor-injection rule
 *       (IEventBus / ModContainer / FMLModContainer / Dist), real per-mod event
 *       bus, and {@code AutomaticEventSubscriber.inject} for
 *       {@code @EventBusSubscriber} classes (driven by scan data assembled
 *       from the native scan);</li>
 *   <li>{@code RegistryManager.postNewRegistryEvent()} →
 *       {@code GameData.unfreezeData()} → {@code postRegisterEvents()} →
 *       {@code freezeData()} — the real RegisterEvent flow, per registry in
 *       the real order, posted to mod buses in mod order;</li>
 *   <li>{@code FMLCommonSetupEvent} + {@code FMLDedicatedServerSetupEvent} per
 *       container, then {@code NeoForge.EVENT_BUS.start()}.</li>
 * </ol>
 *
 * <p>Everything reaches the relocated stack reflectively with the
 * <b>relocated</b> binary names ({@code cim.shadow.*}); compiling against the
 * layer is impossible by design. The manifest handed to {@link #run} is
 * pre-relocated by the bootstrap: entrypoint names, annotation descriptors
 * and class names already point into the shadow namespace.
 *
 * <p>Marker lines (CI greps them):
 *
 * <pre>
 * [c-imagine] mod &lt;id&gt;: constructed (v&lt;version&gt;)
 * [c-imagine] mod execution ok (mods=N, ...)
 * </pre>
 */
public final class FmlHost {

    // ---- relocated binary names (shadow space) --------------------------------
    private static final String FML_LOADER =
            "cim.shadow.neoforge.net.neoforged.fml.loading.FMLLoader";
    private static final String DIST =
            "cim.shadow.neoforge.net.neoforged.api.distmarker.Dist";
    private static final String I_BINDINGS =
            "cim.shadow.neoforge.net.neoforged.fml.IBindingsProvider";
    private static final String NEOFORGE =
            "cim.shadow.neoforge.net.neoforged.neoforge.common.NeoForge";
    private static final String EVENT =
            "cim.shadow.neoforge.net.neoforged.bus.api.Event";
    private static final String SECURE_JAR =
            "cpw.mods.jarhandling.SecureJar"; // cpw.mods.** is deliberately unrelocated
    private static final String MOD_FILE =
            "cim.shadow.neoforge.net.neoforged.fml.loading.moddiscovery.ModFile";
    private static final String MOD_FILE_PARSER =
            "cim.shadow.neoforge.net.neoforged.fml.loading.moddiscovery.ModFileParser";
    private static final String MOD_FILE_INFO_PARSER =
            "cim.shadow.neoforge.net.neoforged.neoforgespi.locating.ModFileInfoParser";
    private static final String I_MOD_FILE =
            "cim.shadow.neoforge.net.neoforged.neoforgespi.locating.IModFile";
    private static final String DISCOVERY_ATTRS =
            "cim.shadow.neoforge.net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes";
    private static final String I_MOD_INFO =
            "cim.shadow.neoforge.net.neoforged.neoforgespi.language.IModInfo";
    private static final String MOD_LIST =
            "cim.shadow.neoforge.net.neoforged.fml.ModList";
    private static final String LOADING_MOD_LIST =
            "cim.shadow.neoforge.net.neoforged.fml.loading.LoadingModList";
    private static final String MOD_CONTAINER =
            "cim.shadow.neoforge.net.neoforged.fml.ModContainer";
    private static final String FML_MOD_CONTAINER =
            "cim.shadow.neoforge.net.neoforged.fml.javafmlmod.FMLModContainer";
    private static final String SCAN_DATA =
            "cim.shadow.neoforge.net.neoforged.neoforgespi.language.ModFileScanData";
    private static final String ASM_TYPE = "org.objectweb.asm.Type";
    private static final String ENUM_HOLDER =
            "cim.shadow.neoforge.net.neoforged.fml.loading.modscan.ModAnnotation$EnumHolder";
    private static final String REGISTRY_MANAGER =
            "cim.shadow.neoforge.net.neoforged.neoforge.registries.RegistryManager";
    private static final String GAME_DATA =
            "cim.shadow.neoforge.net.neoforged.neoforge.registries.GameData";
    private static final String MOD_LOADING_CONTEXT =
            "cim.shadow.neoforge.net.neoforged.fml.ModLoadingContext";
    private static final String DEFERRED_QUEUE =
            "cim.shadow.neoforge.net.neoforged.fml.DeferredWorkQueue";
    private static final String COMMON_SETUP =
            "cim.shadow.neoforge.net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent";
    private static final String SERVER_SETUP =
            "cim.shadow.neoforge.net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent";
    private static final String BUILT_IN_REGISTRIES =
            "cim.shadow.mc1211.net.minecraft.core.registries.BuiltInRegistries";

    private static final String LOG = "[c-imagine]";

    private FmlHost() {
    }

    // ---- manifest model (pre-relocated by the bootstrap) ----------------------

    record ModSpec(
            String id,
            String jar,
            String version,
            List<String> entrypoints,
            Set<String> modulePackages,
            List<ServiceEntry> services,
            List<ScanAnn> annotations) {
    }

    record ServiceEntry(String service, List<String> providers) {
    }

    record ScanAnn(String descriptor, String clazz, String member, Map<String, Object> data) {
    }

    /**
     * Run the mini-FML pipeline over the (pre-relocated) layer manifest.
     *
     * @return JSON status: {"state":"ok",...} or {"state":"error","error":...}
     */
    public static String run(Map<String, Object> manifest) {
        long started = System.nanoTime();
        try {
            List<ModSpec> mods = parseManifest(manifest);
            System.out.println(LOG + " fml host: " + mods.size() + " mod(s) in manifest");
            if (mods.isEmpty()) {
                return "{\"state\":\"ok\",\"mods\":0}";
            }

            // 1) seed FML environment statics before anything touches FML classes
            seedEnvironment();

            // 2) real JPMS layer over the shadow loader (modules named by mod id)
            Object layer = defineLayer(mods);

            // 3) real ModFile / ModFileInfo / ModInfo per jar (real toml parsing)
            List<Object> modFiles = new ArrayList<>();
            List<Object> modInfos = new ArrayList<>();
            Map<ModSpec, Object> infoBySpec = new HashMap<>();
            for (ModSpec mod : mods) {
                Object file = buildModFile(mod);
                modFiles.add(file);
                Object fileInfo = call(file, "getModFileInfo");
                if (fileInfo == null) {
                    throw new IllegalStateException("ModFileInfo null for " + mod.id()
                            + " (mods.toml not found through the SecureJar proxy)");
                }
                List<?> infos = (List<?>) call(fileInfo, "getMods");
                for (Object info : infos) {
                    modInfos.add(info);
                    infoBySpec.put(mod, info);
                }
            }

            // 4) real ModList + LoadingModList
            Class<?> modListCls = Class.forName(MOD_LIST);
            Method of = modListCls.getDeclaredMethod("of", List.class, List.class);
            of.setAccessible(true);
            Object modList = of.invoke(null, modFiles, modInfos);
            Class<?> loadingModListCls = Class.forName(LOADING_MOD_LIST);
            Method lof = loadingModListCls.getDeclaredMethod("of",
                    List.class, List.class, List.class, List.class, Map.class);
            lof.setAccessible(true);
            Object loadingModList = lof.invoke(null, modFiles, modFiles, modInfos, List.of(), Map.of());
            setStatic(Class.forName(FML_LOADER), "loadingModList", loadingModList);

            // 5) real containers (class loading only — mod constructors do NOT
            // run here; real FML constructs all containers first so that
            // ModList.isLoaded works during mod <init>)
            Class<?> containerCls = Class.forName(FML_MOD_CONTAINER);
            Constructor<?> containerCtor = containerCls.getConstructor(
                    Class.forName(I_MOD_INFO),
                    List.class, Class.forName(SCAN_DATA), java.lang.ModuleLayer.class);
            Method construct = containerCls.getDeclaredMethod("constructMod");
            construct.setAccessible(true);
            List<Object> containers = new ArrayList<>();
            // Paired with containers index-by-index: mods skipped by the dist
            // filter are excluded here too, so downstream (mod, container)
            // parallel loops stay aligned.
            List<ModSpec> containerMods = new ArrayList<>();
            for (ModSpec mod : mods) {
                Object scanData = buildScanData(mod);
                Object info = infoBySpec.get(mod);
                if (info == null) {
                    throw new IllegalStateException("no ModInfo for " + mod.id());
                }
                List<String> entrypoints = distFilteredEntrypoints(mod);
                if (entrypoints.isEmpty()) {
                    System.out.println(LOG + " mod " + mod.id()
                            + ": no server-side entrypoints (dist filter) — skipping");
                    continue;
                }
                containers.add(containerCtor.newInstance(
                        info, entrypoints, scanData, layer));
                containerMods.add(mod);
            }

            // containers into ModList (package-private; load order preserved)
            Method setLoaded = modListCls.getDeclaredMethod("setLoadedMods", List.class);
            setLoaded.setAccessible(true);
            setLoaded.invoke(modList, containers);
            try {
                setStatic(Class.forName(
                        "cim.shadow.neoforge.net.neoforged.fml.ModLoader"), "modList", modList);
            } catch (Throwable ignore) {
                // optional static; mods read ModList.get() which is already set
            }

            if (Boolean.getBoolean("cim.debug.services")) {
                try {
                    Class<?> slIf = Class.forName("cim.shadow.mojang.net.mezzdev.config.api.Configs$IConfigProvider", false, shadowLoader());
                    System.out.println("[c-imagine][dbg] IConfigProvider (relocated lookup) -> " + slIf);
                } catch (Throwable t) {
                    System.out.println("[c-imagine][dbg] relocated lookup failed (expected): " + t);
                }
                try {
                    Class<?> slIf = Class.forName("net.mezzdev.config.api.Configs$IConfigProvider", false, shadowLoader());
                    System.out.println("[c-imagine][dbg] IConfigProvider (original) -> " + slIf
                            + " loader=" + slIf.getClassLoader());
                    java.util.Enumeration<java.net.URL> res = shadowLoader()
                            .getResources("META-INF/services/net.mezzdev.config.api.Configs$IConfigProvider");
                    int rc = 0;
                    while (res.hasMoreElements()) {
                        System.out.println("[c-imagine][dbg] service url[" + (rc++) + "] = " + res.nextElement());
                    }
                    System.out.println("[c-imagine][dbg] service resources: " + rc);
                    Object sl = java.util.ServiceLoader.load(slIf, shadowLoader());
                    java.util.Iterator<?> it = ((Iterable<?>) sl).iterator();
                    int n = 0;
                    while (it.hasNext()) {
                        System.out.println("[c-imagine][dbg] provider[" + (n++) + "] = " + it.next());
                    }
                    System.out.println("[c-imagine][dbg] providers found: " + n);
                    Object mzMod = layer.getClass().getMethod("findModule", String.class).invoke(layer, "mezz_config");
                    Object mz = ((java.util.Optional<?>) mzMod).orElse(null);
                    if (mz != null) {
                        Object desc = mz.getClass().getMethod("getDescriptor").invoke(mz);
                        Object provides = desc.getClass().getMethod("provides").invoke(desc);
                        System.out.println("[c-imagine][dbg] mezz_config provides = " + provides);
                    }
                } catch (Throwable t) {
                    System.out.println("[c-imagine][dbg] service probe failed: " + t);
                }
            }

            // 5a) P0 Create: custom registry CREATION during mod construction.
            // Registrate-driven mods (create) instantiate their own registries
            // and register them into the ROOT registry inside <clinit>
            // (CreateBuiltInRegistries.register → BuiltInRegistries.REGISTRY
            // .register(...)). On real NeoForge the construct phase runs with
            // the ROOT registry WRITABLE — GameData.freezeData() later freezes
            // only the CONTENT registries, never the root. Replicate that
            // here: unfreeze the root registry itself before constructing
            // mods. Content registries stay in their post-bootstrap state;
            // GameData.unfreezeData() below still runs at the real NeoForge
            // position (after NewRegistryEvent).
            try {
                Object rootRegistry = getStatic(
                        Class.forName(BUILT_IN_REGISTRIES), "REGISTRY");
                rootRegistry.getClass()
                        .getMethod("unfreeze")
                        .invoke(rootRegistry);
                System.out.println(LOG
                        + " root registry unfrozen for mod construction (real-NeoForge construct-phase contract)");
            } catch (NoSuchMethodException e) {
                System.out.println(LOG
                        + " root registry has no unfreeze() — root state unchanged: " + e);
            }

            // 5b) construct mods in order (real FML: constructMod + ConstructMod
            // event per container, AFTER ModList is fully populated), then run
            // the shared work queue.
            Class<?> constructModEventCls = Class.forName(
                    "cim.shadow.neoforge.net.neoforged.fml.event.lifecycle.FMLConstructModEvent");
            Method acceptEvent = Class.forName(MOD_CONTAINER)
                    .getMethod("acceptEvent", Class.forName(EVENT));
            Object queue = Class.forName(DEFERRED_QUEUE)
                    .getConstructor(String.class).newInstance("Mod Construction");
            for (int i = 0; i < containerMods.size(); i++) {
                ModSpec mod = containerMods.get(i);
                Object container = containers.get(i);
                try {
                    construct.invoke(container);
                    System.out.println(LOG + " mod " + mod.id()
                            + ": constructed (v" + mod.version() + ")");
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new RuntimeException("construct " + mod.id() + ": " + cause, cause);
                }
                Object event = constructModEventCls
                        .getConstructor(Class.forName(MOD_CONTAINER), Class.forName(DEFERRED_QUEUE))
                        .newInstance(container, queue);
                acceptEvent.invoke(container, event);
            }
            Class.forName(DEFERRED_QUEUE).getMethod("runTasks").invoke(queue);

            if (Boolean.getBoolean("cim.diag")) {
                diagRegisterProbe(containers);
            }

            // 6) real registration flow (per-registry RegisterEvent, mod order)
            Class.forName(REGISTRY_MANAGER).getMethod("postNewRegistryEvent").invoke(null);
            Class<?> gameData = Class.forName(GAME_DATA);
            gameData.getMethod("unfreezeData").invoke(null);
            Map<String, Integer> registered = postRegisterEvents(gameData);
            gameData.getMethod("freezeData").invoke(null);

            // 7) lifecycle: common setup + dedicated server setup, per container
            fireSetupEvents(containers);

            // 8) start the game bus (mirrors ServerModLoader: after registration)
            Object gameBus = getStatic(Class.forName(NEOFORGE), "EVENT_BUS");
            gameBus.getClass().getMethod("start").invoke(gameBus);

            // 9) census of modded entries + status
            Map<String, Object> modded = census(mods);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            System.out.println(LOG + " mod execution ok (mods=" + containers.size()
                    + ", registered=" + registered + ", moddedEntries=" + modded
                    + ", " + ms + " ms)");
            return "{\"state\":\"ok\",\"mods\":" + containers.size()
                    + ",\"registered\":" + MiniJson.json(registered)
                    + ",\"moddedEntries\":" + MiniJson.json(modded)
                    + ",\"tookMs\":" + ms + "}";
        } catch (Throwable t) {
            while (t instanceof InvocationTargetException && t.getCause() != null) {
                t = t.getCause();
            }
            System.out.println(LOG + " mod execution FAILED: " + t);
            t.printStackTrace();
            String msg = String.valueOf(t).replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", " ").replace("\r", " ");
            return "{\"state\":\"error\",\"error\":\"" + msg + "\"}";
        }
    }

    // ---- environment seeding ---------------------------------------------------

    /**
     * Seed the FMLLoader statics the shadow-space FML code expects from its
     * own launcher: {@code dist}, {@code bindings} (game bus provider) and,
     * later, {@code gameLayer}. Must run before FMLEnvironment's &lt;clinit&gt;
     * reads {@code FMLLoader.getDist()}.
     */
    private static void seedEnvironment() throws Exception {
        @SuppressWarnings("unchecked")
        Class<? extends Enum<?>> distCls =
                (Class<? extends Enum<?>>) Class.forName(DIST).asSubclass(Enum.class);
        Object dedicatedServer = Enum.valueOf(
                (Class<? extends Enum>) distCls.asSubclass(Enum.class), "DEDICATED_SERVER");
        setStatic(Class.forName(FML_LOADER), "dist", dedicatedServer);

        // game bus = NeoForge.EVENT_BUS (created shut-down; started after registration)
        Object gameBus = getStatic(Class.forName(NEOFORGE), "EVENT_BUS");

        // IBindingsProvider proxy handing out the game bus (FMLLoader.bindings)
        Class<?> bindingsCls = Class.forName(I_BINDINGS);
        Object bindings = Proxy.newProxyInstance(shadowLoader(), new Class<?>[] {bindingsCls},
                (proxy, method, args) -> {
                    if ("getGameBus".equals(method.getName())) {
                        return gameBus;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt.isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
        setStatic(Class.forName(FML_LOADER), "bindings", bindings);
    }

    // ---- JPMS layer over the shadow loader --------------------------------------

    /** The JPMS layer built during {@code Bootstrap.init} (pre-boot). */
    private static volatile Object prebuiltLayer;

    /**
     * Define the module layer BEFORE the M1 layer boot (called from
     * {@code Bootstrap.init}). Once the shadow loader's unnamed module owns
     * {@code cim.shadow.neoforge.**} packages, {@code defineModules} can no
     * longer claim them for the neoforge module.
     */
    public static void defineLayerEarly(Map<String, Object> manifest) {
        List<ModSpec> mods = parseManifest(manifest);
        prebuiltLayer = defineLayerInternal(mods);
    }

    /**
     * Real FML honors {@code @Mod(dist = ...)}: client-only entrypoints are
     * skipped on a dedicated server (the neoforge universal jar ships
     * {@code ClientNeoForgeMod} with {@code dist = Dist.CLIENT} next to the
     * server entrypoint). Only entrypoints whose {@code @Mod} target has no
     * dist or a DEDICATED_SERVER/ANY dist survive.
     */
    private static List<String> distFilteredEntrypoints(ModSpec mod) {
        List<String> out = new ArrayList<>();
        if (Boolean.getBoolean("cim.debug.modulelayer")) {
            for (ScanAnn ann : mod.annotations()) {
                System.out.println("[c-imagine][dbg] ann " + ann.descriptor() + " clazz=" + ann.clazz()
                        + " data=" + ann.data().keySet() + " dist=" + ann.data().get("dist"));
            }
        }
        for (String entry : mod.entrypoints()) {
            String dist = null;
            for (ScanAnn ann : mod.annotations()) {
                if (!ann.descriptor().contains("/fml/common/Mod;")) {
                    continue;
                }
                if (!entry.replace('.', '/').equals(ann.clazz())) {
                    continue;
                }
                Object d = ann.data().get("dist");
                if (d != null) {
                    dist = String.valueOf(invokeValue(d));
                }
            }
            if (dist == null || dist.equals("DEDICATED_SERVER") || dist.equals("ANY")) {
                out.add(entry);
            } else {
                System.out.println(LOG + " entrypoint " + entry
                        + " skipped (dist=" + dist + ")");
            }
        }
        return out;
    }

    private static Object invokeValue(Object enumHolder) {
        try {
            return enumHolder.getClass().getMethod("value").invoke(enumHolder);
        } catch (Exception e) {
            return enumHolder;
        }
    }

    private static Object defineLayer(List<ModSpec> mods) {
        if (prebuiltLayer != null) {
            return prebuiltLayer;
        }
        return defineLayerInternal(mods);
    }

    private static Object defineLayerInternal(List<ModSpec> mods) {
        List<String> names = new ArrayList<>();
        Map<String, Set<String>> packagesBy = new HashMap<>();
        Map<String, String> jarsBy = new HashMap<>();
        for (ModSpec mod : mods) {
            names.add(mod.id());
            packagesBy.put(mod.id(), mod.modulePackages());
            jarsBy.put(mod.id(), mod.jar());
        }
        Map<String, Map<String, List<String>>> servicesBy = new HashMap<>();
        for (ModSpec mod : mods) {
            if (!mod.services().isEmpty()) {
                Map<String, List<String>> byService = new HashMap<>();
                for (ServiceEntry se : mod.services()) {
                    byService.put(se.service(), se.providers());
                }
                servicesBy.put(mod.id(), byService);
            }
        }
        ClassLoader shadow = shadowLoader();
        Object layer = ShadowModuleRef.defineLayer(names, packagesBy, jarsBy, servicesBy, name -> shadow);
        try {
            setStatic(Class.forName(FML_LOADER), "gameLayer", layer);
        } catch (Exception e) {
            throw new IllegalStateException("seed gameLayer: " + e, e);
        }
        System.out.println(LOG + " fml host: module layer over " + mods.size()
                + " mod module(s)");
        return layer;
    }

    private static ClassLoader shadowLoader() {
        // FmlHost itself is defined by the shadow loader, so its defining
        // loader IS the shadow space — no reflection needed. (Calling the
        // shadow COPY of ShadowClassLoader.get() would read the wrong
        // statics: the bootstrap jar exists in both the host loader and the
        // shadow space as separate classes.)
        ClassLoader cl = FmlHost.class.getClassLoader();
        if (cl == null) {
            throw new IllegalStateException("shadow loader is the bootstrap loader?!");
        }
        return cl;
    }

    // ---- ModFile / scan data -----------------------------------------------------

    private static Object buildModFile(ModSpec mod) throws Exception {
        Object secureJar = proxySecureJar(mod);

        Class<?> parserIf = Class.forName(MOD_FILE_INFO_PARSER);
        Method modsToml = Class.forName(MOD_FILE_PARSER)
                .getMethod("modsTomlParser", Class.forName(I_MOD_FILE));
        final String dbgId = mod.id();
        Object parser = Proxy.newProxyInstance(shadowLoader(), new Class<?>[] {parserIf},
                (proxy, method, args) -> {
                    System.out.println("[c-imagine][dbg] parser." + method.getName() + " for " + dbgId);
                    return modsToml.invoke(null, args[0]);
                });

        Class<?> attrsCls = Class.forName(DISCOVERY_ATTRS);
        Object attrs = getStatic(attrsCls, "DEFAULT");
        Class<?> mfCls = Class.forName(MOD_FILE);
        Constructor<?> ctor = mfCls.getConstructor(Class.forName(SECURE_JAR), parserIf, attrsCls);
        return ctor.newInstance(secureJar, parser, attrs);
    }

    /**
     * A {@code SecureJar} proxy over the mod jar file.
     *
     * <p>The real {@code SecureJar.from(Path)} needs the
     * {@code UnionFileSystemProvider} installed at JVM boot
     * ({@code FileSystemProvider.installedProviders()} is immutable after
     * startup and our jars are never on the system classpath), so the
     * interface is served by hand: the manifest is read through a plain
     * {@code JarFile}, and resource lookups ({@code getPath}) extract single
     * entries to a temp dir — the only consumer on this path is
     * {@code ModFileParser.modsTomlParser} reading {@code neoforge.mods.toml}.
     */
    private static Object proxySecureJar(ModSpec mod) throws Exception {
        Class<?> sjIf = Class.forName(SECURE_JAR);
        Class<?> mdpIf = Class.forName(SECURE_JAR + "$ModuleDataProvider");
        Path jarPath = Path.of(mod.jar());
        java.util.jar.Manifest manifest = readManifest(jarPath);
        Object moduleDescriptor = java.lang.module.ModuleDescriptor
                .newOpenModule(mod.id()).build();
        Map<String, Path> extracted = new HashMap<>();

        Object mdp = Proxy.newProxyInstance(shadowLoader(), new Class<?>[] {mdpIf},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> mod.id();
                    case "descriptor" -> moduleDescriptor;
                    case "uri" -> jarPath.toUri();
                    case "findFile" -> java.util.Optional.empty();
                    case "open" -> java.util.Optional.empty();
                    case "getManifest" -> manifest;
                    case "verifyAndGetSigners" -> null;
                    default -> defaultValue(method);
                });

        return Proxy.newProxyInstance(shadowLoader(), new Class<?>[] {sjIf},
                (proxy, method, args) -> switch (method.getName()) {
                    case "moduleDataProvider" -> mdp;
                    case "name" -> mod.id();
                    case "getPrimaryPath", "getRootPath" -> jarPath;
                    case "getPath" -> extractEntry(jarPath, joinPaths(args), extracted);
                    case "getManifestSigners", "getTrustedManifestEntries" -> null;
                    case "verifyAndGetSigners" -> null;
                    case "hasSecurityData" -> false;
                    case "verifyPath", "getFileStatus" -> defaultValue(method);
                    case "close" -> null;
                    default -> defaultValue(method);
                });
    }

    private static java.util.jar.Manifest readManifest(Path jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            java.util.jar.Manifest m = jf.getManifest();
            return m != null ? m : new java.util.jar.Manifest();
        } catch (Exception e) {
            return new java.util.jar.Manifest();
        }
    }

    private static String joinPaths(Object[] args) {
        StringBuilder sb = new StringBuilder(String.valueOf(args[0]));
        for (int i = 1; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof String[] rest) {
                for (String r : rest) {
                    sb.append('/').append(r);
                }
            } else if (a != null) {
                sb.append('/').append(a);
            }
        }
        return sb.toString();
    }

    private static final Map<String, Path> EXTRACT_ROOT = new HashMap<>();

    private static Path extractEntry(Path jar, String entryPath, Map<String, Path> cache) {
        return cache.computeIfAbsent(entryPath, k -> {
            String safe = k.replace('/', '_').replace("..", "__");
            Path out = stdTempDir().resolve("cim-securejar")
                    .resolve(String.valueOf(jar.getFileName()))
                    .resolve(safe);
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
                java.util.jar.JarEntry entry = jf.getJarEntry(k);
                if (entry == null) {
                    // Distinguishable missing path: exists() stays false.
                    System.out.println("[c-imagine][dbg] getPath miss: jar=" + jar + " entry=" + k);
                    return out.resolve("__missing__");
                }
                System.out.println("[c-imagine][dbg] getPath hit: " + k);
                try (var in = jf.getInputStream(entry)) {
                    java.nio.file.Files.createDirectories(out.getParent());
                    java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return out;
            } catch (Exception e) {
                System.out.println("[c-imagine][dbg] getPath EXTRACT ERROR: jar=" + jar
                        + " entry=" + k + " -> " + e);
                return out.resolve("__error__");
            }
        });
    }

    private static Path stdTempDir() {
        return Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
    }

    /** Sensible defaults for proxy methods we never expect to be called. */
    private static Object defaultValue(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == boolean.class) {
            return false;
        }
        if (rt.isPrimitive()) {
            return 0;
        }
        if (rt.isEnum()) {
            @SuppressWarnings("unchecked")
            Object[] constants = ((Class<? extends Enum>) rt.asSubclass(Enum.class)).getEnumConstants();
            return constants != null && constants.length > 0 ? constants[0] : null;
        }
        return null;
    }

    /** Build a real ModFileScanData from the manifest's annotation hits. */
    private static Object buildScanData(ModSpec mod) throws Exception {
        Class<?> sdCls = Class.forName(SCAN_DATA);
        Object scanData = sdCls.getConstructor().newInstance();
        Class<?> adCls = Class.forName(SCAN_DATA + "$AnnotationData");
        Constructor<?> adCtor = adCtor(adCls);
        Class<?> typeCls = Class.forName(ASM_TYPE);
        Method getType = typeCls.getMethod("getType", String.class);
        // clazz is an INTERNAL name — getObjectType, not getType (descriptors).
        Method getObjectType = typeCls.getMethod("getObjectType", String.class);
        @SuppressWarnings("unchecked")
        Set<Object> annotations = (Set<Object>) sdCls.getMethod("getAnnotations").invoke(scanData);

        for (ScanAnn ann : mod.annotations()) {
            Object annType = getType.invoke(null, ann.descriptor());
            Object targetType = Enum.valueOf(
                    (Class<? extends Enum>) Class.forName("java.lang.annotation.ElementType")
                            .asSubclass(Enum.class),
                    ann.member() != null ? "METHOD" : "TYPE");
            Object clazz = getObjectType.invoke(null, ann.clazz());
            annotations.add(adCtor.newInstance(annType, targetType, clazz,
                    ann.member(), ann.data()));
        }
        return scanData;
    }

    private static Constructor<?> adCtor(Class<?> adCls) throws Exception {
        Class<?> typeCls = Class.forName(ASM_TYPE);
        Class<?> elementType = Class.forName("java.lang.annotation.ElementType");
        for (Constructor<?> c : adCls.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 5 && p[0] == typeCls && p[1] == elementType
                    && p[2] == typeCls && p[3] == String.class && p[4] == Map.class) {
                return c;
            }
        }
        throw new NoSuchMethodException("ModFileScanData$AnnotationData record ctor");
    }

    // ---- registration + lifecycle --------------------------------------------------

    /** Run GameData.postRegisterEvents() and diff the registry census. */
    private static Map<String, Integer> postRegisterEvents(Class<?> gameData) throws Exception {
        Map<String, Integer> before = registryCensus();
        gameData.getMethod("postRegisterEvents").invoke(null);
        Map<String, Integer> after = registryCensus();
        Map<String, Integer> delta = new HashMap<>();
        for (String k : after.keySet()) {
            int d = after.get(k) - before.getOrDefault(k, 0);
            if (d != 0) {
                delta.put(k, d);
            }
        }
        return delta;
    }

    private static void fireSetupEvents(List<Object> containers) throws Exception {
        Class<?> ctxCls = Class.forName(MOD_LOADING_CONTEXT);
        Object ctx = ctxCls.getMethod("get").invoke(null);
        Method setActive = ctxCls.getMethod("setActiveContainer", Class.forName(MOD_CONTAINER));
        Method acceptEvent = Class.forName(MOD_CONTAINER)
                .getMethod("acceptEvent", Class.forName(EVENT));
        Class<?> queueCls = Class.forName(DEFERRED_QUEUE);
        for (Object container : containers) {
            setActive.invoke(ctx, container);
            for (String evt : new String[] {COMMON_SETUP, SERVER_SETUP}) {
                Class<?> evtCls = Class.forName(evt);
                Object queue = queueCls.getConstructor(String.class)
                        .newInstance("cim-" + evtCls.getSimpleName());
                Object event = evtCls
                        .getConstructor(Class.forName(MOD_CONTAINER), queueCls)
                        .newInstance(container, queue);
                acceptEvent.invoke(container, event);
                queueCls.getMethod("runTasks").invoke(queue);
            }
            setActive.invoke(ctx, (Object) null);
        }
    }

    // ---- census -------------------------------------------------------------------

    private static Map<String, Integer> registryCensus() throws Exception {
        Map<String, Integer> out = new HashMap<>();
        Class<?> bir = Class.forName(BUILT_IN_REGISTRIES);
        for (String field : new String[] {"BLOCK", "ITEM", "BLOCK_ENTITY_TYPE", "ENTITY_TYPE"}) {
            try {
                Object registry = getStatic(bir, field);
                Set<?> keys = (Set<?>) registry.getClass().getMethod("keySet").invoke(registry);
                out.put(field, keys.size());
            } catch (Throwable t) {
                out.put(field, -1);
            }
        }
        return out;
    }

    /** List entries registered under the loaded mods' namespaces. */
    private static Map<String, Object> census(List<ModSpec> mods) throws Exception {
        Set<String> prefixes = new LinkedHashSet<>();
        for (ModSpec mod : mods) {
            prefixes.add(mod.id() + ":");
        }
        Map<String, Object> out = new HashMap<>();
        Class<?> bir = Class.forName(BUILT_IN_REGISTRIES);
        for (String field : new String[] {"BLOCK", "ITEM", "BLOCK_ENTITY_TYPE", "ENTITY_TYPE"}) {
            try {
                Object registry = getStatic(bir, field);
                Set<?> keys = (Set<?>) registry.getClass().getMethod("keySet").invoke(registry);
                List<String> names = new ArrayList<>();
                for (Object k : keys) {
                    String s = String.valueOf(k);
                    for (String p : prefixes) {
                        if (s.startsWith(p)) {
                            names.add(s);
                        }
                    }
                }
                out.put(field, names);
            } catch (Throwable t) {
                out.put(field, List.of());
            }
        }
        return out;
    }

    // ---- manifest parsing -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    static List<ModSpec> parseManifest(Map<String, Object> manifest) {
        List<Object> modsArr = (List<Object>) manifest.get("mods");
        List<ModSpec> mods = new ArrayList<>();
        if (modsArr == null) {
            return mods;
        }
        Set<String> order = new LinkedHashSet<>();
        List<Object> loadOrder = (List<Object>) manifest.get("loadOrder");
        if (loadOrder != null) {
            for (Object o : loadOrder) {
                order.add(String.valueOf(o));
            }
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Object o : modsArr) {
            Map<String, Object> m = (Map<String, Object>) o;
            byId.put(String.valueOf(m.get("id")), m);
        }
        // iterate in load order; manifest order for anything unlisted
        List<String> ids = new ArrayList<>();
        for (String id : order) {
            if (byId.containsKey(id)) {
                ids.add(id);
            }
        }
        for (Object mo : modsArr) {
            Map<String, Object> m = (Map<String, Object>) mo;
            String id = String.valueOf(m.get("id"));
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        for (String id : ids) {
            Map<String, Object> m = byId.get(id);
            if (m == null) {
                continue;
            }
            Set<String> modulePackages = new LinkedHashSet<>(strings(m.get("modulePackages")));
            List<ServiceEntry> services = new ArrayList<>();
            List<Object> svcArr = (List<Object>) m.get("services");
            if (svcArr != null) {
                for (Object so : svcArr) {
                    Map<String, Object> sv = (Map<String, Object>) so;
                    services.add(new ServiceEntry(
                            String.valueOf(sv.get("service")),
                            strings(sv.get("providers"))));
                }
            }
            mods.add(new ModSpec(
                    id,
                    String.valueOf(m.get("jar")),
                    String.valueOf(m.get("version")),
                    strings(m.get("entrypoints")),
                    modulePackages,
                    services,
                    parseAnnotations(m)));
        }
        return mods;
    }

    @SuppressWarnings("unchecked")
    private static List<ScanAnn> parseAnnotations(Map<String, Object> m) {
        List<ScanAnn> annotations = new ArrayList<>();
        List<Object> annArr = (List<Object>) m.get("annotations");
        if (annArr == null) {
            return annotations;
        }
        for (Object ao : annArr) {
            Map<String, Object> a = (Map<String, Object>) ao;
            Map<String, Object> data = new HashMap<>();
            List<Object> values = (List<Object>) a.get("values");
            if (values != null) {
                for (Object vo : values) {
                    Map<String, Object> pair = (Map<String, Object>) vo;
                    data.put(String.valueOf(pair.get("name")),
                            convertAnnValue(pair.get("value")));
                }
            }
            String member = null;
            Map<String, Object> mem = (Map<String, Object>) a.get("member");
            if (mem != null) {
                member = String.valueOf(mem.get("method"));
            }
            annotations.add(new ScanAnn(
                    String.valueOf(a.get("descriptor")),
                    String.valueOf(a.get("class")),
                    member,
                    data));
        }
        return annotations;
    }

    /** Convert a tagged annotation value (manifest JSON) into its Java form. */
    @SuppressWarnings("unchecked")
    private static Object convertAnnValue(Object raw) {
        if (!(raw instanceof Map)) {
            return raw;
        }
        Map<String, Object> v = (Map<String, Object>) raw;
        String t = String.valueOf(v.get("t"));
        Object val = v.get("v");
        switch (t) {
            case "Bool":
                return val instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(val));
            case "Int":
                return val instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(val));
            case "Float":
                return val instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(val));
            case "Str":
                return String.valueOf(val);
            case "Enum": {
                try {
                    Map<String, Object> ev = (Map<String, Object>) val;
                    return Class.forName(ENUM_HOLDER)
                            .getConstructor(String.class, String.class)
                            .newInstance(String.valueOf(ev.get("desc")),
                                    String.valueOf(ev.get("constant")));
                } catch (Exception e) {
                    return String.valueOf(val);
                }
            }
            case "Class":
                // already relocated by the bootstrap (descriptor rewritten)
                try {
                    return Class.forName(ASM_TYPE).getMethod("getType", String.class)
                            .invoke(null, String.valueOf(val));
                } catch (Exception e) {
                    return String.valueOf(val);
                }
            case "List": {
                List<Object> out = new ArrayList<>();
                for (Object item : (List<Object>) val) {
                    out.add(convertAnnValue(item));
                }
                return out;
            }
            default:
                return String.valueOf(val);
        }
    }

    // ---- helpers -----------------------------------------------------------------

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    private static void setStatic(Class<?> cls, String field, Object value) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    /**
     * Diagnostics for the registration pipeline (enabled with
     * {@code -Dcim.diag=1}).
     *
     * <ol>
     *   <li>Verifies the shadow-space {@code ModList.get()} identity and
     *       container count — {@code GameData.postRegisterEvents()} posts
     *       through {@code ModList.get()}; a detached instance silently
     *       drops every event.</li>
     *   <li>Verifies every container's event bus is non-null (acceptEvent
     *       returns silently on null).</li>
     *   <li>Manually posts a {@code RegisterEvent} for the ITEM registry on
     *       every container and measures the registry delta — isolates
     *       "events not posted" from "listeners not attached".</li>
     * </ol>
     */
    private static void diagRegisterProbe(List<Object> containers) {
        try {
            Object modList = getStatic(Class.forName(MOD_LIST), "INSTANCE");
            Object ids = call(modList, "getMods"); // returns List<ModInfo>
            int idCount = ids instanceof List<?> l ? l.size() : -1;
            System.out.println(LOG + "[diag] ModList.get() = " + modList
                    + " modInfoCount=" + idCount);

            Class<?> modContainer = Class.forName(MOD_CONTAINER);
            Class<?> regEventCls = Class.forName(
                    "cim.shadow.neoforge.net.neoforged.neoforge.registries.RegisterEvent");
            Class<?> bir = Class.forName(BUILT_IN_REGISTRIES);
            Object itemRegistry = getStatic(bir, "ITEM");
            Method acceptEvent = modContainer.getMethod("acceptEvent", Class.forName(EVENT));
            Object itemKey = itemRegistry.getClass().getMethod("key").invoke(itemRegistry);
            Constructor<?> regEventCtor = regEventCls.getDeclaredConstructor(Class.forName(
                    "cim.shadow.mc1211.net.minecraft.resources.ResourceKey"),
                    Class.forName("cim.shadow.mc1211.net.minecraft.core.Registry"));
            regEventCtor.setAccessible(true);
            Object regEvent = regEventCtor.newInstance(itemKey, itemRegistry);

            int before = registryCensus().getOrDefault("ITEM", -1);
            for (Object c : containers) {
                Object bus = call(c, "getEventBus");
                String modId = String.valueOf(call(c, "getModId"));
                System.out.println(LOG + "[diag] container " + modId
                        + " bus=" + (bus != null ? "ok" : "NULL"));
                try {
                    acceptEvent.invoke(c, regEvent);
                } catch (Throwable t) {
                    Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                            ? t.getCause() : t;
                    System.out.println(LOG + "[diag] manual RegisterEvent post on " + modId
                            + " FAILED: " + cause);
                }
            }
            int after = registryCensus().getOrDefault("ITEM", -1);
            System.out.println(LOG + "[diag] manual RegisterEvent(ITEM) post delta: "
                    + before + " -> " + after);
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                    ? t.getCause() : t;
            System.out.println(LOG + "[diag] register probe failed: " + cause);
        }
    }

    private static Object getStatic(Class<?> cls, String field) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object call(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }
}
