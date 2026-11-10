#!/usr/bin/env python3
"""Require real boot/RSS evidence before enforcing the existing Paper budgets.

THE SINGLE-SAMPLE RATIO WAS MEASURED UNSTABLE (profiling #e943e2e vs #dfe859bf,
2026-11-10): bare Paper booted 13.840s vs 11.295s (a 22% swing the c-imagine
merge cannot touch — the bare leg boots plain Paper) and the modded leg swung
the other way, so the modded/bare ratio crossed the 1.25 limit on runner noise
alone (0.879 green -> 1.284 red). The limits did not move; the measurement got
honest: each leg boots N times and the gate compares MEDIANS of the boot times
(robust to one noisy boot) and the WORST-CASE RSS delta (max of each leg's
per-boot maxima — conservative, never hides a leak). A single pair keeps the
legacy behavior (the median of one sample is the sample).

CLI (both forms):
  boot-budget.py BARE MODDED
  boot-budget.py --bare D1 --bare D2 --modded M1 --modded M2 [--modded M3 ...]
The --bare/--modded counts must be equal and >= 1.
"""
import argparse
import math
from pathlib import Path
import re
import statistics
import sys


def measure(root):
    match = re.search(r"Done \(([0-9.]+)s\)", (root / "boot.log").read_text())
    if not match:
        raise ValueError(f"missing successful boot marker: {root}")
    seconds = float(match[1])
    samples = [int(line.strip()) for line in (root / "rss.log").read_text().splitlines() if line.strip()]
    if not math.isfinite(seconds) or seconds <= 0 or not samples or min(samples) <= 0:
        raise ValueError(f"invalid boot/RSS measurements: {root}")
    return seconds, max(samples)


def compare(*legs):
    """legs: flat (bare_dir, modded_dir, bare_dir2, modded_dir2, ...) pairs."""
    if len(legs) < 2 or len(legs) % 2 != 0:
        raise ValueError(f"expected bare/modded dir pairs, got {len(legs)} arg(s)")
    bare, modded = legs[0::2], legs[1::2]
    bare_times = []
    bare_rss = []
    mod_times = []
    mod_rss = []
    for root in bare:
        seconds, rss = measure(root)
        bare_times.append(seconds)
        bare_rss.append(rss)
    for root in modded:
        seconds, rss = measure(root)
        mod_times.append(seconds)
        mod_rss.append(rss)
    return statistics.median(mod_times) / statistics.median(bare_times), (
        max(mod_rss) - max(bare_rss)) / 1024


def main():
    parser = argparse.ArgumentParser(description="boot-time + RSS budget gate")
    parser.add_argument("pairs", nargs="*", help="legacy two-arg form: BARE MODDED")
    parser.add_argument("--bare", action="append", default=[], help="bare boot dir (repeatable)")
    parser.add_argument("--modded", action="append", default=[], help="modded boot dir (repeatable)")
    args = parser.parse_args()
    try:
        if args.bare or args.modded:
            if args.pairs or len(args.bare) != len(args.modded) or not args.bare:
                raise ValueError("--bare/--modded must be given an equal nonzero number of times (and not mixed with legacy args)")
            legs = [d for pair in zip(args.bare, args.modded) for d in pair]
        else:
            if len(args.pairs) != 2:
                raise ValueError("expected BARE MODDED dirs (or --bare/--modded samples)")
            legs = args.pairs
        legs = [Path(d) for d in legs]
        for i, root in enumerate(legs[0::2]):
            b, m = measure(Path(root)), measure(Path(legs[1 + 2 * i]))
            print(f"sample {i + 1}: bare={b[0]:.3f}s/{b[1] // 1024:.3f}MiB modded={m[0]:.3f}s/{m[1] // 1024:.3f}MiB")
        ratio, rss = compare(*legs)
        ok = ratio <= 1.25 and rss <= 400
        print(f"boot ratio={ratio:.6f} (limit1.25); RSS delta={rss:.6f}MiB (limit400): {'PASS' if ok else 'FAIL'}")
        return 0 if ok else 1
    except (ValueError, OSError, IndexError) as error:
        print(f"boot budget FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
