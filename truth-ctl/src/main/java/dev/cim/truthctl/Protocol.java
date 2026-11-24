package dev.cim.truthctl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * 127.0.0.1 line protocol: one JSON request per line, one JSON reply per line.
 * Request: {"cmd":"break","args":["10","64","10"],"opts":{"timeout":"20"}}.
 * Port: -Dtruthctl.port (default 25599).
 */
final class Protocol {
    private Protocol() {}

    static void start() {
        Thread t = new Thread(Protocol::serve, "truthctl-accept");
        t.setDaemon(true);
        t.start();
    }

    private static void serve() {
        int port = Integer.getInteger("truthctl.port", 25599);
        ServerSocket ss;
        try {
            ss = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            TruthCtl.LOG.error(TruthCtl.TAG + "cannot bind 127.0.0.1:{}: {}", port, e.toString());
            return;
        }
        TruthCtl.LOG.info(TruthCtl.TAG + "listening on 127.0.0.1:{}", port);
        while (true) {
            try {
                Socket s = ss.accept();
                Thread c = new Thread(() -> handle(s), "truthctl-conn");
                c.setDaemon(true);
                c.start();
            } catch (IOException e) {
                TruthCtl.LOG.warn(TruthCtl.TAG + "accept failed: {}", e.toString());
            }
        }
    }

    private static void handle(Socket s) {
        try (s;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String reply = process(line).toString();
                out.write(reply);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            TruthCtl.LOG.debug(TruthCtl.TAG + "connection closed: {}", e.toString());
        }
    }

    /** Parses one request line, runs it on the main thread, waits for the reply. Never throws. */
    static JsonObject process(String line) {
        JsonObject req;
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) {
                TruthCtl.LOG.info(TruthCtl.TAG + "bad request (not an object), {} bytes", line.length());
                return TruthCtl.err("request must be a JSON object", "bad_request");
            }
            req = el.getAsJsonObject();
        } catch (RuntimeException e) {
            TruthCtl.LOG.info(TruthCtl.TAG + "malformed JSON, {} bytes", line.length());
            return TruthCtl.err("malformed JSON: " + e.getMessage(), "bad_json");
        }
        JsonElement cmdEl = req.get("cmd");
        if (cmdEl == null || !cmdEl.isJsonPrimitive()) {
            return TruthCtl.err("missing cmd", "bad_request");
        }
        String cmd = cmdEl.getAsString();
        List<String> args = new ArrayList<>();
        JsonElement argsEl = req.get("args");
        if (argsEl != null && argsEl.isJsonArray()) {
            for (JsonElement a : (JsonArray) argsEl) {
                args.add(a.isJsonPrimitive() ? a.getAsString() : a.toString());
            }
        }
        JsonObject opts = req.has("opts") && req.get("opts").isJsonObject() ? req.getAsJsonObject("opts") : new JsonObject();
        // "--timeout S" inside args is accepted too (plan table syntax for break).
        for (int i = 0; i < args.size(); i++) {
            if ("--timeout".equals(args.get(i)) && i + 1 < args.size()) {
                opts.addProperty("timeout", args.get(i + 1));
                args.remove(i);
                args.remove(i);
                i--;
            }
        }
        TruthCtl.LOG.info(TruthCtl.TAG + "request cmd={} args={} bytes={}", cmd, args.size(), line.length());

        JsonObject reply;
        try {
            double waitSec = Commands.timeoutSeconds(cmd, args, opts);
            CompletableFuture<JsonObject> f = Minecraft.getInstance()
                    .submit(() -> Commands.dispatch(cmd, args, opts))
                    .get((long) (waitSec * 1000) + 15_000, TimeUnit.MILLISECONDS);
            reply = f.get((long) (waitSec * 1000) + 10_000, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            reply = TruthCtl.err("no reply from client main thread in time", "timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply = TruthCtl.err("interrupted", "exception");
        } catch (java.util.concurrent.ExecutionException e) {
            reply = TruthCtl.err(String.valueOf(e.getCause()), "exception");
        } catch (RuntimeException e) {
            reply = TruthCtl.err(String.valueOf(e), "exception");
        }
        TruthCtl.LOG.info(TruthCtl.TAG + "reply cmd={} bytes={}", cmd, reply.toString().length());
        return reply;
    }
}
