package dev.cim.testmod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Entity types registered by the test mod. */
public final class CimEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, CimTestMod.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<TestEntity>> TEST_ENTITY =
            ENTITY_TYPES.register("test_entity", () ->
                    EntityType.Builder.<TestEntity>of(TestEntity::new, MobCategory.MISC)
                            .sized(0.8f, 0.8f)
                            .clientTrackingRange(8)
                            .build("test_entity"));

    private CimEntities() {
    }
}
