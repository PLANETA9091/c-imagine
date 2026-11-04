#!/usr/bin/env python3
"""ANIM liveness probe for the cimscene block wall (2026-11-04, DIAGNOSTIC).

The BLOCK gallery's static pixel gate cannot see motion: a kinetic block
whose animation the ours leg freezes renders a "perfect" still frame and
greens. The probe measures MOTION instead: the walk shoots the SAME page
twice ~2s apart on each leg (gal-<stem>-t0 / -t1), this script computes the
per-side frame DELTA (mean |Δ| over RGB) and joins the deltas:

  truth_delta > threshold AND ours_delta ~ 0  -> OURS-STATIC (the ours
      leg froze content the truth leg animates — the divergence the probe
      exists to catch);
  both ~ 0                                    -> BOTH-STATIC (not a
      kinetic page — fine, and it cross-checks the walk's dyn-cell
      selection);
  both > threshold                            -> LIVE (both legs animate);
  truth ~ 0 AND ours > threshold              -> OURS-ONLY-MOTION (the
      ours leg animates something the truth leg does not — e.g. a display
      rig idling — reported for the walk to quarantine).

Delta is measured on the FULL frame (the camera is pinned by the walk; the
wall is the only content). The threshold defaults to 0.35 mean |Δ| per
channel — the value the IDENTIFY-IDLE datum on the mob pages measured for
clearly-visible motion vs render noise (a llvmpipe frame pair on a still
scene measures < 0.05; the flag stays explicit so the first multi-ns run
can recalibrate it against its own both-static pages).

DIAGNOSTIC: the join runs `|| true` in the workflow — the report names the
frozen pages, the gate flips when the kinetic channel is measured stable
(same law as coverage-gate.py DIAGNOSTIC_CLASSES).

Usage:
    anim-diff.py --truth-t0 T0.png --truth-t1 T1.png \
        --ours-t0 O0.png --ours-t1 O1.png [--threshold 0.35] \
        [--ns N --page P] --out report.json
"""
import argparse
import json
import pathlib
import sys

import numpy as np
from PIL import Image


def load(p):
    im = Image.open(p).convert("RGB")
    return np.asarray(im).astype(np.float64)


def delta(a, b):
    """Mean |Δ| per channel over the full frame (0..255 scale)."""
    if a.shape != b.shape:
        raise SystemExit(f"frame size mismatch {a.shape} vs {b.shape}")
    return float(np.abs(a - b).mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth-t0", required=True)
    ap.add_argument("--truth-t1", required=True)
    ap.add_argument("--ours-t0", required=True)
    ap.add_argument("--ours-t1", required=True)
    ap.add_argument("--threshold", type=float, default=0.35)
    ap.add_argument("--ns", default="")
    ap.add_argument("--page", default="")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    td = delta(load(args.truth_t0), load(args.truth_t1))
    od = delta(load(args.ours_t0), load(args.ours_t1))
    t_live = td > args.threshold
    o_live = od > args.threshold
    if t_live and not o_live:
        verdict = "OURS-STATIC"
    elif not t_live and not o_live:
        verdict = "BOTH-STATIC"
    elif t_live and o_live:
        verdict = "LIVE"
    else:
        verdict = "OURS-ONLY-MOTION"
    report = {
        "ns": args.ns, "page": args.page, "verdict": verdict,
        "truth_delta": round(td, 4), "ours_delta": round(od, 4),
        "threshold": args.threshold,
        "note": "a static-but-should-rotate divergence shows as "
                "ours_delta ~ 0 while truth_delta > threshold",
    }
    pathlib.Path(args.out).write_text(json.dumps(report, indent=1))
    print(f"anim-diff: ns={args.ns} page={args.page} {verdict} "
          f"truth_delta={td:.3f} ours_delta={od:.3f} "
          f"threshold={args.threshold} -> {args.out}")
    # DIAGNOSTIC: never a red exit — the report carries the verdict
    return 0


if __name__ == "__main__":
    sys.exit(main())
