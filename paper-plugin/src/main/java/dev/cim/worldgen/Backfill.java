package dev.cim.worldgen;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The backfill of slice 1: a chunk the populator saw before the layer answered is decorated once the layer
 * is ready, under the same rules as a new chunk.
 *
 * <p>The main thread takes a {@link ChunkSnapshot} of a loaded pending chunk (cheap: a copy of its sections),
 * a worker encodes it and asks the layer, and the main thread writes the answer back into the chunk, a
 * block only where it still has the type the snapshot had. The chunk's PDC flag {@code cim:wg_done} is set
 * in the same tick as the writes, so the chunk is saved with them: that flag is the guard against a second
 * decoration, across restarts too. A chunk is decorated only when it is loaded; a chunk that unloads keeps
 * its place in the pending file until its next {@link ChunkLoadEvent}. New overworld chunks that were not
 * pending get the flag on load, so the flag also says "decorated or never needed".
 *
 * <p>The pending file ({@code worldgen-pending.txt} in the plugin folder, one {@code world TAB x TAB z} line
 * per chunk) is only the record of chunks still waiting across restarts; it is rewritten on the main thread
 * when the set changed, never by the populator.
 */
final class Backfill implements Listener {

    /** The chunk's flag: decorated (by the populator or the backfill), or not to be decorated at all. */
    static final NamespacedKey DONE = new NamespacedKey("cim", "wg_done");
    /** Chunks started per tick, and the most in flight at once: the tick stays short on the main thread. */
    private static final int PER_TICK = 2;
    private static final int IN_FLIGHT = 4;
    private static final String FILE = "worldgen-pending.txt";

