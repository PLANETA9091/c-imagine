#!/usr/bin/env python3
"""Test the grid-scaling hypothesis: per-cell join shift vs radial distance from page centre."""
import glob, json, collections, math

# page centre in cell coords: the join crops cells at (col,row) of an 11x7-ish grid
files = glob.glob("/tmp/gr0/join-create-p*.json") + glob.glob("/tmp/gr0/join-biomesoplenty-p*.json") + glob.glob("/tmp/gr0/join-dynamictrees-p*.json")
buckets = collections.defaultdict(list)  # key = rounded radial distance in cells -> list of (dx,dy,page,col,row)
for f in files:
    try:
        j = json.load(open(f))
    except Exception:
        continue
    # page name -> col/row extents vary; use the max col/row seen on the page as centre proxy
    rows = j.get("rows", [])
    if not rows:
        continue
    maxc = max(r.get("col", 0) for r in rows)
    maxr = max(r.get("row", 0) for r in rows)
    cx, cy = maxc / 2.0, maxr / 2.0
    for r in rows:
        if r.get("verdict") != "RED":
            continue
        s = r.get("shift")
        if not s or s[0] is None:
            continue
        dx, dy = s
        dist = math.hypot(r.get("col", 0) - cx, r.get("row", 0) - cy)
        key = round(dist * 2) / 2
        buckets[key].append((dx, dy))

print("radial-cell-dist -> n, mean dx, mean dy, mean |d|")
for k in sorted(buckets):
    v = buckets[k]
    n = len(v)
    mdx = sum(a for a, b in v) / n
    mdy = sum(b for a, b in v) / n
    mad = sum(math.hypot(a, b) for a, b in v) / n
    print(f"  {k:5.1f}  n={n:4d}  dx={mdx:+7.2f}  dy={mdy:+7.2f}  |d|={mad:7.2f}")
