package dev.cim.f2v;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.cim.f2v.F2vPack;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ItemText {
    private final Map<String, String> translations = new TreeMap<String, String>();

    public ItemText(Path mods, Logger log) {
        if (!Files.isDirectory(mods, new LinkOption[0])) {
            return;
        }
        try (Stream<Path> files = Files.list(mods);){
            for (Path jar : files.filter(path -> path.toString().endsWith(".jar")).sorted().toList()) {
                try (ZipFile zip = new ZipFile(jar.toFile());){
                    for (ZipEntry entry : zip.stream().filter(e -> e.getName().matches("assets/[^/]+/lang/en_us\\.json")).toList()) {
                        try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);){
                            JsonParser.parseReader((Reader)reader).getAsJsonObject().entrySet().forEach(value -> {
                                if (((JsonElement)value.getValue()).isJsonPrimitive() && ((JsonElement)value.getValue()).getAsJsonPrimitive().isString()) {
                                    this.translations.putIfAbsent((String)value.getKey(), ((JsonElement)value.getValue()).getAsString());
                                }
                            });
                        }
                    }
                }
                catch (IOException | RuntimeException e2) {
                    log.warning("Could not read item text in " + String.valueOf(jar) + ": " + e2.getMessage());
                }
            }
        }
        catch (IOException e3) {
            log.warning("Could not list mod jars for item text: " + e3.getMessage());
        }
    }

    public String name(String id) {
        String key = id.replace(':', '.').replace('/', '.');
        return this.translations.getOrDefault("item." + key, this.translations.getOrDefault("block." + key, F2vPack.allocatedItemName(id)));
    }

    public List<String> lore(String id) {
        String key = id.replace(':', '.').replace('/', '.');
        ArrayList<String> lines = new ArrayList<String>();
        for (String string : List.of("item." + key, "block." + key)) {
            for (String suffix : List.of(".tooltip.summary", ".description", ".desc")) {
                ItemText.append(lines, this.translations.get(string + suffix));
            }
            int n = 1;
            while (this.translations.containsKey(string + ".tooltip.behaviour" + n)) {
                ItemText.append(lines, this.translations.get(string + ".tooltip.condition" + n));
                ItemText.append(lines, this.translations.get(string + ".tooltip.behaviour" + n));
                ++n;
            }
        }
        if (lines.isEmpty()) {
            String[] parts = id.split(":", 2);
            ItemText.append(lines, this.translations.get(parts[0] + ".ponder." + parts[1] + ".text_1"));
        }
        return lines.stream().distinct().toList();
    }

    private static void append(List<String> lines, String text) {
        if (text != null) {
            text.replaceAll("_([^_]+)_", "$1").lines().filter(line -> !line.isBlank()).forEach(lines::add);
        }
    }
}
