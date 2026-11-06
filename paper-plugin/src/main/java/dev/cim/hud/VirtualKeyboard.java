package dev.cim.hud;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * {@code /keyboard}: a keyboard drawn by the vanilla dialog screen.
 *
 * <p>A vanilla client never tells the server about a key the server has no
 * packet for, so a mod's bindings have no way in. Dialog buttons do: each one
 * answers with a custom click, and {@code after_action: none} keeps the screen
 * up, so typing does not flicker. The dialog is only resent when something it
 * shows changed. {@code pause: false} keeps the world running behind it.
 */
public final class VirtualKeyboard implements Listener, CommandExecutor, HudService.Layer {

    private static final String NS = "c-imagine";
    private static final Key CLOSE = Key.key(NS, "kb/close");
    private static final Key HOLD = Key.key(NS, "kb/hold");
    private static final Key PAGE_KEYS = Key.key(NS, "kb/page/keys");
    private static final Key PAGE_MODS = Key.key(NS, "kb/page/mods");
    private static final String PRESS_PREFIX = "kb/key/";
    private static final String BINDING_PREFIX = "kb/bind/";
    private static final int KEY_WIDTH = 25;
    private static final int BINDING_WIDTH = 190;

    private enum Page { KEYS, MODS }

    private static final class Session {
        final KeyboardState state;
        Page page = Page.MODS;
        boolean open;

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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // "execute as" and command blocks arrive as a proxy around the player.
        CommandSender target = sender instanceof ProxiedCommandSender proxy ? proxy.getCallee() : sender;
        if (!(target instanceof Player player)) {
            sender.sendMessage("Only a player has a screen to show the keyboard on.");
            return true;
        }
        Session s = session(player);
        if (s.open) {
            close(player, s);
        } else {
            s.page = census.isEmpty() ? Page.KEYS : Page.MODS;
            show(player, s);
        }
        return true;
    }

    /** A latched key outlives the dialog, so the HUD keeps showing it. */
    @Override
    public void draw(Player player, HudFrame frame) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        List<String> names = s.state.heldKeys().stream().map(KeyboardState::keyName).toList();
        if (!names.isEmpty()) {
            frame.text(HudLayout.Anchor.BOTTOM_LEFT, HudLayout.TEXT_ROWS - 1,
                    "Held: " + String.join(" ", names), NamedTextColor.GREEN);
        }
    }

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!NS.equals(id.namespace()) || !id.value().startsWith("kb/")
                || !(event.getCommonConnection() instanceof PlayerGameConnection game)) {
            return;
        }
        Player player = game.getPlayer();
        Session s = session(player);
        String value = id.value();
        if (id.equals(CLOSE)) {
            s.open = false;
            player.closeDialog();
        } else if (id.equals(HOLD)) {
            s.state.toggleHoldMode();
            show(player, s);
        } else if (id.equals(PAGE_KEYS) || id.equals(PAGE_MODS)) {
            s.page = id.equals(PAGE_KEYS) ? Page.KEYS : Page.MODS;
            show(player, s);
        } else if (value.startsWith(PRESS_PREFIX)) {
            press(player, s, parse(value, PRESS_PREFIX));
        } else if (value.startsWith(BINDING_PREFIX)) {
            int index = parse(value, BINDING_PREFIX);
            if (index >= 0 && index < census.size()) {
                pressBinding(player, s, census.get(index));
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

    private static int parse(String value, String prefix) {
        try {
            return Integer.parseInt(value.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), u -> new Session(census));
    }

    private void press(Player player, Session s, int key) {
        if (KeyboardState.LAYOUT.stream().noneMatch(c -> c.key() == key && key >= 0)) {
            return;
        }
        List<KeyboardState.Transition> steps = s.state.click(key);
        deliver(player, steps);
        if (steps.size() == 1) {
            show(player, s);
        }
    }

    /** A binding whose default is not on the board has nothing to hold, so it taps. */
    private void pressBinding(Player player, Session s, KeyCensus.ModKey binding) {
        if (KeyboardState.LAYOUT.stream().anyMatch(c -> c.key() == binding.defaultKey())) {
            press(player, s, binding.defaultKey());
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

    private void close(Player player, Session s) {
        s.open = false;
        player.closeDialog();
    }

    private void show(Player player, Session s) {
        s.open = true;
        List<ActionButton> buttons = s.page == Page.KEYS ? keyButtons(s) : bindingButtons(s);
        int columns = s.page == Page.KEYS ? KeyboardState.COLUMNS : 2;
        Component hint = s.page == Page.KEYS
                ? Component.text("Shift, Ctrl and Alt stay down until clicked again.", NamedTextColor.GRAY)
                : Component.text(census.isEmpty() ? "No mod on this server registers a key."
                        : "Keys the mods on this server register.", NamedTextColor.GRAY);
        DialogBase base = DialogBase.builder(Component.text("Keyboard"))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(List.of(DialogBody.plainMessage(hint, 400)))
                .build();
        ActionButton exit = click(Component.text("Close"), null, 120, CLOSE);
        player.showDialog(Dialog.create(b -> b.empty().base(base)
                .type(DialogType.multiAction(buttons, exit, columns))));
    }

    private List<ActionButton> keyButtons(Session s) {
        List<ActionButton> out = new ArrayList<>(KeyboardState.LAYOUT.size());
        for (KeyboardState.Cell cell : KeyboardState.LAYOUT) {
            if (cell.key() == KeyboardState.CELL_HOLD) {
                out.add(click(lit(cell.label(), s.state.holdMode()),
                        Component.text("While lit, a clicked key stays down until clicked again."), KEY_WIDTH, HOLD));
            } else if (cell.key() == KeyboardState.CELL_PAGE) {
                out.add(click(Component.text(cell.label()), Component.text("Show the keys mods register."),
                        KEY_WIDTH, PAGE_MODS));
            } else {
                List<KeyCensus.ModKey> bound = s.state.bindingsFor(cell.key());
                Component tip = bound.isEmpty() ? null : Component.text(
                        String.join(", ", bound.stream().map(KeyCensus.ModKey::label).toList()));
                out.add(click(lit(cell.label(), s.state.isHeld(cell.key())), tip, KEY_WIDTH,
                        Key.key(NS, PRESS_PREFIX + cell.key())));
            }
        }
        return out;
    }

    private List<ActionButton> bindingButtons(Session s) {
        List<ActionButton> out = new ArrayList<>(census.size() + 1);
        for (int i = 0; i < census.size(); i++) {
            KeyCensus.ModKey k = census.get(i);
            String name = k.label() + " [" + KeyboardState.keyName(k.defaultKey()) + "]";
            out.add(click(lit(name, k.defaultKey() >= 0 && s.state.isHeld(k.defaultKey())),
                    Component.text(k.modId()), BINDING_WIDTH, Key.key(NS, BINDING_PREFIX + i)));
        }
        out.add(click(Component.text("Full keyboard"), null, BINDING_WIDTH, PAGE_KEYS));
        return out;
    }

    private static Component lit(String label, boolean on) {
        return on ? Component.text(label, NamedTextColor.GREEN) : Component.text(label);
    }

    private static ActionButton click(Component label, Component tooltip, int width, Key id) {
        return ActionButton.create(label, tooltip, width, DialogAction.customClick(id, null));
    }
}
