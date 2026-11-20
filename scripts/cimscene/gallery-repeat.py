#!/usr/bin/env python3
"""Generate the Create repeat targets from all gallery report artifacts of one run."""

import argparse
import json
import re
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=int, help="GitHub Actions run ID")
    parser.add_argument("reports", nargs="+", type=Path, help="extracted gallery-report-* directories")
    args = parser.parse_args()

    pages = {}
    for directory in args.reports:
        for path in directory.glob("join-*.json"):
            match = re.fullmatch(r"join-(item-)?create-p(\d+)\.json", path.name)
            if not match:
                continue
            key = ("item" if match[1] else "block", int(match[2]))
            if key in pages:
                parser.error(f"duplicate Create page in {path}")
            pages[key] = sum(
                row["verdict"] == "RED" and None in (row.get("self_ae") or (None, None))
                for row in json.loads(path.read_text(encoding="utf-8"))["rows"]
            )
    if not pages:
        parser.error("no Create block or item join reports found")

    print(f"# Run {args.run}. Regenerate with python3 scripts/cimscene/gallery-repeat.py "
          f"{args.run} gallery-report-*")
    print("# Kind\tNamespace\tPage\tBaseline RED rows missing a self pair")
    for (kind, page), count in sorted(pages.items()):
        if count:
            print(f"{kind}\tcreate\t{page}\t{count}")


if __name__ == "__main__":
    main()
