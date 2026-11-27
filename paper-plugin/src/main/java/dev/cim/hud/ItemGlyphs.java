package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item icons as free-font glyphs. A bitmap provider can name a texture the
 * client already has, so nothing is shipped: {@code minecraft:item/diamond.png}
 * is read from the client's own assets. An item with no flat icon uses its block
 * texture, which is a face, not the isometric icon. Animated strips are left out
 * because one glyph would squash them. Plane 15 gives every id a codepoint outside
 * the BMP's limited private range.
 *
 * <p>Scale buckets: a 3D GuiGameElement drawn at scale {@code s} is {@code s} times
 * bigger than its slot icon. The free font has the same texture at 2x and 3x as
 * further bitmap providers (height 32 and 48, same ascent so the top-left stays put),
 * on plane 16, two codepoints per id. Scales above 3 draw at 3x: the cap keeps the
 * provider count at three per id.
 */
public final class ItemGlyphs {

    private static final int BASE = 0xF0000;
    /** Modded ids start here, so a different modded set can never shift vanilla icons. */
    private static final int MODDED_BASE = 0xF8000;
    /** Plane 16: the scaled glyphs of every id, numbered by the id's order (see {@link #ordinals}). */
    private static final int SCALED_BASE = 0x100000;
    private static final int ICON = 16;
    /** The largest scale bucket (3x is 48 px); buckets 2 .. MAX_BUCKET have their own codepoints. */
    public static final int MAX_BUCKET = 3;

    private final Map<String, Integer> codepoints = new HashMap<>();
    private final Map<String, Integer> ordinals = new HashMap<>();
    private final Map<String, String> files = new TreeMap<>();
    private final Map<String, Integer> advances = new HashMap<>();

    /**
     * @param moddedTextures modded item id to "texture, tab, advance"; the file is known to exist. The
     *                       client refuses a whole font over one missing file.
     */
    public ItemGlyphs(Map<String, String> moddedTextures) throws IOException {
        try (InputStream in = ItemGlyphs.class.getResourceAsStream("/hud/items-26.3.txt")) {
            if (in == null) {
                throw new IOException("items-26.3.txt missing from the plugin");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String[] p = line.split("\t");
                if (p.length == 3) {
                    files.put("minecraft:" + p[0], "minecraft:" + p[1] + ".png");
                    advances.put("minecraft:" + p[0], Integer.parseInt(p[2]));
                }
            }
        }
        int i = 0;
        for (String id : files.keySet()) {
            codepoints.put(id, BASE + i++);
        }
        i = 0;
        for (String id : new TreeMap<>(moddedTextures).keySet()) {
            codepoints.put(id, MODDED_BASE + i++);
        }
        moddedTextures.forEach((id, value) -> {
            String[] p = value.split("\t");
            files.put(id, p[0]);
            advances.put(id, Integer.parseInt(p[1]));
        });
        // Dense numbering by codepoint: the scaled glyphs of an id keep the same index in every run.
        List<Map.Entry<String, Integer>> byCodepoint = new ArrayList<>(codepoints.entrySet());
        byCodepoint.sort(Map.Entry.comparingByValue());
        for (int n = 0; n < byCodepoint.size(); n++) {
            ordinals.put(byCodepoint.get(n).getKey(), n);
        }
    }

    /**
     * The scale bucket of a GuiGameElement scale: 1 for an unknown or 1x scale, up to {@link #MAX_BUCKET}.
     * An item draws 16 px per scale unit and a block one px per unit; a scale of 3 or more is the 3x
     * bucket for both, which is the largest one the font carries.
     */
    public static int bucket(int scale) {
        return Math.max(1, Math.min(MAX_BUCKET, scale));
    }

