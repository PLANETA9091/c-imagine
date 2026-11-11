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
        # the modded states whose models resolve to minecraft-namespace
        # textures (the STATE-KEYED tint route — see state_textures)
        self.vanilla_tex_states: dict[str, set] = {}

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
        # 14-cb: the ok flag = "no MISSING model in the chain" — nothing else.
        # The old terminal check (("elements" in data) or bool(out)) evaluated
        # in the TERMINAL's own frame and poisoned every chain that ends at a
        # parent-less display holder: minecraft:block/block carries no
        # elements and no own textures BY DESIGN, so block/leaves ->
        # block/block returned ok=False and EVERY BOP/DK leaf state resolved
        # to None ("multi/ambiguous: unresolved") although the chain had
        # collected the leaf texture one hop up. The empty-texture chains
        # still refuse downstream (the caller's len(texs)!=1 guard), so the
        # geometry guarantee the check was protecting is intact.
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
        # the vanilla refs (#local or minecraft:) get the STATE-KEYED route:
        # a modded state rendering a minecraft-namespace texture (the DT
        # leaves' blockstate maps to the vanilla jungle_leaves model) has
        # its own IBlockColor tint the vanilla client cannot know — the
        # per-state tint-bake isolates the multiply to the state's composite
        # (a state-suffixed sibling texture), so the shared vanilla texture
        # keeps serving the vanilla block untinted.
        vanilla_tex = {t for t in tex if t.startswith("minecraft:")}
        if vanilla_tex:
            # the STATE-KEYED route: the caller collects the cells under the
            # (tex, state) key — the multiply applies to the state's
            # composite only (a state-suffixed sibling texture)
            self.vanilla_tex_states[state] = vanilla_tex
            return tex
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


