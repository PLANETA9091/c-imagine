# 14-ah uvlock ground truth (the REAL 1.21.1 classes)

The 1.21.1 uvlock bake chain, extracted from the real srg classes and ported
into `paper-plugin/src/main/java/dev/cim/f2v/UvLock.java`.

## Reproduce the renamed client jar

The probes run against the official-mappings 1.21.1 client:

    java -jar AutoRenamingTool-2.0.3-all.jar \
        --input client-1.21.1.jar --output /tmp/121-official.jar \
        --map client-1.21.1.txt --reverse --ann-fix --ids-fix

(client-1.21.1.txt = the piston client_mappings; AutoRenamingTool rides in the
nfclient libraries; --reverse because the piston mappings are official->obf.)

## Probe -> compile/run (jdk21+, the renamed jar + the nfclient/truth-client
libraries on the classpath)

- `QuadTable.java`  -> `quadtable_full.out`: recomputeUVs rotation indexes +
  uv arrays for all 16 tiers x 6 faces, plain AND uvlock.
- `UVProbe.java`    -> `uvprobe_full.out`: the non-uvlock recompute behavior.
- `MatrixTable.java`-> `tiertable_full.out`: the 16 BlockModelRotation matrices
  + the 96 getUVLockTransform matrices (get(r,c) form).
- `MatProbe.java`   -> `uvltable_full.out`: the 96 UVL matrices via reflection
  over VANILLA_UV_TRANSFORM_LOCAL_TO_GLOBAL + getUVLockTransform.
- `PermProbe.java`  -> `probe_perms.out`: the 96 winding-transport corner
  permutations (FaceInfo corners, box-local, real joml transformDirection).
- `gen_uvlock.py`   : generates UvLock.java from the measured tables.
- `UvLockVerify.java`: the verifier (layers B-E vs the real classes; ALL OK).

## Measured facts (14-ah)

1. bakeQuad gates recomputeUVs on ModelState.isUvLocked (NOT hasRotation); a
   uvlocked variant recomputes EVERY face uv (default or explicit).
2. getUVLockTransform(T, face) maps the uv plane so the texture stays
   world-fixed under the tier rotation.
3. After makeVertices, recalculateWinding re-matches the quad's vertices to
   the canonical FaceInfo order of the CALCULATED facing, carrying each
   vertex's uv with it - the truth's value at the mapped canonical corner j
   is the recomputed walk slot (perm[j] + kOut/90) % 4.
4. The 14-ag plain-path v-swap is the EXACT transport only for the
   reflection-class perms at rotation 0; for the cycle-class perms
   (e.g. X90_Y0 up: perm=[2,3,0,1]) it renders a U-mirror of the truth - it
   passed #84 because the pillar cap textures are near-symmetric. The exact
   plain-path transport (the k-fitted relabel, same as the uvlock path) is
   the NEXT pool's lever (the axis=x rows / the arm-carrying variants).
