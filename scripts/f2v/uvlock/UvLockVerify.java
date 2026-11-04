import dev.cim.f2v.UvLock;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.resources.model.BlockModelRotation;
import net.minecraft.core.BlockMath;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.FaceInfo;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * 14-ah verification: the UvLock port vs the REAL 1.21.1 classes.
 *
 * Layers:
 *  B. the 96 UVL constants vs BlockMath.getUVLockTransform (exact)
 *  C. recomputeUvs vs FaceBakery.recomputeUVs (exact floats, exhaustive
 *     16 tiers x 6 faces x 8 rects x 4 input rotations)
 *  D. the 96 PERM constants vs the real FaceInfo corner transport
 *  E. END-TO-END uvlock invariant: per physical corner, the truth's
 *     rotated-quad uv sample == our canonical-corner sample
 *  F. plain-path cross-check: the 14-ag v-swap emission satisfies the same
 *     invariant for the non-uvlock semantics on x=90/270 x-remapped faces
 *
 * Run (JAVA_HOME=jdk21+, the official-renamed 1.21.1 client jar + libs):
 *   javac -proc:none -cp <jar>:<libs> UvLockVerify.java <UvLock.java>
 *   java  -cp .:<jar>:<libs> UvLockVerify
 */
public class UvLockVerify {

    static int fails = 0;

