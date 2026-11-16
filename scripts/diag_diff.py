#!/usr/bin/env python3
"""The diag-vs-frame falsification executor (14-e57d / 14-e57l).

Reads a capture-diag.log (the 14-e51 door-BER lines + the 14-e57 door-head
lines), joins the receipts against the expected frame (ci-artifacts/
e57-expect.txt, scripts/diag_expect.py) as (state, pos) MULTISETS — the diag
lines carry no page name and the wall repeats both states and positions
across pages — and runs the 14-e57f anim law as a self-contained check:
per door-BER line the predicted receipt is anim=1.000 iff open=true /
anim=0.000 iff open=false (the ctor starts at the target, the 0.15/tick
chase never moves it, no NBT persistence); the fallback anim=v<value>
forms are normalized before the compare.

Verdict: PASS iff mismatches == 0 and missing == 0 and extra == 0 (the
14-e57l honest-verdict law — an empty receipt log FAILS, never passes).

Usage: diag_diff.py <capture-diag.log> [expect-file]
"""
import collections
import pathlib
import re
import sys

E51 = re.compile(r"14-e51 door-BER diag (\S+) @([-\d,]+): (.*)$")
E57 = re.compile(r"14-e57 door-head diag (\S+) @([-\d,]+): below=(.*)$")
ANIM = re.compile(r"anim=(\S+)")
UPPER = re.compile(r"upper=(.*)$")
OPEN = re.compile(r"(^|,)open=(true|false)")


def norm_state(s):
    """Both sides carry Block{id}[props]; normalize to id[props]."""
    m = re.match(r"^Block\{([^}]+)\}(\[.*\])?$", s)
    return f"{m.group(1)}{m.group(2) or ''}" if m else s


def parse_anim(v):
    """The %.3f hop, the raw toString garbage, the v<value> fallback."""
    v = v.strip()
    if v.startswith("v"):
        v = v[1:]
    m = re.match(r"^(\d+\.\d+)", v)
    return float(m.group(1)) if m else None


def load_frame(path):
    rows = []
    for line in pathlib.Path(path).read_text().splitlines():
        m = E51.match(line)
        if m:
            um = UPPER.search(m.group(3))
            rows.append((norm_state(m.group(1)), m.group(2), "upper",
                         um.group(1).strip() if um else None))
            continue
        m = E57.match(line)
        if m:
            rows.append((norm_state(m.group(1)), m.group(2), "below",
                         m.group(3).strip()))
    return rows


def load_actual(path):
    rows = []
    anims = []
    for line in pathlib.Path(path).read_text().splitlines():
        m = E51.match(line)
        if m:
            st, pos, rest = m.group(1), m.group(2), m.group(3)
            am = ANIM.search(rest)
            um = UPPER.search(rest)
            if am:
                anims.append((st, pos, parse_anim(am.group(1))))
            if um:
                rows.append((norm_state(st), pos, "upper", um.group(1).strip()))
            continue
        m = E57.match(line)
        if m:
            rows.append((norm_state(m.group(1)), m.group(2), "below",
                         m.group(3).strip()))
    return rows, anims


def main():
    log = sys.argv[1] if len(sys.argv) > 1 else "ci-artifacts/capture-diag.log"
    expect = sys.argv[2] if len(sys.argv) > 2 else "ci-artifacts/e57-expect.txt"

    frame = load_frame(expect)
    actual, anims = load_actual(log)

    fm = collections.Counter((r[0], r[1], r[2]) for r in frame)
    am = collections.Counter((r[0], r[1], r[2]) for r in actual)
    fvals = collections.defaultdict(collections.Counter)
    avals = collections.defaultdict(collections.Counter)
    for r in frame:
        fvals[(r[0], r[1], r[2])][r[3]] += 1
    for r in actual:
        avals[(r[0], r[1], r[2])][r[3]] += 1

    exact, mism, miss, extra = 0, 0, 0, 0
    examples = []
    for key in sorted(set(fm) | set(am)):
        fv, av = fm.get(key, 0), am.get(key, 0)
        if fv and av:
            if fvals[key] == avals[key]:
                exact += min(fv, av)
            else:
                mism += min(fv, av)
                if len(examples) < 12:
                    examples.append(
                        f"MISMATCH {key}: frame={dict(fvals[key])} actual={dict(avals[key])}")
        miss += max(0, fv - av)
        extra += max(0, av - fv)
        if fv and not av and len(examples) < 12:
            examples.append(f"MISSING {key}")
        if av and not fv and len(examples) < 12:
            examples.append(f"EXTRA {key}")

    # the 14-e57f anim law: anim == open?1:0, exact
    anim_ok, anim_bad, anim_nopred = 0, 0, 0
    bad_examples = []
    for st, pos, val in anims:
        om = OPEN.search(st)
        if val is None or om is None:
            anim_nopred += 1
            continue
        pred = 1.0 if om.group(2) == "true" else 0.0
        if abs(val - pred) < 0.0005:
            anim_ok += 1
        else:
            anim_bad += 1
            if len(bad_examples) < 12:
                bad_examples.append(f"{st} @{pos}: anim={val} predicted {pred:.3f}")

    print(f"frame rows: {len(frame)}   actual receipts: {len(actual)}")
    print(f"frame contract: exact={exact} mismatched={mism} missing={miss} extra={extra}")
    for e in examples:
        print(f"  {e}")
    print(f"anim law (open?1:0): ok={anim_ok} bad={anim_bad} unparsable={anim_nopred}"
          f" (of {len(anims)} anim readings)")
    for e in bad_examples:
        print(f"  ANIM {e}")
    verdict = (mism == 0 and miss == 0 and extra == 0)
    print(f"FRAME CONTRACT: {'PASS' if verdict else 'FAIL'}")
    return 0 if verdict else 1


if __name__ == "__main__":
    sys.exit(main())
