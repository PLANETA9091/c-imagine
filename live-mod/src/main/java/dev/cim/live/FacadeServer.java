package dev.cim.live;

import com.mojang.authlib.GameProfile;
import net.minecraft.SystemReport;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debugchart.SampleLogger;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server a mod reaches from the facade level ({@code level.getServer()}, NeoForge's
 * {@code ServerLifecycleHooks.getCurrentServer()}): allocated, never started. It answers the
 * level, the registries, the tick count and "run this now"; the world, its players and its
 * network are Paper's.
 */
public final class FacadeServer extends MinecraftServer {

    private FacadeServerLevel level;
    private RegistryAccess.Frozen access;
    private final GameRules rules = new GameRules();

    private FacadeServer() {
        super(null, null, null, null, null, null, null, null);
        throw new AssertionError();
    }

    @SuppressWarnings("unchecked")
    static FacadeServer create(FacadeServerLevel level, RegistryAccess.Frozen access) {
        FacadeServer self = SessionServerPlayer.allocate(FacadeServer.class);
        self.level = level;
        self.access = access;
        try {
            // overworld() is final and reads this map.
            Field levels = MinecraftServer.class.getDeclaredField("levels");
            levels.setAccessible(true);
            Map<ResourceKey<Level>, ServerLevel> map = new LinkedHashMap<>();
            map.put(Level.OVERWORLD, level);
            levels.set(self, map);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind the facade server's level", e);
        }
        // Mods that ask NeoForge for the running server (Mekanism's saved data) get this one,
        // where no real one runs: in the layer. On a real server the real one stays.
        try {
            Field current = net.neoforged.neoforge.server.ServerLifecycleHooks.class.getDeclaredField("currentServer");
            current.setAccessible(true);
            if (current.get(null) == null) {
                current.set(null, self);
            }
        } catch (ReflectiveOperationException e) {
            // no hook: mods that need the server keep failing as before
        }
        return self;
    }

    @Override
    public ServerLevel getLevel(ResourceKey<Level> key) {
        return level;
    }

    @Override
    public Iterable<ServerLevel> getAllLevels() {
        return List.of(level);
    }

    @Override
    public Set<ResourceKey<Level>> levelKeys() {
        return Set.of(Level.OVERWORLD);
    }

    @Override
    public RegistryAccess.Frozen registryAccess() {
        return access;
    }

    @Override
    public RecipeManager getRecipeManager() {
        return level.getRecipeManager();
    }

    @Override
    public GameRules getGameRules() {
        return rules;
    }

    @Override
    public int getTickCount() {
        return LiveProtocol.ticks();
    }

    @Override
    public boolean isSameThread() {
        return true;
    }

    @Override
    public void executeIfPossible(Runnable task) {
        task.run();
    }

    @Override
    public void execute(Runnable task) {
        task.run();
    }

    @Override
    protected boolean initServer() {
        return false;
    }

    @Override
    public int getOperatorUserPermissionLevel() {
        return 4;
    }

    @Override
    public int getFunctionCompilationLevel() {
        return 2;
    }

    @Override
    public boolean shouldRconBroadcast() {
        return false;
    }

    @Override
    protected SampleLogger getTickTimeLogger() {
        return null;
    }

    @Override
    public boolean isTickTimeLoggingEnabled() {
        return false;
    }

    @Override
    public SystemReport fillServerSystemReport(SystemReport report) {
        return report;
    }

    @Override
    public boolean isDedicatedServer() {
        return true;
    }

    @Override
    public int getRateLimitPacketsPerSecond() {
        return 0;
    }

    @Override
    public boolean isEpollEnabled() {
        return false;
    }

    @Override
    public boolean isCommandBlockEnabled() {
        return false;
    }

    @Override
    public boolean isPublished() {
        return false;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    @Override
    public boolean isSingleplayerOwner(GameProfile profile) {
        return false;
    }
}
