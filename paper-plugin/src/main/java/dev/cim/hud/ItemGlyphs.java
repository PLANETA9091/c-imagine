package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item icons as free-font glyphs. A bitmap provider can name a texture the
 * client already has, so nothing is shipped: {@code minecraft:item/diamond.png}
 * is read from the client's own assets. An item with no flat icon uses its block
 * texture, which is a face, not the isometric icon. Animated strips are left out
 * because one glyph would squash them. Plane 15 gives every id a codepoint outside
 * the BMP's limited private range.
 */
public final class ItemGlyphs {

    private static final int BASE = 0xF0000;
    /** Modded ids start here, so a different modded set can never shift vanilla icons. */
    private static final int MODDED_BASE = 0xF8000;
    private static final int ICON = 16;

    private final Map<String, Integer> codepoints = new HashMap<>();
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
    }

    /** How far the client moves the pen for this icon. */
    public int advance(String id) {
        return advances.getOrDefault(id, ICON + 1);
    }

    /** The glyph for an id, or null when no item has that id. */
    public String glyph(String id) {
        Integer cp = codepoints.get(id);
        return cp == null ? null : new String(Character.toChars(cp));
    }

    /** One bitmap provider per icon, for the free font. */
    public JsonArray providers() {
        return providers(HudLayout.ascent(HudLayout.freeId()));
    }

    /** The same providers for another font (a wash's). */
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
