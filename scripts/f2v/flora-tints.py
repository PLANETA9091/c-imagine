#!/usr/bin/env python3
"""14-bu FLORA TINT calibration: the chroma-masked per-pixel median ratio.

The tint-calibrate.py law (truth/ours, pixel-aligned median) refused the BOP
flora: the wall's achromatic smooth_stone pixels sit inside the compared area
and pull every per-cell median toward 1.0, so the per-cell tints scatter past
the tolerance and the tool honestly refuses.

The flora sprites are PRE-TINT GRAYSCALE (chroma 0 in the raw texture), the
truth renders them TINTED (chroma = the tint's own spread), and the wall/floor
stays achromatic on both legs. So: mask the truth crop by CHROMA (the tinted
plant pixels only), take the per-pixel ratio truth/ours at those same
positions, and the MEDIAN over that population is the pure multiply — the
sway/particle minority shifts do not survive the median.

Output: the per-texture rows in the visual-tints.tsv format plus the
honesty stats (n, maxDev across the state's cells). A row is DEFENSIBLE when
maxDev <= --tolerance on every channel.
"""
from __future__ import annotations

import collections
import glob
import json
import pathlib
import re
import sys

import numpy as np
from PIL import Image

CHROMA_MIN = 18.0
LOW_SIGNAL = 24.0


def cells(artifacts: pathlib.Path, ns: str):
    for jf in sorted(artifacts.glob(f"join-{ns}-p*.json")):
        m = re.search(r"join-" + ns + r"-p(\d+)\.json", jf.name)
        pg = m.group(1)
        diffdir = jf.parent / ("diff-" + ns + "-p" + pg)
        try:
            d = json.load(open(jf))
        except (OSError, ValueError):
            continue
        for r in d.get("rows", []):
            if r.get("verdict") != "RED":
                continue
            state = r.get("state", "")
            tp = diffdir / f"gallery-{ns}-p{pg}_c{r['col']}_r{r['row']}_truth.png"
            op = diffdir / f"gallery-{ns}-p{pg}_c{r['col']}_r{r['row']}_ours.png"
            if not (tp.is_file() and op.is_file()):
                continue
            yield state, pg, r, tp, op


def main() -> int:
    ap = __import__("argparse").ArgumentParser()
    ap.add_argument("artifacts")
    ap.add_argument("--ns", default="biomesoplenty")
    ap.add_argument("--tolerance", type=float, default=0.06)
    ap.add_argument("--out")
    args = ap.parse_args()

    arts = pathlib.Path(args.artifacts)
    # texture attribution: the state -> the sprite ids via the blockstate
    # naming convention (biomesoplenty:<name> -> biomesoplenty:block/<name>)
    per_tex = collections.defaultdict(lambda: collections.defaultdict(list))
    for state, pg, r, tp, op in cells(arts, args.ns):
        base = re.sub(r"\[.*", "", state).split(":", 1)[1]
        tex = f"{args.ns}:block/{base}"
        t = np.asarray(Image.open(tp).convert("RGB")).astype(np.float64)
        o = np.asarray(Image.open(op).convert("RGB")).astype(np.float64)
        if t.shape != o.shape:
            continue
        chroma = t.max(axis=2) - t.min(axis=2)
        mask = chroma > CHROMA_MIN
        if mask.sum() < 40:
            continue
        # drop the low-signal ours pixels (the ratio would explode)
        mask &= o.sum(axis=2) > LOW_SIGNAL
        if mask.sum() < 40:
            continue
        ratios = []
        for c in range(3):
            tc = t[:, :, c][mask]
            oc = o[:, :, c][mask]
            good = oc > 8
            if good.sum() < 40:
                break
            ratios.append(float(np.median(tc[good] / oc[good])))
        if len(ratios) == 3:
            per_tex[tex][state].append((r["col"], r["row"], pg, ratios, int(mask.sum())))

    rows = []
    for tex in sorted(per_tex):
        allv = [v for cells_ in per_tex[tex].values() for v in cells_]
        arr = np.array([v[3] for v in allv])
        med = np.median(arr, axis=0)
        dev = np.abs(arr - med).max(axis=0)
        ok = dev.max() <= args.tolerance
        ev = ";".join(
            f"{state}@gallery-{args.ns}-p{pg}:c{col},r{row}|n={n}|class=chroma-median"
            for state, vs in sorted(per_tex[tex].items())
            for col, row, pg, _, n in vs[:3]
        )
        rows.append((tex, med, dev, len(allv), ok, ev))
        print(
            f"{'OK  ' if ok else 'REF '} {tex:52s} n={len(allv):3d} "
            f"tint=({round(med[0],4)},{round(med[1],4)},{round(med[2],4)}) "
            f"maxDev=({dev[0]:.3f},{dev[1]:.3f},{dev[2]:.3f})"
        )
    if args.out:
        with open(args.out, "w") as fh:
            fh.write(
                "# visual-tints.tsv — flora tint extension (14-bu): the\n"
                "# chroma-masked per-pixel median of truth/ours (see\n"
                "# scripts/flora-tints.py). Defensible rows only.\n"
            )
            for tex, med, dev, n, ok, ev in rows:
                if ok:
                    fh.write(
                        f"{tex}\t{round(med[0],4)}\t{round(med[1],4)}\t{round(med[2],4)}\t{ev}\n"
                    )
        print(f"written: {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
