#!/usr/bin/env python3
"""Direct page-frame comparison: detect content blobs in ours vs truth frames of the same page."""
import numpy as np
from PIL import Image
from collections import deque
import sys


def blobs(path, name):
    a = np.asarray(Image.open(path).convert("RGB")).astype(int)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    # content = not-sky-ish, not-grayish (same mask family as the datum)
    obj = ~(((abs(r - g) < 14) & (abs(g - b) < 14)) | (b > r + 25))
    obj[:330, :] = False   # sky band
    seen = np.zeros_like(obj, dtype=bool)
    out = []
    ys, xs = np.where(obj)
    for sy, sx in zip(ys.tolist(), xs.tolist()):
        if seen[sy, sx]:
            continue
        q = deque([(sy, sx)])
        seen[sy, sx] = True
        n = 0
        minx = maxx = sx
        miny = maxy = sy
        while q:
            y, x = q.popleft()
            n += 1
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
            for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                ny, nx = y + dy, x + dx
                if 0 <= ny < obj.shape[0] and 0 <= nx < obj.shape[1] and obj[ny, nx] and not seen[ny, nx]:
                    seen[ny, nx] = True
                    q.append((ny, nx))
        if n >= 800:
            out.append((minx, maxx, miny, maxy, n))
    out.sort()
    print(f"--- {name}: {len(out)} blobs (w,h,x0,y0 sorted left->right)")
    for x0, x1, y0, y1, n in out[:16]:
        print(f"   w={x1-x0+1:4d} h={y1-y0+1:4d} x={x0:4d}..{x1:4d} y={y0:4d}..{y1:4d} n={n}")
    # pitch estimate: median gap between consecutive blob left edges on the widest row band
    if len(out) >= 3:
        lefts = [o[0] for o in out]
        gaps = [b - a for a, b in zip(sorted(lefts), sorted(lefts)[1:]) if b - a > 20]
        if gaps:
            gaps.sort()
            print(f"   median left-gap: {gaps[len(gaps)//2]} (n={len(gaps)})")
    return out


ours = blobs(sys.argv[1], "OURS " + sys.argv[1].split("/")[-1])
truth = blobs(sys.argv[2], "TRUTH " + sys.argv[2].split("/")[-1])
