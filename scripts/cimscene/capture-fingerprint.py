#!/usr/bin/env python3
"""THE CAPTURE FINGERPRINT (the 14-e9 content-class census, report-only).

For every RED row of the pulled reports this tool answers ONE question:
DOES OURS RENDER THE CAPTURE THE SEED TOOK FOR THIS CELL?

The ours wall = the pack's per-state composites; each composite's textures =
the seed's captured_*.png crops. A cell whose ours render shows a DIFFERENT
capture's colors = a foreign-content composite (the wrong mesh/capture
binding or the runtime CMD resolution miss) — a CONTENT class no luma
normalization can ever absorb. A cell whose ours render matches its own
capture's colors but still differs from truth = a bake/shading residual
(the tint lanes), not a binding bug.

Method (all inputs already pulled by verdict-pull.py + the seed artifacts):
  1. The seed's quads/rows.tsv + the per-page .ctx.tsv bind (page, cell) ->
     q<hash> -> quads/<hash>.tsv (the quad rows name the sprites).
  2. quads/sprite-aliases.tsv maps native sprite -> ~<capture hash>;
     the expected pixels = quads/textures/<hash>.png.
  3. The expected cell color = the capture set's opaque-pixel mean, weighted
     per quad by its area share on the camera-facing side (the flat wall
     geometry makes the sprite-mean average honest enough for a census).
  4. The ours crop = the triptych PNG the join saved for the RED row
     (report-dir diff-<page>/<page>_c<col>_r<row>_ours.png). The truth
     crop's mean rides the report for context.
  5. The class: MATCH (the ours mean ~= the expected mean), FOREIGN:<hash>
     (the nearest OTHER capture matches better and clearly), DIM (the ours
     mean = the expected scaled by a roughly uniform factor < 0.6 — the
     light/pin class, not a binding bug), NO-CAPTURE (the seed data
     missing for the cell — the audit gap, named).

Usage:
  capture-fingerprint.py <reports-root> <seed-scenes-dir> [--tolerance 0.18]
                         [--out /tmp/capture-fingerprint.json] [--top 24]

  <reports-root> = the dir holding gallery-report-N/ (the verdict-pull --out)
  <seed-scenes-dir> = the seed's scenes/ dir (rows.tsv, quads/, *.ctx.tsv)

Exit code 0 always: report-only, never gates.
"""
import argparse
import collections
import glob
import json
import os
import re
import sys

import numpy as np
from PIL import Image


def load_mean(png_path):
    a = np.asarray(Image.open(png_path).convert("RGBA"), dtype=np.float32)
    op = a[..., 3] > 8
    if not op.any():
        return None
    return a[..., :3][op].mean(axis=0)


