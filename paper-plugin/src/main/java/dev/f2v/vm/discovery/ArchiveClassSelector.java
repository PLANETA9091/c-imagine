package dev.f2v.vm.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Selects the class-file view a running JVM would see from a JAR archive.
 *
 * <p>Raw ZIP enumeration exposes every {@code META-INF/versions/<n>/...} entry. Feeding all of
 * those to ASM is both incorrect and fragile: a Java 21 server must not parse a Java 26 override,
 * and scanners must not let archive ordering decide which duplicate logical class wins. This
 * helper collapses base and multi-release entries deterministically to the highest version that is
 * valid for the current runtime.</p>
 */
final class ArchiveClassSelector {
    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    private ArchiveClassSelector() {}

    static <T> List<T> select(Collection<T> entries, Function<T, String> nameOf,
                              boolean multiRelease) {
        return select(entries, nameOf, multiRelease, Runtime.version().feature());
    }

    static <T> List<T> select(Collection<T> entries, Function<T, String> nameOf,
                              boolean multiRelease, int runtimeFeature) {
        if (entries == null || entries.isEmpty() || nameOf == null) return List.of();
        int feature = Math.max(1, runtimeFeature);
        Map<String, Candidate<T>> selected = new LinkedHashMap<>();
        for (T value : entries) {
            if (value == null) continue;
            Parsed parsed = parse(nameOf.apply(value), multiRelease, feature);
            if (parsed == null) continue;
            Candidate<T> previous = selected.get(parsed.logicalPath());
            if (previous == null || parsed.version() > previous.version()) {
                selected.put(parsed.logicalPath(), new Candidate<>(parsed.logicalPath(), parsed.version(), value));
            }
        }
        ArrayList<Candidate<T>> ordered = new ArrayList<>(selected.values());
        ordered.sort(Comparator.comparing(Candidate::logicalPath));
        ArrayList<T> result = new ArrayList<>(ordered.size());
        for (Candidate<T> candidate : ordered) result.add(candidate.value());
        return List.copyOf(result);
    }

    /** Logical class-file path selected for this runtime, or null when the entry is not visible. */
    static String logicalPath(String name, boolean multiRelease) {
        Parsed parsed = parse(name, multiRelease, Runtime.version().feature());
        return parsed == null ? null : parsed.logicalPath();
    }

    private static Parsed parse(String name, boolean multiRelease, int runtimeFeature) {
        if (name == null || !name.endsWith(".class")) return null;
        if (!name.startsWith(VERSIONS_PREFIX)) return new Parsed(name, 0);
        if (!multiRelease) return null;

        int versionStart = VERSIONS_PREFIX.length();
        int slash = name.indexOf('/', versionStart);
        if (slash <= versionStart || slash >= name.length() - 1) return null;
        int version;
        try {
            version = Integer.parseInt(name.substring(versionStart, slash));
        } catch (NumberFormatException ignored) {
            return null;
        }
        // Multi-release JAR versions start at Java 9 by specification.
        if (version < 9 || version > runtimeFeature) return null;
        String logical = name.substring(slash + 1);
        if (!logical.endsWith(".class") || logical.isBlank()) return null;
        return new Parsed(logical, version);
    }

    private record Parsed(String logicalPath, int version) {}
    private record Candidate<T>(String logicalPath, int version, T value) {}
}
