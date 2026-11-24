package dev.cim.paper;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cim.f2v.F2vPack;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Keeps a modded enchantment on the modded items it belongs to.
 *
 * <p>A mod enchantment whose supported items name mod items is registered on the
 * carrier host item (see {@code cim-enchant}: {@code supported_items} = the host),
 * so every stack of that host satisfies {@code canEnchant}. This listener refuses
 * the enchantment on any stack whose modded id (its allocated {@code item_model})
 * is not in the mod's gate list: a vanilla stack of the same host is never offered
 * it at the enchanting table, and the anvil never produces it on one.
 *
 * <p>The gate comes from {@code enchant-defs.json} ({@code "gate"}), loaded by
 * {@link EnchantRegistry#install}. An enchantment with no gate entry is a vanilla or
 * unrestricted one and is never touched.
 */
public final class EnchantGate implements Listener {

    private static volatile Map<String, Set<String>> gate = Map.of();

    /** Replaces the gate (enchantment id -> modded item ids). */
    static void set(Map<String, List<String>> doc) {
        Map<String, Set<String>> copy = new java.util.HashMap<>();
        for (Map.Entry<String, List<String>> e : doc.entrySet()) {
            copy.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        gate = Map.copyOf(copy);
    }

    /** The pure rule: an ungated enchantment is allowed; a gated one needs the modded id in its list. */
    static boolean allowed(Map<String, Set<String>> gate, String enchantId, String moddedId) {
        Set<String> items = gate.get(enchantId);
        return items == null || (moddedId != null && items.contains(moddedId));
    }

    /** The modded id a Paper stack stands for (its allocated item_model), or null for a vanilla stack. */
    static String moddedIdOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.hasItemModel() ? F2vPack.moddedIdOfItemModel(meta.getItemModel().toString()) : null;
    }

    private static boolean allowedOn(String enchantId, ItemStack stack) {
        return allowed(gate, enchantId, moddedIdOf(stack));
    }

    private static String keyOf(Enchantment enchantment) {
        return enchantment.getKey().toString();
    }

    @EventHandler
    public void onOffers(PrepareItemEnchantEvent event) {
        for (EnchantmentOffer offer : event.getOffers()) {
            if (offer != null && !allowedOn(keyOf(offer.getEnchantment()), event.getItem())) {
                // Offers are one list: a gated offer for this item refuses the whole table.
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        for (Enchantment enchantment : event.getEnchantsToAdd().keySet()) {
            if (!allowedOn(keyOf(enchantment), event.getItem())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        ItemStack base = event.getInventory().getFirstItem();
        if (result == null || base == null) {
            return;
        }
        for (Enchantment enchantment : enchantsOf(result).keySet()) {
            if (!allowedOn(keyOf(enchantment), base)) {
                event.setResult(null);
                return;
            }
        }
    }

    /**
     * The layer's stack JSON with its {@code minecraft:enchantments} component set to {@code levels}
     * (1.21.1 form: {@code {"levels":{id:level}}}). No levels removes a stale component. Pure, so the
     * selftest runs it without a server.
     */
    public static String layerJson(String json, Map<String, Integer> levels) {
        if (levels.isEmpty() && !json.contains("minecraft:enchantments")) {
            return json;
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject components = obj.has("components") && obj.get("components").isJsonObject()
                ? obj.getAsJsonObject("components") : null;
        if (levels.isEmpty()) {
            if (components != null) {
                components.remove("minecraft:enchantments");
                if (components.size() == 0) {
                    obj.remove("components");
                }
            }
            return obj.toString();
        }
        if (components == null) {
            components = new JsonObject();
            obj.add("components", components);
        }
        JsonObject map = new JsonObject();
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            map.addProperty(e.getKey(), e.getValue());
        }
        JsonObject component = new JsonObject();
        component.add("levels", map);
        components.add("minecraft:enchantments", component);
        return obj.toString();
    }

    /** The enchantments a stack carries (stored ones for an enchanted book). */
    public static Map<Enchantment, Integer> enchantsOf(ItemStack stack) {
        if (stack.getItemMeta() instanceof EnchantmentStorageMeta books) {
            return books.getStoredEnchants();
        }
        return stack.getEnchantments();
    }
}
