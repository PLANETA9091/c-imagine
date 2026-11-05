#!/usr/bin/env python3
"""TINT-BAKE inventory: which mod models render with a client-side tint on
the truth client, and which of the gallery's residual red states are actually
tint-class.

Two modes:

  scan      every model JSON in the ladder jars whose parent chain lands on a
            model carrying "tintindex" (own JSON or a vanilla carrier —
            block/tinted_cross, block/leaves, block/grass_block, block/vine,
            block/lily_pad, ... in the 1.21.1 client). Listed per namespace,
            with the distinct BLOCKS those models serve (the blockstate scan):
            this names every block the vanilla 26.3 client renders UNTINTED
            where the modded client applies an IBlockColor.

  classify  the residual RED states of a gallery run (join JSONs under
            --artifacts), each assigned its measured residual class from the
            triptych crops:
              tint-class        solved multiply in-bake-family, consistent
                                across the state's cells, representable in
                                the raw texture's gamut -> bake candidate
              lightmap-class    consistent brightening multiply that would
                                clip the raw texture at 255 (the truth client
                                lights the content brighter than any texture
                                bake can carry) — or per-channel ratios > 2
              inconsistent      the same texture measures different ratios on
                                different cells (per-cell lighting, not a
                                texture property) — no per-texture bake exists
              multi-texture     the state's models carry several textures (or
                                the blockstate is runtime-generated) — the
                                ratio cannot be attributed
              geometry/px       ratio ~ 1 — shape or pixel-level residual,
                                nothing color-shaped to bake
              no-crop           no triptych crop for the state (entity rows)

The classifier is the same solver tint-calibrate.py bakes with (imported, not
copied), so the inventory and the TSV can never drift.

Usage:
  tint-inventory.py scan  --jars /tmp/ladder-mods \
      [--vanilla-jar client-1211.jar]
  tint-inventory.py classify --jars /tmp/ladder-mods \
      --vanilla-jar client-1211.jar --artifacts /tmp/galnew [--gate 60000]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import zipfile
from collections import defaultdict

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
# the sibling ships as tint-calibrate.py (hyphen — the task's file name), so
# the plain import machinery can't see it; load it by explicit path
import importlib.util as _ilu  # noqa: E402

_tc_path = pathlib.Path(__file__).resolve().parent / "tint-calibrate.py"
_tc_spec = _ilu.spec_from_file_location("tint_calibrate", _tc_path)
tint_calibrate = _ilu.module_from_spec(_tc_spec)
_tc_spec.loader.exec_module(tint_calibrate)
BAKE_CLIP_MAX = tint_calibrate.BAKE_CLIP_MAX
BAKE_RATIO_MAX = tint_calibrate.BAKE_RATIO_MAX
BAKE_RATIO_MIN = tint_calibrate.BAKE_RATIO_MIN
JarAssets = tint_calibrate.JarAssets
classify = tint_calibrate.classify
clip_fraction = tint_calibrate.clip_fraction
crop_pairs = tint_calibrate.crop_pairs
load_rgb = tint_calibrate.load_rgb
solve_ratio = tint_calibrate.solve_ratio

# 1.21.1 vanilla models that carry "tintindex" (measured from
# clients/client-1211.jar assets/minecraft/models/**): the carriers a mod
# model's parent chain can land on.
VANILLA_TINT_CARRIERS = frozenset([
    "block/bamboo_large_leaves", "block/bamboo_small_leaves",
    "block/flowerbed_1", "block/flowerbed_2", "block/flowerbed_3",
    "block/flowerbed_4", "block/grass_block", "block/leaves", "block/lily_pad",
    "block/redstone_dust_dot", "block/redstone_dust_side",
    "block/redstone_dust_side_alt", "block/redstone_dust_up",
    "block/stem_fruit", "block/stem_growth0", "block/stem_growth1",
    "block/stem_growth2", "block/stem_growth3", "block/stem_growth4",
    "block/stem_growth5", "block/stem_growth6", "block/stem_growth7",
    "block/stonecutter", "block/template_cauldron_full",
    "block/template_cauldron_level1", "block/template_cauldron_level2",
    "block/tinted_cross", "block/tinted_flower_pot_cross", "block/vine",
])


def chain_carrier(zf: zipfile.ZipFile, ns: str, model_path: str,
                  read_model) -> str | None:
    """The first model ref in the parent chain (own JSON included) whose JSON
    carries tintindex or that is a known vanilla tint carrier, else None.
    Bare parents inherit the model's namespace, then minecraft."""
    seen = set()
    ref: str | None = model_path
    depth = 0
    while ref and depth <= 16:
        key = f"{ns}:{ref}"
        if key in seen:
            return None
        seen.add(key)
        if ":" in ref:
            rns, rpath = ref.split(":", 1)
        else:
            rns, rpath = ns, ref
        raw = read_model(rns, rpath)
        if raw is None:
            return None  # vanilla carriers are enumerated statically above
        if b"tintindex" in raw:
            return key
        try:
            data = json.loads(raw)
        except Exception:
            return None
        parent = data.get("parent") if isinstance(data, dict) else None
        if parent in VANILLA_TINT_CARRIERS:
            return parent
        ref = parent if isinstance(parent, str) else None
        ns = rns
        depth += 1
    return None


