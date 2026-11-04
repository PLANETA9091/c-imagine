#!/usr/bin/env python3
"""The GREEN campaign measurement: merge a gallery run's 8 gallery-report-*
artifacts (already unzipped under <dir>/art-*/), print the worklog-formula
green-share (rows verdicts over join-p*.json only) + the RED breakdown by
block family. Usage: measure_gal.py <dir> [<label>]"""
import collections
import glob
import json
import re
import sys


def main() -> int:
    d = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else d
    agg = collections.Counter()
    red_rows = []
    for jf in sorted(glob.glob(d + "/art-*/join-p*.json")):
        j = json.load(open(jf))
        for r in j["rows"]:
            agg[r["verdict"]] += 1
            if r["verdict"] == "RED":
                red_rows.append((r["state"], r["ae"], jf))
    g, r, s = agg["GREEN"], agg["RED"], agg["SPILL"]
    print(f"{label}: GREEN {g} / RED {r} / SPILL {s} / DYN {agg['DYNAMIC']} / "
          f"AIR {agg.get('AIR', 0)} / ANIM {agg.get('ANIMATED', 0)} -> "
          f"green-share {100 * g / (g + r):.2f}%")
    fam = collections.Counter()
    ae_by_blk = collections.Counter()
    for st, ae, _ in red_rows:
        blk = st.split(":")[1].split("[")[0]
        fam[blk] += 1
        ae_by_blk[blk] += ae
    print(f"distinct RED blocks: {len(fam)}")
    for blk, c in fam.most_common(15):
        print(f"  {c:4d} ae={ae_by_blk[blk]:8d} {blk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
