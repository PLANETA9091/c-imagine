package dev.cim.testmod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block entities registered by the test mod. */
public final class CimBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, CimTestMod.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TestBlockEntity>> TEST_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("test_block_entity", () ->
                    BlockEntityType.Builder.of(
                                    TestBlockEntity::new, CimBlocks.TEST_BLOCK.get())
                            .build(null));

    private CimBlockEntities() {
    }
}
