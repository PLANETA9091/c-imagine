#!/usr/bin/env python3
"""c-imagine leg of the universal gallery — pack-carrier cells.

Owner directive 2026-10-30: the gallery is THE gate and it must run on BOTH
stacks. The c-imagine stack is a vanilla 26.3 world — modded blocks cannot
be placed as real blocks. Every modded cell is therefore rendered the F2V v1
way: a VIRTUAL block — a block_display entity carrying the manifest's
note_block[instrument,note] carrier (the pack overrides its model to the
modded one), brightness PINNED to (15,15) so world light never drifts into
the pixel gate. Vanilla control cells are REAL vanilla blocks on both sides
(setblock) — they measure pure transport/camera drift and calibrate the
datum.

The output manifest has the EXACT same schema as /cimscene gallery on the
truth side (wall/col/row/x/y/z/ns/id/state) so gallery-diff.py joins by
cell index. Modded ids WITHOUT a manifest row are not summoned — the join
reports them as RED missing cells: that is the f2v port's fix backlog,
which is the honest state the gate exists to expose.

Usage: cim-gallery-walk.py <visual-blocks.tsv> <out-gallery.tsv> [floorY]
  stdout = the console commands (summon + setblock) to run BEFORE walking.
"""

import csv
import sys

# MUST match CimSceneMod gallery geometry (scene-mod, owner directive rig)
COLS, ROWS = 6, 6
STRIDE, WALL_STRIDE, WALL1 = 2, 9, 8
CONTROLS = ["minecraft:stone", "minecraft:iron_block", "minecraft:gold_block",
            "minecraft:diamond_block", "minecraft:oak_log", "minecraft:oak_leaves",
            "minecraft:glass", "minecraft:bricks", "minecraft:red_wool",
            "minecraft:oak_planks", "minecraft:crafting_table", "minecraft:furnace"]
DEFAULT_NOTE = 13
STATE_INSTRUMENTS = {"creeper", "basedrum", "snare", "hat", "bass", "flute",
                     "bell", "chime", "xylophone", "iron_xylophone", "cow_bell",
                     "didgeridoo", "bit", "banjo", "pling", "zombie", "skeleton",
                     "creeper", "dragon", "wither_skull", "piglin", "elder_guardian"}


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: cim-gallery-walk.py <visual-blocks.tsv> <out.tsv> [floorY]")
    blocks_tsv, out_tsv = sys.argv[1], sys.argv[2]
    floor_y = int(sys.argv[3]) if len(sys.argv) > 3 else -60  # flat seed 42 ground top

    carriers = []
    with open(blocks_tsv, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            bid = parts[0].strip()
            instrument = parts[1].strip().lower() if len(parts) > 1 else "creeper"
            note = int(parts[2]) if len(parts) > 2 else DEFAULT_NOTE
            carriers.append((bid, instrument, note))

    # cell plan: 12 vanilla controls first, then every carrier (same order on
    # both sides by construction — truth sorts modded by id, carriers follow
    # the manifest; the manifest IS the cim-side ordering contract)
    cells = []
    for cid in CONTROLS:
        cells.append(("minecraft", cid.split(":", 1)[1], cid, None))
    for bid, instrument, note in carriers:
        ns, idd = bid.split(":", 1)
        cells.append((ns, idd, bid, (instrument, note)))

    per_wall = COLS * ROWS
    walls = (len(cells) + per_wall - 1) // per_wall

    cmds = ["kill @e[type=minecraft:block_display]"]
    manifest = ["wall\tcol\trow\tx\ty\tz\tns\tid\tstate"]
    for w in range(walls):
        wz = WALL1 + w * WALL_STRIDE
        for row in range(ROWS):
            for col in range(COLS):
                idx = w * per_wall + row * COLS + col
                if idx >= len(cells):
                    break
                ns, idd, bid, carrier = cells[idx]
                x = -(COLS // 2) * STRIDE + col * STRIDE
                y = floor_y + 1 + row
                state = ""
                if carrier is None:
                    cmds.append(f"setblock {x} {y} {wz} {bid}")
                    state = f"Block{{#{bid}}}"
                else:
                    instrument, note = carrier
                    props = f'instrument:"{instrument}",note:"{note}"'
                    nbt = ('{Tags:["cimgallery"],brightness:{sky:15b,block:15b},'
                           'block_state:{Name:"minecraft:note_block",Properties:{'
                           + props + '}}}')
                    cmds.append(f'summon minecraft:block_display {x} {y} {wz} {nbt}')
                    state = f"Block{{minecraft:note_block}}[{props}]"
                manifest.append(f"{w}\t{col}\t{row}\t{x}\t{y}\t{wz}\t{ns}\t{idd}\t{state}")

    with open(out_tsv, "w", encoding="utf-8") as f:
        f.write("\n".join(manifest) + "\n")
    for c in cmds:
        print(c)
    print(f"# cim gallery: {len(cells)} cells, {walls} walls -> {out_tsv}", file=sys.stderr)


if __name__ == "__main__":
    main()
