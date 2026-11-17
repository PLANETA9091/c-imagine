#!/usr/bin/env python3
"""The 512-arbiter read (the 14-e91q item/fluid A/B verification).

One command over a run id:
  1. the ITEM receipts' geometry: 'f2v gallery-item: page N cell ... at= +
     tr=[...]' — the 14-e91q contract: at = z+0.5, tr z = -1.04, net = z-0.54;
  2. the FLUID receipts' geometry: the same contract (the q = the
     rotationY(PI) 0.7071 for the mod-tank/block channels);
  3. the item join green-rate movement vs the 510/511 baseline
     (ae2 3.939, biomesoplenty 9.112, create 6.475, farmersdelight 3.261,
     mekanism 3.143, supplementaries 2.632 — the noise floor = +-0.05pp);
  4. the fluid join movement (mekanism-p0 was 15/18 red);
  5. the block families = the stability check (must hold the 94.4% band).

Usage: arbiter_512_read.py <run-id>
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

ITEM_BASE = {'ae2': 12.987, 'biomesoplenty': 10.390, 'create': 3.896,
             'farmersdelight': 1.299, 'mekanism': 10.390,
             'supplementaries': 5.195}  # the join-formula green, the 510/511
BLOCK_BASE = {'ae2': 96.852, 'biomesoplenty': 86.352, 'create': 96.650,
              'dynamictrees': 100.0, 'farmersdelight': 91.339,
              'mekanism': 95.211, 'minecraft': 94.656,
              'sophisticatedbackpacks': 96.094, 'supplementaries': 93.044}
ITEM = re.compile(
    r'f2v gallery-item: page (\S+) cell (-?\d+),(-?\d+),(-?\d+) item=(\S+)'
    r'.*? at=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)'
    r'(?: tr=\[([^;]*); q=([^;]*); s=([^\]]*)\])?')
FLUID = re.compile(
    r'f2v gallery-fluid: page (\S+) cell (-?\d+),(-?\d+),(-?\d+) fluid=(\S+)'
    r'.*? at=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+)'
    r'(?: tr=\[([^;]*); q=([^;]*); s=([^\]]*)\])?')


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


def receipt_check(lines, rx, label):
    n = ok = bad = 0
    examples = []
    for l in lines:
        m = rx.search(l)
        if not m or not m.group(8):
            continue
        n += 1
        x, y, z = int(m.group(2)), int(m.group(3)), int(m.group(4))
        atz = float(m.group(7))
        trz = float(m.group(9).split(',')[2])
        good = (abs(atz - (z + 0.5)) < 0.01 and abs(trz + 1.04) < 0.01)
        if good:
            ok += 1
        else:
            bad += 1
            if len(examples) < 4:
                examples.append((x, y, z, atz, trz))
    print('%s receipts: %d with geometry, ok=%d bad=%d' % (label, n, ok, bad))
    for e in examples:
        print('   BAD cell %s at_z=%.2f tr_z=%.2f' % (e[:3], e[3], e[4]))
    return n, ok


def main():
    arts = json.loads(api(
        f'https://api.github.com/repos/{REPO}/actions/runs/{RUN}/artifacts?per_page=100'))
    ids = {a['name']: a['id'] for a in arts.get('artifacts', [])}
    meta = fetch_artifact(ids['wall-ours-meta-0'], f'/tmp/a512-meta-{RUN}.zip')
    vz = fetch_artifact(ids['gallery-verdict'], f'/tmp/a512-verdict-{RUN}.zip')
    rz = fetch_artifact(ids.get('gallery-report-0'), f'/tmp/a512-report-{RUN}.zip')
    with zipfile.ZipFile(meta) as z:
        boot = z.read('srv-wall/boot.log').decode('utf-8', 'replace')
    lines = boot.splitlines()
    print('=== the 14-e91q content-lane receipts (the net = z-0.54) ===')
    receipt_check(lines, ITEM, 'ITEM')
    receipt_check(lines, FLUID, 'FLUID')
    if rz:
        print('\n=== the item join movement (vs the 510/511 baseline) ===')
        with zipfile.ZipFile(rz) as z:
            for n in sorted(z.namelist()):
                if not n.startswith('join-item-') or not n.endswith('.json'):
                    continue
                base = n[10:-5]
                try:
                    j = json.loads(z.read(n))
                except Exception:
                    continue
                cells = j.get('cells', 0)
                red = j.get('red', 0)
                green = (cells - red - j.get('dynamic', 0)
                         - j.get('skipped', 0) - j.get('family_demoted', 0)
                         - j.get('dissolved_race', 0))
                g = 100.0 * green / max(1, green + red)
                ns = base.split('-')[0] if '-' in base else base
                b = ITEM_BASE.get(ns)
                if g is not None and b is not None:
                    print('%-34s green %8.3f%% (base %8.3f%%, delta %+8.3f)  '
                          'red %d/%d' % (base, g, b, g - b, red, cells))
        print('\n=== the fluid join movement ===')
        with zipfile.ZipFile(rz) as z:
            for n in sorted(z.namelist()):
                if not n.startswith('join-fluid-') or not n.endswith('.json'):
                    continue
                base = n[11:-5]
                try:
                    j = json.loads(z.read(n))
                except Exception:
                    continue
                print('%-34s red %d/%d cells  verdict %s'
                      % (base, j.get('red', 0), j.get('cells', 0),
                         j.get('verdict')))
    with zipfile.ZipFile(vz) as z:
        summ = None
        for n in z.namelist():
            if n.endswith('class-summary.json'):
                summ = json.loads(z.read(n))
    if summ:
        print('\n=== the block stability (must hold the 94.4% band) ===')
        tot_g = tot_r = 0
        for r in summ:
            if r.get('class') != 'block':
                continue
            tot_g += r.get('green', 0)
            tot_r += r.get('red', 0)
            b = BLOCK_BASE.get(r['ns'])
            if b is not None:
                print('%-24s %8.3f%% (base %8.3f%%, delta %+7.3f)'
                      % (r['ns'], r.get('green_pct') or -1, b,
                         (r.get('green_pct') or 0) - b))
        print('BLOCK TOTAL: green %d red %d -> %.2f%%'
              % (tot_g, tot_r, 100 * tot_g / max(1, tot_g + tot_r)))


if __name__ == '__main__':
    main()
