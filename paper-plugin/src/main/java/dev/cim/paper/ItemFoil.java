package dev.cim.paper;

import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;

/** Applies the native default foil to a vanilla carrier. */
public final class ItemFoil {

    private ItemFoil() {
    }

    public static void apply(ItemMeta meta, String id, Set<String> captured) {
        Object live = System.getProperties().get("cim.live.itemFoil");
        if (live instanceof Set<?> nativeFoil ? nativeFoil.contains(id) : captured.contains(id)) {
            meta.setEnchantmentGlintOverride(true);
        }
    }
}
