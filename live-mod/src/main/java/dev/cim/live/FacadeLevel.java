package dev.cim.live;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

import java.util.List;

/**
 * A server-side 1.21.1 level with no chunks, no entities and no tick loop of its own: the
 * ground a session player stands on while the mod's menu code runs. Paper owns the world;
 * block and block-entity reads go through the bridge once a menu needs them (roadmap
 * M6.3 step 4). Built once and shared by every session, so a menu costs a player and a
 * menu object, not a world.
 */
final class FacadeLevel extends Level {

    private final Scoreboard scoreboard = new Scoreboard();
    private final TickRateManager ticks = new TickRateManager();
    private final RecipeManager recipes;

    FacadeLevel(RegistryAccess.Frozen access) {
        super(new Data(), Level.OVERWORLD, access,
                access.registryOrThrow(Registries.DIMENSION_TYPE).getHolderOrThrow(BuiltinDimensionTypes.OVERWORLD),
                () -> InactiveProfiler.INSTANCE, false, false, 0L, 1_000_000);
        this.recipes = new RecipeManager(access);
    }

    // --- what a menu may ask a level for

    @Override
    public RecipeManager getRecipeManager() {
        return recipes;
    }

    @Override
    public Scoreboard getScoreboard() {
        return scoreboard;
    }

    @Override
    public TickRateManager tickRateManager() {
        return ticks;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return FeatureFlags.DEFAULT_FLAGS;
    }

    @Override
    public PotionBrewing potionBrewing() {
        return PotionBrewing.EMPTY;
    }

    @Override
    public List<? extends Player> players() {
        return List.of();
    }

    // --- the world is Paper's: nothing lives here yet

    @Override
    public ChunkSource getChunkSource() {
        throw new UnsupportedOperationException("the facade level has no chunks yet (M6.3 step 4: blocks through the bridge)");
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        throw new UnsupportedOperationException("the facade level holds no entities");
    }

    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Override
    public LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        throw new UnsupportedOperationException("the facade level has no biomes");
    }

    @Override
    public float getShade(net.minecraft.core.Direction direction, boolean shade) {
        return 1.0f;
    }

    @Override
    public String gatherChunkSourceStats() {
        return "facade";
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

    // --- effects a vanilla client gets from Paper, not from here

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
    public void destroyBlockProgress(int id, BlockPos pos, int progress) {
    }

    @Override
    public void levelEvent(Player except, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public void setDayTimeFraction(float fraction) {
    }

    @Override
    public float getDayTimeFraction() {
        return 0f;
    }

    @Override
    public float getDayTimePerTick() {
        return 1f;
    }

    @Override
    public void setDayTimePerTick(float perTick) {
    }

    /** Level data with nothing to say: noon, clear, normal difficulty, default rules. */
    private static final class Data implements WritableLevelData {
        private final GameRules rules = new GameRules();

        @Override
        public void setSpawn(BlockPos pos, float angle) {
        }

        @Override
        public BlockPos getSpawnPos() {
            return BlockPos.ZERO;
        }

        @Override
        public float getSpawnAngle() {
            return 0f;
        }

        @Override
        public long getGameTime() {
            return 0L;
        }

        @Override
        public long getDayTime() {
            return 6000L;
        }

        @Override
        public boolean isThundering() {
            return false;
        }

        @Override
        public boolean isRaining() {
            return false;
        }

        @Override
        public void setRaining(boolean raining) {
        }

        @Override
        public boolean isHardcore() {
            return false;
        }

        @Override
        public GameRules getGameRules() {
            return rules;
        }

        @Override
        public Difficulty getDifficulty() {
            return Difficulty.NORMAL;
        }

        @Override
        public boolean isDifficultyLocked() {
            return false;
        }
    }
}
