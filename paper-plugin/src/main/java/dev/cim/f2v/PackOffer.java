package dev.cim.f2v;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Where the F2V pack is bound, which URL is offered to players, and whether
 * it is offered on join. Pure functions (no Paper types) so the selftest runs
 * without a server.
 *
 * <p>Knobs (system property wins over environment; a blank value means unset):
 * <ul>
 *   <li>{@code cim.f2v.bind} / {@code CIM_F2V_BIND} — bind address, default
 *       {@value #DEFAULT_BIND} so LAN and remote clients can fetch the pack.</li>
 *   <li>{@code cim.f2v.publicUrl} / {@code CIM_F2V_PUBLIC_URL} — the URL the
 *       client downloads. Fallback chain: {@code server-ip} from
 *       server.properties, then the host's outbound address, then loopback.</li>
 *   <li>{@code cim.f2v.autoOffer} / {@code CIM_F2V_AUTO_OFFER} — offer on
 *       PlayerJoinEvent (default true). Skipped when server.properties already
 *       sets {@code resource-pack}: the server offers it in the config phase.</li>
 *   <li>{@code cim.f2v.autoOfferRequired} / {@code CIM_F2V_AUTO_OFFER_REQUIRED}
 *       — the {@code required} flag of the join offer (default true).</li>
 * </ul>
 */
public final class PackOffer {

    public static final String DEFAULT_BIND = "0.0.0.0";
    public static final String LOOPBACK = "127.0.0.1";

    private PackOffer() {}

    /** Boolean knob: system property first, then environment, then {@code def}. */
    public static boolean flag(String sysProp, String envValue, boolean def) {
        String v = text(sysProp, envValue, null);
        if (v == null) {
            return def;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> def;
        };
    }

    /** String knob: system property first, then environment, then {@code def}. Blank = unset. */
    public static String text(String sysProp, String envValue, String def) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) {
            v = envValue;
        }
        return v == null || v.isBlank() ? def : v.trim();
    }

    /** True for the wildcard / unset server-ip values (bind-all, not a host). */
    public static boolean isAnyLocal(String host) {
        return host == null || host.isBlank() || "0.0.0.0".equals(host.trim())
                || "::".equals(host.trim()) || "[::]".equals(host.trim());
    }

    /**
     * Why the join offer is off, or {@code null} when it is on.
     * {@code serverProps} is the parsed server.properties (may be empty).
     */
    public static String autoOfferSkipReason(boolean knob, Properties serverProps) {
        if (!knob) {
            return "cim.f2v.autoOffer=false";
        }
        String rp = serverProps == null ? null : serverProps.getProperty("resource-pack");
        if (rp != null && !rp.isBlank()) {
            return "server.properties resource-pack is set";
        }
        return null;
    }

    /**
     * The URL clients download: {@code explicit} verbatim when set, else
     * {@code http://<server-ip | host-guess | 127.0.0.1>:<port>/cim-f2v-pack.zip}.
     */
    public static String publicUrl(String explicit, String serverIp, String hostGuess, int port) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String host;
        if (!isAnyLocal(serverIp)) {
            host = serverIp.trim();
        } else if (hostGuess != null && !hostGuess.isBlank()) {
            host = hostGuess.trim();
        } else {
            host = LOOPBACK;
        }
        return url(host, port);
    }

    /** {@code http://host:port/cim-f2v-pack.zip}; IPv6 literals are bracketed. */
    public static String url(String host, int port) {
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + PackHttp.PACK_PATH;
    }

    /**
     * The host's outbound IPv4/IPv6 address, found by a routing lookup. A UDP
     * {@code connect} sends no packets. Null when there is no route (offline).
     */
    public static String guessHostAddress() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("192.0.2.1"), 9); // TEST-NET-1, never contacted
            InetAddress a = s.getLocalAddress();
            if (a == null || a.isAnyLocalAddress() || a.isLoopbackAddress()) {
                return null;
            }
            String h = a.getHostAddress();
            int scope = h.indexOf('%');
            return scope >= 0 ? h.substring(0, scope) : h;
        } catch (SocketException | java.net.UnknownHostException e) {
            return null;
        }
    }

    /** Parsed server.properties; an absent or unreadable file yields empty properties. */
    public static Properties readServerProperties(Path file) {
        Properties p = new Properties();
        if (file == null || !Files.isRegularFile(file)) {
            return p;
        }
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return new Properties();
        }
        return p;
    }

    // ---- selftest -------------------------------------------------------------

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--selftest")) {
            System.exit(selftest() ? 0 : 1);
        }
        System.err.println("usage: PackOffer --selftest");
        System.exit(2);
    }

    static boolean selftest() {
        int fail = 0;
        // the property names below are never set by the selftest, so env alone decides
        String unset = "cim.f2v.selftest.unset";
        fail += check("flag env true", flag(unset, "true", false), true);
        fail += check("flag env off", flag(unset, "off", true), false);
        fail += check("flag blank -> default", flag(unset, "  ", true), true);
        fail += check("flag junk -> default", flag(unset, "maybe", false), false);
        fail += check("text blank -> default", text(unset, "  ", DEFAULT_BIND), DEFAULT_BIND);
        fail += check("bind default", DEFAULT_BIND, "0.0.0.0");
        fail += check("anyLocal 0.0.0.0", isAnyLocal("0.0.0.0"), true);
        fail += check("anyLocal blank", isAnyLocal(""), true);
        fail += check("anyLocal host", isAnyLocal("10.0.0.5"), false);
        fail += check("url loopback", url(LOOPBACK, 25580), "http://127.0.0.1:25580/cim-f2v-pack.zip");
        fail += check("url ipv6 bracket", url("fd00::7", 25580), "http://[fd00::7]:25580/cim-f2v-pack.zip");
        fail += check("publicUrl explicit wins",
                publicUrl("https://packs.example/p.zip", "10.0.0.5", "192.168.1.9", 25580),
                "https://packs.example/p.zip");
        fail += check("publicUrl server-ip",
                publicUrl(null, "10.0.0.5", "192.168.1.9", 25580), "http://10.0.0.5:25580/cim-f2v-pack.zip");
        fail += check("publicUrl guess when server-ip unset",
                publicUrl(null, "", "192.168.1.9", 25580), "http://192.168.1.9:25580/cim-f2v-pack.zip");
        fail += check("publicUrl server-ip 0.0.0.0 falls to guess",
                publicUrl(null, "0.0.0.0", "192.168.1.9", 25580), "http://192.168.1.9:25580/cim-f2v-pack.zip");
        fail += check("publicUrl loopback when offline",
                publicUrl(null, "", null, 25580), "http://127.0.0.1:25580/cim-f2v-pack.zip");
        Properties on = new Properties();
        Properties pinned = new Properties();
        pinned.setProperty("resource-pack", "http://127.0.0.1:25580/cim-f2v-pack.zip");
        fail += check("autoOffer on by default", autoOfferSkipReason(true, on), null);
        fail += check("autoOffer off by knob", autoOfferSkipReason(false, on), "cim.f2v.autoOffer=false");
        fail += check("autoOffer skipped when pinned", autoOfferSkipReason(true, pinned),
                "server.properties resource-pack is set");
        fail += check("autoOffer blank resource-pack stays on",
                autoOfferSkipReason(true, withPack("  ")), null);
        System.out.println(fail == 0 ? "PackOffer selftest: ALL OK" : "PackOffer selftest: " + fail + " FAILED");
        return fail == 0;
    }

    private static Properties withPack(String v) {
        Properties p = new Properties();
        p.setProperty("resource-pack", v);
        return p;
    }

    private static int check(String name, Object got, Object want) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) {
            System.err.println("FAIL " + name + ": got=" + got + " want=" + want);
            return 1;
        }
        return 0;
    }
}
