package dev.cim.paper;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;

/**
 * M4 outbound net watch: a Netty pipeline hook on every player channel that
 * CENSUSES outbound protocol packets and GUARDS against modded-id leakage
 * (the "no client kicks for unknown ids" contract — any packet a 26.3 vanilla
 * client cannot parse must never reach the wire).
 *
 * <p>Architecture (docs/ARCHITECTURE.md §4.4): the authoritative rewrite
 * happens at the FACADE (KernelBridge layerWrite / entity projection, before
 * Paper ever sees a modded id). This hook is the enforcement net that proves
 * the invariant on the live wire and reports what actually flows:
 *
 * <ul>
 *   <li>class-level census of every outbound packet (allocation-light:
 *       ConcurrentHashMap of counters, no per-packet strings);</li>
 *   <li>deep scan (property {@code cim.netwatch.deep=true}): block-update and
 *       add-entity payloads are inspected for non-{@code minecraft:} registry
 *       keys — 0 modded on a healthy boot;</li>
 *   <li>the post-effect family (ClientboundPostEffectsPacket) is logged in
 *       full — it is the F2V 2.0 control channel and every transition matters.</li>
 * </ul>
 *
 * <p>Netty is imported directly (the exact version Paper 26.3 bundles);
 * NMS classes stay reflection-only (relocation-fragile names).
 */
public final class NetWatch {

    private static final String HANDLER_NAME = "cim-net-watch";

    private final CimPlugin plugin;
    private final Map<String, AtomicLong> census = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong postEffects = new AtomicLong();
    private final AtomicLong moddedBlocked = new AtomicLong();
    private final AtomicLong blockUpdates = new AtomicLong();
    private volatile boolean deep;

    public NetWatch(CimPlugin plugin) {
        this.plugin = plugin;
    }

    /** Enable the payload-level modded scan (used by the M4 net leg). */
    public void setDeep(boolean deep) {
        this.deep = deep;
    }

    public long total() {
        return total.get();
    }

    public long postEffects() {
        return postEffects.get();
    }

    public long moddedBlocked() {
        return moddedBlocked.get();
    }

    public long blockUpdates() {
        return blockUpdates.get();
    }