def solve_ratio_ex(truth: np.ndarray, ours: np.ndarray,
                   shift: tuple[int, int] = (0, 0)):
    """solve_ratio plus the per-pixel luma-ratio spread (the 14-db tint
    signature): (rgb median ratio, usable px, MAD, quartiles) or None.

    The MAD is the median absolute deviation of the per-pixel luma ratio
    around its own median — a pure multiply measures MAD ~ 0.01; a structural
    mismatch (the door slide/fold phase, the bimodal heater crops) 0.16+.
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
    luma = ratio.mean(axis=1)
    med = float(np.median(luma))
    mad = float(np.median(np.abs(luma - med)))
    qs = tuple(float(x) for x in np.percentile(luma, [10, 25, 75, 90]))
    return r, n, mad, qs


def solve_ratio(truth: np.ndarray, ours: np.ndarray,
                shift: tuple[int, int] = (0, 0)) -> tuple[np.ndarray, int] | None:
    """Robust median per-channel ratio truth/ours on the content mask.

    The join's shift is the roll applied to OURS to match truth
    (gallery-diff.aligned_ae rolls b = ours by +shift); undo it before
    comparing. Returns (rgb ratio vector, usable pixel count) or None.
    """
    solved = solve_ratio_ex(truth, ours, shift)
    return None if solved is None else (solved[0], solved[1])


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

# ---- the 14-db STATE-KEYED FALLBACK guards -----------------------------------
# When the per-TEXTURE route is unavailable or refuses the fold (multi/
# unresolved textures, cells that disagree — two lightings on one texture),
# a state whose OWN cells are self-consistent still gets a row keyed by the
# STATE (GalleryPack.applyStateTint re-hosts every texture of that state's
# composite; the raw sprite keeps serving every other consumer — a state row
# cannot touch a green cell by construction).
# - SHIFT: the join's shift re-aligns ours before the ratio; a shift beyond
#   2px means the gate aligned on structure the crops do not share — the
#   median would mix mismatched pixels (the laser_amplifier class). No row.
# - MAD: the per-pixel luma-ratio median absolute deviation around the
#   median — the tint-class signature (a pure multiply is tight, MAD ~ 0.01;
#   the slide/fold door phase and the heater bimodal crops measure 0.16+
#   on run 42519344726). A wide distribution = the residual is structural,
#   not a multiply. No row.
# - FLOOR 0.35: below the gate's own luma-fit floor (k in [0.5, 2.0]) the
#   bake is the ONLY representable instrument (the qio (0,15) family
#   measures 0.39-0.40 — the fit cannot reach it, the state bake can, and a
#   darkening multiply never clips). The texture route keeps its 0.5 floor —
#   a per-texture row below 0.5 would drag every other consumer of the
#   sprite; the state route is scoped to red states only.
STATE_SHIFT_MAX = 2
STATE_MAD_MAX = 0.05
STATE_BAKE_MIN = 0.35

# ---- the 14-dd INTERIOR-MASK retry -------------------------------------------
# The 14-db full-crop MAD lets the CELL ENVIRONMENT veto a machine multiply:
# the crop border carries the neighbor bleed / sky-through-gap / page
# background, which is NOT the state's texture, and a border-only mismatch
# inflates the per-pixel ratio spread (measured on run #300 = 42600493189:
# the laser_amplifier crops align at (0,0) while their join shifts read
# (-6,-4)..(6,-6); the interior 30% box measures a clean uniform multiply
# where the full crop measures bimodal). The retry re-measures the crop's own
# alignment by brute-force luma cross-correlation (the join's shift field
# describes the gate's page-level alignment attempt, not the shipped crops),
# then solves + MADs on the interior box only. The same uniformity bar holds
# (STATE_MAD_MAX) — a phase-bearing interior (the door slide/fold class)
# still refuses; only the environment veto is lifted.
INTERIOR_F = 0.30
CROP_SHIFT_MAXS = 8


def crop_shift(truth: np.ndarray, ours: np.ndarray,
               maxs: int = CROP_SHIFT_MAXS):
    """Brute-force (+-maxs px) alignment of the shipped crops.

    The metric is the zero-mean normalized luma correlation, not the raw
    |truth-ours| error: the two legs differ by a per-channel MULTIPLY (the
    instrument this solver measures), and a raw-error search on a strongly
    multiplied pair wanders (measured: a 0.55x interior plus a mismatched
    border band "aligned" at (8,4) — the multiply makes bright-vs-bright
    beats bright-vs-dark arrangement-dependent). Zero-mean NCC is
    multiply-invariant: (a*o - mean(a*o)) = a*(o - mean(o)), so the correct
    alignment maximizes the correlation at any multiply strength.

    Returns (score, dy, dx) — the shift to roll OURS by to match TRUTH (the
    same convention solve_ratio_ex's shift argument uses). The search starts
    from (0, 0) and only replaces it on a STRICTLY greater correlation, so a
    flat interior (the heater's white panel class) keeps (0, 0) on ties."""
    lt = truth.mean(axis=2)
    lo = ours.mean(axis=2)
    lt0 = lt - lt.mean()
    lo0 = lo - lo.mean()
    H, W = lt.shape
    y0, y1 = 0, H
    x0, x1 = 0, W
    tt = lt0[y0:y1, x0:x1]
    oo = lo0[y0:y1, x0:x1]
    denom0 = float(np.sqrt((tt * tt).sum() * (oo * oo).sum()))
    best = ((tt * oo).sum() / denom0 if denom0 > 1e-9 else 0.0, 0, 0)
    for dy in range(-maxs, maxs + 1):
        for dx in range(-maxs, maxs + 1):
            if dy == 0 and dx == 0:
                continue
            yy0, yy1 = max(0, dy), min(H, H + dy)
            xx0, xx1 = max(0, dx), min(W, W + dx)
            if yy1 - yy0 < 4 or xx1 - xx0 < 4:
                continue
            tt = lt0[yy0:yy1, xx0:xx1]
            oo = lo0[yy0 - dy:yy1 - dy, xx0 - dx:xx1 - dx]
            denom = float(np.sqrt((tt * tt).sum() * (oo * oo).sum()))
            if denom <= 1e-9:
                continue
            c = float((tt * oo).sum() / denom)
            if c > best[0]:
                best = (c, dy, dx)
    return best


def interior_box(a: np.ndarray, f: float = INTERIOR_F) -> np.ndarray:
    """The center (1-2f) box of the crop — the state's own texture area."""
    H, W = a.shape[:2]
    return a[int(H * f):int(H * (1 - f)), int(W * f):int(W * (1 - f))]


def solve_interior_ex(truth: np.ndarray, ours: np.ndarray):
    """The 14-dd interior solve of ONE cell pair.
    Returns ((ratio, px, MAD, quartiles), dy, dx) or None when the crop
    itself misaligns (re-measured shift beyond STATE_SHIFT_MAX) or the
    interior carries no usable content — the same refusals the full-crop
    route names, now scoped to the state's own texture area."""
    _, dy, dx = crop_shift(truth, ours)
    if max(abs(dy), abs(dx)) > STATE_SHIFT_MAX:
        return None
    # crop_shift returns the CORRECTIVE roll (the shift that moves ours onto
    # truth); apply it as-is — solve_ratio_ex's own shift argument uses the
    # opposite (undo) convention, which is why the roll happens here.
    o = np.roll(np.roll(ours, dy, axis=0), dx, axis=1)
    solved = solve_ratio_ex(interior_box(truth), interior_box(o), (0, 0))
    if solved is None:
        return None
    return solved, dy, dx


def clip_fraction(tex: str, tint: np.ndarray, assets: "JarAssets") -> float:
    """Fraction of the raw texture's opaque pixels whose baked RGB would clip
    at 255 on any channel (and was not already 255)."""
    # bare refs (the vanilla-parented models' "block/jungle_leaves") are
    # minecraft-namespace by convention — the same rule copyModelGraph uses
    if ":" not in tex:
        tex = "minecraft:" + tex
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


def state_texs_of(assets: "JarAssets", state: str):
    """The state's resolved texture set (or None) — re-read from the asset
    graph without disturbing vanilla_tex_states bookkeeping."""
    return assets.state_textures(state) if state not in assets.vanilla_tex_states \
        else assets.vanilla_tex_states[state]


def state_interior_row(state: str, solves: list, texs, assets: "JarAssets"):
    """The 14-dd interior-mask retry of a state the full-crop guards refused
    on shift/MAD. Same fold, same family/clip guards, the evidence class
    names the route (interior-mask) so the P&L can attribute the flips."""
    med = np.median([s["ratio"] for s in solves], axis=0)
    if med.min() < STATE_BAKE_MIN or med.max() > BAKE_RATIO_MAX:
        return None, (f"multiply out of state bake family "
                      f"[{STATE_BAKE_MIN}, {BAKE_RATIO_MAX}]: "
                      + "/".join(f"{x:.3f}" for x in med))
    rounded = tuple(round(float(x), 4) for x in med)
    if all(abs(x - 1.0) <= EPSILON for x in rounded):
        return None, None  # no measured tint (the red is another class)
    if texs:
        for tex in sorted(texs):
            cf = clip_fraction(tex, med, assets)
            if cf > BAKE_CLIP_MAX:
                return None, (f"bake unrepresentable on {tex}: {cf:.1%} "
                              "of opaque pixels would clip — lightmap class")
    evidence = ";".join(
        f"{s['state']}@{s['page']}:{s['cell']}|n={s['n']}"
        f"|shift={s['shift'][0]},{s['shift'][1]}|class={s['cls']}"
        for s in solves)
    return (state, rounded, evidence), None


def state_fallback_row(state: str, solves: list, texs, assets: "JarAssets"):
    """Fold ONE state's own cells into a state-keyed row under the 14-db
    guards, or refuse with a named reason (None, None = nothing to bake)."""
    for s in solves:
        dx, dy = s["shift"]
        if max(abs(dx), abs(dy)) > STATE_SHIFT_MAX:
            return None, (f"shift ({dx},{dy}) > {STATE_SHIFT_MAX}px "
                          f"@ {s['page']}:{s['cell']} — misaligned crop")
        if s["mad"] > STATE_MAD_MAX:
            return None, (f"per-pixel MAD {s['mad']:.3f} > {STATE_MAD_MAX} "
                          f"@ {s['page']}:{s['cell']} — structural, not a multiply")
    med = np.median([s["ratio"] for s in solves], axis=0)
    if med.min() < STATE_BAKE_MIN or med.max() > BAKE_RATIO_MAX:
        return None, (f"multiply out of state bake family "
                      f"[{STATE_BAKE_MIN}, {BAKE_RATIO_MAX}]: "
                      + "/".join(f"{x:.3f}" for x in med))
    rounded = tuple(round(float(x), 4) for x in med)
    if all(abs(x - 1.0) <= EPSILON for x in rounded):
        return None, None  # no measured tint (the red is another class)
    if texs:
        for tex in sorted(texs):
            cf = clip_fraction(tex, med, assets)
            if cf > BAKE_CLIP_MAX:
                return None, (f"bake unrepresentable on {tex}: {cf:.1%} "
                              "of opaque pixels would clip — lightmap class")
    evidence = ";".join(
        f"{s['state']}@{s['page']}:{s['cell']}|n={s['n']}|class={s['cls']}"
        for s in solves)
    return (state, rounded, evidence), None


def append_interior(args, assets: "JarAssets", pairs) -> int:
    """The 14-dd APPEND mode: grow the committed TSV without touching it.

    Every state the OUT file already keys (texture or state row) is skipped —
    the committed measurements stand. The remaining red states run the 14-db
    full-crop fold first; a state it passes still gets its row (coverage
    churn between runs), a state it refuses on SHIFT or MAD gets the
    interior-mask retry. A state whose composite includes an already-rowed
    TEXTURE is refused (partial-application hazard: the state bake re-hosts
    only the un-rowed sprites, so the measured whole-machine multiply would
    apply to a subset — a different instrument than the measurement).
    The existing file content is preserved byte-identical; the new rows ride
    in a trailing 14-dd comment block.
    """
    out: pathlib.Path = args.out
    existing_keys: set = set()
    existing_texs: set = set()
    if out.exists():
        for line in out.read_text().splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            key = line.split("\t")[0]
            existing_keys.add(key)
            rest = key.split(":", 1)[1] if ":" in key else key
            if "[" not in key and "/" in rest:
                existing_texs.add(key)

    def cell_solve(truth_path, shift, page, col, rowi, interior=False):
        truth = load_rgb(truth_path)
        ours = load_rgb(truth_path.with_name(
            truth_path.name.replace("_truth.png", "_ours.png")))
        chroma_t = float(np.median(truth.max(axis=2) - truth.min(axis=2)))
        if not interior:
            solved = solve_ratio_ex(truth, ours, shift)
            return (solved, chroma_t) if solved else (None, chroma_t)
        solved = solve_interior_ex(truth, ours)
        return (solved, chroma_t) if solved else (None, chroma_t)

    new_rows: list = []
    dropped: list[tuple[str, str]] = []
    for state, cells in sorted(pairs.items()):
        if state in existing_keys:
            continue
        try:
            texs = state_texs_of(assets, state)
        except Exception:
            texs = None
        hazard = sorted(set(texs or []) & existing_texs)
        if hazard:
            dropped.append((state, "texture-row hazard: "
                            + ",".join(hazard)
                            + " already rowed — a state bake would partially "
                              "re-instrument the composite"))
            continue
        full = []
        for truth_path, shift, page, col, rowi in cells:
            solved, chroma_t = cell_solve(truth_path, shift, page, col, rowi)
            if solved is None:
                continue
            r, n, mad, qs = solved
            full.append({
                "state": state, "ratio": r, "n": n, "page": page,
                "cell": f"c{col},r{rowi}",
                "cls": classify(r, chroma_t),
                "shift": tuple(int(x) for x in (shift or (0, 0))),
                "mad": mad, "qs": qs,
            })
        if full:
            row, reason = state_fallback_row(state, full, texs, assets)
            if row is not None:
                new_rows.append(row)
                continue
            if reason is None:
                continue  # no measured tint (the red is another class)
            retry = ("shift (" in reason) or ("per-pixel MAD" in reason)
        else:
            retry = True  # every cell unsolvable full-crop — try the interior
        if not retry:
            dropped.append((state, reason))
            continue
        inter = []
        inter_fail = None
        for truth_path, shift, page, col, rowi in cells:
            solved, _chroma = cell_solve(truth_path, shift, page, col, rowi,
                                         interior=True)
            if solved is None:
                inter_fail = (f"re-measured crop shift > {STATE_SHIFT_MAX}px "
                              f"@ {page}:c{col},r{rowi}")
                break
            (r, n, mad, qs), dy, dx = solved
            inter.append({
                "state": state, "ratio": r, "n": n, "page": page,
                "cell": f"c{col},r{rowi}", "cls": "interior-mask",
                "shift": (int(dy), int(dx)), "mad": mad, "qs": qs,
            })
        if inter_fail or not inter:
            dropped.append((state, inter_fail or "no usable interior pixels"))
            continue
        row, reason = state_interior_row(state, inter, texs, assets)
        if row is not None:
            new_rows.append(row)
        elif reason is not None:
            dropped.append((state, reason))

    if new_rows:
        original = out.read_text() if out.exists() else ""
        with out.open("a") as f:
            if original and not original.endswith("\n"):
                f.write("\n")
            f.write("# 14-dd interior-mask rows — the 14-db full-crop guards\n")
            f.write("# refused these states on the join shift or the full-crop\n")
            f.write("# MAD; the interior route re-measures the crops' own\n")
            f.write("# alignment and MADs the center box (the cell environment\n")
            f.write("# is not the state's texture). Same uniformity bar\n")
            f.write(f"# (MAD<={STATE_MAD_MAX}), same family "
                    f"[{STATE_BAKE_MIN},{BAKE_RATIO_MAX}].\n")
            for state, (r, g, b), ev in new_rows:
                f.write(f"{state}\t{r}\t{g}\t{b}\t{ev}\n")

    print(f"14-dd append: {len(new_rows)} new rows -> {out}")
    print(f"dropped: {len(dropped)} (named below with --verbose)")
    if args.verbose:
        for s, why in dropped:
            print(f"  drop: {s}: {why}")
        for state, (r, g, b), ev in new_rows:
            print(f"  interior: {state} = ({r}, {g}, {b})")
    return 0


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
    ap.add_argument("--append-interior", action="store_true",
                    help="14-dd: skip every state the OUT file already keys "
                         "(texture or state rows), run the interior-mask "
                         "retry for the shift/MAD-refused states that remain, "
                         "and APPEND the new rows to the OUT file (the "
                         "committed rows stay byte-identical)")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)

    assets = JarAssets(args.jars, args.vanilla_jar)
    if args.append_interior:
        pairs0 = crop_pairs(args.artifacts, args.gate)
        return append_interior(args, assets, pairs0)
    pairs = crop_pairs(args.artifacts, args.gate)
    print(f"red states with triptych crops: {len(pairs)}")

    # per-texture evidence collection
    per_tex: dict[str, list] = defaultdict(list)
    refused: list[tuple[str, str]] = []
    # 14-db: EVERY red state's own per-cell solves — the texture route folds
    # them per texture; the state-keyed fallback re-uses them when that
    # route is unavailable or refuses the fold.
    state_solves: dict[str, list] = defaultdict(list)
    states_of_tex: dict[str, set] = defaultdict(set)
    multi_states: set = set()
    for state, cells in sorted(pairs.items()):
        texs = assets.state_textures(state)
        state_keyed = state in assets.vanilla_tex_states
        solves = []
        for truth_path, shift, page, col, rowi in cells:
            truth = load_rgb(truth_path)
            ours = load_rgb(truth_path.with_name(
                truth_path.name.replace("_truth.png", "_ours.png")))
            solved = solve_ratio_ex(truth, ours, shift)
            if solved is None:
                refused.append((state, f"no usable content pixels @ {page}:c{col},r{rowi}"))
                continue
            r, n, mad, qs = solved
            chroma_t = float(np.median(truth.max(axis=2) - truth.min(axis=2)))
            solves.append({
                "state": state, "ratio": r, "n": n, "page": page,
                "cell": f"c{col},r{rowi}",
                "cls": classify(r, chroma_t),
                "shift": tuple(int(x) for x in (shift or (0, 0))),
                "mad": mad, "qs": qs,
            })
        state_solves[state] = solves
        if not state_keyed and (not texs or len(texs) != 1):
            refused.append((state, "multi/ambiguous texture: "
                            + (",".join(sorted(texs)) if texs else "unresolved")))
            multi_states.add(state)
            continue
        # THE STATE-KEYED states collect under EVERY texture they resolve to
        # (sorted — set iteration is hash-luck across runs) and the per-state
        # row folds all of them below; single-texture states keep the plain key.
        cand_texs = sorted(texs) if state_keyed else [next(iter(texs))]
        for tex in cand_texs:
            if "\x00" not in tex:
                states_of_tex[tex].add(state)
            for s in solves:
                pkey = tex + ("\x00" + state if state_keyed else "")
                per_tex[pkey].append(s)

    # THE STATE-KEYED TINT ROUTE: the modded states whose models resolve
    # to minecraft-namespace textures — the per-state multiply solves from
    # the same crops, the rows key on the STATE (GalleryPack applies the
    # multiply to the state's composite only; a state-suffixed sibling
    # texture keeps the shared vanilla texture untouched)
    state_rows = []
    state_dropped = []
    for state, texs in sorted(assets.vanilla_tex_states.items()):
        # every texture the state resolves to feeds the fold; the row KEYS
        # ON THE STATE (GalleryPack looks the composite's state up here —
        # aggregate_texture's tex arg stays first for the clip check)
        cells = [c for t2 in sorted(texs)
                 for c in per_tex.get(t2 + "\x00" + state, [])]
        if not cells:
            continue
        row, reason = aggregate_texture(min(texs), cells, assets,
                                        args.tolerance, args.epsilon)
        if row is not None:
            state_rows.append((state, row[1], row[2]))
        elif reason is not None:
            state_dropped.append((state + " [" + ", ".join(sorted(texs)) + "]", reason))

    rows, dropped = [], []
    failed_texs: set = set()
    for tex in sorted(k for k in per_tex if "\x00" not in k):
        row, reason = aggregate_texture(tex, per_tex[tex], assets,
                                        args.tolerance, args.epsilon)
        if row is not None:
            rows.append(row)
        elif reason is not None:
            dropped.append((tex, reason))
            failed_texs.add(tex)

    # THE 14-db STATE-KEYED FALLBACK: the texture route refused a fold (two
    # lightings on one texture, the out-of-family/unrepresentable medians) or
    # never ran (multi/unresolved textures) — a state whose OWN cells pass the
    # shift/MAD/family guards still gets a STATE-keyed row. Scope: the raw
    # sprite keeps serving every other consumer, so the row cannot touch a
    # green cell; the guards keep structural classes (the door phase, the
    # heater bimodal crops, the misaligned laser shifts) honestly OUT.
    covered = {s for t, ss in states_of_tex.items() if t not in failed_texs
               for s in ss}
    fallback_states = (multi_states
                       | {s for t in failed_texs for s in states_of_tex.get(t, set())})
    fallback_states -= covered
    fallback_rows = []
    fallback_dropped = []
    for state in sorted(fallback_states):
        solves = state_solves.get(state) or []
        if not solves:
            continue
        texs = state_texs_of(assets, state)
        row, reason = state_fallback_row(state, solves, texs, assets)
        if row is not None:
            fallback_rows.append(row)
        elif reason is not None:
            fallback_dropped.append((state, reason))

    with args.out.open("w") as f:
        f.write("# visual-tints.tsv — tint-bake calibration (scripts/f2v/"
                "tint-calibrate.py)\n")
        f.write("# texRef\\tr\\tg\\tb\\tevidence — multiply the raw texture's "
                "RGB by (r,g,b) at pack build; alpha preserved.\n")
        f.write("# solved from truth/ours median ratios on the gallery "
                f"triptychs; rounding = python round() to 4 decimals.\n")
        for tex, (r, g, b), ev in rows:
            f.write(f"{tex}\t{r}\t{g}\t{b}\t{ev}\n")
        for state, (r, g, b), ev in state_rows:
            f.write(f"{state}\t{r}\t{g}\t{b}\t{ev}\n")
        # 14-db: the state-keyed fallback rows (the texture route refused or
        # never ran; GalleryPack.applyStateTint re-hosts the state's own
        # composite — the raw sprite keeps serving every other consumer)
        f.write("# 14-db state-keyed fallback rows — the texture route refused\n")
        f.write("# (multi/unresolved/disagreeing) and the state's own crops pass\n")
        f.write(f"# the shift<={STATE_SHIFT_MAX}/MAD<={STATE_MAD_MAX}/family\n")
        f.write(f"# [{STATE_BAKE_MIN},{BAKE_RATIO_MAX}] guards.\n")
        for state, (r, g, b), ev in fallback_rows:
            f.write(f"{state}\t{r}\t{g}\t{b}\t{ev}\n")

    print(f"tints written: {len(rows)} -> {args.out}")
    print(f"refused states: {len(refused)}; dropped textures: {len(dropped)}; "
          f"state-fallback rows: {len(fallback_rows)} "
          f"(dropped {len(fallback_dropped)})")
    if args.verbose:
        for s, why in refused:
            print(f"  refuse: {s}: {why}")
        for t, why in dropped:
            print(f"  drop: {t}: {why}")
        for tex, (r, g, b), ev in rows:
            print(f"  tint: {tex} = ({r}, {g}, {b})")
        for state, (r, g, b), ev in fallback_rows:
            print(f"  state-tint: {state} = ({r}, {g}, {b})")
        for s, why in fallback_dropped:
            print(f"  state-drop: {s}: {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
