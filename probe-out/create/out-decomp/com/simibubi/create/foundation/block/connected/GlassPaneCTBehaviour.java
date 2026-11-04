package com.simibubi.create.foundation.block.connected;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

public class GlassPaneCTBehaviour extends SimpleCTBehaviour {
   public GlassPaneCTBehaviour(CTSpriteShiftEntry shift) {
      super(shift);
   }

   @Override
   public boolean buildContextForOccludedDirections() {
      return true;
   }

   @Override
   public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter reader, BlockPos pos, BlockPos otherPos, Direction face) {
      return state.getBlock() == other.getBlock();
   }

   @Override
   protected boolean reverseUVsHorizontally(BlockState state, Direction face) {
      return face.getAxisDirection() == AxisDirection.NEGATIVE ? true : super.reverseUVsHorizontally(state, face);
   }
}
