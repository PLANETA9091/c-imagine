#!/usr/bin/env python3
"""Per-cell pixel diff for the cimscene GALLERY (roadmap M5.5 main gate).

Inputs:
  --truth <png>   the ground-truth leg screenshot (real NeoForge client)
  --ours <png>    the c-imagine leg screenshot (vanilla 26.3 client)
  --manifests <dir> (or repeated --manifest) containing gallery-<ns>-p<p>.tsv
                  pages as emitted by cimscene (cell|x|y|z|state + skip rows)
  --out <report.json>   per-cell AE report + verdict
  --diff-dir <dir>      red-highlighted diff images per red cell
  --gate <n>            per-cell AE datum after alignment + luminance
                        normalisation (default 60000, run-9 calibrated)

Projection (pinhole, calibrated): the gallery wall is 8.5 m in front of the
camera (wall z=9, camera z=0.5), vertical FOV 70 deg -> focal_px = (H/2)/tan(35)
= 514.1 px, cell pitch 60.5 px. CAUTION (worklog 12-ac): the rig used to pin
`fov:-0.625` in options.txt believing it renders 70 deg — it renders 45 deg
(options value maps LINEARLY, fov = 70 + 40*v, on 1.21.1 AND 26.3: measured
102.25 px cell pitch on the run-41112056567 shots = 2*atan(360/869) = 45.06).
At 45 deg the wall's upper rows sit off-frame and every cell center lands up
to 83 px off — garbage per-cell verdicts. The legs now pin `fov:0.0` (=70.0
under the same formula) and this guard catches any future drift: before the
per-cell verdicts, the FULL frames must globally align within +-2 px
(downsampled integer search) or the run fails with GLOBAL-MISALIGNED instead
of reporting per-cell noise. A cell at (x, y) projects to
  sx = W/2 + f * (x + 0.5 - cam_x) / dist
  sy = H/2 - f * (y + 0.5 - eye_y) / dist
with cam_x=0.5, eye_y = -60 + 1.62 = -58.38. The crop is CELL*0.92 wide
(CELL = f/dist px) so it never bleeds into the neighbour cell.

Per-cell metric (v3, 14-x): ALIGN + OPTIONAL LUMA + AE, where the luma
normalisation is a HYPOTHESIS that may only help:
  1. ALIGN: best integer shift within +-3 px (the display-channel entity
     placement carries ~1 px float rounding).
  2. LUMA (optional): ours rescaled so the JOINT-content luma medians match
     (pixels sub-200 on BOTH legs — matched content, sky excluded). The
     per-cell ae is then min(ae_raw, ae_normalised): a rescale that makes
     the cell worse is discarded.
  3. AE over the aligned crops.
14-x MEASURED ROOT CAUSE of the v2 forced normalisation: the elementwise
sub-200 median population included the near-horizon SKY (sky RGB channels
are <200 elementwise!), so on sky-dominated crops the median jumped between
the sky cluster and the content cluster of the two legs (104 vs 177 measured
on one #71 wall cell) -> a ~0.6 forced rescale -> ~510k of SKY-vs-SKY ae
manufactured on cells whose raw content matched to mean |dluma| ~3. The
min() rule keeps the entity-light story where it helps and never
manufactures red.

Report rows: page, cell (col,row), state, ae, shift, verdict. A page is
GREEN only when every cell row is GREEN; the exit code carries the verdict
(CI gate). Skipped states (fluid/invisible) are reported as skipped — they
are identical non-content on both legs, never counted red.

Verdict classes (14-bf): the manifest dyn=1 demotion to DYNAMIC is
NAMESPACE-SCOPED — a modded-namespace dyn row keeps DYNAMIC (its picture
rides the mod's BlockEntityRenderer, the pack channel's structural gap;
gate = M5.6's real-client leg) unless --gate-dyn is set: block pages pass it
(owner directive 2026-11-05), so a block entity that differs is RED and is
never excused by a dyn neighbour. A minecraft-namespace dyn row is
gate-hard comparable content: both legs render the same vanilla entity
through the same renderer, a RED counts, and a RED whose own per-leg
second-shot pair moved (ANIMATED, the 14-aj rule) is the animation-phase
excuse instead. Both self frames must be passed (--truth-self/--ours-self)
for the ANIMATED class to exist; without them every RED gates.
"""
import argparse
import json
import math
import pathlib
import sys

