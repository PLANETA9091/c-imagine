#!/usr/bin/env python3
"""Print the id of the latest SUCCESSFUL gallery.yml run strictly before the
current one (GITHUB_RUN_ID), or nothing when none exists.

Usage: find-prev-run.py  (reads the run list on stdin)
"""
import json
import os
import sys

d = json.load(sys.stdin)
me = int(os.environ["GITHUB_RUN_ID"])
# the newest COMPLETED run (the conclusion is irrelevant: the truth shards
# upload their artifacts even when the ours/verdict legs fail the run, and
# the truth-side fluid render is what the faces sample)
rs = [r for r in d.get("workflow_runs", [])
      if r["id"] < me and r["status"] == "completed"]
print(rs[0]["id"] if rs else "")


def _unused():
    pass
