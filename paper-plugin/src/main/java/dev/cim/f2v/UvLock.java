package dev.cim.f2v;

/**
 * 14-ah: the 1.21.1 uvlock bake for the gallery composite bake. Ground truth
 * extracted from the REAL 1.21.1 classes (scripts/f2v/uvlock/ holds the probe
 * sources and their outputs):
 *
 * <ul>
 *   <li>the 1.21.1 block bake gates recomputeUVs on ModelState.isUvLocked -
 *       for a uvlocked variant EVERY face uv (default or explicit) is
 *       recomputed through recomputeUVs(uv, face, getUVLockTransform);</li>
 *   <li>create blockstates carry uvlock:true on the stairs/slab/wall families
 *       (the ~300-cell clean RED pool);</li>
 *   <li>the 96 getUVLockTransform matrices and the 96 FaceInfo corner
 *       transports are MEASURED constants here (jcrontab: joml's mul/translate
 *       conventions made a chain reconstruction error-prone - the constants
 *       are pinned by UvLockVerify against the real classes instead);</li>
 *   <li>recomputeUVs itself is the verbatim algorithm port (corner walk,
 *       sign-preserving rect reassembly, rotation-index recompute).</li>
 * </ul>
 *
 * <p>The WINDING TRANSPORT: the truth renders the recomputed uv on the ROTATED
 * quad (corners = the transported FaceInfo corners of the ORIGINAL face);
 * our composite renders on the canonical corners of the REMAPPED face. The
 * measured corner permutation relabels the recomputed rect before emission -
 * on x=90/270 x-remapped faces it is the V-mirror reflection, the same
 * transport the 14-ag v-mirror fix validated pixel-wise on #84.
 *
 * <p>Pure java, no joml. Matrix entries are the joml get(r,c) form; the
 * consumption follows joml's transform (x' = m00*x + m10*y + m20*z + m30*w).
 */
public final class UvLock {

    private UvLock() {
    }

    /** BlockModelRotation ordinal: index = (x/90)*4 + y/90 over X0..X270, Y0..Y270. */
    public static int tierIdx(int xRot, int yRot) {
        int x = (((xRot % 360) + 360) % 360) / 90;
        int y = (((yRot % 360) + 360) % 360) / 90;
        return x * 4 + y;
    }

