package dev.cim.hud;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;

/**
 * Draws a scanned mod screen on the HUD, with a mouse.
 *
 * <p>A vanilla client sends no pointer position, so the look direction stands
 * in for it: each tick the change in yaw and pitch moves a virtual cursor.
 * Attack and use packets are the buttons. Esc is not visible to the server
 * when no vanilla screen is open, so sneaking closes.
 */
public final class ScreenHud implements HudService.Layer, Listener, CommandExecutor {

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
        final Pointer pointer;

        Session(ScreenPack.Scene scene, Pointer pointer) {
            this.scene = scene;
            this.pointer = pointer;
        }
    }

    private final Logger log;
    private final List<ScreenPack.Scene> scenes;
    private final Map<UUID, Session> open = new HashMap<>();

    public ScreenHud(Logger log, Path modsDir) {
        this.log = log;
        List<ScreenPack.Scene> found;
        try {
            found = ScreenPack.scenes(modsDir);
        } catch (IOException e) {
            log.warning("[cim-screen] scan failed: " + e);
            found = List.of();
        }
        scenes = found;
        log.info("[cim-screen] " + scenes.size() + " screen(s) scanned");
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
        open.put(player.getUniqueId(), new Session(scene,
                new Pointer(300, 170, player.getYaw(), player.getPitch())));
        return true;
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
        frame.free(-s.scene.width() / 2, -s.scene.height() / 2, String.valueOf(s.scene.glyph()),
                s.scene.width() + 1);
        frame.free(s.pointer.x(), s.pointer.y(), String.valueOf(HudPack.CURSOR), 9);
    }

    private void click(Player player, String button) {
        Session s = open.get(player.getUniqueId());
        if (s != null) {
            int x = s.pointer.x() + s.scene.width() / 2;
            int y = s.pointer.y() + s.scene.height() / 2;
            log.info("[cim-screen] " + player.getName() + " " + button + " click at " + x + "," + y
                    + " in " + s.scene.shortName());
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (open.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            click(event.getPlayer(), "left");
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (open.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                click(event.getPlayer(), "right");
            }
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            open.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId());
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
        System.out.println(bad == 0 ? "ScreenHud selftest: ALL PASS" : "ScreenHud selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
