package dev.cim.f2v;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Loopback HTTP server for the generated F2V visual pack.
 *
 * <p>Mirror of F2F v1's {@code PackHttpServer} idea with zero dependencies:
 * the vanilla client must download the pack over http, and in CI / local
 * stands both ends share the host, so a loopback listener is sufficient and
 * exposes nothing to the network. Production deployments would point
 * {@code server.properties} at a real pack host instead.
 *
 * <p>The server serves exactly one fixed payload (the pack zip bytes) at
 * {@code /cim-f2v-pack.zip} — a snapshot taken at start, so the sha1 the
 * client verifies (from {@code server.properties}) always matches what is
 * served even if the builder later regenerates the file on disk.
 */
public final class PackHttp {

    public static final String PACK_PATH = "/cim-f2v-pack.zip";

    private final HttpServer server;
    private final int port;
    private final String sha1;

    private PackHttp(HttpServer server, int port, String sha1) {
        this.server = server;
        this.port = port;
        this.sha1 = sha1;
    }

    /**
     * Start serving {@code packZip} on {@code 127.0.0.1:<port>} (0 = pick an
     * ephemeral port). Returns the running instance.
     */
    public static PackHttp start(Path packZip, int port, String sha1) throws IOException {
        byte[] payload = Files.readAllBytes(packZip);
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext(PACK_PATH, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        // few clients, long-poll style downloads: virtual-thread-friendly pool
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cim-f2v-pack-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return new PackHttp(server, server.getAddress().getPort(), sha1);
    }

    public int port() {
        return port;
    }

    public String sha1() {
        return sha1;
    }

    public String url() {
        return "http://127.0.0.1:" + port + PACK_PATH;
    }

    public void stop() {
        server.stop(0);
    }
}
