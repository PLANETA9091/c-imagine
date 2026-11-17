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
    private final List<Job> jobs = new ArrayList<>();
    private boolean scanned;
    private int finishedSinceLog;

    /** One chunk in flight: its snapshot (the compare base) and the worker's answer. */
    private record Job(ModWorldgen.Pending at, World world, ChunkSnapshot snapshot, CompletableFuture<String> answer) {
    }

    private Backfill(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    /** Loads the pending file, listens for chunk loads and runs the tick on the main thread. */
    static void start(Plugin plugin) {
        Backfill backfill = new Backfill(plugin);
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
            for (ModWorldgen.Pending at : ModWorldgen.PENDING) {
                enqueue(at);
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
        while (started < PER_TICK && jobs.size() < IN_FLIGHT && !queue.isEmpty()) {
            ModWorldgen.Pending at = queue.poll();
            queued.remove(at);
            if (!ModWorldgen.PENDING.contains(at) || busy(at)) {
                continue;
            }
            World world = Bukkit.getWorld(at.world());
            if (world == null || !world.isChunkLoaded(at.x(), at.z())) {
                continue;
            }
            Chunk chunk = world.getChunkAt(at.x(), at.z());
            if (flagged(chunk)) {
                complete(at, false);
                continue;
            }
            start(at, world, chunk);
            started++;
        }
        persist();
        if (queue.isEmpty() && jobs.isEmpty() && finishedSinceLog > 0) {
            ModWorldgen.log().info(ModWorldgen.LOG_PREFIX + "worldgen: backfilled " + ModWorldgen.BACKFILLED.get()
                    + " chunk(s), " + ModWorldgen.PENDING.size() + " pending");
            finishedSinceLog = 0;
        }
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
        if (!ModWorldgen.PENDING.contains(at) || !world.isChunkLoaded(at.x(), at.z())) {
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
            enqueue(at);
            return;
        }
        if (answer.startsWith("error")) {
            plugin.getLogger().warning(ModWorldgen.LOG_PREFIX + "worldgen: backfill of " + at.x() + "," + at.z()
                    + " answered " + answer.split("\n")[0]);
            complete(at, false);
            return;
        }
        Chunk chunk = world.getChunkAt(at.x(), at.z());
        if (flagged(chunk)) {
            complete(at, false);
            return;
        }
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
        complete(at, true);
        if (ModWorldgen.DEBUG) {
            ModWorldgen.log().info(ModWorldgen.LOG_PREFIX + "worldgen: backfilled chunk " + at.x() + "," + at.z()
                    + ": " + sets + " block(s)");
        }
    }

    private void complete(ModWorldgen.Pending at, boolean backfilled) {
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
