package dev.cim.paper;

import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper-side companion of the c-imagine CRUSSTY module.
 *
 * The native module (pre-kernel) owns the heavy lifting; this plugin owns the
 * Paper-visible lifecycle: bridge health reporting, the /cim command, and the
 * attach point where M3's state bridge will register Paper event listeners.
 *
 * Deliberately minimal API usage (plain Bukkit surface) so it compiles against
 * every 26.3 API build.
 */
public final class CimPlugin extends JavaPlugin {

    public static final String LOG_PREFIX = "[c-imagine] ";

    private Logger log() {
        return getLogger();
    }

    @Override
    public void onEnable() {
        log().info(LOG_PREFIX + "paper plugin enabled (bridge lifecycle owner)");
        log().info(LOG_PREFIX + "status: module=native (CRUSSTY), layer=M0 skeleton");
    }

    @Override
    public void onDisable() {
        log().info(LOG_PREFIX + "paper plugin disabled");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(LOG_PREFIX + "c-imagine 0.1.0 (M0)");
        sender.sendMessage(LOG_PREFIX + "kernel: Paper 26.3 | layer: mini NeoForge 1.21.1 (skeleton)");
        sender.sendMessage(LOG_PREFIX + "native module: loaded by CRUSSTY runtime before kernel classes");
        return true;
    }
}
