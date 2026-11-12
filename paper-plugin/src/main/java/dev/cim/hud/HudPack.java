package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.imageio.ImageIO;

/** The resource pack half of the HUD channel: shader, fonts and the hidden carrier. */
public final class HudPack {

    public static final String NS = "cimhud";

    /** A run of these moves the pen; bit {@code n} of the distance picks {@code SPACE_BASE + n}. */
    public static final char SPACE_BASE = '\uE100';
    public static final char BACK_BASE = '\uE120';
    public static final int SPACE_BITS = 11;

    public static final char CURSOR = '\uE020';
    public static final char BACKDROP = '\uE021';
    public static final char SLOT = '\uE022';
    public static final char SLOT_HOVER = '\uE023';
    /** A 4x4 dark tile: a disabled widget is drawn under it, as vanilla greys an inactive button. */
    public static final char DIM = '\uE02B';
    public static final int DIM_SIZE = 4;
    /** Plate under a scanned icon button. */
    public static final char BUTTON = '\uE024';
    public static final char KEY = '\uE025';
    public static final char KEY_LIT = '\uE026';
    public static final char KEY_HOVER = '\uE027';
    public static final char KEY_WIDE = '\uE028';
    public static final char KEY_WIDE_LIT = '\uE029';
    public static final char KEY_WIDE_HOVER = '\uE02A';
    public static final int BACKDROP_W = 1024;
    public static final int BACKDROP_H = 640;
    private static final int BACKDROP_SCALE = 16;

    private static final String[] ASCII_ROWS = asciiRows();

    private HudPack() {
    }

