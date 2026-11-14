#!/usr/bin/env python3
"""THE VERDICT DELTA (the 14-e8 measurement instrument, report-only).

Compares two gallery runs' verdict summaries and answers the only question
a fix run must answer: WHICH reds moved. The 14-e8 chunk poke predicted a
specific shape — the DARK-class pages (the ours-leg lightmap starvation,
the ~2000-row mass on the 37 BLACKWALL pages) collapse while the CONTENT
classes (the hourglass/glowshroom tint lane, the geometry residuals) stay —
and this script turns that prediction into a measured number instead of a
vibe:

  - per-shard gate reds: total, by namespace, by family
  - the red-set diff: identical / only-in-old / only-in-new (Jaccard)
  - per-family deltas sorted by |delta| (the collapse vs the stubborn)

Usage:
  verdict-delta.py <old-verdict.json> <new-verdict.json> [--top N]

  each path = a pulled gallery-verdict/verdict-summary.json (verdict-pull.py
  --kinds verdict). Exit code 0 always: report-only, never gates.

The red rows are the gate's per-shard "reds" lists ("ns:state[props] ae=N");
the cell key = (shard, state-string) — the ae value rides the comparison so
a bit-identical reproduce (the 14-e8 batch->e7 result: 391/397 identical)
reads differently from a genuine collapse.
"""
import argparse
import collections
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as fh:
        d = json.load(fh)
    cells = {}
    for shard, sv in d.get("shards", {}).items():
        for row in sv.get("reds", []):
            if " ae=" in row:
                state, ae = row.rsplit(" ae=", 1)
            else:
                state, ae = row, "0"
            cells[(shard, state)] = int(ae)
    return cells


def family(state):
    return state.split(" ")[0].split("[")[0]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("--top", type=int, default=24)
    args = ap.parse_args(argv)

    old = load(args.old)
    new = load(args.new)
    print(f"old {args.old}: {len(old)} red cells")
    print(f"new {args.new}: {len(new)} red cells")

    keys = set(old) | set(new)
    same = sum(1 for k in keys if k in old and k in new and old[k] == new[k])
    only_old = [k for k in keys if k in old and k not in new]
    only_new = [k for k in keys if k not in old and k in new]
    union = len(set(old) | set(new))
    inter = len(set(old) & set(new))
    jac = (inter / union) if union else 1.0
    print(f"identical (state+ae): {same}  only-old: {len(only_old)}  "
          f"only-new: {len(only_new)}  jaccard: {jac:.4f}")

    by_fam_old = collections.Counter(family(s) for _, s in old)
    by_fam_new = collections.Counter(family(s) for _, s in new)
    rows = []
    for fam in sorted(set(by_fam_old) | set(by_fam_new)):
        o, n = by_fam_old.get(fam, 0), by_fam_new.get(fam, 0)
        rows.append((o - n, o, n, fam))
    rows.sort(key=lambda r: -abs(r[0]))
    print("\nfamily deltas (collapse -> stubborn), top "
          f"{args.top} of {len(rows)}:")
    for delta, o, n, fam in rows[:args.top]:
        mark = "COLLAPSE" if delta > 0 and n == 0 else (
            "GROWTH" if delta < 0 and o == 0 else "delta")
        print(f"  {fam:52s} {o:5d} -> {n:5d}  ({delta:+5d})  {mark}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
