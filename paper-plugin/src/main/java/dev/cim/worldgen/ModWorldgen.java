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
    /** The owning plugin (set at start): the async callbacks schedule through it. */
    private static volatile Plugin PLUGIN;
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
    /**
     * Display-family blocks a chunk's decoration placed, by chunk key: a
     * populator cannot spawn entities, so the positions wait here until the
     * chunk loads — {@link #flushDisplays} writes them into the chunk's PDC
     * (the same key the imported-world path uses) and DisplayCarriers adopts
     * them into item displays.
     */
    static final Map<Long, List<String>> DISPLAY_QUEUE = new ConcurrentHashMap<>();

    /** Chunks whose biome grid was written from the layer's map (slice 1). */
    static final AtomicLong BIOME_CHUNKS = new AtomicLong();
    static final AtomicLong BIOME_CELLS = new AtomicLong();
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
        PLUGIN = plugin;
        // One-shot self check once the layer answers: the biome map of the spawn
        // chunk, written into the live chunk. Boot-log proof for the playtest.
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                if (org.bukkit.Bukkit.getWorlds().isEmpty()) {
                    return;
                }
                org.bukkit.World world = org.bukkit.Bukkit.getWorlds().get(0);
                int cells = writeBiomes(world, 0, 0);
                log.info(LOG_PREFIX + "worldgen: biome self-check chunk 0,0 -> " + cells
                        + " cell(s), " + BIOME_BY_ID.size() + " biome(s) resolved");
                // A FRESH chunk through the real path: the populator writes
                // the biome map and then decorates with the biome's modded
                // features. This is what exploration triggers.
                String raw = ask("biomes\t0\t0\t" + world.getSeed());
                log.info(LOG_PREFIX + "worldgen: biome raw answer -> "
                        + (raw == null ? "null" : raw.substring(0, Math.min(160, raw.length())).replace('\n', '|')));
                // 14-e253: the probe chunk loads ASYNC — a sync getChunkAt on
                // the main thread can wedge the server on a mid-generation
                // chunk (the survival-run 45427818790 class).
                world.getChunkAtAsync(8, 8).thenAccept(chunk ->
                        log.info(LOG_PREFIX + "worldgen: self-check after chunk 8,8: display=" + DISPLAY.get()
                                + " queued=" + DISPLAY_QUEUE.size() + " sets=" + SETS.get()));
            } catch (RuntimeException e) {
                log.warning(LOG_PREFIX + "worldgen: biome self-check failed: " + e);
            }
        }, 120L);
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
                long worldSeed = org.bukkit.Bukkit.getWorlds().isEmpty()
                        ? 0L
                        : org.bukkit.Bukkit.getWorlds().get(0).getSeed();
                String said = fn.apply("list\t" + worldSeed);
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
            // Slice 1: the chunk's biomes become the layer's modded ones FIRST, so
            // the decoration below filters features by the modded biome and the
            // client renders the right grass/sky/water without any wire overlay.
            int biomeCells = writeBiomes(fn, region, info, chunkX, chunkZ);
            if (biomeCells > 0) {
                BIOME_CHUNKS.incrementAndGet();
            }
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

    /** Resolved Bukkit biomes by registry id (the plugin registered the modded ones at enable). */
    private static final Map<String, Biome> BIOME_BY_ID = new ConcurrentHashMap<>();
    private static final AtomicBoolean BIOME_LOOKUP_LOGGED = new AtomicBoolean();

    /**
     * Writes the layer's modded biome map into the chunk.
     *
     * <p>{@code biomes TAB cx TAB cz TAB seed} asks the layer for its biome source
     * sampled with THIS world's seed, so biome borders line up with the terrain
     * Paper generates. The answer is a palette plus 16 run-length columns over the
     * world's quart rows; every 4x4x4 cell is written with
     * {@link LimitedRegion#setBiome}.
     *
     * @return the number of cells written (0 = the layer has no answer yet)
     */
    static int writeBiomes(Function<String, String> fn, LimitedRegion region, WorldInfo info, int chunkX, int chunkZ) {
        String answer;
        try {
            answer = fn.apply("biomes\t" + chunkX + "\t" + chunkZ + "\t" + info.getSeed() + "\n");
        } catch (RuntimeException e) {
            return 0;
        }
        if (answer == null || answer.startsWith("wait") || answer.startsWith("error") || answer.isBlank()) {
            return 0;
        }
        int quartMin = info.getMinHeight() >> 2;
        int quartCount = ((info.getMaxHeight() - 1) >> 2) - quartMin + 1;
        Map<Integer, String> palette = new HashMap<>();
        int[][] columns = new int[16][];
        for (String line : answer.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 3) {
                continue;
            }
            if (f[0].equals("bpal")) {
                palette.put(Integer.parseInt(f[1]), f[2]);
            } else if (f[0].equals("bio") && f.length >= 4) {
                int qx = Integer.parseInt(f[1]);
                int qz = Integer.parseInt(f[2]);
                int[] column = new int[quartCount];
                if (fillRuns(f[3], column, quartCount) != quartCount) {
                    return 0;
                }
                columns[qz * 4 + qx] = column;
            }
        }
        if (palette.isEmpty()) {
            return 0;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int cells = 0;
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                int[] column = columns[qz * 4 + qx];
                if (column == null) {
                    continue;
                }
                for (int qy = 0; qy < quartCount; qy++) {
                    String id = palette.get(column[qy]);
                    if (id == null) {
                        continue;
                    }
                    Biome biome = biomeOf(id);
                    if (biome == null) {
                        continue;
                    }
                    int x = baseX + qx * 4;
                    int y = (quartMin + qy) * 4;
                    int z = baseZ + qz * 4;
                    if (y < info.getMinHeight() || y >= info.getMaxHeight()) {
                        continue;
                    }
                    if (region.isInRegion(x, y, z)) {
                        region.setBiome(x, y, z, biome);
                        cells++;
                    }
                }
            }
        }
        BIOME_CELLS.addAndGet(cells);
        if (DEBUG) {
            log.info(LOG_PREFIX + "worldgen biome map: chunk " + chunkX + "," + chunkZ + ": " + cells
                    + " cell(s), biomes=" + palette.values());
        }
        return cells;
    }

    /**
     * The backfill variant of the biome pass: the chunk is already live, so the
     * cells go through {@link World#setBiome}. Same request, same answer format.
     */
    static int writeBiomes(World world, int chunkX, int chunkZ) {
        Function<String, String> fn = layer;
        if (fn == null) {
            return 0;
        }
        String answer;
        try {
            answer = fn.apply("biomes\t" + chunkX + "\t" + chunkZ + "\t" + world.getSeed() + "\n");
        } catch (RuntimeException e) {
            return 0;
        }
        if (answer == null || answer.startsWith("wait") || answer.startsWith("error") || answer.isBlank()) {
            return 0;
        }
        int quartMin = world.getMinHeight() >> 2;
        int quartCount = ((world.getMaxHeight() - 1) >> 2) - quartMin + 1;
        Map<Integer, String> palette = new HashMap<>();
        int[][] columns = new int[16][];
        for (String line : answer.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 3) {
                continue;
            }
            if (f[0].equals("bpal")) {
                palette.put(Integer.parseInt(f[1]), f[2]);
            } else if (f[0].equals("bio") && f.length >= 4) {
                int qx = Integer.parseInt(f[1]);
                int qz = Integer.parseInt(f[2]);
                int[] column = new int[quartCount];
                if (fillRuns(f[3], column, quartCount) != quartCount) {
                    return 0;
                }
                columns[qz * 4 + qx] = column;
            }
        }
        if (palette.isEmpty()) {
            return 0;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int cells = 0;
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                int[] column = columns[qz * 4 + qx];
                if (column == null) {
                    continue;
                }
                for (int qy = 0; qy < quartCount; qy++) {
                    String id = palette.get(column[qy]);
                    if (id == null) {
                        continue;
                    }
                    Biome biome = biomeOf(id);
                    if (biome == null) {
                        continue;
                    }
                    int x = baseX + qx * 4;
                    int y = (quartMin + qy) * 4;
                    int z = baseZ + qz * 4;
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                        continue;
                    }
                    world.setBiome(x, y, z, biome);
                    cells++;
                }
            }
        }
        BIOME_CELLS.addAndGet(cells);
        return cells;
    }

    /** The Bukkit biome for a registry id (null when the registry has no such entry). */
    private static Biome biomeOf(String id) {
        Biome cached = BIOME_BY_ID.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(id);
            if (key == null) {
                return null;
            }
            org.bukkit.Registry<Biome> registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME);
            Biome biome = registry.get(key);
            if (biome != null) {
                BIOME_BY_ID.put(id, biome);
            } else if (BIOME_LOOKUP_LOGGED.compareAndSet(false, true)) {
                log.warning(LOG_PREFIX + "worldgen: biome lookup MISS for " + id
                        + " (registry size=" + registry.size() + ")");
            }
            return biome;
        } catch (RuntimeException e) {
            if (BIOME_LOOKUP_LOGGED.compareAndSet(false, true)) {
                log.warning(LOG_PREFIX + "worldgen: biome lookup failed for " + id + ": " + e);
            }
            return null;
        }
    }

    /** Fills {@code target} from runs {@code i:n,...}; returns the cells written. */
    private static int fillRuns(String runs, int[] target, int length) {
        int at = 0;
        for (String run : runs.split(",")) {
            if (run.isEmpty()) {
                continue;
            }
            int colon = run.indexOf(':');
            if (colon <= 0) {
                return -1;
            }
            int index = Integer.parseInt(run.substring(0, colon));
            int count = Integer.parseInt(run.substring(colon + 1));
            if (at + count > length) {
                return -1;
            }
            Arrays.fill(target, at, at + count, index);
            at += count;
        }
        return at;
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
                    int x = baseX + Integer.parseInt(f[1]);
                    int y = Integer.parseInt(f[2]);
                    int z = baseZ + Integer.parseInt(f[3]);
                    String state = f[4];
                    int bracket = state.indexOf('[');
                    String id = bracket < 0 ? state : state.substring(0, bracket);
                    if (!id.startsWith("minecraft:")) {
                        F2vPack.VisualBlock row = rows.get(id);
                        if (row != null && row.isDisplay()) {
                            // No entity from a populator: remember it for the
                            // chunk-load flush (barrier + item display).
                            DISPLAY.incrementAndGet();
                            recordDisplay(chunkX, chunkZ, x, y, z, id);
                            continue;
                        }
                    }
                    BlockData data = data(state, cache);
                    if (data == null) {
                        continue;
                    }
                    if (sink.set(x, y, z, data)) {
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

    private static void recordDisplay(int chunkX, int chunkZ, int x, int y, int z, String id) {
        long key = chunkKey(chunkX, chunkZ);
        DISPLAY_QUEUE.computeIfAbsent(key, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(id + "|" + x + "|" + y + "|" + z);
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFF_FFFFL);
    }

    /**
     * Writes the chunk's recorded display-family positions into its PDC
     * ({@code cim:disp_x_y_z = <mod id>}), which is what
     * {@code dev.cim.hud.DisplayCarriers} reads when it adopts a chunk.
     * Called from the chunk-load path before the adopt.
     */
    public static int flushDisplays(org.bukkit.Chunk chunk) {
        List<String> queued = DISPLAY_QUEUE.remove(chunkKey(chunk.getX(), chunk.getZ()));
        if (queued == null || queued.isEmpty()) {
            return 0;
        }
        var pdc = chunk.getPersistentDataContainer();
        int written = 0;
        synchronized (queued) {
            for (String entry : queued) {
                String[] f = entry.split("\\|");
                if (f.length != 4) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(f[1]);
                    int y = Integer.parseInt(f[2]);
                    int z = Integer.parseInt(f[3]);
                    pdc.set(new org.bukkit.NamespacedKey("cim", "disp_" + x + "_" + y + "_" + z),
                            org.bukkit.persistence.PersistentDataType.STRING, f[0]);
                    written++;
                } catch (IllegalArgumentException ignored) {
                    // an unconvertible key: skip the cell, keep the rest
                }
            }
        }
        return written;
    }

    /** One request to the layer (null when it is not up yet). */
    public static String ask(String request) {
        Function<String, String> fn = layer;
        return fn == null ? null : fn.apply(request);
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
     * the carriers of {@code carrierId} below {@code yCut}.
     *
     * <p>14-e253 THE ASYNC PROBE: the old shape ran on the main thread via
     * {@code callSync} and loaded the far chunks with sync {@code getChunkAt}
     * — the same wedge class the backfill's step loop hit (survival run
     * 45427818790: the server thread parked in syncLoad, the watchdog
     * thread-dumped, the harness never ran). The loads go through Paper's
     * async loader now; the scan runs on the main thread once every chunk of
     * the square is loaded, and the {@code [cim-worldgen] probe:} lines are
     * logged from here (the caller is fire-and-forget).
     */
    public static void probe(World world, String carrierId, int chunkX, int chunkZ, int size, int yCut) {
        Plan p = plan;
        if (p == null) {
            log().info("[cim-worldgen] probe: no worldgen plan (the layer did not answer)");
            return;
        }
        F2vPack.VisualBlock row = rows.get(carrierId);
        if (row == null || row.isDisplay()) {
            log().info("[cim-worldgen] probe: no note carrier for " + carrierId);
            return;
        }
        List<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>> loads = new ArrayList<>();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                loads.add(world.getChunkAtAsync(chunkX + dx, chunkZ + dz));
            }
        }
        java.util.concurrent.CompletableFuture
                .allOf(loads.toArray(new java.util.concurrent.CompletableFuture[0]))
                .thenAccept(v -> {
                    try {
                        org.bukkit.Bukkit.getScheduler().runTask(PLUGIN,
                                () -> scanProbe(world, carrierId, chunkX, chunkZ, size, yCut));
                    } catch (RuntimeException e) {
                        // the plugin disabled before the square loaded: no scan, no crash
                        log().warning("[cim-worldgen] probe: scan dropped (plugin disabled): " + e);
                    }
                });
    }

    /** Main thread, every chunk of the probe square loaded: the old probe scan. */
    private static void scanProbe(World world, String carrierId, int chunkX, int chunkZ, int size, int yCut) {
        F2vPack.VisualBlock row = rows.get(carrierId);
        if (row == null || row.isDisplay()) {
            log().info("[cim-worldgen] probe: no note carrier for " + carrierId);
            return;
        }
        BlockData want = Bukkit.createBlockData(F2vPack.carrierState(row));
        long found = 0;
        int loaded = 0;
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                if (!world.isChunkLoaded(chunkX + dx, chunkZ + dz)) {
                    continue; // unloaded between the load and the scan: the chunk waits for its next load
                }
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
        log().info("[cim-worldgen] probe: " + carrierId + " carriers below y=" + yCut + " in " + loaded
                + " chunk(s): " + found);
        log().info("[cim-worldgen] probe: " + stats());
    }
}
