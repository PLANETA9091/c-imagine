package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.f2v.vm.discovery.ClientSurfaceScanner;
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
    private static final String ICONS = "icons.tsv";
    private static final String CAPTURES = "screens";

    /**
     * One glyph of a scene, at (x, y) from the screen's top left. A floating
     * piece is an inventory panel whose position the bytecode only computes at
     * runtime: its (x, y) is relative to the panel, and the captured player
     * slots place it.
     */
    public record Piece(char glyph, int x, int y, int w, int h, boolean floating) {
    }

    /** A control the scan proved: where it is and what it is called. */
    public record Widget(int x, int y, int w, int h, String label) {
    }

    /**
     * Set when the art is the mod's real rendered screen: where its corner sits
     * from the screen centre, and its slots. Title and widgets are in the pixels.
     */
    /**
     * {@code states} holds what the screen looked like after each recorded press, by widget
     * index: a recording of the real mod's screen, not its logic. {@code move} is the slot
     * move the real client made first, or null.
     */
    /**
     * {@code covers}: per press state, the part of its art that draws over slots (a window, a
     * popup), drawn again over the slots' items: the real screen hides an item under a window
     * and shows the part of it the window leaves free.
     */
    public record Captured(int originX, int originY, List<GuiCaptures.Slot> slots,
                           Map<Integer, List<Piece>> states, int[] move, Map<Integer, List<Piece>> covers,
                           Map<Integer, int[]> shifts, java.util.Set<Integer> hidden) {
        public Captured(int originX, int originY, List<GuiCaptures.Slot> slots,
                        Map<Integer, List<Piece>> states, int[] move) {
            this(originX, originY, slots, states, move, new HashMap<>(), new HashMap<>(), new java.util.HashSet<>());
        }

        /** How far the menu's slots moved in a press state (a recipe book opening), or 0,0. */
        public int[] shift(int state) {
            return shifts.getOrDefault(state, ZERO_SHIFT);
        }
    }

    private static final int[] ZERO_SHIFT = {0, 0};

    /** The state key of the screen as the slot move left it. */
    public static final int MOVE_STATE = 9999;

    /** One drawable screen. Glyphs are the same at pack build and at runtime. */
    public record Scene(String className, String menu, int width, int height, boolean titleCentered,
                        List<Piece> pieces, List<Widget> widgets, Captured captured) {
        public String shortName() {
            return className.substring(Math.max(className.lastIndexOf('/'), className.lastIndexOf('$')) + 1);
        }
    }

    /** Scenes plus the art of each glyph, in a stable order. */
    record Baked(List<Scene> scenes, Map<Character, BufferedImage> art, Map<Character, Integer> heights) {
    }

    private ScreenPack() {
    }

    /** The scanned scenes, from the cache when the jars have not changed. */
    public static List<Scene> scenes(Path modsDir) throws IOException {
        Path cache = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(CACHE);
        String stamp = stamp(modsDir);
        if (Files.isRegularFile(cache)) {
            List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
            // Without the capture folder a rescan would lose art the pack was built
            // with, as on a server that only received the seed's pack and scenes.
            boolean canRebake = Files.isDirectory(modsDir.resolveSibling("c-imagine").resolve(CAPTURES));
            if (!lines.isEmpty() && (lines.get(0).equals(stamp) || !canRebake)) {
                return parse(lines);
            }
        }
        return bake(modsDir).scenes();
    }

    private static String stamp(Path modsDir) throws IOException {
        StringBuilder out = new StringBuilder("v16");
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
                    .append('\t').append(s.width()).append('\t').append(s.height()).append('\t')
                    .append(s.titleCentered()).append('\n');
            if (s.captured() != null) {
                out.append("captured\t").append(s.captured().originX()).append('\t')
                        .append(s.captured().originY()).append('\n');
                if (s.captured().move() != null) {
                    out.append("move");
                    for (int click : s.captured().move()) {
                        out.append('\t').append(click);
                    }
                    out.append('\n');
                }
                for (var st : s.captured().states().entrySet()) {
                    for (Piece q : st.getValue()) {
                        out.append("spiece\t").append(st.getKey()).append('\t').append((int) q.glyph()).append('\t')
                                .append(q.x()).append('\t').append(q.y()).append('\t').append(q.w()).append('\t')
                                .append(q.h()).append('\n');
                    }
                }
                for (int h : s.captured().hidden()) {
                    out.append("hidden\t").append(h).append('\n');
                }
                for (var sh : s.captured().shifts().entrySet()) {
                    out.append("shift\t").append(sh.getKey()).append('\t').append(sh.getValue()[0]).append('\t')
                            .append(sh.getValue()[1]).append('\n');
                }
                for (var cv : s.captured().covers().entrySet()) {
                    for (Piece q : cv.getValue()) {
                        out.append("cover\t").append(cv.getKey()).append('\t').append((int) q.glyph()).append('\t')
                                .append(q.x()).append('\t').append(q.y()).append('\t').append(q.w()).append('\t')
                                .append(q.h()).append('\n');
                    }
                }
                for (GuiCaptures.Slot slot : s.captured().slots()) {
                    out.append("cslot\t").append(slot.x()).append('\t').append(slot.y()).append('\t')
                            .append(slot.player()).append('\t').append(slot.index()).append('\t')
                            .append(slot.menuIndex()).append('\n');
                }
            }
            for (Piece p : s.pieces()) {
                out.append("piece\t").append((int) p.glyph()).append('\t').append(p.x()).append('\t')
                        .append(p.y()).append('\t').append(p.w()).append('\t').append(p.h()).append('\t')
                        .append(p.floating()).append('\n');
            }
            for (Widget w : s.widgets()) {
                out.append("widget\t").append(w.x()).append('\t').append(w.y()).append('\t').append(w.w())
                        .append('\t').append(w.h()).append('\t').append(w.label() == null ? "" : w.label()).append('\n');
            }
        }
        Files.writeString(cache, out.toString(), StandardCharsets.UTF_8);
    }

    static List<Scene> parse(List<String> lines) {
        List<Scene> out = new ArrayList<>();
        List<Piece> pieces = null;
        List<Widget> widgets = null;
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if (p[0].equals("scene")) {
                pieces = new ArrayList<>();
                widgets = new ArrayList<>();
                out.add(new Scene(p[1], p[2].isEmpty() ? null : p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                        Boolean.parseBoolean(p[5]), pieces, widgets, null));
            } else if (p[0].equals("captured")) {
                Scene last = out.remove(out.size() - 1);
                out.add(new Scene(last.className(), last.menu(), last.width(), last.height(), last.titleCentered(),
                        pieces, widgets, new Captured(Integer.parseInt(p[1]), Integer.parseInt(p[2]), new ArrayList<>(),
                        new HashMap<>(), null)));
            } else if (p[0].equals("move")) {
                Scene last = out.remove(out.size() - 1);
                Captured c = last.captured();
                out.add(new Scene(last.className(), last.menu(), last.width(), last.height(), last.titleCentered(),
                        last.pieces(), last.widgets(), new Captured(c.originX(), c.originY(), c.slots(), c.states(),
                        java.util.Arrays.stream(p).skip(1).mapToInt(Integer::parseInt).toArray(), c.covers(), c.shifts(), c.hidden())));
            } else if (p[0].equals("hidden")) {
                out.get(out.size() - 1).captured().hidden().add(Integer.parseInt(p[1]));
            } else if (p[0].equals("shift")) {
                out.get(out.size() - 1).captured().shifts().put(Integer.parseInt(p[1]),
                        new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3])});
            } else if (p[0].equals("cover")) {
                out.get(out.size() - 1).captured().covers().computeIfAbsent(Integer.parseInt(p[1]), k -> new ArrayList<>())
                        .add(new Piece((char) Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                                Integer.parseInt(p[5]), Integer.parseInt(p[6]), false));
            } else if (p[0].equals("spiece")) {
                out.get(out.size() - 1).captured().states().computeIfAbsent(Integer.parseInt(p[1]), k -> new ArrayList<>())
                        .add(new Piece((char) Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                                Integer.parseInt(p[5]), Integer.parseInt(p[6]), false));
            } else if (p[0].equals("cslot")) {
                out.get(out.size() - 1).captured().slots().add(new GuiCaptures.Slot(
                        Integer.parseInt(p[1]), Integer.parseInt(p[2]), Boolean.parseBoolean(p[3]),
                        Integer.parseInt(p[4]), p.length > 5 ? Integer.parseInt(p[5]) : -1));
            } else if (p[0].equals("piece") && pieces != null) {
                pieces.add(new Piece((char) Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]), Boolean.parseBoolean(p[6])));
            } else if (p[0].equals("widget") && widgets != null) {
                widgets.add(new Widget(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), p[5]));
            }
        }
        return out;
    }

    /** Adds every scanned screen to the pack and returns how many. */
    public static int contribute(Map<String, byte[]> entries, Path modsDir) throws IOException {
        return contribute(entries, modsDir, null);
    }

    /** {@code moddedIds} is the pack's item list; null reads it from the server's manifest. */
    public static int contribute(Map<String, byte[]> entries, Path modsDir, java.util.Collection<String> moddedIds)
            throws IOException {
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
            provider.addProperty("height", baked.heights().getOrDefault(e.getKey(), e.getValue().getHeight()));
            provider.addProperty("ascent", ascent);
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(e.getKey()));
            provider.add("chars", chars);
            providers.add(provider);
        }
        Map<String, String> icons = moddedIds == null ? moddedIcons(modsDir) : iconsFor(modsDir, moddedIds);
        addCapturedIcons(entries, icons, modsDir.resolveSibling("c-imagine").resolve(CAPTURES).resolve("icons"));
        // The running server may not have the jars, and must number glyphs as this pack does.
        Path iconFile = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(ICONS);
        Files.createDirectories(iconFile.getParent());
        StringBuilder listed = new StringBuilder();
        icons.forEach((id, file) -> listed.append(id).append('\t').append(file).append('\n'));
        Files.writeString(iconFile, listed.toString(), StandardCharsets.UTF_8);
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
        if (!Files.isRegularFile(manifest)) {
            return new java.util.TreeMap<>();
        }
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (dev.cim.f2v.F2vPack.VisualItem row : dev.cim.f2v.F2vPack.readManifest(manifest)) {
            wanted.add(row.moddedId());
        }
        return iconsFor(modsDir, wanted);
    }

    /** What the pack was built with when known, else whatever the jars here offer. */
    static Map<String, String> iconsForRuntime(Path modsDir) throws IOException {
        Path file = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(ICONS);
        if (!Files.isRegularFile(file)) {
            return moddedIcons(modsDir);
        }
        Map<String, String> out = new java.util.TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length == 3) {
                out.put(p[0], p[1] + "\t" + p[2]);
            }
        }
        return out;
    }

    /** Rendered icons from the real client replace flat textures for the same item. */
    private static void addCapturedIcons(Map<String, byte[]> entries, Map<String, String> icons, Path dir)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".png")).sorted().toList();
        }
        for (Path file : files) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(.+)__(.+)_s(\\d+)\\.png")
                    .matcher(file.getFileName().toString());
            if (!m.matches()) {
                continue;
            }
            int size = 16 * Integer.parseInt(m.group(3));
            BufferedImage shot = ImageIO.read(file.toFile());
            BufferedImage icon = opaque(copy(shot, 0, 0, size, size));
            // A solid last column makes every captured icon advance by 17.
            if (icon.getRGB(size - 1, size - 1) >>> 24 == 0) {
                icon.setRGB(size - 1, size - 1, 0x01000000);
            }
            String name = m.group(1) + "_" + m.group(2);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(icon, "png", png);
            entries.put("assets/" + HudPack.NS + "/textures/font/icon/" + name + ".png", png.toByteArray());
            icons.put(m.group(1) + ":" + m.group(2), HudPack.NS + ":font/icon/" + name + ".png\t17");
        }
    }

    /**
     * What the client will advance the pen by, the way BitmapProvider measures it: from the
     * last column holding any alpha. A wrong number moves everything drawn after the glyph.
     */
    private static int advance(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int right = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = img.getWidth() - 1; x >= right; x--) {
                if (img.getRGB(x, y) >>> 24 != 0) {
                    right = x + 1;
                    break;
                }
            }
        }
        return (int) (0.5 + right * 16.0 / img.getHeight()) + 1;
    }

    private static Map<String, String> iconsFor(Path modsDir, java.util.Collection<String> wanted) throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        if (!Files.isDirectory(modsDir)) {
            return out;
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
                    out.put(id, p[0] + ":" + folder + "/" + p[1] + ".png\t" + advance(png));
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
        for (String value : icons.values()) {
            String[] p = value.split("\t")[0].split(":", 2);
            String entry = "assets/" + p[0] + "/textures/" + p[1];
            if (art.containsKey(entry)) {
                entries.putIfAbsent(entry, art.get(entry));
            }
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
        Map<Character, Integer> heights = new HashMap<>();
        if (!Files.isDirectory(modsDir)) {
            return new Baked(scenes, art, heights);
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        Map<String, List<Piece>> shared = new HashMap<>();
        for (Path jar : jars) {
            Map<String, byte[]> textures = new HashMap<>();
            Map<String, byte[]> classes = new HashMap<>();
            readTextures(Files.readAllBytes(jar), textures, classes, 0);
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
                List<Widget> widgets = new ArrayList<>();
                for (ClientSurfaceScanner.ClassSurface surface : profile.clientSurfaces) {
                    for (ClientSurfaceScanner.Action a : surface.actions) {
                        if (!slashed(b.screenClass).equals(slashed(a.sourceScreen)) || a.widgetX < 0 || a.widgetY < 0
                                || a.widgetWidth <= 0 || a.widgetXAnchor != ClientSurfaceScanner.WidgetCoordinateAnchor.START
                                || a.widgetYAnchor != ClientSurfaceScanner.WidgetCoordinateAnchor.START) {
                            continue;
                        }
                        widgets.add(new Widget(a.widgetX, a.widgetY, a.widgetWidth, a.widgetHeight, a.widgetLabel));
                        GuiSpriteBytecodeScanner.Sprite icon = sprites.get(a.widgetVisualOwner + "." + a.widgetVisualField);
                        if (icon != null) {
                            int dx = Math.max(0, (a.widgetWidth - icon.width) / 2);
                            int dy = Math.max(0, (a.widgetHeight - icon.height) / 2);
                            pieces.add(new Piece(HudPack.BUTTON, a.widgetX, a.widgetY, a.widgetWidth, a.widgetHeight, false));
                            pieces.addAll(tiles(icon, textures, a.widgetX + dx, a.widgetY + dy, shared, art));
                        }
                    }
                }
                ModScanner.ScreenInfo i = info.get(slashed(b.screenClass));
                String menu = i == null || i.menuClass == null ? null : slashed(i.menuClass);
                scenes.add(new Scene(slashed(b.screenClass), menu, bg.width, bg.height,
                        measuresTitle(slashed(b.screenClass), classes), pieces, widgets, null));
            }
        }
        addCaptures(modsDir.resolveSibling("c-imagine").resolve(CAPTURES), scenes, art, heights);
        writeCache(modsDir, scenes);
        return new Baked(scenes, art, heights);
    }

    /**
     * Real rendered screens from {@code ScreenArt}. They replace any scanned scene for
     * the same menu, because they are what the mod actually draws.
     */
    private static void addCaptures(Path dir, List<Scene> scenes, Map<Character, BufferedImage> art2,
                                    Map<Character, Integer> heights) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".tsv")).sorted().toList();
        }
        Map<String, Integer> classUses = new HashMap<>();
        for (Path tsv : files) {
            for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
                if (line.startsWith("menu\t")) {
                    classUses.merge(slashed(line.split("\t")[2]), 1, Integer::sum);
                }
            }
        }
        for (Path tsv : files) {
            Path png = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".png"));
            if (!Files.isRegularFile(png)) {
                continue;
            }
            String menu = null;
            String menuId = null;
            int frameW = 0;
            int frameH = 0;
            int scale = 1;
            int left = 0;
            int top = 0;
            int xSize = 0;
            int ySize = 0;
            List<GuiCaptures.Slot> slots = new ArrayList<>();
            java.util.Set<Integer> hidden = new java.util.HashSet<>();
            for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                switch (p[0]) {
                    case "menu" -> {
                        menuId = p[1];
                        menu = slashed(p[2]);
                    }
                    case "frame" -> {
                        frameW = Integer.parseInt(p[1]);
                        frameH = Integer.parseInt(p[2]);
                        scale = Integer.parseInt(p[3]);
                    }
                    case "bounds" -> {
                        left = Integer.parseInt(p[1]);
                        top = Integer.parseInt(p[2]);
                        xSize = Integer.parseInt(p[3]);
                        ySize = Integer.parseInt(p[4]);
                    }
                    case "slot" -> {
                        // The menu index the real client gave the slot (column 5): what the
                        // server's content packet fills it from.
                        slots.add(new GuiCaptures.Slot(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                                p[3].equals("player"), p.length > 4 ? Integer.parseInt(p[4]) : -1,
                                p.length > 5 ? Integer.parseInt(p[5]) : -1));
                        // A slot the real screen never draws (column 6, the slot-render record).
                        if (p.length > 6 && p[6].equals("0")) {
                            hidden.add(Integer.parseInt(p[5]));
                        }
                    }
                    default -> {
                    }
                }
            }
            BufferedImage image = ImageIO.read(png.toFile());
            if (menu == null || image == null) {
                continue;
            }
            List<Piece> pieces = captureTiles(opaque(image, frameOf(tsv, "")), scale, left, top, art2, heights);
            if (pieces.isEmpty()) {
                continue;
            }
            // Many menu ids can share one class, so only a scanned guess is replaced.
            // Its buttons are kept when that class belongs to this menu alone, because
            // the capture has their pixels but not where a click should land.
            String key = menu;
            List<Widget> widgets = new ArrayList<>();
            Map<Integer, List<Piece>> states = new HashMap<>();
            Map<Integer, List<Piece>> covers = new HashMap<>();
            Map<Integer, int[]> shifts = new HashMap<>();
            int[] move = null;
            Path presses = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".presses"));
            if (Files.isRegularFile(presses)) {
                // What the real client pressed is the exact list of controls, in order.
                for (String line : Files.readAllLines(presses, StandardCharsets.UTF_8)) {
                    String[] q = line.split("\t");
                    if (q[0].equals("move") && move == null) {
                        // Every click of the move, in order (a third puts a copied stack back).
                        move = java.util.Arrays.stream(q).skip(1).mapToInt(Integer::parseInt).toArray();
                        // How the screen itself looks once the slot holds something (a present's
                        // label field turns editable): the move's own art, items lifted, as a state.
                        Path moveArt = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".move.png"));
                        BufferedImage moveImage = Files.isRegularFile(moveArt) ? ImageIO.read(moveArt.toFile()) : null;
                        if (moveImage != null) {
                            BufferedImage shown = opaque(moveImage, frameOf(tsv, ".move"));
                            states.put(MOVE_STATE, captureTiles(shown, scale, left, top, art2, heights));
                            BufferedImage over = coverArt(image, moveImage, shown, slots, scale, left, top);
                            if (over != null) {
                                covers.put(MOVE_STATE, captureTiles(over, scale, left, top, art2, heights));
                            }
                        }
                    } else if (q[0].equals("press")) {
                        int i = widgets.size();
                        // What the real press sent rides the label: the HUD presses the scanned
                        // button that sends the same payload.
                        String sent = q.length > 8 && !q[8].equals("-") ? " sent=" + q[8] : "";
                        widgets.add(new Widget(Integer.parseInt(q[2]), Integer.parseInt(q[3]), Integer.parseInt(q[4]),
                                Integer.parseInt(q[5]), "press " + q[1] + sent));
                        if (q.length > 7 && (Integer.parseInt(q[6]) != 0 || Integer.parseInt(q[7]) != 0)) {
                            shifts.put(i, new int[]{Integer.parseInt(q[6]), Integer.parseInt(q[7])});
                        }
                        Path art = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".w" + q[1] + ".png"));
                        BufferedImage stateImage = Files.isRegularFile(art) ? ImageIO.read(art.toFile()) : null;
                        if (stateImage != null) {
                            BufferedImage shown = opaque(stateImage, frameOf(tsv, ".w" + q[1]));
                            states.put(i, captureTiles(shown, scale, left, top, art2, heights));
                            BufferedImage over = coverArt(image, stateImage, shown, slots, scale, left, top);
                            if (over != null) {
                                covers.put(i, captureTiles(over, scale, left, top, art2, heights));
                            }
                        }
                    }
                }
            }
            if (widgets.isEmpty() && classUses.getOrDefault(key, 0) == 1) {
                for (Scene s : scenes) {
                    if (s.captured() == null && key.equals(s.menu())) {
                        widgets.addAll(s.widgets());
                    }
                }
            }
            scenes.removeIf(s -> s.captured() == null && key.equals(s.menu()));
            int guiW = (frameW + scale - 1) / scale;
            int guiH = (frameH + scale - 1) / scale;
            scenes.add(new Scene(menuId, menu, xSize, ySize, false, pieces, widgets,
                    new Captured(left - guiW / 2, top - guiH / 2, slots, states, move, covers, shifts, hidden)));
        }
    }

    /**
     * What a press state draws over the slots: the state's pixels, in each slot's item and count
     * square, where they differ from the base art (both captured with the inventory lifted, so
     * only the screen itself differs), or null. Drawn after the items, it hides what a window
     * covers and leaves what it does not (measured: Mekanism's Upgrades window hides the slots
     * beneath it; its side configuration leaves a diamond's count showing below its edge).
     */
    static BufferedImage coverArt(BufferedImage base, BufferedImage state, BufferedImage shown,
                                  List<GuiCaptures.Slot> slots, int scale, int left, int top) {
        if (base.getWidth() != state.getWidth() || base.getHeight() != state.getHeight()
                || shown.getWidth() != state.getWidth() || shown.getHeight() != state.getHeight()) {
            return null;
        }
        BufferedImage out = null;
        for (GuiCaptures.Slot slot : slots) {
            if (slot.x() <= -1000) {
                continue;
            }
            // The item and its count: 16 pixels, the count's shadow one more past the 17th.
            int x0 = (left + slot.x()) * scale;
            int y0 = (top + slot.y()) * scale;
            int size = 18 * scale;
            for (int y = Math.max(0, y0); y < Math.min(base.getHeight(), y0 + size); y++) {
                for (int x = Math.max(0, x0); x < Math.min(base.getWidth(), x0 + size); x++) {
                    if (base.getRGB(x, y) != state.getRGB(x, y)) {
                        if (out == null) {
                            out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        }
                        out.setRGB(x, y, shown.getRGB(x, y));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Some draws write colour with zero alpha. A real framebuffer is opaque, so that
     * colour is what a player sees: any pixel holding colour counts as solid.
     */
    private static BufferedImage opaque(BufferedImage src) {
        return opaque(src, null);
    }

    /**
     * {@code frame}: the real screenshot taken with the capture. A translucent capture pixel is
     * either a shadow over the world (the frame shows the world through it) or a pixel the
     * screen covered with a blend that left alpha below 255 (AE2's "Out of Power" overlay: the
     * frame shows exactly the captured colour). Only the first stays translucent.
     */
    private static BufferedImage opaque(BufferedImage src, BufferedImage frame) {
        boolean judge = frame != null && frame.getWidth() == src.getWidth() && frame.getHeight() == src.getHeight();
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int c = src.getRGB(x, y);
                int alpha = c >>> 24;
                if (alpha == 0 && (c & 0xFFFFFF) != 0 && judge && near(c, frame.getRGB(x, y))) {
                    // Colour with zero alpha is solid in a real framebuffer, and the real frame
                    // says which colour: drawn over the screen's background, a little darker than
                    // the capture over nothing (measured: Mekanism's window border, 3-4 levels).
                    out.setRGB(x, y, 0xFF000000 | (frame.getRGB(x, y) & 0xFFFFFF));
                } else if (alpha == 0) {
                    // Colour with zero alpha is solid in a real framebuffer.
                    out.setRGB(x, y, (c & 0xFFFFFF) != 0 ? 0xFF000000 | c : c);
                } else if (alpha < 255 && judge && !sameColour(c, frame.getRGB(x, y)) && near(c, frame.getRGB(x, y))) {
                    // The same, for a covered pixel whose blend left alpha below 255.
                    out.setRGB(x, y, 0xFF000000 | (frame.getRGB(x, y) & 0xFFFFFF));
                } else if (alpha < 255 && (!judge || sameColour(c, frame.getRGB(x, y)))) {
                    // The real frame shows the captured colour itself: the screen covered this pixel.
                    out.setRGB(x, y, 0xFF000000 | c);
                } else if (alpha < 255) {
                    // Nothing of the screen beneath it: a shadow over the world. The capture holds
                    // it premultiplied (drawn over transparent black); the client blends a glyph
                    // as colour*alpha over the world, so the colour goes back to straight.
                    int r = Math.min(255, Math.round(((c >> 16) & 0xFF) * 255f / alpha));
                    int g = Math.min(255, Math.round(((c >> 8) & 0xFF) * 255f / alpha));
                    int b = Math.min(255, Math.round((c & 0xFF) * 255f / alpha));
                    out.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
                } else {
                    out.setRGB(x, y, c);
                }
            }
        }
        return out;
    }

    /** Within a few levels per channel: the same pixel drawn over a slightly different backdrop. */
    private static boolean near(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= 8
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= 8
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= 8;
    }

    private static boolean sameColour(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= 1
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= 1
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= 1;
    }

    private static BufferedImage frameOf(Path tsv, String suffix) {
        Path f = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", suffix + ".frame"));
        try {
            return Files.isRegularFile(f) ? ImageIO.read(f.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Cuts the opaque area into glyphs that land one GUI pixel per {@code scale} source pixels. */
    private static List<Piece> captureTiles(BufferedImage img, int scale, int left, int top,
                                            Map<Character, BufferedImage> art, Map<Character, Integer> heights)
            throws IOException {
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) >>> 24 != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        List<Piece> out = new ArrayList<>();
        if (maxX < 0) {
            return out;
        }
        minX -= minX % scale;
        minY -= minY % scale;
        int step = TILE / scale * scale;
        for (int y = minY; y <= maxY; y += step) {
            for (int x = minX; x <= maxX; x += step) {
                int w = Math.min(step, (maxX + scale - x) / scale * scale);
                int h = Math.min(step, (maxY + scale - y) / scale * scale);
                BufferedImage tile = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                boolean any = false;
                for (int ty = 0; ty < h; ty++) {
                    for (int tx = 0; tx < w; tx++) {
                        if (x + tx < img.getWidth() && y + ty < img.getHeight()) {
                            int c = img.getRGB(x + tx, y + ty);
                            tile.setRGB(tx, ty, c);
                            any |= c >>> 24 != 0;
                        }
                    }
                }
                if (!any) {
                    continue;
                }
                char glyph = (char) (GLYPH_BASE + art.size());
                if (glyph >= GLYPH_LIMIT) {
                    throw new IOException("out of screen glyphs");
                }
                if (tile.getRGB(w - 1, h - 1) >>> 24 == 0) {
                    tile.setRGB(w - 1, h - 1, 0x01000000);
                }
                art.put(glyph, tile);
                heights.put(glyph, h / scale);
                out.add(new Piece(glyph, x / scale - left, y / scale - top, w / scale, h / scale, false));
            }
        }
        return out;
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

    /**
     * True when the screen or a superclass measures text with {@code Font.width}
     * while drawing, which is how a screen centres its title.
     */
    private static boolean measuresTitle(String screen, Map<String, byte[]> classes) {
        for (String at = screen; at != null && classes.containsKey(at); ) {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(classes.get(at));
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (!method.name.equals("renderBg") && !method.name.equals("renderLabels")) {
                    continue;
                }
                for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                            && call.owner.equals("net/minecraft/client/gui/Font") && call.name.equals("width")) {
                        return true;
                    }
                }
            }
            at = node.superName;
        }
        return false;
    }

    private static void readTextures(byte[] jar, Map<String, byte[]> out, Map<String, byte[]> classes, int depth)
            throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String name = e.getName();
                if (name.startsWith("assets/") && name.contains("/textures/gui/") && name.endsWith(".png")) {
                    out.put(name, zip.readAllBytes());
                } else if (name.endsWith(".class")) {
                    classes.put(name.substring(0, name.length() - 6), zip.readAllBytes());
                } else if (depth < 2 && name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                    readTextures(zip.readAllBytes(), out, classes, depth + 1);
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
