#!/usr/bin/env python3
"""Menu gallery join: the real NeoForge client beside c-imagine, GREEN or RED.

Every menu a namespace registers gets one verdict. GREEN means every pixel of the
menu's area is identical on both legs. RED is everything else, including a menu that
did not open and a frame that is missing, so nothing passes by being absent.

The area is what the mod really draws, from its captured screen, or failing that the
menu's slots centred on the frame. The world behind a screen differs between the legs
and is not compared.

Writes join-menu-<ns>-<menu>.json (the shape the shard verdict reads), one sheet per
menu with truth, ours and the differing pixels, and menus.md for the run summary.
Exits 1 when any menu is RED.

Usage: menu-sheet.py --meta DIR --truth DIR --ours DIR --out DIR --report DIR --ns "a b"
"""
import argparse
import glob
import json
import os
import re

from PIL import Image, ImageChops, ImageDraw

LABEL = 18
SHEET = (426, 240)
# A vanilla screen with no slots to measure.
DEFAULT_W, DEFAULT_H = 176, 166


def read_manifest(path):
    """Returns (menu, opened, note, slots) for one page, or None."""
    menu, opened, note, slots = None, False, "", []
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 6 or p[1] == "x":
                continue
            if p[0] == "cell":
                menu, opened, note = p[4], True, p[5]
            elif p[0] == "skip":
                menu, opened, note = p[4], False, p[5]
            elif p[0] == "slot" and int(p[2]) > -1000:
                slots.append((int(p[2]), int(p[3])))
    return None if menu is None else (menu, opened, note, slots)


def menu_box(frame_size, slots, scale):
    """Pixel rectangle of the menu, from its slots, centred on the frame."""
    if slots:
        w = max(x for x, _ in slots) - min(x for x, _ in slots) + 18
        h = max(y for _, y in slots) - min(y for _, y in slots) + 18
    else:
        w, h = DEFAULT_W, DEFAULT_H
    cx, cy = frame_size[0] // 2, frame_size[1] // 2
    return (cx - w * scale // 2, cy - h * scale // 2, cx + w * scale // 2, cy + h * scale // 2)


def art_mask(path, size):
    """The pixels the mod really draws, from its captured screen, or None."""
    if not path or not os.path.isfile(path):
        return None
    art = Image.open(path).convert("RGBA")
    if art.size != size:
        return None
    r, g, b, a = art.split()
    # Colour with zero alpha is solid in a real framebuffer, as ScreenPack treats it.
    solid = ImageChops.lighter(ImageChops.lighter(r, g), ImageChops.lighter(b, a))
    return solid.point(lambda v: 255 if v else 0)


def compare(truth_path, ours_path, slots, scale, art=None):
    """Returns (differing pixels, total pixels, mask image), or None without both frames."""
    if not (os.path.isfile(truth_path) and os.path.isfile(ours_path)):
        return None
    truth = Image.open(truth_path).convert("RGB")
    ours = Image.open(ours_path).convert("RGB")
    if truth.size != ours.size:
        return None
    mask = art_mask(art, truth.size)
    if mask is not None and mask.getbbox():
        r, g, b = ImageChops.difference(truth, ours).split()
        diff = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda v: 255 if v else 0)
        diff = ImageChops.multiply(diff, mask)
        box = mask.getbbox()
        total = sum(1 for v in mask.crop(box).getdata() if v)
        return sum(1 for v in diff.crop(box).getdata() if v), total, diff.crop(box)
    box = menu_box(truth.size, slots, scale)
    # Per channel: converting to grey would round a one-step difference away.
    r, g, b = ImageChops.difference(truth.crop(box), ours.crop(box)).split()
    diff = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda v: 255 if v else 0)
    red = sum(1 for v in diff.getdata() if v)
    return red, diff.size[0] * diff.size[1], diff


