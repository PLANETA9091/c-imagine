package dev.cim.hud;

import dev.cim.f2v.F2vPack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Draws a scanned mod screen on the HUD, with a mouse.
 *
 * <p>A vanilla client sends no pointer position, so the look direction stands
 * in for it: each tick the change in yaw and pitch moves a virtual cursor.
 * Attack and use packets are the buttons. Esc is not visible to the server
 * when no vanilla screen is open, so sneaking closes.
 */
public final class ScreenHud implements HudService.Layer, Listener, CommandExecutor {

    private static final Pattern STATE = Pattern.compile("(\\w+)=(\\w+)");

    /** Where AbstractContainerScreen puts its labels. */
    private static final int TITLE_X = 8;
    private static final int TITLE_Y = 6;

    /** Half of a 1280x720 screen at GUI scale 3, the smallest common one. */
    private static final int POINTER_X = 210;
    private static final int POINTER_Y = 118;

    /** GUI pixels per degree of look. */
    static final double SENSITIVITY = 6.0;

    /** Virtual pointer state; pure so it tests without a server. */
    static final class Pointer {
        private final int halfW;
        private final int halfH;
        private double x;
        private double y;
        private float yaw;
        private float pitch;

        Pointer(int halfW, int halfH, float yaw, float pitch) {
            this.halfW = halfW;
            this.halfH = halfH;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        /** Test harness: put the pointer on a spot, as if the mouse had gone there. */
        void moveTo(int newX, int newY) {
            x = clamp(newX, halfW);
            y = clamp(newY, halfH);
        }

        void look(float newYaw, float newPitch) {
            float dYaw = wrap(newYaw - yaw);
            x = clamp(x + dYaw * SENSITIVITY, halfW);
            y = clamp(y + (newPitch - pitch) * SENSITIVITY, halfH);
            yaw = newYaw;
            pitch = newPitch;
        }

        int x() {
            return (int) Math.round(x);
        }

        int y() {
            return (int) Math.round(y);
        }

        private static float wrap(float degrees) {
            float d = degrees % 360f;
            return d >= 180f ? d - 360f : d < -180f ? d + 360f : d;
        }

        private static double clamp(double v, int half) {
            return Math.max(-half, Math.min(half, v));
        }
    }

    private static final class Session {
        final ScreenPack.Scene scene;
        final GuiCaptures.Capture capture;
        final Pointer pointer;
        final ItemStack[] held;
        final int originX;
        final int originY;
        ItemStack cursor;
        boolean pointerShown = true;
        /** The mod's own menu in the layer runs this screen's clicks (M6.3). */
        boolean live;
        /** The layer menu's slots, in the order the capture lists them. */
        List<LiveBridge.Slot> liveSlots = List.of();
        /** Per captured slot: whether the server's menu has a slot of that index (it draws that). */
        boolean[] liveHeld;
        /**
         * Captured slots past the end of the server's menu that a click emptied: the real client
         * predicts the pickup, the server ignores a slot it does not have and never sends that
         * slot back, so the client keeps it empty.
         */
        final java.util.Set<Integer> desynced = new java.util.HashSet<>();
        /** The layer menu's class, which picks the scanned buttons that belong to it. */
        String liveMenuClass;
        /** Live state of the menu's stateful widgets, by scene widget index: {active, visible}. */
        java.util.Map<Integer, boolean[]> liveWidgets = java.util.Map.of();
        /** The recorded state on screen, after the last press that has one. */
        List<ScreenPack.Piece> pieces;
        /** Which press state that is (its widget index), or -1 for the menu as opened. */
        int stateIndex = -1;

        Session(ScreenPack.Scene scene, GuiCaptures.Capture capture, Pointer pointer) {
            this.scene = scene;
            ScreenPack.Captured real = scene.captured();
            this.capture = real == null ? capture
                    : new GuiCaptures.Capture(null, scene.menu(), null, List.of(), real.slots());
            capture = this.capture;
            this.pointer = pointer;
            this.held = new ItemStack[capture == null ? 0 : capture.slots().size()];
            int minX = 0;
            int minY = 0;
            int maxX = scene.width();
            int maxY = scene.height();
            if (capture != null) {
                for (GuiCaptures.Slot s : capture.slots()) {
                    if (s.x() > -1000) {
                        minX = Math.min(minX, s.x() - 1);
                        minY = Math.min(minY, s.y() - 1);
                        maxX = Math.max(maxX, s.x() + 17);
                        maxY = Math.max(maxY, s.y() + 17);
                    }
                }
            }
            originX = real != null ? real.originX() : -(minX + maxX) / 2;
            originY = real != null ? real.originY() : -(minY + maxY) / 2;
        }

        /** Where a floating inventory panel goes: the first player slot is its (8, 17). */
        int[] panelAt() {
            if (capture != null) {
                for (GuiCaptures.Slot s : capture.slots()) {
                    if (s.player() && s.x() > -1000) {
                        return new int[]{s.x() - 8, s.y() - 17};
                    }
                }
            }
            return null;
        }

        /** The slot under the pointer, or -1. */
        /** Where the slots sit in the current press state, against where the menu opened. */
        int[] slotShift() {
            return stateIndex >= 0 && scene.captured() != null ? scene.captured().shift(stateIndex) : new int[]{0, 0};
        }

        int hover() {
            if (capture == null) {
                return -1;
            }
            int[] shift = slotShift();
            int x = pointer.x() - originX - shift[0];
            int y = pointer.y() - originY - shift[1];
            for (int i = 0; i < capture.slots().size(); i++) {
                GuiCaptures.Slot s = capture.slots().get(i);
                if (x >= s.x() - 1 && x < s.x() + 17 && y >= s.y() - 1 && y < s.y() + 17) {
                    return i;
                }
            }
            return -1;
        }
    }

    private final Logger log;
    /** The floating keyboard takes clicks first. */
    private java.util.function.Predicate<Player> busy = p -> false;
    private CameraLock camera;
    private org.bukkit.plugin.Plugin plugin;
    private boolean harnessFrame;
    private final List<ScreenPack.Scene> scenes;
    private Map<String, GuiCaptures.Capture> captures;
    private final Path scenesDir;
    private final ItemGlyphs glyphs;
    private final Map<Float, String> itemCarriers = new HashMap<>();
    private final Map<String, F2vPack.VisualItem> carrierRows = new HashMap<>();
    private final Map<String, String> blockCarriers = new HashMap<>();
    /** Per player: inventory slots the real client shows empty after a click its server ignored. */
    private final Map<UUID, java.util.Set<Integer>> clientEmptied = new HashMap<>();
    /** Mod block id to the note block that stands for it in Paper's world. */
    private final Map<String, F2vPack.VisualBlock> blockCarrierRows = new HashMap<>();
    private final Map<UUID, Session> open = new HashMap<>();

    private final Path modsDir;

    /**
     * F2V's scan of every mod screen's buttons and stateful widgets: a full jar scan of every
     * mod, which takes tens of seconds, so it runs once in the background from boot. The
     * server thread never waits for it (a scan on it once froze the server into its watchdog).
     */
    private final java.util.concurrent.CompletableFuture<Object[]> liveScan;

