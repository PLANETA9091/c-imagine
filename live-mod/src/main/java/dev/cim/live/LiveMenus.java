package dev.cim.live;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Live menus (roadmap M6.3): one session per Paper player with a mod menu open. A session
 * opens the menu through the mod's own code and passes every click to the real
 * {@link AbstractContainerMenu}; what the HUD draws is read back from that menu.
 *
 * <p>Memory: one {@link FacadeLevel} for the whole server (two small data registries, no
 * chunks), and per open menu one player object and the mod's menu. Nothing else.
 */
public final class LiveMenus {

    private static final Logger LOG = LoggerFactory.getLogger("cimlive");

    private static FacadeServerLevel level;
    private static final Map<UUID, SessionServerPlayer> SESSIONS = new HashMap<>();

    private LiveMenus() {
    }

    public static synchronized FacadeServerLevel level() {
        if (level == null) {
            long t0 = System.nanoTime();
            level = FacadeServerLevel.create(new FacadeLevel(LiveRegistries.build()));
            LOG.info("[cimlive] facade level up in {} ms", (System.nanoTime() - t0) / 1_000_000);
        }
        return level;
    }

    /** The session for a Paper player, made on first use. */
    public static synchronized SessionServerPlayer session(UUID id, String name) {
        return SESSIONS.computeIfAbsent(id, k -> SessionServerPlayer.create(level(), new GameProfile(k, name)));
    }

    /** Every live session, for the tick. */
    static synchronized java.util.List<SessionServerPlayer> openSessions() {
        return new java.util.ArrayList<>(SESSIONS.values());
    }

    /** Ends a player's session: the menu's own removed() runs, as on close. */
    public static synchronized void end(UUID id) {
        SessionServerPlayer p = SESSIONS.remove(id);
        if (p != null) {
            p.closeContainer();
        }
    }

    /**
     * Opens a held-item menu the way the client's right click does: the item's own use().
     * Returns the menu the mod built, or null when its code opened none.
     */
    public static AbstractContainerMenu useHeld(SessionServerPlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        stack.use(level(), player, InteractionHand.MAIN_HAND);
        return player.opened();
    }

