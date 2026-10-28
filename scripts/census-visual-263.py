#!/usr/bin/env python3
"""F2V 2.0 visual capability census: vanilla client 26.3 vs 1.21.1.

Rebuilds the registry-side ground truth behind
docs/runtime-analysis/f2v2-client-capabilities.md from the two client jars.
Read-only (zipfile scans), no network, safe to run anywhere:

    python3 scripts/census-visual-263.py <client-26.3.jar> <client-1.21.1.jar> <out-dir>

Outputs: <out-dir>/visual-inventory.json (both jars) + a human summary.
"""
from __future__ import annotations

import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import PurePosixPath

ASSETS = "assets/minecraft/"

# class-constant-pool triggers: which post_effect / legacy post chain ids the
# CLIENT CODE actually references (trigger ground truth, not guesses)
PATH_TRIGGER = re.compile(rb"post_effect/[a-z0-9_./-]{1,64}")
OLD_TRIGGER = re.compile(rb"shaders/post/[a-z0-9_./-]{1,64}")


def top_dirs(names: list[str]) -> Counter:
    c: Counter = Counter()
    for n in names:
        if n.startswith(ASSETS):
            parts = PurePosixPath(n[len(ASSETS):]).parts
            if parts:
                c[parts[0]] += 1
    return c


def walk_types(zf: zipfile.ZipFile, prefix: str) -> Counter:
    """All `type` strings inside json files under prefix (maker/property
    histograms across items/, post_effect/, etc.)."""
    types: Counter = Counter()
    for n in zf.namelist():
        if not n.startswith(prefix) or not n.endswith(".json"):
            continue
        try:
            data = json.loads(zf.read(n))
        except Exception:
            continue
        for t in _walk(data):
            types[t] += 1
    return types


def _walk(node, acc: list | None = None):
    if acc is None:
        acc = []
    if isinstance(node, dict):
        t = node.get("type")
        if isinstance(t, str):
            acc.append(t)
        for v in node.values():
            _walk(v, acc)
    elif isinstance(node, list):
        for v in node:
            _walk(v, acc)
    return acc


def class_triggers(zf: zipfile.ZipFile, rx: re.Pattern) -> set[str]:
    found: set[str] = set()
    for n in zf.namelist():
        if not n.endswith(".class"):
            continue
        blob = zf.read(n)
        for m in rx.finditer(blob):
            found.add(m.group(0).decode("ascii", "replace"))
    return found


def census(jar: str) -> dict:
    out: dict = {}
    with zipfile.ZipFile(jar) as z:
        names = z.namelist()
        out["jar"] = jar
        out["asset_files_total"] = sum(1 for n in names if n.startswith(ASSETS))
        out["assets_top_dirs"] = dict(top_dirs(names).most_common())

        # registry-side json surfaces
        def count_files(prefix: str) -> int:
            return sum(1 for n in names if n.startswith(prefix) and n.endswith(".json"))

        for label, prefix in [
            ("post_effect_chains", ASSETS + "post_effect/"),
            ("items_defs", ASSETS + "items/"),
            ("models", ASSETS + "models/"),
            ("blockstates", ASSETS + "blockstates/"),
            ("equipment", ASSETS + "equipment/"),
            ("atlases", ASSETS + "atlases/"),
            ("fonts", ASSETS + "font/"),
            ("shaders_all", ASSETS + "shaders/"),
            ("legacy_post_chains", ASSETS + "shaders/post/"),
            ("legacy_programs", ASSETS + "shaders/program/"),
            ("waypoint_style", ASSETS + "waypoint_style/"),
            ("palettes", ASSETS + "textures/palette"),
        ]:
            out[label] = count_files(prefix)

        # item definition maker histogram (the ItemsAdder/Oraxen vocabulary)
        if out["items_defs"]:
            out["item_maker_histogram"] = dict(
                walk_types(z, ASSETS + "items/").most_common(24))

        # shader tree split
        split: Counter = Counter()
        for n in names:
            if n.startswith(ASSETS + "shaders/"):
                rel = n[len(ASSETS) + len("shaders/"):]
                parts = PurePosixPath(rel).parts
                if len(parts) > 1:
                    split[parts[0]] += 1
        out["shaders_tree"] = dict(split.most_common())

        # client-code triggers
        out["post_effect_ids_referenced_by_code"] = sorted(
            class_triggers(z, PATH_TRIGGER))
        out["legacy_post_ids_referenced_by_code"] = sorted(
            class_triggers(z, OLD_TRIGGER))

        # atlas sprite sources
        atlas_sources: Counter = Counter()
        for n in names:
            if n.startswith(ASSETS + "atlases/") and n.endswith(".json"):
                try:
                    data = json.loads(z.read(n))
                except Exception:
                    continue
                for src in data.get("sources", []):
                    atlas_sources[src.get("type", "?")] += 1
        out["atlas_source_types"] = dict(atlas_sources.most_common())

        # font provider histogram
        font_providers: Counter = Counter()
        for n in names:
            if n.startswith(ASSETS + "font/") and n.endswith(".json"):
                try:
                    data = json.loads(z.read(n))
                except Exception:
                    continue
                for p in data.get("providers", []):
                    font_providers[p.get("type", "?")] += 1
        out["font_providers"] = dict(font_providers.most_common(16))
    return out


def main() -> None:
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    jar263, jar1211, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
    a, b = census(jar263), census(jar1211)
    report = {"client_26_3": a, "client_1_21_1": b}
    with open(outdir.rstrip("/") + "/visual-inventory.json", "w") as f:
        json.dump(report, f, indent=1)

    dirs_a = set(a["assets_top_dirs"])
    dirs_b = set(b["assets_top_dirs"])
    print(f"26.3 assets: {a['asset_files_total']} files (1.21.1: {b['asset_files_total']})")
    print(f"new top-level roots in 26.3: {sorted(dirs_a - dirs_b)}")
    print(f"removed in 26.3: {sorted(dirs_b - dirs_a)}")
    print("26.3 headline counts:")
    for k in ("post_effect_chains", "items_defs", "models", "equipment",
              "atlases", "fonts", "shaders_tree", "palettes"):
        print(f"  {k}: {a.get(k)}")
    print("26.3 item makers:", list(a.get("item_maker_histogram", {}).items())[:12])
    print("26.3 post_effect ids referenced by CODE:",
          a["post_effect_ids_referenced_by_code"])
    print("1.21.1 legacy post ids referenced by CODE:",
          b["legacy_post_ids_referenced_by_code"])
    print("26.3 atlas sources:", a.get("atlas_source_types"))


if __name__ == "__main__":
    main()
