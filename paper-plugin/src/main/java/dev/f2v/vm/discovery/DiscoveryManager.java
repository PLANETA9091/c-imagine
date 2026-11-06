package dev.f2v.vm.discovery;

import java.util.LinkedHashMap;
import java.util.Map;

/** Holds scanned profiles; F2V's version also drives its NeoForge discovery lifecycle. */
public final class DiscoveryManager {
    private static final Map<String, ModScanner.Profile> PROFILES = new LinkedHashMap<>();
    private static volatile long generation;

    private DiscoveryManager() {
    }

    public static long generation() {
        return generation;
    }

    public static synchronized Map<String, ModScanner.Profile> profiles() {
        return Map.copyOf(PROFILES);
    }

    public static synchronized void publish(Map<String, ModScanner.Profile> profiles) {
        PROFILES.clear();
        PROFILES.putAll(profiles);
        generation++;
    }
}
