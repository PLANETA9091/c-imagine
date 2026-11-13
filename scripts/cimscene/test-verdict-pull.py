#!/usr/bin/env python3
"""Selftest for verdict-pull.py (lane 14-dg operationalizer): the crush
detector's ratio math + family aggregation + GREEN-row skip, over a
synthetic artifact tree (no network; the tool's "local" mode)."""
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "verdict-pull.py")


def fixture(out):
    from PIL import Image
    # the cell at col 8 row 1: bright truth, crushed ours (the f414ec3d
    # turn_table law: ratio ~0.12, hue intact)
    F = (720 / 2.0) / math.tan(math.radians(35.0))
    x0 = -5 + 8
    y0 = -60 + 1
    sx = 640 - F * (x0 + 0.5 - 0.5) / 8.5
    sy = 360 - F * (y0 + 0.5 + 58.38) / 8.5
    half = int(F / 8.5 * 0.92 / 2)
    b = (int(sx - half), int(sy - half), int(sx + half), int(sy + half))
    ns = "supplementaries"
    shard = 1  # p1 % 8
    for leg, name, fill in [("wall-truth-shots-%d" % shard, f"gal-p-{ns}1.png", (200, 190, 170)),
                            ("wall-ours-shots-%d" % shard, f"ours-gal-p-{ns}1.png", (24, 22, 19))]:
        d = os.path.join(out, leg)
        os.makedirs(d, exist_ok=True)
        im = Image.new("RGB", (1280, 720), (10, 20, 30))
        px = im.load()
        for x in range(b[0], b[2]):
            for y in range(b[1], b[3]):
                px[x, y] = fill
        im.save(os.path.join(d, name))
    rep = os.path.join(out, "gallery-report-0")
    os.makedirs(rep, exist_ok=True)
    join = {"page": f"gallery-{ns}-p1", "rows": [
        {"page": f"gallery-{ns}-p1", "col": 8, "row": 1,
         "state": f"{ns}:turn_table[facing=north,inverted=true,power=0]",
         "ae": 311793, "verdict": "RED"},
        {"page": f"gallery-{ns}-p1", "col": 0, "row": 0,
         "state": f"{ns}:turn_table[facing=north,inverted=false,power=0]",
         "ae": 12, "verdict": "GREEN"}]}
    json.dump(join, open(os.path.join(rep, f"join-{ns}-p1.json"), "w"))
    return b, (200 + 190 + 170) / 3.0, (24 + 22 + 19) / 3.0


def test_crush_ratio():
    out = tempfile.mkdtemp()
    b, tmean, omean = fixture(out)
    r = subprocess.run([sys.executable, TOOL, "local", "--out", out, "--ratio",
                        "--top", "8"], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert "turn_table" in r.stdout, r.stdout
    line = [ln for ln in r.stdout.splitlines() if "turn_table" in ln][0]
    med = float(line.split()[0])
    expect = omean / tmean
    assert abs(med - expect) < 0.02, (med, expect)
    print(f"PASS crush ratio (fixture {med:.3f} vs expected {expect:.3f})")
    shutil.rmtree(out)


def test_green_rows_skipped():
    out = tempfile.mkdtemp()
    rep = os.path.join(out, "gallery-report-0")
    os.makedirs(rep, exist_ok=True)
    join = {"page": "gallery-ae2-p1", "rows": [
        {"page": "gallery-ae2-p1", "col": 1, "row": 1,
         "state": "ae2:controller[state=offline,type=column_x]",
         "ae": 340488, "verdict": "GREEN"}]}
    json.dump(join, open(os.path.join(rep, "join-ae2-p1.json"), "w"))
    r = subprocess.run([sys.executable, TOOL, "local", "--out", out, "--ratio"],
                       capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert "controller" not in r.stdout
    print("PASS green rows skipped")
    shutil.rmtree(out)


def test_local_needs_no_token():
    out = tempfile.mkdtemp()
    # the local mode must not touch the network: no remote, no token —
    # run with a repo dir that has no origin
    env = dict(os.environ, CIM_REPO="/tmp")
    r = subprocess.run([sys.executable, TOOL, "local", "--out", out,
                        "--ratio"], capture_output=True, text=True, env=env)
    assert r.returncode == 0, r.stderr
    print("PASS local mode offline")
    shutil.rmtree(out)


if __name__ == "__main__":
    test_crush_ratio()
    test_green_rows_skipped()
    test_local_needs_no_token()
    print("SELFTESTS ALL PASS")
