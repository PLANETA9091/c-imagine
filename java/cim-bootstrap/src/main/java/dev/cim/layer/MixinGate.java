package dev.cim.layer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

import dev.cim.bootstrap.ClassBytecodeHook;
import dev.cim.bootstrap.MiniJson;
import dev.cim.bootstrap.ShadowClassLoader;

/**
 * The mixin gate driver (harness contract
 * {@code docs/runtime-analysis/mod-parity-harness.md §8}).
 *
 * <p>Run order (Bootstrap.init): the shadow loader and the mod jars are
 * registered, THEN this gate initializes sponge-mixin, THEN the vanilla
 * layer boot loads the first game classes — so every game class passes the
 * transformer before being defined, exactly like the real server's
 * transforming loader.
 *
 * <p>UNIVERSALITY (owner directive): the config set is whatever
 * cim-modscan collected into the manifest ({@code mods[].mixin_configs})
 * plus the layer's own {@code neoforge.mixins.json}. Any mod's mixins apply
 * through the same path; no mod is special-cased anywhere on this gate.
 */
public final class MixinGate {

    private static volatile IMixinTransformer transformer;
    private static volatile boolean initialized;
    private static volatile boolean activated;
    private static volatile boolean failed;
    private static volatile long hookCalls;
    private static volatile String firstHookName = "";
    private static volatile long changedClasses;
    private static volatile String lastChanged = "";

    private static final String LOG = "[c-imagine]";

    private MixinGate() {
    }

    /**
     * The shadow loader. Safe from any dev.cim context under the
     * single-definition contract (ShadowClassLoader delegates dev.cim.** to
     * the host loader — one copy, shared statics).
     */
    static ClassLoader shadowLoader() {
        return ShadowClassLoader.get();
    }

    /**
     * Initialize sponge-mixin in the shadow space and register every mixin
     * config the manifest carries. Idempotent; failures are fatal to the
     * run (the gate reports and rethrows — a half-initialized mixin
     * subsystem would corrupt class definition downstream).
     *
     * @param manifestJson the cim-modscan manifest (mods[].mixin_configs)
     */
    public static synchronized void init(String manifestJson) {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            // 1) direct service selection, BEFORE MixinService clinit:
            //    our bootstrap suppresses the LaunchWrapper/ModLauncher boot
            //    services shipped in the sponge-mixin jar, and the service
            //    property bypasses ServiceLoader entirely.
            System.setProperty("mixin.bootstrapService", "dev.cim.layer.CimMixinBootstrap");
            System.setProperty("mixin.service", "dev.cim.layer.CimMixinService");
            if (Boolean.getBoolean("cim.debug.mixins")) {
                System.setProperty("mixin.debug", "true");
                System.setProperty("mixin.debug.countInjections", "true");
            }

            // 2) bootstrap the subsystem: registers the transformer factory,
            //    creates the PREINIT environment, wires the phase consumer.
            MixinBootstrap.init();

            // Pin the environment side: Side.SERVER.detect() goes through the
            // FINAL service getSideName() (platform agents — modlauncher
            // machinery we do not run), so it would read "UNKNOWN". The
            // universal dist contract of this harness is DEDICATED_SERVER
            // (seedEnvironment pins the same value into FMLLoader).
            MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.SERVER);

            IMixinService service = MixinService.getService();
            if (!(service instanceof CimMixinService cim)) {
                throw new IllegalStateException("mixin service is " + service
                        + " — the mixin.service property did not win");
            }
            org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory factory =
                    cim.transformerFactory();
            if (factory == null) {
                throw new IllegalStateException("no IMixinTransformerFactory offered — "
                        + "MixinBootstrap.offerInternals did not run");
            }

            // 3) transformer FIRST (library bootstrap configs — mixinextras'
            //    init config plugin — register their extensions into the
            //    ACTIVE transformer at config-load time), then configs: every
            //    manifest mod's mixin_configs + the layer's neoforge config +
            //    every MOD-SPACE root *.mixins.json (library bootstrap
            //    configs — discovered universally, no library is named).
            IMixinTransformer t = factory.createTransformer();
            MixinEnvironment.getCurrentEnvironment().setActiveTransformer(t);
            transformer = t;

