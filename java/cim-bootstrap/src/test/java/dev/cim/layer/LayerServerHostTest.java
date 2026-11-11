package dev.cim.layer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure contracts for {@link LayerServerHost}; no shadow classes or server
 * runtime required (the runtime construction is CI-proven — integration
 * asserts the started marker, profiling's tick gate consumes its evidence).
 *
 * <p>What this pins locally:
 * <ol>
 *   <li>the {@code cim.layerServer=false} gate logs its honest marker and
 *       never touches the plugin object;</li>
 *   <li>a failed start (no shadow classes in this bare JVM) is HONEST: the
 *       {@code start FAILED} marker prints, no exception escapes into
 *       Paper's tick loop, and the driver stops looping the failure
 *       (fail-fast, never a silent retry storm);</li>
 *   <li>the second tick after a failed start is a no-op.</li>
 * </ol>
 */
public final class LayerServerHostTest {
    private LayerServerHostTest() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("layer-server-host-test");
        System.setProperty("user.dir", dir.toString());

        // The public bridge must fail before plugin contact without a prepared layer.
        try {
            dev.cim.bootstrap.Bootstrap.installLayerServer(null);
            throw new AssertionError("unprepared layer accepted a server host");
        } catch (IllegalStateException expected) {
            expect(expected.getMessage().equals("ShadowClassLoader not prepared"),
                    "unprepared layer is reported explicitly");
        }

        // 1) the gate: disabled = no plugin contact, marker printed
        System.setProperty("cim.layerServer", "false");
        String out = capture(() -> LayerServerHost.install(null));
        expect(out.contains("layer server: disabled (cim.layerServer=false)"),
                "the disabled gate logs its marker");
        expect(!out.contains("armed"), "the disabled gate never arms");
        System.clearProperty("cim.layerServer");

        // 2) a failed start is honest and fail-fast (no shadow classes here)
        out = capture(() -> {
            Method runTick;
            try {
                runTick = LayerServerHost.class.getDeclaredMethod("runTick");
                runTick.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            try {
                runTick.invoke(null);
            } catch (Exception e) {
                throw new AssertionError("runTick leaked an exception into the tick", e);
            }
            // 3) the second tick after a failed start must be a silent no-op
            try {
                runTick.invoke(null);
            } catch (Exception e) {
                throw new AssertionError("the failed driver did not stop looping", e);
            }
        });
        expect(out.contains("layer server: start FAILED:"),
                "a failed start prints its marker (the gate stays failing)");
        expect(!out.contains("layer server: started"),
                "a failed start never claims to be started");
        expect(count(out, "start FAILED:") == 1,
                "the failure is reported once, not looped every tick");
        expect(!out.contains("layer server: stopped"),
                "a never-started server is never reported stopped");

        System.out.println("LayerServerHost contracts: ALL OK");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    private static String capture(Runnable action) {
        PrintStream old = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true));
        try {
            action.run();
        } finally {
            System.setOut(old);
        }
        return buf.toString();
    }

    private static void expect(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
