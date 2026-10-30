package dev.cim.paper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
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

    /** The dialog custom-click id the HUD leg registers (namespaced, paper only allows plugin-owned ids). */
    static final net.kyori.adventure.key.Key HUD_CLICK_ID =
            net.kyori.adventure.key.Key.key("c-imagine", "hud_dialog_click");

    private volatile boolean worldReady;
    private NetWatch netWatch;
    /** M4 net leg state (single fire per boot). */
    private volatile boolean netLegStarted;
    /** F2V 2.0 visual pipeline state (M5.2). */
    private F2vState f2v;

    private Logger log() {
        return getLogger();
    }

    @Override
    public void onEnable() {
        log().info(LOG_PREFIX + "paper plugin enabled (bridge lifecycle owner)");
        log().info(LOG_PREFIX + "status: module=native (CRUSSTY), layer=mini NeoForge 1.21.1");
        getServer().getPluginManager().registerEvents(this, this);
        netWatch = new NetWatch(this);
        f2vInit();
    }

    // ---- F2V 2.0 visual pipeline (M5.2) ---------------------------------------

    /** Immutable F2V state resolved at enable time. */
    private record F2vState(List<dev.cim.f2v.F2vPack.VisualItem> items,
                            List<dev.cim.f2v.F2vPack.VisualBlock> blocks,
                            List<dev.cim.f2v.GalleryPack.Resolved> gallery,
                            String sha1) {
    }

    /**
     * Resolve the visual pack: reuse a pre-boot build when present
     * (integrity-first — the sha1 in server.properties must keep matching
     * what is served), otherwise build from {@code mods/} + the local
     * manifest. Then serve it on loopback for vanilla clients. Non-fatal:
     * visual parity degrades, the server still boots.
     */
    private void f2vInit() {
        try {
            Path base = Path.of(System.getProperty("user.dir"), "c-imagine", "f2v");
            Path packZip = base.resolve("pack.zip");
            Path modsDir = Path.of(System.getProperty("user.dir"), "mods");
            Path manifest = base.resolve("visual-items.tsv");
            dev.cim.f2v.F2vPack.Result result;
            if (Files.isRegularFile(packZip) && Files.isRegularFile(base.resolve("pack.sha1"))) {
                String preSha1 = Files.readString(base.resolve("pack.sha1")).trim();
                result = rebuildForVerification(modsDir, base, manifest);
                boolean match = result.sha1().equals(preSha1);
                log().info(LOG_PREFIX + "f2v pack: built (" + result.items().size()
                        + " item(s), sha1=" + result.sha1() + ") integrity="
                        + (match ? "ok (matches pre-boot build)" : "MISMATCH — serving stored zip"));
            } else {
                List<dev.cim.f2v.F2vPack.VisualItem> rows = Files.isRegularFile(manifest)
                        ? dev.cim.f2v.F2vPack.readManifest(manifest) : List.of();
                result = dev.cim.f2v.F2vPack.build(modsDir, base, rows);
                log().info(LOG_PREFIX + "f2v pack: built (" + result.items().size()
                        + " item(s), sha1=" + result.sha1() + ")");
            }
            if (!result.warnings().isEmpty()) {
                for (String w : result.warnings()) {
                    log().warning(LOG_PREFIX + "f2v pack: warn " + w);
                }
            }
            f2v = new F2vState(result.items(), result.blocks(), result.gallery(), result.sha1());
            int port = Integer.getInteger("cim.f2v.port", 25580);
            dev.cim.f2v.PackHttp http =
                    dev.cim.f2v.PackHttp.start(packZip, port, result.sha1());
            log().info(LOG_PREFIX + "f2v pack: serving " + http.url()
                    + " sha1=" + result.sha1());
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v pack: init FAILED (visual parity degraded): " + t);
        }
    }

    /**
     * Rebuild into a scratch dir for sha1 verification WITHOUT touching the
     * stored zip (integrity-first: serve what the properties hash covers).
     * Both manifests come from the REAL f2v dir (the scratch dir is empty —
     * measured 19ad1e3: an equipment manifest read from the scratch dir
     * silently drops the armor assets and breaks the sha1 match).
     */
    private dev.cim.f2v.F2vPack.Result rebuildForVerification(Path modsDir, Path base,
            Path manifest) throws IOException {
        Path scratch = base.resolve("verify");
        try {
            List<dev.cim.f2v.F2vPack.VisualItem> rows = Files.isRegularFile(manifest)
                    ? dev.cim.f2v.F2vPack.readManifest(manifest) : List.of();
            List<dev.cim.f2v.F2vPack.EquipmentAsset> equipment =
                    dev.cim.f2v.F2vPack.readEquipmentManifest(
                            base.resolve("visual-equipment.tsv"));
            List<dev.cim.f2v.F2vPack.VisualBlock> blocks =
                    dev.cim.f2v.F2vPack.readBlockManifest(
                            base.resolve("visual-blocks.tsv"));
            // the GALLERY manifest must come from the REAL f2v dir too (same
            // 19ad1e3 lesson as the equipment manifests: build() reads
            // visual-gallery.tsv from its OUT dir — which is the scratch here)
            Path gallerySrc = base.resolve("visual-gallery.tsv");
            if (Files.isRegularFile(gallerySrc)) {
                Files.createDirectories(scratch);
                Files.copy(gallerySrc, scratch.resolve("visual-gallery.tsv"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return dev.cim.f2v.F2vPack.build(modsDir, scratch, rows, equipment, blocks);
        } finally {
            // keep the tree small; the stored pack.zip is the served truth
            try {
                Files.deleteIfExists(scratch.resolve("pack.zip"));
                Files.deleteIfExists(scratch.resolve("pack.sha1"));
                Files.deleteIfExists(scratch.resolve("build-report.txt"));
                Files.deleteIfExists(scratch.resolve("visual-items.resolved.tsv"));
                Files.deleteIfExists(scratch.resolve("visual-gallery.tsv"));
                Files.deleteIfExists(scratch.resolve("visual-gallery.resolved.tsv"));
                Files.deleteIfExists(scratch);
            } catch (IOException ignored) {
                // scratch cleanup is best-effort
            }
        }
    }

    /**
     * Client-side pack lifecycle — the vanilla download/apply state machine
     * reported back by every real client (and by cimbot on the wire). The
     * SUCCESSFULY_LOADED marker is the CI assertion anchor for the F2V pack
     * contract.
     */
    @EventHandler
    public void onResourcePackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
        log().info(LOG_PREFIX + "f2v pack: status " + event.getPlayer().getName()
                + "=" + event.getStatus());
    }

    /**
     * The HUD channel response leg (dialog-hud-26-3.md §5): the vanilla
     * client hit-tests its own dialog buttons and answers a
     * {@code dynamic/custom} action with
     * {@code ServerboundCustomClickActionPacket}; Paper funnels it into this
     * event — the semantic, coordinate-free replacement for F2F v1's
     * server-side cursor hit-testing. Only our namespaced id logs (the
     * marker the CI asserts).
     */
    @EventHandler
    public void onCustomClick(io.papermc.paper.event.player.PlayerCustomClickEvent event) {
        if (HUD_CLICK_ID.equals(event.getIdentifier())) {
            String payload = event.getTag() != null ? event.getTag().string() : "none";
            log().info(LOG_PREFIX + "[cim-hud] custom click received id="
                    + event.getIdentifier() + " payload=" + payload);
        }
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

            // 0) F2V 2.0 visual item FIRST (measured on 3cab5d8: the bot exits
            //    as soon as its own contract completes — later legs ran after
            //    the bot was gone). The give + its container_set_content
            //    wire proof ride the vanilla codec while the bot is live.
            giveVisualItem("cimbot", "cimtest:test_block");
            Thread.sleep(1500); // inventory sync reaches the bot

            // 0.2) the SELECT row (v2 surface): CMD strings ride the same
            //    vanilla component codec — proves the second ItemsAdder/
            //    Oraxen dispatch family on the wire
            giveVisualItem("cimbot", "cimtest:test_wand");
            Thread.sleep(1500);

            // 0.3) the EQUIPMENT row (M6 pull-forward): an equippable helmet
            //    whose asset_id points at the pack's cimtest:test_armor —
            //    the legacy 1.21.1 armor textures mapped to the 26.3
            //    equipment surface ride the vanilla component codec
            equipVisualArmor("cimbot");
            Thread.sleep(1500);

            // 0.5) M5.4 groundwork: the parity matrix "entities" channel —
            //    item_display rig carrying the modded model's CMD stack. The
            //    bot observes add_entity(minecraft:item_display) on the wire;
            //    the pixel proof is the M5 harness's job once the pack reload
            //    works. Spawned here while the bot is live.
            String dispUuid = spawnVisualDisplay(world, 5, 100, 0, "cimtest:test_block");
            log().info(LOG_PREFIX + "f2v net: display leg uuid=" + dispUuid);
            Thread.sleep(1500); // add_entity reaches the bot

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

            // 2) post effects: apply the vanilla creeper chain, then the
            //    MODDED chain contributed by the F2V pack (c-imagine:cimsepia,
            //    composed from vanilla programs), then clear. The bot parses
            //    the ids on the wire; whether the client RESOLVES the modded
            //    chain from the pack is measured by the M5 xvfb harness (the
            //    registry_names census decides the delivery channel).
            String apply = sendPostEffects("cimbot", java.util.List.of("minecraft:creeper"));
            log().info(LOG_PREFIX + "f2v net: postfx apply -> " + apply);
            Thread.sleep(1500);
            String chain = sendPostEffects("cimbot",
                    java.util.List.of(dev.cim.f2v.F2vPack.MODDED_CHAIN_ID));
            log().info(LOG_PREFIX + "f2v net: postfx modded chain "
                    + dev.cim.f2v.F2vPack.MODDED_CHAIN_ID + " requested -> " + chain);
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

            // 3.6) BLOCK channel (parity matrix "blocks",
            //      block-channel-26-3.md): the note_block stand-in placed for
            //      the bot — the wire sees a block update carrying
            //      note_block[instrument=creeper,note=13]; the pack's merged
            //      blockstates override renders it as the modded cube on
            //      stock clients (pixel proof = the visual harness 09/10 leg).
            placeVisualBlock("cimbot", "cimtest:test_block");
            Thread.sleep(1500);

            // 3.7) HUD channel (parity matrix "hud", dialog-hud-26-3.md): a
            //    server-authored screen through the 26.3 dialog registry —
            //    Paper's Dialog.create() inlined builder (no data pack file),
            //    Audience.showDialog (CraftPlayer override sends the
            //    COMMON-phase ClientboundShowDialogPacket), ItemDialogBody
            //    rendering the modded cube stack (the items-channel graphics
            //    INLINE in a form), and a dynamic/custom action — the F2F v1
            //    cursor-hit-testing replacement. The bot observes show_dialog
            //    on the wire and answers with serverbound custom_click; the
            //    plugin logs the PaperPlayerCustomClickEvent marker.
            String dlg = showHudDialog("cimbot", "cimtest:test_block");
            log().info(LOG_PREFIX + "f2v net: hud dialog requested -> " + dlg);
            Thread.sleep(2000); // show_dialog + the bot's custom_click round-trip

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

    /**
     * Show a server-authored HUD dialog to a player (the parity matrix "hud"
     * channel — dialog-hud-26-3.md).
     *
     * <p>The 26.3 native replacement for F2F v1's ClientOnlyHudSimulator:
     * the dialog registry is a data-driven, server-authored screen system with
     * NATIVE widgets (inputs/bodies/buttons). Paper's {@code Dialog.create()}
     * registers an INLINED entry (no data pack file needed) and
     * {@code Audience.showDialog} (adventure 5.2.0 no-op overridden by
     * CraftPlayer) sends the COMMON-phase {@code ClientboundShowDialogPacket}
     * with the full dialog object inline ({@code ByteBufCodecs.holder(...,
     * fromCodecWithRegistriesTrusted)}). The body renders the modded cube
     * stack ({@code ItemDialogBody} — the items-channel graphics INSIDE a
     * form), and the button carries a {@code dynamic/custom} action whose
     * click comes back as {@code ServerboundCustomClickActionPacket} and
     * surfaces as {@code PaperPlayerCustomClickEvent} — vanilla does the
     * hit-testing, the server gets a semantic event (v1 did the opposite).
     *
     * <p>Main-thread ops are wrapped via the scheduler (the net leg runs
     * async). Logs the [cim-hud] markers the CI asserts.
     */
    private String showHudDialog(String playerName, String moddedItemId) {
        try {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) {
                log().warning(LOG_PREFIX + "f2v hud: player not online: " + playerName);
                return "player-offline";
            }
            final java.util.concurrent.CompletableFuture<String> done =
                    new java.util.concurrent.CompletableFuture<>();
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    final Player pl = Bukkit.getPlayerExact(playerName);
                    if (pl == null) {
                        done.complete("player-gone");
                        return;
                    }
                    // the modded stack: the host paper item + the manifest CMD
                    // floats (the SAME stack the items channel gives out —
                    // identical pixels, now inside a native form)
                    ItemStack stack = null;
                    if (f2v != null) {
                        for (dev.cim.f2v.F2vPack.VisualItem r : f2v.items()) {
                            if (r.moddedId().equals(moddedItemId)) {
                                String host = r.hostItem();
                                String bare = host.substring(host.indexOf(':') + 1);
                                org.bukkit.Material m = org.bukkit.Material.matchMaterial(bare);
                                if (m != null) {
                                    stack = new ItemStack(m);
                                    org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
                                    if (meta != null) {
                                        meta.displayName(net.kyori.adventure.text.Component.text(
                                                moddedItemId));
                                        org.bukkit.inventory.meta.components.CustomModelDataComponent cmd =
                                                meta.getCustomModelDataComponent();
                                        cmd.setFloats(java.util.List.of(r.cmd()));
                                        meta.setCustomModelDataComponent(cmd);
                                        stack.setItemMeta(meta);
                                    }
                                }
                                break;
                            }
                        }
                    }
                    if (stack == null) {
                        stack = new ItemStack(org.bukkit.Material.PAPER); // degraded but valid
                    }
                    final ItemStack bodyStack = stack;
                    io.papermc.paper.dialog.Dialog dialog = io.papermc.paper.dialog.Dialog.create(b -> b.empty()
                            .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(
                                            net.kyori.adventure.text.Component.text("c-imagine HUD"))
                                    .canCloseWithEscape(true)
                                    // HUD overlays must NOT pause: the pause+
                                    // after_action validation rejects it
                                    .pause(false)
                                    .afterAction(io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction.CLOSE)
                                    .body(java.util.List.of(
                                            io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                                                    net.kyori.adventure.text.Component.text(
                                                            "The modded cube, rendered by the vanilla client inside a server-authored form:"),
                                                    250),
                                            io.papermc.paper.registry.data.dialog.body.DialogBody.item(
                                                    bodyStack)
                                                    .showDecorations(true)
                                                    .showTooltip(false)
                                                    .width(64)
                                                    .height(64)
                                                    .build()))
                                    .inputs(java.util.List.of())
                                    .build())
                            .type(io.papermc.paper.registry.data.dialog.type.DialogType.multiAction(
                                    java.util.List.of(
                                            io.papermc.paper.registry.data.dialog.ActionButton.create(
                                                    net.kyori.adventure.text.Component.text("Click me (custom)"),
                                                    net.kyori.adventure.text.Component.text(
                                                            "fires the serverbound custom_click — the v1 hit-testing replacement"),
                                                    150,
                                                    io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                                            HUD_CLICK_ID,
                                                            net.kyori.adventure.nbt.api.BinaryTagHolder.binaryTagHolder(
                                                                    "{\"from\":\"c-imagine\",\"leg\":\"hud-dialog\"}"))),
                                            io.papermc.paper.registry.data.dialog.ActionButton.create(
                                                    net.kyori.adventure.text.Component.text("Close"),
                                                    null,
                                                    150,
                                                    null)),
                                    null,
                                    2)));
                    pl.showDialog(dialog);
                    log().info(LOG_PREFIX + "[cim-hud] dialog shown to " + playerName
                            + " click-id=" + HUD_CLICK_ID);
                    done.complete("shown");
                } catch (Throwable t) {
                    log().warning(LOG_PREFIX + "[cim-hud] show FAILED: " + t);
                    done.complete("show-failed: " + t);
                }
            });
            return done.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v hud: FAILED: " + t);
            return "failed: " + t;
        }
    }

    /**
     * Give one manifest-mapped visual item to a player (M5.2 item leg).
     *
     * <p>The F2F v1 mechanism on the 26.3 surface: the item handed out is
     * the HOST vanilla item carrying a {@code custom_model_data} component;
     * the generated pack's {@code items/<host>.json} range_dispatch turns
     * that CMD into the modded item model, so a stock client shows exactly
     * what a modded client would. paper-api 26.3 surface javap-verified
     * (ItemMeta#getCustomModelDataComponent + CustomModelDataComponent
     * floats). Logs the inventory-verified marker the CI asserts.
     */
    private void giveVisualItem(String playerName, String moddedItemId) {
        try {
            if (f2v == null) {
                log().warning(LOG_PREFIX + "f2v item: give skipped (pipeline not ready)");
                return;
            }
            dev.cim.f2v.F2vPack.VisualItem row = null;
            for (dev.cim.f2v.F2vPack.VisualItem r : f2v.items()) {
                if (r.moddedId().equals(moddedItemId)) {
                    row = r;
                    break;
                }
            }
            if (row == null) {
                log().warning(LOG_PREFIX + "f2v item: no manifest row for "
                        + moddedItemId + " (give skipped)");
                return;
            }
            final dev.cim.f2v.F2vPack.VisualItem visual = row;
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) {
                log().warning(LOG_PREFIX + "f2v item: player not online: " + playerName);
                return;
            }
            // inventory ops are main-thread-only (the net leg runs async)
            String bare = visual.hostItem().substring(visual.hostItem().indexOf(':') + 1);
            boolean selectRow = !visual.isFloatDispatch();
            String verifiedLine = callSync(() -> {
                Material material = Material.matchMaterial(bare);
                if (material == null) {
                    return "unknown host material " + visual.hostItem();
                }
                ItemStack stack = new ItemStack(material);
                stack.editMeta(meta -> {
                    CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                    if (selectRow) {
                        cmd.setStrings(List.of(visual.selectValue()));
                    } else {
                        cmd.setFloats(List.of(visual.cmd()));
                    }
                    meta.setCustomModelDataComponent(cmd);
                });
                var leftover = p.getInventory().addItem(stack);
                // verify: the inventory must hold the host item with the exact CMD
                boolean verified = false;
                for (ItemStack inv : p.getInventory().getContents()) {
                    if (inv == null || inv.getType() != material || !inv.hasItemMeta()) {
                        continue;
                    }
                    var meta = inv.getItemMeta();
                    if (!meta.hasCustomModelDataComponent()) {
                        continue;
                    }
                    if (selectRow) {
                        if (meta.getCustomModelDataComponent().getStrings()
                                .contains(visual.selectValue())) {
                            verified = true;
                            break;
                        }
                    } else if (meta.getCustomModelDataComponent().getFloats()
                            .contains(visual.cmd())) {
                        verified = true;
                        break;
                    }
                }
                return (verified ? "verified" : "NOT verified")
                        + (leftover.isEmpty() ? "" : ", leftover=" + leftover.size());
            });
            log().info(LOG_PREFIX + "f2v item: gave " + moddedItemId + " as "
                    + row.hostItem() + " "
                    + (selectRow ? "strings=[" + row.selectValue() + "]" : "cmd=" + row.cmd())
                    + " (inventory " + verifiedLine + ")");
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v item: give FAILED: " + t);
        }
    }

    /**
     * Wear one manifest-mapped armor asset on a player's head (M6
     * pull-forward — the parity matrix "armor" channel). The helmet is the
     * HOST armor item with its {@code equippable} component pointing at the
     * pack's equipment asset (paper-api 26.3 javap-verified:
     * {@code EquippableComponent#setModel(NamespacedKey)} — Bukkit's name
     * for the codec's {@code asset_id}). The pack's equipment/<ns>/<name>.json
     * (mapped from the legacy 1.21.1 armor textures) renders the layer on
     * ANY stock client. Logs the armor marker the CI asserts.
     */
    private void equipVisualArmor(String playerName) {
        try {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) {
                log().warning(LOG_PREFIX + "f2v armor: player not online: " + playerName);
                return;
            }
            String verified = callSync(() -> {
                ItemStack helmet = new ItemStack(Material.LEATHER_HELMET);
                try {
                    var meta = helmet.getItemMeta();
                    // javap-proven (paper-api 26.3): the equippable accessors
                    // live on ItemMeta — getEquippable()/setEquippable()
                    org.bukkit.inventory.meta.components.EquippableComponent eq =
                            meta.getEquippable();
                    eq.setModel(new org.bukkit.NamespacedKey("cimtest", "test_armor"));
                    meta.setEquippable(eq);
                    helmet.setItemMeta(meta);
                } catch (Throwable t) {
                    return "component-failed: " + t;
                }
                p.getInventory().setHelmet(helmet);
                // verify: the worn helmet carries the asset
                ItemStack worn = p.getInventory().getHelmet();
                if (worn != null && worn.hasItemMeta()
                        && worn.getItemMeta().hasEquippable()
                        && "test_armor".equals(String.valueOf(
                                worn.getItemMeta().getEquippable().getModel().getKey()))) {
                    return "verified";
                }
                return "NOT verified";
            });
            log().info(LOG_PREFIX + "f2v armor: equipped cimtest:test_armor on "
                    + playerName + " (" + verified + ")");
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v armor: equip FAILED: " + t);
        }
    }

    /**
     * Spawn one visual display entity (M5.4 groundwork — the parity matrix
     * "entities" channel): an {@code minecraft:item_display} whose item stack
     * is the HOST item carrying the modded item's {@code custom_model_data}
     * component. The pack's {@code items/<host>.json} range_dispatch renders
     * that CMD as the modded model ON ANY STOCK CLIENT, exactly like F2F v1's
     * ItemDisplay rigs (display/Rig: one display per part) — but packet-level:
     * no mod classes, the stack rides the vanilla display-entity metadata.
     *
     * <p>paper-api 26.3 surface javap-verified (ItemDisplay#setItemStack,
     * Display#setBillboard, Transformation over joml). Returns the uuid for
     * later cleanup, or a diagnostic string on failure.
     */
    public String spawnVisualDisplay(String world, double x, double y, double z,
                                     String moddedItemId) {
        return callSync(() -> {
            try {
                if (f2v == null) {
                    return "pipeline-not-ready";
                }
                dev.cim.f2v.F2vPack.VisualItem row = null;
                for (dev.cim.f2v.F2vPack.VisualItem r : f2v.items()) {
                    if (r.moddedId().equals(moddedItemId)) {
                        row = r;
                        break;
                    }
                }
                if (row == null) {
                    return "no-manifest-row-for-" + moddedItemId;
                }
                final dev.cim.f2v.F2vPack.VisualItem visual = row;
                String bare = visual.hostItem()
                        .substring(visual.hostItem().indexOf(':') + 1);
                Material material = Material.matchMaterial(bare);
                if (material == null) {
                    return "unknown-host-material-" + visual.hostItem();
                }
                ItemStack stack = new ItemStack(material);
                stack.editMeta(meta -> {
                    CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                    cmd.setFloats(List.of(visual.cmd()));
                    meta.setCustomModelDataComponent(cmd);
                });
                org.bukkit.Location at =
                        new org.bukkit.Location(world(world), x, y, z);
                org.bukkit.entity.ItemDisplay display = world(world).spawn(
                        at, org.bukkit.entity.ItemDisplay.class, e -> {
                            e.setItemStack(stack);
                            e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                        });
                log().info(LOG_PREFIX + "f2v net: display spawned uuid="
                        + display.getUniqueId() + " type=minecraft:item_display"
                        + " item=" + visual.hostItem() + " cmd=" + visual.cmd());
                return display.getUniqueId().toString();
            } catch (Throwable t) {
                log().warning(LOG_PREFIX + "f2v display spawn FAILED: " + t);
                return null;
            }
        });
    }

    /**
     * Show one manifest-mapped visual BLOCK in front of a player (the parity
     * matrix "blocks" channel) — OWNER DIRECTIVE 2026-10-30: the real-block
     * note_block stand-in is DEPRECATED ("working on note blocks is shit";
     * F2V v1 confirms — its BlockDisplayManager renders virtual blocks, it
     * does not pollute the world). The carrier blockstate is still
     * {@code note_block[instrument=<I>, note=<N>]} (the measured pack
     * override surface, block-channel-26-3.md) but it is rendered by a
     * {@code BlockDisplay} ENTITY: no world block, no collision, no
     * neighbor-shape side effects — and the display's brightness is PINNED
     * to (15,15) so the pixel gate never sees world-light drift.
     * paper-api 26.3 surface: {@code RegionAccessor#spawn(Class, Consumer)},
     * {@code BlockDisplay#setBlock(BlockData)},
     * {@code Display#setBrightness(Brightness)}. Logs the verified marker
     * the CI asserts.
     */
    private void placeVisualBlock(String playerName, String moddedBlockId) {
        try {
            if (f2v == null || f2v.blocks().isEmpty()) {
                log().warning(LOG_PREFIX + "f2v block: place skipped (no block manifest rows)");
                return;
            }
            dev.cim.f2v.F2vPack.VisualBlock row = null;
            for (dev.cim.f2v.F2vPack.VisualBlock r : f2v.blocks()) {
                if (r.moddedBlockId().equals(moddedBlockId)) {
                    row = r;
                    break;
                }
            }
            if (row == null) {
                log().warning(LOG_PREFIX + "f2v block: no manifest row for "
                        + moddedBlockId + " (place skipped)");
                return;
            }
            final dev.cim.f2v.F2vPack.VisualBlock visual = row;
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) {
                log().warning(LOG_PREFIX + "f2v block: player not online: " + playerName);
                return;
            }
            String verified = callSync(() -> {
                org.bukkit.block.data.type.NoteBlock data;
                try {
                    data = (org.bukkit.block.data.type.NoteBlock)
                            Material.NOTE_BLOCK.createBlockData();
                    data.setInstrument(org.bukkit.Instrument.valueOf(
                            visual.instrument().toUpperCase(Locale.ROOT)));
                    data.setNote(new org.bukkit.Note(visual.note()));
                } catch (Throwable t) {
                    return "component-failed: " + t;
                }
                org.bukkit.Location eye = p.getLocation();
                org.bukkit.util.Vector dir = eye.getDirection();
                dir = new org.bukkit.util.Vector(dir.getX(), 0, dir.getZ());
                if (dir.lengthSquared() < 1.0E-4) {
                    dir = new org.bukkit.util.Vector(0, 0, 1); // level look fallback (+Z)
                }
                dir.normalize();
                org.bukkit.Location at = eye.clone().add(dir.multiply(3.0));
                at.setY(eye.getBlockY());
                final org.bukkit.block.data.BlockData carrier = data;
                org.bukkit.entity.BlockDisplay disp = at.getWorld().spawn(at,
                        org.bukkit.entity.BlockDisplay.class, d -> {
                            d.setBlock(carrier);
                            // PIN the light: (block, sky) = (15, 15) fullbright —
                            // the pixel gate must never see world-light drift
                            d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                        });
                // verify: the entity exists and carries exactly that state
                if (disp.isValid() && disp.getBlock() instanceof org.bukkit.block.data.type.NoteBlock nb
                        && nb.getInstrument() == org.bukkit.Instrument.valueOf(
                                visual.instrument().toUpperCase(Locale.ROOT))
                        && nb.getNote().getId() == visual.note()) {
                    org.bukkit.Location l = disp.getLocation();
                    return "verified @" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
                }
                return "NOT verified @" + at.getBlockX() + "," + at.getBlockY() + "," + at.getBlockZ();
            });
            log().info(LOG_PREFIX + "f2v block: placed " + moddedBlockId
                    + " as block_display[note_block instrument=" + visual.instrument()
                    + ",note=" + visual.note() + "] for " + playerName
                    + " (" + verified + ")");
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v block: place FAILED: " + t);
        }
    }

    /**
     * Spawn the item_display rig IN VIEW of a player (the entity pixel leg —
     * parity matrix "entities"): the packet-level v1 Rig. Stack = the HOST
     * item carrying the modded model's CMD component; the pack's
     * items/&lt;host&gt;.json renders the modded model on the stock client.
     */
    private void spawnDisplayFor(String playerName, String moddedItemId) {
        try {
            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null) {
                log().warning(LOG_PREFIX + "f2v display: player not online: " + playerName);
                return;
            }
            org.bukkit.Location eye = p.getLocation();
            org.bukkit.util.Vector dir = eye.getDirection();
            dir = new org.bukkit.util.Vector(dir.getX(), 0, dir.getZ());
            if (dir.lengthSquared() < 1.0E-4) {
                dir = new org.bukkit.util.Vector(0, 0, 1);
            }
            dir.normalize();
            org.bukkit.Location at = eye.clone().add(dir.multiply(3.5));
            // MEASURED (visual run 43): at feet+0.4 the rig is visually lost
            // INSIDE the placed block's silhouette (both live 3 blocks ahead
            // on the same column); and the full-frame AE picks up CLOUD DRIFT
            // (~tens of k pixels between shots). Park the rig in the SKY 2.5
            // above feet — unmistakably in frame, clear of the block; the
            // cloud drift is then subtracted via the hold shot (12).
            at.setY(eye.getY() + 2.5);
            String uuid = spawnVisualDisplay(worldName(), at.getX(),
                    at.getY(), at.getZ(), moddedItemId);
            log().info(LOG_PREFIX + "f2v display: rig for " + playerName
                    + " @" + at.getBlockX() + "," + at.getBlockY() + ","
                    + at.getBlockZ() + " -> " + uuid);
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v display: spawn FAILED: " + t);
        }
    }

    // ---- GALLERY ours-leg surface (M5.5, roadmap) ------------------------------

    /**
     * Entity-transform calibration constants for gallery cells. MEASURED
     * against the truth leg's page-0 screenshot (wall bounding box):
     * run 4 @ scale 2.0 / ty −3/16 rendered the wall at 70.2 px/cell with its
     * centre 14 px above the truth leg's — the applied item context is NOT
     * the assumed ground transform. Calibrated (run 5): scale ×60.48/70.2 =
     * 1.7236 (truth cell = F/dist = 60.48 px @ 720p FOV 70, dist 8.5),
     * ty −0.419 (14 px / 60.48 px/m further down). Re-measure after any
     * client-version bump.
     */
    static final float GALLERY_SCALE = 1.0126f;
    static final float GALLERY_TX = 0.0f;
    static final float GALLERY_TY = -0.0184f;
    static final float GALLERY_TZ = 0.0f;

    /**
     * Replay one truth-leg gallery page manifest as ItemDisplay rigs:
     * {@code c-imagine/scenes/gallery-<ns>-p<page>.tsv} rows
     * {@code cell<TAB>x<TAB>y<TAB>TABz<TAB>state} — one display per cell at
     * the cell CENTER (x+0.5, y+0.5, z+0.5), the actual block left AIR (the
     * note-block stand-in is rejected for the gallery). States resolve to
     * pack CMDs through the F2vState gallery rows (visual-gallery.resolved.tsv).
     * Returns the placed count.
     */
    private int galleryPage(String ns, int page) {
        if (f2v == null || f2v.gallery().isEmpty()) {
            log().warning(LOG_PREFIX + "f2v gallery: no gallery rows in the pack "
                    + "(build the pack with visual-gallery.tsv)");
            return 0;
        }
        java.util.Map<String, dev.cim.f2v.GalleryPack.Resolved> byState = new java.util.HashMap<>();
        for (dev.cim.f2v.GalleryPack.Resolved r : f2v.gallery()) {
            byState.put(r.stateId(), r);
        }
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery: manifest missing: " + mf);
            return 0;
        }
        final java.util.List<String[]> cells = new java.util.ArrayList<>();
        try {
            for (String line : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("cell\t")) {
                    if (line.startsWith("cell\t")) {
                        cells.add(line.split("\t", -1));
                    }
                    continue;
                }
                // header row or skip row — nothing to spawn
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery: manifest read failed " + mf + ": " + ioe);
            return 0;
        }
        final java.util.Map<String, dev.cim.f2v.GalleryPack.Resolved> map = byState;
        Integer placed = callSync(() -> {
            int n = 0;
            for (String[] row : cells) {
                // cell<TAB>x<TAB>y<TAB>z<TAB>state
                if (row.length < 5) {
                    continue;
                }
                int x, y, z;
                try {
                    x = Integer.parseInt(row[1]);
                    y = Integer.parseInt(row[2]);
                    z = Integer.parseInt(row[3]);
                } catch (NumberFormatException bad) {
                    continue;
                }
                String state = row[4];
                dev.cim.f2v.GalleryPack.Resolved r = map.get(state);
                if (r == null) {
                    log().warning(LOG_PREFIX + "f2v gallery: no pack CMD for " + state
                            + " — cell " + row[1] + "," + row[2] + "," + row[3] + " skipped");
                    continue;
                }
                String bare = "paper";
                Material material = Material.matchMaterial(bare);
                ItemStack stack = new ItemStack(material);
                stack.editMeta(meta -> {
                    CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                    cmd.setFloats(List.of(r.cmd()));
                    meta.setCustomModelDataComponent(cmd);
                });
                org.bukkit.Location at = new org.bukkit.Location(world(worldName()),
                        x + 0.5, y + 0.5, z + 0.5);
                org.bukkit.util.Transformation tr = new org.bukkit.util.Transformation(
                        new org.joml.Vector3f(GALLERY_TX, GALLERY_TY, GALLERY_TZ),
                        // MEASURED (run 9 vs the truth leg): vanilla blockstate
                        // x=90 is R_x(-90) — joml rotationXYZ applies +angles,
                        // so the variant rotations ride NEGATED (a positive
                        // x=90 swung the chain-drive shaft UP out of the cell;
                        // negated, it renders the truth's vertical drive)
                        new org.joml.Quaternionf().rotationXYZ(
                                -(float) Math.toRadians(r.xRot()),
                                -(float) Math.toRadians(r.yRot()), 0f),
                        new org.joml.Vector3f(GALLERY_SCALE, GALLERY_SCALE, GALLERY_SCALE),
                        new org.joml.Quaternionf());
                org.bukkit.entity.ItemDisplay display = world(worldName()).spawn(at,
                        org.bukkit.entity.ItemDisplay.class, e -> {
                            e.setItemStack(stack);
                            e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                            e.setTransformation(tr);
                        });
                log().info(LOG_PREFIX + "f2v gallery: page " + page + " cell "
                        + row[1] + "," + row[2] + "," + row[3] + " state=" + state
                        + " cmd=" + r.cmd() + " uuid=" + display.getUniqueId());
                n++;
            }
            return n;
        });
        return placed == null ? 0 : placed;
    }

    /** Despawn every ItemDisplay in every world. Returns the removed count. */
    private int galleryClear() {
        Integer removed = callSync(() -> {
            int n = 0;
            for (World w : Bukkit.getWorlds()) {
                for (org.bukkit.entity.ItemDisplay d : w.getEntitiesByClass(
                        org.bukkit.entity.ItemDisplay.class)) {
                    d.remove();
                    n++;
                }
            }
            return n;
        });
        return removed == null ? 0 : removed;
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
        // /cim postfx <player> <id[,id...]|clear> — the M5 harness surface
        // for the post-effect channel (parity matrix "atmosphere"): request
        // pack-contributed chains for a viewer, or clear them all.
        if (args.length >= 3 && args[0].equalsIgnoreCase("postfx")) {
            String player = args[1];
            java.util.List<String> ids;
            if (args[2].equalsIgnoreCase("clear")) {
                ids = java.util.List.of();
            } else {
                ids = java.util.Arrays.asList(args[2].split(","));
            }
            String result = sendPostEffects(player, ids);
            sender.sendMessage(LOG_PREFIX + "postfx " + player + " <- " + args[2]
                    + " -> " + result);
            return true;
        }
        // /cim placeblock <player> [moddedId] — the parity matrix "blocks"
        // pixel-leg surface: the note_block stand-in placed in the viewer's
        // front (block-channel-26-3.md).
        if (args.length >= 2 && args[0].equalsIgnoreCase("placeblock")) {
            String modded = args.length >= 3 ? args[2] : "cimtest:test_block";
            placeVisualBlock(args[1], modded);
            return true;
        }
        // /cim display <player> [moddedItemId] — the entity pixel-leg surface:
        // the item_display rig spawned in the viewer's view.
        if (args.length >= 2 && args[0].equalsIgnoreCase("display")) {
            String modded = args.length >= 3 ? args[2] : "cimtest:test_block";
            spawnDisplayFor(args[1], modded);
            return true;
        }
        // /cim gallerypage <ns> <page> — the M5.5 GALLERY ours-leg surface:
        // replay a truth-leg page manifest (c-imagine/scenes/) as ItemDisplay
        // rigs — one per cell, carrying the state's pack CMD (GalleryPack).
        // The NOTE BLOCK channel is rejected here by owner directive.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage")) {
            try {
                int page = Integer.parseInt(args[2]);
                int placed = galleryPage(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage " + args[1]
                        + " p" + page + " -> placed=" + placed);
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim galleryclear — despawn every ItemDisplay (page swaps).
        if (args.length >= 1 && args[0].equalsIgnoreCase("galleryclear")) {
            int removed = galleryClear();
            sender.sendMessage(LOG_PREFIX + "galleryclear -> removed=" + removed);
            return true;
        }
        sender.sendMessage(LOG_PREFIX + "c-imagine 0.1.0 (M3.3)");
        sender.sendMessage(LOG_PREFIX + "kernel: Paper 26.3 | layer: mini NeoForge 1.21.1");
        sender.sendMessage(LOG_PREFIX + "native module: loaded by CRUSSTY runtime before kernel classes");
        sender.sendMessage(LOG_PREFIX + "bridge: " + (worldReady ? "open" : "closed (world not ready)"));
        sender.sendMessage(LOG_PREFIX + "postfx: /cim postfx <player> <id[,id2]|clear>");
        sender.sendMessage(LOG_PREFIX + "blocks: /cim placeblock <player> [moddedId]");
        sender.sendMessage(LOG_PREFIX + "entities: /cim display <player> [moddedItemId]");
        sender.sendMessage(LOG_PREFIX + "gallery: /cim gallerypage <ns> <page> | /cim galleryclear");
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