            List<String> configs = new ArrayList<>();
            if (manifestJson != null && !manifestJson.isEmpty()) {
                Map<String, Object> manifest = MiniJson.parseObject(manifestJson);
                for (Object m : (List<?>) manifest.getOrDefault("mods", List.of())) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> mod = (Map<String, Object>) m;
                    Object mc = mod.get("mixin_configs");
                    if (mc instanceof List<?> l) {
                        for (Object c : l) {
                            if (c != null && !String.valueOf(c).isEmpty()) {
                                configs.add(String.valueOf(c));
                            }
                        }
                    }
                }
            }
            if (shadowLoader().getResource("neoforge.mixins.json") != null
                    && !configs.contains("neoforge.mixins.json")) {
                configs.add("neoforge.mixins.json");
            }
            for (String discovered : ShadowClassLoader.modSpaceRootMixinConfigs()) {
                if (!configs.contains(discovered)) {
                    configs.add(discovered);
                }
            }
            for (String config : configs) {
                Mixins.addConfiguration(config);
            }

            System.out.println(LOG + " mixin gate: transformer armed, "
                    + configs.size() + " config(s) registered " + configs);
        } catch (Throwable t) {
            failed = true;
            System.out.println(LOG + " mixin gate: INIT FAILED: " + t);
            t.printStackTrace();
            throw t;
        }
    }

    /**
     * Second stage (Bootstrap.init order: init → defineLayerEarly →
     * activate → layer boot): advance the phase to DEFAULT and wire the
     * loader hook. The processor promotes its configs on the FIRST
     * environment change (MixinProcessor.checkSelect) — and that promotion
     * resolves every mixin class. The mod mixin classes belong to their
     * modules, so the promotion must run AFTER defineLayerEarly claimed the
     * packages (an earlier definition would pin them to the unnamed module
     * and break the layer claim) and BEFORE the first game class loads
     * (LayerMain.boot), so boot classes get their mixins.
     */
    public static synchronized void activate() {
        if (activated || failed) {
            return;
        }
        try {
            CimMixinService cim = (CimMixinService) MixinService.getService();
            cim.gotoDefaultPhase();
            // The DEFAULT environment is a fresh object — pin the side on it
            // (the PREINIT env was pinned before config registration).
            MixinEnvironment.getCurrentEnvironment().setSide(MixinEnvironment.Side.SERVER);
            // Wire the loader hook (host-side interface — dev.cim.layer stays
            // shadow-defined and the loader is host-defined, so the wiring is
            // an object handoff, never a static class reference).
            ShadowClassLoader.get().setBytecodeHook(new ClassBytecodeHook() {
                @Override
                public byte[] transform(String name, byte[] bytes) {
                    return MixinGate.transform(name, bytes);
                }
            });
            activated = true;
            System.out.println(LOG + " mixin gate: activated (DEFAULT phase, hook wired)");
        } catch (Throwable t) {
            failed = true;
            System.out.println(LOG + " mixin gate: ACTIVATE FAILED: " + t);
            t.printStackTrace();
            throw t;
        }
    }

    /**
     * The ShadowClassLoader hook: consult the mixin transformer before a
     * LAYER (game) class is defined. Called with RELOCATED bytes under the
     * RELOCATED name — the same byte stream the loader would define, so the
     * processor's targets (translated through the same relocation table)
     * match exactly.
     *
     * <p>Propagates transformer failures like the real pipeline does: a
     * failed mixin apply is a failed class load (the configs carry
     * {@code required: true} / {@code defaultRequire: 1}, and silently
     * skipping would diverge from the real server's behavior).
     */
    public static byte[] transform(String name, byte[] bytes) {
        // The hook is wired only when ACTIVATED (after the module layer claims
        // the mod packages) — see activate().
        if (!activated || failed) {
            return bytes;
        }
        if ((hookCalls++) == 0) {
            firstHookName = name;
            System.out.println(LOG + " mixin gate: first hook call: " + name);
        }
        // The subsystem's own classes never pass through the transformer —
        // the processor requests them while already defining them
        // (ClassCircularityError). The real environments exclude the same
        // packages (modlauncher delegates mixin classes raw; Knot skips
        // org.spongepowered/org.objectweb.asm in KnotClassDelegate). No mod
        // can legitimately mix into sponge-mixin itself.
        if (name.startsWith("org.spongepowered.") || name.startsWith("org.objectweb.asm.")) {
            return bytes;
        }
        IMixinTransformer t = transformer;
        if (t == null) {
            return bytes;
        }
        try {
            byte[] out = t.transformClassBytes(name, name, bytes);
            if (out != null && out != bytes) {
                if ((changedClasses++) < 8) {
                    System.out.println(LOG + " mixin gate: TRANSFORMED " + name);
                }
                lastChanged = name;
            }
            return out == null ? bytes : out;
        } catch (Throwable e) {
            System.out.println(LOG + " mixin gate: transform FAILED for " + name
                    + " — failing the class load like the real pipeline: " + e);
            throw e;
        }
    }

    /**
     * Acceptance probe (§8 point 5): the target must implement the accessor
     * after the gate ran. Universal shape — both names are caller-supplied.
     */
    public static boolean accessorApplied(String targetBinaryName, String accessorBinaryName) {
        try {
            System.out.println(LOG + " mixin gate: hook diagnostics — calls=" + hookCalls
                    + " first=" + firstHookName
                    + " changed=" + changedClasses + " last=" + lastChanged
                    + " configs=" + Mixins.getConfigs().size()
                    + " unvisited=" + Mixins.getUnvisitedCount()
                    + " transformer=" + (transformer != null));
            ClassLoader sl = shadowLoader();
            Class<?> target = Class.forName(targetBinaryName, false, sl);
            Class<?> accessor = Class.forName(accessorBinaryName, false, sl);
            return accessor.isAssignableFrom(target);
        } catch (Throwable t) {
            System.out.println(LOG + " mixin gate: accessor probe failed for "
                    + targetBinaryName + " ← " + accessorBinaryName + ": " + t);
            return false;
        }
    }
}
