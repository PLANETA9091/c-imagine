# Tile fidelity law — why the e273 output-density tile still lands edges 1–4 px off, and the minimal fix

Task B3 · repo c-imagine @ e2cbbbe3 · research-only (this file is the only artifact).
Inputs: `paper-plugin/src/main/java/dev/cim/f2v/ObjMesh.java` (full),
`paper-plugin/src/main/java/dev/cim/f2v/GalleryPack.java`,
`paper-plugin/src/main/java/dev/cim/paper/CimPlugin.java` (:1990-2072, :2350-2434, :2680-2769,
:3130-3259), `paper-plugin/src/main/java/dev/cim/f2v/F2vPack.java` (:4123-4134),
`scripts/f2v/selftest_poly.py`, `docs/worklog.md` receipts e273/e275/e278 (:9656/:9660/:9665),
`ci-logs/r632/forensics-supp.md` (A1) + `ci-logs/r632/forensics-bop.md` (A2b), and one census
grep over `/tmp/ci-logs/r632/reports/x-report-*/join-*.json`.

---

## 1. Problem statement (measured)

The persistent red class is **edge placement 1–4 px inside the tile path** — not displacement,
not AO, not tint, not animation, not mesh-binding:

| evidence | number | source |
|---|---|---|
| Same mesh, two lanes | rope_knot p205 c5r5 renders **ae = 46** through the ours-sm runtime lane vs **84,144** through the F2vPack tile lane | A1 §4 (forensics-supp.md:144-147), A2b :229-232 |
| Displacement search | ±20 px step-2 never beats the join ae on any red subject (ae20 = ae6 = join on 5/7 A2b cells); per-cell best shifts vary (1,0)/(3,-4)/(-2,-2)/(1,-1) — no global phase; aligned residual stays 60–150k | A1 :70-73, :210-212; A2b :216-220 |
| Edge localization | diff-band ≤3 px holds **81–100 %** of diff px (webbing/vine/rope: 100 %, 0 px ≥4 px); diagonal-edge enrichment **1.48–2.44×** (rope 2.44×), cage straight-edge **0.53–0.74×** with per-bar **±1–3 px width deltas in BOTH directions** and 1-px sky slits | A1 :64-73, :101-121; A2b :125-131, :147-158, :196-208 |
| Straight-edge phase | 1-px constant row-left offsets (cage c10r3: all 47 rows at −1) = one constant phase per straight edge | A1 :117-120 |
| e278 snap too small | the landed per-cell phase constants (−0.22/−0.32 px) are **10× smaller** than the measured 1–4 px per-cell offsets | A1 :70-73 |
| AO / tint | joint-pixel deltas are flat +5–11 luma offsets, no corner structure; RGB ratio spreads ≤0.06 — both refuted | A1 :202-218; A2b :221-228 |
| BE-content carve-out | sophisticatedbackpacks battery=true (3 reds, joint-heavy 48–65 %) and create fluid-tank window (15 reds, G=1.18 hue delta, mid-band +61 luma) are a **different lever** — tile-content, not edge phase | A1 §6; A2b §5 |

**Census (one grep-count, this session):** `x-report-*/join-*.json` rows with verdict RED in the
11 edge-placement family states = **353 red rows** — barnacles 51 (supp 18 + bop 33),
timber_brace 49, cage 47, palm_door 38, fd rope 35, webbing 25, willow_vine 24, willow_door 24,
mahogany_door 21, timber_cross_brace 21, rope_knot 18. The two forensics attribute the
pure-geometry lever-1 share to **~270 cells** (A1 lever 1 = 135; A2b lever 1 = 99 outright + the
geometry share of barnacles/doors); the remaining ~80 include the barnacles/door interior and
BE-content shares that an edge fix will not clear.

## 2. Code-path map (the tile pipeline, file/method/line)

1. **Tile sizing** — `ObjMesh.java:711` `final double ppu = 60.5 / 16;` (= 3.78125 px/model-unit,
   the e273 nominal output density) and `ObjMesh.java:719-720`
   `pw = Math.max(2, Math.min(184, (int) Math.round((a1 - a0) * ppu)));` (same for `ph`).
