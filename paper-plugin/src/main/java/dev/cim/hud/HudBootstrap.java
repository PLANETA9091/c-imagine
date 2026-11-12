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
    private static VirtualKeyboard keyboard;
    private static CameraLock camera;

    /** Nobody may stay on a seat that outlives the plugin. */
    public static void disable() {
        if (camera != null) {
            camera.releaseAll();
        }
    }

    private HudBootstrap() {
    }

    public static String aimForHarness(org.bukkit.entity.Player player, String target) {
        if (screens == null || player == null) {
            return "no hud";
        }
        return target.startsWith("k") ? keyboard.aim(player, Integer.parseInt(target.substring(1)))
                : screens.aim(player, target);
    }

    public static String pressForHarness(org.bukkit.entity.Player player, String what) {
        if (screens == null || player == null) {
            return "no hud";
        }
        return what.equals("move") ? screens.move(player) : screens.press(player, Integer.parseInt(what));
    }

    public static java.util.List<String> listForHarness() {
        return screens == null ? java.util.List.of() : screens.listForHarness();
    }

    public static boolean openNamedForHarness(org.bukkit.entity.Player player, String name) {
        return screens != null && player != null && screens.openNamed(player, name);
    }

    /** Integration: live menus on Paper end to end, see {@link ScreenHud#liveSelfTest}. */
    public static java.util.List<String> liveSelfTest(org.bukkit.entity.Player player, String itemId) {
        return screens == null ? java.util.List.of("no screens") : screens.liveSelfTest(player, itemId);
    }

    /** Whether the live button scan has finished (integration waits for it off the server thread). */
    public static boolean liveScanDone() {
        return screens == null || screens.liveScanDone();
    }

    /** Integration: a mod item placed by its own code, then opened as a block, see {@link ScreenHud#livePlaceTest}. */
    public static java.util.List<String> livePlaceTest(org.bukkit.entity.Player player, String itemId) {
        return screens == null ? java.util.List.of("no screens") : screens.livePlaceTest(player, itemId);
    }

    /** Integration: block menus on Paper end to end, see {@link ScreenHud#liveBlockTest}. */
    public static java.util.List<String> liveBlockTest(org.bukkit.entity.Player player, String blockId) {
        return screens == null ? java.util.List.of("no screens") : screens.liveBlockTest(player, blockId);
    }

    public static void closeForHarness(org.bukkit.entity.Player player) {
        if (screens != null && player != null) {
            screens.close(player);
        }
    }

    /** The gallery's ours leg: shows the menu it recorded, to the harness account too. */
    public static boolean openForHarness(org.bukkit.entity.Player player, String menuId, String menuClass,
                                         String heldId) {
        return openForHarness(player, menuId, menuClass, heldId, null);
    }

    /**
     * The gallery's ours leg with the opener the walk used: when the layer answers, the page's
     * menu is opened by the mod's own code (the held item's use, the block's use), so every
     * press and slot move after it runs the mod's logic; otherwise the recorded screen.
     */
    public static boolean openForHarness(org.bukkit.entity.Player player, String menuId, String menuClass,
                                         String heldId, String opener) {
        if (screens == null) {
            return false;
        }
        hud.show(player);
        player.getInventory().setItemInMainHand(screens.carrier(heldId));
        return screens.openMenuLive(player, menuId, menuClass, heldId, opener) || screens.openMenu(player, menuId, menuClass);
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
        camera = new CameraLock(plugin);
        VirtualKeyboard keyboard = new VirtualKeyboard(plugin.getLogger(), keys, camera);
        plugin.getServer().getPluginManager().registerEvents(keyboard, plugin);
        PluginCommand command = plugin.getCommand("keyboard");
        if (command != null) {
            command.setExecutor(keyboard);
        }
        HudService hud = new HudService();
        hud.register(keyboard);
        HudBootstrap.keyboard = keyboard;
        screens = new ScreenHud(plugin.getLogger(), Path.of(System.getProperty("user.dir"), "mods"));
        plugin.getServer().getPluginManager().registerEvents(screens, plugin);
        hud.register(screens);
        screens.busyWhen(keyboard::isOpen);
        screens.useCamera(camera, plugin);
        HudBootstrap.hud = hud;
        PluginCommand screen = plugin.getCommand("modscreen");
        if (screen != null) {
            screen.setExecutor(screens);
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
