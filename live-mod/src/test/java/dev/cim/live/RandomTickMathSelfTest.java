package dev.cim.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** JUnit 5 port of the former bare-JDK main-method selftest for RandomTickMath. */
final class RandomTickMathSelfTest {

    @Test
    void firstDrawMatchesVanillaBitLayout() {
        // LCG reference: vanilla randomValue step, first draw from seed 0.
        RandomTickMath m = new RandomTickMath(0);
        int v = 0 * 3 + RandomTickMath.LCG_ADD;
        int cell = m.nextCell();
        int i = v >> 2;
        assertEquals((i & 15) | (((i >> 8) & 15) << 4) | (((i >> 16) & 15) << 8), cell,
                "first draw matches vanilla bit layout");
        assertTrue(cell >= 0 && cell < 4096, "cell within section");
    }

    @Test
    void speedBoundsDrawCount() {
        // Speed 3 (default gamerule): three draws per section per tick, capped by buffer.
        int[] buf = new int[16];
        assertEquals(3, new RandomTickMath(7).drawSection(3, buf), "default speed draws 3");
        assertEquals(0, new RandomTickMath(7).drawSection(0, buf), "speed 0 draws nothing");
        assertEquals(16, new RandomTickMath(7).drawSection(99, buf), "buffer caps the draw");
    }

    @Test
    void cellHitsAreRoughlyUniform() {
        // Expected 2000 per cell with 4096 * 2000 draws over 4096 cells; loose band.
        RandomTickMath u = new RandomTickMath(12345);
        int[] hits = new int[4096];
        int draws = 4096 * 2000;
        for (int d = 0; d < draws; d++) {
            hits[u.nextCell()]++;
        }
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int h : hits) {
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        assertTrue(min > 1000 && max < 3000, "cell hit counts roughly uniform: min=" + min + " max=" + max);
    }

    @Test
    void chunkKeys() {
        assertEquals(RandomTickMath.chunkKey(0, 0), RandomTickMath.chunkKey(15, 15), "one chunk one key");
        assertNotEquals(RandomTickMath.chunkKey(0, 0), RandomTickMath.chunkKey(16, 0), "adjacent chunks differ");
        assertNotEquals(RandomTickMath.chunkKey(-1, 0), RandomTickMath.chunkKey(1, 0), "negative x does not collide");
        assertNotEquals(RandomTickMath.chunkKey(-16, -16), RandomTickMath.chunkKey(16, 16),
                "negative and positive differ");
    }
}