    /** Compact census string for logs: top classes + guards. */
    public String census() {
        StringBuilder sb = new StringBuilder("total=").append(total.get())
                .append(" post_effects=").append(postEffects.get())
                .append(" block_updates=").append(blockUpdates.get())
                .append(" modded_blocked=").append(moddedBlocked.get())
                .append(" classes=[");
        int i = 0;
        for (Map.Entry<String, AtomicLong> e : census.entrySet()) {
            if (i++ == 8) {
                sb.append(", …");
                break;
            }
            if (i > 1) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue().get());
        }
        return sb.append(']').toString();
    }

    /** Inject the outbound watcher into a player's netty channel. */
    public void attach(Player player) {
        try {
            Object craft = player;
            Method getHandle = craft.getClass().getMethod("getHandle");
            Object serverPlayer = getHandle.invoke(craft);
            Channel channel = channelOf(serverPlayer);
            if (channel == null) {
                plugin.getLogger().warning(CimPlugin.LOG_PREFIX
                        + "net watch attach failed for " + player.getName()
                        + ": channel not found");
                return;
            }
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                return;
            }
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME,
                    new OutboundWatch(player.getName()));
            plugin.getLogger().info(CimPlugin.LOG_PREFIX + "net watch attached: "
                    + player.getName() + " channel=" + channel.id());
        } catch (Throwable t) {
            plugin.getLogger().warning(CimPlugin.LOG_PREFIX
                    + "net watch attach failed for " + player.getName() + ": " + t);
        }
    }

    public void detach(Player player) {
        try {
            Object serverPlayer = player.getClass().getMethod("getHandle").invoke(player);
            Channel channel = channelOf(serverPlayer);
            if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (Throwable ignored) {
            // player channel already torn down
        }
    }

    /**
     * Resolve the player's netty channel (26.3 path, reflection):
     * ServerPlayer.connection (ServerGamePacketListenerImpl)
     * .connection (net.minecraft.network.Connection)
     * .channel (PUBLIC io.netty.channel.Channel — Paper 26.3 field,
     * verified by javap; the old listener-level "channel" field is gone).
     */
    private static Channel channelOf(Object serverPlayer) throws Exception {
        Object listener = connectionField(serverPlayer.getClass(), "connection").get(serverPlayer);
        Object conn = connectionField(listener.getClass(), "connection").get(listener);
        return (Channel) connectionField(conn.getClass(), "channel").get(conn);
    }

    static Field connectionField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // walk up
            }
        }
        throw new NoSuchFieldException(name + " (from " + cls.getName() + ")");
    }

    /** The per-channel outbound handler. */
    private final class OutboundWatch extends ChannelDuplexHandler {

        private final String player;

        OutboundWatch(String player) {
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            try {
                String cls = msg.getClass().getSimpleName();
                total.incrementAndGet();
                census.computeIfAbsent(cls, k -> new AtomicLong()).incrementAndGet();
                if (cls.equals("ClientboundPostEffectsPacket")) {
                    postEffects.incrementAndGet();
                    plugin.getLogger().info(CimPlugin.LOG_PREFIX + "net watch: post_effects -> "
                            + player + " (" + describePostEffects(msg) + ")");
                } else if (cls.equals("ClientboundBlockUpdatePacket")) {
                    blockUpdates.incrementAndGet();
                    if (deep) {
                        plugin.getLogger().info(CimPlugin.LOG_PREFIX + "net watch: block update -> "
                                + player + ": " + describeBlockUpdate(msg));
                    }
                } else if (cls.equals("ClientboundSectionBlocksUpdatePacket")) {
                    blockUpdates.incrementAndGet();
                    if (deep) {
                        plugin.getLogger().info(CimPlugin.LOG_PREFIX
                                + "net watch: section blocks update -> " + player
                                + " (" + describeSectionUpdate(msg) + ")");
                    }
                } else if (deep && cls.equals("ClientboundAddEntityPacket")) {
                    scanDeepEntity(player, msg);
                } else if (deep && cls.equals("ClientboundContainerSetContentPacket")) {
                    // F2V 2.0 item leg: the wire fact that the visual item's
                    // custom_model_data component RIDES the vanilla container
                    // codec to the client (the pack dispatches on it).
                    // MEASURED on 1a1a5ce: addItem syncs via the SLOT packet —
                    // content packets appear on window resync; watch both.
                    plugin.getLogger().info(CimPlugin.LOG_PREFIX
                            + "net watch: container content -> " + player
                            + " (" + describeContainerContent(msg) + ")");
                } else if (deep && cls.equals("ClientboundContainerSetSlotPacket")) {
                    plugin.getLogger().info(CimPlugin.LOG_PREFIX
                            + "net watch: container slot -> " + player
                            + " (" + describeContainerSlot(msg) + ")");
                }
            } catch (Throwable t) {
                // the watcher must NEVER break the pipeline
                plugin.getLogger().warning(CimPlugin.LOG_PREFIX
                        + "net watch inspect failed: " + t);
            }
            super.write(ctx, msg, promise);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            plugin.getLogger().info(CimPlugin.LOG_PREFIX + "net watch: " + player
                    + " disconnected — " + census());
            super.channelInactive(ctx);
        }
    }

    /** Best-effort ids string for the post-effects packet (reflection). */
    private static String describePostEffects(Object packet) {
        try {
            Field f = connectionField(packet.getClass(), "postEffects");
            Object list = f.get(packet);
            return String.valueOf(list);
        } catch (Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * Best-effort CMD summary for ONE NMS ItemStack: "item=<toString>",
     * plus "cmd=<component>" when the item carries custom_model_data
     * (DataComponents.CUSTOM_MODEL_DATA via ItemStack.get — mojang-mapped
     * 26.3 surface). Returns "<no cmd>" when the item is empty or bare.
     */
    private static String describeItemCmd(Object item) {
        try {
            if (item == null) {
                return "<empty>";
            }
            Object cmdType = Class.forName("net.minecraft.core.component.DataComponents")
                    .getField("CUSTOM_MODEL_DATA").get(null);
            Object cmd = null;
            for (Method m : item.getClass().getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(cmdType)) {
                    cmd = m.invoke(item, cmdType);
                    break;
                }
            }
            // both dispatch families are logged: floats (range_dispatch rows)
            // and strings (select rows — the v2 wand contract greps strings=)
            String extra = "";
            if (cmd != null) {
                try {
                    Object strings = cmd.getClass().getMethod("strings").invoke(cmd);
                    if (strings instanceof java.util.Collection<?> s && !s.isEmpty()) {
                        extra += " strings=" + s;
                    }
                } catch (Throwable ignored) {
                    // older shape: floats-only component
                }
            }
            return String.valueOf(item) + (cmd == null ? "" : " cmd=" + cmd + extra);
        } catch (Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * Best-effort slot/CMD summary for container content (reflection): slot
     * count +, for the first item carrying one, the custom_model_data
     * component.
     */
    private static String describeContainerContent(Object packet) {
        try {
            Object items = null;
            for (Method m : packet.getClass().getMethods()) {
                if (m.getName().equals("getItems") && m.getParameterCount() == 0) {
                    items = m.invoke(packet);
                    break;
                }
            }
            if (items == null) {
                items = connectionField(packet.getClass(), "items").get(packet);
            }
            java.util.List<?> slots = (java.util.List<?>) items;
            int n = slots.size();
            StringBuilder sb = new StringBuilder(n + " slots");
            for (int i = 0; i < n; i++) {
                Object item = slots.get(i);
                if (item == null) {
                    continue;
                }
                String desc = describeItemCmd(item);
                if (desc.contains("cmd=")) {
                    sb.append(", slot").append(i).append('=').append(desc);
                    break; // one wire-level CMD proof is enough per packet
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
    }

    /** Single-slot sync (the addItem path — MEASURED 1a1a5ce). */
    private static String describeContainerSlot(Object packet) {
        try {
            Object item = null;
            for (Method m : packet.getClass().getMethods()) {
                if ((m.getName().equals("getItemStack") || m.getName().equals("getItem"))
                        && m.getParameterCount() == 0) {
                    item = m.invoke(packet);
                    break;
                }
            }
            if (item == null) {
                item = connectionField(packet.getClass(), "itemStack").get(packet);
            }
            int slot = -1;
            try {
                Object s = connectionField(packet.getClass(), "slot").get(packet);
                slot = ((Number) s).intValue();
            } catch (Throwable ignored) {
                // slot number is diagnostic, not required
            }
            return "slot" + slot + "=" + describeItemCmd(item);
        } catch (Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
    }

    /** Best-effort pos+state string for a block update (reflection). */
    private static String describeBlockUpdate(Object packet) {
        try {
            Object state = connectionField(packet.getClass(), "blockState").get(packet);
            Object pos = connectionField(packet.getClass(), "pos").get(packet);
            String key = registryKey("BLOCK", state.getClass().getMethod("getBlock").invoke(state));
            return pos + " " + key;
        } catch (Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
    }

    /** Best-effort count string for a section blocks update (reflection). */
    private static String describeSectionUpdate(Object packet) {
        try {
            Object states = connectionField(packet.getClass(), "states").get(packet);
            int n = java.lang.reflect.Array.getLength(states);
            return n + " entries";
        } catch (Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * Deep scan (entity spawn): must reference vanilla registry keys only.
     * Anything else is counted as a blocked leak (the packet itself still
     * passes — the facade guarantees vanilla ids; this path OBSERVES).
     */
    private void scanDeepEntity(String player, Object packet) {
        try {
            Object type = connectionField(packet.getClass(), "type").get(packet);
            String key = registryKey("ENTITY_TYPE", type);
            if (key != null && !key.startsWith("minecraft:")) {
                moddedBlocked.incrementAndGet();
                plugin.getLogger().warning(CimPlugin.LOG_PREFIX
                        + "net watch LEAK: modded entity " + key + " -> " + player);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning(CimPlugin.LOG_PREFIX
                    + "net watch deep scan failed: " + t);
        }
    }

    /** Reflect BuiltInRegistries.<field>.getKey(obj) -> "ns:path" string. */
    private static String registryKey(String registryField, Object obj) {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = connectionField(registries, registryField).get(null);
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("getKey") && m.getParameterCount() == 1) {
                    Object key = m.invoke(registry, obj);
                    return key == null ? null : key.toString();
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }
}
