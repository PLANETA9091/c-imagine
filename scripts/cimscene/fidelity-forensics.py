#!/usr/bin/env python3
"""The FIDELITY FORENSICS tool (lane 14-dg) — per-RED-row attribution.

Reads the extracted gallery-report artifact dirs (the join-*.json the
per-page pixel diff wrote) and buckets every RED row by its self_ae pair
(the 14-aj self-consistency measurement, [truth_self_ae, ours_self_ae]):

  FIDELITY  both legs reproduce the cell stably (both self_ae <= gate):
            the cross-leg ae > gate is a REAL fidelity hole in ours —
            deterministic content the pack channel drew differently.
  ANIM-SELF one leg disagrees with ITSELF (self_ae > gate): the cell is
            animated content that escaped the ANIMATED demotion — a
            demote-law violation, expected to be zero.
  NO-PAIR   no self frame pair was passed for that leg: undecidable from
            this report alone.

For the FIDELITY bucket the tool aggregates per subject state (count, ae
distribution, pages, luma-normalisation usage) and — when a seed pack is
given (--seed <dir> holding quads/rows.tsv + quads/<hash>.tsv, the
QuadDump capture) — cross-references every state's baked mesh: the number
of distinct quad hashes the state rendered, the total quad lines and the
distinct sprites those quads sample. That is the geometry attribution the
per-cell quad-diffing lane needs: a 2-quad state and a 200-quad state are
different forensics jobs.

Report-only: this tool never flips a gate, never edits a verdict. The
gate keeps its own law (coverage-gate.py); this is the magnifier over the
rows the gate already counted.

Usage:
  fidelity-forensics.py <shard-dir> [<shard-dir> ...] [--seed <pack>]
                        [--top N] [--json out.json]

  <shard-dir>   extracted gallery-report-*/ dirs (join-*.json inside)
  --seed        the QuadDump seed-pack dir (quads/rows.tsv, quads/*.tsv)
  --top N       print N worst states (default 20)
  --json        also write the machine-readable report
"""
import argparse
import glob
import json
import os
import re
import statistics
import sys
from collections import defaultdict


def load_joins(dirs):
    """(path, parsed) for every join-*.json in the shard dirs."""
    out = []
    for d in dirs:
        for f in sorted(glob.glob(os.path.join(d, "[j]oin-*.json"))):
            try:
                out.append((f, json.load(open(f, encoding="utf-8"))))
            except (OSError, ValueError) as error:
                raise SystemExit(f"invalid report {f}: {error}") from error
    return out


def class_of(join_name):
    """The ledger class a join file belongs to (the flip-report contract)."""
    m = re.match(r"join-(item|fluid|particle|e)(?:-|\d)", join_name)
    if m:
        cls = m.group(1)
        if cls == "e":
            fam = re.search(r"-(dyn|large)-", join_name)
            if fam:
                return "e-" + fam.group(1)
        return cls
    if re.match(r"join-[a-z0-9_.-]+-p\d+\.json", join_name):
        return "variant-blockentity" if "-blockentity-" in join_name else "block"
    return "unknown"


def ns_of(join_name):
    m = re.fullmatch(
        r"join-(?:(?:item|fluid|particle|e(?:-(?:dyn|large))?)-)?"
        r"([a-z0-9_.-]+?)-p?\d+\.json", join_name)
    return m.group(1) if m else "minecraft"


def bucket_of(row, gate):
    sa = row.get("self_ae") or [None, None]
    sa_t, sa_o = sa[0], sa[1]
    if sa_t is None or sa_o is None:
        return "NO-PAIR"
    if sa_t > gate or sa_o > gate:
        return "ANIM-SELF"
    return "FIDELITY"


