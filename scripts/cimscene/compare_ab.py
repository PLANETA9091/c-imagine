#!/usr/bin/env python3
"""14-e61 THE A/B ARBITER COMPANION: diff the page-composite run's join JSONs
against the per-cell baseline for the SAME page(s).

The e60 design's pre-registered prediction: the page lane's crops collapse the
door lane's reds toward zero IF the context-bleed law is the whole mechanism.
This tool measures exactly that: per cell, the baseline ae vs the page-lane
ae, the verdict flips, and the residual class (the e58h BACKDROP hue candidate
= a small ae with luma_norm flipped).

Usage:
  python3 scripts/cimscene/compare_ab.py BASELINE_DIR PAGE_RUN_DIR [PAGE ...]

  BASELINE_DIR  the per-cell run's gallery-report dir (the join-*.json files)
  PAGE_RUN_DIR  the page-lane run's gallery-report dir
  PAGE          optional page filter, e.g. gallery-create-p16 (default: every
                page present in BOTH dirs with a join JSON)

The output: per page a verdict line + per-cell lines for every cell that is
RED in either run (the flip set is the arbiter's receipt).
"""
import json
import sys
from pathlib import Path


def load_rows(report_dir: Path, page: str):
    # the join files name the ns-page pair (join-create-p16.json); the
    # callers pass the manifest base (gallery-create-p16) — strip it
    page_key = page[len("gallery-"):] if page.startswith("gallery-") else page
    p = report_dir / f"join-{page_key}.json"
    if not p.is_file():
        return None
    data = json.loads(p.read_text())
    out = {}
    for r in data.get("rows", []):
        # the TALL-2 door pairs share a crop box: the state string keeps
        # both rows distinct (the join emits one row per manifest cell)
        key = (r.get("col"), r.get("row"), r.get("state"))
        out[key] = r
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    base_dir = Path(sys.argv[1])
    page_dir = Path(sys.argv[2])
    pages = sys.argv[3:]
    if not pages:
        names = {p.name for p in base_dir.glob("join-*.json")}
        names &= {p.name for p in page_dir.glob("join-*.json")}
        pages = sorted(n[len("join-"):-len(".json")] for n in names)
    flips = 0
    for page in pages:
        a = load_rows(base_dir, page)
        b = load_rows(page_dir, page)
        if a is None or b is None:
            print(f"{page}: MISSING JOIN (base={'ok' if a else 'none'}, page={'ok' if b else 'none'})")
            continue
        red_a = {k for k, r in a.items() if str(r.get("verdict", "")).upper() == "RED"}
        red_b = {k for k, r in b.items() if str(r.get("verdict", "")).upper() == "RED"}
        fixed = red_a - red_b
        broken = red_b - red_a
        both = red_a & red_b
        flips += len(fixed) - len(broken)
        verdict = "COLLAPSED" if red_a and not red_b else (
            "UNCHANGED-RED" if red_a and red_b else ("CLEAN-BOTH" if not red_a else "BROKE"))
        print(f"{page}: {verdict}  base_red={len(red_a)} page_red={len(red_b)} "
              f"fixed={len(fixed)} broke={len(broken)} stayed={len(both)}")
        def short(k):
            st = k[2] or ""
            st = st.split("[")[0].replace("create:", "").replace("minecraft:", "") + \
                ("[" + st[st.index("[") + 1:-1] + "]" if "[" in st else "")
            return f"c{k[0]} r{k[1]} {st}"
        for k in sorted(fixed):
            print(f"  FIXED   {short(k)} ae {a[k].get('ae')} -> {b[k].get('ae')} "
                  f"ls {a[k].get('luma_scale')} -> {b[k].get('luma_scale')}")
        for k in sorted(broken):
            print(f"  BROKE   {short(k)} ae {b[k].get('ae')} "
                  f"ls {b[k].get('luma_scale')}")
        for k in sorted(both):
            ra, rb = a[k], b[k]
            print(f"  STAYED  {short(k)} ae {ra.get('ae')} -> {rb.get('ae')} "
                  f"ls {ra.get('luma_scale')} -> {rb.get('luma_scale')}")
    print(f"\nNET FLIPS: {flips:+d} (positive = the page lane fixed more than it broke)")


if __name__ == "__main__":
    main()
