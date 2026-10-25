package dev.cim.testmod;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minimal block entity on the test block. Holds a counter so M3's state
 * bridge has observable per-block state to keep in sync across worlds.
 */
public class TestBlockEntity extends BlockEntity {

    private int ticksObserved;

    public TestBlockEntity(BlockPos pos, BlockState state) {
        super(CimBlockEntities.TEST_BLOCK_ENTITY.get(), pos, state);
    }

    public int getTicksObserved() {
        return ticksObserved;
    }

    public void bumpTicksObserved() {
        ticksObserved++;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putInt("ticksObserved", ticksObserved);
    }

    @Override
    protected void loadAdditional(CompoundTag tag) {
        super.loadAdditional(tag);
        ticksObserved = tag.contains("ticksObserved") ? tag.getInt("ticksObserved") : 0;
    }
}
