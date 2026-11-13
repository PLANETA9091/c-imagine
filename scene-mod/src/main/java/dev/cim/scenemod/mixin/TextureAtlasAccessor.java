package dev.cim.scenemod.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Exposes the atlas's animated-sprite tickers to the 14-eb3 frame pin. */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("animatedTextures")
    List<TextureAtlasSprite.Ticker> cimscene$animatedTextures();
}
