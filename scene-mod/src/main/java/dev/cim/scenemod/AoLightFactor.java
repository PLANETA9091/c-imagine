package dev.cim.scenemod;

import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 14-e155 THE AO FACTOR SIDE-CHANNEL READER (the e154b honest path's
 * client half). The plugin computes the coarse per-face AO census at
 * placement (the 4-corner in-plane occluder mean; the 0.222 anchor =
 * the e153c measured luma_scale 0.778 for the wall-embedded class) and
 * rides it on the display's custom name side-channel "cimao=NN" (the
 * name never renders -- customNameVisible stays false). This helper
 * parses the factor once per entity (the name is static after spawn;
 * the WeakHashMap cache drops it with the entity). Untagged or
 * unparsable = 1.0 = the exact legacy render (the honest no-op default).
 */
public final class AoLightFactor {

    private AoLightFactor() { }

    private static final Map<Entity, Float> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static float factorOf(Entity entity) {
        if (entity == null) {
            return 1.0f;
        }
        Float cached = CACHE.get(entity);
        if (cached != null) {
            return cached;
        }
        float f = 1.0f;
        try {
            net.minecraft.network.chat.Component name = entity.getCustomName();
            if (name != null) {
                String s = name.getString();
                if (s.startsWith("cimao=")) {
                    int pct = Integer.parseInt(s.substring(6).trim());
                    f = Math.max(0.0f, Math.min(1.0f, pct / 100.0f));
                }
            }
        } catch (Throwable t) {
            f = 1.0f;
        }
        CACHE.put(entity, f);
        return f;
    }

    /**
     * The packed-light scale: the block and sky coords both times the
     * factor (the LightTexture.pack layout measured from the mapped
     * client bytecode: {@code (block << 4) | (sky << 20)}, the levels
     * 0-15). The coarse lightmap rung -- the calibration walk refines
     * the mapping against the measured residuals.
     */
    public static int scalePackedLight(int packedLight, float f) {
        if (f >= 0.999f) {
            return packedLight;
        }
        int block = (packedLight >> 4) & 15;
        int sky = (packedLight >> 20) & 15;
        return ((Math.round(sky * f) & 15) << 20) | ((Math.round(block * f) & 15) << 4);
    }
}
