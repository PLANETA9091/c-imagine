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
  6. The channel-swap audit (14-e9.2, the inverted-colors suspicion settled
     mechanically): for every compared pair above, the file's own relative
     error (|ours - expected| / max(expected, 8), mean) is computed TWICE —
     unswapped and with ours' R and B channels exchanged. Swapping either
     side is the identical metric (the exchange is an involution and each
     denominator travels with its own channel), so one direction covers
     both suspicions: ours inverted at capture OR the expected capture
     inverted at bake. CHANNEL-SWAP = the swapped fit at least HALVES the
     unswapped MAE (ratio <= --swap-factor 0.5) while the unswapped MAE
     clears the noise floor (--swap-floor 0.02 — below it the pair already
     fits and no delta exists for a swap to explain). The calibration: a
     genuine swap measures ratio ~0 (the exchange KILLS the error), while
     the match/dim/tint mechanisms all measure ratio >= 1 (the exchange
     never helps a scaling mechanism — the per-face cimtints are all
     <= 0.8 and cannot reorder the channels). PROVISIONAL: the 0.5 factor
     is fixture-calibrated, not dispatch-calibrated — it hardens only
     after a real run's ratio distribution is read x2 (the flip law);
     report-only, never a gate.

Usage:
  capture-fingerprint.py <reports-root> <seed-scenes-dir> [--tolerance 0.18]
                         [--swap-factor 0.5] [--swap-floor 0.02]
                         [--out /tmp/capture-fingerprint.json] [--top 24]
                         [--no-pixel-audit]

  <reports-root> = the dir holding gallery-report-N/ (the verdict-pull --out)
  <seed-scenes-dir> = the seed's scenes/ dir (rows.tsv, quads/, *.ctx.tsv)

Output contract: the census JSON gains an additive "channel_swap" object
(threshold, status, pairs, flagged, subjects) and every COMPARED row gains
swap_mae / swap_mae_rb / swap_ratio / swap_flag — existing keys never change
semantics. Images that are absent or yield no pixels stay NAMED (NO-CAPTURE /
NO-OURS-CROP / EMPTY-OURS / UNREADABLE-OURS) and simply leave their pair
unaudited — the honest degradation, never a crash.

14-e280 THE PIXEL-SWAP AUDIT (the mean-metric's dispatch read x2 measured
the flag zone = the sparse-alpha-mask artifact class: 22/24 flagged rows
deterministic across two flights with IDENTICAL ratios 0.15-0.46, but
clustered on windowed doors / flora — families whose opaque masks differ
between the legs, which skews a MEAN non-uniformly and lets the exchange
"fit" by coincidence; a genuine hard swap measures ratio ~0). The upgrade
compares the ALIGNED PIXEL PAIR (ours rolled by the join's own shift, the
joint opaque mask) instead of the means: pixel_mae = mean |ours-truth| over
the joint opaque pixels, pixel_mae_rb = the same with ours' R/B exchanged,
pixel_flag = the exchange HALVES the pixel MAE above the floor. Additive
"pixel_swap" object + per-row pixel_* keys; report-only, never gates —
the same flip law hardens it after both flights' distributions agree.

