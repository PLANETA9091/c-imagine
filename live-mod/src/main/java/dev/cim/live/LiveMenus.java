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

    private static FacadeLevel level;
    private static final Map<UUID, SessionServerPlayer> SESSIONS = new HashMap<>();

    private LiveMenus() {
    }

    static synchronized FacadeLevel level() {
        if (level == null) {
            long t0 = System.nanoTime();
            level = new FacadeLevel(LiveRegistries.build());
            LOG.info("[cimlive] facade level up in {} ms", (System.nanoTime() - t0) / 1_000_000);
        }
        return level;
    }

    /** The session for a Paper player, made on first use. */
    public static synchronized SessionServerPlayer session(UUID id, String name) {
        return SESSIONS.computeIfAbsent(id, k -> SessionServerPlayer.create(level(), new GameProfile(k, name)));
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
        level().clearBlocks();
        LOG.info("[cimlive] selftest blocks: blocks={} menus={} threw={}", blocks.size(), blockMenus, blockThrew);
        LOG.info("[cimlive] selftest summary: items={} menus={} kinds={} threw={}", items.size(), menus, kinds.size(), threw);
        LOG.info("[cimlive] selftest kinds {}", kinds);
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
