#!/usr/bin/env python3
"""Run every scenario on both stacks, diff them, write results/<id>/ and results/SUMMARY.md.

Usage: python3 -I all.py [--only SUBSTR ...] [--stacks truth,ours] [--no-shots]
Per scenario: results/<id>/truth.json, ours.json, diff.md, shots/{truth,ours}/.
Each run is a subprocess (run.py / diff.py), so one crash does not stop the batch.
"""
import argparse
import glob
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.environ.get("PARITY_RESULTS", os.path.join(HERE, "results"))  # results/ is not committed


def run(args):
    return subprocess.run([sys.executable, "-I"] + args, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=1800)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--stacks", default="truth,ours")
    ap.add_argument("--no-shots", action="store_true")
    args = ap.parse_args(argv)
    stacks = args.stacks.split(",")
    rows = []
    for path in sorted(glob.glob(os.path.join(HERE, "scenarios", "*.json"))):
        sid = os.path.splitext(os.path.basename(path))[0]
        if args.only and not any(o in sid for o in args.only):
            continue
        d = os.path.join(RESULTS, sid)
        os.makedirs(d, exist_ok=True)
        outs = {}
        for st in stacks:
            out = os.path.join(d, st + ".json")
            cmd = [os.path.join(HERE, "run.py"), "--stack", st, path, "-o", out]
            if not args.no_shots:
                cmd += ["--shots", os.path.join(d, "shots")]
            t0 = time.time()
            p = run(cmd)
            print(p.stdout.decode("utf-8", "replace").strip(), "(rc=%d, %.0fs)" % (p.returncode, time.time() - t0), flush=True)
            outs[st] = out
        if "truth" in outs and "ours" in outs:
            p = run([os.path.join(HERE, "diff.py"), outs["truth"], outs["ours"], "-o", os.path.join(d, "diff.md")])
            verdict = p.stdout.decode("utf-8", "replace").strip()
            print(verdict, flush=True)
            rows.append((sid, "EQUAL" if p.returncode == 0 else "DIFFERENT", verdict))
        else:
            rows.append((sid, "NOT-DIFFED", ""))
    write_summary(rows)
    return 0


def write_summary(rows):
    lines = ["# Parity scenario summary", "",
             "| scenario | verdict | counts |", "|---|---|---|"]
    for sid, verdict, detail in rows:
        lines.append("| %s | %s | %s |" % (sid, verdict, detail.replace("|", "/")))
    lines.append("")
    os.makedirs(RESULTS, exist_ok=True)
    with open(os.path.join(RESULTS, "SUMMARY.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.exit(main())
