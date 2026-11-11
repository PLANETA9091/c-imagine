package dev.cim.live;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.UUID;

/**
 * The facade as mods expect a server level: an {@code instanceof ServerLevel} (mods cast to it,
 * ask it for its server, keep saved data in its data storage). A ServerLevel is constructed only
 * by a running MinecraftServer with chunk storage, so none is constructed: the object is
 * allocated, takes every field a Level has from the {@link FacadeLevel} it fronts, and answers
 * everything else here. Blocks, block entities and chunks stay in that FacadeLevel's sparse
 * store; mods' saved data (Mekanism's frequencies) lives in one in-memory data storage.
 */
public final class FacadeServerLevel extends ServerLevel {

    private FacadeLevel store;
    private FacadeServer server;
    private DimensionDataStorage data;
    private FacadeChunkCache chunks;
    private ServerScoreboard scoreboard;

    private FacadeServerLevel() {
        super(null, null, null, null, null, null, null, false, 0L, null, false, null);
        throw new AssertionError();
    }

    static FacadeServerLevel create(FacadeLevel store) {
        FacadeServerLevel self = SessionServerPlayer.allocate(FacadeServerLevel.class);
        for (Class<?> c = Level.class; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(self, f.get(store));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new IllegalStateException("cannot take field Level." + f.getName(), e);
                }
            }
        }
        self.store = store;
        self.server = FacadeServer.create(self, (net.minecraft.core.RegistryAccess.Frozen) store.registryAccess());
        // ServerLevel's own code reads its server field directly (mayInteract's spawn
        // protection, measured on a backpack's placement): it is the facade server too.
        try {
            Field own = ServerLevel.class.getDeclaredField("server");
            own.setAccessible(true);
            own.set(self, self.server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind ServerLevel.server", e);
        }
        store.front(self);
        return self;
    }

    FacadeLevel store() {
        return store;
    }

    // --- the server side mods ask for

    /** Paper decides who may build where before a click reaches the layer. */
    @Override
    public boolean mayInteract(Player player, BlockPos pos) {
        return true;
    }

    @Override
    public MinecraftServer getServer() {
        return server;
    }

    @Override
    public DimensionDataStorage getDataStorage() {
        if (data == null) {
            try {
                java.io.File dir = java.nio.file.Files.createTempDirectory("cimlive-data").toFile();
                dir.deleteOnExit();
                data = new DimensionDataStorage(dir, net.minecraft.util.datafix.DataFixers.getDataFixer(),
                        registryAccess());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("no data storage for the facade", e);
            }
        }
        return data;
    }

    @Override
    public ServerScoreboard getScoreboard() {
        if (scoreboard == null) {
            scoreboard = new ServerScoreboard(server);
        }
        return scoreboard;
    }

    @Override
    public RecipeManager getRecipeManager() {
        return store.getRecipeManager();
    }

    @Override
    public TickRateManager tickRateManager() {
        return store.tickRateManager();
    }

    @Override
    public List<ServerPlayer> players() {
        return List.of();
    }

    @Override
    public long getSeed() {
        return 0L;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return store.enabledFeatures();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return store.potionBrewing();
    }

    @Override
    public net.minecraft.server.level.ServerChunkCache getChunkSource() {
        if (chunks == null) {
            chunks = FacadeChunkCache.create(this);
        }
        return chunks;
    }

    /*
     * Scheduled block and fluid ticks: a mod schedules one when a block changes. They are held
     * and never run here (Paper's world does the world's ticking); a menu needs only that the
     * schedule call works.
     */
    // Lazy, never initialisers: the instance is allocated, so no constructor or initialiser runs.
    private net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.block.Block> blockTicks;
    private net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.material.Fluid> fluidTicks;

    @Override
    public net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.block.Block> getBlockTicks() {
        if (blockTicks == null) {
            blockTicks = new net.minecraft.world.ticks.LevelTicks<>(pos -> false,
                    () -> net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
        }
        return blockTicks;
    }

    @Override
    public net.minecraft.world.ticks.LevelTicks<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        if (fluidTicks == null) {
            fluidTicks = new net.minecraft.world.ticks.LevelTicks<>(pos -> false,
                    () -> net.minecraft.util.profiling.InactiveProfiler.INSTANCE);
        }
        return fluidTicks;
    }

    @Override
    public java.util.Collection<net.neoforged.neoforge.entity.PartEntity<?>> getPartEntities() {
        return java.util.List.of();
    }

    // NeoForge's capability caches listen for block changes on a real level; nothing caches here.

    @Override
    public void invalidateCapabilities(BlockPos pos) {
    }

    @Override
    public void invalidateCapabilities(net.minecraft.world.level.ChunkPos pos) {
    }

    @Override
    public void registerCapabilityListener(BlockPos pos,
                                           net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener listener) {
    }

    @Override
    public void cleanCapabilityListenerReferences() {
    }

    // --- blocks, chunks, entities: the sparse store

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return store.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return store.getFluidState(pos);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        return store.setBlock(pos, state, flags, recursionLeft);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return store.getBlockEntity(pos);
    }

    @Override
    public void setBlockEntity(BlockEntity blockEntity) {
        store.setBlockEntity(blockEntity);
    }

    @Override
    public void removeBlockEntity(BlockPos pos) {
        store.removeBlockEntity(pos);
    }

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true;
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return true;
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        return store.getChunk(x, z, status, create);
    }

    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public Entity getEntity(UUID id) {
        return null;
    }

    @Override
    public LevelEntityGetter<Entity> getEntities() {
        return store.entities();
    }

    /** Items the mods' code spilled into the world (a broken block's drops), for Paper to drop. */
    private static final java.util.List<net.minecraft.world.item.ItemStack> SPILLED = new java.util.ArrayList<>();

    static java.util.List<net.minecraft.world.item.ItemStack> takeSpilled() {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>(SPILLED);
        SPILLED.clear();
        return out;
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        // The world's entities are Paper's: an item entity's stack goes to Paper to drop.
        if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && !item.getItem().isEmpty()) {
            SPILLED.add(item.getItem().copy());
            return true;
        }
        return false;
    }

    // --- effects the vanilla client gets from Paper

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(Player except, double x, double y, double z, Holder<SoundEvent> sound,
                                SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(Player except, Entity entity, Holder<SoundEvent> sound, SoundSource source,
                                float volume, float pitch, long seed) {
    }

    @Override
    public void levelEvent(Player except, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public void destroyBlockProgress(int id, BlockPos pos, int progress) {
    }

    /** A block event (a chest lid opening) animates the world, which Paper draws. */
    @Override
    public void blockEvent(BlockPos pos, net.minecraft.world.level.block.Block block, int type, int data) {
    }

    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
    }

    @Override
    public MapId getFreeMapId() {
        return new MapId(0);
    }

    @Override
    public String gatherChunkSourceStats() {
        return "facade";
    }
}
