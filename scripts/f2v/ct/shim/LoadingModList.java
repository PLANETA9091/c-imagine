package net.neoforged.fml.loading;

import java.util.List;

/** 14-ak probe shim: the minimal LoadingModList surface the NeoForge
 *  FeatureFlagLoader touches when bootstrapping the registries OUTSIDE a
 *  real FML run (CTProbe runs the real create classes headless). The
 *  empty mod-file list short-circuits the stream pipeline before any
 *  IModFile materializes. Shadows the real class via classpath order. */
public class LoadingModList {
    private static final LoadingModList INSTANCE = new LoadingModList();

    public static LoadingModList get() {
        return INSTANCE;
    }

    public List getModFiles() {
        return List.of();
    }
}
