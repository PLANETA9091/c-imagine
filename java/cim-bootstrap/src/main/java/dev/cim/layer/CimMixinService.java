package dev.cim.layer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import dev.cim.bootstrap.ShadowClassLoader;

/**
 * The shadow-space {@code IMixinService} (harness contract
 * {@code docs/runtime-analysis/mod-parity-harness.md §8}).
 *
 * <p>Selected by the {@code mixin.service} system property (sponge-mixin's
 * {@code MixinService.initService} instantiates it directly — no ServiceLoader
 * ambiguity against the LaunchWrapper/ModLauncher services shipped in the
 * sponge-mixin jar). Deliberately NOT FML's {@code MixinServiceModLauncher}:
 * that service's plumbing assumes the modlauncher environment (launch plugin
 * lookup, containers) which the shadow space does not run (§8 open question —
 * resolved: a thin service of our own).
 *
 * <p>UNIVERSALITY (owner directive 2026-10-29): nothing here knows any mod by
 * name. Mixin classes resolve through the shadow loader (mod classes keep
 * their names; layer classes carry the relocated ones), targets arrive at the
 * transformer already relocated, and config translation goes through the one
 * relocation table ({@link MixinConfigs}) — every mod's {@code mixin_configs}
 * collected by cim-modscan work identically.
 *
 * <p>Everything the service hands to sponge-mixin is defined by the SAME
 * loader ({@link ShadowClassLoader}): sponge-mixin classes, ASM, the mixin
 * classes and the targets all live in the shadow space, so the processor's
 * identity assumptions (same classloader for {@code instanceof} checks and
 * {@code ClassInfo} lookups) hold.
 */
public class CimMixinService extends MixinServiceAbstract {

    static volatile CimMixinService instance;

    /**
     * The shadow loader. Safe from any dev.cim context: the bootstrap
     * classes are defined ONCE (ShadowClassLoader delegates dev.cim.** to
     * the host loader — single-definition contract), so the statics here,
     * the gate's and the loader's are the same set everywhere.
     */
    private static ClassLoader loader() {
        return ShadowClassLoader.get();
    }

    /** Captured at {@link #offer} time (MixinBootstrap.offerInternals). */
    private volatile IMixinTransformerFactory factory;

    /** The phase consumer received in {@link #wire} — the sanctioned way to
     *  advance the phase in this fork (gotoPhase is package-private). */
    private volatile org.spongepowered.asm.util.IConsumer<MixinEnvironment.Phase> phaseConsumer;

    private final IClassProvider classProvider = new ShadowClassProvider();
    private final IClassBytecodeProvider bytecodeProvider = new ShadowBytecodeProvider();
    private final IClassTracker classTracker = new ShadowClassTracker();
    private final ITransformerProvider transformerProvider = new ShadowTransformerProvider();
    private final IMixinAuditTrail auditTrail = new ShadowAuditTrail();

    public CimMixinService() {
        instance = this;
    }

    /**
     * Advance the mixin phase to DEFAULT (the sanctioned fork mechanism —
     * the consumer wraps the package-private gotoPhase). MixinGate drives
     * this after the transformer exists: the processor promotes its configs
     * on the first environment CHANGE (MixinProcessor.checkSelect) — with a
     * never-changing PREINIT environment the processor stays inert forever
     * (observed: 4500+ transform calls, zero mixins applied).
     */
    void gotoDefaultPhase() {
        org.spongepowered.asm.util.IConsumer<MixinEnvironment.Phase> consumer = phaseConsumer;
        if (consumer == null) {
            throw new IllegalStateException("mixin service was never wired (no phase consumer)");
        }
        consumer.accept(MixinEnvironment.Phase.DEFAULT);
    }

    @Override
    public void wire(MixinEnvironment.Phase phase,
            org.spongepowered.asm.util.IConsumer<MixinEnvironment.Phase> consumer) {
        this.phaseConsumer = consumer;
    }

    @Override
    public void unwire() {
        this.phaseConsumer = null;
    }

    /** Minimal non-null root container (MixinPlatformManager requirement). */
    private static final IContainerHandle PRIMARY_CONTAINER = new IContainerHandle() {
        @Override
        public String getId() {
            return "cim-shadow";
        }

        @Override
        public String getDescription() {
            return "c-imagine shadow space (bootstrap jar + 1.21.1 layer + mods)";
        }

        @Override
        public String getAttribute(String attribute) {
            return null;
        }

        @Override
        public java.util.Collection<IContainerHandle> getNestedContainers() {
            return List.of();
        }
    };

