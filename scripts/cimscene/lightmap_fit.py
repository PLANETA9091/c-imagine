#!/usr/bin/env python3
"""14-e75 THE LIGHTMAP FIT (the first cut): regress the per-row ae against
the (captured-pair, shot-pair) classes over the 498 baseline.

The captured pair = the Resolved row's 14-cy (lightBlock, lightSky); the
shot pair = the .light field's front-air row at the cell. The ae per row =
the pixel-difference aggregate — the brightness-driven share = the mean ae
per (captured, shot) class. The out = the table + the implied f() ratio per
shot pair (the tint-correction's curve).
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

BASE = Path("/tmp/e57-art/baseline-498")
SEED = Path("/tmp/e57-art/seed-pack-x")

def main():
    # the resolved rows: the BARE state -> (captured block, captured sky)
    # the pick law = the quad row first (state#q...) — the first #q row's
    # pair serves the bare state, then the plain row's -1 default loses
    cap = {}
    for line in (SEED / "f2v/visual-gallery.resolved.tsv").read_text().splitlines():
        p = line.split("\t")
        if len(p) >= 8 and not p[0].startswith("#"):
            try:
                pair = (int(p[6]), int(p[7]))
            except (ValueError, IndexError):
                continue
            state = p[0]
            bare = state.split("#")[0]
            is_q = "#q" in state
            if bare not in cap or (is_q and cap[bare] is None):
                cap.setdefault(bare, pair if is_q else None)
                if is_q:
                    cap[bare] = pair
    # the .light fields per manifest base: (x,y,z) -> (block, sky)
    fields = {}
    for f in (SEED / "scenes").glob("gallery-*-p*.light"):
        base = f.stem
        d = {}
        for line in f.read_text().splitlines():
            p = line.split()
            if len(p) >= 5 and not line.startswith("#"):
                try:
                    d[(p[0], p[1], p[2])] = (int(p[3]), int(p[4]))
                except ValueError:
                    pass
        fields[base] = d
    # the joins
    cls = defaultdict(lambda: [0, 0])
    skipped_files = 0
    for jf in BASE.glob("join-*.json"):
        if jf.name.startswith("join-menu") or jf.name.startswith("join-e-") or \
           jf.name.startswith("join-item") or jf.name.startswith("join-fluid") or \
           jf.name.startswith("join-iso"):
            continue
        try:
            data = json.loads(jf.read_text())
        except Exception:
            skipped_files += 1
            continue
        for r in data.get("rows", []):
            if r.get("col") is None or r.get("row") is None:
                continue
            # the join grid coords -> the world coords (the wall x = col-5,
            # y = row-60, z = 9)
            key = (str(int(r["col"]) - 5), str(int(r["row"]) - 60), "9")
            fr = fields.get(r.get("page"), {}).get(key)
            cr = cap.get(r.get("state"))
            if fr is None or cr is None:
                continue
            cls[(cr, fr)][0] += r.get("ae", 0)
            cls[(cr, fr)][1] += 1
    print("skipped files:", skipped_files)
    print("classes (captured, shot) -> mean ae / rows:")
    rows = sorted(cls.items(), key=lambda kv: -kv[1][1])
    for (cr, fr), (sae, n) in rows[:25]:
        print(f"  cap={cr} shot={fr} rows={n} mean_ae={sae // max(1, n)}")

if __name__ == "__main__":
    main()
