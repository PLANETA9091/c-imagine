#!/usr/bin/env python3
"""Jar-side census of the LOADER-UNBAKED class (14-bi / 14-br).

Answers, WITHOUT a server walk, which ladder blockstates CANNOT get a pack
composite because their model chain rides a loader the resolver does not
bake (dynamictrees:branch|roots|surface_root, mekanism:transmitter|
energy_cube|data_based, sophisticatedbackpacks:backpack, neoforge loaders
beyond composite/obj) or resolves to an EMPTY model ({}/particle-only —
the geometry is the mod's own code: AE2 formed multiblocks, fluids, sign
backboards). These are the honest dyn-flip rows; the census is the
evidence table for the 2202/30560 (7.2%) measured on run 42255431005.

Usage: loader-unbaked-census.py <mods-dir> [vanilla-client.jar]
"""
import collections
import json
import pathlib
import re
import sys
import zipfile


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    mods = pathlib.Path(sys.argv[1])
    vanilla = sys.argv[2] if len(sys.argv) > 2 else None
    zips = [zipfile.ZipFile(p) for p in sorted(mods.glob("*.jar"))]
    if vanilla:
        zips.append(zipfile.ZipFile(vanilla))

    def read(path):
        for zf in zips:
            try:
                if path in zf.namelist():
                    return zf.read(path)
            except Exception:
                pass
        return None

    def model_raw(ref, ns):
        """Bare refs ride the ref's namespace first, minecraft: second —
        the 14-ay resolver rule (mirror it or the census lies)."""
        mns = ns
        if ":" in ref:
            mns, ref = ref.split(":", 1)
        raw = read(f"assets/{mns}/models/{ref}.json")
        if raw is None and ":" not in ref and mns != "minecraft":
            raw = read(f"assets/minecraft/models/{ref}.json")
            if raw is not None:
                mns = "minecraft"
        return raw, mns

    def chain(ref, ns, depth=0):
        """(class, detail): None = bakeable (elements), else the terminal reason."""
        if depth > 16:
            return "depth", ref
        raw, mns = model_raw(ref, ns)
        if raw is None:
            return "missing-model", ref
        j = json.loads(raw)
        if "loader" in j:
            loader = j["loader"]
            if loader in ("neoforge:composite", "neoforge:obj"):
                return None, loader  # resolver handles both
            return "custom-loader", loader
        if "elements" in j or "children" in j:
            return None, "elements"
        if "parent" in j:
            return chain(j["parent"], mns, depth + 1)
        return "empty-model", ref  # {} / particle-only: geometry lives in mod code

    report = collections.defaultdict(lambda: collections.defaultdict(list))
    for zf in zips:
        try:
            names = zf.namelist()
        except Exception:
            continue
        for n in names:
            m = re.match(r"assets/([^/]+)/blockstates/(.+)\.json$", n)
            if not m or m.group(1) == "minecraft":
                continue
            ns, blk = m.groups()
            try:
                bs = json.loads(zf.read(n))
            except Exception:
                continue
            refs = set()
            for v in bs.get("variants", {}).values():
                for e in (v if isinstance(v, list) else [v]):
                    if isinstance(e, dict) and "model" in e:
                        refs.add(e["model"])
            for pe in bs.get("multipart", []):
                ap = pe.get("apply", {})
                for e in (ap if isinstance(ap, list) else [ap]):
                    if isinstance(e, dict) and "model" in e:
                        refs.add(e["model"])
            for r in sorted(refs):
                cls, det = chain(r, ns)
                if cls is not None:
                    report[ns][(cls, det)].append(blk)

    for ns in sorted(report):
        print(f"== {ns}")
        for (cls, det), blks in sorted(report[ns].items()):
            print(f"   {cls} [{det}]: {len(blks)} blockstate(s), e.g. {sorted(set(blks))[:6]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