    /** The factory offered by {@code MixinBootstrap.offerInternals}, if any. */
    IMixinTransformerFactory transformerFactory() {
        return factory;
    }

    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory f) {
            this.factory = f;
            return;
        }
        super.offer(internal);
    }

    // ---- identity ------------------------------------------------------------

    @Override
    public String getName() {
        return "cim-shadow";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void init() {
        // No platform agents: FMLMixinPlatformAgent and friends are
        // modlauncher machinery (§8); the shadow space drives everything
        // through MixinGate directly.
    }

    @Override
    public void beginPhase() {
        // The gate pins one phase for the whole run; no phase transitions.
    }

    @Override
    public void checkEnv(Object hotSwap) {
        // no hot-swap agent in the shadow space
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        // PREINIT avoids MixinBootstrap's platform-manager path and matches
        // the real server (mixin boots before the game). Configs registered
        // after init() attach to THIS environment; every transform runs
        // against the same environment — self-consistent.
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_8;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        // The 1.21.1 stack compiles to class file v65 (JAVA_21); allow the
        // headroom this sponge-mixin build knows about.
        return MixinEnvironment.CompatibilityLevel.JAVA_25;
    }

    // NOTE: getSideName() is FINAL in MixinServiceAbstract (it walks
    // platform agents — modlauncher machinery). The environment side is
    // pinned explicitly in MixinGate.init (setSide(SERVER)) — the universal
    // dist contract of this harness (DEDICATED_SERVER, matching
    // seedEnvironment). Side.SERVER.detect() reads getSideName() only once,
    // and only when the side was never set.

    // ---- providers -------------------------------------------------------------

    @Override
    public IClassProvider getClassProvider() {
        return classProvider;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return bytecodeProvider;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return transformerProvider;
    }

    @Override
    public IClassTracker getClassTracker() {
        return classTracker;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return auditTrail;
    }

    /**
     * Mixin reads configs, refmaps and its own resources through this — the
     * one place where the relocation reaches resource-space names (§8 point
     * 4): {@code *.mixins.json} / {@code *refmap.json} are served with
     * package/target/refmap keys translated to the relocated namespace.
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        try {
            InputStream translated = MixinConfigs.serve(name);
            if (translated != null) {
                return translated;
            }
        } catch (Exception e) {
            System.out.println("[c-imagine] mixin configs: translate FAILED for "
                    + name + ": " + e);
            // fall through to the raw resource rather than dying here — the
            // processor will fail later with the actual apply error if the
            // untranslated config is genuinely unusable.
        }
        return loader().getResourceAsStream(name);
    }

    // ---- platform containers -----------------------------------------------------

    @Override
    public Collection<String> getPlatformAgents() {
        return List.of();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        // MixinPlatformManager dereferences this unconditionally
        // (getNestedContainers) — a minimal, non-null handle.
        return PRIMARY_CONTAINER;
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return List.of();
    }

    /**
     * Console logger with the debug stream wired to stdout when
     * {@code cim.debug.mixins} is set — the base adapter drops DEBUG lines
     * (null stream), which blinds every mixin diagnostic (processor apply
     * traces, plugin decisions). CI logs are unrestricted (AGENTS.md), so
     * the harness keeps them one property away.
     */
    @Override
    protected ILogger createLogger(String name) {
        org.spongepowered.asm.logging.LoggerAdapterConsole adapter =
                new org.spongepowered.asm.logging.LoggerAdapterConsole(name);
        if (Boolean.getBoolean("cim.debug.mixins")) {
            adapter.setDebugStream(System.out);
        }
        return adapter;
    }

    // ---- nested SPI impls ---------------------------------------------------------

    /** Class resolution straight through the shadow loader (hermetic). */
    private static final class ShadowClassProvider implements IClassProvider {
        @Override
        public URL[] getClassPath() {
            return new URL[0]; // the processor uses the bytecode provider, not the classpath
        }

        @Override
        public Class<?> findClass(String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader());
        }

        @Override
        public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
            return Class.forName(name, initialize, loader());
        }

        @Override
        public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
            return Class.forName(name, initialize, loader());
        }
    }

    /**
     * Target/mixin bytes via the loader's own pipeline (index → raw →
     * relocation): the SAME bytes the loader would define, so the @Mixin
     * targets the processor reads match the names arriving at the transformer
     * exactly.
     */
    private static final class ShadowBytecodeProvider implements IClassBytecodeProvider {
        @Override
        public ClassNode getClassNode(String name) throws ClassNotFoundException, java.io.IOException {
            return getClassNode(name, false, 0);
        }

        @Override
        public ClassNode getClassNode(String name, boolean runTransformers)
                throws ClassNotFoundException, java.io.IOException {
            return getClassNode(name, runTransformers, 0);
        }

        @Override
        public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
                throws ClassNotFoundException, java.io.IOException {
            // Class names arrive binary-dotted from the processor; the loader
            // indexes binary names directly. Typed call is valid under the
            // single-definition contract (dev.cim.** delegates to the host).
            byte[] bytes = ShadowClassLoader.get().classBytesFor(name);
            ClassNode node = new ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node, readerFlags);
            return node;
        }
    }

    private static final class ShadowClassTracker implements IClassTracker {
        @Override
        public void registerInvalidClass(String name) {
            // no invalid-class cache in the shadow space
        }

        @Override
        public boolean isClassLoaded(String name) {
            return ShadowClassLoader.get().isLoadedHere(name);
        }

        @Override
        public String getClassRestrictions(String className) {
            return "";
        }
    }

    private static final class ShadowTransformerProvider implements ITransformerProvider {
        @Override
        public Collection<ITransformer> getTransformers() {
            return Collections.emptyList();
        }

        @Override
        public Collection<ITransformer> getDelegatedTransformers() {
            return Collections.emptyList();
        }

        @Override
        public void addTransformerExclusion(String name) {
            // no external transformers in the shadow space
        }
    }

    private static final class ShadowAuditTrail implements IMixinAuditTrail {
        @Override
        public void onApply(String className, String mixinName) {
        }

        @Override
        public void onPostProcess(String className) {
        }

        @Override
        public void onGenerate(String className, String mixinName) {
        }
    }
}
