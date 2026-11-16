#!/usr/bin/env python3
"""The expected-receipt frame for the capture-diag door lines (14-e57b).

For every door cell of every gallery-create page this emits the literal
receipt string the QuadDump diag will print at capture time:

  lower half (has BE)  -> 14-e51 door-BER diag <state> @x,y,z: ... upper=<v>
  upper half (no BE)   -> 14-e57 door-head diag <state> @x,y,z: below=<v>

The receipt semantics are pinned from the committed code (QuadDump at the
e57f head): Page.getBlockState = cells.get | SMOOTH_STONE @FLOOR_Y=-61 | AIR;
doorUpper/doorBelow return the same-block state stripped to its property
string, else "none(" + Block.toString() + ")".  The frame is the
falsification contract: the next galleries' capture-diag.log door lines must
match line-for-line; any mismatch is a real discovery.

Usage: diag_expect.py [scenes-glob] [out-file]
  scenes-glob default: ci-artifacts/truth-meta-495/wall-truth-meta-*/srv-wall/c-imagine/scenes
  out-file   default: ci-artifacts/e57-expect.txt
"""
import collections
import glob
import pathlib
import re
import sys

FLOOR_Y = -61
SMOOTH = "Block{minecraft:smooth_stone}"
AIR = "Block{minecraft:air}"

scenes_glob = sys.argv[1] if len(sys.argv) > 1 else \
    "ci-artifacts/truth-meta-495/wall-truth-meta-*/srv-wall/c-imagine/scenes"
out_file = sys.argv[2] if len(sys.argv) > 2 else "ci-artifacts/e57-expect.txt"


def page_key(name):
    m = re.search(r"p(\d+)$", name)
    return int(m.group(1)) if m else 0


def load_pages():
    pages = {}
    seen_dup = collections.Counter()
    for path in sorted(glob.glob(f"{scenes_glob}/gallery-create-p*.tsv")):
        name = re.search(r"(gallery-create-p\d+)\.tsv$", path).group(1)
        cells = {}
        with open(path) as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if parts and parts[0] == "cell" and len(parts) >= 5 \
                        and parts[1].lstrip("-").isdigit():
                    pos = (int(parts[1]), int(parts[2]), int(parts[3]))
                    cells[pos] = parts[4]
        if name in pages and pages[name] != cells:
            seen_dup[name] += 1
        pages[name] = cells
    return pages, seen_dup


def block_id(state):
    return state.split("[", 1)[0]


def diag_state(state):
    """The diag prints the BlockState object -> Block{id}[props] form."""
    return f"Block{{{state}}}" if "[" not in state else None


def strip_props(state):
    """doorUpper/doorBelow same-block value: Block{id}[props] -> props."""
    if "[" in state:
        ident, props = state.split("[", 1)
        return props.rstrip("]")
    return ""


def main():
    pages, dups = load_pages()
    rows = []
    counts = collections.Counter()
    for name in sorted(pages, key=page_key):
        cells = pages[name]
        for pos, state in sorted(cells.items()):
            if not block_id(state).endswith("_door"):
                continue  # isDoorState = the registry path's _door suffix
            x, y, z = pos
            half = None
            m = re.search(r"(^|,)half=(lower|upper)", state)
            if m:
                half = m.group(2)
            st = f"Block{{{state}}}"  # BlockState.toString() form
            if half == "upper":
                # the head cell: page.getBlockEntity -> null -> the e57 line
                below = cells.get((x, y - 1, z))
                if below is None:
                    below = SMOOTH if y - 1 == FLOOR_Y else AIR
                if block_id(below) == block_id(state):
                    val = strip_props(below)
                else:
                    val = f"none({below})"
                rows.append(f"14-e57 door-head diag {st} @{x},{y},{z}: below={val}")
                counts["below"] += 1
                if val.startswith("none("):
                    counts[f"below none={val[5:-1]}"] += 1
                else:
                    counts["below same-block"] += 1
            else:
                # the base cell: the BE path -> the e51 line (upper= receipt)
                above = cells.get((x, y + 1, z))
                if above is None:
                    above = SMOOTH if y + 1 == FLOOR_Y else AIR
                if block_id(above) == block_id(state):
                    val = strip_props(above)
                else:
                    val = f"none({above})"
                rows.append(f"14-e51 door-BER diag {st} @{x},{y},{z}: upper={val}")
                counts["upper"] += 1
                if val.startswith("none("):
                    counts[f"upper none={val[5:-1]}"] += 1
                else:
                    counts["upper same-block"] += 1

    out = pathlib.Path(out_file)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(rows) + "\n")
    print(f"pages: {len(pages)} (conflicting duplicates: {sum(dups.values())})")
    print(f"frame lines: {len(rows)} -> {out}")
    for k in sorted(counts):
        print(f"  {k}: {counts[k]}")


if __name__ == "__main__":
    main()
