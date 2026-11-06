package dev.cim.hud;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * A north-up minimap drawn from the server's own world.
 *
 * <p>Minimap mods exist only on the client, so nothing of theirs can run here;
 * this is c-imagine's map, not a port of one. Colours are cached per chunk and
 * only ever read from loaded chunks, and at most {@link #CHUNKS_PER_TICK}
 * chunks are sampled per tick, so the cost per tick is bounded.
 */
public final class Minimap implements HudService.Layer, org.bukkit.command.CommandExecutor {

    private static final int SIZE = HudLayout.MAP_ROWS - 1;
    private static final int HALF = SIZE / 2;
    private static final int CHUNKS_PER_TICK = 2;
    private static final long CHUNK_TTL_TICKS = 200;
    private static final int MAX_CACHED_CHUNKS = 4096;
    private static final int UNKNOWN = 0x202020;

    private record Sample(int[] rgb, long tick) {
    }

    private record View(int x, int z, int heading, String world) {
    }

    private final Map<String, Sample> chunks = new HashMap<>();
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, HudFrame> frames = new HashMap<>();
    private final java.util.Set<UUID> hidden = new java.util.HashSet<>();
    private long tick;
    private int budget;

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                             String label, String[] args) {
        if (sender instanceof Player player && !hidden.remove(player.getUniqueId())) {
            hidden.add(player.getUniqueId());
        }
        return true;
    }

    @Override
    public void draw(Player player, HudFrame frame) {
        if (hidden.contains(player.getUniqueId())) {
            return;
        }
        long now = org.bukkit.Bukkit.getCurrentTick();
        if (now != tick) {
            tick = now;
            budget = CHUNKS_PER_TICK;
        }
        World world = player.getWorld();
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        int heading = Math.floorMod(Math.round((player.getYaw() + 180f) / 45f), HudPack.ARROWS);
        boolean refresh = tick % 20 == 0;
        View view = new View(px, pz, heading, world.getName());
        if (!refresh && view.equals(views.get(player.getUniqueId()))) {
            replay(player, frame);
            return;
        }
        views.put(player.getUniqueId(), view);
        HudFrame kept = new HudFrame();
        for (int row = 0; row < SIZE; row++) {
            int[] line = new int[SIZE];
            for (int col = 0; col < SIZE; col++) {
                line[col] = color(world, px + col - HALF, pz + row - HALF);
            }
            kept.cells(row, line);
        }
        kept.marker(HALF - 1, HALF, (char) (HudPack.ARROW_BASE + heading));
        frames.put(player.getUniqueId(), kept);
        frame.append(kept);
        trim();
    }

    private void replay(Player player, HudFrame frame) {
        HudFrame kept = frames.get(player.getUniqueId());
        if (kept != null) {
            frame.append(kept);
        }
    }

    private int color(World world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        String key = world.getName() + ':' + cx + ':' + cz;
        Sample s = chunks.get(key);
        if (s == null || tick - s.tick() > CHUNK_TTL_TICKS) {
            if (budget > 0 && world.isChunkLoaded(cx, cz)) {
                budget--;
                s = new Sample(sample(world.getChunkAt(cx, cz)), tick);
                chunks.put(key, s);
            } else if (s == null) {
                return UNKNOWN;
            }
        }
        return s.rgb()[((z & 15) << 4) | (x & 15)];
    }

    private static int[] sample(Chunk chunk) {
        int[] rgb = new int[256];
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (int z = 0; z < 16; z++) {
            int north = -1;
            for (int x = 0; x < 16; x++) {
                Block top = world.getHighestBlockAt(baseX + x, baseZ + z, HeightMap.WORLD_SURFACE);
                int c = top.getBlockData().getMapColor().asRGB();
                int y = top.getY();
                if (north >= 0) {
                    c = shade(c, y > north ? 1.08 : y < north ? 0.86 : 1.0);
                }
                north = y;
                rgb[(z << 4) | x] = quantize(c);
            }
        }
        return rgb;
    }

    private static int shade(int rgb, double f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** Fewer distinct colours means longer runs and smaller frames. */
    private static int quantize(int rgb) {
        return rgb & 0xF8F8F8;
    }

    private void trim() {
        if (chunks.size() <= MAX_CACHED_CHUNKS) {
            return;
        }
        Iterator<Sample> it = chunks.values().iterator();
        while (it.hasNext()) {
            if (tick - it.next().tick() > CHUNK_TTL_TICKS) {
                it.remove();
            }
        }
    }
}