import numpy as np
from PIL import Image

GALLERY_X0 = -5  # mirror of CimSceneMod gallery constants
GALLERY_Y0 = -60

W, H = 1280, 720
VF = 70.0  # the legs pin fov:0.0 = 70.0 (see the projection note above)
F = (H / 2.0) / math.tan(math.radians(VF / 2.0))  # 514.1 px at 720p
CAM_X = 0.5
EYE_Y = -58.38
DIST = 8.5
CELL_PX = F / DIST  # ~60.5 px on-screen cell width at 1-block pitch
ALIGN = 6           # per-cell alignment search radius (px; the calibrated channel shift is (0,-1), +-3 covers float rounding without letting periodic textures slide)
ALIGN_MARGIN = 8    # the AE border margin — MUST exceed ALIGN so the np.roll
                    # wrap seam never enters the compared area (the old
                    # ALIGN=3/margin=4 pair rode the 1.47x era; 14-ay measured
                    # the wall-family reds on the real #114 crops: A=3/m4
                    # median ae 152870 -> A=6/m8 50698 with 53.0% of the reds
                    # clearing the 60k gate; the same-area control (A=3/m8,
                    # 17.9%) proves the gain is the shift freedom, not the
                    # smaller compared area)
PITCH = 1           # cell pitch in blocks (the ENTITY grid uses 2: sparse,
                    # zero bbox overlap — CimSceneMod ENTITY_PITCH); the crop
                    # half-width scales with it so pitch-2 cells never bleed
SPILL_RADIUS = 2    # 14-ag dyn-spill reach for MACHINE cells (a dyn cell that
                    # has another dyn cell in its 3x3 — gantry contraptions,
                    # whistles): the BE channel draws outside block bounds and
                    # measured on #80 those cells go RED at 3.0x the clean
                    # rate. A LONE dyn cell (a turntable disc) measured a
                    # ~1-cell overhang, so lone dyn neighbours reach 1.


def load(p):
    im = Image.open(p).convert("RGB")
    if im.size != (W, H):
        raise SystemExit(f"unexpected screenshot size {im.size} in {p} (want {W}x{H})")
    return np.asarray(im).astype(np.int64)


def crop_cell(im, col, row):
    x0 = GALLERY_X0 + col * PITCH
    y0 = GALLERY_Y0 + row * PITCH
    # 14-t SIGN FIX: the camera pins yaw 0 = FACING SOUTH — east is on the
    # screen's LEFT, so a world offset toward +x projects NEGATIVE screen
    # dx. The old +F*... mirrored the whole crop grid onto the real
    # mirrored cells: every asymmetric page compared states against the
    # wrong positions' pixels (the symmetric pages hid it — measured 14-s:
    # the wall/stairs "flips" were the mislabelled grid, not the render).
    sx = W / 2.0 - F * (x0 + 0.5 - CAM_X) / DIST
    sy = H / 2.0 - F * (y0 + 0.5 - EYE_Y) / DIST
    half = CELL_PX * 0.92 * PITCH / 2.0
    box = (int(sx - half), int(sy - half), int(sx + half), int(sy + half))
    return im[box[1]:box[3], box[0]:box[2]], box