Exit code 0 always: report-only, never gates.
"""
import argparse
import collections
import glob
import json
import os
import re
import sys
import tempfile

import numpy as np
from PIL import Image

# 14-e9.2 the channel-swap audit defaults — PROVISIONAL (docstring step 6):
# fixture-calibrated, never gate-read; a real dispatch calibrates them via
# --swap-factor/--swap-floor before they harden.
SWAP_FACTOR = 0.5
SWAP_FLOOR = 0.02
# 14-e280 the pixel audit's own floor: pixel MAE rides RAW 0-255 units (no
# relative normalization — the joint-opaque comparison needs no denominator),
# so the "no delta exists" guard is its own constant (2/255 ~ invisible).
PIXEL_SWAP_FLOOR = 2.0


def load_mean(png_path):
    a = np.asarray(Image.open(png_path).convert("RGBA"), dtype=np.float32)
    op = a[..., 3] > 8
    if not op.any():
        return None
    return a[..., :3][op].mean(axis=0)


def pixel_swap_pair(ours_png, truth_png, shift):
    """The aligned per-pixel R/B exchange audit (14-e280). Returns None when
    either crop is absent/unreadable or the joint opaque mask is skimpy
    (< 50 px — the honest naming upstream). Otherwise a dict with the
    unswapped and R/B-exchanged pixel MAEs and their ratio."""
    try:
        o = np.asarray(Image.open(ours_png).convert("RGBA"), dtype=np.float32)
        t = np.asarray(Image.open(truth_png).convert("RGBA"), dtype=np.float32)
    except Exception:
        return None
    if o.shape != t.shape:
        return None
    dx, dy = (shift or [0, 0])[:2]
    oo = np.roll(np.roll(o, int(dy), axis=0), int(dx), axis=1)
    joint = (o[..., 3] > 8) & (t[..., 3] > 8)
    if int(joint.sum()) < 50:
        return None
    diff = np.abs(oo[..., :3] - t[..., :3])[joint]
    swapped = np.abs(oo[..., [2, 1, 0]] - t[..., :3])[joint]
    mae = float(diff.mean())
    mae_rb = float(swapped.mean())
    return {"pixel_mae": round(mae, 4), "pixel_mae_rb": round(mae_rb, 4),
            "pixel_ratio": round(mae_rb / mae, 3) if mae > 0 else None,
            "pixel_n": int(joint.sum())}


def fixture_liveness(out_dir):
    """THE DETECTOR LIVENESS FIXTURE (the gate-grade precondition): a
    deterministic synthetic pair the audit MUST flag — ours = a structured
    pattern, truth = ours with R/B hard-swapped. The unswapped MAE is huge
    (the images disagree everywhere) while the swapped MAE collapses to ~0,
    so pixel_ratio << 1 and the flag must fire. Runs without any reports or
    seed (the liveness of the MATH inside the verdict environment); a NOT-
    detected fixture is the receipt that the audit ran dead. Returns the
    capture_integrity dict."""
    rng = np.random.default_rng(20261128)
    w, h = 96, 96
    base = np.zeros((h, w, 4), dtype=np.float32)
    base[..., :3] = rng.integers(16, 240, size=(h, w, 3))
    base[..., 3] = 255.0
    ours_path = os.path.join(out_dir, "fixture_ours.png")
    truth_path = os.path.join(out_dir, "fixture_truth.png")
    Image.fromarray(base.astype(np.uint8)).save(ours_path)
    swapped = base.copy()
    swapped[..., :3] = swapped[..., [2, 1, 0]]
    Image.fromarray(swapped.astype(np.uint8)).save(truth_path)
    pair = pixel_swap_pair(ours_path, truth_path, [0, 0])
    detected = bool(pair and pair["pixel_ratio"] is not None
                    and pair["pixel_ratio"] <= SWAP_FACTOR
                    and pair["pixel_mae"] > PIXEL_SWAP_FLOOR)
    os.remove(ours_path)
    os.remove(truth_path)
    return {
        "schema": "capture-integrity-v1",
        "status": "PROVISIONAL — report-only, never gate-read",
        "fixture": {
            "expected": "flagged (the truth IS ours' hard R/B swap)",
            "detected": detected,
            "pair": pair,
            "law": "the fixture NOT detected = the audit ran dead — the "
                   "gate-flip precondition (detected 3/3 consecutive runs) "
                   "can never start from a dead detector",
        },
    }


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
            if not os.path.isfile(p):
                self.capture_means[h] = None
            else:
                try:
                    self.capture_means[h] = load_mean(p)
                except Exception:
                    # the corrupt capture = the audit gap, named NO-CAPTURE
                    # upstream — the honest degradation, never a crash
                    self.capture_means[h] = None
        return self.capture_means[h]


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("reports_root", nargs="?", default=None)
    ap.add_argument("seed_scenes", nargs="?", default=None)
    ap.add_argument("--fixture-only", action="store_true",
                    help="run the detector-liveness fixture without reports"
                         " or seed (the capture-integrity receipt for the"
                         " verdict job)")
    ap.add_argument("--tolerance", type=float, default=0.18)
    ap.add_argument("--swap-factor", type=float, default=SWAP_FACTOR)
    ap.add_argument("--swap-floor", type=float, default=SWAP_FLOOR)
    ap.add_argument("--pixel-floor", type=float, default=PIXEL_SWAP_FLOOR,
                    help="the pixel audit's raw-MAE floor (0-255 units)")
    ap.add_argument("--no-pixel-audit", action="store_true",
                    help="skip the 14-e280 aligned per-pixel swap audit")
    ap.add_argument("--out", default="/tmp/capture-fingerprint.json")
    ap.add_argument("--top", type=int, default=24)
    args = ap.parse_args(argv)

    if args.fixture_only:
        integrity = fixture_liveness(tempfile.mkdtemp(prefix="capfix-"))
        with open(args.out, "w", encoding="utf-8") as fh:
            json.dump(integrity, fh, indent=1)
        det = integrity["fixture"]["detected"]
        pair = integrity["fixture"]["pair"] or {}
        print(f"capture-integrity fixture: detected={det} "
              f"(pixel_ratio={pair.get('pixel_ratio')}, n={pair.get('pixel_n')})")
        if not det:
            print("capture-integrity fixture: NOT DETECTED — the audit ran"
                  " dead; the gate-flip precondition can never start")
            return 2
        print(f"-> {args.out}")
        return 0

    if not args.reports_root or not args.seed_scenes:
        ap.error("reports_root and seed_scenes are required without --fixture-only")

    seed = SeedIndex(args.seed_scenes)
    # the capture means for the nearest-match search
    all_caps = {}
    tex_dir = seed.tex_dir
    for f in glob.glob(os.path.join(tex_dir, "*.png")):
        h = os.path.basename(f)[:-4]
        try:
            m = load_mean(f)
        except Exception:
            continue   # the corrupt capture never joins the nearest-match set
        if m is not None:
            all_caps[h] = m
    cap_ids = list(all_caps)
    cap_mat = np.array([all_caps[h] for h in cap_ids]) if cap_ids else np.zeros((0, 3))

    report_dirs = sorted(glob.glob(os.path.join(args.reports_root, "gallery-report-*")))
    census = collections.Counter()
    rows_out = []
    # 14-e9.2 the swap audit's own tallies (kept OUT of the census Counter —
    # a flagged row is still exactly one red row, and "red rows audited" must
    # keep counting rows, not flags)
    swap_pairs = 0
    swap_flagged = 0
    swap_subjects = []
    # 14-e280 the pixel audit's own tallies (separate from the mean tallies
    # for the same reason: rows, not flags)
    pixel_pairs = 0
    pixel_flagged = 0
    pixel_subjects = []
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
                try:
                    ours = load_mean(hits[0])
                except Exception:
                    # the crop file exists but yields no pixels — named, not
                    # a crash (the honesty law)
                    census["UNREADABLE-OURS"] += 1
                    continue
                if ours is None:
                    census["EMPTY-OURS"] += 1
                    continue
                rel = np.abs(ours - expected) / np.maximum(expected, 8.0)
                # 14-e9.2 the channel-swap audit: the SAME normalization,
                # computed once with ours' R/B exchanged. Swapping either
                # side is the identical metric (the exchange is an
                # involution; each denominator travels with its own
                # channel), so one direction covers both suspicions — ours
                # inverted at capture OR the expected capture inverted at
                # bake. The flag = the swap decisively EXPLAINS the delta:
                # unswapped MAE above the floor (a delta exists) and the
                # swapped MAE at most --swap-factor of it (the exchange
                # halves the error — no scaling mechanism ever does: the
                # match/dim/tint fixtures all measure ratio >= 1).
                swap_mae = float(rel.mean())
                swap_mae_rb = float(
                    (np.abs(ours[[2, 1, 0]] - expected)
                     / np.maximum(expected, 8.0)).mean())
                swap_pairs += 1
                if swap_mae > args.swap_floor:
                    swap_ratio = swap_mae_rb / swap_mae
                    swap_flag = bool(swap_ratio <= args.swap_factor)
                else:
                    swap_ratio = None   # the pair already fits: no delta
                    swap_flag = False   # for a swap to explain (0/0 guard)
                swap_row = {"swap_mae": round(swap_mae, 4),
                            "swap_mae_rb": round(swap_mae_rb, 4),
                            "swap_ratio": None if swap_ratio is None
                                          else round(swap_ratio, 3),
                            "swap_flag": swap_flag}
                if swap_flag:
                    swap_flagged += 1
                    swap_subjects.append({"page": page, "col": r["col"],
                                          "row": r["row"],
                                          "state": r.get("state", ""),
                                          "swap_mae": swap_row["swap_mae"],
                                          "swap_mae_rb": swap_row["swap_mae_rb"],
                                          "swap_ratio": swap_row["swap_ratio"]})
                # 14-e280 the aligned per-pixel audit on the SAME pair: the
                # mean metric's dispatch flag zone measured = the
                # sparse-alpha-mask artifact class; the pixel metric on the
                # joint opaque mask is the hardening candidate.
                px = None
                if not args.no_pixel_audit:
                    truth_stem = stem.replace("_ours.png", "_truth.png")
                    truth_hits = glob.glob(os.path.join(rd, "diff-*", truth_stem))
                    if truth_hits:
                        px = pixel_swap_pair(hits[0], truth_hits[0], r.get("shift"))
                    if px is not None:
                        pixel_pairs += 1
                        pr = px["pixel_ratio"]
                        px_flag = bool(pr is not None
                                       and px["pixel_mae"] > args.pixel_floor
                                       and pr <= args.swap_factor)
                        px["pixel_flag"] = px_flag
                        swap_row.update(px)
                        if px_flag:
                            pixel_flagged += 1
                            pixel_subjects.append({"page": page, "col": r["col"],
                                                   "row": r["row"],
                                                   "state": r.get("state", ""),
                                                   **px})
                if rel.mean() <= args.tolerance:
                    census["MATCH"] += 1
                    rows_out.append({"page": page, "col": r["col"],
                                     "row": r["row"], "state": r.get("state", ""),
                                     "class": "MATCH", "q": qhash, **swap_row})
                    continue
                scale = float(np.median(ours / np.maximum(expected, 8.0)))
                if 0.0 < scale < 0.6:
                    census["DIM"] += 1
                    rows_out.append({"page": page, "col": r["col"],
                                     "row": r["row"], "state": r.get("state", ""),
                                     "class": "DIM", "q": qhash,
                                     "scale": round(scale, 3), **swap_row})
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
                                 "q": qhash, "nearest": nearest, **swap_row})

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
    # 14-e9.2 the channel-swap summary line (additive — the census above and
    # its "red rows audited" total are untouched by the flag tally)
    if swap_pairs:
        print("\nchannel-swap audit (ours' R/B exchanged, the file's own normalization):")
        print(f"  pairs compared: {swap_pairs}   CHANNEL-SWAP flagged: {swap_flagged}")
        print(f"  threshold: swapped MAE <= {args.swap_factor} x unswapped MAE"
              f" (floor {args.swap_floor}) — PROVISIONAL, fixture-calibrated only;"
              " report-only, never gates")
        for s in swap_subjects[: args.top]:
            print(f"  {s['page']} c{s['col']} r{s['row']}  {s['state']}"
                  f"  ratio={s['swap_ratio']}")
    elif total:
        print("\nchannel-swap audit: 0 pairs compared (every red row named"
              " absent/empty upstream — the honest degradation)")
    # 14-e280 the pixel audit's summary (additive, report-only)
    if pixel_pairs:
        prs = [s["pixel_ratio"] for s in pixel_subjects]
        print("\npixel-swap audit (aligned pair, joint opaque mask, ours' R/B"
              " exchanged):")
        print(f"  pairs compared: {pixel_pairs}   PIXEL-SWAP flagged:"
              f" {pixel_flagged}")
        print(f"  threshold: swapped pixel MAE <= {args.swap_factor} x"
              f" unswapped (floor {args.swap_floor}) — report-only, never"
              " gates")
        for s in pixel_subjects[: args.top]:
            print(f"  {s['page']} c{s['col']} r{s['row']}  {s['state']}"
                  f"  pixel_ratio={s['pixel_ratio']} n={s['pixel_n']}")
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump({"census": dict(census), "rows": rows_out,
                   "channel_swap": {
                       "threshold": f"swapped MAE <= {args.swap_factor} x unswapped MAE,"
                                    f" unswapped MAE > {args.swap_floor}",
                       "status": "PROVISIONAL — fixture-calibrated, never gate-read",
                       "pairs": swap_pairs, "flagged": swap_flagged,
                       "subjects": swap_subjects[: args.top]},
                   "pixel_swap": {
                       "threshold": f"swapped pixel MAE <= {args.swap_factor} x"
                                    f" unswapped, unswapped > {args.pixel_floor}"
                                    " (raw 0-255), joint opaque mask >= 50 px",
                       "status": "PROVISIONAL — report-only, never gate-read",
                       "pairs": pixel_pairs, "flagged": pixel_flagged,
                       "subjects": pixel_subjects[: args.top]}}, fh, indent=1)
    print(f"\n-> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
