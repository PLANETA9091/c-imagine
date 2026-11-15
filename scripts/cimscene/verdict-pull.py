#!/usr/bin/env python3
"""THE VERDICT PULL (lane 14-dg operationalizer, report-only).

One command over a finished gallery run: pull the verdict/report/seed
artifacts, run the fidelity forensics over the per-shard joins, and —
when both legs' page shots are present — run the CRUSH DETECTOR: the
per-family truth/ours brightness ratio over the RED rows' crops.

The crush detector names the class the ratio already identifies: a
family whose ours/truth ratio collapses to ~0.1-0.15 with the hue
intact (the pattern visible, a slight blue shift) is the display
lightmap-starvation class (the client's skylight data empty at the
display sections at shot time) — geometry and textures are innocent,
the ratio table proves it per family. A family at ratio ~1.0 with a
huge ae is a CONTENT class (different pixels, same brightness) — the
mesh-diff/census lanes own those.

Usage:
  verdict-pull.py <run_id|local> [--out DIR] [--kinds verdict,reports,meshes]
                  [--forensics] [--ratio] [--top N]

  <run_id>   the GitHub Actions run id (the gallery run); the literal
             "local" skips the pull and operates on --out as-is (the
             selftest's mode)
  --kinds    which artifacts to pull (default: verdict,reports)
             meshes = seed-captured-meshes (the quads/ tree)
  --forensics  run fidelity-forensics.py over the pulled reports
  --ratio    run the crush detector (needs the pulled reports and both
             legs' shots in the out dir, pulled with --kinds shots)
  --top N    the ratio table's row cap (default 24)

Report-only: never gates, never edits a verdict.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

API_REPO = "repos/PLANETA9091/c-imagine"


ARTIFACT_NAMES = {
    "verdict": ["gallery-verdict"],
    "reports": ["gallery-report-"],
    "meshes": ["seed-captured-meshes"],
    "pack": ["seed-pack"],
    "shots": ["wall-truth-shots-", "wall-ours-shots-"],
    "meta": ["wall-truth-meta-", "wall-ours-meta-"],
}


def list_artifacts(run_id):
    # gh owns authentication (credential store or GH_TOKEN). Never extract a
    # secret from a remote URL or put an Authorization header in process argv.
    r = subprocess.run(
        ["gh", "api", f"{API_REPO}/actions/runs/{run_id}/artifacts?per_page=100",
         "--paginate", "--slurp"],
        capture_output=True, text=True, timeout=90, check=True)
    pages = json.loads(r.stdout)
    return [(a["id"], a["name"], a["size_in_bytes"])
            for page in pages for a in page["artifacts"]]


def pull(run_id, kinds, out):
    want = []
    for k in kinds:
        want += ARTIFACT_NAMES[k]
    got = []
    artifacts = list_artifacts(run_id)
    for kind in kinds:
        if not any(any(name.startswith(prefix) for prefix in ARTIFACT_NAMES[kind])
                   for _, name, _ in artifacts):
            raise RuntimeError(f"run {run_id}: no {kind} artifacts; evidence is incomplete")
    for aid, name, size in artifacts:
        if any(name.startswith(w) for w in want):
            # Names repeat across runs. The immutable artifact ID is the
            # cache identity; a new revision must never inherit old evidence.
            z = os.path.join(out, f"{name}-{aid}.zip")
            if not os.path.exists(z):
                # A failed HTTP transfer must not poison the next run's ZIP
                # cache or leave an extracted directory that looks complete.
                with tempfile.NamedTemporaryFile(dir=out, suffix=".part", delete=False) as f:
                    partial = f.name
                    try:
                        subprocess.run(
                            ["gh", "api", f"{API_REPO}/actions/artifacts/{aid}/zip"],
                            stdout=f, stderr=subprocess.PIPE, timeout=600, check=True)
                        f.flush()
                        with zipfile.ZipFile(partial) as zf:
                            bad = zf.testzip()
                            if bad is not None:
                                raise zipfile.BadZipFile(f"{name}: corrupt member {bad}")
                        os.replace(partial, z)
                    finally:
                        if os.path.exists(partial):
                            os.unlink(partial)
            d = os.path.join(out, name)
            receipt = os.path.join(d, ".artifact-id")
            cached_id = None
            if os.path.isfile(receipt):
                with open(receipt, encoding="utf-8") as f:
                    cached_id = f.read().strip()
            if cached_id != str(aid):
                if os.path.isdir(d):
                    shutil.rmtree(d)
                os.makedirs(d, exist_ok=True)
                with zipfile.ZipFile(z) as zf:
                    zf.extractall(d)
                with open(receipt, "w", encoding="utf-8") as f:
                    f.write(str(aid) + "\n")
            got.append(d)
            print(f"pulled {name} ({size/1e6:.1f} MB)")
    return got


def load_joins(out):
    import glob
    joins = []
    for f in sorted(glob.glob(os.path.join(out, "gallery-report-*", "join-*.json"))):
        try:
            joins.append(json.load(open(f, encoding="utf-8")))
        except (OSError, ValueError):
            continue
    return joins


def crush_ratio(out, top):
    """Per-family ours/truth brightness ratio over the red rows' crops."""
    import glob
    import math
    import numpy as np
    from PIL import Image
    F = (720 / 2.0) / math.tan(math.radians(35.0))
    CELL = F / 8.5
    joins = load_joins(out)
    pages = {}
    fam_acc = {}
    for j in joins:
        for r in j.get("rows", []):
            if not isinstance(r, dict) or r.get("verdict") != "RED":
                continue
            ae = r.get("ae", 0)
            if ae < 60000:
                continue
            # the page id rides the ROW (the join top level carries no
            # 'page' key — the wall rows are 'cell' rows with page/col/row)
            page = r.get("page") or ""
            if not page.startswith("gallery-") or "-p" not in page:
                continue  # the non-wall joins (items/inv/sound) have no crops
            # gallery-supplementaries-p237 -> ns "supplementaries", page 237;
            # rsplit keeps hyphenated namespaces (biomes-o-plenty) whole
            tail = page.split("-", 1)[-1]
            ns, pnum = tail.rsplit("-p", 1)
            tp = os.path.join(out, f"wall-truth-shots-{int(pnum) % 8}",
                              f"gal-p-{ns}{pnum}.png")
            op = os.path.join(out, f"wall-ours-shots-{int(pnum) % 8}",
                              f"ours-gal-p-{ns}{pnum}.png")
            if page not in pages:
                pages[page] = None
                if os.path.exists(tp) and os.path.exists(op):
                    pages[page] = (np.asarray(
                        Image.open(tp).convert("RGB")).astype(float),
                        np.asarray(
                            Image.open(op).convert("RGB")).astype(float))
            pair = pages.get(page)
            if pair is None:
                continue
            t, o = pair
            x0 = -5 + int(r["col"])
            y0 = -60 + int(r["row"])
            sx = 640 - F * (x0 + 0.5 - 0.5) / 8.5
            sy = 360 - F * (y0 + 0.5 + 58.38) / 8.5
            half = CELL * 0.92 / 2
            b = (int(sx - half), int(sy - half), int(sx + half), int(sy + half))
            tc = t[b[1]:b[3], b[0]:b[2]]
            oc = o[b[1]:b[3], b[0]:b[2]]
            if tc.size == 0 or oc.size == 0:
                continue
            ratio = float(oc.mean() / max(tc.mean(), 1.0))
            fam = r["state"].split("[")[0]
            a = fam_acc.setdefault(fam, [])
            a.append((ratio, ae, page, r["state"]))
    rows = []
    for fam, vals in fam_acc.items():
        rs = sorted(v[0] for v in vals)
        med = rs[len(rs) // 2]
        rows.append((med, len(vals), fam))
    rows.sort()
    print(f"{'ratio':>7} {'n':>4}  family   (ours/truth brightness, red rows)")
    for med, n, fam in rows[:top]:
        print(f"{med:7.3f} {n:4d}  {fam}")
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run_id")
    ap.add_argument("--out", default="/tmp/verdict-pull")
    ap.add_argument("--kinds", default="verdict,reports")
    ap.add_argument("--forensics", action="store_true")
    ap.add_argument("--ratio", action="store_true")
    ap.add_argument("--top", type=int, default=24)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    if a.run_id != "local":
        pull(a.run_id, [k.strip() for k in a.kinds.split(",")], a.out)
    if a.forensics:
        seed = None
        sdir = os.path.join(a.out, "seed-captured-meshes",
                            "srv-wall", "c-imagine", "scenes")
        if os.path.isdir(sdir):
            seed = sdir
        dirs = [os.path.join(a.out, d) for d in sorted(os.listdir(a.out))
                if d.startswith("gallery-report-") and os.path.isdir(os.path.join(a.out, d))]
        cmd = [sys.executable, os.path.join(os.path.dirname(__file__),
                "fidelity-forensics.py"), *dirs, "--top", str(a.top)]
        if seed:
            cmd += ["--seed", seed]
        print("+", " ".join(cmd))
        subprocess.run(cmd, check=True)
    if a.ratio:
        crush_ratio(a.out, a.top)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, subprocess.SubprocessError) as exc:
        print(f"verdict-pull: {exc}", file=sys.stderr)
        sys.exit(1)
