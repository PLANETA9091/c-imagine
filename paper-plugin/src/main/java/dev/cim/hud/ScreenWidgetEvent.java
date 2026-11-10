package dev.cim.hud;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A click landed on a scanned button. Delivery to the mod listens here. */
public final class ScreenWidgetEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String screen;
    private final String label;

    public ScreenWidgetEvent(Player player, String screen, String label) {
        this.player = player;
        this.screen = screen;
        this.label = label;
    }

    public Player getPlayer() {
        return player;
    }

    public String getScreen() {
        return screen;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
