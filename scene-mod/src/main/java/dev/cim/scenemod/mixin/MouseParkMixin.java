package dev.cim.scenemod.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a gallery client's mouse off every screen. A screen re-centres the cursor as it
 * opens, so parking the real cursor races the renderer; hover would then land in frames.
 */
@Mixin(MouseHandler.class)
public class MouseParkMixin {
    private static final boolean PARKED =
            System.getProperty("cim.screenart") != null || System.getProperty("cim.nomouse") != null;

    @Inject(method = "xpos", at = @At("HEAD"), cancellable = true)
    private void cimscene$parkX(CallbackInfoReturnable<Double> cir) {
        if (PARKED) {
            cir.setReturnValue(-10000.0);
        }
    }

    @Inject(method = "ypos", at = @At("HEAD"), cancellable = true)
    private void cimscene$parkY(CallbackInfoReturnable<Double> cir) {
        if (PARKED) {
            cir.setReturnValue(-10000.0);
        }
    }
}
