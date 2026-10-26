#!/usr/bin/env python3
"""Diff two census JSONs into a markdown summary.

Usage: census_diff.py <census-a.json> <census-b.json> <out.md>
"""

import json
import sys
from pathlib import Path


def main() -> None:
    a_path, b_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
    a = json.loads(Path(a_path).read_text())
    b = json.loads(Path(b_path).read_text())

    lines = [
        f"# Vanilla server census diff — {a['version']} vs {b['version']}",
        "",
        f"Classes: {a['classes_total']} → {b['classes_total']}"
        f" ({b['classes_total'] - a['classes_total']:+d})",
        "",
        "## Data file types (version A → version B)",
        "",
        "| data type | A | B | Δ |",
        "|---|---|---|---|",
    ]
    dt_a, dt_b = a["data_file_types"], b["data_file_types"]
    for key in sorted(set(dt_a) | set(dt_b)):
        va, vb = dt_a.get(key, 0), dt_b.get(key, 0)
        if va != vb:
            lines.append(f"| {key} | {va} | {vb} | {vb - va:+d} |")
    Path(out).write_text("\n".join(lines) + "\n")
    print(f"diff written: {out}")


if __name__ == "__main__":
    main()
