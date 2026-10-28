package dev.cim.bridge;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3.3 persistent sidecar store (modded block states keyed by position).
 *
 * <p>The sidecar IS the modded world state: Paper stores a stand-in block
 * (structure_void) at each recorded position; this store maps the position
 * back to the real modded state. To honour the M3 acceptance contract
 * ("zero orphan state after restart") the store is
 *
 * <ul>
 *   <li><b>write-through</b>: every put/erase appends one line to
 *       {@code <cwd>/c-imagine/sidecar.tsv} and flushes (modded blocks are
 *       rare; the async ring comes later with cim-bridge);</li>
 *   <li><b>last-wins on load</b>: the log replays in order, later lines
 *       override earlier ones, erased positions are re-put as tombstones;</li>
 *   <li><b>torn-line tolerant</b>: a crash mid-append yields a short final
 *       line which the loader skips instead of failing the boot;</li>
 *   <li><b>cull-able</b>: {@link #erase} removes a live entry AND appends a
 *       tombstone so reloads agree (KernelBridge culls entries whose Paper
 *       stand-in no longer exists — self-healing, no ghost states).</li>
 * </ul>
 *
 * <p>File format (TSV, one record per line):
 * {@code world\tx\ty\tz\tstate} — tombstones use {@code -} as the state.
 * Blockstate strings never contain tabs or newlines (vanilla/mod ids are
 * {@code [a-z0-9_./\[\]=,]-shaped}), so no escaping is required.
 */
public final class SidecarStore {

    private static final String TOMBSTONE = "-";
    private static final String DIR_NAME = "c-imagine";
    private static final String FILE_NAME = "sidecar.tsv";

    private final Map<String, String> entries = new ConcurrentHashMap<>();
    private final Path file;
    private final Object writeLock = new Object();
    private volatile boolean dirty;

    private SidecarStore(Path file) {
        this.file = file;
    }

    /** Shared process-wide store at {@code <cwd>/c-imagine/sidecar.tsv}. */
    public static SidecarStore shared() {
        Path file = Path.of(System.getProperty("user.dir"), DIR_NAME, FILE_NAME);
        SidecarStore store = new SidecarStore(file);
        store.load();
        return store;
    }

    /** Test-visible constructor. */
    static SidecarStore at(Path file) {
        SidecarStore store = new SidecarStore(file);
        store.load();
        return store;
    }

    /** Replay the log (last wins, torn tail skipped). Idempotent per file. */
    synchronized void load() {
        entries.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length != 5) {
                    continue; // torn tail or foreign line: skip
                }
                String key = key(parts[0], parts[1], parts[2], parts[3]);
                if (TOMBSTONE.equals(parts[4])) {
                    entries.remove(key);
                } else {
                    entries.put(key, parts[4]);
                }
            }
        } catch (IOException e) {
            System.out.println("[c-imagine] sidecar: load failed (" + e + ") — starting empty");
        }
    }

    /** Record a modded state at a position (write-through append). */
    public void put(String world, int x, int y, int z, String state) {
        entries.put(key(world, x, y, z), state);
        append(world, x, y, z, state);
    }

    /** Drop a position (live map + tombstone on disk). */
    public void erase(String world, int x, int y, int z) {
        if (entries.remove(key(world, x, y, z)) != null) {
            append(world, x, y, z, TOMBSTONE);
        }
    }

    /** Look up the modded state recorded at a position. */
    public String get(String world, int x, int y, int z) {
        return entries.get(key(world, x, y, z));
    }

    /** Live entry count. */
    public int size() {
        return entries.size();
    }

    /** Snapshot of live entries (diagnostics + restore verification). */
    public List<String[]> snapshot() {
        List<String[]> out = new ArrayList<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String[] k = e.getKey().split(",", -1);
            out.add(new String[] {k[0], k[1], k[2], k[3], e.getValue()});
        }
        return out;
    }

    /** True when at least one write happened since process start (diagnostics). */
    public boolean dirty() {
        return dirty;
    }

    private void append(String world, int x, int y, int z, String state) {
        synchronized (writeLock) {
            dirty = true;
            try {
                Files.createDirectories(file.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(world + "\t" + x + "\t" + y + "\t" + z + "\t" + state);
                    w.newLine();
                }
            } catch (IOException e) {
                // never block gameplay on the journal: surface loudly, stay up
                System.out.println("[c-imagine] sidecar: append FAILED (" + e + ")"
                        + " — persistence degraded");
            }
        }
    }

    private static String key(String world, String x, String y, String z) {
        return world + "," + x + "," + y + "," + z;
    }

    private static String key(String world, int x, int y, int z) {
        return world + "," + x + "," + y + "," + z;
    }

    // ---- selftest ----------------------------------------------------------

    /**
     * Fixture selftest: {@code java dev.cim.bridge.SidecarStore --selftest}
     * (exit 0 = pass). Exercises put/get/erase, last-wins replay across a
     * store reload, torn-line tolerance and tombstone semantics.
     */
    public static void main(String[] args) {
        if (args.length == 0 || !"--selftest".equals(args[0])) {
            System.out.println("usage: SidecarStore --selftest");
            return;
        }
        int fails = 0;
        try {
            Path dir = Files.createTempDirectory("cim-sidecar-selftest");
            Path file = dir.resolve("c-imagine").resolve("sidecar.tsv");

            SidecarStore s = SidecarStore.at(file);
            fails += check("empty on fresh file", s.size(), 0);
            s.put("world", 1, 2, 3, "cimtest:test_block");
            s.put("world", 1, 2, 4, "cimtest:machine[active=true]");
            s.put("world", 1, 2, 3, "cimtest:test_block[facing=north]"); // last wins
            fails += check("get after put", String.valueOf(s.get("world", 1, 2, 3)),
                    "cimtest:test_block[facing=north]");
            fails += check("two live entries", s.size(), 2);
            fails += check("dirty flag", String.valueOf(s.dirty()), "true");

            SidecarStore reloaded = SidecarStore.at(file);
            fails += check("replay size", reloaded.size(), 2);
            fails += check("replay last-wins", String.valueOf(reloaded.get("world", 1, 2, 3)),
                    "cimtest:test_block[facing=north]");
            fails += check("replay second", String.valueOf(reloaded.get("world", 1, 2, 4)),
                    "cimtest:machine[active=true]");

            reloaded.erase("world", 1, 2, 4);
            fails += check("erase removes", String.valueOf(reloaded.get("world", 1, 2, 4)), "null");

            SidecarStore afterErase = SidecarStore.at(file);
            fails += check("tombstone replay", afterErase.size(), 1);
            fails += check("tombstone gone", String.valueOf(afterErase.get("world", 1, 2, 4)), "null");
            fails += check("tombstone kept", String.valueOf(afterErase.get("world", 1, 2, 3)),
                    "cimtest:test_block[facing=north]");

            // torn tail: simulate a crash mid-append
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write("world\t5\t5"); // no newline, truncated
            }
            SidecarStore torn = SidecarStore.at(file);
            fails += check("torn tail tolerated", torn.size(), 1);

            System.out.println("[c-imagine] SidecarStore selftest: entries=" + torn.size()
                    + " -> " + (fails == 0 ? "ALL OK" : fails + " FAILURES"));
            if (fails > 0) {
                System.exit(1);
            }
        } catch (IOException e) {
            System.out.println("[c-imagine] SidecarStore selftest IO failure: " + e);
            System.exit(1);
        }
    }

    private static int check(String what, Object actual, Object expected) {
        if (!expected.equals(actual)) {
            System.out.println("[c-imagine] SidecarStore FAIL " + what + ": got '" + actual
                    + "' expected '" + expected + "'");
            return 1;
        }
        return 0;
    }
}
