package dev.cim.bootstrap;

/**
 * Bridge to the native (Rust) relocation stage living in {@code libimagine.so}.
 *
 * The shadow loader hands every class it defines through
 * {@link #relocate(String, byte[])}; the native side rewrites the constant
 * pool for classes inside the relocation space (see {@code cim-reloc}) and
 * passes everything else through unchanged.
 *
 * The library is {@link System#load(String)ed} lazily on first use, by
 * absolute path discovered by the native module itself (it knows where its
 * own .so lives). Loading through the bootstrap classloader keeps a single
 * copy of the shared library: CRUSSTY already dlopen()ed it as the module,
 * so this is a refcount-only operation.
 */
public final class NativeReloc {

    private static volatile String libPath;
    private static volatile boolean loaded;

    private NativeReloc() {
    }

    /** Record the absolute .so path (before any class loading happens). */
    public static void setup(String path) {
        libPath = path;
    }

    /** Whether relocation is available (library path known). */
    public static boolean available() {
        return libPath != null && !libPath.isEmpty();
    }

    private static void ensureLoaded() {
        if (!loaded) {
            synchronized (NativeReloc.class) {
                if (!loaded) {
                    if (libPath == null || libPath.isEmpty()) {
                        throw new IllegalStateException(
                                "relocation library path not set (NativeReloc.setup missing)");
                    }
                    System.load(libPath);
                    loaded = true;
                }
            }
        }
    }

    /**
     * Relocate one class file.
     *
     * @param internalName slash-separated requested name, e.g.
     *                     {@code net/minecraft/server/Main}
     * @param classBytes   raw class file bytes
     * @return relocated bytes (a new array), or the input array when the
     *         class is outside the relocation space
     * @throws IllegalStateException when the native stage fails (fail-closed:
     *         the class must not be defined unrelocated)
     */
    static byte[] relocate(String internalName, byte[] classBytes) {
        ensureLoaded();
        byte[] out = relocate0(internalName, classBytes);
        if (out == null || out.length == 0) {
            throw new IllegalStateException("native relocation failed for " + internalName);
        }
        return out;
    }

    /**
     * Relocated internal name for {@code internalName}, or {@code null} when
     * the name is outside the relocation space. Used by the shadow loader to
     * index layer jars under their relocated class paths.
     */
    static String mapInternal(String internalName) {
        ensureLoaded();
        return mapInternal0(internalName);
    }

    private static native String mapInternal0(String internalName);

    private static native byte[] relocate0(String internalName, byte[] classBytes);
}
