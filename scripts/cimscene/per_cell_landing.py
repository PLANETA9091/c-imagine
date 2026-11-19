#!/usr/bin/env python3
"""14-e169 THE PER-CELL RUNG LANDING READ (the R-a rung's validation
instrument).

Reads the per-cell measured calibration file (scripts/f2v/
visual-tints-cells.tsv, the keys = state@page:c,col,row) and the TWO runs'
join artifacts (the baseline = the 547's reports, the landing = the e169
arbiter's reports), then reports per keyed cell:
  - the baseline verdict (RED by construction — the calibrator only solved
    the RED rows);
  - the landing verdict (GREEN = the per-cell measured multiply landed the
    cell; RED = the measured ratio did not close it; ANIMATED/skipped =
    the honest other classes);
  - the keyed cells' mass walk (the sum of the keyed cells' ae).

Usage: per_cell_landing.py <baseline-dir> <landing-dir> <tints-cells.tsv>
"""
import json
import glob
import os
import sys
from collections import Counter


def cells_map(root):
    """(page, col, row) -> (state, verdict, ae) from the join rows."""
    out = {}
    for rep in sorted(glob.glob(root + '/gallery-report-*')):
        if rep.endswith('.zip'):
            continue
        for jf in glob.glob(rep + '/join-*.json'):
            b = os.path.basename(jf)
            # the calibrator's crop_pairs reads ALL joins (the item rows too)
            # — the same scope here, only the menu/e lanes excluded
            if any(x in b for x in ('-menu-', '-e-')):
                continue
            try:
                d = json.load(open(jf))
            except Exception:
                continue
            for r in d.get('rows', []):
                page = r.get('page')
                col = r.get('col')
                row = r.get('row')
                if page is None or col is None or row is None:
                    continue
                out[(page, col, row)] = (
                    r.get('state', '?'), r.get('verdict', '?'), r.get('ae') or 0)
    return out


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    base = cells_map(sys.argv[1])
    land = cells_map(sys.argv[2])
    keyed = []
    for line in open(sys.argv[3]):
        if line.startswith('#') or not line.strip():
            continue
        k = line.split('\t')[0].strip()
        state, _, rest = k.partition('@')
        page, _, cell = rest.partition(':')
        c, _, r = cell.partition(',')
        keyed.append((k, state, page, int(c[1:]), int(r[1:])))
    res = Counter()
    ae_before = ae_after = 0
    missing = 0
    for k, state, page, col, row in keyed:
        key = (page, col, row)
        b = base.get(key)
        l = land.get(key)
        if b is None or l is None:
            missing += 1
            continue
        res[f"{b[1]}->{l[1]}"] += 1
        ae_before += b[2]
        ae_after += l[2]
    print(f"keyed cells: {len(keyed)}  unreadable in the artifacts: {missing}")
    print("verdict transitions:")
    for k, v in sorted(res.items(), key=lambda kv: -kv[1]):
        print(f"  {v:>4}  {k}")
    print(f"keyed cells' ae mass: {ae_before} -> {ae_after} "
          f"({ae_before - ae_after:+d})")
    # the keyed states' totals
    states = Counter(k.split('@')[0].split(':')[0] for k, *_ in keyed)
    print("keyed cells per ns:", dict(states.most_common()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
