package dev.cim.paper;

import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Paper-side companion of the c-imagine CRUSSTY module.
 *
 * <p>The native module (pre-kernel) owns the heavy lifting; this plugin owns
 * the Paper-visible lifecycle: bridge health reporting, the /cim command, and
 * the M3.1 kernel state bridge surface.
 *
 * <p>Bridge contract (values, never types — the module's class space is
 * hermetic and the two worlds must not share a classloader):
 * <ul>
 *   <li>{@link #isWorldReady()} — polled by the module (native side) until
 *       the Paper world accepts block operations;</li>
 *   <li>{@link #worldName()}, {@link #layerGetBlock}, {@link #layerSetBlock},
 *       {@link #paperSetAir()} — invoked by {@code dev.cim.bridge.KernelBridge}
 *       through reflection on the plugin instance the module passes over;</li>
 *   <li>every Bukkit call hops to the main thread (bridge callers run on
 *       module threads; Paper API is main-thread-only).</li>
 * </ul>
 *
 * <p>Deliberately minimal API usage (plain Bukkit surface) so it compiles
 * against every 26.3 API build.
 */
public final class CimPlugin extends JavaPlugin implements Listener {

    public static final String LOG_PREFIX = "[c-imagine] ";

    private volatile boolean worldReady;
    private NetWatch netWatch;
    /** M4 net leg state (single fire per boot). */
    private volatile boolean netLegStarted;

    private Logger log() {
        return getLogger();
    }

    @Override
    public void onEnable() {
        log().info(LOG_PREFIX + "paper plugin enabled (bridge lifecycle owner)");
        log().info(LOG_PREFIX + "status: module=native (CRUSSTY), layer=mini NeoForge 1.21.1");
        getServer().getPluginManager().registerEvents(this, this);
        netWatch = new NetWatch(this);
    }

    // ---- M4: player lifecycle -> net watch ----------------------------------

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        netWatch.attach(event.getPlayer());
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        netWatch.detach(event.getPlayer());
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        worldReady = true;
        log().info(LOG_PREFIX + "world ready (ServerLoadEvent " + event.getType()
                + ") — bridge open");
    }

    @Override
    public void onDisable() {
        log().info(LOG_PREFIX + "paper plugin disabled");
    }

    // ---- module-polled state ------------------------------------------------

    /** Polled by the native module before opening the bridge. */
    public boolean isWorldReady() {
        return worldReady && !getServer().getWorlds().isEmpty();
    }

    /** Default (over)world name for bridge coordinates. */
    public String worldName() {
        World w = getServer().getWorlds().get(0);
        return w.getName();
    }

    // ---- bridge surface (KernelBridge reflection targets) --------------------

    /** Kernel block read, serialized as a 26.3 blockstate string. */
    public String layerGetBlock(String world, int x, int y, int z) {
        return callSync(() -> {
            Block b = world(world).getBlockAt(x, y, z);
            return b.getBlockData().getAsString();
        });
    }

    /** Kernel block write from a (possibly 1.21.1) blockstate string. */
    public boolean layerSetBlock(String world, int x, int y, int z, String state) {
        return callSync(() -> {
            try {
                org.bukkit.block.data.BlockData data = Bukkit.createBlockData(state);
                world(world).getBlockAt(x, y, z).setBlockData(data);
                return true;
            } catch (Exception e) {
                log().warning(LOG_PREFIX + "layerSetBlock(" + state + ") failed: " + e);
                return false;
            }
        });
    }

    /** Paper-side break (drives the layer→observes-air smoke leg). */
    public boolean paperSetAir(String world, int x, int y, int z) {
        // paper-api 26.3: Block#setAir(boolean) is gone — setType(Material) is
        // the supported surface (verified against the 26.3 build in CI).
        return callSync(() -> {
            world(world).getBlockAt(x, y, z).setType(org.bukkit.Material.AIR);
            return true;
        });
    }

    // ---- entity projection surface (M3.4, KernelBridge reflection targets) ----

    /**
     * Spawn a kernel entity from a 26.3 entity-type key string
     * ("minecraft:oak_boat" — ALREADY remapped by the caller). Coordinates are
     * ABSOLUTE world coordinates, same convention as the block bridge surface
     * (getBlockAt/layerSetBlock). Returns the spawned entity's UUID string,
     * or null on failure.
     */
    public String paperSpawnEntity(String world, double x, double y, double z, String typeKey) {
        return callSync(() -> {
            try {
                String bare = typeKey.contains(":")
                        ? typeKey.substring(typeKey.indexOf(':') + 1) : typeKey;
                org.bukkit.entity.EntityType type =
                        org.bukkit.entity.EntityType.valueOf(bare.toUpperCase(java.util.Locale.ROOT));
                org.bukkit.entity.Entity e = world(world).spawnEntity(
                        new org.bukkit.Location(world(world), x, y, z), type);
                return e.getUniqueId().toString();
            } catch (Exception ex) {
                log().warning(LOG_PREFIX + "paperSpawnEntity(" + typeKey + ") failed: " + ex);
                return null;
            }
        });
    }

    /**
     * List entities in a cube around a point. Each row is
     * {@code uuid|type-key|x|y|z} with 26.3 keys (the caller translates).
     */
    public String[] paperListEntities(String world, double x, double y, double z, double radius) {
        return callSync(() -> {
            org.bukkit.util.BoundingBox box = org.bukkit.util.BoundingBox.of(
                    new org.bukkit.Location(world(world), x - radius, y - radius, z - radius),
                    new org.bukkit.Location(world(world), x + radius, y + radius, z + radius));
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (org.bukkit.entity.Entity e : world(world).getNearbyEntities(box)) {
                org.bukkit.Location l = e.getLocation();
                rows.add(e.getUniqueId() + "|" + e.getType().getKey()
                        + "|" + l.getX() + "|" + l.getY() + "|" + l.getZ());
            }
            return rows.toArray(new String[0]);
        });
    }

    /**
     * Force-load the chunk containing (x, z) so it ENTITY-TICKS without
     * players. Spawn chunks without players are block-ticking only (Minecraft
     * 1.20.5+ semantics): entities spawned there stay untracked and invisible
     * to getEntity/getNearbyEntities. The facade owns a force ticket around
     * entity-projection operations. Returns the previous force state.
     */
    public boolean paperForceChunk(String world, double x, double z, boolean on) {
        return callSync(() -> {
            org.bukkit.World w = world(world);
            int cx = (int) Math.floor(x) >> 4;
            int cz = (int) Math.floor(z) >> 4;
            boolean was = w.isChunkForceLoaded(cx, cz);
            // paper-api 26.3: World#setForceLoaded was renamed —
            // setChunkForceLoaded is the supported surface (javap-verified
            // against the 26.3 build).
            w.setChunkForceLoaded(cx, cz, on);
            return was;
        });
    }

    /**
     * Resolve an entity by UUID and return its 26.3 type key
     * ("minecraft:oak_boat"), or null when absent. UUID lookup is the
     * registration-authoritative channel (box scans can lag a tick behind
     * section indexing).
     */
    public String paperGetEntityType(String world, String uuid) {
        return callSync(() -> {
            try {
                org.bukkit.entity.Entity e =
                        world(world).getEntity(java.util.UUID.fromString(uuid));
                return e == null ? null : e.getType().getKey().toString();
            } catch (IllegalArgumentException badUuid) {
                return null;
            }
        });
    }

    /** Remove a kernel entity by UUID (Paper side). True when it existed. */
    public boolean paperRemoveEntity(String world, String uuid) {
        return callSync(() -> {
            try {
                java.util.UUID id = java.util.UUID.fromString(uuid);
                org.bukkit.entity.Entity e = world(world).getEntity(id);
                if (e == null) {
                    return false;
                }
                e.remove();
                return true;
            } catch (IllegalArgumentException badUuid) {
                return false;
            }
        });
    }

    /**
     * Toggle the glowing shared flag on an entity (M4 metadata leg):
     * a data-flag change is what actually makes the server emit a
     * ClientboundSetEntityDataPacket — spawning with default data does not.
     * paper-api 26.3 surface verified by javap (Entity#setGlowing(boolean)).
     */
    public boolean paperSetEntityGlowing(String world, String uuid, boolean on) {
        return callSync(() -> {
            try {
                org.bukkit.entity.Entity e =
                        world(world).getEntity(java.util.UUID.fromString(uuid));
                if (e == null) {
                    return false;
                }
                e.setGlowing(on);
                return true;
            } catch (IllegalArgumentException badUuid) {
                return false;
            }
        });
    }

    // ---- M4: post-effect control surface + net leg (KernelBridge targets) ----

    /**
     * Send a 26.3 post-effect request to one player by name:
     * {@code sendPostEffects("cimbot", ["minecraft:creeper"])} applies the
     * chain, an EMPTY list clears all. Reflective packet construction (the
     * packet class is NMS, not paper-api). Returns a diagnostics string.
     */
    public String sendPostEffects(String playerName, java.util.List<String> effectIds) {
        return callSync(() -> {
            try {
                Player p = org.bukkit.Bukkit.getPlayerExact(playerName);
                if (p == null) {
                    return "player-not-online";
                }
                Class<?> rl = Class.forName("net.minecraft.resources.Identifier");
                java.util.List<Object> ids = new java.util.ArrayList<>();
                for (String raw : effectIds) {
                    ids.add(makeIdentifier(rl, raw));
                }
                Class<?> pkt = Class.forName(
                        "net.minecraft.network.protocol.common.ClientboundPostEffectsPacket");
                Object packet = pkt.getConstructor(java.util.List.class).newInstance(ids);
                Object handle = p.getClass().getMethod("getHandle").invoke(p);
                Object listener = NetWatch.connectionField(handle.getClass(), "connection").get(handle);
                for (Method m : listener.getClass().getMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(pkt)) {
                        m.invoke(listener, packet);
                        return "sent " + effectIds;
                    }
                }
                return "no-send-method";
            } catch (Throwable t) {
                return "failed: " + t;
            }
        });
    }

    /** Identifier factory across the renamed factory surface. */
    private static Object makeIdentifier(Class<?> rl, String raw) throws Exception {
        String ns = raw.contains(":") ? raw.substring(0, raw.indexOf(':')) : "minecraft";
        String path = raw.contains(":") ? raw.substring(raw.indexOf(':') + 1) : raw;
        for (java.lang.reflect.Method m : rl.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != rl) {
                continue;
            }
            if (p.length == 2 && p[0] == String.class && p[1] == String.class
                    && (m.getName().equals("fromNamespaceAndPath") || m.getName().equals("of"))) {
                return m.invoke(null, ns, path);
            }
            if (p.length == 1 && p[0] == String.class
                    && (m.getName().equals("of") || m.getName().equals("parse")
                        || m.getName().equals("withDefaultNamespace"))) {
                return m.invoke(null, (Object) raw);
            }
        }
        throw new NoSuchMethodException("no Identifier factory for " + raw);
    }

    /** Net watch census (KernelBridge leg diagnostics). */
    public String netWatchCensus() {
        return netWatch.census();
    }

    /**
     * M4 net leg (async — must NOT block the boot smoke): wait for the
     * protocol bot to join, then drive the full wire contract against it:
     * spawn a projected boat, apply + clear a post effect, replay the drift
     * block (block_update on the wire), remove the boat. Completion markers
     * are asserted by the integration job AFTER the bot exits.
     */
    public String startNetLeg() {
        if (netLegStarted) {
            return "already-running";
        }
        netLegStarted = true;
        Thread leg = new Thread(this::runNetLeg, "cim-m4-net-leg");
        leg.setDaemon(true);
        leg.start();
        return "started";
    }

    private void runNetLeg() {
        try {
            log().info(LOG_PREFIX + "f2v net leg: armed (waiting for the protocol bot)");
            Player bot = null;
            for (int i = 0; i < 300 && bot == null; i++) {
                bot = callSync(() -> org.bukkit.Bukkit.getPlayerExact("cimbot"));
                if (bot == null) {
                    Thread.sleep(500);
                }
            }
            if (bot == null) {
                log().info(LOG_PREFIX + "f2v net leg: no bot joined within 150s (skip)");
                return;
            }
            log().info(LOG_PREFIX + "f2v net: bot joined name=" + bot.getName());
            // Attach the outbound net watch HERE, not via PlayerJoinEvent: the
            // join event did not fire on the CI wire order (measured — census
            // stayed empty), and a missed attach silently blinds the watch.
            netWatch.attach(bot);
            String world = worldName();
            boolean deepScan = Boolean.parseBoolean(
                    System.getProperty("cim.netwatch.deep", "true"));
            netWatch.setDeep(deepScan);

            // 1) projected entity: 1.21.1 boat -> 26.3 oak_boat on the wire
            paperForceChunk(world, 3, 0, true);
            String boatUuid = paperSpawnEntity(world, 3, 100, 0, "minecraft:oak_boat");
            log().info(LOG_PREFIX + "f2v net: boat spawned uuid=" + boatUuid);
            // glowing flag change -> SynchedEntityData delta -> SetEntityData
            // on the wire (a fresh boat with default data sends NO metadata —
            // measured on CI: the metadata leg needs an actual change).
            // paper-api 26.3 surface verified by javap (Entity#setGlowing).
            boolean glow = boatUuid != null
                    && paperSetEntityGlowing(world, boatUuid, true);
            log().info(LOG_PREFIX + "f2v net: boat glowing=" + glow);
            Thread.sleep(2500); // add_entity + set_entity_data reach the bot

            // 2) post effects: apply the vanilla creeper chain, then clear
            String apply = sendPostEffects("cimbot", java.util.List.of("minecraft:creeper"));
            log().info(LOG_PREFIX + "f2v net: postfx apply -> " + apply);
            Thread.sleep(1500);
            String clear = sendPostEffects("cimbot", java.util.List.of());
            log().info(LOG_PREFIX + "f2v net: postfx clear -> " + clear);
            Thread.sleep(1000);

            // 3) replay the M3.2 drift block — block_update on the wire must
            //    carry the translated 26.3 state (chain -> iron_chain[axis=y]).
            //    TWICE: a change sent before the client finished chunk loading
            //    can be dropped by the client-side tracker (measured on CI).
            paperSetAir(world, 0, 100, 2);
            Thread.sleep(500);
            boolean set = layerSetBlock(world, 0, 100, 2, "minecraft:iron_chain[axis=y]");
            log().info(LOG_PREFIX + "f2v net: drift block replay set=" + set);
            Thread.sleep(1500);
            layerSetBlock(world, 0, 100, 2, "minecraft:air");
            Thread.sleep(500);
            boolean set2 = layerSetBlock(world, 0, 100, 2, "minecraft:iron_chain[axis=y]");
            log().info(LOG_PREFIX + "f2v net: drift block replay-2 set=" + set2);
            Thread.sleep(1500);

            // 4) despawn through the facade -> remove_entities on the wire
            boolean removed = boatUuid != null && paperRemoveEntity(world, boatUuid);
            paperForceChunk(world, 3, 0, false);
            log().info(LOG_PREFIX + "f2v net: boat removed=" + removed);
            Thread.sleep(1500);

            log().info(LOG_PREFIX + "f2v net: leg complete boat=" + boatUuid
                    + " postfx=apply+clear block=iron_chain[axis=y] census="
                    + netWatch.census());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v net leg FAILED: " + t);
        }
    }

    // ---- helpers -------------------------------------------------------------

    private World world(String name) {
        World w = Bukkit.getWorld(name);
        if (w == null) {
            throw new IllegalStateException("world not found: " + name);
        }
        return w;
    }

    /** Run a Bukkit operation on the main thread; block the caller until done. */
    private <T> T callSync(java.util.function.Supplier<T> op) {
        if (Bukkit.isPrimaryThread()) {
            return op.get();
        }
        try {
            return Bukkit.getScheduler().callSyncMethod(this, op::get).get();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("bridge main-thread hop failed: " + c, c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("bridge main-thread hop interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("bridge main-thread hop failed: " + c, c);
        }
    }

    // ---- command surface ------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage(LOG_PREFIX + "c-imagine 0.1.0 (M3.3)");
        sender.sendMessage(LOG_PREFIX + "kernel: Paper 26.3 | layer: mini NeoForge 1.21.1");
        sender.sendMessage(LOG_PREFIX + "native module: loaded by CRUSSTY runtime before kernel classes");
        sender.sendMessage(LOG_PREFIX + "bridge: " + (worldReady ? "open" : "closed (world not ready)"));
        for (String line : readStatusLines()) {
            sender.sendMessage(LOG_PREFIX + line);
        }
        return true;
    }

    /**
     * Read the module's persisted boot status. Expected shape (M2):
     *
     * <pre>{@code
     * {"init":{"state":"ok","layer":{"state":"ok","registries":78,...}},
     *  "mods":{"state":"ok","mods":8,"registered":{...},"moddedEntries":{...}}}
     * }</pre>
     *
     * Rendered with tiny hand-rolled extraction (no JSON lib on the Paper
     * side): a regex over the known fields keeps the plugin dependency-free.
     */
    private String[] readStatusLines() {
        java.nio.file.Path file = java.nio.file.Path.of(
                System.getProperty("user.dir"), "c-imagine", "status.json");
        if (!java.nio.file.Files.isRegularFile(file)) {
            return new String[] {"status: module has not written status.json yet"
                    + " (still booting or failed before ready)"};
        }
        try {
            String json = java.nio.file.Files.readString(file);
            return new String[] {
                "status: " + (json.contains("\"init\":{\"state\":\"ok\"")
                        && json.contains("\"mods\":{\"state\":\"ok\"") ? "ok" : "degraded"),
                "layer: " + extract(json, "\"registries\":(\\d+)"),
                "mods: " + extract(json, "\"mods\":(\\d+),\"registered\""),
                "detail: " + file,
            };
        } catch (Exception e) {
            return new String[] {"status: unreadable (" + e.getClass().getSimpleName() + ": "
                    + e.getMessage() + ")"};
        }
    }

    private String extract(String json, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(json);
        return m.find() ? m.group(1) : "unknown";
    }
}
