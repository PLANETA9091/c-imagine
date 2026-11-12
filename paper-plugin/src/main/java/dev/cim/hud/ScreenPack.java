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
     * move the real client made first, or null. {@code movePieces} is the visual state after move.
     */
    public record Captured(int originX, int originY, List<GuiCaptures.Slot> slots,
                           Map<Integer, List<Piece>> states, int[] move, List<Piece> movePieces) {
    }

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

    /**
     * 14-di: the merged scene list the run-capture overlay pass produced at
     * pack boot, or null. The overlay pass is the glyph-numbering source of
     * truth for the run's captures — the replay MUST ride its scene list so
     * both sides agree on which char carries which pixels.
     */
    private static volatile List<Scene> merged;

    /** The scanned scenes, from the cache when the jars have not changed. */
    public static List<Scene> scenes(Path modsDir) throws IOException {
        List<Scene> m = merged;
        if (m != null) {
            return m;
        }
        Path cache = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(CACHE);
        String stamp = stamp(modsDir);
        if (Files.isRegularFile(cache)) {
            List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
            // Without the capture folder a rescan would lose art the pack was built
            // with, as on a server that only received the seed's pack and scenes.
            boolean canRebake = Files.isDirectory(modsDir.resolveSibling("c-imagine").resolve(CAPTURES));
            if (!lines.isEmpty() && lines.get(0).equals(stamp)) {
                return parse(lines);
            }
            if (!lines.isEmpty() && !canRebake) {
                return parse(lines);
            }
            if (!lines.isEmpty() && stamp.equals("v9")) {
                // 14-di: a no-jars boot cannot rescan the seed's scanned scenes —
                // keep them and merge the run's captures over the cache instead of
                // dropping everything the jars (absent here) would have scanned.
                return mergeNoJars(modsDir, lines);
            }
        }
        return bake(modsDir).scenes();
    }

    /**
     * 14-di slice 5: the cached scenes plus the run's ScreenArt captures, glyph
     * numbering continued after the highest glyph the cache itself names. Serves
     * the no-jars boot that runs without the pack-overlay pass — the replay then
     * renders the new captures' glyphs only if the served pack carried them (the
     * honest degrade is blank art, never a crash).
     */
    private static List<Scene> mergeNoJars(Path modsDir, List<String> lines) throws IOException {
        List<Scene> scenes = parse(lines);
        int maxUsed = GLYPH_BASE - 1;
        for (String line : lines) {
            String[] p = line.split("\t", -1);
            if ((p[0].equals("piece") || p[0].equals("spiece") || p[0].equals("mpiece"))
                    && p.length > 1 && !p[1].isEmpty()) {
                try {
                    maxUsed = Math.max(maxUsed, Integer.parseInt(p[1]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Map<Character, BufferedImage> art = new HashMap<>();
        Map<Character, Integer> heights = new HashMap<>();
        for (int c = GLYPH_BASE; c <= maxUsed; c++) {
            art.put((char) c, new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        }
        addCaptures(modsDir.resolveSibling("c-imagine").resolve(CAPTURES), scenes, art, heights, capturedMenus(scenes));
        return scenes;
    }

    /** Menus the seed already captured: the run's identical capture adds nothing. */
    private static java.util.Set<String> capturedMenus(List<Scene> scenes) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Scene s : scenes) {
            if (s.captured() != null && s.className() != null) {
                out.add(s.className());
            }
        }
        return out;
    }

    /** One menu's ScreenArt capture: the run art the truth client dumped. */
    public record RunOverlay(List<Scene> scenes, java.util.TreeMap<String, byte[]> entries,
                             int seedGlyphs, int newGlyphs, String fontEntry) {
    }

    /**
     * 14-di the run-capture overlay: the truth client dumped every menu it opened
     * (ScreenArt) into the captures dir; the seed's pack cannot carry scenes the
     * seed's walk never opened (the generic-screen class ceiling, the opener
     * asymmetry). ONE pass parses the cached scenes, merges the run's captures
     * (deterministic sorted order), allocates the NEW glyphs after the pack's
     * existing screen-glyph block and bakes them as extra providers onto the same
     * font json — the returned entries complete the served pack, the returned
     * scenes drive the replay, and both sides agree on the glyph numbering
     * because they share this single allocation. Returns null when there is
     * nothing to overlay (no captures, no cache, no screen-glyph font).
     */
    public static synchronized RunOverlay mergeRunCaptures(Path modsDir, Map<String, byte[]> packEntries)
            throws IOException {
        Path captures = modsDir.resolveSibling("c-imagine").resolve(CAPTURES);
        Path cache = modsDir.resolveSibling("c-imagine").resolve("f2v").resolve(CACHE);
        if (!Files.isDirectory(captures) || !Files.isRegularFile(cache)) {
            return null;
        }
        try (Stream<Path> list = Files.list(captures)) {
            if (list.filter(p -> p.toString().endsWith(".tsv")).count() == 0) {
                return null;
            }
        }
        List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return null;
        }
        // The pack's screen-glyph block: every bitmap char in [GLYPH_BASE,
        // GLYPH_LIMIT) across the pack's cimhud fonts, plus the font json that
        // carries them. The block must be contiguous from GLYPH_BASE — that is
        // what captureTiles' sequential allocation produced at seed time.
        java.util.TreeMap<String, byte[]> out = new java.util.TreeMap<>(packEntries);
        String fontEntry = null;
        JsonObject fontJson = null;
        java.util.TreeSet<Integer> used = new java.util.TreeSet<>();
        for (Map.Entry<String, byte[]> e : packEntries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith("assets/" + HudPack.NS + "/font/") || !name.endsWith(".json")) {
                continue;
            }
            JsonObject font = com.google.gson.JsonParser.parseString(
                    new String(e.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
            boolean screenFont = false;
            for (var pe : font.getAsJsonArray("providers")) {
                JsonObject po = pe.getAsJsonObject();
                if (!po.get("type").getAsString().equals("bitmap") || !po.has("chars")) {
                    continue;
                }
                for (var ch : po.getAsJsonArray("chars")) {
                    String s = ch.getAsString();
                    for (int i = 0; i < s.length(); i++) {
                        int c = s.codePointAt(i);
                        if (c >= GLYPH_BASE && c < GLYPH_LIMIT) {
                            used.add(c);
                            screenFont = true;
                        }
                    }
                }
            }
            if (screenFont && fontEntry == null) {
                fontEntry = name;
                fontJson = font;
            }
        }
        if (fontEntry == null || used.isEmpty()
                || used.first() != GLYPH_BASE
                || used.size() != used.last() - GLYPH_BASE + 1) {
            return null;
        }
        Map<Character, BufferedImage> art = new HashMap<>();
        Map<Character, Integer> heights = new HashMap<>();
        for (int c : used) {
            art.put((char) c, new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
        }
        List<Scene> scenes = parse(lines);
        addCaptures(captures, scenes, art, heights, capturedMenus(scenes));
        int seedGlyphs = used.size();
        JsonArray providers = fontJson.getAsJsonArray("providers");
        int added = 0;
        for (char c = (char) (GLYPH_BASE + seedGlyphs); c < (char) (GLYPH_BASE + art.size()); c++) {
            BufferedImage img = art.get(c);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            String file = "screen/" + (int) c + ".png";
            out.put("assets/" + HudPack.NS + "/textures/font/" + file, png.toByteArray());
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", HudPack.NS + ":font/" + file);
            provider.addProperty("height", heights.getOrDefault(c, img.getHeight()));
            provider.addProperty("ascent", HudLayout.ascent(HudLayout.freeId()));
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(c));
            provider.add("chars", chars);
            providers.add(provider);
            added++;
        }
        if (added > 0) {
            out.put(fontEntry, fontJson.toString().getBytes(StandardCharsets.UTF_8));
        }
        merged = List.copyOf(scenes);
        return new RunOverlay(merged, out, seedGlyphs, added, fontEntry);
    }

    private static String stamp(Path modsDir) throws IOException {
        StringBuilder out = new StringBuilder("v9");
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
                    out.append("move\t").append(s.captured().move()[0]).append('\t')
                            .append(s.captured().move()[1]);
                    if (s.captured().move().length > 2) {
                        out.append('\t').append(s.captured().move()[2]);
                    }
                    out.append('\n');
                }
                if (s.captured().movePieces() != null) {
                    for (Piece q : s.captured().movePieces()) {
                        out.append("mpiece\t").append((int) q.glyph()).append('\t')
                                .append(q.x()).append('\t').append(q.y()).append('\t').append(q.w()).append('\t')
                                .append(q.h()).append('\n');
                    }
                }
                for (var st : s.captured().states().entrySet()) {
                    for (Piece q : st.getValue()) {
                        out.append("spiece\t").append(st.getKey()).append('\t').append((int) q.glyph()).append('\t')
                                .append(q.x()).append('\t').append(q.y()).append('\t').append(q.w()).append('\t')
                                .append(q.h()).append('\n');
                    }
                }
                for (GuiCaptures.Slot slot : s.captured().slots()) {
                    out.append("cslot\t").append(slot.x()).append('\t').append(slot.y()).append('\t')
                            .append(slot.player()).append('\t').append(slot.index()).append('\n');
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
                        new HashMap<>(), null, new ArrayList<>())));
            } else if (p[0].equals("move")) {
                Scene last = out.remove(out.size() - 1);
                Captured c = last.captured();
                int[] m = p.length > 3
                        ? new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])}
                        : new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2]), 1};
                out.add(new Scene(last.className(), last.menu(), last.width(), last.height(), last.titleCentered(),
                        last.pieces(), last.widgets(), new Captured(c.originX(), c.originY(), c.slots(), c.states(),
                        m, c.movePieces())));
            } else if (p[0].equals("mpiece")) {
                Captured c = out.get(out.size() - 1).captured();
                if (c != null && c.movePieces() != null) {
                    c.movePieces().add(new Piece((char) Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Integer.parseInt(p[4]), Integer.parseInt(p[5]), false));
                }
            } else if (p[0].equals("spiece")) {
                out.get(out.size() - 1).captured().states().computeIfAbsent(Integer.parseInt(p[1]), k -> new ArrayList<>())
                        .add(new Piece((char) Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                                Integer.parseInt(p[5]), Integer.parseInt(p[6]), false));
            } else if (p[0].equals("cslot")) {
                out.get(out.size() - 1).captured().slots().add(new GuiCaptures.Slot(
                        Integer.parseInt(p[1]), Integer.parseInt(p[2]), Boolean.parseBoolean(p[3]),
                        Integer.parseInt(p[4])));
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
        addCaptures(dir, scenes, art2, heights, java.util.Set.of());
    }

    /**
     * {@code skipMenus} names menus (by ScreenArt id) whose captured scene already
     * exists — the seed captured them, the run's identical capture must not spend
     * glyphs on the same pixels again (14-di).
     */
    private static void addCaptures(Path dir, List<Scene> scenes, Map<Character, BufferedImage> art2,
                                    Map<Character, Integer> heights, java.util.Set<String> skipMenus) throws IOException {
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
                    case "slot" -> slots.add(new GuiCaptures.Slot(Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                            p[3].equals("player"), p.length > 4 ? Integer.parseInt(p[4]) : -1));
                    default -> {
                    }
                }
            }
            if (menuId != null && skipMenus.contains(menuId)) {
                continue;
            }
            BufferedImage image = ImageIO.read(png.toFile());
            if (menu == null || image == null) {
                continue;
            }
            List<Piece> pieces = captureTiles(opaque(image), scale, left, top, art2, heights);
            if (pieces.isEmpty()) {
                continue;
            }
            // Many menu ids can share one class, so only a scanned guess is replaced.
            // Its buttons are kept when that class belongs to this menu alone, because
            // the capture has their pixels but not where a click should land.
            String key = menu;
            List<Widget> widgets = new ArrayList<>();
            Map<Integer, List<Piece>> states = new HashMap<>();
            int[] move = null;
            List<Piece> movePieces = null;
            Path presses = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".presses"));
            if (Files.isRegularFile(presses)) {
                // What the real client pressed is the exact list of controls, in order.
                for (String line : Files.readAllLines(presses, StandardCharsets.UTF_8)) {
                    String[] q = line.split("\t");
                    if (q[0].equals("move") && move == null) {
                        boolean accepted = q.length > 3 ? Boolean.parseBoolean(q[3]) : true;
                        move = new int[]{Integer.parseInt(q[1]), Integer.parseInt(q[2]), accepted ? 1 : 0};
                        Path moveArt = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".move.png"));
                        if (Files.isRegularFile(moveArt)) {
                            BufferedImage moveImage = ImageIO.read(moveArt.toFile());
                            if (moveImage != null) {
                                movePieces = captureTiles(opaque(moveImage), scale, left, top, art2, heights);
                            }
                        }
                    } else if (q[0].equals("press")) {
                        int i = widgets.size();
                        int targetId = Integer.parseInt(q[1]);
                        widgets.add(new Widget(Integer.parseInt(q[2]), Integer.parseInt(q[3]), Integer.parseInt(q[4]),
                                Integer.parseInt(q[5]), "press " + targetId));
                        Path art = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".w" + targetId + ".png"));
                        BufferedImage stateImage = Files.isRegularFile(art) ? ImageIO.read(art.toFile()) : null;
                        if (stateImage != null) {
                            List<Piece> tiles = captureTiles(opaque(stateImage), scale, left, top, art2, heights);
                            states.put(targetId, tiles);
                            states.put(i, tiles);
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
                    new Captured(left - guiW / 2, top - guiH / 2, slots, states, move, movePieces)));
        }
    }

    /**
     * Some draws write colour with zero alpha. A real framebuffer is opaque, so that
     * colour is what a player sees: any pixel holding colour counts as solid.
     */
    private static BufferedImage opaque(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int c = src.getRGB(x, y);
                out.setRGB(x, y, (c & 0xFFFFFF) != 0 ? 0xFF000000 | c : c);
            }
        }
        return out;
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

    /** One synthetic ScreenArt dump: a 4x4 opaque square + its capture tsv. */
    private static void overlayCapture(Path dir, String id) throws IOException {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, 0xFFCC0000);
            }
        }
        ImageIO.write(img, "png", dir.resolve(id + ".png").toFile());
        String tsv = "menu\t" + id + "\tsome/Screen/Class\n"
                + "frame\t4\t4\t1\n"
                + "bounds\t8\t17\t4\t4\n";
        Files.writeString(dir.resolve(id + ".tsv"), tsv, StandardCharsets.UTF_8);
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
        // 14-di the run-capture overlay selftest: a synthetic seed pack, a
        // synthetic ScreenArt dump, the whole merge law offline (numbering,
        // skip law, font append, the no-jars merge that replaced the rebake).
        if (args.length > 2 && args[2].equals("overlay")) {
            Path t = Files.createTempDirectory("cim-overlay");
            Path mods = t.resolve("mods");
            Path ci = t.resolve("c-imagine");
            Files.createDirectories(mods);
            Files.createDirectories(ci.resolve("f2v"));
            Files.createDirectories(ci.resolve("screens"));
            merged = null;   // the static is shared across selftest phases
            // The seed cache: one scanned guess (no capture), one seed-captured
            // scene. Line 0 is the seed's stamp — NOT this boot's "v9" — so the
            // no-jars merge path fires instead of the plain parse.
            List<String> cache = List.of(
                    "v9|seed.jar:1234",
                    "scene\tvanilla/chest\tvanilla/chest\t176\t166\tfalse",
                    "piece\t57344\t0\t0\t4\t4\tfalse",
                    "scene\tcreate_fa\tcreate/fa\t176\t166\tfalse",
                    "captured\t-88\t-83",
                    "cslot\t0\t0\tfalse\t-1",
                    "piece\t57344\t0\t0\t4\t4\tfalse");
            Files.write(ci.resolve("f2v").resolve("screens.tsv"), cache, StandardCharsets.UTF_8);
            overlayCapture(ci.resolve("screens"), "new_menu_a");   // new: must ride the overlay
            overlayCapture(ci.resolve("screens"), "create_fa");    // seed-captured: must be skipped
            JsonObject font = new JsonObject();
            JsonArray providers = new JsonArray();
            JsonObject p0 = new JsonObject();
            p0.addProperty("type", "bitmap");
            p0.addProperty("file", HudPack.NS + ":font/screen/58112.png");
            p0.addProperty("height", 240);
            p0.addProperty("ascent", HudLayout.ascent(HudLayout.freeId()));
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf((char) 58112));
            p0.add("chars", chars);
            providers.add(p0);
            font.add("providers", providers);
            java.util.TreeMap<String, byte[]> pack = new java.util.TreeMap<>();
            String fontName = "assets/" + HudPack.NS + "/font/" + HudLayout.fontName(HudLayout.freeId()) + ".json";
            pack.put(fontName, font.toString().getBytes(StandardCharsets.UTF_8));
            pack.put("assets/" + HudPack.NS + "/textures/font/screen/58112.png", new byte[16]);
            RunOverlay ov = mergeRunCaptures(mods, pack);
            bad += check(ov != null, "the overlay pass runs");
            if (ov != null) {
                bad += check(ov.seedGlyphs() == 1, "one seed glyph counted, got " + ov.seedGlyphs());
                bad += check(ov.newGlyphs() == 1,
                        "one new glyph baked (the captured menu's single tile), got " + ov.newGlyphs());
                bad += check(ov.entries().containsKey("assets/" + HudPack.NS + "/textures/font/screen/58113.png"),
                        "the new glyph rides the next char after the seed's block");
                JsonObject mergedFont = com.google.gson.JsonParser.parseString(new String(
                        ov.entries().get(ov.fontEntry()), StandardCharsets.UTF_8)).getAsJsonObject();
                bad += check(mergedFont.getAsJsonArray("providers").size() == 2,
                        "the overlay appends its provider to the same font json");
                Scene nm = ov.scenes().stream().filter(s -> s.className().equals("new_menu_a"))
                        .findFirst().orElse(null);
                bad += check(nm != null && nm.captured() != null && !nm.pieces().isEmpty(),
                        "the run capture became a captured scene");
                bad += check(ov.scenes().stream().filter(s -> s.className().equals("create_fa")).count() == 1,
                        "the seed's captured scene was not duplicated (skip law)");
                bad += check(ov.scenes().stream().anyMatch(s -> s.className().equals("vanilla/chest")),
                        "the seed's scanned guess survives (no run art for it)");
            }
            List<Scene> viaScenes = scenes(mods);
            bad += check(ov != null && viaScenes.equals(ov.scenes()),
                    "scenes() returns the overlay's merged list");
            merged = null;
            List<Scene> noJar = scenes(mods);
            bad += check(noJar.stream().anyMatch(s -> s.className().equals("new_menu_a") && s.captured() != null),
                    "the no-jars merge keeps the run capture");
            bad += check(noJar.stream().anyMatch(s -> s.className().equals("vanilla/chest")),
                    "the no-jars merge keeps the cached scanned scenes (the pre-14-di rebake dropped them)");
            // An empty captures dir: the overlay pass must decline (null), not bake.
            Path empty = Files.createTempDirectory("cim-overlay-empty");
            Files.createDirectories(empty.resolve("c-imagine").resolve("f2v"));
            Files.createDirectories(empty.resolve("c-imagine").resolve("screens"));
            Files.write(empty.resolve("c-imagine").resolve("f2v").resolve("screens.tsv"),
                    List.of("v9", "scene\ta/x\ta/x\t1\t1\tfalse"), StandardCharsets.UTF_8);
            RunOverlay none = mergeRunCaptures(empty.resolve("mods"), pack);
            bad += check(none == null, "no captures -> no overlay");
            merged = null;
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
