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
 * HTTP server for the generated F2V visual pack.
 *
 * <p>Mirror of F2F v1's {@code PackHttpServer} idea with zero dependencies:
 * the vanilla client must download the pack over http. The listener binds
 * {@code bind} (default {@code 0.0.0.0}, so LAN and remote clients can fetch
 * it; {@code 127.0.0.1} keeps it host-local). The URL offered to players is
 * the <em>public</em> URL resolved by {@link PackOffer}; the loopback URL is
 * kept for local probes and the CI serving marker.
 *
 * <p>The server serves exactly one fixed payload (the pack zip bytes) at
 * {@code /cim-f2v-pack.zip} — a snapshot taken at start, so the sha1 the
 * client verifies (from {@code server.properties} or the join offer) always
 * matches what is served even if the builder later regenerates the file.
 */
public final class PackHttp {

    public static final String PACK_PATH = "/cim-f2v-pack.zip";

    private final HttpServer server;
    private final int port;
    private final String bindHost;
    private final String localHost;
    private final String sha1;
    private final String publicUrl;

    private PackHttp(HttpServer server, int port, String bindHost, String localHost,
                     String sha1, String publicUrl) {
        this.server = server;
        this.port = port;
        this.bindHost = bindHost;
        this.localHost = localHost;
        this.sha1 = sha1;
        this.publicUrl = publicUrl;
    }

    /**
     * Start serving {@code packZip} on {@code bind:<port>} (port 0 = pick an
     * ephemeral port). {@code publicUrl} is the URL players are offered.
     * Returns the running instance.
     */
    public static PackHttp start(Path packZip, String bind, int port, String sha1, String publicUrl)
            throws IOException {
        byte[] payload = Files.readAllBytes(packZip);
        InetAddress addr = InetAddress.getByName(bind);
        HttpServer server = HttpServer.create(new InetSocketAddress(addr, port), 0);
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
        int bound = server.getAddress().getPort();
        // a wildcard bind (0.0.0.0 / ::) also answers on loopback
        String local = addr.isAnyLocalAddress() ? PackOffer.LOOPBACK : addr.getHostAddress();
        return new PackHttp(server, bound, addr.getHostAddress(), local, sha1, publicUrl);
    }

    public int port() {
        return port;
    }

    /** The address the listener is bound to (the cim.f2v.bind value, resolved). */
    public String bindHost() {
        return bindHost;
    }

    public String sha1() {
        return sha1;
    }

    /** The URL offered to players (cim.f2v.publicUrl or the resolved fallback). */
    public String url() {
        return publicUrl;
    }

    /**
     * The host-local URL: loopback for a wildcard bind, else the bound address.
     * Used for the {@code f2v pack: serving} marker and host-local probes.
     */
    public String localUrl() {
        return PackOffer.url(localHost, port);
    }

    public void stop() {
        server.stop(0);
    }
}
