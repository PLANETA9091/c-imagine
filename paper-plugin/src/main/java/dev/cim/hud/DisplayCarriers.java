package dev.cim.hud;

import dev.cim.f2v.F2vPack;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The display family: a mod block with no note or mushroom slot left. In Paper's world it is a
 * barrier (the collision, invisible) with an {@code ItemDisplay} of the block's own model at its
 * centre. The item's item_model is {@link F2vPack#displayItemModel}, so the client draws the block.
 *
 * <p>Identity is O(1) and lives in the chunk: a chunk PDC key {@code disp_x_y_z} holds
 * {@code <modid>|<display uuid>} for every display position. The display entity carries its
 * block id and position in its own PDC, so a reconcile can tell its own displays from anything
 * else. A display is spawned on placement, removed with its barrier, and reconciled on every chunk
 * load: a display whose barrier is gone (or whose mod is gone) is removed, and a barrier whose
 * display is missing gets one. Entities persist with their chunk, so an unload needs no work.
 *
 * <p>Display size and place: an item_display in the NONE context draws the model unscaled, so the
 * 16-unit block model fills the block at identity scale, centred at the block centre (the gallery
 * rig measured this). The 26.3 renderer yaws every item_display by PI, so the left rotation carries
 * a counter-yaw of PI (the same correction the gallery composites apply).
 */
final class DisplayCarriers {
    private static final String NS = "cim";
    /** On the display entity: the mod block id it stands for. */
    private static final NamespacedKey ENTITY_ID = new NamespacedKey(NS, "display_id");
    /** On the display entity: the block position it stands at ({@code x,y,z}). */
    private static final NamespacedKey ENTITY_AT = new NamespacedKey(NS, "display_at");
    private static final String KEY_PREFIX = "disp_";
    private static final Pattern KEY = Pattern.compile(KEY_PREFIX + "(-?\\d+)_(-?\\d+)_(-?\\d+)");

    private DisplayCarriers() {
    }

    /** The mod id of the display at a barrier's position, or null (O(1): the chunk's PDC; no entity scan). */
    static String idAt(Block block) {
        if (block == null || block.getType() != Material.BARRIER) {
            return null;
        }
        String v = block.getChunk().getPersistentDataContainer().get(slot(block), PersistentDataType.STRING);
        return v == null ? null : idOf(v);
    }

    /** The chunk's value {@code <id>|<uuid>} with the uuid taken off. */
    private static String idOf(String value) {
        int bar = value.indexOf('|');
        return bar < 0 ? value : value.substring(0, bar);
    }

