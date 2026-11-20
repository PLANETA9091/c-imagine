#!/usr/bin/env python3
"""14-e179 THE SECOND-FRAME POLICY PRODUCER (the R-c refactor, the hashed lane).

The block-lane determinism producer used to live INLINE in
.github/workflows/gallery.yml (an awk dyn census + an `if [ DYNC -gt 0 ]`
guard per leg, the 14-br law): invisible to every future edit that does not
also touch the job text, and once moved out of the job text it would be
invisible to the shard cache keys entirely — the stale-seed trap (a policy
edit silently serving the old probe plan from a cache hit). The policy now
lives HERE, a hashed repo file: the wall-truth shard key hashes this script
(scripts/cimscene/second-frames.py added to its hashFiles list), so any
policy change re-keys the truth shards and re-renders them. The workflow
EXECUTES the plan (the 14-e15 coproc law keeps the rcon/shot protocol in
bash); this file only DECIDES — a pure function of (manifest, policy).

The probe = the 14-aj/14-br self-consistency pair: two extra shots of the
SAME placed page ~2s apart (t0, t1) per leg. The join passes t1 as
--truth-self/--ours-self (gallery-report/join-*.json rows carry self_ae);
fidelity-forensics.py then classifies every RED row:

  FIDELITY   both legs stable (self_ae <= gate)  — the deterministic target
  ANIM-SELF  a leg disagrees with itself         — the demote-law's next input
  NO-PAIR    no self frame passed                — UNDECIDABLE

Policies (--policy):
  legacy   the 14-br law verbatim: probe ONLY when the manifest census
           counts dyn=1 cells (the anim liveness probe; static pages stay
           one-shot). Byte-compatible with the inline awk it replaces —
           the census law replicated exactly: rows with $1=="cell" and the
           RAW 6th tab-field == "1" (no strip: awk $6=="1" does not match
           "1 "). A missing/unreadable manifest = no probes (the awk died
           under set -e on a missing file; the producer degrades to
           no-probes instead — the safer contract, documented).
  widened  the R-c rung (14-e179): probe EVERY walked block page. The
           NO-PAIR hole (the e161b forensics census: 533 of 1025 red rows,
           later 698 — undecidable without a second frame) closes: every
           RED row gains a measured self pair. Zero green->red risk by
           construction (gallery-diff classify: `not over -> GREEN` before
           the animated branch; only a RED row demotes, the 14-ag law).

The particle/fluid/entity lanes already shoot a self frame on every page
(14-dx/14-bw/14-aj) — the block lane was the last dyn-gated hole; the iso
lane keeps its diagnostic-only inline probe (not gated, out of scope).

Usage:
  second-frames.py plan --manifest <tsv> [--ns NS] [--page P]
                        [--policy legacy|widened]

stdout = the action contract: zero or more lines, exactly `t0` then `t1`
(bash shoots t0, sleeps 2s, shoots t1 — the same order the inline law
used). stderr = the one-line decision receipt for the job log (the census,
the policy, the decision) — the landing read measures the widened shot
counts from these lines.
"""
import argparse
import pathlib
import sys


def dyn_census(manifest: pathlib.Path):
    """The inline awk law, replicated exactly.

    awk -F'\\t' '$1=="cell" && $6=="1"{n++} END{print n+0}':
    rows whose first tab-field is `cell` and whose RAW sixth tab-field is
    exactly `1` (no strip — awk compares the field string verbatim). Rows
    with fewer than 6 fields never match ($6 is empty there).

    Returns None when the manifest is unreadable — the missing-page case
    the walk loop must never probe against (a probe against a page that
    never placed compares different content and is meaningless, the
    workflow's own law). The inline awk instead died under `set -e` on a
    missing file; the producer degrades to no-probes (the safer contract,
    documented).
    """
    try:
        text = manifest.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    count = 0
    for line in text.splitlines():
        fields = line.split("\t")
        if len(fields) >= 6 and fields[0] == "cell" and fields[5] == "1":
            count += 1
    return count


def plan_actions(census, policy: str) -> list:
    """The decision: which second frames this page shoots.

    census None = the manifest is missing/unreadable: no probe on EITHER
    policy. Present manifest: widened probes always (the R-c rung);
    legacy probes only census>0 pages (the 14-br law).
    """
    if census is None:
        return []
    if policy == "widened":
        return ["t0", "t1"]
    if census > 0:
        return ["t0", "t1"]
    return []


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("command", choices=["plan"],
                    help="print the second-frame actions for one page")
    ap.add_argument("--manifest", required=True,
                    help="the page's manifest tsv (the census input)")
    ap.add_argument("--ns", default="",
                    help="namespace, for the stderr receipt only")
    ap.add_argument("--page", default="",
                    help="page index, for the stderr receipt only")
    ap.add_argument("--policy", default="widened", choices=["legacy", "widened"],
                    help="legacy = the 14-br law (dyn pages only); "
                         "widened = every walked block page (14-e179)")
    args = ap.parse_args()

    census = dyn_census(pathlib.Path(args.manifest))
    actions = plan_actions(census, args.policy)
    # The stderr receipt (the job log = the landing read's measurement lane).
    print(f"second-frames: ns={args.ns} page={args.page} "
          f"census={census if census is not None else 'missing'} "
          f"policy={args.policy} -> "
          f"{' '.join(actions) if actions else 'none'}", file=sys.stderr)
    for action in actions:
        print(action)
    return 0


if __name__ == "__main__":
    sys.exit(main())
