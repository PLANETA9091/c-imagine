package dev.cim.scenemod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the sprite animation's frame counters to the 14-eb3 pin. */
@Mixin(targets = "net.minecraft.client.renderer.texture.SpriteContents$Ticker")
public interface SpriteContentsTickerAccessor {
    @Accessor("frame")
    void cimscene$frame(int value);

    @Accessor("subFrame")
    void cimscene$subFrame(int value);
}
