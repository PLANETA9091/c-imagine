package dev.cim.live;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;

import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The layer's stand-in for one Paper player while a mod menu is open: same UUID and
 * name, an inventory the session mirrors from Paper. When the mod's own code opens a
 * menu for it (an item's use, a block's click), the menu is built right here with the
 * mod's real MenuProvider, the way a server builds it for a real player.
 */
final class SessionPlayer extends Player {

    private AbstractContainerMenu opened;
    private int nextId;

    SessionPlayer(Level level, GameProfile profile) {
        super(level, BlockPos.ZERO, 0f, profile);
    }

    /** The menu the mod's code opened last, or null. */
    AbstractContainerMenu opened() {
        return opened;
    }

    @Override
    public OptionalInt openMenu(MenuProvider provider) {
        return build(provider);
    }

    @Override
    public OptionalInt openMenu(MenuProvider provider, Consumer<RegistryFriendlyByteBuf> extra) {
        return build(provider);
    }

    private OptionalInt build(MenuProvider provider) {
        nextId = nextId % 100 + 1;
        AbstractContainerMenu menu = provider.createMenu(nextId, getInventory(), this);
        if (menu == null) {
            return OptionalInt.empty();
        }
        opened = menu;
        containerMenu = menu;
        return OptionalInt.of(nextId);
    }

    @Override
    public void closeContainer() {
        if (opened != null) {
            opened.removed(this);
        }
        opened = null;
        containerMenu = inventoryMenu;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }
}
