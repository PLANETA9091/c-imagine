package dev.cim.scenemod;

import dev.cim.scenemod.mixin.SpriteContentsTickerAccessor;
import dev.cim.scenemod.mixin.TextureAtlasAccessor;
import dev.cim.scenemod.mixin.TextureSpriteTickerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteTicker;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 14-eb3 THE MCMETA FRAME-INDEX PIN (see TickingStatePinMixin): the freeze
 * lands with every animated sprite's frame index at f(client tick history)
 * — the two legs differ. This walks the blocks + particles atlases, zeroes
 * each animated sprite's frame/subFrame counters and re-uploads frame 0,
 * so the frozen render is the SAME frame on both legs (the pack's 14-dx
 * bake ships frame 0 exactly). Idempotent: re-running on every freeze
 * packet re-uploads the same frame.
 */
public final class SceneTexturePin {
    private static final Logger LOGGER = LoggerFactory.getLogger("cimscene");

    private SceneTexturePin() {
    }

    public static void pinAllAnimatedSprites(Minecraft mc) {
        int pinned = 0;
        pinned += pinAtlas(mc, TextureAtlas.LOCATION_BLOCKS);
        pinned += pinAtlas(mc, TextureAtlas.LOCATION_PARTICLES);
        if (pinned > 0) {
            LOGGER.info("[cimscene] 14-eb3 frame pin: {} animated sprites re-uploaded at frame 0", pinned);
        }
    }

    private static int pinAtlas(Minecraft mc, net.minecraft.resources.ResourceLocation atlasLocation) {
        net.minecraft.client.renderer.texture.AbstractTexture tex =
                mc.getTextureManager().getTexture(atlasLocation);
        if (!(tex instanceof TextureAtlas atlas)) {
            return 0;
        }
        List<TextureAtlasSprite.Ticker> tickers = ((TextureAtlasAccessor) atlas).cimscene$animatedTextures();
        if (tickers == null) {
            return 0;
        }
        int pinned = 0;
        for (TextureAtlasSprite.Ticker ticker : tickers) {
            try {
                SpriteTicker delegate = ((TextureSpriteTickerAccessor) ticker).cimscene$delegate();
                if (delegate instanceof SpriteContentsTickerAccessor counters) {
                    counters.cimscene$frame(0);
                    counters.cimscene$subFrame(0);
                }
                ticker.tickAndUpload();
                pinned++;
            } catch (Throwable t) {
                LOGGER.debug("[cimscene] frame pin skipped a ticker: {}", t.toString());
            }
        }
        return pinned;
    }
}
