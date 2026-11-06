package dev.cim.scenemod.mixin;

import net.minecraft.Util;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A blinking text cursor is a coin toss in any frame, so gallery clients hold it off. */
@Mixin(EditBox.class)
public class EditBoxBlinkMixin {
    private static final boolean FROZEN =
            System.getProperty("cim.screenart") != null || System.getProperty("cim.nomouse") != null;

    @Shadow
    private long focusedTime;

    @Redirect(method = "renderWidget", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;getMillis()J"),
            require = 0)
    private long cimscene$holdBlink() {
        return FROZEN ? focusedTime + 300L : Util.getMillis();
    }
}
