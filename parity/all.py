#!/usr/bin/env python3
"""Run scenarios on both stacks, diff them, write results/<id>/, results/SUMMARY.md and a CSV report.

Usage:
  python3 -I all.py [--only SUBSTR ...] [--stacks truth,ours] [--no-shots]
  python3 -I all.py --dir scenarios/census/place-break --shard 2/8 --no-shots --csv results/pb-2of8.csv [--resume]

Default (no --dir): every scenarios/*.json (top level), as before.
--dir DIR: every *.json under DIR, recursively (files starting with '_' are skipped), sorted by path.
--shard i/n: slice i of n of that sorted list (item k goes to slice k % n + 1), so n unattended
  processes cover the family once and never overlap. SUMMARY.md gets a -iofn suffix when sharded.
--resume: scenarios already listed in the CSV report are skipped (restart after a crash).

Per scenario: results/<id>/truth.json, ours.json, diff.md, diff.json, shots/{truth,ours}/ (unless --no-shots).
CSV report (results/REPORT.csv unless --csv): scenario, verdict, first_diff_step, reason. One row per
scenario, appended as it finishes.
Verdicts: EQUAL, DIFFERENT (a comparison difference), ERROR (infrastructure: run.py failed or timed out,
a setup command or heal/wait-ready/verify-empty failed, or a step error reads like a connection failure),
NOT-DIFFED (only one stack was run). Each run is a subprocess, so one crash or hang does not stop the batch.
"""
import argparse
import csv
import glob
import io
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RESULTS = os.environ.get("PARITY_RESULTS", os.path.join(HERE, "results"))  # results/ is not committed
RUN_TIMEOUT = 1800
INFRA_RE = re.compile(r"timed? ?out|refused|reset by peer|broken pipe|connection|socket|no reply|"
                      r"not connected|JSONDecode|ConnectionError|OSError|Errno|wait-ready|heal", re.I)
CSV_FIELDS = ["scenario", "verdict", "first_diff_step", "reason"]


def run(args, timeout=RUN_TIMEOUT):
    return subprocess.run([sys.executable, "-I"] + args, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout)


def shard_items(items, i, n):
    """The i-th (1-based) of n slices. Item k goes to slice k % n + 1, so the slices partition items."""
    if n < 1 or not 1 <= i <= n:
        raise ValueError("shard %d/%d out of range" % (i, n))
    return [x for k, x in enumerate(items) if k % n == i - 1]


def parse_shard(text):
    m = re.match(r"^(\d+)/(\d+)$", text or "")
    if not m:
        raise argparse.ArgumentTypeError("--shard wants i/n, e.g. 2/8")
    i, n = int(m.group(1)), int(m.group(2))
    if not 1 <= i <= n:
        raise argparse.ArgumentTypeError("--shard %s: need 1 <= i <= n" % text)
    return i, n


def discover(dir_arg):
    if dir_arg:
        base = dir_arg if os.path.isabs(dir_arg) else os.path.join(HERE, dir_arg)
        paths = [p for p in glob.glob(os.path.join(base, "**", "*.json"), recursive=True)
                 if not os.path.basename(p).startswith("_")]
    else:
        paths = glob.glob(os.path.join(HERE, "scenarios", "*.json"))
    return sorted(paths)


def infra_reason(js):
    """'' when the run's setup and steps look sound, else why the run is an infrastructure ERROR."""
    for rec in js.get("setup", []):
        if rec.get("ok") is False:
            return "setup %s failed: %s" % (rec.get("cmd"), rec.get("error") or "not ok")
    for rec in js.get("steps", []):
        err = rec.get("error")
        if err and INFRA_RE.search(str(err)):
            kind = (rec.get("step") or {}).get("do") or "observe"
            return "step %s %s: %s" % (rec.get("i", "?"), kind, short(err, 140))
    return ""


def short(text, n=90):
    text = re.sub(r"\s+", " ", str(text))
    return text if len(text) <= n else text[: n - 3] + "..."


def run_stack(path, sid, d, st, shots_dir):
    """-> (output path or None, infra reason)."""
    out = os.path.join(d, st + ".json")
    if os.path.exists(out):
        os.remove(out)
    cmd = [os.path.join(HERE, "run.py"), "--stack", st, path, "-o", out]
    if shots_dir:
        cmd += ["--shots", shots_dir]
    t0 = time.time()
    try:
        p = run(cmd)
    except subprocess.TimeoutExpired:
        print("%s %s: run.py TIMEOUT after %ds" % (st, sid, RUN_TIMEOUT), flush=True)
        return None, "run.py timed out after %ds on %s" % (RUN_TIMEOUT, st)
    text = p.stdout.decode("utf-8", "replace").strip()
    print(text, "(rc=%d, %.0fs)" % (p.returncode, time.time() - t0), flush=True)
    if p.returncode != 0 or not os.path.exists(out):
        last = text.splitlines()[-1] if text else ""
        return None, "run.py failed on %s (rc=%d): %s" % (st, p.returncode, short(last, 140))
    return out, ""


