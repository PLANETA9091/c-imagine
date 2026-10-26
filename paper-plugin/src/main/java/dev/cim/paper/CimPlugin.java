package dev.cim.paper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper-side companion of the c-imagine CRUSSTY module.
 *
 * <p>The native module (pre-kernel) owns the heavy lifting; this plugin owns
 * the Paper-visible lifecycle: bridge health reporting, the /cim command, and
 * the attach point where M3's state bridge will register Paper event
 * listeners.
 *
 * <p>The module persists its combined boot status to
 * {@code <server root>/c-imagine/status.json} — the file is the M2 module→
 * plugin bridge (no cross-loader JVM tricks needed). /cim status renders it;
 * M3 replaces the file with the cim-bridge ring transport.
 *
 * <p>Deliberately minimal API usage (plain Bukkit surface) so it compiles
 * against every 26.3 API build.
 */
public final class CimPlugin extends JavaPlugin {

    public static final String LOG_PREFIX = "[c-imagine] ";

    private Logger log() {
        return getLogger();
    }

    @Override
    public void onEnable() {
        log().info(LOG_PREFIX + "paper plugin enabled (bridge lifecycle owner)");
        log().info(LOG_PREFIX + "status: module=native (CRUSSTY), layer=mini NeoForge 1.21.1");
    }

    @Override
    public void onDisable() {
        log().info(LOG_PREFIX + "paper plugin disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(LOG_PREFIX + "c-imagine 0.1.0 (M2)");
        sender.sendMessage(LOG_PREFIX + "kernel: Paper 26.3 | layer: mini NeoForge 1.21.1");
        sender.sendMessage(LOG_PREFIX + "native module: loaded by CRUSSTY runtime before kernel classes");
        String[] status = readStatusLines();
        for (String line : status) {
            sender.sendMessage(LOG_PREFIX + line);
        }
        return true;
    }

    /**
     * Read the module's persisted boot status. Expected shape (M2):
     *
     * <pre>{@code
     * {"init":{"state":"ok","layer":{"state":"ok","registries":78,...}},
     *  "mods":{"state":"ok","mods":8,"registered":{...},"moddedEntries":{...}}}
     * }</pre>
     *
     * Rendered with tiny hand-rolled extraction (no JSON lib on the Paper
     * side): a regex over the known fields keeps the plugin dependency-free.
     */
    private String[] readStatusLines() {
        Path file = Path.of(System.getProperty("user.dir"), "c-imagine", "status.json");
        if (!Files.isRegularFile(file)) {
            return new String[] {"status: module has not written status.json yet"
                    + " (still booting or failed before ready)"};
        }
        try {
            String json = Files.readString(file);
            return new String[] {
                "status: " + extract(json, "ready"),
                "layer: " + extractLayer(json),
                "mods: " + extractMods(json),
                "detail: " + file,
            };
        } catch (Exception e) {
            return new String[] {"status: unreadable (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")"};
        }
    }

    /** "ok"/"error" top-level mood of the combined status. */
    private String extract(String json, String unused) {
        boolean initOk = json.contains("\"init\":{\"state\":\"ok\"");
        boolean modsOk = json.contains("\"mods\":{\"state\":\"ok\"");
        return initOk && modsOk ? "ok" : (initOk ? "init ok, mods degraded" : "degraded");
    }

    private String extractLayer(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"registries\":(\\d+)").matcher(json);
        return m.find()
                ? "booted, vanilla registries=" + m.group(1)
                : "booted";
    }

    private String extractMods(String json) {
        java.util.regex.Matcher count = java.util.regex.Pattern
                .compile("\"mods\":(\\d+),\"registered\"").matcher(json);
        java.util.regex.Matcher entries = java.util.regex.Pattern
                .compile("\"moddedEntries\":\\{([^}]*)\\}").matcher(json);
        String head = count.find() ? count.group(1) + " mods executed" : "mods unknown";
        return head + (entries.find() ? ", modded entries: " + entries.group(1) : "");
    }
}
