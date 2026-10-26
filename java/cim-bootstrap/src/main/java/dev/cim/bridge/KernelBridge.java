package dev.cim.bridge;

import java.lang.reflect.Method;
import java.util.Locale;

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
 * <p>Translation rules (M3.1 facade + M3.2 tables):
 * <ul>
 *   <li>vanilla 1.21.1 blockstate strings pass through {@link StateMapper}
 *       (measured drift: chain->iron_chain rename, note_block instrument
 *       value-set growth; everything else is identical at 26.3 release);</li>
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
    /**
     * Sidecar: modded layer states keyed by (world,x,y,z). M3.3: write-through
     * to {@code <cwd>/c-imagine/sidecar.tsv} so modded state survives restart
     * (zero orphan state); entries whose Paper stand-in disappeared are culled
     * on read (self-healing, see {@link #layerRead}).
     */
    private static final SidecarStore SIDECAR = SidecarStore.shared();

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

            // 0) M3.3 restore: verify every persisted sidecar entry against
            //    the Paper world. Stand-in intact -> the modded state is
            //    live again (zero orphan state); stand-in gone -> cull.
            int restored = 0;
            int culled = 0;
            String restoreDetail = "";
            for (String[] e : SIDECAR.snapshot()) {
                String ew = e[0];
                int ex = Integer.parseInt(e[1]);
                int ey = Integer.parseInt(e[2]);
                int ez = Integer.parseInt(e[3]);
                String state = e[4];
                String paperNow = (String) getBlock.invoke(plugin, ew, ex, ey, ez);
                if (paperNow.startsWith(STAND_IN)) {
                    restored++;
                    restoreDetail = state + " @ " + ew + "," + ex + "," + ey + "," + ez;
                    System.out.println(LOG + " sidecar restore: " + state
                            + " @ " + ew + "," + ex + "," + ey + "," + ez + " (stand-in intact)");
                } else {
                    culled++;
                    SIDECAR.erase(ew, ex, ey, ez);
                    System.out.println(LOG + " sidecar cull: " + state
                            + " @ " + ew + "," + ex + "," + ey + "," + ez
                            + " (Paper now " + paperNow + ")");
                }
            }
            System.out.println(LOG + " sidecar restore: " + restored + " live, "
                    + culled + " culled"
                    + (restored > 0 ? " (last: " + restoreDetail + ")" : ""));

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
                    + " sidecar=" + SIDECAR.get(world, x, y, z + 1));

            // 3) break on the Paper side → the layer facade observes air
            boolean broke = (Boolean) paperSetAir.invoke(plugin, world, x, y, z);
            String afterBreak = (String) getBlock.invoke(plugin, world, x, y, z);
            System.out.println(LOG + " bridge smoke: paper-side break ok=" + broke
                    + " layer observes=" + afterBreak
                    + ("minecraft:air".equals(afterBreak) ? " (observed)" : " (MISMATCH)"));

            // 4) M3.2 drift leg: a 1.21.1 state with a RENAMED block. The
            //    Paper world must end up holding iron_chain (26.3 name) while
            //    the layer view still reports the 1.21.1 name. Bukkit stores
            //    every property on read (waterlogged default), so prefix match.
            String chain1211 = "minecraft:chain[axis=y]";
            boolean chainSet = kernelSet(setBlock, plugin, world, x, y, z + 2, chain1211);
            String paperChain = (String) getBlock.invoke(plugin, world, x, y, z + 2);
            String layerChain = kernelGet(getBlock, plugin, world, x, y, z + 2);
            boolean driftOk = chainSet && paperChain.startsWith("minecraft:iron_chain[axis=y")
                    && layerChain.startsWith("minecraft:chain[axis=y");
            System.out.println(LOG + " bridge smoke: drift leg set=" + chainSet
                    + " paper=" + paperChain + " layer=" + layerChain
                    + (driftOk ? " (chain->iron_chain ok)" : " (MISMATCH)"));

            // 5) M3.2 identity leg: drifted VALUE-SET block, unchanged value —
            //    note_block instrument=harp exists on both sides.
            String note = "minecraft:note_block[instrument=harp]";
            boolean noteSet = kernelSet(setBlock, plugin, world, x, y, z + 3, note);
            String paperNote = (String) getBlock.invoke(plugin, world, x, y, z + 3);
            String layerNote = kernelGet(getBlock, plugin, world, x, y, z + 3);
            boolean noteOk = noteSet && paperNote.startsWith("minecraft:note_block[instrument=harp")
                    && layerNote.startsWith("minecraft:note_block[instrument=harp");
            System.out.println(LOG + " bridge smoke: note leg set=" + noteSet
                    + " paper=" + paperNote + " layer=" + layerNote
                    + (noteOk ? " (value-set ok)" : " (MISMATCH)"));

            // 6) M3.4 entity projection legs: spawn through the LAYER name,
            //    verify the kernel world holds the remapped type, verify the
            //    layer view translates back, then despawn through the facade.
            Class<?> pluginClass = plugin.getClass();
            Method spawnEntity = pluginClass.getMethod("paperSpawnEntity",
                    String.class, double.class, double.class, double.class, String.class);
            Method listEntities = pluginClass.getMethod("paperListEntities",
                    String.class, double.class, double.class, double.class, double.class);
            Method removeEntity = pluginClass.getMethod("paperRemoveEntity",
                    String.class, String.class);

            // 6a) remapped type: 1.21.1 boat -> 26.3 oak_boat
            String boatLayer = "minecraft:boat";
            String boatKernel = StateMapper.get().layerToKernelKey("entity_type", boatLayer);
            String boatUuid = (String) spawnEntity.invoke(plugin, world,
                    (double) x + 3, (double) y, (double) z, boatKernel);
            boolean boatSeen = false;
            boolean boatBack = false;
            boolean boatGone = false;
            if (boatUuid != null) {
                String[] rows = (String[]) listEntities.invoke(plugin, world,
                        (double) x + 3, (double) y, (double) z, 8.0);
                for (String row : rows) {
                    String[] f = row.split("\\|", -1);
                    if (f.length < 2) {
                        continue;
                    }
                    if (f[1].equals(boatKernel) && f[0].equals(boatUuid)) {
                        boatSeen = true;
                    }
                    // the layer view translates 26.3 keys back to 1.21.1 names
                    if (StateMapper.get().kernelToLayerKey("entity_type", f[1]).equals(boatLayer)
                            && f[0].equals(boatUuid)) {
                        boatBack = true;
                    }
                }
                removeEntity.invoke(plugin, world, boatUuid);
                String[] after = (String[]) listEntities.invoke(plugin, world,
                        (double) x + 3, (double) y, (double) z, 8.0);
                boatGone = true;
                for (String row : after) {
                    if (row.startsWith(boatUuid)) {
                        boatGone = false;
                    }
                }
            }
            boolean boatOk = boatUuid != null && boatSeen && boatBack && boatGone;
            System.out.println(LOG + " bridge smoke: entity boat leg uuid=" + boatUuid
                    + " kernel=" + boatKernel
                    + " seen=" + boatSeen + " layerView=minecraft:boat (" + boatBack + ")"
                    + " removed&gone=" + boatGone
                    + (boatOk ? " (boat split ok)" : " (MISMATCH)"));

            // 6b) identity type: pig passes the tables untouched
            String pigKernel = StateMapper.get().layerToKernelKey("entity_type", "minecraft:pig");
            String pigUuid = (String) spawnEntity.invoke(plugin, world,
                    (double) x + 5, (double) y, (double) z, pigKernel);
            boolean pigOk = pigUuid != null && "minecraft:pig".equals(pigKernel);
            if (pigUuid != null) {
                removeEntity.invoke(plugin, world, pigUuid);
            }
            System.out.println(LOG + " bridge smoke: entity pig leg uuid=" + pigUuid
                    + " type=" + pigKernel + (pigOk ? " (identity ok)" : " (MISMATCH)"));

            boolean entityOk = boatOk && pigOk;

            boolean allOk = setOk && vanilla.equals(paperSees) && moddedSet
                    && modded.equals(layerView)
                    && broke && "minecraft:air".equals(afterBreak)
                    && driftOk && noteOk
                    && entityOk;
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
     * Layer-side read facade: resolves the modded sidecar first (culling
     * entries whose Paper stand-in no longer exists — zero orphan state),
     * otherwise translates the kernel string back (M3.2 tables).
     */
    public static String layerRead(String world, int x, int y, int z, String kernelState) {
        String modded = SIDECAR.get(world, x, y, z);
        if (modded != null) {
            if (kernelState != null && kernelState.startsWith(STAND_IN)) {
                return modded;
            }
            // the stand-in was replaced/broken underneath us: self-heal
            SIDECAR.erase(world, x, y, z);
            System.out.println(LOG + " sidecar cull: " + modded + " @ "
                    + world + "," + x + "," + y + "," + z + " (Paper now " + kernelState + ")");
        }
        return StateMapper.get().kernelToLayerState(kernelState);
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
     * return the kernel-facing state; vanilla states go through the M3.2
     * drift tables (renames, property-set and value-set drift).
     */
    public static String layerWrite(String world, int x, int y, int z, String layerState) {
        String s = layerState.toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:") || s.startsWith("air")) {
            SIDECAR.erase(world, x, y, z);
            return StateMapper.get().layerToKernelState(layerState);
        }
        // modded namespace: record and stand in
        SIDECAR.put(world, x, y, z, layerState);
        return STAND_IN;
    }

    /** Sidecar census (diagnostics). */
    public static java.util.List<String[]> sidecarEntries() {
        return SIDECAR.snapshot();
    }
}
