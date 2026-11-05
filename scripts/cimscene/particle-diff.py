#!/usr/bin/env python3
"""PARTICLE-STREAM channel join for the cimscene gallery (2026-11-05,
DIAGNOSTIC-first).

Joins the two legs' ordered particle-type streams for one page:
  truth  gallery-particle-<ns>-p<p>.log — the packet-level capture the
         scene-mod packet-capture mixin's particle branch wrote while the
         page spawned each registered ParticleType in order (one type id
         per line, arrival order);
  ours   gallery-particle-<ns>-p<p>.log — the SAME artifact shape, written
         by the plugin replay: one REAL ClientboundLevelParticlesPacket per
         manifest cell through the F2V visual-particles.tsv rewrite, the
         POST-rewrite id the vanilla 26.3 client received. Without the
         rewrite file on a seed the ours log is EMPTY — the join names the
         whole page as missing ids (the honest backlog; an empty-but-PRESENT
         file proves the replay ran).

THE OPTIONS CONTRACT lives in the mapping script's docstring
(gallery-particles-mapping.py): modded options bytes are NOT portable —
the replay rebuilds simple default options, so data-carrying particles
DEGRADE to the simple lookalike by contract and the stream records the
rewrite. The pixel shots stay a separate (older) class; THIS join is the
stream proof.

Exact ordered equality per page (the stream IS the channel): a permutation
or an extra/missing id is a row-level mismatch, named below. Exit 0 =
GREEN (streams equal), exit 2 = RED (mismatch), exit 3 = missing artifact.
The GALLERY JOIN RUNS THIS SCRIPT DIAGNOSTICALLY (`|| true`) until the
particle channel is measured stable — the same flip law as sound-diff.py
(the truth names the MODDED type ids; the flip would resolve them through
visual-particles.tsv before comparing; --map previews that, never a
verdict).

Usage:
    particle-diff.py --truth <truth.log> --ours <ours.log> [--ns N --page P]
                     [--map visual-particles.tsv] [--out report.json]
"""
import argparse
import json
import pathlib
import sys

MAX_ROWS = 200  # bound the printed diff; the full lists live in the artifacts


def load_stream(path, label):
    """The ordered type-id stream: one id per line, arrival order. A
    missing file is exit-3 (a crashed walk is not an empty stream)."""
    p = pathlib.Path(path)
    if not p.is_file():
        print(f"particle-diff: {label} log MISSING: {p}")
        return None
    return [ln.strip() for ln in p.read_text(encoding="utf-8").splitlines()
            if ln.strip()]


def load_mapping(path):
    """visual-particles.tsv: modded_id -> vanilla target (comments skipped)."""
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
    args = ap.parse_args()

    label = f"ns={args.ns or '?'} page={args.page or '?'}"
    truth = load_stream(args.truth, "truth")
    ours = load_stream(args.ours, "ours")
    if truth is None or ours is None:
        print(f"particle-diff: {label} ARTIFACT-MISSING — a leg's capture log "
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
    # stream is a different observable behavior (the page spawns the types
    # in registry order; both legs run the same manifest)
    ordered_equal = truth == ours

    verdict = "GREEN" if (ordered_equal and not missing and not extra) else "RED"
    print(f"particle-diff: {label} {verdict} truth={len(truth)} ours={len(ours)} "
          f"missing={len(missing)} extra={len(extra)} ordered_equal={ordered_equal}")
    for s in missing[:MAX_ROWS]:
        print(f"  MISSING {s}")
    for s in extra[:MAX_ROWS]:
        print(f"  EXTRA   {s}")

    # REPORT-ONLY flip preview (--map): how many truth spawns, resolved
    # through the mapping, are answered by the ours stream at the SAME
    # position — the shape of the future flipped verdict, never the verdict
    mapped = None
    if args.map:
        try:
            m = load_mapping(args.map)
            expected = [m.get(s, s) for s in truth]
            hits = sum(1 for i, e in enumerate(expected)
                       if i < len(ours) and ours[i] == e)
            mapped = {"expected": len(expected), "position_hits": hits,
                      "order_exact": expected == ours}
            print(f"particle-diff: {label} MAPPED-PREVIEW hits={hits}/{len(expected)} "
                  f"order_exact={mapped['order_exact']} (report-only, never a verdict)")
        except OSError as ose:
            print(f"particle-diff: {label} map unreadable: {ose}")

    if args.out:
        pathlib.Path(args.out).write_text(json.dumps({
            "ns": args.ns, "page": args.page, "verdict": verdict,
            "truth_count": len(truth), "ours_count": len(ours),
            "missing": missing, "extra": extra,
            "ordered_equal": ordered_equal,
            "mapped_preview": mapped}, indent=1))
    return 0 if verdict == "GREEN" else 2


if __name__ == "__main__":
    sys.exit(main())
