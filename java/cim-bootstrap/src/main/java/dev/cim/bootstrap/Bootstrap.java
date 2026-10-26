package dev.cim.bootstrap;

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
 * M1 contract: verify the embedded jar, arm the native relocation stage,
 * prepare the isolated {@link ShadowClassLoader} over the 1.21.1 layer jars,
 * and (when a layer dir is configured) boot the relocated vanilla stack
 * through {@code dev.cim.layer.LayerMain}, reporting status as JSON.
 */
public final class Bootstrap {

    private Bootstrap() {
    }

    /**
     * Called via JNI by the native module.
     *
     * @param optionsJson {"jar": "<bootstrap jar>", "mods": "<mods dir>",
     *                    "kernel": "<paper jar>", "moduleLib": "<libimagine.so>",
     *                    "layerDir": "<1.21.1 libraries dir>"}
     * @return JSON status string; the native side logs it verbatim.
     */
    public static String init(String optionsJson) {
        long started = System.nanoTime();
        String jarPath = JsonKit.str(optionsJson, "jar");
        String modsDir = JsonKit.str(optionsJson, "mods");
        String kernel = JsonKit.str(optionsJson, "kernel");
        String moduleLib = JsonKit.str(optionsJson, "moduleLib");
        String layerDir = JsonKit.str(optionsJson, "layerDir");

        int jarEntries = -1;
        if (jarPath != null) {
            try (JarFile jar = new JarFile(jarPath)) {
                jarEntries = jar.size();
            } catch (Exception e) {
                return status("error", e.getClass().getSimpleName() + ": " + e.getMessage(),
                        jarEntries, 0, null, started);
            }
        }

        try {
            // 1) relocation stage must be armed before any layer class loads
            NativeReloc.setup(moduleLib);

            // 2) shadow class space: bootstrap jar + 1.21.1 library set
            ShadowClassLoader.prepare(jarPath, layerDir);

            // 3) M1: boot the relocated vanilla stack (registries bootstrap)
            String layerResult = null;
            if (layerDir != null && !layerDir.isEmpty()) {
                Class<?> layer = Class.forName("dev.cim.layer.LayerMain", true,
                        ShadowClassLoader.get());
                layerResult = (String) layer.getMethod("boot", int.class)
                        .invoke(null, ShadowClassLoader.loadedClassCount());
            }

            int loaded = ShadowClassLoader.loadedClassCount();
            int jars = ShadowClassLoader.jarCount();
            System.out.println("[c-imagine] bootstrap init: jar=" + jarPath
                    + " entries=" + jarEntries
                    + " mods=" + modsDir
                    + " kernel=" + kernel
                    + " shadowJars=" + jars);
            return status("ok", null, jarEntries, loaded, layerResult, started);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            Throwable c = t.getCause();
            if (c != null) {
                msg += " <- " + c.getClass().getSimpleName()
                        + (c.getMessage() != null ? ": " + c.getMessage() : "");
            }
            return status("error", msg, jarEntries, ShadowClassLoader.loadedClassCount(),
                    null, started);
        }
    }

    private static String status(String state, String error, int jarEntries,
                                 int shadowClasses, String layer, long startedNanos) {
        long ms = (System.nanoTime() - startedNanos) / 1_000_000L;
        return "{\"state\":\"" + state + "\""
                + (error != null ? ",\"error\":\"" + JsonKit.escape(error) + "\"" : "")
                + ",\"jarEntries\":" + jarEntries
                + ",\"shadowClasses\":" + shadowClasses
                + (layer != null ? ",\"layer\":" + layer : "")
                + ",\"tookMs\":" + ms
                + "}";
    }
}
