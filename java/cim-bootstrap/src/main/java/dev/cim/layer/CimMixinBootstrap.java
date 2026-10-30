package dev.cim.layer;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * Trivial bootstrap service so sponge-mixin's own boot discovery never runs.
 *
 * <p>{@code MixinService} reads the {@code mixin.bootstrapService} system
 * property first: when it is set, {@code runBootServices()} instantiates ONLY
 * that class and returns — the LaunchWrapper/ModLauncher bootstraps shipped
 * inside the sponge-mixin jar (both in the 1.21.1 layer jar set, both visible
 * through {@code getResources}) never execute. Without this they would run
 * modlauncher/LaunchWrapper probes and pollute the shadow-space boot.
 *
 * <p>The service itself is selected by {@code mixin.service} →
 * {@link CimMixinService} (harness contract {@code mod-parity-harness.md §8}).
 */
public final class CimMixinBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "cim";
    }

    @Override
    public String getServiceClassName() {
        return "dev.cim.layer.CimMixinService";
    }

    @Override
    public void bootstrap() {
        // Nothing to arm: the shadow loader, the relocation table and the mod
        // jars are already registered by the time the mixin gate initializes
        // (Bootstrap.init order — mod-parity-harness.md §8 point 2).
    }
}
