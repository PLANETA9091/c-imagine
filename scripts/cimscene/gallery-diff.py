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

Per-cell metric (v2, calibrated on the run-9 identity sample):
  1. ALIGN: best integer shift within +-10 px (the display-channel entity
     placement carries ~1 px float rounding; the wall measurement showed
     31/39 cells at (0,-1) after calibration).
  2. LUMA: ours' brightness rescaled to truth's mean (the display channel
     samples entity light; the truth side renders real blocks whose
     light-emitting states brighten their own cell).
  3. AE over the aligned, normalised crops.
The three steps strip the channel's rendering-physics deltas (entity light
vs block light+AO, float placement) and keep the STRUCTURAL deltas (missing
textures, wrong models, wrong rotations) — the gate then separates them.

Report rows: page, cell (col,row), state, ae, shift, verdict. A page is
GREEN only when every cell row is GREEN; the exit code carries the verdict
(CI gate). Skipped states (fluid/invisible) are reported as skipped — they
are identical non-content on both legs, never counted red.
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
CELL_PX = F / DIST  # ~60.5 px on-screen cell width
ALIGN = 3           # per-cell alignment search radius (px; the calibrated channel shift is (0,-1), +-3 covers float rounding without letting periodic textures slide)


def load(p):
    im = Image.open(p).convert("RGB")
    if im.size != (W, H):
        raise SystemExit(f"unexpected screenshot size {im.size} in {p} (want {W}x{H})")
    return np.asarray(im).astype(np.int64)


def crop_cell(im, col, row):
    x0 = GALLERY_X0 + col
    y0 = GALLERY_Y0 + row
    sx = W / 2.0 + F * (x0 + 0.5 - CAM_X) / DIST
    sy = H / 2.0 - F * (y0 + 0.5 - EYE_Y) / DIST
    half = CELL_PX * 0.92 / 2.0
    box = (int(sx - half), int(sy - half), int(sx + half), int(sy + half))
    return im[box[1]:box[3], box[0]:box[2]], box


def normalize_luma(a, b):
    """Scale b's brightness so its mean matches a's (per-cell luminance
    normalisation — the display channel samples entity light while the truth
    side renders real blocks whose light-emitting states (Create powered
    gearshifts...) brighten their own cell; the camera-lock identity metric
    normalises this version-level/luminaire factor instead of chasing it)."""
    am = a.mean()
    bm = b.mean()
    if bm < 1.0:
        return b
    out = b * (am / bm)
    return np.clip(out, 0, 255)


def aligned_ae(a, b, margin=4):
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--manifest", action="append", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--diff-dir")
    ap.add_argument("--gate", type=int, default=60000,
                    help="per-cell AE datum after alignment + luminance "
                         "normalisation (calibrated on the run-9 identity "
                         "sample: p0 median 60.8k, p90 210k — see worklog)")
    args = ap.parse_args()

    truth = load(args.truth)
    ours = load(args.ours)
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
    cells = 0
    for mf in args.manifest:
        page = pathlib.Path(mf).stem  # gallery-<ns>-p<p>
        for line in pathlib.Path(mf).read_text().splitlines()[1:]:
            parts = line.split("\t")
            kind, x, y, z, state = parts[:5]
            # dyn=1 (6th column, scene-mod >= 2026-10-30): the block carries a
            # BlockEntity — part of its picture is drawn by the mod's
            # BlockEntityRenderer, which the vanilla-client pack channel
            # structurally cannot reproduce. The cell is still diffed (the
            # static model surface is the signal) but carries no red verdict:
            # its gate is the real-client leg (roadmap M5.6).
            dyn = len(parts) > 5 and parts[5].strip() == "1"
            if kind == "skip":
                skipped += 1
                rows.append({"page": page, "state": state, "verdict": "skipped"})
                continue
            col = int(x) - GALLERY_X0
            row = int(y) - GALLERY_Y0
            box = None
            ct, box = crop_cell(truth, col, row)
            co, _ = crop_cell(ours, col, row)
            co = normalize_luma(ct, co)
            v, dx, dy = aligned_ae(ct, co)
            cells += 1
            verdict = "GREEN" if v <= args.gate else "RED"
            if dyn:
                dynamic += 1
                if verdict == "RED":
                    verdict = "DYNAMIC"
            elif verdict == "RED":
                red += 1
            if verdict in ("RED", "DYNAMIC") and args.diff_dir:
                dd = pathlib.Path(args.diff_dir)
                dd.mkdir(parents=True, exist_ok=True)
                name = f"{page}_c{col}_r{row}.png"
                highlight(truth, ours, box).save(dd / name)
                Image.fromarray(ct.astype(np.uint8)).save(
                    dd / f"{page}_c{col}_r{row}_truth.png")
                Image.fromarray(co.astype(np.uint8)).save(
                    dd / f"{page}_c{col}_r{row}_ours.png")
            rows.append({"page": page, "col": col, "row": row, "state": state,
                         "ae": v, "shift": [dx, dy], "verdict": verdict})

    report = {
        "cells": cells, "skipped": skipped, "red": red, "dynamic": dynamic,
        "gate": args.gate, "verdict": "GREEN" if red == 0 else "RED",
        "rows": rows,
    }
    pathlib.Path(args.out).write_text(json.dumps(report, indent=1))
    print(f"gallery-diff: {report['verdict']} cells={cells} red={red} "
          f"dynamic={dynamic} skipped={skipped} gate={args.gate} -> {args.out}")
    return 0 if red == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
