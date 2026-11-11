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
}
