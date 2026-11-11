#!/usr/bin/env python3
"""Aggregate the run's join-*.json reports: green share + RED breakdown by family.

Usage: scan_14cz1.py <rep_dir_root>   (each repXXXX/ subdir = one shard)
Writes /tmp/red_rows.json (list of RED rows) for the follow-up forensics.
"""
import json, glob, os, re, sys
from collections import Counter, defaultdict

root = sys.argv[1] if len(sys.argv) > 1 else "/tmp/rep0"

tot_cells = 0
tot_red = 0
per_mod = Counter()
per_mod_eff = Counter()
per_fam = Counter()
red_rows = []
pages = 0

shards = sorted(glob.glob(os.path.join(root, "rep*")))
if not shards:
    shards = [root]
for shard in shards:
    for f in sorted(glob.glob(os.path.join(shard, "join-*.json"))):
        try:
            d = json.load(open(f))
        except Exception as e:
            print("BAD", f, e)
            continue
        cells = d.get("cells", 0)
        red = d.get("red", 0)
        if cells == 0 and red == 0:
            continue  # no-data page
        pages += 1
        tot_cells += cells
        tot_red += red
        for r in d.get("rows", []):
            st = r.get("state", "")
            mod = st.split(":")[0] if ":" in st else "?"
            m = re.match(r"[a-z0-9_]+:([a-z0-9_]+)", st)
            fam = m.group(1) if m else "?"
            per_mod_eff[mod] += 1
            if r.get("verdict") == "RED":
                per_mod[mod] += 1
                per_fam[(mod, fam)] += 1
                red_rows.append(r)

eff = tot_cells
share = 100.0 * (eff - tot_red) / eff if eff else 0.0
print(f"pages={pages} cells={eff} RED={tot_red} green={share:.2f}%")
print("--- by mod (RED / eff / green%) ---")
for mod, red in per_mod.most_common():
    e = per_mod_eff[mod]
    print(f"  {mod:26s} {red:5d} / {e:6d} = {100.0*(e-red)/e:6.2f}%")
print("--- top families ---")
for (m, f), v in per_fam.most_common(30):
    print(f"  {m}:{f:36s} {v}")
json.dump(red_rows, open("/tmp/red_rows.json", "w"))
print("wrote /tmp/red_rows.json:", len(red_rows))
