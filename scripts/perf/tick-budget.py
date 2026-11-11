#!/usr/bin/env python3
"""Require completed, configured heavy-load evidence before applying its budget."""
import math
from pathlib import Path
import re
import sys


ARM = '[cimtest_heavy] load armed:'
DONE = '[cimtest_heavy] load done:'


def measure(text):
    armed = False
    average = None
    for line in text.splitlines():
        if ARM in line:
            match = re.fullmatch(r' msPerTick=(\d+) ticks=(\d+)\s*', line.split(ARM, 1)[1])
            if armed or not match or tuple(map(int, match.groups())) != (10, 600):
                raise ValueError('expected one 10ms/600tick arm')
            armed = True
        if DONE in line:
            match = re.fullmatch(
                r' ticks=(\d+) overMsTotal=(\d+) avgOverMsPerTick=(\S+)\s*',
                line.split(DONE, 1)[1])
            if not armed or average is not None or not match or int(match[1]) != 600:
                raise ValueError('expected one completed 600tick load after its arm')
            average = float(match[3])
            if not math.isfinite(average) or average < 0:
                raise ValueError('invalid tick overshoot')
            # Total is truncated to integer milliseconds; average is rounded
            # to two decimals by the producer. Reject contradictory evidence.
            if abs(int(match[2]) / 600 - average) > 0.007:
                raise ValueError('inconsistent total and average tick overshoot')
    if average is None:
        raise ValueError('missing heavy-load completion measurement')
    return average


def main():
    try:
        average = measure(Path(sys.argv[1]).read_text())
        ok = average <= 2.0
        print(f'tick-load avgOverMsPerTick={average} (limit2.0): {"PASS" if ok else "FAIL"}')
        return 0 if ok else 1
    except (ValueError, OSError, IndexError) as error:
        print(f'tick-load budget FAIL: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
