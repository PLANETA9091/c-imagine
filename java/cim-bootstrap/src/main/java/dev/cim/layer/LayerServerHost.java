package dev.cim.layer;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M2/M3 open-blocker slice (roadmap "Open blockers — server execution and F2V
 * completeness", 2026-11-10): a REAL vanilla {@code DedicatedServer}
 * constructed inside the shadow space, its lifecycle driven by Paper's main
 * thread over the bridge — not a dummy server and not fabricated events.
 *
 * <p>The construction replicates {@code net.minecraft.server.Main.main} up to
 * {@code initServer()} and {@code runServer()}'s skeleton, calling the SAME
 * native methods in the SAME order; NeoForge's own patched code fires every
 * lifecycle event through {@code ServerLifecycleHooks} / {@code EventHooks}:
 *
 * <pre>
 * DedicatedServerSettings(forceSave) -> Eula -> Services.create ->
 * LevelStorageSource.createDefault(...).validateAndCreateAccess ->
 * ServerPacksSource.createPackRepository -> Main.loadOrCreateConfig ->
 * WorldLoader.load -> saveDataTag -> new DedicatedServer(...) ->
 * initServer()   [natively: key pair, bind, handleServerAboutToStart,
 *                 loadLevel, handleServerStarting]
 * -> ServerLifecycleHooks.handleServerStarted   [ServerStartedEvent]
 * -> per Paper tick: tickServer(haveTime)  [natively: fireServerTickPre,
 *                 tickChildren, fireServerTickPost]
 * -> handleServerStopping -> expectServerStopped -> stopServer()
 * -> handleServerStopped
 * </pre>
 *
 * <p>Thread discipline: {@code tickServer} runs ON Paper's main thread (the
 * {@code ServerTickEndEvent} executor), and the {@code DedicatedServer}'s
 * server thread IS that thread — the same contract vanilla {@code spin}
 * establishes (the server thread is the thread that ticks the server). The
 * {@code runServer} loop's PACING half belongs to Paper (we advance
 * {@code nextTickTimeNanos} by the tick rate and never wait); the task-drain
 * half runs under the same {@code haveTime()} bound vanilla's
 * {@code waitUntilNextTick} uses.
 *
 * <p>Storage is real and layer-owned: {@code <cwd>/c-imagine/layer-server/}
 * holds {@code server.properties}, {@code eula.txt}, {@code usercache.json}
 * and the level directories — Paper's authoritative world stays untouched
 * (the state bridge remains the content channel; world-state mirroring is the
 * next M3 slice and is NOT claimed here). The network listener binds
 * {@code server-port=0} (an ephemeral local port — no Paper conflict, the
 * vanilla bind path is real). {@code max-tick-time=-1} keeps the native
 * ServerWatchdog from judging Paper's pacing (its contract is the vanilla
 * loop's, not an externally driven one).
 *
 * <p>Profiling contract (M9): the tick-budget gate requires
 * {@code [cimtest_heavy] load armed/done} evidence produced by REAL
 * {@code ServerStartedEvent} + {@code ServerTickEvent} delivery. This class is
 * what delivers them; a failed start logs {@code layer server: start FAILED}
 * and the gate keeps failing — no synthesized markers, no relaxed budgets.
 *
 * <p>Marker lines (CI greps):
 *
 * <pre>
 * [c-imagine] layer server: disabled (cim.layerServer=false)
 * [c-imagine] layer server: armed (ticks ride Paper's ServerTickEndEvent)
 * [c-imagine] layer server: about-to-start (dir=...)
 * [c-imagine] layer server: started (first tick on <thread>)
 * [c-imagine] layer server: tick N (every 600)
 * [c-imagine] layer server: stopping / stopped (ticks=N)
 * [c-imagine] layer server: start FAILED: ...
 * </pre>
 */
public final class LayerServerHost {

    private static final String LOG = "[c-imagine] layer server:";
    private static final String NS = "cim.shadow.mc1211.net.minecraft.";
    private static final String NEOFORGE_NS = "cim.shadow.neoforge.net.neoforged.";
    private static final String NEOFORGE_UNIVERSAL_NS = "cim.shadow.neoforge.net.neoforged.";

    // shadow-space binary names (verified against the real 21.1.265 artifacts)
    private static final String MAIN = NS + "server.Main";
    private static final String UTIL = NS + "Util";
    private static final String DEDICATED_SERVER = NS + "server.dedicated.DedicatedServer";
    private static final String DEDICATED_SETTINGS = NS + "server.dedicated.DedicatedServerSettings";
    private static final String EULA = NS + "server.Eula";
    private static final String SERVICES = NS + "server.Services";
    private static final String SERVER_PACKS_SOURCE = NS + "server.packs.repository.ServerPacksSource";
    private static final String LEVEL_STORAGE_SOURCE = NS + "world.level.storage.LevelStorageSource";
    private static final String WORLD_LOADER = NS + "server.WorldLoader";
    private static final String WORLD_STEM = NS + "server.WorldStem";
    private static final String WORLD_DATA_SUPPLIER = NS + "server.WorldLoader$WorldDataSupplier";
    private static final String RESULT_FACTORY = NS + "server.WorldLoader$ResultFactory";
    private static final String DATA_LOAD_OUTPUT = NS + "server.WorldLoader$DataLoadOutput";
    private static final String DATA_LOAD_CONTEXT = NS + "server.WorldLoader$DataLoadContext";
    private static final String REGISTRIES = NS + "core.registries.Registries";
    private static final String LEVEL_SETTINGS = NS + "world.level.LevelSettings";
    private static final String GAME_RULES = NS + "world.level.GameRules";
    private static final String PRIMARY_LEVEL_DATA = NS + "world.level.storage.PrimaryLevelData";
    private static final String REGISTRY_ACCESS = NS + "core.RegistryAccess";
    private static final String LAYERED_REGISTRY_ACCESS = NS + "core.LayeredRegistryAccess";
    private static final String DATA_FIXERS = NS + "util.datafix.DataFixers";
    private static final String CHUNK_PROGRESS_FACTORY = NS + "server.level.progress.ChunkProgressListenerFactory";
    private static final String LOGGER_PROGRESS = NS + "server.level.progress.LoggerChunkProgressListener";
    private static final String LIFECYCLE_HOOKS = NEOFORGE_NS + "neoforge.server.ServerLifecycleHooks";
    private static final String YGGDRASIL = "cim.shadow.mojang.com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService";

    // Paper-side names resolved through the PLUGIN's classloader
    private static final String TICK_END_EVENT =
            "com.destroystokyo.paper.event.server.ServerTickEndEvent";
    private static final String PLUGIN_DISABLE_EVENT =
            "org.bukkit.event.server.PluginDisableEvent";
    private static final String LISTENER_IF = "org.bukkit.event.Listener";
    private static final String EXECUTOR_IF = "org.bukkit.plugin.EventExecutor";
    private static final String PRIORITY_CLS = "org.bukkit.event.EventPriority";

    /** The layer server's own storage root (properties, eula, levels). */
    private static final String SERVER_DIR = "c-imagine/layer-server";

    /** Config gate: -Dcim.layerServer=false skips the whole host. */
    private static final String GATE_PROP = "cim.layerServer";

    private static final AtomicBoolean ARMED = new AtomicBoolean();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static final AtomicLong TICKS = new AtomicLong();

    /** Reflective handles resolved once at start; null until then. */
    private static Object server;
    private static Method tickServer;
    private static Method haveTime;
    private static Method pollTask;
    private static Method startMetrics;
    private static Method endMetrics;
    private static Field nextTickTimeNanos;
    private static Field delayedTasksMaxNextTickTimeNanos;
    private static Field mayHaveDelayedTasks;
    private static Field isReady;
    private static Object tickRateManager;
    private static Method nanosPerTick;

    private LayerServerHost() {
    }

    // ---- install (the Paper-facing half) ---------------------------------------

    /**
     * Register the tick/disable executors through the PLUGIN's classloader.
     * Called by the native bridge after the facade smoke. The first tick
     * event constructs and starts the real server ON Paper's main thread.
     */
    public static void install(Object plugin) {
        if ("false".equals(System.getProperty(GATE_PROP))) {
            System.out.println(LOG + " disabled (" + GATE_PROP + "=false)");
            return;
        }
        if (!ARMED.compareAndSet(false, true)) {
            return;
        }
        try {
            ClassLoader pcl = plugin.getClass().getClassLoader();
            Class<?> tickEnd = pcl.loadClass(TICK_END_EVENT);
            Class<?> disable = pcl.loadClass(PLUGIN_DISABLE_EVENT);
            Class<?> listenerIf = pcl.loadClass(LISTENER_IF);
            Class<?> executorIf = pcl.loadClass(EXECUTOR_IF);
            Class<?> priority = pcl.loadClass(PRIORITY_CLS);
            Object normal = enumConstant(priority, "NORMAL");

            Object server = plugin.getClass().getMethod("getServer").invoke(plugin);
            Object pm = server.getClass().getMethod("getPluginManager").invoke(server);
            Class<?> pluginIf = pcl.loadClass("org.bukkit.plugin.Plugin");
            Method register = pm.getClass().getMethod("registerEvent",
                    Class.class, listenerIf, priority,
                    executorIf, pluginIf);
            register.setAccessible(true);

            Object tickListener = Proxy.newProxyInstance(pcl, new Class<?>[] {listenerIf},
                    (proxy, method, args) -> null);
            InvocationHandler tickHandler = (proxy, method, args) -> {
                if ("execute".equals(method.getName()) && args != null && args.length == 2) {
                    runTick();
                }
                return null;
            };
            Object tickExecutor = Proxy.newProxyInstance(pcl, new Class<?>[] {executorIf}, tickHandler);
            register.invoke(pm, tickEnd, tickListener, normal, tickExecutor, plugin);

            Object disableListener = Proxy.newProxyInstance(pcl, new Class<?>[] {listenerIf},
                    (proxy, method, args) -> null);
            InvocationHandler disableHandler = (proxy, method, args) -> {
                if ("execute".equals(method.getName()) && args != null && args.length == 2) {
                    Object ev = args[1];
                    Object affected = ev.getClass().getMethod("getPlugin").invoke(ev);
                    if (plugin == affected) {
                        stop();
                    }
                }
                return null;
            };
            Object disableExecutor = Proxy.newProxyInstance(pcl, new Class<?>[] {executorIf}, disableHandler);
            register.invoke(pm, disable, disableListener, normal, disableExecutor, plugin);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(), "cim-layer-server-stop"));
            System.out.println(LOG + " armed (ticks ride Paper's ServerTickEndEvent)");
        } catch (Throwable t) {
            System.out.println(LOG + " arm FAILED: " + t);
            t.printStackTrace();
        }
    }

    /** Enum constant by name without the capture headaches. */
    private static Object enumConstant(Class<?> cls, String name) throws Exception {
        return cls.getMethod("valueOf", Class.class, String.class).invoke(null, cls, name);
    }

    // ---- lifecycle (the shadow-space half) -------------------------------------

    /** One Paper tick: lazily start, then drive one real tickServer pass. */
    private static final java.util.concurrent.atomic.AtomicLong WAITED = new java.util.concurrent.atomic.AtomicLong();

    private static void runTick() {
        if (STOPPING.get()) {
            return;
        }
        try {
            if (!STARTED.get()) {
                // Paper ticks before the mods finish loading (they wait for its server
                // object): a start now finds every mod registry entry unbound (measured:
                // Create's AddPackFindersEvent on create:cutting, 5 s before the load ended).
                // A host that never loads mods starts after two minutes of waiting.
                if (!FmlHost.loadEnded() && WAITED.incrementAndGet() < 2400) {
                    return;
                }
                start();
                if (!STARTED.get()) {
                    return; // a failed start keeps the gate failing; no markers synthesized
                }
            }
            if (server == null || STOPPING.get()) {
                return;
            }
            long perTick = (Long) nanosPerTick.invoke(tickRateManager);
            long next = nextTickTimeNanos.getLong(server) + perTick;
            nextTickTimeNanos.setLong(server, next);
            mayHaveDelayedTasks.setBoolean(server, true);
            delayedTasksMaxNextTickTimeNanos.setLong(server,
                    Math.max(Util_getNanos() + perTick, next));
            startMetrics.invoke(server);
            tickServer.invoke(server, (java.util.function.BooleanSupplier) () -> {
                try {
                    return (Boolean) haveTime.invoke(server);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            // waitUntilNextTick's task half, under the same haveTime bound
            while ((Boolean) haveTime.invoke(server) && (Boolean) pollTask.invoke(server)) {
                // drain queued work (chunk tasks, executor jobs) the vanilla loop
                // would have run between ticks; Paper owns the pacing, so we
                // never wait — only drain what the budget allows
            }
            endMetrics.invoke(server);
            isReady.setBoolean(server, true);
            long ticks = TICKS.incrementAndGet();
            if (ticks % 600 == 0) {
                System.out.println(LOG + " tick " + ticks);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            STOPPING.set(true); // a broken tick loop must not loop the failure every tick
        }
    }

    /**
     * The REAL construction sequence (Main.main's prefix + runServer's
     * skeleton), on the calling thread = Paper main = the ticking thread.
     */
    private static synchronized void start() throws Exception {
        if (STARTED.get() || STOPPING.get()) {
            return;
        }
        Path root = Path.of(System.getProperty("user.dir"), SERVER_DIR);
        Files.createDirectories(root.resolve("mods"));
        try {
            System.out.println(LOG + " about-to-start (dir=" + root + ")");

            // Main: properties + eula (the layer-owned copies, written once)
            writeOnce(root.resolve("server.properties"), """
                    level-name=layer-world
                    level-type=minecraft\\:flat
                    generate-structures=false
                    online-mode=false
                    server-port=0
                    max-tick-time=-1
                    spawn-protection=0
                    enable-query=false
                    enable-rcon=false
                    enable-command-block=false
                    """);
            writeOnce(root.resolve("eula.txt"), "eula=true\n");

            Class<?> settingsCls = Class.forName(DEDICATED_SETTINGS);
            Object settings = settingsCls.getConstructor(Path.class)
                    .newInstance(root.resolve("server.properties"));
            settingsCls.getMethod("forceSave").invoke(settings);
            Object properties = settingsCls.getMethod("getProperties").invoke(settings);

            Class<?> eulaCls = Class.forName(EULA);
            Object eula = eulaCls.getConstructor(Path.class)
                    .newInstance(root.resolve("eula.txt"));
            if (!(Boolean) eulaCls.getMethod("hasAgreedToEULA").invoke(eula)) {
                throw new IllegalStateException("the layer server's eula.txt is not agreed");
            }

            // Services (offline: NO_PROXY, the layer dir holds usercache.json)
            Class<?> servicesCls = Class.forName(SERVICES);
            Class<?> authCls = Class.forName(YGGDRASIL);
            Object auth = authCls.getConstructor(java.net.Proxy.class)
                    .newInstance(java.net.Proxy.NO_PROXY);
            Object services = servicesCls.getMethod("create", authCls, File.class)
                    .invoke(null, auth, root.toFile());

            // storage + packs (real LevelStorageAccess with a real session lock)
            String levelName = (String) field(properties, "levelName").get(properties);
            Class<?> storageCls = Class.forName(LEVEL_STORAGE_SOURCE);
            Object storage = storageCls.getMethod("createDefault", Path.class)
                    .invoke(null, root);
            Object access = storageCls.getMethod("validateAndCreateAccess", String.class)
                    .invoke(storage, levelName);
            Class<?> accessCls = Class.forName(
                    NS + "world.level.storage.LevelStorageSource$LevelStorageAccess");
            Object levelTag = (Boolean) accessCls.getMethod("hasWorldData").invoke(access)
                    ? accessCls.getMethod("getDataTag").invoke(access) : null;
            Object packs = Class.forName(SERVER_PACKS_SOURCE)
                    .getMethod("createPackRepository", accessCls)
                    .invoke(null, access);

            // Main.loadOrCreateConfig (private static) -> InitConfig
            Class<?> mainCls = Class.forName(MAIN);
            Method loadOrCreateConfig = null;
            for (Method m : mainCls.getDeclaredMethods()) {
                if (m.getName().equals("loadOrCreateConfig") && m.getParameterCount() == 4) {
                    m.setAccessible(true);
                    loadOrCreateConfig = m;
                    break;
                }
            }
            if (loadOrCreateConfig == null) {
                throw new NoSuchMethodException(MAIN + ".loadOrCreateConfig(4 args)");
            }
            Object initConfig = loadOrCreateConfig.invoke(null, properties, levelTag, false, packs);

            // WorldStem: WorldLoader.load with the NEW-WORLD branch of Main's
            // world-data supplier (a fresh superflat level on first boot)
            Class<?> loaderCls = Class.forName(WORLD_LOADER);
            Class<?> supplierIf = Class.forName(WORLD_DATA_SUPPLIER);
            Class<?> factoryIf = Class.forName(RESULT_FACTORY);
            Class<?> utilCls = Class.forName(UTIL);
            Object backgroundExecutor = utilCls.getMethod("backgroundExecutor").invoke(null);
            Method blockUntilDone = utilCls.getMethod("blockUntilDone",
                    java.util.function.Function.class);
            Method loaderLoad = null;
            for (Method m : loaderCls.getMethods()) {
                if (m.getName().equals("load") && m.getParameterCount() == 5) {
                    loaderLoad = m;
                    break;
                }
            }
            if (loaderLoad == null) {
                throw new NoSuchMethodException(WORLD_LOADER + ".load(5 args)");
            }
            loaderLoad.setAccessible(true);
            Method loadMethod = loaderLoad;
            Object worldDataSupplier = Proxy.newProxyInstance(supplierIf.getClassLoader(),
                    new Class<?>[] {supplierIf},
                    (proxy, method, args) -> worldDataFor(properties, levelTag, args[0]));
            // WorldStem::new — (CloseableResourceManager, ReloadableServerResources,
            // LayeredRegistryAccess, WorldData) -> WorldStem
            Class<?> stemCls = Class.forName(WORLD_STEM);
            Constructor<?> stemCtor = stemCls.getDeclaredConstructor(
                    Class.forName(NS + "server.packs.resources.CloseableResourceManager"),
                    Class.forName(NS + "server.ReloadableServerResources"),
                    Class.forName(LAYERED_REGISTRY_ACCESS),
                    Class.forName(NS + "world.level.storage.WorldData"));
            stemCtor.setAccessible(true);
            Object resultFactory = Proxy.newProxyInstance(factoryIf.getClassLoader(),
                    new Class<?>[] {factoryIf},
                    (proxy, method, args) -> stemCtor.newInstance(args[0], args[1], args[2], args[3]));
            Object stemFuture = blockUntilDone.invoke(null,
                    (java.util.function.Function<Object, Object>) executor -> {
                        try {
                            return loadMethod.invoke(null, initConfig, worldDataSupplier,
                                    resultFactory, backgroundExecutor, executor);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            Throwable c = e.getCause() == null ? e : e.getCause();
                            throw new RuntimeException("WorldLoader.load failed", c);
                        } catch (ReflectiveOperationException e) {
                            throw new RuntimeException(e);
                        }
                    });
            Object stem = ((CompletableFuture<?>) stemFuture).get();

            // registries + world data tag (Main's sequence)
            Object layeredRegistries = stem.getClass().getMethod("registries").invoke(stem);
            Object registries = Class.forName(LAYERED_REGISTRY_ACCESS)
                    .getMethod("compositeAccess").invoke(layeredRegistries);
            Object worldData = stem.getClass().getMethod("worldData").invoke(stem);
            accessCls.getMethod("saveDataTag",
                    Class.forName(REGISTRY_ACCESS), Class.forName(NS + "world.level.storage.WorldData"))
                    .invoke(access, registries, worldData);

            // the REAL DedicatedServer; the server thread IS Paper main
            Class<?> serverCls = Class.forName(DEDICATED_SERVER);
            Class<?> minecraftServerCls = Class.forName(NS + "server.MinecraftServer");
            Class<?> factoryIf2 = Class.forName(CHUNK_PROGRESS_FACTORY);
            Object progressFactory = Proxy.newProxyInstance(factoryIf2.getClassLoader(),
                    new Class<?>[] {factoryIf2},
                    (proxy, method, args) -> Class.forName(LOGGER_PROGRESS)
                            .getMethod("createFromGameruleRadius", int.class)
                            .invoke(null, args[0]));
            Object fixer = Class.forName(DATA_FIXERS).getMethod("getDataFixer").invoke(null);
            server = serverCls.getConstructor(Thread.class, accessCls,
                    Class.forName(NS + "server.packs.repository.PackRepository"),
                    Class.forName(WORLD_STEM), settingsCls,
                    Class.forName("cim.shadow.mojang.com.mojang.datafixers.DataFixer"), servicesCls,
                    factoryIf2)
                    .newInstance(Thread.currentThread(), access, packs, stem, settings,
                            fixer, services, progressFactory);

            // initServer: key pair, bind(0), handleServerAboutToStart, loadLevel,
            // handleServerStarting — the native order, native code
            boolean ok = (Boolean) serverCls.getMethod("initServer").invoke(server);
            if (!ok) {
                throw new IllegalStateException("DedicatedServer.initServer returned false");
            }
            // ServerStartedEvent through the native hook (runServer's next line)
            Class.forName(LIFECYCLE_HOOKS)
                    .getMethod("handleServerStarted", minecraftServerCls)
                    .invoke(null, server);

            // runServer's pre-loop state + the reflective handles for the driver
            resolveDriverHandles(serverCls, server);
            nextTickTimeNanos.setLong(server, Util_getNanos());

            STARTED.set(true);
            System.out.println(LOG + " started (first tick on " + Thread.currentThread() + ")");
        } catch (Throwable t) {
            while (t instanceof java.lang.reflect.InvocationTargetException
                    && t.getCause() != null) {
                t = t.getCause();
            }
            System.out.println(LOG + " start FAILED: " + t);
            STOPPING.set(true);
            throw t instanceof Exception e ? e : new RuntimeException(t);
        }
    }

    /**
     * Main's world-data supplier, BOTH branches: an existing level dir
     * re-reads its level.dat through the native
     * {@code getLevelDataAndDimensions} path (the second boot must resume
     * that world, never overwrite it); a fresh dir creates the new superflat
     * world through the properties.
     */
    private static Object worldDataFor(Object properties, Object levelTag,
                                       Object context) throws Exception {
        Class<?> ctxCls = context.getClass();
        Object dataConfiguration = ctxCls.getMethod("dataConfiguration").invoke(context);
        Object datapackWorldgen = ctxCls.getMethod("datapackWorldgen").invoke(context);
        Object datapackDimensions = ctxCls.getMethod("datapackDimensions").invoke(context);
        Object levelStemRegistry = datapackDimensions.getClass()
                .getMethod("registryOrThrow", Class.forName(NS + "resources.ResourceKey"))
                .invoke(datapackDimensions, staticField(Class.forName(REGISTRIES), "LEVEL_STEM"));

        Class<?> frozenCls = Class.forName(REGISTRY_ACCESS + "$Frozen");
        Class<?> dataLoadOutputCls = Class.forName(DATA_LOAD_OUTPUT);
        if (levelTag != null) {
            // Main's existing-world branch: resume the stored level
            Object ldd = Class.forName(LEVEL_STORAGE_SOURCE)
                    .getMethod("getLevelDataAndDimensions", levelTag.getClass(),
                            Class.forName(NS + "world.level.WorldDataConfiguration"),
                            Class.forName(NS + "core.Registry"), frozenCls)
                    .invoke(null, levelTag, dataConfiguration, levelStemRegistry, datapackWorldgen);
            Class<?> lddCls = ldd.getClass();
            Object data = lddCls.getMethod("worldData").invoke(ldd);
            Object complete = lddCls.getMethod("dimensions").invoke(ldd);
            Object frozen = complete.getClass()
                    .getMethod("dimensionsRegistryAccess").invoke(complete);
            return dataLoadOutputCls.getConstructor(Object.class, frozenCls)
                    .newInstance(data, frozen);
        }
        Class<?> settingsCls = Class.forName(LEVEL_SETTINGS);
        Object levelSettings = settingsCls.getConstructor(String.class,
                Class.forName(NS + "world.level.GameType"), boolean.class,
                Class.forName(NS + "world.Difficulty"), boolean.class,
                Class.forName(GAME_RULES), dataConfiguration.getClass())
                .newInstance(field(properties, "levelName").get(properties),
                        field(properties, "gamemode").get(properties),
                        field(properties, "hardcore").getBoolean(properties),
                        field(properties, "difficulty").get(properties),
                        false,
                        Class.forName(GAME_RULES).getConstructor().newInstance(),
                        dataConfiguration);
        Object worldOptions = field(properties, "worldOptions").get(properties);
        Object dimensions = properties.getClass()
                .getMethod("createDimensions", Class.forName(NS + "core.RegistryAccess"))
                .invoke(properties, datapackWorldgen);
        Class<?> completeCls = Class.forName(
                NS + "world.level.levelgen.WorldDimensions$Complete");
        Object complete = dimensions.getClass()
                .getMethod("bake", Class.forName(NS + "core.Registry"))
                .invoke(dimensions, levelStemRegistry);
        Object lifecycle = completeCls.getMethod("lifecycle").invoke(complete);
        Object worldgenLifecycle = datapackWorldgen.getClass()
                .getMethod("allRegistriesLifecycle").invoke(datapackWorldgen);
        Class<?> lifecycleCls = Class.forName("cim.shadow.mojang.com.mojang.serialization.Lifecycle");
        Object combined = lifecycleCls.getMethod("add", lifecycleCls)
                .invoke(lifecycle, worldgenLifecycle);
        Class<?> specialPropertyCls = Class.forName(
                NS + "world.level.storage.PrimaryLevelData$SpecialWorldProperty");
        Object data = Class.forName(PRIMARY_LEVEL_DATA)
                .getConstructor(settingsCls,
                        Class.forName(NS + "world.level.levelgen.WorldOptions"),
                        specialPropertyCls,
                        lifecycleCls)
                .newInstance(levelSettings, worldOptions,
                        completeCls.getMethod("specialWorldProperty").invoke(complete),
                        combined);
        Object frozen = completeCls.getMethod("dimensionsRegistryAccess").invoke(complete);
        return Class.forName(DATA_LOAD_OUTPUT)
                .getConstructor(Object.class, Class.forName(REGISTRY_ACCESS + "$Frozen"))
                .newInstance(data, frozen);
    }

    /** Graceful stop: the native stopping -> stopServer -> stopped sequence. */
    private static void stop() {
        if (!STARTED.get() || !STOPPING.compareAndSet(false, true)) {
            return;
        }
        try {
            System.out.println(LOG + " stopping");
            Class<?> serverCls = server.getClass();
            Class<?> minecraftServerCls = Class.forName(NS + "server.MinecraftServer");
            Class.forName(LIFECYCLE_HOOKS)
                    .getMethod("handleServerStopping", minecraftServerCls)
                    .invoke(null, server);
            Class.forName(LIFECYCLE_HOOKS).getMethod("expectServerStopped").invoke(null);
            serverCls.getMethod("stopServer").invoke(server);
            field(server, "stopped").setBoolean(server, true);
            Class.forName(LIFECYCLE_HOOKS)
                    .getMethod("handleServerStopped", minecraftServerCls)
                    .invoke(null, server);
            System.out.println(LOG + " stopped (ticks=" + TICKS.get() + ")");
        } catch (Throwable t) {
            System.out.println(LOG + " stop FAILED: " + t);
            t.printStackTrace();
        }
    }

    // ---- reflection helpers -----------------------------------------------------

    private static void resolveDriverHandles(Class<?> serverCls, Object srv) throws Exception {
        tickServer = serverCls.getMethod("tickServer", java.util.function.BooleanSupplier.class);
        haveTime = findMethod(serverCls, "haveTime");
        pollTask = serverCls.getMethod("pollTask");
        startMetrics = findMethod(serverCls, "startMetricsRecordingTick");
        endMetrics = serverCls.getMethod("endMetricsRecordingTick");
        nextTickTimeNanos = findField(serverCls, "nextTickTimeNanos");
        delayedTasksMaxNextTickTimeNanos = findField(serverCls, "delayedTasksMaxNextTickTimeNanos");
        mayHaveDelayedTasks = findField(serverCls, "mayHaveDelayedTasks");
        isReady = findField(serverCls, "isReady");
        tickRateManager = serverCls.getMethod("tickRateManager").invoke(srv);
        nanosPerTick = tickRateManager.getClass().getMethod("nanosecondsPerTick");
    }

    private static Object staticField(Class<?> cls, String name) throws Exception {
        return cls.getField(name).get(null);
    }

    private static Field field(Object owner, String name) throws Exception {
        return findField(owner.getClass(), name);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // walk the hierarchy
            }
        }
        throw new NoSuchFieldException(name + " on " + cls.getName());
    }

    private static Method findMethod(Class<?> cls, String name) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(name + " on " + cls.getName());
    }

    private static long Util_getNanos() throws Exception {
        return (Long) Class.forName(UTIL).getMethod("getNanos").invoke(null);
    }

    private static void writeOnce(Path path, String content) throws Exception {
        if (!Files.exists(path)) {
            Files.writeString(path, content);
        }
    }
}
