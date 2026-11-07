package dev.cim.live;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The session player as mods expect it: an {@code instanceof ServerPlayer}. Mods check that
 * before they open a menu (Create's filter does), and a ServerPlayer is constructed only by
 * a running MinecraftServer with a ServerLevel. So none is constructed: the object is
 * allocated, and every field a Player has (inventory, menus, synced data, attributes) is
 * taken from a plain {@link SessionPlayer} built on the facade level, with that player's
 * back-references pointed here. ServerPlayer's own fields stay empty; the methods that
 * would read them (game mode, the network) are answered by this class.
 *
 * <p>Memory: one Player's worth of objects, the same as the template it replaces.
 */
public final class SessionServerPlayer extends ServerPlayer {

    private AbstractContainerMenu opened;
    private int nextId;

    /** Never called: the instance comes from {@link #create}. */
    private SessionServerPlayer() {
        super(null, null, null, null);
        throw new AssertionError();
    }

    static SessionServerPlayer create(Level level, GameProfile profile) {
        SessionPlayer template = new SessionPlayer(level, profile);
        SessionServerPlayer self = allocate(SessionServerPlayer.class);
        Map<Object, Boolean> rewired = new IdentityHashMap<>();
        for (Class<?> c = Player.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(template);
                    field.set(self, value);
                    // The player's parts point back at the player that made them
                    // (inventory.player, the synced data's entity, the menu's owner).
                    if (value != null && rewired.put(value, Boolean.TRUE) == null) {
                        rewire(value, template, self);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalStateException("cannot take field " + c.getSimpleName() + "." + field.getName(), e);
                }
            }
        }
        self.containerMenu = self.inventoryMenu;
        self.connection = SessionConnection.create(self);
        return self;
    }

    /** An instance with no constructor run: the classes a running server alone would construct. */
    static <T> T allocate(Class<T> type) {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return type.cast(((sun.misc.Unsafe) f.get(null)).allocateInstance(type));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate " + type.getSimpleName(), e);
        }
    }

    /** The session's network side: what the mod's code sent to this player. */
    public SessionConnection session() {
        return (SessionConnection) connection;
    }

    private static void rewire(Object part, Object from, Object to) throws IllegalAccessException {
        if (part.getClass().isArray() || part.getClass().getName().startsWith("java.")) {
            return;
        }
        for (Class<?> c = part.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                } catch (RuntimeException inaccessible) {
                    continue;
                }
                if (field.get(part) == from) {
                    field.set(part, to);
                }
            }
        }
    }

    /** The menu the mod's code opened last, or null. */
    public AbstractContainerMenu opened() {
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
        if (opened != null) {
            opened.removed(this);
        }
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
        doCloseContainer();
    }

    @Override
    public void doCloseContainer() {
        if (opened != null) {
            opened.removed(this);
        }
        opened = null;
        containerMenu = inventoryMenu;
    }

    @Override
    public void awardStat(net.minecraft.stats.Stat<?> stat, int amount) {
        // Statistics are the Paper player's: the server keeps them, not the session.
    }

    @Override
    public void resetStat(net.minecraft.stats.Stat<?> stat) {
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public void tick() {
        // The session never ticks as an entity; the menu is what lives.
    }
}
