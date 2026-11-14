package dev.cim.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.plugin.Plugin;

/**
 * The layer's mod crafting recipes on Paper (shaped, shapeless, smelting, blasting, smoking and
 * campfire), registered once at boot under the {@code cim} namespace, the mod id and path as the key.
 *
 * <p>The layer answers with the recipes its real server loaded ({@code recipes} in cimlive's
 * protocol). A background thread waits for that answer and does not touch Bukkit; the recipes
 * are built and registered on the main thread. A recipe is expressed only when every ingredient
 * maps to a Paper choice: a vanilla item is a {@link RecipeChoice.MaterialChoice}, a mod item is an
 * {@link RecipeChoice.ExactChoice} of its carrier stack, which is how a player's held mod item is
 * built ({@link LiveBridge#toPaper}). Results are the full mod item, as the players receive it.
 */
public final class LiveRecipes {

    private static final long WAIT_MS = 600_000L;
    private static final long POLL_MS = 2_000L;
    private static final String NAMESPACE = "cim";
    private static final char[] SLOTS = "ABCDEFGHI".toCharArray();

    /** The keys registered at boot, for the recipe book of each player who joins. */
    private static volatile List<NamespacedKey> registered = List.of();

    private LiveRecipes() {
    }

    /** Starts the boot export in the background; the main thread registers when the layer answers. */
    static void start(Plugin plugin, Supplier<LiveBridge> bridge, Function<String, ItemStack> carrier) {
        Thread thread = new Thread(() -> fetch(plugin, bridge, carrier), "cim-recipes");
        thread.setDaemon(true);
        thread.start();
    }

    /** Unlocks the registered mod recipes in a joining player's recipe book. */
    public static void discover(Player player) {
        List<NamespacedKey> keys = registered;
        if (!keys.isEmpty()) {
            player.discoverRecipes(keys);
        }
    }

    private static void fetch(Plugin plugin, Supplier<LiveBridge> bridge, Function<String, ItemStack> carrier) {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        String answer = null;
        boolean layerSeen = false;
        while (answer == null && System.currentTimeMillis() < deadline) {
            Function<String, String> layer = LiveBridge.entry();
            if (layer != null) {
                layerSeen = true;
                String said = layer.apply("recipes");
                if (!said.startsWith("wait")) {
                    answer = said;
                }
            }
            if (answer == null) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        if (answer == null) {
            // Plain Paper (no layer) is the normal case: only a layer that never answered is news.
            if (layerSeen) {
                plugin.getLogger().warning("[c-imagine] recipes: the layer had no recipes after " + WAIT_MS / 1000 + "s");
            }
            return;
        }
        if (answer.startsWith("error")) {
            plugin.getLogger().warning("[c-imagine] recipes: the layer answered " + answer.split("\n")[0]);
            return;
        }
        String text = answer;
        plugin.getServer().getScheduler().runTask(plugin, () -> register(plugin, bridge.get(), carrier, text));
    }

    /** A recipe that cannot be expressed on Paper, with the reason it was skipped. */
    private static final class Skip extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Skip(String reason) {
            super(reason, null, false, false);
        }
    }

