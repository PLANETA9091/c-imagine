package dev.cim.live;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;

import java.util.concurrent.CompletableFuture;

/**
 * The facade's light engine: allocated, never constructed. Light is the world's, which Paper
 * computes; a mod that places a block (an AE2 part) asks the level to re-check the light there,
 * and nothing needs computing in the facade. Everything reads full light.
 */
final class FacadeLightEngine extends ThreadedLevelLightEngine {

    private FacadeLightEngine() {
        super(null, null, false, null, null);
        throw new AssertionError();
    }

    static FacadeLightEngine create() {
        return SessionServerPlayer.allocate(FacadeLightEngine.class);
    }

    @Override
    public void checkBlock(BlockPos pos) {
    }

    @Override
    public int runLightUpdates() {
        return 0;
    }

    @Override
    public void updateSectionStatus(SectionPos pos, boolean empty) {
    }

    @Override
    public void propagateLightSources(ChunkPos pos) {
    }

    @Override
    public void setLightEnabled(ChunkPos pos, boolean enabled) {
    }

    @Override
    public void queueSectionData(LightLayer layer, SectionPos pos, DataLayer data) {
    }

    @Override
    public void retainData(ChunkPos pos, boolean retain) {
    }

    @Override
    public CompletableFuture<ChunkAccess> initializeLight(ChunkAccess chunk, boolean lit) {
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<ChunkAccess> lightChunk(ChunkAccess chunk, boolean lit) {
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void tryScheduleUpdate() {
    }

    @Override
    public boolean hasLightWork() {
        return false;
    }

    @Override
    public LayerLightEventListener getLayerListener(LightLayer layer) {
        return LayerLightEventListener.DummyLightLayerEventListener.INSTANCE;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int skyDarken) {
        return 15;
    }

    @Override
    public boolean lightOnInSection(SectionPos pos) {
        return true;
    }

    @Override
    public void close() {
    }
}
