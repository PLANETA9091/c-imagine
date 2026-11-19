package dev.cim.scenemod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 14-e155 THE AO INJECT (the e154b honest path = the scene-mod render
 * hook): the composites render flat (no world AO) while the truth's real
 * blocks carry the vanilla smooth-lighting AO -- the e153c luma_scale
 * 0.778 = the measured gap for the wall-embedded class. The plugin's
 * placement census rides "cimao=NN" on the display's custom name
 * side-channel; this mixin scales the packed light the display render
 * submits its content quads with (the coarse per-face rung -- the
 * calibration walk refines the mapping). Scope = the renderInner call
 * only (the content quads); the shadow pass (the EntityRenderer.render
 * call) keeps the untouched light. Untagged displays = the factor 1.0 =
 * the exact legacy render (the honest no-op default). The fullbright
 * companions stay untagged (the truth renders them fullbright; dimming
 * them = the wrong direction).
 */
@Mixin(DisplayRenderer.class)
public abstract class DisplayAoLightMixin {

    @Shadow
    protected abstract void renderInner(Display entity, Object state,
                                        PoseStack poseStack,
                                        MultiBufferSource buffers,
                                        int packedLight, float partialTick);

    @Redirect(
            method = "render(Lnet/minecraft/world/entity/Display;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/DisplayRenderer;renderInner(Lnet/minecraft/world/entity/Display;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V"
            )
    )
    private void cimscene$aoScaledInner(DisplayRenderer instance,
                                        Display entity, Object state,
                                        PoseStack poseStack,
                                        MultiBufferSource buffers,
                                        int packedLight, float partialTick) {
        int light = dev.cim.scenemod.AoLightFactor.scalePackedLight(
                packedLight, dev.cim.scenemod.AoLightFactor.factorOf(entity));
        renderInner(entity, state, poseStack, buffers, light, partialTick);
    }
}
