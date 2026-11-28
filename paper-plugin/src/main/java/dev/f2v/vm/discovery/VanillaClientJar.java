package dev.f2v.vm.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.f2v.bridge.F2VLog;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches the vanilla 1.21.1 CLIENT jar once, so the server-side screen scanner can see the
 * client-only classes (Screen, ConfirmScreen, AbstractContainerScreen ...) that no server jar ships.
 *
 * <p>Path: Mojang's {@code version_manifest_v2.json} -> the 1.21.1 version json -> its
 * {@code downloads.client} object. Every hop is SHA-1 verified against the value Mojang publishes,
 * and the jar is written to {@code <server>/cache/vanilla-client-1.21.1.jar} through a temp file
 * and an atomic move, so a partial download never looks like a cached jar.
 *
 * <p>Runs on a daemon thread started from plugin enable ({@link #startAsync(Path)}). Scans that
 * need the jar call {@link #awaitClientJar(Path)}, which waits up to {@link #WAIT} once and then
 * gives up (logged once) so the scan proceeds without it. Offline servers log once and continue;
 * {@code -Df2v.vanillaJar} always wins and skips the fetch entirely.
 */
public final class VanillaClientJar {
    private VanillaClientJar() {}

    public static final String MINECRAFT_VERSION = "1.21.1";
    public static final String CACHE_FILE = "vanilla-client-" + MINECRAFT_VERSION + ".jar";
    /** Mojang's official ProGuard client mappings (named classes for the obfuscated client jar). */
    public static final String MAPPINGS_FILE = "vanilla-client-" + MINECRAFT_VERSION + ".mappings.txt";
    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    /** Upper bound a scan waits for an in-flight download before continuing without the jar. */
    static final Duration WAIT = Duration.ofSeconds(120);

    private static final AtomicReference<CompletableFuture<Path>> PENDING = new AtomicReference<>();
    private static final AtomicBoolean WAIT_GAVE_UP = new AtomicBoolean();
    private static final AtomicBoolean FETCH_FAILURE_LOGGED = new AtomicBoolean();

    /** Minimal HTTP seam so tests run with no network. */
    public interface Http {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    /** A {@code url} + optional SHA-1 pair taken from Mojang JSON. */
    public record Ref(String url, String sha1, long size) {}

    // ---- pure parsing and verification (unit-tested, no I/O) ------------------

    /** The version json URL for {@code versionId} in a version_manifest_v2 document. */
    public static Ref parseManifest(String manifestJson, String versionId) {
        JsonObject root = JsonParser.parseString(manifestJson).getAsJsonObject();
        JsonArray versions = root.getAsJsonArray("versions");
        if (versions != null) {
            for (JsonElement el : versions) {
                JsonObject v = el.getAsJsonObject();
                if (versionId.equals(optString(v, "id"))) {
                    return new Ref(requireString(v, "url"), optString(v, "sha1"), -1L);
                }
            }
        }
        throw new IllegalArgumentException("version " + versionId + " not in manifest");
    }

    /** The {@code downloads.client} entry of a version json (url, sha1, size). */
    public static Ref parseClientDownload(String versionJson) {
        JsonObject root = JsonParser.parseString(versionJson).getAsJsonObject();
        JsonObject client = root.getAsJsonObject("downloads").getAsJsonObject("client");
        if (client == null) throw new IllegalArgumentException("version json has no downloads.client");
        long size = client.has("size") ? client.get("size").getAsLong() : -1L;
        return new Ref(requireString(client, "url"), requireString(client, "sha1").toLowerCase(Locale.ROOT), size);
    }

    /** The {@code downloads.client_mappings} entry of a version json, or null when absent. */
    public static Ref parseClientMappings(String versionJson) {
        JsonObject root = JsonParser.parseString(versionJson).getAsJsonObject();
        JsonObject downloads = root.getAsJsonObject("downloads");
        JsonObject map = downloads == null ? null : downloads.getAsJsonObject("client_mappings");
        if (map == null) return null;
        long size = map.has("size") ? map.get("size").getAsLong() : -1L;
        return new Ref(requireString(map, "url"), requireString(map, "sha1").toLowerCase(Locale.ROOT), size);
    }

    /** True when {@code data} hashes to {@code expectedHex} (case-insensitive). */
    public static boolean sha1Matches(byte[] data, String expectedHex) {
        return expectedHex != null && sha1Hex(data).equalsIgnoreCase(expectedHex);
    }

    public static String sha1Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 unavailable", impossible);
        }
    }

    // ---- fetch -----------------------------------------------------------------

    /**
     * Ensures {@code cacheDir/vanilla-client-1.21.1.jar} exists. Returns immediately when it is
     * already present. Otherwise downloads manifest -> version json -> client jar, verifies each
     * SHA-1, and moves the jar into place atomically.
     */
    public static Path ensure(Path cacheDir, Http http) throws IOException, InterruptedException {
        Path target = cacheDir.resolve(CACHE_FILE);
        Path mappings = cacheDir.resolve(MAPPINGS_FILE);
        if (Files.isRegularFile(target) && Files.isRegularFile(mappings)) return target;
        byte[] manifest = http.get(MANIFEST_URL);
        Ref versionRef = parseManifest(new String(manifest, StandardCharsets.UTF_8), MINECRAFT_VERSION);
        byte[] versionJson = http.get(versionRef.url());
        if (versionRef.sha1() != null && !sha1Matches(versionJson, versionRef.sha1())) {
            throw new IOException("version json sha1 mismatch for " + MINECRAFT_VERSION);
        }
        String versionText = new String(versionJson, StandardCharsets.UTF_8);
        if (!Files.isRegularFile(target)) {
            Ref client = parseClientDownload(versionText);
            byte[] jar = http.get(client.url());
            if (!sha1Matches(jar, client.sha1())) {
                throw new IOException("client jar sha1 mismatch: expected " + client.sha1()
                        + " got " + sha1Hex(jar));
            }
            if (client.size() >= 0 && jar.length != client.size()) {
                throw new IOException("client jar size mismatch: expected " + client.size() + " got " + jar.length);
            }
            writeAtomic(cacheDir, CACHE_FILE, jar);
        }
        if (!Files.isRegularFile(mappings)) {
            // Without mappings the jar still scans (obfuscated names only); the next boot retries.
            try {
                Ref map = parseClientMappings(versionText);
                if (map == null) throw new IOException("version json has no downloads.client_mappings");
                byte[] text = http.get(map.url());
                if (!sha1Matches(text, map.sha1())) {
                    throw new IOException("mappings sha1 mismatch: expected " + map.sha1() + " got " + sha1Hex(text));
                }
                writeAtomic(cacheDir, MAPPINGS_FILE, text);
            } catch (IOException mappingFailure) {
                F2VLog.warn("[vm] vanilla client mappings not fetched (" + mappingFailure.getMessage() + ")");
            }
        }
        return target;
    }

    private static void writeAtomic(Path cacheDir, String name, byte[] data) throws IOException {
        Files.createDirectories(cacheDir);
        // Fixed .part name (not createTempFile) so the file keeps the umask-default permissions.
        Path tmp = cacheDir.resolve(name + ".part");
        Path target = cacheDir.resolve(name);
        try {
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The cached client mappings next to a cached client jar, or null. */
    public static Path mappingsFor(Path clientJar) {
        if (clientJar == null) return null;
        Path m = clientJar.resolveSibling(MAPPINGS_FILE);
        return Files.isRegularFile(m) ? m : null;
    }

    /** Java HTTP client backed by {@link HttpClient}; non-200 responses fail. */
    static Http defaultHttp() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "c-imagine-f2v")
                    .GET().build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            return resp.body();
        };
    }

    /**
     * Starts the one-time background fetch unless the jar is already cached or
     * {@code -Df2v.vanillaJar} is set. Safe to call more than once; only the first call starts a thread.
     */
    public static void startAsync(Path serverDir) {
        String explicit = System.getProperty("f2v.vanillaJar");
        if (explicit != null && !explicit.isBlank()) return;
        Path cacheDir = serverDir.toAbsolutePath().normalize().resolve("cache");
        if (Files.isRegularFile(cacheDir.resolve(CACHE_FILE))
                && Files.isRegularFile(cacheDir.resolve(MAPPINGS_FILE))) return;
        CompletableFuture<Path> future = new CompletableFuture<>();
        if (!PENDING.compareAndSet(null, future)) return;
        Thread worker = new Thread(() -> {
            try {
                Path jar = ensure(cacheDir, defaultHttp());
                F2VLog.info("[vm] fetched vanilla " + MINECRAFT_VERSION + " client jar: " + jar
                        + " sha1=" + sha1Hex(Files.readAllBytes(jar)));
                future.complete(jar);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.complete(null);
            } catch (Exception failure) {
                if (FETCH_FAILURE_LOGGED.compareAndSet(false, true)) {
                    F2VLog.warn("[vm] vanilla " + MINECRAFT_VERSION + " client jar not fetched ("
                            + failure + "); scanning without it (offline? set -Df2v.vanillaJar)");
                }
                future.complete(null);
            }
        }, "f2v-vanilla-client-fetch");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The cached client jar under {@code userDir/cache} or {@code userDir/srv/cache}, waiting for an
     * in-flight fetch up to {@link #WAIT} the first time it is needed. Returns null when no jar is
     * available; a timed-out wait is logged once and never repeated.
     */
    public static Path awaitClientJar(Path userDir) {
        for (Path base : new Path[] {userDir.resolve("cache"), userDir.resolve("srv").resolve("cache")}) {
            Path jar = base.resolve(CACHE_FILE);
            if (Files.isRegularFile(jar)) return jar;
        }
        CompletableFuture<Path> future = PENDING.get();
        if (future == null || future.isDone() || WAIT_GAVE_UP.get()) return null;
        try {
            Path jar = future.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return jar != null && Files.isRegularFile(jar) ? jar : null;
        } catch (TimeoutException timedOut) {
            if (WAIT_GAVE_UP.compareAndSet(false, true)) {
                F2VLog.warn("[vm] vanilla client jar still downloading after " + WAIT.toSeconds()
                        + "s; scanning without it");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failed) {
            return null;
        }
        return null;
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String requireString(JsonObject o, String key) {
        String s = optString(o, key);
        if (s == null || s.isBlank()) throw new IllegalArgumentException("missing " + key);
        return s;
    }
}