class SeedIndex:
    def __init__(self, scenes):
        self.scenes = scenes
        quads = os.path.join(scenes, "quads")
        self.aliases = {}
        alias_file = os.path.join(quads, "sprite-aliases.tsv")
        if os.path.isfile(alias_file):
            for ln in open(alias_file, encoding="utf-8"):
                ln = ln.rstrip("\n")
                if not ln or ln.startswith("#"):
                    continue
                parts = ln.split("\t")
                if len(parts) == 2 and parts[1].startswith("~"):
                    self.aliases[parts[0]] = parts[1][1:]
        self.tex_dir = os.path.join(quads, "textures")
        self.capture_means = {}
        self.ctx = collections.defaultdict(dict)
        for f in glob.glob(os.path.join(scenes, "*.ctx.tsv")):
            page = os.path.basename(f)[: -len(".ctx.tsv")]
            for ln in open(f, encoding="utf-8"):
                row = ln.rstrip("\n").split("\t")
                if len(row) >= 4 and row[3].startswith("q"):
                    self.ctx[page][(row[0], row[1], row[2])] = row[3][1:]
        self._mesh_sprites = {}

    def cell_mesh(self, page, x, y, z):
        return self.ctx.get(page, {}).get((str(x), str(y), str(z)))

    def mesh_sprites(self, qhash):
        if qhash in self._mesh_sprites:
            return self._mesh_sprites[qhash]
        f = os.path.join(self.scenes, "quads", qhash + ".tsv")
        sprites = []
        if os.path.isfile(f):
            for ln in open(f, encoding="utf-8"):
                if ln.startswith("#"):
                    continue
                c = ln.split("\t")
                if len(c) >= 1 and ":" in c[0]:
                    sprites.append(c[0].strip())
        self._mesh_sprites[qhash] = sprites
        return sprites

    def capture_mean(self, sprite):
        h = self.aliases.get(sprite)
        if h is None:
            return None
        if h not in self.capture_means:
            p = os.path.join(self.tex_dir, h + ".png")
            self.capture_means[h] = load_mean(p) if os.path.isfile(p) else None
        return self.capture_means[h]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("reports_root")
    ap.add_argument("seed_scenes")
    ap.add_argument("--tolerance", type=float, default=0.18)
    ap.add_argument("--out", default="/tmp/capture-fingerprint.json")
    ap.add_argument("--top", type=int, default=24)
    args = ap.parse_args(argv)

    seed = SeedIndex(args.seed_scenes)
    # the capture means for the nearest-match search
    all_caps = {}
    tex_dir = seed.tex_dir
    for f in glob.glob(os.path.join(tex_dir, "*.png")):
        h = os.path.basename(f)[:-4]
        m = load_mean(f)
        if m is not None:
            all_caps[h] = m
    cap_ids = list(all_caps)
    cap_mat = np.array([all_caps[h] for h in cap_ids]) if cap_ids else np.zeros((0, 3))

    report_dirs = sorted(glob.glob(os.path.join(args.reports_root, "gallery-report-*")))
    census = collections.Counter()
    rows_out = []
    for rd in report_dirs:
        for jf in sorted(glob.glob(os.path.join(rd, "join-*.json"))):
            try:
                data = json.load(open(jf, encoding="utf-8"))
            except Exception:
                continue
            for r in data.get("rows", []):
                if r.get("verdict") != "RED" or "col" not in r:
                    continue
                page = r.get("page", "")
                if not page.startswith("gallery-"):
                    continue
                m = re.fullmatch(r"gallery-(.+)-p(\d+)", page)
                if not m:
                    continue
                ns, pnum = m.group(1), m.group(2)
                # the ctx keys by the WALL COORDS (x,y,z); the join rows
                # carry the grid col/row — the walk's grid: x = X0+col,
                # y = Y0+row, z = the wall plane (CimSceneMod GALLERY_X0=-5,
                # GALLERY_Y0=-60, GALLERY_Z=9)
                wx, wy, wz = str(-5 + int(r["col"])), str(-60 + int(r["row"])), "9"
                qhash = seed.ctx.get(page, {}).get((wx, wy, wz))
                if qhash is None:
                    # the ctx keys by the WALL COORDS (x,y,z); the join rows
                    # carry the grid col/row — the ctx position mapping rides
                    # the manifest; fall back to the state's own hash via
                    # rows.tsv order (the representative) when the mapping is
                    # not locally recoverable
                    census["NO-CTX"] += 1
                    continue
                sprites = seed.mesh_sprites(qhash)
                means = [seed.capture_mean(s) for s in sprites]
                means = [m2 for m2 in means if m2 is not None]
                if not means:
                    census["NO-CAPTURE"] += 1
                    rows_out.append({"page": page, "col": r["col"],
                                     "row": r["row"], "state": r.get("state", ""),
                                     "class": "NO-CAPTURE", "q": qhash})
                    continue
                expected = np.mean(means, axis=0)
                stem = f"{page}_c{r['col']}_r{r['row']}_ours.png"
                hits = glob.glob(os.path.join(rd, "diff-*", stem))
                if not hits:
                    census["NO-OURS-CROP"] += 1
                    continue
                ours = load_mean(hits[0])
                if ours is None:
                    census["EMPTY-OURS"] += 1
                    continue
                rel = np.abs(ours - expected) / np.maximum(expected, 8.0)
                if rel.mean() <= args.tolerance:
                    census["MATCH"] += 1
                    rows_out.append({"page": page, "col": r["col"],
                                     "row": r["row"], "state": r.get("state", ""),
                                     "class": "MATCH", "q": qhash})
                    continue
                scale = float(np.median(ours / np.maximum(expected, 8.0)))
                if 0.0 < scale < 0.6:
                    census["DIM"] += 1
                    rows_out.append({"page": page, "col": r["col"],
                                     "row": r["row"], "state": r.get("state", ""),
                                     "class": "DIM", "q": qhash,
                                     "scale": round(scale, 3)})
                    continue
                # 14-e9.1 the honest semantics: the expected here is the RAW
                # capture mean, while the pack's composite multiplies it by
                # the bake's per-face cimtints (0.5-0.8 per channel) and the
                # display pin's lightmap — a tint-only delta lands here too.
                # This class = "the ours crop matches neither the raw capture
                # nor a uniform dim" = TINT-OR-FOREIGN: the per-model audit
                # (the composite's texture set vs the state's own capture)
                # separates the two before any fix lane is named.
                nearest = "UNMATCHED"
                if len(cap_mat):
                    d = np.abs(cap_mat - ours).mean(axis=1)
                    i = int(np.argmin(d))
                    if d[i] / max(ours.mean(), 8.0) <= args.tolerance:
                        nearest = cap_ids[i]
                cls = "TINT-OR-FOREIGN"
                census[cls] += 1
                rows_out.append({"page": page, "col": r["col"],
                                 "row": r["row"], "state": r.get("state", ""),
                                 "class": cls,
                                 "q": qhash, "nearest": nearest})

    total = sum(census.values())
    print(f"red rows audited: {total}")
    for k, v in census.most_common():
        print(f"  {k:14s} {v:5d} ({100.0 * v / max(total, 1):.1f}%)")
    fam_tof = collections.Counter(
        row["state"].split("[")[0]
        for row in rows_out if row["class"] == "TINT-OR-FOREIGN")
    if fam_tof:
        print("\ntint-or-foreign families (the bake-transform audit separates the two):")
        for fam, v in fam_tof.most_common(args.top):
            print(f"  {fam:52s} x{v}")
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump({"census": dict(census), "rows": rows_out}, fh, indent=1)
    print(f"\n-> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
