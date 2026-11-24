package dev.cim.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Registers the modded enchantments as REAL entries of Paper's server
 * enchantment registry, the same way {@link BiomeRegistry} does for biomes.
 *
 * <p>The definitions come from the Rust module ({@code crates/cim-enchant}:
 * the mod jars' {@code data/<ns>/enchantment/*.json}, translated 1.21.1 →
 * 26.3), written to {@code <root>/c-imagine/enchant-defs.json}. The document is
 * {@code {"enchantments":[{"id","definition"}], "tags":{"<tag id>":[ids]}}}.
 *
 * <p>Registration happens at enable, before the world loads: the lowered
 * {@code MappedRegistry.frozen} window, {@code Enchantment.DIRECT_CODEC}
 * decoding with registry-aware ops (the definitions name item tags), and the
 * stand-alone holders bound by hand (see {@link BiomeRegistry} for why
 * {@code freeze()} is not used). Enchantment tags are rebound with the full tag
 * map: vanilla memberships plus the mods' additions. The client learns the
 * entries through the normal configuration-phase registry sync.
 */
public final class EnchantRegistry {

    private EnchantRegistry() {
    }

    /** One enchantment definition read from the document. */
    record Entry(String id, JsonElement definition) {
    }

    /**
     * The parsed document: enchantments in file order, tag memberships, and the
     * gate (enchantment id -> the mod item ids it applies to; see {@link EnchantGate}).
     */
    record Document(List<Entry> enchantments, Map<String, List<String>> tags, Map<String, List<String>> gate) {
    }

    /**
     * Parse {@code enchant-defs.json}. Pure: no server objects, so it runs in
     * the selftest without Paper.
     */
    static Document parse(String raw) {
        JsonObject doc = JsonParser.parseString(raw).getAsJsonObject();
        List<Entry> entries = new ArrayList<>();
        JsonArray defs = doc.has("enchantments") ? doc.getAsJsonArray("enchantments") : new JsonArray();
        for (JsonElement element : defs) {
            JsonObject def = element.getAsJsonObject();
            entries.add(new Entry(def.get("id").getAsString(), def.get("definition")));
        }
        Map<String, List<String>> tags = new LinkedHashMap<>();
        if (doc.has("tags") && doc.get("tags").isJsonObject()) {
            for (Map.Entry<String, JsonElement> tag : doc.getAsJsonObject("tags").entrySet()) {
                List<String> members = new ArrayList<>();
                for (JsonElement m : tag.getValue().getAsJsonArray()) {
                    members.add(m.getAsString());
                }
                tags.put(tag.getKey(), members);
            }
        }
        Map<String, List<String>> gate = new LinkedHashMap<>();
        if (doc.has("gate") && doc.get("gate").isJsonObject()) {
            for (Map.Entry<String, JsonElement> row : doc.getAsJsonObject("gate").entrySet()) {
                List<String> items = new ArrayList<>();
                for (JsonElement m : row.getValue().getAsJsonArray()) {
                    items.add(m.getAsString());
                }
                gate.put(row.getKey(), items);
            }
        }
        return new Document(entries, tags, gate);
    }

    /**
     * @return the number of modded enchantments registered (0 when the module
     *     wrote no definitions, e.g. a plain Paper boot without the module).
     */
    static int install(Path root, Logger log) {
        Path defsPath = root.resolve("c-imagine").resolve("enchant-defs.json");
        if (!Files.isRegularFile(defsPath)) {
            return 0;
        }
        try {
            String raw = Files.readString(defsPath, StandardCharsets.UTF_8);
            Document doc = parse(raw);
            EnchantGate.set(doc.gate());
            if (doc.enchantments().isEmpty()) {
                return 0;
            }
            return register(doc, log);
        } catch (Throwable t) {
            log.log(Level.WARNING, CimPlugin.LOG_PREFIX + "enchant registry: install failed", t);
            return 0;
        }
    }

