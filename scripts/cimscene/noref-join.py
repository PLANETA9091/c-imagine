#!/usr/bin/env python3
"""14-e186 THE R-B3 STEP-0 NO-REF JOIN — the e183 named tool ("one command off
the visual-tints-cells ledger"), CORRECTED at 14-e188 by the boot-log dive.

Joins a flight's block-lane RED rows to the seed-pack ledgers:
  1. visual-gallery.resolved.tsv  (state -> ref) — the resolved-ref identity;
  2. visual-gallery-scales.tsv    (ref  -> k)   — the scale attribution
     (the missing ref defaults to k=1.0 — the unscale table carries only
     the compensation entries);
  3. visual-tints-cells.tsv       (state@page:cX,rY -> factor) — the per-cell
     tint identity for the states the resolved ledger does not carry.

THE THREE-TIER JOIN LAW (the e188 correction — the boot-log dive proved the
ledger keys come in TWO formats, plain `state[props]` and hash-suffixed
`state[props]#q<hex>`; 35,594 of the 96,529 #566 ledger keys are #q-suffixed
and the original two-tier law mis-filed every #q-keyed state as "no-ref"):
  tier 1  exact    — the RED state string == a plain ledger key;
  tier 2  #q-strip — the RED state string == a ledger key minus its #q suffix;
  tier 3  bare-id  — the gallery-diff demotion (state minus [props] and #q).
Under the three-tier law the #566 block lane = 769 + 119 + 0: the "119-row
no-resolve class" of e183/e186 = the #q key-format artifact, the TRUE no-ref
count = 0.

Outputs the mass-ranked attribution:
  - the three resolved tiers, the k split over all resolved rows,
  - the per-cell tinted subset (state@page hits the tints-cells ledger).

Usage:
  python3 scripts/cimscene/noref-join.py --vp /tmp/vp566_read \
      --ledgers /tmp/sp566_ledger [--lane block] [--top 15]
"""
import argparse
import collections
import glob
import json
import os


def bare(state):
    return state.split("[", 1)[0].split("#q", 1)[0]


def load_join_reds(vp, lane):
    """(page, state) pairs of verdict==RED rows whose page matches the lane."""
    rows = []
    for jf in glob.glob(os.path.join(vp, "gallery-report-*", "join-*.json")):
        try:
            d = json.load(open(jf))
        except Exception:
            continue
        for r in d.get("rows") or []:
            if not isinstance(r, dict) or r.get("verdict") != "RED":
                continue
            page = str(r.get("page") or "")
            state = str(r.get("state") or "")
            if lane == "block":
                # the block lane = the page-tagged plain gallery-<ns>-pN rows
                # only (the measured #566 census: 888 such rows; the class
                # summary's block 900 = 888 + the 12 menu-join rows whose page
                # is empty; the NO-PAIR 14 = the 12 menu + the 2 gallery-entity)
                if not page.startswith("gallery-"):
                    continue
                if page.startswith("gallery-item-") or page.startswith("gallery-entity-"):
                    continue
            rows.append((page, state))
    return rows


def load_resolved(path):
    """(plain_exact, q_stripped) — the two ledger key formats, one lookup each."""
    m = {}
    q = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 3:
                m[parts[0]] = parts[2]
                q.setdefault(parts[0].split("#q", 1)[0], parts[2])
    return m, q


def load_scales(path):
    m = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 2:
                try:
                    m[parts[0]] = float(parts[1])
                except ValueError:
                    pass
    return m


def load_tints_cells(path):
    """state@page:cX,rY -> row; also the state@page -> count index."""
    exact = {}
    by_state_page = collections.Counter()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            key = line.split("\t", 1)[0]
            exact[key] = line
            if "@" in key:
                sp = key.rsplit(":", 1)[0]
                by_state_page[sp] += 1
    return exact, by_state_page


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vp", required=True, help="verdict-pull out dir")
    ap.add_argument("--ledgers", required=True, help="dir with the seed-pack f2v/*.tsv")
    ap.add_argument("--lane", default="block")
    ap.add_argument("--top", type=int, default=15)
    a = ap.parse_args()

    resolved, resolved_q = load_resolved(
        os.path.join(a.ledgers, "visual-gallery.resolved.tsv"))
    scales = load_scales(os.path.join(a.ledgers, "visual-gallery-scales.tsv"))
    tints_exact, tints_sp = load_tints_cells(os.path.join(a.ledgers, "visual-tints-cells.tsv"))

    reds = load_join_reds(a.vp, a.lane)
    print(f"lane={a.lane} RED rows: {len(reds)}")

    exact_ref = 0
    qstrip_ref = 0
    bare_ref = 0
    k_dist = collections.Counter()
    k1_rows = 0
    k1_states = set()
    kgt1_rows = 0
    kgt1_states = set()
    noref_rows = []
    noref_states = set()
    tinted_rows = 0
    tinted_states = set()
    tinted_tier = collections.Counter()
    vanilla_noref = collections.Counter()
    state_rows = collections.Counter()

    for page, state in reds:
        state_rows[state] += 1
        ref = resolved.get(state)
        how = None
        if ref is not None:
            how = "exact-plain"
            exact_ref += 1
        else:
            ref = resolved_q.get(state)
            if ref is not None:
                how = "q-strip"
                qstrip_ref += 1
            else:
                b = bare(state)
                ref = resolved.get(b) or resolved_q.get(b)
                if ref is not None:
                    how = "bare"
                    bare_ref += 1
        if ref is not None:
            k = scales.get(ref, 1.0)
            k_dist[k] += 1
            if k == 1.0:
                k1_rows += 1
                k1_states.add(state)
            else:
                kgt1_rows += 1
                kgt1_states.add(state)
        else:
            noref_rows.append((page, state))
            noref_states.add(state)
            sp = f"{state}@{page}"
            n = tints_sp.get(sp, 0)
            if n:
                tinted_rows += 1
                tinted_states.add(state)
            else:
                ns = state.split(":", 1)[0] if ":" in state else state
                vanilla_noref[ns] += 1

    states_total = len(state_rows)
    print(f"distinct states: {states_total}")
    print(f"tier1 exact-plain:  {exact_ref} rows")
    print(f"tier2 q-strip:      {qstrip_ref} rows")
    print(f"tier3 bare-demotion: {bare_ref} rows")
    print(f"k=1 (no rescale): {k1_rows} rows / {len(k1_states)} states")
    print(f"k>1 (rescaled):   {kgt1_rows} rows / {len(kgt1_states)} states")
    print(f"no-resolved-ref (TRUE, post-e188):  {len(noref_rows)} rows / "
          f"{len(noref_states)} states")
    print(f"  of which per-cell-tinted (state@page in tints-cells): "
          f"{tinted_rows} rows / {len(tinted_states)} states")
    print(f"  bare remainder by namespace: {dict(vanilla_noref.most_common(8))}")
    print("top no-ref states by rows:")
    for st, n in state_rows.most_common():
        if st in noref_states and n and n >= 1:
            print(f"  {n}\t{st[:110]}")
            n -= 1
            if n <= 0:
                break
    by_state_noref = collections.Counter()
    for _page, st in noref_rows:
        by_state_noref[st] += 1
    for st, n in by_state_noref.most_common(a.top):
        print(f"  {n}\t{st[:110]}")


if __name__ == "__main__":
    main()
