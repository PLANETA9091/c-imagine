#!/usr/bin/env python3
"""Gallery walk — camera-locked vantage + shot per wall (both stacks).

Reads a gallery.tsv manifest (wall/col/row/x/y/z/...), and for every wall:
  1. teleports the viewer to the wall's vantage (centre col x, eye band y,
     z = min(cell z) - 8, yaw 0 pitch 0) through the server console FIFO;
  2. settles, then captures a full 1280x720 frame with the proven Java-only
     Robot tool (scripts/visual/Shot.java) into <shots>/wall_<n>.png.

The SAME script serves both stacks — truth (real NeoForge) and cim (Paper
26.3) — because both manifests carry the same schema. Pixel geometry is
fixed by the client contract: fov 45 (options.txt), 1280x720, vantage 8
blocks from the wall plane (focal 869 px, 108.6 px per block — see
gallery-diff.py).

Usage: gallery-walk.py <gallery.tsv> <console.fifo> <shots-dir> [player]
Env: DISPLAY must point at the Xvfb session running the viewer client.
"""

import csv
import os
import subprocess
import sys
import time

VANTAGE_DIST = 8
SETTLE = 3.0


def main():
    if len(sys.argv) < 4:
        raise SystemExit("usage: gallery-walk.py <gallery.tsv> <fifo> <shots-dir> [player]")
    manifest, fifo, shots = sys.argv[1], sys.argv[2], sys.argv[3]
    player = sys.argv[4] if len(sys.argv) > 4 else "cimviewer"
    os.makedirs(shots, exist_ok=True)

    rows = []
    with open(manifest, encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            rows.append(r)
    walls = sorted({int(r["wall"]) for r in rows})
    if not walls:
        raise SystemExit("manifest has no cells")

    for w in walls:
        wr = [r for r in rows if int(r["wall"]) == w]
        x = sum(int(r["x"]) for r in wr) // len(wr)
        z = min(int(r["z"]) for r in wr) - VANTAGE_DIST
        y = min(int(r["y"]) for r in wr) + 1
        with open(fifo, "w") as f:
            f.write(f"tp {player} {x} {y} {z} 0 0\n")
        time.sleep(SETTLE)
        out = os.path.join(shots, f"wall_{w}.png")
        subprocess.run(["java", "-cp", "vtools", "Shot", out], check=True)
    print(f"gallery-walk: {len(walls)} walls shot into {shots}")


if __name__ == "__main__":
    main()
