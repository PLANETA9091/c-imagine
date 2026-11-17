package dev.cim.worldgen;

import dev.cim.f2v.F2vPack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
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
 * blocks of the layer's window (the y range the runnable features can reach, see {@code Worldgen}) and
 * the chunk's biomes through the {@link LimitedRegion} (the populated chunk only), asks the layer, and
 * writes the answer: vanilla states as they are, a mod block as its carrier (the note-block or mushroom
 * slot its ledger row names, see {@link F2vPack#carrierState}). A mod block on the display family needs
 * an entity, which a populator cannot spawn, so it is counted and left out.
 *
 * <p>A chunk the populator sees before the layer answered is recorded as pending: {@link Backfill}
 * decorates it once the layer is ready, from a snapshot of the loaded chunk, under the same rules.
 *
 * <p>Thread safety: the populator runs on Paper's chunk generation thread. Its shared state is the plan
 * and the layer entry (written once, read through volatile fields), the pending set (concurrent) and
 * atomic counters; the layer serializes the decorations among themselves and never takes the main
 * thread's lock. The {@link LimitedRegion} is used only inside its own {@code populate} call.
 */
public final class ModWorldgen {

    static final String LOG_PREFIX = "[c-imagine] ";
    static final String KEY = "cim.worldgen";
    /** {@code -Dcim.worldgen.debug=true}: one line per chunk and the running average. Off, nothing per chunk. */
    static final boolean DEBUG = Boolean.getBoolean("cim.worldgen.debug");
    private static final long WAIT_MS = 600_000L;
    private static final long POLL_MS = 2_000L;
    /** The pending chunks kept at most: a server whose layer never answers stops recording at this count. */
    static final int PENDING_CAP = 65_536;

    /** What the layer's listing said at boot: the window is the y range [windowMin, windowMax) of the snapshot. */
    public record Plan(int features, int modifiers, int skipped, int windowMin, int windowMax, String reasons) {
    }

    /** A chunk generated before the layer answered, waiting for its backfill. */
    record Pending(String world, int x, int z) {
    }

    private static volatile Plan plan;
    private static volatile Function<String, String> layer;
    /** The ledger rows by modded id (display rows too: they are counted, not placed). */
    private static volatile Map<String, F2vPack.VisualBlock> rows = Map.of();
    private static volatile Logger log = Logger.getLogger("c-imagine");
    /** Set when the layer never answered: nothing more is deferred and the pending set is dropped. */
    private static volatile boolean gaveUp;

    /** Chunks waiting for the layer (populator adds, the backfill removes). */
    static final Set<Pending> PENDING = ConcurrentHashMap.newKeySet();
    /** The pending set changed since the file was written. */
    static final AtomicBoolean PENDING_DIRTY = new AtomicBoolean();

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
    private static final AtomicLong WINDOW_WRITES = new AtomicLong();
    private static final AtomicLong PARTIAL_HEIGHTS = new AtomicLong();
    static final AtomicLong BACKFILLED = new AtomicLong();
    static final AtomicLong BACKFILL_CONFLICTS = new AtomicLong();
    private static final Map<String, AtomicLong> FAILED = new ConcurrentHashMap<>();

    private ModWorldgen() {
    }

    /**
     * Starts slice 1: attaches the populator to the overworld(s) now loaded and to those that init later,
     * loads the pending chunks of earlier boots, and waits in the background for the layer's listing. Plain
     * Paper (no layer) only attaches; nothing is logged for the listing.
     */
    public static void start(Plugin plugin, List<F2vPack.VisualBlock> blocks) {
        log = plugin.getLogger();
        Map<String, F2vPack.VisualBlock> byId = new HashMap<>();
        for (F2vPack.VisualBlock row : blocks) {
            byId.put(row.moddedBlockId(), row);
        }
        rows = Map.copyOf(byId);
        Backfill.start(plugin);
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

    /**
     * Waits (off the main thread) until the pending chunks are all decorated; false when some still wait
     * (an unloaded one keeps its place until its chunk loads) or the layer never answered.
     */
    public static boolean awaitBackfill(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (plan != null && !PENDING.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return plan != null && PENDING.isEmpty();
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

    /** The boot listing, or null before it came. */
    static Plan plan() {
        return plan;
    }

    /** The layer's entry, or null before the layer published it. */
    static Function<String, String> layer() {
        return layer;
    }

    static Logger log() {
        return log;
    }

    static boolean gaveUp() {
        return gaveUp;
    }

    private static void fetchPlan() {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            Function<String, String> fn = entry();
            if (fn != null) {
                layer = fn;
                String said = fn.apply("list");
                if (!said.startsWith("wait")) {
                    if (!answer(said)) {
                        giveUp();
                    }
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
        giveUp();
    }

    /** The layer will not decorate: the pending chunks are dropped, and nothing more is deferred. */
    private static void giveUp() {
        gaveUp = true;
        PENDING.clear();
        PENDING_DIRTY.set(true);
    }

    /** Reads the listing; false when the layer's answer is an error or unreadable. */
    private static boolean answer(String said) {
        if (said.startsWith("error")) {
            log.warning(LOG_PREFIX + "worldgen: the layer answered " + said.split("\n")[0]);
            return false;
        }
        String[] f = said.trim().split("\t", 7);
        if (f.length < 7 || !f[0].equals("ready")) {
            log.warning(LOG_PREFIX + "worldgen: unreadable listing: " + said.trim());
            return false;
        }
        Plan built;
        try {
            built = new Plan(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), f[6]);
        } catch (NumberFormatException e) {
            log.warning(LOG_PREFIX + "worldgen: unreadable listing: " + said.trim());
            return false;
        }
        plan = built;
        log.info(LOG_PREFIX + "worldgen: " + built.features() + " mod feature(s) from " + built.modifiers()
                + " biome modifier(s) active (skipped " + built.skipped() + ": " + built.reasons()
                + "; snapshot y=" + built.windowMin() + ".." + built.windowMax() + ")");
        long early = NOT_READY.get();
        if (early > 0) {
            log.info(LOG_PREFIX + "worldgen: " + early + " chunk(s) generated before the layer answered (pending: "
                    + PENDING.size() + ", decorated once the layer is ready)");
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Function<String, String> entry() {
        Object f = System.getProperties().get(KEY);
        return f instanceof Function<?, ?> fn ? (Function<String, String>) fn : null;
    }

    /** Records a chunk the layer did not decorate, so its backfill can decorate it once the layer is ready. */
    static void defer(String world, int chunkX, int chunkZ) {
        if (gaveUp || PENDING.size() >= PENDING_CAP) {
            return;
        }
        if (PENDING.add(new Pending(world, chunkX, chunkZ))) {
            PENDING_DIRTY.set(true);
        }
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
            Plan p = plan;
            if (p == null || fn == null) {
                NOT_READY.incrementAndGet();
                defer(info.getName(), chunkX, chunkZ);
                return;
            }
            int lo = Math.max(p.windowMin(), info.getMinHeight());
            int hi = Math.min(p.windowMax(), info.getMaxHeight());
            if (lo >= hi) {
                return;
            }
            long t0 = System.nanoTime();
            String answer = fn.apply(encode(Cells.of(region), info.getSeed(), info.getMinHeight(), info.getMaxHeight(),
                    chunkX, chunkZ, lo, hi));
            int sets = apply(answer, chunkX, chunkZ, (x, y, z, data) -> {
                if (!region.isInRegion(x, y, z)) {
                    OUTSIDE.incrementAndGet();
                    return false;
                }
                region.setBlockData(x, y, z, data);
                return true;
            });
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

    /** Where a request's blocks and biomes come from: the populator's region, or a backfill's chunk snapshot. */
    interface Cells {
        Material type(int x, int y, int z);

        Biome biome(int x, int y, int z);

        static Cells of(LimitedRegion region) {
            return new Cells() {
                @Override
                public Material type(int x, int y, int z) {
                    return region.getType(x, y, z);
                }

                @Override
                public Biome biome(int x, int y, int z) {
                    return region.getBiome(x, y, z);
                }
            };
        }

        /** A snapshot is immutable, so the worker thread reads it without the main thread. */
        static Cells of(ChunkSnapshot snapshot, int chunkX, int chunkZ) {
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            return new Cells() {
                @Override
                public Material type(int x, int y, int z) {
                    return snapshot.getBlockType(x - baseX, y, z - baseZ);
                }

                @Override
                public Biome biome(int x, int y, int z) {
                    return snapshot.getBiome(x - baseX, y, z - baseZ);
                }
            };
        }
    }

    /**
     * The request for one chunk: the block palette and the columns as runs of palette indices (bottom up,
     * over the window [snapMin, snapMax)), the biome palette and the 4x4 biome columns over the world's quart
     * rows. Runs are the only per-block output.
     */
    static String encode(Cells cells, long seed, int minY, int maxY, int chunkX, int chunkZ, int snapMin, int snapMax) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] palette = new int[MATERIALS.length];
        Arrays.fill(palette, -1);
        List<String> ids = new ArrayList<>();
        StringBuilder columns = new StringBuilder(16 * 16 * 24);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                StringBuilder runs = new StringBuilder();
                int runIndex = -1;
                int runCount = 0;
                for (int y = snapMin; y < snapMax; y++) {
                    Material m = cells.type(baseX + lx, y, baseZ + lz);
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
                    Biome biome = cells.biome(baseX + qx * 4, Math.max(minY, q * 4), baseZ + qz * 4);
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
        out.append("decorate\t").append(chunkX).append('\t').append(chunkZ).append('\t').append(seed)
                .append('\t').append(snapMin).append('\t').append(snapMax).append('\t').append(minY)
                .append('\t').append(maxY).append('\n');
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

    /** Where one written block goes: returns true when it was written. */
    @FunctionalInterface
    interface Sink {
        boolean set(int x, int y, int z, BlockData data);
    }

    /**
     * Writes the layer's answer through the sink. A block that cannot be written (no carrier, the display
     * family, an unreadable state, refused by the sink) is counted and left out. Returns the blocks written.
     */
    static int apply(String answer, int chunkX, int chunkZ, Sink sink) {
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
                    if (sink.set(baseX + Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                            baseZ + Integer.parseInt(f[3]), data)) {
                        sets++;
                    }
                }
                case "fail" -> FAILED.computeIfAbsent(f[1], k -> new AtomicLong()).addAndGet(Long.parseLong(f[2]));
                case "stat" -> {
                    String[] s = line.split("\t");
                    CLIPPED.addAndGet(Long.parseLong(s[2]));
                    BLOCK_ENTITIES.addAndGet(Long.parseLong(s[3]));
                    if (s.length > 6) {
                        WINDOW_WRITES.addAndGet(Long.parseLong(s[5]));
                        PARTIAL_HEIGHTS.addAndGet(Long.parseLong(s[6]));
                    }
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
                + " block_entities=" + BLOCK_ENTITIES.get() + " window_writes=" + WINDOW_WRITES.get()
                + " partial_height=" + PARTIAL_HEIGHTS.get() + " backfilled=" + BACKFILLED.get()
                + " backfill_conflicts=" + BACKFILL_CONFLICTS.get() + " pending=" + PENDING.size()
                + " failed=" + FAILED.keySet();
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
