package dev.cim.bootstrap;

import java.nio.file.Path;

/**
 * Local (no-CRUSSTY) harness that reproduces the native loader thread's
 * exact sequence against a built {@code libimagine.so}: arm the relocation
 * natives, run {@link Bootstrap#init}, then {@link Bootstrap#loadMods} with
 * a real manifest built by the Rust scanner.
 *
 * <p>This is the fast inner loop for M2 development: no Paper, no CRUSSTY —
 * just the embedded 1.21.1 layer and the mod pipeline. CI integration runs
 * the full CRUSSTY boot; this harness must behave identically.
 *
 * <p>Usage:
 * <pre>
 *   java -cp cim-bootstrap.jar dev.cim.bootstrap.LocalHarness \
 *        &lt;libimagine.so&gt; &lt;layerDir&gt; &lt;modsDir&gt;
 * </pre>
 */
public final class LocalHarness {

    private LocalHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: LocalHarness <libimagine.so> <layerDir> <modsDir>");
            System.exit(2);
        }
        String lib = args[0];
        String layerDir = args[1];
        String modsDir = args[2];

        // Locate this harness's own jar (the bootstrap jar the native module
        // would otherwise deploy).
        String jarPath = Path.of(LocalHarness.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();

        NativeReloc.setup(lib);
        System.out.println("[c-imagine][local] init:");
        // The manifest is produced by the Rust scanner (cim-manifest, same
        // code path as the module); it must reach init for the pre-boot
        // module-layer definition.
        String manifest = args.length >= 4 ? java.nio.file.Files.readString(Path.of(args[3])) : null;
        String init = Bootstrap.init("{\"jar\":\"" + json(jarPath)
                + "\",\"mods\":\"" + json(modsDir)
                + "\",\"kernel\":\"\",\"layerDir\":\"" + json(layerDir)
                + "\",\"moduleLib\":\"" + json(lib) + "\"}", manifest);
        System.out.println("[c-imagine][local] init -> " + init);
        if (!init.contains("\"layer\":{\"state\":\"ok\"}") && init.contains("\"layer\"")) {
            System.out.println("[c-imagine][local] layer boot did not report ok");
        }

        System.out.println("[c-imagine][local] loadMods:");
        String mods = Bootstrap.loadMods(manifest);
        System.out.println("[c-imagine][local] loadMods -> " + mods);

        if (args.length >= 5 && args[4].contains("probe-log4j")) {
            ClassLoader sl = ShadowClassLoader.get();
            System.out.println("[c-imagine][probe] jarCount=" + ShadowClassLoader.jarCount()
                    + " loaded=" + ShadowClassLoader.loadedClassCount());
            try {
                String datPath = "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat";
                System.out.println("[c-imagine][probe] dat(new path) URLs: "
                        + java.util.Collections.list(sl.getResources(datPath)));
                System.out.println("[c-imagine][probe] dat(old path) URLs: "
                        + java.util.Collections.list(sl.getResources("META-INF/Log4j2Plugins.dat")));
            } catch (Throwable t) {
                System.out.println("[c-imagine][probe] dat lookup failed: " + t);
            }
            try {
                System.out.println("[c-imagine][probe] LoggerFactory.class: "
                        + sl.getResource("org/slf4j/LoggerFactory.class"));
            } catch (Throwable t) {
                System.out.println("[c-imagine][probe] slf4j lookup failed: " + t);
            }
            try {
                System.out.println("[c-imagine][probe] props: " + sl.getResource("log4j2.component.properties"));
                var in = sl.getResourceAsStream("log4j2.component.properties");
                if (in != null) {
                    System.out.println("[c-imagine][probe] props size: " + in.readAllBytes().length);
                }
            } catch (Throwable t) {
                System.out.println("[c-imagine][probe] props lookup failed: " + t);
            }
            try {
                Class<?> slf = Class.forName("org.slf4j.LoggerFactory", true, sl);
                Object logger = slf.getMethod("getLogger", String.class).invoke(null, "cim.probe");
                logger.getClass().getMethod("info", String.class)
                        .invoke(logger, "shadow slf4j->log4j probe line");
            } catch (Throwable t) {
                System.out.println("[c-imagine][probe] logger probe failed: " + t);
            }
        }
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
