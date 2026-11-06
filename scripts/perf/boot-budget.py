#!/usr/bin/env python3
"""Require real boot/RSS evidence before enforcing the existing Paper budgets."""
import math
from pathlib import Path
import re
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


def compare(bare, modded):
    a, ar = measure(bare)
    b, br = measure(modded)
    return b / a, (br - ar) / 1024


def main():
    try:
        ratio, rss = compare(Path(sys.argv[1]), Path(sys.argv[2]))
        ok = ratio <= 1.25 and rss <= 400
        print(f"boot ratio={ratio:.6f} (limit1.25); RSS delta={rss:.6f}MiB (limit400): {'PASS' if ok else 'FAIL'}")
        return 0 if ok else 1
    except (ValueError, OSError, IndexError) as error:
        print(f"boot budget FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
