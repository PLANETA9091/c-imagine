package dev.f2v.vm.discovery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Locates mod archives that F2V may inspect without classloading them. Ordinary server mods live
 * in {@code mods/}; truly client-only mods can be dropped into the server-root {@code clientmods/}
 * directory so Forge/NeoForge never attempts to load them on the dedicated server.
 */
public final class ClientModSources {

    public record Source(Path path, boolean sidecar) {}

    private ClientModSources() {}

    public static Path sidecarDir(Path modsDir) {
        Path root = modsDir == null ? null : modsDir.toAbsolutePath().normalize().getParent();
        if (root == null) root = Path.of(".").toAbsolutePath().normalize();
        return root.resolve("clientmods");
    }

    static Path legacySidecarDir(Path modsDir) {
        Path root = modsDir == null ? null : modsDir.toAbsolutePath().normalize().getParent();
        if (root == null) root = Path.of(".").toAbsolutePath().normalize();
        return root.resolve("config/f2v/clientmods");
    }

    /** Loaded server JARs first, inert client-side sidecars second. */
    public static List<Source> list(Path modsDir) throws IOException {
        List<Source> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        collect(modsDir, false, out, seen);

        Path sidecars = sidecarDir(modsDir);
        try {
            Files.createDirectories(sidecars);
            collect(sidecars, true, out, seen);
        } catch (IOException ignored) {
            // An optional sidecar directory must never stop discovery of the real server mods.
        }
        Path legacySidecars = legacySidecarDir(modsDir);
        if (!legacySidecars.equals(sidecars)) {
            try {
                collect(legacySidecars, true, out, seen);
            } catch (IOException ignored) {
                // Existing installations may still have sidecars under config/f2v/clientmods.
            }
        }
        out.sort(Comparator.comparing((Source source) -> source.sidecar() ? 1 : 0)
                .thenComparing(source -> source.path().getFileName().toString()));
        return List.copyOf(out);
    }

    public static List<Path> allJars(Path modsDir) throws IOException {
        return list(modsDir).stream().map(Source::path).toList();
    }

    private static void collect(Path directory, boolean sidecar, List<Source> out, Set<Path> seen)
            throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return;
        try (var stream = Files.list(directory)) {
            List<Path> jars = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path jar : jars) {
                Path normalized = jar.toAbsolutePath().normalize();
                if (seen.add(normalized)) out.add(new Source(normalized, sidecar));
            }
        }
    }
}
