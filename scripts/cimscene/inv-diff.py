#!/usr/bin/env python3
"""INVENTORY-view join for the cimscene gallery (round 2, 2026-11-04).

Compares the OPEN-INVENTORY screenshots of the two legs:
  truth  gal-inv-<ns>{p}.png   the real modded client rendering the REAL
                               modded items in the 36 filled slots;
  ours   ours-gal-inv-<ns>{p}.png the vanilla 26.3 client rendering the
                               pack's paper+CMD carriers in the same slots
                               (the item channel's inventory view).

JOIN = full-frame delta + per-slot crops through the GUI geometry. THE
GEOMETRY IS FIRST-RUN-UNMEASURED: it is COMPUTED from the standard scale-3
GUI math (1280x720, guiScale auto -> 3: the 176x166 inventory panel
centered, the 9x4 slot grid at gui-x 8 + 18*col, hotbar row gui-y 142, main
rows gui-y 84/103/122, 16-unit slot sprites at 3x = 48 px pitch) and every
number is a parameter (--gui-scale, --slot-gate, --frame-gate) so the FIRST
RUN calibrates without code edits. Do not trust the defaults until a run
measured them — that is the same discipline as the gallery wall's
calibrated 60k datum (which also started as a run-calibrated number).

Verdict GREEN only when every slot crop AND the full frame are under their
gates. DIAGNOSTIC in CI (`|| true`): the class rides the flip law in
coverage-gate.py (DIAGNOSTIC_CLASSES) until measured stable.

Usage:
    inv-diff.py --truth T.png --ours O.png --ns N --page P --out report.json
        [--gui-scale 3] [--slot-gate 60000] [--frame-gate 3000]
"""
import argparse
import json
import pathlib
import sys

import numpy as np
from PIL import Image

W, H = 1280, 720
PANEL_W, PANEL_H = 176, 166          # the vanilla inventory panel, gui units
SLOT_PITCH = 18                      # gui units between slot origins
SLOT_SIZE = 16                       # gui units of the slot sprite
HOTBAR_Y = 142                       # gui-y of the hotbar row
MAIN_ROWS_Y = (84, 103, 122)         # gui-y of the 3 main rows, bottom-up


def slot_boxes(gui_scale):
    """Per-slot pixel crop boxes, slot order = the manifest order
    (hotbar.0..8, inventory.9..35). scale-3 math, unmeasured (see the
    module docstring)."""
    x0 = (W - PANEL_W * gui_scale) // 2
    y0 = (H - PANEL_H * gui_scale) // 2
    rows = list(MAIN_ROWS_Y) + [HOTBAR_Y]   # manifest order: inventory.9..35 then hotbar
    boxes = []
    for ry in rows:
        for c in range(9):
            cx = x0 + (8 + c * SLOT_PITCH + SLOT_SIZE // 2) * gui_scale
            cy = y0 + (ry + SLOT_SIZE // 2) * gui_scale
            half = SLOT_SIZE * gui_scale * 0.92 / 2
            boxes.append((int(cx - half), int(cy - half), int(cx + half), int(cy + half)))
    return boxes


def load(p):
    im = Image.open(p).convert("RGB")
    if im.size != (W, H):
        raise SystemExit(f"unexpected screenshot size {im.size} in {p} (want {W}x{H})")
    return np.asarray(im).astype(np.int64)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--ns", default="")
    ap.add_argument("--page", default="")
    ap.add_argument("--gui-scale", type=int, default=3,
                    help="FIRST-RUN-UNMEASURED: the 26.3 auto gui scale at "
                         "1280x720 computes 3; recalibrate if the first run "
                         "measures otherwise")
    ap.add_argument("--slot-gate", type=int, default=60000,
                    help="FIRST-RUN-UNMEASURED per-slot AE datum (the wall "
                         "gate's calibrated 60k as the starting hypothesis)")
    ap.add_argument("--frame-gate", type=float, default=3000.0,
                    help="FIRST-RUN-UNMEASURED full-frame mean|d| datum "
                         "(the GUI chrome should be identical; the items are "
                         "the content)")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    truth = load(args.truth)
    ours = load(args.ours)
    frame_delta = float(np.abs(truth - ours).mean())
    slots = []
    red_slots = 0
    for i, box in enumerate(slot_boxes(args.gui_scale)):
        t = truth[box[1]:box[3], box[0]:box[2]]
        o = ours[box[1]:box[3], box[0]:box[2]]
        ae = int(np.abs(t - o).sum())
        # manifest order: inventory.9..35 (rows 84/103/122) then hotbar.0..8
        idx = i
        if idx < 27:
            slot = "inventory.%d" % (idx + 9)
        else:
            slot = "hotbar.%d" % (idx - 27)
        verdict = "GREEN" if ae <= args.slot_gate else "RED"
        if verdict == "RED":
            red_slots += 1
        slots.append({"slot": slot, "ae": ae, "verdict": verdict})
    ok = frame_delta <= args.frame_gate and red_slots == 0
    verdict = "GREEN" if ok else "RED"
    report = {
        "ns": args.ns, "page": args.page, "verdict": verdict,
        "frame_delta": round(frame_delta, 3),
        "frame_gate": args.frame_gate,
        "gui_scale": args.gui_scale,
        "slot_gate": args.slot_gate,
        "red_slots": red_slots,
        "slots": slots,
        "note": "geometry + gates are FIRST-RUN-UNMEASURED (scale-3 GUI "
                "math); the first run calibrates them",
    }
    pathlib.Path(args.out).write_text(json.dumps(report, indent=1))
    print(f"inv-diff: ns={args.ns} page={args.page} {verdict} "
          f"frame_delta={frame_delta:.2f} red_slots={red_slots} "
          f"gui_scale={args.gui_scale} -> {args.out}")
    for s in slots:
        if s["verdict"] == "RED":
            print(f"  RED {s['slot']} ae={s['ae']}")
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
