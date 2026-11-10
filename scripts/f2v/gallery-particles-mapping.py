#!/usr/bin/env python3
"""Truth-leg particle manifests -> the F2V visual-particles.tsv mapping.

THE PARTICLE CHANNEL's pack-side half (DIAGNOSTIC-first): every modded
ParticleType id the truth walk spawned (the gallery-particle-<ns>-p<p>.tsv
manifests) gets a deterministic vanilla stand-in the vanilla 26.3 client
can resolve AND PLAY as simple options. Heuristic per modded id
``ns:path`` (documented, in order):

  1. exact-name -> ``minecraft:<path>`` but ONLY when the path is a
     VERIFIED-SIMPLE vanilla particle (VANILLA_SIMPLE below) — an
     exact-name candidate that is unregistered or data-carrying would
     degrade at replay time to the default while the join still expects
     the candidate, a manufactured miss (measured #302: the old TSV
     carried 43 exact-name rows of which exactly ONE — create:soul —
     could ever resolve; the other 42 replayed as poof against an
     expected exact-name, a 1/43 mapped ceiling BY CONSTRUCTION);
  2. category:<keyword> -> a small keyword table over the path whose
     every target is verified simple (first match wins, most specific
     first) — the sound channel's table law (gallery-sounds-mapping.py);
  3. default    -> ``minecraft:poof`` (applied at REPLAY time when the
     TSV target is absent from Paper's live PARTICLE_TYPE registry, and
     by this generator when nothing matches).

THE OPTIONS CONTRACT: a modded particle's serialized options bytes are
NOT portable to a vanilla client, so the replay rebuilds the packet with
the vanilla particle's SIMPLE default options (the registry ParticleType
itself when it is a SimpleParticleType, else the poof fallback) carrying
the original count/speed/offsets — data-carrying options (dust colours,
block states) DEGRADE to the simple lookalike by contract, and the
capture stream records the rewrite so the join names it.

THE TARGET VALIDATION (14-dq, measured sources, no third parties): the
26.3 client jar (piston e877b6a07acd633fb3bb475002175cec036e7b87) lists
115 registered particle definitions in assets/minecraft/particles/*.json;
paper-api 26.3.build.248's org/bukkit/Particle enum constructor encodes
the per-constant data type (a 4-arg constructor with a Class = data-
carrying: dust, dust_color_transition, entity_effect, effect,
instant_effect, item, block, block_marker, falling_dust, dust_pillar,
block_crumble, tinted_leaves, dragon_breath, flash, vibration, trail,
sculk_charge, shriek, geyser, geyser_base, geyser_plume, geyser_poof —
22 constants; the replay refuses any type that is not a ParticleOptions
and falls back to poof). VANILLA_SIMPLE = registered AND simple: 98 ids.

Usage: gallery-particles-mapping.py <scenes-dir> <out-tsv>
  scenes-dir  dir holding gallery-particle-<ns>-p<p>.tsv (seed-pack/scenes)
  out-tsv     the visual-particles.tsv to write
"""
import pathlib
import sys

DEFAULT = "minecraft:poof"

# registered in the 26.3 client (assets/minecraft/particles/) AND simple in
# paper-api 26.3.build.248 (no data class in the Particle enum constructor).
VANILLA_SIMPLE = frozenset("""
angry_villager ash bubble bubble_column_up bubble_pop campfire_cosy_smoke
campfire_signal_smoke cherry_leaves cloud composter copper_fire_flame
crimson_spore crit current_down damage_indicator dolphin
dripping_dripstone_lava dripping_dripstone_water dripping_honey dripping_lava
dripping_obsidian_tear dripping_water dust_plume egg_crack electric_spark
enchant enchanted_hit end_rod explosion falling_dripstone_lava
falling_dripstone_water falling_honey falling_lava falling_nectar
falling_obsidian_tear falling_spore_blossom falling_water firefly firework
fishing flame glow glow_squid_ink gust happy_villager heart infested
landing_honey landing_lava landing_obsidian_tear large_smoke lava mycelium
nautilus note noxious_gas ominous_spawning orange_poplar_leaves
pale_oak_leaves pause_mob_growth poof portal raid_omen rain red_poplar_leaves
reset_mob_growth reverse_portal scrape sculk_charge_pop sculk_soul small_flame
small_gust smoke sneeze snowflake sonic_boom soul soul_fire_flame spit splash
spore_blossom_air squid_ink sulfur_bubbles sulfur_cube_goo sweep_attack
totem_of_undying trial_omen trial_spawner_detection
trial_spawner_detection_ominous underwater vault_connection warped_spore
wax_off wax_on white_ash white_smoke witch yellow_poplar_leaves
""".split())