    static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    static int faceIdx(String face) {
        for (int i = 0; i < 6; i++) {
            if (FACES[i].equals(face)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The 96 getUVLockTransform matrices, MEASURED from the real 1.21.1
     * BlockMath (row-major of the joml get(r,c) form; every entry an exact
     * -1/0/1). Index: [tierIdx(x,y) * 6 + faceIdx].
     */
    private static final byte[][] UVL = {
            // X0_Y0
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            // X0_Y90
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            // X0_Y180
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            // X0_Y270
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            // X90_Y0
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            // X90_Y90
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            // X90_Y180
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            // X90_Y270
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            // X180_Y0
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            // X180_Y90
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            // X180_Y180
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            // X180_Y270
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            // X270_Y0
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            // X270_Y90
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            // X270_Y180
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
            // X270_Y270
            {-1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1},
            {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1},
            {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1},
    };

    /**
     * The 96 winding-transport corner permutations, MEASURED from the real
     * FaceInfo tables + the tier rotations (PermProbe): perm[j] = i such that
     * the tier-rotated FaceInfo(origFace)[i] corner lands on the
     * FaceInfo(mappedFace)[j] corner of the canonical box.
     */
    private static final byte[][] PERM = {
            // X0_Y0
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            // X0_Y90
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            // X0_Y180
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            // X0_Y270
            {1, 2, 3, 0},
            {3, 0, 1, 2},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            // X90_Y0
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            // X90_Y90
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {1, 2, 3, 0},
            {1, 2, 3, 0},
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            // X90_Y180
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            // X90_Y270
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {3, 0, 1, 2},
            {3, 0, 1, 2},
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            // X180_Y0
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            // X180_Y90
            {1, 2, 3, 0},
            {3, 0, 1, 2},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            // X180_Y180
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            // X180_Y270
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            {2, 3, 0, 1},
            // X270_Y0
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {1, 2, 3, 0},
            {3, 0, 1, 2},
            // X270_Y90
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {3, 0, 1, 2},
            {3, 0, 1, 2},
            {1, 2, 3, 0},
            {3, 0, 1, 2},
            // X270_Y180
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {0, 1, 2, 3},
            {2, 3, 0, 1},
            {1, 2, 3, 0},
            {3, 0, 1, 2},
            // X270_Y270
            {2, 3, 0, 1},
            {0, 1, 2, 3},
            {1, 2, 3, 0},
            {1, 2, 3, 0},
            {1, 2, 3, 0},
            {3, 0, 1, 2},
    };

    /** the measured uvlock matrix (16 get-form entries) for (tier, face). */
    public static byte[] uvlRow(int tier, int face) {
        return UVL[tier * 6 + face];
    }

    /** the measured winding-transport permutation for (tier, face). */
    public static int[] permRow(int tier, int face) {
        byte[] p = PERM[tier * 6 + face];
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            out[i] = p[i];
        }
        return out;
    }

    /** getU over the walk: slots 0,1 carry u1; slots 2,3 carry u2. */
    private static float walkU(float[] uvs, int slot) {
        return (slot == 0 || slot == 1) ? uvs[0] : uvs[2];
    }

    /** getV over the walk: slots 0,3 carry v1; slots 1,2 carry v2. */
    private static float walkV(float[] uvs, int slot) {
        return (slot == 0 || slot == 3) ? uvs[1] : uvs[3];
    }

    /**
     * Port of FaceBakery.recomputeUVs(uv, face, rotation): transforms the uv
     * rect's diagonal corners through the uvlock matrix, reassembles the rect
     * preserving the input's axis orientations, and recomputes the rotation
     * index. The walk index in the real code is getU(getReverseIndex(i)) -
     * the reverse (i + 4 - k) then the internal shift (idx + k) CANCEL, so
     * the input corners are the rect's own diagonal regardless of inRot.
     * Returns {u1, v1, u2, v2, rot}.
     */
    public static float[] recomputeUvs(float[] uvs, int inRot, int tier, int face) {
        byte[] mm = UVL[tier * 6 + face];
        // joml transform semantics: x' = m00*x + m10*y + m20*z + m30*w
        float u0in = uvs[0], v0in = uvs[1];
        float px = mm[0] * (u0in / 16f) + mm[4] * (v0in / 16f) + mm[12];
        float py = mm[1] * (u0in / 16f) + mm[5] * (v0in / 16f) + mm[13];
        float u0 = 16f * px, v0 = 16f * py;
        float u1in = uvs[2], v1in = uvs[3];
        px = mm[0] * (u1in / 16f) + mm[4] * (v1in / 16f) + mm[12];
        py = mm[1] * (u1in / 16f) + mm[5] * (v1in / 16f) + mm[13];
        float u1 = 16f * px, v1 = 16f * py;
        float nu0, nu1;
        if (Math.signum(u1in - u0in) == Math.signum(u1 - u0)) {
            nu0 = u0;
            nu1 = u1;
        } else {
            nu0 = u1;
            nu1 = u0;
        }
        float nv0, nv1;
        if (Math.signum(v1in - v0in) == Math.signum(v1 - v0)) {
            nv0 = v0;
            nv1 = v1;
        } else {
            nv0 = v1;
            nv1 = v0;
        }
        float ang = (float) Math.toRadians(inRot);
        float dx = mm[0] * (float) Math.cos(ang) + mm[4] * (float) Math.sin(ang);
        float dy = mm[1] * (float) Math.cos(ang) + mm[5] * (float) Math.sin(ang);
        int rotOut = Math.floorMod(-(int) Math.round(Math.toDegrees(Math.atan2(dy, dx)) / 90.0) * 90, 360);
        return new float[]{nu0, nv0, nu1, nv1, rotOut};
    }

    /**
     * The relabel-fitting core shared by the uvlock and the plain paths:
     * find the (rect, rot) emission whose canonical-corner walk carries the
     * source walk slot (perm[j] + kOut/90) % 4 to corner j, fitting the
     * emitted rotation over the 4 values.
     */
    private static void relabelFit(float[] r, int kOut, byte[] perm, float[] result) {
        int kS = kOut / 90;              // the source walk SHIFT (slots)
        float eps = 1e-4f;
        for (int tryNo = 0; tryNo < 4; tryNo++) {
            int kEm = (kOut + tryNo * 90) % 360;
            int kE = kEm / 90;           // the emitted walk SHIFT (slots)
            float[] vx = new float[4];
            float[] vy = new float[4];
            for (int t = 0; t < 4; t++) {
                int phi = (perm[((t - kE) % 4 + 4) % 4] + kS) % 4;
                vx[t] = walkU(r, phi);
                vy[t] = walkV(r, phi);
            }
            boolean rect = Math.abs(vx[0] - vx[1]) < eps && Math.abs(vx[2] - vx[3]) < eps
                    && Math.abs(vy[0] - vy[3]) < eps && Math.abs(vy[1] - vy[2]) < eps;
            if (rect) {
                result[0] = vx[0];
                result[1] = vy[0];
                result[2] = vx[2];
                result[3] = vy[2];
                result[4] = kEm;
                return;
            }
        }
        // no (rect, rot) represents the relabeled walk exactly (harness-
        // monitored); the verbatim source rect is the honest fallback
        result[0] = r[0];
        result[1] = r[1];
        result[2] = r[2];
        result[3] = r[3];
        result[4] = kOut;
    }

    /**
     * The FULL uvlock bake for one face: recompute the uv through the truth's
     * chain, then relabel the rect corners through the measured winding
     * transport so the composite (unrotated, canonical-corner) render matches
     * the truth's rotated-quad render per physical corner.
     *
     * @param uvs    the face's uv rect (default or explicit), texel units
     * @param inRot  the face's existing rotation index (0 for defaults)
     * @param result float[5]: {u1, v1, u2, v2, rot}; rot = the rotation index
     *               to emit (0 = omit)
     */
    public static void bakeUvlockFace(float[] uvs, int inRot, String origFace, int xRot, int yRot, float[] result) {
        int tier = tierIdx(xRot, yRot);
        int fi = faceIdx(origFace);
        float[] rec = recomputeUvs(uvs, inRot, tier, fi);
        float[] r = {rec[0], rec[1], rec[2], rec[3]};
        relabelFit(r, (int) rec[4], PERM[tier * 6 + fi], result);
    }

    /**
     * 14-ai: the EXACT plain-path transport for one face of a rotated
     * NON-uvlocked variant. The truth's raw uv (default or explicit, with its
     * own rotation index) rides the rotated winding and lands on the mapped
     * canonical corners through the same measured position-rematch
     * permutation (kOut = the input rotation, NO recompute). For the
     * reflection-class perms at rotation 0 this reproduces the 14-ag v-swap
     * byte-for-byte; for the cycle-class perms (X90_Y0 up, X180_Y0 north,
     * X0_Y90 down, ...) it is the exact form the v-swap only approximated.
     * Identity-perm faces stay verbatim.
     */
    public static void bakePlainFace(float[] uvs, int inRot, String origFace, int xRot, int yRot, float[] result) {
        int tier = tierIdx(xRot, yRot);
        int fi = faceIdx(origFace);
        byte[] perm = PERM[tier * 6 + fi];
        if (perm[0] == 0 && perm[1] == 1 && perm[2] == 2 && perm[3] == 3) {
            result[0] = uvs[0];
            result[1] = uvs[1];
            result[2] = uvs[2];
            result[3] = uvs[3];
            result[4] = inRot;
            return;
        }
        relabelFit(uvs, inRot, perm, result);
    }
}
