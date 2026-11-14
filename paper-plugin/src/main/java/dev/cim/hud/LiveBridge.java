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
        saveFresh(block, blockId);
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
        return new NamespacedKey(key.getNamespace(),
                "be_" + block.getX() + "_" + block.getY() + "_" + block.getZ() + "_" + sanitize(blockId));
    }

    private static String sanitize(String blockId) {
        return blockId.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /** A placed mod block in a loaded chunk, whose block entity ticks in the layer. */
    record Tracked(String world, int x, int y, int z, String blockId) {
        int chunkX() {
            return x >> 4;
        }

        int chunkZ() {
            return z >> 4;
        }
    }

    /** Ticking placed block entities by world and position; the layer ticks each one every tick. */
    private final java.util.Map<String, Tracked> ticking = new java.util.HashMap<>();

    private static String where(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    /** How many placed block entities tick in the layer now. */
    int tickingCount() {
        return ticking.size();
    }

    /** True while any placed block entity ticks: the Paper tick drives the layer for them too. */
    boolean hasTicking() {
        return !ticking.isEmpty();
    }

    /**
     * The placed mod block at {@code block} starts ticking in the layer, with the data Paper kept
     * for it. True when the set grew; a layer that refuses it leaves the set as it was.
     */
    boolean track(org.bukkit.block.Block block, String blockId, String blockEntity) {
        String at = where(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        // The layer keys its block entities by position alone: another world's block there would share it.
        for (Tracked t : ticking.values()) {
            if (t.x() == block.getX() && t.y() == block.getY() && t.z() == block.getZ()
                    && !t.world().equals(block.getWorld().getName())) {
                return false;
            }
        }
        String in = "adopt\t" + block.getX() + "\t" + block.getY() + "\t" + block.getZ() + "\t" + blockId
                + (blockEntity == null ? "" : "\nbe\t" + blockEntity);
        if (parse(call(in)).error() != null) {
            return false;
        }
        return ticking.put(at, new Tracked(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                blockId)) == null;
    }

    /**
     * The block at {@code block} stops ticking. Its data is kept in {@code keepIn} when given (a
     * chunk unload), else the block is gone (broken). True when the set shrank.
     */
    boolean release(org.bukkit.block.Block block, org.bukkit.Chunk keepIn) {
        String at = where(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        Tracked t = ticking.get(at);
        if (t == null) {
            return false;
        }
        State state = parse(call("release\t" + block.getX() + "\t" + block.getY() + "\t" + block.getZ()));
        if (keepIn != null && state.blockEntity() != null) {
            put(keepIn.getPersistentDataContainer(), block, t.blockId(), state.blockEntity());
        }
        ticking.remove(at);
        return true;
    }

    /**
     * A loaded chunk: each placed mod block its PDC keeps (under its position and id) starts
     * ticking, when a carrier of that id stands there. True when the set grew.
     */
    boolean adoptChunk(org.bukkit.Chunk chunk, java.util.function.Function<org.bukkit.block.Block, String> carriedId) {
        org.bukkit.persistence.PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        boolean grew = false;
        for (NamespacedKey k : pdc.getKeys()) {
            if (!k.getNamespace().equals(key.getNamespace()) || !k.getKey().startsWith("be_")) {
                continue;
            }
            java.util.regex.Matcher m = KEPT.matcher(k.getKey());
            if (!m.matches()) {
                continue;
            }
            org.bukkit.block.Block block = chunk.getWorld().getBlockAt(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            String id = carriedId.apply(block);
            String kept = pdc.get(k, PersistentDataType.STRING);
            // A kept entry no carrier of its id stands on is stale: it waits for its block.
            if (id == null || !sanitize(id).equals(m.group(4)) || kept == null) {
                continue;
            }
            grew |= track(block, id, kept);
        }
        return grew;
    }

    private static final Pattern KEPT = Pattern.compile("be_(-?\\d+)_(-?\\d+)_(-?\\d+)_(.+)");

    /** A chunk unloads: its placed block entities stop ticking, their data kept in the chunk. True when the set shrank. */
    boolean releaseChunk(org.bukkit.Chunk chunk) {
        boolean shrank = false;
        for (Tracked t : new ArrayList<>(ticking.values())) {
            if (t.world().equals(chunk.getWorld().getName()) && t.chunkX() == chunk.getX() && t.chunkZ() == chunk.getZ()) {
                shrank |= release(chunk.getWorld().getBlockAt(t.x(), t.y(), t.z()), chunk);
            }
        }
        return shrank;
    }

    /** A ticking block's data as it is now, kept in its chunk, before a menu or a break reads the chunk. */
    private void saveFresh(org.bukkit.block.Block block, String blockId) {
        Tracked t = ticking.get(where(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        // Only the block that ticking is for: a stale entry's data must not be kept under another id.
        if (t != null && t.blockId().equals(blockId)) {
            State state = parse(call("save\t" + block.getX() + "\t" + block.getY() + "\t" + block.getZ()));
            if (state.blockEntity() != null) {
                keepPlaced(block, blockId, state.blockEntity());
            }
        }
    }

    /**
     * Every ticking block's data as it is now, kept in its chunk: the periodic and world-save write.
     * A ticking block whose carrier no longer stands (an explosion, a piston, a set by a command)
     * stops ticking and its kept data goes with it. True when the set shrank.
     */
    boolean saveAll(java.util.function.Function<org.bukkit.block.Block, String> carriedId) {
        boolean shrank = false;
        for (Tracked t : new ArrayList<>(ticking.values())) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorld(t.world());
            if (world == null || !world.isChunkLoaded(t.chunkX(), t.chunkZ())) {
                continue;
            }
            org.bukkit.block.Block block = world.getBlockAt(t.x(), t.y(), t.z());
            if (!t.blockId().equals(carriedId.apply(block))) {
                block.getChunk().getPersistentDataContainer().remove(blockKey(block, t.blockId()));
                shrank |= release(block, null);
                continue;
            }
            State state = parse(call("save\t" + t.x() + "\t" + t.y() + "\t" + t.z()));
            if (state.blockEntity() != null) {
                keepPlaced(block, t.blockId(), state.blockEntity());
            }
        }
        return shrank;
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
        saveFresh(block, blockId);
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

    /** A record payload rebuilt from the plain values the real client's press sent. */
    State payload(Player player, String payloadClass, String values) {
        return parse(call("payload\t" + player.getUniqueId() + "\t" + payloadClass + "\t" + values));
    }

    /** A vanilla menu packet a screen's button sends ("button" id or "trade" offer), by the server's handler. */
    State vanilla(Player player, String kind, int n) {
        return parse(call("vanilla\t" + player.getUniqueId() + "\t" + kind + "\t" + n));
    }

    /** A mod block Paper now holds: its block entity's data, kept for its first opening. */
    void keepPlaced(org.bukkit.block.Block block, String blockId, String blockEntity) {
        put(block.getChunk().getPersistentDataContainer(), block, blockId, blockEntity);
    }

    private void put(org.bukkit.persistence.PersistentDataContainer pdc, org.bukkit.block.Block block, String blockId,
                     String blockEntity) {
        if (blockEntity != null) {
            pdc.set(blockKey(block, blockId), PersistentDataType.STRING, blockEntity);
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
    /**
     * One server tick of the layer's menus and placed block entities. What they spilled drops in
     * Paper's world at the block that spilled it; nothing is kept for a later break to hand out.
     */
    void tick() {
        Function<String, String> fn = entry();
        if (fn == null) {
            return;
        }
        String answer = fn.apply("tick\t-");
        if (answer.indexOf('\n') < 0) {
            return; // nothing spilled: the common tick splits nothing
        }
        for (String line : answer.split("\n")) {
            if (line.startsWith("spill\t")) {
                drop(line);
            }
        }
    }

    private void drop(String line) {
        String[] p = line.split("\t", 5);
        int x = Integer.parseInt(p[1]);
        int y = Integer.parseInt(p[2]);
        int z = Integer.parseInt(p[3]);
        org.bukkit.World world = null;
        for (Tracked t : ticking.values()) {
            if (t.x() == x && t.y() == y && t.z() == z) {
                world = org.bukkit.Bukkit.getWorld(t.world());
                break;
            }
        }
        for (org.bukkit.block.Block b : openBlocks.values()) {
            if (world == null && b.getX() == x && b.getY() == y && b.getZ() == z) {
                world = b.getWorld();
            }
        }
        ItemStack stack = toPaper(p[4]);
        if (world != null && stack != null) {
            world.dropItemNaturally(new org.bukkit.Location(world, x + 0.5, y + 0.5, z + 0.5), stack);
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
