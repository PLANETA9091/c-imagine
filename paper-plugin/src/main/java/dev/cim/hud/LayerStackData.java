package dev.cim.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The layer's stack JSON, read and rewritten for the Paper side. Pure: no server, so the selftest
 * runs it on its own.
 *
 * <p>The layer writes a stack as its {@code ItemStack} codec does ({@code id}, {@code count}, and
 * {@code components} holding only what differs from the prototype), plus two top-level keys for a
 * mod item the prototype defines: {@code max_damage} (its durability, present when the item is
 * damageable) and {@code enchantable} (its enchantment value, present when above 0). The prototype
 * values are not in the components, so the layer has to send them; the Paper carrier takes them as
 * its {@code minecraft:max_damage} and {@code minecraft:enchantable} components.
 */
public final class LayerStackData {

    private LayerStackData() {
    }

    /** The top-level keys the layer adds for a mod item's prototype; the layer's codec does not read them. */
    static final String MAX_DAMAGE = "max_damage";
    static final String ENCHANTABLE = "enchantable";
    static final String DAMAGE = "minecraft:damage";
    static final String ENCHANTMENTS = "minecraft:enchantments";

    private static JsonObject object(String json) {
        JsonElement el = JsonParser.parseString(json);
        return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject components(JsonObject obj) {
        return obj.has("components") && obj.get("components").isJsonObject() ? obj.getAsJsonObject("components") : null;
    }

    /** The stack's damage: the {@code minecraft:damage} component, 0 when absent. */
    public static int damageOf(String json) {
        JsonObject comps = components(object(json));
        return comps != null && comps.has(DAMAGE) ? comps.get(DAMAGE).getAsInt() : 0;
    }

    /** The prototype's max damage (the top-level {@code max_damage}), 0 when the item is not damageable. */
    public static int maxDamageOf(String json) {
        JsonObject obj = object(json);
        return obj.has(MAX_DAMAGE) ? obj.get(MAX_DAMAGE).getAsInt() : 0;
    }

    /** The prototype's enchantment value, or null when the layer sent none (the item is not enchantable). */
    public static Integer enchantableOf(String json) {
        JsonObject obj = object(json);
        return obj.has(ENCHANTABLE) ? obj.get(ENCHANTABLE).getAsInt() : null;
    }

    /** The enchantments the stack carries: id to level, from {@code minecraft:enchantments.levels}. */
    public static Map<String, Integer> enchantmentsOf(String json) {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject comps = components(object(json));
        if (comps == null || !comps.has(ENCHANTMENTS) || !comps.get(ENCHANTMENTS).isJsonObject()) {
            return out;
        }
        JsonObject component = comps.getAsJsonObject(ENCHANTMENTS);
        if (component.has("levels") && component.get("levels").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : component.getAsJsonObject("levels").entrySet()) {
                out.put(e.getKey(), e.getValue().getAsInt());
            }
        }
        return out;
    }

    /** The stack JSON with its {@code minecraft:damage} set to {@code damage}; 0 removes the component. */
    public static String withDamage(String json, int damage) {
        JsonObject obj = object(json);
        JsonObject comps = components(obj);
        if (damage <= 0) {
            if (comps != null) {
                comps.remove(DAMAGE);
                if (comps.size() == 0) {
                    obj.remove("components");
                }
            }
            return obj.toString();
        }
        if (comps == null) {
            comps = new JsonObject();
            obj.add("components", comps);
        }
        comps.addProperty(DAMAGE, damage);
        return obj.toString();
    }

    /** The stack JSON without the prototype keys the layer's codec would not use (what Paper keeps is the layer's own data). */
    public static String stripPrototype(String json) {
        JsonObject obj = object(json);
        if (!obj.has(MAX_DAMAGE) && !obj.has(ENCHANTABLE)) {
            return json;
        }
        obj.remove(MAX_DAMAGE);
        obj.remove(ENCHANTABLE);
        return obj.toString();
    }

    // ---- selftest ----

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code LayerStackData --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        String pick = "{\"id\":\"create:cardboard_sword\",\"count\":1,\"components\":{\"minecraft:damage\":7},\"max_damage\":59}";
        bad += check(damageOf(pick) == 7, "the damage component reads as the layer's damage");
        bad += check(maxDamageOf(pick) == 59, "the prototype max reads from the top-level key");
        bad += check(damageOf("{\"id\":\"create:wrench\",\"count\":1}") == 0, "no components: damage 0");
        bad += check(maxDamageOf("{\"id\":\"create:wrench\",\"count\":1}") == 0, "no max_damage key: not damageable");
        bad += check(enchantableOf("{\"id\":\"x:y\",\"count\":1}") == null, "no enchantable key: not enchantable");
        bad += check(enchantableOf("{\"id\":\"x:y\",\"count\":1,\"enchantable\":14}") == 14, "enchantable reads its value");
        String hurt = withDamage(pick, 8);
        bad += check(damageOf(hurt) == 8 && maxDamageOf(hurt) == 59, "withDamage sets the damage and keeps the max");
        String healed = withDamage(hurt, 0);
        bad += check(damageOf(healed) == 0 && !healed.contains("minecraft:damage"), "damage 0 drops the component");
        bad += check(!withDamage("{\"id\":\"a:b\",\"count\":1}", 0).contains("components"),
                "damage 0 on a bare stack adds no components");
        String stripped = stripPrototype(pick);
        bad += check(!stripped.contains("max_damage") && damageOf(stripped) == 7, "stripPrototype drops max_damage, keeps damage");
        bad += check(stripPrototype("{\"id\":\"a:b\",\"count\":1}").equals("{\"id\":\"a:b\",\"count\":1}"),
                "stripPrototype leaves a bare stack as it is");
        String enchanted = "{\"id\":\"create:cardboard_sword\",\"count\":1,\"components\":{\"minecraft:enchantments\":"
                + "{\"levels\":{\"create:capacity\":2,\"minecraft:sharpness\":3}}},\"enchantable\":15}";
        Map<String, Integer> levels = enchantmentsOf(enchanted);
        bad += check(levels.size() == 2 && levels.get("create:capacity") == 2 && levels.get("minecraft:sharpness") == 3,
                "enchantmentsOf reads id to level");
        bad += check(enchantmentsOf(pick).isEmpty(), "no enchantments component: empty map");
        bad += check(enchantableOf(enchanted) == 15, "enchantable beside enchantments");
        bad += check(damageOf("{\"id\":\"a:b\",\"count\":1,\"components\":{}}") == 0, "an empty components object: damage 0");
        System.out.println(bad == 0 ? "LayerStackData selftest: ALL PASS" : "LayerStackData selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
