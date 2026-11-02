#!/usr/bin/env python3
"""14-g: measure the isolated-suspects shot (ours-isolated-suspects.png).

Each suspect display sits at a known (x, y) on the wall with the walk's
transformation (scale 1.0126, ty -0.0184). The EXPECTED content center comes
from the composite JSON's element bbox (model units 0..16 -> the display cube
[-0.5..0.5] * scale, centered on the entity) projected with the gallery
pinhole. The MEASURED content center = the non-background bbox center inside
the position's window (searched wider than the cell to catch displaced
content). The per-model delta answers: composite-borne offset vs page-context.
"""
import io
import json
import math
import sys
import zipfile

import numpy as np
from PIL import Image

W, H = 1280, 720
F = (H / 2.0) / math.tan(math.radians(35.0))  # fov:-0.65 = 70 deg on 26.3
CAM_X = 0.5
EYE_Y = -58.38
SCALE = 1.0126
TY = -0.0184

# position -> (name, composite path in the pack)
CELLS = {
    (-4.5, -59.5): ("wall_ochrum_control", "gallery/g7444.json"),
    (-2.5, -59.5): ("stairs_north_bottom_straight", "gallery/g7680.json"),
    (-0.5, -59.5): ("stairs_south_bottom_straight", "gallery/g7700.json"),
    (1.5, -59.5): ("stairs_south_top_straight", "gallery/g7710.json"),
    (3.5, -59.5): ("sail_north", "gallery/g11049.json"),
    (5.5, -59.5): ("seat", "gallery/g11053.json"),
    (-4.5, -57.5): ("layered_scoria", "gallery/g11012.json"),
    (-2.5, -57.5): ("contact_down", "gallery/g16152.json"),
}


def composite_bbox(model_json):
    els = model_json.get("elements", [])
    lo = [1e9] * 3
    hi = [-1e9] * 3
    for e in els:
        f, t = e.get("from"), e.get("to")
        if not f or not t:
            continue
        for i in range(3):
            lo[i] = min(lo[i], f[i], t[i])
            hi[i] = max(hi[i], f[i], t[i])
    if not els:
        return None
    return lo, hi


def project(wx, wy, wz):
    sx = W / 2.0 + F * (wx - CAM_X) / wz
    sy = H / 2.0 - F * (wy - EYE_Y) / wz
    return sx, sy


def measure(img, sx, sy, half=55):
    x0, x1 = int(sx - half), int(sx + half)
    y0, y1 = int(sy - half), int(sy + half)
    x0 = max(0, x0); y0 = max(0, y0)
    x1 = min(W, x1); y1 = min(H, y1)
    win = img[y0:y1, x0:x1]
    r, g, b = win[:, :, 0], win[:, :, 1], win[:, :, 2]
    sky = (b > 170) & (b > r + 15) & (g > 130)
    grass = (g > 90) & (g > b + 25) & (r < g + 10)
    content = ~(sky | grass)
    n = int(content.sum())
    if n < 20:
        return None
    ys, xs = np.nonzero(content)
    return (n, (int(xs.min()) + x0, int(xs.max()) + x0),
            (int(ys.min()) + y0, int(ys.max()) + y0))


def main(png_path, pack_zip):
    img = np.asarray(Image.open(png_path).convert("RGB")).astype(np.int64)
    z = zipfile.ZipFile(pack_zip)
    print(f"{'model':32s} {'px':>6} {'expected cx,cy':>16} {'measured cx,cy':>16} "
          f"{'delta':>12} {'bbox w x h':>10}")
    for (x0, y0), (name, comp) in sorted(CELLS.items()):
        d = json.loads(z.read("assets/c-imagine/models/" + comp))
        bb = composite_bbox(d)
        if bb is None:
            print(f"{name:32s} NO ELEMENTS")
            continue
        (lo, hi) = bb
        cx_u = (lo[0] + hi[0]) / 2.0
        cy_u = (lo[1] + hi[1]) / 2.0
        # model units -> world: (u - 8)/16 * SCALE, centered on the entity
        wx = x0 + (cx_u - 8.0) / 16.0 * SCALE
        wy = y0 + (cy_u - 8.0) / 16.0 * SCALE + TY
        sx, sy = project(wx, wy, 9.5)
        m = measure(img, sx, sy)
        if m is None:
            print(f"{name:32s} EMPTY around ({sx:.0f},{sy:.0f})")
            continue
        n, (bx0, bx1), (by0, by1) = m
        mcx = (bx0 + bx1) / 2.0
        mcy = (by0 + by1) / 2.0
        print(f"{name:32s} {n:>6} {sx:>7.1f},{sy:>7.1f} {mcx:>7.1f},{mcy:>7.1f} "
              f"({mcx-sx:+5.1f},{mcy-sy:+5.1f}) {bx1-bx0:>4} x {by1-by0:<4}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
