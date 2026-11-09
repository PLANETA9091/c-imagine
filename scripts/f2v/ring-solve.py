#!/usr/bin/env python3
"""14-df CORRIDOR RING SOLVER — visual-rings.tsv for the two-lighting casing class.

The captured mesh rows carry the seed scene's shade constants (0.5-0.8 grays,
the vanilla directional shades + the capture's lightmap cast). The gallery
corridor renders the same ambient-lit rows darker: the truth's casing carries
the wall's directional/ambient contribution ours cannot know from the capture
(the 14-de verdict: ours/truth 2.5-3x on the casing class, the frames and
windows matching at identity). A per-STATE multiply cannot express it (the
contested-texture law: frame + casing share one sprite), but the ROW-level
node tint can: the ring row scales each ambient-lit row's current factor by
the measured corridor ratio, leaving the identity (fullbright art) rows and
the (15,15) mod-light rows untouched (GalleryPack.ringTint).

Measurement (per RED state, from the run's own crops):
  per-pixel ratio truth/ours on the content mask (tint-calibrate's contract),
  clustered by luma ratio: the DIM cluster ([0.25, 0.82]) is the casing
  population, the MATCHED cluster ([0.88, 1.15]) the frame/window population.
  A state votes when the dim cluster is substantial, tight, and the matched
  cluster really matches — the pure ring signature. k = the dim cluster's
  per-channel median (the ON-TOP correction: ours already renders the
  captured shade the gate applied, so final = current_factor x k).

Row schema (visual-rings.tsv, the readTintManifest parser shape):
  state <TAB> kr <TAB> kg <TAB> kb <TAB> evidence
Absent file = no ring route (byte-identical packs).

Usage:
  ring-solve.py --artifacts /tmp/r302 --out scripts/f2v/visual-rings.tsv \
      [--existing scripts/f2v/visual-tints.tsv] [--gate 40000] [--min-dim-frac 0.12]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
from collections import defaultdict

import numpy as np
from PIL import Image

LOW_SIGNAL = 40.0
MIN_CONTENT_PX = 40
DIM_LO, DIM_HI = 0.25, 0.82
MATCH_LO, MATCH_HI = 0.88, 1.15
K_MIN, K_MAX = 0.30, 0.85
CHANNEL_SPREAD_MAX = 0.15
MIN_DIM_PX = 60


def content_mask(ours: np.ndarray, truth: np.ndarray) -> np.ndarray:
    """tint-calibrate's mask contract: drop the blue sky, the low-signal
    pixels and the clipping-bright pixels; everything else votes."""
    bluesky_o = (ours[..., 2] > ours[..., 0] + 30) & (ours[..., 2] > 200)
    bluesky_t = (truth[..., 2] > truth[..., 0] + 30) & (truth[..., 2] > 200)
    luma_o = ours.mean(axis=2)
    luma_t = truth.mean(axis=2)
    window = (luma_o >= LOW_SIGNAL) & (luma_o <= 250) \
        & (luma_t >= LOW_SIGNAL) & (luma_t <= 250)
    return ~(bluesky_o | bluesky_t) & window


def load_rgb(p: pathlib.Path) -> np.ndarray:
    return np.asarray(Image.open(p).convert("RGB")).astype(float)


def join_scans(artifacts: pathlib.Path):
    """(state -> [(page, col, row, shift, ae)]) for every RED row in the
    run's join JSONs; the layout accepts both the CI extraction (r*/join-*)
    and the per-shard subdirs (r*/*/join-*)."""
    out: dict[str, list] = defaultdict(list)
    for join in sorted(artifacts.glob("**/join-*.json")):
        data = json.loads(join.read_text())
        rows = data.get("rows")
        if rows is None:
            rows = data.get("cells")
        if not isinstance(rows, list):
            continue
        for row in rows:
            if row.get("verdict") != "RED":
                continue
            ae = row.get("ae") or 0
            col, rowi = row.get("col"), row.get("row")
            if col is None or rowi is None:
                continue
            out[row["state"]].append((row["page"], col, rowi,
                                      tuple(row.get("shift") or (0, 0)), ae))
    return out


def crop_pair(artifacts: pathlib.Path, page: str, col: int, rowi: int):
    stem = f"{page}_c{col}_r{rowi}_truth.png"
    hits = sorted(artifacts.glob(f"**/diff-{page[len('gallery-'):]}/{stem}"))
    if not hits:
        return None
    tp = hits[0]
    return tp, tp.with_name(tp.name.replace("_truth.png", "_ours.png"))


def cell_ring(truth: np.ndarray, ours: np.ndarray, shift: tuple[int, int]):
    """(k_rgb, n_dim, n_match, dim_mad, matched_med) or (None, reason)."""
    dx, dy = shift
    o = np.roll(np.roll(ours, -dy, axis=0), -dx, axis=1)
    m = content_mask(o, truth)
    n_content = int(m.sum())
    if n_content < MIN_CONTENT_PX:
        return None, f"thin content ({n_content}px)"
    with np.errstate(divide="ignore", invalid="ignore"):
        ratio = truth[m] / np.maximum(o[m], 1.0)
    luma = ratio.mean(axis=1)
    sig = o[m].mean(axis=1)
    dim = (luma >= DIM_LO) & (luma <= DIM_HI) & (sig >= LOW_SIGNAL)
    match = (luma >= MATCH_LO) & (luma <= MATCH_HI)
    n_dim, n_match = int(dim.sum()), int(match.sum())
    if n_dim < MIN_DIM_PX or n_dim < MIN_DIM_FRAC * n_content:
        return None, (f"dim cluster too small ({n_dim}/{n_content}, "
                      f"match {n_match})")
    if n_match < n_dim * 0.5:
        return None, (f"no matched frame population ({n_match} vs dim "
                      f"{n_dim}) — not the ring class")
    k = np.nanmedian(ratio[dim], axis=0)
    if not np.all(np.isfinite(k)) or np.any(k < K_MIN) or np.any(k > K_MAX):
        return None, ("k out of family [" + str(K_MIN) + "," + str(K_MAX)
                      + "]: " + "/".join(f"{x:.3f}" for x in k))
    if float(k.max() - k.min()) > CHANNEL_SPREAD_MAX:
        return None, ("channel spread " + f"{float(k.max()-k.min()):.3f} "
                      f"> {CHANNEL_SPREAD_MAX} — chromatic, not shade")
    dl = luma[dim]
    mad = float(np.median(np.abs(dl - np.median(dl))))
    mm = float(np.median(luma[match]))
    return (k, n_dim, n_match, mad, mm), None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--artifacts", required=True, type=pathlib.Path)
    ap.add_argument("--out", required=True, type=pathlib.Path)
    ap.add_argument("--existing", type=pathlib.Path, default=None)
    ap.add_argument("--gate", type=float, default=40000.0)
    ap.add_argument("--min-dim-frac", type=float, default=0.12)
    global MIN_DIM_FRAC
    MIN_DIM_FRAC = ap.parse_args().min_dim_frac
    args = ap.parse_args()

    existing_rows: dict[str, tuple] = {}
    if args.existing and args.existing.exists():
        for line in args.existing.read_text().splitlines():
            if line.strip() and not line.startswith("#"):
                c = line.split("\t")
                if len(c) >= 4:
                    try:
                        existing_rows[c[0]] = tuple(float(x) for x in c[1:4])
                    except ValueError:
                        pass

    scans = join_scans(args.artifacts)
    red_cells_total = sum(len(v) for v in scans.values())
    print(f"red rows in joins: {red_cells_total} across {len(scans)} states")

    rows: list[tuple] = []
    refused: list[tuple[str, str]] = []
    for state, cells in sorted(scans.items()):
        # The ring composes with every standing instrument BY DESIGN: the
        # measured dim ratio is truth/ours on the CURRENT render (whatever
        # the texture/state bakes already applied), so final = current x k
        # lands exactly on the measured target. No skip — only the report.
        ks, evs, covered = [], [], 0
        for page, col, rowi, shift, ae in cells:
            if ae < args.gate:
                continue
            pair = crop_pair(args.artifacts, page, col, rowi)
            if pair is None or not pair[1].exists():
                continue
            truth = load_rgb(pair[0])
            ours = load_rgb(pair[1])
            solved, reason = cell_ring(truth, ours, shift)
            if solved is None:
                refused.append((state, f"{page}:c{col},r{rowi} {reason}"))
                continue
            k, n_dim, n_match, mad, mm = solved
            ks.append(k)
            evs.append(f"{state}@{page}:c{col},r{rowi}|n_dim={n_dim}"
                       f"|n_match={n_match}|mad={mad:.3f}|match={mm:.3f}")
            covered += 1
        if not ks:
            if covered == 0 and not any(r[0] == state for r in refused):
                refused.append((state, "no crops at gate"))
            continue
        k = np.median(ks, axis=0)
        rounded = tuple(round(float(x), 4) for x in k)
        evidence = ";".join(evs)
        rows.append((state, rounded, evidence, covered))
        print(f"RING {state}  k={rounded}  cells={covered}/{len(cells)}")

    out = args.out
    header = (
        "# visual-rings.tsv — the 14-df corridor ring route (scripts/f2v/ring-solve.py)\n"
        "# state\tkr\tkg\tkb\tevidence — scales the state's ambient-lit captured rows'\n"
        "# node tint by the measured corridor ratio (truth/ours on the dim cluster);\n"
        "# the identity (fullbright art) and (15,15) rows stay untouched (GalleryPack.ringTint).\n"
        f"# solved from the run's own crops; k = the on-top correction (ours already renders\n"
        f"# the captured shade the 14-da gate applies).\n")
    with out.open("w") as f:
        f.write(header)
        for state, k, ev, _ in rows:
            f.write(f"{state}\t{k[0]}\t{k[1]}\t{k[2]}\t{ev}\n")
    covered_total = sum(c for *_, c in rows)
    print(f"\nwrote {len(rows)} ring rows -> {out}")
    print(f"red-cell coverage at gate: {covered_total}")
    print("\nrefusals (first 25):")
    for s, r in refused[:999]:
        print(f"  {s[:70]:70s} {r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
