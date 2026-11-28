package dev.cim.bootstrap;

import java.util.Arrays;

/**
 * Bridge to the Rust class-hierarchy index in {@code libimagine.so} (crate {@code cim-classgraph}).
 *
 * <p>Build once per boot over the jar list (mods, vanilla client when present, NeoForge universal),
 * then query in batch: {@link #classify(String[], String[])} answers a whole jar's worth of subtype
 * questions in one JNI crossing. Every method reports {@link #available()} == false when the library
 * is not loaded; callers must then keep their Java walk (CI unit tests run without the native lib).
 */
public final class NativeClassGraph {

    private static volatile String libPath;
    private static volatile boolean loaded;
    private static volatile boolean failed;

    private NativeClassGraph() {
    }

    /** Record the absolute {@code libimagine.so} path (same path {@link NativeReloc} uses). */
    public static void setup(String path) {
        libPath = path;
    }

    private static synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        if (failed || libPath == null || libPath.isEmpty()) {
            return false;
        }
        try {
            System.load(libPath);
            loaded = true;
        } catch (UnsatisfiedLinkError | SecurityException e) {
            failed = true;
            return false;
        }
        return true;
    }

    /** True when the native index is loaded and usable. */
    public static boolean available() {
        return ensureLoaded();
    }

    /**
     * Build (or load from cache) the global index.
     *
     * @param jars     archives in priority order (earlier wins on duplicate class names); missing ones are skipped
     * @param cacheDir directory for the keyed cache file, or {@code null}/empty to disable caching
     * @return indexed class count, or -1 when the native library is unavailable or the build failed
     */
    public static int build(String[] jars, String cacheDir) {
        if (!ensureLoaded()) {
            return -1;
        }
        return build0(jars, cacheDir == null ? "" : cacheDir);
    }

    /**
     * Batch classification: entry i is a bitmask where bit j is set when {@code classes[i]} is a
     * proper subtype (superclass or superinterface, transitive) of {@code roots[j]}. At most 32 roots.
     * Returns {@code null} when the library or index is unavailable.
     */
    public static int[] classify(String[] classes, String[] roots) {
        if (!ensureLoaded()) {
            return null;
        }
        return classify0(classes, roots);
    }

    /** Strict transitive subtype test; {@code false} when unavailable. */
    public static boolean isSubclass(String cls, String target) {
        return ensureLoaded() && isSubclass0(cls, target);
    }

    /** Transitive ancestors in BFS order; empty when unavailable. */
    public static String[] ancestors(String cls) {
        if (!ensureLoaded()) {
            return new String[0];
        }
        String[] out = ancestors0(cls);
        return out == null ? new String[0] : out;
    }

    /** Convenience for one class against several roots, using {@link #classify}. */
    public static boolean isSubclassOfAny(String cls, String[] roots) {
        int[] mask = classify(new String[] {cls}, roots);
        return mask != null && mask.length == 1 && mask[0] != 0;
    }

    static String describe(String[] jars) {
        return Arrays.toString(jars);
    }

    private static native int build0(String[] jars, String cacheDir);

    private static native int[] classify0(String[] classes, String[] roots);

    private static native boolean isSubclass0(String cls, String target);

    private static native String[] ancestors0(String cls);
}
