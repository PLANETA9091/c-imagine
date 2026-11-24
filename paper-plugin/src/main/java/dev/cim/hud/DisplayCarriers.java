package dev.cim.hud;

import dev.cim.f2v.BlockLooks;
import dev.cim.f2v.BlockParts;
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
import java.util.Collections;
import java.util.logging.Logger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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
 *
 * <p>Per-state looks (S3a): a display block whose state is known (the placed-state text Paper keeps,
 * {@code bs_x_y_z}) shows the look {@link BlockLooks#resolve} picks for that state: its item_model,
 * and as the right rotation the look's x/y ({@link BlockLooks#rotation}). The rig's left rotation is
 * unchanged, so the renderer's yaw still cancels. A block with no state, or no look row, keeps the
 * default item and identity right rotation (the pre-S3a display).
 *
 * <p>Multipart (S5): a multipart block has one display per ACTIVE part of its placed state
 * ({@link BlockParts#active}), each an ItemDisplay with the part's item and rotation, tagged with its part
 * signature. A sync keeps the displays already showing the wanted set and replaces the rest. Neighbour
 * changes arrive as placements (use-on and break report the cells whose shape the layer recomputed), so a
 * fence's connections follow its neighbours.
 */
final class DisplayCarriers {
    private static final String NS = "cim";
    /** On the display entity: the mod block id it stands for. */
    private static final NamespacedKey ENTITY_ID = new NamespacedKey(NS, "display_id");
    /** On the display entity: the block position it stands at ({@code x,y,z}). */
    private static final NamespacedKey ENTITY_AT = new NamespacedKey(NS, "display_at");
    /** On the display entity: the part it shows for a multipart block ({@link #partSig}); absent on a single display. */
    private static final NamespacedKey ENTITY_SIG = new NamespacedKey(NS, "display_sig");
    private static final String KEY_PREFIX = "disp_";
    private static final Pattern KEY = Pattern.compile(KEY_PREFIX + "(-?\\d+)_(-?\\d+)_(-?\\d+)");

    /** The per-state look table of the running pack ({@link BlockLooks#load}); empty until set. */
    private static volatile Map<String, List<BlockLooks.Look>> looks = Map.of();

    private DisplayCarriers() {
    }

    /** The multipart part table of the running pack ({@link BlockParts#load}); empty until set (S5). */
    private static volatile Map<String, List<BlockParts.Row>> parts = Map.of();

    /** Installs the running pack's look table (called where the block rows are read at boot). */
    static void setLooks(Map<String, List<BlockLooks.Look>> table) {
        looks = table == null ? Map.of() : table;
    }

    /** Installs the running pack's multipart part table (called where the look table is read at boot). */
    static void setParts(Map<String, List<BlockParts.Row>> table) {
        parts = table == null ? Map.of() : table;
    }

    /** True when the pack's part table names {@code moddedId} as a multipart block: one display per active part. */
    static boolean isMultipart(String moddedId) {
        return BlockParts.isMultipart(parts, moddedId);
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

    /**
     * The display stack: a paper item whose item_model names the display items/ definition. {@code itemModel}
     * is the look's (null: the block's default display item).
     */
    static ItemStack stack(String moddedId, String itemModel) {
        ItemStack stack = new ItemStack(Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        NamespacedKey model = NamespacedKey.fromString(itemModel != null ? itemModel : F2vPack.displayItemModel(moddedId));
        if (model != null) {
            meta.setItemModel(model);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * The rig: identity scale at the block centre (set by the caller), counter-yawed by PI for the renderer's
     * yaw on the left; {@code look} is the right rotation (the look's x/y, identity for the default).
     */
    private static Transformation rig(Quaternionf look) {
        return new Transformation(new Vector3f(0f, 0f, 0f), new Quaternionf().rotateY((float) Math.PI),
                new Vector3f(1f, 1f, 1f), look);
    }

    /** The look {@code state} shows at {@code at}, or null (no state, or no row matches it). */
    static BlockLooks.Look lookAt(Block at, String moddedId, String state) {
        if (state == null) {
            return null;
        }
        return BlockLooks.resolve(looks, moddedId, state, at.getX(), at.getY(), at.getZ());
    }

    /** Debug flag {@code -Dcim.looks.debug=true}: one INFO line per applied look (off by default). */
    private static final boolean LOOKS_DEBUG = Boolean.getBoolean("cim.looks.debug");
    private static final Logger LOOKS_LOG = Logger.getLogger("cim.looks");

    /** Logs the look a display takes for {@code state} (block, state text, chosen row, rotations). */
    static void debugLook(Block at, String moddedId, String state, BlockLooks.Look look) {
        if (!LOOKS_DEBUG) {
            return;
        }
        LOOKS_LOG.info("CIM-LOOK block=" + moddedId + " at=" + at.getX() + "," + at.getY() + "," + at.getZ()
                + " state=" + state + " variant=" + (look == null ? "null" : look.variantKey())
                + " item_model=" + (look == null ? "null" : look.itemModel())
                + " rotX=" + (look == null ? 0 : look.rotX()) + " rotY=" + (look == null ? 0 : look.rotY()));
    }

    /** Sets a display to the look of {@code state} at {@code at} (item_model and right rotation). */
    private static void apply(ItemDisplay display, Block at, String moddedId, String state) {
        BlockLooks.Look look = lookAt(at, moddedId, state);
        debugLook(at, moddedId, state, look);
        display.setItemStack(stack(moddedId, look == null ? null : look.itemModel()));
        display.setTransformation(rig(look == null ? new Quaternionf()
                : BlockLooks.rotation(look.rotX(), look.rotY())));
    }

    /**
     * Places the display family's block at {@code at} in {@code state} (the layer's placed state; null when
     * none): the barrier, its display entity and the chunk's key.
     */
    static void place(Block at, String moddedId, String state) {
        remove(at); // a stale display at this position goes first
        if (isMultipart(moddedId)) {
            List<ItemDisplay> now = syncParts(at, moddedId, state, List.of());
            keyTo(at, moddedId, now.getFirst());
            return;
        }
        ItemDisplay display = spawn(at, moddedId, state);
        at.getChunk().getPersistentDataContainer().set(slot(at), PersistentDataType.STRING,
                moddedId + "|" + display.getUniqueId());
    }

    /**
     * A display block's state changed (the layer's use-on reported the block's new state): its display
     * shows the new look and the chunk keeps nothing new (the caller keeps the state text). False when the
     * block has no display entity to update (the chunk reconcile then gives it one with the state).
     */
    static boolean updateState(Block at, String moddedId, String state) {
        Chunk chunk = at.getChunk();
        if (isMultipart(moddedId)) {
            List<ItemDisplay> here = displaysAt(chunk, at);
            if (here.isEmpty()) {
                return false;
            }
            keyTo(at, moddedId, syncParts(at, moddedId, state, here).getFirst());
            return true;
        }
        String value = chunk.getPersistentDataContainer().get(slot(at), PersistentDataType.STRING);
        ItemDisplay display = value == null ? null : displayOf(at.getWorld(), value);
        if (display == null) {
            String where = where(at);
            for (Entity e : chunk.getEntities()) {
                if (e instanceof ItemDisplay d && where.equals(e.getPersistentDataContainer().get(ENTITY_AT,
                        PersistentDataType.STRING))) {
                    display = d;
                    break;
                }
            }
        }
        if (display == null) {
            return false;
        }
        apply(display, at, moddedId, state);
        return true;
    }

    /** The display entity for {@code moddedId} at {@code at} in {@code state} (the block centre, FIXED billboard). */
    private static ItemDisplay spawn(Block at, String moddedId, String state) {
        BlockLooks.Look look = lookAt(at, moddedId, state);
        debugLook(at, moddedId, state, look);
        return spawnWith(at, moddedId, look, null);
    }

    /** One display at the block centre showing {@code look} (null: the default item); {@code sig} marks a part. */
    private static ItemDisplay spawnWith(Block at, String moddedId, BlockLooks.Look look, String sig) {
        World world = at.getWorld();
        Location centre = at.getLocation().add(0.5, 0.5, 0.5);
        String where = where(at);
        return world.spawn(centre, ItemDisplay.class, e -> {
            e.setItemStack(stack(moddedId, look == null ? null : look.itemModel()));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setBillboard(Display.Billboard.FIXED);
            e.setTransformation(rig(look == null ? new Quaternionf()
                    : BlockLooks.rotation(look.rotX(), look.rotY())));
            PersistentDataContainer pdc = e.getPersistentDataContainer();
            pdc.set(ENTITY_ID, PersistentDataType.STRING, moddedId);
            pdc.set(ENTITY_AT, PersistentDataType.STRING, where);
            if (sig != null) {
                pdc.set(ENTITY_SIG, PersistentDataType.STRING, sig);
            }
        });
    }

    /**
     * The part signature of a multipart display: its part index, item and rotation. The default display of a
     * block with no active part is {@code default}.
     */
    static String partSig(BlockLooks.Look look) {
        return look == null ? "default" : look.variantKey() + "|" + look.itemModel() + "|" + look.rotX() + "," + look.rotY();
    }

    /**
     * Makes a multipart block's displays show the parts its placed state activates: one display per active part
     * (the default one when none is active). Displays already showing exactly that set are kept; any other set
     * is replaced. Returns the block's displays (never empty).
     */
    private static List<ItemDisplay> syncParts(Block at, String moddedId, String state, List<ItemDisplay> existing) {
        List<BlockLooks.Look> active = BlockParts.active(parts, moddedId, state == null ? "" : state,
                at.getX(), at.getY(), at.getZ());
        List<String> want = new ArrayList<>();
        if (active.isEmpty()) {
            want.add(partSig(null));
        } else {
            for (BlockLooks.Look l : active) {
                want.add(partSig(l));
            }
        }
        List<String> have = new ArrayList<>();
        for (ItemDisplay d : existing) {
            String sig = d.getPersistentDataContainer().get(ENTITY_SIG, PersistentDataType.STRING);
            have.add(sig == null ? "" : sig);
        }
        Collections.sort(want);
        Collections.sort(have);
        if (want.equals(have)) {
            return new ArrayList<>(existing);
        }
        for (ItemDisplay d : existing) {
            d.remove();
        }
        List<ItemDisplay> out = new ArrayList<>();
        if (active.isEmpty()) {
            out.add(spawnWith(at, moddedId, null, partSig(null)));
        } else {
            for (BlockLooks.Look l : active) {
                out.add(spawnWith(at, moddedId, l, partSig(l)));
            }
        }
        return out;
    }

    /** The loaded displays standing at {@code at} (a chunk entity scan: a multipart block has several). */
    private static List<ItemDisplay> displaysAt(Chunk chunk, Block at) {
        List<ItemDisplay> out = new ArrayList<>();
        String where = where(at);
        for (Entity e : chunk.getEntities()) {
            if (e instanceof ItemDisplay d && where.equals(e.getPersistentDataContainer().get(ENTITY_AT,
                    PersistentDataType.STRING))) {
                out.add(d);
            }
        }
        return out;
    }

    /** The chunk key of {@code at} names {@code display} (its uuid) and the mod id. */
    private static void keyTo(Block at, String moddedId, ItemDisplay display) {
        at.getChunk().getPersistentDataContainer().set(slot(at), PersistentDataType.STRING,
                moddedId + "|" + display.getUniqueId());
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
        if (value != null) {
            ItemDisplay display = displayOf(at.getWorld(), value);
            if (display != null) {
                display.remove();
            }
        }
        // a multipart block has one display per part: the position is swept whether or not the key's one was there
        for (ItemDisplay d : displaysAt(chunk, at)) {
            d.remove();
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
    static void adopt(Chunk chunk, Predicate<String> known, Function<Block, String> stateOf) {
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
            if (isMultipart(id)) {
                // one display per active part: the parts the state activates, the rest of the set goes
                String partState = stateOf == null ? null : stateOf.apply(at);
                List<ItemDisplay> shown = byPos.remove(where);
                List<ItemDisplay> now = syncParts(at, id, partState, shown == null ? List.of() : shown);
                keyTo(at, id, now.getFirst());
                continue;
            }
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
            String state = stateOf == null ? null : stateOf.apply(at);
            if (keep == null) {
                keep = spawn(at, id, state);
            } else {
                apply(keep, at, id, state); // a kept display takes the look of the state it stands in
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
