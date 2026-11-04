import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.BlockMath;
import net.minecraft.core.Direction;
import com.mojang.math.Transformation;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.Map;

public class Probe {
    public static void main(String[] a) throws Exception {
        Field f = BlockMath.class.getField("VANILLA_UV_TRANSFORM_LOCAL_TO_GLOBAL");
        Map<?, ?> m = (Map<?, ?>) f.get(null);
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        String[] fn = {"down", "up", "north", "south", "west", "east"};
        for (Direction d : faces) {
            Matrix4f mm = new Matrix4f(((Transformation) m.get(d)).getMatrix());
            StringBuilder sb = new StringBuilder("L2G " + fn[d.ordinal()] + " ");
            for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) sb.append(String.format("%8.4f", mm.get(r, c)));
            System.out.println(sb);
        }
        // real UVL matrices for composition fitting
        for (BlockModelRotation rot : BlockModelRotation.values()) {
            for (Direction d : faces) {
                Matrix4f mm = new Matrix4f(BlockMath.getUVLockTransform(rot.getRotation(), d).getMatrix());
                StringBuilder sb = new StringBuilder("UVL " + rot.name() + " " + fn[d.ordinal()] + " ");
                for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) sb.append(String.format("%8.4f", mm.get(r, c)));
                System.out.println(sb);
            }
        }
    }
}
