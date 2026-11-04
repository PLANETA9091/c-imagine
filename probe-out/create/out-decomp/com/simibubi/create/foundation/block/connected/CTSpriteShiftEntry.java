package com.simibubi.create.foundation.block.connected;

import net.createmod.catnip.render.SpriteShiftEntry;

public class CTSpriteShiftEntry extends SpriteShiftEntry {
   protected final CTType type;

   public CTSpriteShiftEntry(CTType type) {
      this.type = type;
   }

   public CTType getType() {
      return this.type;
   }

   public float getTargetU(float localU, int index) {
      float uOffset = (float)(index % this.type.getSheetSize());
      return this.getTarget().getU((getUnInterpolatedU(this.getOriginal(), localU) + uOffset) / (float)this.type.getSheetSize());
   }

   public float getTargetV(float localV, int index) {
      float vOffset = (float)(index / this.type.getSheetSize());
      return this.getTarget().getV((getUnInterpolatedV(this.getOriginal(), localV) + vOffset) / (float)this.type.getSheetSize());
   }
}
