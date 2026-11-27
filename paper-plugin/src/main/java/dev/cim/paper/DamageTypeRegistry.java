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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registers the modded damage types (Create, Biomes O' Plenty, Dynamic Trees) as
 * REAL entries of Paper's server damage type registry, the same way
 * {@link EnchantRegistry} does for enchantments.
 *
 * <p>The definitions come from the Rust module ({@code crates/cim-damage}: the
 * mod jars' {@code data/<ns>/damage_type/*.json}), written to
 * {@code <root>/c-imagine/damage-defs.json}. The document is
 * {@code {"damage_types":[{"id","definition"}], "dropped":[...]}}.
 *
 * <p>Registration happens at enable, before the world loads: the lowered
 * {@code MappedRegistry.frozen} window, {@code DamageType.DIRECT_CODEC} decoding
 * (no registry references in the definition, so plain JSON ops), and the holder
 * bound by hand. Entries whose id the registry already has (vanilla) are skipped.
 * The client learns the entries through the normal configuration-phase registry
 * sync; their death messages come from the pack lang
 * ({@code death.attack.<message_id>}, see {@code F2vPack}).
 */
public final class DamageTypeRegistry {

    private DamageTypeRegistry() {
    }

    /** One damage type definition read from the document. */
    record Entry(String id, JsonElement definition) {
    }

    /** The parsed document: damage types in file order. */
    record Document(List<Entry> damageTypes) {
    }

    /**
     * Parse {@code damage-defs.json}. Pure: no server objects, so it runs in the
     * selftest without Paper.
     */
    static Document parse(String raw) {
        JsonObject doc = JsonParser.parseString(raw).getAsJsonObject();
        List<Entry> entries = new ArrayList<>();
        JsonArray defs = doc.has("damage_types") ? doc.getAsJsonArray("damage_types") : new JsonArray();
        for (JsonElement element : defs) {
            JsonObject def = element.getAsJsonObject();
            entries.add(new Entry(def.get("id").getAsString(), def.get("definition")));
        }
        return new Document(entries);
    }

    /**
     * @return the number of modded damage types registered (0 when the module
     *     wrote no definitions, e.g. a plain Paper boot without the module).
     */
    static int install(Path root, Logger log) {
        Path defsPath = root.resolve("c-imagine").resolve("damage-defs.json");
        if (!Files.isRegularFile(defsPath)) {
            return 0;
        }
        try {
            String raw = Files.readString(defsPath, StandardCharsets.UTF_8);
            Document doc = parse(raw);
            if (doc.damageTypes().isEmpty()) {
                return 0;
            }
            return register(doc, log);
        } catch (Throwable t) {
            log.log(Level.WARNING, CimPlugin.LOG_PREFIX + "damage registry: install failed", t);
            return 0;
        }
    }

    private static int register(Document doc, Logger log) throws Exception {
        Class<?> craftRegistry = Class.forName("org.bukkit.craftbukkit.CraftRegistry");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        Object damageTypeKey = registries.getField("DAMAGE_TYPE").get(null);
        Method getNms = craftRegistry.getMethod("getMinecraftRegistry", resourceKey);
        Object registry = getNms.invoke(null, damageTypeKey);
        if (registry == null) {
            log.warning(CimPlugin.LOG_PREFIX + "damage registry: no NMS damage type registry");
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

        Class<?> damageCls = Class.forName("net.minecraft.world.damagesource.DamageType");
        Object codec = damageCls.getField("DIRECT_CODEC").get(null);
        Class<?> codecCls = Class.forName("com.mojang.serialization.Codec");
        Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
        Class<?> jsonOps = Class.forName("com.mojang.serialization.JsonOps");
        Object ops = jsonOps.getField("INSTANCE").get(null);
        Method parse = codecCls.getMethod("parse", dynamicOps, Object.class);

        Class<?> holderRef = Class.forName("net.minecraft.core.Holder$Reference");
        Method bindValue = holderRef.getDeclaredMethod("bindValue", Object.class);
        bindValue.setAccessible(true);
        Method bindHolderTags = holderRef.getDeclaredMethod("bindTags", java.util.Collection.class);
        bindHolderTags.setAccessible(true);

        int registered = 0;
        int skipped = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (Entry entry : doc.damageTypes()) {
                try {
                    Object identifier = idParse.invoke(null, entry.id());
                    if (Boolean.TRUE.equals(containsKey.invoke(registry, identifier))) {
                        skipped++;
                        continue;
                    }
                    Object key = createKey.invoke(null, damageTypeKey, identifier);
                    Object result = parse.invoke(codec, ops, entry.definition());
                    Object value = result.getClass().getMethod("getOrThrow").invoke(result);
                    Object holder = register.invoke(registry, key, value, builtIn);
                    bindValue.invoke(holder, value);
                    bindHolderTags.invoke(holder, List.of());
                    registered++;
                } catch (Throwable t) {
                    failed.add(entry.id() + ":" + t.getCause());
                }
            }
        } finally {
            // Restore the flag the registry had: freeze() is never called (it
            // refuses an already-frozen registry, see BiomeRegistry).
            frozen.setBoolean(registry, wasFrozen);
        }
        log.info(CimPlugin.LOG_PREFIX + "damage registry: " + registered + " modded damage type(s) registered"
                + (skipped == 0 ? "" : " (" + skipped + " already present)")
                + (failed.isEmpty() ? "" : " (" + failed.size() + " failed: " + failed + ")"));
        return registered;
    }

    /** {@code java -cp ... dev.cim.paper.DamageTypeRegistry --selftest}: the pure document reader. */
    public static void main(String[] args) {
        if (!Arrays.asList(args).contains("--selftest")) {
            System.err.println("usage: DamageTypeRegistry --selftest");
            System.exit(2);
        }
        String doc = "{\"damage_types\":[{\"id\":\"create:mechanical_saw\",\"definition\":{"
                + "\"message_id\":\"create.mechanical_saw\",\"scaling\":\"always\",\"exhaustion\":0.0}}],"
                + "\"dropped\":[\"x: effects sparkle unknown\"]}";
        Document d = parse(doc);
        check(d.damageTypes().size() == 1, "one damage type");
        check(d.damageTypes().get(0).id().equals("create:mechanical_saw"), "id");
        check(d.damageTypes().get(0).definition().getAsJsonObject().get("message_id").getAsString()
                .equals("create.mechanical_saw"), "message_id");
        check(parse("{}").damageTypes().isEmpty(), "empty document");
        System.out.println("damage registry selftest ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("damage registry selftest failed: " + what);
        }
    }
}
