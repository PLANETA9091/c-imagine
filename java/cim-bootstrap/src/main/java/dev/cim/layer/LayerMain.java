package dev.cim.layer;

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

/**
 * M1 layer-boot harness, loaded <b>by the ShadowClassLoader</b> so it lives
 * in the shadow space and can reach the relocated 1.21.1 classes.
 *
 * Boot sequence (M1 acceptance, {@code docs/ROADMAP.md}):
 * <ol>
 *   <li>relocated {@code SharedConstants.tryDetectVersion()} — version
 *       metadata for the relocated stack;</li>
 *   <li>relocated {@code Bootstrap.bootStrap()} — the vanilla registry
 *       bootstrap the real server runs before starting;</li>
 *   <li>count the registries in the relocated
 *       {@code BuiltInRegistries.REGISTRY} root.</li>
 * </ol>
 *
 * All access is reflective with the <b>relocated</b> binary names
 * ({@code cim.shadow.mc1211.*}) because the relocated classes exist only at
 * layer runtime; compiling against them is impossible (and unnecessary).
 * Marker line printed on success (CI greps it):
 *
 * <pre>[c-imagine] layer boot ok (vanilla registries: N)</pre>
 */
public final class LayerMain {

    private LayerMain() {
    }

    /** Called reflectively by {@code Bootstrap.init} through the shadow loader.
     *
     * @param classesAtBoot number of shadow classes defined so far (counted
     *                      by the bootstrap-side loader identity; the shadow
     *                      copy of the loader class has its own statics). */
    public static String boot(int classesAtBoot) {
        long started = System.nanoTime();
        String state = "ok";
        String error = null;
        int registries = -1;
        int shadowClasses = classesAtBoot;

        // 1) version metadata (optional: some jar layouts carry version.json)
        try {
            Class<?> shared = Class.forName("cim.shadow.mc1211.net.minecraft.SharedConstants");
            shared.getMethod("tryDetectVersion").invoke(null);
        } catch (Throwable t) {
            System.out.println("[c-imagine] layer: tryDetectVersion failed (non-fatal):");
            t.printStackTrace();
        }

        // 2) vanilla registry bootstrap — the load-bearing call
        try {
            System.out.println("[c-imagine] layer: fml state shim: " + FmlStateShim.apply());
            Class<?> bootstrap = Class.forName("cim.shadow.mc1211.net.minecraft.server.Bootstrap");
            bootstrap.getMethod("bootStrap").invoke(null);
        } catch (Throwable t) {
            state = "error";
            error = causeChain(t);
            t.printStackTrace();
        }

        // 3) registry census
        if ("ok".equals(state)) {
            try {
                Class<?> registriesCls = Class.forName(
                        "cim.shadow.mc1211.net.minecraft.core.registries.BuiltInRegistries");
                Object root = registriesCls.getField("REGISTRY").get(null);
                @SuppressWarnings("unchecked")
                Set<Object> keys = (Set<Object>) root.getClass().getMethod("keySet").invoke(root);
                registries = keys.size();
            } catch (Throwable t) {
                state = "error";
                error = causeChain(t);
            }
        }

        long ms = (System.nanoTime() - started) / 1_000_000L;
        if ("ok".equals(state)) {
            System.out.println("[c-imagine] layer boot ok (vanilla registries: " + registries
                    + ", shadow classes: " + shadowClasses + ", " + ms + " ms)");
        } else {
            System.out.println("[c-imagine] layer boot FAILED: " + error);
        }
        return "{\"state\":\"" + state + "\""
                + (error != null ? ",\"error\":\"" + jsonEscape(error) + "\"" : "")
                + ",\"registries\":" + registries
                + ",\"shadowClasses\":" + shadowClasses
                + ",\"tookMs\":" + ms
                + "}";
    }

    private static String causeChain(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        if (t.getMessage() != null) {
            sb.append(": ").append(t.getMessage());
        }
        if (t.getCause() != null) {
            sb.append(" <- ").append(t.getCause().getClass().getName());
            if (t.getCause().getMessage() != null) {
                sb.append(": ").append(t.getCause().getMessage());
            }
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
