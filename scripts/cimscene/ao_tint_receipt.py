#!/usr/bin/env python3
"""14-e161 THE AO-TINT RECEIPT READER (the tint-lane pack bake's provenance
instrument — the ao_receipt.py twin for the PACK side).

Parses the ours-leg boot logs' e161 bake receipts (the F2vPack build's
warnings, logged as:
  ... f2v pack: warn tint-bake applied: ao-tint <state> f=<factor>
) and builds the census:
  - the receipt count and the DISTINCT states;
  - the f= histogram (the per-state census factors the build applied);
  - the states grouped by factor class (the interior 78-class / the edge
    82-class / the corner 88-class / the freestanding 100 = never receipted).

Usage: ao_tint_receipt.py <bootlog> [<bootlog>...]
Exit contract: prints the table; nonzero if NO ao-tint receipts found (the
run predates the e161 plugin = the honest negative).
"""
import re
import sys
from collections import Counter

AO_TINT_RE = re.compile(r"tint-bake applied: ao-tint (\S+) f=([0-9.]+)")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    rows = []  # (state, factor)
    for path in sys.argv[1:]:
        try:
            with open(path, "r", errors="replace") as f:
                for line in f:
                    m = AO_TINT_RE.search(line)
                    if m:
                        rows.append((m.group(1), float(m.group(2))))
        except OSError as e:
            print(f"WARN: {path}: {e}")
    if not rows:
        print("NO AO-TINT RECEIPTS FOUND (the run predates the e161 plugin — honest negative)")
        return 1
    print("== e161 AO-TINT CENSUS RECEIPTS ==")
    states = {s for s, _ in rows}
    fhist = Counter(f for _, f in rows)
    print(f"receipts: {len(rows)}  distinct states: {len(states)}")
    print("  f histogram: " + " ".join(f"{k:.4f}:{v}" for k, v in sorted(fhist.items())))
    # the factor classes (the e159 v2 face-mean anchors, x100 quantized)
    classes = Counter()
    for f in fhist:
        pct = round(f * 100)
        if pct >= 99:
            classes["freestanding(100)"] += fhist[f]
        elif 87 <= pct <= 89:
            classes["corner(88)"] += fhist[f]
        elif 81 <= pct <= 83:
            classes["edge(82)"] += fhist[f]
        elif 77 <= pct <= 79:
            classes["interior(78)"] += fhist[f]
        else:
            classes[f"other({pct})"] += fhist[f]
    print("  class spread: " + " ".join(f"{k}:{v}" for k, v in sorted(classes.items())))
    for s in sorted(states):
        fs = sorted({f for s2, f in rows if s2 == s})
        print(f"    {s[:100]}  f={'/'.join(f'{x:.4f}' for x in fs)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
