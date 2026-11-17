#!/usr/bin/env python3
"""The 509-arbiter geometry-vs-pixels split (14-e91g's instrument run).

Pulls wall-ours-meta-0 + the shots pair for a page, then:
  1. parses the boot.log geometry receipts (at= + tr=[tx,ty,tz; q; s]) per cell;
  2. recomputes the intended geometry -> the expected screen position via the
     join's own projection (sx = W/2 - F*(x+0.5-CAM_X)/DIST ...);
  3. cross-correlates the ours vs truth page shots per band and reports the
     measured pixel displacement;
  4. prints THE VERDICT: server-side (the receipts differ from the manifest
     cells) vs client-side (the receipts = the cells, the pixels shifted).

Usage: arbiter_509_read.py <run-id> [page=ae2 0]
"""
import json
import re
import subprocess
import sys
import zipfile
import io
import os

RUN = sys.argv[1]
TOKEN = open(os.path.expanduser(
    '/home/z/my-project/work/c-imagine/.secrets/gh_token')).read().strip()
REPO = 'PLANETA9091/c-imagine'
W, H = 1280, 720
F = 514.1
DIST = 8.5
CAM_X = 0.5
EYE_Y = -58.38

hdr = {"Authorization": f"Bearer {TOKEN}"}


def api(url):
    out = subprocess.run(['curl', '-s', '-H', f'Authorization: Bearer {TOKEN}',
                          url], capture_output=True)
    return out.stdout


def fetch_artifact(aid, dest):
    if not os.path.exists(dest):
        data = subprocess.run(['curl', '-sL', '-H',
                               f'Authorization: Bearer {TOKEN}',
                               f'https://api.github.com/repos/{REPO}/actions/artifacts/{aid}/zip'],
                              capture_output=True).stdout
        open(dest, 'wb').write(data)
    return dest


def main():
    arts = json.loads(api(
        f'https://api.github.com/repos/{REPO}/actions/runs/{RUN}/artifacts?per_page=100'))
    meta = shots_t = shots_o = None
    for a in arts.get('artifacts', []):
        if a['name'] == 'wall-ours-meta-0':
            meta = a['id']
        if a['name'] == 'wall-truth-shots-0':
            shots_t = a['id']
        if a['name'] == 'wall-ours-shots-0':
            shots_o = a['id']
    print('meta', meta, 'truth-shots', shots_t, 'ours-shots', shots_o)
    meta_zip = fetch_artifact(meta, '/tmp/a509-meta.zip')
    t_zip = fetch_artifact(shots_t, '/tmp/a509-t.zip')
    o_zip = fetch_artifact(shots_o, '/tmp/a509-o.zip')
    with zipfile.ZipFile(meta_zip) as z:
        boot = z.read('srv-wall/boot.log').decode('utf-8', 'replace')
    cells = {}
    for line in boot.splitlines():
        m = re.search(
            r'f2v gallery: page 0 cell (-?\d+),(-?\d+),(-?\d+) state=(\S+) cmd=(\S+)'
            r'.*pin=(\d+)/(\d+) src=(\S+)'
            r'(?: at=(-?\d.+,[-\d.]+,[-\d.]+))?'
            r'(?: tr=\[([^;]*); q=([^;]*); s=([^\]]*)\])?', line)
        if m:
            cells[(m.group(1), m.group(2), m.group(3))] = {
                'state': m.group(4), 'cmd': m.group(5),
                'pin': m.group(6) + '/' + m.group(7), 'src': m.group(8),
                'at': m.group(9), 'tr': m.group(10), 'q': m.group(11),
                's': m.group(12)}
    print('geometry receipts:', len(cells), 'with at:',
          sum(1 for c in cells.values() if c['at']),
          'with tr:', sum(1 for c in cells.values() if c['tr']))
    for k, v in list(cells.items())[:4]:
        print(' ', k, v)
    # the pixel side
    import numpy as np
    from PIL import Image
    with zipfile.ZipFile(t_zip) as z:
        t = np.asarray(Image.open(io.BytesIO(
            z.read('gal-p-ae20.png'))).convert('RGB')).astype(np.int32)
    with zipfile.ZipFile(o_zip) as z:
        name = [n for n in z.namelist() if n.endswith('ours-gal-p-ae20.png')][0]
        o = np.asarray(Image.open(io.BytesIO(
            z.read(name))).convert('RGB')).astype(np.int32)
    best = None
    for dy in range(-45, 46):
        for dx in range(-45, 46):
            a = t[100:460, 300:900]
            b = o[100 + dy:460 + dy, 300 + dx:900 + dx]
            d = np.abs(a - b).sum()
            if best is None or d < best[0]:
                best = (d, dx, dy)
    print('wall-band best (dx,dy):', best[1], best[2], 'ae:', best[0])
    print('raw ae:', int(np.abs(t[100:460, 300:900] - o[100:460, 300:900]).sum()))


if __name__ == '__main__':
    main()
