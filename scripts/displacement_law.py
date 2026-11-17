#!/usr/bin/env python3
"""The depth-law displacement check (the 509 arbiter's follow-up).

Hypothesis (14-e91g theory (a) sharpened): the truth shot renders the REAL
block face at z=9.0 (the block occupies z 9..10, camera at z=0.5 side), the
ours shot renders the display content at the NET plane z=9.5 (the front-air
trick: spawn z=8.5 + translation z=+1). The 0.5-block depth delta projects
to a per-cell pixel shift; the join's fixed crops then compare misregistered
windows -> the giant ae = the explosion mechanism.

The law: sx(z) = W/2 - F*(x+0.5-CAM_X)/(z-CAM_Z), sy(z) similar with EYE_Y.
For each ae2-p0 cell measure the REAL best shift of the ours crop vs the
truth crop (a +-40px search per cell) and compare with the predicted delta
between z=9.0 and z=9.5 projections.
"""
import io
import json
import re
import zipfile
import numpy as np
from PIL import Image

W, H = 1280, 720
F = 514.1
CAM_X = 0.5
CAM_Z = 0.5
EYE_Y = -58.38

META = '/tmp/a509-meta.zip'
TZIP = '/tmp/a509-t.zip'
OZIP = '/tmp/a509-o.zip'


def proj(x, y, z):
    d = z - CAM_Z
    sx = W / 2 - F * (x + 0.5 - CAM_X) / d
    sy = H / 2 - F * (y + 0.5 - EYE_Y) / d
    return sx, sy


def main():
    with zipfile.ZipFile(META) as z:
        man = z.read('srv-wall/c-imagine/scenes/gallery-ae2-p0.tsv').decode()
    cells = []
    for r in man.splitlines()[1:]:
        p = r.split('\t')
        if p[0] == 'cell':
            cells.append((int(p[1]), int(p[2]), int(p[3]), p[4]))

    with zipfile.ZipFile(TZIP) as z:
        t = np.asarray(Image.open(io.BytesIO(
            z.read('gal-p-ae20.png'))).convert('RGB')).astype(np.int32)
    with zipfile.ZipFile(OZIP) as z:
        name = [n for n in z.namelist()
                if n.endswith('ours-gal-p-ae20.png')][0]
        o = np.asarray(Image.open(io.BytesIO(
            z.read(name))).convert('RGB')).astype(np.int32)

    CS = 64  # the join's crop size guess; use the manifest cell screen size
    print('cell  pred_dxs pred_dys  meas_dxs meas_dys  ae_at0  ae_atpred')
    rows = []
    for cx, cy, cz, st in cells:
        sx9, sy9 = proj(cx, cy, 9.0)     # the truth's real block face
        sx95, sy95 = proj(cx, cy, 9.5)   # the ours display net plane
        pdx, pdy = sx95 - sx9, sy95 - sy9
        ix, iy = int(round(sx9)), int(round(sy9))
        half = 20
        t_crop = t[iy - half + 40:iy + half + 40, ix - half + 40:ix + half + 40]
        best0 = None
        bestp = None
        for dy in range(-40, 41):
            for dx in range(-40, 41):
                oc = o[iy + dy - half + 40:iy + dy + half + 40,
                       ix + dx - half + 40:ix + dx + half + 40]
                if oc.shape != t_crop.shape:
                    continue
                a = int(np.abs(t_crop - oc).sum())
                if best0 is None or a < best0[0]:
                    best0 = (a, dx, dy)
        # ae at the ideal (0,0) window vs at the predicted window
        oc0 = o[iy - half + 40:iy + half + 40, ix - half + 40:ix + half + 40]
        ae0 = int(np.abs(t_crop - oc0).sum())
        oc2 = o[iy + int(round(pdy)) - half + 40:iy + int(round(pdy)) + half + 40,
                ix + int(round(pdx)) - half + 40:ix + int(round(pdx)) + half + 40]
        aep = int(np.abs(t_crop - oc2).sum()) if oc2.shape == t_crop.shape else -1
        rows.append((st.split('[')[0], cx, cy, pdx, pdy,
                     best0[1], best0[2], ae0, aep))
    for r in rows[:24]:
        print('%-46s (%2d,%3d) p=(%6.1f,%6.1f) m=(%3d,%3d) ae0=%8d aep=%8d'
              % (r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]))
    n = sum(1 for r in rows if r[8] >= 0)
    better = sum(1 for r in rows if r[8] >= 0 and r[8] < r[7] * 0.95)
    print('cells', len(rows), 'predicted-window better(>5%% gain):', better, '/', n)


if __name__ == '__main__':
    main()
