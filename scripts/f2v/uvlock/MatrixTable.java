import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockMath;
import com.mojang.math.Transformation;
import org.joml.Matrix4f;

public class MatrixTable {
    public static void main(String[] args) {
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (BlockModelRotation rot : BlockModelRotation.values()) {
            Matrix4f m = new Matrix4f(rot.getRotation().getMatrix());
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 4; r++) {
                sb.append('[');
                for (int c = 0; c < 4; c++) {
                    float v = m.get(r, c);
                    sb.append(String.format("%.4f", v));
                    if (c < 3) sb.append(',');
                }
                sb.append(']');
            }
            System.out.println("MT " + rot.name() + " rowmajor=" + sb);
        }
        // the getUVLockTransform output matrices per (tier, face), row-major
        for (BlockModelRotation rot : BlockModelRotation.values()) {
            for (Direction f : faces) {
                Matrix4f m = new Matrix4f(BlockMath.getUVLockTransform(rot.getRotation(), f).getMatrix());
                StringBuilder sb = new StringBuilder();
                for (int r = 0; r < 4; r++) {
                    sb.append('[');
                    for (int c = 0; c < 4; c++) {
                        sb.append(String.format("%.5f", m.get(r, c)));
                        if (c < 3) sb.append(',');
                    }
                    sb.append(']');
                }
                System.out.println("UVL " + rot.name() + " " + f + " rowmajor=" + sb);
            }
        }
    }
}
