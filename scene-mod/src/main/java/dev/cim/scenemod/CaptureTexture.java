package dev.cim.scenemod;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TextureStateShard bindings are resources, not necessarily textures/ sprites. */
final class CaptureTexture {
    private static final Pattern TEXTURE =
            Pattern.compile("Optional\\[([a-z0-9_.-]+):([a-z0-9_./-]+\\.png)\\]");

    private CaptureTexture() {
    }

    /** Null denotes block-atlas UVs; @ denotes an exact jar resource path. */
    static String fromRenderState(String state) {
        Matcher match = TEXTURE.matcher(state);
        if (!match.find()) {
            return null;
        }
        String namespace = match.group(1);
        String path = match.group(2);
        if (namespace.equals("minecraft") && path.equals("textures/atlas/blocks.png")) {
            return null;
        }
        if (path.startsWith("textures/")) {
            return namespace + ":" + path.substring(9, path.length() - 4);
        }
        return "@" + namespace + ":" + path;
    }
}
