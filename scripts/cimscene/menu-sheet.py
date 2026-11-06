#!/usr/bin/env python3
"""Side-by-side menu report: the real NeoForge client beside c-imagine.

Reads the truth gui manifests and writes one PNG per menu, truth left, ours
right, plus menus.md for the run summary. A menu that did not open, or a frame
that is missing, is listed by name. Diagnostic: it never fails the run.

Usage: menu-sheet.py --meta DIR --truth DIR --ours DIR --out DIR --ns a,b
"""
import argparse
import glob
import os
import re

from PIL import Image, ImageDraw

LABEL = 18


def rows(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] in ("cell", "skip") and parts[1] != "x" and len(parts) >= 6:
                yield parts[0], parts[4], parts[5]


def panel(path, title, size):
    out = Image.new("RGB", (size[0], size[1] + LABEL), (24, 24, 24))
    ImageDraw.Draw(out).text((4, 3), title, fill=(255, 255, 255))
    if path and os.path.isfile(path):
        out.paste(Image.open(path).convert("RGB").resize(size), (0, LABEL))
    else:
        ImageDraw.Draw(out).text((8, LABEL + 8), "no frame", fill=(255, 90, 90))
    return out


def main():
    ap = argparse.ArgumentParser()
    for name in ("--meta", "--truth", "--ours", "--out", "--ns"):
        ap.add_argument(name, required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    size = (640, 360)
    table = []
    for ns in a.ns.replace(",", " ").split():
        pattern = os.path.join(a.meta, "**", f"gallery-gui-{ns}-p*.tsv")
        for manifest in sorted(glob.glob(pattern, recursive=True)):
            page = re.search(r"-p(\d+)\.tsv$", manifest).group(1)
            truth = os.path.join(a.truth, f"gal-gui-{ns}{page}.png")
            ours = os.path.join(a.ours, f"ours-gal-gui-{ns}{page}.png")
            for kind, menu, note in rows(manifest):
                name = menu.replace(":", "_")
                sheet = Image.new("RGB", (size[0] * 2 + 4, size[1] + LABEL), (70, 70, 70))
                sheet.paste(panel(truth, f"NeoForge: {menu}", size), (0, 0))
                sheet.paste(panel(ours, "c-imagine, vanilla 26.3", size), (size[0] + 4, 0))
                sheet.save(os.path.join(a.out, f"{name}.png"))
                state = "opened" if kind == "cell" else "NOT OPENED"
                table.append((menu, state, note, os.path.isfile(truth), os.path.isfile(ours)))
    opened = sum(1 for r in table if r[1] == "opened")
    with open(os.path.join(a.out, "menus.md"), "w", encoding="utf-8") as f:
        f.write(f"### Menus: {opened} of {len(table)} opened on the real client\n\n")
        f.write("| menu | truth | how | truth frame | ours frame |\n|---|---|---|---|---|\n")
        for menu, state, note, t, o in table:
            f.write(f"| {menu} | {state} | {note} | {'yes' if t else 'no'} | {'yes' if o else 'no'} |\n")
    print(f"menu sheets: {opened}/{len(table)} opened, {a.out}")


if __name__ == "__main__":
    main()
