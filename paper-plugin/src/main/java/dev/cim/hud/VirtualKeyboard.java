package dev.cim.hud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * {@code /keyboard}: a keyboard floating on the HUD.
 *
 * <p>A vanilla client never tells the server about a key the server has no packet
 * for, so a mod's bindings have no way in. This draws keycaps over the game with
 * no screen open: the world stays sharp, the player keeps walking, and the look
 * direction moves a pointer that attack and use packets click. Keycaps go out as
 * one text run per row, so a frame stays small.
 */
public final class VirtualKeyboard implements Listener, CommandExecutor, HudService.Layer {

    private static final class Session {
        final KeyboardState state;
        ScreenHud.Pointer pointer;
        boolean onBindings;
        int page;
        boolean open;
        long lastClick = -1;

        Session(List<KeyCensus.ModKey> census) {
            state = new KeyboardState(census);
        }
    }

    private final Logger log;
    private final List<KeyCensus.ModKey> census;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public VirtualKeyboard(Logger log, List<KeyCensus.ModKey> census) {
        this.log = log;
        this.census = census;
    }

    /** True while a player's clicks belong to the keyboard. */
    public boolean isOpen(Player player) {
        Session s = sessions.get(player.getUniqueId());
        return s != null && s.open;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // "execute as" and command blocks arrive as a proxy around the player.
        CommandSender target = sender instanceof ProxiedCommandSender proxy ? proxy.getCallee() : sender;
        if (!(target instanceof Player player)) {
            sender.sendMessage("Only a player has a screen to show the keyboard on.");
            return true;
        }
        Session s = session(player);
        s.open = !s.open;
        if (s.open) {
            s.pointer = new ScreenHud.Pointer(213, 118, player.getYaw(), player.getPitch());
        }
        log.info("[cim-key] " + player.getName() + " keyboard " + (s.open ? "open" : "closed"));
        return true;
    }

    private List<KeyboardLayout.Cap> caps(Session s) {
        return s.onBindings ? KeyboardLayout.bindings(census, s.page) : KeyboardLayout.keys();
    }

    private boolean lit(Session s, KeyboardLayout.Cap cap) {
        int action = cap.action();
        if (action == KeyboardState.CELL_HOLD) {
            return s.state.holdMode();
        }
        if (action <= KeyboardLayout.BINDING) {
            int key = census.get(KeyboardLayout.BINDING - action).defaultKey();
            return key >= 0 && s.state.isHeld(key);
        }
        return action >= 0 && s.state.isHeld(action);
    }

    private static boolean wide(KeyboardLayout.Cap cap) {
        return cap.w() != KeyboardLayout.KEY_W;
    }

