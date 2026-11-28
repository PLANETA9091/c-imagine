package dev.f2v.vm.discovery;

import dev.f2v.bridge.F2VLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java-side reader for Mojang's ProGuard client mappings ({@code vanilla-client-1.21.1.mappings.txt}).
 * Mirrors the native reader in {@code crates/cim-classgraph/src/mapping.rs}: class lines
 * ({@code net.minecraft.Foo -> dsx:}) give obfuscated -> named internal names, indented member
 * lines are ignored, so the Java fallback renames only classes, exactly like the native index.
 * Lets the Java walk resolve named client classes (e.g. ConfirmScreen) in the obfuscated client jar.
 */
final class JavaClientMapping {
    private static final Map<Path, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private JavaClientMapping() {}

    /** Obfuscated -> named internal-name map for the vanilla client jar, or null when the jar has
     * no sibling mappings file (any other archive, such as the Paper cache jar). */
    static Map<String, String> forArchive(Path archive) {
        if (archive == null || archive.getFileName() == null) return null;
        if ("off".equals(System.getProperty("f2v.javaClientMapping"))) return null;
        String name = archive.getFileName().toString();
        if (!name.startsWith("vanilla-client-") || !name.endsWith(".jar")) return null;
        Path mappings = archive.toAbsolutePath().normalize().resolveSibling(
                name.substring(0, name.length() - ".jar".length()) + ".mappings.txt");
        if (!Files.isRegularFile(mappings)) return null;
        return CACHE.computeIfAbsent(mappings, JavaClientMapping::load);
    }

    static Map<String, String> parse(String text) {
        Map<String, String> obfToNamed = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t' || line.charAt(0) == '#') continue;
            int arrow = line.indexOf(" -> ");
            if (arrow < 0 || !line.endsWith(":")) continue;
            String named = line.substring(0, arrow).trim().replace('.', '/');
            String obf = line.substring(arrow + 4, line.length() - 1).trim().replace('.', '/');
            if (!named.isEmpty() && !obf.isEmpty()) obfToNamed.put(obf, named);
        }
        return obfToNamed;
    }

    private static Map<String, String> load(Path mappings) {
        try {
            return Collections.unmodifiableMap(parse(Files.readString(mappings, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException failure) {
            F2VLog.warn("[vm] client mappings unreadable " + mappings + ": " + failure);
            return Collections.emptyMap();
        }
    }
}
