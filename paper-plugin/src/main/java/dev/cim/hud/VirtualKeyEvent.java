package dev.cim.hud;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A virtual key went down or up. Mod bridges listen here for the bindings they own. */
public final class VirtualKeyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final KeyboardState.Transition transition;

    public VirtualKeyEvent(Player player, KeyboardState.Transition transition) {
        this.player = player;
        this.transition = transition;
    }

    public Player getPlayer() {
        return player;
    }

    public int getKey() {
        return transition.key();
    }

    public boolean isDown() {
        return transition.down();
    }

    public int getModifiers() {
        return transition.modifiers();
    }

    public List<KeyCensus.ModKey> getBindings() {
        return transition.bindings();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
