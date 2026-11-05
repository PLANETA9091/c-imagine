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
column is mechanical: pixel/stream classes with measured content and zero
failures are candidates for review. Frame CONTENT only proves presence;
those classes still require calibration. This tool never flips a gate.
"""
import argparse
import glob
import json
import os
import re
import sys
from collections import Counter, defaultdict


def load_all(dirs, pattern):
    out = []
    for d in dirs:
        for f in glob.glob(os.path.join(d, pattern)):
            try:
                out.append((f, json.load(open(f))))
            except (OSError, ValueError) as error:
                raise SystemExit(f"invalid report {f}: {error}") from error
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
            match = re.match(r'join-(item|fluid|particle|e)(?:-|\d)', name)
            cls = match.group(1) if match else "block"
            # The dyn/large entity series are diagnostic, separate from mobs.
            if cls == "e" and re.search(r'-(dyn|large)-', name):
                cls = "e-" + re.search(r'-(dyn|large)-', name).group(1)
            ns = ns_of(name)
            counts = Counter(r["verdict"] for r in d.get("rows", []))
            if d.get("verdict") == "GLOBAL-MISALIGNED":
                counts["RED"] += 1
            eff = counts["GREEN"] + counts["RED"]
            rows.append({"class": cls, "ns": ns, "pages": 1,
                         "metric": "green",
                         "green": counts["GREEN"], "eff": eff,
                         "red": counts["RED"],
                         "skipped": sum(n for v, n in counts.items()
                                        if v not in ("GREEN", "RED"))})

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
            preview = d.get("mapped_preview") or {}
            total = preview.get("expected", d.get("truth_count", 0))
            hits = preview.get("position_hits", total if d.get("ordered_equal") else 0)
            a["mapped"] += hits
            a["total"] += total
            a["missing_ours"] += total - hits
            # Missing artifacts and extra/reordered events cannot be candidates.
            if d.get("verdict") == "ARTIFACT-MISSING" or (
                    total == hits and not preview.get("order_exact", d.get("ordered_equal", False))):
                a["missing_ours"] += 1
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
            if v in ("EMPTY", "RED", "ARTIFACT-MISSING"):
                a["empty"] += 1
            elif v in ("CONTENT", "GREEN"):
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
        if m["red"] and not m["eff"]:
            ready = "BROKEN (missing artifact)"
        if pct is not None and m["metrics"] != {"frame"}:
            if m["red"] == 0:
                ready = "FLIP-CANDIDATE (0 red)"
            elif pct >= 90:
                ready = "NEAR (>=90%)"
        if m["metrics"] == {"frame"}:
            ready = "DIAGNOSTIC (calibration required)"
        table.append({"class": cls, "ns": ns, "pages": m["pages"],
                      "eff": m["eff"], "green": m["green"], "red": m["red"],
                      "green_pct": round(pct, 3) if pct is not None else None,
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
