#!/usr/bin/env python3
"""THE ISO CENSUS (14-e32) — the isolation-lane arbiter read.

Compares the ae2 ISO lane's per-cell verdicts (diag-iso-ae2-p*.json, the
pitch-2 moat wall) against the block lane's (join-ae2-p*.json, pitch 1) for
the SAME states:

  LEAK-CLEARED  RED at pitch 1, GREEN in the moat  — the neighbour leak was
                the mechanism; the moat fixed the measurement
  TRUE-DEFECT   RED at both pitches                — the state itself renders
                differently between the stacks (the real fidelity work)
  GREEN-BOTH    — the state was always fine
  INCOMPLETE    — at least one lane has no explicit RED/GREEN observation;
                missing or skipped evidence does not prove parity
  ISO-ONLY-RED  GREEN at pitch 1, RED in the moat  — the moat changed the
                render (the neighbour was PART of the expected picture —
                e.g. a wall whose arms need neighbours; the moat wall shows
                the bare post) — the moat's own contract caveat

Report-only: names the classes, never flips a gate.

Usage: iso-census.py <report-dir> [<report-dir> ...] [--ns ae2] [--top N]
"""
import argparse, collections, glob, json, os, re, sys


def load_states(dirs, pattern):
    """state -> {'red': n, 'green': n} aggregated over the matching files."""
    out = collections.defaultdict(lambda: collections.Counter())
    for d in dirs:
        for f in glob.glob(os.path.join(d, pattern)):
            try:
                data = json.load(open(f))
            except Exception:
                continue
            for r in data.get("rows", []):
                st = str(r.get("state", ""))
                if not st:
                    continue
                out[st][str(r.get("verdict", "?")).lower()] += 1
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--ns", default="ae2")
    ap.add_argument("--top", type=int, default=20)
    args = ap.parse_args()

    block = load_states(args.dirs, f"join-{args.ns}-p*.json")
    iso = load_states(args.dirs, f"diag-iso-{args.ns}-p*.json")
    if not iso:
        print(f"NO ISO DATA (no diag-iso-{args.ns}-p*.json in the given dirs)")
        return 1

    classes = collections.defaultdict(list)
    for st in sorted(set(block) | set(iso)):
        b = block.get(st, collections.Counter())
        i = iso.get(st, collections.Counter())
        b_red = b["red"] > 0
        i_red = i["red"] > 0
        if not (b["red"] + b["green"]) or not (i["red"] + i["green"]):
            classes["INCOMPLETE"].append((st,))
        elif b_red and not i_red and i["green"] > 0:
            classes["LEAK-CLEARED"].append((st, b["red"]))
        elif b_red and i_red:
            classes["TRUE-DEFECT"].append((st, b["red"], i["red"]))
        elif not b_red and i_red:
            classes["ISO-ONLY-RED"].append((st, b["green"], i["red"]))
        elif b["green"] > 0 and i["green"] > 0:
            classes["GREEN-BOTH"].append((st,))

    print(f"states compared: {len(block)} (block) / {len(iso)} (iso)")
    for k in ("LEAK-CLEARED", "TRUE-DEFECT", "ISO-ONLY-RED", "INCOMPLETE"):
        rows = classes.get(k, [])
        print(f"\n{k}: {len(rows)}")
        for row in rows[: args.top]:
            print("  ", *row)
    gb = classes.get("GREEN-BOTH", [])
    print(f"\nGREEN-BOTH: {len(gb)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
