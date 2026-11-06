package dev.cim.hud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Wires the player-facing translation channels into the plugin. */
public final class HudBootstrap {

    private static ScreenHud screens;
    private static HudService hud;

    private HudBootstrap() {
    }

    public static void closeForHarness(org.bukkit.entity.Player player) {
        if (screens != null && player != null) {
            screens.close(player);
        }
    }

    /** The gallery's ours leg: shows the menu it recorded, to the harness account too. */
    public static boolean openForHarness(org.bukkit.entity.Player player, String menuClass) {
        if (screens == null) {
            return false;
        }
        hud.show(player);
        return screens.openMenuClass(player, menuClass);
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
        Minimap minimap = new Minimap();
        hud.register(minimap);
        hud.register(keyboard);
        screens = new ScreenHud(plugin.getLogger(), Path.of(System.getProperty("user.dir"), "mods"));
        plugin.getServer().getPluginManager().registerEvents(screens, plugin);
        hud.register(screens);
        HudBootstrap.hud = hud;
        PluginCommand screen = plugin.getCommand("modscreen");
        if (screen != null) {
            screen.setExecutor(screens);
        }
        PluginCommand map = plugin.getCommand("minimap");
        if (map != null) {
            map.setExecutor(minimap);
        }
        if (Boolean.getBoolean("cim.hud.probe")) {
            hud.register(HudService.anchorProbe());
        }
        if (Boolean.getBoolean("cim.hud.debug")) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> plugin.getLogger().info(
                    "[cim-hud] frames=" + hud.sentFrames()), 200L, 200L);
        }
        hud.start(plugin);
    }
}