    private static void register(Plugin plugin, LiveBridge bridge, Function<String, ItemStack> carrier, String text) {
        if (bridge == null) {
            plugin.getLogger().warning("[c-imagine] recipes: no live bridge, no recipes registered");
            return;
        }
        List<NamespacedKey> keys = new ArrayList<>();
        Map<String, Integer> skipped = new TreeMap<>();
        int ok = 0;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t");
            if (f[0].equals("skip")) {
                skipped.merge(f[1], 1, Integer::sum);
                continue;
            }
            if (!f[0].equals("recipe") || f.length < 3) {
                continue;
            }
            String kind = f[1];
            // One bad recipe is a skip, never the end of the batch.
            try {
                NamespacedKey key = keyOf(f[2]);
                Recipe recipe = build(bridge, carrier, key, f);
                if (!Bukkit.addRecipe(recipe)) {
                    throw new Skip("rejected");
                }
                keys.add(key);
                ok++;
            } catch (RuntimeException e) {
                String reason = e instanceof Skip || e instanceof IllegalArgumentException
                        ? String.valueOf(e.getMessage()) : "failed " + e.getClass().getSimpleName();
                skipped.merge(kind + " (" + reason + ")", 1, Integer::sum);
            }
        }
        registered = List.copyOf(keys);
        int total = skipped.values().stream().mapToInt(Integer::intValue).sum();
        String by = skipped.isEmpty() ? "none" : skipped.toString().replaceAll("[{}]", "");
        plugin.getLogger().info("[c-imagine] recipes: registered " + ok + ", skipped " + total + " (by type: " + by + ")");
    }

    /** The mod's recipe key: {@code cim:<mod id>/<path>}. */
    static NamespacedKey keyOf(String id) {
        int colon = id.indexOf(':');
        String mod = colon < 0 ? "mod" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        return new NamespacedKey(NAMESPACE, mod + "/" + path);
    }

    private static Recipe build(LiveBridge bridge, Function<String, ItemStack> carrier, NamespacedKey key, String[] f) {
        String kind = f[1];
        switch (kind) {
            case "shaped": {
                int w = Integer.parseInt(f[4]);
                int h = Integer.parseInt(f[5]);
                if (f.length != 6 + w * h || w < 1 || h < 1 || w > 3 || h > 3) {
                    throw new Skip("malformed");
                }
                ItemStack result = result(bridge, f[3]);
                ShapedRecipe recipe = new ShapedRecipe(key, result);
                Map<String, Character> letters = new TreeMap<>();
                Map<String, RecipeChoice> choices = new TreeMap<>();
                String[] rows = new String[h];
                for (int r = 0; r < h; r++) {
                    StringBuilder row = new StringBuilder();
                    for (int c = 0; c < w; c++) {
                        String token = f[6 + r * w + c];
                        if (token.equals("-")) {
                            row.append(' ');
                            continue;
                        }
                        Character letter = letters.get(token);
                        if (letter == null) {
                            if (letters.size() >= SLOTS.length) {
                                throw new Skip("malformed");
                            }
                            letter = SLOTS[letters.size()];
                            letters.put(token, letter);
                            choices.put(token, choice(bridge, carrier, token));
                        }
                        row.append(letter.charValue());
                    }
                    rows[r] = row.toString();
                }
                recipe.shape(rows);
                for (Map.Entry<String, Character> e : letters.entrySet()) {
                    recipe.setIngredient(e.getValue(), choices.get(e.getKey()));
                }
                return recipe;
            }
            case "shapeless": {
                ShapelessRecipe recipe = new ShapelessRecipe(key, result(bridge, f[3]));
                for (int i = 4; i < f.length; i++) {
                    RecipeChoice c = choice(bridge, carrier, f[i]);
                    if (c == null) {
                        throw new Skip("empty ingredient");
                    }
                    recipe.addIngredient(c);
                }
                return recipe;
            }
            case "smelting":
            case "blasting":
            case "smoking":
            case "campfire": {
                if (f.length != 7) {
                    throw new Skip("malformed");
                }
                ItemStack result = result(bridge, f[3]);
                int ticks = Integer.parseInt(f[4]);
                float xp = Float.parseFloat(f[5]);
                RecipeChoice input = choice(bridge, carrier, f[6]);
                if (input == null) {
                    throw new Skip("no input");
                }
                return switch (kind) {
                    case "smelting" -> new FurnaceRecipe(key, result, input, xp, ticks);
                    case "blasting" -> new BlastingRecipe(key, result, input, xp, ticks);
                    case "smoking" -> new SmokingRecipe(key, result, input, xp, ticks);
                    default -> new CampfireRecipe(key, result, input, xp, ticks);
                };
            }
            default:
                throw new Skip("unknown kind");
        }
    }

    /** The result as the players get it: a vanilla item, or a mod item's carrier with its data. */
    private static ItemStack result(LiveBridge bridge, String json) {
        ItemStack stack = bridge.toPaper(json);
        if (stack == null || stack.getType().isAir()) {
            throw new Skip("no result");
        }
        return stack;
    }

    /** One ingredient token as a Paper choice; null for an empty slot. */
    private static RecipeChoice choice(LiveBridge bridge, Function<String, ItemStack> carrier, String token) {
        if (token.equals("-")) {
            return null;
        }
        if (token.equals("?")) {
            throw new Skip("no items");
        }
        if (token.startsWith("!")) {
            throw new Skip("components");
        }
        List<Material> materials = new ArrayList<>();
        List<ItemStack> stacks = new ArrayList<>();
        boolean vanilla = true;
        for (String id : token.split(",")) {
            if (id.startsWith("minecraft:")) {
                Material m = Material.matchMaterial(id);
                if (m == null || m.isAir()) {
                    throw new Skip("unknown item");
                }
                materials.add(m);
                stacks.add(new ItemStack(m));
            } else {
                vanilla = false;
                ItemStack carried = carrier.apply(id);
                if (carried == null || carried.getType().isAir()) {
                    throw new Skip("no carrier");
                }
                stacks.add(carried);
            }
        }
        return vanilla ? new RecipeChoice.MaterialChoice(materials) : RecipeChoice.exactChoice(stacks);
    }
}
