#!/usr/bin/env python3
"""The 514-arbiter read (the 14-e91w calibration roof's A/B).

One command over a run id:
  1. the calib-roof receipts: 'f2v gallery: page N calib-roof ns=X rows=R
     placed=M cleared=K x=.. z=..' — the contract: rows=1 for the five
     families (ae2/mekanism/supplementaries/farmersdelight/
     sophisticatedbackpacks), rows=2 for create, 0 for the control class
     (biomesoplenty/dynamictrees/minecraft), placed=11 cleared=0 per page
     (the x range = the wall's -5..5, z = the wall z - 1);
  2. the block family movement vs the 510-513 baseline (94.4% band;
     the prediction: the calibrated families move +2..+15pp, the controls
     unchanged +-0.05pp);
  3. the per-family luminance ratio re-measure on the ae2/create pairs
     (the prediction: the ratio -> ~1.00 +- the AO residual).

Usage: arbiter_514_read.py <run-id>
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
TOKEN = open(os.path.expanduser(
    '/home/z/my-project/work/c-imagine/.secrets/gh_token')).read().strip()
REPO = 'PLANETA9091/c-imagine'
W, H = 1280, 720
F = 514.1
CAM_X = 0.5
CAM_Z = 0.5
EYE_Y = -58.38
HALF = 20

ROOF = re.compile(
    r'f2v gallery: page (\S+) calib-roof ns=(\S+) rows=(\d+) placed=(\d+)'
    r' cleared=(\d+) x=(-?\d+)\.\.(-?\d+) z=(-?\d+)')
BLOCK_BASE = {'ae2': 96.852, 'biomesoplenty': 86.352, 'create': 96.650,
              'dynamictrees': 100.0, 'farmersdelight': 91.339,
              'mekanism': 95.211, 'minecraft': 94.656,
              'sophisticatedbackpacks': 96.094, 'supplementaries': 93.044}


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
    meta = fetch_artifact(ids['wall-ours-meta-0'], f'/tmp/a514-meta-{RUN}.zip')
    tz = fetch_artifact(ids['wall-truth-shots-0'], f'/tmp/a514-t-{RUN}.zip')
    oz = fetch_artifact(ids['wall-ours-shots-0'], f'/tmp/a514-o-{RUN}.zip')
    vz = fetch_artifact(ids['gallery-verdict'], f'/tmp/a514-verdict-{RUN}.zip')
    with zipfile.ZipFile(meta) as z:
        boot = z.read('srv-wall/boot.log').decode('utf-8', 'replace')
    print('=== the calib-roof receipts ===')
    seen = {}
    for l in boot.splitlines():
        m = ROOF.search(l)
        if m:
            key = m.group(2)
            seen[key] = seen.get(key, 0) + 1
            if seen[key] <= 2:
                print('  page %s ns=%s rows=%s placed=%s cleared=%s x=%s..%s z=%s'
                      % m.groups())
    for ns, n in sorted(seen.items()):
        print('  %-24s receipts %d' % (ns, n))
    with zipfile.ZipFile(vz) as z:
        summ = None
        for n in z.namelist():
            if n.endswith('class-summary.json'):
                summ = json.loads(z.read(n))
    if summ:
        print('\n=== the block family movement (vs the 510-513 baseline) ===')
        tot_g = tot_r = 0
        for r in summ:
            if r.get('class') != 'block':
                continue
            tot_g += r.get('green', 0)
            tot_r += r.get('red', 0)
            b = BLOCK_BASE.get(r['ns'])
            if b is not None:
                print('%-24s %8.3f%% (base %8.3f%%, delta %+7.3f)  red %d'
                      % (r['ns'], r.get('green_pct') or -1, b,
                         (r.get('green_pct') or 0) - b, r.get('red', 0)))
        print('BLOCK TOTAL: green %d red %d -> %.2f%%'
              % (tot_g, tot_r, 100 * tot_g / max(1, tot_g + tot_r)))
    # the per-family luminance ratio re-measure
    with zipfile.ZipFile(meta) as z:
        mans = {n.split('/')[-1]: n for n in z.namelist()
                if n.endswith('.tsv') and '.ctx' not in n
                and 'index' not in n and 'uncovered' not in n}
    print('\n=== the per-family luminance ratio re-measure ===')
    for fam in ('ae2', 'create', 'mekanism', 'supplementaries',
                'farmersdelight', 'sophisticatedbackpacks',
                'biomesoplenty', 'dynamictrees'):
        mf = mans.get(f'gallery-{fam}-p0.tsv')
        tkey = f'gal-p-{fam}0.png'
        if mf is None or tkey not in zipfile.ZipFile(tz).namelist():
            continue
        with zipfile.ZipFile(meta) as z:
            man = z.read(mf).decode()
        with zipfile.ZipFile(tz) as z:
            t = np.asarray(Image.open(io.BytesIO(
                z.read(tkey))).convert('RGB')).astype(np.float32)
        with zipfile.ZipFile(oz) as z:
            nm = [n for n in z.namelist()
                  if n.endswith('ours-' + tkey)][0]
            o = np.asarray(Image.open(io.BytesIO(
                z.read(nm))).convert('RGB')).astype(np.float32)
        rs = []
        for r in man.splitlines()[1:]:
            p = r.split('\t')
            if p[0] != 'cell':
                continue
            cx, cy = int(p[1]), int(p[2])
            ix, iy = map(int, np.round(proj(cx, cy, 9.0)))
            tc = t[iy - HALF:iy + HALF, ix - HALF:ix + HALF]
            oc = o[iy - HALF:iy + HALF, ix - HALF:ix + HALF]
            if tc.shape[0] < 2 * HALF or tc.shape[1] < 2 * HALF:
                continue
            rs.append(tc.mean() / max(1e-6, oc.mean()))
        if rs:
            print('%-24s cells %3d  ratio mean %.3f median %.3f'
                  ' p10 %.3f p90 %.3f'
                  % (fam, len(rs), np.mean(rs), np.median(rs),
                     np.percentile(rs, 10), np.percentile(rs, 90)))


if __name__ == '__main__':
    main()
