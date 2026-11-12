package dev.cim.scenemod;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TextureStateShard bindings are resources, not necessarily textures/ sprites. */
final class CaptureTexture {
    private static final Pattern TEXTURE =
            Pattern.compile("Optional\\[([a-z0-9_.-]+):([a-z0-9_./-]+\\.png)\\]");
    private static final Pattern ANY_LOCATION =
            Pattern.compile("Optional\\[([a-z0-9_.-]+:[a-z0-9_./-]+)\\]");

    private CaptureTexture() {
    }

    /** Null denotes block-atlas UVs; @ denotes an exact jar resource path. */
    static String fromRenderState(String state) {
        String resource = resourceFromRenderState(state);
        if (resource == null) {
            return null;
        }
        int colon = resource.indexOf(':');
        String namespace = resource.substring(0, colon);
        String path = resource.substring(colon + 1);
        if (namespace.equals("minecraft") && path.equals("textures/atlas/blocks.png")) {
            return null;
        }
        if (path.startsWith("textures/")) {
            return namespace + ":" + path.substring(9, path.length() - 4);
        }
        return "@" + resource;
    }

    /** Exact texture-manager key, before any sprite-path conversion. */
    static String resourceFromRenderState(String state) {
        Matcher match = TEXTURE.matcher(state);
        if (!match.find()) {
            return null;
        }
        return match.group(1) + ":" + match.group(2);
    }

    /**
     * 14-dg: any Optional[...] location, not just the .png jar resources.
     * The font glyph atlases register WITHOUT an extension (FontSet.stitch
     * registers {@code minecraft:<font>/<n>}, e.g. minecraft:default/0), so
     * {@link #resourceFromRenderState} misses every text RenderType and the
     * glyph quads dropped at the sprite search. Null when no location token
     * is present.
     */
    static String optionalLocation(String state) {
        Matcher match = ANY_LOCATION.matcher(state);
        if (!match.find()) {
            return null;
        }
        return match.group(1);
    }

    /**
     * 14-ds THE READBACK-CHANNEL LAW: a texture is captured as a WHITE
     * COVERAGE MASK (RED-channel readback, the vertex tint repaints it)
     * ONLY when its class hierarchy carries the glyph-atlas marker — a
     * boolean {@code colored} field, {@code false} on the single-channel
     * fonts ({@code FontTexture}). EVERY OTHER runtime texture is full
     * color. The previous default (mask when no field exists anywhere)
     * turned the supplementaries globe's 32x16 DynamicTexture map — a
     * runtime texture with no {@code colored} field anywhere — into a
     * white coverage mask. Measured on gallery 43185864024: the captured
     * map read back mean RGBA [255,255,255,106], 0 non-white pixels, and
     * the 75 globe/globe_sepia red cells measured 18.45M ae — 41% of the
     * entire 44.85M red mass — on a family whose capture row, mesh model,
     * replay channel and light pin were all correct except this decision.
     *
     * <p>{@code Object} on purpose: the law holds headless
     * (CaptureTextureTest drives it with plain fake classes).</p>
     */
    static boolean isCoverageMaskReadback(Object texture) {
        for (Class<?> c = texture.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("colored");
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    return !f.getBoolean(texture);
                }
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (Throwable t) {
                break;
            }
        }
        return false;
    }
}
