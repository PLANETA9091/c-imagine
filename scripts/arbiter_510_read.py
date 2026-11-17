#!/usr/bin/env python3
"""The 510-arbiter read (the 14-e91j repair's verification, one command).

Pulls wall-ours-meta-0 + the shots pair + the verdict for a run, then:
  1. geom_receipt_check.py on the fetched boot.log at the NEW net law
     (dz=-1.04 default; the pre-registered prediction: the front-rig rows
     tr z = -0.04, the plane rows tr z = -1.04, net = z-0.54, PASS);
     the --dz 0 fallback = the OLD contract for the A/B direction;
  2. the ae2-p0 band best-shift (the prediction: (0,0), the residual ae
     down from 22.1M);
  3. the per-cell displacement-law spot check (scripts/displacement_law.py
     inlined, the 45 manifest cells, the meas vs the pred at dz=0);
  4. the verdict-summary family census (the green-rate movement vs the 509
     baseline: ae2 16.412, biomesoplenty 17.95, create 17.66,
     dynamictrees 90.213, farmersdelight 24.455, mekanism 9.591,
     minecraft 93.893, sophisticatedbackpacks 0.26, supplementaries 11.326).

Usage: arbiter_510_read.py <run-id> [--dz -1.04]
"""
import collections
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
TOKEN = open(os.path.expanduser(
    '/home/z/my-project/work/c-imagine/.secrets/gh_token')).read().strip()
REPO = 'PLANETA9091/c-imagine'
W, H = 1280, 720
F = 514.1
CAM_X = 0.5
CAM_Z = 0.5
EYE_Y = -58.38

