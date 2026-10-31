#!/usr/bin/env python3
"""The COVERAGE gate — partial walks can never green the verdict again.

Owner directive 2026-10-31: the pixel verdict must SEE every registered
entry of the mod's content classes. The scene-mod writes a per-namespace
coverage ledger (gallery-<ns>-index.tsv: class|total|cellsPerPage, derived
from the same registries on both stacks). This gate joins the ledger
against what the run ACTUALLY produced:

  for every class with total > 0:
    pages_needed = ceil(total / cellsPerPage)
    - every page manifest present in the truth scenes dir?
    - every truth screenshot present?
    - every ours screenshot present?
    - the walk REACHED exhaustion: the terminal done marker
      (page = pages_needed, cells=0 ... exhausted=1) present in the truth
      boot log?

Any shortfall is COVERAGE-INCOMPLETE — the run is RED no matter how green
the per-cell joins were (the measured motivating gap: create registers
31,389 blockstates; the gate walked 3 pages = 231 = 0.74% and could green
on that).

Usage:
  coverage-gate.py --ns create --index <gallery-<ns>-index.tsv> \
      --scenes <truth scenes dir> --truth-shots <dir> --ours-shots <dir> \
      [--boot <truth boot.log>]
"""
import argparse
import math
import pathlib
import sys

# class -> (manifest glob template, truth shot template, ours shot template,
#           terminal-marker template). {p} = page number. The ENTITY series
# split: mobs/dyn/large each paginate the SAME entry list, so each series
# must walk ceil(total/18) pages (wrong-series entries are recorded skip
# rows, not coverage).
CLASSES = {
    "block": ("gallery-{ns}-p{p}.tsv", "gal-p{p}.png", "ours-gal-p{p}.png",
              "gallery done ns={ns} page={p} cells=0"),
    "entity-mobs": ("gallery-entity-{ns}-p{p}.tsv", "gal-e{p}.png",
                    "ours-gal-e{p}.png",
                    "gallery-entity done ns={ns} page={p} cells=0"),
    "entity-dyn": ("gallery-entitydyn-{ns}-p{p}.tsv", "gal-edyn{p}.png",
                   "ours-gal-edyn{p}.png",
                   "gallery-entitydyn done ns={ns} page={p} cells=0"),
    "entity-large": ("gallery-entitylarge-{ns}-p{p}.tsv", "gal-elarge{p}.png",
                     "ours-gal-elarge{p}.png",
                     "gallery-entitylarge done ns={ns} page={p} cells=0"),
    "item": ("gallery-item-{ns}-p{p}.tsv", "gal-i{p}.png", "ours-gal-i{p}.png",
             "gallery-item done ns={ns} page={p} cells=0"),
    "particle": ("gallery-particle-{ns}-p{p}.tsv", "gal-pa{p}.png",
                 "ours-gal-pa{p}.png",
                 "gallery-particle done ns={ns} page={p} cells=0"),
}


def read_index(path, ns):
    rows = {}
    lines = pathlib.Path(path).read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].split("\t") != ["class", "total", "cellsPerPage"]:
        raise SystemExit(f"{path}: unexpected index header")
    for ln, line in enumerate(lines[1:], start=2):
        parts = line.split("\t")
        if len(parts) != 3:
            raise SystemExit(f"{path}:{ln}: bad row {line!r}")
        rows[parts[0]] = (int(parts[1]), int(parts[2]))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ns", required=True)
    ap.add_argument("--index", required=True)
    ap.add_argument("--scenes", required=True)
    ap.add_argument("--truth-shots", required=True)
    ap.add_argument("--ours-shots", required=True)
    ap.add_argument("--boot")
    args = ap.parse_args()

    index = read_index(args.index, args.ns)
    scenes = pathlib.Path(args.scenes)
    tshots = pathlib.Path(args.truth_shots)
    oshots = pathlib.Path(args.ours_shots)
    boot = pathlib.Path(args.boot).read_text(encoding="utf-8",
                                             errors="replace") if args.boot else None

    incomplete = []
    print(f"coverage-gate: ns={args.ns} index={args.index}")
    for cls, total_cells in index.items():
        total, cells_per_page = total_cells
        # the ledger's single `entity` row fans out to the three series —
        # each series paginates the SAME entry list, so each must walk
        # ceil(total / cellsPerPage) pages (wrong-series entries are
        # recorded skip rows, never coverage)
        series = (["entity-mobs", "entity-dyn", "entity-large"]
                  if cls == "entity" else [cls])
        for sub in series:
            if sub not in CLASSES:
                raise SystemExit(f"{args.index}: unknown class {sub!r}")
            mf_tpl, ts_tpl, os_tpl, done_tpl = CLASSES[sub]
            needed = math.ceil(total / cells_per_page) if total > 0 else 0
            missing_mf = missing_ts = missing_os = 0
            no_exhaust = False
            for p in range(needed):
                if not (scenes / mf_tpl.format(ns=args.ns, p=p)).is_file():
                    missing_mf += 1
                if not (tshots / ts_tpl.format(ns=args.ns, p=p)).is_file():
                    missing_ts += 1
                if not (oshots / os_tpl.format(ns=args.ns, p=p)).is_file():
                    missing_os += 1
            if boot is not None and needed > 0:
                marker = done_tpl.format(ns=args.ns, p=needed)
                if marker not in boot:
                    no_exhaust = True
            ok = needed == 0 or (missing_mf == 0 and missing_ts == 0
                                 and missing_os == 0 and not no_exhaust)
            note = "OK" if ok else "COVERAGE-INCOMPLETE"
            print(f"{note}\t{sub}\ttotal={total}\tpages_needed={needed} "
                  f"missing_manifests={missing_mf} "
                  f"missing_truth_shots={missing_ts} "
                  f"missing_ours_shots={missing_os}"
                  + (" terminal-marker-missing" if no_exhaust else ""))
            if not ok:
                incomplete.append(sub)

    if incomplete:
        print(f"COVERAGE: RED — incomplete walks: {', '.join(incomplete)} "
              f"(content that never reached a page is RED, not green)")
        return 1
    print("COVERAGE: GREEN — every class walked to exhaustion, "
          "every page manifested and shot on both legs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