    @Override
    public void draw(Player player, HudFrame frame) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        if (!s.open) {
            // A latched key outlives the keyboard, so the HUD keeps showing it.
            List<String> names = s.state.heldKeys().stream().map(KeyboardState::keyName).toList();
            if (!names.isEmpty()) {
                frame.text(HudLayout.Anchor.BOTTOM_LEFT, HudLayout.TEXT_ROWS - 1,
                        "Held: " + String.join(" ", names), NamedTextColor.GREEN);
            }
            return;
        }
        s.pointer.look(player.getYaw(), player.getPitch());
        List<KeyboardLayout.Cap> caps = caps(s);
        Map<Integer, List<KeyboardLayout.Cap>> rows = new TreeMap<>();
        for (KeyboardLayout.Cap c : caps) {
            rows.computeIfAbsent(c.y(), y -> new ArrayList<>()).add(c);
        }
        for (var row : rows.entrySet()) {
            List<KeyboardLayout.Cap> line = new ArrayList<>(row.getValue());
            line.sort((a, b) -> a.x() - b.x());
            int start = line.get(0).x();
            int pen = start;
            StringBuilder glyphs = new StringBuilder();
            for (KeyboardLayout.Cap c : line) {
                glyphs.append(HudFrame.move(c.x() - pen)).append(wide(c) ? HudPack.KEY_WIDE : HudPack.KEY);
                pen = c.x() + c.w() + 1;
            }
            frame.free(start, row.getKey(), glyphs.toString(), pen - start);
        }
        for (KeyboardLayout.Cap c : caps) {
            if (lit(s, c)) {
                frame.free(c.x(), c.y(), String.valueOf(wide(c) ? HudPack.KEY_WIDE_LIT : HudPack.KEY_LIT), c.w() + 1);
            }
        }
        for (var row : rows.entrySet()) {
            List<KeyboardLayout.Cap> line = new ArrayList<>(row.getValue());
            line.sort((a, b) -> a.x() - b.x());
            int start = line.get(0).x();
            int pen = start;
            StringBuilder text = new StringBuilder();
            for (KeyboardLayout.Cap c : line) {
                int w = HudText.width(c.label());
                int at = c.x() + (c.w() - w) / 2 + 1;
                text.append(HudFrame.move(at - pen)).append(c.label());
                pen = at + w;
            }
            frame.freeRun(start, row.getKey() + 3, text.toString(), pen - start);
        }
        KeyboardLayout.Cap hover = KeyboardLayout.at(caps, s.pointer.x(), s.pointer.y());
        if (hover != null) {
            frame.free(hover.x(), hover.y(), String.valueOf(wide(hover) ? HudPack.KEY_WIDE_HOVER : HudPack.KEY_HOVER),
                    hover.w() + 1);
        }
        frame.free(s.pointer.x(), s.pointer.y(), String.valueOf(HudPack.CURSOR), 9);
    }

    /** One physical click can reach the server as both a swing and a use. */
    private void click(Player player, Session s) {
        long now = Bukkit.getCurrentTick();
        if (now == s.lastClick) {
            return;
        }
        s.lastClick = now;
        KeyboardLayout.Cap cap = KeyboardLayout.at(caps(s), s.pointer.x(), s.pointer.y());
        if (cap == null) {
            return;
        }
        int action = cap.action();
        if (action == KeyboardLayout.CLOSE) {
            s.open = false;
            log.info("[cim-key] " + player.getName() + " keyboard closed");
        } else if (action == KeyboardState.CELL_HOLD) {
            s.state.toggleHoldMode();
        } else if (action == KeyboardState.CELL_PAGE) {
            s.onBindings = !s.onBindings;
            s.page = 0;
        } else if (action == KeyboardLayout.NEXT) {
            s.page = (s.page + 1) % KeyboardLayout.pages(census.size());
        } else if (action <= KeyboardLayout.BINDING) {
            pressBinding(player, s, census.get(KeyboardLayout.BINDING - action));
        } else {
            deliver(player, s.state.click(action));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(PlayerAnimationEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && s.open) {
            event.setCancelled(true);
            click(event.getPlayer(), s);
        }
    }

    /** While open the hands are on the keyboard: nothing is mined, placed or used. */
    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && s.open) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND
                    && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                click(event.getPlayer(), s);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            deliver(event.getPlayer(), s.state.releaseAll());
        }
    }

    private Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), u -> new Session(census));
    }

    /** A binding whose default is not on the board has nothing to hold, so it taps. */
    private void pressBinding(Player player, Session s, KeyCensus.ModKey binding) {
        if (KeyboardState.LAYOUT.stream().anyMatch(c -> c.key() == binding.defaultKey())) {
            deliver(player, s.state.click(binding.defaultKey()));
            return;
        }
        List<KeyCensus.ModKey> only = List.of(binding);
        deliver(player, List.of(
                new KeyboardState.Transition(-1, true, s.state.modifiers(), only),
                new KeyboardState.Transition(-1, false, s.state.modifiers(), only)));
    }

    private void deliver(Player player, List<KeyboardState.Transition> steps) {
        for (KeyboardState.Transition t : steps) {
            Bukkit.getPluginManager().callEvent(new VirtualKeyEvent(player, t));
            log.info("[cim-key] " + player.getName() + " key=" + t.key() + (t.down() ? " down" : " up")
                    + " mods=" + t.modifiers() + " bindings="
                    + t.bindings().stream().map(KeyCensus.ModKey::id).toList());
        }
    }

    /** Test harness: turns the look so the pointer lands on the cap with this action. */
    public String aim(Player player, int action) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || !s.open) {
            return "keyboard closed";
        }
        KeyboardLayout.Cap cap = caps(s).stream().filter(c -> c.action() == action).findFirst().orElse(null);
        if (cap == null) {
            return "no cap " + action;
        }
        Location at = player.getLocation();
        at.setYaw(player.getYaw() + (float) ((cap.x() + cap.w() / 2 - s.pointer.x()) / ScreenHud.SENSITIVITY));
        at.setPitch(player.getPitch() + (float) ((cap.y() + 7 - s.pointer.y()) / ScreenHud.SENSITIVITY));
        player.teleport(at);
        return "aimed cap " + cap.label();
    }
}