    /** Adds every HUD asset to a pack's entry map. */
    public static void contribute(Map<String, byte[]> entries) throws IOException {
        entries.put("assets/minecraft/shaders/core/text.vsh",
                HudLayout.vertexShader().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/minecraft/shaders/core/text.fsh",
                HudLayout.fragmentShader().getBytes(StandardCharsets.UTF_8));
        // The carrier: a boss bar colour whose bar draws nothing.
        byte[] clear = png(new BufferedImage(182, 5, BufferedImage.TYPE_INT_ARGB));
        entries.put("assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png", clear);
        entries.put("assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png", clear);
        entries.put("assets/" + NS + "/textures/font/cursor.png", png(cursor()));
        entries.put("assets/" + NS + "/textures/font/backdrop.png", png(backdrop()));
        entries.put("assets/" + NS + "/textures/font/slot.png", png(slot()));
        entries.put("assets/" + NS + "/textures/font/slot_hover.png", png(hover()));
        entries.put("assets/" + NS + "/textures/font/dim.png", png(dim()));
        entries.put("assets/" + NS + "/textures/font/button.png", png(button()));
        for (int i = 0; i < 6; i++) {
            entries.put("assets/" + NS + "/textures/font/key" + i + ".png", png(keycap(i)));
        }
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeId()) + ".json",
                freeFont(new JsonArray()).toString().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeDarkId()) + ".json",
                font(HudLayout.freeDarkId()).toString().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeTextId()) + ".json",
                font(HudLayout.freeTextId()).toString().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeShadowId()) + ".json",
                font(HudLayout.freeShadowId()).toString().getBytes(StandardCharsets.UTF_8));
        for (HudLayout.Anchor a : HudLayout.Anchor.values()) {
            for (int row = 0; row < HudLayout.rows(a); row++) {
                int id = HudLayout.id(a, row);
                entries.put("assets/" + NS + "/font/" + HudLayout.fontName(id) + ".json",
                        font(id).toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /** The free font, plus the given extra bitmap providers (one per scanned screen). */
    static JsonObject freeFont(JsonArray screens) {
        JsonObject font = font(HudLayout.freeId());
        JsonArray providers = font.getAsJsonArray("providers");
        int ascent = HudLayout.ascent(HudLayout.freeId());
        JsonObject cursor = bitmap(NS + ":font/cursor.png", 12, ascent);
        JsonArray c = new JsonArray();
        c.add(String.valueOf(CURSOR));
        cursor.add("chars", c);
        providers.add(cursor);
        JsonObject backdrop = bitmap(NS + ":font/backdrop.png", BACKDROP_H, ascent);
        JsonArray b = new JsonArray();
        b.add(String.valueOf(BACKDROP));
        backdrop.add("chars", b);
        providers.add(backdrop);
        JsonObject dim = bitmap(NS + ":font/dim.png", DIM_SIZE, ascent);
        JsonArray dimChars = new JsonArray();
        dimChars.add(String.valueOf(DIM));
        dim.add("chars", dimChars);
        providers.add(dim);
        for (boolean hover : new boolean[]{false, true}) {
            JsonObject slot = bitmap(NS + ":font/" + (hover ? "slot_hover" : "slot") + ".png", hover ? 16 : 18, ascent);
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(hover ? SLOT_HOVER : SLOT));
            slot.add("chars", chars);
            providers.add(slot);
        }
        for (int i = 0; i < 6; i++) {
            JsonObject key = bitmap(NS + ":font/key" + i + ".png", KeyboardLayout.CAP_H, ascent);
            JsonArray keyChars = new JsonArray();
            keyChars.add(String.valueOf((char) (KEY + i)));
            key.add("chars", keyChars);
            providers.add(key);
        }
        JsonObject button = bitmap(NS + ":font/button.png", 18, ascent);
        JsonArray buttonChars = new JsonArray();
        buttonChars.add(String.valueOf(BUTTON));
        button.add("chars", buttonChars);
        providers.add(button);
        providers.addAll(screens);
        return font;
    }

    static JsonObject font(int id) {
        int ascent = HudLayout.ascent(id);
        JsonArray providers = new JsonArray();

        JsonObject spaces = new JsonObject();
        spaces.addProperty("type", "space");
        JsonObject advances = new JsonObject();
        advances.addProperty(" ", 4);
        for (int bit = 0; bit < SPACE_BITS; bit++) {
            advances.addProperty(String.valueOf((char) (SPACE_BASE + bit)), 1 << bit);
            advances.addProperty(String.valueOf((char) (BACK_BASE + bit)), -(1 << bit));
        }
        spaces.add("advances", advances);
        providers.add(spaces);

        JsonObject ascii = bitmap("minecraft:font/ascii.png", 8, ascent);
        JsonArray chars = new JsonArray();
        for (String row : ASCII_ROWS) {
            chars.add(row);
        }
        ascii.add("chars", chars);
        providers.add(ascii);

        JsonObject root = new JsonObject();
        root.add("providers", providers);
        return root;
    }

    private static JsonObject bitmap(String file, int height, int ascent) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "bitmap");
        o.addProperty("file", file);
        o.addProperty("height", height);
        o.addProperty("ascent", ascent);
        return o;
    }

    /** The grid of {@code ascii.png}; NUL marks a cell the font must skip. */
    static String[] asciiRows() {
        String[] rows = new String[16];
        for (int r = 0; r < 16; r++) {
            StringBuilder row = new StringBuilder(16);
            for (int c = 0; c < 16; c++) {
                int cp = r * 16 + c;
                row.append(cp > 32 && cp < 127 ? (char) cp : '\0');
            }
            rows[r] = row.toString();
        }
        return rows;
    }

    /** Keycaps, in {@link #KEY} order: normal, held, hover, then the same three wide. */
    private static BufferedImage keycap(int kind) {
        int w = kind < 3 ? KeyboardLayout.KEY_W : KeyboardLayout.WIDE_W;
        int h = KeyboardLayout.CAP_H;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int style = kind % 3;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean corner = (x == 0 || x == w - 1) && (y == 0 || y == h - 1);
                int c;
                if (style == 2) {
                    c = 0x60FFFFFF;
                } else if (corner) {
                    c = 0;
                } else if (x == 0 || y == 0) {
                    c = style == 1 ? 0xF0A4F0A4 : 0xE8FFFFFF;
                } else if (x == w - 1 || y == h - 1) {
                    c = style == 1 ? 0xF02E7D32 : 0xE8585858;
                } else {
                    c = style == 1 ? 0xF066D06A : 0xE8C6C6C6;
                }
                img.setRGB(x, y, c);
            }
        }
        // Corners are clear, and a glyph's advance comes from its last solid column.
        img.setRGB(w - 1, h - 1, style == 2 ? 0x60FFFFFF : 0x01000000);
        return img;
    }

    /** A raised 18px button: light top and left, dark bottom and right. */
    private static BufferedImage button() {
        BufferedImage img = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                int c = 0xFF9C9C9C;
                if (x == 0 || y == 0) {
                    c = 0xFFD8D8D8;
                } else if (x == 17 || y == 17) {
                    c = 0xFF4A4A4A;
                }
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /** An 18px inventory slot, with vanilla's bevel. */
    private static BufferedImage slot() {
        BufferedImage img = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 18; y++) {
            for (int x = 0; x < 18; x++) {
                int c = 0xFF8B8B8B;
                if (x == 0 || y == 0) {
                    c = 0xFF373737;
                } else if (x == 17 || y == 17) {
                    c = 0xFFFFFFFF;
                }
                img.setRGB(x, y, c);
            }
        }
        return img;
    }

    /** Vanilla's slot highlight: half-transparent white over whatever the mod's slot is. */
    private static BufferedImage dim() {
        BufferedImage img = new BufferedImage(DIM_SIZE, DIM_SIZE, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < DIM_SIZE; y++) {
            for (int x = 0; x < DIM_SIZE; x++) {
                img.setRGB(x, y, 0x90000000);
            }
        }
        return img;
    }

    private static BufferedImage hover() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, 0x80FFFFFF);
            }
        }
        return img;
    }

    /** The vanilla arrow pointer shape, 8 wide and 12 tall. */
    private static BufferedImage cursor() {
        String[] shape = {"X.......", "XX......", "XOX.....", "XOOX....", "XOOOX...", "XOOOOX..",
                "XOOOOOX.", "XOOOOOOX", "XOOOXXXX", "XOXOOX..", "XX.XOOX.", "....XX.."};
        BufferedImage img = new BufferedImage(8, 12, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 8; x++) {
                char c = shape[y].charAt(x);
                if (c != '.') {
                    img.setRGB(x, y, c == 'X' ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
        }
        img.setRGB(7, 11, 0x01000000);
        return img;
    }

    /**
     * Dims the world while a screen is up, the way a vanilla screen does. Font
     * glyphs must fit a 256px atlas page, so this is small and the provider's
     * height scales it up.
     */
    private static BufferedImage backdrop() {
        int w = BACKDROP_W / BACKDROP_SCALE;
        int h = BACKDROP_H / BACKDROP_SCALE;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, 0xC0101010);
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code HudPack --emit out.zip}, or {@code HudPack --selftest [client.jar]}. */
    public static void main(String[] args) throws Exception {
        java.util.TreeMap<String, byte[]> entries = new java.util.TreeMap<>();
        contribute(entries);
        if (args.length >= 2 && args[0].equals("--emit")) {
            if (args.length == 3) {
                System.out.println("screens baked: " + ScreenPack.contribute(entries, java.nio.file.Path.of(args[2])));
            }
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(
                    java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[1])))) {
                z.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
                z.write(("{\"pack\":{\"description\":\"cim hud\",\"min_format\":[97,1],"
                        + "\"max_format\":[97,1]}}").getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                for (var e : entries.entrySet()) {
                    z.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                    z.write(e.getValue());
                    z.closeEntry();
                }
            }
            return;
        }
        int bad = 0;
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeId()) + ".json",
                freeFont(new JsonArray()).toString().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeDarkId()) + ".json",
                font(HudLayout.freeDarkId()).toString().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeTextId()) + ".json",
                font(HudLayout.freeTextId()).toString().getBytes(StandardCharsets.UTF_8));
        entries.put("assets/" + NS + "/font/" + HudLayout.fontName(HudLayout.freeShadowId()) + ".json",
                font(HudLayout.freeShadowId()).toString().getBytes(StandardCharsets.UTF_8));
        for (HudLayout.Anchor a : HudLayout.Anchor.values()) {
            for (int row = 0; row < HudLayout.rows(a); row++) {
                int id = HudLayout.id(a, row);
                int vertexY = -HudLayout.ascent(id) + 12;
                if (HudLayout.decode(vertexY) != id) {
                    bad += check(false, "id survives the round trip: " + a + " row " + row);
                }
                if (vertexY >= (1 << 24)) {
                    bad += check(false, "vertex Y stays exact in a float: " + a + " row " + row);
                }
                if (!entries.containsKey("assets/" + NS + "/font/" + HudLayout.fontName(id) + ".json")) {
                    bad += check(false, "font emitted: " + id);
                }
            }
        }
        bad += check(true, "every anchor and row encodes, decodes and has a font");
        for (int[] p : new int[][]{{0, 0}, {-320, -180}, {319, 179}, {-2048, 2047}}) {
            int c = HudLayout.freeColor(p[0], p[1]);
            int r = c >> 16 & 255;
            int g = c >> 8 & 255;
            int b = c & 255;
            int x = ((r << 4) | (g >> 4)) - 2048;
            int y = (((g & 15) << 8) | b) - 2048;
            bad += check(x == p[0] && y == p[1], "the shader decodes a free position: " + p[0] + "," + p[1]);
        }
        bad += check(HudLayout.decode(300) == -1, "ordinary GUI text is not decoded");
        String vsh = HudLayout.vertexShader();
        bad += check(!vsh.matches("(?s).*\\b(HEIGHT_BIT|MAX_BIT|ROW_BITS|MAP_[A-Z]+|BASELINE|MARGIN|TEXT_[A-Z_]+|BOTTOM_CLEAR)\\b.*"),
                "no placeholder left in the shader");
        for (int d : new int[]{0, 1, -1, 7, -7, 1000, -1000, 2047, -2047}) {
            int sum = 0;
            for (char c : HudFrame.move(d).toCharArray()) {
                sum += c >= BACK_BASE ? -(1 << (c - BACK_BASE)) : 1 << (c - SPACE_BASE);
            }
            bad += check(sum == d, "pen move adds up: " + d);
        }
        bad += check(HudText.width("Hi") == 8 && HudText.width(" ") == 4, "known glyph widths");
        bad += check(HudText.sanitize("za\u017C").equals("za?"), "non-ASCII prints as ?");
        if (args.length > 1) {
            try (java.util.zip.ZipFile jar = new java.util.zip.ZipFile(args[1])) {
                BufferedImage ascii = ImageIO.read(jar.getInputStream(
                        jar.getEntry("assets/minecraft/textures/font/ascii.png")));
                int mismatches = 0;
                for (char c = 33; c < 127; c++) {
                    int right = -1;
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            if (ascii.getRGB((c % 16) * 8 + x, (c / 16) * 8 + y) >>> 24 != 0) {
                                right = Math.max(right, x);
                            }
                        }
                    }
                    if (right + 2 != HudText.width(c)) {
                        mismatches++;
                    }
                }
                bad += check(mismatches == 0, "width table matches the client font, off by " + mismatches);
            }
        }
        System.out.println(bad == 0 ? "HudPack selftest: ALL PASS" : "HudPack selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
