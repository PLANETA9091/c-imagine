#!/usr/bin/env python3
"""c-imagine leg of the universal gallery — MIRROR of the truth manifest.

Owner directive 2026-10-30: the gallery is THE gate and both stacks must run
the SAME cell sequence. The truth side (real NeoForge) enumerates registries
and writes gallery.tsv (wall/col/row -> x/y/z + ns/id/state, vanilla controls
with ALL their states first, then every modded block state). This tool takes
that manifest as THE cell contract and generates the console command stream
that places the SAME cells in the c-imagine world (Paper 26.3):

  vanilla cells  -> real setblock with the EXACT state parsed from the truth
                    state string ("Block{minecraft:oak_log}[axis=x]" ->
                    setblock ... minecraft:oak_log[axis=x])
  modded cells   -> F2V v1 style VIRTUAL block: a block_display entity
                    carrying the manifest's note_block[instrument,note]
                    carrier (the pack overrides its model to the modded one),
                    brightness PINNED (15,15) so world light never drifts
                    into the pixel gate; carriers come from visual-blocks.tsv
                    (block ns:id -> instrument note). A modded cell whose
                    block has NO carrier row is placed as a RED barrier
                    marker: gallery-diff joins it and reports RED — that is
                    the honest fix backlog of the f2v port.

Usage: cim-gallery-walk.py <truth-gallery.tsv> <visual-blocks.tsv> <out.tsv> [floorY]
  stdout = console commands (fills + setblock + summon) to run BEFORE walking.
"""

import re
import sys

# MUST match CimSceneMod gallery geometry (scene-mod, owner directive rig)
COLS, ROWS = 6, 6
STRIDE, WALL_STRIDE, WALL1 = 2, 12, 8  # WALL_STRIDE must exceed the 11-block corridor (vantage 8 + margin)
STATE_RE = re.compile(r"^Block\{(?P<id>[^}]+)\}(?:\[(?P<props>.*)\])?$")


def main():
    if len(sys.argv) < 4:
        raise SystemExit(
            "usage: cim-gallery-walk.py <truth-gallery.tsv> <visual-blocks.tsv> <out.tsv> [floorY]")
    truth_tsv, blocks_tsv, out_tsv = sys.argv[1], sys.argv[2], sys.argv[3]
    floor_y = int(sys.argv[4]) if len(sys.argv) > 4 else -60  # flat seed 42 surface stand height

    carriers = {}
    with open(blocks_tsv, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            bid = parts[0].strip()
            instrument = parts[1].strip().lower() if len(parts) > 1 else "creeper"
            note = int(parts[2]) if len(parts) > 2 else 13
            carriers[bid] = (instrument, note)

    cells = []
    with open(truth_tsv, encoding="utf-8") as f:
        head = f.readline().rstrip("\n").split("\t")
        for line in f:
            vals = line.rstrip("\n").split("\t")
            if len(vals) < len(head):
                continue
            cells.append(dict(zip(head, vals)))
    if not cells:
        raise SystemExit("truth manifest has no cells")

    walls = max(int(c["wall"]) for c in cells) + 1
    cmds = [
        # corridor hygiene — SAME rectangle CimSceneMod builds on the truth
        # side (platform z from wallZ-VANTAGE_DIST-2 to wallZ+1): any world
        # quirk (spawn lake water measured 2026-10-29) would otherwise leak
        # asymmetric pixels into the gate
        f"fill -8 {floor_y} -2 8 {floor_y} 9 minecraft:smooth_stone",
        f"fill -8 {floor_y + 1} -2 8 {floor_y + 10} 9 minecraft:air",
        "kill @e[type=minecraft:block_display]",
    ]
    for w in range(1, walls):
        wz = WALL1 + w * WALL_STRIDE
        cmds.append(f"fill -8 {floor_y} {wz - 10} 8 {floor_y} {wz + 1} minecraft:smooth_stone")
        cmds.append(f"fill -8 {floor_y + 1} {wz - 10} 8 {floor_y + 10} {wz + 1} minecraft:air")

    manifest = ["wall\tcol\trow\tx\ty\tz\tns\tid\tstate\tcarrier"]
    red_cells = []
    for c in cells:
        wall, col, row = int(c["wall"]), int(c["col"]), int(c["row"])
        wz = WALL1 + wall * WALL_STRIDE
        x = -(COLS // 2) * STRIDE + col * STRIDE
        y = floor_y + 1 + row
        ns, idd = c["ns"], c["id"]
        # the truth manifest's id column is ALREADY namespaced (measured:
        # "minecraft:stone"); double-prefixing produced minecraft:minecraft:*
        # and "Invalid ID" on Paper
        bid = idd if ":" in idd else f"{ns}:{idd}"
        m = STATE_RE.match(c["state"])
        state_props = (m.group("props") or "") if m else ""
        if ns == "minecraft" or bid.startswith("minecraft:"):
            props = f"[{state_props}]" if state_props else ""
            cmds.append(f"setblock {x} {y} {wz} {bid}{props}")
            out_state = f"Block{{{bid}}}{props}"
            carrier = ""
        else:
            if bid in carriers:
                instrument, note = carriers[bid]
                props = f'instrument:"{instrument}",note:"{note}"'
                nbt = ('{Tags:["cimgallery"],brightness:{sky:15b,block:15b},'
                       f'block_state:{{Name:"minecraft:note_block",Properties:{{{props}}}}}}}')
                cmds.append(f"summon minecraft:block_display {x} {y} {wz} {nbt}")
                out_state = f"Block{{minecraft:note_block}}[{props}]"
                carrier = f"{instrument}/{note}"
            else:
                # NO CARRIER = honest RED: a visible marker the diff reports
                cmds.append(f"setblock {x} {y} {wz} minecraft:red_concrete")
                out_state = "Block{minecraft:red_concrete}"
                carrier = "MISSING"
                red_cells.append((wall, col, row, bid))
        manifest.append(f"{wall}\t{col}\t{row}\t{x}\t{y}\t{wz}\t{ns}\t{idd}\t{out_state}\t{carrier}")
    with open(out_tsv, "w", encoding="utf-8") as f:
        f.write("\n".join(manifest) + "\n")
    for c in cmds:
        print(c)
    print(f"# cim gallery mirror: {len(cells)} cells, {walls} walls, "
          f"{len(red_cells)} missing carriers -> {out_tsv}", file=sys.stderr)
    for rc in red_cells[:40]:
        print(f"# MISSING-CARRIER\t{rc}", file=sys.stderr)


if __name__ == "__main__":
    main()