    private static int register(Document doc, Logger log) throws Exception {
        Class<?> craftRegistry = Class.forName("org.bukkit.craftbukkit.CraftRegistry");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        Object enchantmentKey = registries.getField("ENCHANTMENT").get(null);
        Method getNms = craftRegistry.getMethod("getMinecraftRegistry", resourceKey);
        Object registry = getNms.invoke(null, enchantmentKey);
        if (registry == null) {
            log.warning(CimPlugin.LOG_PREFIX + "enchant registry: no NMS enchantment registry");
            return 0;
        }

        Class<?> mapped = Class.forName("net.minecraft.core.MappedRegistry");
        Field frozen = mapped.getDeclaredField("frozen");
        frozen.setAccessible(true);
        boolean wasFrozen = frozen.getBoolean(registry);
        frozen.setBoolean(registry, false);

        Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier");
        Method idParse = identifierCls.getMethod("parse", String.class);
        Class<?> registrationInfo = Class.forName("net.minecraft.core.RegistrationInfo");
        Object builtIn = registrationInfo.getField("BUILT_IN").get(null);
        Method createKey = resourceKey.getMethod("create", resourceKey, identifierCls);
        Method containsKey = Class.forName("net.minecraft.core.Registry").getMethod("containsKey", identifierCls);
        Method register = mapped.getMethod("register", resourceKey, Object.class, registrationInfo);

        Class<?> enchantCls = Class.forName("net.minecraft.world.item.enchantment.Enchantment");
        Object codec = enchantCls.getField("DIRECT_CODEC").get(null);
        Class<?> codecCls = Class.forName("com.mojang.serialization.Codec");
        Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
        Class<?> jsonOps = Class.forName("com.mojang.serialization.JsonOps");
        Object jsonOpsInstance = jsonOps.getField("INSTANCE").get(null);
        // Registry-aware ops: the definitions name item tags (#minecraft:enchantable/...),
        // which a plain JsonOps cannot resolve.
        Object ops = registryOps(jsonOpsInstance, dynamicOps);
        Method parse = codecCls.getMethod("parse", dynamicOps, Object.class);

        Class<?> holderRef = Class.forName("net.minecraft.core.Holder$Reference");
        Method bindValue = holderRef.getDeclaredMethod("bindValue", Object.class);
        bindValue.setAccessible(true);
        Class<?> componentMap = Class.forName("net.minecraft.core.component.DataComponentMap");
        Object emptyComponents = componentMap.getField("EMPTY").get(null);
        Method bindComponents = holderRef.getMethod("bindComponents", componentMap);
        Method bindHolderTags = holderRef.getDeclaredMethod("bindTags", java.util.Collection.class);
        bindHolderTags.setAccessible(true);

        Class<?> tagKeyCls = Class.forName("net.minecraft.tags.TagKey");
        Method tagCreate = tagKeyCls.getMethod("create", resourceKey, identifierCls);
        Method bindTags = mapped.getMethod("bindTags", Map.class);
        Method getTags = mapped.getMethod("getTags");

        Map<String, Object> holders = new HashMap<>();
        List<String> failed = new ArrayList<>();
        int tagsBound = 0;
        try {
            for (Entry entry : doc.enchantments()) {
                try {
                    Object identifier = idParse.invoke(null, entry.id());
                    if (Boolean.TRUE.equals(containsKey.invoke(registry, identifier))) {
                        continue;
                    }
                    Object key = createKey.invoke(null, enchantmentKey, identifier);
                    Object result = parse.invoke(codec, ops, entry.definition());
                    Object value = result.getClass().getMethod("getOrThrow").invoke(result);
                    Object holder = register.invoke(registry, key, value, builtIn);
                    bindValue.invoke(holder, value);
                    bindComponents.invoke(holder, emptyComponents);
                    bindHolderTags.invoke(holder, List.of());
                    holders.put(entry.id(), holder);
                } catch (Throwable t) {
                    failed.add(entry.id() + ":" + t.getCause());
                }
            }
            if (!holders.isEmpty() && !doc.tags().isEmpty()) {
                // The full tag map: vanilla memberships (kept as they are) plus
                // the mods' additions. bindTags replaces every tag, so the
                // existing ones are carried over first.
                Map<Object, List<Object>> tagMap = new LinkedHashMap<>();
                java.util.stream.Stream<?> existing = (java.util.stream.Stream<?>) getTags.invoke(registry);
                existing.forEach(named -> {
                    List<Object> members = new ArrayList<>();
                    for (Object h : (Iterable<?>) named) {
                        members.add(h);
                    }
                    try {
                        tagMap.put(named.getClass().getMethod("key").invoke(named), members);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
                boolean changed = false;
                for (Map.Entry<String, List<String>> tag : doc.tags().entrySet()) {
                    Object tagKey = tagCreate.invoke(null, enchantmentKey, idParse.invoke(null, tag.getKey()));
                    List<Object> members = tagMap.computeIfAbsent(tagKey, k -> new ArrayList<>());
                    for (String id : tag.getValue()) {
                        Object holder = holders.get(id);
                        if (holder != null && !members.contains(holder)) {
                            members.add(holder);
                            changed = true;
                        }
                    }
                    tagsBound++;
                }
                if (changed) {
                    bindTags.invoke(registry, tagMap);
                }
            }
        } finally {
            // Restore the flag the registry had: freeze() is never called (it
            // refuses an already-frozen registry, see BiomeRegistry).
            frozen.setBoolean(registry, wasFrozen);
        }
        log.info(CimPlugin.LOG_PREFIX + "enchant registry: " + holders.size() + " modded enchantment(s) registered"
                + " (" + tagsBound + " tag(s) bound)"
                + (failed.isEmpty() ? "" : " (" + failed.size() + " failed: " + failed + ")"));
        return holders.size();
    }

    /** {@code RegistryOps.create(JsonOps, server registry access)}. */
    private static Object registryOps(Object jsonOps, Class<?> dynamicOps) throws Exception {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object access = server.getClass().getMethod("registryAccess").invoke(server);
        Class<?> registryOpsCls = Class.forName("net.minecraft.resources.RegistryOps");
        Class<?> provider = Class.forName("net.minecraft.core.HolderLookup$Provider");
        Method create = registryOpsCls.getMethod("create", dynamicOps, provider);
        return create.invoke(null, jsonOps, access);
    }

    /** {@code java -cp ... dev.cim.paper.EnchantRegistry --selftest}: the pure document reader. */
    public static void main(String[] args) {
        if (!Arrays.asList(args).contains("--selftest")) {
            System.err.println("usage: EnchantRegistry --selftest");
            System.exit(2);
        }
        String doc = "{\"enchantments\":[{\"id\":\"combat_roll:acrobat\",\"definition\":{\"max_level\":5,"
                + "\"effects\":{}}}],\"tags\":{\"minecraft:non_treasure\":[\"combat_roll:acrobat\"]},"
                + "\"dropped\":[\"combat_roll:x: effect y unknown\"]}";
        Document d = parse(doc);
        check(d.enchantments().size() == 1, "one enchantment");
        check(d.enchantments().get(0).id().equals("combat_roll:acrobat"), "id");
        check(d.enchantments().get(0).definition().getAsJsonObject().get("max_level").getAsInt() == 5, "max_level");
        check(d.tags().get("minecraft:non_treasure").equals(List.of("combat_roll:acrobat")), "tag member");
        check(parse("{}").enchantments().isEmpty() && parse("{}").tags().isEmpty(), "empty document");
        check(parse("{}").gate().isEmpty(), "no gate");
        Document g = parse("{\"enchantments\":[],\"tags\":{},\"gate\":{\"create:capacity\":[\"create:copper_backtank\"]}}");
        check(g.gate().get("create:capacity").equals(List.of("create:copper_backtank")), "gate row");
        Map<String, java.util.Set<String>> rules = Map.of("create:capacity", java.util.Set.of("create:copper_backtank"));
        check(EnchantGate.allowed(rules, "create:capacity", "create:copper_backtank"), "gated item allowed");
        check(!EnchantGate.allowed(rules, "create:capacity", null), "vanilla host refused");
        check(!EnchantGate.allowed(rules, "create:capacity", "create:andesite_alloy"), "other mod item refused");
        check(EnchantGate.allowed(rules, "minecraft:sharpness", null), "ungated enchantment untouched");
        String stack = "{\"id\":\"create:copper_backtank\",\"count\":1}";
        String withAir = EnchantGate.layerJson(stack, Map.of("create:capacity", 3));
        check(JsonParser.parseString(withAir).getAsJsonObject().getAsJsonObject("components")
                .getAsJsonObject("minecraft:enchantments").getAsJsonObject("levels")
                .get("create:capacity").getAsInt() == 3, "layer json carries the level");
        check(EnchantGate.layerJson(withAir, Map.of()).equals(stack), "stale enchantments removed");
        check(EnchantGate.layerJson(stack, Map.of()).equals(stack), "no enchantments leaves the json alone");
        System.out.println("enchant registry selftest ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("enchant registry selftest failed: " + what);
        }
    }
}
