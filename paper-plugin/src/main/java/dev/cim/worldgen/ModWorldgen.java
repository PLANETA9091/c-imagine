package dev.cim.worldgen;

import dev.cim.f2v.F2vPack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.Plugin;

/**
 * The layer's mod world generation on Paper (survival slice 1): every new overworld chunk gets the
 * placed features the mods' biome modifiers add (Create's zinc and striated ores), decorated by the
 * real feature code in the layer ({@code dev.cim.live.Worldgen}) and written back here.
 *
 * <p>A {@link BlockPopulator} per overworld, added at world init. For each chunk it snapshots the
 * chunk's blocks and biomes through the {@link LimitedRegion} (the populated chunk only), asks the
 * layer, and writes the answer: vanilla states as they are, a mod block as its carrier (the note-block
 * or mushroom slot its ledger row names, see {@link F2vPack#carrierState}). A mod block on the display
 * family needs an entity, which a populator cannot spawn, so it is counted and left out.
 *
 * <p>Thread safety: the populator runs on Paper's chunk generation thread. Its only shared state is
 * the boot plan and the layer entry (both written once, read through volatile fields) and atomic
 * counters; the layer serializes the decorations among themselves, and never takes the main thread's
 * lock. The {@link LimitedRegion} is used only inside its own {@code populate} call, as the API requires.
 */
public final class ModWorldgen {

    static final String LOG_PREFIX = "[c-imagine] ";
    static final String KEY = "cim.worldgen";
    /** {@code -Dcim.worldgen.debug=true}: one line per chunk and the running average. Off, nothing per chunk. */
    static final boolean DEBUG = Boolean.getBoolean("cim.worldgen.debug");
    private static final long WAIT_MS = 600_000L;
    private static final long POLL_MS = 2_000L;

    /** What the layer's listing said at boot. */
    public record Plan(int features, int modifiers, int skipped, String reasons) {
    }

    private static volatile Plan plan;
    private static volatile Function<String, String> layer;
    /** The ledger rows by modded id (display rows too: they are counted, not placed). */
    private static volatile Map<String, F2vPack.VisualBlock> rows = Map.of();
    private static volatile Logger log = Logger.getLogger("c-imagine");

    /** Materials by ordinal, once: the chunk encoder indexes them without allocating per chunk. */
    private static final Material[] MATERIALS = Material.values();
    /** A layer error is logged once, not once per chunk. */
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean();

    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();
    private static final AtomicLong SETS = new AtomicLong();
    private static final AtomicLong NOT_READY = new AtomicLong();
    private static final AtomicLong NO_CARRIER = new AtomicLong();
    private static final AtomicLong DISPLAY = new AtomicLong();
    private static final AtomicLong BAD_STATE = new AtomicLong();
    private static final AtomicLong OUTSIDE = new AtomicLong();
    private static final AtomicLong CLIPPED = new AtomicLong();
    private static final AtomicLong BLOCK_ENTITIES = new AtomicLong();
    private static final Map<String, AtomicLong> FAILED = new ConcurrentHashMap<>();

    private ModWorldgen() {
    }

    /**
     * Starts slice 1: attaches the populator to the overworld(s) now loaded and to those that init later,
     * and waits in the background for the layer's listing. Plain Paper (no layer) only attaches; nothing
     * is logged for the listing.
     */
    public static void start(Plugin plugin, List<F2vPack.VisualBlock> blocks) {
        log = plugin.getLogger();
        Map<String, F2vPack.VisualBlock> byId = new HashMap<>();
        for (F2vPack.VisualBlock row : blocks) {
            byId.put(row.moddedBlockId(), row);
        }
        rows = Map.copyOf(byId);
        for (World world : Bukkit.getWorlds()) {
            attach(world);
        }
        plugin.getServer().getPluginManager().registerEvents(new Attach(), plugin);
        Thread thread = new Thread(ModWorldgen::fetchPlan, "cim-worldgen");
        thread.setDaemon(true);
        thread.start();
    }

