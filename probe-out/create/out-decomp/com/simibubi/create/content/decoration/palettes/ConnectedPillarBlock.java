package com.simibubi.create.content.decoration.palettes;

import net.createmod.catnip.data.Iterate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.ticks.LevelTickAccess;

public class ConnectedPillarBlock extends LayeredBlock {
   public static final BooleanProperty NORTH = BooleanProperty.create("north");
   public static final BooleanProperty SOUTH = BooleanProperty.create("south");
   public static final BooleanProperty EAST = BooleanProperty.create("east");
   public static final BooleanProperty WEST = BooleanProperty.create("west");

   public ConnectedPillarBlock(Properties p_55926_) {
      super(p_55926_);
      this.registerDefaultState(
         (BlockState)((BlockState)((BlockState)((BlockState)this.defaultBlockState().setValue(NORTH, false)).setValue(WEST, false)).setValue(EAST, false))
            .setValue(SOUTH, false)
      );
   }

   protected void createBlockStateDefinition(Builder<Block, BlockState> pBuilder) {
      super.createBlockStateDefinition(pBuilder.add(new Property[]{NORTH, SOUTH, EAST, WEST}));
   }

   public BlockState getStateForPlacement(BlockPlaceContext pContext) {
      BlockState state = super.getStateForPlacement(pContext);
      return this.updateColumn(pContext.getLevel(), pContext.getClickedPos(), state, true);
   }

   private BlockState updateColumn(Level level, BlockPos pos, BlockState state, boolean present) {
      MutableBlockPos currentPos = new MutableBlockPos();
      Axis axis = (Axis)state.getValue(AXIS);

      for (Direction connection : Iterate.directions) {
         if (connection.getAxis() != axis) {
            boolean connect = true;

            label54:
            for (Direction movement : Iterate.directionsInAxis(axis)) {
               currentPos.set(pos);

               for (int i = 0; i < 1000 && level.isLoaded(currentPos); i++) {
                  BlockState other1 = currentPos.equals(pos) ? state : level.getBlockState(currentPos);
                  BlockState other2 = level.getBlockState(currentPos.relative(connection));
                  boolean col1 = this.canConnect(state, other1);
                  boolean col2 = this.canConnect(state, other2);
                  currentPos.move(movement);
                  if (!col1 && !col2) {
                     break;
                  }

                  if (!col1 || !col2) {
                     connect = false;
                     break label54;
                  }
               }
            }

            state = setConnection(state, connection, connect);
         }
      }

      return state;
   }

   public void onPlace(BlockState pState, Level pLevel, BlockPos pPos, BlockState pOldState, boolean pIsMoving) {
      if (pOldState.getBlock() != this) {
         LevelTickAccess<Block> blockTicks = pLevel.getBlockTicks();
         if (!blockTicks.hasScheduledTick(pPos, this)) {
            pLevel.scheduleTick(pPos, this, 1);
         }
      }
   }

   public void tick(BlockState pState, ServerLevel pLevel, BlockPos pPos, RandomSource pRandom) {
      if (pState.getBlock() == this) {
         BlockPos belowPos = pPos.relative(Direction.fromAxisAndDirection((Axis)pState.getValue(AXIS), AxisDirection.NEGATIVE));
         BlockState belowState = pLevel.getBlockState(belowPos);
         if (!this.canConnect(pState, belowState)) {
            pLevel.setBlock(pPos, this.updateColumn(pLevel, pPos, pState, true), 3);
         }
      }
   }

   public BlockState updateShape(
      BlockState state, Direction pDirection, BlockState pNeighborState, LevelAccessor pLevel, BlockPos pCurrentPos, BlockPos pNeighborPos
   ) {
      if (!this.canConnect(state, pNeighborState)) {
         return setConnection(state, pDirection, false);
      } else {
         return pDirection.getAxis() == state.getValue(AXIS)
            ? this.withPropertiesOf(pNeighborState)
            : setConnection(state, pDirection, getConnection(pNeighborState, pDirection.getOpposite()));
      }
   }

   protected boolean canConnect(BlockState state, BlockState other) {
      return other.getBlock() == this && state.getValue(AXIS) == other.getValue(AXIS);
   }

   public void onRemove(BlockState pState, Level pLevel, BlockPos pPos, BlockState pNewState, boolean pIsMoving) {
      if (!pIsMoving && pNewState.getBlock() != this) {
         for (Direction d : Iterate.directionsInAxis((Axis)pState.getValue(AXIS))) {
            BlockPos relative = pPos.relative(d);
            BlockState adjacent = pLevel.getBlockState(relative);
            if (this.canConnect(pState, adjacent)) {
               pLevel.setBlock(relative, this.updateColumn(pLevel, relative, adjacent, false), 3);
            }
         }
      }
   }

   public static boolean getConnection(BlockState state, Direction side) {
      BooleanProperty property = connection((Axis)state.getValue(AXIS), side);
      return property != null && (Boolean)state.getValue(property);
   }

   public static BlockState setConnection(BlockState state, Direction side, boolean connect) {
      BooleanProperty property = connection((Axis)state.getValue(AXIS), side);
      if (property != null) {
         state = (BlockState)state.setValue(property, connect);
      }

      return state;
   }

   public static BooleanProperty connection(Axis axis, Direction side) {
      if (side.getAxis() == axis) {
         return null;
      } else if (axis == Axis.X) {
         switch (side) {
            case UP:
               return EAST;
            case NORTH:
               return NORTH;
            case SOUTH:
               return SOUTH;
            case DOWN:
               return WEST;
            default:
               return null;
         }
      } else if (axis == Axis.Y) {
         switch (side) {
            case NORTH:
               return NORTH;
            case SOUTH:
               return SOUTH;
            case DOWN:
            default:
               return null;
            case EAST:
               return EAST;
            case WEST:
               return WEST;
         }
      } else if (axis == Axis.Z) {
         switch (side) {
            case UP:
               return WEST;
            case NORTH:
            case SOUTH:
            default:
               return null;
            case DOWN:
               return EAST;
            case EAST:
               return NORTH;
            case WEST:
               return SOUTH;
         }
      } else {
         return null;
      }
   }
}
