package dev.cim.keys;

/**
 * Combat Roll 2.0.6 (NeoForge 1.21.1) roll rules, pure Java.
 *
 * <p>Ported from the decompiled jar: {@code MinecraftClientMixin.tryRolling} (gates and
 * velocity), {@code RollManager} (charges, recharge, cooldown), {@code CombatRoll.Attributes}
 * (attribute defaults and ranges), {@code ServerConfig} (defaults) and the enchantment data
 * under {@code data/combat_roll/enchantment/}. The Bukkit side lives in {@link CombatRollAdapter}.
 *
 * <p>Known gaps, all on the client in the original and not observable on the server: the attack
 * key gate (a held attack key blocks the roll), the right-click delay, and BetterCombat's upswing
 * cancel. The roll sets the velocity through the server, so the client's own move-prediction is
 * not reproduced.
 */
public final class CombatRollRules {

    // ServerConfig defaults (ServerConfig.<init>).
    public static final int ROLL_DURATION = 8;
    public static final boolean ALLOW_AIRBORNE = false;
    public static final boolean ALLOW_WEAPON_COOLDOWN = false;
    public static final float FOOD_LEVEL_REQUIRED = 6.0f;
    public static final float EXHAUST_ON_ROLL = 0.1f;
    public static final float ROLL_COOLDOWN_SECONDS = 4.0f;
    public static final float ADDITIONAL_ROLL_DISTANCE = 0.0f;
    public static final int INVULNERABLE_TICKS_UPON_ROLL = 0;

    // CombatRoll.Attributes: entry(name, base, min, max, syncable) -> RangedAttribute(base, min, max).
    public static final double DISTANCE_BASE = 3.0;
    public static final double DISTANCE_MIN = 1.0;
    public static final double DISTANCE_MAX = 24.0;
    public static final double RECHARGE_BASE = 20.0;
    public static final double RECHARGE_MIN = 0.1;
    public static final double RECHARGE_MAX = 200.0;
    public static final double COUNT_BASE = 1.0;
    public static final double COUNT_MIN = 0.0;
    public static final double COUNT_MAX = 20.0;

    /** Grass block friction: the reference the friction scale divides by. */
    static final float GRASS_FRICTION = 0.6f;

    private CombatRollRules() {
    }

    /** A Vec3 as the client builds it. */
    public record Vec(double x, double y, double z) {
        Vec scale(double s) {
            return new Vec(x * s, y * s, z * s);
        }
    }

    /** Levels of the three enchantments that feed the attributes; zero when absent. */
    public record Enchants(int longfooted, int acrobat, int multiRoll) {
    }

    /** The three attribute values a player has (already clamped to the ranges). */
    public record Loadout(double distance, double recharge, double count) {
    }

    /**
     * Attribute value from the enchantment modifiers. longfooted (feet) is add_value of
     * {@code 1.0 + 1.0*(L-1)} = L on distance; acrobat (chest) is add_multiplied_base of
     * {@code 0.2 + 0.2*(L-1)} = 0.2L on recharge; multi_roll (head) is add_value of L on count.
     */
    public static Loadout loadout(Enchants e) {
        double distance = DISTANCE_BASE + addValue(e.longfooted(), 1.0, 1.0);
        double recharge = RECHARGE_BASE + RECHARGE_BASE * addValue(e.acrobat(), 0.2, 0.2);
        double count = COUNT_BASE + addValue(e.multiRoll(), 1.0, 1.0);
        return new Loadout(clamp(distance, DISTANCE_MIN, DISTANCE_MAX),
                clamp(recharge, RECHARGE_MIN, RECHARGE_MAX), clamp(count, COUNT_MIN, COUNT_MAX));
    }

