#!/usr/bin/env python3
"""14-e156 THE AO RECEIPT READER (the 540 arbiter's provenance instrument).

Parses the ours-leg boot logs' per-cell receipts (the e155/e155b lines:
  ... cell x,y,z state=... pin=<scaled>/<scaled> pin0=<unscaled>/<unscaled> ao=<pct> ...
  ... PAGE-LANE ... pin=<scaled>/<scaled> pin0=<unscaled>/<unscaled> ao=<pct> ...)
and builds the census provenance table:
  - the ao= histogram (how many displays rode each census factor);
  - the pin0 -> pin scale map (the per-pin-pair scaled outcome);
  - the untagged share (ao=100 = the exact legacy render).

Usage: ao_receipt.py <bootlog> [morebootlogs...]
Exit contract: prints the table; nonzero if NO ao= receipts found (the
run predates the e155 plugin = the honest negative).
"""
import re
import sys
from collections import Counter

CELL_RE = re.compile(r" state=\S+ cmd=\S+ uuid=\S+ pin=(\d+)/(\d+) pin0=(\d+)/(\d+) ao=(\d+) ")
PAGE_RE = re.compile(r" PAGE-LANE \S+ cmd=\S+ anchor=\S+ cells=\d+ elements=\d+ pin=(\d+)/(\d+) pin0=(\d+)/(\d+) ao=(\d+) ")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cells = []       # (pin_b, pin_s, pin0_b, pin0_s, ao)
    pages = []
    for path in sys.argv[1:]:
        try:
            with open(path, "r", errors="replace") as f:
                for line in f:
                    m = CELL_RE.search(line)
                    if m:
                        cells.append(tuple(int(x) for x in m.groups()))
                        continue
                    m = PAGE_RE.search(line)
                    if m:
                        pages.append(tuple(int(x) for x in m.groups()))
        except OSError as e:
            print(f"WARN: {path}: {e}")
    if not cells and not pages:
        print("NO AO RECEIPTS FOUND (the run predates the e155 plugin — honest negative)")
        return 1
    print("== e155/e155b AO CENSUS RECEIPTS ==")
    for name, rows in (("per-cell", cells), ("page-lane", pages)):
        if not rows:
            print(f"{name}: 0 receipts")
            continue
        ao_hist = Counter(r[4] for r in rows)
        total = len(rows)
        untagged = ao_hist.get(100, 0)
        print(f"{name}: {total} display(s)")
        print("  ao histogram: " + " ".join(f"{k}:{v}" for k, v in sorted(ao_hist.items())))
        print(f"  untagged (ao=100, exact legacy render): {untagged}/{total}"
              f" = {100.0 * untagged / total:.1f}%")
        scaled = sorted({(r[0], r[1], r[2], r[3]) for r in rows})
        shown = 0
        for pb, ps, b0, s0 in scaled:
            if shown >= 12:
                print(f"  ... and {len(scaled) - shown} more distinct pin pairs")
                break
            print(f"  pin0 {b0}/{s0} -> pin {pb}/{ps}")
            shown += 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
