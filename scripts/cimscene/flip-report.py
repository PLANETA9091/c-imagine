#!/usr/bin/env python3
"""The FLIP REPORT — per-class measured state for the gate-flip decision.

Reads the extracted gallery-report artifact dirs (the per-shard JSONs the
join steps wrote) and prints the per-class table the flip law needs:

  class        ns    pages  metric                          flip-readiness
  ------------- ----  -----  -------------------------------- -----------------
  item         create   10  green 20.3% (post-CMD-fix)        gate-ready when
  sound        create    9  mapped 84/97 positions            stable x2 runs
  ...

Usage:
  flip-report.py <shard-dir> [<shard-dir> ...]      # the gallery-report-*/
  flip-report.py --json <out.json> <dir> ...        # machine-readable

Every diagnostic class reports its own measured metric; the RECOMMENDATION
column is mechanical (the law lives in the owner's head + worklog): a class
is FLIP-CANDIDATE only when it measured on >=1 run with no EMPTY/BROKEN
rows and its metric band is stated in the run summary — this tool names the
numbers, the owner flips.
"""
import argparse
import glob
import json
import os
import re
import sys
from collections import defaultdict


def load_all(dirs, pattern):
    out = []
    for d in dirs:
        for f in glob.glob(os.path.join(d, pattern)):
            try:
                out.append((f, json.load(open(f))))
            except Exception:
                out.append((f, None))
    return out


def ns_of(name):
    m = re.search(r'-(create|biomesoplenty|dynamictrees|combat_roll|minecraft)', name)
    return m.group(1) if m else "minecraft"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--json", dest="out_json")
    args = ap.parse_args()
    dirs = args.dirs

    rows = []

    # --- gated pixel classes: blocks (join-<ns>-p*), items, fluids, mobs ---
    for pat, label in (("[j]oin-*.json", "join"),):
        for f, d in load_all(dirs, pat):
            if d is None:
                continue
            name = os.path.basename(f)
            if name.startswith("join-e") or name.startswith("join-item") \
                    or name.startswith("join-fluid"):
                cls = re.match(r'join-([a-z]+)', name).group(1)
            else:
                cls = "block"
            ns = ns_of(name)
            eff = d["cells"] - d.get("dynamic", 0) - d.get("skipped", 0)
            if eff <= 0:
                continue
            rows.append({"class": cls, "ns": ns, "pages": 1,
                         "metric": "green",
                         "green": eff - d["red"], "eff": eff,
                         "red": d["red"], "skipped": d.get("skipped", 0)})

    # --- stream diagnostics: sound / particle ---
    for pat, cls in (("[d]iag-sound-*.json", "sound"),
                     ("[d]iag-pstream-*.json", "particle")):
        agg = defaultdict(lambda: {"pages": 0, "mapped": 0, "total": 0,
                                   "missing_ours": 0})
        for f, d in load_all(dirs, pat):
            if d is None:
                continue
            a = agg[ns_of(os.path.basename(f))]
            a["pages"] += 1
            a["mapped"] += d.get("mapped", 0)
            a["total"] += d.get("total", 0)
            a["missing_ours"] += d.get("missing_ours", 0)
        for ns, a in sorted(agg.items()):
            rows.append({"class": cls, "ns": ns, "pages": a["pages"],
                         "metric": "stream",
                         "green": a["mapped"], "eff": a["total"],
                         "red": a["missing_ours"], "skipped": 0})

    # --- framediff classes: held / gui / biome ---
    for pat, cls in (("[d]iag-held-*.json", "held"),
                     ("[d]iag-gui-*.json", "gui"),
                     ("[d]iag-biome-*.json", "biome"),
                     ("[d]iag-inv-*.json", "inv")):
        agg = defaultdict(lambda: {"pages": 0, "empty": 0, "content": 0,
                                   "ae": []})
        for f, d in load_all(dirs, pat):
            if d is None:
                continue
            a = agg[ns_of(os.path.basename(f))]
            a["pages"] += 1
            v = d.get("verdict")
            if v == "EMPTY":
                a["empty"] += 1
            elif v == "CONTENT":
                a["content"] += 1
                if d.get("ae") is not None:
                    a["ae"].append(d["ae"])
        for ns, a in sorted(agg.items()):
            med = sorted(a["ae"])[len(a["ae"]) // 2] if a["ae"] else None
            rows.append({"class": cls, "ns": ns, "pages": a["pages"],
                         "metric": "frame",
                         "green": a["content"], "eff": a["pages"],
                         "red": a["empty"], "skipped": 0, "ae_median": med})

    # --- aggregate per (class, ns) ---
    merged = defaultdict(lambda: {"pages": 0, "green": 0, "eff": 0,
                                  "red": 0, "skipped": 0, "metrics": set()})
    for r in rows:
        k = (r["class"], r["ns"])
        m = merged[k]
        m["pages"] += r["pages"]
        m["green"] += r["green"]
        m["eff"] += r["eff"]
        m["red"] += r["red"]
        m["skipped"] += r["skipped"]
        m["metrics"].add(r["metric"])

    table = []
    for (cls, ns), m in sorted(merged.items()):
        pct = 100 * m["green"] / m["eff"] if m["eff"] else None
        ready = "MEASURED" if m["eff"] else "NO-DATA"
        if pct is not None:
            if m["red"] == 0:
                ready = "FLIP-CANDIDATE (0 red)"
            elif pct >= 90:
                ready = "NEAR (>=90%)"
        table.append({"class": cls, "ns": ns, "pages": m["pages"],
                      "eff": m["eff"], "green": m["green"], "red": m["red"],
                      "green_pct": round(pct, 1) if pct is not None else None,
                      "skipped": m["skipped"], "state": ready})

    if args.out_json:
        json.dump(table, open(args.out_json, "w"), indent=1)
    hdr = (f"{'class':10s} {'ns':16s} {'pages':>5s} {'eff':>7s} "
           f"{'green':>7s} {'red':>7s} {'green%':>7s}  state")
    print(hdr)
    for t in table:
        print(f"{t['class']:10s} {t['ns']:16s} {t['pages']:5d} {t['eff']:7d} "
              f"{t['green']:7d} {t['red']:7d} "
              f"{(str(t['green_pct']) if t['green_pct'] is not None else '-'):>7s}  {t['state']}")
    if not table:
        print("no report JSONs found in the given dirs")


if __name__ == "__main__":
    sys.exit(main())