    public ScreenHud(Logger log, Path modsDir) {
        this.log = log;
        this.modsDir = modsDir;
        this.liveScan = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            // The layer loads its mods on its own thread: wait for cimlive, but not forever.
            for (int i = 0; i < 600 && !LiveBridge.available(); i++) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!LiveBridge.available()) {
                return new Object[]{List.of(), List.of()};
            }
            try {
                List<LiveButtons.Button> b = LiveButtons.scan(modsDir);
                List<LiveButtons.Stateful> w = LiveButtons.stateful(modsDir);
                log.info("[cim-screen] live scan: " + b.size() + " proven payload buttons, " + w.size()
                        + " stateful widgets");
                return new Object[]{b, w};
            } catch (Exception e) {
                log.warning("[cim-screen] live scan failed: " + e);
                return new Object[]{List.of(), List.of()};
            }
        });
        List<ScreenPack.Scene> found = List.of();
        Map<String, GuiCaptures.Capture> known = Map.of();
        ItemGlyphs icons = null;
        Path root = modsDir.resolveSibling("c-imagine");
        scenesDir = root.resolve("scenes");
        try {
            found = ScreenPack.scenes(modsDir);
            known = GuiCaptures.byId(GuiCaptures.read(root.resolve("scenes")));
            icons = new ItemGlyphs(ScreenPack.iconsForRuntime(modsDir));
            readCarriers(root.resolve("f2v"));
        } catch (IOException e) {
            log.warning("[cim-screen] scan failed: " + e);
        }
        scenes = found;
        captures = known;
        glyphs = icons;
        log.info("[cim-screen] " + scenes.size() + " screen(s) scanned, " + captures.size() + " captured");
    }

    /** The walk's files can land after boot, as they do in the gallery. */
    private void refreshCaptures() {
        try {
            captures = GuiCaptures.byId(GuiCaptures.read(scenesDir));
        } catch (IOException e) {
            log.warning("[cim-screen] capture read failed: " + e);
        }
    }

    private void readCarriers(Path f2v) throws IOException {
        Path items = f2v.resolve("visual-items.resolved.tsv");
        if (Files.isRegularFile(items)) {
            for (F2vPack.VisualItem row : F2vPack.readManifest(items)) {
                itemCarriers.put(row.cmd(), row.moddedId());
                carrierRows.put(row.moddedId(), row);
            }
        }
        Path blocks = f2v.resolve("visual-blocks.tsv");
        if (Files.isRegularFile(blocks)) {
            for (F2vPack.VisualBlock row : F2vPack.readBlockManifest(blocks)) {
                blockCarriers.put(row.instrument() + ":" + row.note(), row.moddedBlockId());
                blockCarrierRows.put(row.moddedBlockId(), row);
            }
        }
    }

    private LiveBridge live;

    private LiveBridge live() {
        if (live == null && plugin != null && LiveBridge.available()) {
            live = new LiveBridge(plugin, this::carrier, this::itemId);
            startLiveTick();
            log.info("[cim-screen] live menus: the layer's cimlive answers, mod menus run their own code");
        }
        return live;
    }

    private boolean ticking;
    private long liveTicks;

    /** Paper's tick drives the open live menus' block entities; refreshes the screens that moved. */
    private void startLiveTick() {
        if (ticking || plugin == null) {
            return;
        }
        ticking = true;
        org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (live == null) {
                return;
            }
            boolean any = false;
            for (Session s : open.values()) {
                any |= s.live;
            }
            if (!any) {
                return;
            }
            LiveBridge.tick();
            // Every 4 ticks the screens show what the ticks did (a machine's slots, its output).
            if (++liveTicks % 4 == 0) {
                for (Map.Entry<UUID, Session> e : new ArrayList<>(open.entrySet())) {
                    Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
                    if (p != null && e.getValue().live) {
                        LiveBridge.State state = live.state(p);
                        if (state.error() == null && state.open()) {
                            applyLive(p, e.getValue(), state);
                        }
                    }
                }
            }
        }, 1L, 1L);
    }

    public void useCamera(CameraLock lock, org.bukkit.plugin.Plugin owner) {
        camera = lock;
        plugin = owner;
    }

    public void busyWhen(java.util.function.Predicate<Player> test) {
        busy = test;
    }

    /** Every scene and the first on-screen slot, one per line, for the sweep test. */
    public List<String> listForHarness() {
        List<String> out = new ArrayList<>();
        for (ScreenPack.Scene scene : scenes) {
            GuiCaptures.Capture c = scene.captured() != null
                    ? new GuiCaptures.Capture(null, null, null, List.of(), scene.captured().slots()) : captureOf(scene);
            int first = -1;
            if (c != null) {
                for (int i = 0; i < c.slots().size() && first < 0; i++) {
                    if (c.slots().get(i).x() > -1000) {
                        first = i;
                    }
                }
            }
            out.add(scene.className() + "\t" + first + "\t" + scene.widgets().size());
        }
        return out;
    }

    /** Opens a scene by its name, for the sweep test. */
    public boolean openNamed(Player player, String name) {
        ScreenPack.Scene scene = scenes.stream().filter(s -> s.className().equals(name)).findFirst().orElse(null);
        if (scene != null) {
            open(player, scene);
        }
        return scene != null;
    }

    /** The real stack that shows as a modded item, or air when there is none. */
    ItemStack carrier(String moddedId) {
        F2vPack.VisualItem row = moddedId == null ? null : carrierRows.get(moddedId);
        Material host = row == null ? null : Material.matchMaterial(row.hostItem());
        if (host == null) {
            return new ItemStack(Material.AIR);
        }
        ItemStack stack = new ItemStack(host);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setFloats(List.of(row.cmd()));
        meta.setCustomModelDataComponent(cmd);
        stack.setItemMeta(meta);
        return stack;
    }

    /** The screen for a menu: its own capture first, then a scan of its class. */
    private ScreenPack.Scene sceneFor(String menuId, String menuClass) {
        ScreenPack.Scene own = scenes.stream().filter(s -> s.className().equals(menuId)).findFirst().orElse(null);
        if (own != null || menuClass == null) {
            return own;
        }
        String wanted = menuClass.replace('.', '/');
        return scenes.stream().filter(s -> wanted.equals(s.menu())).findFirst().orElse(null);
    }

    private GuiCaptures.Capture captureOf(ScreenPack.Scene scene) {
        GuiCaptures.Capture own = captures.get(scene.className());
        if (own != null || scene.menu() == null) {
            return own;
        }
        return captures.values().stream().filter(c -> scene.menu().equals(c.menuClass())).findFirst().orElse(null);
    }

    public void open(Player player, ScreenPack.Scene scene) {
        log.info("[cim-screen] " + player.getName() + " opened " + scene.shortName());
        GuiCaptures.Capture capture = captureOf(scene);
        open.put(player.getUniqueId(), new Session(scene, capture,
                new Pointer(POINTER_X, POINTER_Y, player.getYaw(), player.getPitch())));
        if (camera != null && !harnessFrame) {
            // Not during the click that opened it: a mount from inside that event is lost.
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                if (open.containsKey(player.getUniqueId())) {
                    camera.engage(player, "screen", () -> close(player));
                }
            });
        }
    }

    /**
     * Test harness: turns the player's look so the pointer lands on a target, the
     * way moving the mouse does. {@code s<n>} is a slot, {@code w<n>} a widget,
     * {@code x,y} a point in the screen. Returns what it aimed at.
     */
    public String aim(Player player, String target) {
        Session s = open.get(player.getUniqueId());
        if (s == null) {
            return "no screen open";
        }
        int x;
        int y;
        if (target.startsWith("s")) {
            GuiCaptures.Slot slot = s.capture.slots().get(Integer.parseInt(target.substring(1)));
            x = slot.x() + 8;
            y = slot.y() + 8;
        } else if (target.startsWith("w")) {
            ScreenPack.Widget w = s.scene.widgets().get(Integer.parseInt(target.substring(1)));
            x = w.x() + w.w() / 2;
            y = w.y() + w.h() / 2;
        } else {
            String[] p = target.split(",");
            x = Integer.parseInt(p[0]);
            y = Integer.parseInt(p[1]);
        }
        s.pointer.moveTo(x + s.originX, y + s.originY);
        return "aimed " + target + " at " + x + "," + y + " widgets=" + s.scene.widgets().size();
    }

    /** Harness: presses widget {@code i} the way a click on it does. */
    public String press(Player player, int i) {
        Session s = open.get(player.getUniqueId());
        if (s == null) {
            return "no widget " + i;
        }
        // The real client's press number, not a list position: a press that left the screen
        // recorded no widget, so the list is shorter (measured: AE2's terminals and pattern
        // terminal showed the next press's state).
        ScreenPack.Widget w = null;
        for (ScreenPack.Widget each : s.scene.widgets()) {
            if (each.label() != null && (each.label().equals("press " + i) || each.label().startsWith("press " + i + " sent="))) {
                w = each;
                break;
            }
        }
        if (w == null) {
            return "no widget " + i;
        }
        s.pointer.moveTo(s.originX + w.x() + w.w() / 2, s.originY + w.y() + w.h() / 2);
        click(player, "left");
        return "pressed " + w.label();
    }

    /** Harness: the slot move the real client made, done the way two clicks do it. */
    public String move(Player player) {
        Session s = open.get(player.getUniqueId());
        int[] m = s == null || s.scene.captured() == null ? null : s.scene.captured().move();
        if (m == null) {
            return "no move";
        }
        // The real client counted the slots on its screen, which the capture's own list is;
        // the manifest's list is the server's, hidden slots included (Mekanism's QIO window).
        List<GuiCaptures.Slot> shown = s.scene.captured().slots().isEmpty()
                ? s.capture.slots() : s.scene.captured().slots();
        for (int slot : m) {
            if (slot < 0 || slot >= shown.size()) {
                return "no slot " + slot;
            }
            GuiCaptures.Slot at = shown.get(slot);
            s.pointer.moveTo(s.originX + at.x() + 8, s.originY + at.y() + 8);
            click(player, "left");
        }
        // The screen as the move left it (a recording of its look; the items stay live).
        List<ScreenPack.Piece> moved = s.scene.captured().states().get(ScreenPack.MOVE_STATE);
        if (moved != null) {
            s.pieces = moved;
            s.stateIndex = ScreenPack.MOVE_STATE;
        }
        return "moved " + m[0] + " to " + m[1];
    }

    /**
     * The gallery's ours leg through the mod's own code: the opener the walk proved (a held
     * item, a block) is used for real in the layer, and the screen is drawn from the menu it
     * opened. False when the layer is absent, the opener is of another kind, or the mod's code
     * opened a different menu; the caller then shows the recorded screen.
     */
    public boolean openMenuLive(Player player, String menuId, String menuClass, String heldId, String opener) {
        LiveBridge bridge = live();
        if (bridge == null || opener == null) {
            return false;
        }
        refreshCaptures();
        close(player);
        LiveBridge.State state;
        boolean sneak = opener.startsWith("sneak+");
        if (sneak) {
            opener = opener.substring("sneak+".length());
        }
        if (opener.startsWith("item:")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if ((hand == null || hand.getType().isAir()) && heldId != null) {
                player.getInventory().setItemInMainHand(bridge.stackOf(heldId, 1));
            }
            state = bridge.openHeld(player, sneak);
        } else if (opener.startsWith("block:")) {
            state = bridge.openBlock(player, player.getLocation().getBlock().getRelative(2, 0, 0),
                    opener.substring("block:".length()), true);
        } else if (opener.startsWith("place+item:") || opener.startsWith("use+item:")
                || opener.startsWith("settle+use+item:")) {
            state = bridge.openUse(player, player.getLocation().getBlock().getRelative(2, 0, 0), opener);
        } else {
            return false;
        }
        if (state.error() != null || !state.open() || !menuId.equals(state.menuType())) {
            log.info("[cim-screen] live harness open of " + menuId + " by " + opener + " -> "
                    + (state.error() != null ? state.error() : state.menuType()) + "; recorded screen instead");
            if (state.error() == null) {
                bridge.keepBlockEntity(player, bridge.close(player));
            }
            return false;
        }
        ScreenPack.Scene scene = sceneFor(menuId, menuClass);
        if (scene == null) {
            bridge.keepBlockEntity(player, bridge.close(player));
            return false;
        }
        harnessFrame = true;
        open(player, scene);
        harnessFrame = false;
        Session s = open.get(player.getUniqueId());
        s.pointerShown = false;
        s.live = true;
        applyLive(player, s, state);
        log.info("[cim-screen] live harness opened " + menuId + " by " + opener + " (" + state.slots().size() + " slots)"
                + pairing(s));
        return true;
    }

    /** Opens the screen of one menu; the gallery's ours leg uses this. */
    public boolean openMenu(Player player, String menuId, String menuClass) {
        refreshCaptures();
        // A menu with no scene must show nothing, not the previous page's screen.
        close(player);
        ScreenPack.Scene scene = sceneFor(menuId, menuClass);
        if (scene != null) {
            // A gallery frame is compared with a real screen, which keeps the hotbar.
            harnessFrame = true;
            open(player, scene);
            harnessFrame = false;
            // The real screen has no pointer in the frame either.
            open.get(player.getUniqueId()).pointerShown = false;
        }
        return scene != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandSender target = sender instanceof ProxiedCommandSender proxy ? proxy.getCallee() : sender;
        ScreenPack.Scene scene = args.length >= 1 ? scenes.stream()
                .filter(s -> s.shortName().equalsIgnoreCase(args[0])).findFirst().orElse(null) : null;
        if (!(target instanceof Player player) || scene == null) {
            sender.sendMessage("Usage: /modscreen <" + String.join("|",
                    scenes.stream().map(ScreenPack.Scene::shortName).limit(12).toList()) + "...>");
            return true;
        }
        open(player, scene);
        open.get(player.getUniqueId()).pointerShown = !(args.length > 1);
        return true;
    }

    private String itemId(ItemStack stack) {
        if (stack.hasItemMeta() && stack.getItemMeta().hasCustomModelDataComponent()) {
            List<Float> floats = stack.getItemMeta().getCustomModelDataComponent().getFloats();
            String modded = floats.isEmpty() ? null : itemCarriers.get(floats.get(0));
            if (modded != null) {
                return modded;
            }
        }
        return stack.getType().getKey().toString();
    }

    private void drawItem(HudFrame frame, int x, int y, ItemStack stack) {
        if (stack == null || stack.getType().isAir() || glyphs == null) {
            return;
        }
        String id = itemId(stack);
        String glyph = glyphs.glyph(id);
        if (glyph != null) {
            frame.free(x, y, glyph, glyphs.advance(id));
        }
        if (stack.getAmount() > 1) {
            String n = String.valueOf(stack.getAmount());
            frame.freeShadowed(x + 17 - HudText.width(n), y + 9, n);
        }
    }

    /** Player slots run main inventory then hotbar, as vanilla menus add them. */
    private static int playerIndex(int nth) {
        return nth < 27 ? nth + 9 : nth - 27;
    }

    /** The real inventory index when the capture has it: mods add player slots in any order. */
    private static int playerSlot(Session s, int index) {
        int recorded = s.capture.slots().get(index).index();
        return recorded >= 0 ? recorded : playerIndex(nthPlayer(s, index));
    }

    private ItemStack slotItem(Player player, Session s, int index) {
        if (s.live && s.liveHeld != null && index < s.liveHeld.length) {
            if (s.liveHeld[index]) {
                return s.held[index];
            }
            if (s.desynced.contains(index)) {
                return null;
            }
            GuiCaptures.Slot c = s.capture.slots().get(index);
            if (c.player() && clientEmptied.getOrDefault(player.getUniqueId(), java.util.Set.of())
                    .contains(playerSlot(s, index))) {
                return null;
            }
        }
        return s.capture.slots().get(index).player()
                ? player.getInventory().getItem(playerSlot(s, index)) : s.held[index];
    }

    private static int nthPlayer(Session s, int index) {
        int n = 0;
        for (int i = 0; i < index; i++) {
            n += s.capture.slots().get(i).player() ? 1 : 0;
        }
        return n;
    }

    @Override
    public void draw(Player player, HudFrame frame) {
        Session s = open.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.pointer.look(player.getYaw(), player.getPitch());
        frame.free(-HudPack.BACKDROP_W / 2, -HudPack.BACKDROP_H / 2, String.valueOf(HudPack.BACKDROP),
                HudPack.BACKDROP_W + 1);
        boolean panel = false;
        for (ScreenPack.Piece piece : s.pieces != null ? s.pieces : s.scene.pieces()) {
            int[] at = s.panelAt();
            if (piece.floating() && at == null) {
                continue;
            }
            panel |= piece.floating();
            int x = s.originX + piece.x() + (piece.floating() ? at[0] : 0);
            int y = s.originY + piece.y() + (piece.floating() ? at[1] : 0);
            frame.free(x, y, String.valueOf(piece.glyph()), piece.w() + 1);
        }
        if (s.capture != null) {
            if (s.capture.title() != null) {
                // A centring screen uses Create's convention: on (width - 8) / 2, near the top.
                int w = HudText.width(s.capture.title());
                boolean centered = s.scene.titleCentered();
                frame.freeLabel(s.originX + (centered ? (s.scene.width() - 8) / 2 - w / 2 : TITLE_X),
                        s.originY + (centered ? TITLE_Y - 2 : TITLE_Y), s.capture.title());
            }
            int[] at = s.scene.captured() != null ? null : s.panelAt();
            if (at != null) {
                frame.freeLabel(s.originX + at[0] + TITLE_X, s.originY + at[1] + TITLE_Y, "Inventory");
            }
            // The gallery's real mouse is parked off the menu, so nothing is hovered there.
            int hover = s.pointerShown ? s.hover() : -1;
            int[] shift = s.slotShift();
            for (int i = 0; i < s.capture.slots().size(); i++) {
                GuiCaptures.Slot slot = s.capture.slots().get(i);
                int x = s.originX + slot.x() + shift[0];
                int y = s.originY + slot.y() + shift[1];
                // A slot the real screen never draws shows nothing here either.
                boolean drawn = s.scene.captured() == null || !s.scene.captured().hidden().contains(slot.menuIndex());
                if (slot.x() > -1000 && drawn) {
                    if (slot.player() && !panel && s.scene.captured() == null) {
                        frame.free(x - 1, y - 1, String.valueOf(HudPack.SLOT), 19);
                    }
                    ItemStack item = slotItem(player, s, i);
                    if (item != null && !item.getType().isAir() && s.scene.captured() != null) {
                        // The slot's own background when it holds something (no placeholder icon).
                        List<ScreenPack.Piece> fill = s.scene.captured().fill(s.stateIndex, slot.menuIndex());
                        if (fill == null && s.stateIndex >= 0) {
                            fill = s.scene.captured().fill(-1, slot.menuIndex());
                        }
                        if (fill != null) {
                            for (ScreenPack.Piece piece : fill) {
                                frame.free(s.originX + piece.x(), s.originY + piece.y(), String.valueOf(piece.glyph()),
                                        piece.w() + 1);
                            }
                        }
                    }
                    drawItem(frame, x, y, item);
                    if (i == hover) {
                        frame.free(x, y, String.valueOf(HudPack.SLOT_HOVER), 17);
                    }
                }
            }
        }
        // What a press state (a window) draws over the slots goes over their items, as it does
        // on the real screen.
        if (s.stateIndex >= 0 && s.scene.captured() != null) {
            for (ScreenPack.Piece piece : s.scene.captured().covers().getOrDefault(s.stateIndex, List.of())) {
                frame.free(s.originX + piece.x(), s.originY + piece.y(), String.valueOf(piece.glyph()), piece.w() + 1);
            }
        }
        if (s.pointerShown) {
            // The carried stack is part of the cursor: where vanilla draws it, at the mouse.
            drawItem(frame, s.pointer.x() - 8, s.pointer.y() - 8, s.cursor);
            frame.free(s.pointer.x(), s.pointer.y(), String.valueOf(HudPack.CURSOR), 9);
        }
    }

    private void click(Player player, String button) {
        Session s = open.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        int px = s.pointer.x() - s.originX;
        int py = s.pointer.y() - s.originY;
        for (int wi = 0; wi < s.scene.widgets().size(); wi++) {
            ScreenPack.Widget w = s.scene.widgets().get(wi);
            boolean[] liveState = s.liveWidgets.get(wi);
            if (liveState != null && (!liveState[0] || !liveState[1])) {
                // The real screen ignores a click on an inactive or hidden button, so does this one.
                continue;
            }
            if (px >= w.x() && px < w.x() + w.w() && py >= w.y() && py < w.y() + w.h()) {
                // A recording of the screen the real mod showed after this press. The press
                // itself only reaches the mod once the layer hosts players.
                if (s.scene.captured() != null && s.scene.captured().states().containsKey(wi)) {
                    s.pieces = s.scene.captured().states().get(wi);
                    s.stateIndex = wi;
                }
                int vanillaAt = w.label() == null ? -1 : w.label().indexOf(" sent=vanilla:");
                if (s.live && live != null && vanillaAt >= 0) {
                    // A vanilla menu packet (a menu button id, a merchant offer): what the real
                    // client's press of this control sends, through the server's own handler.
                    String[] kind = w.label().substring(vanillaAt + " sent=vanilla:".length()).split("#", 2);
                    LiveBridge.State state = live.vanilla(player, kind[0], Integer.parseInt(kind[1].trim()));
                    if (state.error() != null) {
                        log.warning("[cim-screen] live vanilla press failed: " + state.error());
                    } else {
                        applyLive(player, s, state);
                        log.info("[cim-screen] live " + player.getName() + " pressed '" + w.label() + "': " + state.pressed());
                    }
                } else if (s.live && live != null) {
                    LiveButtons.Button b = buttonFor(s.liveMenuClass, wi, w.label());
                    if (b != null) {
                        LiveBridge.State state = live.press(player, b);
                        if (state.error() != null) {
                            log.warning("[cim-screen] live button failed: " + state.error());
                        } else {
                            applyLive(player, s, state);
                            log.info("[cim-screen] live " + player.getName() + " pressed '" + w.label() + "': "
                                    + state.pressed());
                        }
                    }
                }
                org.bukkit.Bukkit.getPluginManager().callEvent(new ScreenWidgetEvent(player, s.scene.className(), w.label()));
                log.info("[cim-screen] " + player.getName() + " " + button + " widget '" + w.label()
                        + "' in " + s.scene.shortName());
                return;
            }
        }
        int index = s.hover();
        log.info("[cim-screen] " + player.getName() + " " + button + " click slot=" + index + " at "
                + (s.pointer.x() - s.originX) + "," + (s.pointer.y() - s.originY) + " in " + s.scene.shortName());
        if (index < 0) {
            return;
        }
        LiveBridge.Slot target = s.live && live != null ? liveSlotFor(s, index) : null;
        if (target == null && s.live && live != null && s.capture.slots().get(index).menuIndex() >= 0) {
            // A slot the client has and the server's menu does not (a block-placed backpack's
            // storage on a server that gave it none): the click reaches nothing on the server.
            s.desynced.add(index);
            if (s.capture.slots().get(index).player()) {
                // The client's own inventory slot stays empty until the server sends it again,
                // which a menu without that slot never does (also after the page is reopened).
                clientEmptied.computeIfAbsent(player.getUniqueId(), k -> new java.util.HashSet<>())
                        .add(playerSlot(s, index));
            }
            return;
        }
        if (target != null) {
            LiveBridge.State state = live.click(player, target.menuIndex(),
                    button.equals("right") ? 1 : 0, "PICKUP");
            if (state.error() != null) {
                log.warning("[cim-screen] live click failed: " + state.error());
                return;
            }
            applyLive(player, s, state);
            return;
        }
        // Left swaps whole stacks, as a vanilla slot does. Nothing here is the mod's menu.
        ItemStack carried = s.cursor;
        if (s.capture.slots().get(index).player()) {
            int slot = playerSlot(s, index);
            ItemStack there = player.getInventory().getItem(slot);
            s.cursor = there == null || there.getType().isAir() ? null : there;
            player.getInventory().setItem(slot, carried);
        } else {
            s.cursor = s.held[index];
            s.held[index] = carried;
        }
    }

    /**
     * End-to-end check of live menus on Paper (integration): the item is put in the player's
     * hand and 5 diamonds in the inventory, the held item's own use() runs through the bridge,
     * two clicks move the diamonds onto the menu's first own slot, and the close's inventory is
     * applied to the Paper player. Every step is logged with what Paper ends up holding.
     */
    public List<String> liveSelfTest(Player player, String itemId) {
        List<String> out = new ArrayList<>();
        LiveBridge bridge = live();
        if (bridge == null) {
            out.add("no layer: live menus unavailable");
            return out;
        }
        player.getInventory().clear();
        ItemStack held = bridge.stackOf(itemId, 1);
        player.getInventory().setItem(player.getInventory().getHeldItemSlot(), held);
        player.getInventory().setItem(9, new ItemStack(org.bukkit.Material.DIAMOND, 5));
        LiveBridge.State state = bridge.openHeld(player);
        out.add("open " + itemId + " held " + paperName(held) + " -> " + (state.error() != null ? state.error()
                : state.open() ? state.menuType() + " slots=" + state.slots().size() : "no menu"));
        if (!state.open()) {
            return out;
        }
        int from = -1;
        int to = -1;
        for (LiveBridge.Slot slot : state.slots()) {
            if (slot.player() && slot.containerSlot() == 9 && from < 0) {
                from = slot.menuIndex();
            }
            if (!slot.player() && to < 0) {
                to = slot.menuIndex();
            }
        }
        if (from >= 0 && to >= 0) {
            bridge.click(player, from, 0, "PICKUP");
            state = bridge.click(player, to, 0, "PICKUP");
            String own = state.slots().stream().filter(sl -> !sl.player()).findFirst()
                    .map(sl -> paperName(bridge.toPaper(sl.stack()))).orElse("none");
            out.add("move: first own slot " + own + ", carried " + paperName(bridge.toPaper(state.carried())));
        }
        String pressLabel = System.getenv("CIM_LIVETEST_BUTTON");
        if (pressLabel != null && !pressLabel.isBlank()) {
            LiveButtons.Button b = buttonFor(state.menuClass(), -1, pressLabel.trim());
            if (b == null) {
                out.add("button '" + pressLabel + "': no scanned button of " + state.menuClass());
            } else {
                LiveBridge.State pressed = bridge.press(player, b);
                out.add("button '" + pressLabel + "': " + (pressed.error() != null ? pressed.error() : pressed.pressed()));
            }
        }
        state = bridge.close(player);
        bridge.applyInventory(player, state);
        StringBuilder inv = new StringBuilder();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && !contents[i].getType().isAir()) {
                inv.append(i).append('=').append(paperName(contents[i])).append(' ');
            }
        }
        ItemStack after = player.getInventory().getItem(player.getInventory().getHeldItemSlot());
        String data = after == null || !after.hasItemMeta() ? null : after.getItemMeta().getPersistentDataContainer()
                .get(new org.bukkit.NamespacedKey(plugin, "live_stack"), org.bukkit.persistence.PersistentDataType.STRING);
        out.add("held data: " + (data == null ? "none" : data));
        out.add("close: paper inventory " + inv.toString().trim()
                + (state.dropped().isEmpty() ? "" : ", dropped " + state.dropped().size()));
        return out;
    }

    /**
     * Block menus on Paper end to end: the mod block's own use opens its menu on a block
     * entity, 5 diamonds go into its first own slot, the close stores the block entity's data
     * in the Paper chunk, and a second opening must show the diamonds still there.
     */
    public List<String> liveBlockTest(Player player, String blockId) {
        List<String> out = new ArrayList<>();
        LiveBridge bridge = live();
        if (bridge == null) {
            out.add("no layer: live menus unavailable");
            return out;
        }
        org.bukkit.block.Block block = player.getLocation().getBlock().getRelative(2, 0, 0);
        player.getInventory().clear();
        player.getInventory().setItem(9, new ItemStack(org.bukkit.Material.DIAMOND, 5));
        LiveBridge.State state = bridge.openBlock(player, block, blockId);
        out.add("block open " + blockId + " -> " + (state.error() != null ? state.error()
                : state.open() ? state.menuType() + " slots=" + state.slots().size() : "no menu"));
        if (!state.open()) {
            bridge.keepBlockEntity(player, bridge.close(player));
            return out;
        }
        int from = -1;
        int to = -1;
        for (LiveBridge.Slot slot : state.slots()) {
            if (slot.player() && slot.containerSlot() == 9 && from < 0) {
                from = slot.menuIndex();
            }
            if (!slot.player() && to < 0) {
                to = slot.menuIndex();
            }
        }
        if (from >= 0 && to >= 0) {
            bridge.click(player, from, 0, "PICKUP");
            state = bridge.click(player, to, 0, "PICKUP");
            out.add("block move: first own slot " + firstOwn(bridge, state) + ", carried "
                    + paperName(bridge.toPaper(state.carried())));
        }
        state = bridge.close(player);
        bridge.applyInventory(player, state);
        bridge.keepBlockEntity(player, state);
        out.add("block close: kept " + (state.blockEntity() == null ? "nothing" : state.blockEntity().length() + " chars"));
        LiveBridge.State again = bridge.openBlock(player, block, blockId);
        out.add("block reopen: first own slot " + firstOwn(bridge, again));
        bridge.keepBlockEntity(player, bridge.close(player));
        return out;
    }

    /**
     * Integration: a mod item set down in Paper's world by its own code (a sneaking click on
     * the block below), then opened as the block it became, with the data it was placed with.
     */
    public List<String> livePlaceTest(Player player, String itemId) {
        List<String> out = new ArrayList<>();
        LiveBridge bridge = live();
        if (bridge == null) {
            out.add("no layer: live menus unavailable");
            return out;
        }
        org.bukkit.block.Block ground = player.getLocation().getBlock().getRelative(3, -1, 0);
        ground.setType(Material.STONE, false);
        ground.getRelative(org.bukkit.block.BlockFace.UP).setType(Material.AIR, false);
        player.getInventory().clear();
        player.getInventory().setItemInMainHand(bridge.stackOf(itemId, 1));
        LiveBridge.UseOn result = bridge.useOn(player, ground, org.bukkit.block.BlockFace.UP, null, true);
        out.add("place " + itemId + " -> " + (result.state().error() != null ? result.state().error()
                : result.placedId() == null ? "nothing placed" : "placed " + result.placedId() + " with "
                + (result.placedBlockEntity() == null ? "no" : "its") + " block entity data"));
        if (result.placedId() == null) {
            return out;
        }
        org.bukkit.block.Block at = ground.getWorld().getBlockAt(result.x(), result.y(), result.z());
        bridge.applyInventory(player, result.state());
        bridge.keepPlaced(at, result.placedId(), result.placedBlockEntity());
        out.add("place held after: " + paperName(player.getInventory().getItemInMainHand()));
        player.getInventory().setItem(9, new ItemStack(org.bukkit.Material.DIAMOND, 5));
        LiveBridge.State state = bridge.openBlock(player, at, result.placedId());
        long own = state.slots().stream().filter(sl -> !sl.player()).count();
        out.add("placed block open -> " + (state.error() != null ? state.error()
                : state.open() ? state.menuType() + " own=" + own : "no menu"));
        // The diamonds go into the backpack's storage: they must survive the break.
        int from = -1;
        int to = -1;
        for (LiveBridge.Slot slot : state.slots()) {
            if (slot.player() && slot.containerSlot() == 9 && from < 0) {
                from = slot.menuIndex();
            }
            if (!slot.player() && to < 0) {
                to = slot.menuIndex();
            }
        }
        if (from >= 0 && to >= 0) {
            bridge.click(player, from, 0, "PICKUP");
            state = bridge.click(player, to, 0, "PICKUP");
            out.add("placed block move: first own slot " + firstOwn(bridge, state));
        }
        state = bridge.close(player);
        bridge.applyInventory(player, state);
        bridge.keepBlockEntity(player, state);
        // Broken by hand, by the block's own code: it drops the backpack, with its contents.
        player.getInventory().setItemInMainHand(null);
        LiveBridge.Broken broken = bridge.breakBlock(player, at, result.placedId());
        out.add("placed block broken -> " + (broken.state().error() != null ? broken.state().error()
                : "removed=" + broken.removed() + " drops " + broken.spilled().stream()
                .map(st -> bridge.toLayer(st).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1") + " x" + st.getAmount()).toList()));
        if (!broken.spilled().isEmpty()) {
            // The dropped backpack, opened from the hand: its storage still holds the diamonds.
            player.getInventory().setItemInMainHand(broken.spilled().get(0));
            LiveBridge.State held = bridge.openHeld(player);
            out.add("dropped item open -> " + (held.error() != null ? held.error()
                    : held.open() ? held.menuType() + " first own slot " + firstOwn(bridge, held) : "no menu"));
            bridge.applyInventory(player, bridge.close(player));
        }
        return out;
    }

    private String firstOwn(LiveBridge bridge, LiveBridge.State state) {
        return state.slots().stream().filter(sl -> !sl.player()).findFirst()
                .map(sl -> paperName(bridge.toPaper(sl.stack()))).orElse("none");
    }

    private String paperName(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "empty";
        }
        return itemId(stack) + " x" + stack.getAmount();
    }

    /**
     * Shows what the mod's own code opened, on the screen drawn for that menu type. False when
     * it opened nothing (the click is then the player's to keep) or there is no screen for it.
     */
    private boolean openLive(Player player, LiveBridge.State state, String opener) {
        if (state.error() != null) {
            log.warning("[cim-screen] live open failed for " + player.getName() + ": " + state.error());
            return false;
        }
        if (!state.open()) {
            live.keepBlockEntity(player, live.close(player));
            return false;
        }
        ScreenPack.Scene scene = sceneFor(state.menuType(), null);
        if (scene == null && opener != null) {
            scene = sceneForOpener(opener);
        }
        if (scene == null) {
            live.keepBlockEntity(player, live.close(player));
            log.warning("[cim-screen] live menu " + state.menuType() + " has no screen to draw");
            return false;
        }
        open(player, scene);
        Session s = open.get(player.getUniqueId());
        s.live = true;
        applyLive(player, s, state);
        log.info("[cim-screen] live " + player.getName() + " opened " + state.menuType()
                + " (" + state.slots().size() + " slots) through the mod's own code");
        return true;
    }

    private List<LiveButtons.Stateful> statefulWidgets;

    /**
     * The open menu's stateful widgets, evaluated by the layer on the menu's real objects, and
     * keyed by the scene's widget index (matched by label, else by place in construction order).
     */
    private java.util.Map<Integer, boolean[]> widgetStates(Player player, Session s) {
        if (s.liveMenuClass == null || s.scene == null) {
            return java.util.Map.of();
        }
        if (statefulWidgets == null) {
            Object[] done = liveScan.getNow(null);
            if (done == null) {
                return java.util.Map.of();   // still scanning: the widgets keep their drawn look
            }
            @SuppressWarnings("unchecked")
            List<LiveButtons.Stateful> w = (List<LiveButtons.Stateful>) done[1];
            statefulWidgets = w;
        }
        String menu = s.liveMenuClass.replace('.', '/');
        List<LiveButtons.Stateful> mine = new ArrayList<>();
        for (LiveButtons.Stateful w : statefulWidgets) {
            // A constant says nothing about the live look (measured: Mekanism's side tabs
            // carry active=false and the real screen shows them on); only real conditions count.
            if (w.menuClass().equals(menu) && w.label() != null && (live(w.active()) || live(w.visible()))) {
                mine.add(w);
            }
        }
        if (mine.isEmpty()) {
            return java.util.Map.of();
        }
        mine.sort(java.util.Comparator.comparingInt(LiveButtons.Stateful::ordinal));
        java.util.Map<Integer, boolean[]> byOrdinal = live.widgets(player, mine);
        java.util.Map<Integer, boolean[]> byWidget = new java.util.HashMap<>();
        List<ScreenPack.Widget> shown = s.scene.widgets();
        for (int k = 0; k < mine.size(); k++) {
            LiveButtons.Stateful w = mine.get(k);
            int index = -1;
            for (int wi = 0; wi < shown.size() && index < 0; wi++) {
                if (w.label() != null && w.label().equalsIgnoreCase(shown.get(wi).label())) {
                    index = wi;
                }
            }
            boolean[] state = byOrdinal.get(w.ordinal());
            if (index >= 0 && state != null) {
                byWidget.put(index, state);
            }
        }
        return byWidget;
    }

    /** Whether the background button and widget scan has finished. */
    public boolean liveScanDone() {
        return liveScan.isDone();
    }

    private static boolean live(String expr) {
        return expr != null && !expr.isEmpty() && !expr.equals("true") && !expr.equals("false") && !expr.equals("null");
    }

    private List<LiveButtons.Button> buttons;

    /** F2V's proof of every mod screen's payload buttons, scanned once on first use. */
    private List<LiveButtons.Button> buttons() {
        if (buttons == null) {
            Object[] done = liveScan.getNow(null);
            if (done == null) {
                return List.of();   // still scanning: no button reaches a mod yet
            }
            @SuppressWarnings("unchecked")
            List<LiveButtons.Button> b = (List<LiveButtons.Button>) done[0];
            buttons = b;
        }
        return buttons;
    }

    /**
     * The scanned button behind a widget of the open live menu: the one with the widget's
     * label, else the widget's place among the menu's buttons in construction order.
     */
    LiveButtons.Button buttonFor(String menuClass, int widgetIndex, String label) {
        if (menuClass == null) {
            return null;
        }
        String menu = menuClass.replace('.', '/');
        List<LiveButtons.Button> mine = new ArrayList<>();
        for (LiveButtons.Button b : buttons()) {
            if (b.menuClass().equals(menu)) {
                mine.add(b);
            }
        }
        // A crawl press names what the real client sent: the button that sends the same payload
        // (and the same enum constant, when the payload carries one).
        int at = label == null ? -1 : label.indexOf(" sent=");
        if (at >= 0) {
            String[] sent = label.substring(at + " sent=".length()).split("#", 2);
            String payload = sent[0].replace('.', '/');
            for (LiveButtons.Button b : mine) {
                if (!b.payload().equals(payload)) {
                    continue;
                }
                if (sent.length == 1 || b.args().stream().anyMatch(arg -> arg.endsWith("." + sent[1]))) {
                    return b;
                }
            }
            return null;
        }
        if (label != null) {
            for (LiveButtons.Button b : mine) {
                if (label.equalsIgnoreCase(b.label())) {
                    return b;
                }
            }
        }
        mine.sort(java.util.Comparator.comparingInt(LiveButtons.Button::ordinal));
        return widgetIndex >= 0 && widgetIndex < mine.size() ? mine.get(widgetIndex) : null;
    }

    /** The layer menu's state on the screen: its own slots, the cursor, the player's inventory. */
    /**
     * The live menu's slot behind a captured slot, by where both are drawn: the capture lists
     * the slots active on the client, the server may count more (Mekanism's hidden crafting
     * window), so a list position is not a slot (measured: the QIO dashboard's move landed in
     * the hidden window's first slot).
     */
    private static LiveBridge.Slot liveSlotFor(Session s, int index) {
        if (index < 0 || index >= s.capture.slots().size()) {
            return null;
        }
        GuiCaptures.Slot c = s.capture.slots().get(index);
        // 0) The menu's own index: the number the server's content packet carries, which the
        // real client puts in ITS slot of that index whatever either side thinks the slot is
        // (measured: a block-placed Sophisticated Backpack has no storage on the server, 36
        // slots, while its client screen has 27 more; the server's slot 26, the player's
        // last inventory slot, shows in the client's backpack slot 26).
        if (c.menuIndex() >= 0) {
            for (LiveBridge.Slot l : s.liveSlots) {
                if (l.menuIndex() == c.menuIndex()) {
                    return l;
                }
            }
            // An index the server's menu does not have: no slot there, whatever is drawn here.
            if (!s.liveSlots.isEmpty()) {
                return null;
            }
        }
        // 1) Where both are drawn, when the server's positions are real ones.
        LiveBridge.Slot byPosition = null;
        int at = 0;
        for (LiveBridge.Slot l : s.liveSlots) {
            if (l.x() == c.x() && l.y() == c.y() && l.player() == c.player()) {
                byPosition = l;
                at++;
            }
        }
        if (at == 1) {
            return byPosition;
        }
        // 2) Some mods create slots at 0,0 on the server and place them in the screen (AE2):
        // the same owner and container slot, the n-th such capture slot to the n-th live one.
        int nth = 0;
        for (int k = 0; k < index; k++) {
            GuiCaptures.Slot o = s.capture.slots().get(k);
            if (o.player() == c.player() && o.index() == c.index()) {
                nth++;
            }
        }
        if (c.index() >= 0) {
            for (LiveBridge.Slot l : s.liveSlots) {
                if (l.player() == c.player() && l.containerSlot() == c.index() && nth-- == 0) {
                    return l;
                }
            }
        }
        // 3) An answer that says neither: the list position.
        return index < s.liveSlots.size() ? s.liveSlots.get(index) : null;
    }

    /** Where each filled live slot is drawn: the evidence when a gallery page shows an item elsewhere. */
    private static String pairing(Session s) {
        StringBuilder out = new StringBuilder();
        java.util.Set<LiveBridge.Slot> drawn = new java.util.HashSet<>();
        for (int k = 0; k < s.capture.slots().size(); k++) {
            LiveBridge.Slot l = liveSlotFor(s, k);
            if (l != null) {
                drawn.add(l);
                if (!l.stack().equals("-")) {
                    out.append(' ').append(k).append("->").append(l.menuIndex());
                }
            }
        }
        for (LiveBridge.Slot l : s.liveSlots) {
            if (!drawn.contains(l) && !l.stack().equals("-")) {
                out.append(" undrawn:").append(l.menuIndex());
            }
        }
        return out.length() == 0 ? "" : "; filled" + out;
    }

    private void applyLive(Player player, Session s, LiveBridge.State state) {
        s.liveSlots = state.slots();
        if (state.menuClass() != null) {
            s.liveMenuClass = state.menuClass();
        }
        s.liveWidgets = widgetStates(player, s);
        if (s.liveHeld == null || s.liveHeld.length != s.held.length) {
            s.liveHeld = new boolean[s.held.length];
        }
        for (int k = 0; k < s.held.length; k++) {
            LiveBridge.Slot slot = liveSlotFor(s, k);
            // Every client slot shows what the server sent for its index, as the real client's
            // content packet fills it; a slot past the server's menu keeps the client's own.
            // A display slot is in the art, drawn by the mod's screen with its own decorations.
            // A player slot both sides agree on is the player's inventory, which Paper holds
            // (the carried item as itself, not rebuilt from the layer's stack).
            boolean bothPlayer = slot != null && slot.player() && s.capture.slots().get(k).player();
            if (bothPlayer && slot.containerSlot() == s.capture.slots().get(k).index()) {
                // This menu sends that inventory slot to the client: what it shows is real again.
                java.util.Set<Integer> emptied = clientEmptied.get(player.getUniqueId());
                if (emptied != null) {
                    emptied.remove(slot.containerSlot());
                }
            }
            s.liveHeld[k] = slot != null && !bothPlayer;
            s.held[k] = slot == null || bothPlayer || !slot.pickable() ? null : live.toPaper(slot.stack());
        }
        s.cursor = live.toPaper(state.carried());
        live.applyInventory(player, state);
    }

    /** Whatever the player left in the screen goes back, so closing never eats items. */
    void close(Player player) {
        Session s = open.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        if (camera != null) {
            camera.release(player, "screen");
        }
        if (s.live && live != null) {
            // The mod's menu closes the way it does on a server (removed(), its own give-back);
            // the inventory it leaves is the player's.
            LiveBridge.State state = live.close(player);
            if (state.error() == null) {
                live.applyInventory(player, state);
                live.keepBlockEntity(player, state);
            } else {
                log.warning("[cim-screen] live close failed: " + state.error());
            }
            return;
        }
        List<ItemStack> back = new ArrayList<>();
        if (s.cursor != null) {
            back.add(s.cursor);
        }
        for (ItemStack stack : s.held) {
            if (stack != null) {
                back.add(stack);
            }
        }
        for (ItemStack stack : back) {
            player.getInventory().addItem(stack).values()
                    .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        }
    }

    /**
     * A mod block broken in Paper's world: the block's own code decides what it drops (a
     * Sophisticated Backpack drops itself with its contents), not the note block that stands
     * for it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.NOTE_BLOCK) {
            return;
        }
        String modded = blockCarriers.get(carrierKey(block.getBlockData().getAsString()));
        LiveBridge bridge = modded == null ? null : live();
        if (bridge == null) {
            return;
        }
        LiveBridge.Broken broken = bridge.breakBlock(event.getPlayer(), block, modded);
        if (broken.state().error() != null) {
            log.warning("[cim-screen] live break failed: " + broken.state().error());
            return;
        }
        event.setDropItems(false);
        org.bukkit.Location at = block.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack stack : broken.spilled()) {
            block.getWorld().dropItemNaturally(at, stack);
        }
        bridge.applyInventory(event.getPlayer(), broken.state());
        log.info("[cim-screen] " + event.getPlayer().getName() + " broke " + modded + ": " + broken.spilled().size()
                + " stack(s) dropped");
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (!busy.test(event.getPlayer()) && open.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            click(event.getPlayer(), "left");
        }
    }

    /** Opens the screen the walk saw this block or item open, or passes the click to it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (busy.test(player)) {
            return;
        }
        if (open.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND
                    && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                click(player, "right");
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        String opener = null;
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.NOTE_BLOCK) {
            String modded = blockCarriers.get(carrierKey(block.getBlockData().getAsString()));
            opener = modded == null ? null : "block:" + modded;
            LiveBridge bridge = live();
            if (modded != null && bridge != null && !player.isSneaking()) {
                LiveBridge.State state = bridge.openBlock(player, block, modded);
                if (openLive(player, state, opener)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        ItemStack item = event.getItem();
        // A mod item on a block: its own useOn decides, as on a real server (a block it
        // places, a backpack set down by a sneaking click, a menu it opens).
        if (block != null && event.getAction() == Action.RIGHT_CLICK_BLOCK && item != null
                && !item.getType().isAir() && !itemId(item).startsWith("minecraft:")) {
            LiveBridge bridge = live();
            if (bridge != null && placeOrUse(player, bridge, block, event.getBlockFace(),
                    block.getType() == Material.NOTE_BLOCK ? blockCarriers.get(carrierKey(block.getBlockData().getAsString())) : null)) {
                event.setCancelled(true);
                return;
            }
        }
        if (opener == null && item != null && !item.getType().isAir()) {
            opener = "item:" + itemId(item);
            // The mod's own use() decides, when the layer is there: what opens is its menu.
            LiveBridge bridge = live();
            if (bridge != null && !player.isSneaking()) {
                LiveBridge.State state = bridge.openHeld(player);
                if (openLive(player, state, opener)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        // The walk recorded whether the mod wants a sneaking click, as Create's controller does.
        ScreenPack.Scene scene = opener == null ? null
                : sceneForOpener((player.isSneaking() ? "sneak+" : "") + opener);
        if (scene != null) {
            event.setCancelled(true);
            open(player, scene);
        }
    }

    /**
     * The held mod item used on a block by its own code. A block it placed is put down in
     * Paper's world as its note-block carrier, with its block entity's data kept for the
     * first opening; a menu it opened is shown. True when the click did something.
     */
    private boolean placeOrUse(Player player, LiveBridge bridge, Block clicked, org.bukkit.block.BlockFace face,
                               String clickedId) {
        LiveBridge.UseOn result = bridge.useOn(player, clicked, face, clickedId);
        if (result.state().error() != null) {
            log.warning("[cim-screen] live use-on failed: " + result.state().error());
            return false;
        }
        boolean did = false;
        if (result.placedId() != null) {
            F2vPack.VisualBlock row = blockCarrierRows.get(result.placedId());
            Block at = clicked.getWorld().getBlockAt(result.x(), result.y(), result.z());
            if (row != null && (at.getType().isAir() || at.isReplaceable())) {
                at.setBlockData(org.bukkit.Bukkit.createBlockData("minecraft:note_block[instrument=" + row.instrument()
                        + ",note=" + row.note() + "]"), false);
                bridge.keepPlaced(at, result.placedId(), result.placedBlockEntity());
                log.info("[cim-screen] " + player.getName() + " placed " + result.placedId() + " at "
                        + at.getX() + "," + at.getY() + "," + at.getZ());
                did = true;
            } else {
                log.info("[cim-screen] " + result.placedId() + " placed in the layer has no carrier here");
            }
        }
        if (result.state().open()) {
            did |= openLive(player, result.state(), "item:" + itemId(player.getInventory().getItemInMainHand()));
        } else if (did) {
            bridge.applyInventory(player, result.state());
        }
        return did;
    }

    private ScreenPack.Scene sceneForOpener(String opener) {
        for (GuiCaptures.Capture c : captures.values()) {
            if (c.openers().contains(opener)) {
                return sceneFor(c.menuId(), c.menuClass());
            }
        }
        return null;
    }

    /** {@code minecraft:note_block[instrument=harp,note=5,...]} to {@code harp:5}. */
    static String carrierKey(String state) {
        Matcher m = STATE.matcher(state);
        String instrument = null;
        String note = null;
        while (m.find()) {
            if (m.group(1).equals("instrument")) {
                instrument = m.group(2);
            } else if (m.group(1).equals("note")) {
                note = m.group(2);
            }
        }
        return instrument + ":" + note;
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            close(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        close(event.getPlayer());
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code ScreenHud --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        Pointer p = new Pointer(300, 170, 179f, 0f);
        bad += check(p.x() == 0 && p.y() == 0, "the pointer starts centred");
        p.look(-179f, 0f);
        bad += check(p.x() == 12, "crossing the yaw seam is a 2 degree move: " + p.x());
        p.look(-179f, 10f);
        bad += check(p.y() == 60, "looking down moves the pointer down: " + p.y());
        p.look(-179f, 90f);
        bad += check(p.y() == 170, "the pointer stops at the edge: " + p.y());
        p.look(-179f, 60f);
        bad += check(p.y() == -10, "it leaves the edge as soon as the look reverses: " + p.y());
        bad += check(carrierKey("minecraft:note_block[instrument=trumpet_exposed,note=12,powered=false]")
                .equals("trumpet_exposed:12"), "carrier key reads a real state string");
        GuiCaptures.Capture c = GuiCaptures.parse(List.of(
                "cell\tx\ty\tz\tmenu\tnote",
                "cell\t1\t2\t3\tcreate:filter\topened-by:item:create:filter\tcom.simibubi.FilterMenu",
                "title\t-\t-\t-\tcreate:filter\tList Filter",
                "opener\t-\t-\t-\tcreate:filter\titem:create:filter",
                "opener\t-\t-\t-\tcreate:filter\titem:create:other",
                "slot\t0\t23\t25\tcreate:filter\tmenu",
                "slot\t1\t38\t121\tcreate:filter\tplayer"));
        bad += check(c != null && c.menuClass().equals("com/simibubi/FilterMenu"), "capture keeps the menu class");
        bad += check(c != null && "List Filter".equals(c.title()), "capture keeps the title");
        bad += check(c != null && c.openers().size() == 2, "openers are collected once each");
        bad += check(c != null && c.slots().size() == 2 && c.slots().get(1).player(), "slots keep their owner");
        bad += check(playerIndex(0) == 9 && playerIndex(26) == 35 && playerIndex(27) == 0,
                "player slots map main inventory then hotbar");
        System.out.println(bad == 0 ? "ScreenHud selftest: ALL PASS" : "ScreenHud selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
