#!/usr/bin/env python3
"""Print the newest gallery run id (strictly before GITHUB_RUN_ID) whose
truth artifacts are actually usable: completed, and carrying
wall-truth-shots-0 + wall-truth-meta-0. Scans the 12 newest runs.

Usage: find-sample-run.py <api_base> <repo> <token>
"""
import json
import os
import sys
import urllib.request

base, repo, token = sys.argv[1], sys.argv[2], sys.argv[3]
me = int(os.environ.get("GITHUB_RUN_ID", "0"))


def get(url):
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "User-Agent": "c-imagine-face-sample",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


runs = get(f"{base}/repos/{repo}/actions/workflows/gallery.yml/runs?per_page=12")
done = [r for r in runs.get("workflow_runs", [])
        if r["id"] < me and r["status"] == "completed"]
# prefer finished runs over cancelled ones (a cancel kills the truth shards
# mid-walk and leaves partial/no artifacts), newest first within each class
candidates = ([r["id"] for r in done if r["conclusion"] != "cancelled"]
              + [r["id"] for r in done if r["conclusion"] == "cancelled"])
for rid in candidates:
    try:
        arts = get(f"{base}/repos/{repo}/actions/runs/{rid}/artifacts?per_page=100")
    except Exception:
        continue
    names = {a["name"] for a in arts.get("artifacts", []) if not a.get("expired")}
    if "wall-truth-shots-0" in names and "wall-truth-meta-0" in names:
        print(rid)
        break
