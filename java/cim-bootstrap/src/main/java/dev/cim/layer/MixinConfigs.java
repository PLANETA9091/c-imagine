package dev.cim.layer;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import dev.cim.bootstrap.MiniJson;
import dev.cim.bootstrap.ShadowClassLoader;

/**
 * Mixin-config translation through the relocation table (harness contract
 * §8 point 4) — the UNIVERSAL path: every mod's {@code *.mixins.json} /
 * {@code *refmap.json} served through {@link CimMixinService} gets its
 * class-space strings rewritten to the shadow namespace before sponge-mixin
 * reads them. No mod is named here; the one relocation table decides.
 *
 * <p>What is translated and why:
 * <ul>
 *   <li>{@code package} — the mixin class namespace. Layer mods (the NeoForge
 *       universal jar) are FULL-relocation classes, so their mixin classes
 *       carry relocated names and the package must follow. Mod jars keep
 *       their original class names (refs-only relocation) and their packages
 *       do not map — the table returns them unchanged.</li>
 *   <li>{@code plugin} — a class name, same rule.</li>
 *   <li>absolute class entries inside {@code mixins}/{@code client}/{@code
 *       server} lists (relative entries resolve against the translated
 *       package; the table leaves non-relocatable names unchanged, so the
 *       common {@code subpackage.Mixin} relative form passes through).</li>
 *   <li>{@code targets} map keys ({@code @Pseudo}-style configs).</li>
 *   <li>refmap mappings keys (source-file paths in the original namespace).
 *       Refmap VALUES carry SRG-era member names — remapping those is a
 *       real rename (not a namespace prefix) and stays an open §8 item until
 *       a leg actually carries a refmap (Create 6.0.10 is mojmap-native,
 *       refmap-less).</li>
 * </ul>
 */
final class MixinConfigs {

    private MixinConfigs() {
    }

    /**
     * Serve a resource; {@code null} when the loader does not carry it. Mixin
     * configs and refmaps are parsed, translated and re-serialized; every
     * other resource passes through byte-identical.
     */
    static InputStream serve(String name) throws Exception {
        byte[] raw = readRaw(name);
        if (raw == null) {
            return null;
        }
        if (!name.endsWith(".json")) {
            return new ByteArrayInputStream(raw);
        }
        boolean refmap = name.endsWith("refmap.json");
        if (!refmap && !name.endsWith("mixins.json")) {
            return new ByteArrayInputStream(raw);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        Object parsed = MiniJson.parse(text);
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) parsed;
            rewrite(refmap, root);
            String out = MiniJson.json(root);
            if (Boolean.getBoolean("cim.debug.mixins")) {
                System.out.println("[c-imagine] mixin config served (" + name + "): " + out);
            }
            return new ByteArrayInputStream(out.getBytes(StandardCharsets.UTF_8));
        }
        return new ByteArrayInputStream(raw);
    }

    private static void rewrite(boolean refmap, Map<String, Object> root) {
        if (refmap) {
            // Refmap shape: {"mappings": {"<source path>": {...}}, "data": {...}}
            // Only the KEYS (original-namespace source paths) translate.
            rewriteMapKeys(root.get("mappings"));
            rewriteMapKeys(root.get("data"));
            return;
        }
        for (Map.Entry<String, Object> e : root.entrySet()) {
            switch (e.getKey()) {
                case "package", "plugin", "refmap" -> {
                    if (e.getValue() instanceof String s) {
                        e.setValue(mapDotted(s));
                    }
                }
                case "mixins", "client", "server" -> {
                    if (e.getValue() instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        List<Object> entries = (List<Object>) list;
                        for (int i = 0; i < entries.size(); i++) {
                            Object item = entries.get(i);
                            if (item instanceof String s) {
                                entries.set(i, mapEntry(s));
                            }
                        }
                    }
                }
                case "targets" -> {
                    if (e.getValue() instanceof Map<?, ?> targets) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tm = (Map<String, Object>) targets;
                        for (Map.Entry<String, Object> t : tm.entrySet()) {
                            String mapped = mapDotted(t.getKey());
                            if (!mapped.equals(t.getKey())) {
                                Object v = tm.remove(t.getKey());
                                tm.put(mapped, v);
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
    }

    private static void rewriteMapKeys(Object section) {
        if (!(section instanceof Map<?, ?> m)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) m;
        // Re-key in a copy: ConcurrentHashMap-free parse output, but removal
        // during iteration is still forbidden.
        java.util.LinkedHashMap<String, Object> re = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            re.put(mapEntry(e.getKey()), e.getValue());
        }
        map.clear();
        map.putAll(re);
    }

    /** A mixin-list entry: relative names pass through; absolute names map. */
    private static String mapEntry(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String wildcard = "";
        String body = s;
        int star = s.indexOf('*');
        if (star >= 0) {
            wildcard = s.substring(star);
            body = s.substring(0, star);
        }
        if (!body.contains(".")) {
            return s; // relative to the (already translated) package
        }
        return mapDotted(body) + wildcard;
    }

    /**
     * Dotted name → internal → relocation table → dotted. Unchanged for
     * anything outside the relocation space (mod packages, JDK, ...).
     */
    static String mapDotted(String dotted) {
        if (dotted == null || dotted.isEmpty()) {
            return dotted;
        }
        String mapped = ShadowClassLoader.mapInternalName(dotted.replace('.', '/'));
        if (mapped == null) {
            return dotted;
        }
        return mapped.replace('/', '.');
    }

    private static byte[] readRaw(String name) {
        // Shadow loader resources (mod/layer jars) — safe under the
        // single-definition contract (dev.cim.** delegates to the host).
        try (InputStream in = ShadowClassLoader.get().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
