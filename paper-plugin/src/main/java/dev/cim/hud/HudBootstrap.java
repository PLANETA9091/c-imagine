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
        ModMenus menus = new ModMenus();
        plugin.getServer().getPluginManager().registerEvents(menus, plugin);
        PluginCommand menu = plugin.getCommand("modmenu");
        if (menu != null) {
            menu.setExecutor(menus);
        }
        try {
            MenuTriggers triggers = new MenuTriggers(menus, Path.of(System.getProperty("user.dir"), "c-imagine", "f2v"));
            plugin.getServer().getPluginManager().registerEvents(triggers, plugin);
            plugin.getLogger().info("[cim-menu] " + MenuCatalog.MENUS.size() + " menu(s), "
                    + triggers.size() + " carrier(s) known");
        } catch (IOException e) {
            plugin.getLogger().warning("[cim-menu] carrier manifests unreadable, menus open by command only: " + e);
        }
        HudService hud = new HudService();
        Minimap minimap = new Minimap();
        hud.register(minimap);
        hud.register(keyboard);
        PluginCommand map = plugin.getCommand("minimap");
        if (map != null) {
            map.setExecutor(minimap);
        }
        if (Boolean.getBoolean("cim.hud.debug")) {
            hud.register(HudService.anchorProbe());
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> plugin.getLogger().info(
                    "[cim-hud] frames=" + hud.sentFrames()), 200L, 200L);
        }
        hud.start(plugin);
    }
}
