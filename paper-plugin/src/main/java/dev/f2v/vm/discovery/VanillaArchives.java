package dev.f2v.vm.discovery;

import dev.f2v.bridge.F2VLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Finds the vanilla/NeoForge 1.21.1 archives that mod bytecode extends, without any
 * configuration. Resolution order:
 * <ol>
 *   <li>{@code -Df2v.vanillaJar=<file>} when it names an existing file (explicit wins);</li>
 *   <li>the layer's NeoForge 1.21.1 install under the server directory: the Mojang server jar
 *       (srg-mapped, contains the common menus such as CraftingMenu) and the NeoForge universal
 *       jar (NeoForge base classes);</li>
 *   <li>the vanilla 1.21.1 client jar in {@code cache/} (fetched once, see {@link VanillaClientJar});</li>
 *   <li>the Paper 26.3 cache jar, only as a last resort (different version, logged).</li>
 * </ol>
 * Client-only classes (Screen, ConfirmScreen, AbstractContainerScreen ...) come from the vanilla
 * client jar when it is cached. When it is absent (offline) the scan runs without it and logs
 * once; there is no built-in class table.
 */
final class VanillaArchives {
    private VanillaArchives() {}

    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    /** Resolves the archives to add to the cross-jar index for every scan. Logs its decision once. */
    static List<Path> resolve() {
        List<Path> out = new ArrayList<>();
        String explicit = System.getProperty("f2v.vanillaJar");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Path.of(explicit);
            if (Files.isRegularFile(p)) {
                out.add(p);
                logOnce("vanilla archive from -Df2v.vanillaJar: " + p);
                return out;
            }
            F2VLog.warn("[vm] -Df2v.vanillaJar is not a file, falling back to auto-resolve: " + explicit);
        }
        Path userDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        // The vanilla 1.21.1 CLIENT jar (fetched once at boot, see VanillaClientJar) carries the
        // client-only screen classes. It waits for an in-flight download (bounded) before deciding.
        Path clientJar = VanillaClientJar.awaitClientJar(userDir);
        for (Path base : List.of(userDir.resolve("layer1211"), userDir.resolve("srv").resolve("layer1211"))) {
            Path libs = base.resolve("libraries");
            Path serverJar = findServerSrgJar(libs.resolve("net/minecraft/server"));
            if (serverJar == null) continue;
            out.add(serverJar);
            Path universal = findNeoForgeUniversal(libs.resolve("net/neoforged/neoforge"));
            if (universal != null) out.add(universal);
            if (clientJar != null) out.add(clientJar);
            logOnce("vanilla archives auto-resolved (NeoForge 1.21.1 layer): " + out);
            return out;
        }
        if (clientJar != null) {
            out.add(clientJar);
            logOnce("vanilla client archive auto-resolved (1.21.1 cache): " + clientJar);
            return out;
        }
        for (Path base : List.of(userDir.resolve("cache"), userDir.resolve("srv").resolve("cache"))) {
            Path paper = base.resolve("mojang_26.3.jar");
            if (Files.isRegularFile(paper)) {
                out.add(paper);
                logOnce("vanilla archive auto-resolved from Paper cache (26.3, not 1.21.1): " + paper);
                return out;
            }
        }
        if (LOGGED.compareAndSet(false, true)) F2VLog.warn("[vm] no vanilla archive found under " + userDir
                + "; scanning without vanilla (no client jar cached)");
        return out;
    }

    /** {@code libraries/net/minecraft/server/1.21.1-<build>/server-1.21.1-<build>-srg.jar}. */
    private static Path findServerSrgJar(Path serverDir) {
        if (!Files.isDirectory(serverDir)) return null;
        try (Stream<Path> dirs = Files.list(serverDir)) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("1.21.1"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(d -> d.resolve("server-" + d.getFileName() + "-srg.jar"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (IOException failure) {
            return null;
        }
    }

    /** {@code libraries/net/neoforged/neoforge/21.1.*}/neoforge-21.1.*-universal.jar. */
    private static Path findNeoForgeUniversal(Path neoforgeDir) {
        if (!Files.isDirectory(neoforgeDir)) return null;
        try (Stream<Path> dirs = Files.list(neoforgeDir)) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("21.1."))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(d -> d.resolve("neoforge-" + d.getFileName() + "-universal.jar"))
                    .filter(Files::isRegularFile)
                    .findFirst().orElse(null);
        } catch (IOException failure) {
            return null;
        }
    }

    private static void logOnce(String message) {
        if (LOGGED.compareAndSet(false, true)) F2VLog.info("[vm] " + message);
    }
}
