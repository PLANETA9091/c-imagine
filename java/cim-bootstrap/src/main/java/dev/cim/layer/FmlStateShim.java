package dev.cim.layer;

import java.lang.reflect.Field;
import java.util.List;

/**
 * M1 shim: seed the minimal FML statics the relocated NeoForge-flavored MC
 * stack expects at bootstrap time.
 *
 * The 1.21.1 NeoForge distribution ships MC classes that call into FML
 * during their own {@code <clinit>} (e.g. {@code FeatureFlags} asks
 * {@code LoadingModList} for mod-supplied feature flags). The full FML
 * launch pipeline (mod discovery, transformation services) lands in M2; the
 * M1 "logical server" boot runs vanilla-only, so we seed empty FML state:
 * every list field on {@code LoadingModList} becomes an empty list, which is
 * exactly what a no-mods environment looks like to those call sites.
 *
 * This is deliberately reflective and fail-soft: anything it cannot reach is
 * logged and skipped — the M2 FML pipeline makes this class obsolete.
 */
final class FmlStateShim {

    private static final String LOADING_MOD_LIST =
            "cim.shadow.neoforge.net.neoforged.fml.loading.LoadingModList";

    private FmlStateShim() {
    }

    /** Seed empty FML state; returns a human-readable report for the log. */
    static String apply() {
        int seeded = 0;
        StringBuilder skipped = new StringBuilder();
        try {
            Class<?> lml = Class.forName(LOADING_MOD_LIST);
            Object instance = allocate(lml);
            if (instance != null) {
                for (Field f : lml.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue; // seed instance fields only
                    }
                    try {
                        f.setAccessible(true);
                        Object cur = f.get(instance);
                        if (f.getType() == List.class && cur == null) {
                            f.set(instance, List.of());
                            seeded++;
                        } else if (f.getType() == java.util.Map.class && cur == null) {
                            f.set(instance, java.util.Map.of());
                            seeded++;
                        }
                    } catch (Throwable t) {
                        skipped.append(f.getName()).append(' ');
                    }
                }
                Field inst = lml.getDeclaredField("INSTANCE");
                inst.setAccessible(true);
                inst.set(null, instance);
                seeded++;
            }
        } catch (Throwable t) {
            return "partial (seeded=" + seeded + ", skipped: " + skipped + "): " + t;
        }
        return "ok (seeded=" + seeded
                + (skipped.isEmpty() ? "" : ", skipped: " + skipped) + ")";
    }

    /** Allocate an uninitialized instance (all-null fields) via Unsafe. */
    private static Object allocate(Class<?> cls) throws Exception {
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        var allocate = unsafeCls.getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, cls);
    }
}
