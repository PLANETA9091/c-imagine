package dev.cim.live;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.function.BooleanSupplier;

/**
 * The facade's server chunk cache: allocated like the level it belongs to. It hands out the
 * facade's empty chunks and takes what a mod asks it to send to the players tracking an
 * entity or a block; those players are Paper's, so nothing goes out from here.
 */
public final class FacadeChunkCache extends ServerChunkCache {

    private FacadeServerLevel facade;

    private FacadeChunkCache() {
        super(null, null, null, null, null, null, 0, 0, false, null, null, null);
        throw new AssertionError();
    }

    static FacadeChunkCache create(FacadeServerLevel level) {
        FacadeChunkCache self = SessionServerPlayer.allocate(FacadeChunkCache.class);
        self.facade = level;
        return self;
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        return facade.getChunk(x, z, status, create);
    }

    @Override
    public LevelChunk getChunkNow(int x, int z) {
        return (LevelChunk) facade.getChunk(x, z, ChunkStatus.FULL, true);
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return true;
    }

    @Override
    public FacadeServerLevel getLevel() {
        return facade;
    }

    @Override
    public void tick(BooleanSupplier hasTime, boolean tickChunks) {
    }

    @Override
    public void blockChanged(BlockPos pos) {
    }

    @Override
    public void onLightUpdate(LightLayer layer, SectionPos pos) {
    }

    @Override
    public <T> void addRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value) {
    }

    @Override
    public <T> void addRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value, boolean forced) {
    }

    @Override
    public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value) {
    }

    @Override
    public <T> void removeRegionTicket(TicketType<T> type, ChunkPos pos, int distance, T value, boolean forced) {
    }

    @Override
    public void broadcastAndSend(Entity entity, Packet<?> packet) {
        if (entity instanceof SessionServerPlayer player) {
            player.connection.send(packet);
        }
    }

    @Override
    public void broadcast(Entity entity, Packet<?> packet) {
    }
}
