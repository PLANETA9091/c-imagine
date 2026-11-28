package dev.f2v.vm.discovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Selftest for {@link VanillaClientJar}. No arguments: fixture tests only (no network).
 * {@code fetch <serverDir>}: one real download into {@code <serverDir>/cache}, prints the sha1.
 */
public final class VanillaClientJarSelfTest {
    private VanillaClientJarSelfTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("fetch")) {
            Path jar = VanillaClientJar.ensure(Path.of(args[1]).toAbsolutePath().resolve("cache"),
                    VanillaClientJar.defaultHttp());
            System.out.println("cached: " + jar);
            System.out.println("sha1: " + VanillaClientJar.sha1Hex(Files.readAllBytes(jar)));
            return;
        }
        int failures = 0;
        byte[] clientBytes = "PK\u0003\u0004 fixture client jar".getBytes(StandardCharsets.ISO_8859_1);
        String clientSha = VanillaClientJar.sha1Hex(clientBytes);
        String versionJson = "{\"id\":\"1.21.1\",\"downloads\":{\"client\":{\"sha1\":\"" + clientSha
                + "\",\"size\":" + clientBytes.length + ",\"url\":\"https://fixture.invalid/client.jar\"}}}";
        byte[] versionBytes = versionJson.getBytes(StandardCharsets.UTF_8);
        String manifest = "{\"versions\":[{\"id\":\"1.21\",\"url\":\"https://fixture.invalid/1.21.json\",\"sha1\":\"00\"},"
                + "{\"id\":\"1.21.1\",\"url\":\"https://fixture.invalid/1.21.1.json\",\"sha1\":\""
                + VanillaClientJar.sha1Hex(versionBytes) + "\"}]}";

        VanillaClientJar.Ref ref = VanillaClientJar.parseManifest(manifest, "1.21.1");
        failures += check("manifest picks 1.21.1 url", "https://fixture.invalid/1.21.1.json".equals(ref.url()));
        VanillaClientJar.Ref dl = VanillaClientJar.parseClientDownload(versionJson);
        failures += check("client download sha1", clientSha.equals(dl.sha1()));
        failures += check("client download size", dl.size() == clientBytes.length);
        failures += check("sha1 verify accepts good bytes", VanillaClientJar.sha1Matches(clientBytes, clientSha));
        failures += check("sha1 verify rejects bad bytes", !VanillaClientJar.sha1Matches(new byte[] {1}, clientSha));

        Path dir = Path.of("target", "vanilla-client-selftest").toAbsolutePath();
        deleteTree(dir);
        Map<String, byte[]> served = new HashMap<>();
        served.put(VanillaClientJar.MANIFEST_URL, manifest.getBytes(StandardCharsets.UTF_8));
        served.put("https://fixture.invalid/1.21.1.json", versionBytes);
        served.put("https://fixture.invalid/client.jar", clientBytes);
        Path cache = dir.resolve("cache");
        Path jar = VanillaClientJar.ensure(cache, fixtureHttp(served));
        failures += check("ensure writes cache file", Files.isRegularFile(jar) && jar.getFileName().toString()
                .equals(VanillaClientJar.CACHE_FILE));
        failures += check("cached bytes match", clientSha.equals(VanillaClientJar.sha1Hex(Files.readAllBytes(jar))));
        failures += check("no temp files left", Files.list(cache).allMatch(p -> p.getFileName().toString()
                .equals(VanillaClientJar.CACHE_FILE)));

        served.put("https://fixture.invalid/client.jar", "tampered".getBytes(StandardCharsets.UTF_8));
        Path tamperDir = dir.resolve("tampered");
        boolean rejected = false;
        try {
            VanillaClientJar.ensure(tamperDir.resolve("cache"), fixtureHttp(served));
        } catch (IOException expected) {
            rejected = true;
        }
        failures += check("sha1 mismatch rejected, nothing cached",
                rejected && !Files.exists(tamperDir.resolve("cache").resolve(VanillaClientJar.CACHE_FILE)));

        System.out.println(failures == 0 ? "VanillaClientJarSelfTest OK" : "VanillaClientJarSelfTest FAILED: " + failures);
        if (failures != 0) System.exit(1);
    }

    private static VanillaClientJar.Http fixtureHttp(Map<String, byte[]> served) {
        return url -> {
            byte[] body = served.get(url);
            if (body == null) throw new IOException("no fixture for " + url);
            return body;
        };
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        return ok ? 0 : 1;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
