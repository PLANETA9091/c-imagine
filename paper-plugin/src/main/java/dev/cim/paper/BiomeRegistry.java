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
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Registers the modded biome definitions as REAL entries of Paper's server
 * biome registry.
 *
 * <p>The definitions come from the Rust module ({@code crates/cim-biome}: the
 * 1.21.1 biome JSON → 26.3 definition), written to
 * {@code <root>/c-imagine/biome-defs.json}. Registering them server-side (not
 * only on the wire) buys the whole surface at once: the biome id is a real
 * registry entry, chunk biome palettes can carry it, {@code /locate biome}
 * suggests it, and the client's dynamic registry receives it through the
 * normal configuration sync.
 *
 * <p>Paper 26.3's registry API ({@code RegistryEvents}) covers game events,
 * enchantments and the other small registries — not biomes. So this goes
 * through NMS: {@code CraftRegistry.getMinecraftRegistry(Registries.BIOME)}
 * returns the live {@code MappedRegistry}, whose {@code frozen} flag is
 * lowered for the registration window and re-frozen afterwards (the same
 * shape datapack loading uses, just driven by our definitions).
 */
final class BiomeRegistry {

    private BiomeRegistry() {
    }

    /**
     * @return the number of biomes registered (0 when the module wrote no
     *     definitions, e.g. a plain Paper boot without the module).
     */
    static int install(Path root, Logger log) {
        Path defsPath = root.resolve("c-imagine").resolve("biome-defs.json");
        if (!Files.isRegularFile(defsPath)) {
            return 0;
        }
        try {
            String raw = Files.readString(defsPath, StandardCharsets.UTF_8);
            JsonArray defs = JsonParser.parseString(raw).getAsJsonArray();
            if (defs.isEmpty()) {
                return 0;
            }
            return register(defs, root, log);
        } catch (Throwable t) {
            log.log(java.util.logging.Level.WARNING,
                    CimPlugin.LOG_PREFIX + "biome registry: install failed", t);
            return 0;
        }
    }

    private static int register(JsonArray defs, Path root, Logger log) throws Exception {
        Class<?> craftRegistry = Class.forName("org.bukkit.craftbukkit.CraftRegistry");
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        Object biomeKey = registries.getField("BIOME").get(null);
        Method getNms = craftRegistry.getMethod("getMinecraftRegistry", resourceKey);
        Object registry = getNms.invoke(null, biomeKey);
        if (registry == null) {
            log.warning(CimPlugin.LOG_PREFIX + "biome registry: no NMS biome registry");
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
        Method register = mapped.getMethod("register", resourceKey, Object.class, registrationInfo);

        Class<?> biomeCls = Class.forName("net.minecraft.world.level.biome.Biome");
        Object codec = biomeCls.getField("DIRECT_CODEC").get(null);
        Class<?> jsonOps = Class.forName("com.mojang.serialization.JsonOps");
        Object ops = jsonOps.getField("INSTANCE").get(null);
        Class<?> dynamicOps = Class.forName("com.mojang.serialization.DynamicOps");
        Method parse = codec.getClass().getMethod("parse", dynamicOps, Object.class);

        Class<?> registryCls = Class.forName("net.minecraft.core.Registry");
        Method containsKey = registryCls.getMethod("containsKey", identifierCls);
        // register() creates a STAND-ALONE (unbound) holder: freeze() is what
        // normally binds it, and freeze() refuses on an already-frozen
        // registry ("Tags already present before freezing"). Binding the
        // holder ourselves is the narrow, side-effect-free equivalent — an
        // unbound holder makes RegistrySynchronization throw for every client.
        Class<?> holderRef = Class.forName("net.minecraft.core.Holder$Reference");
        Method bindValue = holderRef.getDeclaredMethod("bindValue", Object.class);
        bindValue.setAccessible(true);
        Class<?> componentMap = Class.forName("net.minecraft.core.component.DataComponentMap");
        Object emptyComponents = componentMap.getField("EMPTY").get(null);
        Method bindComponents = holderRef.getMethod("bindComponents", componentMap);
        // A holder with unbound tags throws inside HolderSet.contains/freeze-time
        // variant checks (measured: "Tags not bound" during spawnOriginalMobs on
        // chicken variant selection). Empty tags are correct: the modded biomes
        // carry no datapack tags.
        Method bindTags = holderRef.getDeclaredMethod("bindTags", java.util.Collection.class);
        bindTags.setAccessible(true);

        int added = 0;
        List<String> failed = new ArrayList<>();
        try {
            for (JsonElement element : defs) {
                JsonObject def = element.getAsJsonObject();
                String id = def.get("id").getAsString();
                JsonElement definition = def.get("definition");
                try {
                    Object identifier = idParse.invoke(null, id);
                    if (Boolean.TRUE.equals(containsKey.invoke(registry, identifier))) {
                        continue;
                    }
                    Object result = parse.invoke(codec, ops, definition);
                    Object biome = result.getClass().getMethod("getOrThrow").invoke(result);
                    Object key = createKey.invoke(null, biomeKey, identifier);
                    Object holder = register.invoke(registry, key, biome, builtIn);
                    bindValue.invoke(holder, biome);
                    bindComponents.invoke(holder, emptyComponents);
                    bindTags.invoke(holder, java.util.List.of());
                    added++;
                } catch (Throwable t) {
                    failed.add(id + ":" + t.getCause());
                }
            }
        } finally {
            // Restore the flag only: freeze() rebuilds the tag state and
            // throws "Tags already present before freezing" on a registry
            // that was frozen once. register() already appended to
            // byId/byLocation/byKey/byValue, which is all lookups need.
            frozen.setBoolean(registry, wasFrozen || true);
        }
        log.info(CimPlugin.LOG_PREFIX + "biome registry: " + added + " modded biome(s) registered"
                + (failed.isEmpty() ? "" : " (" + failed.size() + " failed: " + failed + ")"));
        if (added > 0) {
            // The module's wire overlay stands down when this marker exists:
            // the server already sends these entries, appending them again
            // would duplicate ids on the client.
            Path marker = root.resolve("c-imagine").resolve("biome-registry.ok");
            Files.writeString(marker, "registered=" + added + "\n", StandardCharsets.UTF_8);
        }
        return added;
    }
}
