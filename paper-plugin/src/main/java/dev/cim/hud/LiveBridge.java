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
    record State(String menuType, String menuClass, List<Slot> slots, String carried, String[] inventory,
                 List<String> dropped, String pressed, String blockEntity, String error) {
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

    /**
     * A right click on a mod block: the block's own use, on a block entity holding the data
     * Paper kept for it (none the first time, as a freshly placed block has none).
     */
    State openBlock(Player player, org.bukkit.block.Block block, String blockId) {
        StringBuilder in = new StringBuilder("open-block\t").append(player.getUniqueId()).append('\t')
                .append(player.getName()).append('\t').append(blockId).append('\t')
                .append(block.getX()).append('\t').append(block.getY()).append('\t').append(block.getZ());
        appendInventory(in, player, false);
        String kept = block.getChunk().getPersistentDataContainer().get(blockKey(block), PersistentDataType.STRING);
        if (kept != null) {
            in.append("\nbe\t").append(kept);
        }
        heldSlots.put(player.getUniqueId(), 0);
        openBlocks.put(player.getUniqueId(), block);
        return parse(call(in.toString()));
    }

    /** Per player: the mod block whose menu is open, and the held slot the layer's slot 0 stands for. */
    private final java.util.Map<java.util.UUID, org.bukkit.block.Block> openBlocks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> heldSlots = new java.util.HashMap<>();

    /** Paper keeps a mod block entity's data in its chunk, under the block's position. */
    private NamespacedKey blockKey(org.bukkit.block.Block block) {
        return new NamespacedKey(key.getNamespace(), "be_" + block.getX() + "_" + block.getY() + "_" + block.getZ());
    }

    /** The block entity's data the close handed back, stored for the next opening. */
    void keepBlockEntity(Player player, State state) {
        org.bukkit.block.Block block = openBlocks.remove(player.getUniqueId());
        if (block != null && state.blockEntity() != null) {
            block.getChunk().getPersistentDataContainer().set(blockKey(block), PersistentDataType.STRING,
                    state.blockEntity());
        }
    }

    private void appendInventory(StringBuilder in, Player player, boolean heldFirst) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int held = player.getInventory().getHeldItemSlot();
        for (int i = 0; i < contents.length; i++) {
            int to = !heldFirst ? i : i == held ? 0 : i == 0 ? held : i;
            String stack = toLayer(contents[i]);
            if (!stack.equals("-")) {
                in.append("\ninv\t").append(to).append('\t').append(stack);
            }
        }
    }

    /** The held item's own use(), with the player's inventory mirrored first. */
    State openHeld(Player player) {
        StringBuilder in = new StringBuilder("open-item\t").append(player.getUniqueId()).append('\t')
                .append(player.getName());
        // The layer's player holds slot 0 selected: the held stack goes there, the stack
        // there goes where the held one was, so no item is lost or doubled.
        appendInventory(in, player, true);
        heldSlots.put(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        return parse(call(in.toString()));
    }


    State click(Player player, int menuIndex, int button, String type) {
        return parse(call("click\t" + player.getUniqueId() + "\t" + menuIndex + "\t" + button + "\t" + type));
    }

    /** A scanned button of the open menu: its payload, built in the layer, to the mod's handler. */
    State press(Player player, LiveButtons.Button b) {
        StringBuilder cmd = new StringBuilder("button\t").append(player.getUniqueId()).append('\t')
                .append(b.payload()).append('\t').append(b.constructorDesc());
        for (String a : b.args()) {
            cmd.append('\t').append(a);
        }
        return parse(call(cmd.toString()));
    }

    /** Each stateful widget's live state on the open menu: ordinal to {active, visible}. */
    java.util.Map<Integer, boolean[]> widgets(Player player, List<LiveButtons.Stateful> widgets) {
        StringBuilder cmd = new StringBuilder("widgets\t").append(player.getUniqueId());
        for (LiveButtons.Stateful w : widgets) {
            cmd.append('\n').append(w.ordinal()).append('\t').append(w.active()).append('\t').append(w.visible());
        }
        java.util.Map<Integer, boolean[]> out = new java.util.HashMap<>();
        for (String line : call(cmd.toString()).split("\n")) {
            String[] p = line.split("\t");
            if (p.length == 4 && p[0].equals("widget")) {
                // An expression the layer could not ground (null) leaves the widget as drawn.
                out.put(Integer.parseInt(p[1]), new boolean[]{!"false".equals(p[2]), !"false".equals(p[3])});
            }
        }
        return out;
    }

    State close(Player player) {
        return parse(call("close\t" + player.getUniqueId()));
    }

    /** The layer's inventory back on Paper, slot for slot (the held swap undone). */
    void applyInventory(Player player, State state) {
        int heldSlot = heldSlots.getOrDefault(player.getUniqueId(), 0);
        ItemStack[] out = new ItemStack[player.getInventory().getStorageContents().length];
        for (int i = 0; i < out.length && i < state.inventory().length; i++) {
            int to = i == 0 ? heldSlot : i == heldSlot ? 0 : i;
            out[to] = toPaper(state.inventory()[i]);
        }
        player.getInventory().setStorageContents(out);
        // What the mod's code dropped from its player falls where the real player stands.
        for (String json : state.dropped()) {
            ItemStack stack = toPaper(json);
            if (stack != null) {
                player.getWorld().dropItem(player.getLocation(), stack);
            }
        }
    }

    private static String call(String request) {
        Function<String, String> fn = entry();
        return fn == null ? "error\tno layer" : fn.apply(request);
    }

    private static State parse(String answer) {
        String type = null;
        String menuClass = null;
        String pressed = null;
        String blockEntity = null;
        String error = null;
        String carried = "-";
        List<Slot> slots = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        String[] inventory = new String[41];
        java.util.Arrays.fill(inventory, "-");
        for (String line : answer.split("\n")) {
            String[] p = line.split("\t", 6);
            switch (p[0]) {
                case "menu" -> {
                    type = p[1];
                    menuClass = p.length > 2 ? p[2] : null;
                }
                case "pressed" -> pressed = p.length > 1 ? p[1] : "";
                case "be" -> blockEntity = line.substring(3);
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
                case "drop" -> dropped.add(p[1]);
                case "error" -> error = line;
                default -> {
                }
            }
        }
        return new State(type, menuClass, slots, carried, inventory, dropped, pressed, blockEntity, error);
    }

    /** A Paper stack for a mod item, as {@link #toPaper} makes it. */
    ItemStack stackOf(String itemId, int count) {
        return toPaper("{\"id\":\"" + itemId + "\",\"count\":" + count + "}");
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
        boolean standIn = stack.getType().isAir();
        if (standIn) {
            // No carrier for this item (it has no look in the pack): a plain stand-in keeps
            // the item itself, so a mod's stack never vanishes from the player.
            stack = new ItemStack(Material.PAPER);
        }
        stack.setAmount(amount);
        // Only a stack with data of its own needs its JSON: a bare one rebuilds from its id.
        if (standIn || json.contains("\"components\"")) {
            org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, json);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
