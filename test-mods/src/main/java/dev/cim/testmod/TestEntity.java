package dev.cim.testmod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Minimal modded entity: enough for spawn/despawn and metadata packet paths
 * (F2V v2 M4) without pulling in AI, attributes or client renderers.
 */
public class TestEntity extends Entity {

    public TestEntity(EntityType<? extends TestEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // stateless by design (M0)
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        // stateless by design (M0)
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // no synched fields yet; M4 adds one to exercise metadata translation
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide && random.nextInt(100) == 0) {
            CimTestMod.LOGGER.debug("[cimtest] test_entity alive at {}",
                    blockPosition());
        }
    }
}