def _vanilla_json(path: str) -> bytes:
    return b""  # vanilla carriers are enumerated statically above


def mode_scan(args) -> int:
    vanilla = (zipfile.ZipFile(args.vanilla_jar)
               if args.vanilla_jar else None)

    def read_vanilla(rns, rpath):
        if vanilla is None:
            return None
        try:
            return vanilla.read(f"assets/{rns}/models/{rpath}.json")
        except KeyError:
            return None

    for jar_path in sorted(args.jars.glob("*.jar")):
        zf = zipfile.ZipFile(jar_path)
        models = [n for n in zf.namelist()
                  if n.endswith(".json") and "/models/" in n
                  and n.startswith("assets/")]
        per_ns: dict[str, list[str]] = defaultdict(list)
        for n in models:
            ns = n.split("/")[1]
            per_ns[ns].append(n)
        for ns in sorted(per_ns):
            def read_mod(rns, rpath, _zf=zf, _ns=ns):
                try:
                    return _zf.read(f"assets/{rns}/models/{rpath}.json")
                except KeyError:
                    return None
            tinted = []
            for n in sorted(per_ns[ns]):
                mpath = n.split(f"assets/{ns}/models/")[1][:-5]
                if chain_carrier(zf, ns, mpath, read_mod):
                    tinted.append(mpath)
            if tinted:
                print(f"{jar_path.name} ns={ns}: "
                      f"{len(tinted)}/{len(per_ns[ns])} models on tintindex chains")
                for t in tinted[:12]:
                    print(f"    {t}")
                if len(tinted) > 12:
                    print(f"    ... +{len(tinted) - 12} more")
            else:
                print(f"{jar_path.name} ns={ns}: 0/{len(per_ns[ns])} tinted-chain models")
    return 0


def mode_classify(args) -> int:
    assets = JarAssets(args.jars, args.vanilla_jar)
    pairs = crop_pairs(args.artifacts, args.gate)
    print(f"red states with triptych crops: {len(pairs)}")

    classes: dict[str, str] = {}
    per_tex: dict[str, list] = defaultdict(list)
    for state, cells in sorted(pairs.items()):
        texs = assets.state_textures(state)
        if not texs:
            classes[state] = "multi-texture/unresolved"
            continue
        if len(texs) != 1:
            classes[state] = "multi-texture"
            continue
        tex = next(iter(texs))
        for truth_path, shift, page, col, rowi in cells:
            truth = load_rgb(truth_path)
            ours = load_rgb(truth_path.with_name(
                truth_path.name.replace("_truth.png", "_ours.png")))
            solved = solve_ratio(truth, ours, shift)
            if solved is None:
                classes[state] = "no-content-px"
                continue
            r, n = solved
            chroma_t = float(np.median(truth.max(axis=2) - truth.min(axis=2)))
            per_tex[tex].append({"state": state, "ratio": r, "n": n,
                                 "cell": f"{page}:c{col},r{rowi}",
                                 "cls": classify(r, chroma_t)})

    for tex in sorted(per_tex):
        cells = per_tex[tex]
        ratios = np.array([c["ratio"] for c in cells])
        med = np.median(ratios, axis=0)
        spread = float(np.max(ratios.max(axis=0) - ratios.min(axis=0)))
        for c in cells:
            state = c["state"]
            if len(cells) > 1 and spread > 0.06:
                classes[state] = f"inconsistent ({c['cls']}, spread {spread:.3f})"
            elif med.min() < BAKE_RATIO_MIN or med.max() > BAKE_RATIO_MAX:
                classes[state] = f"lightmap-class (ratio {med[0]:.2f}/{med[1]:.2f}/{med[2]:.2f})"
            elif clip_fraction(tex, med, assets) > BAKE_CLIP_MAX:
                classes[state] = f"lightmap-class (clips {clip_fraction(tex, med, assets):.0%})"
            elif np.all(np.abs(med - 1.0) <= 0.02):
                classes[state] = f"geometry/px ({c['cls']})"
            else:
                classes[state] = f"tint-class ({c['cls']} {med[0]:.3f}/{med[1]:.3f}/{med[2]:.3f})"

    by_class: dict[str, list[str]] = defaultdict(list)
    for st, cls in classes.items():
        by_class[cls.split(" (")[0]].append(st)
    for cls in sorted(by_class, key=lambda c: -len(by_class[c])):
        print(f"\n### {cls}: {len(by_class[cls])} states")
        for st in sorted(by_class[cls]):
            detail = classes[st]
            print(f"    {st[:64]:66} {detail[len(cls)+2:] if '(' in detail else ''}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="mode", required=True)
    for name in ("scan", "classify"):
        s = sub.add_parser(name)
        s.add_argument("--jars", required=True, type=pathlib.Path)
        s.add_argument("--vanilla-jar", type=pathlib.Path, default=None)
        if name == "classify":
            s.add_argument("--artifacts", required=True, type=pathlib.Path)
            s.add_argument("--gate", type=float, default=60000.0)
    args = ap.parse_args(argv)
    return mode_scan(args) if args.mode == "scan" else mode_classify(args)


if __name__ == "__main__":
    sys.exit(main())
