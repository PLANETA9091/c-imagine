package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Bakes each fitted menu into one font glyph, drawn as the chest's title.
 *
 * <p>The client draws labels before slots, so the glyph lies under the items.
 * It has to cover the whole chest, because the chest's own background texture
 * is shared with every real chest and cannot be changed.
 */
public final class MenuPack {

    public static final String FONT = HudPack.NS + ":menu";
    public static final char GLYPH_BASE = '\uE200';
    /** The title's baseline sits this far below the chest's top edge. */
    private static final int TITLE_BASELINE = 13;
    private static final int GAP_COLOR = 0xFF1A1A1A;
    private static final String INVENTORY_TEXTURE = "create:gui/player_inventory";

    /** Where a baked glyph sits relative to the chest's top left corner. */
    public record Baked(int originX, int originY, int width) {
    }

    private MenuPack() {
    }

    /** Adds every menu whose textures exist under {@code modsDir}. Returns how many. */
    public static int contribute(Map<String, byte[]> entries, Path modsDir) throws IOException {
        Map<String, BufferedImage> textures = new HashMap<>();
        JsonArray providers = new JsonArray();
        int baked = 0;
        for (int i = 0; i < MenuCatalog.MENUS.size(); i++) {
            MenuCatalog.Menu menu = MenuCatalog.MENUS.get(i);
            BufferedImage img = bake(menu, name -> load(textures, name, modsDir));
            if (img == null) {
                continue;
            }
            String file = "menu/" + i + ".png";
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            entries.put("assets/" + HudPack.NS + "/textures/font/" + file, png.toByteArray());
            Baked at = placement(menu);
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", HudPack.NS + ":font/" + file);
            provider.addProperty("height", img.getHeight());
            provider.addProperty("ascent", TITLE_BASELINE - at.originY());
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf((char) (GLYPH_BASE + i)));
            provider.add("chars", chars);
            providers.add(provider);
            entries.put("assets/" + HudPack.NS + "/font/" + titleFontName(i) + ".json",
                    titleFont(menu).toString().getBytes(StandardCharsets.UTF_8));
            baked++;
        }
        JsonObject spaces = new JsonObject();
        spaces.addProperty("type", "space");
        JsonObject advances = new JsonObject();
        for (int bit = 0; bit < HudPack.SPACE_BITS; bit++) {
            advances.addProperty(String.valueOf((char) (HudPack.SPACE_BASE + bit)), 1 << bit);
            advances.addProperty(String.valueOf((char) (HudPack.BACK_BASE + bit)), -(1 << bit));
        }
        spaces.add("advances", advances);
        providers.add(spaces);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        entries.put("assets/" + HudPack.NS + "/font/menu.json", font.toString().getBytes(StandardCharsets.UTF_8));
        return baked;
    }

    public static String titleFontName(int index) {
        return "menu_t" + index;
    }

    /** Where the title goes, in chest coordinates. */
    public static int[] titleAt(MenuCatalog.Menu menu) {
        MenuFit.Fit fit = MenuFit.fit(menu);
        MenuCatalog.Panel p = menu.panels().get(0);
        int w = HudText.width(menu.title());
        int x = menu.titleX() == MenuCatalog.CENTERED ? (p.w() - 8) / 2 - w / 2 : menu.titleX();
        return new int[]{p.x() + fit.shiftX() + x, p.y() + fit.shiftY() + menu.titleY()};
    }

    /** ASCII that the shader moves to the panel's header, since a chest title has a fixed y. */
    private static JsonObject titleFont(MenuCatalog.Menu menu) {
        JsonObject spaces = new JsonObject();
        spaces.addProperty("type", "space");
        JsonObject advances = new JsonObject();
        advances.addProperty(" ", 4);
        spaces.add("advances", advances);
        JsonObject ascii = new JsonObject();
        ascii.addProperty("type", "bitmap");
        ascii.addProperty("file", "minecraft:font/ascii.png");
        ascii.addProperty("height", 8);
        // The natural glyph top is TITLE_BASELINE once the shader strips the code.
        ascii.addProperty("ascent", HudLayout.ascent(HudLayout.titleId(titleAt(menu)[1] - TITLE_BASELINE)));
        JsonArray chars = new JsonArray();
        for (String row : HudPack.asciiRows()) {
            chars.add(row);
        }
        ascii.add("chars", chars);
        JsonArray providers = new JsonArray();
        providers.add(spaces);
        providers.add(ascii);
        JsonObject font = new JsonObject();
        font.add("providers", providers);
        return font;
    }

    /** Canvas bounds: the union of the chest and the shifted mod panels. */
    public static Baked placement(MenuCatalog.Menu menu) {
        MenuFit.Fit fit = MenuFit.fit(menu);
        int left = 0;
        int top = 0;
        int right = MenuFit.WIDTH;
        for (MenuCatalog.Panel p : menu.panels()) {
            left = Math.min(left, p.x() + fit.shiftX());
            top = Math.min(top, p.y() + fit.shiftY());
            right = Math.max(right, p.x() + fit.shiftX() + p.w());
        }
        return new Baked(left, top, right - left);
    }

    interface Textures {
        BufferedImage get(String name) throws IOException;
    }

    /** Draws a menu's picture, or returns null when its mod is not installed. */
    static BufferedImage bake(MenuCatalog.Menu menu, Textures textures) throws IOException {
        MenuFit.Fit fit = MenuFit.fit(menu);
        Baked at = placement(menu);
        int bottom = fit.height();
        for (MenuCatalog.Panel p : menu.panels()) {
            bottom = Math.max(bottom, p.y() + fit.shiftY() + p.h());
        }
        BufferedImage inventory = textures.get(INVENTORY_TEXTURE);
        if (inventory == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(at.width(), bottom - at.originY(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new java.awt.Color(GAP_COLOR, true));
        g.fillRect(-at.originX(), -at.originY(), MenuFit.WIDTH, fit.height());
        for (MenuCatalog.Panel p : menu.panels()) {
            BufferedImage src = textures.get(p.texture());
            if (src == null) {
                g.dispose();
                return null;
            }
            g.drawImage(src.getSubimage(p.u(), p.v(), p.w(), p.h()),
                    p.x() + fit.shiftX() - at.originX(), p.y() + fit.shiftY() - at.originY(), null);
        }
        g.drawImage(inventory.getSubimage(0, 0, 176, 108), -at.originX(), fit.invPanelY() - at.originY(), null);
        repaintMovedSlots(g, menu, fit, at, textures);
        g.dispose();
        return out;
    }

    /** A slot that left its painted square needs that square drawn under its cell. */
    private static void repaintMovedSlots(Graphics2D g, MenuCatalog.Menu menu, MenuFit.Fit fit, Baked at,
                                          Textures textures) throws IOException {
        MenuCatalog.Panel p = menu.panels().get(0);
        BufferedImage src = textures.get(p.texture());
        // Erase every moved square first, so a new square is never painted over.
        for (int i = 0; i < menu.slots().size(); i++) {
            MenuCatalog.Slot s = menu.slots().get(i);
            int cell = fit.cells()[i];
            if (!moved(s, cell, fit)) {
                continue;
            }
            int u = p.u() + s.x() - p.x() - 1;
            int v = p.v() + s.y() - p.y() - 1;
            // Some art frames its slot, so the patch reaches past the 18px square.
            if (u >= 4 && v >= 4 && u + 20 <= src.getWidth() && v + 20 <= src.getHeight()) {
                g.setColor(new java.awt.Color(src.getRGB(u - 4, v - 4), true));
                g.fillRect(s.x() + fit.shiftX() - 3 - at.originX(), s.y() + fit.shiftY() - 3 - at.originY(), 22, 22);
            }
        }
        for (int i = 0; i < menu.slots().size(); i++) {
            MenuCatalog.Slot s = menu.slots().get(i);
            int cell = fit.cells()[i];
            int u = p.u() + s.x() - p.x() - 1;
            int v = p.v() + s.y() - p.y() - 1;
            if (moved(s, cell, fit) && u >= 0 && v >= 0 && u + 18 <= src.getWidth() && v + 18 <= src.getHeight()) {
                g.drawImage(src.getSubimage(u, v, 18, 18), MenuFit.cellX(cell) - 1 - at.originX(),
                        MenuFit.cellY(cell) - 1 - at.originY(), null);
            }
        }
    }

    private static boolean moved(MenuCatalog.Slot s, int cell, MenuFit.Fit fit) {
        return s.x() + fit.shiftX() != MenuFit.cellX(cell) || s.y() + fit.shiftY() != MenuFit.cellY(cell);
    }

    private static BufferedImage load(Map<String, BufferedImage> cache, String name, Path modsDir) throws IOException {
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        String[] parts = name.split(":", 2);
        String entry = "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
        BufferedImage img = null;
        if (Files.isDirectory(modsDir)) {
            List<Path> jars;
            try (Stream<Path> s = Files.list(modsDir)) {
                jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
            }
            for (Path jar : jars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    ZipEntry e = zip.getEntry(entry);
                    if (e != null) {
                        img = ImageIO.read(new ByteArrayInputStream(zip.getInputStream(e).readAllBytes()));
                        break;
                    }
                }
            }
        }
        cache.put(name, img);
        return img;
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code MenuPack --selftest <modsDir>}: runs against real textures. */
    public static void main(String[] args) throws IOException {
        int bad = 0;
        java.util.TreeMap<String, byte[]> entries = new java.util.TreeMap<>();
        HudPack.contribute(entries);
        int baked = args.length > 1 ? contribute(entries, Path.of(args[1])) : 0;
        if (args.length > 1) {
            bad += check(baked == MenuCatalog.MENUS.size(), "every catalogued menu baked: " + baked);
        }
        int fonts = 0;
        for (var e : entries.entrySet()) {
            if (!e.getKey().contains("/font/") || !e.getKey().endsWith(".json")) {
                continue;
            }
            fonts++;
            JsonObject font = com.google.gson.JsonParser.parseString(
                    new String(e.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (var p : font.getAsJsonArray("providers")) {
                JsonObject o = p.getAsJsonObject();
                if (o.get("type").getAsString().equals("bitmap")
                        && o.get("ascent").getAsInt() > o.get("height").getAsInt()) {
                    // The client refuses the whole font for this, silently.
                    bad += check(false, e.getKey() + ": ascent above height");
                }
            }
        }
        bad += check(fonts > 0, "no font breaks the ascent rule, checked " + fonts);
        for (MenuCatalog.Menu m : MenuCatalog.MENUS) {
            int[] at = titleAt(m);
            bad += check(at[1] - TITLE_BASELINE >= -HudLayout.TITLE_ROW_ZERO
                            && HudLayout.decode(-HudLayout.ascent(HudLayout.titleId(at[1] - TITLE_BASELINE)))
                            >> 6 == HudLayout.Anchor.MENU_TITLE.ordinal(),
                    m.id() + ": title offset encodes");
        }
        System.out.println(bad == 0 ? "MenuPack selftest: ALL PASS" : "MenuPack selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
