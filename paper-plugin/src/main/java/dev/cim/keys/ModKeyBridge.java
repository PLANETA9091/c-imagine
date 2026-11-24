package dev.cim.keys;

import dev.cim.hud.KeyCensus;
import dev.cim.hud.VirtualKeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Routes virtual key presses to the mod that owns the binding.
 *
 * <p>The keyboard HUD fires a {@link VirtualKeyEvent} with the census bindings the key carries.
 * Each binding name (for example {@code keybinds.combat_roll.roll}) maps to one
 * {@link ModKeyAdapter}; a key with no adapter is logged once at debug and otherwise ignored.
 * It also tracks each player's movement keys from {@link PlayerInputEvent} for the adapters
 * that need them.
 */
public final class ModKeyBridge implements Listener {

    private final Logger log;
    private final Map<String, ModKeyAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<UUID, MovementInput> movement = new ConcurrentHashMap<>();
    private final Set<String> reportedUnbound = ConcurrentHashMap.newKeySet();

    public ModKeyBridge(Logger log) {
        this.log = log;
    }

    /** Registers the adapter for one binding name; a second registration for the same name is an error. */
    public void register(String keyName, ModKeyAdapter adapter) {
        if (adapters.putIfAbsent(keyName, adapter) != null) {
            throw new IllegalStateException("two adapters for " + keyName);
        }
    }

    public boolean isRegistered(String keyName) {
        return adapters.containsKey(keyName);
    }

    /** The player's last reported movement keys, or {@link MovementInput#NONE}. */
    public MovementInput movement(Player player) {
        return movement.getOrDefault(player.getUniqueId(), MovementInput.NONE);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onVirtualKey(VirtualKeyEvent event) {
        if (!event.isDown()) {
            return;
        }
        for (KeyCensus.ModKey binding : event.getBindings()) {
            ModKeyAdapter adapter = adapters.get(binding.id());
            if (adapter == null) {
                if (reportedUnbound.add(binding.id())) {
                    log.fine("[cim-key] no adapter for " + binding.id() + " (first press by " + event.getPlayer().getName() + ")");
                }
                continue;
            }
            adapter.press(event.getPlayer(), event);
        }
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        movement.put(event.getPlayer().getUniqueId(), MovementInput.of(event.getInput()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        movement.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Wires the bridge into the plugin. The Combat Roll adapter is registered only when a
     * combat_roll jar is in the layer's mods folder.
     */
    public static ModKeyBridge enable(Plugin plugin, Path modsDir) {
        ModKeyBridge bridge = new ModKeyBridge(plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        if (hasMod(modsDir, "combat_roll")) {
            CombatRollAdapter roll = new CombatRollAdapter(plugin, bridge);
            bridge.register(CombatRollAdapter.KEY, roll);
            Bukkit.getPluginManager().registerEvents(roll, plugin);
            roll.start();
            plugin.getLogger().info("[cim-key] Combat Roll adapter active on " + CombatRollAdapter.KEY);
        }
        return bridge;
    }

    /** True when a jar named {@code <modId>-*.jar} is in the mods folder. */
    static boolean hasMod(Path modsDir, String modId) {
        if (!Files.isDirectory(modsDir)) {
            return false;
        }
        try (Stream<Path> jars = Files.list(modsDir)) {
            return jars.map(p -> p.getFileName().toString())
                    .anyMatch(n -> n.startsWith(modId + "-") && n.endsWith(".jar"));
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
