package dev.cim.bridge;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3.1 kernel state bridge (facade side, loaded on the bootstrap loader).
 *
 * <p>The layer NEVER stores authoritative world state (ARCHITECTURE §4.4):
 * every block read/write is translated and executed against the Paper kernel.
 * This class is the synchronous v1 of that bridge:
 *
 * <pre>
 *   module thread ─ runSmoke(pluginRef) ─ this class
 *        │ reflection by NAME (String/primitive values only — the two worlds
 *        │ never share a classloader, so the bridge speaks values, not types)
 *        ▼
 *   CimPlugin (Paper space) ─ Bukkit API (main-thread hop) ─ Paper chunks
 * </pre>
 *
 * <p>Translation rules v1:
 * <ul>
 *   <li>vanilla 1.21.1 blockstate strings pass through (same-name blocks;
 *       property drift is the M3.2 cim-registry table's job);</li>
 *   <li>modded ids ({@code cimtest:*}, anything outside {@code minecraft:})
 *       have no 26.3 registry entry — writes record the modded state in a
 *       sidecar and place a stand-in block in the Paper world; reads of
 *       sidecar positions report the modded state (the Paper world keeps the
 *       stand-in until M6 pack synthesis gives it a real texture);</li>
 *   <li>{@code minecraft:air} writes clear the sidecar (break).</li>
 * </ul>
 */
public final class KernelBridge {

    private static final String LOG = "[c-imagine]";
    /** Paper-side stand-in for modded blocks (26.3 has no modded registry). */
    private static final String STAND_IN = "minecraft:structure_void";
    /** Sidecar: modded layer states keyed by (world,x,y,z). */
    private static final Map<String, String> SIDECAR = new ConcurrentHashMap<>();

    private KernelBridge() {
    }

    /** Serialize a bridge position key (world,x,y,z). */
    private static String key(String world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }

    /**
     * M3.1 smoke, called by the native module after the Paper world is ready.
     * Exercises every bridge direction and logs the markers the CI job
     * asserts:
     *
     * <ol>
     *   <li>vanilla set through the layer → Paper sees the same state;</li>
     *   <li>modded set through the layer → Paper sees the stand-in, the layer
     *       reports the modded state (sidecar);</li>
     *   <li>break performed on the Paper side → the layer observes air.</li>
     * </ol>
     *
     * @param plugin the {@code dev.cim.paper.CimPlugin} instance (passed as
     *               Object so the two class spaces never share a type)
     */
    public static void runSmoke(Object plugin) {
        try {
            Class<?> cls = plugin.getClass();
            Method worldName = cls.getMethod("worldName");
            Method getBlock = cls.getMethod("layerGetBlock",
                    String.class, int.class, int.class, int.class);
            Method setBlock = cls.getMethod("layerSetBlock",
                    String.class, int.class, int.class, int.class, String.class);
            Method paperSetAir = cls.getMethod("paperSetAir",
                    String.class, int.class, int.class, int.class);

            String world = (String) worldName.invoke(plugin);
            int x = 0;
            int y = 100;
            int z = 0;

            // 1) vanilla block: layer write → kernel storage → kernel read
            String vanilla = "minecraft:yellow_terracotta";
            boolean setOk = kernelSet(setBlock, plugin, world, x, y, z, vanilla);
            String paperSees = (String) getBlock.invoke(plugin, world, x, y, z);
            System.out.println(LOG + " bridge smoke: vanilla set ok=" + setOk
                    + " paper=" + paperSees
                    + (vanilla.equals(paperSees) ? " (roundtrip ok)" : " (MISMATCH)"));

            // 2) modded block: sidecar + stand-in. Paper-side view is the
            //    stand-in; the layer view (kernelGet) resolves the sidecar.
            String modded = "cimtest:test_block";
            boolean moddedSet = kernelSet(setBlock, plugin, world, x, y, z + 1, modded);
            String paperModded = (String) getBlock.invoke(plugin, world, x, y, z + 1);
            String layerView = kernelGet(getBlock, plugin, world, x, y, z + 1);
            System.out.println(LOG + " bridge smoke: modded set ok=" + moddedSet
                    + " paper=" + paperModded + " (stand-in expected)"
                    + " layer=" + layerView
                    + " sidecar=" + SIDECAR.get(key(world, x, y, z + 1)));

            // 3) break on the Paper side → the layer facade observes air
            boolean broke = (Boolean) paperSetAir.invoke(plugin, world, x, y, z);
            String afterBreak = (String) getBlock.invoke(plugin, world, x, y, z);
            System.out.println(LOG + " bridge smoke: paper-side break ok=" + broke
                    + " layer observes=" + afterBreak
                    + ("minecraft:air".equals(afterBreak) ? " (observed)" : " (MISMATCH)"));

            boolean allOk = setOk && vanilla.equals(paperSees) && moddedSet
                    && modded.equals(layerView)
                    && broke && "minecraft:air".equals(afterBreak);
            System.out.println(LOG + " bridge smoke: " + (allOk ? "ALL OK" : "FAILED"));
        } catch (Throwable t) {
            StringBuilder chain = new StringBuilder(String.valueOf(t));
            for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
                chain.append(" <- ").append(c);
            }
            System.out.println(LOG + " bridge smoke FAILED: " + chain);
        }
    }

    /**
     * Layer-side read facade: resolves the modded sidecar first, otherwise
     * the kernel string passes through (v1 identity translation).
     */
    public static String layerRead(String world, int x, int y, int z, String kernelState) {
        String modded = SIDECAR.get(key(world, x, y, z));
        return modded != null ? modded : kernelState;
    }

    /** Facade write through the plugin surface: translate, then set in Paper. */
    private static boolean kernelSet(Method setBlock, Object plugin, String world,
                                     int x, int y, int z, String layerState) throws Exception {
        String kernelState = layerWrite(world, x, y, z, layerState);
        return (Boolean) setBlock.invoke(plugin, world, x, y, z, kernelState);
    }

    /** Facade read through the plugin surface: read Paper, then translate. */
    private static String kernelGet(Method getBlock, Object plugin, String world,
                                    int x, int y, int z) throws Exception {
        String kernelState = (String) getBlock.invoke(plugin, world, x, y, z);
        return layerRead(world, x, y, z, kernelState);
    }

    /**
     * Layer-side write facade: route modded states into the sidecar and
     * return the kernel-facing state; vanilla states pass through.
     */
    public static String layerWrite(String world, int x, int y, int z, String layerState) {
        String s = layerState.toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:") || s.startsWith("air")) {
            SIDECAR.remove(key(world, x, y, z));
            return layerState;
        }
        // modded namespace: record and stand in
        SIDECAR.put(key(world, x, y, z), layerState);
        return STAND_IN;
    }

    /** Sidecar census (diagnostics). */
    public static java.util.List<Map.Entry<String, String>> sidecarEntries() {
        return List.copyOf(SIDECAR.entrySet());
    }
}