def diff_pair(d, truth, ours):
    """-> (verdict, detail line, first differing step or '', reason)."""
    dj = os.path.join(d, "diff.json")
    if os.path.exists(dj):
        os.remove(dj)
    p = run([os.path.join(HERE, "diff.py"), truth, ours, "-o", os.path.join(d, "diff.md"), "--json", dj])
    detail = p.stdout.decode("utf-8", "replace").strip()
    print(detail, flush=True)
    if not os.path.exists(dj):
        return "ERROR", detail, "", "diff.py failed: %s" % short(detail.splitlines()[-1] if detail else "", 140)
    with io.open(dj, encoding="utf-8") as f:
        info = json.load(f)
    first = info.get("first_diff")
    if not first:
        return info["verdict"], detail, "", ""
    reason = "%s: %s; truth=%s ours=%s" % (first["step"], first["why"] or first["verdict"],
                                            short(first["truth"], 60), short(first["ours"], 60))
    return info["verdict"], detail, str(first["i"]), reason


def scenario_row(path, sid, d, stacks, shots_dir):
    outs = {}
    for st in stacks:
        out, why = run_stack(path, sid, d, st, shots_dir)
        if why:
            return {"scenario": sid, "verdict": "ERROR", "first_diff_step": "", "reason": why, "detail": ""}
        outs[st] = out
    if set(stacks) != {"truth", "ours"}:
        return {"scenario": sid, "verdict": "NOT-DIFFED", "first_diff_step": "", "reason": "", "detail": ""}
    for st in ("truth", "ours"):
        with io.open(outs[st], encoding="utf-8") as f:
            why = infra_reason(json.load(f))
        if why:
            return {"scenario": sid, "verdict": "ERROR", "first_diff_step": "",
                    "reason": "%s (on %s)" % (why, st), "detail": ""}
    verdict, detail, first, reason = diff_pair(d, outs["truth"], outs["ours"])
    return {"scenario": sid, "verdict": verdict, "first_diff_step": first, "reason": reason, "detail": detail}


def load_done(csv_path):
    if not os.path.exists(csv_path):
        return set()
    with io.open(csv_path, encoding="utf-8", newline="") as f:
        return set(r["scenario"] for r in csv.DictReader(f))


class CsvReport(object):
    def __init__(self, path):
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        new = not os.path.exists(path) or os.path.getsize(path) == 0
        self.f = io.open(path, "a", encoding="utf-8", newline="")
        self.w = csv.DictWriter(self.f, fieldnames=CSV_FIELDS, extrasaction="ignore")
        if new:
            self.w.writeheader()
        self.f.flush()

    def add(self, row):
        self.w.writerow({k: row.get(k, "") for k in CSV_FIELDS})
        self.f.flush()

    def close(self):
        self.f.close()


def write_summary(rows, name="SUMMARY.md"):
    lines = ["# Parity scenario summary", "",
             "| scenario | verdict | counts |", "|---|---|---|"]
    for r in rows:
        lines.append("| %s | %s | %s |" % (r["scenario"], r["verdict"],
                                           (r.get("detail") or r.get("reason") or "").replace("|", "/")))
    lines.append("")
    os.makedirs(RESULTS, exist_ok=True)
    with open(os.path.join(RESULTS, name), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None)
    ap.add_argument("--stacks", default="truth,ours")
    ap.add_argument("--no-shots", action="store_true")
    ap.add_argument("--dir", default=None, help="scenario directory, searched recursively")
    ap.add_argument("--shard", type=parse_shard, default=None, help="i/n: run slice i of n")
    ap.add_argument("--csv", default=None, help="CSV report path (default: results/REPORT.csv)")
    ap.add_argument("--resume", action="store_true", help="skip scenarios already in the CSV report")
    args = ap.parse_args(argv)
    stacks = args.stacks.split(",")

    paths = discover(args.dir)
    if args.only:
        paths = [p for p in paths if any(o in os.path.splitext(os.path.basename(p))[0] for o in args.only)]
    if args.shard:
        paths = shard_items(paths, args.shard[0], args.shard[1])
    csv_path = args.csv or os.path.join(RESULTS, "REPORT.csv")
    done = load_done(csv_path) if args.resume else set()
    report = CsvReport(csv_path)
    suffix = "" if not args.shard else "-%dof%d" % args.shard
    rows = []
    try:
        for path in paths:
            sid = os.path.splitext(os.path.basename(path))[0]
            if sid in done:
                print("skip (in report): %s" % sid, flush=True)
                continue
            d = os.path.join(RESULTS, sid)
            os.makedirs(d, exist_ok=True)
            shots = None if args.no_shots else os.path.join(d, "shots")
            row = scenario_row(path, sid, d, stacks, shots)
            print("VERDICT %s %s %s" % (row["verdict"], sid, row["reason"]), flush=True)
            report.add(row)
            rows.append(row)
    finally:
        report.close()
    write_summary(rows, "SUMMARY%s.md" % suffix)
    return 0


if __name__ == "__main__":
    sys.exit(main())
