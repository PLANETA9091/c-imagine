package dev.cim.f2v;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Open-once jar reader + per-path byte cache (task 13-j).
 *
 * MEASURED #50: the cold seed's pack build re-opened every ZipFile on EVERY
 * json/png read — ~22k gallery states x (mod jars + the 41.5 MB client jar
 * with its 66k-entry central directory) = the 5.9-min pack build. With the
 * cache every jar is opened exactly once and every resource body is read at
 * most once per run (parent models, wall/tail parents and vanilla-parent
 * chains repeat heavily across states).
 *
 * SEMANTICS (preserved exactly): the jars are probed IN ORDER (mods first,
 * client last), first match wins. {@link #readFirst(String...)} preserves
 * the older loop's nested order (jar, then candidate) — per jar it tries
 * every candidate before moving on, so a later jar's first candidate never
 * shadows an earlier jar's second candidate.
 */
final class JarCache implements AutoCloseable {

    /** One resolved resource: the winning candidate path + its raw bytes. */
    record Found(String file, byte[] bytes) {
    }

    private final List<Path> paths;
    private final Map<Path, ZipFile> open = new HashMap<>();
    private final Map<String, byte[]> cache = new HashMap<>();

    JarCache(List<Path> paths) {
        this.paths = List.copyOf(paths);
    }

    /** The jar paths, in probe order (mods first, client last). */
    List<Path> paths() {
        return paths;
    }

    /** True when no jars were found (the equipment fallback probes modsDir again). */
    boolean isEmpty() {
        return paths.isEmpty();
    }

    /** The zip file for one jar path, opened once (read-only contract). */
    ZipFile zip(Path jar) throws IOException {
        ZipFile zf = open.get(jar);
        if (zf == null) {
            zf = new ZipFile(jar.toFile());
            open.put(jar, zf);
        }
        return zf;
    }

    /**
     * Raw bytes of one resource from the first jar that carries it; null
     * when absent everywhere.
     */
    byte[] read(String file) throws IOException {
        byte[] hit = cache.get(file);
        if (hit != null) {
            return hit;
        }
        for (Path jar : paths) {
            ZipEntry e = zip(jar).getEntry(file);
            if (e != null) {
                byte[] b = zip(jar).getInputStream(e).readAllBytes();
                cache.put(file, b);
                return b;
            }
        }
        return null;
    }

    /**
     * The legacy loop's probe order, preserved: PER JAR try every candidate,
     * then move to the next jar (a later jar's first candidate must not
     * shadow an earlier jar's second candidate). Null when no jar carries
     * any candidate.
     */
    Found readFirst(String... candidates) throws IOException {
        for (Path jar : paths) {
            ZipFile zf = zip(jar);
            for (String cand : candidates) {
                ZipEntry e = zf.getEntry(cand);
                if (e != null) {
                    return new Found(cand, zf.getInputStream(e).readAllBytes());
                }
            }
        }
        return null;
    }

    @Override
    public void close() {
        for (ZipFile zf : open.values()) {
            try {
                zf.close();
            } catch (IOException ignored) {
            }
        }
        open.clear();
        cache.clear();
    }

    /** The legacy listJars result: every *.jar directly inside modsDir, sorted. */
    static List<Path> listJars(Path modsDir) throws IOException {
        List<Path> jars = new ArrayList<>();
        if (modsDir != null && java.nio.file.Files.isDirectory(modsDir)) {
            try (var s = java.nio.file.Files.list(modsDir)) {
                s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
            }
        }
        return jars;
    }
}
