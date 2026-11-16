#!/usr/bin/env python3
"""The door-red positions map + the frame cross-tab (14-e58c).

Builds the red-state -> cell-instances map from the e55b arbiter's
verdict-summary.json (run 43884657696, the 24 door reds = the standing set)
joined against the seed manifests, then cross-tabs every red instance
against the expected-receipt frame (the diag_expect walk, page-keyed).
The output: the class census the 496 window's diag receipts complete.

Usage: red_positions.py <verdict-summary.json> [scenes-glob]
"""
import collections
import glob
import json
import re
import sys

FLOOR_Y = -61
SMOOTH = "Block{minecraft:smooth_stone}"
AIR = "Block{minecraft:air}"


def block_id(s):
    return s.split("[", 1)[0]


def load_scenes(glob_pat):
    pages = {}
    for path in sorted(glob.glob(f"{glob_pat}/gallery-create-p*.tsv")):
        name = re.search(r"(gallery-create-p\d+)\.tsv$", path).group(1)
        cells = {}
        for line in open(path):
            p = line.rstrip("\n").split("\t")
            if p and p[0] == "cell" and len(p) >= 5 and p[1].lstrip("-").isdigit():
                cells[(int(p[1]), int(p[2]), int(p[3]))] = p[4]
        pages[name] = cells
    return pages


def build_frame(pages):
    frame = {}
    for name, cells in pages.items():
        for (x, y, z), state in cells.items():
            if not block_id(state).endswith("_door"):
                continue
            nb = cells.get((x, y - 1, z)) if "half=upper" in state else cells.get((x, y + 1, z))
            if nb is None:
                nb = SMOOTH if (("half=upper" in state and y - 1 == FLOOR_Y)
                                or ("half=upper" not in state and y + 1 == FLOOR_Y)) else AIR
            same = block_id(nb) == block_id(state)
            val = re.sub(r"^Block\{[^}]+\}\[", "", nb).rstrip("]") if same else f"none({nb})"
            kind = "below" if "half=upper" in state else "upper"
            frame[(name, f"{x},{y},{z}")] = (state, kind, val)
    return frame


def main():
    verdict = json.load(open(sys.argv[1]))
    scenes = sys.argv[2] if len(sys.argv) > 2 else \
        "ci-artifacts/seed-captured-meshes/srv-wall/c-imagine/scenes"
    pages = load_scenes(scenes)
    frame = build_frame(pages)

    cells_all = collections.defaultdict(list)
    for name, cells in pages.items():
        for pos, state in cells.items():
            cells_all[state].append((pos[0], pos[1], pos[2], name))

    reds = []
    for shard, info in verdict["shards"].items():
        reds.extend(info.get("reds", []))
    door_reds = sorted(set(r.split(" ae=")[0] for r in reds if "_door[" in r))
    print(f"door red states: {len(door_reds)}")

    out = {}
    cls = collections.Counter()
    inst = collections.Counter()
    for state in door_reds:
        out[state] = cells_all.get(state, [])
        for x, y, z, page in out[state]:
            f = frame.get((page, f"{x},{y},{z}"))
            if not f:
                cls["NO-FRAME-LINE"] += 1
                continue
            st, kind, val = f
            if st != state:
                cls[f"{kind}:state-mismatch"] += 1
                continue
            v = "none" if val.startswith("none(") else "same-block"
            cls[f"{kind}:{v}"] += 1
            inst[f"{kind}:{v}({val[:44]})"] += 1

    json.dump(out, open("/tmp/door-red-positions.json", "w"), indent=1)
    total = sum(len(v) for v in out.values())
    unamb = sum(1 for v in out.values() if len(v) == 1)
    print(f"red instances: {total}  unambiguous states: {unamb}/{len(door_reds)}")
    print("frame classes:")
    for k, v in sorted(cls.items()):
        print(f"  {k}: {v}")
    print("receipt classes:")
    for k, v in sorted(inst.items()):
        print(f"  {v}x {k}")
    triad = [k for k in inst if "smooth_stone" in k]
    print(f"the y=-60 no-pair head class (the e56 triad) among the reds: "
          f"{sum(inst[k] for k in triad)}")


if __name__ == "__main__":
    main()
