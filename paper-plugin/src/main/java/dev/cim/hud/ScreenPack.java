package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.f2v.vm.discovery.GuiSpriteBytecodeScanner;
import dev.f2v.vm.discovery.ModScanner;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/**
 * Turns what F2V's scanners find in {@code mods/} into free-font glyphs. Nothing
 * here names a mod. A scan takes seconds, so the result is cached by jar stamp.
 */
public final class ScreenPack {

    private static final char GLYPH_BASE = '\uE300';
    private static final int GLYPH_LIMIT = 0xF000;
    /** A glyph must fit the client's 256px font page, with room for its padding. */
    private static final int TILE = 240;
    /** The vanilla inventory panel's width; its first slot sits at (8, 17). */
    private static final int INVENTORY_PANEL_WIDTH = 176;
    private static final String ITEM_MANIFEST = "visual-items.resolved.tsv";
    private static final String CACHE = "screens.tsv";

    /**
     * One glyph of a scene, at (x, y) from the screen's top left. A floating
     * piece is an inventory panel whose position the bytecode only computes at
     * runtime: its (x, y) is relative to the panel, and the captured player
     * slots place it.
     */
    public record Piece(char glyph, int x, int y, int w, int h, boolean floating) {
    }

    /** One drawable screen. Glyphs are the same at pack build and at runtime. */
    public record Scene(String className, String menu, int width, int height, List<Piece> pieces) {
        public String shortName() {
            return className.substring(Math.max(className.lastIndexOf('/'), className.lastIndexOf('$')) + 1);
        }
    }

    /** Scenes plus the art of each glyph, in a stable order. */
    record Baked(List<Scene> scenes, Map<Character, BufferedImage> art) {
    }

    private ScreenPack() {
    }

