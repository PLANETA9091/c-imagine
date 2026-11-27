package dev.cim.live;

/**
 * The pure part of vanilla random ticking (1.21.1 ServerLevel.tickChunk / getBlockRandomPos), with no
 * Minecraft classes so it can be selftested on a bare JDK.
 *
 * <p>Vanilla: per game tick, per non-empty 16x16x16 section, {@code randomTickSpeed} (gamerule, default 3)
 * cells are drawn. Each draw steps the level's LCG {@code randomValue = randomValue * 3 + 1013904223} and
 * reads the cell from bits of {@code randomValue >> 2}: x = bits 0-3, z = bits 8-11, y = bits 16-19 (the
 * section's local y, 0-15). Over a 4096-cell section this is a per-cell probability of
 * {@code randomTickSpeed / 4096} per game tick.
 *
 * <p>Allocation-free: one int of state, one int result per draw.
 */
public final class RandomTickMath {
    /** Vanilla ServerLevel's LCG multiplier step (randomValue = randomValue * 3 + 1013904223). */
    static final int LCG_ADD = 1013904223;

    /** The level's random state (vanilla {@code ServerLevel.randomValue}); seeded like the facade's. */
    private int randomValue;

    public RandomTickMath(int seed) {
        this.randomValue = seed;
    }

    /** Steps the LCG and returns the section-local cell index {@code x | z << 4 | y << 8} (0..4095). */
    public int nextCell() {
        randomValue = randomValue * 3 + LCG_ADD;
        int i = randomValue >> 2;
        int x = i & 15;
        int z = (i >> 8) & 15;
        int y = (i >> 16) & 15;
        return x | (z << 4) | (y << 8);
    }

    /**
     * Draws the cells one non-empty section gets this game tick: {@code randomTickSpeed} draws, written into
     * {@code out} from index 0. Returns the number written (the speed, capped at the buffer size).
     */
    public int drawSection(int randomTickSpeed, int[] out) {
        int n = Math.min(Math.max(randomTickSpeed, 0), out.length);
        for (int k = 0; k < n; k++) {
            out[k] = nextCell();
        }
        return n;
    }

    /** Packs a chunk's (x, z) into one long key, the chunk-keyed filter for tracked positions. */
    public static long chunkKey(int blockX, int blockZ) {
        return ((long) (blockX >> 4) & 0xFFFFFFFFL) | (((long) (blockZ >> 4) & 0xFFFFFFFFL) << 32);
    }
}
