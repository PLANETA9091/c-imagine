package dev.cim.scenemod.mixin;

import dev.cim.scenemod.ScreenArt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves out the dimmed world during a capture; the screen's own panel still draws. */
@Mixin(Screen.class)
public class ScreenBackgroundMixin {
    @Inject(method = "renderBackground", at = @At("HEAD"), cancellable = true)
    private void cimscene$skipBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick,
                                         CallbackInfo ci) {
        if (ScreenArt.capturing) {
            ci.cancel();
        }
    }

    @Inject(method = "renderTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void cimscene$skipDim(GuiGraphics graphics, CallbackInfo ci) {
        if (ScreenArt.capturing) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true)
    private void cimscene$skipBlur(float partialTick, CallbackInfo ci) {
        if (ScreenArt.capturing) {
            ci.cancel();
        }
    }
}
