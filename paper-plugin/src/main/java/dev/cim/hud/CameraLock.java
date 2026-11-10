package dev.cim.hud;

import io.papermc.paper.entity.TeleportFlag;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.event.entity.EntityDismountEvent;

/**
 * Holds the view still while the mouse drives a HUD pointer.
 *
 * <p>Mouse movement always turns the local player, and a vanilla client only reports
 * look through movement packets. Pointing the camera at another entity would freeze
 * the view but also stop {@code sendPosition}. A passenger is different: in the 26.3
 * client {@code LocalPlayer.sendChanges} sends its look every tick wherever the camera
 * is, and sends the movement keys too. So the player sits on an invisible seat, the
 * client's camera moves to a fixed display entity at their eyes, and look keeps
 * arriving for the pointer. The server walks the seat from the movement keys.
 */
public final class CameraLock implements Listener {

    private static final double WALK_PER_TICK = 0.2;

    private static final class Lock {
        final Set<String> owners = new HashSet<>();
        final Map<String, Runnable> onExit = new HashMap<>();
        ArmorStand seat;
        ItemDisplay camera;
        float yaw;
        boolean releasing;
    }

    private final JavaPlugin plugin;
    private final Logger log;
    private final Map<UUID, Lock> locks = new HashMap<>();

    public CameraLock(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::walk, 1L, 1L);
    }

    /** Freezes the view for {@code owner}. {@code onExit} runs if the player gets off. */
    public void engage(Player player, String owner, Runnable onExit) {
        Lock lock = locks.get(player.getUniqueId());
        if (lock != null) {
            lock.owners.add(owner);
            lock.onExit.put(owner, onExit);
            return;
        }
        Location feet = player.getLocation();
        Location eyes = player.getEyeLocation();
        Lock made = new Lock();
        made.owners.add(owner);
        made.onExit.put(owner, onExit);
        made.yaw = feet.getYaw();
        made.seat = player.getWorld().spawn(feet, ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setPersistent(false);
            s.setSilent(true);
            hideFromOthers(s, player);
        });
        made.camera = player.getWorld().spawn(eyes, ItemDisplay.class, c -> {
            c.setPersistent(false);
            c.setTeleportDuration(2);
            hideFromOthers(c, player);
        });
        if (!made.seat.addPassenger(player)) {
            log.warning("[cim-cam] " + player.getName() + " could not take the seat");
            made.seat.remove();
            made.camera.remove();
            return;
        }
        locks.put(player.getUniqueId(), made);
        // The client ignores a camera it has not been told about yet, so wait for tracking.
        for (long delay : new long[]{3L, 10L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (locks.get(player.getUniqueId()) == made) {
                    setCamera(player, made.camera);
                }
            }, delay);
        }
        log.info("[cim-cam] " + player.getName() + " view locked by " + owner);
    }

    public void release(Player player, String owner) {
        Lock lock = locks.get(player.getUniqueId());
        if (lock == null) {
            return;
        }
        lock.owners.remove(owner);
        lock.onExit.remove(owner);
        if (lock.owners.isEmpty()) {
            drop(player, lock);
        }
    }

    public boolean locked(Player player) {
        return locks.containsKey(player.getUniqueId());
    }

    public void releaseAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Lock lock = locks.get(player.getUniqueId());
            if (lock != null) {
                drop(player, lock);
            }
        }
    }

    private void drop(Player player, Lock lock) {
        lock.releasing = true;
        locks.remove(player.getUniqueId());
        setCamera(player, player);
        Location at = lock.seat.getLocation();
        at.setYaw(lock.yaw);
        at.setPitch(lock.camera.getPitch());
        lock.seat.removePassenger(player);
        lock.seat.remove();
        lock.camera.remove();
        // The look wandered with the pointer; hand back the view the player left.
        player.teleport(at);
        log.info("[cim-cam] " + player.getName() + " view released");
    }

    private void hideFromOthers(Entity entity, Player viewer) {
        entity.setVisibleByDefault(false);
        viewer.showEntity(plugin, entity);
    }

    /** Sent directly: Paper only exposes this for spectators. */
    private void setCamera(Player player, Entity target) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object entity = target.getClass().getMethod("getHandle").invoke(target);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Object packet = Class.forName("net.minecraft.network.protocol.game.ClientboundSetCameraPacket")
                    .getConstructor(entityType).newInstance(entity);
            Field connection = handle.getClass().getField("connection");
            Object listener = connection.get(handle);
            Class<?> packetType = Class.forName("net.minecraft.network.protocol.Packet");
            Method send = listener.getClass().getMethod("send", packetType);
            send.invoke(listener, packet);
        } catch (ReflectiveOperationException e) {
            log.warning("[cim-cam] camera packet failed: " + e);
        }
    }

    /** Walks the seat from the movement keys; a screen holds the player still, as a real one does. */
    private void walk() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Lock lock = locks.get(player.getUniqueId());
            if (lock == null || lock.owners.contains("screen")) {
                continue;
            }
            org.bukkit.Input input = player.getCurrentInput();
            double forward = (input.isForward() ? 1 : 0) - (input.isBackward() ? 1 : 0);
            double strafe = (input.isLeft() ? 1 : 0) - (input.isRight() ? 1 : 0);
            if (forward == 0 && strafe == 0) {
                continue;
            }
            Vector step = walkStep(lock.yaw, forward, strafe);
            Location next = settle(lock.seat.getLocation().add(step));
            if (next == null) {
                continue;
            }
            double rise = next.getY() - lock.seat.getLocation().getY();
            lock.seat.teleport(next, TeleportFlag.EntityState.RETAIN_PASSENGERS);
            lock.camera.teleport(lock.camera.getLocation().add(step.getX(), rise, step.getZ()));
        }
    }

    /** One tick of walking, along the frozen view: yaw 0 faces +Z, as in vanilla. */
    static Vector walkStep(float yaw, double forward, double strafe) {
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        double length = Math.sqrt(forward * forward + strafe * strafe);
        double f = forward / length * WALK_PER_TICK;
        double s = strafe / length * WALK_PER_TICK;
        return new Vector(-sin * f + cos * s, 0, cos * f + sin * s);
    }

    /** Feet position after stepping up or down one block, or null when a wall is in the way. */
    private static Location settle(Location at) {
        for (int dy : new int[]{0, 1, -1}) {
            Location tryAt = at.clone().add(0, dy, 0);
            Block feet = tryAt.getBlock();
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable()
                    && !feet.getRelative(0, -1, 0).isPassable()) {
                return tryAt;
            }
        }
        return null;
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Lock lock = locks.get(player.getUniqueId());
        if (lock == null || lock.releasing) {
            return;
        }
        // Sneaking is the way off the seat, so it closes whatever held the view.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Runnable exit : Map.copyOf(lock.onExit).values()) {
                exit.run();
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Lock lock = locks.get(event.getPlayer().getUniqueId());
        if (lock != null) {
            drop(event.getPlayer(), lock);
        }
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code CameraLock --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        Vector north = walkStep(180f, 1, 0);
        bad += check(Math.abs(north.getZ() + 0.2) < 1e-9 && Math.abs(north.getX()) < 1e-9, "yaw 180 walks to -Z");
        Vector south = walkStep(0f, 1, 0);
        bad += check(Math.abs(south.getZ() - 0.2) < 1e-9, "yaw 0 walks to +Z");
        Vector left = walkStep(0f, 0, 1);
        bad += check(Math.abs(left.getX() - 0.2) < 1e-9, "strafing left at yaw 0 goes +X, as vanilla does");
        Vector diagonal = walkStep(0f, 1, 1);
        bad += check(Math.abs(diagonal.length() - 0.2) < 1e-9, "diagonal walking is not faster");
        System.out.println(bad == 0 ? "CameraLock selftest: ALL PASS" : "CameraLock selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
