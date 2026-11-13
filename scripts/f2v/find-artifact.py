#!/usr/bin/env python3
"""Print the artifact id for wall-truth-<kind>-<shard> on the run list given
on stdin (kind/shard via argv), or nothing when absent.

Usage: find-artifact.py <kind> <shard>  (reads the artifact list on stdin)
"""
import json
import sys

kind, shard = sys.argv[1], sys.argv[2]
want = f"wall-truth-{kind}-{shard}"
d = json.load(sys.stdin)
for a in d.get("artifacts", []):
    if a["name"] == want:
        print(a["id"])
        break
