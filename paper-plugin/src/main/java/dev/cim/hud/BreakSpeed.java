package dev.cim.hud;

/**
 * How long a mod block takes to break, as NeoForge 1.21.1 times it, when Paper breaks its carrier
 * (a note block) instead. Pure math, no Bukkit: the Paper side reads the inputs and sets the
 * result as a transient multiplier on the player's BLOCK_BREAK_SPEED attribute.
 *
 * <p>The vanilla 1.21.1 chain (Player.getDigSpeed, BlockState.getDestroyProgress), with the
 * terms shared by both sides (haste, mining fatigue, water, air, the attribute's own base) left out
 * because they cancel in the ratio:
 * <pre>
 *   dig      = toolSpeed &gt; 1 ? toolSpeed + MINING_EFFICIENCY : toolSpeed
 *   progress = dig / hardness / (canHarvest ? 30 : 100)     (a block breaks at progress 1)
 *   canHarvest = !requiresCorrectToolForDrops || the held tool is correct for the block
 * </pre>
 * The carrier breaks at {@code progress_mod / progress_note}. Paper's note block never requires a
 * tool, so Paper always harvests it at 30, and its hardness is the vanilla 0.8 the layer reports.
 *
 * <p>The multiplier is applied as {@code MULTIPLY_SCALAR_1} (value * (1 + amount)), so the amount is
 * {@code multiplier - 1}. The attribute is clamped to [0, 1024] by the game, so a multiplier above
 * 1024 (a very soft block under a fast tool) is capped there.
 */
final class BreakSpeed {

    /** Divisor when the held tool harvests the block (vanilla {@code BlockState.getDestroyProgress}). */
    static final double HARVEST = 30;
    /** Divisor when the block's drops need a tool the player does not hold correctly. */
    static final double SLOW = 100;

    private BreakSpeed() {
    }

    /** {@code Player.getDigSpeed} before the shared factors: efficiency adds only to a speed above 1. */
    static double digSpeed(double toolSpeed, double efficiency) {
        return toolSpeed > 1 ? toolSpeed + efficiency : toolSpeed;
    }

    /** The share of the block one tick adds; the block breaks when the sum reaches 1. */
    static double progressPerTick(double dig, double hardness, boolean canHarvest) {
        return dig / hardness / (canHarvest ? HARVEST : SLOW);
    }

    /**
     * The multiplier on Paper's break speed that makes the carrier take the mod block's NeoForge
     * time. Requires {@code hardness > 0} and {@code refHardness > 0}; a hardness of 0 or below is
     * handled by the caller (instant break, or unbreakable).
     *
     * @param hardness      the mod block's destroy time
     * @param requiresTool  the mod block's requiresCorrectToolForDrops
     * @param correct       the held stack is a correct tool for the mod block
     * @param toolSpeed     the held stack's destroy speed on the mod block (1.0 for a non-tool)
     * @param refHardness   the note block's destroy time (vanilla 0.8)
     * @param refToolSpeed  the held stack's destroy speed on the note block
     * @param efficiency    the player's MINING_EFFICIENCY attribute
     */
    static double multiplier(double hardness, boolean requiresTool, boolean correct, double toolSpeed,
                             double refHardness, double refToolSpeed, double efficiency) {
        boolean canHarvest = !requiresTool || correct;
        double mod = progressPerTick(digSpeed(toolSpeed, efficiency), hardness, canHarvest);
        double note = progressPerTick(digSpeed(refToolSpeed, efficiency), refHardness, true);
        return mod / note;
    }

    /** The amount for an attribute modifier of {@code MULTIPLY_SCALAR_1}: value * (1 + amount) = value * multiplier. */
    static double modifierAmount(double multiplier) {
        return multiplier - 1;
    }

    /** Ticks a block takes at a progress per tick: the first tick whose sum reaches 1. */
    static int ticks(double progressPerTick) {
        return (int) Math.ceil(1.0 / progressPerTick - 1e-9);
    }

    // ---- selftest ----

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    /** {@code BreakSpeed --selftest}. */
    public static void main(String[] args) {
        int bad = 0;
        // The note block against itself, bare hand and iron axe: the carrier is the note block.
        bad += check(near(multiplier(0.8, false, true, 6, 0.8, 6, 0), 1.0),
                "a note-like block (0.8, no tool rule) under an iron axe is unchanged");
        bad += check(near(multiplier(0.8, false, true, 1, 0.8, 1, 0), 1.0),
                "the same block under the bare hand is unchanged");
        // A 2.0 block with no tool rule, iron axe: 6/2/30 vs 6/0.8/30 => 0.4.
        double m = multiplier(2.0, false, true, 6, 0.8, 6, 0);
        bad += check(near(m, 0.4), "a 2.0 block under an iron axe takes 2.5x the note's time: " + m);
        bad += check(ticks(progressPerTick(digSpeed(6, 0), 2.0, true)) == 10,
                "the 2.0 block with an iron axe is 10 ticks at NeoForge rates");
        double mAfter = m * progressPerTick(digSpeed(6, 0), 0.8, true);
        bad += check(ticks(mAfter) == 10, "the carrier with the multiplier breaks in the same 10 ticks: " + ticks(mAfter));
        // Wrong tool on a block that requires one: NeoForge harvests at 100, the carrier at 30 (x0.3).
        double wrong = multiplier(2.0, true, false, 1, 0.8, 1, 0);
        bad += check(near(wrong, 0.12), "a tool-required 2.0 block under the bare hand: (1/2/100)/(1/0.8/30) = 0.12: " + wrong);
        // Right tool on the same block: the speed, not the 100, decides.
        double right = multiplier(2.0, true, true, 6, 0.8, 6, 0);
        bad += check(near(right, 0.4), "a tool-required block under a correct iron pickaxe is 0.4: " + right);
        // Efficiency adds to a tool speed above 1 on both sides, so it cancels for equal hardness.
        bad += check(near(multiplier(1.0, true, true, 6, 1.0, 6, 10), 1.0),
                "efficiency on both sides with equal hardness cancels");
        // Efficiency: a 1.0 block with a 6 pickaxe at efficiency 10 (16) against a 0.8 note (16).
        bad += check(near(multiplier(1.0, true, true, 6, 0.8, 6, 10), 0.8),
                "efficiency reaches both sides: (16/1/30)/(16/0.8/30) = 0.8");
        // A bare hand adds no efficiency: a speed of 1 is not a tool.
        bad += check(near(multiplier(0.8, false, true, 1, 0.8, 1, 10), 1.0),
                "efficiency does not apply to a bare hand (speed 1)");
        // A sword on a block it does not harvest: the speed 15 counts, but the drop rule slows it to 100.
        double sword = multiplier(0.8, true, false, 15, 0.8, 6, 0);
        bad += check(near(sword, 0.75), "a sword (15, not correct) on a tool-required 0.8 block: (15/0.8/100)/(6/0.8/30) = 0.75: " + sword);
        // The multiplier scales the attribute's final value: amount = m - 1.
        bad += check(near(modifierAmount(0.4), -0.6), "the MULTIPLY_SCALAR_1 amount is multiplier - 1");
        bad += check(near(modifierAmount(1.0), 0.0), "an unchanged break keeps a zero amount");
        // Ticks from progress: 1.0 breaks in one tick, 0.25 in four, and exact boundaries do not round up.
        bad += check(ticks(1.0) == 1 && ticks(0.25) == 4 && ticks(0.5) == 2, "ticks are the ceiling of 1 / progress");
        System.out.println(bad == 0 ? "BreakSpeed selftest: ALL PASS" : "BreakSpeed selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
