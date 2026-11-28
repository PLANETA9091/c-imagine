package dev.f2v.vm.discovery;

import dev.f2v.bridge.F2VLog;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reaches the Rust class-hierarchy index ({@code dev.cim.bootstrap.NativeClassGraph}, crate
 * cim-classgraph) by reflection: no compile dependency, and the Java walk in {@link ModScanner}
 * stays the fallback when the class or the native library is absent (CI unit tests).
 */
final class NativeClassIndex {
    private NativeClassIndex() {}

    private static final String TARGET = "dev.cim.bootstrap.NativeClassGraph";

    private static volatile boolean resolved;
    private static Method build;
    private static Method known;
    private static Method isSubclass;
    private static String builtFor;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        Class<?> c = null;
        for (ClassLoader cl : new ClassLoader[] {NativeClassIndex.class.getClassLoader(),
                ClassLoader.getSystemClassLoader()}) {
            if (cl == null) continue;
            try {
                c = Class.forName(TARGET, false, cl);
                break;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // try the next loader
            }
        }
        if (c == null) return;
        try {
            Method b = c.getMethod("build", String[].class, String.class);
            Method k = c.getMethod("known", String.class);
            Method s = c.getMethod("isSubclass", String.class, String.class);
            Method av = c.getMethod("available");
            if (!(Boolean) av.invoke(null)) return;
            build = b;
            known = k;
            isSubclass = s;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            F2VLog.warn("[vm] native class index unavailable: " + e);
        }
    }

    /** True when the native index is loaded (after the last successful {@link #ensure}). */
    static synchronized boolean available() {
        resolve();
        return build != null && builtFor != null;
    }

    /**
     * Builds the native index over {@code archives} (once per distinct list). The vanilla client
     * jar, when present in the archive list, is indexed like any other archive.
     *
     * @return true when the native index covers these archives
     */
    static synchronized boolean ensure(List<Path> archives, Path userDir) {
        resolve();
        if (build == null) return false;
        String key = archives.stream().map(p -> p.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining("\n"));
        if (key.equals(builtFor)) return true;
        Path cache = userDir == null ? null : userDir.resolve("cache").resolve("classgraph");
        String[] jars = archives.stream().filter(Files::isRegularFile)
                .map(p -> p.toAbsolutePath().normalize().toString()).toArray(String[]::new);
        try {
            Object n = build.invoke(null, (Object) jars, cache == null ? "" : cache.toString());
            if (!(n instanceof Integer count) || count < 0) return false;
            builtFor = key;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            F2VLog.warn("[vm] native class index build failed, using Java walk: " + e);
            return false;
        }
    }

    /** Strict transitive subtype answer, or null when the native index does not know the class. */
    static synchronized Boolean isSubclass(String cls, String target) {
        if (!available() || cls == null || target == null) return null;
        try {
            if (!(Boolean) known.invoke(null, cls)) return null;
            return (Boolean) isSubclass.invoke(null, cls, target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
