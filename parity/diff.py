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

A step may carry "compare" for outcomes that are random on the game side (drop counts, chance drops).
Then the step is equal by the rule, not by identical results:
  {"mode": "present"}  the same set of ids on both sides (ids with count > 0)
  {"mode": "range", "keys": {id: [min, max]}}  the same set of ids, and every count on both sides
      inside its [min, max]; "min"/"max" give the default for an id not in keys, else counts must be equal.
      An id whose range admits 0 may be absent on either side (an optional drop).
  "field": compare only this key of the result (a dict); the other keys must still be equal.
  "ids": compare only these ids (the rest of the field is ignored, e.g. random sticks beside a log count).
A result field that is a list of entities ({type, pos, item?, count?}) counts as {item or type: count}.
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


def _side(rec):
    """The compared value of one side (its result, else its error), for the JSON summary."""
    if rec is None:
        return None
    return rec.get("result") if rec.get("result") is not None else rec.get("error")


COMPARE_MODES = ("range", "present")


def check_compare_spec(spec):
    """Raise ValueError for a malformed "compare" (run.py records the step, diff.py judges it)."""
    if not isinstance(spec, dict):
        raise ValueError("compare must be an object")
    if spec.get("mode") not in COMPARE_MODES:
        raise ValueError("compare mode must be one of %s" % (COMPARE_MODES,))
    if "field" in spec and not isinstance(spec["field"], str):
        raise ValueError("compare field must be a string")
    if "ids" in spec and not (isinstance(spec["ids"], list) and all(isinstance(i, str) for i in spec["ids"])):
        raise ValueError("compare ids must be a list of strings")
    for id_, rng in (spec.get("keys") or {}).items():
        if not (isinstance(rng, list) and len(rng) == 2 and rng[0] <= rng[1]):
            raise ValueError("compare keys[%s] must be [min, max]" % id_)


def counts_of(value):
    """A counts map {id: n}, or an entity list [{type, pos, item?, count?}] as {item or type: n}."""
    out = {}
    if isinstance(value, dict):
        for k, v in value.items():
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                out[k] = out.get(k, 0) + int(v)
        return out
    if isinstance(value, list):
        for e in value:
            if not isinstance(e, dict):
                raise ValueError("entity list holds %r, not an object" % (e,))
            if e.get("item"):
                key, n = e["item"], int(e.get("count") or 1)
            else:
                key, n = e.get("type"), 1
            if key is not None:
                out[key] = out.get(key, 0) + n
        return out
    raise ValueError("compare value is neither a counts map nor an entity list: %r" % (value,))


def compare_rule(tres, ores, spec):
    """(verdict, why) for two results under a compare spec. Verdict is equal or different."""
    check_compare_spec(spec)
    field = spec.get("field")
    tv, ov = tres, ores
    if field is not None:
        if not (isinstance(tv, dict) and isinstance(ov, dict) and field in tv and field in ov):
            return "different", "field %r missing on a side" % field
        rest_t = {k: v for k, v in tv.items() if k != field}
        rest_o = {k: v for k, v in ov.items() if k != field}
        if canon(rest_t) != canon(rest_o):
            return "different", "results other than %r differ" % field
        tv, ov = tv[field], ov[field]
    tc, oc = counts_of(tv), counts_of(ov)
    if spec.get("ids") is not None:
        keep = set(spec["ids"])
        tc = {k: v for k, v in tc.items() if k in keep}
        oc = {k: v for k, v in oc.items() if k in keep}
    keys = spec.get("keys") or {}
    dmin, dmax = spec.get("min"), spec.get("max")

    def bounds(id_):
        if id_ in keys:
            return keys[id_]
        if dmin is not None or dmax is not None:
            return [dmin if dmin is not None else 0, dmax if dmax is not None else float("inf")]
        return None

    for id_ in sorted(set(tc) | set(oc)):
        b = bounds(id_)
        tn, on = tc.get(id_, 0), oc.get(id_, 0)
        optional = b is not None and b[0] <= 0
        if (tn > 0) != (on > 0) and not optional:
            return "different", "id %s present on one side only (truth %d, ours %d)" % (id_, tn, on)
        if spec["mode"] == "present":
            continue
        if b is None:
            if tn != on:
                return "different", "count of %s differs (truth %d, ours %d)" % (id_, tn, on)
            continue
        for side, n in (("truth", tn), ("ours", on)):
            if n == 0 and optional:
                continue
            if not (b[0] <= n <= b[1]):
                return "different", "%s count of %s is %d, outside [%s, %s]" % (side, id_, n, b[0], b[1])
    return "equal", ""


def compare(t, o):
    if t is None or o is None:
        return "missing", "step absent on %s" % ("truth" if t is None else "ours")
    if t.get("infra") or o.get("infra"):
        return "error", "infra error on %s" % " and ".join(
            side for side, r in (("truth", t), ("ours", o)) if r.get("infra"))
    if t.get("error") or o.get("error"):
        return "missing", "error on %s" % " and ".join(
            side for side, r in (("truth", t), ("ours", o)) if r.get("error"))
    spec = ((t.get("step") or {}).get("compare")) or ((o.get("step") or {}).get("compare"))
    if spec:
        try:
            return compare_rule(t.get("result"), o.get("result"), spec)
        except ValueError as e:
            return "different", "compare: %s" % e
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
    ap.add_argument("--json", default=None, help="also write {verdict, counts, first_diff} as JSON")
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
    first = None
    for i in range(n):
        t = ts[i] if i < len(ts) else None
        o = os_[i] if i < len(os_) else None
        verdict, why = compare(t, o)
        counts[verdict] += 1
        label = describe(t or o or {})
        rows.append("| %d | %s | %s |" % (i, label, verdict + (" (" + why + ")" if why else "")))
        if verdict != "equal" and first is None:
            first = {
                "i": i, "step": label, "verdict": verdict, "why": why,
                "truth": canon(_side(t)), "ours": canon(_side(o)),
            }
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
    if args.json:
        summary = {
            "scenario": T.get("scenario", "?"),
            "verdict": verdict_word,
            "counts": counts,
            "first_diff": first,
        }
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(summary, f, indent=2, sort_keys=True, ensure_ascii=False)
            f.write("\n")
    print("%s: equal %d different %d missing %d error %d -> %s" % (
        verdict_word, counts["equal"], counts["different"], counts["missing"], counts["error"], args.out))
    return code


if __name__ == "__main__":
    sys.exit(main())
