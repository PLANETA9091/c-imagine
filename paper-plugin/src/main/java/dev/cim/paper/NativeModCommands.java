package dev.cim.paper;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Native vanilla command bridge for modded IDs: intercepts {@code /give},
 * {@code /summon}, and {@code /locate biome} when a non-{@code minecraft:}
 * namespaced ID is passed, and injects modded IDs into Paper's
 * {@link AsyncTabCompleteEvent}.
 */
final class NativeModCommands implements Listener {

    private final CimPlugin plugin;
    private final List<String> moddedBiomeIds;

    NativeModCommands(CimPlugin plugin) {
        this.plugin = plugin;
        this.moddedBiomeIds = scanModdedBiomeIds();
    }

    private static List<String> scanModdedBiomeIds() {
        TreeSet<String> out = new TreeSet<>();
        File modsDir = new File(System.getProperty("user.dir"), "mods");
        File[] jars = modsDir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return List.of();
        }
        for (File jar : jars) {
            try (JarFile jf = new JarFile(jar)) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    // data/<ns>/worldgen/biome/<id>.json
                    if (!name.startsWith("data/") || !name.endsWith(".json")) {
                        continue;
                    }
                    int idx = name.indexOf("/worldgen/biome/");
                    if (idx <= 5) {
                        continue;
                    }
                    String ns = name.substring(5, idx);
                    if (ns.equals("minecraft") || ns.contains("/")) {
                        continue;
                    }
                    String path = name.substring(idx + "/worldgen/biome/".length(), name.length() - 5);
                    if (!path.isEmpty()) {
                        out.add(ns + ":" + path);
                    }
                }
            } catch (Exception ignored) {
                // skip unreadable jar
            }
        }
        return List.copyOf(out);
    }

    /**
     * Automatically appends any missing {@code assets/<ns>/models/item/<name>.json}
     * entries from {@code mods/*.jar} into {@code c-imagine/f2v/visual-items.tsv}
     * before F2V pack synthesis runs, so modded items are available to {@code /give}
     * without manual manifest editing.
     */
    static int syncVisualItemsManifest() {
        File modsDir = new File(System.getProperty("user.dir"), "mods");
        File[] jars = modsDir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return 0;
        }
        java.nio.file.Path manifest = java.nio.file.Path.of(
                System.getProperty("user.dir"), "c-imagine", "f2v", "visual-items.tsv");
        try {
            java.nio.file.Files.createDirectories(manifest.getParent());
            java.util.Set<String> existing = new java.util.HashSet<>();
            if (java.nio.file.Files.isRegularFile(manifest)) {
                for (String line : java.nio.file.Files.readAllLines(manifest)) {
                    String s = line.trim();
                    if (!s.isEmpty() && !s.startsWith("#")) {
                        int tab = s.indexOf('\t');
                        existing.add(tab > 0 ? s.substring(0, tab) : s);
                    }
                }
            }
            TreeSet<String> toAdd = new TreeSet<>();
            for (File jar : jars) {
                try (JarFile jf = new JarFile(jar)) {
                    Enumeration<JarEntry> entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (!name.startsWith("assets/") || !name.endsWith(".json")) {
                            continue;
                        }
                        int idx = name.indexOf("/models/item/");
                        if (idx <= 7) {
                            continue;
                        }
                        String ns = name.substring(7, idx);
                        if (ns.equals("minecraft") || ns.contains("/")) {
                            continue;
                        }
                        String item = name.substring(idx + "/models/item/".length(), name.length() - 5);
                        if (!item.isEmpty() && !item.contains("/")) {
                            String id = ns + ":" + item;
                            if (!existing.contains(id)) {
                                toAdd.add(id);
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // skip unreadable jar
                }
            }
            if (toAdd.isEmpty()) {
                return 0;
            }
            StringBuilder sb = new StringBuilder();
            for (String id : toAdd) {
                sb.append(id).append("\tminecraft:paper\n");
            }
            java.nio.file.Files.writeString(manifest, sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            return toAdd.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static boolean isModdedId(String token) {
        if (token == null) {
            return false;
        }
        int colon = token.indexOf(':');
        return colon > 0 && !token.startsWith("minecraft:");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg.startsWith("/")) {
            msg = msg.substring(1);
        }
        if (handleCommand(event.getPlayer(), msg)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (handleCommand(event.getSender(), event.getCommand())) {
            event.setCancelled(true);
        }
    }

    private boolean handleCommand(CommandSender sender, String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String[] parts = trimmed.split("\\s+");
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("minecraft:")) {
            cmd = cmd.substring("minecraft:".length());
        }

        // /give <target> <modded:item> [count]
        if (cmd.equals("give") && parts.length >= 3 && isModdedId(parts[2])) {
            if (!sender.hasPermission("minecraft.command.give") && !sender.isOp()) {
                sender.sendMessage("You do not have permission to use /give.");
                return true;
            }
            List<Player> targets = resolveTargets(sender, parts[1]);
            if (targets.isEmpty()) {
                sender.sendMessage("[c-imagine] No matching player for: " + parts[1]);
                return true;
            }
            int count = 1;
            if (parts.length >= 4) {
                try {
                    count = Math.max(1, Math.min(64, Integer.parseInt(parts[3])));
                } catch (NumberFormatException ignored) {
                    count = 1;
                }
            }
            String moddedId = parts[2].toLowerCase(Locale.ROOT);
            for (Player p : targets) {
                for (int i = 0; i < count; i++) {
                    plugin.giveVisualItem(p.getName(), moddedId);
                }
            }
            sender.sendMessage("[c-imagine] Gave " + count + "x [" + moddedId + "] to " + targets.size() + " player(s)");
            return true;
        }

        // /summon <modded:entity_or_item> [x y z]
        if (cmd.equals("summon") && parts.length >= 2 && isModdedId(parts[1])) {
            if (!sender.hasPermission("minecraft.command.summon") && !sender.isOp()) {
                sender.sendMessage("You do not have permission to use /summon.");
                return true;
            }
            String moddedId = parts[1].toLowerCase(Locale.ROOT);
            Location loc = null;
            if (sender instanceof Player p) {
                loc = p.getLocation();
            } else if (!Bukkit.getWorlds().isEmpty()) {
                loc = Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            if (loc == null) {
                sender.sendMessage("[c-imagine] No world available to summon " + moddedId);
                return true;
            }
            double x = loc.getX();
            double y = loc.getY();
            double z = loc.getZ();
            if (parts.length >= 5) {
                try {
                    x = parseCoord(parts[2], x);
                    y = parseCoord(parts[3], y);
                    z = parseCoord(parts[4], z);
                } catch (NumberFormatException ignored) {
                    // keep default
                }
            }
            String uuid = plugin.spawnVisualDisplay(loc.getWorld().getName(), x, y, z, moddedId);
            if (uuid != null) {
                sender.sendMessage("[c-imagine] Summoned [" + moddedId + "] at "
                        + String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z));
            } else {
                sender.sendMessage("[c-imagine] Could not summon [" + moddedId + "] (not in F2V visual manifest)");
            }
            return true;
        }

        // /locate biome <modded:biome>
        if (cmd.equals("locate") && parts.length >= 3
                && parts[1].equalsIgnoreCase("biome") && isModdedId(parts[2])) {
            if (!sender.hasPermission("minecraft.command.locate") && !sender.isOp()) {
                sender.sendMessage("You do not have permission to use /locate.");
                return true;
            }
            sender.sendMessage("[c-imagine] Modded biome [" + parts[2]
                    + "] is not yet bridged into Paper's biome registry/worldgen (tracked in ROADMAP.md).");
            return true;
        }

        return false;
    }

    @EventHandler
    public void onAsyncTabComplete(AsyncTabCompleteEvent event) {
        String buf = event.getBuffer();
        if (buf == null) {
            return;
        }
        if (buf.startsWith("/")) {
            buf = buf.substring(1);
        }
        boolean trailingSpace = buf.endsWith(" ");
        String[] parts = buf.trim().isEmpty() ? new String[0] : buf.trim().split("\\s+");
        if (parts.length == 0) {
            return;
        }
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("minecraft:")) {
            cmd = cmd.substring("minecraft:".length());
        }

        // /give <target> <item>
        if (cmd.equals("give") && ((parts.length == 2 && trailingSpace) || (parts.length == 3 && !trailingSpace))) {
            String prefix = (parts.length == 3 && !trailingSpace) ? parts[2].toLowerCase(Locale.ROOT) : "";
            addCompletions(event, plugin.f2vItemIds(), prefix);
            return;
        }

        // /summon <entity>
        if (cmd.equals("summon") && ((parts.length == 1 && trailingSpace) || (parts.length == 2 && !trailingSpace))) {
            String prefix = (parts.length == 2 && !trailingSpace) ? parts[1].toLowerCase(Locale.ROOT) : "";
            addCompletions(event, plugin.f2vItemIds(), prefix);
            return;
        }

        // /locate biome <modded:biome>
        if (cmd.equals("locate") && parts.length >= 2 && parts[1].equalsIgnoreCase("biome")
                && ((parts.length == 2 && trailingSpace) || (parts.length == 3 && !trailingSpace))) {
            String prefix = (parts.length == 3 && !trailingSpace) ? parts[2].toLowerCase(Locale.ROOT) : "";
            addCompletions(event, moddedBiomeIds, prefix);
        }
    }

    private static void addCompletions(AsyncTabCompleteEvent event, Collection<String> ids, String prefix) {
        if (ids.isEmpty()) {
            return;
        }
        List<AsyncTabCompleteEvent.Completion> merged = new ArrayList<>(event.completions());
        int added = 0;
        for (String id : ids) {
            if (prefix.isEmpty() || id.toLowerCase(Locale.ROOT).contains(prefix)) {
                merged.add(AsyncTabCompleteEvent.Completion.completion(id));
                if (++added >= 50) {
                    break;
                }
            }
        }
        if (added > 0) {
            event.completions(merged);
            event.setHandled(true);
        }
    }

    private static List<Player> resolveTargets(CommandSender sender, String selector) {
        if (selector.equalsIgnoreCase("@s") && sender instanceof Player p) {
            return List.of(p);
        }
        if (selector.equalsIgnoreCase("@a")) {
            return new ArrayList<>(Bukkit.getOnlinePlayers());
        }
        if (selector.equalsIgnoreCase("@p")) {
            if (sender instanceof Player p) {
                return List.of(p);
            }
            return Bukkit.getOnlinePlayers().stream().limit(1).map(p -> (Player) p).toList();
        }
        Player exact = Bukkit.getPlayerExact(selector);
        return exact != null ? List.of(exact) : List.of();
    }

    private static double parseCoord(String tok, double base) {
        if (tok.startsWith("~")) {
            return tok.length() == 1 ? base : base + Double.parseDouble(tok.substring(1));
        }
        return Double.parseDouble(tok);
    }
}