    /** The display stack: a paper item whose item_model names the block's display items/ definition. */
    static ItemStack stack(String moddedId) {
        ItemStack stack = new ItemStack(Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        NamespacedKey model = NamespacedKey.fromString(F2vPack.displayItemModel(moddedId));
        if (model != null) {
            meta.setItemModel(model);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** The rig: identity scale at the block centre (set by the caller), counter-yawed by PI for the renderer's yaw. */
    private static Transformation rig() {
        return new Transformation(new Vector3f(0f, 0f, 0f), new Quaternionf().rotateY((float) Math.PI),
                new Vector3f(1f, 1f, 1f), new Quaternionf());
    }

    /** Places the display family's block at {@code at}: the barrier, its display entity and the chunk's key. */
    static void place(Block at, String moddedId) {
        remove(at); // a stale display at this position goes first
        ItemDisplay display = spawn(at, moddedId);
        at.getChunk().getPersistentDataContainer().set(slot(at), PersistentDataType.STRING,
                moddedId + "|" + display.getUniqueId());
    }

    /** The display entity for {@code moddedId} at {@code at} (the block centre, the rig, FIXED billboard). */
    private static ItemDisplay spawn(Block at, String moddedId) {
        World world = at.getWorld();
        Location centre = at.getLocation().add(0.5, 0.5, 0.5);
        String where = where(at);
        return world.spawn(centre, ItemDisplay.class, e -> {
            e.setItemStack(stack(moddedId));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformation(rig());
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(ENTITY_ID, PersistentDataType.STRING, moddedId);
            pdc.set(ENTITY_AT, PersistentDataType.STRING, where);
        });
    }

    /**
     * The display at {@code at} goes: its entity and its chunk key. The entity is found by the uuid
     * the key holds; a chunk scan catches one the key lost. Safe on a position with no display.
     */
    static void remove(Block at) {
        Chunk chunk = at.getChunk();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        NamespacedKey key = slot(at);
        String value = pdc.get(key, PersistentDataType.STRING);
        pdc.remove(key);
        boolean gone = false;
        if (value != null) {
            ItemDisplay display = displayOf(at.getWorld(), value);
            if (display != null) {
                display.remove();
                gone = true;
            }
        }
        if (!gone) {
            String where = where(at);
            for (Entity e : chunk.getEntities()) {
                if (e instanceof ItemDisplay && where.equals(e.getPersistentDataContainer().get(ENTITY_AT,
                        PersistentDataType.STRING))) {
                    e.remove();
                }
            }
        }
    }

    /** The loaded display a chunk value's uuid names, or null (malformed, unloaded or not a display). */
    private static ItemDisplay displayOf(World world, String value) {
        int bar = value.indexOf('|');
        if (bar < 0) {
            return null;
        }
        try {
            Entity e = world.getEntity(UUID.fromString(value.substring(bar + 1)));
            return e instanceof ItemDisplay d ? d : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** True when a barrier at {@code block} is a display (the pistons and explosions read it). */
    static boolean displayed(Block block) {
        return idAt(block) != null;
    }

    /**
     * Reconciles a loaded chunk's displays. A key whose barrier stands and whose mod is {@code known}
     * keeps (or gets) exactly one display; every other key goes, with its display. A display no key
     * owns is removed. Does nothing until the chunk's entities are loaded (the caller retries then).
     */
    static void adopt(Chunk chunk, Predicate<String> known) {
        if (!chunk.isEntitiesLoaded()) {
            return;
        }
        World world = chunk.getWorld();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        Map<String, List<ItemDisplay>> byPos = new HashMap<>();
        for (Entity e : chunk.getEntities()) {
            if (e instanceof ItemDisplay d) {
                String at = d.getPersistentDataContainer().get(ENTITY_AT, PersistentDataType.STRING);
                if (at != null) {
                    byPos.computeIfAbsent(at, k -> new ArrayList<>()).add(d);
                }
            }
        }
        List<NamespacedKey> keys = new ArrayList<>(pdc.getKeys());
        for (NamespacedKey key : keys) {
            if (!NS.equals(key.getNamespace()) || !key.getKey().startsWith(KEY_PREFIX)) {
                continue;
            }
            Matcher m = KEY.matcher(key.getKey());
            if (!m.matches()) {
                pdc.remove(key);
                continue;
            }
            int x = Integer.parseInt(m.group(1));
            int y = Integer.parseInt(m.group(2));
            int z = Integer.parseInt(m.group(3));
            Block at = world.getBlockAt(x, y, z);
            String value = pdc.get(key, PersistentDataType.STRING);
            String id = value == null ? null : idOf(value);
            if (id == null || at.getType() != Material.BARRIER || !known.test(id)) {
                pdc.remove(key); // no barrier, or the mod is gone: the display goes too
                continue;
            }
            String where = where(at);
            List<ItemDisplay> here = byPos.getOrDefault(where, new ArrayList<>());
            // one display per position: the one the key names when it is there, else the first
            ItemDisplay keep = null;
            String uuid = value.substring(value.indexOf('|') + 1);
            for (ItemDisplay d : here) {
                if (d.getUniqueId().toString().equals(uuid)) {
                    keep = d;
                    break;
                }
            }
            if (keep == null && !here.isEmpty()) {
                keep = here.get(0);
            }
            if (keep == null) {
                keep = spawn(at, id);
            }
            for (ItemDisplay d : here) {
                if (d != keep) {
                    d.remove();
                }
            }
            byPos.remove(where);
            pdc.set(key, PersistentDataType.STRING, id + "|" + keep.getUniqueId());
        }
        // displays no key owns: stale
        for (Map.Entry<String, List<ItemDisplay>> stale : byPos.entrySet()) {
            for (ItemDisplay d : stale.getValue()) {
                d.remove();
            }
        }
    }

    /** The block position as the display entity records it. */
    private static String where(Block at) {
        return at.getX() + "," + at.getY() + "," + at.getZ();
    }

    /** The chunk PDC key of a block's display position. */
    private static NamespacedKey slot(Block at) {
        return new NamespacedKey(NS, KEY_PREFIX + at.getX() + "_" + at.getY() + "_" + at.getZ());
    }
}