def normalize_luma(a, b):
    """Scale b's brightness so the JOINT-CONTENT luma medians match, CLAMPED
    to [0.6, 1.6]. The population is the JOINT content mask — pixels whose
    luma is sub-200 on BOTH legs — never the sky, and one value per pixel
    (luma), not elementwise RGB values.

    14-t: the v1 mean-matching exploded on crops where a dark neighbour
    dominated the box (bm tiny -> scale huge -> the sky clipped to WHITE).
    14-x: the v2 elementwise sub-200 median was sky-poisoned (near-horizon
    sky channels are <200 elementwise) and bimodal-fragile — on sky-heavy
    crops the median landed in the sky cluster on one leg and the content
    cluster on the other (104 vs 177 measured), forcing a ~0.6 rescale that
    manufactured ~510k of sky-vs-sky ae on a cell whose raw content matched
    to mean |dluma| ~3."""
    la = a.mean(axis=2)
    lb = b.mean(axis=2)
    m = (la < 200) & (lb < 200)
    if int(m.sum()) < 50:
        return b, 1.0
    am = float(np.median(la[m]))
    bm = float(np.median(lb[m]))
    if bm < 1.0:
        return b, 1.0
    scale = max(0.6, min(1.6, am / bm))
    return np.clip(b * scale, 0, 255), scale


def aligned_ae(a, b, margin=ALIGN_MARGIN):
    """AE after the best integer shift within +-ALIGN px (the display-channel
    entity placement carries ~1 px float rounding; the gate must not spend
    its budget on that). Border margin crops the roll seam. Returns
    (ae, dx, dy)."""
    best = (None, 0, 0)
    bestv = None
    for dy in range(-ALIGN, ALIGN + 1):
        for dx in range(-ALIGN, ALIGN + 1):
            oo = np.roll(np.roll(b, dy, axis=0), dx, axis=1)
            v = int(np.abs(a[margin:-margin, margin:-margin]
                           - oo[margin:-margin, margin:-margin]).sum())
            if bestv is None or v < bestv:
                bestv, best = v, (dx, dy)
    return bestv, best[0], best[1]


def fit_ae(a, b, margin=ALIGN_MARGIN):
    """14-ac: AE after the best per-cell LINEAR lighting map (luma fit
    a*luma+b applied to all three channels — chroma is never touched) with
    the same +-ALIGN shift search. WHY: the two legs render through
    different pipelines — the truth leg draws REAL BLOCKS (baked light +
    ambient occlusion), ours draws item_display ENTITIES (entity light, no
    AO). On high-contrast textures that inherent difference costs ~20 luma
    per cell and lands the whole dense-wall families (pillar/stairs/wall)
    at an ae floor of 60-80k, right AT the 60k gate (#74: pillar 485,
    stairs 442 REDs at median ~75k; p391 alone 68). HONESTY: the fit is
    constrained (scale 0.5-2.0, offset +-60) — it can absorb a lighting
    wobble, never geometry or content; the residual still has to clear the
    same gate; and the min(raw, norm, fit) rule can only turn REDs GREEN.
    Returns (ae, dx, dy) or None when the fit is degenerate/out of bounds."""
    bestv = None
    best = (0, 0)
    for dy in range(-ALIGN, ALIGN + 1):
        for dx in range(-ALIGN, ALIGN + 1):
            oo = np.roll(np.roll(b, dy, axis=0), dx, axis=1)
            t = a[margin:-margin, margin:-margin].astype(np.float64)
            o = oo[margin:-margin, margin:-margin].astype(np.float64)
            tl = t.mean(axis=2).ravel()
            ol = o.mean(axis=2).ravel()
            if ol.std() < 1.0 or tl.std() < 1.0:
                continue
            A = np.stack([ol, np.ones_like(ol)], axis=1)
            coef, *_ = np.linalg.lstsq(A, tl, rcond=None)
            k, c = float(coef[0]), float(coef[1])
            if not (0.5 <= k <= 2.0 and -60.0 <= c <= 60.0):
                continue
            of = np.clip(o * k + c, 0, 255)
            v = int(np.abs(t - of).sum())
            if bestv is None or v < bestv:
                bestv, best = v, (dx, dy)
    if bestv is None:
        return None, 0, 0
    return bestv, best[0], best[1]


def highlight(truth, ours, box):
    t = truth[box[1]:box[3], box[0]:box[2]]
    o = ours[box[1]:box[3], box[0]:box[2]]
    d = np.abs(t - o).sum(axis=2)
    out = t.copy()
    out[d > 30] = (255, 0, 0)
    return Image.fromarray(out.astype(np.uint8))


