#!/usr/bin/env python3
"""Full-frame diagnostic join for the shot-only classes (held/gui/biome).

The held, gui and biome classes are shot on BOTH legs (per the SHOT NAME
LAW) but have no per-cell contract to join against — the held leg rides the
item pages (first-person framing, no grid), the gui shot is a fullscreen
menu, the biome page is a tint sample rig. This join measures the WHOLE
frame the same way on both sides and names the outcome:

  EMPTY          one side's frame is ~uniform (sky/void) — the leg shot
                 nothing (a dead page, a closed screen, an unbuilt rig);
                 the histogram names which side
  BOTH-STATIC    both frames ~uniform (nothing to compare — e.g. a biome
                 page whose rig never placed)
  CONTENT        real content on both sides; the AE / mean-abs-delta and
                 the per-quadrant AE split name the divergence scale

DIAGNOSTIC-ONLY by design (the flip law, coverage-gate.py): no gate here —
the numbers calibrate the channel; a later flip turns them into the gate.
"""
import argparse
import json
import sys

from PIL import Image
import numpy as np


def load(path):
    img = Image.open(path).convert("RGB")
    return np.asarray(img, dtype=np.int16)


def uniform(a):
    """True when the frame is ~one color (sky/void): the share of pixels
    deviating from the MEDIAN color by >10 must be under 0.3% — a small
    content patch (a held item over sky, a menu on a still background)
    is CONTENT at 0.3%, while a truly empty frame sits at ~0.00x%."""
    med = np.median(a.reshape(-1, 3), axis=0)
    dev = np.abs(a - med).max(axis=2)
    return float((dev > 10).mean()) < 0.003


def stats(a):
    """Uniformity signature of one frame."""
    luma = a.mean(axis=2)
    med = np.median(a.reshape(-1, 3), axis=0)
    dev = np.abs(a - med).max(axis=2)
    return {
        "std": round(float(luma.std()), 2),
        "mean": round(float(luma.mean()), 2),
        "dev_share": round(float((dev > 10).mean()), 5),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--ns", required=True)
    ap.add_argument("--page", required=True)
    ap.add_argument("--class-name", dest="klass", required=True,
                    choices=("held", "gui", "biome"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    try:
        t = load(args.truth)
        o = load(args.ours)
    except Exception as e:  # noqa: BLE001 — a broken PNG is a named row, not a crash
        json.dump({"ns": args.ns, "page": args.page, "class": args.klass,
                   "verdict": "BROKEN", "error": str(e)},
                  open(args.out, "w"), indent=1)
        return

    tsig, osig = stats(t), stats(o)
    t_empty, o_empty = uniform(t), uniform(o)

    # same-shape guard (the two legs render the same 1280x720 contract; a
    # mismatch is itself the finding)
    same_shape = t.shape == o.shape
    if same_shape:
        d = np.abs(t - o)
        ae = int((d.max(axis=2) > 8).sum())  # AE at the ±8 tolerance the
        # pixel gates use for the lightmap drift (camera-lock-26-3.md)
        mad = round(float(d.mean()), 2)
        h, w = d.shape[:2]
        quads = {
            "q": [round(float(d[:h//2, :w//2].mean()), 2),
                  round(float(d[:h//2, w//2:].mean()), 2),
                  round(float(d[h//2:, :w//2].mean()), 2),
                  round(float(d[h//2:, w//2:].mean()), 2)],
        }
    else:
        ae, mad, quads = None, None, {}

    if t_empty and o_empty:
        verdict = "BOTH-STATIC"
    elif t_empty or o_empty:
        verdict = "EMPTY"
    else:
        verdict = "CONTENT"

    json.dump({
        "ns": args.ns, "page": args.page, "class": args.klass,
        "verdict": verdict,
        "truth": tsig | {"empty": t_empty},
        "ours": osig | {"empty": o_empty},
        "same_shape": same_shape,
        "ae": ae, "mean_abs_delta": mad, "quadrants": quads,
        "note": "DIAGNOSTIC-ONLY — calibrates the channel, never gates",
    }, open(args.out, "w"), indent=1)
    print(f"framediff {args.klass}/{args.ns} p{args.page}: {verdict} "
          f"ae={ae} mad={mad} -> {args.out}")


if __name__ == "__main__":
    sys.exit(main())
