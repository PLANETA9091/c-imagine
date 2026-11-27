package dev.cim.live;

/** Bare-JDK selftest: javac + java -cp out dev.cim.live.RandomTickMathSelfTest. Exits 1 on failure. */
public final class RandomTickMathSelfTest {
    private static int failures;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    public static void main(String[] args) {
        // LCG reference: vanilla randomValue step, first draw from seed 0.
        RandomTickMath m = new RandomTickMath(0);
        int v = 0 * 3 + RandomTickMath.LCG_ADD;
        int cell = m.nextCell();
        int i = v >> 2;
        check(cell == ((i & 15) | (((i >> 8) & 15) << 4) | (((i >> 16) & 15) << 8)), "first draw matches vanilla bit layout");
        check(cell >= 0 && cell < 4096, "cell within section");

        // Speed 3 (default gamerule): three draws per section per tick, capped by buffer.
        int[] buf = new int[16];
        check(new RandomTickMath(7).drawSection(3, buf) == 3, "default speed draws 3");
        check(new RandomTickMath(7).drawSection(0, buf) == 0, "speed 0 draws nothing");
        check(new RandomTickMath(7).drawSection(99, buf) == 16, "buffer caps the draw");

        // Probability: over many draws, every cell is hit about speed/4096 of the time (uniform LCG bits).
        RandomTickMath u = new RandomTickMath(12345);
        int[] hits = new int[4096];
        int draws = 4096 * 2000;
        for (int d = 0; d < draws; d++) {
            hits[u.nextCell()]++;
        }
        int min = Integer.MAX_VALUE, max = 0;
        for (int h : hits) {
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        // Expected 2000 per cell with speed 4096 draws/4096 cells; allow a loose band (LCG low bits are fine here).
        check(min > 1000 && max < 3000, "cell hit counts roughly uniform: min=" + min + " max=" + max);

        // Chunk key: same chunk, same key; neighbours differ; negative coordinates do not collide.
        check(RandomTickMath.chunkKey(0, 0) == RandomTickMath.chunkKey(15, 15), "one chunk one key");
        check(RandomTickMath.chunkKey(0, 0) != RandomTickMath.chunkKey(16, 0), "adjacent chunks differ");
        check(RandomTickMath.chunkKey(-1, 0) != RandomTickMath.chunkKey(1, 0), "negative x does not collide");
        check(RandomTickMath.chunkKey(-16, -16) != RandomTickMath.chunkKey(16, 16), "negative and positive differ");

        System.out.println(failures == 0 ? "RandomTickMathSelfTest OK" : "RandomTickMathSelfTest FAILED " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
