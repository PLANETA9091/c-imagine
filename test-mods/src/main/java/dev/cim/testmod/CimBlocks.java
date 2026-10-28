package dev.cim.testmod;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Blocks registered by the test mod. */
public final class CimBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CimTestMod.MOD_ID);

    public static final DeferredBlock<Block> TEST_BLOCK =
            BLOCKS.registerSimpleBlock("test_block",
                    BlockBehaviour.Properties.of().strength(1.5f, 6.0f));

    private CimBlocks() {
    }
}
