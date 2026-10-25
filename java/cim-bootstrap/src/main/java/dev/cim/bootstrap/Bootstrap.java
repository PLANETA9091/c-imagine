package dev.cim.bootstrap;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Java-side entry point driven by the c-imagine native module (CRUSSTY).
 *
 * The native module deploys this jar to a temp dir and calls
 * {@link #init(String)} through JNI on a loader thread. This class must stay
 * dependency-free (JDK only) and bytecode-compatible with Java 21 (compiled
 * with {@code -release 21}) so it loads on any modern host JVM including the
 * Java 25 runtime Paper 26.3 requires.
 *
 * M0 contract: verify the embedded jar, prepare the isolated
 * {@link ShadowClassLoader} for the 1.21.1 layer (M1 wires the relocated
 * Minecraft/NeoForge stack into it), and report status as JSON.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /**
     * Called via JNI by the native module.
     *
     * @param optionsJson {"jar": "<deployed bootstrap jar>", "mods": "<mods dir>", "kernel": "<paper jar>"}
     * @return JSON status string; the native side logs it verbatim.
     */
    public static String init(String optionsJson) {
        long started = System.nanoTime();
        String jarPath = JsonKit.str(optionsJson, "jar");
        String modsDir = JsonKit.str(optionsJson, "mods");
        String kernel = JsonKit.str(optionsJson, "kernel");

        int jarEntries = -1;
        if (jarPath != null) {
            try (JarFile jar = new JarFile(jarPath)) {
                jarEntries = jar.size();
            } catch (Exception e) {
                return status("error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                        jarEntries, 0, started);
            }
        }

        ShadowClassLoader.prepare(jarPath);
        int loaded = ShadowClassLoader.loadedClassCount();

        System.out.println("[c-imagine] bootstrap init: jar=" + jarPath
                + " entries=" + jarEntries
                + " mods=" + modsDir
                + " kernel=" + kernel);
        return status("ok", null, jarEntries, loaded, started);
    }

    private static String status(String state, String error, int jarEntries,
                                 int shadowClasses, long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000L;
        return "{\"state\":\"" + state + "\""
                + (error != null ? ",\"error\":\"" + JsonKit.escape(error) + "\"" : "")
                + ",\"jarEntries\":" + jarEntries
                + ",\"shadowClasses\":" + shadowClasses
                + ",\"tookMs\":" + ms
                + "}";
    }
}