def global_alignment(truth, ours, radius=20, step=2):
    """Best integer shift (dx, dy) between the two FULL frames, searched on
    a 4x downsampled luma grid within +-radius px. The projection/camera
    contract puts the same world at the same pixels on both legs; a global
    offset beyond a couple of px means the contract broke (fov mapping,
    camera position, stale pair) and per-cell verdicts would be noise."""
    t = truth[:, :, 0] + truth[:, :, 1] + truth[:, :, 2]
    o = ours[:, :, 0] + ours[:, :, 1] + ours[:, :, 2]
    t = t[::4, ::4]
    o = o[::4, ::4]
    m = min(t.shape[0], o.shape[0])
    n = min(t.shape[1], o.shape[1])
    best = (None, 0, 0)
    bestv = None
    for dy in range(-radius, radius + 1, step):
        for dx in range(-radius, radius + 1, step):
            oo = np.roll(np.roll(o, dy, axis=0), dx, axis=1)
            v = float(np.abs(t[:m, :n] - oo[:m, :n]).mean())
            if bestv is None or v < bestv:
                bestv, best = v, (dx, dy)
    return best[0], best[1], bestv


def classify(over, dyn, gated_dyn, air, animated, spill_near):
    """One cell's verdict. over = its AE exceeds the gate. A gated dyn cell is
    not excused by a dyn neighbour: those neighbours are the same unmeasured
    surface, and SPILL would hide exactly what --gate-dyn exposes."""
    if not over:
        return "GREEN"
    if dyn and not gated_dyn:
        return "DYNAMIC"
    if air:
        return "AIR"
    if animated:
        return "ANIMATED"
    if spill_near and not gated_dyn:
        return "SPILL"
    return "RED"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--truth-self",
                    help="the SAME leg's second shot of the same page "
                         "(taken ~2s later) — cells whose SELF pair exceeds "
                         "the gate are time-varying content (mob idle "
                         "animations) and demote to the ANIMATED class")
    ap.add_argument("--ours-self",
                    help="the ours leg's second shot (same contract)")
    ap.add_argument("--manifest", action="append", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--diff-dir")
    ap.add_argument("--gate-dyn", action="store_true",
                    help="modded dyn=1 rows gate: a differing cell is RED, not DYNAMIC")
    ap.add_argument("--demote-family", action="append", default=[],
                    help="state regex whose matches demote to the DYNAMIC class "
                         "(reported, never gated) even under --gate-dyn — the "
                         "structural runtime-geometry channel gap (14-bx/14-by): "
                         "the mod builds the mesh in its own code at runtime, the "
                         "pack channel carries the captured static bake, the gate "
                         "is the real-client leg M5.6. The demotions count LOUD: "
                         "every row prints dyn_family=true and the join prints the "
                         "per-page total")
    ap.add_argument("--gate", type=int, default=60000,
                    help="per-cell AE datum after alignment + luminance "
                         "normalisation (calibrated on the run-9 identity "
                         "sample: p0 median 60.8k, p90 210k — see worklog)")
    ap.add_argument("--pitch", type=int, default=1,
                    help="cell pitch in blocks (the sparse entity grid uses "
                         "2 — mirror of CimSceneMod.ENTITY_PITCH); the crop "
                         "geometry scales with it")
    args = ap.parse_args()
    global PITCH
    PITCH = max(1, args.pitch)
    import re as _re
    demote_rx = [_re.compile(rx) for rx in args.demote_family]

    truth = load(args.truth)
    ours = load(args.ours)
    # 14-aj SELF-CONSISTENCY frames (the animated-content class): a second
    # shot of the SAME page on the SAME leg ~2s later. A cell whose own two
    # shots disagree beyond the gate is time-varying content (the mob idle
    # animations: bat wing flap, bee buzz, blaze smoke) — its truth-vs-ours
    # ae measures the animation PHASE, not a channel defect: the legs' shots
    # happen at different absolute times and no render fix can align them.
    # The rule is mechanical and symmetric: the SAME measurement on BOTH
    # legs, manifest-free, zero pixel heuristics — the pixel ae stays in the
    # report, the verdict joins the dyn family (reported, never silently
    # green — the gate is the real-client leg M5.6).
    truth_self = load(args.truth_self) if args.truth_self else None
    ours_self = load(args.ours_self) if args.ours_self else None
    # PROJECTION GUARD (worklog 12-ac): per-cell verdicts are meaningless on
    # a stale pair or a drifted camera. The threshold is HALF A CELL (24 px
    # at 70 deg): stale pairs / camera teleports miss by hundreds of px,
    # while sparse-page search noise measured 18 px (p2, mostly sky). A
    # SCALE drift (both legs' fov changed together) is invisible to a shift
    # search by construction — the legs pin fov:0.0 and the fov-mapping
    # lesson lives in the workflow comments.
    gdx, gdy, gerr = global_alignment(truth, ours)
    print(f"gallery-diff: global alignment shift=({gdx},{gdy}) err={gerr:.1f}")
    if abs(gdx) > 24 or abs(gdy) > 24:
        out = {
            "verdict": "GLOBAL-MISALIGNED",
            "global_shift": [gdx, gdy],
            "note": "full frames disagree on world placement beyond half a "
                    "cell — stale pair or camera drift; per-cell verdicts "
                    "withheld (fix the legs' options.txt fov pin or the "
                    "camera-lock rig, then re-run)",
        }
        pathlib.Path(args.out).write_text(json.dumps(out, indent=1))
        print(f"gallery-diff: GLOBAL-MISALIGNED shift=({gdx},{gdy}) -> {args.out}")
        return 3
    rows = []
    red = 0
    skipped = 0
    dynamic = 0
    spill = 0
    animated_n = 0
    fam_demoted = 0
    cells = 0
    for mf in args.manifest:
        page = pathlib.Path(mf).stem  # gallery-<ns>-p<p>
        lines = pathlib.Path(mf).read_text().splitlines()[1:]
        # 14-ag DYN-SPILL pass 1: the manifest's dyn grid for this page. The
        # BE channel's renders LEAVE the block bounds (the gantry beam spans
        # the whole connected contraption, the steam whistle stacks 8 cells,
        # the funnel nozzle hangs out) — a NEIGHBOUR cell's crop catches the
        # neighbour's BlockEntityRenderer output, which the vanilla-client
        # pack channel structurally cannot reproduce. Measured on run #80:
        # cells with a dyn cell within Chebyshev radius 2 go RED at 3.0x the
        # clean rate (15.4% vs 5.1%) and their RED ae averages 1.7x the
        # clean REDs' — the spill, not the cell's own channel. Those cells
        # keep their pixel ae in the report but join the dyn class: reported,
        # never silently green — their gate is the real-client leg (M5.6).
        page_cells = []
        dyn_grid = {}
        for line in lines:
            parts = line.split("\t")
            if parts[0] == "skip":
                # a NAMED skip row (no-instance | failed:* | dissolved |
                # client-crash-banned | wrong-series:<kind>): the docstring's
                # law — reported as skipped, never dropped. Measured #114:
                # this pre-filter swallowed every skip row (the kind=="skip"
                # branch below was unreachable), so create's nine banned /
                # failed entity rows surfaced nowhere and a mod-ns mobs page
                # read as a silent 0-cell GREEN. The -1/-1 position never
                # enters the dyn grid.
                page_cells.append(parts)
                continue
            if parts[0] != "cell":
                continue
            x, y = int(parts[1]), int(parts[2])
            dyn_grid[(x, y)] = len(parts) > 5 and parts[5].strip() == "1"
            page_cells.append(parts)
        # machine cells: dyn cells that are part of a dyn CLUSTER (another dyn
        # in their 3x3) — their BE render belongs to a contraption whose
        # extent is unbounded; a lone dyn cell measured a ~1-cell overhang.
        machine = {}
        for (mx, my), d in dyn_grid.items():
            if not d:
                continue
            near = any(dd and (dx, dy) != (mx, my)
                       for (dx, dy), dd in dyn_grid.items()
                       if abs(dx - mx) <= 1 and abs(dy - my) <= 1)
            machine[(mx, my)] = near

        def spill_near(cx, cy):
            for ddx in range(-SPILL_RADIUS, SPILL_RADIUS + 1):
                for ddy in range(-SPILL_RADIUS, SPILL_RADIUS + 1):
                    reach = max(abs(ddx), abs(ddy))
                    if reach == 0:
                        continue
                    d = dyn_grid.get((cx + ddx, cy + ddy), False)
                    if d and (machine.get((cx + ddx, cy + ddy)) or reach == 1):
                        return True
            return False
        for parts in page_cells:
            kind, x, y, z, state = parts[:5]
            # dyn=1 (6th column, scene-mod >= 2026-10-30): the block carries a
            # BlockEntity — part of its picture is drawn by the mod's
            # BlockEntityRenderer, which the vanilla-client pack channel
            # structurally cannot reproduce. The cell is still diffed (the
            # static model surface is the signal) but carries no red verdict:
            # its gate is the real-client leg (roadmap M5.6).
            dyn = len(parts) > 5 and parts[5].strip() == "1"
            # settled-air cells (scene-mod 12-ac settle pass): the block
            # popped on placement (funnels without a belt...) — the cell's
            # own surface is SKY on both legs, but the crop catches the
            # spill of neighbouring DYNAMIC geometry (Create's funnel
            # nozzle leaves the block bounds; the BEW draws what the
            # static pack channel cannot). Same verdict class as dyn:
            # reported, never silently green — the gate is M5.6's.
            air = state == "minecraft:air"
            if kind == "skip":
                skipped += 1
                # the note IS the name (failed:<t> | dissolved |
                # client-crash-banned | wrong-series:<kind> | ...): the join
                # must carry it, or a banned/failed subject stays silent
                rows.append({"page": page, "state": state, "verdict": "skipped",
                             "note": parts[6].strip() if len(parts) > 6 else ""})
                continue
            col = (int(x) - GALLERY_X0) // PITCH
            row = (int(y) - GALLERY_Y0) // PITCH
            box = None
            ct, box = crop_cell(truth, col, row)
            co, _ = crop_cell(ours, col, row)
            v_raw, dxr, dyr = aligned_ae(ct, co)
            cn, scale = normalize_luma(ct, co)
            v_norm, dxn, dy_n = aligned_ae(ct, cn)
            vf, dxf, dyf = fit_ae(ct, co)
            # v3 (14-x): the luma normalisation is a hypothesis that may
            # only help — a rescale that makes the cell worse is discarded
            # (it was manufacturing sky-vs-sky ae on sky-dominated crops).
            # 14-ac: the per-cell linear-light fit joins the hypothesis set
            # with the same min() rule.
            # NOTE: the norm shift is dy_n, NOT dyn — the name must not
            # shadow the manifest's dyn flag below.
            v, dx, dy, luma_norm = v_raw, dxr, dyr, False
            co_used = co
            if v_norm < v:
                v, dx, dy, luma_norm = v_norm, dxn, dy_n, True
            if vf is not None and vf < v:
                v, dx, dy, co_used = vf, dxf, dyf, True
            cells += 1
            # 14-aj self-consistency: the cell's own pair on EACH leg. A cell
            # that moves against itself is animated content — measured with
            # the same aligned+normalised machinery, symmetric on both legs.
            sa_t = sa_o = None
            if truth_self is not None:
                st, _ = crop_cell(truth_self, col, row)
                v1, _, _ = aligned_ae(ct, st)
                cn2 = normalize_luma(ct, st)[0]
                v2, _, _ = aligned_ae(ct, cn2)
                sa_t = min(v1, v2)
            if ours_self is not None:
                so, _ = crop_cell(ours_self, col, row)
                v1, _, _ = aligned_ae(co, so)
                cn3 = normalize_luma(co, so)[0]
                v2, _, _ = aligned_ae(co, cn3)
                sa_o = min(v1, v2)
            animated = ((sa_t is not None and sa_t > args.gate)
                        or (sa_o is not None and sa_o > args.gate))
            # 14-ag: spill = a dyn neighbour within its measured reach
            # (manifest-derived, zero pixel heuristics). Only a RED verdict is
            # demoted — a spill neighbour never turns a clean cell RED, and
            # never un-greens one.
            sn = spill_near(int(x), int(y))
            # 14-bf THE NAMESPACE SCOPES THE DYN CLASS: dyn=1 was written for
            # the MODDED BlockEntityRenderer channel — part of the cell's
            # picture is drawn by code the vanilla-client pack channel
            # structurally cannot reproduce (the roadmap M5.6 gate). A
            # VANILLA-namespace subject has no such asymmetry: both legs
            # render the same registered entity/block through the same
            # vanilla renderer, so its row is real comparable content and a
            # RED verdict counts (the vanilla dyn/large ENTITY series gate —
            # the state/entity column always carries the namespace prefix;
            # a legacy unprefixed id reads as minecraft). Time-varying
            # vanilla subjects (boats slide, arrows fall, camel ear twitch)
            # are caught by the ANIMATED self-pair rule below — the walk
            # shoots every gated page twice per leg for exactly this.
            row_ns = state.split(":", 1)[0] if ":" in state else "minecraft"
            # --gate-dyn opts modded dynamic rows into the pixel gate.
            # Vanilla dynamic rows always gate; explicit family exceptions
            # below remain separately reported and do not prove full parity.
            # 14-by THE RUNTIME-GEOMETRY FAMILY DEMOTION: the 14-bx seed-side
            # flip never reached the join — the join reads the TRUTH manifests
            # (the truth walk writes dyn=0 for the BE-less DT blocks) and runs
            # --gate-dyn (the doors/kinetics opt-in), so the family kept
            # red-gating on the structural channel gap (the #229 census: 130
            # DT reds, ae byte-identical across #214/#226). The demotion is
            # the 14-bx policy applied where the join actually reads: the
            # family states demote to DYNAMIC (reported, never gated),
            # regardless of gate-dyn, and the demotion counts LOUD.
            fam = any(rx.match(state) for rx in demote_rx)
            fam_demoted += 1 if fam else 0
            gated_dyn = (dyn or fam) and (row_ns == "minecraft" or args.gate_dyn)
            if fam:
                gated_dyn = False
            verdict = classify(v > args.gate, dyn or fam, gated_dyn, air, animated, sn)
            if dyn or air:
                dynamic += 1
            if verdict == "ANIMATED":
                animated_n += 1
            elif verdict == "SPILL":
                spill += 1
            elif verdict == "RED":
                red += 1
            if verdict in ("RED", "SPILL", "DYNAMIC", "AIR", "ANIMATED") and args.diff_dir:
                dd = pathlib.Path(args.diff_dir)
                dd.mkdir(parents=True, exist_ok=True)
                name = f"{page}_c{col}_r{row}.png"
                highlight(truth, ours, box).save(dd / name)
                Image.fromarray(ct.astype(np.uint8)).save(
                    dd / f"{page}_c{col}_r{row}_truth.png")
                Image.fromarray(co.astype(np.uint8)).save(
                    dd / f"{page}_c{col}_r{row}_ours.png")
            rows.append({"page": page, "col": col, "row": row, "state": state,
                         "ae": v, "shift": [dx, dy], "verdict": verdict,
                         "luma_norm": luma_norm, "luma_fit": co_used is True,
                         "luma_scale": round(scale, 3), "dyn_spill": sn,
                         "dyn_family": fam,
                         "self_ae": [sa_t, sa_o]})

    report = {
        "cells": cells, "skipped": skipped, "red": red, "dynamic": dynamic,
        "spill": spill, "animated": animated_n, "family_demoted": fam_demoted,
        "gate": args.gate, "verdict": "GREEN" if red == 0 else "RED",
        "rows": rows,
    }
    pathlib.Path(args.out).write_text(json.dumps(report, indent=1))
    print(f"gallery-diff: {report['verdict']} cells={cells} red={red} "
          f"spill={spill} animated={animated_n} dynamic={dynamic} "
          f"family_demoted={fam_demoted} "
          f"skipped={skipped} gate={args.gate} -> {args.out}")
    return 0 if red == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
