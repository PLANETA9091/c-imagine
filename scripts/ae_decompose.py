#!/usr/bin/env python3
"""The per-cell ae decomposition (the 14-e91j follow-up rung's instrument).

For the ae2-p0 pair (or any page pair present in the run's shots-0
artifacts) split each cell's raw ae into:
  - the FLOOR: the best-shift ae over a +-48px window (the 509 lesson: the +-24 clamp manufactured the edge floors) = the CONTENT
    mismatch (the composite quads vs the truth's real render — shading,
    AO, the texture differences the shift cannot fix);
  - the SHIFT component: raw_ae - floor = the misregistration share the
    geometry owns (the 14-e91j depth law's recoverable part).

Prints the per-page table + the family totals. The repair's ceiling =
the floor's share: if the floor dominates, the next rung = the content
lane (the composite bakes), NOT the geometry.

Usage: ae_decompose.py <run-id> [page=ae20]
"""
import io
import json
import os
import re
import subprocess
import sys
import zipfile

import numpy as np
from PIL import Image

RUN = sys.argv[1]
PAGE = sys.argv[2] if len(sys.argv) > 2 else 'ae20'
TOKEN = open(os.path.expanduser(
    '/home/z/my-project/work/c-imagine/.secrets/gh_token')).read().strip()
REPO = 'PLANETA9091/c-imagine'
W, H = 1280, 720
F = 514.1
CAM_X = 0.5
CAM_Z = 0.5
EYE_Y = -58.38
HALF = 24


def api(url):
    return subprocess.run(['curl', '-s', '-H',
                           f'Authorization: Bearer {TOKEN}', url],
                          capture_output=True).stdout


def fetch_artifact(aid, dest):
    if not os.path.exists(dest):
        data = subprocess.run(['curl', '-sL', '-H',
                               f'Authorization: Bearer {TOKEN}',
                               f'https://api.github.com/repos/{REPO}/actions/artifacts/{aid}/zip'],
                              capture_output=True).stdout
        open(dest, 'wb').write(data)
    return dest


def proj(x, y, z):
    d = z - CAM_Z
    return (W / 2 - F * (x + 0.5 - CAM_X) / d,
            H / 2 - F * (y + 0.5 - EYE_Y) / d)


def main():
    arts = json.loads(api(
        f'https://api.github.com/repos/{REPO}/actions/runs/{RUN}/artifacts?per_page=100'))
    ids = {a['name']: a['id'] for a in arts.get('artifacts', [])}
    m = re.match(r'([a-z]+)0*$', PAGE)
    ns = m.group(1) if m else 'ae2'
    # the manifest: the p0 page of the ns family
    meta = fetch_artifact(ids['wall-ours-meta-0'], f'/tmp/ad-meta-{RUN}.zip')
    tz = fetch_artifact(ids['wall-truth-shots-0'], f'/tmp/ad-t-{RUN}.zip')
    oz = fetch_artifact(ids['wall-ours-shots-0'], f'/tmp/ad-o-{RUN}.zip')
    with zipfile.ZipFile(meta) as z:
        man = z.read(
            f'srv-wall/c-imagine/scenes/gallery-{ns}-p0.tsv').decode()
    tname = f'gal-p-{PAGE}.png'
    oname = f'ours-gal-p-{PAGE}.png'
    with zipfile.ZipFile(tz) as z:
        t = np.asarray(Image.open(io.BytesIO(
            z.read(tname))).convert('RGB')).astype(np.int32)
    with zipfile.ZipFile(oz) as z:
        nm = [n for n in z.namelist() if n.endswith(oname)][0]
        o = np.asarray(Image.open(io.BytesIO(
            z.read(nm))).convert('RGB')).astype(np.int32)
    tot_raw = tot_floor = n = 0
    print('state  cell  raw_ae  floor  shift_share  (dx,dy)')
    for r in man.splitlines()[1:]:
        p = r.split('\t')
        if p[0] != 'cell':
            continue
        cx, cy = int(p[1]), int(p[2])
        st = p[4]
        ix, iy = map(int, np.round(proj(cx, cy, 9.0)))
        tc = t[iy - HALF:iy + HALF, ix - HALF:ix + HALF]
        if tc.shape[0] < 2 * HALF or tc.shape[1] < 2 * HALF:
            continue
        raw = int(np.abs(tc - o[iy - HALF:iy + HALF,
                               ix - HALF:ix + HALF]).sum())
        best = None
        for dy in range(-48, 49):
            for dx in range(-48, 49):
                oc = o[iy + dy - HALF:iy + dy + HALF,
                       ix + dx - HALF:ix + dx + HALF]
                if oc.shape != tc.shape:
                    continue
                a = int(np.abs(tc - oc).sum())
                if best is None or a < best[0]:
                    best = (a, dx, dy)
        floor = best[0]
        tot_raw += raw
        tot_floor += floor
        n += 1
        print('%-40s (%3d,%3d) %9d %9d %6.1f%% (%d,%d)'
              % (st[:40], cx, cy, raw, floor,
                 100.0 * (raw - floor) / max(1, raw), best[1], best[2]))
    if tot_raw:
        print('\nTOTAL cells %d raw %d floor %d -> the shift share %.1f%%, '
              'the content share %.1f%% (the repair ceiling = the shift share)'
              % (n, tot_raw, tot_floor,
                 100.0 * (tot_raw - tot_floor) / tot_raw,
                 100.0 * tot_floor / tot_raw))


if __name__ == '__main__':
    main()
