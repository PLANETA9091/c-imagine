package dev.cim.paper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private dev.cim.f2v.PackHttp visualPack;

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
        dev.cim.hud.HudBootstrap.enable(this);
    }

    // ---- F2V 2.0 visual pipeline (M5.2) ---------------------------------------

    /** Immutable F2V state resolved at enable time. */
    private record F2vState(List<dev.cim.f2v.F2vPack.VisualItem> items,
                            List<dev.cim.f2v.F2vPack.VisualBlock> blocks,
                            List<dev.cim.f2v.GalleryPack.Resolved> gallery,
                            java.util.Map<String, Float> fluids,
                            java.util.Map<String, Float> fluidCubes,
                            String sha1) {
    }

    /**
     * The fluid-texture manifest (round 2): fluidId -> pack CMD, written by
     * the F2vPack build (visual-gallery-fluids.tsv). Read from the f2v dir
     * in BOTH resolution paths (fast boot and full rebuild write the same
     * deterministic scan). Missing file = an empty map (the fluid walk's
     * texture-model rows then replay as nochannel — the honest backlog).
     */
    private java.util.Map<String, Float> readFluidTextureMap(Path base) {
        Path f = base.resolve("visual-gallery-fluids.tsv");
        if (!Files.isRegularFile(f)) {
            return java.util.Map.of();
        }
        try {
            return dev.cim.f2v.F2vPack.readFluidManifest(f);
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v fluid manifest read failed " + f + ": " + ioe);
            return java.util.Map.of();
        }
    }

    /**
     * 14-di: the served pack's entries for the run-capture overlay builder.
     * Sorted by name — the overlay's zip must be deterministic (F2vPack.zip
     * writes sorted entries at a fixed epoch).
     */
    private static java.util.TreeMap<String, byte[]> readZipEntries(Path zip) throws IOException {
        java.util.TreeMap<String, byte[]> out = new java.util.TreeMap<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                out.put(e.getName(), zis.readAllBytes());
            }
        }
        return out;
    }

    /**
     * The fluid-CUBE manifest (14-cd): fluidId -> pack CMD, written by the
     * F2vPack build (visual-fluid-cubes.tsv, the 410000+i space). Read from
     * the f2v dir in BOTH resolution paths (fast boot and full rebuild). A
     * missing file = an empty map (the fluid walk's modded block rows then
     * replay as case-only — the honest surface the truth's decayed flowing
     * rows render anyway).
     */
    private java.util.Map<String, Float> readFluidCubeMap(Path base) {
        Path f = base.resolve("visual-fluid-cubes.tsv");
        if (!Files.isRegularFile(f)) {
            return java.util.Map.of();
        }
        try {
            return dev.cim.f2v.F2vPack.readFluidManifest(f);
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v fluid cube manifest read failed " + f + ": " + ioe);
            return java.util.Map.of();
        }
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
            // THE FAST BOOT PATH (gallery CI shards, 13-b): the pack + sha1 +
            // fully-resolved rows were produced by a pre-boot build THIS RUN
            // (the seeded F2vPack run) — the boot-time rebuild would
            // re-execute the same pure function over the same jars for
            // minutes of CPU (measured 4.8 min at 21939 gallery states,
            // boot step #35). Integrity is NOT weakened: the served zip is
            // the stored one, the sha1 is pinned in server.properties (the
            // client verifies the pack hash at download), and the resolved
            // rows load from the SAME build that wrote that zip. Default is
            // unchanged: without -Dcim.f2v.fastBoot=true every boot still
            // rebuilds + compares (drift detection for non-CI boots).
            if ((Boolean.getBoolean("cim.f2v.fastBoot") || "true".equals(System.getenv("CIM_F2V_FASTBOOT")))
                    && Files.isRegularFile(packZip)
                    && Files.isRegularFile(base.resolve("pack.sha1"))) {
                String cachedSha1 = Files.readString(base.resolve("pack.sha1")).trim();
                List<dev.cim.f2v.F2vPack.VisualItem> fastItems =
                        Files.isRegularFile(base.resolve("visual-items.resolved.tsv"))
                                ? dev.cim.f2v.F2vPack.readManifest(
                                        base.resolve("visual-items.resolved.tsv"))
                                : List.of();
                List<dev.cim.f2v.F2vPack.VisualBlock> fastBlocks =
                        dev.cim.f2v.F2vPack.readBlockManifest(
                                base.resolve("visual-blocks.tsv"));
                List<dev.cim.f2v.GalleryPack.Resolved> fastGallery =
                        Files.isRegularFile(base.resolve("visual-gallery.resolved.tsv"))
                                ? dev.cim.f2v.GalleryPack.readResolvedManifest(
                                        base.resolve("visual-gallery.resolved.tsv"))
                                : List.of();
                f2v = new F2vState(fastItems, fastBlocks, fastGallery,
                        readFluidTextureMap(base), readFluidCubeMap(base), cachedSha1);
                int fastPort = Integer.getInteger("cim.f2v.port", envInt("CIM_F2V_PORT", 25580));
                dev.cim.f2v.PackHttp fastHttp = null;
                // 14-di THE RUN-CAPTURE OVERLAY: the captures dir carries the truth
                // client's ScreenArt dump for THIS run's menus; scenes the seed's
                // walk never captured (the generic-screen class ceiling, the opener
                // asymmetry) get real art here. One pass allocates the glyph numbers
                // the pack and the replay agree on; the overlay zip is served at ITS
                // sha1 and the play-phase offer pins that. Any failure falls back to
                // the plain seed pack — the named scene-gap reds stay, nothing crashes.
                if (Boolean.getBoolean("cim.f2v.screenOverlay")) {
                    try {
                        dev.cim.hud.ScreenPack.RunOverlay ov = dev.cim.hud.ScreenPack.mergeRunCaptures(
                                modsDir, readZipEntries(packZip));
                        if (ov != null && ov.newGlyphs() > 0) {
                            byte[] zipBytes = dev.cim.f2v.F2vPack.zip(ov.entries());
                            dev.cim.f2v.F2vPack.validateDownloadSize(zipBytes.length);
                            Path overlayZip = base.resolve("pack-overlay.zip");
                            Files.write(overlayZip, zipBytes);
                            String overlaySha1 = dev.cim.f2v.F2vPack.sha1Hex(zipBytes);
                            fastHttp = dev.cim.f2v.PackHttp.start(overlayZip, fastPort, overlaySha1);
                            visualPack = fastHttp;
                            log().info(LOG_PREFIX + "f2v pack: FAST BOOT + 14-di RUN-CAPTURE OVERLAY — "
                                    + ov.newGlyphs() + " new glyph(s) over " + ov.seedGlyphs()
                                    + " seed glyph(s), scenes=" + ov.scenes().size()
                                    + ", font=" + ov.fontEntry()
                                    + ", serving pack-overlay.zip sha1=" + overlaySha1);
                        } else {
                            log().info(LOG_PREFIX + "f2v pack: 14-di overlay: no new run captures to serve");
                        }
                    } catch (Throwable t) {
                        log().warning(LOG_PREFIX + "f2v pack: 14-di overlay FAILED (serving the plain seed pack): " + t);
                    }
                }
                if (fastHttp == null) {
                    fastHttp = dev.cim.f2v.PackHttp.start(packZip, fastPort, cachedSha1);
                }
                visualPack = fastHttp;
                log().info(LOG_PREFIX + "f2v pack: FAST BOOT (cim.f2v.fastBoot) — "
                        + "serving the pre-boot build's zip, sha1=" + visualPack.sha1()
                        + ", " + fastGallery.size() + " gallery row(s), "
                        + fastItems.size() + " item row(s); rebuild-verification "
                        + "skipped (integrity = the server.properties sha1 pin "
                        + "+ the client's pack hash check)");
                log().info(LOG_PREFIX + "f2v pack: serving " + fastHttp.url()
                        + " sha1=" + visualPack.sha1());
                return;
            }
            if (Files.isRegularFile(packZip) && Files.isRegularFile(base.resolve("pack.sha1"))) {
                String preSha1 = Files.readString(base.resolve("pack.sha1")).trim();
                result = dev.cim.f2v.F2vPack.rebuildForVerification(modsDir, base);
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
            f2v = new F2vState(result.items(), result.blocks(), result.gallery(),
                    readFluidTextureMap(base), readFluidCubeMap(base), result.sha1());
            int port = Integer.getInteger("cim.f2v.port", envInt("CIM_F2V_PORT", 25580));
            dev.cim.f2v.PackHttp http =
                    dev.cim.f2v.PackHttp.start(packZip, port, result.sha1());
            visualPack = http;
            log().info(LOG_PREFIX + "f2v pack: serving " + http.url()
                    + " sha1=" + result.sha1());
        } catch (Throwable t) {
            log().warning(LOG_PREFIX + "f2v pack: init FAILED (visual parity degraded): " + t);
        }
    }

    /**
     * Client-side pack lifecycle — the vanilla download/apply state machine
     * reported back by every real client (and by cimbot on the wire). The
     * SUCCESSFULLY_LOADED marker is the CI assertion anchor for a pack
     * offered in play. Paper does not emit this event for the configuration
     * phase's server.properties pack.
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
        dev.cim.hud.HudBootstrap.disable();
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

    /** A launcher without JVM arguments (CRUSSTY) passes the same settings as environment. */
    private static int envInt(String name, int fallback) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
            // M6.3 end to end (live.yml): a mod item's menu run by the mod's own code in the
            // layer, for a real connected player, and the Paper inventory it leaves.
            String liveItem = System.getenv("CIM_LIVETEST");
            // The button scan runs in the background from boot; the test presses one, so it waits.
            for (int i = 0; i < 360 && liveItem != null && !dev.cim.hud.HudBootstrap.liveScanDone(); i++) {
                Thread.sleep(500);
            }
            if (liveItem != null && !liveItem.isBlank()) {
                Player target = bot;
                for (String line : callSync(() -> dev.cim.hud.HudBootstrap.liveSelfTest(target, liveItem.trim()))) {
                    log().info("[cim-live] paper " + line);
                }
            }
            String livePlace = System.getenv("CIM_LIVETEST_PLACE");
            if (livePlace != null && !livePlace.isBlank()) {
                Player target = bot;
                for (String line : callSync(() -> dev.cim.hud.HudBootstrap.livePlaceTest(target, livePlace.trim()))) {
                    log().info("[cim-live] paper " + line);
                }
            }
            String liveBlock = System.getenv("CIM_LIVETEST_BLOCK");
            if (liveBlock != null && !liveBlock.isBlank()) {
                Player target = bot;
                for (String line : callSync(() -> dev.cim.hud.HudBootstrap.liveBlockTest(target, liveBlock.trim()))) {
                    log().info("[cim-live] paper " + line);
                }
            }
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
     * The gallery rig needs no correction: with both legs on the 70-degree
     * contract, an identity display lands exactly on the truth cell. The old
     * values (scale 1.0126, ty -0.0184, tz +0.20) were fitted while both clients
     * rendered at 1.47x; at the real camera they left every page 2.2% small and
     * 1 px low (scale 0.978 on gallery 42036584838, every page alike).
     */
    static final float GALLERY_SCALE = 1.0f;
    static final float GALLERY_TX = 0.0f;
    static final float GALLERY_TY = 0.0f;
    static final float GALLERY_TZ = 0.0f;

    /**
     * 14-du THE LIGHT-SPILL LAW: the truth's light-emitting rows light the
     * scene AROUND them (the lit candle's wall renders at block light 12-13
     * while the replay's air band sits at 0 — the crop measured the whole-cell
     * delta on every lit row: the 7 candle-holder reds, the BOP glow family,
     * the emissive table's neighbour-spill half). The seed's .light file
     * (written per page by the walk, next to the manifest) carries the truth's
     * MEASURED block light at every scanned position. A real
     * {@code minecraft:light[level=N]} at each AIR position reproduces the
     * field structurally — the vanilla propagation does the decay, zero
     * per-family code, no threshold touched. Solid positions (the floor, the
     * wall blocks) are never replaced: only AIR carries a light block, and
     * the real blocks light from their lit neighbours exactly as in the truth
     * scene. Every page clears the previous page's lights first (a lit page
     * must never leak into an unlit one); the entity/item sweeps clear the
     * band too, the tracker keeps the ledger exact.
     */
    private final java.util.List<int[]> placedLightBlocks = new java.util.ArrayList<>();

    /** {@code x y z block sky} per scanned position; block light 1..15 kept. */
    public static java.util.List<int[]> parseLightFile(Path file) throws IOException {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) {
                continue;
            }
            try {
                int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                int block = Integer.parseInt(p[3]);
                Integer.parseInt(p[4]); // sky must parse too — a malformed row skips whole
                if (block >= 1 && block <= 15) {
                    out.add(new int[]{x, y, z, block});
                }
            } catch (NumberFormatException malformed) {
                // the header or a partial trailing row: skip, never abort the page
            }
        }
        return out;
    }

    private void clearPlacedLights(World w) {
        for (int[] pos : placedLightBlocks) {
            try {
                w.getBlockAt(pos[0], pos[1], pos[2]).setType(Material.AIR, false);
            } catch (Exception clearFail) {
                // unloaded corner: never abort the page
            }
        }
        placedLightBlocks.clear();
    }

    private void placeLightsFromTruth(World w, Path lightMf) {
        if (!Files.isRegularFile(lightMf)) {
            return;
        }
        try {
            for (int[] row : parseLightFile(lightMf)) {
                try {
                    org.bukkit.block.Block b = w.getBlockAt(row[0], row[1], row[2]);
                    if (b.getType() != Material.AIR) {
                        continue;   // the floor/wall blocks stay; they light from their lit neighbours
                    }
                    b.setBlockData(org.bukkit.Bukkit.createBlockData(
                            "minecraft:light[level=" + row[3] + "]"), false);
                    placedLightBlocks.add(new int[]{row[0], row[1], row[2]});
                } catch (Exception placeFail) {
                    // unloaded corner: never abort the page
                }
            }
        } catch (IOException readFail) {
            log().warning(LOG_PREFIX + "f2v gallery: 14-du light file read failed " + lightMf + ": " + readFail);
        }
    }

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
        // 14-bt THE MESH INDEX: the pack's quad rows name ONE representative
        // state per distinct captured mesh (rows.tsv dedupes by content —
        // the #214 acacia_leaves measured it: qdc34ed7381c8e259 serves both
        // the persistent=false representative AND the persistent=true cells,
        // but the byState lookup `state#q<hash>` only ever hit the exact
        // representative string — every sibling state fell back to the plain
        // static composite and measured ae 323K-369K against the truth's own
        // captured geometry it ALREADY CARRIES). The mesh IS the content:
        // index every quad row by its bare hash and let the ctx position
        // resolve through it directly.
        java.util.Map<String, dev.cim.f2v.GalleryPack.Resolved> byQuadHash = new java.util.HashMap<>();
        // 14-dh THE CAPTURE-FIRST INDEX: every quad row indexed by its BARE
        // state prefix — when the capture names the manifest state exactly,
        // that row is the truth client's own rendering of the state and
        // outranks the static composite (the runtime-built classes' stub
        // models never reproduce the BER-painted content: measured 313 of
        // the 385 red cells on gallery 43072158799 carry an exact capture).
        // First wins: the resolved manifest is sorted, so the representative
        // capture is deterministic across builds.
        java.util.Map<String, dev.cim.f2v.GalleryPack.Resolved> byQuadState = new java.util.HashMap<>();
        // 14-dt THE MULTI-MESH INDEX: a state's truth render can be SEVERAL
        // distinct captured meshes (the sliding door's panel + window, the
        // pillar's shaft + connected caps, the cog's parts — measured 300
        // states in the seed's resolved manifest carry >1 quad row); the
        // pick serves the first and the caller spawns the rest, one display
        // per extra mesh, same cell, same transform, each with its own
        // captured light fields.
        java.util.Map<String, java.util.List<dev.cim.f2v.GalleryPack.Resolved>> byQuadStateAll = new java.util.HashMap<>();
        for (dev.cim.f2v.GalleryPack.Resolved r : f2v.gallery()) {
            byState.putIfAbsent(r.stateId(), r);
            String id = r.stateId();
            int q = id.indexOf("#q");
            if (q >= 0) {
                byQuadHash.putIfAbsent(id.substring(q + 2), r);
                byQuadState.putIfAbsent(id.substring(0, q), r);
                byQuadStateAll.computeIfAbsent(id.substring(0, q),
                        ignored -> new java.util.ArrayList<>()).add(r);
            }
        }
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery: manifest missing: " + mf);
            return 0;
        }
        final java.util.List<String[]> cells = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("cell\t")) {
                    cells.add(line.split("\t", -1));
                }
                // 14-dr THE DISSOLVED-RACE LAW (supersedes the b104a36f
                // re-placement): a "skip ... dissolved" row means the subject
                // self-removed before the settle marker — the truth leg's shot
                // is ALWAYS post-dissolution (the shot waits for the marker),
                // so the honest replay renders NOTHING at that position. The
                // old re-placement bet that the truth walk KEPT the subject
                // (the dynamictrees leaves incident); when both walks dissolve
                // it — the measured common case (rope_tomatoes, run
                // 43264497882: 8 rope cells RED at 60-105k ae) — the re-placed
                // geometry spills into the NEIGHBOURING compared cells and
                // pollutes them red. The truth-kept case is now the join's
                // DISSOLVED-RACE diagnostic (gallery-diff --replay-manifests):
                // reported with its measured ae, never a gate red, because the
                // replay's only input (the seed manifest) lawfully named the
                // subject absent.
                // header row or any other skip row — nothing to spawn
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery: manifest read failed " + mf + ": " + ioe);
            return 0;
        }
        // 14-ak CT: the seed's per-cell tile indices (gallery_ct.py,
        // verified against the real create classes 515/515) — the pack
        // carries one composite PER (state, ctx); the suffixed lookup
        // falls back to the plain state row.
        final java.util.Map<String, String> ctByPos = new java.util.HashMap<>();
        // the seed writes gallery-<ns>-p<page>.ctx.tsv (gallery_ct.py) —
        // strip the manifest's .tsv, don't append (the 14-ak #91 miss: the
        // appended name never matched and every cell rode the plain row)
        String mfName = mf.getFileName().toString();
        String ctxName = (mfName.endsWith(".tsv")
                ? mfName.substring(0, mfName.length() - 4) : mfName)
                + ".ctx.tsv";
        Path ctxMf = mf.resolveSibling(ctxName);
        if (Files.isRegularFile(ctxMf)) {
            try {
                for (String line : Files.readAllLines(ctxMf, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    String[] parts = line.split("\t", -1);
                    if (parts.length >= 4) {
                        ctByPos.put(parts[0] + "," + parts[1] + "," + parts[2], parts[3]);
                    }
                }
            } catch (IOException ioe) {
                log().warning(LOG_PREFIX + "f2v gallery: ctx read failed " + ctxMf
                        + ": " + ioe + " — plain states ride");
            }
        }
        final java.util.Map<String, dev.cim.f2v.GalleryPack.Resolved> map = byState;
        Integer placed = callSync(() -> {
            int n = 0;
            // 14-du: the previous page's light blocks go first, then this
            // page's truth-measured light field lands before any display
            // spawns — the lit cells' surroundings render at the truth's
            // world light from the very first shot.
            World wLights = world(worldName());
            clearPlacedLights(wLights);
            String lightName = (mfName.endsWith(".tsv")
                    ? mfName.substring(0, mfName.length() - 4) : mfName) + ".light";
            placeLightsFromTruth(wLights, mf.resolveSibling(lightName));
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
                String ctKey = ctByPos.get(row[1] + "," + row[2] + "," + row[3]);
                // 14-dh: the ONE pick law (CT ctx -> mesh hash -> the exact
                // state's captured mesh -> the plain composite) lives in
                // GalleryPack.pickResolved — the selftest holds it
                dev.cim.f2v.GalleryPack.Resolved picked =
                        dev.cim.f2v.GalleryPack.pickResolved(map, byQuadState,
                                byQuadHash, state, ctKey);
                if (picked == null) {
                    log().warning(LOG_PREFIX + "f2v gallery: no pack CMD for " + state
                            + " — cell " + row[1] + "," + row[2] + "," + row[3] + " skipped");
                    continue;
                }
                final dev.cim.f2v.GalleryPack.Resolved r = picked;
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
                        // MEASURED (14-v, local stand + client decompile): the
                        // 26.3 ItemDisplayRenderer rotates EVERY item_display
                        // pose by PI around Y before submitting the item
                        // (DisplayRenderer$ItemDisplayRenderer.submitInner:
                        // poseStack.rotate(Axis.YP, PI)). Uncompensated, every
                        // gallery composite renders 180-deg-yawed — asymmetric
                        // states (stairs/walls/panes) mirror vs the truth leg
                        // (the flip test only caught it dead-center on straight
                        // stairs: c5's 52 wins, the "x=0 axis defect" was the
                        // one configuration where yaw ~= image flip). The
                        // entity-space counter-yaw lands LEFT of the renderer's
                        // PI: L o R_y(PI) o R_van(PI) = L — variant rotations
                        // keep their measured semantics for every row.
                        new org.joml.Quaternionf().rotationXYZ(
                                -(float) Math.toRadians(r.xRot()),
                                -(float) Math.toRadians(r.yRot()), 0f)
                                .rotateY((float) Math.PI),
                        new org.joml.Vector3f(GALLERY_SCALE, GALLERY_SCALE, GALLERY_SCALE),
                        new org.joml.Quaternionf());
                // 14-bk: pin the display's light to the state's own emission —
                // the truth leg renders an emissive block at its block light
                // (the lit lumaloop, the blaze burner, the nixie tubes), while
                // the display's default local light is (sky 15, block 0) and
                // the crop measured the glow ~0.85x dimmer with the warm R/G
                // shift of the missing block-light component. The 7th manifest
                // column (the scene walk's getLightEmission) carries it; the
                // non-emissive rows pin (0, 15) = the same local light they
                // already rode, so nothing but the glow changes.
                int emission = 0;
                if (row.length > 6) {
                    try {
                        emission = Integer.parseInt(row[6].trim());
                    } catch (NumberFormatException bad) {
                        emission = 0;
                    }
                }
                final int fEmission = Math.min(15, Math.max(0, emission));
                // 14-cy CAPTURED LIGHT: a quad-route row carries the captured
                // scene light its scene-lit quads rendered with — the replay
                // pins the display to THAT light instead of (emission, 15),
                // so the lightmap multiply matches the truth's per-face
                // smooth-light environment (the packed-wall machines rendered
                // ~0.52 warm at the walk; the legacy pin rendered them
                // fullbright). Legacy rows (light < 0) keep the 14-bk pin.
                final boolean capturedLight = r.lightBlock() >= 0 && r.lightSky() >= 0;
                final int pinBlock = capturedLight ? Math.min(15, Math.max(0, r.lightBlock())) : fEmission;
                final int pinSky = capturedLight ? Math.min(15, Math.max(0, r.lightSky())) : 15;
                org.bukkit.entity.ItemDisplay display = world(worldName()).spawn(at,
                        org.bukkit.entity.ItemDisplay.class, e -> {
                            e.setItemStack(stack);
                            e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                            e.setTransformation(tr);
                            e.setBrightness(new org.bukkit.entity.Display.Brightness(pinBlock, pinSky));
                        });
                log().info(LOG_PREFIX + "f2v gallery: page " + page + " cell "
                        + row[1] + "," + row[2] + "," + row[3] + " state=" + state
                        + " cmd=" + r.cmd() + " uuid=" + display.getUniqueId());
                n++;
                // 14-cy FULLBRIGHT COMPANION: the mesh's mod light-override
                // quads (the LED/window art the truth renders fullbright)
                // split into their own composite — same transform, pinned
                // (15, 15) so the graded scene light never dims them.
                if (r.fbCmd() != 0f) {
                    final float fbCmd = r.fbCmd();
                    ItemStack fbStack = new ItemStack(material);
                    fbStack.editMeta(meta -> {
                        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                        cmd.setFloats(List.of(fbCmd));
                        meta.setCustomModelDataComponent(cmd);
                    });
                    org.bukkit.entity.ItemDisplay fbDisplay = world(worldName()).spawn(at,
                            org.bukkit.entity.ItemDisplay.class, e -> {
                                e.setItemStack(fbStack);
                                e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                                e.setTransformation(tr);
                                e.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                            });
                    log().info(LOG_PREFIX + "f2v gallery: page " + page + " cell "
                            + row[1] + "," + row[2] + "," + row[3] + " state=" + state
                            + " fullbright cmd=" + fbCmd + " uuid=" + fbDisplay.getUniqueId());
                }
                // 14-dt THE MULTI-MESH EXTRAS: a state whose truth render is
                // several distinct captured meshes (the door's window, the
                // pillar's caps) spawns one extra display per remaining mesh
                // — same cell, same transform, the extra row's own captured
                // light fields (legacy rows keep the 14-bk pin). GUARD: only
                // when the pick rode the BARE-STATE capture row — a
                // position-calibrated 14-ak CT tile or 14-bt ctx hash row
                // already carries that position's full content, and drawing
                // the bare state's other meshes on top would double it.
                if (r == byQuadState.get(state)) {
                    java.util.List<dev.cim.f2v.GalleryPack.Resolved> extras =
                            dev.cim.f2v.GalleryPack.remainingCapturedMeshes(byQuadStateAll, r, state);
                    for (dev.cim.f2v.GalleryPack.Resolved extra : extras) {
                        final dev.cim.f2v.GalleryPack.Resolved xr = extra;
                        ItemStack xStack = new ItemStack(material);
                        xStack.editMeta(meta -> {
                            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                            cmd.setFloats(List.of(xr.cmd()));
                            meta.setCustomModelDataComponent(cmd);
                        });
                        final boolean xCaptured = xr.lightBlock() >= 0 && xr.lightSky() >= 0;
                        final int xPinBlock = xCaptured
                                ? Math.min(15, Math.max(0, xr.lightBlock())) : fEmission;
                        final int xPinSky = xCaptured
                                ? Math.min(15, Math.max(0, xr.lightSky())) : 15;
                        org.bukkit.entity.ItemDisplay xDisplay = world(worldName()).spawn(at,
                                org.bukkit.entity.ItemDisplay.class, e -> {
                                    e.setItemStack(xStack);
                                    e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                                    e.setTransformation(tr);
                                    e.setBrightness(new org.bukkit.entity.Display.Brightness(xPinBlock, xPinSky));
                                });
                        log().info(LOG_PREFIX + "f2v gallery: page " + page + " cell "
                                + row[1] + "," + row[2] + "," + row[3] + " state=" + state
                                + " extra-mesh cmd=" + xr.cmd() + " uuid=" + xDisplay.getUniqueId());
                    }
                }
            }
            return n;
        });
        return placed == null ? 0 : placed;
    }

    /**
     * Replay one truth-leg ENTITY gallery page manifest as REAL entities:
     * {@code c-imagine/scenes/gallery-entity-<ns>-p<page>.tsv} rows
     * {@code cell|x|y|z|entity|dyn|note} — one entity per cell, feet at the
     * cell position (x+0.5, y, z+0.5), yaw 180 / pitch 0 (the same freeze
     * contract the truth leg uses: Mob#setAware(false) = NMS NoAI,
     * NoGravity, Invulnerable, scoreboard tag {@code cimgallery}).
     *
     * <p>Entity types resolve through the Bukkit EntityType registry key
     * (vanilla keys only — a modded namespace has no stock-client renderer;
     * its rows are counted as {@code nochannel} and stay empty on this leg,
     * which the pixel join honestly reports RED until the entity-model
     * channel exists — roadmap M5.5/M5.6 accounting).
     *
     * <p>Returns the placed count; the RCON response marker is
     * {@code gallerypage-entity <ns> p<page> [series] -> placed=N nochannel=K
     * cells=M} — cells is the manifest's parsed cell-row count; a page with a
     * ZERO-cell manifest (create mobs: measured truth cells=0) is HONESTLY
     * empty, while cells=missing means the manifest file itself was absent —
     * two states that must never collapse into the same {0,0} again
     * (measured #34 forensics).
     */
    private int[] galleryPageEntity(String ns, int page, String series) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-entity" + (series.equals("mobs") ? "" : series)
                        + "-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-entity: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        final java.util.List<String[]> cells = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("cell\t")) {
                    cells.add(line.split("\t", -1));
                }
                // header / skip rows — nothing to spawn
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-entity: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        final String nsf = ns;
        final int pagef = page;
        int[] counts = callSync(() -> {
            int placed = 0, nochannel = 0;
            World w = world(worldName());
            // CLEAR THE WALL REGION FIRST (measured afebfbc: the truth scene
            // sweeps the wall blocks to air before spawning, the ours replay
            // did not — the entity pages shot the CREATE BLOCK WALL with the
            // mobs in front, and every cell differed by construction). The
            // same sweep, same coordinates as CimSceneMod's gallery region.
            for (int bx = -6; bx <= 6; bx++) {
                for (int by = -60; by <= -53; by++) {
                    for (int bz = 8; bz <= 10; bz++) {
                        try {
                            w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
                        } catch (Exception clearFail) {
                            // unloaded corner: forceload covers the region;
                            // never let one block abort the page
                        }
                    }
                }
            }
            // 14-du: the sweep cleared any light blocks in the band — the tracker follows
            placedLightBlocks.clear();
            for (String[] row : cells) {
                // cell|x|y|z|entity|dyn|note
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
                String entityKey = row[4];
                org.bukkit.entity.EntityType type = null;
                for (org.bukkit.entity.EntityType t : org.bukkit.entity.EntityType.values()) {
                    // UNKNOWN is a legacy sentinel declared FIRST in the enum
                    // and getKey() THROWS on it (measured run de1679d:
                    // "EntityType doesn't have key! Is it UNKNOWN?" killed
                    // the whole replay on the first row) — skip it and any
                    // other keyless entry
                    if (t == org.bukkit.entity.EntityType.UNKNOWN) {
                        continue;
                    }
                    String k;
                    try {
                        k = t.getKey().toString();
                    } catch (Exception keyless) {
                        continue;
                    }
                    if (k.equals(entityKey)) {
                        type = t;
                        break;
                    }
                }
                if (type == null) {
                    // not spawnable through the stock registry (a modded
                    // namespace) — the cell stays empty on this leg and the
                    // pixel join names it; nothing is hidden
                    nochannel++;
                    log().warning(LOG_PREFIX + "f2v gallery-entity: no stock entity type "
                            + entityKey + " — cell " + row[1] + "," + row[2] + "," + row[3]
                            + " left empty (nochannel)");
                    continue;
                }
                org.bukkit.Location at = new org.bukkit.Location(w,
                        x + 0.5, y, z + 0.5, 180.0f, 0.0f);
                try {
                    // randomizeData=false: no finalizeSpawn, so no random variant or
                    // gear. The truth leg's type.create() gets the defaults too
                    org.bukkit.entity.Entity e = w.spawnEntity(at, type, false);
                    if (e instanceof org.bukkit.entity.Mob m) {
                        // NoAI equivalent (paper 26.3 surface): a still mob —
                        // no movement, no look-at, no AI despawn
                        m.setAware(false);
                        m.setRemoveWhenFarAway(false);
                    }
                    e.setGravity(false);
                    e.setInvulnerable(true);
                    e.setRotation(180.0f, 0.0f);
                    e.addScoreboardTag("cimgallery");
                    placed++;
                    log().info(LOG_PREFIX + "f2v gallery-entity: page " + pagef
                            + " cell " + row[1] + "," + row[2] + "," + row[3]
                            + " entity=" + entityKey + " uuid=" + e.getUniqueId());
                } catch (Exception ex) {
                    log().warning(LOG_PREFIX + "f2v gallery-entity: spawn failed "
                            + entityKey + " at " + row[1] + "," + row[2] + "," + row[3]
                            + ": " + ex);
                }
            }
            return new int[]{placed, nochannel, cells.size()};
        });
        return counts == null ? new int[]{0, 0, -1} : counts;
    }

    /**
     * Replay one truth-leg ITEM gallery page manifest as ItemDisplays:
     * {@code c-imagine/scenes/gallery-item-<ns>-p<page>.tsv} rows
     * {@code cell|x|y|z|item|dyn|note} — one display per cell at the cell
     * CENTER, billboard FIXED, light PINNED 15/15 (the same rig contract the
     * truth leg summons with). Item stacks resolve to pack CMD carriers
     * (paper + custom_model_data float) through the resolved visual-items
     * rows — the SAME item-model channel the M5.2 hotbar datum proved; the
     * CI pack step generates a row for every gallery item. An item with no
     * pack row is {@code nochannel}: the cell stays empty and the pixel
     * join names it (honest backlog, never a silent skip).
     *
     * <p>Returns {@code [placed, nochannel, cells]}; the RCON response marker
     * is {@code gallerypage-item <ns> p<page> -> placed=N nochannel=K cells=M}
     * (cells=-1 prints as {@code cells=missing} — the manifest file itself
     * was absent, never collapsed with a zero-cell page).
     */
    private int[] galleryPageItem(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-item-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-item: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        final java.util.List<String[]> cells = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("cell\t")) {
                    cells.add(line.split("\t", -1));
                }
                // header / skip rows — nothing to spawn
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-item: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        // item id -> pack CMD carrier (the resolved visual-items rows carry
        // BOTH the hand-written manifest and the CI-generated gallery rows)
        final java.util.Map<String, Float> cmdByItem = new java.util.HashMap<>();
        if (f2v != null) {
            for (dev.cim.f2v.F2vPack.VisualItem r : f2v.items()) {
                cmdByItem.putIfAbsent(r.moddedId(), r.cmd());
            }
        }
        final int pagef = page;
        int[] counts = callSync(() -> {
            int placed = 0, nochannel = 0;
            World w = world(worldName());
            // SAME wall sweep the truth scene runs (12-ac symmetry law)
            for (int bx = -6; bx <= 6; bx++) {
                for (int by = -60; by <= -53; by++) {
                    for (int bz = 8; bz <= 10; bz++) {
                        try {
                            w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
                        } catch (Exception clearFail) {
                            // unloaded corner: forceload covers the region
                        }
                    }
                }
            }
            // 14-du: the sweep cleared any light blocks in the band — the tracker follows
            placedLightBlocks.clear();
            for (String[] row : cells) {
                // cell|x|y|z|item|dyn|note
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
                String itemId = row[4];
                Float cmd = cmdByItem.get(itemId);
                if (cmd == null) {
                    nochannel++;
                    log().warning(LOG_PREFIX + "f2v gallery-item: no pack CMD for "
                            + itemId + " — cell " + row[1] + "," + row[2] + ","
                            + row[3] + " left empty (nochannel)");
                    continue;
                }
                ItemStack stack = new ItemStack(Material.matchMaterial("paper"));
                stack.editMeta(meta -> {
                    CustomModelDataComponent c = meta.getCustomModelDataComponent();
                    c.setFloats(List.of(cmd));
                    meta.setCustomModelDataComponent(c);
                });
                org.bukkit.Location at = new org.bukkit.Location(w,
                        x + 0.5, y + 0.5, z + 0.5);
                final float fcmd = cmd;
                org.bukkit.entity.ItemDisplay display = w.spawn(at,
                        org.bukkit.entity.ItemDisplay.class, e -> {
                            e.setItemStack(stack);
                            e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                            // the same rig as the block pages
                            e.setTransformation(new org.bukkit.util.Transformation(
                                    new org.joml.Vector3f(GALLERY_TX, GALLERY_TY, GALLERY_TZ),
                                    new org.joml.Quaternionf(),
                                    new org.joml.Vector3f(GALLERY_SCALE, GALLERY_SCALE, GALLERY_SCALE),
                                    new org.joml.Quaternionf()));
                            e.setBrightness(new org.bukkit.entity.Display.Brightness(
                                    15, 15));
                        });
                log().info(LOG_PREFIX + "f2v gallery-item: page " + pagef
                        + " cell " + row[1] + "," + row[2] + "," + row[3]
                        + " item=" + itemId + " cmd=" + fcmd
                        + " uuid=" + display.getUniqueId());
                placed++;
            }
            return new int[]{placed, nochannel, cells.size()};
        });
        return counts == null ? new int[]{0, 0, -1} : counts;
    }

    /**
     * Replay one truth-leg PARTICLE gallery page manifest: every cell counts
     * as {@code nochannel} — Paper cannot spawn mod particles (the mod's
     * particle textures/logic exist only in the mod's client, which this
     * vanilla-client pack channel structurally cannot express). The wall is
     * still swept (the shot shows the honestly empty wall) and every cell is
     * counted, so the pixel join reports the full cell list — the particle
     * channel backlog is VISIBLE in the verdict, not a missing screenshot.
     *
     * <p>Returns {@code [placed=0, nochannel=N, cells=N]}; the RCON response
     * marker is {@code gallerypage-particle <ns> p<page> -> placed=0
     * nochannel=N cells=M} (cells=-1 prints as {@code cells=missing}).
     */
    /**
     * Replay one truth-leg SOUND gallery page manifest (2026-11-05, the F2V
     * SOUND channel — DIAGNOSTIC-first, first measurement). The truth leg's
     * capture is a packet-level ordered event-id stream; the replay sends
     * one REAL {@code ClientboundSoundPacket} per manifest cell to the rig
     * viewer and records the POST-rewrite id the vanilla 26.3 client
     * actually receives (one id per line, send order — the same artifact
     * shape the truth capture writes).
     *
     * <p>THE REWRITE: Paper's SOUND_EVENT registry holds no modded ids (a
     * modded {@code Holder} cannot even be constructed on this side), so the
     * replay resolves every cell through {@code c-imagine/f2v/
     * visual-sounds.tsv} (the pack-side mapping,
     * gallery-sounds-mapping.py): the mapped target is validated against the
     * LIVE registry and an absent target (drift) falls back to the
     * documented {@code minecraft:block.wood.place}, counted and logged as
     * {@code unmapped-fallback} — the stream records what actually played,
     * never a silence. A missing mapping file keeps the old honestly-empty
     * log (the channel is ABSENT, not empty).
     *
     * <p>Returns {@code [placed=N, nochannel=K, cells=M]}; the RCON marker
     * is {@code gallerypage-sound <ns> p<page> -> placed=N nochannel=K cells=M}.
     */
    private int[] galleryPageSound(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-sound-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-sound: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        // cell rows in file order: {x, y, z, soundId} (the truth plays them
        // in this order; the stream join is an ORDERED compare)
        java.util.List<String[]> cells = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                String[] p = line.split("\t");
                // MEASURED (run 42085254497 ours boot log): the HEADER row is
                // "cell\tx\ty\tz\tsound\tnote" — it starts with "cell" too,
                // so a bare p[0] check admitted it and the send loop died on
                // Integer.parseInt("x") per page (empty capture logs, the
                // whole channel silent on ours). A data row carries a
                // NUMERIC second field; the header carries the literal "x".
                if (p.length >= 5 && p[0].equals("cell")
                        && p[1].matches("-?\\d+")) {
                    cells.add(new String[]{p[1], p[2], p[3], p[4]});
                }
                // header / skip rows
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-sound: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        Map<String, String> mapping = readVisualMapping("visual-sounds.tsv");
        if (mapping == null) {
            // NO MAPPING FILE = no channel (a stale seed): the empty log is
            // the honest measurement of the channel's absence, never a
            // missing file (indistinguishable from a crashed walk otherwise)
            writeEmptyCapture("gallery-sound-" + ns + "-p" + page + ".log");
            log().warning(LOG_PREFIX + "f2v gallery-sound: " + cells.size()
                    + " cell(s) nochannel — no visual-sounds.tsv (no F2V sound"
                    + " rewrite on this seed; the capture stream stays empty)");
            return new int[]{0, cells.size(), cells.size()};
        }
        final String nsf = ns;
        final int pagef = page;
        Integer sent = callSync(() -> {
            Player viewer = Bukkit.getPlayerExact("cimviewer");
            if (viewer == null) {
                log().warning(LOG_PREFIX + "f2v gallery-sound: no cimviewer online — "
                        + cells.size() + " cell(s) nochannel (the stream needs the rig)");
                return Integer.valueOf(-1);
            }
            try {
                Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
                Object listener = NetWatch.connectionField(handle.getClass(), "connection").get(handle);
                java.util.List<String> stream = new java.util.ArrayList<>();
                java.util.List<String> subs = new java.util.ArrayList<>();
                int fallbacks = 0;
                int misses = 0;
                for (String[] cell : cells) {
                    String target = mapping.get(cell[3]);
                    if (target == null) {
                        subs.add(cell[3] + "\t" + DEFAULT_SOUND_TARGET + "\tunmapped");
                        target = DEFAULT_SOUND_TARGET;
                        fallbacks++;
                    }
                    Object holder = soundHolder(target);
                    if (holder == null && !target.equals(DEFAULT_SOUND_TARGET)) {
                        subs.add(cell[3] + "\t" + DEFAULT_SOUND_TARGET + "\tdrift:" + target);
                        fallbacks++;
                        target = DEFAULT_SOUND_TARGET;
                        holder = soundHolder(target);
                    }
                    if (holder == null) {
                        misses++;
                        continue;
                    }
                    Object id = makeIdentifier(identifierClass(), target);
                    Object packet = clientboundSoundPacket(holder,
                            Double.parseDouble(cell[0]) + 0.5,
                            Double.parseDouble(cell[1]) + 0.5,
                            Double.parseDouble(cell[2]) + 0.5);
                    if (packet == null) {
                        misses++;
                        continue;
                    }
                    sendToConnection(listener, packet);
                    stream.add(target);
                }
                writeCapture("gallery-sound-" + nsf + "-p" + pagef + ".log", stream);
                writeSubstitutions("gallery-sound-" + nsf + "-p" + pagef + ".subs.tsv", subs);
                log().warning(LOG_PREFIX + "f2v gallery-sound: page " + pagef
                        + " sent=" + stream.size() + " unmapped-fallback=" + fallbacks
                        + " substituted=" + subs.size() + " miss=" + misses
                        + " (the post-rewrite stream the vanilla client received;"
                        + " the per-source substitutions ride the .subs.tsv ledger)");
                return Integer.valueOf(stream.size());
            } catch (Throwable t) {
                log().warning(LOG_PREFIX + "f2v gallery-sound: replay failed: " + t);
                return Integer.valueOf(-2);
            }
        });
        if (sent == null || sent.intValue() < 0) {
            // viewer offline / reflection failure: no stream, honest nochannel
            writeEmptyCapture("gallery-sound-" + nsf + "-p" + pagef + ".log");
            return new int[]{0, cells.size(), cells.size()};
        }
        int placed = sent.intValue();
        return new int[]{placed, cells.size() - placed, cells.size()};
    }

    /** The documented last-resort stand-in of the SOUND channel. */
    private static final String DEFAULT_SOUND_TARGET = "minecraft:block.wood.place";

    /** The documented last-resort stand-in of the PARTICLE channel. */
    private static final String DEFAULT_PARTICLE_TARGET = "minecraft:poof";

    /**
     * Read one F2V visual mapping ({@code visual-sounds.tsv} /
     * {@code visual-particles.tsv}): {@code modded_id <TAB> target <TAB>
     * heuristic}. Returns null when the file is absent (the channel is
     * absent — callers keep their no-channel contract).
     */
    private static Map<String, String> readVisualMapping(String fileName) {
        Path f = Path.of(System.getProperty("user.dir"), "c-imagine", "f2v", fileName);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        Map<String, String> out = new java.util.HashMap<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t");
                if (p.length >= 2 && !p[0].isEmpty() && !p[1].isEmpty()) {
                    out.putIfAbsent(p[0], p[1]);
                }
            }
        } catch (IOException ioe) {
            return null;
        }
        return out;
    }

    /** The f2v mapping class (26.3 renamed ResourceLocation -> Identifier). */
    private static Class<?> identifierClass() throws ClassNotFoundException {
        try {
            return Class.forName("net.minecraft.resources.Identifier");
        } catch (ClassNotFoundException older) {
            return Class.forName("net.minecraft.resources.ResourceLocation");
        }
    }

    /**
     * Resolve a vanilla sound id to its registry {@code Holder} on the live
     * Paper registry (javap-verified 26.3 surface: BuiltInRegistries.
     * SOUND_EVENT, ResourceKey.create(Registries.SOUND_EVENT, id),
     * HolderGetter.get(key) -> Optional<Holder.Reference>). Null when the
     * id is not registered.
     */
    private static Object soundHolder(String target) {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = NetWatch.connectionField(registries, "SOUND_EVENT").get(null);
            Class<?> regKeys = Class.forName("net.minecraft.core.registries.Registries");
            Object regKey = NetWatch.connectionField(regKeys, "SOUND_EVENT").get(null);
            Class<?> rk = Class.forName("net.minecraft.resources.ResourceKey");
            Object key = rk.getMethod("create", regKey.getClass(),
                    identifierClass()).invoke(null, regKey,
                    makeIdentifier(identifierClass(), target));
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("get") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(key)) {
                    Object v = m.invoke(registry, key);
                    if (v instanceof java.util.Optional<?> opt) {
                        return opt.orElse(null);
                    }
                    return v;
                }
            }
        } catch (Throwable t) {
            // fall through: unregistered / surface drift
        }
        return null;
    }

    /** Build the 26.3 {@code ClientboundSoundPacket} (MASTER, v1 p1, seed 0). */
    private static Object clientboundSoundPacket(Object holder,
                                                 double x, double y, double z) {
        try {
            Class<?> source = Class.forName("net.minecraft.sounds.SoundSource");
            Object master = source.getField("MASTER").get(null);
            Class<?> pkt = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSoundPacket");
            for (java.lang.reflect.Constructor<?> c : pkt.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 8 && pt[0].isInstance(holder) && pt[1].isInstance(master)
                        && pt[2] == double.class && pt[5] == float.class && pt[7] == long.class) {
                    return c.newInstance(holder, master, x, y, z, 1.0f, 1.0f, 0L);
                }
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    /** Reflective one-arg send on the player's Connection (the NetWatch path). */
    private static void sendToConnection(Object listener, Object packet) throws Exception {
        for (Method m : listener.getClass().getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isInstance(packet)) {
                m.invoke(listener, packet);
                return;
            }
        }
        throw new IllegalStateException("no Connection.send(Object) surface");
    }

    /** One resolved particle send: the id that will PLAY + its SIMPLE options. */
    private record ResolvedParticle(String id, Object options) {
    }

    /**
     * Resolve a vanilla particle id to SIMPLE options on the live registry:
     * the registry ParticleType when it is a ParticleOptions (a
     * SimpleParticleType is), else the documented {@code minecraft:poof}
     * default; null when even the default is unavailable. The returned id
     * is always what the client will actually receive (the stream records
     * it, never the failed candidate).
     */
    private static ResolvedParticle resolveParticle(String target) {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object registry = NetWatch.connectionField(registries, "PARTICLE_TYPE").get(null);
            Class<?> rl = identifierClass();
            Object id = makeIdentifier(rl, target);
            Object value = null;
            for (Method m : registry.getClass().getMethods()) {
                if (m.getName().equals("getValue") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(id)) {
                    value = m.invoke(registry, id);
                    break;
                }
            }
            Class<?> options = Class.forName("net.minecraft.core.particles.ParticleOptions");
            if (options.isInstance(value)) {
                return new ResolvedParticle(target, value);
            }
        } catch (Throwable t) {
            // fall through: unregistered / surface drift
        }
        Object poof = particleTypeField(DEFAULT_PARTICLE_TARGET);
        return poof == null ? null : new ResolvedParticle(DEFAULT_PARTICLE_TARGET, poof);
    }

    /** A ParticleTypes.* constant by 26.3 field name (SCREAMING path). */
    private static Object particleTypeField(String target) {
        try {
            String path = target.contains(":")
                    ? target.substring(target.indexOf(':') + 1) : target;
            Class<?> types = Class.forName("net.minecraft.core.particles.ParticleTypes");
            return types.getField(path.toUpperCase(Locale.ROOT)).get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Build the 26.3 {@code ClientboundLevelParticlesPacket} (the 11-arg ctor). */
    private static Object clientboundLevelParticlesPacket(Object options,
                                                          double x, double y, double z) {
        try {
            Class<?> pkt = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket");
            for (java.lang.reflect.Constructor<?> c : pkt.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 11 && pt[0].isInstance(options)
                        && pt[1] == boolean.class && pt[3] == double.class
                        && pt[6] == float.class && pt[10] == int.class) {
                    // overrideLimiter=false, alwaysShow=true (the broadcast
                    // shape of the truth leg's ServerLevel.sendParticles),
                    // deltas 0/0/0 speed 0 count 1 — one STATIC particle
                    return c.newInstance(options, false, true, x, y, z,
                            0.0f, 0.0f, 0.0f, 0.0f, 1);
                }
            }
        } catch (Throwable t) {
            // fall through
        }
        return null;
    }

    /** Write an EMPTY capture log (the no-channel contract artifact). */
    private void writeEmptyCapture(String name) {
        writeCapture(name, java.util.List.of());
    }

    /** Write one capture log: one id per line, arrival order. */
    private void writeCapture(String name, java.util.List<String> stream) {
        Path cap = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes", name);
        try {
            Files.createDirectories(cap.getParent());
            Files.writeString(cap,
                    stream.isEmpty() ? "" : String.join("\n", stream) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v capture log write failed "
                    + cap + ": " + ioe);
        }
    }

    /**
     * THE SUBSTITUTION LEDGER (the content-fidelity audit, 2026-11-10): one
     * row per SUBSTITUTED source id — {@code source<TAB>resolved<TAB>reason}
     * ({@code unmapped} = no mapping row; {@code drift:<target>} = the mapped
     * target was absent from the live registry and degraded to the documented
     * default). Written for EVERY replay page even when empty: the
     * header-only file is the receipt that the page substituted nothing, so
     * "zero unexplained fallbacks" becomes a measured claim instead of an
     * absent one. The capture log's stream format stays untouched (the join
     * reads it positionally).
     */
    private void writeSubstitutions(String name, java.util.List<String> subs) {
        Path cap = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes", name);
        try {
            Files.createDirectories(cap.getParent());
            StringBuilder sb = new StringBuilder("source\tresolved\treason\n");
            for (String row : subs) {
                sb.append(row).append('\n');
            }
            Files.writeString(cap, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v substitution ledger write failed "
                    + cap + ": " + ioe);
        }
    }

    /**
     * Replay one truth-leg GUI (MENU) gallery page manifest (2026-11-04,
     * DIAGNOSTIC-first). Paper cannot instantiate a mod MenuType (the
     * menu classes live in the mod jar; no menu translation exists in the
     * layer/plugin), so every row counts as nochannel. The viewer's open
     * inventory is closed for determinism (the truth leg opens the menu
     * AFTER its manifest write; this leg closes whatever was open) — the
     * shot shows the honestly closed screen.
     *
     * <p>Returns {@code [placed=0, nochannel=N, cells=N]}; the RCON marker
     * is {@code gallerypage-gui <ns> p<page> -> placed=0 nochannel=N cells=M}.
     */
    private int[] galleryPageGui(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-gui-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-gui: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        int cells = 0;
        String menuClass = null;
        String opener = "";
        String sneakOpener = null;
        String menuId = null;
        String replay = null;
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("cell\t")) {
                    cells++;
                    String[] row = line.split("\t", -1);
                    if (row.length > 6) {
                        menuClass = row[6];
                    }
                    if (row.length > 4) {
                        menuId = row[4];
                    }
                    if (row.length > 5) {
                        opener = row[5].replace("opened-by:", "").replace("sneak+", "");
                        sneakOpener = row[5].replace("opened-by:", "").startsWith("sneak+")
                                ? row[5].replace("opened-by:", "") : null;
                    }
                }
                if (line.startsWith("replay\t")) {
                    // The walk's opener said plainly (a tuned item placed, then clicked): the
                    // layer replays it with the mod's own code where the walk's own is specialized.
                    String[] row = line.split("\t", -1);
                    if (row.length > 5) {
                        replay = row[5];
                    }
                }
                // header / skip rows (open-failed notes pass through — the
                // join reads them from the manifest, not from here)
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-gui: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        final int cellsf = cells;
        final String hudClass = menuClass;
        final String hudId = menuId;
        // The truth leg holds the item that opened the menu, and its slot shows it.
        final String heldId = opener.startsWith("item:") ? opener.substring("item:".length()) : null;
        // The live opener keeps the walk's sneak (Create's linked controller opens on a
        // sneaking use only); the held item's id is the same either way.
        final String harnessOpener = replay != null ? replay : sneakOpener != null ? sneakOpener : opener;
        callSync(() -> {
            org.bukkit.entity.Player viewer =
                    org.bukkit.Bukkit.getPlayerExact("cimviewer");
            if (viewer != null) {
                viewer.closeInventory();
                if (hudId != null) {
                    dev.cim.hud.HudBootstrap.openForHarness(viewer, hudId, hudClass, heldId, harnessOpener);
                }
            }
            World w = world(worldName());
            for (int bx = -6; bx <= 6; bx++) {
                for (int by = -60; by <= -53; by++) {
                    for (int bz = 8; bz <= 10; bz++) {
                        try {
                            w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
                        } catch (Exception clearFail) {
                            // unloaded corner: forceload covers the region
                        }
                    }
                }
            }
            return cellsf;
        });
        log().warning(LOG_PREFIX + "f2v gallery-gui: " + cellsf
                + " menu(s) nochannel — Paper cannot instantiate a mod MenuType");
        return new int[]{0, cells, cells};
    }

    /**
     * Replay one truth-leg BIOME-TINT gallery page manifest (2026-11-04,
     * DIAGNOSTIC-first). The tint sample rig (grass_block + oak_leaves, the
     * same cells the truth rig uses) IS placeable on Paper — placed as real
     * blocks so the shot compares the SAME surfaces. The biome itself is
     * not: a modded biome id is not in the Paper registry (the layer that
     * registers it does not run in the gallery ours leg), so every row
     * counts as nochannel and the shot shows the honestly untinted rig.
     *
     * <p>Returns {@code [placed, nochannel, cells]}; the RCON marker is
     * {@code gallerypage-biome <ns> p<page> -> placed=N nochannel=K cells=M}.
     */
    private int[] galleryPageBiome(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-biome-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-biome: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        final java.util.List<String[]> rows = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("cell\t") || line.startsWith("skip\t")) {
                    rows.add(line.split("\t", -1));
                }
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-biome: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        int[] counts = callSync(() -> {
            int placed = 0, nochannel = 0;
            World w = world(worldName());
            // 14-du: stale light blocks never leak into the biome shots
            clearPlacedLights(w);
            for (int bx = -6; bx <= 6; bx++) {
                for (int by = -60; by <= -53; by++) {
                    for (int bz = 8; bz <= 10; bz++) {
                        try {
                            w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
                        } catch (Exception clearFail) {
                            // unloaded corner: forceload covers the region
                        }
                    }
                }
            }
            // the SAME tint-sample rig the truth page places (real vanilla
            // blocks — placeable on both stacks; the TINT is the channel)
            try {
                w.getBlockAt(-5, -60, 9).setType(Material.GRASS_BLOCK, false);
                w.getBlockAt(-4, -60, 9).setType(Material.OAK_LEAVES, false);
            } catch (Exception rigFail) {
                // unloaded corner: forceload covers the region
            }
            for (String[] row : rows) {
                // cell|x|y|z|biome|note
                if (row.length < 5 || row[0].equals("skip")) {
                    continue;
                }
                // no biome translation: a modded id cannot be applied by
                // Paper (the vanilla surface fails by construction here —
                // the honest nochannel the join names)
                nochannel++;
            }
            return new int[]{placed, nochannel};
        });
        int cells = rows.size();
        log().warning(LOG_PREFIX + "f2v gallery-biome: " + (counts == null ? cells : counts[1])
                + " biome(s) nochannel — Paper has no modded-biome surface "
                + "(the sample rig is placed; the tint is not)");
        return new int[]{0, counts == null ? cells : counts[1], cells};
    }

    /**
     * The HELD leg of the ITEM gallery (2026-11-04, DIAGNOSTIC-first):
     * stage the page's FIRST item row into the viewer's hotbar slot 0 as
     * the pack's CMD carrier (the same resolution the item replay uses),
     * so the first-person shot (taken after the "1" hotbar key on BOTH
     * legs) proves the item channel in the hand. The item id comes from the
     * SAME manifest row the truth leg stages over RCON
     * ({@code item replace entity cimviewer hotbar.0 with <item>}) — the
     * two legs read the identical contract.
     *
     * <p>Returns {@code [itemId, cmd]} where cmd is the pack CMD number or
     * {@code nochannel}; the RCON marker is
     * {@code gallerypage-held <ns> p<page> -> held=<item> cmd=<n|nochannel>}.
     */
    private String[] galleryPageHeld(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-item-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-held: manifest missing: " + mf);
            return new String[]{"missing", "nochannel"};
        }
        String itemId = null;
        try {
            itemId = dev.cim.f2v.GalleryPack.firstSceneItem(mf);
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-held: manifest read failed "
                    + mf + ": " + ioe);
            return new String[]{"missing", "nochannel"};
        }
        if (itemId == null) {
            return new String[]{"none", "nochannel"};
        }
        Float cmd = null;
        if (f2v != null) {
            for (dev.cim.f2v.F2vPack.VisualItem r : f2v.items()) {
                if (r.moddedId().equals(itemId)) {
                    cmd = r.cmd();
                    break;
                }
            }
        }
        if (cmd == null) {
            log().warning(LOG_PREFIX + "f2v gallery-held: no pack CMD for " + itemId
                    + " — the hand stays empty (nochannel)");
            return new String[]{itemId, "nochannel"};
        }
        final float fcmd = cmd;
        final String fid = itemId;
        String placed = callSync(() -> {
            org.bukkit.entity.Player viewer =
                    org.bukkit.Bukkit.getPlayerExact("cimviewer");
            if (viewer == null) {
                return "no-viewer";
            }
            ItemStack stack = new ItemStack(Material.matchMaterial("paper"));
            stack.editMeta(meta -> {
                CustomModelDataComponent c = meta.getCustomModelDataComponent();
                c.setFloats(List.of(fcmd));
                meta.setCustomModelDataComponent(c);
            });
            viewer.getInventory().setItem(0, stack);
            return fid;
        });
        return new String[]{placed == null ? "no-viewer" : placed, String.valueOf(cmd)};
    }

    /**
     * The INVENTORY gallery's ours-side fill (round 2, 2026-11-04,
     * DIAGNOSTIC-first): resolve EVERY manifest row's item through the pack
     * item channel (the SAME resolution the item replay uses) and set the
     * viewer's real inventory slots (0..8 hotbar, 9..35 main) to paper+CMD
     * carriers. The truth leg fills the SAME slots over RCON with the REAL
     * modded items (read from the SAME manifest) — the open-inventory shot
     * then compares the GUI ITEMS channel: real modded item models vs the
     * pack's CMD carriers, both in the vanilla inventory grid. Items with
     * no pack row count as nochannel (the slot stays empty, the join names
     * it). The E/ESC key presses and the shot live in the CI walk.
     *
     * <p>Returns {@code [placed, nochannel, cells]}; the RCON marker is
     * {@code gallerypage-inv <ns> p<page> -> placed=N nochannel=K cells=M}.
     */
    private int[] galleryPageInv(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-inv-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-inv: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        final java.util.List<String[]> slots = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("slot\t")) {
                    continue;   // header
                }
                if (!line.isBlank()) {
                    slots.add(line.split("\t", -1));
                }
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-inv: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        // item id -> pack CMD carrier (the resolved visual-items rows carry
        // BOTH the hand-written manifest and the CI-generated gallery rows)
        final java.util.Map<String, Float> cmdByItem = new java.util.HashMap<>();
        if (f2v != null) {
            for (dev.cim.f2v.F2vPack.VisualItem r : f2v.items()) {
                cmdByItem.putIfAbsent(r.moddedId(), r.cmd());
            }
        }
        int[] counts = callSync(() -> {
            int placed = 0, nochannel = 0;
            org.bukkit.entity.Player viewer =
                    org.bukkit.Bukkit.getPlayerExact("cimviewer");
            if (viewer == null) {
                log().warning(LOG_PREFIX + "f2v gallery-inv: no cimviewer online — fill skipped");
                return new int[]{0, slots.size(), slots.size()};
            }
            for (String[] row : slots) {
                // slot|item  (slot = hotbar.0..8 | inventory.9..35)
                if (row.length < 2) {
                    continue;
                }
                String slotName = row[0];
                String itemId = row[1];
                Float cmd = cmdByItem.get(itemId);
                if (cmd == null) {
                    nochannel++;
                    log().warning(LOG_PREFIX + "f2v gallery-inv: no pack CMD for "
                            + itemId + " — slot " + slotName + " left empty (nochannel)");
                    continue;
                }
                int bukkitSlot;
                if (slotName.startsWith("hotbar.")) {
                    bukkitSlot = Integer.parseInt(slotName.substring("hotbar.".length()));
                } else if (slotName.startsWith("inventory.")) {
                    // THE VANILLA SLOT SEMANTICS (measured #302): the
                    // manifest's inventory.N names the vanilla MAIN slot N
                    // (0..26) — the truth's rcon `item replace` passes it
                    // raw — and Bukkit numbers the same slot N+9 (0..8
                    // hotbar, 9..35 main). The old direct parse filled the
                    // main row N-9 while the truth filled row N: the 9-slot
                    // permutation the join measured.
                    bukkitSlot = Integer.parseInt(slotName.substring("inventory.".length())) + 9;
                } else {
                    continue;
                }
                if (bukkitSlot < 0 || bukkitSlot > 35) {
                    continue;
                }
                ItemStack stack = new ItemStack(Material.matchMaterial("paper"));
                final float fcmd = cmd;
                stack.editMeta(meta -> {
                    CustomModelDataComponent c = meta.getCustomModelDataComponent();
                    c.setFloats(List.of(fcmd));
                    meta.setCustomModelDataComponent(c);
                });
                viewer.getInventory().setItem(bukkitSlot, stack);
                placed++;
            }
            viewer.updateInventory();
            return new int[]{placed, nochannel, slots.size()};
        });
        return counts == null ? new int[]{0, 0, -1} : counts;
    }

    /**
     * Replay one truth-leg PARTICLE gallery page manifest (2026-11-05, the
     * F2V PARTICLE channel — DIAGNOSTIC-first, first measurement). The truth
     * leg spawned one static particle per cell; the replay sends one REAL
     * {@code ClientboundLevelParticlesPacket} per manifest cell to the rig
     * viewer and records the POST-rewrite particle id the vanilla 26.3
     * client receives (one id per line, send order —
     * {@code gallery-particle-<ns>-p<page>.log}, the stream-diff artifact
     * shape; the pixel shots stay what they are, the stream is the new
     * channel proof).
     *
     * <p>THE OPTIONS CONTRACT (documented, gallery-particles-mapping.py): a
     * modded particle's serialized options bytes are NOT portable, so the
     * mapped vanilla type is sent as its SIMPLE default options (the
     * registry ParticleType when it is a SimpleParticleType) carrying the
     * original count/speed/offsets — data-carrying options DEGRADE to the
     * simple lookalike. An exact-name candidate absent from the live Paper
     * registry falls back to the documented {@code minecraft:poof}. A
     * missing mapping file keeps the old honestly-empty log (the channel is
     * ABSENT, not empty).
     *
     * <p>Returns {@code [placed=N, nochannel=K, cells=M]}; the RCON marker
     * is {@code gallerypage-particle <ns> p<page> -> placed=N nochannel=K cells=M}.
     */
    private int[] galleryPageParticle(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-particle-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-particle: manifest missing: " + mf);
            return new int[]{0, 0, -1};
        }
        // cell rows in file order: {x, y, z, particleId} (the truth spawned
        // them in this order; the stream join is an ORDERED compare)
        java.util.List<String[]> cells = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                String[] p = line.split("\t");
                // MEASURED (run 42085254497 ours boot log): the HEADER row is
                // "cell\tx\ty\tz\tsound\tnote" — it starts with "cell" too,
                // so a bare p[0] check admitted it and the send loop died on
                // Integer.parseInt("x") per page (empty capture logs, the
                // whole channel silent on ours). A data row carries a
                // NUMERIC second field; the header carries the literal "x".
                if (p.length >= 5 && p[0].equals("cell")
                        && p[1].matches("-?\\d+")) {
                    cells.add(new String[]{p[1], p[2], p[3], p[4]});
                }
                // header / skip rows
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-particle: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, -1};
        }
        Map<String, String> mapping = readVisualMapping("visual-particles.tsv");
        if (mapping == null) {
            // NO MAPPING FILE = no channel (a stale seed): the empty log is
            // the honest measurement of the channel's absence
            writeEmptyCapture("gallery-particle-" + ns + "-p" + page + ".log");
            log().warning(LOG_PREFIX + "f2v gallery-particle: " + cells.size()
                    + " cell(s) nochannel — no visual-particles.tsv (no F2V"
                    + " particle channel on this seed; the capture stream stays empty)");
            return new int[]{0, cells.size(), cells.size()};
        }
        final String nsf = ns;
        final int pagef = page;
        Integer sent = callSync(() -> {
            World w = world(worldName());
            for (int bx = -6; bx <= 6; bx++) {
                for (int by = -60; by <= -53; by++) {
                    for (int bz = 8; bz <= 10; bz++) {
                        try {
                            w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
                        } catch (Exception clearFail) {
                            // unloaded corner: forceload covers the region
                        }
                    }
                }
            }
            // 14-du: the sweep cleared any light blocks in the band — the tracker follows
            placedLightBlocks.clear();
            Player viewer = Bukkit.getPlayerExact("cimviewer");
            if (viewer == null) {
                log().warning(LOG_PREFIX + "f2v gallery-particle: no cimviewer online — "
                        + cells.size() + " cell(s) nochannel (the stream needs the rig)");
                return Integer.valueOf(-1);
            }
            try {
                Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
                Object listener = NetWatch.connectionField(handle.getClass(), "connection").get(handle);
                java.util.List<String> stream = new java.util.ArrayList<>();
                java.util.List<String> subs = new java.util.ArrayList<>();
                int fallbacks = 0;
                int misses = 0;
                for (String[] cell : cells) {
                    String target = mapping.get(cell[3]);
                    if (target == null) {
                        subs.add(cell[3] + "\t" + DEFAULT_PARTICLE_TARGET + "\tunmapped");
                        target = DEFAULT_PARTICLE_TARGET;
                        fallbacks++;
                    }
                    ResolvedParticle resolved = resolveParticle(target);
                    if (resolved == null) {
                        misses++;
                        continue;
                    }
                    if (!resolved.id().equals(target)) {
                        subs.add(cell[3] + "\t" + resolved.id() + "\tdrift:" + target);
                        fallbacks++;
                    }
                    Object packet = clientboundLevelParticlesPacket(resolved.options(),
                            Double.parseDouble(cell[0]) + 0.5,
                            Double.parseDouble(cell[1]) + 0.5,
                            Double.parseDouble(cell[2]) + 0.5);
                    if (packet == null) {
                        misses++;
                        continue;
                    }
                    sendToConnection(listener, packet);
                    stream.add(resolved.id());
                }
                writeCapture("gallery-particle-" + nsf + "-p" + pagef + ".log", stream);
                writeSubstitutions("gallery-particle-" + nsf + "-p" + pagef + ".subs.tsv", subs);
                log().warning(LOG_PREFIX + "f2v gallery-particle: page " + pagef
                        + " sent=" + stream.size() + " unmapped-fallback=" + fallbacks
                        + " substituted=" + subs.size() + " miss=" + misses
                        + " (the post-rewrite stream the vanilla client received;"
                        + " the per-source substitutions ride the .subs.tsv ledger)");
                return Integer.valueOf(stream.size());
            } catch (Throwable t) {
                log().warning(LOG_PREFIX + "f2v gallery-particle: replay failed: " + t);
                return Integer.valueOf(-2);
            }
        });
        if (sent == null || sent.intValue() < 0) {
            // viewer offline / reflection failure: no stream, honest nochannel
            writeEmptyCapture("gallery-particle-" + nsf + "-p" + pagef + ".log");
            return new int[]{0, cells.size(), cells.size()};
        }
        int placed = sent.intValue();
        return new int[]{placed, cells.size() - placed, cells.size()};
    }

    /**
     * Replay one truth-leg FLUID gallery page manifest:
     * {@code c-imagine/scenes/gallery-fluid-<ns>-p<page>.tsv} rows
     * {@code cell|x|y|z|fluid|dyn|channel|extra}. THE MULTI-SURFACE LADDER's
     * ours side (round 2, 2026-11-04 + 14-cd 2026-11-07):
     * <ul>
     *   <li>THE GLASS CASE (14-cd) builds for EVERY non-skip cell row first —
     *       the truth leg shells every cell before the fluid goes in, the
     *       replay must frame identically (the #231 forensics: the shell
     *       skipped with the nochannel branch left the ours page bare sky);</li>
     *   <li>{@code block} — vanilla-namespace rows are placed as REAL liquid
     *       states (Bukkit BlockData) inside their glass cases — the vanilla
     *       client renders them through the same liquid surface the truth
     *       client uses; modded rows replay the settled native liquid capture
     *       with its registered material, geometry and vertex RGBA. A default
     *       falling state that settled to vanilla air replays that exact state;</li>
     *   <li>{@code mod-tank} — the per-cell native capture includes the settled
     *       tank block and its actual block-entity-rendered contents;</li>
     *   <li>{@code texture-model} — the fluid id resolves through the pack's
     *       fluid-texture manifest (visual-gallery-fluids.tsv) to a
     *       paper+CMD item_display rig; the truth side has NO native surface
     *       for these cells (the join compares truth-empty vs ours-model
     *       honestly, dyn class);</li>
     *   <li>everything else (buckets on cells whose rig the pack lacks,
     *       states Paper cannot express, bare nochannel) is {@code nochannel}:
     *       the cell keeps its glass case and the pixel join names it (honest
     *       backlog, never a silent skip). Skip rows pass through untouched
     *       (both legs recorded them identically).</li>
     * </ul>
     *
     * <p>Returns {@code [placed, nochannel, caseonly, cells]}; the RCON
     * response marker is {@code gallerypage-fluid <ns> p<page> -> placed=N
     * nochannel=K caseonly=J cells=M} (cells=-1 prints as {@code cells=missing}).
     */
    private int[] galleryPageFluid(String ns, int page) {
        Path mf = Path.of(System.getProperty("user.dir"), "c-imagine", "scenes",
                "gallery-fluid-" + ns + "-p" + page + ".tsv");
        if (!Files.isRegularFile(mf)) {
            log().warning(LOG_PREFIX + "f2v gallery-fluid: manifest missing: " + mf);
            return new int[]{0, 0, 0, -1};
        }
        final java.util.List<String[]> rows = new java.util.ArrayList<>();
        try {
            for (String line : dev.cim.f2v.GalleryPack.readSceneLines(mf)) {
                if (line.startsWith("cell\t") || line.startsWith("skip\t")) {
                    rows.add(line.split("\t", -1));
                }
            }
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-fluid: manifest read failed "
                    + mf + ": " + ioe);
            return new int[]{0, 0, 0, -1};
        }
        final java.util.Map<String, dev.cim.f2v.GalleryPack.Resolved> capturedTanks;
        try {
            capturedTanks = dev.cim.f2v.GalleryPack.capturedSceneRows(mf,
                    f2v == null ? List.of() : f2v.gallery());
        } catch (IOException ioe) {
            log().warning(LOG_PREFIX + "f2v gallery-fluid: native mesh context read failed " + mf + ": " + ioe);
            return new int[]{0, 0, 0, -1};
        }
        final int pagef = page;
        int[] counts = callSync(() -> {
            int placed = 0, nochannel = 0, caseonly = 0;
            World w = world(worldName());
            // SAME wall sweep the truth scene runs (12-ac symmetry law) —
            // covers the pitch-2 fluid grid and its glass cases entirely
            for (int bx = -6; bx <= 6; bx++) {
                for (int by = -60; by <= -53; by++) {
                    for (int bz = 8; bz <= 10; bz++) {
                        try {
                            w.getBlockAt(bx, by, bz).setType(Material.AIR, false);
                        } catch (Exception clearFail) {
                            // unloaded corner: forceload covers the region
                        }
                    }
                }
            }
            // 14-du: the sweep cleared any light blocks in the band — the tracker follows,
            // then this page's truth-measured light field lands (the glow fluids light
            // their cases: the liquid_null spill measured block 3-5 at the cells)
            placedLightBlocks.clear();
            String fluidMfName = mf.getFileName().toString();
            placeLightsFromTruth(w, mf.resolveSibling(
                    (fluidMfName.endsWith(".tsv")
                            ? fluidMfName.substring(0, fluidMfName.length() - 4) : fluidMfName)
                            + ".light"));
            for (String[] row : rows) {
                // cell|x|y|z|fluid|dyn|channel|extra   (skip|...|fluid|dyn|reason)
                if (row.length < 5 || row[0].equals("skip")) {
                    continue;
                }
                String channel = row.length > 6 ? row[6] : "";
                String extra = row.length > 7 ? row[7] : "";
                int x, y, z;
                try {
                    x = Integer.parseInt(row[1]);
                    y = Integer.parseInt(row[2]);
                    z = Integer.parseInt(row[3]);
                } catch (NumberFormatException bad) {
                    continue;
                }
                // THE GLASS CASE FIRST (14-cd): the truth leg builds the
                // sealed shell for EVERY channel=block/mod-tank row BEFORE
                // the fluid goes in — the ours replay must frame its cells
                // identically (the #231 forensics: the nochannel branch
                // skipped the shell too and the whole ours fluid page shot
                // read as bare sky, ae 370K-1.8M on rows whose truth content
                // was just the case + the neighbour spill). Skip rows keep
                // their recorded skip (both legs named it).
                try {
                    org.bukkit.block.data.BlockData glass = Material.GLASS.createBlockData();
                    int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
                            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                    for (int[] f : faces) {
                        int bx = x + f[0], by = y + f[1], bz = z + f[2];
                        if (by < -60) {
                            continue; // the floor stays
                        }
                        w.getBlockAt(bx, by, bz).setBlockData(glass, false);
                    }
                } catch (Exception caseFail) {
                    log().warning(LOG_PREFIX + "f2v gallery-fluid: glass case failed at "
                            + row[1] + "," + row[2] + "," + row[3] + ": " + caseFail);
                }
                // THE MULTI-SURFACE LADDER's ours side (round 2 + 14-cd):
                // block rows replay as real vanilla liquid states (unchanged);
                // Modded block rows replay their exact native liquid mesh;
                // a missing capture remains nochannel. The settled state may
                // genuinely be vanilla air, which replays as air. Mod-tank rows
                // replay the native captured tank and its filled contents;
                // texture-model rows
                // keep the flat fluid-texture rig. Everything else is
                // nochannel (the honest backlog).
                Float rigCmd = null;
                String tankState = null;
                boolean caseOnly = false;
                if (channel.equals("block")) {
                    if (extra.startsWith("minecraft:")) {
                        tankState = extra;
                    } else {
                        // Replay the actual settled native liquid mesh, including
                        // height, flow UVs, tint/alpha and translucent material.
                        var liquid = capturedTanks.get(row[1] + "," + row[2] + "," + row[3]);
                        if (liquid == null) {
                            nochannel++;
                            log().warning(LOG_PREFIX + "f2v gallery-fluid: no native liquid capture for "
                                    + row[4] + " at " + row[1] + "," + row[2] + "," + row[3]);
                            continue;
                        }
                        rigCmd = liquid.cmd();
                    }
                } else if (channel.equals("mod-tank")) {
                    // The captured model includes the real settled tank AND its BER contents.
                    // A missing capture remains a named failure; no empty/static/cube substitute.
                    dev.cim.f2v.GalleryPack.Resolved tank = capturedTanks.get(
                            row[1] + "," + row[2] + "," + row[3]);
                    if (tank == null) {
                        nochannel++;
                        log().warning(LOG_PREFIX + "f2v gallery-fluid: no native tank capture for " + row[4]
                                + " at " + row[1] + "," + row[2] + "," + row[3]);
                        continue;
                    }
                    rigCmd = tank.cmd();
                } else if (channel.equals("texture-model")) {
                    // extra = the fluid id
                    rigCmd = f2v != null ? f2v.fluids().get(extra) : null;
                    if (rigCmd == null) {
                        nochannel++;
                        log().warning(LOG_PREFIX + "f2v gallery-fluid: no pack fluid-texture row for "
                                + extra + " — cell " + row[1] + "," + row[2] + ","
                                + row[3] + " left empty (nochannel)");
                        continue;
                    }
                } else {
                    nochannel++;
                    log().warning(LOG_PREFIX + "f2v gallery-fluid: " + row[4]
                            + " channel=" + channel + " — cell " + row[1] + ","
                            + row[2] + "," + row[3] + " left empty (nochannel)");
                    continue;
                }
                try {
                    if (rigCmd != null) {
                        // the item_display rig grammar the item walk proved
                        // (paper + CMD carrier, fixed billboard, pinned
                        // brightness) — the 14-cd cube and the flat
                        // texture-model rigs share it
                        ItemStack stack = new ItemStack(Material.matchMaterial("paper"));
                        final float fcmd = rigCmd;
                        stack.editMeta(meta -> {
                            CustomModelDataComponent c = meta.getCustomModelDataComponent();
                            c.setFloats(List.of(fcmd));
                            meta.setCustomModelDataComponent(c);
                        });
                        org.bukkit.Location at = new org.bukkit.Location(w,
                                x + 0.5, y + 0.5, z + 0.5);
                        w.spawn(at, org.bukkit.entity.ItemDisplay.class, e -> {
                            e.setItemStack(stack);
                            e.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                            if (channel.equals("mod-tank") || channel.equals("block")) {
                                e.setTransformation(new org.bukkit.util.Transformation(
                                        new org.joml.Vector3f(GALLERY_TX, GALLERY_TY, GALLERY_TZ),
                                        new org.joml.Quaternionf().rotationY((float) Math.PI),
                                        new org.joml.Vector3f(GALLERY_SCALE, GALLERY_SCALE, GALLERY_SCALE),
                                        new org.joml.Quaternionf()));
                            }
                            e.setBrightness(new org.bukkit.entity.Display.Brightness(
                                    (channel.equals("mod-tank") || channel.equals("block")) ? 0 : 15, 15));
                        });
                        log().info(LOG_PREFIX + "f2v gallery-fluid: page " + pagef
                                + " cell " + row[1] + "," + row[2] + "," + row[3]
                                + " fluid=" + row[4] + " rig cmd=" + rigCmd);
                        placed++;
                    } else if (caseOnly) {
                        // the case IS the surface (the truth's decaying
                        // falling rows render case-only too) — counted, named
                        caseonly++;
                        log().info(LOG_PREFIX + "f2v gallery-fluid: page " + pagef
                                + " cell " + row[1] + "," + row[2] + "," + row[3]
                                + " fluid=" + row[4] + " case-only");
                    } else if (tankState != null) {
                        w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(tankState), false);
                        log().info(LOG_PREFIX + "f2v gallery-fluid: page " + pagef
                                + " cell " + row[1] + "," + row[2] + "," + row[3]
                                + " fluid=" + row[4] + " state=" + tankState);
                        placed++;
                    }
                } catch (IllegalArgumentException parseFail) {
                    nochannel++;
                    log().warning(LOG_PREFIX + "f2v gallery-fluid: state " + extra
                            + " not expressible on Paper — cell " + row[1] + ","
                            + row[2] + "," + row[3] + " left empty (nochannel: "
                            + parseFail.getMessage() + ")");
                }
            }
            return new int[]{placed, nochannel, caseonly, rows.size()};
        });
        return counts == null ? new int[]{0, 0, 0, -1} : counts;
    }

    /** Despawn every ItemDisplay AND every tagged gallery entity in every
     *  world (page swaps; the tag is shared with the truth leg). Returns the
     *  removed count. */
    private int galleryClear() {
        Integer removed = callSync(() -> {
            int n = 0;
            for (World w : Bukkit.getWorlds()) {
                for (org.bukkit.entity.ItemDisplay d : w.getEntitiesByClass(
                        org.bukkit.entity.ItemDisplay.class)) {
                    d.remove();
                    n++;
                }
                for (org.bukkit.entity.Entity e : w.getEntities()) {
                    if (e.getScoreboardTags().contains("cimgallery")) {
                        e.remove();
                        n++;
                    }
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
        // Offer the actual served snapshot after login so the stand can
        // observe the client's real download AND reload protocol result.
        if (args.length == 2 && args[0].equalsIgnoreCase("pack")) {
            if (!sender.isOp()) {
                sender.sendMessage(LOG_PREFIX + "pack: operator required");
                return true;
            }
            var player = getServer().getPlayerExact(args[1]);
            if (player == null || visualPack == null) {
                sender.sendMessage(LOG_PREFIX + "pack: player or served pack unavailable");
                return true;
            }
            player.setResourcePack(visualPack.url(),
                    java.util.HexFormat.of().parseHex(visualPack.sha1()), true);
            sender.sendMessage(LOG_PREFIX + "pack: offered " + visualPack.sha1() + " to " + player.getName());
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hudpress")) {
            String done = callSync(() -> dev.cim.hud.HudBootstrap.pressForHarness(
                    org.bukkit.Bukkit.getPlayerExact("cimviewer"), args[1]));
            sender.sendMessage(LOG_PREFIX + "hudpress " + done);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("hudlist")) {
            try {
                Path list = Path.of(System.getProperty("user.dir"), "c-imagine", "hud-screens.txt");
                Files.write(list, dev.cim.hud.HudBootstrap.listForHarness(), StandardCharsets.UTF_8);
                sender.sendMessage(LOG_PREFIX + "hudlist " + list);
            } catch (IOException e) {
                sender.sendMessage(LOG_PREFIX + "hudlist failed: " + e);
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hudopen")) {
            boolean opened = callSync(() -> dev.cim.hud.HudBootstrap.openNamedForHarness(
                    org.bukkit.Bukkit.getPlayerExact("cimviewer"), args[1]));
            sender.sendMessage(LOG_PREFIX + "hudopen " + opened);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hudaim")) {
            String aimed = callSync(() -> dev.cim.hud.HudBootstrap.aimForHarness(
                    org.bukkit.Bukkit.getPlayerExact("cimviewer"), args[1]));
            sender.sendMessage(LOG_PREFIX + "hudaim " + aimed);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("guiclose")) {
            callSync(() -> {
                dev.cim.hud.HudBootstrap.closeForHarness(org.bukkit.Bukkit.getPlayerExact("cimviewer"));
                return 0;
            });
            return true;
        }
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
        // /cim gallerypage-entity <ns> <page> [dyn|large] — the M5.5 ENTITY
        // GALLERY ours-leg surface (owner directive 2026-10-31: mobs 100%
        // identical): replay a truth-leg entity manifest as REAL frozen
        // entities — the same spawn contract the truth leg uses, no pack
        // channel involved. Series tail: default = the gateable Mob wall;
        // `large` = oversized/animated Mobs (the dragon, ghast, wither —
        // universal bbox rule); `dyn` = non-Mobs. The non-default series
        // are diagnostics, never the gate.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-entity")) {
            try {
                int page = Integer.parseInt(args[2]);
                String series = args.length >= 4
                        && (args[3].equalsIgnoreCase("dyn") || args[3].equalsIgnoreCase("large"))
                        ? args[3].toLowerCase(java.util.Locale.ROOT) : "mobs";
                int[] counts = galleryPageEntity(args[1], page, series);
                sender.sendMessage(LOG_PREFIX + "gallerypage-entity " + args[1]
                        + " p" + page + (series.equals("mobs") ? "" : " " + series)
                        + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-entity: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-item <ns> <page> — the M5.5 ITEM gallery ours-leg
        // surface (owner directive 2026-10-31: ALL content under the verdict):
        // replay a truth-leg item manifest as ItemDisplays carrying the pack's
        // CMD carrier (the same item-model channel the M5.2 hotbar datum
        // proved — visual-items.tsv rows are generated for every gallery item
        // by the CI pack step). Items with no pack row count as nochannel
        // (empty cell, named by the pixel join) — never a silent skip.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-item")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageItem(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-item " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-item: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-particle <ns> <page> — the M5.5 PARTICLE gallery
        // ours-leg surface: the truth manifests are the cell contract; Paper
        // has NO mod-particle channel, so every cell counts as nochannel and
        // the shot shows the honestly empty wall — the pixel join names every
        // cell (the backlog is visible, never hidden behind a missing shot).
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-particle")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageParticle(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-particle " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-particle: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-fluid <ns> <page> — the M5.5 FLUID gallery
        // ours-leg surface: replay a truth-leg fluid manifest;
        // vanilla-namespace block rows become REAL sealed liquid states,
        // everything else is nochannel (honest backlog the join names).
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-fluid")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageFluid(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-fluid " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-fluid: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim galleryclear — despawn every ItemDisplay + tagged gallery
        // entity (page swaps).
        // /cim gallerypage-sound <ns> <page> — the SOUND gallery ours-leg
        // surface (2026-11-04, DIAGNOSTIC-first): replay the truth-leg
        // sound manifest. Paper has NO mod-sound channel (no F2V sound
        // rewrite exists in this plugin — verified before wiring), so every
        // cell counts as nochannel and the page writes its own replay log
        // (the same artifact shape the truth leg's packet capture emits —
        // the file sound-diff.py joins). The empty stream is the honest
        // measurement of the channel's absence, never a silence.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-sound")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageSound(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-sound " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-sound: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-gui <ns> <page> — the GUI (MENU) gallery
        // ours-leg surface (2026-11-04, DIAGNOSTIC-first): replay the
        // truth-leg gui manifest. Paper cannot instantiate a mod MenuType
        // (no menu translation in the layer/plugin), so every row counts as
        // nochannel — the shot shows the honestly closed screen.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-gui")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageGui(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-gui " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-gui: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-biome <ns> <page> — the BIOME-TINT gallery
        // ours-leg surface (2026-11-04, DIAGNOSTIC-first): replay the
        // truth-leg biome manifest. Paper cannot set a modded biome
        // (the ids are not in the Paper registry without the layer), so
        // every row counts as nochannel — the shot shows the honestly
        // untinted sample rig.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-biome")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageBiome(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-biome " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-biome: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-held <ns> <page> — the HELD (first-person) leg of
        // the ITEM gallery (2026-11-04, DIAGNOSTIC-first): take the page's
        // FIRST item row (the same manifest row the truth leg stages into
        // the viewer's hotbar over RCON), resolve its pack CMD carrier, and
        // put it into the viewer's hotbar slot 0 — the shot (after the
        // "1" hotbar key) proves the item channel in first person.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-held")) {
            try {
                int page = Integer.parseInt(args[2]);
                String[] res = galleryPageHeld(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-held " + args[1]
                        + " p" + page + " -> held=" + res[0] + " cmd=" + res[1]);
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-held: bad page number: " + args[2]);
            }
            return true;
        }
        // /cim gallerypage-inv <ns> <page> — the INVENTORY gallery
        // ours-leg surface (round 2, DIAGNOSTIC-first): fill the viewer's
        // real inventory slots from the truth manifest (the same rows the
        // truth leg stages with real items over RCON), each item as the
        // pack's CMD carrier; the E/ESC key presses + the shot live in the
        // CI walk on BOTH legs.
        if (args.length >= 3 && args[0].equalsIgnoreCase("gallerypage-inv")) {
            try {
                int page = Integer.parseInt(args[2]);
                int[] counts = galleryPageInv(args[1], page);
                sender.sendMessage(LOG_PREFIX + "gallerypage-inv " + args[1]
                        + " p" + page + " -> placed=" + counts[0]
                        + " nochannel=" + counts[1]
                        + " cells=" + (counts[2] < 0 ? "missing" : counts[2]));
            } catch (NumberFormatException nfe) {
                sender.sendMessage(LOG_PREFIX + "gallerypage-inv: bad page number: " + args[2]);
            }
            return true;
        }
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
        sender.sendMessage(LOG_PREFIX + "gallery: /cim gallerypage <ns> <page> "
                + "| /cim gallerypage-entity <ns> <page> | /cim galleryclear");
        sender.sendMessage(LOG_PREFIX + "       /cim gallerypage-item <ns> <page> "
                + "| /cim gallerypage-particle <ns> <page> "
                + "| /cim gallerypage-fluid <ns> <page> "
                + "| /cim gallerypage-sound <ns> <page>");
        sender.sendMessage(LOG_PREFIX + "       /cim gallerypage-gui <ns> <page> "
                + "| /cim gallerypage-biome <ns> <page> "
                + "| /cim gallerypage-held <ns> <page> "
                + "| /cim gallerypage-inv <ns> <page>");
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
