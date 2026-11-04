import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.Direction;
import com.mojang.math.Transformation;

public class UVProbe {
    public static void main(String[] args) {
        String[] variants = {
            "X90_Y90",  // pillar axis=x (both-rotated)
            "X90_Y0",   // pillar axis=z
            "X180_Y180",// stairs facing=south half=top
            "X0_Y90",   // stairs facing=? single y
            "X180_Y0",  // half=top facing=north
            "X0_Y180",  // facing=south bottom
        };
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (String name : variants) {
            BlockModelRotation rot = BlockModelRotation.valueOf(name);
            Transformation t = rot.getRotation();
            System.out.println("== " + name);
            for (Direction f : faces) {
                // the truth's null-uv face: defaultFaceUV computed by the
                // BlockModel deserializer for the ORIGINAL face — full cube:
                float[] def = defaultUv(f, 0, 0, 0, 16, 16, 16);
                BlockFaceUV uv = new BlockFaceUV(def, 0);
                BlockFaceUV out = FaceBakery.recomputeUVs(uv, f, t);
                float[] o = out.uvs;
                Direction mapped = rot.actualRotation().rotate(f);
                System.out.printf("  %-5s -> %-5s uv=[%s] rot=%d  (raw %s)%n",
                    f, mapped,
                    o == null ? "null" : String.format("%.0f,%.0f,%.0f,%.0f", o[0], o[1], o[2], o[3]),
                    out.rotation, java.util.Arrays.toString(o));
            }
        }
    }

    // the decompiled 1.21.1 BlockModel deserializer's defaultFaceUV
    static float[] defaultUv(Direction d, float x1, float y1, float z1, float x2, float y2, float z2) {
        return switch (d) {
            case DOWN -> new float[]{x1, 16 - z2, x2, 16 - z1};
            case UP -> new float[]{x1, z1, x2, z2};
            case NORTH -> new float[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case SOUTH -> new float[]{x1, 16 - y2, x2, 16 - y1};
            case WEST -> new float[]{z1, 16 - y2, z2, 16 - y1};
            case EAST -> new float[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
        };
    }
}
