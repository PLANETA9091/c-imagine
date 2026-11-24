package dev.cim.hud;

import org.bukkit.block.BlockFace;
import org.joml.Matrix4f;

/** Create 6.0.10's door panel transforms in the vanilla item-display coordinate space. */
final class DoorPanelTransform {
    private DoorPanelTransform() {
    }

    static Matrix4f matrix(BlockFace facing, boolean rightHinge, boolean firstPanel, float progress) {
        float sign = rightHinge ? -1 : 1;
        float eased = sign * progress * progress;
        float lip = Math.min(1, progress * 10) / 32;

        // Create's model coordinates have a block-corner origin. The display entity is centred.
        var transform = new Matrix4f()
                .translate(-0.5f + facing.getModX() * lip, -0.5f - 1f / 512,
                        -0.5f + facing.getModZ() * lip)
                .translate(0.5f, 0.5f, 0.5f)
                .rotateY(radians(facingAngle(facing)))
                .translate(-0.5f, -0.5f, -0.5f);
        if (rightHinge) {
            transform.translate(0, 0, 1);
        }
        transform.rotateY(radians(91 * eased));
        if (!firstPanel) {
            transform.translate(0, 0, sign * 0.5f).rotateY(radians(-181 * eased));
        }
        if (rightHinge) {
            transform.translate(0, 0, -0.5f);
        }
        // The client applies ItemDisplayRenderer's yaw after this matrix.
        return transform.translate(0.5f, 0.5f, 0.5f).rotateY((float) Math.PI);
    }

    static Matrix4f sliding(BlockFace facing, boolean rightHinge, boolean lower, float progress) {
        float travel = progress * progress * 13f / 16 * (rightHinge ? 1 : -1);
        float lip = Math.min(1, progress * 10) / 32;
        int x = facing.getModX();
        int z = facing.getModZ();
        return new Matrix4f()
                .translate(x * lip - z * travel, (lower ? 0 : 1) - 1f / 512, z * lip + x * travel)
                .rotateY(radians(facingAngle(facing)))
                .rotateY((float) Math.PI);
    }

    private static float facingAngle(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> -90;
            case WEST -> 180;
            case NORTH -> -270;
            case EAST -> 0;
            default -> throw new IllegalArgumentException("Non-horizontal door facing: " + facing);
        };
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
