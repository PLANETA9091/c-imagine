#!/usr/bin/env python3
"""The boot.log geometry-receipt verifier (14-e91g/e91f).

Reads srv-wall/boot.log 'f2v gallery: page N cell ...' lines carrying the
14-e91g geometry receipt (at= + tr=[tx,ty,tz; q=qx,qy,qz,qw; s=sx,sy,sz]),
joins them against the manifest cells (cell x y z state) and verifies:

  LAW 1 (front-air): for the src=front-air cells the spawned at = (x+0.5,
       y+0.5, z+0.5-1) and the translation = (GALLERY_TX, GALLERY_TY,
       GALLERY_TZ+1) — the geometry net = the cell plane (the trick = pixel
       neutral iff the translation carries the +1 back).
  LAW 2 (plane): for the non-front-air cells at = (x+0.5, y+0.5, z+0.5) and
       the translation = (GALLERY_TX, GALLERY_TY, GALLERY_TZ).
  LAW 3 (scale): s = (GALLERY_SCALE, GALLERY_SCALE, GALLERY_SCALE).

Usage: geom_receipt_check.py <boot.log> [--tx 0 --ty 0 --tz 0 --scale 8]
"""
import collections
import re
import sys

CELL = re.compile(
    r'f2v gallery: page (\S+) cell (-?\d+),(-?\d+),(-?\d+) state=(\S+)'
    r'.*?src=(\S+)'
    r'(?: at=(-?[\d.]+),(-?[\d.]+),(-?[\d.]+))?'
    r'(?: tr=\[(-?[\d.eE+]+),(-?[\d.eE+]+),(-?[\d.eE+]+); q=(-?[\d.eE+]+),(-?[\d.eE+]+),(-?[\d.eE+]+),(-?[\d.eE+]+); s=(-?[\d.eE+]+),(-?[\d.eE+]+),(-?[\d.eE+]+)\])')


def main():
    log = sys.argv[1]
    tx = ty = tz = 0.0
    scale = 8.0
    argv = sys.argv[2:]
    for i, a in enumerate(argv):
        if a == '--tx':
            tx = float(argv[i + 1])
        if a == '--ty':
            ty = float(argv[i + 1])
        if a == '--tz':
            tz = float(argv[i + 1])
        if a == '--scale':
            scale = float(argv[i + 1])
    per_page = collections.defaultdict(list)
    for line in open(log, errors='ignore'):
        m = CELL.search(line)
        if not m:
            continue
        page = m.group(1)
        per_page[page].append({
            'cell': (int(m.group(2)), int(m.group(3)), int(m.group(4))),
            'state': m.group(5), 'src': m.group(6),
            'at': tuple(float(m.group(k)) for k in (7, 8, 9)) if m.group(7) else None,
            't': tuple(float(m.group(k)) for k in (10, 11, 12)) if m.group(10) else None,
            'q': tuple(float(m.group(k)) for k in (13, 14, 15, 16)) if m.group(13) else None,
            's': tuple(float(m.group(k)) for k in (17, 18, 19)) if m.group(17) else None,
        })
    total = sum(len(v) for v in per_page.values())
    with_geom = sum(1 for v in per_page.values() for c in v if c['at'] and c['t'])
    print(f"pages: {len(per_page)}  cell receipts: {total}  with geometry: {with_geom}")
    ok = bad = 0
    examples = []
    for page, cells in per_page.items():
        for c in cells:
            if not c['at'] or not c['t']:
                continue
            x, y, z = c['cell']
            front = c['src'] == 'front-air'
            exp_at = (x + 0.5, y + 0.5, z + 0.5 - (1.0 if front else 0.0))
            exp_t = (tx, ty, tz + (1.0 if front else 0.0))
            good = (abs(c['at'][0] - exp_at[0]) < 0.01
                    and abs(c['at'][1] - exp_at[1]) < 0.01
                    and abs(c['at'][2] - exp_at[2]) < 0.01
                    and abs(c['t'][0] - exp_t[0]) < 0.01
                    and abs(c['t'][1] - exp_t[1]) < 0.01
                    and abs(c['t'][2] - exp_t[2]) < 0.01
                    and c['s'] == (scale, scale, scale))
            if good:
                ok += 1
            else:
                bad += 1
                if len(examples) < 8:
                    examples.append(
                        f"page {page} {c['cell']} {c['state'][:40]} src={c['src']}"
                        f" at={c['at']} t={c['t']} s={c['s']} exp_at={exp_at} exp_t={exp_t}")
    print(f"geometry laws: ok={ok} bad={bad}")
    for e in examples:
        print("  BAD", e)
    verdict = (bad == 0 and with_geom > 0)
    print("GEOMETRY RECEIPTS:", "PASS" if verdict else "FAIL")
    return 0 if verdict else 1


if __name__ == '__main__':
    sys.exit(main())
