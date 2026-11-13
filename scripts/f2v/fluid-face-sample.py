#!/usr/bin/env python3
"""14-ea THE FLUID FACE SAMPLE: measure the truth leg's rendered fluid faces
and bake them as the pack's fluidcube textures.

THE CLASS (run 43468177851, the fluid joins): the still fluids RED at
257-723K ae while their flowing siblings sit just under the 240K gate —
the truth client renders the real fluid through the vanilla translucent
pipeline (the backdrop visibly shows through the fluid's side faces) and
ours replays the opaque 14/16 fluidcube; the crush table proves the
geometry sits at the same 14/16 height (the seed's captured brine mesh:
the top face at y=14.2062) — the residual is the SURFACE (tint + alpha +
the overlay layer), not the box.

The L1-optimal surface for the front face is the truth's OWN pixels: the
camera is head-on, the case's glass is clear vanilla glass (an alpha-0
interior), so the fluid's south face samples 1:1 from the truth's fluid
page shots. The sample rides the f2v overlay into the pack build; the
fluidcube's south face (and its mirrored north) draw the sampled surface,
the remaining faces keep the shaded canvas (they are hidden behind the
case's side glass in the head-on crop).

Usage:
  fluid-face-sample.py --meta <truth-meta dirs...> --shots <truth-shots dirs...>
                       --out <overlay dir>   (writes <out>/fluidfaces/*.png
                                               + <out>/fluidfaces/index.tsv)
"""
import argparse
import glob
import json
import math
import os
import sys

import numpy as np
from PIL import Image

W, H = 1280, 720
VF = 70.0
F = (H / 2.0) / math.tan(math.radians(VF / 2.0))
CAM_X = 0.5
EYE_Y = -58.38
# the fluid block's south face plane: the block at z=9 spans 8.5..9.5, the
# camera sits at z=0.5 -> the face distance is 9.0 (the case's front glass
# at 7.5..8.5 projects larger and frames the sample)
FACE_DIST = 9.0
INSET_X = 2.0 / 16   # the fluid cube's l/r inset inside the case + the glass
                     # frame margin (1/16 measured the frame's blue columns in)
INSET_TOP = 2.0 / 16  # the vanilla source's 14/16 surface rule
INSET_BOTTOM = 2.0 / 16


def face_box(x, y, z):
    # the face plane distance from the camera (the camera at z=0.5, the
    # block at z spans z-0.5..z+0.5, its south face at z+0.5)
    d = (z + 0.5) - 0.5
    sx = W / 2.0 - F * (x + 0.5 - CAM_X) / d
    sy = H / 2.0 - F * (y + 0.5 - EYE_Y) / d
    half = F * 0.5 / d
    left = sx - half + 2 * half * INSET_X
    right = sx + half - 2 * half * INSET_X
    top = sy - half + 2 * half * INSET_TOP
    bottom = sy + half - 2 * half * INSET_BOTTOM
    return int(round(left)), int(round(top)), int(round(right)), int(round(bottom))


def load_shots(dirs):
    shots = {}
    for d in dirs:
        for p in glob.glob(os.path.join(d, "**", "gal-fl-*.png"), recursive=True):
            name = os.path.basename(p)  # gal-fl-<ns><p>[-self].png
            if name.endswith("-self.png"):
                continue
            key = name[len("gal-fl-"):-len(".png")]
            shots.setdefault(key, p)
    return shots


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--meta", nargs="+", required=True)
    ap.add_argument("--shots", nargs="+", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--receipt", default=None)
    args = ap.parse_args()
    os.makedirs(os.path.join(args.out, "fluidfaces"), exist_ok=True)

    shots = load_shots(args.shots)
    samples = {}
    receipt = []
    for mdir in args.meta:
        for mf in sorted(glob.glob(os.path.join(mdir, "**",
                "gallery-fluid-*.tsv"), recursive=True)):
            for line in open(mf, encoding="utf-8"):
                parts = line.rstrip("\n").split("\t")
                if parts[0] != "cell":
                    continue
                _, x, y, z, fluid, dyn, channel, extra = parts[:8]
                if channel != "block" or extra == "minecraft:air":
                    continue  # the flowing cells decay to air on the truth leg
                ns_page = os.path.basename(mf)[len("gallery-fluid-"):-len(".tsv")]
                # gallery-fluid-mekanism-p0 -> shot key mekanism0
                ns, p = ns_page.rsplit("-p", 1)
                key = ns + str(int(p))
                sp = shots.get(key)
                if sp is None:
                    receipt.append(f"MISSING-SHOT\t{fluid}\t{key}")
                    continue
                im = np.asarray(Image.open(sp).convert("RGB")).astype(np.float64)
                b = face_box(int(x), int(y), int(z))
                crop = im[b[1]:b[3], b[0]:b[2]]
                if crop.size == 0 or crop.shape[0] < 8 or crop.shape[1] < 8:
                    receipt.append(f"EMPTY-CROP\t{fluid}\t{key}\t{b}")
                    continue
                # 16x16: rows 2..16 = the sampled face (the cube's side uv
                # reads the texture's lower 14 rows), rows 0..1 repeat row 2
                face = np.asarray(
                    Image.fromarray(crop.astype(np.uint8)).resize(
                        (16, 14), Image.LANCZOS)).astype(np.uint8)
                tex = np.zeros((16, 16, 3), np.uint8)
                tex[2:] = face
                tex[:2] = face[0]
                fluid_id = extra.split("[")[0]
                out_png = os.path.join(args.out, "fluidfaces",
                        fluid_id.replace(":", "_") + ".png")
                Image.fromarray(tex).save(out_png)
                samples[fluid_id] = out_png
                receipt.append(f"SAMPLED\t{fluid_id}\t{extra}\t{key}\t"
                               f"cell={x},{y},{z}")
    idx = os.path.join(args.out, "fluidfaces", "index.tsv")
    with open(idx, "w", encoding="utf-8") as f:
        f.write("fluid\ttexture\n")
        for k in sorted(samples):
            f.write(f"{k}\tfluidfaces/{os.path.basename(samples[k])}\n")
    for r in receipt:
        print(r)
    print(f"fluid-face-sample: {len(samples)} face(s) -> {idx}")
    if args.receipt:
        json.dump(receipt, open(args.receipt, "w"), indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
