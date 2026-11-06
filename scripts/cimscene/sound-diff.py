#!/usr/bin/env python3
"""SOUND-channel join for the cimscene gallery (2026-11-04, DIAGNOSTIC-first).

Joins the two legs' ordered sound-event streams for one page:
  truth  gallery-sound-<ns>-p<p>.log — the packet-level capture the
         scene-mod sound-packet mixin wrote while the page played each
         registered SoundEvent in order (one event id per line, arrival
         order);
  ours   gallery-sound-<ns>-p<p>.log — the SAME artifact shape, written by
         the plugin replay: since 2026-11-05 the replay sends one REAL
         ClientboundSoundPacket per manifest cell through the F2V
         visual-sounds.tsv rewrite and records the POST-rewrite id the
         vanilla 26.3 client received. Without the rewrite file on a seed
         the ours log is EMPTY (every row nochannel) — the join names the
         whole page as missing ids; that is the honest backlog, not a
         silence (an empty-but-PRESENT file proves the replay ran).

Exact ordered equality per page (the stream IS the channel): a permutation
or an extra/missing id is a row-level mismatch, named below. Exit 0 =
GREEN (streams equal), exit 2 = RED (mismatch), exit 3 = missing artifact.
The GALLERY JOIN RUNS THIS SCRIPT DIAGNOSTICALLY (`|| true`) until the
sound channel is measured stable — the flip law is documented in
coverage-gate.py (DIAGNOSTIC_CLASSES) and the class comments.

THE FLIP (documented, not yet applied): the truth stream names the MODDED
event ids and the ours stream names the vanilla ids the client received,
so raw equality can never go green while the rewrite is a stand-in. When
the channel is measured stable, the flip resolves the truth ids through
visual-sounds.tsv (the same mapping the replay used) and compares the
RESOLVED streams — that verdict then means "the client heard exactly the
mapped stand-in for every truth event, in order". `--map` gives a
report-only preview of that comparison today (mapped-position coverage),
it never changes the verdict.

Usage:
    sound-diff.py --truth <truth.log> --ours <ours.log> [--ns N --page P]
                  [--map visual-sounds.tsv] [--out report.json]
"""
import argparse
import json
import pathlib
import sys

MAX_ROWS = 200  # bound the printed diff; the full lists live in the artifacts


def load_stream(path, label):
    """The ordered event-id stream: one id per line, arrival order. A
    missing file is exit-3 (a crashed walk is not an empty stream)."""
    p = pathlib.Path(path)
    if not p.is_file():
        print(f"sound-diff: {label} log MISSING: {p}")
        return None
    return [ln.strip() for ln in p.read_text(encoding="utf-8").splitlines()
            if ln.strip()]


