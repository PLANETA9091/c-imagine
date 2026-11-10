package dev.f2v.vm.discovery;

import java.util.Map;

/** Fixed to what the c-imagine layer provides; the original asks NeoForge's ModList. */
public final class RuntimePlatformVersions {
    private RuntimePlatformVersions() {}

    public static Map<String, String> current() {
        return Map.of("minecraft", "1.21.1", "neoforge", "21.1.265");
    }
}
