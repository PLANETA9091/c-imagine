#!/usr/bin/env python3
"""14-dg forensic crop tool: crop the two legs' cell for named RED rows,
measure WHERE the difference lives (mask geometry), and emit a side-by-side
montage for the eye.

Usage: forensic-crop.py <report-dir> <truth-shots-dir> <ours-shots-dir>
                        --page <join-file-name> --state <substring> [--all]

The page shot naming: truth <dir>/gal-b-<ns><p>.png,
ours <dir>/ours-gal-b-<ns><p>.png (the s5 artifact layout).
"""
import argparse
import glob
import json
import os
import sys

import numpy as np
from PIL import Image

REPO = os.environ.get("CIM_REPO", "/home/z/my-project/work/c-imagine")
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location(
    "gallery_diff", os.path.join(REPO, "scripts", "cimscene", "gallery-diff.py"))
gd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gd)


def page_png(shot_dir, ns, page, ours):
    pref = "ours-" if ours else ""
    for tag in ("gal-p-", "gal-b-"):
        name = pref + tag + ns + str(page) + ".png"
        p = os.path.join(shot_dir, name)
        if os.path.exists(p):
            return p
        cand = glob.glob(os.path.join(shot_dir, "**", name), recursive=True)
        if cand:
            return cand[0]
    return None


def montage(truth_im, ours_im, col, row, out, title):
    t, _ = gd.crop_cell(truth_im, col, row)
    o, _ = gd.crop_cell(ours_im, col, row)
    t = t.astype(np.uint8)
    o = o.astype(np.uint8)
    h, w, _ = t.shape
    d = (np.abs(t.astype(int) - o.astype(int)).sum(axis=2) > 60)
    ov = o.copy()
    ov[d] = [255, 0, 0]
    canvas = np.full((h, w * 3 + 16, 3), 30, np.uint8)
    canvas[:, :w] = t
    canvas[:, w + 8:2 * w + 8] = o
    canvas[:, 2 * w + 16:] = ov
    im = Image.fromarray(canvas)
    im = im.resize((im.width * 3, im.height * 3), Image.NEAREST)
    im.save(out)
    return d


def stats(d):
    if d.sum() == 0:
        return "no diff px"
    ys, xs = np.nonzero(d)
    h, w = d.shape
    return (f"diffpx={d.sum()} ({100.0*d.sum()/(h*w):.1f}%) "
            f"rows {ys.min()}..{ys.max()}/{h} cols {xs.min()}..{xs.max()}/{w} "
            f"tophalf={d[:h//2].sum()} bothalf={d[h//2:].sum()} "
            f"lefthalf={d[:,:w//2].sum()} righthalf={d[:,w//2:].sum()}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report")
    ap.add_argument("truth_shots")
    ap.add_argument("ours_shots")
    ap.add_argument("--state", required=True, help="state substring to find")
    ap.add_argument("--max", type=int, default=4, help="max rows to dump")
    ap.add_argument("--out", default="/tmp/forensic")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    n = 0
    for f in sorted(glob.glob(os.path.join(args.report, "join-*.json"))):
        d = json.load(open(f))
        for r in d["rows"]:
            if r["verdict"] != "RED" or args.state not in r["state"]:
                continue
            page = r["page"]
            ns = page.split("-")[1]
            pnum = page.rsplit("p", 1)[1]
            tp = page_png(args.truth_shots, ns, pnum, False)
            op = page_png(args.ours_shots, ns, pnum, True)
            if not tp or not op:
                print(f"MISSING SHOT for {page}: {tp} {op}")
                continue
            t = gd.load(tp)
            o = gd.load(op)
            out = os.path.join(args.out, f"{page}-c{r['col']}r{r['row']}.png")
            mask = montage(t, o, r["col"], r["row"], out, r["state"])
            print(f"{page} c{r['col']} r{r['row']} ae={r['ae']} {r['state']}")
            print(f"  {stats(mask)}")
            print(f"  -> {out}")
            n += 1
            if n >= args.max:
                return


if __name__ == "__main__":
    main()
