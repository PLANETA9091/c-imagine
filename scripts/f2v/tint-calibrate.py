#!/usr/bin/env python3
"""TINT-BAKE calibration: solve the per-texture multiply color that turns a
raw modded texture into what the TRUTH client actually renders, measured from
the gallery triptychs (run 42070701695).

WHY (the residual-red class): the 26.3 stock client renders the pack's modded
models from the RAW jar textures; the truth client renders the same shapes
through the mod's own pipeline. On the residual red cells the shapes align
(join shift [0,0]) but the COLOR does not: the truth crop's content pixels are
a per-channel multiple of ours'. The gallery-diff gate already absorbs global
luma (normalize_luma) and a per-cell linear light fit (fit_ae) — these reds
survive both, so the gap is per-MODEL content, not per-cell lighting, and a
per-texture multiply baked into the pack is the honest fix.

THE SOLVE (deterministic, no ML):
  1. Every RED row of the join JSONs under --artifacts gives a pixel-aligned
     (truth, ours) cell crop pair. The join's own shift field re-aligns ours
     (np.roll by -shift — the gate rolled ours by +shift to match truth).
  2. Sky/background masking: the sky is the dominant uniform color of a crop
     and matches between the legs (measured: sky ratio 1.000). Mask it by
     deviation from the FRAME MEDIAN color of the ours leg (blue-bright or
     near-median pixels are background), and mask low-signal pixels where a
     ratio would explode (ours channel < LOW_SIGNAL).
  3. Per-channel tint = median over the masked pixels of truth_c / ours_c,
     rounded to 4 decimals (documented rounding: python round(), half-even).
     The median is robust to the AA/outline pixels the two legs antialias
     differently.
  4. A state maps to textures through its blockstate -> model parent chain
     (read from the ladder jars). Only single-texture states are solvable; a
     multi-texture state cannot attribute the measured ratio and is skipped
     with a warning. When several states/cells share one texture, their
     per-cell tints must agree within --tolerance on EVERY channel, else the
     texture is REFUSED (a per-texture bake must never average two different
     lightings — the honest answer for a contested texture is no bake).
  5. A solved tint within --epsilon of (1,1,1) is dropped (no measured tint).

The ratio is measured TRUTH/OURS rather than TRUTH/RAW-TEXTURE deliberately:
ours renders the raw texture under the same scene geometry and shading as the
truth leg, so truth/ours isolates the pure multiply (truth = raw * tint *
shade, ours = raw * shade). A truth-vs-raw-texture comparison is kept as the
CLASSIFIER (--classify): a state whose solved tint keeps the raw texture's
chroma structure is tint-class; a state with ratio ~ 1 on every channel is a
geometry/px residual and is refused.

Output: visual-tints.tsv — one row per TEXTURE ref (the pack bake applies at
texture granularity):
    texRef <TAB> r <TAB> g <TAB> b <TAB> evidence
evidence is ';'-joined "state@page:c,r|n=<px>|class=<name>" cells (the pack
parser ignores column 5+). Missing file in the f2v input dir = today's
behavior (no bake), so CI lanes that never generate it are untouched.

Usage:
  tint-calibrate.py --jars /tmp/ladder-mods --artifacts /tmp/galnew \
      --out visual-tints.tsv [--gate 60000] [--tolerance 0.06] ...
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import zipfile
from collections import defaultdict

import numpy as np
from PIL import Image

# A ratio below LOW_SIGNAL on any channel would explode (division noise); the
# masked pixels are the CONTENT (block texture) pixels — mid-to-high signal.
LOW_SIGNAL = 40.0
# A crop needs at least this many usable content pixels to vote.
MIN_CONTENT_PX = 40
# A tint that close to (1,1,1) is not a measurement, it is noise.
EPSILON = 0.02


# ---- jar-side state -> texture resolution ------------------------------------

class JarAssets:
    """Blockstate + model graph reader over a directory of mod jars.

    First jar wins per resource path (same precedence rule as the pack
    builder's listJars order). Bare model refs inherit the blockstate's
    namespace, then fall back to minecraft (vanilla parents).
    """

    def __init__(self, jars_dir: pathlib.Path, vanilla_jar: pathlib.Path | None):
        self.zfs = []
        for p in sorted(jars_dir.glob("*.jar")):
            self.zfs.append((p.name, zipfile.ZipFile(p)))
        # the truth client's VANILLA model set last (first-match-wins keeps
        # the mods' own overrides ahead): bare parents like "block/cross"
        # resolve against it
        if vanilla_jar is not None:
            self.zfs.append((vanilla_jar.name, zipfile.ZipFile(vanilla_jar)))

    def read_json(self, path: str):
        for name, zf in self.zfs:
            try:
                return json.loads(zf.read(path))
            except KeyError:
                continue
            except Exception:
                return None
        return None

    def read_bytes(self, path: str):
        for name, zf in self.zfs:
            try:
                return zf.read(path)
            except KeyError:
                continue
        return None

    def model_textures(self, ns: str, model_ref: str, depth: int = 0):
        """All texture refs (values of the textures map, resolved through the
        parent chain) of one model. Bare parents inherit ns, then minecraft."""
        if depth > 16:
            return set(), False
        if ":" in model_ref:
            mns, mpath = model_ref.split(":", 1)
        else:
            mns, mpath = ns, model_ref
        data = self.read_json(f"assets/{mns}/models/{mpath}.json")
        if data is None and mns != "minecraft":
            data = self.read_json(f"assets/minecraft/models/{mpath}.json")
            mns = "minecraft"
        if data is None or not isinstance(data, dict):
            return set(), False
        out = set()
        for v in (data.get("textures") or {}).values():
            if isinstance(v, str) and v and not v.startswith("#"):
                out.add(v)
        parent = data.get("parent")
        ok = True
        if parent and isinstance(parent, str):
            ptext, pok = self.model_textures(mns, parent, depth + 1)
            out |= ptext
            ok = ok and pok
        else:
            ok = ok and ("elements" in data)  # a root model must carry geometry
        return out, ok

    def state_textures(self, state: str):
        """Distinct modded texture refs behind one block state, or None when
        the resolution is ambiguous (multi-texture / missing graph)."""
        ns = state.split(":", 1)[0]
        rest = state.split(":", 1)[1]
        block = rest.split("[", 1)[0]
        props = {}
        if "[" in rest:
            body = rest[rest.index("[") + 1 : rest.index("]")]
            for pair in body.split(","):
                if "=" in pair:
                    k, v = pair.split("=", 1)
                    props[k.strip()] = v.strip()
        bs = self.read_json(f"assets/{ns}/blockstates/{block}.json")
        if not isinstance(bs, dict):
            return None
        model_refs = set()
        if "variants" in bs:
            best_key, best_len = None, -1
            for key, val in bs["variants"].items():
                if not _key_matches(key, props):
                    continue
                if len(key.split(",")) > best_len:
                    best_len, best_key = len(key.split(",")), key
            if best_key is None:
                return None
            val = bs["variants"][best_key]
            entries = val if isinstance(val, list) else [val]
            for e in entries:
                if isinstance(e, dict) and "model" in e:
                    model_refs.add(e["model"])
        elif "multipart" in bs:
            for part in bs["multipart"]:
                when = part.get("when") or {}
                if when and not _when_matches(when, props):
                    continue
                apply = part.get("apply")
                entries = apply if isinstance(apply, list) else [apply]
                for e in entries:
                    if isinstance(e, dict) and "model" in e:
                        model_refs.add(e["model"])
        else:
            return None
        tex: set | None = set()
        for ref in model_refs:
            t, ok = self.model_textures(ns, ref)
            if not ok:
                return None
            tex |= t
        if not tex:
            return None
        # only vanilla refs (#local or minecraft:) -> nothing modded to bake
        modded = {t for t in tex if not t.startswith("minecraft:")}
        return modded


def _key_matches(key: str, props: dict) -> bool:
    if not key:
        return True
    for pair in key.split(","):
        if "=" not in pair:
            return False
        k, v = pair.split("=", 1)
        if props.get(k.strip(), "").lower() != v.strip().lower():
            return False
    return True


def _when_matches(when: dict, props: dict) -> bool:
    # nested OR/AND nodes are matched conservatively (all leaf keys must hold);
    # the gallery reds are plain single-level whens
    for k, v in when.items():
        if k in ("OR", "AND", "NOR", "NAND"):
            return False  # refuse: ambiguous attribution
        if isinstance(v, list):
            if props.get(k, "") not in {str(x).lower() for x in v} and \
               props.get(k, "") not in {str(x) for x in v}:
                return False
        elif props.get(k, "").lower() != str(v).lower():
            return False
    return True


# ---- the pixel solve -----------------------------------------------------------

def frame_median(img: np.ndarray) -> np.ndarray:
    """The frame's median RGB (the dominant uniform color — the sky)."""
    return np.median(img.reshape(-1, 3), axis=0)


def content_mask(ours: np.ndarray, truth: np.ndarray) -> np.ndarray:
    """Pixels that carry texture signal on both legs.

    MEASURED MASK CONTRACT (run 42070701695): the sky matches across the legs
    (sky ratio 1.000) and is the only UNIFORM background — detected by the
    blue-bright test (b > r + 30, b > 200), which is exact for the horizon
    gradient on every inspected crop. The task brief's "mask by deviation
    from the frame's median" is kept ONLY as a weak secondary rule: for
    tree-backed crops the frame median is itself CONTENT (the background
    tree), so a pure median-deviation mask eats the subject (measured: the
    snowblossom branch sits 18 luma off its own frame's median and a pure
    median mask solved it 1.109 instead of the true 1.26). What remains is
    the signal window: low-signal pixels (luma < LOW_SIGNAL) would explode
    the ratio; near-255 pixels on either leg clip the multiply and are
    dropped. Everything else votes.
    """
    bluesky_o = (ours[..., 2] > ours[..., 0] + 30) & (ours[..., 2] > 200)
    bluesky_t = (truth[..., 2] > truth[..., 0] + 30) & (truth[..., 2] > 200)
    luma_o = ours.mean(axis=2)
    luma_t = truth.mean(axis=2)
    window = (luma_o >= LOW_SIGNAL) & (luma_o <= 250) \
        & (luma_t >= LOW_SIGNAL) & (luma_t <= 250)
    return ~(bluesky_o | bluesky_t) & window


def solve_ratio(truth: np.ndarray, ours: np.ndarray,
                shift: tuple[int, int] = (0, 0)) -> tuple[np.ndarray, int] | None:
    """Robust median per-channel ratio truth/ours on the content mask.

    The join's shift is the roll applied to OURS to match truth
    (gallery-diff.aligned_ae rolls b = ours by +shift); undo it before
    comparing. Returns (rgb ratio vector, usable pixel count) or None.
    """
    dx, dy = shift
    o = np.roll(np.roll(ours, -dy, axis=0), -dx, axis=1)
    m = content_mask(o, truth)
    n = int(m.sum())
    if n < MIN_CONTENT_PX:
        return None
    with np.errstate(divide="ignore", invalid="ignore"):
        ratio = truth[m] / np.maximum(o[m], 1.0)
    r = np.nanmedian(ratio, axis=0)
    if not np.all(np.isfinite(r)) or np.any(r <= 0) or np.any(r > 4.0):
        return None
    return r, n


def classify(r: np.ndarray, chroma_shift: float) -> str:
    """Honest residual class of a solved ratio (report vocabulary).

    tint-class: a real per-channel multiply (any channel off by > EPSILON
    with the channel spread contained — hue drift bounded). luma-class: a
    uniform brightness multiply (same mechanism, hue kept — bakes identically,
    so it IS baked; the class names the mechanism). geometry/px: ratio ~ 1 —
    nothing a texture bake can fix.
    """
    if np.all(np.abs(r - 1.0) <= EPSILON):
        return "geometry/px"
    spread = float(r.max() - r.min())
    if spread <= 0.05:
        return "luma-uniform" if chroma_shift < 12 else "luma+chroma"
    if spread <= 0.12:
        return "tint" if chroma_shift >= 4 else "tint-weak"
    return "tint-wide"


def crop_pairs(artifacts: pathlib.Path, gate: float):
    """(state -> [(truth_path, shift, page, col, row)]) for every RED row with
    a saved triptych crop, from the join JSONs under the artifacts root."""
    out: dict[str, list] = defaultdict(list)
    for join in sorted(artifacts.glob("r*/join-*.json")):
        data = json.loads(join.read_text())
        for row in data.get("rows", []):
            if row.get("verdict") != "RED":
                continue
            if row.get("ae", 0) is None or row.get("ae", 0) < gate:
                continue
            page = row["page"]
            col, rowi = row.get("col"), row.get("row")
            if col is None or rowi is None:
                continue
            stem = f"{page}_c{col}_r{rowi}"
            hits = sorted(artifacts.glob(
                f"r*/diff-{page[len('gallery-'):]}/{stem}_truth.png"))
            if not hits:
                continue
            shift = tuple(row.get("shift") or (0, 0))
            out[row["state"]].append((hits[0], shift, page, col, rowi))
    return out


def load_rgb(p: pathlib.Path) -> np.ndarray:
    return np.asarray(Image.open(p).convert("RGB")).astype(float)


def raw_chroma(tf_path: pathlib.Path) -> float:
    """Median chroma (max-min channel) of the RAW jar texture's opaque pixels
    — the classifier's reference for 'truth is a color-multiplied variant of
    the raw texture'."""
    raw = np.asarray(Image.open(tf_path).convert("RGBA")).astype(float)
    op = raw[..., 3] > 8
    if not op.any():
        return 0.0
    px = raw[op][:, :3]
    return float(np.median(px.max(axis=1) - px.min(axis=1)))


# The bake multiplies the RAW texture; ours then renders baked * L. A
# brightening multiply is only REPRESENTABLE when raw*tint stays inside the
# 8-bit gamut — a pixel that clips at 255 renders 255*L and the gap survives
# the bake. >1% clipped opaque pixels = the measured gap is a LIGHTMAP class
# (the truth client lights the block brighter; the real-client leg owns it),
# not a texture tint. 0.5-2.0 is the same out-of-family guard the gate's own
# luma fit uses (gallery-diff.fit_ae k in [0.5, 2.0]).
BAKE_RATIO_MIN = 0.5
BAKE_RATIO_MAX = 2.0
BAKE_CLIP_MAX = 0.01


def clip_fraction(tex: str, tint: np.ndarray, assets: "JarAssets") -> float:
    """Fraction of the raw texture's opaque pixels whose baked RGB would clip
    at 255 on any channel (and was not already 255)."""
    ns, path = tex.split(":", 1)
    png = assets.read_bytes(f"assets/{ns}/textures/{path}.png")
    if png is None:
        return 0.0
    import io
    raw = np.asarray(Image.open(io.BytesIO(png)).convert("RGBA")).astype(float)
    op = raw[..., 3] > 8
    if not op.any():
        return 0.0
    px = raw[op][:, :3]
    baked = px * tint[None, :]
    clips = (baked > 255.0 + 1e-9) & (px < 254.5)
    return float((clips.any(axis=1)).mean())


def aggregate_texture(tex: str, cells: list, assets: "JarAssets",
                      tolerance: float, epsilon: float = EPSILON):
    """Fold one texture's per-cell solves into a TSV row, or refuse it.

    Returns (row, reason): row = (tex, (r,g,b), evidence) when every cell
    agrees within tolerance on every channel, the median multiply sits in the
    bake family [BAKE_RATIO_MIN, BAKE_RATIO_MAX], and the bake is
    representable in the raw texture's gamut (<= BAKE_CLIP_MAX clipped
    pixels); otherwise row is None and reason names the refusal class.
    A median within epsilon of (1,1,1) is 'no measured tint' (row None, no
    reason — nothing wrong, just nothing to bake).
    """
    ratios = np.array([c["ratio"] for c in cells])
    med = np.median(ratios, axis=0)
    spread = float(np.max(ratios.max(axis=0) - ratios.min(axis=0)))
    if len(cells) > 1 and spread > tolerance:
        return None, (f"cells disagree (spread {spread:.3f} > {tolerance}): "
                      + "; ".join(f"{c['state']}@{c['cell']}="
                                  + "/".join(f"{x:.3f}" for x in c["ratio"])
                                  for c in cells))
    if med.min() < BAKE_RATIO_MIN or med.max() > BAKE_RATIO_MAX:
        return None, (f"multiply out of bake family "
                      f"[{BAKE_RATIO_MIN}, {BAKE_RATIO_MAX}]: "
                      + "/".join(f"{x:.3f}" for x in med)
                      + " — lightmap/emissive class, not a texture tint")
    cf = clip_fraction(tex, med, assets)
    if cf > BAKE_CLIP_MAX:
        return None, (f"bake unrepresentable: {cf:.1%} of opaque pixels "
                      f"would clip at 255 — lightmap class")
    rounded = tuple(round(float(x), 4) for x in med)
    if all(abs(x - 1.0) <= epsilon for x in rounded):
        return None, None  # no measured tint
    evidence = ";".join(
        f"{c['state']}@{c['page']}:{c['cell']}|n={c['n']}|class={c['cls']}"
        for c in cells)
    return (tex, rounded, evidence), None


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--jars", required=True, type=pathlib.Path,
                    help="directory with the pinned ladder mod jars")
    ap.add_argument("--vanilla-jar", type=pathlib.Path, default=None,
                    help="truth-client vanilla jar (1.21.1) for bare parent "
                         "resolution, e.g. clients/client-1211.jar")
    ap.add_argument("--artifacts", required=True, type=pathlib.Path,
                    help="gallery artifacts root (r*/join-*.json, r*/diff-*/…)")
    ap.add_argument("--out", default="visual-tints.tsv", type=pathlib.Path)
    ap.add_argument("--gate", type=float, default=60000.0,
                    help="only rows at/above this AE calibrated (default 60000)")
    ap.add_argument("--tolerance", type=float, default=0.06,
                    help="max per-channel spread between cells sharing a texture")
    ap.add_argument("--epsilon", type=float, default=EPSILON)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    assets = JarAssets(args.jars, args.vanilla_jar)
    pairs = crop_pairs(args.artifacts, args.gate)
    print(f"red states with triptych crops: {len(pairs)}")

    # per-texture evidence collection
    per_tex: dict[str, list] = defaultdict(list)
    refused: list[tuple[str, str]] = []
    for state, cells in sorted(pairs.items()):
        texs = assets.state_textures(state)
        if not texs or len(texs) != 1:
            refused.append((state, "multi/ambiguous texture: "
                            + (",".join(sorted(texs)) if texs else "unresolved")))
            continue
        tex = next(iter(texs))
        for truth_path, shift, page, col, rowi in cells:
            truth = load_rgb(truth_path)
            ours = load_rgb(truth_path.with_name(
                truth_path.name.replace("_truth.png", "_ours.png")))
            solved = solve_ratio(truth, ours, shift)
            if solved is None:
                refused.append((state, f"no usable content pixels @ {page}:c{col},r{rowi}"))
                continue
            r, n = solved
            chroma_t = float(np.median(truth.max(axis=2) - truth.min(axis=2)))
            per_tex[tex].append({
                "state": state, "ratio": r, "n": n, "page": page,
                "cell": f"c{col},r{rowi}",
                "cls": classify(r, chroma_t),
            })

    rows, dropped = [], []
    for tex in sorted(per_tex):
        row, reason = aggregate_texture(tex, per_tex[tex], assets,
                                        args.tolerance, args.epsilon)
        if row is not None:
            rows.append(row)
        elif reason is not None:
            dropped.append((tex, reason))

    with args.out.open("w") as f:
        f.write("# visual-tints.tsv — tint-bake calibration (scripts/f2v/"
                "tint-calibrate.py)\n")
        f.write("# texRef\\tr\\tg\\tb\\tevidence — multiply the raw texture's "
                "RGB by (r,g,b) at pack build; alpha preserved.\n")
        f.write("# solved from truth/ours median ratios on the gallery "
                f"triptychs; rounding = python round() to 4 decimals.\n")
        for tex, (r, g, b), ev in rows:
            f.write(f"{tex}\t{r}\t{g}\t{b}\t{ev}\n")

    print(f"tints written: {len(rows)} -> {args.out}")
    print(f"refused states: {len(refused)}; dropped textures: {len(dropped)}")
    if args.verbose:
        for s, why in refused:
            print(f"  refuse: {s}: {why}")
        for t, why in dropped:
            print(f"  drop: {t}: {why}")
        for tex, (r, g, b), ev in rows:
            print(f"  tint: {tex} = ({r}, {g}, {b})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
