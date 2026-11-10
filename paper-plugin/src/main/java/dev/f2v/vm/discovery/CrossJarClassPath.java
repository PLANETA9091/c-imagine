package dev.f2v.vm.discovery;

import dev.f2v.bridge.F2VLog;
import dev.f2v.generic.JarAccess;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/**
 * Lazy classpath view used by bytecode discovery.
 *
 * <p>Every scanner must iterate only the classes owned by the jar currently being profiled, but
 * inheritance and helper-method walks need to resolve parents supplied by dependency jars. The
 * overlay map below deliberately exposes local entries through iteration while {@code get()} falls
 * back to this cross-jar index. Dependency classes are parsed only when a scanner actually follows
 * an edge to them, which avoids materializing the entire modpack as ASM trees.</p>
 */
final class CrossJarClassPath implements AutoCloseable {
    private static final AtomicLong PARSE_WARNINGS = new AtomicLong();

    private record Location(Path archive, String entry) {}

    private final Map<String, Location> locations;
    private final int parsedLimit = Math.max(64,
            Integer.getInteger("f2v.discoveryClassCache", 1024));
    /** Access-ordered dependency cache. A giant pack can contain tens of thousands of classes;
     * retaining every ClassNode (including every instruction tree) for the entire discovery pass
     * needlessly turns lazy lookup into an unbounded heap leak. Re-parsing a cold parent is much
     * cheaper than keeping the whole modpack resident. */
    private final Map<String, ClassNode> parsed = Collections.synchronizedMap(
            new LinkedHashMap<>(256, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ClassNode> eldest) {
                    return size() > parsedLimit;
                }
            });
    private final Set<String> failed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final JarAccess.Session session = JarAccess.session();
    private final String fingerprint;

    private CrossJarClassPath(Map<String, Location> locations, String fingerprint) {
        this.locations = Map.copyOf(locations);
        this.fingerprint = fingerprint;
    }

    static CrossJarClassPath index(Collection<Path> archives) throws IOException {
        List<Path> ordered = normalizedArchives(archives);
        LinkedHashMap<String, Location> locations = new LinkedHashMap<>();
        for (Path archive : ordered) {
            try {
                indexDirect(archive, locations);
                indexNested(archive, locations);
            } catch (IOException | RuntimeException failure) {
                if (PARSE_WARNINGS.incrementAndGet() <= 20) {
                    F2VLog.warn("[vm] dependency archive index skipped " + archive + ": " + failure);
                }
            }
        }
        return new CrossJarClassPath(locations, fingerprintOfNormalized(ordered));
    }

    /** Cheap cache identity that does not open or enumerate any archives. */
    static String fingerprintOf(Collection<Path> archives) {
        return fingerprintOfNormalized(normalizedArchives(archives));
    }

    private static List<Path> normalizedArchives(Collection<Path> archives) {
        List<Path> ordered = new ArrayList<>();
        if (archives != null) {
            for (Path archive : archives) {
                if (archive != null && Files.isRegularFile(archive)) {
                    ordered.add(archive.toAbsolutePath().normalize());
                }
            }
        }
        return ordered;
    }

    int indexedClasses() {
        return locations.size();
    }

    String fingerprint() {
        return fingerprint;
    }

    Map<String, ClassNode> overlay(Map<String, ClassNode> local) {
        Map<String, ClassNode> owned = local == null ? new LinkedHashMap<>() : local;
        return new AbstractMap<>() {
            @Override
            public Set<Entry<String, ClassNode>> entrySet() {
                return owned.entrySet();
            }

            @Override
            public ClassNode get(Object key) {
                ClassNode value = owned.get(key);
                if (value != null || owned.containsKey(key)) return value;
                return key instanceof String name ? resolve(name) : null;
            }

            @Override
            public boolean containsKey(Object key) {
                return owned.containsKey(key)
                        || key instanceof String name && CrossJarClassPath.this.contains(name);
            }

            @Override
            public ClassNode put(String key, ClassNode value) {
                return owned.put(key, value);
            }

            @Override
            public int size() {
                return owned.size();
            }
        };
    }

    private boolean contains(String className) {
        return className != null && locations.containsKey(className);
    }

    private ClassNode resolve(String className) {
        if (className == null || failed.contains(className)) return null;
        ClassNode cached;
        synchronized (parsed) {
            cached = parsed.get(className);
        }
        if (cached != null) return cached;
        Location location = locations.get(className);
        if (location == null) return null;
        try {
            byte[] bytes;
            synchronized (session) {
                bytes = session.read(location.archive(), location.entry());
            }
            if (bytes == null || bytes.length == 0) {
                failed.add(className);
                return null;
            }
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (node.name == null) {
                failed.add(className);
                return null;
            }
            synchronized (parsed) {
                ClassNode previous = parsed.get(className);
                if (previous != null) return previous;
                parsed.put(className, node);
                return node;
            }
        } catch (RuntimeException failure) {
            failed.add(className);
            if (PARSE_WARNINGS.incrementAndGet() <= 20) {
                F2VLog.warn("[vm] dependency class parse skipped " + className + " from "
                        + location.archive() + "!/" + location.entry() + ": " + failure);
            }
            return null;
        }
    }

    private static void indexDirect(Path archive, Map<String, Location> out) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            List<JarEntry> candidates = new ArrayList<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) candidates.add(entry);
            }
            List<JarEntry> selected = ArchiveClassSelector.select(
                    candidates, JarEntry::getName, jar.isMultiRelease());
            for (JarEntry entry : selected) {
                String logical = ArchiveClassSelector.logicalPath(entry.getName(), jar.isMultiRelease());
                String className = className(logical);
                if (className != null) out.putIfAbsent(className, new Location(archive, entry.getName()));
            }
        }
    }

    private static void indexNested(Path outer, Map<String, Location> out) {
        for (JarAccess.NestedArchive archive : JarAccess.nestedArchives(outer)) {
            try {
                List<String> candidates = new ArrayList<>();
                boolean multiRelease;
                try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(archive.bytes()))) {
                    var manifest = input.getManifest();
                    multiRelease = manifest != null && "true".equalsIgnoreCase(
                            manifest.getMainAttributes().getValue("Multi-Release"));
                    JarEntry entry;
                    while ((entry = input.getNextJarEntry()) != null) {
                        if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                            candidates.add(entry.getName());
                        }
                    }
                }
                List<String> selected = ArchiveClassSelector.select(
                        candidates, Function.identity(), multiRelease);
                Path compound = compoundPath(outer, archive.reference());
                for (String entry : selected) {
                    String logical = ArchiveClassSelector.logicalPath(entry, multiRelease);
                    String className = className(logical);
                    if (className != null) out.putIfAbsent(className, new Location(compound, entry));
                }
            } catch (IOException | RuntimeException failure) {
                if (PARSE_WARNINGS.incrementAndGet() <= 20) {
                    F2VLog.warn("[vm] dependency nested-index skipped " + archive.reference()
                            + ": " + failure);
                }
            }
        }
    }

    private static Path compoundPath(Path outer, String reference) {
        String normalizedOuter = outer.toAbsolutePath().normalize().toString();
        String outerName = outer.getFileName().toString();
        String suffix = reference != null && reference.startsWith(outerName)
                ? reference.substring(outerName.length())
                : "!/" + (reference == null ? "" : reference);
        return Path.of(normalizedOuter + suffix);
    }

    private static String className(String logicalPath) {
        if (logicalPath == null || !logicalPath.endsWith(".class")) return null;
        String value = logicalPath.substring(0, logicalPath.length() - ".class".length());
        return value.isBlank() ? null : value;
    }

    private static String fingerprintOfNormalized(List<Path> archives) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            archives.stream().sorted(Comparator.comparing(Path::toString)).forEach(path -> {
                try {
                    String value = path + "\n" + Files.size(path) + "\n"
                            + Files.getLastModifiedTime(path).toMillis() + "\n";
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    digest.update((path + "\nmissing\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(24);
            for (int i = 0; i < Math.min(12, bytes.length); i++) {
                out.append(String.format(java.util.Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "classpath";
        }
    }

    @Override
    public void close() {
        session.close();
        synchronized (parsed) {
            parsed.clear();
        }
        failed.clear();
    }
}
