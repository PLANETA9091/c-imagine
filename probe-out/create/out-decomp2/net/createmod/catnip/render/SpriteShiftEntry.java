package net.createmod.catnip.render;

import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

public class SpriteShiftEntry {
   @Nullable
   protected StitchedSprite original;
   @Nullable
   protected StitchedSprite target;

   public void set(ResourceLocation originalLocation, ResourceLocation targetLocation) {
      this.original = new StitchedSprite(originalLocation);
      this.target = new StitchedSprite(targetLocation);
   }

   public ResourceLocation getOriginalResourceLocation() {
      Objects.requireNonNull(this.original);
      return this.original.getLocation();
   }

   public ResourceLocation getTargetResourceLocation() {
      Objects.requireNonNull(this.target);
      return this.target.getLocation();
   }

   public TextureAtlasSprite getOriginal() {
      Objects.requireNonNull(this.original);
      return this.original.get();
   }

   public TextureAtlasSprite getTarget() {
      Objects.requireNonNull(this.target);
      return this.target.get();
   }

   public float getTargetU(float localU) {
      return this.getTarget().getU(getUnInterpolatedU(this.getOriginal(), localU));
   }

   public float getTargetV(float localV) {
      return this.getTarget().getV(getUnInterpolatedV(this.getOriginal(), localV));
   }

   public static float getUnInterpolatedU(TextureAtlasSprite sprite, float u) {
      float f = sprite.getU1() - sprite.getU0();
      return (u - sprite.getU0()) / f;
   }

   public static float getUnInterpolatedV(TextureAtlasSprite sprite, float v) {
      float f = sprite.getV1() - sprite.getV0();
      return (v - sprite.getV0()) / f;
   }
}
