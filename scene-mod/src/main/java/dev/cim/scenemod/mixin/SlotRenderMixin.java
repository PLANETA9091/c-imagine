package dev.cim.scenemod.mixin;

import dev.cim.scenemod.ScreenArt;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Which slots a screen really draws. A slot can be active and still never drawn by its screen
 * (Mekanism's QIO crafting window keeps its slots at 0,0 while the window is closed): the
 * capture records the drawn ones, so the HUD never shows an item the real screen hides and
 * the crawl's move goes where a player could put it.
 */
@Mixin(AbstractContainerScreen.class)
public class SlotRenderMixin {

    @Inject(method = "renderSlot", at = @At("HEAD"), cancellable = true)
    private void cimscene$drawn(GuiGraphics graphics, Slot slot, CallbackInfo ci) {
        ScreenArt.slotDrawn(slot);
        if (ScreenArt.filledPass && slot.hasItem()
                && slot.mayPickup(net.minecraft.client.Minecraft.getInstance().player)) {
            ci.cancel();
        }
    }
}
