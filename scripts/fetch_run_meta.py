#!/usr/bin/env python3
"""Fetch CI artifacts by name pattern for a workflow run and unzip them.

Usage: fetch_run_meta.py <run_id> <name-regex> <out-dir> [repo]
Downloads every artifact whose name matches the regex (unexpired), unzips each
into <out-dir>/<artifact-name>/ and prints the fetched inventory. Token read
from .secrets/gh_token relative to CWD or the repo root guess.
"""
import os
import pathlib
import re
import subprocess
import sys
import zipfile

repo = sys.argv[4] if len(sys.argv) > 4 else "PLANETA9091/c-imagine"
run_id, pattern, out_dir = sys.argv[1], sys.argv[2], sys.argv[3]
out = pathlib.Path(out_dir)
out.mkdir(parents=True, exist_ok=True)

tok = None
for cand in (pathlib.Path(".secrets/gh_token"), pathlib.Path("/home/z/my-project/work/c-imagine/.secrets/gh_token")):
    if cand.exists():
        tok = cand.read_text().strip()
        break
if not tok:
    sys.exit("no .secrets/gh_token found")

api = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100"
hdr = {"Authorization": f"Bearer {tok}", "User-Agent": "c-imagine-loop/1.0"}
import json
import urllib.request

arts = []
url = api
while url:
    raw = subprocess.run(["curl", "-sS", "-H", f"Authorization: Bearer {tok}", url],
                         capture_output=True, timeout=120).stdout
    d = json.loads(raw)
    arts.extend(d.get("artifacts", []))
    url = d.get("next_page_url")

rx = re.compile(pattern)
hits = [a for a in arts if rx.search(a["name"]) and not a["expired"]]
print(f"run {run_id}: {len(arts)} artifacts, {len(hits)} match /{pattern}/")
for a in hits:
    dest_zip = out / f"{a['name']}.zip"
    if not dest_zip.exists():
        # curl -L: drops the Authorization header on the cross-origin redirect
        # (urllib forwards it to S3, which 401s — the classic artifact bug).
        subprocess.run(["curl", "-sSL", "-H", f"Authorization: Bearer {tok}",
                        "-o", str(dest_zip),
                        f"https://api.github.com/repos/{repo}/actions/artifacts/{a['id']}/zip"],
                       timeout=300, check=True)
    adir = out / a["name"]
    adir.mkdir(exist_ok=True)
    with zipfile.ZipFile(dest_zip) as z:
        z.extractall(adir)
    files = sorted(p.name for p in adir.iterdir())
    print(f"  {a['name']} ({a['size_in_bytes']}B) -> {files}")
print(f"DONE {len(hits)} artifacts into {out}")
