package dev.cim.hud;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;

/** Mounted shulkers can hold fractional coordinates and supply vanilla entity collision. */
final class DoorCollision {
    record Proxy(ItemDisplay mount, Shulker shell) {
        void remove() {
            pushTeam().removeEntry(shell.getUniqueId().toString());
            shell.remove();
            mount.remove();
        }
    }

    static List<Proxy> spawn(Block owner, List<BoundingBox> boxes) {
        var proxies = new ArrayList<Proxy>();
        try {
            for (BoundingBox box : boxes) {
                for (BoundingBox cube : cubes(box)) {
                    var center = owner.getLocation().add(cube.getCenterX(), cube.getMinY(), cube.getCenterZ());
                    ItemDisplay mount = owner.getWorld().spawn(center, ItemDisplay.class, entity ->
                            entity.setPersistent(false));
                    try {
                        var shell = owner.getWorld().spawn(center, Shulker.class, entity -> {
                            entity.setPersistent(false);
                            entity.setAI(false);
                            entity.setAware(false);
                            // Paper's mob-push filter is separate from Shulker.canBeCollidedWith.
                            entity.setCollidable(false);
                            entity.setGravity(false);
                            entity.setSilent(true);
                            entity.setInvisible(true);
                            entity.setInvulnerable(true);
                            entity.setColor(DyeColor.MAGENTA);
                            entity.setPeek(0);
                            entity.getAttribute(Attribute.SCALE).setBaseValue(cube.getWidthX());
                        });
                        proxies.add(new Proxy(mount, shell));
                        pushTeam().addEntry(shell.getUniqueId().toString());
                        if (!mount.addPassenger(shell)) {
                            throw new IllegalStateException("Could not mount shulker collision proxy");
                        }
                        positionProxy(mount, shell);
                    } catch (RuntimeException failure) {
                        mount.remove();
                        throw failure;
                    }
                }
            }
            return proxies;
        } catch (RuntimeException failure) {
            proxies.forEach(Proxy::remove);
            throw failure;
        }
    }

    private static Team pushTeam() {
        var scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam("cim_door_shell");
        if (team == null) {
            team = scoreboard.registerNewTeam("cim_door_shell");
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        return team;
    }

    // Mount positioning normally waits for rideTick. A default-sized box at the
    // spawn cell until then can clip a player the instant a door changes state.
    private static void positionProxy(ItemDisplay mount, Shulker shell) {
        try {
            Object carrier = mount.getClass().getMethod("getHandle").invoke(mount);
            Object rider = shell.getClass().getMethod("getHandle").invoke(shell);
            Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
            entity.getMethod("positionRider", entity).invoke(carrier, rider);
            entity.getMethod("refreshDimensions").invoke(rider);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not initialize shulker collision bounds", failure);
        }
    }

    /** Cover the source box with cubes contained in it. Edge cubes overlap to close fractional gaps. */
    static List<BoundingBox> cubes(BoundingBox box) {
        double side = Math.min(box.getWidthX(), Math.min(box.getHeight(), box.getWidthZ()));
        if (side < 1.0 / 16) {
            throw new IllegalArgumentException("Door collision is thinner than vanilla shulker scale");
        }
        int columns = (int) Math.ceil(box.getWidthX() / side);
        int rows = (int) Math.ceil(box.getHeight() / side);
        int depth = (int) Math.ceil(box.getWidthZ() / side);
        if ((long) columns * rows * depth > 128) {
            throw new IllegalArgumentException("Door collision exceeds the shulker budget");
        }
        var cubes = new ArrayList<BoundingBox>(columns * rows * depth);
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                for (int z = 0; z < depth; z++) {
                    double minX = Math.min(box.getMinX() + x * side, box.getMaxX() - side);
                    double minY = Math.min(box.getMinY() + y * side, box.getMaxY() - side);
                    double minZ = Math.min(box.getMinZ() + z * side, box.getMaxZ() - side);
                    cubes.add(new BoundingBox(minX, minY, minZ, minX + side, minY + side, minZ + side));
                }
            }
        }
        return cubes;
    }
}
