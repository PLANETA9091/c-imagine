#!/usr/bin/env python3
"""Selftest for gallery-particles-mapping.py — the 14-dz resolved-target lever.

The measured facts the test pins (the reconciled verified-simple contract,
the parallel 108009d4 shape kept by the rebase):
  1. the 38 refusing families (14-ds: create/ae2/biomesoplenty/mekanism,
     the p0 manifests of the three measured runs) resolve to a
     REGISTRY-REAL target — exact-name (verified-simple paths only) or a
     keyword bucket — NOT to the replay-time default that manufactured the
     0/38 mapped ceiling;
  2. every target the generator can emit (exact-name candidate, keyword
     bucket, default) is in VANILLA_SIMPLE — registered in the 26.3 client
     jar AND simple in paper-api's Particle enum (a data-carrying or
     unregistered target would degrade at replay against an expected TSV
     target: the manufactured-miss trap);
  3. the generator is a pure function of the manifests (deterministic);
  4. the default rows carry the documented default itself (the replay's
     send then equals the TSV target even there).
"""
import importlib.util
import os
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
GEN = HERE / "gallery-particles-mapping.py"
PY = os.environ.get("PYGATE_PYTHON") or sys.executable

_spec = importlib.util.spec_from_file_location("gpm", GEN)
gpm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gpm)

# the 14-ds refusing families (the p0 manifests of the three measured runs,
# ours legs died at the pixel gate before later particle pages)
REFUSING_38 = [
    # create (13)
    "create:air", "create:air_flow", "create:basin_fluid", "create:cube",
    "create:fluid_drip", "create:fluid_particle",
    "create:rotation_indicator", "create:soul", "create:soul_base",
    "create:soul_expanding_perimeter", "create:soul_perimeter",
    "create:steam_jet", "create:wifi",
    # biomesoplenty (15)
    "biomesoplenty:binary", "biomesoplenty:dripping_blood",
    "biomesoplenty:end_spore", "biomesoplenty:falling_blood",
    "biomesoplenty:glowworm", "biomesoplenty:jacaranda_leaves",
    "biomesoplenty:landing_blood", "biomesoplenty:null",
    "biomesoplenty:orange_maple_leaves", "biomesoplenty:pus",
    "biomesoplenty:red_maple_leaves", "biomesoplenty:snowblossom_leaves",
    "biomesoplenty:steam", "biomesoplenty:wisp_bubble",
    "biomesoplenty:yellow_maple_leaves",
    # ae2 (5)
    "ae2:crafting_fx", "ae2:energy_fx", "ae2:lightning_fx",
    "ae2:matter_cannon_fx", "ae2:vibrant_fx",
    # mekanism (5)
    "mekanism:jetpack_flame", "mekanism:jetpack_smoke", "mekanism:laser",
    "mekanism:radiation", "mekanism:scuba_bubble",
]


def gen(scenes: pathlib.Path, out: pathlib.Path):
    return subprocess.run([PY, str(GEN), str(scenes), str(out)],
                          capture_output=True, text=True)


def parse(tsv: pathlib.Path) -> dict:
    rows = {}
    for line in tsv.read_text(encoding="utf-8").splitlines():
        if line.startswith("#") or not line.strip():
            continue
        pid, target, heuristic = line.split("\t")
        rows[pid] = (target, heuristic)
    return rows


def main() -> int:
    failures = 0

    # 1: the 38 families resolve to a registry-real target
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        scenes = root / "scenes"
        scenes.mkdir()
        lines = ["cell\tx\ty\tz\tparticle\tdyn\tnote\n"]
        for i, pid in enumerate(REFUSING_38):
            lines.append(f"cell\t{i % 11}\t{i // 11 - 60}\t9\t{pid}\t1\tok\n")
        (scenes / "gallery-particle-create-p0.tsv").write_text(
            "".join(lines), encoding="utf-8")
        r = gen(scenes, root / "visual-particles.tsv")
        ok = r.returncode == 0 and "38 particle ids" in r.stdout
        rows = parse(root / "visual-particles.tsv")
        resolved = [p for p, (t, h) in rows.items()
                    if h.startswith(("exact-name", "keyword"))]
        # the measured shape of the reconciled verified-simple table: the
        # overwhelming majority of the 38 lands on a registry-real target
        # (create:air and create:cube ride the documented default)
        ok = ok and len(resolved) >= 34
        print(f"1 resolved-target shape on the 38 refusing families "
              f"({len(resolved)}/38 registry-real, >=34 required):",
              "PASS" if ok else f"FAIL\n{r.stdout}{r.stderr}")
        failures += 0 if ok else 1

        # 2: every emitted target is in VANILLA_SIMPLE (registered AND
        # simple — the paper-api enum law)
        bad = [(p, t) for p, (t, h) in rows.items()
               if t[len("minecraft:"):] not in gpm.VANILLA_SIMPLE]
        print("2 every target verified-simple (26.3 registered + "
              "paper-api simple):",
              "PASS" if not bad else f"FAIL {bad}")
        failures += 0 if not bad else 1

        # 3: determinism — the same manifests produce byte-identical output
        out2 = root / "visual-particles-2.tsv"
        gen(scenes, out2)
        same = (root / "visual-particles.tsv").read_bytes() == out2.read_bytes()
        print("3 deterministic:", "PASS" if same else "FAIL")
        failures += 0 if same else 1

        # 3b: generation hard-fails on a table target outside VANILLA_SIMPLE
        # (the manufactured-miss guard) — a tampered table cannot ship
        broken = root / "broken.tsv"
        broken.write_text("# x\n", encoding="utf-8")
        (scenes / "gallery-particle-create-p1.tsv").write_text(
            "cell\tx\ty\tz\tparticle\tdyn\tnote\n"
            "cell\t0\t-60\t9\tmod:zzz_unverified_thing\t1\tok\n",
            encoding="utf-8")
        saved = list(gpm.CATEGORY_TABLE)
        saved_argv = sys.argv
        try:
            gpm.CATEGORY_TABLE.append(("zzz_unverified", "minecraft:not_a_particle"))
            sys.argv = [str(GEN), str(scenes), str(broken)]
            guard = False
            try:
                gpm.main()
            except SystemExit as ex:
                guard = ex.code != 0
        finally:
            gpm.CATEGORY_TABLE[:] = saved
            sys.argv = saved_argv
        print("3b generation hard-fails on an unverified target:",
              "PASS" if guard else "FAIL")
        failures += 0 if guard else 1

    # 4: default rows carry the documented default itself
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        scenes = root / "scenes"
        scenes.mkdir()
        (scenes / "gallery-particle-create-p0.tsv").write_text(
            "cell\tx\ty\tz\tparticle\tdyn\tnote\n"
            "cell\t-5\t-60\t9\tcreate:cube\t1\tok\n", encoding="utf-8")
        r = gen(scenes, root / "visual-particles.tsv")
        rows = parse(root / "visual-particles.tsv")
        target, heuristic = rows["create:cube"]
        ok = r.returncode == 0 and target == gpm.DEFAULT \
            and (heuristic == "default" or heuristic.startswith("keyword:"))
        print("4 default/unmatched rows carry the documented default:",
              "PASS" if ok else f"FAIL {rows}")
        failures += 0 if ok else 1

    print("SMOKE:", "ALL PASS" if failures == 0 else f"{failures} FAILURES")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