    /** The scanned scenes, from the cache when the jars have not changed. */
    public static List<Scene> scenes(Path modsDir) throws IOException {
        Path cache = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(CACHE);
        String stamp = stamp(modsDir);
        if (Files.isRegularFile(cache)) {
            List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).equals(stamp)) {
                return parse(lines);
            }
        }
        return bake(modsDir).scenes();
    }

    private static String stamp(Path modsDir) throws IOException {
        StringBuilder out = new StringBuilder("v3");
        if (Files.isDirectory(modsDir)) {
            try (Stream<Path> s = Files.list(modsDir)) {
                for (Path jar : s.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    out.append('|').append(jar.getFileName()).append(':').append(Files.size(jar));
                }
            }
        }
        return out.toString();
    }

    private static void writeCache(Path modsDir, List<Scene> scenes) throws IOException {
        Path cache = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(CACHE);
        Files.createDirectories(cache.getParent());
        StringBuilder out = new StringBuilder(stamp(modsDir)).append('\n');
        for (Scene s : scenes) {
            out.append("scene\t").append(s.className()).append('\t').append(s.menu() == null ? "" : s.menu())
                    .append('\t').append(s.width()).append('\t').append(s.height()).append('\n');
            for (Piece p : s.pieces()) {
                out.append("piece\t").append((int) p.glyph()).append('\t').append(p.x()).append('\t')
                        .append(p.y()).append('\t').append(p.w()).append('\t').append(p.h()).append('\t')
                        .append(p.floating()).append('\n');
            }
        }
        Files.writeString(cache, out.toString(), StandardCharsets.UTF_8);
    }

    static List<Scene> parse(List<String> lines) {
        List<Scene> out = new ArrayList<>();
        List<Piece> pieces = null;
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p[0].equals("scene")) {
                pieces = new ArrayList<>();
                out.add(new Scene(p[1], p[2].isEmpty() ? null : p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), pieces));
            } else if (p[0].equals("piece") && pieces != null) {
                pieces.add(new Piece((char) Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), Boolean.parseBoolean(p[6])));
            }
        }
        return out;
    }

    /** Adds every scanned screen to the pack and returns how many. */
    public static int contribute(Map<String, byte[]> entries, Path modsDir) throws IOException {
        Baked baked = bake(modsDir);
        JsonArray providers = new JsonArray();
        int ascent = HudLayout.ascent(HudLayout.freeId());
        for (Map.Entry<Character, BufferedImage> e : baked.art().entrySet()) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(e.getValue(), "png", png);
            String file = "screen/" + (int) e.getKey() + ".png";
            entries.put("assets/" + HudPack.NS + "/textures/font/" + file, png.toByteArray());
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", HudPack.NS + ":font/" + file);
            provider.addProperty("height", e.getValue().getHeight());
            provider.addProperty("ascent", ascent);
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(e.getKey()));
            provider.add("chars", chars);
            providers.add(provider);
        }
        Map<String, String> icons = moddedIcons(modsDir);
        providers.addAll(new ItemGlyphs(icons).providers());
        // A provider's file must exist in the pack, or the client drops the font.
        copyIcons(entries, icons, modsDir);
        entries.put("assets/" + HudPack.NS + "/font/" + HudLayout.fontName(HudLayout.freeId()) + ".json",
                HudPack.freeFont(providers).toString().getBytes(StandardCharsets.UTF_8));
        return baked.scenes().size();
    }

    /**
     * Modded item id to its icon, for ids the item manifest names and whose flat
     * square texture is really in a mod jar.
     */
    static Map<String, String> moddedIcons(Path modsDir) throws IOException {
        Path manifest = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(ITEM_MANIFEST);
        Map<String, String> out = new java.util.TreeMap<>();
        if (!Files.isRegularFile(manifest) || !Files.isDirectory(modsDir)) {
            return out;
        }
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (dev.cim.f2v.F2vPack.VisualItem row : dev.cim.f2v.F2vPack.readManifest(manifest)) {
            wanted.add(row.moddedId());
        }
        Map<String, byte[]> art = new HashMap<>();
        try (Stream<Path> s = Files.list(modsDir)) {
            for (Path jar : s.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                readIcons(Files.readAllBytes(jar), art, 0);
            }
        }
        for (String id : wanted) {
            String[] p = id.split(":", 2);
            for (String folder : new String[]{"item", "block"}) {
                byte[] png = art.get("assets/" + p[0] + "/textures/" + folder + "/" + p[1] + ".png");
                if (png != null && png.length > 24 && square(png)) {
                    out.put(id, p[0] + ":" + folder + "/" + p[1] + ".png");
                    break;
                }
            }
        }
        return out;
    }

    private static void copyIcons(Map<String, byte[]> entries, Map<String, String> icons, Path modsDir)
            throws IOException {
        Map<String, byte[]> art = new HashMap<>();
        try (Stream<Path> s = Files.list(modsDir)) {
            for (Path jar : s.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                readIcons(Files.readAllBytes(jar), art, 0);
            }
        }
        for (String file : icons.values()) {
            String[] p = file.split(":", 2);
            String entry = "assets/" + p[0] + "/textures/" + p[1];
            entries.putIfAbsent(entry, art.get(entry));
        }
    }

    private static boolean square(byte[] png) {
        return java.nio.ByteBuffer.wrap(png).getInt(16) == java.nio.ByteBuffer.wrap(png).getInt(20);
    }

    private static void readIcons(byte[] jar, Map<String, byte[]> out, int depth) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.startsWith("assets/") && name.endsWith(".png")
                        && (name.contains("/textures/item/") || name.contains("/textures/block/"))) {
                    out.put(name, zip.readAllBytes());
                } else if (depth < 2 && name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    readIcons(zip.readAllBytes(), out, depth + 1);
                }
            }
        }
    }

    private static Baked bake(Path modsDir) throws IOException {
        List<Scene> scenes = new ArrayList<>();
        Map<Character, BufferedImage> art = new java.util.LinkedHashMap<>();
        if (!Files.isDirectory(modsDir)) {
            return new Baked(scenes, art);
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        Map<String, List<Piece>> shared = new HashMap<>();
        for (Path jar : jars) {
            Map<String, byte[]> textures = new HashMap<>();
            readTextures(Files.readAllBytes(jar), textures, 0);
            ModScanner.Profile profile = ModScanner.scan(jar);
            Map<String, GuiSpriteBytecodeScanner.Sprite> sprites = new HashMap<>();
            for (GuiSpriteBytecodeScanner.Sprite s : profile.guiSprites) {
                sprites.put(s.owner + "." + s.field, s);
            }
            Map<String, ModScanner.ScreenInfo> info = new HashMap<>();
            for (ModScanner.ScreenInfo i : profile.screens) {
                info.put(slashed(i.screenClass), i);
            }
            List<GuiSpriteBytecodeScanner.ScreenBackground> backgrounds = new ArrayList<>(profile.guiBackgrounds);
            backgrounds.sort(java.util.Comparator.comparing(b -> b.screenClass));
            for (GuiSpriteBytecodeScanner.ScreenBackground b : backgrounds) {
                GuiSpriteBytecodeScanner.Sprite bg = sprites.get(b.spriteOwner + "." + b.spriteField);
                if (bg == null) {
                    continue;
                }
                List<Piece> pieces = new ArrayList<>(tiles(bg, textures, 0, 0, shared, art));
                if (pieces.isEmpty()) {
                    continue;
                }
                for (GuiSpriteBytecodeScanner.ScreenLayer l : profile.guiLayers) {
                    GuiSpriteBytecodeScanner.Sprite sprite = sprites.get(l.spriteOwner + "." + l.spriteField);
                    // Conditional art depends on live state the scan cannot know.
                    if (sprite != null && slashed(l.screenClass).equals(slashed(b.screenClass))
                            && !l.conditional && !l.partialOnly && !l.stretched) {
                        boolean floating = l.x < 0 && sprite.width == INVENTORY_PANEL_WIDTH;
                        for (Piece p : tiles(sprite, textures, floating ? 0 : l.x, floating ? 0 : l.y, shared, art)) {
                            pieces.add(new Piece(p.glyph(), p.x(), p.y(), p.w(), p.h(), floating));
                        }
                    }
                }
                ModScanner.ScreenInfo i = info.get(slashed(b.screenClass));
                String menu = i == null || i.menuClass == null ? null : slashed(i.menuClass);
                scenes.add(new Scene(slashed(b.screenClass), menu, bg.width, bg.height, pieces));
            }
        }
        writeCache(modsDir, scenes);
        return new Baked(scenes, art);
    }

    private static String slashed(String name) {
        return name == null ? null : name.replace('.', '/');
    }

    /** A sprite as glyphs of at most {@link #TILE} pixels, each shared between screens. */
    private static List<Piece> tiles(GuiSpriteBytecodeScanner.Sprite s, Map<String, byte[]> textures, int x, int y,
                                     Map<String, List<Piece>> shared, Map<Character, BufferedImage> art)
            throws IOException {
        String key = s.texture + "|" + s.u + "|" + s.v + "|" + s.width + "|" + s.height;
        List<Piece> origin = shared.get(key);
        if (origin == null) {
            origin = new ArrayList<>();
            byte[] png = find(textures, s.texture);
            if (png != null && s.width > 0 && s.height > 0) {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
                if (s.u >= 0 && s.v >= 0 && s.u + s.width <= src.getWidth() && s.v + s.height <= src.getHeight()) {
                    for (int ty = 0; ty < s.height; ty += TILE) {
                        for (int tx = 0; tx < s.width; tx += TILE) {
                            char glyph = (char) (GLYPH_BASE + art.size());
                            if (glyph >= GLYPH_LIMIT) {
                                throw new IOException("out of screen glyphs");
                            }
                            int w = Math.min(TILE, s.width - tx);
                            int h = Math.min(TILE, s.height - ty);
                            BufferedImage tile = copy(src, s.u + tx, s.v + ty, w, h);
                            // A glyph's advance comes from its last opaque column, so pin it.
                            if (tile.getRGB(w - 1, h - 1) >>> 24 == 0) {
                                tile.setRGB(w - 1, h - 1, 0x01000000);
                            }
                            art.put(glyph, tile);
                            origin.add(new Piece(glyph, tx, ty, w, h, false));
                        }
                    }
                }
            }
            shared.put(key, origin);
        }
        List<Piece> placed = new ArrayList<>(origin.size());
        for (Piece p : origin) {
            placed.add(new Piece(p.glyph(), p.x() + x, p.y() + y, p.w(), p.h(), false));
        }
        return placed;
    }

    private static void readTextures(byte[] jar, Map<String, byte[]> out, int depth) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.startsWith("assets/") && name.contains("/textures/gui/") && name.endsWith(".png")) {
                    out.put(name, zip.readAllBytes());
                } else if (depth < 2 && name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    readTextures(zip.readAllBytes(), out, depth + 1);
                }
            }
        }
    }

    /** An enum names a bare file like {@code filters}; a string names a full path. */
    private static byte[] find(Map<String, byte[]> textures, String name) {
        String tail = name.substring(name.indexOf(':') + 1);
        String suffix = (tail.startsWith("textures/") ? "/" + tail : "/textures/gui/" + tail)
                .replaceAll("\\.png$", "") + ".png";
        return textures.entrySet().stream().filter(e -> e.getKey().endsWith(suffix)).sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static BufferedImage copy(BufferedImage src, int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.createGraphics().drawImage(src.getSubimage(x, y, w, h), 0, 0, null);
        return out;
    }

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    /** {@code ScreenPack --selftest [modsDir]}. */
    public static void main(String[] args) throws IOException {
        int bad = 0;
        java.util.TreeMap<String, byte[]> entries = new java.util.TreeMap<>();
        HudPack.contribute(entries);
        if (args.length > 1) {
            int n = contribute(entries, Path.of(args[1]));
            System.out.println("     screens baked: " + n);
            bad += check(n > 0, "the scanner feeds the pack");
            List<Scene> again = scenes(Path.of(args[1]));
            bad += check(again.size() == n && again.equals(scenes(Path.of(args[1]))), "the cache returns the same scenes");
            Map<String, int[]> oracle = Map.of("FilterScreen", new int[]{214, 99},
                    "AttributeFilterScreen", new int[]{241, 85}, "ToolboxScreen", new int[]{188, 171},
                    "LinkedControllerScreen", new int[]{179, 109}, "BlueprintScreen", new int[]{179, 109},
                    "SchematicTableScreen", new int[]{214, 85});
            for (var e : oracle.entrySet()) {
                Scene s = again.stream().filter(x -> x.shortName().equals(e.getKey())).findFirst().orElse(null);
                bad += check(s != null && s.width() == e.getValue()[0] && s.height() == e.getValue()[1],
                        e.getKey() + " art from the scanner: " + (s == null ? null : s.width() + "x" + s.height()
                                + " pieces=" + s.pieces().size() + " menu=" + s.menu()));
            }
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
        System.out.println(bad == 0 ? "ScreenPack selftest: ALL PASS" : "ScreenPack selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
