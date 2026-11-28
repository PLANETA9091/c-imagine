#!/usr/bin/env python3
"""THE REGISTRY CENSUS — registered vs exercised, per registry per namespace.

Reads what the scene-mod census wrote at ServerStarted (scene-manifest.tsv =
registry/namespace/count, scene-manifest-entries.tsv = registry/namespace/id)
and what the gallery walks actually PLACED (the gallery-*.tsv page manifests),
and reports the gap: every registered id the walks never exercised, and every
exercised id the registry census never knew (a reverse-parity receipt).

REPORT-ONLY first flight (the 14-e31 / 14-e36 flip-law precedent: a new
channel is born DIAGNOSTIC and gates only after measured stable x2). This
tool never flips a gate; it names:

  registry    ns       registered  exercised  unexercised  reverse
  ----------- -------- ----------- ---------- ------------ --------
  block       create          643        610           33        0
  item        ae2              51         49            2        0
  ...

Per-registry exercise sources (the page-manifest column law):
  block       gallery-<ns>-p<p>.tsv            state column, [props] stripped
  item        gallery-item-<ns>-p<p>.tsv       item column
  entity      gallery-entity-<ns>-p<p>.tsv     entity column (skip rows ignored)
  particle    gallery-particle-<ns>-p<p>.tsv   particle column
  sound       gallery-sound-<ns>-p<p>.tsv      sound column
  blockiso    the SAME states the block pages carry — deliberately not a
              second source (the iso lane replays the block manifest)
  menu / block_entity  NO page-manifest exercise source yet — NAMED GAPS
              (the gui pages ride truth-screens/_gui-manifests, the be lanes
              ride be-uncovered/gated/variant files; both need their own
              exercise parser before they can join this census honestly).

Usage:
  registry-census.py --scenes <merged scenes dir> --entries <scene-manifest-entries.tsv> \
      --json <out.json>
"""
import argparse
import glob
import json
import os
import re
import sys
from collections import defaultdict

# gallery-<class>-<ns>-p<p>.tsv (the class-prefixed families) and
# gallery-<ns>-p<p>.tsv (the block lane, no prefix). K = the stride is
# irrelevant here: every shard's pages merge into one scenes dir.
PAGE_RE = re.compile(r"^gallery-(?:([a-z]+)-)?([a-z0-9_]+)-p\d+\.tsv$")
# the class word that maps to a registry; blockiso deliberately absent
# (the iso lane replays the block manifest — a second source would
# double-count the exercise set)
CLASS_TO_REGISTRY = {
    "item": "item",
    "entity": "entity",
    "particle": "particle",
    "sound": "sound",
}
NAMED_GAPS = ["menu", "block_entity"]
MAX_UNEXERCISED_LIST = 50


def bare_state(state):
    """`ae2:charger[facing=north,spin=1]` -> `charger` (the entries.tsv id
    form: bare name, the namespace rides the key)."""
    name = state.split("[", 1)[0].strip()
    if ":" in name:
        name = name.split(":", 1)[1]
    return name


def read_page(path, registry):
    """One page manifest -> the set of exercised ids (the id column, rows
    whose first cell is not `cell` are skip rows = absences, never counts)."""
    ids = set()
    col = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        header = fh.readline().rstrip("\n").split("\t")
        # the id column: the first non-structural column after cell/x/y/z
        for i, name in enumerate(header):
            if name in ("state", "item", "entity", "particle", "sound"):
                col = i
                break
        if col is None:
            return ids, "no-id-column"
        for line in fh:
            parts = line.rstrip("\n").split("\t")
            if len(parts) <= col or parts[0] != "cell":
                continue
            raw = parts[col].strip()
            if not raw or raw == "-":
                continue
            ids.add(bare_state(raw))
    return ids, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--scenes", required=True, help="the merged scenes dir")
    ap.add_argument("--entries", required=True, help="scene-manifest-entries.tsv")
    ap.add_argument("--json", dest="out_json", default=None)
    ap.add_argument("--max-list", type=int, default=MAX_UNEXERCISED_LIST)
    args = ap.parse_args()

    registered = defaultdict(set)  # (registry, ns) -> {id}
    if os.path.isfile(args.entries):
        with open(args.entries, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                parts = line.rstrip("\n").split("\t")
                if len(parts) < 3 or parts[0] == "registry":
                    continue
                registered[(parts[0], parts[1])].add(parts[2])
    else:
        print(f"registry-census: the entries manifest is missing: {args.entries}",
              file=sys.stderr)

    exercised = defaultdict(set)  # (registry, ns) -> {id}
    pages_seen = 0
    no_id_column = []
    for path in sorted(glob.glob(os.path.join(args.scenes, "gallery-*.tsv"))):
        base = os.path.basename(path)
        m = PAGE_RE.match(base)
        if not m:
            continue
        cls, ns = m.group(1), m.group(2)
        registry = CLASS_TO_REGISTRY.get(cls, "block" if cls is None else None)
        if registry is None:
            continue  # biome/gui/inv/variant/... — not a registry lane here
        ids, problem = read_page(path, registry)
        if problem:
            no_id_column.append(base)
            continue
        if ids:
            pages_seen += 1
            exercised[(registry, ns)] |= ids

    registries = {}
    keys = sorted(set(registered) | set(exercised), key=lambda k: (k[0], k[1]))
    for key in keys:
        reg, ns = key
        reg_ids = registered.get(key, set())
        ex_ids = exercised.get(key, set())
        unex = sorted(reg_ids - ex_ids)
        reverse = sorted(ex_ids - reg_ids)
        registries.setdefault(reg, {})[ns] = {
            "registered": len(reg_ids),
            "exercised": len(ex_ids),
            "unexercised_count": len(unex),
            "unexercised": unex[: args.max_list],
            "reverse_count": len(reverse),
            "reverse": reverse[: args.max_list],
        }
    out = {
        "schema": "registry-census-v1",
        "source": "scene-manifest-entries vs the merged gallery page manifests",
        "totals": {
            "registered": sum(v["registered"] for r in registries.values()
                              for v in r.values()),
            "exercised": sum(v["exercised"] for r in registries.values()
                             for v in r.values()),
            "unexercised": sum(v["unexercised_count"] for r in registries.values()
                               for v in r.values()),
            "reverse": sum(v["reverse_count"] for r in registries.values()
                           for v in r.values()),
        },
        "registries": registries,
        "integrity": {
            "page_manifests_with_content": pages_seen,
            "no_id_column_files": no_id_column[:20],
            "named_gaps": NAMED_GAPS,
        },
    }
    # the stdout table (the receipt line)
    print(f"{'registry':12s} {'ns':24s} {'reg':>6s} {'ex':>6s} {'unex':>6s} {'rev':>5s}")
    for reg, rows in registries.items():
        for ns, v in rows.items():
            print(f"{reg:12s} {ns:24s} {v['registered']:6d} {v['exercised']:6d}"
                  f" {v['unexercised_count']:6d} {v['reverse_count']:5d}")
    t = out["totals"]
    print(f"TOTAL registered={t['registered']} exercised={t['exercised']} "
          f"unexercised={t['unexercised']} reverse={t['reverse']}")
    if args.out_json:
        os.makedirs(os.path.dirname(args.out_json) or ".", exist_ok=True)
        with open(args.out_json, "w", encoding="utf-8") as fh:
            json.dump(out, fh, indent=1, sort_keys=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
