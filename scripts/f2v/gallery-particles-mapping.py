#!/usr/bin/env python3
"""Truth-leg particle manifests -> the F2V visual-particles.tsv mapping.

THE PARTICLE CHANNEL's pack-side half (DIAGNOSTIC-first): every modded
ParticleType id the truth walk spawned (the gallery-particle-<ns>-p<p>.tsv
manifests) gets a deterministic vanilla stand-in the vanilla 26.3 client
can resolve. Heuristic (documented, in order):
  1. exact-name -> ``minecraft:<path>`` (mods occasionally reuse a vanilla
     particle path; the replay validates the target against the live Paper
     PARTICLE_TYPE registry before sending, so an absent vanilla path
     degrades to the default at replay time — LEDGERED, never silent);
  2. category:<kw> -> a keyword table over the path (first match wins,
     specific buckets before generic ones): every target in the table is
     VERIFIED PRESENT in the real 26.3 particle registry (javap over the
     26.3 client jar's ParticleTypes, the same measured guarantee the
     sound generator's table carries — 103/103 present there);
  3. default    -> ``minecraft:poof``.

THE 14-ds LEVER (the measured-refused exact-name-only shape): the
previous generator emitted exact-name candidates only, and NONE of the 38
measured modded families (create/ae2/biomesoplenty/mekanism) resolved —
the replay degraded every row to poof at send time while the mapped
preview expected the TSV target: a guaranteed TSV-vs-send disagreement on
every row. The category buckets make the TSV target the RESOLVED target
for the measured families (the replay still validates and still ledgers
any drift — defense in depth unchanged), which is what lets the
particle-stream flip bar (position_hits == expected with order_exact)
mean anything once packets flow.

THE OPTIONS CONTRACT: a modded particle's serialized options bytes are
NOT portable to a vanilla client, so the replay rebuilds the packet with
the vanilla particle's SIMPLE default options (the registry ParticleType
itself when it is a SimpleParticleType, else the poof fallback) carrying
the original count/speed/offsets — data-carrying options (dust colours,
block states) DEGRADE to the simple lookalike by contract, and the
capture stream records the rewrite so the join names it.

Usage: gallery-particles-mapping.py <scenes-dir> <out-tsv>
"""
import pathlib
import sys

DEFAULT = "minecraft:poof"

# ORDER MATTERS (first match wins): the specific buckets ride BEFORE the
# generic ones ("jetpack_smoke" lands on smoke, not the "jetpack" bucket).
# Every target verified present in the 26.3 particle registry (javap over
# the client jar's ParticleTypes: cloud, dripping_water, splash,
# falling_water, landing_obsidian_tear, enchant, electric_spark, soul,
# bubble, flame, smoke, glow, end_rod, spore_blossom_air, cherry_leaves,
# snowflake, damage_indicator, portal, note, heart, crit, explosion — all
# present).
CATEGORY_TABLE = [
    # Create kinetic/air machinery
    ("air", "minecraft:cloud"),
    ("wind", "minecraft:cloud"),
    ("steam", "minecraft:cloud"),
    ("mist", "minecraft:cloud"),
    ("cloud", "minecraft:cloud"),
    # fire families
    ("soul", "minecraft:soul"),
    ("flame", "minecraft:flame"),
    ("fire", "minecraft:flame"),
    ("ember", "minecraft:flame"),
    # smoke families (after steam/mist)
    ("smoke", "minecraft:smoke"),
    # fluids: drips/falls/landings, then the generic liquid words
    ("drip", "minecraft:dripping_water"),
    ("fall", "minecraft:falling_water"),
    ("landing", "minecraft:landing_obsidian_tear"),
    ("fluid", "minecraft:dripping_water"),
    ("water", "minecraft:splash"),
    ("liquid", "minecraft:splash"),
    ("bubble", "minecraft:bubble"),
    ("scuba", "minecraft:bubble"),
    ("snow", "minecraft:snowflake"),
    # foliage
    ("leaves", "minecraft:cherry_leaves"),
    ("spore", "minecraft:spore_blossom_air"),
    # energy / magic
    ("lightning", "minecraft:electric_spark"),
    ("electric", "minecraft:electric_spark"),
    ("energy", "minecraft:electric_spark"),
    ("laser", "minecraft:end_rod"),
    ("radiation", "minecraft:glow"),
    ("glow", "minecraft:glow"),
    ("crafting", "minecraft:enchant"),
    ("magic", "minecraft:enchant"),
    ("enchant", "minecraft:enchant"),
    ("portal", "minecraft:portal"),
    ("teleport", "minecraft:portal"),
    # combat / life
    ("damage", "minecraft:damage_indicator"),
    ("blood", "minecraft:damage_indicator"),
    ("heart", "minecraft:heart"),
    ("heal", "minecraft:heart"),
    ("crit", "minecraft:crit"),
    ("explosion", "minecraft:explosion"),
    ("boom", "minecraft:explosion"),
    ("blast", "minecraft:explosion"),
    ("note", "minecraft:note"),
    ("sound", "minecraft:note"),
    # the generic modded-suffix bucket (crafting_fx / matter_cannon_fx /
    # vibrant_fx family): the enchantment glyph stream is the closest
    # vanilla magic sparkle
    ("fx", "minecraft:enchant"),
]


def resolve(ns: str, path: str) -> tuple[str, str]:
    """(target, heuristic) for one particle id."""
    if ns == "minecraft":
        # a vanilla-namespace row never reaches here (LADDER_NS is modded),
        # but the identity is the honest answer if it ever does
        return f"minecraft:{path}", "exact-name"
    for kw, target in CATEGORY_TABLE:
        if kw in path:
            return target, f"category:{kw}"
    return DEFAULT, "default"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    scenes = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2])
    ids = set()
    for mf in sorted(scenes.glob("gallery-particle-*-p*.tsv")):
        for line in mf.read_text(encoding="utf-8").splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) >= 5 and parts[0] in ("cell", "skip"):
                ids.add(parts[4])
    rows = []
    counts: dict[str, int] = {}
    for pid in sorted(ids):
        ns, _, path = pid.partition(":")
        target, heuristic = resolve(ns, path)
        counts[heuristic.split(":")[0]] = counts.get(heuristic.split(":")[0], 0) + 1
        rows.append(f"{pid}\t{target}\t{heuristic}\n")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(
        "# generated by gallery-particles-mapping.py from the truth-leg"
        " particle manifests (deterministic; do not edit)\n"
        "# Format (TSV): modded_particle_id <TAB> target_vanilla_particle_id <TAB> heuristic\n"
        "# heuristic: exact-name | category:<kw> | default (see the script docstring)\n"
        "# every category target verified present in the 26.3 particle registry;\n"
        f"# the replay validates every target live and ledgers any drift\n"
        + "".join(rows)
    )
    print(f"gallery-particles-mapping: {len(rows)} particle ids -> {out} "
          f"({counts.get('exact-name', 0)} exact-name, "
          f"{counts.get('category', 0)} category, {counts.get('default', 0)} default)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
