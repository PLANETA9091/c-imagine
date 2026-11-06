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

        Session(ScreenPack.Scene scene, GuiCaptures.Capture capture, Pointer pointer) {
            this.scene = scene;
            this.capture = capture;
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
            originX = -(minX + maxX) / 2;
            originY = -(minY + maxY) / 2;
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
        int hover() {
            if (capture == null) {
                return -1;
            }
            int x = pointer.x() - originX;
            int y = pointer.y() - originY;
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
    private final List<ScreenPack.Scene> scenes;
    private Map<String, GuiCaptures.Capture> captures;
    private final Path scenesDir;
    private final ItemGlyphs glyphs;
    private final Map<Float, String> itemCarriers = new HashMap<>();
    private final Map<String, String> blockCarriers = new HashMap<>();
    private final Map<UUID, Session> open = new HashMap<>();

    public ScreenHud(Logger log, Path modsDir) {
        this.log = log;
        List<ScreenPack.Scene> found = List.of();
        Map<String, GuiCaptures.Capture> known = Map.of();
        ItemGlyphs icons = null;
        Path root = modsDir.resolveSibling("c-imagine");
        scenesDir = root.resolve("scenes");
        try {
            found = ScreenPack.scenes(modsDir);
            known = GuiCaptures.byClass(GuiCaptures.read(root.resolve("scenes")));
            icons = new ItemGlyphs(ScreenPack.moddedIcons(modsDir));
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
            captures = GuiCaptures.byClass(GuiCaptures.read(scenesDir));
        } catch (IOException e) {
            log.warning("[cim-screen] capture read failed: " + e);
        }
    }

    private void readCarriers(Path f2v) throws IOException {
        Path items = f2v.resolve("visual-items.resolved.tsv");
        if (Files.isRegularFile(items)) {
            for (F2vPack.VisualItem row : F2vPack.readManifest(items)) {
                itemCarriers.put(row.cmd(), row.moddedId());
            }
        }
        Path blocks = f2v.resolve("visual-blocks.tsv");
        if (Files.isRegularFile(blocks)) {
            for (F2vPack.VisualBlock row : F2vPack.readBlockManifest(blocks)) {
                blockCarriers.put(row.instrument() + ":" + row.note(), row.moddedBlockId());
            }
        }
    }

    /** The scanned screen for a menu class, or null. */
    private ScreenPack.Scene sceneFor(String menuClass) {
        return scenes.stream().filter(s -> menuClass.equals(s.menu())).findFirst().orElse(null);
    }

    public void open(Player player, ScreenPack.Scene scene) {
        GuiCaptures.Capture capture = scene.menu() == null ? null : captures.get(scene.menu());
        open.put(player.getUniqueId(), new Session(scene, capture,
                new Pointer(300, 170, player.getYaw(), player.getPitch())));
    }

    /** Opens by menu class; the gallery's ours leg uses this. */
    public boolean openMenuClass(Player player, String menuClass) {
        refreshCaptures();
        ScreenPack.Scene scene = sceneFor(menuClass.replace('.', '/'));
        if (scene != null) {
            open(player, scene);
        }
        return scene != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        CommandSender target = sender instanceof ProxiedCommandSender proxy ? proxy.getCallee() : sender;
        ScreenPack.Scene scene = args.length == 1 ? scenes.stream()
                .filter(s -> s.shortName().equalsIgnoreCase(args[0])).findFirst().orElse(null) : null;
        if (!(target instanceof Player player) || scene == null) {
            sender.sendMessage("Usage: /modscreen <" + String.join("|",
                    scenes.stream().map(ScreenPack.Scene::shortName).limit(12).toList()) + "...>");
            return true;
        }
        open(player, scene);
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
        String glyph = glyphs.glyph(itemId(stack));
        if (glyph != null) {
            frame.free(x, y, glyph, 17);
        }
        if (stack.getAmount() > 1) {
            String n = String.valueOf(stack.getAmount());
            frame.freeText(x + 17 - HudText.width(n), y + 9, n);
        }
    }

    /** Player slots run main inventory then hotbar, as vanilla menus add them. */
    private static int playerIndex(int nth) {
        return nth < 27 ? nth + 9 : nth - 27;
    }

    private ItemStack slotItem(Player player, Session s, int index, int nthPlayer) {
        return s.capture.slots().get(index).player()
                ? player.getInventory().getItem(playerIndex(nthPlayer)) : s.held[index];
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
        for (ScreenPack.Piece piece : s.scene.pieces()) {
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
            int hover = s.hover();
            int nth = 0;
            for (int i = 0; i < s.capture.slots().size(); i++) {
                GuiCaptures.Slot slot = s.capture.slots().get(i);
                int x = s.originX + slot.x();
                int y = s.originY + slot.y();
                if (slot.x() > -1000) {
                    if ((slot.player() && !panel) || i == hover) {
                        frame.free(x - 1, y - 1, String.valueOf(i == hover ? HudPack.SLOT_HOVER : HudPack.SLOT), 19);
                    }
                    drawItem(frame, x, y, slotItem(player, s, i, nth));
                }
                nth += slot.player() ? 1 : 0;
            }
        }
        drawItem(frame, s.pointer.x() - 8, s.pointer.y() - 8, s.cursor);
        frame.free(s.pointer.x(), s.pointer.y(), String.valueOf(HudPack.CURSOR), 9);
    }

    private void click(Player player, String button) {
        Session s = open.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        int index = s.hover();
        log.info("[cim-screen] " + player.getName() + " " + button + " click slot=" + index + " at "
                + (s.pointer.x() - s.originX) + "," + (s.pointer.y() - s.originY) + " in " + s.scene.shortName());
        if (index < 0) {
            return;
        }
        // Left swaps whole stacks, as a vanilla slot does. Nothing here is the mod's menu.
        ItemStack carried = s.cursor;
        if (s.capture.slots().get(index).player()) {
            int slot = playerIndex(nthPlayer(s, index));
            ItemStack there = player.getInventory().getItem(slot);
            s.cursor = there == null || there.getType().isAir() ? null : there;
            player.getInventory().setItem(slot, carried);
        } else {
            s.cursor = s.held[index];
            s.held[index] = carried;
        }
    }

    /** Whatever the player left in the screen goes back, so closing never eats items. */
    void close(Player player) {
        Session s = open.remove(player.getUniqueId());
        if (s == null) {
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

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (open.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            click(event.getPlayer(), "left");
        }
    }

    /** Opens the screen the walk saw this block or item open, or passes the click to it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (open.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            if (event.getHand() == EquipmentSlot.HAND
                    && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                click(player, "right");
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND || player.isSneaking()
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        String opener = null;
        Block block = event.getClickedBlock();
        if (block != null && block.getType() == Material.NOTE_BLOCK) {
            String modded = blockCarriers.get(carrierKey(block.getBlockData().getAsString()));
            opener = modded == null ? null : "block:" + modded;
        }
        ItemStack item = event.getItem();
        if (opener == null && item != null && !item.getType().isAir()) {
            opener = "item:" + itemId(item);
        }
        ScreenPack.Scene scene = opener == null ? null : sceneForOpener(opener);
        if (scene != null) {
            event.setCancelled(true);
            open(player, scene);
        }
    }

    private ScreenPack.Scene sceneForOpener(String opener) {
        for (GuiCaptures.Capture c : captures.values()) {
            if (c.openers().contains(opener)) {
                return sceneFor(c.menuClass());
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
                "opener\t-\t-\t-\tcreate:filter\titem:create:filter",
                "opener\t-\t-\t-\tcreate:filter\titem:create:other",
                "slot\t0\t23\t25\tcreate:filter\tmenu",
                "slot\t1\t38\t121\tcreate:filter\tplayer"));
        bad += check(c != null && c.menuClass().equals("com/simibubi/FilterMenu"), "capture keeps the menu class");
        bad += check(c != null && c.openers().size() == 2, "openers are collected once each");
        bad += check(c != null && c.slots().size() == 2 && c.slots().get(1).player(), "slots keep their owner");
        bad += check(playerIndex(0) == 9 && playerIndex(26) == 35 && playerIndex(27) == 0,
                "player slots map main inventory then hotbar");
        System.out.println(bad == 0 ? "ScreenHud selftest: ALL PASS" : "ScreenHud selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
