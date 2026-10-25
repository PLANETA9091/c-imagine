package dev.cim.testmod;

import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Items registered by the test mod. */
public final class CimItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CimTestMod.MOD_ID);

    public static final DeferredItem<BlockItem> TEST_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("test_block", CimBlocks.TEST_BLOCK);

    private CimItems() {
    }
}