def zoom(truth_path, ours_path, art, out_path, pad=4, limit=96):
    """Full-resolution truth | ours | diff around where they differ, so a RED is readable."""
    if not (os.path.isfile(truth_path) and os.path.isfile(ours_path)):
        return
    truth = Image.open(truth_path).convert("RGB")
    ours = Image.open(ours_path).convert("RGB")
    if truth.size != ours.size:
        return
    r, g, b = ImageChops.difference(truth, ours).split()
    diff = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda v: 255 if v else 0)
    mask = art_mask(art, truth.size)
    if mask is not None and mask.getbbox():
        diff = ImageChops.multiply(diff, mask)
    box = diff.getbbox()
    if not box:
        return
    x0, y0 = max(0, box[0] - pad), max(0, box[1] - pad)
    x1, y1 = min(truth.size[0], min(box[2], x0 + limit) + pad), min(truth.size[1], min(box[3], y0 + limit) + pad)
    crop = (x0, y0, x1, y1)
    k = max(1, min(8, 640 // max(1, x1 - x0)))
    parts = [im.crop(crop).resize(((x1 - x0) * k, (y1 - y0) * k), Image.NEAREST)
             for im in (truth, ours, diff.convert("RGB"))]
    out = Image.new("RGB", (parts[0].width * 3 + 8, parts[0].height + LABEL), (70, 70, 70))
    ImageDraw.Draw(out).text((4, 3), f"full-res crop at x={x0} y={y0}, x{k}", fill=(255, 255, 255))
    for i, im in enumerate(parts):
        out.paste(im, (i * (parts[0].width + 4), LABEL))
    out.save(out_path)


def panel(image, title, size):
    out = Image.new("RGB", (size[0], size[1] + LABEL), (24, 24, 24))
    ImageDraw.Draw(out).text((4, 3), title, fill=(255, 255, 255))
    if image is not None:
        out.paste(image.convert("RGB").resize(size), (0, LABEL))
    else:
        ImageDraw.Draw(out).text((8, LABEL + 8), "no frame", fill=(255, 90, 90))
    return out


def load(path):
    return Image.open(path) if os.path.isfile(path) else None


def judge(menu, opened, note, result):
    if not opened:
        return "RED", f"not opened: {note}"
    if result is None:
        return "RED", "frame missing"
    red, total, _ = result
    return ("GREEN", "pixel identical") if red == 0 else ("RED", f"{red} of {total} pixels differ")


def main():
    ap = argparse.ArgumentParser()
    for name in ("--meta", "--truth", "--ours", "--out", "--report", "--ns"):
        ap.add_argument(name, required=True)
    ap.add_argument("--scale", type=int, default=3)
    ap.add_argument("--art", default="")
    ap.add_argument("--stride", default="", help="index/count of this shard's pages, e.g. 3/8")
    a = ap.parse_args()
    a.stride = tuple(int(v) for v in a.stride.split("/")) if a.stride else None
    os.makedirs(a.out, exist_ok=True)
    os.makedirs(a.report, exist_ok=True)
    rows = []
    for ns in a.ns.replace(",", " ").split():
        pattern = os.path.join(a.meta, "**", f"gallery-gui-{ns}-p*.tsv")
        for manifest in sorted(glob.glob(pattern, recursive=True)):
            page = re.search(r"-p(\d+)\.tsv$", manifest).group(1)
            truth = os.path.join(a.truth, f"gal-gui-{ns}{page}.png")
            ours = os.path.join(a.ours, f"ours-gal-gui-{ns}{page}.png")
            read = read_manifest(manifest)
            if read is None:
                continue
            # Each shard shoots one stride of the pages. The stride is checked by
            # number, so a page this shard owed and failed to shoot still counts RED.
            menu, opened, note, slots = read
            if a.stride and int(page) % a.stride[1] != a.stride[0] and note != "opened-by:crawl":
                continue
            art = os.path.join(a.art, menu.replace(":", "_").replace("/", "_") + ".png") if a.art else None
            result = compare(truth, ours, slots, a.scale, art)
            verdict, why = judge(menu, opened, note, result)
            name = menu.replace(":", "_")
            sheet = Image.new("RGB", (SHEET[0] * 3 + 8, SHEET[1] + LABEL), (70, 70, 70))
            sheet.paste(panel(load(truth), f"NeoForge: {menu}", SHEET), (0, 0))
            sheet.paste(panel(load(ours), f"c-imagine: {verdict}", SHEET), (SHEET[0] + 4, 0))
            sheet.paste(panel(result[2] if result else None, why, SHEET), (SHEET[0] * 2 + 8, 0))
            sheet.save(os.path.join(a.out, f"{name}.png"))
            if verdict == "RED" and result:
                zoom(truth, ours, art, os.path.join(a.out, f"{name}.zoom.png"))
            with open(os.path.join(a.report, f"join-menu-{name}.json"), "w", encoding="utf-8") as f:
                json.dump({"verdict": verdict, "cells": 1, "red": int(verdict == "RED"), "dynamic": 0, "skipped": 0,
                           "rows": [{"verdict": verdict, "state": menu, "ae": result[0] if result else None}]}, f)
            rows.append((menu, verdict, why))
            # Every recorded interaction is a row of its own, judged the same way.
            for t in sorted(glob.glob(os.path.join(a.truth, f"gal-guiw-{ns}{page}-*.png"))):
                tag = t.rsplit("-", 1)[1][:-4]
                o = os.path.join(a.ours, f"ours-gal-guiw-{ns}{page}-{tag}.png")
                state = (art[:-4] + "." + tag + ".png") if art and tag != "move" else art
                res = compare(t, o, slots, a.scale, state)
                v, w2 = judge(menu, True, note, res)
                label = f"{menu} {'slot move' if tag == 'move' else 'press ' + tag[1:]}"
                if v == "RED" and res:
                    zoom(t, o, state, os.path.join(a.out, f"{name}-{tag}.zoom.png"))
                with open(os.path.join(a.report, f"join-menu-{name}-{tag}.json"), "w", encoding="utf-8") as f:
                    json.dump({"verdict": v, "cells": 1, "red": int(v == "RED"), "dynamic": 0, "skipped": 0,
                               "rows": [{"verdict": v, "state": label, "ae": res[0] if res else None}]}, f)
                rows.append((label, v, w2))
    green = sum(1 for r in rows if r[1] == "GREEN")
    with open(os.path.join(a.out, "menus.md"), "w", encoding="utf-8") as f:
        f.write(f"### Menus: {green} of {len(rows)} GREEN\n\n| menu | verdict | detail |\n|---|---|---|\n")
        for menu, verdict, why in rows:
            f.write(f"| {menu} | {verdict} | {why} |\n")
    print(f"menus: {green}/{len(rows)} GREEN")
    # No rows proves nothing, so it cannot pass.
    return 0 if rows and green == len(rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
