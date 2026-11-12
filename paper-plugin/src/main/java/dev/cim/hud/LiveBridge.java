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
    /** {@code x, y}: where the menu puts the slot; pairs it with the captured screen's slot. */
    /** {@code pickable}: false for a slot the mod only displays in, which its screen draws itself. */
    record Slot(int menuIndex, boolean player, int containerSlot, String stack, int x, int y, boolean pickable) {
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
        return openBlock(player, block, blockId, false);
    }

    /**
     * {@code fresh}: the block as just placed, whatever was there before (the gallery places a
     * new block for every page, as the truth walk does); its kept data is dropped too.
     */
    State openBlock(Player player, org.bukkit.block.Block block, String blockId, boolean fresh) {
        StringBuilder in = new StringBuilder("open-block\t").append(player.getUniqueId()).append('\t')
                .append(player.getName()).append('\t').append(blockId).append('\t')
                .append(block.getX()).append('\t').append(block.getY()).append('\t').append(block.getZ());
        appendInventory(in, player, false);
        org.bukkit.persistence.PersistentDataContainer chunk = block.getChunk().getPersistentDataContainer();
        if (fresh) {
            chunk.remove(blockKey(block, blockId));
            in.append("\nfresh");
        }
        String kept = chunk.get(blockKey(block, blockId), PersistentDataType.STRING);
        if (kept != null) {
            in.append("\nbe\t").append(kept);
        }
        heldSlots.put(player.getUniqueId(), 0);
        openBlocks.put(player.getUniqueId(), block);
        openBlockIds.put(player.getUniqueId(), blockId);
        return parse(call(in.toString()));
    }

    /** Per player: the mod block whose menu is open, and the held slot the layer's slot 0 stands for. */
    private final java.util.Map<java.util.UUID, org.bukkit.block.Block> openBlocks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> heldSlots = new java.util.HashMap<>();

    private final java.util.Map<java.util.UUID, String> openBlockIds = new java.util.HashMap<>();

    /**
     * Paper keeps a mod block entity's data in its chunk, under the block's position and id: a
     * different block put there later never reads it (measured: a vibration chamber opened with
     * the previous page's machine contents in its fuel slot).
     */
    private NamespacedKey blockKey(org.bukkit.block.Block block, String blockId) {
        String id = blockId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return new NamespacedKey(key.getNamespace(),
                "be_" + block.getX() + "_" + block.getY() + "_" + block.getZ() + "_" + id);
    }

    /** The block entity's data the close handed back, stored for the next opening. */
    void keepBlockEntity(Player player, State state) {
        org.bukkit.block.Block block = openBlocks.remove(player.getUniqueId());
        String id = openBlockIds.remove(player.getUniqueId());
        if (block != null && id != null && state.blockEntity() != null) {
            block.getChunk().getPersistentDataContainer().set(blockKey(block, id), PersistentDataType.STRING,
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

    /** A walk-proven opener done in the facade beside the player (part placement, an item on a block). */
    State openUse(Player player, org.bukkit.block.Block block, String opener) {
        StringBuilder in = new StringBuilder("open-use\t").append(player.getUniqueId()).append('\t')
                .append(player.getName()).append('\t').append(opener).append('\t')
                .append(block.getX()).append('\t').append(block.getY()).append('\t').append(block.getZ());
        appendInventory(in, player, false);
        heldSlots.put(player.getUniqueId(), 0);
        return parse(call(in.toString()));
    }

    /** What a right click with a mod item on a block did: a menu, and/or a block it placed. */
    record UseOn(State state, String placedId, int x, int y, int z, String placedBlockEntity) {
    }

    /**
     * A right click with the held item on a block of Paper's world, by the item's own code:
     * {@code clickedId} is the mod block clicked, or null for a world block.
     */
    UseOn useOn(Player player, org.bukkit.block.Block clicked, org.bukkit.block.BlockFace face, String clickedId) {
        return useOn(player, clicked, face, clickedId, player.isSneaking());
    }

    UseOn useOn(Player player, org.bukkit.block.Block clicked, org.bukkit.block.BlockFace face, String clickedId,
                boolean sneaking) {
        StringBuilder in = new StringBuilder("use-on\t").append(player.getUniqueId()).append('\t')
                .append(player.getName()).append('\t').append(clicked.getX()).append('\t').append(clicked.getY())
                .append('\t').append(clicked.getZ()).append('\t').append(face.name().toLowerCase(java.util.Locale.ROOT))
                .append('\t').append(clickedId == null ? "-" : clickedId).append('\t').append(sneaking ? "1" : "0");
        appendInventory(in, player, true);
        heldSlots.put(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        String answer = call(in.toString());
        String placed = null;
        String be = null;
        int x = 0;
        int y = 0;
        int z = 0;
        for (String line : answer.split("\n")) {
            if (line.startsWith("placed\t")) {
                String[] p = line.split("\t");
                placed = p[1];
                x = Integer.parseInt(p[2]);
                y = Integer.parseInt(p[3]);
                z = Integer.parseInt(p[4]);
            } else if (line.startsWith("placedbe\t")) {
                be = line.substring("placedbe\t".length());
            }
        }
        State state = parse(answer);
        if (state.open() && placed == null) {
            openBlocks.remove(player.getUniqueId());
        }
        return new UseOn(state, placed, x, y, z, be);
    }

    /** What breaking a mod block did: the stacks it spilled, the player's inventory after. */
    record Broken(boolean removed, List<ItemStack> spilled, State state) {
    }

    /**
     * The player breaks a mod block of Paper's world by the block's own code, with the data
     * Paper kept for it; the kept data goes with the block.
     */
    Broken breakBlock(Player player, org.bukkit.block.Block block, String blockId) {
        StringBuilder in = new StringBuilder("break\t").append(player.getUniqueId()).append('\t')
                .append(player.getName()).append('\t').append(blockId).append('\t')
                .append(block.getX()).append('\t').append(block.getY()).append('\t').append(block.getZ());
        appendInventory(in, player, true);
        heldSlots.put(player.getUniqueId(), player.getInventory().getHeldItemSlot());
        org.bukkit.persistence.PersistentDataContainer chunk = block.getChunk().getPersistentDataContainer();
        String kept = chunk.get(blockKey(block, blockId), PersistentDataType.STRING);
        if (kept != null) {
            in.append("\nbe\t").append(kept);
        }
        String answer = call(in.toString());
        boolean removed = false;
        List<ItemStack> spilled = new ArrayList<>();
        for (String line : answer.split("\n")) {
            if (line.startsWith("broken\t")) {
                removed = Boolean.parseBoolean(line.substring(7));
            } else if (line.startsWith("spill\t")) {
                ItemStack stack = toPaper(line.substring(6));
                if (stack != null) {
                    spilled.add(stack);
                }
            }
        }
        State state = parse(answer);
        if (removed) {
            chunk.remove(blockKey(block, blockId));
        }
        return new Broken(removed, spilled, state);
    }

    /** A vanilla menu packet a screen's button sends ("button" id or "trade" offer), by the server's handler. */
    State vanilla(Player player, String kind, int n) {
        return parse(call("vanilla\t" + player.getUniqueId() + "\t" + kind + "\t" + n));
    }

    /** A mod block Paper now holds: its block entity's data, kept for its first opening. */
    void keepPlaced(org.bukkit.block.Block block, String blockId, String blockEntity) {
        if (blockEntity != null) {
            block.getChunk().getPersistentDataContainer().set(blockKey(block, blockId), PersistentDataType.STRING,
                    blockEntity);
        }
    }

    /** The held item's own use(), with the player's inventory mirrored first. */
    State openHeld(Player player) {
        return openHeld(player, player.isSneaking());
    }

    /** {@code sneaking}: the use as a sneaking player makes it (some items open their menu so only). */
    State openHeld(Player player, boolean sneaking) {
        StringBuilder in = new StringBuilder("open-item\t").append(player.getUniqueId()).append('\t')
                .append(player.getName()).append('\t').append(sneaking ? "1" : "0");
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

    /** The open menu as it is now. */
    State state(Player player) {
        return parse(call("state\t" + player.getUniqueId()));
    }

    /** One tick of the open live block menus, once per Paper tick. */
    static void tick() {
        Function<String, String> fn = entry();
        if (fn != null) {
            fn.apply("tick\t-");
        }
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
                case "slot" -> {
                    // stack JSON never holds a tab, so the position follows it.
                    String[] q = line.split("\t");
                    int x = q.length > 7 ? Integer.parseInt(q[6]) : Integer.MIN_VALUE;
                    int y = q.length > 7 ? Integer.parseInt(q[7]) : Integer.MIN_VALUE;
                    boolean pickable = q.length <= 8 || !q[8].equals("0");
                    slots.add(new Slot(Integer.parseInt(q[2]), q[3].equals("player"), Integer.parseInt(q[4]), q[5], x, y,
                            pickable));
                }
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
