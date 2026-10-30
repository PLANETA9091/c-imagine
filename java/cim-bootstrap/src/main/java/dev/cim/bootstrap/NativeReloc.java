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

    /**
     * Arm the M2 mod-space relocation rules from the layer manifest JSON
     * (produced by the native scanner). Must be called before any mod class
     * is defined: the shadow loader's jar index resolves mod classes through
     * {@link #mapInternal}, which only knows mod packages afterwards.
     *
     * <p>Failure is logged on the native side and reflected here by a
     * best-effort contract: the rules are armed best-effort, and any mod
     * class that still fails to relocate fails loudly at define time.
     */
    public static void registerMods(String manifestJson) {
        if (manifestJson == null || manifestJson.isEmpty()) {
            return;
        }
        ensureLoaded();
        registerMods0(manifestJson);
    }

    /**
     * Mod-space relocation (M2): rewrite references into the layer
     * namespaces while keeping the class's own binary name. Always parses;
     * returns the input array when nothing changed.
     */
    static byte[] relocateMod(String internalName, byte[] classBytes) {
        ensureLoaded();
        byte[] out = relocateMod0(internalName, classBytes);
        if (out == null || out.length == 0) {
            throw new IllegalStateException("native relocation failed for " + internalName);
        }
        return out;
    }

    /** Process-wide count of {@code @OnlyIn(Dist.CLIENT)} members stripped
     *  by the relocation stage (layer + mod paths). Reported in the boot log
     *  before mod construction; the CI gate asserts it is non-zero so the
     *  dist-strip parity can never silently stop firing (12-q). */
    public static long clientMembersStripped() {
        // 12-u single-definition contract: dev.cim.** is defined once (the
        // host loader), so this static IS the armed one. Kept tolerant of a
        // missing library for M0-style boots (-1 = not loaded).
        try {
            return clientMembersStripped0();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    private static native void registerMods0(String manifestJson);

    private static native long clientMembersStripped0();

    private static native byte[] relocateMod0(String internalName, byte[] classBytes);

    private static native String mapInternal0(String internalName);

    private static native byte[] relocate0(String internalName, byte[] classBytes);
}
