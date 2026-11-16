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
    for path in sorted(glob.glob(f"{scenes_glob}/gallery-*-p*.tsv")):
        m = re.search(r"(gallery-[a-z-]+-p\d+)\.tsv$", path)
        if m is None:
            continue  # the .ctx.tsv siblings etc.
        name = m.group(1)
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


def block_tostring(state):
    """The none() branch prints Block.toString() — the bare block id, no
    properties (doorBelow reads above.getBlock(), the Block object)."""
    if state.startswith("Block{"):
        return f"Block{{{state[6:].rstrip('}').split('[', 1)[0]}}}"
    return f"Block{{{block_id(state)}}}"


def main():
    pages, dups = load_pages()
    rows = []
    counts = collections.Counter()
    for name in sorted(pages, key=page_key):
        cells = pages[name]
        for pos, state in sorted(cells.items()):
            bid = block_id(state)
            # THE EMPIRICAL DUMP-WALK LAW (the 496 shard-1 log, 614 door lines
            # reconciled exactly): the dump walk covers the create +
            # supplementaries (+ ae2/mekanism, no door cells there) page
            # families; the biomesoplenty family = shot but NOT dump-walked
            # (0 door lines from 832 BOP rows); within the walked families:
            # create door lowers fire the e51 line (the SlidingDoorBE + the
            # isDoorBER class-name law), create uppers fire e57 (newBlockEntity
            # null); supplementaries netherite_door uppers fire e57, gold_door
            # uppers stay silent (the BE exists, the class name lacks "door"),
            # ALL supplementaries door lowers stay silent; the doormat fires
            # the e51 line via its "door"-bearing BE class with anim=? (the
            # reflection finds no animation field).
            if name.startswith("gallery-biomesoplenty") or name.startswith("gallery-item-"):
                continue  # the BOP family + the item pages = shot but NOT
                          # dump-walked (832 BOP door rows + the bare
                          # item-page doormat emit zero door lines)
            is_create = bid.startswith("create:")
            is_supp_netherite = bid == "supplementaries:netherite_door"
            is_doormat = bid == "supplementaries:doormat"
            if not (is_create or is_supp_netherite or is_doormat):
                continue
            x, y, z = pos
            half = None
            m = re.search(r"(^|,)half=(lower|upper)", state)
            if m:
                half = m.group(2)
            # the diag's state form: Block{id}[props] — the braces close
            # BEFORE the property bracket (BlockState.toString())
            ident = bid
            props = state[len(bid):]
            st = f"Block{{{ident}}}{props}" if props else f"Block{{{ident}}}"
            if half == "upper":
                if not (is_create or is_supp_netherite):
                    continue  # the gold_door uppers stay silent
                # the head cell: page.getBlockEntity -> null -> the e57 line
                below = cells.get((x, y - 1, z))
                if below is None:
                    below = SMOOTH if y - 1 == FLOOR_Y else AIR
                if block_id(below) == block_id(state):
                    val = strip_props(below)
                else:
                    # the diag prints none(Block.toString()) — the BLOCK, no
                    # properties (doorBelow's none branch: above.getBlock())
                    val = f"none({block_tostring(below)})"
                rows.append(f"14-e57 door-head diag {st} @{x},{y},{z}: below={val}")
                counts["below"] += 1
                if val.startswith("none("):
                    counts[f"below none={val[5:-1]}"] += 1
                else:
                    counts["below same-block"] += 1
            elif half == "lower":
                if not is_create:
                    continue  # the supp door lowers stay silent
                # the base cell: the BE path -> the e51 line (upper= receipt)
                above = cells.get((x, y + 1, z))
                if above is None:
                    above = SMOOTH if y + 1 == FLOOR_Y else AIR
                if block_id(above) == block_id(state):
                    val = strip_props(above)
                else:
                    # doorUpper's none branch: none(Block.toString()) — the
                    # BLOCK, no properties
                    val = f"none({block_tostring(above)})"
                rows.append(f"14-e51 door-BER diag {st} @{x},{y},{z}: upper={val}")
                counts["upper"] += 1
                if val.startswith("none("):
                    counts[f"upper none={val[5:-1]}"] += 1
                else:
                    counts["upper same-block"] += 1
            elif is_doormat:
                # the doormat's e51 line: the "door"-bearing BE class, no
                # animation field (anim=?) + the above cell's none-form
                above = cells.get((x, y + 1, z))
                if above is None:
                    above = SMOOTH if y + 1 == FLOOR_Y else AIR
                val = f"none({block_tostring(above)})"
                rows.append(f"14-e51 door-BER diag {st} @{x},{y},{z}: anim=? upper={val}")
                counts["doormat"] += 1

    out = pathlib.Path(out_file)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(rows) + "\n")
    print(f"pages: {len(pages)} (conflicting duplicates: {sum(dups.values())})")
    print(f"frame lines: {len(rows)} -> {out}")
    for k in sorted(counts):
        print(f"  {k}: {counts[k]}")


if __name__ == "__main__":
    main()
