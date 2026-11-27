package dev.cim.keys;

import dev.cim.hud.VirtualKeyEvent;
import org.bukkit.entity.Player;

/**
 * Turns one mod key press into the mod's effect on the server. The bridge calls it on the key's
 * down edge only, on the main thread, for the binding this adapter was registered under.
 */
public interface ModKeyAdapter {

    void press(Player player, VirtualKeyEvent event);
}
