#!/usr/bin/env python3
"""Diff a truth run against an ours run, step by step.

Usage: python3 -I diff.py truth.json ours.json -o diff.md

Per step (matched by index):
  equal      both sides have a result and the normalized results are identical
  different  both sides have a result and they differ (both are printed)
  missing    a step is absent on one side, or either side recorded an error
             (the step is not comparable; both sides are printed)
  error      an observation failed on either side (rec "infra": the call errored or returned
             a non-object). Infra, not a parity difference.
Verdict: EQUAL (exit 0) when every step is equal and both sides have the same step count;
DIFFERENT (exit 1) when any step is different or missing; ERROR (exit 2) when there is no
difference or missing step but some observation is an infra error.
"""
import argparse
import json
import os
import sys


def canon(obj):
    return json.dumps(obj, sort_keys=True, ensure_ascii=False)


def describe(step):
    s = step.get("step", {})
    if "observe" in s:
        return "observe %s %s" % (s["observe"], s.get("pos", ""))
    return "%s %s" % (s.get("do", "?"), " ".join(str(x) for x in [s.get("pos", s.get("item", s.get("index", "")))] if x != ""))


def compare(t, o):
    if t is None or o is None:
        return "missing", "step absent on %s" % ("truth" if t is None else "ours")
    if t.get("infra") or o.get("infra"):
        return "error", "infra error on %s" % " and ".join(
            side for side, r in (("truth", t), ("ours", o)) if r.get("infra"))
    if t.get("error") or o.get("error"):
        return "missing", "error on %s" % " and ".join(
            side for side, r in (("truth", t), ("ours", o)) if r.get("error"))
    if canon(t.get("result")) == canon(o.get("result")):
        return "equal", ""
    return "different", ""


def fmt(x):
    return "```json\n" + json.dumps(x, indent=2, sort_keys=True, ensure_ascii=False) + "\n```"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("truth")
    ap.add_argument("ours")
    ap.add_argument("-o", "--out", required=True)
    args = ap.parse_args(argv)
    with open(args.truth, encoding="utf-8") as f:
        T = json.load(f)
    with open(args.ours, encoding="utf-8") as f:
        O = json.load(f)

    ts, os_ = T.get("steps", []), O.get("steps", [])
    n = max(len(ts), len(os_))
    counts = {"equal": 0, "different": 0, "missing": 0, "error": 0}
    rows = []
    body = []
    for i in range(n):
        t = ts[i] if i < len(ts) else None
        o = os_[i] if i < len(os_) else None
        verdict, why = compare(t, o)
        counts[verdict] += 1
        label = describe(t or o or {})
        rows.append("| %d | %s | %s |" % (i, label, verdict + (" (" + why + ")" if why else "")))
        if verdict != "equal":
            body.append("### step %d: %s, %s" % (i, label, verdict))
            if why:
                body.append("reason: %s" % why)
            for side, rec in (("truth", t), ("ours", o)):
                body.append("**%s**" % side)
                if rec is None:
                    body.append("(no record)")
                    continue
                if rec.get("error"):
                    body.append("error: `%s`" % rec["error"])
                if rec.get("result") is not None:
                    body.append(fmt(rec["result"]))
                if rec.get("shot"):
                    body.append("shot: `%s`" % rec["shot"])
            body.append("")

    clean = counts["different"] == 0 and counts["missing"] == 0 and len(ts) == len(os_)
    if clean and counts["error"] == 0:
        verdict_word, code = "EQUAL", 0
    elif counts["different"] or counts["missing"] or len(ts) != len(os_):
        verdict_word, code = "DIFFERENT", 1
    else:
        verdict_word, code = "ERROR", 2
    head = [
        "# diff: %s" % T.get("scenario", "?"),
        "",
        "truth: `%s`  ours: `%s`" % (args.truth, args.ours),
        "",
        "Summary: %d steps, equal %d, different %d, missing %d, error %d. Verdict: **%s**" % (
            n, counts["equal"], counts["different"], counts["missing"], counts["error"], verdict_word),
        "",
        "| # | step | verdict |",
        "|---|---|---|",
    ]
    text = "\n".join(head + rows + [""] + body) + "\n"
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        f.write(text)
    print("%s: equal %d different %d missing %d error %d -> %s" % (
        verdict_word, counts["equal"], counts["different"], counts["missing"], counts["error"], args.out))
    return code


if __name__ == "__main__":
    sys.exit(main())