    /**
     * A right click on a block with an empty hand, as the server's game mode applies it:
     * the block's item use first, then its use without an item when that passes.
     */
    public static AbstractContainerMenu clickBlock(SessionServerPlayer player, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState state = level().getBlockState(pos);
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos).relative(net.minecraft.core.Direction.SOUTH, 0.5),
                net.minecraft.core.Direction.SOUTH, pos, false);
        ItemStack hand = player.getItemInHand(InteractionHand.MAIN_HAND);
        net.minecraft.world.ItemInteractionResult result = state.useItemOn(hand, level(), player,
                InteractionHand.MAIN_HAND, hit);
        if (result == net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            state.useWithoutItem(level(), player, hit);
        }
        return player.opened();
    }

    /** A slot click, exactly as the server applies a container click packet. */
    public static void click(SessionServerPlayer player, int slot, int button, ClickType type) {
        AbstractContainerMenu menu = player.opened();
        if (menu != null && menu.isValidSlotIndex(slot)) {
            menu.clicked(slot, button, type, player);
            menu.broadcastChanges();
        }
    }

    static String inventoryLine(SessionServerPlayer player) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                out.append(out.length() == 0 ? "" : ", ").append(i).append('=').append(describe(stack));
            }
        }
        return out.length() == 0 ? "empty" : out.toString();
    }

    static String describe(ItemStack stack) {
        return stack.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /**
     * Integration self-test, run in the layer at common setup. CIM_LIVE_SELFTEST names the
     * namespaces to cover ("all" for every loaded mod but minecraft and neoforge): every
     * item of them is used by a fresh session the way a right click uses it, and every
     * menu that the mod's own code opens gets the first move a player makes (a stack from
     * the inventory onto the menu's first slot of its own), then a close. One log line per
     * item that did anything, in id order, so the real server and the layer can be diffed.
     */
    /**
     * The protocol Paper drives a placement with: the item set down by a sneaking click on a
     * block below, then the block it became opened with the data it was placed with.
     */
    private static void placeTest(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return;
        }
        UUID uuid = UUID.nameUUIDFromBytes(("cimlive-place-" + itemId).getBytes(StandardCharsets.UTF_8));
        String held = LiveProtocol.encode(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId.trim()))));
        String answer = LiveProtocol.call("use-on\t" + uuid + "\tcimlive\t0\t64\t0\tup\t-\t1\ninv\t0\t" + held);
        String placed = null;
        String be = null;
        String[] at = null;
        for (String line : answer.split("\n")) {
            if (line.startsWith("placed\t")) {
                String[] p = line.split("\t");
                placed = p[1];
                at = new String[]{p[2], p[3], p[4]};
            } else if (line.startsWith("placedbe\t")) {
                be = line.substring("placedbe\t".length());
            }
        }
        LOG.info("[cimlive] selftest place {} -> {}", itemId, placed == null ? "nothing placed: " + answer.split("\n")[0]
                : "placed " + placed + " with " + (be == null ? "no" : be.length() + " chars of") + " block entity data");
        if (placed == null) {
            return;
        }
        String open = LiveProtocol.call("open-block\t" + uuid + "\tcimlive\t" + placed + "\t" + at[0] + "\t" + at[1]
                + "\t" + at[2] + "\nfresh" + (be == null ? "" : "\nbe\t" + be));
        long own = open.lines().filter(l -> l.startsWith("slot\t") && l.split("\t")[3].equals("menu")).count();
        LOG.info("[cimlive] selftest place opened {} own={} be={}", open.split("\n")[0], own, be);
        String closed = LiveProtocol.call("close\t" + uuid);
        String kept = closed.lines().filter(l -> l.startsWith("be\t")).map(l -> l.substring(3)).findFirst().orElse(be);
        // Broken by hand, by the block's own code: what it drops is the item, with its data.
        String broken = LiveProtocol.call("break\t" + uuid + "\tcimlive\t" + placed + "\t" + at[0] + "\t" + at[1]
                + "\t" + at[2] + (kept == null ? "" : "\nbe\t" + kept));
        java.util.List<String> spills = broken.lines().filter(l -> l.startsWith("spill\t")).map(l -> {
            ItemStack drop = LiveProtocol.decode(l.substring(6));
            return BuiltInRegistries.ITEM.getKey(drop.getItem()) + " x" + drop.getCount()
                    + (drop.getComponentsPatch().isEmpty() ? "" : "+data");
        }).toList();
        LOG.info("[cimlive] selftest place broken {}: {}", broken.lines().filter(l -> l.startsWith("broken\t")).findFirst()
                .orElse(broken.split("\n")[0]), spills);
    }

    static void selfTest(String spec) {
        java.util.Set<String> wanted = new java.util.TreeSet<>();
        for (String ns : spec.split("[,\\s]+")) {
            if (!ns.isBlank()) {
                wanted.add(ns.trim());
            }
        }
        boolean all = wanted.contains("all");
        java.util.List<ResourceLocation> items = new java.util.ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String ns = id.getNamespace();
            if (all ? !ns.equals("minecraft") && !ns.equals("neoforge") && !ns.equals("cimlive") : wanted.contains(ns)) {
                items.add(id);
            }
        }
        items.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        // Sessions run on a started server, where SERVER configs are loaded (defaults on a
        // fresh world); the self-test runs at setup, before that, on both stacks alike.
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();

        int menus = 0;
        int threw = 0;
        java.util.List<ResourceLocation> opened = new java.util.ArrayList<>();
        java.util.Set<String> kinds = new java.util.TreeSet<>();
        for (ResourceLocation id : items) {
            UUID uuid = UUID.nameUUIDFromBytes(("cimlive-" + id).getBytes(StandardCharsets.UTF_8));
            try {
                SessionServerPlayer player = session(uuid, "cimlive");
                AbstractContainerMenu menu = useHeld(player, new ItemStack(BuiltInRegistries.ITEM.get(id)));
                if (menu == null) {
                    end(uuid);
                    continue;
                }
                menus++;
                opened.add(id);
                kinds.add(String.valueOf(menuType(menu)));
                LOG.info("[cimlive] selftest item {} opens {}", id, exercise(player, menu, uuid));
            } catch (Throwable t) {
                threw++;
                // The simple class name only: the layer's classes carry relocated packages.
                LOG.info("[cimlive] selftest item {} threw {}", id, t.getClass().getSimpleName());
                // Outside the compared lines: names differ by relocation, the cause does not.
                LOG.info("[cimlive] detail {}: {}", id, detail(t));
                SESSIONS.remove(uuid);
            }
        }
        // The protocol Paper drives sessions with, on every item that opened a menu: the item
        // in hand and 5 diamonds in the inventory, the diamonds moved onto the menu's first
        // slot of its own by two clicks, a close; all through text, as the plugin sends it.
        for (ResourceLocation id : opened) {
            UUID uuid = UUID.nameUUIDFromBytes(("cimlive-proto-" + id).getBytes(StandardCharsets.UTF_8));
            String held = LiveProtocol.encode(new ItemStack(BuiltInRegistries.ITEM.get(id)));
            String diamonds = LiveProtocol.encode(new ItemStack(Items.DIAMOND, 5));
            String open = LiveProtocol.call("open-item\t" + uuid + "\tcimlive\ninv\t0\t" + held + "\ninv\t9\t" + diamonds);
            String from = null;
            String to = null;
            for (String line : open.split("\n")) {
                String[] p = line.split("\t");
                if (p[0].equals("slot") && p[3].equals("player") && p[4].equals("9") && from == null) {
                    from = p[2];
                }
                if (p[0].equals("slot") && p[3].equals("menu") && to == null) {
                    to = p[2];
                }
            }
            String after = open;
            if (from != null && to != null) {
                LiveProtocol.call("click\t" + uuid + "\t" + from + "\t0\tPICKUP");
                after = LiveProtocol.call("click\t" + uuid + "\t" + to + "\t0\tPICKUP");
            }
            String closed = LiveProtocol.call("close\t" + uuid);
            LOG.info("[cimlive] selftest protocol {}: {} | after move {} | after close {}", id,
                    firstLine(open), summary(after), summary(closed));
        }
        // Blocks: every block of these mods that holds a block entity, put down on its own
        // spot and right-clicked with an empty hand, in the order the server tries a click.
        java.util.List<ResourceLocation> blocks = new java.util.ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet()) {
            String ns = id.getNamespace();
            boolean in = all ? !ns.equals("minecraft") && !ns.equals("neoforge") && !ns.equals("cimlive") : wanted.contains(ns);
            if (in && BuiltInRegistries.BLOCK.get(id) instanceof net.minecraft.world.level.block.EntityBlock) {
                blocks.add(id);
            }
        }
        blocks.sort(java.util.Comparator.comparing(ResourceLocation::toString));
        int blockMenus = 0;
        java.util.List<ResourceLocation> openedBlocks = new java.util.ArrayList<>();
        int blockThrew = 0;
        for (int i = 0; i < blocks.size(); i++) {
            ResourceLocation id = blocks.get(i);
            UUID uuid = UUID.nameUUIDFromBytes(("cimlive-block-" + id).getBytes(StandardCharsets.UTF_8));
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(i * 4, 64, 0);
            try {
                SessionServerPlayer player = session(uuid, "cimlive");
                player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
                net.minecraft.world.level.block.state.BlockState state =
                        BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                level().setBlock(pos, state, 3);
                AbstractContainerMenu menu = clickBlock(player, pos);
                if (menu == null) {
                    end(uuid);
                } else {
                    blockMenus++;
                    openedBlocks.add(id);
                    kinds.add(String.valueOf(menuType(menu)));
                    LOG.info("[cimlive] selftest block {} opens {}", id, exercise(player, menu, uuid));
                }
            } catch (Throwable t) {
                blockThrew++;
                LOG.info("[cimlive] selftest block {} threw {}", id, t.getClass().getSimpleName());
                LOG.info("[cimlive] detail {}: {}", id, detail(t));
                SESSIONS.remove(uuid);
            }
        }
        level().store().clearBlocks();
        LOG.info("[cimlive] selftest blocks: blocks={} menus={} threw={}", blocks.size(), blockMenus, blockThrew);
        pressButtons(opened, openedBlocks);
        widgetStates(openedBlocks);
        LOG.info("[cimlive] selftest summary: items={} menus={} kinds={} threw={}", items.size(), menus, kinds.size(), threw);
        LOG.info("[cimlive] selftest kinds {}", kinds);
        placeTest(System.getenv("CIM_LIVE_PLACE"));
        LOG.info("[cimlive] selftest ok");
    }

    /**
     * The first move a player makes in a menu (a stack from the inventory onto the menu's
     * first slot of its own), then the close; what the mod's code made of both.
     */
    private static String exercise(SessionServerPlayer player, AbstractContainerMenu menu, UUID uuid) {
        ResourceLocation type = menuType(menu);
        int own = 0;
        int firstOwn = -1;
        int firstMine = -1;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()) {
                if (firstMine < 0 && slot.getContainerSlot() >= 9 && !slot.hasItem()) {
                    firstMine = i;
                }
            } else {
                own++;
                if (firstOwn < 0) {
                    firstOwn = i;
                }
            }
        }
        String move = "no slot of its own";
        if (firstOwn >= 0 && firstMine >= 0) {
            menu.slots.get(firstMine).set(new ItemStack(Items.DIAMOND, 5));
            click(player, firstMine, 0, ClickType.PICKUP);
            click(player, firstOwn, 0, ClickType.PICKUP);
            move = "slot " + firstOwn + " holds " + describe(menu.slots.get(firstOwn).getItem())
                    + ", carried " + describe(menu.getCarried());
        }
        end(uuid);
        StringBuilder said = new StringBuilder();
        for (net.minecraft.network.protocol.Packet<?> packet : player.session().drain()) {
            said.append(said.length() == 0 ? "" : "+").append(packet.getClass().getSimpleName());
        }
        return type + " (" + menu.slots.size() + " slots, " + own + " own); move: " + move + "; close sent "
                + (said.length() == 0 ? "nothing" : said) + "; inventory " + inventoryLine(player);
    }

    /**
     * Every scanner-proven button of every menu that opened: a fresh menu, the button's own
     * payload built and handed to the mod's handler, then what the mod changed (the held
     * item's data for item menus, the block entity's data for block menus).
     */
    private static void pressButtons(java.util.List<ResourceLocation> items, java.util.List<ResourceLocation> blocks) {
        String file = System.getenv("CIM_LIVE_BUTTONS");
        if (file == null || file.isBlank()) {
            return;
        }
        java.util.List<String[]> buttons = new java.util.ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(file), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length >= 6) {
                    buttons.add(p);
                }
            }
        } catch (java.io.IOException e) {
            LOG.info("[cimlive] selftest buttons: cannot read {}: {}", file, e.toString());
            return;
        }
        int pressed = 0;
        int handled = 0;
        for (ResourceLocation id : items) {
            for (String[] b : forMenuOf(buttons, () -> {
                SessionServerPlayer probe = session(UUID.randomUUID(), "probe");
                AbstractContainerMenu m = useHeld(probe, new ItemStack(BuiltInRegistries.ITEM.get(id)));
                String c = m == null ? null : m.getClass().getName();
                end(probe.getUUID());
                return c;
            })) {
                pressed++;
                UUID uuid = UUID.nameUUIDFromBytes(("cimlive-button-" + id + b[2] + b[4]).getBytes(StandardCharsets.UTF_8));
                String held = LiveProtocol.encode(new ItemStack(BuiltInRegistries.ITEM.get(id)));
                LiveProtocol.call("open-item\t" + uuid + "\tcimlive\ninv\t0\t" + held);
                String answer = LiveProtocol.call(buttonCommand(uuid, b));
                String result = firstLine(answer);
                if (result.startsWith("pressed handled")) {
                    handled++;
                }
                String closed = LiveProtocol.call("close\t" + uuid);
                String after = "-";
                for (String line : closed.split("\n")) {
                    if (line.startsWith("inv\t0\t")) {
                        after = itemChange(held, line.substring(6));
                    }
                }
                LOG.info("[cimlive] selftest button item {} #{} '{}' {}: {} | held after: {}", id, b[2], b[3],
                        simple(b[4]), result, after);
            }
        }
        for (int i = 0; i < blocks.size(); i++) {
            ResourceLocation id = blocks.get(i);
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(i * 4, 80, 8);
            java.util.function.Supplier<String> menuClass = () -> {
                level().setBlock(pos, BuiltInRegistries.BLOCK.get(id).defaultBlockState(), 3);
                SessionServerPlayer probe = session(UUID.randomUUID(), "probe");
                AbstractContainerMenu m = clickBlock(probe, pos);
                String c = m == null ? null : m.getClass().getName();
                end(probe.getUUID());
                return c;
            };
            for (String[] b : forMenuOf(buttons, menuClass)) {
                pressed++;
                UUID uuid = UUID.nameUUIDFromBytes(("cimlive-bbutton-" + id + b[2] + b[4]).getBytes(StandardCharsets.UTF_8));
                level().setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                level().setBlock(pos, BuiltInRegistries.BLOCK.get(id).defaultBlockState(), 3);
                SessionServerPlayer player = session(uuid, "cimlive");
                player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
                net.minecraft.world.level.block.entity.BlockEntity before = level().getBlockEntity(pos);
                net.minecraft.nbt.CompoundTag was = before == null ? new net.minecraft.nbt.CompoundTag()
                        : before.saveWithoutMetadata(level().registryAccess());
                String result;
                try {
                    clickBlock(player, pos);
                    String answer = LiveProtocol.call(buttonCommand(uuid, b));
                    result = firstLine(answer);
                } catch (Throwable t) {
                    result = "threw " + t.getClass().getSimpleName();
                }
                if (result.startsWith("pressed handled")) {
                    handled++;
                }
                LiveProtocol.call("close\t" + uuid);
                net.minecraft.world.level.block.entity.BlockEntity be = level().getBlockEntity(pos);
                String after = be == null ? "-" : changedKeys(was, be.saveWithoutMetadata(level().registryAccess()));
                LOG.info("[cimlive] selftest button block {} #{} '{}' {}: {} | block entity after: {}", id, b[2], b[3],
                        simple(b[4]), result, after);
            }
        }
        level().store().clearBlocks();
        LOG.info("[cimlive] selftest buttons: proven={} pressed={} handled={}", buttons.size(), pressed, handled);
    }

    /**
     * Every stateful widget of every block menu that opened, evaluated on the fresh block's real
     * objects through the protocol's widgets command, exactly as the HUD asks.
     */
    private static void widgetStates(java.util.List<ResourceLocation> blocks) {
        String file = System.getenv("CIM_LIVE_STATEFUL");
        if (file == null || file.isBlank()) {
            return;
        }
        java.util.List<String[]> widgets = new java.util.ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(file), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t", -1);
                if (p.length >= 5) {
                    widgets.add(p);
                }
            }
        } catch (java.io.IOException e) {
            LOG.info("[cimlive] selftest widgets: cannot read {}: {}", file, e.toString());
            return;
        }
        int evaluated = 0;
        int grounded = 0;
        for (int i = 0; i < blocks.size(); i++) {
            ResourceLocation id = blocks.get(i);
            UUID uuid = UUID.nameUUIDFromBytes(("cimlive-widgets-" + id).getBytes(StandardCharsets.UTF_8));
            String open = LiveProtocol.call("open-block\t" + uuid + "\tcimlive\t" + id + "\t" + (i * 4) + "\t96\t16");
            String cls = null;
            for (String line : open.split("\n")) {
                if (line.startsWith("menu\t")) {
                    cls = line.split("\t")[2].replace('.', '/');
                }
            }
            java.util.List<String[]> mine = new java.util.ArrayList<>();
            for (String[] w : widgets) {
                if (w[0].equals(cls)) {
                    mine.add(w);
                }
            }
            if (!mine.isEmpty()) {
                StringBuilder cmd = new StringBuilder("widgets\t").append(uuid);
                for (String[] w : mine) {
                    cmd.append('\n').append(w[1]).append('\t').append(w[3]).append('\t').append(w[4]);
                }
                StringBuilder states = new StringBuilder();
                for (String line : LiveProtocol.call(cmd.toString()).split("\n")) {
                    String[] p = line.split("\t");
                    if (p.length == 4) {
                        evaluated++;
                        if (!p[2].equals("null") && !p[3].equals("null")) {
                            grounded++;
                        }
                        String label = "";
                        for (String[] w : mine) {
                            if (w[1].equals(p[1])) {
                                label = w[2];
                            }
                        }
                        states.append(" #").append(p[1]).append(" '").append(label).append("' active=").append(p[2])
                                .append(" visible=").append(p[3]).append(';');
                    }
                }
                LOG.info("[cimlive] selftest widgets block {}:{}", id, states);
            }
            LiveProtocol.call("close\t" + uuid);
        }
        level().store().clearBlocks();
        LOG.info("[cimlive] selftest widgets: evaluated={} grounded={}", evaluated, grounded);
    }

    /** The buttons whose screen belongs to the menu class the opener produces (or a superclass of it). */
    private static java.util.List<String[]> forMenuOf(java.util.List<String[]> buttons,
                                                     java.util.function.Supplier<String> opener) {
        String cls;
        try {
            cls = opener.get();
        } catch (Throwable t) {
            return java.util.List.of();
        }
        if (cls == null) {
            return java.util.List.of();
        }
        java.util.Set<String> chain = new java.util.HashSet<>();
        try {
            for (Class<?> c = Class.forName(cls, false, LiveMenus.class.getClassLoader()); c != null; c = c.getSuperclass()) {
                chain.add(c.getName().replace('.', '/'));
            }
        } catch (Throwable t) {
            chain.add(cls.replace('.', '/'));
        }
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] b : buttons) {
            if (chain.contains(b[0])) {
                out.add(b);
            }
        }
        return out;
    }

    private static String buttonCommand(UUID uuid, String[] b) {
        StringBuilder cmd = new StringBuilder("button\t").append(uuid).append('\t').append(b[4]).append('\t').append(b[5]);
        for (int k = 6; k < b.length; k++) {
            if (!b[k].isEmpty()) {
                cmd.append('\t').append(b[k]);
            }
        }
        return cmd.toString();
    }

    private static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    /**
     * What a press changed in a block entity: the keys whose values differ, not the values. A
     * block entity may hold a random id (Create's toolbox does), so a hash of all of it would
     * differ between any two runs; the change is what the mod's code did.
     */
    private static String changedKeys(net.minecraft.nbt.CompoundTag was, net.minecraft.nbt.CompoundTag now) {
        java.util.Set<String> keys = new java.util.TreeSet<>(was.getAllKeys());
        keys.addAll(now.getAllKeys());
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (String k : keys) {
            if (!java.util.Objects.equals(was.get(k), now.get(k))) {
                changed.add(k);
            }
        }
        return changed.isEmpty() ? "unchanged" : "changed " + changed;
    }

    /** What a press changed on the held item: the data components that differ, with their values. */
    private static String itemChange(String beforeJson, String afterJson) {
        ItemStack before = LiveProtocol.decode(beforeJson);
        ItemStack after = LiveProtocol.decode(afterJson);
        java.util.List<String> changed = new java.util.ArrayList<>();
        java.util.Set<net.minecraft.core.component.DataComponentType<?>> types = new java.util.LinkedHashSet<>();
        before.getComponents().forEach(c -> types.add(c.type()));
        after.getComponents().forEach(c -> types.add(c.type()));
        for (net.minecraft.core.component.DataComponentType<?> t : types) {
            Object a = before.get(t);
            Object b = after.get(t);
            if (!java.util.Objects.equals(a, b)) {
                String v = String.valueOf(b);
                changed.add(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(t) + "=" + (v.length() > 80 ? v.substring(0, 80) + "..." : v));
            }
        }
        changed.sort(null);
        return changed.isEmpty() ? "unchanged" : "changed " + changed;
    }

    /** A short, stack-independent stamp of some data: its length and hash. */
    private static String fingerprint(String data) {
        return data.length() + "#" + Integer.toHexString(data.hashCode());
    }

    private static String firstLine(String state) {
        int nl = state.indexOf('\n');
        return nl < 0 ? state : state.substring(0, nl).replace('\t', ' ');
    }

    /** A protocol answer in short: the menu, its first own slot, the cursor, the inventory. */
    private static String summary(String state) {
        StringBuilder out = new StringBuilder();
        boolean ownShown = false;
        for (String line : state.split("\n")) {
            String[] p = line.split("\t");
            if (p[0].equals("slot") && p[3].equals("menu") && !ownShown) {
                ownShown = true;
                out.append("first own ").append(shortStack(p[5])).append("; ");
            } else if (p[0].equals("carried")) {
                out.append("carried ").append(shortStack(p[1])).append("; ");
            } else if (p[0].equals("inv")) {
                out.append(p[1]).append('=').append(shortStack(p[2])).append(' ');
            } else if (p[0].equals("drop")) {
                out.append("dropped ").append(shortStack(p[1])).append(' ');
            } else if (p[0].equals("error")) {
                out.append(line);
            }
        }
        return out.toString().trim();
    }

    private static String shortStack(String json) {
        if (json.equals("-")) {
            return "empty";
        }
        ItemStack stack = LiveProtocol.decode(json);
        return describe(stack) + (stack.getComponentsPatch().isEmpty() ? "" : "+data");
    }

    /** The root cause and the first frames that are not the JDK's: where a mod met the facade. */
    private static String detail(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        StringBuilder out = new StringBuilder(root.getClass().getSimpleName()).append(": ").append(root.getMessage());
        int shown = 0;
        for (StackTraceElement e : root.getStackTrace()) {
            if (e.getClassName().startsWith("java.") || e.getClassName().startsWith("com.google.")) {
                continue;
            }
            out.append(" | ").append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1))
                    .append('.').append(e.getMethodName()).append(':').append(e.getLineNumber());
            if (++shown == 5) {
                break;
            }
        }
        return out.toString();
    }

    private static ResourceLocation menuType(AbstractContainerMenu menu) {
        try {
            return BuiltInRegistries.MENU.getKey(menu.getType());
        } catch (UnsupportedOperationException none) {
            return null;
        }
    }
}
