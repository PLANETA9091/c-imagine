#!/usr/bin/env python3
"""Compare complete Criterion 0.5 median estimates; never infer a pass from absent data.

The CURRENT measurement may ride multiple LEGS (repeatable --current): a
benchmark's value is the MINIMUM median across the legs. Runner noise is
one-sided — interference only ever slows a leg, and it does not reproduce —
while a real regression does. The min is the measurement of the sha; any
single slow leg is noise. THE ring_roundtrip_4k LESSON (run 42312707696):
one +13.076% leg red-gated a benchmark three measured legs proved flat.
"""
import argparse
import json
import math
from pathlib import Path
import shutil
import sys


def estimate_paths(root):
    """benchmark name -> estimates.json path, one level per benchmark dir."""
    return {p.parent.parent.relative_to(root).as_posix(): p
            for p in sorted(root.glob("**/estimates.json"))
            if p.parent.name == "new"}


def estimates(root):
    result = {}
    for name, path in estimate_paths(root).items():
        value = json.loads(path.read_text())["median"]["point_estimate"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"invalid median: {name}: {value}")
        result[name] = value
    if not result:
        raise ValueError(f"no Criterion estimates in {root}")
    return result


def _legs(currents):
    if isinstance(currents, (list, tuple)):
        legs = list(currents)
    else:
        legs = [currents]
    if not legs:
        raise ValueError("no current legs given")
    return legs


def best_legs(currents):
    """Fold the current legs: per benchmark the minimum median wins.

    Returns ({name: (value, root, relpath)}, leg count). Leg coverage must
    agree — legs that measure different benchmark sets are not comparable
    and say nothing about noise.
    """
    legs = _legs(currents)
    tables = [(estimates(c), estimate_paths(c), c) for c in legs]
    keys = tables[0][0].keys()
    for est, _, _ in tables[1:]:
        if est.keys() != keys:
            raise ValueError(f"leg coverage changed: {sorted(set(keys) ^ set(est.keys()))}")
    best = {}
    for name in sorted(keys):
        best[name] = min(((est[name], paths[name], root)
                          for est, paths, root in tables), key=lambda t: t[0])
    return best, len(tables)


def compare(baseline, currents):
    before = estimates(baseline)
    best, n_legs = best_legs(currents)
    if before.keys() != best.keys():
        raise ValueError(f"benchmark coverage changed: missing={sorted(before.keys() - best.keys())}, "
                         f"without_baseline={sorted(best.keys() - before.keys())}")
    return [{"benchmark": name, "baseline_ns": before[name], "current_ns": best[name][0],
             "legs": n_legs,
             "delta_pct": (best[name][0] / before[name] - 1) * 100,
             "pass": best[name][0] <= before[name] * 1.10} for name in sorted(before)]


def snapshot(currents, baseline, source_sha):
    """Rebuild the baseline dir from the legs, each benchmark from its min leg."""
    best, n_legs = best_legs(currents)  # rejects empty/malformed legs before mutating
    if baseline.exists():
        shutil.rmtree(baseline)
    for name, (_, path, root) in sorted(best.items()):
        target = baseline / path.relative_to(root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    (baseline / "provenance.json").write_text(
        json.dumps({"source_sha": source_sha, "legs": n_legs}, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("compare", "snapshot"))
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, action="append", required=True,
                        help="a measured leg; repeat for best-of-N (the min median wins)")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--source-sha")
    args = parser.parse_args()
    try:
        if args.mode == "snapshot":
            snapshot(args.current, args.baseline, args.source_sha)
            return 0
        rows = compare(args.baseline, args.current)
        report = {"limit_pct": 10, "baseline_provenance": json.loads((args.baseline / "provenance.json").read_text()),
                  "current_sha": args.source_sha, "benchmarks": rows}
        if args.report:
            args.report.write_text(json.dumps(report, indent=2) + "\n")
        print("| benchmark | baseline ns | current ns | delta | legs | verdict |")
        print("|---|---:|---:|---:|---:|---|")
        for row in rows:
            print(f"| {row['benchmark']} | {row['baseline_ns']:.3f} | {row['current_ns']:.3f} | "
                  f"{row['delta_pct']:+.3f}% | {row['legs']} | {'PASS' if row['pass'] else 'FAIL'} |")
        return 0 if all(row["pass"] for row in rows) else 1
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"criterion gate FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