# ORDER MATTERS (first match wins): the specific compounds ride BEFORE their
# generic substrings, so jetpack_smoke lands on the smoke bucket and
# soul_expanding_perimeter on the soul bucket, never the reverse. Every
# target is verified against VANILLA_SIMPLE at generation time (a target
# outside the set would replay as the default against an expected keyword
# target — a manufactured miss, exactly the #302 exact-name trap).
CATEGORY_TABLE = [
    # leaves: the nearest pastel vanilla leaf/petal color
    ("orange_maple", "minecraft:orange_poplar_leaves"),
    ("red_maple", "minecraft:red_poplar_leaves"),
    ("yellow_maple", "minecraft:yellow_poplar_leaves"),
    ("snowblossom", "minecraft:pale_oak_leaves"),
    ("jacaranda", "minecraft:cherry_leaves"),
    ("leaves", "minecraft:cherry_leaves"),
    # blood drips ride the red obsidian-tear drip family (drip -> fall -> land)
    ("falling_blood", "minecraft:falling_obsidian_tear"),
    ("landing_blood", "minecraft:landing_obsidian_tear"),
    ("dripping_blood", "minecraft:dripping_obsidian_tear"),
    ("blood", "minecraft:dripping_obsidian_tear"),
    ("pus", "minecraft:dripping_honey"),
    ("drip", "minecraft:dripping_water"),
    # energy / electric / spark family
    ("lightning", "minecraft:electric_spark"),
    ("electric", "minecraft:electric_spark"),
    ("energy", "minecraft:electric_spark"),
    ("spark", "minecraft:electric_spark"),
    ("vibrant", "minecraft:glow"),
    ("radiation", "minecraft:glow"),
    ("glow", "minecraft:glow"),
    ("wisp", "minecraft:glow"),
    # soul family: the exact-name soul rides row 1 (minecraft:soul is simple);
    # the derived perimeter/base effects ride the soul fire flame
    ("soul", "minecraft:soul_fire_flame"),
    # Create machinery
    ("crafting", "minecraft:happy_villager"),
    ("rotation", "minecraft:happy_villager"),
    ("basin", "minecraft:splash"),
    ("fluid", "minecraft:splash"),
    ("air_flow", "minecraft:cloud"),
    ("airflow", "minecraft:cloud"),
    ("wifi", "minecraft:end_rod"),
    ("beam", "minecraft:end_rod"),
    ("matter_cannon", "minecraft:crit"),
    ("laser", "minecraft:crit"),
    ("projectile", "minecraft:crit"),
    ("cannon", "minecraft:crit"),
    # generic elemental buckets (the specific compounds above match first)
    ("spore", "minecraft:warped_spore"),
    ("bubble", "minecraft:bubble"),
    ("scuba", "minecraft:bubble"),
    ("steam", "minecraft:white_smoke"),
    ("smoke", "minecraft:smoke"),
    ("flame", "minecraft:flame"),
    ("fire", "minecraft:flame"),
    ("jetpack", "minecraft:flame"),
    ("ash", "minecraft:ash"),
    ("snow", "minecraft:snowflake"),
]


def map_one(pid: str) -> tuple[str, str]:
    """The heuristic ladder for one modded id -> (target, heuristic)."""
    _, _, path = pid.partition(":")
    if path in VANILLA_SIMPLE:
        return f"minecraft:{path}", "exact-name"
    for keyword, target in CATEGORY_TABLE:
        if keyword in path:
            return target, f"keyword:{keyword}"
    return DEFAULT, "default"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    scenes = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2])
    # table hygiene: every keyword target must be a verified-simple vanilla
    # particle — a bad row would replay as the default against an expected
    # keyword target (the #302 exact-name trap, caught here at generation)
    def _path(target):
        _, _, p = target.partition(":")
        return p
    bad = [t for _, t in CATEGORY_TABLE if _path(t) not in VANILLA_SIMPLE]
    if bad:
        raise SystemExit(
            f"CATEGORY_TABLE targets not verified-simple: {', '.join(sorted(bad))}")
    if _path(DEFAULT) not in VANILLA_SIMPLE:
        raise SystemExit(f"default {DEFAULT} not verified-simple")
    ids = set()
    for mf in sorted(scenes.glob("gallery-particle-*-p*.tsv")):
        for line in mf.read_text(encoding="utf-8").splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) >= 5 and parts[0] in ("cell", "skip"):
                ids.add(parts[4])
    rows = []
    for pid in sorted(ids):
        target, heuristic = map_one(pid)
        rows.append(f"{pid}\t{target}\t{heuristic}\n")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        "# generated by gallery-particles-mapping.py from the truth-leg"
        " particle manifests (deterministic; do not edit)\n"
        "# Format (TSV): modded_particle_id <TAB> target_vanilla_particle_id <TAB> heuristic\n"
        f"# heuristic: exact-name / keyword:<kw> / default ({DEFAULT}); every"
        " target is a VERIFIED-SIMPLE 26.3 vanilla particle (registered in the"
        " client jar's assets/minecraft/particles AND option-less in"
        " paper-api's Particle enum) — the replay re-validates against"
        " Paper's live registry at send time\n"
        + "".join(rows)
    )
    print(f"gallery-particles-mapping: {len(rows)} particle ids -> {out} "
          f"(exact-name/keyword/default, all targets verified-simple; "
          f"default at replay = {DEFAULT})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
