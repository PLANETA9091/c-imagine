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
                           Map<Integer, int[]> shifts, java.util.Set<Integer> hidden, Map<String, List<Piece>> fills,
                           Map<String, int[]> placed) {
        public Captured(int originX, int originY, List<GuiCaptures.Slot> slots,
                        Map<Integer, List<Piece>> states, int[] move) {
            this(originX, originY, slots, states, move, new HashMap<>(), new HashMap<>(), new java.util.HashSet<>(),
                    new HashMap<>(), new HashMap<>());
        }

        /** Where a slot sits in a press state that laid the screen out again, or null. */
        public int[] placed(int state, int menuIndex) {
            return placed.get(state + ":" + menuIndex);
        }

        /** A filled slot's background in a state (-1: as opened), drawn before its item, or null. */
        public List<Piece> fill(int state, int menuIndex) {
            return fills.get(state + ":" + menuIndex);
        }

        /** The wash a press state lays over its slots' items (its font row), or -1. */
        public int wash(int state) {
            int[] w = placed.get("wash:" + state);
            return w == null ? -1 : w[0];
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
        StringBuilder out = new StringBuilder("v19");
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
                for (var fl : s.captured().fills().entrySet()) {
                    for (Piece q : fl.getValue()) {
                        out.append("fill\t").append(fl.getKey()).append('\t').append((int) q.glyph()).append('\t')
                                .append(q.x()).append('\t').append(q.y()).append('\t').append(q.w()).append('\t')
                                .append(q.h()).append('\n');
                    }
                }
                for (var pl : s.captured().placed().entrySet()) {
                    out.append("placed\t").append(pl.getKey()).append('\t').append(pl.getValue()[0]).append('\t')
                            .append(pl.getValue()[1]).append('\n');
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
                        java.util.Arrays.stream(p).skip(1).mapToInt(Integer::parseInt).toArray(), c.covers(), c.shifts(), c.hidden(), c.fills(), c.placed())));
            } else if (p[0].equals("fill")) {
                out.get(out.size() - 1).captured().fills().computeIfAbsent(p[1], k -> new ArrayList<>())
                        .add(new Piece((char) Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                                Integer.parseInt(p[5]), Integer.parseInt(p[6]), false));
            } else if (p[0].equals("placed")) {
                out.get(out.size() - 1).captured().placed().put(p[1],
                        new int[]{Integer.parseInt(p[2]), Integer.parseInt(p[3])});
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
        ItemGlyphs itemGlyphs = new ItemGlyphs(icons);
        providers.addAll(itemGlyphs.providers());
        // Washed states: the shader holds each wash, a font row each draws the icons and the
        // counts through it (see HudLayout.Anchor.FREE_WASH).
        entries.put("assets/minecraft/shaders/core/text.vsh",
                HudLayout.vertexShader(WASHES).getBytes(StandardCharsets.UTF_8));
        for (int w = 0; w < WASHES.size(); w++) {
            int id = HudLayout.washId(HudLayout.Anchor.FREE_WASH, w);
            JsonObject washed = HudPack.font(id);
            washed.getAsJsonArray("providers").addAll(itemGlyphs.providers(HudLayout.ascent(id)));
            entries.put("assets/" + HudPack.NS + "/font/" + HudLayout.fontName(id) + ".json",
                    washed.toString().getBytes(StandardCharsets.UTF_8));
        }
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

    /** Every wash the bake found, by font row: alpha, red, green, blue. */
    static final List<double[]> WASHES = new ArrayList<>();
    /** The wash the last {@link #coverArt} recognised (k, then red, green, blue added), or null. */
    private static double[] lastWash;

    private static void noteWash(Map<String, int[]> placed, int state) {
        if (lastWash == null) {
            return;
        }
        double[] w = lastWash;
        int index = -1;
        for (int i = 0; i < WASHES.size(); i++) {
            if (java.util.Arrays.equals(WASHES.get(i), w)) {
                index = i;
            }
        }
        if (index < 0 && WASHES.size() < HudLayout.WASHES) {
            WASHES.add(w);
            index = WASHES.size() - 1;
        }
        if (index >= 0) {
            placed.put("wash:" + state, new int[]{index, 0});
        }
    }

    private static Baked bake(Path modsDir) throws IOException {
        WASHES.clear();
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
            java.util.Set<Integer> hidden = new java.util.HashSet<>();
            // Slots the screen draws through the vanilla body (column 6 = 1): only there does the
            // filled pass show a filled slot's own background.
            java.util.Set<Integer> vanillaDrawn = new java.util.HashSet<>();
            Map<String, List<Piece>> fills = new HashMap<>();
            Map<String, int[]> placed = new HashMap<>();
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
                        if (p.length > 6 && p[6].equals("1")) {
                            vanillaDrawn.add(Integer.parseInt(p[5]));
                        }
                    }
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
            // A screen that draws its slots through the vanilla body: its art with the items left in
            // their slots and only their pixels skipped (the filled pass), so the screen's own logic
            // sees what the slots hold (a present's label field turns editable once it holds
            // something) and a filled slot shows no placeholder. Otherwise the lifted art.
            // Covers compare the lifted arts, where every slot looks empty alike: a window over a
            // slot shows, a slot that only looks different once filled (AE2's placeholder) does not.
            BufferedImage liftedBase = image;
            image = chooseArt(image, filledOf(tsv, ""), vanillaDrawn);
            BufferedImage baseFrame = frameOf(tsv, "");
            BufferedImage baseShown = opaque(image, baseFrame);
            List<Piece> pieces = captureTiles(baseShown, scale, left, top, art2, heights);
            if (pieces.isEmpty()) {
                continue;
            }
            // Many menu ids can share one class, so only a scanned guess is replaced.
            // Its buttons are kept when that class belongs to this menu alone, because
            // the capture has their pixels but not where a click should land.
            String key = menu;
            addFills(fills, -1, image, filledOf(tsv, ""), slots, vanillaDrawn, scale, left, top, art2, heights);
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
                        BufferedImage liftedMove = moveImage;
                        moveImage = moveImage == null ? null : chooseArt(moveImage, filledOf(tsv, ".move"), vanillaDrawn);
                        if (moveImage != null) {
                            BufferedImage shown = opaque(moveImage, frameOf(tsv, ".move"));
                            BufferedImage over = coverArt(liftedBase, liftedMove, shown, baseShown, baseFrame,
                                    frameOf(tsv, ".move"), filledOf(tsv, ".move"), slots,
                                    statePositions(slots, placed, MOVE_STATE, null), scale, left, top);
                            states.put(MOVE_STATE, captureTiles(shown, scale, left, top, art2, heights));
                            addFills(fills, MOVE_STATE, moveImage, filledOf(tsv, ".move"), slots, vanillaDrawn, scale,
                                    left, top, art2, heights);
                            if (over != null) {
                                covers.put(MOVE_STATE, captureTiles(over, scale, left, top, art2, heights));
                            }
                            noteWash(placed, MOVE_STATE);
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
                        readPlaced(placed, i, tsv.resolveSibling(tsv.getFileName().toString()
                                .replaceAll("\\.tsv$", ".w" + q[1] + ".slots")), slots);
                        Path art = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", ".w" + q[1] + ".png"));
                        BufferedImage stateImage = Files.isRegularFile(art) ? ImageIO.read(art.toFile()) : null;
                        BufferedImage liftedState = stateImage;
                        stateImage = stateImage == null ? null : chooseArt(stateImage, filledOf(tsv, ".w" + q[1]), vanillaDrawn);
                        if (stateImage != null) {
                            BufferedImage shown = opaque(stateImage, frameOf(tsv, ".w" + q[1]));
                            BufferedImage over = coverArt(liftedBase, liftedState, shown, baseShown, baseFrame,
                                    frameOf(tsv, ".w" + q[1]), filledOf(tsv, ".w" + q[1]), slots,
                                    statePositions(slots, placed, i, shifts.get(i)), scale, left, top);
                            states.put(i, captureTiles(shown, scale, left, top, art2, heights));
                            addFills(fills, i, stateImage, filledOf(tsv, ".w" + q[1]), slots, vanillaDrawn, scale,
                                    left, top, art2, heights);
                            if (over != null) {
                                covers.put(i, captureTiles(over, scale, left, top, art2, heights));
                            }
                            noteWash(placed, i);
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
                    new Captured(left - guiW / 2, top - guiH / 2, slots, states, move, covers, shifts, hidden, fills,
                            placed)));
        }
    }

    /**
     * Whether a state draws something else there, not just a shade of the same: a window or a
     * popup changes a slot's pixels outright.
     */
    private static boolean visiblyDifferent(int a, int b) {
        int da = Math.abs((a >>> 24) - (b >>> 24));
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(da, Math.max(dr, Math.max(dg, db))) > 24;
    }

    /**
     * A translucent wash a state lays over the whole screen, the items in the slots included
     * (measured: Mekanism's robit rename window tints everything behind it, 139 grey to
     * 120,120,136): one colour blended at one alpha, as the real fill blends it.
     */
    record Wash(int alpha, int r, int g, int b) {
        static int blend(int c, int a, int d) {
            return (int) Math.round((c * a + d * (255 - a)) / 255.0);
        }

        int over(int base) {
            return 0xFF000000 | blend(r, alpha, (base >> 16) & 0xFF) << 16
                    | blend(g, alpha, (base >> 8) & 0xFF) << 8 | blend(b, alpha, base & 0xFF);
        }

        boolean fits(int base, int state) {
            return base >>> 24 == 0xFF && state >>> 24 == 0xFF && over(base) == state;
        }

        int argb() {
            return alpha << 24 | r << 16 | g << 8 | b;
        }
    }

    /**
     * The wash a state casts over the slots, or null: fitted to the slot pixels that changed,
     * kept only when one colour at one alpha reproduces most of them exactly across most slots.
     */
    static Wash washOf(BufferedImage base, BufferedImage state, List<GuiCaptures.Slot> slots, List<int[]> at,
                       int scale, int left, int top) {
        Map<Long, Integer> pairs = new HashMap<>();
        int changed = 0;
        int squares = 0;
        for (int i = 0; i < slots.size(); i++) {
            GuiCaptures.Slot slot = slots.get(i);
            if (slot.x() <= -1000) {
                continue;
            }
            squares++;
            int x0 = (left + slot.x()) * scale;
            int y0 = (top + slot.y()) * scale;
            int dx = (at.get(i)[0] - slot.x()) * scale;
            int dy = (at.get(i)[1] - slot.y()) * scale;
            for (int y = y0; y < y0 + 18 * scale; y++) {
                for (int x = x0; x < x0 + 18 * scale; x++) {
                    if (!inside(base, x, y) || !inside(state, x + dx, y + dy)) {
                        continue;
                    }
                    int b = base.getRGB(x, y);
                    int s = state.getRGB(x + dx, y + dy);
                    if (b != s && b >>> 24 == 0xFF && s >>> 24 == 0xFF) {
                        pairs.merge((long) (b & 0xFFFFFF) << 24 | (s & 0xFFFFFF), 1, Integer::sum);
                        changed++;
                    }
                }
            }
        }
        if (squares == 0 || pairs.size() < 2) {
            return null;
        }
        // The slope from the most common change of each base shade, robust to a window's pixels.
        double sw = 0, sb = 0, ss = 0, sbb = 0, sbs = 0;
        Map<Integer, int[]> modes = new HashMap<>();
        for (Map.Entry<Long, Integer> e : pairs.entrySet()) {
            int b = (int) (e.getKey() >>> 24);
            int[] best = modes.get(b);
            if (best == null || e.getValue() > best[1]) {
                modes.put(b, new int[]{(int) (e.getKey() & 0xFFFFFF), e.getValue()});
            }
        }
        for (Map.Entry<Integer, int[]> e : modes.entrySet()) {
            double w = e.getValue()[1];
            for (int sh = 0; sh <= 16; sh += 8) {
                double b = (e.getKey() >> sh) & 0xFF;
                double s = (e.getValue()[0] >> sh) & 0xFF;
                sw += w;
                sb += w * b;
                ss += w * s;
                sbb += w * b * b;
                sbs += w * b * s;
            }
        }
        double var = sbb - sb * sb / sw;
        if (var < 1) {
            return null;
        }
        double k = (sbs - sb * ss / sw) / var;
        int a0 = (int) Math.round(255 * (1 - k));
        Wash best = null;
        int bestHits = 0;
        for (int a = Math.max(8, a0 - 4); a <= Math.min(247, a0 + 4); a++) {
            int[] c = new int[3];
            for (int ch = 0; ch < 3; ch++) {
                int sh = 16 - 8 * ch;
                int[] votes = new int[256];
                for (Map.Entry<Long, Integer> e : pairs.entrySet()) {
                    int b = (int) (e.getKey() >>> (24 + sh)) & 0xFF;
                    int s = (int) (e.getKey() >>> sh) & 0xFF;
                    // The colours that blend to s: a short run around (s*255 - b*(255-a)) / a.
                    double mid = (s * 255.0 - b * (255.0 - a)) / a;
                    int span = 255 / a + 2;
                    for (int cc = Math.max(0, (int) mid - span); cc <= Math.min(255, (int) mid + span); cc++) {
                        if (Wash.blend(cc, a, b) == s) {
                            votes[cc] += e.getValue();
                        }
                    }
                }
                for (int cc = 1; cc < 256; cc++) {
                    if (votes[cc] > votes[c[ch]]) {
                        c[ch] = cc;
                    }
                }
            }
            Wash w = new Wash(a, c[0], c[1], c[2]);
            int hits = 0;
            for (Map.Entry<Long, Integer> e : pairs.entrySet()) {
                if (w.over((int) (e.getKey() >>> 24)) == (0xFF000000 | (int) (e.getKey() & 0xFFFFFF))) {
                    hits += e.getValue();
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = w;
            }
        }
        if (best == null || bestHits * 5 < changed * 3) {
            return null;
        }
        // Screen-wide: the wash changed most slots, not a shadow along one window's edge.
        int washed = 0;
        for (int i = 0; i < slots.size(); i++) {
            GuiCaptures.Slot slot = slots.get(i);
            if (slot.x() <= -1000) {
                continue;
            }
            int cx = (left + slot.x()) * scale + 8 * scale;
            int cy = (top + slot.y()) * scale + 8 * scale;
            int sx = (left + at.get(i)[0]) * scale + 8 * scale;
            int sy = (top + at.get(i)[1]) * scale + 8 * scale;
            if (inside(base, cx, cy) && inside(state, sx, sy)) {
                int b = base.getRGB(cx, cy);
                int s = state.getRGB(sx, sy);
                if (b != s && best.fits(b, s)) {
                    washed++;
                }
            }
        }
        return washed * 2 >= squares ? best : null;
    }

    /**
     * What a press state draws over the slots, or null; drawn after the items. Both arts are
     * captured with the inventory lifted, so only the screen itself differs.
     * <ul>
     * <li>A slot a window or popup reaches (some pixel visibly different): every pixel the
     * state changed there, opaque, hides what lies under the window and leaves what does not
     * (measured: Mekanism's Upgrades window hides the slots beneath it, its grey list within a
     * few levels of the slot's own grey; its side configuration leaves a diamond's count
     * showing below its edge).</li>
     * <li>A wash over the whole screen ({@link #washOf}): its colour at its alpha over every
     * slot pixel it explains, with the state's tile showing the base there, so an item in the
     * slot is washed as the real screen washes it.</li>
     * <li>A faint change no wash explains covers nothing.</li>
     * <li>Where the base's real frame shows an item (it differs from the lifted art), the
     * state's real frame tells: showing the state's own art there, the state hides the item
     * (measured: the robit's skin window draws cells of the slot's own grey over the
     * inventory, so no colour tells the cell from the slot); showing something over the art,
     * the item stays in view.</li>
     * </ul>
     * {@code shown}: the state's tiles, {@code baseShown}: the base's; washed pixels of
     * {@code shown} take the base's pixel. {@code at}: where each slot sits in the state (a
     * press that lays the screen out again moves them): the state's square there is judged
     * against the base's square where the slot opened, so a moved screen is not a window.
     */
    static BufferedImage coverArt(BufferedImage base, BufferedImage state, BufferedImage shown,
                                  BufferedImage baseShown, List<GuiCaptures.Slot> slots, List<int[]> at,
                                  int scale, int left, int top) {
        return coverArt(base, state, shown, baseShown, null, null, null, slots, at, scale, left, top);
    }

    static BufferedImage coverArt(BufferedImage base, BufferedImage state, BufferedImage shown,
                                  BufferedImage baseShown, BufferedImage baseFrame, BufferedImage stateFrame,
                                  BufferedImage stateFilled, List<GuiCaptures.Slot> slots, List<int[]> at,
                                  int scale, int left, int top) {
        lastWash = null;
        if (base.getWidth() != state.getWidth() || base.getHeight() != state.getHeight()
                || shown.getWidth() != state.getWidth() || shown.getHeight() != state.getHeight()) {
            return null;
        }
        boolean frames = baseFrame != null && stateFrame != null
                && baseFrame.getWidth() == base.getWidth() && baseFrame.getHeight() == base.getHeight()
                && stateFrame.getWidth() == state.getWidth() && stateFrame.getHeight() == state.getHeight();
        boolean filled = frames && stateFilled != null
                && stateFilled.getWidth() == state.getWidth() && stateFilled.getHeight() == state.getHeight();
        // Measured (run f2fa3ff3): the vanilla client blends a translucent glyph a level off the
        // real fill (139 under the robit's wash: 121 against 120), across every washed slot. A
        // wash is recognised, so it reaches no slot as a window, but not drawn.
        Wash wash = washOf(base, state, slots, at, scale, left, top);
        boolean washable = wash != null && baseShown != null
                && baseShown.getWidth() == shown.getWidth() && baseShown.getHeight() == shown.getHeight();
        lastWash = washable ? new double[]{(255 - wash.alpha()) / 255.0, wash.r() * wash.alpha() / 255.0,
                wash.g() * wash.alpha() / 255.0, wash.b() * wash.alpha() / 255.0} : null;
        if (washable && frames) {
            double[] measured = washFromFrames(baseFrame, stateFrame, base, state, wash, slots, at, scale, left, top);
            if (measured != null) {
                lastWash = measured;
            }
        }
        BufferedImage out = null;
        for (int i = 0; i < slots.size(); i++) {
            GuiCaptures.Slot slot = slots.get(i);
            if (slot.x() <= -1000) {
                continue;
            }
            // The item and its count: 16 pixels, the count's shadow one more past the 17th.
            int x0 = (left + slot.x()) * scale;
            int y0 = (top + slot.y()) * scale;
            int dx = (at.get(i)[0] - slot.x()) * scale;
            int dy = (at.get(i)[1] - slot.y()) * scale;
            int size = 18 * scale;
            boolean reached = false;
            for (int y = y0; y < y0 + size && !reached; y++) {
                for (int x = x0; x < x0 + size && !reached; x++) {
                    if (inside(base, x, y) && inside(state, x + dx, y + dy)) {
                        int b = base.getRGB(x, y);
                        int s = state.getRGB(x + dx, y + dy);
                        reached = visiblyDifferent(b, s) && !(washable && wash.fits(b, s));
                    }
                }
            }
            for (int y = y0; y < y0 + size; y++) {
                for (int x = x0; x < x0 + size; x++) {
                    if (!inside(base, x, y) || !inside(state, x + dx, y + dy)) {
                        continue;
                    }
                    int b = base.getRGB(x, y);
                    int s = state.getRGB(x + dx, y + dy);
                    int pixel;
                    int item = frames ? itemShown(base, baseFrame, x, y) : 0;
                    if (filled && drawnOverItem(state, stateFilled, stateFrame, x + dx, y + dy)) {
                        // What the screen draws once the slot holds something, in view on the real
                        // frame (Create's requester writes the amount over its filter items).
                        pixel = 0xFF000000 | stateFilled.getRGB(x + dx, y + dy);
                    } else if (washable && wash.fits(b, s)) {
                        continue;
                    } else if (item > 0 && itemShown(state, stateFrame, x + dx, y + dy) == 0) {
                        // The base's frame drew an item here; the state's frame shows its own art.
                        pixel = shown.getRGB(x + dx, y + dy);
                    } else if (item > 0 && itemShown(state, stateFrame, x + dx, y + dy) > 0) {
                        continue;
                    } else if (reached && b != s) {
                        pixel = shown.getRGB(x + dx, y + dy);
                    } else {
                        continue;
                    }
                    if (out == null) {
                        out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    }
                    out.setRGB(x + dx, y + dy, pixel);
                }
            }
        }
        return out;
    }

    /**
     * Whether the real frame shows something over the art at this pixel: 1 it does (an item),
     * 0 it shows the art itself, -1 the art lets the world through there (no evidence).
     */
    private static int itemShown(BufferedImage art, BufferedImage frame, int x, int y) {
        int a = art.getRGB(x, y);
        int alpha = a >>> 24;
        // Colour with zero alpha is solid in a real framebuffer (see opaque()).
        if (alpha != 0xFF && !(alpha == 0 && (a & 0xFFFFFF) != 0)) {
            return -1;
        }
        return (frame.getRGB(x, y) & 0xFFFFFF) != (a & 0xFFFFFF) ? 1 : 0;
    }

    /**
     * The filled pass (items in their slots, only their pixels skipped) draws something the
     * lifted pass does not, and the real frame shows it: drawn by the screen over the item.
     * A placeholder an empty slot shows is the other way round: the item hides it.
     */
    private static boolean drawnOverItem(BufferedImage lifted, BufferedImage filled, BufferedImage frame, int x, int y) {
        int f = filled.getRGB(x, y);
        int fa = f >>> 24;
        if (fa != 0xFF && !(fa == 0 && (f & 0xFFFFFF) != 0)) {
            return false;
        }
        int l = lifted.getRGB(x, y);
        boolean liftedSolid = l >>> 24 == 0xFF || (l >>> 24 == 0 && (l & 0xFFFFFF) != 0);
        if (liftedSolid && (l & 0xFFFFFF) == (f & 0xFFFFFF)) {
            return false;
        }
        return (frame.getRGB(x, y) & 0xFFFFFF) == (f & 0xFFFFFF);
    }

    /**
     * The wash as the real frames show it, the items under it included: one factor and one
     * added colour per channel that reproduce the most frame pixels exactly (measured: the
     * robit's wash puts 252 at 207 and 139 at 120, which no 8-bit colour and alpha does both).
     */
    static double[] washFromFrames(BufferedImage baseFrame, BufferedImage stateFrame, BufferedImage base,
                                   BufferedImage state, Wash wash, List<GuiCaptures.Slot> slots, List<int[]> at,
                                   int scale, int left, int top) {
        Map<Long, Integer> pairs = new HashMap<>();
        for (int i = 0; i < slots.size(); i++) {
            GuiCaptures.Slot slot = slots.get(i);
            if (slot.x() <= -1000) {
                continue;
            }
            int x0 = (left + slot.x()) * scale;
            int y0 = (top + slot.y()) * scale;
            int dx = (at.get(i)[0] - slot.x()) * scale;
            int dy = (at.get(i)[1] - slot.y()) * scale;
            for (int y = y0; y < y0 + 18 * scale; y++) {
                for (int x = x0; x < x0 + 18 * scale; x++) {
                    if (!inside(base, x, y) || !inside(state, x + dx, y + dy)
                            || !wash.fits(base.getRGB(x, y), state.getRGB(x + dx, y + dy))) {
                        continue;
                    }
                    long b = baseFrame.getRGB(x, y) & 0xFFFFFFL;
                    long s = stateFrame.getRGB(x + dx, y + dy) & 0xFFFFFFL;
                    pairs.merge(b << 24 | s, 1, Integer::sum);
                }
            }
        }
        if (pairs.size() < 2) {
            return null;
        }
        double k0 = (255 - wash.alpha()) / 255.0;
        double[] best = null;
        int bestHits = -1;
        for (int step = -80; step <= 80; step++) {
            double k = k0 + step * 0.0002;
            double[] out = {k, 0, 0, 0};
            int hits = 0;
            for (int ch = 0; ch < 3; ch++) {
                int sh = 16 - 8 * ch;
                // Each pair allows added colours in [s - 0.5 - b*k, s + 0.5 - b*k): the most covered point.
                List<double[]> events = new ArrayList<>();
                for (Map.Entry<Long, Integer> e : pairs.entrySet()) {
                    int b = (int) (e.getKey() >>> (24 + sh)) & 0xFF;
                    int sv = (int) (e.getKey() >>> sh) & 0xFF;
                    events.add(new double[]{sv - 0.5 - b * k, e.getValue()});
                    events.add(new double[]{sv + 0.5 - b * k, -e.getValue()});
                }
                events.sort((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0]) : Double.compare(p[1], q[1]));
                int run = 0;
                int top1 = -1;
                double at1 = 0;
                for (int j = 0; j < events.size(); j++) {
                    run += (int) events.get(j)[1];
                    if (run > top1 && j + 1 < events.size()) {
                        top1 = run;
                        at1 = (events.get(j)[0] + events.get(j + 1)[0]) / 2;
                    }
                }
                out[1 + ch] = at1;
                hits += top1;
            }
            if (hits > bestHits) {
                bestHits = hits;
                best = out;
            }
        }
        return best;
    }

    private static boolean inside(BufferedImage image, int x, int y) {
        return x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight();
    }

    /** Where each slot sits in a state: its placed position there, moved by the state's shift. */
    private static List<int[]> statePositions(List<GuiCaptures.Slot> slots, Map<String, int[]> placed, int state,
                                              int[] shift) {
        List<int[]> at = new ArrayList<>();
        for (GuiCaptures.Slot slot : slots) {
            int[] p = placed.get(state + ":" + slot.menuIndex());
            int x = p != null ? p[0] : slot.x();
            int y = p != null ? p[1] : slot.y();
            at.add(shift == null ? new int[]{x, y} : new int[]{x + shift[0], y + shift[1]});
        }
        return at;
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

    /** A state's slot positions where they differ from the screen as opened. */
    private static void readPlaced(Map<String, int[]> placed, int state, Path file, List<GuiCaptures.Slot> slots)
            throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<Integer, GuiCaptures.Slot> opened = new HashMap<>();
        for (GuiCaptures.Slot s : slots) {
            opened.put(s.menuIndex(), s);
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.split("\t");
            if (p.length < 3) {
                continue;
            }
            int index = Integer.parseInt(p[0]);
            int x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            GuiCaptures.Slot was = opened.get(index);
            if (was != null && (was.x() != x || was.y() != y)) {
                placed.put(state + ":" + index, new int[]{x, y});
            }
        }
    }

    private static BufferedImage chooseArt(BufferedImage lifted, BufferedImage filled, java.util.Set<Integer> vanillaDrawn) {
        return !vanillaDrawn.isEmpty() && filled != null && filled.getWidth() == lifted.getWidth()
                && filled.getHeight() == lifted.getHeight() ? filled : lifted;
    }

    private static BufferedImage filledOf(Path tsv, String suffix) {
        Path f = tsv.resolveSibling(tsv.getFileName().toString().replaceAll("\\.tsv$", suffix + ".filled.png"));
        try {
            return Files.isRegularFile(f) ? ImageIO.read(f.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Each slot's background as the screen draws it when the slot holds an item, where that
     * differs from the empty look in the art (AE2's inscriber draws a placeholder only in an
     * empty slot): a few tiles per slot, keyed by state and menu index, drawn before the item.
     */
    private static void addFills(Map<String, List<Piece>> fills, int state, BufferedImage art, BufferedImage filled,
                                 List<GuiCaptures.Slot> slots, java.util.Set<Integer> vanillaDrawn, int scale,
                                 int left, int top, Map<Character, BufferedImage> glyphs,
                                 Map<Character, Integer> heights) throws IOException {
        if (art == null || filled == null || art.getWidth() != filled.getWidth()
                || art.getHeight() != filled.getHeight()) {
            return;
        }
        BufferedImage opaqueFilled = opaque(filled);
        for (GuiCaptures.Slot slot : slots) {
            if (slot.x() <= -1000 || !vanillaDrawn.contains(slot.menuIndex())) {
                continue;
            }
            int x0 = (left + slot.x()) * scale;
            int y0 = (top + slot.y()) * scale;
            int size = 16 * scale;
            BufferedImage tile = null;
            for (int y = Math.max(0, y0); y < Math.min(art.getHeight(), y0 + size); y++) {
                for (int x = Math.max(0, x0); x < Math.min(art.getWidth(), x0 + size); x++) {
                    if (art.getRGB(x, y) != filled.getRGB(x, y)) {
                        if (tile == null) {
                            tile = new BufferedImage(art.getWidth(), art.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        }
                        tile.setRGB(x, y, opaqueFilled.getRGB(x, y));
                    }
                }
            }
            if (tile != null) {
                // The whole square, so the empty look under it is gone wherever the item leaves it.
                for (int y = Math.max(0, y0); y < Math.min(art.getHeight(), y0 + size); y++) {
                    for (int x = Math.max(0, x0); x < Math.min(art.getWidth(), x0 + size); x++) {
                        tile.setRGB(x, y, opaqueFilled.getRGB(x, y));
                    }
                }
                fills.put(state + ":" + slot.menuIndex(), captureTiles(tile, scale, left, top, glyphs, heights));
            }
        }
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
