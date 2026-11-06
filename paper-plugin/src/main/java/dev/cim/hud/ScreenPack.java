package dev.cim.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
 * Turns every Screen the scanner finds into one free-font glyph. Nothing here
 * names a mod: the input is whatever jars are in {@code mods/}.
 */
public final class ScreenPack {

    private static final char GLYPH_BASE = '\uE300';
    private static final int MAX_SCREENS = 0x1800;

    /** One drawable screen. {@code glyph} is the same at pack build and at runtime. */
    public record Scene(String className, String menu, char glyph, int width, int height) {
        public String shortName() {
            return className.substring(Math.max(className.lastIndexOf('/'), className.lastIndexOf('$')) + 1);
        }
    }

    /** Scenes plus the cropped art, in a stable order. */
    record Baked(List<Scene> scenes, List<BufferedImage> art) {
    }

    private ScreenPack() {
    }

    public static List<Scene> scenes(Path modsDir) throws IOException {
        return bake(modsDir).scenes();
    }

    /** Adds every scanned screen to the pack and returns how many. */
    public static int contribute(Map<String, byte[]> entries, Path modsDir) throws IOException {
        Baked baked = bake(modsDir);
        JsonArray providers = new JsonArray();
        int ascent = HudLayout.ascent(HudLayout.freeId());
        for (int i = 0; i < baked.scenes().size(); i++) {
            Scene scene = baked.scenes().get(i);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(baked.art().get(i), "png", png);
            String file = "screen/" + i + ".png";
            entries.put("assets/" + HudPack.NS + "/textures/font/" + file, png.toByteArray());
            JsonObject provider = new JsonObject();
            provider.addProperty("type", "bitmap");
            provider.addProperty("file", HudPack.NS + ":font/" + file);
            provider.addProperty("height", scene.height());
            provider.addProperty("ascent", ascent);
            JsonArray chars = new JsonArray();
            chars.add(String.valueOf(scene.glyph()));
            provider.add("chars", chars);
            providers.add(provider);
        }
        entries.put("assets/" + HudPack.NS + "/font/" + HudLayout.fontName(HudLayout.freeId()) + ".json",
                HudPack.freeFont(providers).toString().getBytes(StandardCharsets.UTF_8));
        return baked.scenes().size();
    }

    private static Baked bake(Path modsDir) throws IOException {
        List<Scene> scenes = new ArrayList<>();
        List<BufferedImage> art = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return new Baked(scenes, art);
        }
        List<Path> jars;
        try (Stream<Path> s = Files.list(modsDir)) {
            jars = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        for (Path jar : jars) {
            byte[] bytes = Files.readAllBytes(jar);
            Map<String, byte[]> textures = new HashMap<>();
            readTextures(bytes, textures, 0);
            for (ScreenScanner.ScreenInfo info : ScreenScanner.scanJar(bytes)) {
                BufferedImage image = crop(info, textures);
                if (image != null && scenes.size() < MAX_SCREENS) {
                    scenes.add(new Scene(info.className(), info.menu(), (char) (GLYPH_BASE + scenes.size()),
                            image.getWidth(), image.getHeight()));
                    art.add(image);
                }
            }
        }
        return new Baked(scenes, art);
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

    private static BufferedImage crop(ScreenScanner.ScreenInfo info, Map<String, byte[]> textures) throws IOException {
        ScreenScanner.Rect r = info.background();
        if (r != null) {
            byte[] png = find(textures, r.texture());
            if (png != null) {
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(png));
                if (r.u() >= 0 && r.v() >= 0 && r.w() > 0 && r.h() > 0
                        && r.u() + r.w() <= src.getWidth() && r.v() + r.h() <= src.getHeight()) {
                    return copy(src, r.u(), r.v(), r.w(), r.h());
                }
            }
        }
        for (String texture : info.textures()) {
            byte[] png = find(textures, texture);
            if (png != null) {
                return trimmed(ImageIO.read(new ByteArrayInputStream(png)));
            }
        }
        return null;
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

    /** A whole-sheet texture has its screen in the top left and empty space around it. */
    private static BufferedImage trimmed(BufferedImage src) {
        int right = 0;
        int bottom = 0;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                if (src.getRGB(x, y) >>> 24 != 0) {
                    right = Math.max(right, x + 1);
                    bottom = Math.max(bottom, y + 1);
                }
            }
        }
        return right == 0 ? null : copy(src, 0, 0, right, bottom);
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
            bad += check(again.size() == n && again.equals(scenes(Path.of(args[1]))), "glyphs are stable between runs");
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
