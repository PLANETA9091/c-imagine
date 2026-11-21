package dev.cim.paper;

import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import dev.cim.f2v.F2vPack;
import dev.cim.f2v.ItemText;
import dev.cim.paper.CimPlugin;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Server;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

final class NativeModCommands
implements Listener {
    private static final String ID = "[a-z0-9_.-]+:[a-z0-9_./-]+";
    private static final Pattern GIVE = Pattern.compile("((?:minecraft:)?give +.+? +)([a-z0-9_.-]+:[a-z0-9_./-]+)( +[0-9]+)?");
    private static final Pattern SUMMON = Pattern.compile("(?:minecraft:)?summon +([a-z0-9_.-]+:[a-z0-9_./-]+)(?= |$)");
    private final CimPlugin plugin;
    private final ItemText itemText;
    private final List<String> biomes;
    private final List<String> structures;

    NativeModCommands(CimPlugin plugin) {
        this.plugin = plugin;
        TreeSet<String> biomeIds = new TreeSet<>();
        TreeSet<String> structureIds = new TreeSet<>();
        Path mods = Path.of("mods", new String[0]);
        this.itemText = new ItemText(mods, plugin.getLogger());
        if (Files.isDirectory(mods, new LinkOption[0])) {
            try (Stream<Path> files = Files.list(mods);){
                for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    try (ZipFile zip = new ZipFile(jar.toFile());){
                        zip.stream().forEach(entry -> {
                            NativeModCommands.addResource(entry.getName(), "worldgen/biome", biomeIds);
                            NativeModCommands.addResource(entry.getName(), "worldgen/structure", structureIds);
                        });
                    }
                    catch (IOException e) {
                        plugin.getLogger().warning("Could not scan command resources in " + String.valueOf(jar) + ": " + String.valueOf(e));
                    }
                }
            }
            catch (IOException e) {
                plugin.getLogger().warning("Could not list mod jars for command suggestions: " + String.valueOf(e));
            }
        }
        this.biomes = List.copyOf(biomeIds);
        this.structures = List.copyOf(structureIds);
    }

    private static void addResource(String name, String directory, Collection<String> ids) {
        if (!name.startsWith("data/") || !name.endsWith(".json")) {
            return;
        }
        int slash = name.indexOf(47, "data/".length());
        if (slash < 0 || !name.startsWith("/" + directory + "/", slash)) {
            return;
        }
        String namespace = name.substring("data/".length(), slash);
        if (!namespace.equals("minecraft")) {
            ids.add(namespace + ":" + name.substring(slash + directory.length() + 2, name.length() - 5));
        }
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        try {
            Server craft = this.plugin.getServer();
            Object server = craft.getClass().getMethod("getServer", new Class[0]).invoke((Object)craft, new Object[0]);
            Object commands = server.getClass().getMethod("getCommands", new Class[0]).invoke(server, new Object[0]);
            CommandDispatcher dispatcher = (CommandDispatcher)commands.getClass().getMethod("getDispatcher", new Class[0]).invoke(commands, new Object[0]);
            Field suggestions = ArgumentCommandNode.class.getDeclaredField("customSuggestions");
            suggestions.setAccessible(true);
            NativeModCommands.install(dispatcher.getRoot(), suggestions, this.plugin.commandItems().keySet(), new String[]{"give", "targets", "item"});
            NativeModCommands.install(dispatcher.getRoot(), suggestions, this.plugin.commandItems().keySet(), new String[]{"summon", "entity"});
            NativeModCommands.install(dispatcher.getRoot(), suggestions, this.biomes, new String[]{"locate", "biome", "biome"});
            NativeModCommands.install(dispatcher.getRoot(), suggestions, this.structures, new String[]{"locate", "structure", "structure"});
            this.plugin.getServer().getOnlinePlayers().forEach(player -> player.updateCommands());
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            this.plugin.getLogger().warning("Could not install modded command suggestions: " + String.valueOf(e));
        }
    }

    private static <S> void install(CommandNode<S> root, Field field, Collection<String> ids, String ... path) throws IllegalAccessException {
        if (ids.isEmpty()) {
            return;
        }
        CommandNode node = root;
        for (String segment : path) {
            if ((node = node.getChild(segment)) == null) {
                throw new IllegalStateException("Missing vanilla command argument " + String.join((CharSequence)" ", path));
            }
            if (node.getRedirect() == null) continue;
            node = node.getRedirect();
        }
        if (!(node instanceof ArgumentCommandNode)) {
            throw new IllegalStateException("Expected vanilla command argument " + String.join((CharSequence)" ", path));
        }
        ArgumentCommandNode argument = (ArgumentCommandNode)node;
        SuggestionProvider vanilla = argument.getCustomSuggestions();
        if (vanilla instanceof ModSuggestions) {
            return;
        }
        if (vanilla == null) {
            vanilla = (arg_0, arg_1) -> ((ArgumentType)argument.getType()).listSuggestions(arg_0, arg_1);
        }
        field.set(argument, new ModSuggestions(vanilla, ids));
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage().substring(1);
        String rewritten = this.rewriteGive(raw);
        if (!rewritten.equals(raw)) {
            event.setMessage("/" + rewritten);
        } else {
            Matcher match = SUMMON.matcher(raw);
            if (match.find() && this.plugin.commandItems().containsKey(match.group(1))) {
                event.setMessage("/" + this.rewriteSummon(raw, match));
            }
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onServerCommand(ServerCommandEvent event) {
        String raw = this.rewriteGive(event.getCommand());
        Matcher match = SUMMON.matcher(raw);
        event.setCommand(match.find() && this.plugin.commandItems().containsKey(match.group(1)) ? this.rewriteSummon(raw, match) : raw);
    }

    private String rewriteGive(String command) {
        Matcher match = GIVE.matcher(command);
        if (!match.matches()) {
            return command;
        }
        String id = match.group(2);
        String host = this.plugin.commandItems().get(id);
        if (host == null) {
            return command;
        }
        List<String> lore = this.itemText.lore(id);
        String tooltip = lore.isEmpty() ? "" : ",minecraft:lore=" + lore.stream().map(NativeModCommands::textTag).collect(Collectors.joining(",", "[", "]"));
        return match.group(1) + host + "[minecraft:item_model=\"" + F2vPack.allocatedItemModel(id) + "\",minecraft:item_name=" + NativeModCommands.textTag(this.itemText.name(id)) + tooltip + "]" + (match.group(3) == null ? "" : match.group(3));
    }

    private static String textTag(String text) {
        return "{text:" + String.valueOf(new JsonPrimitive(text)) + ",italic:false}";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerInventory inventory = event.getPlayer().getInventory();
        for (int slot = 0; slot < inventory.getSize(); ++slot) {
            String id;
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !stack.hasItemMeta()) continue;
            ItemMeta meta = stack.getItemMeta();
            String string = id = meta.hasItemModel() ? F2vPack.moddedIdOfItemModel(meta.getItemModel().toString()) : null;
            if (id == null || !this.plugin.commandItems().containsKey(id)) continue;
            if (!meta.hasItemName()) {
                meta.itemName((Component)Component.text((String)this.itemText.name(id)));
            }
            List<String> lore = this.itemText.lore(id);
            if (!meta.hasLore() && !lore.isEmpty()) {
                meta.lore(lore.stream().<Component>map(line -> Component.text((String)line).decoration(TextDecoration.ITALIC, false)).toList());
            }
            stack.setItemMeta(meta);
            inventory.setItem(slot, stack);
        }
    }

    private String rewriteSummon(String command, Matcher match) {
        String id = match.group(1);
        String tail = command.substring(match.end()).strip();
        if (tail.isEmpty()) {
            tail = "~ ~ ~";
        } else if (!tail.matches("\\S+ +\\S+ +\\S+")) {
            return command;
        }
        String root = command.startsWith("minecraft:") ? "minecraft:summon" : "summon";
        return root + " minecraft:item_display " + tail + " {item:{id:\"" + this.plugin.commandItems().get(id) + "\",count:1,components:{\"minecraft:item_model\":\"" + F2vPack.allocatedItemModel(id) + "\"}}}";
    }

    private record ModSuggestions<S>(SuggestionProvider<S> vanilla, Collection<String> ids) implements SuggestionProvider<S>
    {
        public CompletableFuture<Suggestions> getSuggestions(CommandContext<S> context, SuggestionsBuilder builder) throws CommandSyntaxException {
            String prefix = builder.getRemainingLowerCase();
            SuggestionsBuilder modded = builder.createOffset(builder.getStart());
            for (String id : this.ids) {
                if (!id.startsWith(prefix) && (prefix.contains(":") || !id.substring(id.indexOf(58) + 1).startsWith(prefix))) continue;
                modded.suggest(id);
            }
            return this.vanilla.getSuggestions(context, builder).thenApply(original -> Suggestions.merge((String)builder.getInput(), List.of(original, modded.build())));
        }
    }
}
