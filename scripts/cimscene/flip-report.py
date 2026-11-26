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

14-e272 THE EXERCISED LAW: a manifest skip row (gallery-diff verdict
"skipped") is an ABSENCE — nothing was compared, and it stays the skipped
column. Every other verdict rode a measured crop (gallery-diff.py
classify: GREEN / RED / DYNAMIC / SPILL / AIR / ANIMATED / DISSOLVED-RACE)
and counts EXERCISED (eff); the demoted column names the report-only
verdicts. The old eff = GREEN + RED hid exercised classes behind NO-DATA:
the 28th flight's fluid rows (49 compared cells, every one over-gate then
demoted ANIMATED by the animation-table + self-pair law) and the modded
entity dyn/large series (their joins were diag-e-* files this tool never
globbed) both measured content while the summary read NO-DATA. The state
machine keeps the laws intact: 0 red with demoted rows is never
GATED-GREEN/FLIP-CANDIDATE, and the entity family folds back into the
ledger's one "e" row.
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


# 14-e33 THE GATED CLASSES: the joins the gallery workflow pairs with
# `|| GATE=1` (the run reds on their failure) — block walls, item walls,
# fluid walls and the mob pages. Every other class with content is
# diagnostic-first (graduates only via a measured stable-x2 flip).
GATED_CLASSES = {"block", "item", "fluid", "e"}


def ns_of(name):
    # Read the producer's filename contract, not a fixed mod allowlist.
    # Entity pages use -N; block/item and diagnostic pages use -pN; menu
    # joins are join-menu-<ns>_<menu>.json (no page suffix — the row's
    # state carries the menu id; the run-623 menu reds read as
    # block/minecraft without this branch).
    m = re.match(r'join-menu-([a-z0-9_.-]+?)_', name)
    if m:
        return m.group(1)
    m = re.fullmatch(
        r'(?:join-(?:(?:item|fluid|particle|e(?:-(?:dyn|large))?|menu)-)?'
        r'|diag-(?:sound|pstream|held|gui|biome|inv|iso)-)'
        r'([a-z0-9_.-]+?)-p?\d+\.json', name)
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
            if name.startswith("diag-iso-"):
                cls = "blockiso"   # 14-e32 the isolation lane (diagnostic)
            else:
                match = re.match(r'join-(item|fluid|particle|e|menu)(?:-|\d)', name)
                cls = match.group(1) if match else "block"
            # The dyn/large entity series are diagnostic, separate from mobs.
            if cls == "e" and re.search(r'-(dyn|large)-', name):
                cls = "e-" + re.search(r'-(dyn|large)-', name).group(1)
            ns = ns_of(name)
            counts = Counter(r["verdict"] for r in d.get("rows", []))
            if d.get("verdict") == "GLOBAL-MISALIGNED":
                counts["RED"] += 1
            # 14-e272 THE EXERCISED LAW: a manifest skip row is an absence;
            # every demoted verdict is a measured cell (see the docstring).
            eff = sum(n for v, n in counts.items() if v != "skipped")
            rows.append({"class": cls, "ns": ns, "pages": 1,
                         "metric": "green",
                         "green": counts["GREEN"], "eff": eff,
                         "red": counts["RED"],
                         "demoted": eff - counts["GREEN"] - counts["RED"],
                         "skipped": counts["skipped"]})

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
                                  "red": 0, "demoted": 0, "skipped": 0,
                                  "metrics": set()})
    for r in rows:
        k = (r["class"], r["ns"])
        m = merged[k]
        m["pages"] += r["pages"]
        m["green"] += r["green"]
        m["eff"] += r["eff"]
        m["red"] += r["red"]
        m["demoted"] += r.get("demoted", 0)
        m["skipped"] += r["skipped"]
        m["metrics"].add(r["metric"])

    # 14-e272 THE ENTITY FAMILY = THE LEDGER'S ONE ROW: coverage-gate.py
    # fans the ledger's single `entity` row into the mobs/dyn/large series
    # (each series paginates the SAME entry list — wrong-series subjects are
    # skip rows, never coverage), so the census folds the three series back
    # into the one ("e", ns) row exercised-vs-registered reads at. The mobs
    # gate's REDs stay the red column; the modded dyn/large diagnostics'
    # DYNAMIC demotions stay demoted (report-only — the state machine keeps
    # them out of every gated-green claim).
    for ns in {ns for (cls, ns) in merged if cls in ("e-dyn", "e-large")}:
        base = merged.setdefault(("e", ns),
                                 {"pages": 0, "green": 0, "eff": 0,
                                  "red": 0, "demoted": 0, "skipped": 0,
                                  "metrics": set()})
        for cls in ("e-dyn", "e-large"):
            part = merged.pop((cls, ns), None)
            if part is None:
                continue
            for f in ("pages", "green", "eff", "red", "demoted", "skipped"):
                base[f] += part[f]
            base["metrics"] |= part["metrics"]

    table = []
    for (cls, ns), m in sorted(merged.items()):
        pct = 100 * m["green"] / m["eff"] if m["eff"] else None
        ready = "MEASURED" if m["eff"] else "NO-DATA"
        if m["red"] and not m["eff"]:
            ready = "BROKEN (missing artifact)"
        if pct is not None and m["metrics"] != {"frame"}:
            if m["red"] == 0 and m["demoted"] == 0:
                # 14-e33 THE GATED-GREEN LAW (the dynamictrees read, measured
                # x2): a GATED pixel class (block/item/fluid/e-mobs — the
                # joins the workflow pairs with || GATE=1) with zero red has
                # NOTHING TO FLIP — it already hard-gates and it already
                # passes; the FLIP-CANDIDATE recommendation belongs to the
                # DIAGNOSTIC classes only (the biome flip's precedent: a
                # diagnostic channel graduates when measured stable x2).
                # Calling the walk's own success a "candidate" sent the e31
                # receipt hunting for a nonexistent gate change.
                ready = ("GATED-GREEN (0 red)" if cls in GATED_CLASSES
                         else "FLIP-CANDIDATE (0 red)")
            elif m["red"] == 0 and m["demoted"] > 0:
                # 14-e272: 0 red with demoted rows is NOT a gated-green — the
                # demoted rows never gated (their verdicts are the report-only
                # structural/animation classes); the name must say so.
                ready = ("GATED (0 red, %d demoted report-only)" % m["demoted"]
                         if cls in GATED_CLASSES else
                         "FLIP-PARTIAL (0 red, %d demoted report-only)"
                         % m["demoted"])
            elif pct >= 90:
                ready = "NEAR (>=90%)"
        if m["metrics"] == {"frame"}:
            ready = "DIAGNOSTIC (calibration required)"
        table.append({"class": cls, "ns": ns, "pages": m["pages"],
                      "eff": m["eff"], "green": m["green"], "red": m["red"],
                      "green_pct": round(pct, 3) if pct is not None else None,
                      "skipped": m["skipped"], "demoted": m["demoted"],
                      "state": ready})

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