2. **Raster grid** — `ObjMesh.java:746-749` (`render()`): the tile pixel (i,j) samples the polygon
   plane at `a = a0 + (i + 0.5) / pw * (a1 - a0)`, `b = b1 - (j + 0.5) / ph * (b1 - b0)` — pixel
   centre → fan barycentric → interpolated UV → NEAREST texel × vertex colour
   (`:755-821`, the native per-pixel math; the selftest pins it,
   `scripts/f2v/selftest_poly.py:14`).
3. **Element emission** — `ObjMesh.java:829-839` (`replace()`): the element rect spans exactly the
   polygon bbox `[a0,a1]×[b0,b1]` model units with the sheet UV `[x, x+pw]×[y, y+ph]` — the tile
   is mapped 1:1 onto the bbox, **no projection anywhere**.
4. **Limit law** — `ObjMesh.java:179-198` (`polyInLimits`): a poly's bounding sphere stays inside
   [-16,32] → span < 48 units; the e273 cap comment (`:712-718`): 48·ppu = 181.5 → cap 184.
   **The cap never clips post-e273** (48·3.78125 = 181.5 < 184) — the "0.47× when cap clips" law
   is dead in the current path (it was the old 120 cap / `fitScale` k>1 era,
   `F2vPack.java:4129-4133`).
5. **Entity** — `CimPlugin.java:1990` `GALLERY_SCALE = 1.0f` (no scale compensation;
   :1983-1989 receipt: identity display lands exactly on the truth cell), `:2006`
   `GALLERY_DZ = -1.04f` (lands the content plane at z = 9 − EPS), `:2050` `EPS = 0.007`,
   `:2055-2057` `GALLERY_F_PX = 514.1333`, `WUPPX`.
6. **Per-cell phase snap (e278)** — `CimPlugin.java:2712-2713` (page children) and `:3197-3199`
   (per-cell hybrid transformation): origin-only, closed-form linear law; the e278 receipt
   (worklog:9665) measured the **in-cell residual ≤ 0.047 px** after the origin null.
7. **Tile sharing** — `GalleryPack.java:1660` `materializeCutouts` runs inside the **per-STATE**
   `synthesizeComposite` — one tile set is shared by every cell carrying the state; the per-cell
   phase rides only the page child translation (`CimPlugin.java:2712-2727`,
   `insertTransformationInto` :2388-2433).
8. **Cutout law** — `GalleryPack.java:1790,1794-1795` `render_type: minecraft:cutout` —
   alpha < 0.1 discarded, **ramp px render opaque**.
9. **Door exact tiles** — `GalleryPack.java:2781-2784` `DOOR_SPRITE` covers only
   create/ae2 doors; BOP hinged doors ride the generic rect/poly paths (the e276 receipt,
   forensics-bop:115-117).

## 3. Diagnosis: texel == pixel is NOT exactly true — the proof line