def load_seed(pack):
    """state -> {hashes, quads, sprites} from the QuadDump capture."""
    rows_p = os.path.join(pack, "quads", "rows.tsv")
    if not os.path.isfile(rows_p):
        raise SystemExit(f"--seed {pack}: {rows_p} missing")
    mesh = {}
    for line in open(rows_p, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        state, _, h = line.rpartition("#q")
        if not state:
            continue
        mesh.setdefault(state, set()).add(h)
    per = {}
    for state, hashes in mesh.items():
        quads = 0
        sprites = set()
        for h in hashes:
            qf = os.path.join(pack, "quads", f"{h}.tsv")
            if not os.path.isfile(qf):
                continue
            for line in open(qf, encoding="utf-8"):
                line = line.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                quads += 1
                sprite = line.split("\t", 1)[0]
                sprites.add(sprite)
        per[state] = {"hashes": len(hashes), "quads": quads,
                      "sprites": sorted(sprites)}
    return per


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--seed")
    ap.add_argument("--top", type=int, default=20)
    ap.add_argument("--json", dest="out_json")
    args = ap.parse_args()

    joins = load_joins(args.dirs)
    if not joins:
        raise SystemExit("no join-*.json found in the given dirs")

    buckets = defaultdict(list)
    states = defaultdict(lambda: {"n": 0, "ae": [], "pages": set(),
                                  "luma": 0, "fit": 0, "fam": 0})
    for f, d in joins:
        name = os.path.basename(f)
        cls, ns = class_of(name), ns_of(name)
        gate = d.get("gate", 60000)
        for row in d.get("rows", []):
            if row.get("verdict") != "RED":
                continue
            b = bucket_of(row, gate)
            buckets[b].append({"file": name, "cls": cls, "ns": ns,
                               "page": row.get("page")})
            if b != "FIDELITY":
                continue
            st = states[(cls, ns, row.get("state", "?"))]
            st["n"] += 1
            st["ae"].append(row.get("ae", 0))
            st["pages"].add(f"{name}:{row.get('page')}")
            st["luma"] += 1 if row.get("luma_norm") else 0
            st["fit"] += 1 if row.get("luma_fit") else 0
            st["fam"] += 1 if row.get("dyn_family") else 0

    seed = load_seed(args.seed) if args.seed else None

    out = {"buckets": {b: len(v) for b, v in buckets.items()},
           "fidelity_states": [], "seed": bool(seed)}
    for (cls, ns, state), st in states.items():
        aes = sorted(st["ae"])
        entry = {"class": cls, "ns": ns, "state": state, "red": st["n"],
                 "ae_median": int(statistics.median(aes)) if aes else 0,
                 "ae_max": max(aes) if aes else 0,
                 "pages": len(st["pages"]), "luma_norm": st["luma"],
                 "luma_fit": st["fit"], "dyn_family": st["fam"]}
        if seed is not None:
            s = seed.get(state)
            entry["mesh"] = {"hashes": s["hashes"], "quads": s["quads"],
                             "sprites": len(s["sprites"]),
                             "top_sprites": s["sprites"][:8]} if s else None
        out["fidelity_states"].append(entry)
    out["fidelity_states"].sort(
        key=lambda e: (-e["red"], -e["ae_max"]))

    if args.out_json:
        with open(args.out_json, "w", encoding="utf-8") as f:
            json.dump(out, f, indent=1)

    print(f"RED rows: FIDELITY={out['buckets'].get('FIDELITY', 0)} "
          f"ANIM-SELF={out['buckets'].get('ANIM-SELF', 0)} "
          f"NO-PAIR={out['buckets'].get('NO-PAIR', 0)}")
    print(f"{'class':<20} {'ns':<10} {'red':>4} {'ae_med':>8} {'ae_max':>8} "
          f"{'pg':>3} {'quads':>6} {'sprites':>7}  state")
    for e in out["fidelity_states"][:args.top]:
        mesh = e.get("mesh") or {}
        print(f"{e['class']:<20} {e['ns']:<10} {e['red']:>4} "
              f"{e['ae_median']:>8} {e['ae_max']:>8} {e['pages']:>3} "
              f"{str(mesh.get('quads', '-')):>6} "
              f"{str(mesh.get('sprites', '-')):>7}  {e['state']}")
    if not out["fidelity_states"]:
        print("no FIDELITY rows — nothing to attribute")


if __name__ == "__main__":
    main()
