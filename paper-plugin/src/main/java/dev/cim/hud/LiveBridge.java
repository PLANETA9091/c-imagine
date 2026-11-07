package dev.cim.hud;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Paper end of live menus (roadmap M6.3): a mod menu's clicks run in the mod's own
 * code in the c-imagine layer, and what comes back is drawn. The layer's cimlive mod
 * publishes one {@code Function<String, String>} under the JVM-wide system property
 * {@code cim.live}; without the layer (plain Paper) there is none and the HUD keeps its
 * local behaviour.
 *
 * <p>Items cross whole: the layer's JSON of each stack (id, count and the mod's data
 * components) rides on the Paper stack in its persistent data, so a filter's list or a
 * backpack's contents are the mod's own data on every trip.
 */
final class LiveBridge {

    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern COUNT = Pattern.compile("\"count\"\\s*:\\s*(\\d+)");

    /** One slot as the layer's menu has it, in the order the screen draws them. */
    record Slot(int menuIndex, boolean player, int containerSlot, String stack) {
    }

    /** What the layer's menu holds after a command. */
    record State(String menuType, List<Slot> slots, String carried, String[] inventory, String error) {
        boolean open() {
            return menuType != null;
        }
    }

    private final NamespacedKey key;
    private final Function<String, ItemStack> carrier;
    private final Function<ItemStack, String> moddedId;

    LiveBridge(org.bukkit.plugin.Plugin plugin, Function<String, ItemStack> carrier,
               Function<ItemStack, String> moddedId) {
        this.key = new NamespacedKey(plugin, "live_stack");
        this.carrier = carrier;
        this.moddedId = moddedId;
    }

    @SuppressWarnings("unchecked")
    static Function<String, String> entry() {
        Object f = System.getProperties().get("cim.live");
        return f instanceof Function<?, ?> fn ? (Function<String, String>) fn : null;
    }

    static boolean available() {
        return entry() != null;
    }

    /** The held item's own use(), with the player's inventory mirrored first. */
    State openHeld(Player player) {
        StringBuilder in = new StringBuilder("open-item\t").append(player.getUniqueId()).append('\t')
                .append(player.getName());
        ItemStack[] contents = player.getInventory().getStorageContents();
        int held = player.getInventory().getHeldItemSlot();
        for (int i = 0; i < contents.length; i++) {
            // The layer's player holds slot 0 selected: the held stack goes there, the stack
            // there goes where the held one was, so no item is lost or doubled.
            int to = i == held ? 0 : i == 0 ? held : i;
            String stack = toLayer(contents[i]);
            if (!stack.equals("-")) {
                in.append("\ninv\t").append(to).append('\t').append(stack);
            }
        }
        heldSlot = held;
        return parse(call(in.toString()));
    }

    private int heldSlot;

    State click(Player player, int menuIndex, int button, String type) {
        return parse(call("click\t" + player.getUniqueId() + "\t" + menuIndex + "\t" + button + "\t" + type));
    }

    State close(Player player) {
        return parse(call("close\t" + player.getUniqueId()));
    }

    /** The layer's inventory back on Paper, slot for slot (the held swap undone). */
    void applyInventory(Player player, State state) {
        ItemStack[] out = new ItemStack[player.getInventory().getStorageContents().length];
        for (int i = 0; i < out.length && i < state.inventory().length; i++) {
            int to = i == 0 ? heldSlot : i == heldSlot ? 0 : i;
            out[to] = toPaper(state.inventory()[i]);
        }
        player.getInventory().setStorageContents(out);
    }

    private static String call(String request) {
        Function<String, String> fn = entry();
        return fn == null ? "error\tno layer" : fn.apply(request);
    }

    private static State parse(String answer) {
        String type = null;
        String error = null;
        String carried = "-";
        List<Slot> slots = new ArrayList<>();
        String[] inventory = new String[41];
        java.util.Arrays.fill(inventory, "-");
        for (String line : answer.split("\n")) {
            String[] p = line.split("\t", 6);
            switch (p[0]) {
                case "menu" -> type = p[1];
                case "slot" -> slots.add(new Slot(Integer.parseInt(p[2]), p[3].equals("player"),
                        Integer.parseInt(p[4]), p[5]));
                case "carried" -> carried = p[1];
                case "inv" -> {
                    String[] q = line.split("\t", 3);
                    int i = Integer.parseInt(q[1]);
                    if (i < inventory.length) {
                        inventory[i] = q[2];
                    }
                }
                case "error" -> error = line;
                default -> {
                }
            }
        }
        return new State(type, slots, carried, inventory, error);
    }

    /** A Paper stack as the layer reads it: its own JSON when it has been there before. */
    String toLayer(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "-";
        }
        String json = stack.hasItemMeta()
                ? stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING) : null;
        if (json != null) {
            // Paper may have changed the count (a stack split, a pick-up); the rest is the mod's.
            return COUNT.matcher(json).replaceFirst("\"count\":" + stack.getAmount());
        }
        String id = moddedId.apply(stack);
        return "{\"id\":\"" + id + "\",\"count\":" + stack.getAmount() + "}";
    }

    /** A layer stack on Paper: the vanilla item, or the modded item's carrier, with its JSON kept. */
    ItemStack toPaper(String json) {
        if (json == null || json.equals("-")) {
            return null;
        }
        Matcher id = ID.matcher(json);
        Matcher count = COUNT.matcher(json);
        if (!id.find()) {
            return null;
        }
        String itemId = id.group(1);
        int amount = count.find() ? Integer.parseInt(count.group(1)) : 1;
        ItemStack stack;
        if (itemId.startsWith("minecraft:")) {
            Material m = Material.matchMaterial(itemId);
            stack = new ItemStack(m == null ? Material.AIR : m);
        } else {
            stack = carrier.apply(itemId);
        }
        if (stack.getType().isAir()) {
            return null;
        }
        stack.setAmount(amount);
        // Only a stack with data of its own needs its JSON: a bare one rebuilds from its id.
        if (json.contains("\"components\"")) {
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, json);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
