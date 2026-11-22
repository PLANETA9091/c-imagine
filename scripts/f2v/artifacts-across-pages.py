#!/usr/bin/env python3
"""Merge the paginated artifacts listing of one Actions run (14-e250).

THE HOLE (measured on the tenth flight, gallery 44939613687): the run-
artifacts endpoint returns 100 entries per page, and the per-run artifact
count of a gallery run crosses 100 (the tenth flight uploaded 133). Every
page-1-only reader then races a flood it cannot see past: the paper ours
join's wait loop polled for wall-truth-meta-0 — registered at 19:27:29Z,
a full 26 minutes BEFORE the wait started — and timed out 30 minutes later
because the artifact sat on page 2 (the 100-newest cutoff), hidden from all
90 polls. The 14-aa race guard lost shard 0's join (gate=1, join_pages=0)
to a pagination artifact, not to content.

The fix rides the SAME curl the workflow already does: fetch pages 1..N
(`?per_page=100&page=$P`, empty/garbage pages tolerated) and pipe the
concatenated bodies here. The script decodes every top-level JSON document
on stdin (raw_decode, whitespace between docs free), merges their
`artifacts` arrays by id, and either

  - prints the merged listing JSON ({"artifacts": [...], "total_count": n})
    so find-artifact.py keeps its stdin contract, or
  - given a NAME argument, prints the count of non-expired artifacts with
    that exact name (the wait loops' contract).

Usage: artifacts-across-pages.py [name]
"""
import json
import sys


def main():
    blob = sys.stdin.buffer.read().decode("utf-8", "replace")
    dec = json.JSONDecoder()
    merged = {}
    idx = 0
    while idx < len(blob):
        while idx < len(blob) and blob[idx] in " \t\r\n":
            idx += 1
        if idx >= len(blob):
            break
        try:
            doc, idx = dec.raw_decode(blob, idx)
        except json.JSONDecodeError:
            # a truncated/garbage page body must never kill the merge —
            # skip one char and keep decoding the stream
            idx += 1
            continue
        if isinstance(doc, dict):
            for a in doc.get("artifacts", []):
                if isinstance(a, dict) and "id" in a:
                    merged[a["id"]] = a
    arts = sorted(merged.values(), key=lambda a: a["id"])
    if len(sys.argv) > 1:
        name = sys.argv[1]
        print(sum(1 for a in arts if a.get("name") == name and not a.get("expired")))
    else:
        json.dump({"artifacts": arts, "total_count": len(arts)}, sys.stdout)


if __name__ == "__main__":
    main()
