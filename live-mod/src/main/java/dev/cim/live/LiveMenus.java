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

    /** A slot click, exactly as the server applies a container click packet. */
    public static void click(SessionServerPlayer player, int slot, int button, ClickType type) {
        AbstractContainerMenu menu = player.opened();
        if (menu != null && menu.isValidSlotIndex(slot)) {
            menu.clicked(slot, button, type, player);
            menu.broadcastChanges();
        }
    }

    static String describe(ItemStack stack) {
        return stack.isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /**
     * Integration self-test, run in the layer at common setup when CIM_LIVE_SELFTEST names an
     * item: open its menu through the item's own code, then move a stack into the first menu
     * slot the way two clicks do it, and log what the mod's menu made of it.
     */
    static void selfTest(String itemId) {
        try {
            SessionServerPlayer player = session(UUID.nameUUIDFromBytes("cimlive-selftest".getBytes(StandardCharsets.UTF_8)),
                    "cimlive");
            ItemStack held = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)));
            AbstractContainerMenu menu = useHeld(player, held);
            if (menu == null) {
                LOG.info("[cimlive] selftest {}: no menu opened", itemId);
                return;
            }
            int own = 0;
            int firstOwn = -1;
            int firstMine = -1;
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                if (s.container == player.getInventory()) {
                    if (firstMine < 0 && s.getContainerSlot() >= 9) {
                        firstMine = i;
                    }
                } else {
                    own++;
                    if (firstOwn < 0) {
                        firstOwn = i;
                    }
                }
            }
            LOG.info("[cimlive] selftest menu {} class={} slots={} menuSlots={}",
                    BuiltInRegistries.MENU.getKey(menu.getType()), menu.getClass().getSimpleName(), menu.slots.size(), own);
            if (firstOwn >= 0 && firstMine >= 0) {
                menu.slots.get(firstMine).set(new ItemStack(Items.DIAMOND, 5));
                click(player, firstMine, 0, ClickType.PICKUP);
                click(player, firstOwn, 0, ClickType.PICKUP);
                LOG.info("[cimlive] selftest move: menu slot {} holds {}, inventory slot {} holds {}, carried {}",
                        firstOwn, describe(menu.slots.get(firstOwn).getItem()), firstMine,
                        describe(menu.slots.get(firstMine).getItem()), describe(menu.getCarried()));
            }
            for (int b = 0; b < 8; b++) {
                boolean took = menu.clickMenuButton(player, b);
                if (took) {
                    LOG.info("[cimlive] selftest button {} handled by the menu", b);
                }
            }
            end(player.getUUID());
            LOG.info("[cimlive] selftest ok");
        } catch (Throwable t) {
            LOG.error("[cimlive] selftest FAILED: {}", t.toString(), t);
        }
    }
}