def load_mapping(path):
    """visual-sounds.tsv: modded_id -> vanilla target (comments skipped)."""
    m = {}
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            parts = line.split("\t")
            if len(parts) >= 2:
                m.setdefault(parts[0], parts[1])
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--truth", required=True)
    ap.add_argument("--ours", required=True)
    ap.add_argument("--ns", default="")
    ap.add_argument("--page", default="")
    ap.add_argument("--map")
    ap.add_argument("--out")
    ap.add_argument("--gate-mapped", action="store_true",
                    help="the flipped verdict: GREEN iff the ours stream "
                         "answers every truth event's MAPPED target at the "
                         "same position (raw modded-id equality is "
                         "impossible by construction — truth records "
                         "modded ids, ours records post-rewrite vanilla "
                         "ids). Requires --map; without the flag the "
                         "verdict stays the raw one (today's behavior).")
    args = ap.parse_args()

    label = f"ns={args.ns or '?'} page={args.page or '?'}"
    truth = load_stream(args.truth, "truth")
    ours = load_stream(args.ours, "ours")
    if truth is None or ours is None:
        print(f"sound-diff: {label} ARTIFACT-MISSING — a leg's capture log "
              "does not exist (a crashed walk is not an empty stream)")
        if args.out:
            pathlib.Path(args.out).write_text(json.dumps({
                "ns": args.ns, "page": args.page,
                "verdict": "ARTIFACT-MISSING"}, indent=1))
        return 3

    truth_set, ours_set = set(truth), set(ours)
    missing = [s for s in truth if s not in ours_set]
    extra = [s for s in ours if s not in truth_set]
    # ORDER check: exact stream equality, not set equality — a reordered
    # stream is a different observable behavior (the page plays the events
    # in registry order; both legs run the same manifest)
    ordered_equal = truth == ours

    # THE MAPPED VERDICT (--gate-mapped, requires --map): resolve every
    # truth event through the mapping and require the ours stream to match
    # EXACTLY, in order. An unmapped truth id resolves to itself, which
    # the ours stream (post-rewrite vanilla ids) can never answer — the
    # honest pressure to extend the mapping instead of a silent pass.
    # Without the flag the verdict stays the raw one (the gallery calls
    # unflagged until the owner flips).
    gate_error = None
    mapped = None
    if args.gate_mapped:
        if not args.map:
            print(f"sound-diff: {label} --gate-mapped requires --map")
            if args.out:
                pathlib.Path(args.out).write_text(json.dumps({
                    "ns": args.ns, "page": args.page,
                    "verdict": "GATE-CONFIG-ERROR",
                    "gate_error": "--gate-mapped requires --map"}, indent=1))
            return 4
        try:
            m = load_mapping(args.map)
            expected = [m.get(s, s) for s in truth]
            hits = sum(1 for i, e in enumerate(expected)
                       if i < len(ours) and ours[i] == e)
            order_exact = expected == ours
            mapped = {"expected": len(expected), "position_hits": hits,
                      "order_exact": order_exact}
            verdict = "GREEN" if order_exact else "RED"
            raw_ordered_equal = ordered_equal
            print(f"sound-diff: {label} {verdict} MAPPED-GATE "
                  f"hits={hits}/{len(expected)} raw_ordered_equal={raw_ordered_equal}")
        except OSError as ose:
            gate_error = str(ose)
            print(f"sound-diff: {label} map unreadable: {gate_error}")
            verdict = "RED"
    else:
        verdict = "GREEN" if (ordered_equal and not missing and not extra) else "RED"
        print(f"sound-diff: {label} {verdict} truth={len(truth)} ours={len(ours)} "
              f"missing={len(missing)} extra={len(extra)} ordered_equal={ordered_equal}")
        for s in missing[:MAX_ROWS]:
            print(f"  MISSING {s}")
        for s in extra[:MAX_ROWS]:
            print(f"  EXTRA   {s}")

    # REPORT-ONLY flip preview (--map without --gate-mapped): how many
    # truth events, resolved through the mapping, are answered by the ours
    # stream at the SAME position
    if args.map and not args.gate_mapped:
        try:
            m = load_mapping(args.map)
            expected = [m.get(s, s) for s in truth]
            hits = sum(1 for i, e in enumerate(expected)
                       if i < len(ours) and ours[i] == e)
            mapped = {"expected": len(expected), "position_hits": hits,
                      "order_exact": expected == ours}
            print(f"sound-diff: {label} MAPPED-PREVIEW hits={hits}/{len(expected)} "
                  f"order_exact={mapped['order_exact']} (report-only, never a verdict)")
        except OSError as ose:
            print(f"sound-diff: {label} map unreadable: {ose}")

    if args.out:
        pathlib.Path(args.out).write_text(json.dumps({
            "ns": args.ns, "page": args.page, "verdict": verdict,
            "truth_count": len(truth), "ours_count": len(ours),
            "missing": missing, "extra": extra,
            "ordered_equal": ordered_equal,
            "gate_mapped": bool(args.gate_mapped),
            "gate_error": gate_error,
            "mapped_preview": mapped}, indent=1))
    return 0 if verdict == "GREEN" else 2


if __name__ == "__main__":
    sys.exit(main())
