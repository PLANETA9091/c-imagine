import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.FaceInfo;
import org.joml.Matrix4f;

/** 14-ah: the winding-transport permutation probe - for every (tier, face)
 * it prints perm[j] = i such that the tier-rotated FaceInfo(origFace)[i]
 * corner lands on the FaceInfo(mappedFace)[j] corner (box-local corners,
 * real joml transformDirection). Output: permtable_full.out. */
public class PermProbe {
    static boolean isMin(int c) {
        return c == FaceInfo.Constants.MIN_X || c == FaceInfo.Constants.MIN_Y || c == FaceInfo.Constants.MIN_Z;
    }

    public static void main(String[] a) {
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        String[] fn = {"down", "up", "north", "south", "west", "east"};
        for (BlockModelRotation rot : BlockModelRotation.values()) {
            Matrix4f m = new Matrix4f(rot.getRotation().getMatrix());
            for (int fi = 0; fi < 6; fi++) {
                Direction mapped = Direction.rotate(new Matrix4f(rot.getRotation().getMatrix()), faces[fi]);
                FaceInfo of = FaceInfo.fromFacing(faces[fi]);
                FaceInfo mf = FaceInfo.fromFacing(mapped);
                int[] perm = new int[4];
                for (int j = 0; j < 4; j++) {
                    perm[j] = -1;
                    FaceInfo.VertexInfo t = mf.getVertexInfo(j);
                    float[] sel = {isMin(t.xFace) ? 0f : 1f, isMin(t.yFace) ? 0f : 1f, isMin(t.zFace) ? 0f : 1f};
                    for (int i = 0; i < 4; i++) {
                        FaceInfo.VertexInfo o = of.getVertexInfo(i);
                        float[] c = {isMin(o.xFace) ? 0f : 1f, isMin(o.yFace) ? 0f : 1f, isMin(o.zFace) ? 0f : 1f};
                        var out = m.transformDirection(new org.joml.Vector3f(c[0] - 0.5f, c[1] - 0.5f, c[2] - 0.5f));
                        float rx = out.x() + 0.5f, ry = out.y() + 0.5f, rz = out.z() + 0.5f;
                        if (Math.round(rx) == sel[0] && Math.round(ry) == sel[1] && Math.round(rz) == sel[2]) {
                            perm[j] = i;
                            break;
                        }
                    }
                }
                System.out.println("PERM " + rot.name() + " " + fn[fi] + " -> " + fn[mapped.ordinal()]
                        + " perm=" + java.util.Arrays.toString(perm));
            }
        }
    }
}
