package dev.cim.scenemod.mixin;

import net.minecraft.client.renderer.texture.SpriteTicker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the anonymous sprite ticker's delegate (SpriteContents$Ticker)
 * so the 14-eb3 pin can zero the frame counters. The impl is the
 * TextureAtlasSprite.createTicker anonymous class — targeted by name. */
@Mixin(targets = "net.minecraft.client.renderer.texture.TextureAtlasSprite$1")
public interface TextureSpriteTickerAccessor {
    @Accessor("val$ticker")
    SpriteTicker cimscene$delegate();
}