The e273 design claim ("at output density the client resample is 1:1 — texel centres hit texel
centres", `ObjMesh.java:706-709`) fails at **two** places, and one of them is the live lever.

**(3a) The aggregate density is fine (refuted lever).** The ours client renders the content
plane z = 9 − 0.007 = 8.993 (`CimPlugin.java:2021,2048-2050`) at distance 8.493 →

- ours px/unit **G_ours = 514.1333 / 8.493 / 16 = 3.78351** (60.536 px/cell),
- truth px/unit G_truth = 514.1333 / 8.5 / 16 = 3.78039 (60.486 px/cell),
- the e273 tile assumed ppu = 3.78125 — within 0.06 % of both.

GALLERY_SCALE = 1.0 adds nothing (`CimPlugin.java:1990`). The residual truth-vs-ours scale
(0.076 %) is ≤ 0.05 px over a 16-unit cell — an order below the 1-px flip quantum. **A density
constant is not the defect; re-tuning `ppu` (or pre-shifting the tile, candidate b) cannot
fix it** — the same argument that killed e278 as a complete fix (A1 :70-73).

**(3b) THE LIVE LEVER: per-polygon integer rounding makes the tile grid non-commensurate with
the screen grid.** Proof line `ObjMesh.java:719`:
`pw = Math.max(2, Math.min(184, (int) Math.round((a1 - a0) * ppu)))`.
The client renders the element's span at `span · G_ours` screen px; the tile has `pw` texels for
that span, so the **tile grid step on screen = span · G_ours / pw px per tile px**. `pw` is
`round(span · 3.78125)` — a non-integer product rounded once per polygon — so the step is **not
1 px** except for lucky spans:

| polygon span (model units) | pw | tile grid step on screen |
|---|---|---|
| 1.3 (rope strand) | 5 | **0.9837 px** |
| 2.5 (cage bar) | 9 | **1.0510 px** |
| 4.0 (vine leaf) | 15 | **1.0089 px** |
| 13.0 (brace beam) | 49 | 1.0038 px |
| 16.0 (full face) | 61 | 0.9924 px |
| 48.0 (cap-bound diagonal) | 182 | 0.9978 px |

Consequences, exactly the measured signature:

- The polygon's silhouette is encoded in the tile's **alpha mask at the tile grid** (pixel-centre
  rule, `ObjMesh.java:750-751` bary test). The truth's silhouette is the native GPU's pixel-centre
  rule **at the screen grid**. Two ~1 px quantizers at grids whose step is 0.98–1.05 px and whose
  relative phase is arbitrary per polygon → every staircase corner lands 0–1 px off the native
  corner, **in both directions** (round-up spans fatten, round-down spans thin) — the cage
  ±1–3 px bar deltas + 1-px sky slits (A1 :101-121) and the rope −0.9 px/row thinning
  (A2b :200-204).
- A straight edge crosses the tile at ONE fractional position → ONE constant phase for the whole
  edge (A1 :117-120: all 47 rows at −1). A diagonal edge quantizes every staircase corner
  independently → the diagonal enrichment 1.48–2.44× (A2b :125-158).
- Sprite-texel boundaries inside the tile re-quantize at the same off-grid
  (`render()` NEAREST at `:785-796`) — the interior texel steps wander with the polygon's own
  rounding, on top of the silhouette error.
- Where a thin feature's `round()` flips a whole tile px (step 1.05 px), the rendered feature is
  a full pixel wider/thinner than the native one; aggregated over a 60 px crop the run traces read
  2–4 px displaced edges (timber c0r2 beam left edge, A1 :62-64; willow_door +3.5 px/row ≈ one
  texel, A2b :104-109).

The e273 rework fixed the **aggregate** density (the 16-unit-face case) and the cap; it left the
**per-polygon** rounding in place, so the shape did not move (e275 receipt, worklog:9660: "the
output-density tile did NOT change the error shape"). e278 then snapped the **per-cell origin**
(in-cell residual ≤ 0.047 px) — but the in-cell, per-polygon grid phase is exactly what e278
cannot touch.

## 4. Chosen design — candidate (a): guarantee texel == pixel end-to-end, by snapping the tile
grid AND the element rect to the projected screen grid

Of the four candidates: (b) pre-shift by a measured phase is refuted — the phase is per-EDGE, not
per-tile (A1 :70-73; a per-tile constant cannot re-phase a staircase); (c) 2× + exact 0.5×
downsample halves the error but keeps a real client-side resample (mip/linear minification + the
cutout alpha ramp, `GalleryPack.java:1794-1795`) and doubles the sheet budget; (d) per-edge
snapping alone is impossible client-independently — but becomes exact when the raster knows the
projection. **(a) with (d)'s snap inside it is the minimal complete fix**, and it needs no entity
scale change (GALLERY_SCALE stays 1.0, `CimPlugin.java:1990`).

**The law.** Let the per-cell model→world map and the rig constants be pinned once from the
e278/e91j receipts (see §6 calibration). Define, per polygon and per screen axis s ∈ {x, y}:

- `G = GALLERY_F_PX / (GALLERY_DIST − GALLERY_PHASE_EPS) / 16` = 514.1333 / 8.493 / 16 =
  **3.78351 px per model unit** (ours' own optics — the tile must match the client that samples
  it, not the truth's 3.78039);
- `S(p)` = the closed-form projected screen coordinate (px, fractional) of plane point p, =
  `S₀ ∓ G·p` per axis (sign/permutation from the polygon basis `ex/ey` vs the screen axes; the
  wall-facing families have `|n_z| ≈ 1`, so the map is a signed permutation);
- `X0 = floor(S(a0))`, `pw = ceil(S(a1)) − X0` (the **screen pixels the element actually
  spans**, ≤ ceil(48·G) + 1 = **183 ≤ 184 cap** — no clip, `ObjMesh.java:712-718` bound holds);
- the tile pixel i samples the plane at the **screen pixel centre** mapped back:
  `a_i = a0 + (X0 + i + 0.5 − S(a0)) / (dS/da)`, and likewise `b_j`;
- the element rect (`replace()`) is **snapped to the same grid**:
  `a0' = a0 + (X0 − S(a0))/(dS/da)`, `a1' = a0 + (X0 + pw − S(a0))/(dS/da)` — the projected
  element edges land at integer px X0 / X0+pw by construction; the rect grows ≤ 1 px per side in
  **transparent** margin px, so the silhouette is untouched.

Then: element span on screen = `pw` px exactly, origin at integer X0, UV rect = the tile rect →
tile px i ↔ screen px X0+i **1:1**; the raster mask = the pixel-centre rule **at the screen
grid** = the native GPU's own rule; the sprite sampling = the same NEAREST-per-screen-pixel the
native math runs. The tile becomes a byte-level replica of what the truth renders for that
polygon, at the shared fractional grid the e278 snap already aligns per cell (residual
≤ 0.047 px, worklog:9665). First function to change: **`ObjMesh.materialize`** (plumb the per-cell
projected phase in), then the `Cell` constructor (pw/ph + snapped bounds), `Cell.render`
(sample positions), `Cell.replace` (snapped from/to).

**Why the per-STATE tile survives per-cell phases** (`GalleryPack.java:1660`): the snap is
*relative to the cell origin* — every cell of the state rides integer block child offsets
(`CimPlugin.java:2712` `dx = c.x() − ax`) plus the e278 per-cell phase correction
(`galleryPhaseDx/Dy`, :2062-2072), which lands each cell's origin on the truth's fractional grid
to ≤ 0.047 px. Integer offsets shift whole px; the snapped in-cell grid is translation-invariant.
No per-cell tile duplication is needed.

## 5. Gates and fallbacks (non-compliant cells fall back byte-identical)

| gate | condition | fallback |
|---|---|---|
| G1 channel | the wall gallery display channel only (rig constants defined); item/fluid/unlit lanes | current path, byte-identical |
| G2 shrink | wrapper normalize factor k == 1 (`GalleryPack.java:1810-1853`; k>1 changes the model→world map and re-opens the 0.47× density hole, `F2vPack.java:4129-4133`) | current path |
| G3 plane | polygon basis screen-axis-aligned (`|n·ẑ| > 0.999` — the wall-facing quads own all 11 red families' silhouettes); arbitrary 3D rotations (shafts, gear axes) keep the current path until their dS/da map is measured | current path |
| G4 cap | `pw, ph ≤ 184` after the +1 px snap growth (worst case 183, `ObjMesh.java:712-718`) | clamp → current sizing |
| G5 phase availability | the caller can supply the cell's projected phase (page lane and per-cell hybrid both know the cell block, `CimPlugin.java:2712,3190-3199`); missing → null phase | current path |

The `DOOR_SPRITE` exact-tile lane (`GalleryPack.java:2797-2874`) and the animation frame-0 rule
(`ObjMesh.java:795`) are untouched.

## 6. Calibration and selftest spec

**Calibration (one-time, measured):** pin (i) the model→world affine for the per-cell wrapper
(model 0..16 ↔ the cell block, PI-yaw cancelling — `GalleryPack.java:2593-2596`), (ii) the
projection `sx = W/2 − F·(x − CAM_X)/8.493` (`CimPlugin.java:2028-2033` sign law), (iii) the e278
constants (EPS 0.007, D0 −0.22/−0.32 px). Verification cell: one measured r632 subject (e.g.
timber_brace p227 c8r4, ae 122,993) — the snapped tile's mask must reproduce the truth crop's
run boundaries within ±0.5 px before flight.

**Selftest extension** (`scripts/f2v/selftest_poly.py` fixture style — PIL-only, verbatim port of
the Java loop + an independent reference, strict per-pixel asserts):

1. **Phase fixture**: the existing 45° diamond + straight control, rendered at a nonzero
   projected origin phase (e.g. frac = 0.37 / 0.61) — the state every real polygon is in.
2. **Contract assert (new law)**: snapped-NEW tile == an independent **screen-grid** reference
   (edge-function barycentric sampled at the snapped centres) — texel pattern EXACT, colour
   |Δ| ≤ 1, same tolerance style as `:234-243`.
3. **Evidence assert**: the CURRENT (unsnapped, `round`) tile vs the screen-grid reference shows
   the boundary-diff pattern the run measures (diagonal ≫ straight, both directions) — ported
   `diff_count` at `:216-222` must stay > 0 on the diamond at frac = 0.37, proving the harness
   can see the disease the fix removes.
4. **Grid assert**: for spans 1.3/2.5/4/13/16/48 the snapped `pw = ceil(S(a1)) − floor(S(a0))` and
   `S(a0') ≡ integer`; the cap table (≤ 184) printed.
5. Keep the existing NEW==REF contract (the loop is unchanged except the sample positions) and
   the old-vs-new boundary-diff printout (`:256-271`).

## 7. Honest expected impact

- The join census for the 11 edge-placement families is **353 RED rows** (§1). The fix targets
  the poly/tile-path silhouette + interior-texel phase, i.e. the forensics' lever-1 share:
  A1 **135 cells** (timber_brace 49 + cross_brace 21 + cage 47 + rope_knot 18) + A2b **99 cells**
  outright (willow_door 24 + webbing 25 + willow_vine 24 + fd rope 26) + the geometry share of
  barnacles (33 + 18) and the doors' placement subtype — **~250–300 cells have a path to green**;
  expect the straight-edge classes (cage constant −1 rows, rope tail overhang) to move first and
  the diagonal classes to collapse with them (the mask becomes the native rule, so diagonals are
  exact by construction, not approximately).
- **Will NOT move** (honest carve-outs): sophisticatedbackpacks battery=true (3, BE-content),
  the tank window content share (15, BE-content, joint-heavy 48.7 % — A2b :170-189), the
  barnacles interior residual (corr 0.605–0.675, resid 18–29.7 luma — the second lever both
  forensics name), and any pure capture-side holes (e.g. the door upper/lower pose lane, e47,
  parked).

## 8. Risks

1. **The projection model must equal the client's optics.** G and S are closed-form from the rig
   constants; if the client's actual item-display pipeline deviates (the e91j DZ fit residual,
   float32 pose-stack precision ~1e-7 blocks = 1e-6 px), the grid misses by that residual —
   measured, sub-0.05 px (worklog:9665). Mitigation: the calibration cell gates the flight.
2. **Per-STATE tiles + per-cell phases**: the design's correctness leans on the e278 per-cell
   snap being origin-exact (≤ 0.047 px). If a future lane drops the snap, the tile stays
   consistent but the per-cell phase returns — the failure mode is the CURRENT one, not worse.
3. **+1 px tile growth** (transparent margins): sheet shelf budget +1 px/tile (2 px gutter regime
   unchanged, `ObjMesh.java:592-607`); tile hash dedupe keys include w/h (`:583-584`) — no
   collision risk beyond the existing counter.
4. **The 1:1 sampling assumption at exactly 1.0 ratio**: at ratio 1.0 the GL filter kernel sits
   on one texel (magnification NEAREST and LINEAR agree at the centre; mip level 0) — the same
   assumption e273 made, now actually true instead of approximately.
5. **Gated-out polygons stay red**: shafts/gears (G3) and k>1 meshes (G2) keep today's behaviour
   — counted in the risk register, not silently claimed.
6. **The selftest's snap math must be the Java math**: sample positions computed in double from
   the same constants; the 1e-9 snap helper (`selftest_poly.py:74-81`) covers the measure-zero
   boundary cases as today.
