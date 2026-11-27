package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps a mod item's 1.21.1 prototype (the {@code prototype} object the layer sends, see
 * {@code LiveProtocol.withPrototype}) to the 26.3 data components its paper carrier takes, so the
 * vanilla 26.3 client shows and uses the carrier like the mod item on NeoForge.
 *
 * <p>Pure: no server objects. {@link #map} returns the 26.3 component map as JSON (the codec form
 * {@code DataComponentPatch.CODEC} reads); {@link #apply} is the only server-side step, in
 * {@code LiveBridge.toPaper}.
 *
 * <p>Mapping (1.21.1 prototype to 26.3 component):
 * <ul>
 * <li>{@code minecraft:food} to {@code minecraft:food} (nutrition, saturation, can_always_eat) and
 *     {@code minecraft:consumable} (consume_seconds from eat_seconds, animation from use_animation, the
 *     effects as apply_effects). The effects are applied by the vanilla server when the eat finishes:
 *     the layer does not run eating on carriers today, so nothing is fed twice.</li>
 * <li>{@code minecraft:tool} to {@code minecraft:tool}. Rules naming only mod blocks or mod tags are
 *     dropped: break speed on mod blocks is the layer's (BreakSpeed), so only vanilla block rules and the
 *     default speed reach the carrier.</li>
 * <li>{@code minecraft:attribute_modifiers}: {@code minecraft:generic.X} becomes {@code minecraft:X};
 *     mod attributes are skipped.</li>
 * <li>{@code minecraft:fire_resistant} to {@code minecraft:damage_resistant} ({@code #minecraft:is_fire}).</li>
 * <li>{@code minecraft:rarity}, {@code minecraft:max_stack_size} as they are.</li>
 * <li>Equipable slot and sound to {@code minecraft:equippable} (no asset_id: the carrier has no worn model).</li>
 * <li>An ElytraItem to {@code minecraft:glider}.</li>
 * </ul>
 * Not mapped here: max_damage and enchantable (LayerStackData, already on the carrier); use_cooldown
 * (1.21.1 has no such component); the layer's own effects and damage (the layer stays the source).
 */
public final class CarrierComponents {

    private CarrierComponents() {
    }

    static final String FOOD = "minecraft:food";
    static final String TOOL = "minecraft:tool";
    static final String ATTRIBUTE_MODIFIERS = "minecraft:attribute_modifiers";
    static final String FIRE_RESISTANT = "minecraft:fire_resistant";
    static final String RARITY = "minecraft:rarity";
    static final String MAX_STACK_SIZE = "minecraft:max_stack_size";
    static final String EQUIP_SLOT = "equip_slot";
    static final String EQUIP_SOUND = "equip_sound";
    static final String GLIDER = "glider";
    static final String USE_ANIMATION = "use_animation";
    static final String GENERIC = "minecraft:generic.";

    /** The 26.3 component map (component id to codec JSON) for the layer's stack JSON; empty for a vanilla or plain item. */
    public static JsonObject map(String layerJson) {
        JsonObject root = object(layerJson);
        JsonObject proto = root.has(LayerStackData.PROTOTYPE) && root.get(LayerStackData.PROTOTYPE).isJsonObject()
                ? root.getAsJsonObject(LayerStackData.PROTOTYPE) : null;
        return proto == null ? new JsonObject() : map(proto);
    }

    /** The 26.3 component map for one prototype object. */
    public static JsonObject map(JsonObject proto) {
        JsonObject out = new JsonObject();
        JsonObject food = obj(proto, FOOD);
        if (food != null) {
            out.add(FOOD, food(food));
            out.add("minecraft:consumable", consumable(food, proto));
        }
        JsonObject tool = tool(obj(proto, TOOL));
        if (tool != null) {
            out.add(TOOL, tool);
        }
        JsonArray attributes = attributes(obj(proto, ATTRIBUTE_MODIFIERS));
        if (attributes != null) {
            out.add(ATTRIBUTE_MODIFIERS, attributes);
        }
        if (proto.has(FIRE_RESISTANT)) {
            JsonObject dr = new JsonObject();
            dr.addProperty("types", "#minecraft:is_fire");
            out.add("minecraft:damage_resistant", dr);
        }
        if (proto.has(RARITY) && proto.get(RARITY).isJsonPrimitive()
                && !"common".equals(proto.get(RARITY).getAsString())) {
            out.addProperty("minecraft:rarity", proto.get(RARITY).getAsString());
        }
        if (proto.has(MAX_STACK_SIZE) && proto.get(MAX_STACK_SIZE).isJsonPrimitive()) {
            out.addProperty(MAX_STACK_SIZE, proto.get(MAX_STACK_SIZE).getAsInt());
        }
        if (proto.has(EQUIP_SLOT) && proto.get(EQUIP_SLOT).isJsonPrimitive()) {
            JsonObject eq = new JsonObject();
            eq.addProperty("slot", proto.get(EQUIP_SLOT).getAsString());
            if (proto.has(EQUIP_SOUND) && proto.get(EQUIP_SOUND).isJsonPrimitive()) {
                eq.addProperty(EQUIP_SOUND, proto.get(EQUIP_SOUND).getAsString());
            }
            eq.addProperty("dispensable", true);
            out.add("minecraft:equippable", eq);
        }
        if (proto.has(GLIDER) && proto.get(GLIDER).getAsBoolean()) {
            out.add("minecraft:glider", new JsonObject());
        }
        return out;
    }

    /** minecraft:food from the 1.21.1 FoodProperties: nutrition, saturation, can_always_eat. */
    static JsonObject food(JsonObject f) {
        JsonObject out = new JsonObject();
        out.addProperty("nutrition", intOf(f, "nutrition", 0));
        out.addProperty("saturation", doubleOf(f, "saturation", 0));
        out.addProperty("can_always_eat", f.has("can_always_eat") && f.get("can_always_eat").getAsBoolean());
        return out;
    }

    /** minecraft:consumable: the eat time, the animation, and the food's effects as apply_effects entries. */
    static JsonObject consumable(JsonObject f, JsonObject proto) {
        JsonObject out = new JsonObject();
        out.addProperty("consume_seconds", doubleOf(f, "eat_seconds", 1.6));
        String animation = proto.has(USE_ANIMATION) && proto.get(USE_ANIMATION).isJsonPrimitive()
                ? proto.get(USE_ANIMATION).getAsString() : "eat";
        out.addProperty("animation", animation);
        out.addProperty("has_consume_particles", true);
        JsonArray effects = new JsonArray();
        if (f.has("effects") && f.get("effects").isJsonArray()) {
            for (JsonElement el : f.getAsJsonArray("effects")) {
                if (!el.isJsonObject() || !el.getAsJsonObject().has("effect")) {
                    continue;
                }
                JsonObject possible = el.getAsJsonObject();
                JsonObject apply = new JsonObject();
                apply.addProperty("type", "minecraft:apply_effects");
                JsonArray one = new JsonArray();
                one.add(possible.get("effect"));
                apply.add("effects", one);
                apply.addProperty("probability", doubleOf(possible, "probability", 1));
                effects.add(apply);
            }
        }
        out.add("on_consume_effects", effects);
        return out;
    }

    /**
     * minecraft:tool from the 1.21.1 Tool: rules whose blocks are vanilla (a vanilla id, a minecraft:
     * tag, or a list with some vanilla ids) keep those blocks; mod-only rules go. Null when there is no tool.
     */
    static JsonObject tool(JsonObject t) {
        if (t == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        JsonArray rules = new JsonArray();
        if (t.has("rules") && t.get("rules").isJsonArray()) {
            for (JsonElement el : t.getAsJsonArray("rules")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject rule = el.getAsJsonObject();
                JsonElement blocks = vanillaBlocks(rule.get("blocks"));
                if (blocks == null) {
                    continue;
                }
                JsonObject kept = new JsonObject();
                kept.add("blocks", blocks);
                if (rule.has("speed")) {
                    kept.add("speed", rule.get("speed"));
                }
                if (rule.has("correct_for_drops")) {
                    kept.add("correct_for_drops", rule.get("correct_for_drops"));
                }
                rules.add(kept);
            }
        }
        out.add("rules", rules);
        out.addProperty("default_mining_speed", doubleOf(t, "default_mining_speed", 1));
        out.addProperty("damage_per_block", intOf(t, "damage_per_block", 1));
        return out;
    }

    /** The vanilla part of a rule's blocks (a string, or a list), or null when none of it is vanilla. */
    static JsonElement vanillaBlocks(JsonElement blocks) {
        if (blocks == null || blocks.isJsonNull()) {
            return null;
        }
        if (blocks.isJsonPrimitive()) {
            return vanilla(blocks.getAsString()) ? blocks : null;
        }
        if (!blocks.isJsonArray()) {
            return null;
        }
        JsonArray kept = new JsonArray();
        for (JsonElement el : blocks.getAsJsonArray()) {
            if (el.isJsonPrimitive() && vanilla(el.getAsString())) {
                kept.add(el.getAsString());
            }
        }
        return kept.size() == 0 ? null : kept;
    }

    private static boolean vanilla(String id) {
        return id.startsWith("minecraft:") || id.startsWith("#minecraft:");
    }

    /** minecraft:attribute_modifiers: the minecraft:generic.X modifiers as minecraft:X; null when none is left. */
    static JsonArray attributes(JsonObject am) {
        if (am == null || !am.has("modifiers") || !am.get("modifiers").isJsonArray()) {
            return null;
        }
        JsonArray modifiers = new JsonArray();
        for (JsonElement el : am.getAsJsonArray("modifiers")) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject m = el.getAsJsonObject();
            String type = m.has("type") ? m.get("type").getAsString() : "";
            if (!type.startsWith(GENERIC)) {
                continue;
            }
            JsonObject out = new JsonObject();
            out.addProperty("type", "minecraft:" + type.substring(GENERIC.length()));
            for (String key : new String[] {"id", "amount", "operation", "slot"}) {
                if (m.has(key)) {
                    out.add(key, m.get(key));
                }
            }
            modifiers.add(out);
        }
        return modifiers.size() == 0 ? null : modifiers;
    }

    // ---- helpers ----

    private static JsonObject object(String json) {
        JsonElement el = JsonParser.parseString(json);
        return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static int intOf(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
    }

    private static double doubleOf(JsonObject o, String key, double fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallback;
    }

    // ---- selftest ----

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code CarrierComponents --selftest}: the pure mapping over hand-made 1.21.1 prototypes. */
    public static void main(String[] args) {
        if (!java.util.Arrays.asList(args).contains("--selftest")) {
            System.out.println("usage: CarrierComponents --selftest");
            System.exit(2);
        }
        int bad = 0;

        String chocolate = "{\"id\":\"create:bar_of_chocolate\",\"count\":1,\"prototype\":{"
                + "\"minecraft:food\":{\"nutrition\":4,\"saturation\":0.6,\"can_always_eat\":true,\"eat_seconds\":2.0,"
                + "\"effects\":[{\"effect\":{\"id\":\"minecraft:speed\",\"amplifier\":0,\"duration\":200},\"probability\":0.5}]},"
                + "\"use_animation\":\"eat\",\"minecraft:rarity\":\"rare\"}}";
        JsonObject food = map(chocolate);
        bad += check(food.has(FOOD) && food.getAsJsonObject(FOOD).get("nutrition").getAsInt() == 4, "food: nutrition carried");
        bad += check(food.getAsJsonObject(FOOD).get("can_always_eat").getAsBoolean(), "food: can_always_eat carried");
        JsonObject cons = food.getAsJsonObject("minecraft:consumable");
        bad += check(cons != null && cons.get("consume_seconds").getAsDouble() == 2.0, "consumable: consume_seconds from eat_seconds");
        bad += check(cons != null && "eat".equals(cons.get("animation").getAsString()), "consumable: animation from use_animation");
        JsonArray effs = cons.getAsJsonArray("on_consume_effects");
        bad += check(effs.size() == 1 && "minecraft:apply_effects".equals(effs.get(0).getAsJsonObject().get("type").getAsString())
                && effs.get(0).getAsJsonObject().getAsJsonArray("effects").size() == 1
                && effs.get(0).getAsJsonObject().get("probability").getAsDouble() == 0.5, "consumable: effect as apply_effects with probability");
        bad += check("rare".equals(map(chocolate).get("minecraft:rarity") == null ? null
                : map(chocolate).get("minecraft:rarity").getAsString()), "rarity: rare carried");

        String plain = "{\"id\":\"create:wrench\",\"count\":1,\"prototype\":{\"minecraft:rarity\":\"common\"}}";
        bad += check(map(plain).size() == 0, "common rarity and no other prototype: no components");
        bad += check(map("{\"id\":\"create:wrench\",\"count\":1}").size() == 0, "no prototype object: empty map");
        bad += check(map("{\"id\":\"minecraft:stick\",\"count\":1}").size() == 0, "vanilla bare stack: empty map");

        String pick = "{\"id\":\"create:cardboard_sword\",\"count\":1,\"prototype\":{"
                + "\"minecraft:tool\":{\"rules\":["
                + "{\"blocks\":\"#minecraft:mineable/pickaxe\",\"speed\":4.0,\"correct_for_drops\":true},"
                + "{\"blocks\":\"#create:brittle\",\"speed\":9.0,\"correct_for_drops\":true},"
                + "{\"blocks\":[\"minecraft:stone\",\"create:depot\"],\"speed\":6.0}],"
                + "\"default_mining_speed\":1.5,\"damage_per_block\":2},"
                + "\"minecraft:fire_resistant\":{}}}";
        JsonObject pk = map(pick);
        JsonObject tool = pk.getAsJsonObject(TOOL);
        bad += check(tool != null && tool.getAsJsonArray("rules").size() == 2, "tool: mod-only tag rule dropped, vanilla kept");
        bad += check(tool != null && tool.get("default_mining_speed").getAsDouble() == 1.5 && tool.get("damage_per_block").getAsInt() == 2,
                "tool: default speed and damage per block carried");
        JsonArray mixed = tool.getAsJsonArray("rules").get(1).getAsJsonObject().getAsJsonArray("blocks");
        bad += check(mixed.size() == 1 && "minecraft:stone".equals(mixed.get(0).getAsString()), "tool: mixed list keeps vanilla ids only");
        bad += check(pk.has("minecraft:damage_resistant") && "#minecraft:is_fire".equals(
                pk.getAsJsonObject("minecraft:damage_resistant").get("types").getAsString()), "fire_resistant: damage_resistant is_fire");

        String armour = "{\"id\":\"x:helm\",\"count\":1,\"prototype\":{\"minecraft:max_stack_size\":1,\"equip_slot\":\"head\","
                + "\"equip_sound\":\"minecraft:item.armor.equip_iron\","
                + "\"minecraft:attribute_modifiers\":{\"modifiers\":["
                + "{\"type\":\"minecraft:generic.armor\",\"id\":\"minecraft:armor.head\",\"amount\":3,\"operation\":\"add_value\",\"slot\":\"head\"},"
                + "{\"type\":\"minecraft:generic.armor_toughness\",\"id\":\"minecraft:armor.head\",\"amount\":1,\"operation\":\"add_value\",\"slot\":\"head\"},"
                + "{\"type\":\"x:mod_thing\",\"id\":\"x:m\",\"amount\":5,\"operation\":\"add_value\",\"slot\":\"head\"}"
                + "],\"show_in_tooltip\":true}}}";
        JsonObject ar = map(armour);
        JsonObject eq = ar.getAsJsonObject("minecraft:equippable");
        bad += check(eq != null && "head".equals(eq.get("slot").getAsString())
                && "minecraft:item.armor.equip_iron".equals(eq.get("equip_sound").getAsString())
                && !eq.has("asset_id"), "equippable: slot and sound, no asset");
        JsonArray attrs = ar.getAsJsonArray(ATTRIBUTE_MODIFIERS);
        bad += check(attrs != null && attrs.size() == 2 && "minecraft:armor".equals(attrs.get(0).getAsJsonObject().get("type").getAsString())
                && "minecraft:armor_toughness".equals(attrs.get(1).getAsJsonObject().get("type").getAsString()),
                "attributes: generic.X to X, mod attribute skipped");
        bad += check(ar.get(MAX_STACK_SIZE).getAsInt() == 1, "max_stack_size carried");

        String elytra = "{\"id\":\"x:wings\",\"count\":1,\"prototype\":{\"equip_slot\":\"chest\",\"glider\":true}}";
        JsonObject el = map(elytra);
        bad += check(el.has("minecraft:glider") && "chest".equals(el.getAsJsonObject("minecraft:equippable").get("slot").getAsString()),
                "elytra-like: glider and chest equippable");

        String stripped = LayerStackData.stripPrototype(armour);
        bad += check(!stripped.contains("prototype") && LayerStackData.prototypeOf(armour) != null
                && LayerStackData.prototypeOf(stripped) == null, "stripPrototype drops the prototype object (kept off the carrier)");

        System.out.println(bad == 0 ? "CarrierComponents selftest: ALL PASS" : "CarrierComponents selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
