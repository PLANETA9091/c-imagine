#!/usr/bin/env python3
"""Gallery walk — camera-locked vantage + shot per wall (both stacks).

Reads a gallery.tsv manifest (wall/col/row/x/y/z/...), and for every wall:
  1. teleports the viewer to the wall's vantage (centre col x, eye band y,
     z = min(cell z) - 8, yaw 0 pitch 0) — transport is fifo:/path or
     rcon:host:port:password (RCON preferred: the fifo tail dies on EOF and
     every later writer blocks forever — measured 2026-10-29);
  2. settles, then captures a full 1280x720 frame with the proven Java-only
     Robot tool (scripts/visual/Shot.java) into <shots>/wall_<n>.png.

The SAME script serves both stacks — truth (real NeoForge) and cim (Paper
26.3) — because both manifests carry the same schema. Pixel geometry is
fixed by the client contract: fov 45 (options.txt), 1280x720, vantage 8
blocks from the wall plane (focal 869 px, 108.6 px per block — see
gallery-diff.py).

Usage: gallery-walk.py <gallery.tsv> <transport> <shots-dir> [player]
       transport = fifo:/path/to/pipe | rcon:host:port:password
Env: DISPLAY must point at the Xvfb session running the viewer client.
"""

import csv
import os
import subprocess
import sys
import time

VANTAGE_DIST = 8
SETTLE = 10.0
HERE = os.path.dirname(os.path.abspath(__file__))


def rcon_cmd(host, port, pw, cmd):
    out = subprocess.run(
        [sys.executable, os.path.join(HERE, "rcon.py"), host, str(port), pw, cmd],
        capture_output=True, text=True, timeout=30)
    if out.returncode != 0:
        raise RuntimeError(f"rcon {cmd!r} failed: {out.stderr.strip()}")
    return out.stdout


def main():
    if len(sys.argv) < 4:
        raise SystemExit("usage: gallery-walk.py <gallery.tsv> <fifo|rcon:h:p:pw> <shots-dir> [player]")
    manifest, transport, shots = sys.argv[1], sys.argv[2], sys.argv[3]
    player = sys.argv[4] if len(sys.argv) > 4 else "cimviewer"
    os.makedirs(shots, exist_ok=True)

    rows = []
    with open(manifest, encoding="utf-8") as f:
        for r in csv.DictReader(f, delimiter="\t"):
            rows.append(r)
    walls = sorted({int(r["wall"]) for r in rows})
    if not walls:
        raise SystemExit("manifest has no cells")

    def vantage(w):
        wr = [r for r in rows if int(r["wall"]) == w]
        x = sum(int(r["x"]) for r in wr) // len(wr)
        z = min(int(r["z"]) for r in wr) - VANTAGE_DIST
        y = min(int(r["y"]) for r in wr) + 1
        return x, y, z

    def rcon_tp(w):
        x, y, z = vantage(w)
        cmd = f"tp {player} {x} {y} {z} 0 0"
        if transport.startswith("rcon:"):
            _, host, port, pw = transport.split(":")
            resp = rcon_cmd(host, int(port), pw, cmd)
            line = resp.strip().splitlines()[-1] if resp.strip() else "?"
            print(f"wall {w}: {cmd} -> {line}")
            # a vanished viewer produces "shots of nothing" — fail loudly
            if "Teleported" not in line:
                raise RuntimeError(f"tp failed for {player!r}: {line}")
        else:
            with open(transport[5:], "w") as f:
                f.write(cmd + "\n")

    # WARM-UP: the FIRST teleport of a session is eaten by the client's
    # terrain-download barrier (position packets ignored until the world is
    # loaded — measured 2026-10-29: wall_0 framed the login spot while the
    # server said "Teleported"). Pass 1 teleports only; pass 2 shoots.
    # SPECTATOR: a survival viewer FALLS to the platform top — the camera
    # lands 1 block below the contract eye height and every crop misses
    # (measured 2026-10-29). Spectator pins the camera to the tp point and
    # removes the first-person hand from the pixels.
    if transport.startswith("rcon:"):
        _, host, port, pw = transport.split(":")
        rcon_cmd(host, int(port), pw, f"gamemode spectator {player}")
    for w in walls:
        rcon_tp(w)
        time.sleep(SETTLE)

    for w in walls:
        rcon_tp(w)
        time.sleep(SETTLE)
        out = os.path.join(shots, f"wall_{w}.png")
        vtools = os.environ.get("CIM_VTOOLS", "vtools")
        subprocess.run(["java", "-cp", vtools, "Shot", out], check=True)
    print(f"gallery-walk: {len(walls)} walls shot into {shots}")


if __name__ == "__main__":
    main()
