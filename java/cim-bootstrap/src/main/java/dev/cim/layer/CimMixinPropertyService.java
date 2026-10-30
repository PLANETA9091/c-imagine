package dev.cim.layer;

import java.util.HashMap;
import java.util.Map;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/**
 * Shadow-space global property service (sponge-mixin's "blackboard").
 *
 * <p>The two implementations shipped in the sponge-mixin jar are unusable in
 * the shadow space: {@code service.mojang.Blackboard} needs the LaunchWrapper
 * {@code Launch} class and {@code service.modlauncher.Blackboard} needs the
 * modlauncher environment — neither exists here ({@code MixinService} logs a
 * {@code ServiceNotAvailableError} when both fail, aborting the gate before
 * the transformer exists). Registered through
 * {@code META-INF/services/org.spongepowered.asm.service.IGlobalPropertyService}
 * packaged in the bootstrap jar — the shadow loader enumerates that jar
 * FIRST in {@code getResources}, so this service wins the discovery
 * deterministically.
 *
 * <p>{@code IPropertyKey} is a marker interface — the key wraps its name.
 */
public final class CimMixinPropertyService implements IGlobalPropertyService {

    private static final Map<String, Object> PROPERTIES = new HashMap<>();

    private record Key(String name) implements IPropertyKey {
    }

    @Override
    public IPropertyKey resolveKey(String name) {
        return new Key(name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getProperty(IPropertyKey key) {
        return (T) PROPERTIES.get(((Key) key).name());
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (value == null) {
            PROPERTIES.remove(((Key) key).name());
        } else {
            PROPERTIES.put(((Key) key).name(), value);
        }
    }

    @Override
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        Object v = PROPERTIES.get(((Key) key).name());
        return v == null ? defaultValue : (T) v;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object v = PROPERTIES.get(((Key) key).name());
        return v == null ? defaultValue : String.valueOf(v);
    }
}
