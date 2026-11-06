#!/usr/bin/env python3
"""Compare complete Criterion 0.5 median estimates; never infer a pass from absent data.

Runner noise (measured: run 42280303622 flagged ring_roundtrip_4k +13.076% with
zero changes to the ring since the initial import; local re-runs reproduce the
baseline stably on a quiet machine and inflate the same bench 50-76% under CPU
contention): the bench suite is measured N times and every comparison uses the
per-benchmark MINIMUM median across runs — on BOTH sides of the gate.
Deterministic code can only lose time to runner contention, so best-of-N
estimates the code's noise floor; a true regression shifts every run and still
fails the unchanged 10% limit.
"""
import argparse
import json
import math
from pathlib import Path
import shutil
import sys


def estimates(root):
    result = {}
    for path in sorted(root.glob("**/new/estimates.json")):
        name = path.parent.parent.relative_to(root).as_posix()
        value = json.loads(path.read_text())["median"]["point_estimate"]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ValueError(f"invalid median: {name}: {value}")
        result[name] = value
    if not result:
        raise ValueError(f"no Criterion estimates in {root}")
    return result


def as_roots(paths):
    roots = list(paths) if isinstance(paths, (list, tuple)) else [paths]
    if not roots:
        raise ValueError("no Criterion runs given")
    return roots


def best_of(roots):
    """Per-benchmark minimum median across runs, plus the run holding it."""
    runs = [estimates(root) for root in as_roots(roots)]
    keys = runs[0].keys()
    for index, run in enumerate(runs[1:], start=2):
        if run.keys() != keys:
            raise ValueError(f"run {index} coverage changed: missing={sorted(keys - run.keys())}, "
                             f"extra={sorted(run.keys() - keys)}")
    best, argmin = {}, {}
    for name in keys:
        index = min(range(len(runs)), key=lambda i: runs[i][name])
        best[name] = runs[index][name]
        argmin[name] = index
    return best, argmin


def compare(baseline, current):
    before = estimates(baseline)
    after, _ = best_of(current)
    if before.keys() != after.keys():
        raise ValueError(f"benchmark coverage changed: missing={sorted(before.keys() - after.keys())}, "
                         f"without_baseline={sorted(after.keys() - before.keys())}")
    return [{"benchmark": name, "baseline_ns": before[name], "current_ns": after[name],
             "delta_pct": (after[name] / before[name] - 1) * 100,
             "pass": after[name] <= before[name] * 1.10} for name in sorted(before)]


def snapshot(current, baseline, source_sha):
    roots = as_roots(current)
    best, argmin = best_of(roots)  # rejects empty/malformed/uneven data before mutating
    if baseline.exists():
        shutil.rmtree(baseline)
    for name, index in argmin.items():
        target = baseline / name / "new" / "estimates.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        # The argmin run already carries the minimum as its median — copy as is.
        shutil.copyfile(roots[index] / name / "new" / "estimates.json", target)
    (baseline / "provenance.json").write_text(json.dumps(
        {"source_sha": source_sha, "runs": len(roots), "aggregate": "min"}, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("compare", "snapshot"))
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True, action="append",
                        help="a Criterion tree; repeat 3x for best-of-N aggregation")
    parser.add_argument("--report", type=Path)
    parser.add_argument("--source-sha")
    args = parser.parse_args()
    try:
        if args.mode == "snapshot":
            snapshot(args.current, args.baseline, args.source_sha)
            return 0
        rows = compare(args.baseline, args.current)
        report = {"limit_pct": 10, "aggregate": "min", "runs": len(as_roots(args.current)),
                  "baseline_provenance": json.loads((args.baseline / "provenance.json").read_text()),
                  "current_sha": args.source_sha, "benchmarks": rows}
        if args.report:
            args.report.write_text(json.dumps(report, indent=2) + "\n")
        print("| benchmark | baseline ns | current ns | delta | verdict |")
        print("|---|---:|---:|---:|---|")
        for row in rows:
            print(f"| {row['benchmark']} | {row['baseline_ns']:.3f} | {row['current_ns']:.3f} | "
                  f"{row['delta_pct']:+.3f}% | {'PASS' if row['pass'] else 'FAIL'} |")
        return 0 if all(row["pass"] for row in rows) else 1
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"criterion gate FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