    private final Plugin plugin;
    private final File file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cim-worldgen-backfill");
        thread.setDaemon(true);
        return thread;
    });
    /** Main thread only from here down. */
    private final ArrayDeque<ModWorldgen.Pending> queue = new ArrayDeque<>();
    private final Set<ModWorldgen.Pending> queued = new HashSet<>();
    /** Spawn-area chunks the scan forced: their wg_done flag predates the first decoration. */
    private final Set<ModWorldgen.Pending> forced = new HashSet<>();
    private static final java.util.concurrent.atomic.AtomicLong TICKS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ANSWERS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong POLLS = new java.util.concurrent.atomic.AtomicLong();
    private final List<Job> jobs = new ArrayList<>();
    /** Chunks whose async load is in flight (the callback has not landed yet), with the
     *  enqueue wall-clock — a future that never completes (the measured 15-stuck-pending
     *  class, run 45673102010) gets re-queued by the sweep, never stranded. Main thread
     *  only, same as every other field in this class. */
    private final java.util.HashMap<ModWorldgen.Pending, Long> loading = new java.util.HashMap<>();
    /** Timeout attempts per chunk: the second one escalates the re-issued load to the
     *  URGENT lane (the measured 12-starved-chunk class — the same chunks timed out and
     *  re-queued forever at the default priority while 157 siblings completed). */
    private final java.util.HashMap<ModWorldgen.Pending, Integer> loadAttempts = new java.util.HashMap<>();
    /** An async load older than this counts as lost: re-queued with a named warning. */
    private static final long LOAD_TIMEOUT_MS = 30_000L;
    private boolean scanned;
    private int finishedSinceLog;

    /** One chunk in flight: its snapshot (the compare base) and the worker's answer. */
    private record Job(ModWorldgen.Pending at, World world, ChunkSnapshot snapshot, CompletableFuture<String> answer) {
    }

    private Backfill(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    private static volatile Backfill instance;

    /** TRUE when nothing is in flight anywhere: the pending set drained and no async load,
     *  snapshot or layer answer is still out. The harness's awaitBackfill waits for this. */
    static boolean quiescent() {
        Backfill b = instance;
        return b != null && b.queue.isEmpty() && b.jobs.isEmpty() && b.loading.isEmpty()
                && ModWorldgen.PENDING.isEmpty();
    }

    /** Main thread: re-queue one pending chunk (a lost load or a busy-race drop) — the chunk
     *  keeps its pending place and cycles through the queue again instead of stranding. */
    private void requeue(ModWorldgen.Pending at, String why) {
        if (ModWorldgen.PENDING.contains(at) && !queued.contains(at)) {
            queued.add(at);
            queue.add(at);
            ModWorldgen.log().warning(ModWorldgen.LOG_PREFIX + "worldgen: re-queued " + at.world()
                    + " " + at.x() + "," + at.z() + " (" + why + ")");
        }
    }

    /** Loads the pending file, listens for chunk loads and runs the tick on the main thread. */
    static void start(Plugin plugin) {
        Backfill backfill = new Backfill(plugin);
        instance = backfill;
        backfill.load();
        plugin.getServer().getPluginManager().registerEvents(backfill, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, backfill::tick, 40L, 1L);
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file.toPath())) {
                String[] f = line.split("\t");
                if (f.length == 3) {
                    ModWorldgen.PENDING.add(new ModWorldgen.Pending(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2])));
                }
            }
            if (!ModWorldgen.PENDING.isEmpty()) {
                plugin.getLogger().info(ModWorldgen.LOG_PREFIX + "worldgen: " + ModWorldgen.PENDING.size()
                        + " chunk(s) pending from an earlier boot");
            }
        } catch (IOException | NumberFormatException e) {
            plugin.getLogger().warning(ModWorldgen.LOG_PREFIX + "worldgen: could not read " + file + ": " + e);
        }
    }

    /** A loaded pending chunk is queued; an unloaded one waits for its load. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        Chunk chunk = event.getChunk();
        ModWorldgen.Pending at = new ModWorldgen.Pending(world.getName(), chunk.getX(), chunk.getZ());
        if (ModWorldgen.PENDING.contains(at)) {
            enqueue(at);
        } else if (event.isNewChunk() && !flagged(chunk)) {
            flag(chunk);
        }
    }

    private void enqueue(ModWorldgen.Pending at) {
        if (queued.add(at)) {
            queue.add(at);
        }
    }

    /** One main-thread step: finish the answered jobs, start the next ones, persist the set when it changed. */
    void tick() {
        try {
            step();
        } catch (RuntimeException e) {
            plugin.getLogger().warning(ModWorldgen.LOG_PREFIX + "worldgen: backfill step failed: " + e);
        }
    }

    private void step() {
        if (ModWorldgen.plan() == null || ModWorldgen.gaveUp()) {
            persist();
            return;
        }
        if (!scanned) {
            // The chunks loaded before this plugin listened: queue the pending ones among them.
            scanned = true;
            // The spawn area was generated BEFORE the plugin (and before the
            // layer answered): it has neither the modded biomes nor the modded
            // features. Queue every loaded chunk of every normal world once;
            // the wg_done flag keeps a chunk from being decorated twice.
            for (World world : Bukkit.getWorlds()) {
                if (world.getEnvironment() != World.Environment.NORMAL) {
                    continue;
                }
                // A RADIUS around spawn, not getLoadedChunks(): Paper unloads
                // the boot-time spawn chunks long before the layer answers, so
                // the loaded set is empty by now. The backfill loads each one.
                int cx = world.getSpawnLocation().getBlockX() >> 4;
                int cz = world.getSpawnLocation().getBlockZ() >> 4;
                int radius = 6;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        ModWorldgen.Pending at = new ModWorldgen.Pending(world.getName(), cx + dx, cz + dz);
                        ModWorldgen.defer(world.getName(), cx + dx, cz + dz);
                        forced.add(at);
                    }
                }
            }
            int defer = 0;
            for (ModWorldgen.Pending at : ModWorldgen.PENDING) {
                enqueue(at);
                defer++;
            }
            if (ModWorldgen.DEBUG) {
                plugin.getLogger().info(ModWorldgen.LOG_PREFIX + "worldgen: backfill scan: "
                        + ModWorldgen.PENDING.size() + " pending, " + queue.size() + " queued");
            }
        }
        for (Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.answer().isDone()) {
                it.remove();
                finish(job);
            }
        }
        int started = 0;
        if (ModWorldgen.DEBUG && (TICKS.incrementAndGet() % 100) == 0) {
            plugin.getLogger().info(ModWorldgen.LOG_PREFIX + "worldgen: backfill tick: queue=" + queue.size()
                    + " jobs=" + jobs.size() + " pending=" + ModWorldgen.PENDING.size());
        }
        if (ModWorldgen.DEBUG && POLLS.incrementAndGet() <= 5) {
            plugin.getLogger().info(ModWorldgen.LOG_PREFIX + "worldgen: backfill poll gate: empty=" + queue.isEmpty()
                    + " size=" + queue.size() + " jobs=" + jobs.size() + " inFlight=" + IN_FLIGHT
                    + " perTick=" + PER_TICK);
        }
        while (started < PER_TICK && jobs.size() < IN_FLIGHT && !queue.isEmpty()) {
            ModWorldgen.Pending at = queue.poll();
            queued.remove(at);
            if (!ModWorldgen.PENDING.contains(at) || busy(at) || loading.containsKey(at)) {
                continue;
            }
            World world = Bukkit.getWorld(at.world());
            if (world == null) {
                continue;
            }
            // 14-e253 THE ASYNC LOAD: the old main-thread getChunkAt wedged
            // the server thread on a mid-generation chunk (survival run
            // 45427818790: syncLoad parked forever on chunk (-1,4) at
            // initialize_light with generationTask=null, priority BLOCKING —
            // the watchdog thread-dumped for 10+ s, the survival harness
            // never ran, the whole smoke failed). Paper's async loader
            // schedules the generation through the chunk system and hands
            // the chunk back later; the start continues in the callback.
            // 14-e259 THE RETRY NET + THE URGENT ESCALATION: a future that FAILS
            // or never lands must not strand the chunk (the measured 15-stuck-pending
            // class) — the exceptionally arm re-queues it, the sweep re-queues the
            // silent ones, and a chunk that timed out TWICE re-issues its load on
            // the urgent lane (the 12-starved-chunk class: the same chunks cycled
            // the default-priority queue forever while 157 siblings completed).
            boolean urgent = loadAttempts.getOrDefault(at, 0) >= 2;
            loading.put(at, System.currentTimeMillis());
            java.util.concurrent.CompletableFuture<Chunk> load = urgent
                    ? world.getChunkAtAsyncUrgently(at.x(), at.z())
                    : world.getChunkAtAsync(at.x(), at.z());
            load.thenAccept(chunk -> {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        loading.remove(at);
                        loadAttempts.remove(at);
                        loadReady(at, world, chunk);
                    });
                } catch (RuntimeException e) {
                    // the scheduler rejects a task once the plugin disabled
                    // (IllegalPluginAccessException): the backfill is gone,
                    // the ticket goes with it
                    loading.remove(at);
                }
            }).exceptionally(ex -> {
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        loading.remove(at);
                        requeue(at, "async load failed: " + ex.getClass().getSimpleName());
                    });
                } catch (RuntimeException e) {
                    loading.remove(at);
                }
                return null;
            });
            started++;
        }
        // 14-e259 THE SWEEP: a load older than LOAD_TIMEOUT_MS counts as lost —
        // re-queue it so the pending set can drain (the quiesce waits on it); the
        // second timeout of the same chunk escalates its next issue to urgent.
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<ModWorldgen.Pending, Long> e : new ArrayList<>(loading.entrySet())) {
            if (now - e.getValue() > LOAD_TIMEOUT_MS) {
                loading.remove(e.getKey());
                int attempts = loadAttempts.merge(e.getKey(), 1, Integer::sum);
                requeue(e.getKey(), "async load timed out after " + LOAD_TIMEOUT_MS + " ms"
                        + (attempts >= 2 ? " — escalating to the urgent lane" : ""));
            }
        }
        persist();
        if (queue.isEmpty() && jobs.isEmpty() && finishedSinceLog > 0) {
            ModWorldgen.log().info(ModWorldgen.LOG_PREFIX + "worldgen: backfilled " + ModWorldgen.BACKFILLED.get()
                    + " chunk(s), " + ModWorldgen.PENDING.size() + " pending");
            finishedSinceLog = 0;
        }
    }

    /** Main thread, once the async load lands: the old in-loop start branch. */
    private void loadReady(ModWorldgen.Pending at, World world, Chunk chunk) {
        if (!ModWorldgen.PENDING.contains(at) || busy(at)) {
            // 14-e259: a busy-race drop used to strand the chunk (its queue slot was
            // already polled) — re-queue so the pending set can drain.
            requeue(at, "load landed while a job holds the chunk");
            return;
        }
        if (flagged(chunk) && !forced.contains(at)) {
            complete(at, false);
            return;
        }
        start(at, world, chunk);
    }

    private boolean busy(ModWorldgen.Pending at) {
        for (Job job : jobs) {
            if (job.at().equals(at)) {
                return true;
            }
        }
        return false;
    }

    /** Main thread: snapshot the chunk and hand the encode and the layer call to the worker. */
    private void start(ModWorldgen.Pending at, World world, Chunk chunk) {
        ModWorldgen.Plan p = ModWorldgen.plan();
        Function<String, String> fn = ModWorldgen.layer();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();
        int lo = Math.max(p.windowMin(), minY);
        int hi = Math.min(p.windowMax(), maxY);
        if (lo >= hi) {
            // No mod feature reaches this world's window: nothing to write, the flag records that.
            flag(chunk);
            complete(at, false);
            return;
        }
        // Pin it: without a ticket Paper unloads a chunk with no players between
        // the job start and the finish, and the answer is then dropped.
        chunk.addPluginChunkTicket(plugin);
        long seed = world.getSeed();
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, true, false);
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> fn.apply(ModWorldgen.encode(
                ModWorldgen.Cells.of(snapshot, at.x(), at.z()), seed, minY, maxY, at.x(), at.z(), lo, hi)), worker);
        jobs.add(new Job(at, world, snapshot, answer));
    }

    /** Main thread: write the answer into the chunk where it is still the snapshot's type, then flag it. */
    private void finish(Job job) {
        ModWorldgen.Pending at = job.at();
        World world = job.world();
        if (!ModWorldgen.PENDING.contains(at)) {
            release(world, at);
            return;
        }
        if (!world.isChunkLoaded(at.x(), at.z())) {
            release(world, at);
            enqueue(at);
            return;
        }
        String answer;
        try {
            answer = job.answer().get();
        } catch (Exception e) {
            // A failed encode or layer call: the chunk is not retried (no loop), the reason is logged.
            plugin.getLogger().warning(ModWorldgen.LOG_PREFIX + "worldgen: backfill of " + at.x() + "," + at.z()
                    + " failed: " + e.getCause());
            complete(at, false);
            return;
        }
        if (answer.startsWith("wait")) {
            if (ModWorldgen.DEBUG && ANSWERS.incrementAndGet() <= 3) {
                plugin.getLogger().info(ModWorldgen.LOG_PREFIX + "worldgen: backfill answer for " + at.x() + ","
                        + at.z() + " -> " + answer.split("\n")[0]);
            }
            release(world, at);
            enqueue(at);
            return;
        }
        if (answer.startsWith("error")) {
            plugin.getLogger().warning(ModWorldgen.LOG_PREFIX + "worldgen: backfill of " + at.x() + "," + at.z()
                    + " answered " + answer.split("\n")[0]);
            release(world, at);
            complete(at, false);
            return;
        }
        Chunk chunk = world.getChunkAt(at.x(), at.z());
        if (flagged(chunk) && !forced.contains(at)) {
            complete(at, false);
            return;
        }
        // The biome map first: the decorate below filters by the cell biome.
        ModWorldgen.writeBiomes(world, at.x(), at.z());
        int sets = ModWorldgen.apply(answer, at.x(), at.z(), (x, y, z, data) -> {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType() != job.snapshot().getBlockType(Math.floorMod(x, 16), y, Math.floorMod(z, 16))) {
                ModWorldgen.BACKFILL_CONFLICTS.incrementAndGet();
                return false;
            }
            block.setBlockData(data, false);
            return true;
        });
        flag(chunk);
        release(world, at);
        complete(at, true);
        if (ModWorldgen.DEBUG) {
            ModWorldgen.log().info(ModWorldgen.LOG_PREFIX + "worldgen: backfilled chunk " + at.x() + "," + at.z()
                    + ": " + sets + " block(s)");
        }
    }

    /** Drop the pin a job put on its chunk (idempotent).
     *  14-e253: the ticket is released ONLY on a loaded chunk — the old
     *  getChunkAt here sync-loaded an unloaded chunk on the main thread to
     *  remove a ticket from it (the same wedge class the step loop hit). */
    private void release(World world, ModWorldgen.Pending at) {
        try {
            World w = world != null ? world : Bukkit.getWorld(at.world());
            if (w != null && w.isChunkLoaded(at.x(), at.z())) {
                // 14-e253: a chunk with a tracked ticking carrier keeps its ticket — the ticket
                // drop let Paper's chunk GC unload the chunk under the carrier mid-smoke (the
                // tracker released and re-adopted on the reload: the ticking flap). The ticket
                // rides until the carrier breaks (the tracker releases it then).
                if (ModWorldgen.tickingIn(w.getName(), at.x(), at.z())) {
                    return;
                }
                w.getChunkAt(at.x(), at.z()).removePluginChunkTicket(plugin);
            }
        } catch (RuntimeException ignored) {
            // the chunk may be gone; the ticket goes with it
        }
    }

    private void complete(ModWorldgen.Pending at, boolean backfilled) {
        forced.remove(at);
        if (ModWorldgen.PENDING.remove(at)) {
            ModWorldgen.PENDING_DIRTY.set(true);
        }
        if (backfilled) {
            ModWorldgen.BACKFILLED.incrementAndGet();
            finishedSinceLog++;
        }
    }

    private static boolean flagged(Chunk chunk) {
        return chunk.getPersistentDataContainer().has(DONE, PersistentDataType.BYTE);
    }

    private static void flag(Chunk chunk) {
        chunk.getPersistentDataContainer().set(DONE, PersistentDataType.BYTE, (byte) 1);
    }

    /** Rewrites the pending file when the set changed (atomic: a temporary file, then a move). */
    private void persist() {
        if (!ModWorldgen.PENDING_DIRTY.getAndSet(false)) {
            return;
        }
        try {
            Path target = file.toPath();
            if (ModWorldgen.PENDING.isEmpty()) {
                Files.deleteIfExists(target);
                return;
            }
            List<String> lines = new ArrayList<>();
            for (ModWorldgen.Pending at : ModWorldgen.PENDING) {
                lines.add(at.world() + "\t" + at.x() + "\t" + at.z());
            }
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Path tmp = target.resolveSibling(FILE + ".tmp");
            Files.write(tmp, lines);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            ModWorldgen.PENDING_DIRTY.set(true);
            plugin.getLogger().warning(ModWorldgen.LOG_PREFIX + "worldgen: could not write " + file + ": " + e);
        }
    }
}
