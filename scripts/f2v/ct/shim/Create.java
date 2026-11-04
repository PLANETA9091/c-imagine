package com.simibubi.create;

import net.minecraft.resources.ResourceLocation;

/** 14-ak probe shim: the real Create.<clinit> runs the full mod init
 *  (Registrate + CreativeModeTabs) which cannot run headless; the CT probe
 *  only needs asResource (the AllCTTypes id factory). Shadows the real
 *  class via classpath order; every other Create member stays unexecuted. */
public class Create {
    public static final String MOD_ID = "create";

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
