package dev.cim.hud;

import dev.cim.f2v.F2vPack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Opens a menu the way the mod does: right-click the item or block.
 *
 * <p>Modded things reach Paper as the pack's carriers: an item with a
 * custom_model_data float, a block as a note block state. The pack's own
 * manifests say which carrier is which modded id.
 */
public final class MenuTriggers implements Listener {

    private static final java.util.regex.Pattern STATE = java.util.regex.Pattern.compile("(\\w+)=(\\w+)");

    private final ModMenus menus;
    private final Map<Float, String> items = new HashMap<>();
    private final Map<String, String> blocks = new HashMap<>();

    public MenuTriggers(ModMenus menus, Path f2vDir) throws IOException {
        this.menus = menus;
        Path resolved = f2vDir.resolve("visual-items.resolved.tsv");
        Path plain = f2vDir.resolve("visual-items.tsv");
        Path itemFile = Files.isRegularFile(resolved) ? resolved : plain;
        if (Files.isRegularFile(itemFile)) {
            for (F2vPack.VisualItem row : F2vPack.readManifest(itemFile)) {
                items.put(row.cmd(), row.moddedId());
            }
        }
        Path blockFile = f2vDir.resolve("visual-blocks.tsv");
        if (Files.isRegularFile(blockFile)) {
            List<F2vPack.VisualBlock> rows = F2vPack.readBlockManifest(blockFile);
            for (F2vPack.VisualBlock row : rows) {
                blocks.put(row.instrument() + ":" + row.note(), row.moddedBlockId());
            }
        }
    }

    public int size() {
        return items.size() + blocks.size();
    }

    /** The menu a modded id opens, or null. Dyed toolboxes all share one menu. */
    static MenuCatalog.Menu menuFor(String moddedId) {
        if (moddedId == null) {
            return null;
        }
        if (moddedId.startsWith("create:") && moddedId.endsWith("toolbox")) {
            return MenuCatalog.find("create:toolbox");
        }
        if (moddedId.equals("create:package_frogport") || moddedId.equals("create:package_postbox")) {
            return MenuCatalog.find("create:package_port");
        }
        return MenuCatalog.find(moddedId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getPlayer().isSneaking()) {
            return;
        }
        MenuCatalog.Menu menu = null;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            menu = menuFor(blockId(event.getClickedBlock()));
        }
        if (menu == null && (event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            menu = menuFor(itemId(event.getItem()));
        }
        if (menu != null) {
            event.setCancelled(true);
            menus.open(event.getPlayer(), menu);
        }
    }

    private String blockId(Block block) {
        if (block == null || block.getType() != Material.NOTE_BLOCK) {
            return null;
        }
        // The manifest speaks vanilla state names; Bukkit's Instrument enum does not.
        return blocks.get(carrierKey(block.getBlockData().getAsString()));
    }

    /** {@code minecraft:note_block[instrument=harp,note=5,...]} to {@code harp:5}. */
    static String carrierKey(String state) {
        java.util.regex.Matcher m = STATE.matcher(state);
        String instrument = null;
        String note = null;
        while (m.find()) {
            if (m.group(1).equals("instrument")) {
                instrument = m.group(2);
            } else if (m.group(1).equals("note")) {
                note = m.group(2);
            }
        }
        return instrument + ":" + note;
    }

    private String itemId(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (!meta.hasCustomModelDataComponent()) {
            return null;
        }
        List<Float> floats = meta.getCustomModelDataComponent().getFloats();
        return floats.isEmpty() ? null : items.get(floats.get(0));
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code MenuTriggers --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        bad += check(carrierKey("minecraft:note_block[instrument=trumpet_exposed,note=12,powered=false]")
                .equals("trumpet_exposed:12"), "carrier key reads a real state string");
        bad += check(menuFor("create:filter").id().equals("create:filter"), "item id opens its menu");
        bad += check(menuFor("create:red_toolbox").id().equals("create:toolbox"), "dyed toolbox shares the menu");
        bad += check(menuFor("create:toolbox").id().equals("create:toolbox"), "plain toolbox id");
        bad += check(menuFor("create:package_postbox").id().equals("create:package_port"), "postbox opens the port");
        bad += check(menuFor("create:cogwheel") == null, "other content opens nothing");
        bad += check(menuFor(null) == null, "no carrier opens nothing");
        System.out.println(bad == 0 ? "MenuTriggers selftest: ALL PASS" : "MenuTriggers selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
