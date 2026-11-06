#!/usr/bin/env python3
"""Compare complete Criterion 0.5 median estimates; never infer a pass from absent data."""
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


def compare(baseline, current):
    before, after = estimates(baseline), estimates(current)
    if before.keys() != after.keys():
        raise ValueError(f"benchmark coverage changed: missing={sorted(before.keys() - after.keys())}, "
                         f"without_baseline={sorted(after.keys() - before.keys())}")
    return [{"benchmark": name, "baseline_ns": before[name], "current_ns": after[name],
             "delta_pct": (after[name] / before[name] - 1) * 100,
             "pass": after[name] <= before[name] * 1.10} for name in sorted(before)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("compare", "snapshot"))
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--source-sha")
    args = parser.parse_args()
    try:
        if args.mode == "snapshot":
            estimates(args.current)  # reject empty/malformed data before mutating
            if args.baseline.exists():
                shutil.rmtree(args.baseline)
            for path in args.current.glob("**/new/estimates.json"):
                target = args.baseline / path.relative_to(args.current)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
            (args.baseline / "provenance.json").write_text(json.dumps({"source_sha": args.source_sha}, indent=2) + "\n")
            return 0
        rows = compare(args.baseline, args.current)
        report = {"limit_pct": 10, "baseline_provenance": json.loads((args.baseline / "provenance.json").read_text()),
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
