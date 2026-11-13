#!/usr/bin/env python3
"""14-dg class scanner: for every RED row in the pulled report dirs, crop both
legs and classify the mechanism:

  DARK    ours content luma < 45% of truth's (the lightmap starvation
          signature: geometry identical, the cell renders unlit)
  BRIGHT  ours content luma > 200% of truth's (the inverse starvation)
  SHIFT   the best +-3px translation drops the diff >4x (sub-cell misalign)
  CONTENT everything else (a real texture/geometry difference)

Census table by family -> the fix lanes by leverage.
"""
import argparse
import glob
import json
import os
import re
import sys

import numpy as np
from PIL import Image

REPO = os.environ.get("CIM_REPO", "/home/z/my-project/work/c-imagine")
import importlib.util  # noqa: E402
_spec = importlib.util.spec_from_file_location(
    "gallery_diff", os.path.join(REPO, "scripts", "cimscene", "gallery-diff.py"))
gd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gd)

_shot_cache = {}


def page_png(shot_dir, ns, page, ours, page_kind="wall"):
    key = (shot_dir, ns, page, ours)
    if key in _shot_cache:
        return _shot_cache[key]
    pref = "ours-" if ours else ""
    found = None
    tags = ("gal-p-", "gal-b-")
    if page_kind == "emobs":
        tags = ("gal-emobs-",)
    elif page_kind == "edyn":
        tags = ("gal-edyn-",)
    elif page_kind == "elarge":
        tags = ("gal-elarge-",)
    for tag in tags:
        name = pref + tag + ns + str(page) + ".png"
        p = os.path.join(shot_dir, name)
        if os.path.exists(p):
            found = p
            break
        cand = glob.glob(os.path.join(shot_dir, "**", name), recursive=True)
        if cand:
            found = cand[0]
            break
    _shot_cache[key] = found
    return found


def content_luma(crop):
    """mean luma over the non-sky content (luma < 200)."""
    l = crop.mean(axis=2)
    m = l < 200
    if m.sum() < 20:
        return float(l.mean())
    return float(l[m].mean())


def best_shift_diff(t, o):
    """min abs-diff px over +-3px integer shifts (sub-cell misalign test)."""
    base = (np.abs(t - o).sum(axis=2) > 60).sum()
    best = base
    for dy in range(-3, 4):
        for dx in range(-3, 4):
            if dx == 0 and dy == 0:
                continue
            s = np.roll(np.roll(o, dy, axis=0), dx, axis=1)
            d = (np.abs(t - s).sum(axis=2) > 60).sum()
            best = min(best, d)
    return base, best



def _crop_pitch2(im, col, row):
    """The entity grid's crop: the same wall origin, ENTITY_PITCH=2 — the
    sparse cell centers land 2 blocks apart, the crop half scales with the
    pitch so the neighbours never bleed."""
    import math
    F = gd.F
    x = gd.GALLERY_X0 + col * 2
    y = gd.GALLERY_Y0 + row * 2
    sx = gd.W / 2.0 - F * (x + 0.5 - gd.CAM_X) / gd.DIST
    sy = gd.H / 2.0 - F * (y + 0.5 - gd.EYE_Y) / gd.DIST
    half = gd.CELL_PX * 0.92 * 2 / 2.0
    b = (int(sx - half), int(sy - half), int(sx + half), int(sy + half))
    return im[b[1]:b[3], b[0]:b[2]]


def classify(t, o):
    lt, lo = content_luma(t), content_luma(o)
    if lt > 8 and lo < 0.45 * lt:
        return "DARK", lt, lo, 0, 0
    if lt > 8 and lo > 2.0 * lt:
        return "BRIGHT", lt, lo, 0, 0
    base, best = best_shift_diff(t.astype(int), o.astype(int))
    if base > 0 and best < base / 4.0:
        return "SHIFT", lt, lo, base, best
    return "CONTENT", lt, lo, base, best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reports", nargs="+")
    ap.add_argument("truth_shots")
    ap.add_argument("ours_shots")
    ap.add_argument("--out", default="/tmp/forensic-scan.json")
    args = ap.parse_args()

    fam = {}
    recs = []
    seen = set()
    for rep in args.reports:
        for f in sorted(glob.glob(os.path.join(rep, "join-*.json"))):
            d = json.load(open(f))
            for r in d["rows"]:
                if r.get("verdict") != "RED" or "page" not in r:
                    continue
                page = r["page"]
                key = (page, r["col"], r["row"], r["state"])
                if key in seen:
                    continue
                seen.add(key)
                # the entity pages carry an extra segment:
                # gallery-entity-<ns>-p<p> (the wall pages are
                # gallery-<ns>-p<p>); the entity grid rides the pitch-2
                # sparse geometry (ENTITY_PITCH), the wall grid pitch 1
                parts = page.split("-")
                if parts[1] == "entity":
                    ns = parts[2]
                    pitch = 2
                    series = ("mobs" if "-emobs" in page or True else "mobs")
                else:
                    ns = parts[1]
                    pitch = 1
                pnum = page.rsplit("p", 1)[1]
                kind = ("emobs" if "/join-e-" in f or "join-e-" in os.path.basename(f) else "wall")
                tp = page_png(args.truth_shots, ns, pnum, False, kind)
                op = page_png(args.ours_shots, ns, pnum, True, kind)
                if not tp or not op:
                    continue
                t_im = gd.load(tp)
                o_im = gd.load(op)
                if pitch == 2:
                    t = _crop_pitch2(t_im, r["col"], r["row"])
                    o = _crop_pitch2(o_im, r["col"], r["row"])
                else:
                    t, _ = gd.crop_cell(t_im, r["col"], r["row"])
                    o, _ = gd.crop_cell(o_im, r["col"], r["row"])
                cls, lt, lo, base, best = classify(t, o)
                # family = the mod's id path stem
                m = re.match(r"[a-z0-9_]+:([a-z0-9_/]+?)[\[]|$",
                             r["state"].replace(";", "[", 1))
                stem = m.group(1) if m else "?"
                fam.setdefault((ns, cls), []).append(stem)
                recs.append({"page": page, "col": r["col"], "row": r["row"],
                             "state": r["state"], "class": cls, "ae": r["ae"],
                             "luma_t": round(lt, 1), "luma_o": round(lo, 1),
                             "diff_base": int(base), "diff_best": int(best)})
    json.dump(recs, open(args.out, "w"), indent=0)
    tot = {}
    for (ns, cls), stems in sorted(fam.items()):
        tot[cls] = tot.get(cls, 0) + len(stems)
        top = {}
        for s in stems:
            top[s] = top.get(s, 0) + 1
        tops = ", ".join(f"{k}x{v}" for k, v in sorted(top.items(),
                          key=lambda kv: -kv[1])[:6])
        print(f"{ns:22s} {cls:8s} {len(stems):5d}  {tops}")
    print()
    print("TOTALS:", {k: v for k, v in sorted(tot.items())})
    print(f"scanned={len(recs)} -> {args.out}")


if __name__ == "__main__":
    main()
