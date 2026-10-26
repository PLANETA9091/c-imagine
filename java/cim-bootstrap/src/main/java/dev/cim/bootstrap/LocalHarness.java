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
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