    /** Waits (off the main thread) until the layer's listing is in; false when it never came. */
    public static boolean awaitReady(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (plan == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return plan != null;
    }

    /** The overworld gets the populator; nether and end wait for their own height and biome slice. */
    static void attach(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        try {
            for (BlockPopulator existing : world.getPopulators()) {
                if (existing instanceof ModPopulator) {
                    return;
                }
            }
            world.getPopulators().add(new ModPopulator());
        } catch (RuntimeException e) {
            // Never fatal: the plugin enables without mod world generation (the warning says so).
            log.warning(LOG_PREFIX + "worldgen: could not attach to " + world.getName() + ": " + e);
        }
    }

    private static void fetchPlan() {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Function<String, String> fn = entry();
            if (fn != null) {
                layer = fn;
                String said = fn.apply("list");
                if (!said.startsWith("wait")) {
                    answer(said);
                    return;
                }
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (layer != null) {
            log.warning(LOG_PREFIX + "worldgen: the layer had no listing after " + WAIT_MS / 1000 + "s");
        }
    }

    private static void answer(String said) {
        if (said.startsWith("error")) {
            log.warning(LOG_PREFIX + "worldgen: the layer answered " + said.split("\n")[0]);
            return;
        }
        String[] f = said.trim().split("\t", 5);
        if (f.length < 5 || !f[0].equals("ready")) {
            log.warning(LOG_PREFIX + "worldgen: unreadable listing: " + said.trim());
            return;
        }
        Plan built = new Plan(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4]);
        plan = built;
        log.info(LOG_PREFIX + "worldgen: " + built.features() + " mod feature(s) from " + built.modifiers()
                + " biome modifier(s) active (skipped " + built.skipped() + ": " + built.reasons() + ")");
        long early = NOT_READY.get();
        if (early > 0) {
            log.info(LOG_PREFIX + "worldgen: " + early + " chunk(s) generated before the layer answered (no mod features there)");
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<String, String> entry() {
        Object f = System.getProperties().get(KEY);
        return f instanceof Function<?, ?> fn ? (Function<String, String>) fn : null;
    }

    // ---- the populator ------------------------------------------------------------------------------

    /** Attaches the populator to worlds initialised after the plugin enabled. */
    private static final class Attach implements Listener {
        @EventHandler
        public void onWorldInit(WorldInitEvent event) {
            attach(event.getWorld());
        }
    }

    static final class ModPopulator extends BlockPopulator {

        @Override
        public void populate(WorldInfo info, Random random, int chunkX, int chunkZ, LimitedRegion region) {
            Function<String, String> fn = layer;
            if (plan == null || fn == null) {
                NOT_READY.incrementAndGet();
                return;
            }
            long t0 = System.nanoTime();
            String answer = fn.apply(encode(info, chunkX, chunkZ, region));
            int sets = apply(region, chunkX, chunkZ, answer);
            long dt = System.nanoTime() - t0;
            long chunks = CHUNKS.incrementAndGet();
            NANOS.addAndGet(dt);
            SETS.addAndGet(sets);
            if (DEBUG) {
                log.info(LOG_PREFIX + "worldgen: chunk " + chunkX + "," + chunkZ + ": " + sets + " block(s), "
                        + dt / 1000 + " us (average " + NANOS.get() / chunks / 1000 + " us over " + chunks + ")");
            }
        }
    }

    /**
     * The request for one chunk: the block palette and the columns as runs of palette indices (bottom up),
     * the biome palette and the 4x4 biome columns over the quart rows. Runs are the only per-block output.
     */
    static String encode(WorldInfo info, int chunkX, int chunkZ, LimitedRegion region) {
        int minY = info.getMinHeight();
        int maxY = info.getMaxHeight();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] palette = new int[MATERIALS.length];
        Arrays.fill(palette, -1);
        List<String> ids = new ArrayList<>();
        StringBuilder columns = new StringBuilder(16 * 16 * 16);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                StringBuilder runs = new StringBuilder();
                int runIndex = -1;
                int runCount = 0;
                for (int y = minY; y < maxY; y++) {
                    Material m = region.getType(baseX + lx, y, baseZ + lz);
                    int index = palette[m.ordinal()];
                    if (index < 0) {
                        index = ids.size();
                        ids.add(m.getKey().toString());
                        palette[m.ordinal()] = index;
                    }
                    if (index == runIndex) {
                        runCount++;
                    } else {
                        if (runCount > 0) {
                            appendRun(runs, runIndex, runCount);
                        }
                        runIndex = index;
                        runCount = 1;
                    }
                }
                appendRun(runs, runIndex, runCount);
                columns.append("col\t").append(lx).append('\t').append(lz).append('\t').append(runs).append('\n');
            }
        }

        int quartMin = minY >> 2;
        int quartMax = (maxY - 1) >> 2;
        Map<Biome, Integer> biomePalette = new IdentityHashMap<>();
        List<String> biomeIds = new ArrayList<>();
        StringBuilder biomes = new StringBuilder(16 * 16 * 4);
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                StringBuilder runs = new StringBuilder();
                int runIndex = -1;
                int runCount = 0;
                for (int q = quartMin; q <= quartMax; q++) {
                    Biome biome = region.getBiome(baseX + qx * 4, Math.max(minY, q * 4), baseZ + qz * 4);
                    Integer index = biomePalette.get(biome);
                    if (index == null) {
                        index = biomeIds.size();
                        biomeIds.add(biome.getKey().toString());
                        biomePalette.put(biome, index);
                    }
                    if (index == runIndex) {
                        runCount++;
                    } else {
                        if (runCount > 0) {
                            appendRun(runs, runIndex, runCount);
                        }
                        runIndex = index;
                        runCount = 1;
                    }
                }
                appendRun(runs, runIndex, runCount);
                biomes.append("bio\t").append(qx).append('\t').append(qz).append('\t').append(runs).append('\n');
            }
        }

        StringBuilder out = new StringBuilder(columns.length() + biomes.length() + 512);
        out.append("decorate\t").append(chunkX).append('\t').append(chunkZ).append('\t').append(info.getSeed())
                .append('\t').append(minY).append('\t').append(maxY).append('\n');
        for (int i = 0; i < ids.size(); i++) {
            out.append("mat\t").append(i).append('\t').append(ids.get(i)).append('\n');
        }
        for (int i = 0; i < biomeIds.size(); i++) {
            out.append("bpal\t").append(i).append('\t').append(biomeIds.get(i)).append('\n');
        }
        return out.append(columns).append(biomes).toString();
    }

    private static void appendRun(StringBuilder runs, int index, int count) {
        if (count == 0) {
            return;
        }
        if (runs.length() > 0) {
            runs.append(',');
        }
        runs.append(index).append(':').append(count);
    }

    /**
     * Writes the layer's answer into the populated chunk. A block that cannot be written (no carrier, the
     * display family, an unreadable state, outside the region) is counted and left out.
     */
    static int apply(LimitedRegion region, int chunkX, int chunkZ, String answer) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        Map<String, BlockData> cache = new HashMap<>();
        int sets = 0;
        for (String line : answer.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split("\t", 5);
            switch (f[0]) {
                case "set" -> {
                    BlockData data = data(f[4], cache);
                    if (data == null) {
                        continue;
                    }
                    int x = baseX + Integer.parseInt(f[1]);
                    int y = Integer.parseInt(f[2]);
                    int z = baseZ + Integer.parseInt(f[3]);
                    if (!region.isInRegion(x, y, z)) {
                        OUTSIDE.incrementAndGet();
                        continue;
                    }
                    region.setBlockData(x, y, z, data);
                    sets++;
                }
                case "fail" -> FAILED.computeIfAbsent(f[1], k -> new AtomicLong()).addAndGet(Long.parseLong(f[2]));
                case "stat" -> {
                    CLIPPED.addAndGet(Long.parseLong(f[2]));
                    BLOCK_ENTITIES.addAndGet(Long.parseLong(f[3]));
                }
                case "error" -> {
                    if (ERROR_LOGGED.compareAndSet(false, true)) {
                        log.warning(LOG_PREFIX + "worldgen: chunk " + chunkX + "," + chunkZ + ": " + line
                                + " (later errors are not logged)");
                    }
                }
                default -> {
                }
            }
        }
        return sets;
    }

    /** The block data a layer state is written as: vanilla as it is, a mod block as its carrier. */
    private static BlockData data(String state, Map<String, BlockData> cache) {
        BlockData cached = cache.get(state);
        if (cached != null) {
            return cached;
        }
        String id = state.indexOf('[') < 0 ? state : state.substring(0, state.indexOf('['));
        String text;
        if (id.startsWith("minecraft:")) {
            text = state;
        } else {
            F2vPack.VisualBlock row = rows.get(id);
            if (row == null) {
                NO_CARRIER.incrementAndGet();
                return null;
            }
            if (row.isDisplay()) {
                DISPLAY.incrementAndGet();
                return null;
            }
            text = F2vPack.carrierState(row);
        }
        try {
            BlockData data = Bukkit.createBlockData(text);
            cache.put(state, data);
            return data;
        } catch (IllegalArgumentException e) {
            BAD_STATE.incrementAndGet();
            return null;
        }
    }

    /** The counters since boot, for the harness and the debug log. */
    public static String stats() {
        long chunks = CHUNKS.get();
        return "chunks=" + chunks + " sets=" + SETS.get() + " average_us=" + (chunks == 0 ? 0 : NANOS.get() / chunks / 1000)
                + " not_ready=" + NOT_READY.get() + " no_carrier=" + NO_CARRIER.get() + " display=" + DISPLAY.get()
                + " bad_state=" + BAD_STATE.get() + " outside=" + OUTSIDE.get() + " clipped=" + CLIPPED.get()
                + " block_entities=" + BLOCK_ENTITIES.get() + " failed=" + FAILED.keySet();
    }

    /**
     * The harness probe (survival.yml): loads a square of chunks, generated now if they are new, and counts
     * the carriers of {@code carrierId} below {@code yCut}. Runs on the main thread (the chunks load there).
     */
    public static List<String> probe(World world, String carrierId, int chunkX, int chunkZ, int size, int yCut) {
        Plan p = plan;
        if (p == null) {
            return List.of("probe: no worldgen plan (the layer did not answer)");
        }
        F2vPack.VisualBlock row = rows.get(carrierId);
        if (row == null || row.isDisplay()) {
            return List.of("probe: no note carrier for " + carrierId);
        }
        BlockData want = Bukkit.createBlockData(F2vPack.carrierState(row));
        long found = 0;
        int loaded = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                world.getChunkAt(chunkX + dx, chunkZ + dz);
                loaded++;
                int bx = (chunkX + dx) << 4;
                int bz = (chunkZ + dz) << 4;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = world.getMinHeight(); y < yCut; y++) {
                            if (world.getType(bx + lx, y, bz + lz) == Material.NOTE_BLOCK
                                    && world.getBlockData(bx + lx, y, bz + lz).equals(want)) {
                                found++;
                            }
                        }
                    }
                }
            }
        }
        return List.of("probe: " + carrierId + " carriers below y=" + yCut + " in " + loaded + " chunk(s): " + found,
                "probe: " + stats());
    }
}
