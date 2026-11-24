package dev.cim.hud;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/** The outline and selection shape of a door state, sampled from the NeoForge block. */
record ProjectedDoorOutline(List<BoundingBox> boxes, List<BoundingBox> collision, List<Edge> edges) {
    private static final NamespacedKey OUTLINE_MODEL = new NamespacedKey("c-imagine", "_door/outline");

    record Edge(Vector start, Vector end) {
    }

    static ProjectedDoorOutline parse(String reply) {
        if (!reply.startsWith("door-shape\n")) {
            return null;
        }
        var boxes = new ArrayList<BoundingBox>();
        var edges = new ArrayList<Edge>();
        var collision = new ArrayList<BoundingBox>();
        for (String line : reply.substring("door-shape\n".length()).split("\n")) {
            String[] fields = line.split("\t");
            if (fields.length != 7) {
                continue;
            }
            var start = new Vector(Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                    Double.parseDouble(fields[3]));
            var end = new Vector(Double.parseDouble(fields[4]), Double.parseDouble(fields[5]),
                    Double.parseDouble(fields[6]));
            if (fields[0].equals("box")) {
                boxes.add(BoundingBox.of(start, end));
            } else if (fields[0].equals("collision")) {
                collision.add(BoundingBox.of(start, end));
            } else if (fields[0].equals("edge")) {
                edges.add(new Edge(start, end));
            }
        }
        return new ProjectedDoorOutline(List.copyOf(boxes), List.copyOf(collision), List.copyOf(edges));
    }

    RayTraceResult trace(Block block, Location eye, double reach) {
        Vector origin = eye.toVector().subtract(block.getLocation().toVector());
        Vector direction = eye.getDirection();
        double nearest = reach * reach;
        RayTraceResult result = null;
        for (BoundingBox box : boxes) {
            RayTraceResult hit = box.rayTrace(origin, direction, reach);
            if (hit != null && hit.getHitPosition().distanceSquared(origin) <= nearest) {
                nearest = hit.getHitPosition().distanceSquared(origin);
                result = hit;
            }
        }
        return result;
    }

    List<ItemDisplay> show(Plugin plugin, Player player, Block block) {
        var item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> meta.setItemModel(OUTLINE_MODEL));
        var displays = new ArrayList<ItemDisplay>(edges.size());
        float thickness = 0.004f;
        for (Edge edge : edges) {
            Vector start = edge.start();
            Vector end = edge.end();
            Vector center = start.clone().midpoint(end);
            var position = block.getLocation().add(center);
            var matrix = new org.joml.Matrix4f().scaling(
                    (float) Math.abs(start.getX() - end.getX()) + thickness,
                    (float) Math.abs(start.getY() - end.getY()) + thickness,
                    (float) Math.abs(start.getZ() - end.getZ()) + thickness);
            ItemDisplay display = block.getWorld().spawn(position, ItemDisplay.class, entity -> {
                entity.setVisibleByDefault(false);
                entity.setPersistent(false);
                entity.setItemStack(item);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setShadowRadius(0);
                entity.setTransformationMatrix(matrix);
            });
            player.showEntity(plugin, display);
            displays.add(display);
        }
        return displays;
    }
}