    /** ItemGlyphs checks for the ScreenPack selftest: scale buckets, codepoints, providers. Returns the failures. */
    static int selftest() {
        int bad = 0;
        try {
            ItemGlyphs g = new ItemGlyphs(Map.of());
            String stone = "minecraft:stone";
            bad += check(bucket(0) == 1 && bucket(1) == 1 && bucket(2) == 2 && bucket(3) == 3
                    && bucket(5) == 3 && bucket(50) == 3, "buckets: 0 and 1 are 1x, 2 and 3 map to their own, larger caps at 3x");
            String one = g.glyph(stone, 1), two = g.glyph(stone, 2), three = g.glyph(stone, 3);
            bad += check(one != null && two != null && three != null && !one.equals(two) && !two.equals(three),
                    "scaled glyphs of an id are distinct codepoints from its 1x glyph");
            bad += check(g.glyph("minecraft:no_such_item", 2) == null, "an unknown id has no scaled glyph");
            bad += check(g.advance(stone, 3) == (g.advance(stone) - 1) * 3 + 1 && g.advance(stone, 1) == g.advance(stone),
                    "scaled advance grows with the glyph (3x of a 16 px icon is 49 px)");
            JsonArray providers = g.providers();
            boolean tall = false, dup = false;
            for (var element : providers) {
                JsonObject o = element.getAsJsonObject();
                if (o.get("height").getAsInt() == 48 && o.getAsJsonArray("chars").get(0).getAsString().equals(three)) tall = true;
                if (o.get("height").getAsInt() == 48 && o.get("ascent").getAsInt() > 48) dup = true;
            }
            bad += check(tall, "the free font has a 48 px provider for the 3x glyph");
            bad += check(!dup, "every scaled provider keeps its ascent within its height");
            String scaledCodepoints = g.providers().toString();
            bad += check(scaledCodepoints.indexOf(three) >= 0 && g.glyph("minecraft:stone", 3).codePointAt(0) >= 0x100000,
                    "scaled glyphs use plane 16, clear of the 1x plane 15 range");
        } catch (IOException e) {
            System.out.println("FAIL ItemGlyphs: " + e);
            bad++;
        }
        return bad;
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** How far the client moves the pen for this icon. */
    public int advance(String id) {
        return advances.getOrDefault(id, ICON + 1);
    }

    /** The pen movement of an icon drawn at a scale bucket: the 1x advance grown with the glyph. */
    public int advance(String id, int bucket) {
        int base = advance(id);
        return bucket <= 1 ? base : (base - 1) * bucket + 1;
    }

    /** The glyph for an id, or null when no item has that id. */
    public String glyph(String id) {
        Integer cp = codepoints.get(id);
        return cp == null ? null : new String(Character.toChars(cp));
    }

    /** The glyph for an id at a scale bucket (1 is {@link #glyph(String)}), or null for an unknown id. */
    public String glyph(String id, int bucket) {
        if (bucket <= 1) {
            return glyph(id);
        }
        Integer index = ordinals.get(id);
        if (index == null || bucket > MAX_BUCKET) {
            return null;
        }
        return new String(Character.toChars(SCALED_BASE + index * (MAX_BUCKET - 1) + (bucket - 2)));
    }

    /** One bitmap provider per icon, for the free font. */
    public JsonArray providers() {
        int ascent = HudLayout.ascent(HudLayout.freeId());
        JsonArray out = providers(ascent);
        for (String id : files.keySet()) {
            for (int bucket = 2; bucket <= MAX_BUCKET; bucket++) {
                // Same texture, taller: the glyph keeps its top (ascent) and grows downwards.
                JsonObject o = new JsonObject();
                o.addProperty("type", "bitmap");
                o.addProperty("file", files.get(id));
                o.addProperty("height", ICON * bucket);
                o.addProperty("ascent", ascent);
                JsonArray chars = new JsonArray();
                chars.add(glyph(id, bucket));
                o.add("chars", chars);
                out.add(o);
            }
        }
        return out;
    }

    /** The 1x providers for another font (a wash's). */
    public JsonArray providers(int ascent) {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, String> e : files.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "bitmap");
            o.addProperty("file", e.getValue());
            o.addProperty("height", ICON);
            o.addProperty("ascent", ascent);
            JsonArray chars = new JsonArray();
            chars.add(glyph(e.getKey()));
            o.add("chars", chars);
            out.add(o);
        }
        return out;
    }
}
