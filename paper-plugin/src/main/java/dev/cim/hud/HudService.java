package dev.cim.hud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps one carrier boss bar per player and redraws it from the registered
 * layers. A frame that matches the last one sends nothing, so an idle HUD
 * costs no packets.
 */
public final class HudService implements Listener {

    /** A source of HUD content; {@code draw} runs on the main thread every tick. */
    public interface Layer {
        void draw(Player player, HudFrame frame);
    }

    private final List<Layer> layers = new ArrayList<>();
    private final Map<UUID, BossBar> carriers = new HashMap<>();
    private final Map<UUID, Component> last = new HashMap<>();
    private long sent;

    public void register(Layer layer) {
        layers.add(layer);
    }

    public void start(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::attach);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Name updates sent so far; the profile measures the channel with this. */
    public long sentFrames() {
        return sent;
    }

    /** Harness layer: a label on every anchor, for the visual check of the shader's placement. */
    static Layer anchorProbe() {
        return (player, frame) -> {
            for (HudLayout.Anchor a : HudLayout.Anchor.values()) {
                if (HudLayout.rows(a) > 0) {
                    frame.text(a, 0, a.name(), net.kyori.adventure.text.format.NamedTextColor.YELLOW);
                    frame.text(a, 1, "second line", net.kyori.adventure.text.format.NamedTextColor.AQUA);
                }
            }
        };
    }

    /** A recipe toast would sit over the frame the gallery compares. */
    @EventHandler
    public void onRecipe(org.bukkit.event.player.PlayerRecipeDiscoverEvent event) {
        if (harnessAccount(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    /** So would an advancement toast: an item the walk hands over can complete one. */
    @EventHandler
    public void onAdvancement(com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent event) {
        if (harnessAccount(event.getPlayer().getName())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        attach(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        carriers.remove(id);
        last.remove(id);
    }

    /**
     * The visual harness compares screenshots with a real NeoForge client, which
     * has no such HUD, so its accounts must keep a bare screen.
     */
    static boolean harnessAccount(String name) {
        return (name.equals("cimviewer") || name.equals("cimbot")) && !Boolean.getBoolean("cim.hud.debug");
    }

    private void attach(Player player) {
        if (!harnessAccount(player.getName())) {
            show(player);
        }
    }

    /** Gives a player the carrier regardless of account. */
    void show(Player player) {
        if (carriers.containsKey(player.getUniqueId())) {
            return;
        }
        BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        carriers.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            BossBar bar = carriers.get(player.getUniqueId());
            if (bar == null) {
                continue;
            }
            HudFrame frame = new HudFrame();
            for (Layer layer : layers) {
                layer.draw(player, frame);
            }
            Component next = frame.build();
            if (!next.equals(last.get(player.getUniqueId()))) {
                last.put(player.getUniqueId(), next);
                bar.name(next);
                sent++;
            }
        }
    }
}
