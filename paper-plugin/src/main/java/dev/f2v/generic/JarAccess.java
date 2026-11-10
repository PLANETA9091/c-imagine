package dev.f2v.generic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;

/** Reads ordinary and jar-in-jar mod archives without extracting them to the server filesystem. */
public final class JarAccess {
    private static final String SEPARATOR = "!/";
    private static final int MAX_NESTING = 4;

    private JarAccess() {}

    public record NestedArchive(String reference, byte[] bytes) {}

    /**
     * Reuses direct JarFile handles for a bounded batch of related asset reads. This avoids
     * reopening the same mod archive hundreds of times during pack generation while keeping the
     * lifetime explicit so mod jars are never pinned after the batch finishes.
     */
    public static final class Session implements AutoCloseable {
        private final Map<Path, JarFile> directArchives = new HashMap<>();
        private final Map<String, byte[]> nestedArchives = new HashMap<>();

        public byte[] read(Path archive, String entry) {
            if (archive == null || entry == null) return null;
            String reference = archive.toString();
            int separator = reference.indexOf(SEPARATOR);
            Path outer = separator < 0 ? archive : Path.of(reference.substring(0, separator));
            if (!Files.isRegularFile(outer)) return null;
            try {
                if (separator < 0) return readDirectCached(outer, entry);
                byte[] bytes = resolveNested(outer,
                        reference.substring(separator + SEPARATOR.length()));
                return bytes == null ? null : readBytes(bytes, entry);
            } catch (IOException ignored) {
                return null;
            }
        }

        private byte[] resolveNested(Path outer, String nested) throws IOException {
            byte[] bytes = null;
            StringBuilder reference = new StringBuilder(outer.toAbsolutePath().normalize().toString());
            for (String segment : nested.split(java.util.regex.Pattern.quote(SEPARATOR))) {
                reference.append(SEPARATOR).append(segment);
                String key = reference.toString();
                byte[] cached = nestedArchives.get(key);
                if (cached != null) {
                    bytes = cached;
                    continue;
                }
                bytes = bytes == null ? readDirectCached(outer, segment) : readBytes(bytes, segment);
                if (bytes == null) return null;
                nestedArchives.put(key, bytes);
            }
            return bytes;
        }

        private byte[] readDirectCached(Path archive, String entry) throws IOException {
            Path key = archive.toAbsolutePath().normalize();
            JarFile jar = directArchives.get(key);
            if (jar == null) {
                jar = new JarFile(key.toFile());
                directArchives.put(key, jar);
            }
            JarEntry value = jar.getJarEntry(entry);
            if (value == null || value.isDirectory()) return null;
            try (InputStream input = jar.getInputStream(value)) {
                return input.readAllBytes();
            }
        }

        @Override
        public void close() {
            for (JarFile jar : directArchives.values()) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
            directArchives.clear();
            nestedArchives.clear();
        }
    }

    public static Session session() {
        return new Session();
    }

    /** Returns an entry from a direct jar or a compound {@code outer.jar!/path/inner.jar}. */
    public static byte[] read(Path archive, String entry) {
        if (archive == null || entry == null) return null;
        String reference = archive.toString();
        int separator = reference.indexOf(SEPARATOR);
        Path outer = separator < 0 ? archive : Path.of(reference.substring(0, separator));
        if (!Files.isRegularFile(outer)) return null;
        try {
            if (separator < 0) return readDirect(outer, entry);
            byte[] bytes = null;
            String nested = reference.substring(separator + SEPARATOR.length());
            for (String segment : nested.split(java.util.regex.Pattern.quote(SEPARATOR))) {
                bytes = bytes == null ? readDirect(outer, segment) : readBytes(bytes, segment);
                if (bytes == null) return null;
            }
            return readBytes(bytes, entry);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** Visits every entry in the selected direct or compound archive. */
    public static void forEach(Path archive, BiConsumer<String, byte[]> visitor) {
        if (archive == null || visitor == null) return;
        String reference = archive.toString();
        int separator = reference.indexOf(SEPARATOR);
        Path outer = separator < 0 ? archive : Path.of(reference.substring(0, separator));
        if (!Files.isRegularFile(outer)) return;
        try {
            if (separator < 0) {
                try (JarFile jar = new JarFile(outer.toFile())) {
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry value = entries.nextElement();
                        if (value.isDirectory()) continue;
                        try (InputStream input = jar.getInputStream(value)) {
                            visitor.accept(value.getName(), input.readAllBytes());
                        }
                    }
                }
                return;
            }
            byte[] bytes = null;
            String nested = reference.substring(separator + SEPARATOR.length());
            for (String segment : nested.split(java.util.regex.Pattern.quote(SEPARATOR))) {
                bytes = bytes == null ? readDirect(outer, segment) : readBytes(bytes, segment);
                if (bytes == null) return;
            }
            forEachBytes(bytes, visitor);
        } catch (IOException ignored) {
        }
    }

    /** Finds all nested jars recursively and gives each one a stable compound reference. */
    public static List<NestedArchive> nestedArchives(Path outer) {
        List<NestedArchive> result = new ArrayList<>();
        if (outer == null || !Files.isRegularFile(outer)) return result;
        try (JarFile jar = new JarFile(outer.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".jar")) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    byte[] bytes = input.readAllBytes();
                    String reference = outer.getFileName() + SEPARATOR + entry.getName();
                    result.add(new NestedArchive(reference, bytes));
                    collectNested(reference, bytes, result, 1);
                }
            }
        } catch (IOException ignored) {
        }
        return List.copyOf(result);
    }

    private static void collectNested(String parent, byte[] archive,
                                      List<NestedArchive> result, int depth) {
        if (depth >= MAX_NESTING) return;
        try {
            forEachBytes(archive, (name, bytes) -> {
                if (!name.endsWith(".jar")) return;
                String reference = parent + SEPARATOR + name;
                result.add(new NestedArchive(reference, bytes));
                collectNested(reference, bytes, result, depth + 1);
            });
        } catch (IOException ignored) {
        }
    }

    private static byte[] readDirect(Path archive, String entry) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile())) {
            JarEntry value = jar.getJarEntry(entry);
            if (value == null || value.isDirectory()) return null;
            try (InputStream input = jar.getInputStream(value)) {
                return input.readAllBytes();
            }
        }
    }

    private static byte[] readBytes(byte[] archive, String entry) throws IOException {
        // A class lookup is not an archive traversal. The old visitor inflated and allocated
        // every remaining asset even after finding the class, multiplied by every call-graph
        // edge into a bundled library. ZipInputStream also preserves the manifest as an entry.
        try (var input = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry candidate;
            while ((candidate = input.getNextEntry()) != null) {
                if (!candidate.isDirectory() && candidate.getName().equals(entry)) return input.readAllBytes();
                input.closeEntry();
            }
        }
        return null;
    }

    private static void forEachBytes(byte[] archive, BiConsumer<String, byte[]> visitor)
            throws IOException {
        try (JarInputStream input = new JarInputStream(new ByteArrayInputStream(archive))) {
            JarEntry entry;
            while ((entry = input.getNextJarEntry()) != null) {
                if (!entry.isDirectory()) visitor.accept(entry.getName(), input.readAllBytes());
            }
        }
    }
}