    public static void main(String[] args) {
        Direction[] faces = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
        String[] fnames = {"down", "up", "north", "south", "west", "east"};
        float[][] rects = {
                {0, 0, 16, 16}, {4, 2, 12, 10}, {0, 8, 16, 16}, {3, 3, 13, 13},
                {-16, 16, 0, 32}, {0, 0, 16, 8}, {8, 0, 16, 16}, {16, 0, 32, 16},
        };

        for (BlockModelRotation rot : BlockModelRotation.values()) {
            int ord = rot.ordinal();
            int x = ord / 4 * 90;
            int y = ord % 4 * 90;
            int tier = UvLock.tierIdx(x, y);

            for (int fi = 0; fi < 6; fi++) {
                Direction f = faces[fi];

                // B. the UVL constant vs the real getUVLockTransform
                Matrix4f realUvl = new Matrix4f(BlockMath.getUVLockTransform(rot.getRotation(), f).getMatrix());
                float[] mineUvl = new float[16];
                for (int i = 0; i < 16; i++) {
                    mineUvl[i] = UvLock.uvlRow(tier, fi)[i];
                }
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) {
                        if (Math.abs(realUvl.get(r, c) - mineUvl[r * 4 + c]) > 1e-5f) {
                            fails++;
                            System.out.printf("FAIL B %s %s [%d][%d] real=%.5f mine=%.5f%n",
                                    rot.name(), f, r, c, realUvl.get(r, c), mineUvl[r * 4 + c]);
                        }
                    }
                }

                // C. recomputeUvs exhaustive
                for (float[] rect : rects) {
                    for (int inRot : new int[]{0, 90, 180, 270}) {
                        BlockFaceUV realOut = FaceBakery.recomputeUVs(new BlockFaceUV(rect.clone(), inRot), f, rot.getRotation());
                        float[] mineOut = UvLock.recomputeUvs(rect, inRot, tier, fi);
                        boolean ok = realOut.uvs != null
                                && Math.abs(realOut.uvs[0] - mineOut[0]) < 1e-4
                                && Math.abs(realOut.uvs[1] - mineOut[1]) < 1e-4
                                && Math.abs(realOut.uvs[2] - mineOut[2]) < 1e-4
                                && Math.abs(realOut.uvs[3] - mineOut[3]) < 1e-4
                                && realOut.rotation == (int) mineOut[4];
                        if (!ok) {
                            fails++;
                            System.out.printf("FAIL C %s %s rect=%s inRot=%d real=%s rot=%d mine=%s%n",
                                    rot.name(), f, Arrays.toString(rect), inRot,
                                    Arrays.toString(realOut.uvs), realOut.rotation, Arrays.toString(mineOut));
                        }
                    }
                }

                // D. the PERM constant vs the real corner transport
                int[] perm = UvLock.permRow(tier, fi);
                Direction mapped = Direction.rotate(new Matrix4f(rot.getRotation().getMatrix()), f);
                FaceInfo ofInfo = FaceInfo.fromFacing(f);
                FaceInfo mfInfo = FaceInfo.fromFacing(mapped);
                for (int j = 0; j < 4; j++) {
                    FaceInfo.VertexInfo target = mfInfo.getVertexInfo(j);
                    float[] sel = {selOf(target.xFace), selOf(target.yFace), selOf(target.zFace)};
                    boolean matched = false;
                    for (int i = 0; i < 4; i++) {
                        FaceInfo.VertexInfo orig = ofInfo.getVertexInfo(i);
                        float[] oc = {selOf(orig.xFace), selOf(orig.yFace), selOf(orig.zFace)};
                        var out = new Matrix4f(rot.getRotation().getMatrix())
                                .transformDirection(new org.joml.Vector3f(oc[0] - 0.5f, oc[1] - 0.5f, oc[2] - 0.5f));
                        float rx = Math.round(out.x() + 0.5f), ry = Math.round(out.y() + 0.5f), rz = Math.round(out.z() + 0.5f);
                        if (rx == sel[0] && ry == sel[1] && rz == sel[2]) {
                            matched = true;
                            if (perm[j] != i) {
                                fails++;
                                System.out.printf("FAIL D %s %s j=%d realPerm=%d mine=%d%n",
                                        rot.name(), f, j, i, perm[j]);
                            }
                            break;
                        }
                    }
                    if (!matched) {
                        fails++;
                        System.out.printf("FAIL D %s %s j=%d no real match%n", rot.name(), f, j);
                    }
                }

                // E. end-to-end uvlock invariant per physical corner
                for (float[] rect : rects) {
                    BlockFaceUV rec = FaceBakery.recomputeUVs(new BlockFaceUV(rect.clone(), 0), f, rot.getRotation());
                    float[] emitted = new float[5];
                    UvLock.bakeUvlockFace(rect, 0, fnames[fi], x, y, emitted);
                    int kOut = (int) emitted[4];
                    for (int j = 0; j < 4; j++) {
                        int i = perm[j];
                        float[] truthSample = walkSample(rec.uvs, (i + rec.rotation / 90) % 4);
                        float[] oursSample = walkSample(new float[]{emitted[0], emitted[1], emitted[2], emitted[3]},
                                (j + kOut / 90) % 4);
                        if (Math.abs(truthSample[0] - oursSample[0]) > 1e-4 || Math.abs(truthSample[1] - oursSample[1]) > 1e-4) {
                            fails++;
                            System.out.printf("FAIL E %s %s rect=%s corner j=%d truth=%s ours=%s emitted=%s%n",
                                    rot.name(), f, Arrays.toString(rect), j,
                                    Arrays.toString(truthSample), Arrays.toString(oursSample), Arrays.toString(emitted));
                        }
                    }
                }

                // G. 14-ai EXACT plain-path transport: bakePlainFace must
                // satisfy the same per-corner invariant for the RAW rect
                // (no recompute), for every tier/face/rect/input-rotation
                for (float[] rect : rects) {
                    for (int inRot : new int[]{0, 90, 180, 270}) {
                        float[] emitted = new float[5];
                        UvLock.bakePlainFace(rect, inRot, fnames[fi], x, y, emitted);
                        int kEm = (int) emitted[4];
                        for (int j = 0; j < 4; j++) {
                            int i = perm[j];
                            float[] truthSample = walkSample(rect, (i + inRot / 90) % 4);
                            float[] oursSample = walkSample(new float[]{emitted[0], emitted[1], emitted[2], emitted[3]},
                                    (j + kEm / 90) % 4);
                            if (Math.abs(truthSample[0] - oursSample[0]) > 1e-4
                                    || Math.abs(truthSample[1] - oursSample[1]) > 1e-4) {
                                fails++;
                                System.out.printf("FAIL G %s %s rect=%s inRot=%d corner j=%d truth=%s ours=%s emitted=%s%n",
                                        rot.name(), f, Arrays.toString(rect), inRot, j,
                                        Arrays.toString(truthSample), Arrays.toString(oursSample), Arrays.toString(emitted));
                            }
                        }
                    }
                }

                // F. plain-path v-swap cross-check (non-uvlock semantics)
                if ((x == 90 || x == 270) && fi <= 3) {
                    for (float[] rect : rects) {
                        float[] swapped = {rect[0], rect[3], rect[2], rect[1]};
                        for (int j = 0; j < 4; j++) {
                            int i = perm[j];
                            float[] truthSample = walkSample(rect, i % 4);
                            float[] oursSample = walkSample(swapped, j);
                            if (Math.abs(truthSample[0] - oursSample[0]) > 1e-4
                                    || Math.abs(truthSample[1] - oursSample[1]) > 1e-4) {
                                fails++;
                                System.out.printf("FAIL F %s %s rect=%s corner j=%d truth=%s swapped=%s%n",
                                        rot.name(), f, Arrays.toString(rect), j,
                                        Arrays.toString(truthSample), Arrays.toString(oursSample));
                            }
                        }
                    }
                }
            }
        }
        System.out.println(fails == 0 ? "UVLOCK VERIFY: ALL OK" : "UVLOCK VERIFY: " + fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** walk slot -> (u, v) sample of a BlockFaceUV-style uv array. */
    static float[] walkSample(float[] uvs, int slot) {
        float u = (slot == 0 || slot == 1) ? uvs[0] : uvs[2];
        float v = (slot == 0 || slot == 3) ? uvs[1] : uvs[3];
        return new float[]{u, v};
    }

    static float selOf(int const_) {
        return (const_ == FaceInfo.Constants.MIN_X || const_ == FaceInfo.Constants.MIN_Y
                || const_ == FaceInfo.Constants.MIN_Z) ? 0f : 1f;
    }
}
