package dev.cim.hud;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Opens a fitted mod menu as a chest and keeps its slot rules.
 *
 * <p>Only the picture and the slots are translated. The mod's own menu object
 * does not exist here, so what is put in these slots has no effect on the mod
 * until the layer hosts real players.
 */
public final class ModMenus implements Listener, CommandExecutor {

    private static final class Holder implements InventoryHolder {
        final MenuCatalog.Menu menu;
        final Map<Integer, Integer> cellToSlot = new HashMap<>();
        Inventory inventory;

        Holder(MenuCatalog.Menu menu) {
            this.menu = menu;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** The chest title: the baked picture, then the mod's title in its own header. */
    static Component title(MenuCatalog.Menu menu, int index) {
        MenuPack.Baked at = MenuPack.placement(menu);
        int titleOrigin = 8;
        int toCanvas = at.originX() - titleOrigin;
        int toTitle = MenuPack.titleAt(menu)[0] - (at.originX() + at.width() + 1);
        String picture = HudFrame.move(toCanvas) + (char) (MenuPack.GLYPH_BASE + index) + HudFrame.move(toTitle);
        return Component.text(picture).font(Key.key(MenuPack.FONT)).color(NamedTextColor.WHITE)
                .append(Component.text(HudText.sanitize(menu.title()))
                        .font(Key.key(HudPack.NS, MenuPack.titleFontName(index)))
                        .color(net.kyori.adventure.text.format.TextColor.color(menu.titleColor())));
    }

    public void open(Player player, MenuCatalog.Menu menu) {
        MenuFit.Fit fit = MenuFit.fit(menu);
        Holder holder = new Holder(menu);
        for (int i = 0; i < fit.cells().length; i++) {
            holder.cellToSlot.put(fit.cells()[i], i);
        }
        holder.inventory = Bukkit.createInventory(holder, fit.rows() * MenuFit.COLUMNS,
                title(menu, MenuCatalog.MENUS.indexOf(menu)));
        player.openInventory(holder.inventory);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandSender target = sender instanceof ProxiedCommandSender proxy ? proxy.getCallee() : sender;
        MenuCatalog.Menu menu = args.length == 1 ? MenuCatalog.find(args[0]) : null;
        if (!(target instanceof Player player) || menu == null) {
            sender.sendMessage("Usage: /modmenu <" + String.join("|",
                    MenuCatalog.MENUS.stream().map(MenuCatalog.Menu::id).toList()) + ">");
            return true;
        }
        open(player, menu);
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder)) {
            return;
        }
        boolean top = event.getRawSlot() < event.getView().getTopInventory().getSize();
        if (!top) {
            // Shift-click would land in a cell that is not a slot.
            event.setCancelled(event.isShiftClick());
            return;
        }
        if (!holder.cellToSlot.containsKey(event.getSlot())) {
            event.setCancelled(true);
            return;
        }
        if (holder.menu.ghost()) {
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            ItemStack ghost = cursor.getType().isAir() ? null : cursor.asOne();
            event.getView().getTopInventory().setItem(event.getSlot(), ghost);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder holder) {
            int size = event.getView().getTopInventory().getSize();
            boolean touchesTop = event.getRawSlots().stream().anyMatch(s -> s < size);
            if (touchesTop && (holder.menu.ghost() || event.getRawSlots().stream()
                    .anyMatch(s -> s < size && !holder.cellToSlot.containsKey(s)))) {
                event.setCancelled(true);
            }
        }
    }

    /** Names of the menus this build can open, for logs and reports. */
    public static List<String> ids() {
        return MenuCatalog.MENUS.stream().map(MenuCatalog.Menu::id).toList();
    }
}
