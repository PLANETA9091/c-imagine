package dev.cim.hud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires the player-facing translation channels into the plugin. */
public final class HudBootstrap {

    private HudBootstrap() {
    }

    public static void enable(JavaPlugin plugin) {
        List<KeyCensus.ModKey> keys;
        try {
            keys = KeyCensus.scan(Path.of(System.getProperty("user.dir"), "mods"));
        } catch (IOException e) {
            plugin.getLogger().warning("[cim-key] key census failed, keyboard shows no mod keys: " + e);
            keys = List.of();
        }
        plugin.getLogger().info("[cim-key] census: " + keys.size() + " mod key(s)");
        VirtualKeyboard keyboard = new VirtualKeyboard(plugin.getLogger(), keys);
        plugin.getServer().getPluginManager().registerEvents(keyboard, plugin);
        PluginCommand command = plugin.getCommand("keyboard");
        if (command != null) {
            command.setExecutor(keyboard);
        }
        HudService hud = new HudService();
        hud.register(keyboard);
        if (Boolean.getBoolean("cim.hud.debug")) {
            hud.register(HudService.anchorProbe());
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> plugin.getLogger().info(
                    "[cim-hud] frames=" + hud.sentFrames()), 200L, 200L);
        }
        hud.start(plugin);
    }
}