    /** Linear enchantment amount for a level (0 when the enchantment is absent). */
    static double addValue(int level, double base, double perLevelAboveFirst) {
        return level <= 0 ? 0.0 : base + perLevelAboveFirst * (level - 1);
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * The per-player state RollManager keeps. Starts like the client's constructor: rolled
     * 10 ticks ago, no charges, no cooldown length yet.
     */
    public static final class RollState {
        int timeSinceLastRoll = 10;
        float cooldownProgress;
        int cooldownLength;
        int maxRolls = 1;
        int availableRolls;
        int invulnerableTicks;

        public boolean isRolling() {
            return timeSinceLastRoll <= ROLL_DURATION;
        }

        public int availableRolls() {
            return availableRolls;
        }

        public int cooldownLength() {
            return cooldownLength;
        }

        public int invulnerableTicks() {
            return invulnerableTicks;
        }

        /** RollManager.isRollAvailable: enabled, not rolling, a charge, not dead, can move. */
        public boolean isAvailable(boolean dead, double moveSpeed) {
            return !isRolling() && availableRolls > 0 && !dead && moveSpeed > 0.0;
        }

        /** RollManager.tick (one client tick: count from the attribute, then recharge). Returns true when a charge came back. */
        public boolean tick(Loadout loadout) {
            maxRolls = (int) loadout.count();
            timeSinceLastRoll++;
            boolean recharged = false;
            if (availableRolls < maxRolls) {
                cooldownProgress += increment(loadout);
                if (cooldownProgress >= (float) cooldownLength) {
                    rechargeRoll();
                    recharged = true;
                }
            }
            if (availableRolls == maxRolls) {
                cooldownProgress = 0.0f;
            }
            if (availableRolls > maxRolls) {
                availableRolls = maxRolls;
            }
            if (invulnerableTicks > 0) {
                invulnerableTicks--;
            }
            return recharged;
        }

        /** RollManager.onRoll: spends a charge and restarts the roll timer. */
        public void onRoll() {
            availableRolls--;
            timeSinceLastRoll = 0;
            updateCooldownLength();
        }

        /** The server side of a roll: invulnerability for the configured ticks (set, not added). */
        public void startInvulnerability() {
            invulnerableTicks = INVULNERABLE_TICKS_UPON_ROLL;
        }

        private void rechargeRoll() {
            availableRolls++;
            cooldownProgress = Math.max(cooldownProgress - (float) cooldownLength, 0.0f);
            updateCooldownLength();
        }

        private void updateCooldownLength() {
            cooldownLength = Math.round(ROLL_COOLDOWN_SECONDS * 20.0f);
        }

        /** RollManager.increment: recharge attribute / 20 per tick, as a float. */
        static float increment(Loadout loadout) {
            return (float) (loadout.recharge() / 20.0);
        }
    }

    /** The movement-relevant bits of MinecraftClientMixin.tryRolling. */
    public record Body(boolean onGround, int food, boolean swimming, boolean inVehicle,
                       boolean usingItemOrBlocking, double attackStrength) {
    }

    /** Every gate of tryRolling that the server can see, in the client's order. */
    public static boolean gatesPass(RollState state, double moveSpeed, boolean dead, Body body) {
        if (!state.isAvailable(dead, moveSpeed)) {
            return false;
        }
        if (!ALLOW_AIRBORNE && !body.onGround()) {
            return false;
        }
        if (!((float) body.food() > FOOD_LEVEL_REQUIRED)) {
            return false;
        }
        if (body.swimming() || body.inVehicle() || body.usingItemOrBlocking()) {
            return false;
        }
        return ALLOW_WEAPON_COOLDOWN || !(body.attackStrength() < 0.95);
    }

    /**
     * The roll impulse, as tryRolling builds it. {@code forward} and {@code left} are the
     * vanilla impulses (-1, 0, 1); with no input the direction is straight ahead. {@code waterHeight}
     * is the water fluid height in [0, 1] when {@code inWater} (ignored otherwise). {@code friction}
     * is the friction of the block under the feet.
     */
    public static Vec impulse(float yawDegrees, float forward, float left, double distance,
                              boolean inWater, double waterHeight, boolean inLava, float friction) {
        Vec dir = (forward == 0.0f && left == 0.0f) ? new Vec(0.0, 0.0, 1.0)
                : normalize(new Vec(left, 0.0, forward));
        float angle = (float) Math.toRadians(-1.0 * yawDegrees);
        dir = yRot(dir, angle);
        Vec v = dir.scale(0.475 * (distance + ADDITIONAL_ROLL_DISTANCE));
        if (inWater) {
            double fh = Math.min(waterHeight, 1.0);
            v = v.scale(Math.max(1.0 - fh * 3.0, 0.3));
        }
        if (inLava) {
            v = v.scale(0.3);
        }
        if (friction > GRASS_FRICTION) {
            float f = GRASS_FRICTION / friction;
            v = v.scale(f * f);
        }
        return v;
    }

    /** Vec3.normalize: the zero vector when the length is under 1e-4. */
    static Vec normalize(Vec v) {
        double d = Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
        return d < 1.0E-4 ? new Vec(0.0, 0.0, 0.0) : new Vec(v.x() / d, v.y() / d, v.z() / d);
    }

    /** Vec3.yRot: x' = x*cos + z*sin, z' = z*cos - x*sin. */
    static Vec yRot(Vec v, float angle) {
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        return new Vec(v.x() * cos + v.z() * sin, v.y(), v.z() * cos - v.x() * sin);
    }

    /** Friction of the block a player stands on (Block.getFriction for the blocks that differ). */
    public static float friction(String blockKey) {
        return switch (blockKey) {
            case "minecraft:ice", "minecraft:packed_ice" -> 0.98f;
            case "minecraft:slime_block" -> 0.8f;
            default -> GRASS_FRICTION;
        };
    }

    // ---- selftest ------------------------------------------------------------

    private static int check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        return ok ? 0 : 1;
    }

