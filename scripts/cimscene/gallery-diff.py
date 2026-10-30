#!/usr/bin/env python3
"""Per-cell pixel diff for the cimscene GALLERY (roadmap M5.5 main gate).

Inputs:
  --truth <png>   the ground-truth leg screenshot (real NeoForge client)
  --ours <png>    the c-imagine leg screenshot (vanilla 26.3 client)
  --manifests <dir> (or repeated --manifest) containing gallery-<ns>-p<p>.tsv
                  pages as emitted by cimscene (cell|x|y|z|state + skip rows)
  --out <report.json>   per-cell AE report + verdict
  --diff-dir <dir>      red-highlighted diff images per red cell
  --gate <n>            per-cell AE gate (default 2500, the calibrated
                        block-identity datum from camera-lock runs)

Projection (pinhole, measured against the vanilla renderer): the gallery wall
is 8.5 m in front of the camera (wall z=9, camera z=0.5), vertical FOV 70 deg
-> focal_px = (H/2)/tan(35). A cell at (x, y) projects to
  sx = W/2 + f * (x + 0.5 - cam_x) / dist
  sy = H/2 - f * (y + 0.5 - eye_y) / dist
with cam_x=0.5, eye_y = -60 + 1.62 = -58.38. The crop is CELL*0.92 wide
(CELL = f/dist px) so it never bleeds into the neighbour cell.

Report rows: page, cell (col,row), state, AE, verdict. A page/screenshot pair
is GREEN only when every cell row is GREEN; the exit code carries the verdict
(CI gate). Skipped states (fluid/invisible) are reported as skipped — they are
identical non-content on both legs, never counted red.
"""
import argparse
import json
import math
import pathlib
import sys

from PIL import Image, ImageChops

GALLERY_X0 = -5  # mirror of CimSceneMod gallery constants
GALLERY_Y0 = -60

W, H = 1280, 720
VF = 70.0
F = (H / 2.0) / math.tan(math.radians(VF / 2.0))  # 514.1 px at 720p
CAM_X = 0.5
EYE_Y = -58.38
DIST = 8.5
CELL_PX = F / DIST  # ~60.5 px on-screen cell width


def load(p):
    im = Image.open(p).convert("RGB")
    if im.size != (W, H):
        raise SystemExit(f"unexpected screenshot size {im.size} in {p} (want {W}x{H})")
    return im


def crop_cell(im, col, row):
    x0 = GALLERY_X0 + col
    y0 = GALLERY_Y0 + row
    sx = W / 2.0 + F * (x0 + 0.5 - CAM_X) / DIST
    sy = H / 2.0 - F * (y0 + 0.5 - EYE_Y) / DIST
    half = CELL_PX * 0.92 / 2.0
    box = (int(sx - half), int(sy - half), int(sx + half), int(sy + half))
    return im.crop(box), box


def ae(a, b):
    d = ImageChops.difference(a, b)
    h = d.histogram()
    total = 0
    for ch in range(3):
        base = ch * 256
        for v in range(256):
            total += v * h[base + v]
    return total


def highlight(truth, ours, box):
    d = ImageChops.difference(truth.crop(box), ours.crop(box))
    px = d.load()
    w, h = d.size
    out = Image.new("RGB", (w, h))
    po = out.load()
    pt = truth.crop(box).load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if r + g + b > 30:
                po[x, y] = (255, 0, 0)
            else:
                po[x, y] = pt[x, y]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--manifest", action="append", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--diff-dir")
    ap.add_argument("--gate", type=int, default=2500)
    args = ap.parse_args()

    truth = load(args.truth)
    ours = load(args.ours)
    rows = []
    red = 0
    skipped = 0
    cells = 0
    for mf in args.manifest:
        page = pathlib.Path(mf).stem  # gallery-<ns>-p<p>
        for line in pathlib.Path(mf).read_text().splitlines()[1:]:
            kind, x, y, z, state = line.split("\t")
            if kind == "skip":
                skipped += 1
                rows.append({"page": page, "state": state, "verdict": "skipped"})
                continue
            col = int(x) - GALLERY_X0
            row = int(y) - GALLERY_Y0
            box = None
            ct, box = crop_cell(truth, col, row)
            co, _ = crop_cell(ours, col, row)
            v = ae(ct, co)
            cells += 1
            verdict = "GREEN" if v <= args.gate else "RED"
            if verdict == "RED":
                red += 1
                if args.diff_dir:
                    dd = pathlib.Path(args.diff_dir)
                    dd.mkdir(parents=True, exist_ok=True)
                    name = f"{page}_c{col}_r{row}.png"
                    highlight(truth, ours, box).save(dd / name)
                    ct.save(dd / f"{page}_c{col}_r{row}_truth.png")
                    co.save(dd / f"{page}_c{col}_r{row}_ours.png")
            rows.append({"page": page, "col": col, "row": row, "state": state,
                         "ae": v, "verdict": verdict})

    report = {
        "cells": cells, "skipped": skipped, "red": red,
        "gate": args.gate, "verdict": "GREEN" if red == 0 else "RED",
        "rows": rows,
    }
    pathlib.Path(args.out).write_text(json.dumps(report, indent=1))
    print(f"gallery-diff: {report['verdict']} cells={cells} red={red} "
          f"skipped={skipped} gate={args.gate} -> {args.out}")
    return 0 if red == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
