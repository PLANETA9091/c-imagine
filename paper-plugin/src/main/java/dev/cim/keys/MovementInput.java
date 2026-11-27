package dev.cim.keys;

import org.bukkit.Input;

/**
 * A player's movement keys as the client last reported them (Paper's PlayerInputEvent).
 * The impulses follow vanilla KeyboardInput: a pressed key counts +1 on its positive side,
 * both or neither cancel to 0.
 */
public record MovementInput(boolean forward, boolean backward, boolean left, boolean right,
                            boolean jump, boolean sneak, boolean sprint) {

    /** No input reported yet. */
    public static final MovementInput NONE = new MovementInput(false, false, false, false, false, false, false);

    public static MovementInput of(Input input) {
        return new MovementInput(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(),
                input.isJump(), input.isSneak(), input.isSprint());
    }

    /** Vanilla forwardImpulse: +1 forward, -1 backward, 0 otherwise. */
    public float forwardImpulse() {
        return impulse(forward, backward);
    }

    /** Vanilla leftImpulse: +1 left, -1 right, 0 otherwise. */
    public float leftImpulse() {
        return impulse(left, right);
    }

    private static float impulse(boolean positive, boolean negative) {
        return positive == negative ? 0.0f : positive ? 1.0f : -1.0f;
    }
}