    private static boolean near(double a, double b, double tol) {
        return Math.abs(a - b) <= tol;
    }

    private static boolean vecNear(Vec v, double x, double y, double z) {
        return near(v.x(), x, 1e-6) && near(v.y(), y, 1e-6) && near(v.z(), z, 1e-6);
    }

    /** {@code CombatRollRules --selftest}. Expected values are worked by hand from the decompiled formula. */
    public static void main(String[] args) {
        int bad = 0;
        Loadout base = loadout(new Enchants(0, 0, 0));
        bad += check(base.distance() == 3.0 && base.recharge() == 20.0 && base.count() == 1.0,
                "attribute defaults: distance 3, recharge 20, count 1");
        Loadout tuned = loadout(new Enchants(2, 3, 3));
        bad += check(tuned.distance() == 5.0, "longfooted L2: distance 3 + 2 = 5");
        bad += check(near(tuned.recharge(), 32.0, 1e-9), "acrobat L3: recharge 20 * (1 + 0.6) = 32");
        bad += check(tuned.count() == 4.0, "multi_roll L3: count 1 + 3 = 4");
        bad += check(loadout(new Enchants(30, 0, 0)).distance() == 24.0 && loadout(new Enchants(0, 0, 99)).count() == 20.0,
                "attributes clamp to the RangedAttribute ranges");

        // Gates. Start from a state with one charge and no roll in progress.
        RollState ready = new RollState();
        for (int i = 0; i < 2; i++) {
            ready.tick(base);
        }
        Body onGround = new Body(true, 20, false, false, false, 1.0);
        bad += check(ready.availableRolls() == 1, "first tick after join gives the one charge");
        bad += check(gatesPass(ready, 0.1, false, onGround), "gates: on ground, fed, ready -> roll");
        bad += check(!gatesPass(ready, 0.1, false, new Body(false, 20, false, false, false, 1.0)),
                "gates: airborne refused (allow_rolling_while_airborn is false)");
        bad += check(!gatesPass(ready, 0.1, false, new Body(true, 6, false, false, false, 1.0)),
                "gates: food 6 refused (needs above 6)");
        bad += check(gatesPass(ready, 0.1, false, new Body(true, 7, false, false, false, 1.0)),
                "gates: food 7 allowed");
        bad += check(!gatesPass(ready, 0.1, false, new Body(true, 20, false, false, false, 0.94)),
                "gates: attack strength 0.94 refused (needs 0.95)");
        bad += check(!gatesPass(ready, 0.1, false, new Body(true, 20, true, false, false, 1.0)),
                "gates: swimming refused");
        bad += check(!gatesPass(ready, 0.1, false, new Body(true, 20, false, false, true, 1.0)),
                "gates: using an item or blocking refused");
        bad += check(!gatesPass(ready, 0.0, false, onGround), "gates: zero movement speed refused");
        bad += check(!gatesPass(ready, 0.1, true, onGround), "gates: dead refused");

        // Cooldown: 80 ticks at recharge 20, one charge back at tick 80 exactly.
        RollState one = new RollState();
        for (int i = 0; i < 300; i++) {
            one.tick(base);
        }
        bad += check(one.availableRolls() == 1 && one.cooldownLength() == 80,
                "cooldown length is 4 s = 80 ticks, one charge");
        one.onRoll();
        bad += check(one.availableRolls() == 0 && one.isRolling(), "after a roll: no charge, rolling");
        int ticksToCharge = 0;
        while (one.availableRolls() == 0 && ticksToCharge < 500) {
            one.tick(base);
            ticksToCharge++;
        }
        bad += check(ticksToCharge == 80, "charge returns after 80 ticks at recharge 20, got " + ticksToCharge);

        // Charges: multi_roll L1 gives two charges.
        RollState two = new RollState();
        Loadout twoCharges = loadout(new Enchants(0, 0, 1));
        for (int i = 0; i < 300; i++) {
            two.tick(twoCharges);
        }
        bad += check(two.availableRolls() == 2, "multi_roll L1: two charges");
        two.onRoll();
        two.onRoll();
        bad += check(!two.isAvailable(false, 0.1) && two.availableRolls() == 0, "two rolls spend both charges");
        for (int i = 0; i < 9; i++) {
            two.tick(twoCharges);
        }
        bad += check(two.isRolling() == false && two.availableRolls() == 0,
                "roll timer is over after 9 ticks, but no charge until the recharge");

        // Velocity. Magnitude is 0.475 * distance = 1.425 at the default distance 3.
        Vec none = impulse(0.0f, 0.0f, 0.0f, 3.0, false, 0.0, false, 0.6f);
        bad += check(vecNear(none, 0.0, 0.0, 1.425), "no input, yaw 0 -> (0, 0, 1.425)");
        Vec diag = impulse(0.0f, 1.0f, 1.0f, 3.0, false, 0.0, false, 0.6f);
        bad += check(vecNear(diag, 1.0076271632, 0.0, 1.0076271632),
                "forward+left, yaw 0 -> normalized (1,0,1)/sqrt2 * 1.425");
        Vec west = impulse(90.0f, 1.0f, 0.0f, 3.0, false, 0.0, false, 0.6f);
        bad += check(vecNear(west, -1.425, 0.0, 0.0), "forward, yaw 90 -> (-1.425, 0, 0)");
        Vec backLeft = impulse(0.0f, -1.0f, 0.0f, 3.0, false, 0.0, false, 0.6f);
        bad += check(vecNear(backLeft, 0.0, 0.0, -1.425), "backward, yaw 0 -> (0, 0, -1.425)");
        Vec ice = impulse(0.0f, 0.0f, 0.0f, 3.0, false, 0.0, false, 0.98f);
        bad += check(vecNear(ice, 0.0, 0.0, 1.425 * 0.37484384), "ice friction 0.98 -> scaled by (0.6/0.98)^2");
        bad += check(friction("minecraft:ice") == 0.98f && friction("minecraft:stone") == 0.6f,
                "friction table: ice 0.98, stone 0.6");
        Vec water02 = impulse(0.0f, 0.0f, 0.0f, 3.0, true, 0.2, false, 0.6f);
        bad += check(vecNear(water02, 0.0, 0.0, 1.425 * 0.4), "water fluid height 0.2 -> scale 1-0.6 = 0.4");
        Vec water05 = impulse(0.0f, 0.0f, 0.0f, 3.0, true, 0.5, false, 0.6f);
        bad += check(vecNear(water05, 0.0, 0.0, 1.425 * 0.3), "water fluid height 0.5 -> floor 0.3");
        Vec lava = impulse(0.0f, 0.0f, 0.0f, 3.0, false, 0.0, true, 0.6f);
        bad += check(vecNear(lava, 0.0, 0.0, 1.425 * 0.3), "lava -> scale 0.3");
        Vec longfoot = impulse(0.0f, 0.0f, 0.0f, 5.0, false, 0.0, false, 0.6f);
        bad += check(vecNear(longfoot, 0.0, 0.0, 2.375), "distance 5 -> magnitude 2.375");
        bad += check(vecNear(normalize(new Vec(0, 0, 0)), 0, 0, 0), "normalize of zero stays zero");

        System.out.println(bad == 0 ? "CombatRollRules selftest: ALL PASS" : "CombatRollRules selftest: " + bad + " FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }
}
