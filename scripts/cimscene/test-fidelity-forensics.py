#!/usr/bin/env python3
"""Selftest for fidelity-forensics.py (14-dg): synthetic shard dirs + a
synthetic QuadDump seed pack; asserts the self_ae bucketing, the per-state
aggregation and the mesh attribution."""
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "fidelity-forensics.py")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main():
    tmp = tempfile.mkdtemp(prefix="fid-forensics-")
    try:
        shard = os.path.join(tmp, "shard0")
        write(os.path.join(shard, "join-create-p0.json"), json.dumps({
            "gate": 60000, "verdict": "RED",
            "rows": [
                {"page": "create-p0", "col": 1, "row": 0,
                 "state": "minecraft:hourglass", "ae": 300000,
                 "verdict": "RED", "luma_norm": False, "luma_fit": False,
                 "self_ae": [10, 12]},                                # FIDELITY
                {"page": "create-p0", "col": 2, "row": 0,
                 "state": "minecraft:hourglass", "ae": 250000,
                 "verdict": "RED", "luma_norm": True, "luma_fit": True,
                 "self_ae": [15, 999999]},                            # ANIM-SELF
                {"page": "create-p0", "col": 3, "row": 0,
                 "state": "minecraft:globe", "ae": 120000,
                 "verdict": "RED", "luma_norm": False, "luma_fit": False,
                 "self_ae": [20, 18]},                                # FIDELITY too
                {"page": "create-p0", "col": 5, "row": 0,
                 "state": "minecraft:candle", "ae": 90000,
                 "verdict": "RED", "luma_norm": False, "luma_fit": False,
                 "self_ae": [None, 9]},                               # NO-PAIR
                {"page": "create-p0", "col": 4, "row": 0,
                 "state": "minecraft:stone", "ae": 100,
                 "verdict": "GREEN", "self_ae": [8, 8]},              # ignored
            ]}))
        seed = os.path.join(tmp, "pack")
        write(os.path.join(seed, "quads", "rows.tsv"),
              "minecraft:hourglass#qaaa\nminecraft:hourglass#qbbb\n"
              "minecraft:globe#qccc\n")
        write(os.path.join(seed, "quads", "aaa.tsv"),
              "minecraft:block/glass\t1.0\t-1\tv v v v v v v v v v v v\n" * 3)
        write(os.path.join(seed, "quads", "bbb.tsv"),
              "minecraft:block/sand\t1.0\t-1\tv v v v v v v v v v v v\n")
        write(os.path.join(seed, "quads", "ccc.tsv"),
              "minecraft:block/white_concrete\t1.0\t-1\tv v v v v v v v v v v v\n")

        r = subprocess.run(
            [sys.executable, TOOL, shard, "--seed", seed, "--top", "5"],
            capture_output=True, text=True)
        assert r.returncode == 0, r.stderr + r.stdout
        out = r.stdout
        assert "FIDELITY=2" in out and "ANIM-SELF=1" in out \
            and "NO-PAIR=1" in out, out
        assert "minecraft:hourglass" in out, out
        # hourglass mesh attribution: 2 hashes, 4 quad lines, 2 sprites
        # (the printed quads/sprites columns); globe: 1 hash, 1 quad, 1 sprite
        assert __import__("re").search(
            r"\b4\s+2\s+minecraft:hourglass\b", out), out
        assert __import__("re").search(
            r"\b1\s+1\s+minecraft:globe\b", out), out

        outj = os.path.join(tmp, "report.json")
        r = subprocess.run(
            [sys.executable, TOOL, shard, "--seed", seed, "--json", outj],
            capture_output=True, text=True)
        assert r.returncode == 0, r.stderr
        d = json.load(open(outj))
        assert d["buckets"] == {"FIDELITY": 2, "ANIM-SELF": 1,
                                "NO-PAIR": 1}, d["buckets"]
        st = {e["state"]: e for e in d["fidelity_states"]}
        assert set(st) == {"minecraft:hourglass", "minecraft:globe"}, set(st)
        h = st["minecraft:hourglass"]
        assert h["red"] == 1 and h["luma_norm"] == 0 and h["ae_max"] == 300000
        assert h["mesh"]["quads"] == 4 and h["mesh"]["sprites"] == 2

        print("PASS fidelity-forensics (bucketing, aggregation, mesh)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