BASELINE = {  # the 509 block-family green_pct (the e91h uncapped census)
    'ae2': 16.412, 'biomesoplenty': 17.95, 'create': 17.66,
    'dynamictrees': 90.213, 'farmersdelight': 24.455, 'mekanism': 9.591,
    'minecraft': 93.893, 'sophisticatedbackpacks': 0.26,
    'supplementaries': 11.326,
}


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
    dz = -1.04
    if '--dz' in sys.argv:
        dz = float(sys.argv[sys.argv.index('--dz') + 1])
    arts = json.loads(api(
        f'https://api.github.com/repos/{REPO}/actions/runs/{RUN}/artifacts?per_page=100'))
    ids = {}
    for a in arts.get('artifacts', []):
        ids[a['name']] = a['id']
    print('artifacts:', {k: ids[k] for k in ids if k in (
        'wall-ours-meta-0', 'wall-truth-shots-0', 'wall-ours-shots-0',
        'gallery-verdict')})
    meta = fetch_artifact(ids['wall-ours-meta-0'], f'/tmp/a510-meta-{RUN}.zip')
    tz = fetch_artifact(ids['wall-truth-shots-0'], f'/tmp/a510-t-{RUN}.zip')
    oz = fetch_artifact(ids['wall-ours-shots-0'], f'/tmp/a510-o-{RUN}.zip')
    vz = fetch_artifact(ids['gallery-verdict'], f'/tmp/a510-verdict-{RUN}.zip')

    # 1. the receipts vs the net law
    with zipfile.ZipFile(meta) as z:
        boot_bytes = z.read('srv-wall/boot.log')
        diag_bytes = z.read('srv-wall/c-imagine/scenes/capture-diag.log')
        man = z.read('srv-wall/c-imagine/scenes/gallery-ae2-p0.tsv').decode()
    logp = f'/tmp/a510-boot-{RUN}.log'
    open(logp, 'wb').write(boot_bytes)
    diagp = f'/tmp/a510-capture-diag-{RUN}.log'
    open(diagp, 'wb').write(diag_bytes)
    print('\n=== the net law (dz=%.2f) ===' % dz)
    subprocess.run(['python3', 'scripts/geom_receipt_check.py', logp,
                    '--dz', str(dz)])
    print('--- the old contract direction (dz=0):')
    subprocess.run(['python3', 'scripts/geom_receipt_check.py', logp,
                    '--dz', '0'])

    # 2. the band best-shift
    with zipfile.ZipFile(tz) as z:
        t = np.asarray(Image.open(io.BytesIO(
            z.read('gal-p-ae20.png'))).convert('RGB')).astype(np.int32)
    with zipfile.ZipFile(oz) as z:
        nm = [n for n in z.namelist() if n.endswith('ours-gal-p-ae20.png')][0]
        o = np.asarray(Image.open(io.BytesIO(
            z.read(nm))).convert('RGB')).astype(np.int32)
    best = None
    for dy in range(-45, 46):
        for dx in range(-45, 46):
            a = t[100:460, 300:900]
            b = o[100 + dy:460 + dy, 300 + dx:900 + dx]
            d = np.abs(a - b).sum()
            if best is None or d < best[0]:
                best = (d, dx, dy)
    raw = int(np.abs(t[100:460, 300:900] - o[100:460, 300:900]).sum())
    print('\n=== the ae2-p0 band ===')
    print('best (dx,dy):', best[1], best[2], 'ae:', int(best[0]),
          '| raw ae:', raw, '| the 509 baseline: (18,19) 22141924 / 25667190')

    # 3. the per-cell law spot check (the 45 manifest cells)
    cells = []
    for r in man.splitlines()[1:]:
        p = r.split('\t')
        if p[0] == 'cell':
            cells.append((int(p[1]), int(p[2]), int(p[3]), p[4]))
    half = 20
    better = worse = same = 0
    for cx, cy, cz, st in cells[:45]:
        sx9, sy9 = proj(cx, cy, 9.0)
        sx95, sy95 = proj(cx, cy, 9.5)
        pdx = sx95 - sx9
        pdy = sy95 - sy9
        ix, iy = int(round(sx9)), int(round(sy9))
        tc = t[iy - half + 40:iy + half + 40, ix - half + 40:ix + half + 40]
        oc0 = o[iy - half + 40:iy + half + 40, ix - half + 40:ix + half + 40]
        ae0 = int(np.abs(tc - oc0).sum())
        ix2 = ix + int(round(2.08 * pdx - 4.2))
        iy2 = iy + int(round(2.08 * pdy - 4.2))
        oc2 = o[iy2 - half + 40:iy2 + half + 40,
                ix2 - half + 40:ix2 + half + 40]
        if oc2.shape != tc.shape:
            continue
        ae2v = int(np.abs(tc - oc2).sum())
        if ae2v < ae0 * 0.95:
            better += 1
        elif ae2v > ae0 * 1.05:
            worse += 1
        else:
            same += 1
    print('\n=== the per-cell law residual (the 509 law still active?) ===')
    print('law-window better(>5%%):', better, 'worse:', worse,
          'same:', same, 'of 45 — the 509 was 31/0/14; '
          'A REPAIRED RUN = ~0 better (the law already applied)')

    # 4. the family census movement
    with zipfile.ZipFile(vz) as z:
        names = z.namelist()
        summ = None
        for n in names:
            if n.endswith('verdict-summary.json'):
                summ = json.loads(z.read(n))
    if summ:
        print('\n=== the family green-rate movement (vs the 509) ===')
        for row in summ if isinstance(summ, list) else summ.get('rows', []):
            if row.get('class') != 'block':
                continue
            ns = row['ns']
            base = BASELINE.get(ns)
            if base is None:
                continue
            g = row.get('green_pct')
            delta = None if g is None else round(g - base, 3)
            print('%-24s green %8.3f%% (509 %8.3f%%, delta %+8.3f)  '
                  'green %d red %d skipped %d'
                  % (ns, g or -1, base, delta or 0,
                     row.get('green', 0), row.get('red', 0),
                     row.get('skipped', 0)))


if __name__ == '__main__':
    main()
