#!/usr/bin/env python3
"""Selftest for gallery-particles-mapping.py — the 14-ds resolved-target lever.

The measured facts the test pins:
  1. the 38 refusing families (14-ds: create/ae2/biomesoplenty/mekanism,
     the p0 manifests of the three measured runs) resolve through the
     category buckets — NOT through the exact-name candidate that the
     replay would have degraded to poof at send time;
  2. every category TARGET the generator can emit is present in the real
     26.3 particle registry (the verified-set invariant, the same measured
     guarantee the sound generator's table carries);
  3. the generator is a pure function of the manifests (deterministic);
  4. a TSV-vs-send disagreement can only come from the DEFAULT rows (whose
     target is the documented default itself) or a live-registry drift —
     both of which the replay ledgers.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
GEN = HERE / "gallery-particles-mapping.py"
PY = os.environ.get("PYGATE_PYTHON") or sys.executable

# the 26.3 particle registry names verified by javap over the client jar's
# ParticleTypes (the invariant set the category table may draw from)
VERIFIED_26_3 = {
    "angry_villager", "ash", "bubble", "bubble_column_up", "bubble_pop",
    "campfire_cosy_smoke", "campfire_signal_smoke", "cherry_leaves",
    "cloud", "composter", "copper_fire_flame", "crimson_spore", "crit",
    "current_down", "damage_indicator", "dolphin",
    "dripping_dripstone_lava", "dripping_dripstone_water", "dripping_honey",
    "dripping_lava", "dripping_obsidian_tear", "dripping_water",
    "dust_plume", "egg_crack", "elder_guardian", "electric_spark",
    "enchant", "enchanted_hit", "end_rod", "explosion",
    "explosion_emitter", "falling_dripstone_lava",
    "falling_dripstone_water", "falling_honey", "falling_lava",
    "falling_nectar", "falling_obsidian_tear", "falling_spore_blossom",
    "falling_water", "firefly", "firework", "fishing", "flame", "glow",
    "glow_squid_ink", "gust", "gust_emitter_large", "gust_emitter_small",
    "happy_villager", "heart", "infested", "item_cobweb", "item_slime",
    "item_snowball", "landing_honey", "landing_lava",
    "landing_obsidian_tear", "large_smoke", "lava", "mycelium", "nautilus",
    "note", "noxious_gas", "noxious_gas_cloud", "ominous_spawning",
    "orange_poplar_leaves", "pale_oak_leaves", "pause_mob_growth", "poof",
    "portal", "raid_omen", "rain", "red_poplar_leaves",
    "reset_mob_growth", "reverse_portal", "scrape", "sculk_charge_pop",
    "sculk_soul", "small_flame", "small_gust", "smoke", "sneeze",
    "snowflake", "sonic_boom", "soul", "soul_fire_flame", "spit",
    "splash", "spore_blossom_air", "squid_ink", "sulfur_bubbles",
    "sulfur_cube_goo", "sweep_attack", "totem_of_undying", "trial_omen",
    "trial_spawner_detected_player", "trial_spawner_detected_player_ominous",
    "underwater", "vault_connection", "warped_spore", "wax_off", "wax_on",
    "white_ash", "white_smoke", "witch", "yellow_poplar_leaves",
}

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

    # 1: the 38 families resolve through the category buckets (not the
    # exact-name candidates the replay would have degraded)
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
        category = sum(1 for t, h in rows.values() if h.startswith("category:"))
        ok = ok and category >= 30
        ok = ok and all(h != "exact-name"
                        for p, (t, h) in rows.items()
                        if p not in ("minecraft:",) and not p.startswith("minecraft:"))
        print(f"1 resolved-target shape on the 38 refusing families "
              f"({category}/38 category):", "PASS" if ok else "FAIL")
        failures += 0 if ok else 1

        # 2: every category target is in the verified 26.3 set
        bad = [(p, t) for p, (t, h) in rows.items()
               if h.startswith("category:") and t[len("minecraft:"):] not in VERIFIED_26_3]
        print("2 category targets registry-verified (26.3):",
              "PASS" if not bad else f"FAIL {bad}")
        failures += 0 if not bad else 1

        # 3: determinism — the same manifests produce byte-identical output
        out2 = root / "visual-particles-2.tsv"
        gen(scenes, out2)
        same = (root / "visual-particles.tsv").read_bytes() == out2.read_bytes()
        print("3 deterministic:", "PASS" if same else "FAIL")
        failures += 0 if same else 1

    # 4: default rows carry the documented default itself (the replay's
    # send then equals the TSV target even on the default rows)
    with tempfile.TemporaryDirectory() as td:
        root = pathlib.Path(td)
        scenes = root / "scenes"
        scenes.mkdir()
        (scenes / "gallery-particle-create-p0.tsv").write_text(
            "cell\tx\ty\tz\tparticle\tdyn\tnote\n"
            "cell\t-5\t-60\t9\tcreate:cube\t1\tok\n"
            "cell\t-4\t-60\t9\tcreate:wifi\t1\tok\n", encoding="utf-8")
        r = gen(scenes, root / "visual-particles.tsv")
        rows = parse(root / "visual-particles.tsv")
        ok = r.returncode == 0 and all(
            t == "minecraft:poof" and h == "default" for t, h in rows.values())
        print("4 default rows carry the documented default:",
              "PASS" if ok else f"FAIL {rows}")
        failures += 0 if ok else 1

    print("SMOKE:", "ALL PASS" if failures == 0 else f"{failures} FAILURES")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
