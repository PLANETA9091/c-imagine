# Vanilla Minecraft 26.3 — runtime analysis

Analyzed from primary artifacts: Mojang piston-meta manifest, `client.jar`,
`server.jar` (bundler + inner jar), and the `misode/mcmeta` registry dumps.
All numbers below were measured in this workspace on 2026-10-26.

## 1. Identity card

| Field | Value | Source |
|---|---|---|
| Version id | `26.3` (type `release`, `stable: true`, `series_id: main`) | `version.json` (client jar), manifest |
| Release date | 2026-09-15 (build time `11:20:48Z`) | `version.json` |
| Protocol version | **777** | `version.json: protocol_version` |
| World version (DataVersion) | **5023** | `version.json: world_version` |
| Data pack format | **121.0** (`data_major 121, data_minor 0`) | `version.json: pack_version` |
| Resource pack format | **97.1** (`resource_major 97, resource_minor 1`) | `version.json: pack_version` |
| Java runtime | `java-runtime-epsilon`, **majorVersion 25** | manifest `javaVersion` |
| Client jar | 41,483,720 B | piston-data download |
| Server jar | bundler 62,294,556 B → inner `server-26.3.jar` 26,652,008 B, **7,762 classes** | download + unzip |

For reference, 1.21.1 (2024-08-08): protocol `767`, DataVersion `3955`, datapack v48,
resourcepack v34, Java 21. Every one of those five numbers moved — this is the gap
the layer bridges (see `cross-version-gap.md`).

## 2. The versioning scheme is year-based "drops"

The manifest shows the `1.x.y` line ended and 2026 versions are `26.1`, `26.2`, `26.3`,
with `26.4-snapshot-1..5` in flight (most recent 2026-10-20). Paper's fill API groups
versions exactly this way (`26.3`, `26.2`, `26.1`, then legacy `1.21`...), and NeoForge's
maven line shows `26.1.2.x` / `26.3.0.x` NeoForge builds alongside `21.1.x`.

Consequences for c-imagine:

- The 26.3 kernel is **two years and many drops ahead** of the 1.21.1 mod stack. The
  layer's job is fundamentally a *cross-version facade*, not a proxy: ids, formats and
  protocols diverged on both sides (see the measured delta).
- We pin 26.3 **exactly** (not "latest"). CI downloads `26.3` by name from both Mojang
  and PaperMC fill v3 so kernel drift can never silently break the matrix. When the
  owner wants 26.4 support later, that is a deliberate bump with a fresh analysis pass.

## 3. What ships inside the vanilla server jar

The inner `server-26.3.jar` embeds the vanilla datapack. Measured data-folder census:

| Data category | Entries | Notes |
|---|---|---|
| `recipe/` | 2,044 | recipes are data-driven |
| `advancement/` | 1,882 | |
| `structure/` | 1,710 | |
| `loot_table/` | 1,476 | |
| `worldgen/` | 1,234 | JSON worldgen |
| `tags/` | 926 | |
| `villager_trade/` | 472 | **new vs 1.21.1** — trades are now data |
| `datapacks/` | 132 | bundled vanilla datapack variants |
| `trade_set/` | 83 | **new** |
| `painting_variant/` | 52 | |
| `damage_type/` | 52 | |
| `trial_spawner/` | 48 | |
| `enchantment/` | 44 | |
| `banner_pattern/` | 44 | |
| `sulfur_cube_archetype/` | 13 | **new 26.x content system** |
| `cat_variant/` | 12 | variant registries expanded (see §4) |
| `wolf_variant/` | 10 | |

The direction is unmistakable: between 1.21.1 and 26.3 Mojang kept converting
hard-coded behavior into registries + data (trades, dialogs, game rules, permissions,
RPC method tables — full list in `cross-version-gap.md §3`). For the layer this is
*good news on the kernel side* (Paper exposes data-driven content without code) and
*extra mapping work on the facade side* (a 1.21.1 mod expecting hard-coded villager
trades or gamerule semantics must be served through the relocated 1.21.1 stack, not
by poking Paper's data-driven equivalents ad hoc).

## 4. Registry snapshot (26.3, measured)

The `misode/mcmeta` `registries` branch lists **191 registries** for 26.3 (the 1.21.1-era
snapshot lists 136; part of the delta is mcmeta coverage of dynamic registries, and the
rest is real growth — see `cross-version-gap.md` for the name-level diff). Key sizes:

| Registry | 1.21.1 | 26.3 | Δ |
|---|---|---|---|
| block | 1,060 | 1,294 | +234 |
| item | 1,333 | 1,668 | +335 |
| entity_type | 130 | 163 | +33 |
| block_entity_type | 44 | 49 | +5 |
| menu | 25 | 26 | +1 |
| mob_effect | 39 | 41 | +2 |
| particle_type | 109 | 128 | +19 |
| sound_event | 1,611 | 2,040 | +429 |
| data_component_type | 57 | 122 | +65 |
| attribute | 31 | 40 | +9 |
| fluid | 5 | 3 | **−2** |
| enchantment | 42 | 43 | +1 |
| recipe_serializer | 23 | 22 | −1 |

Notes worth recording:

- `entity_type` diff shows per-wood `*_boat` / `*_chest_boat` entries as "new" — boats
  were split into per-material entity types after 1.21.1 (1.21.2 line). Mapping tables
  must handle these splits explicitly.
- `fluid` **shrank**: `flowing_water` / `flowing_lava` are gone from the registry in
  26.3. The layer's block-state remap must collapse the flowing-fluid representation
  difference instead of assuming a one-to-one fluid id space.
- `data_component_type` nearly doubled (57 → 122): the item component system is the
  single fastest-moving surface. This is the main risk area for item marshalling
  through the bridge (`ARCHITECTURE.md §4.4`) — treat it as its own subsystem with
  its own tests, not a corner of the remap table.

## 5. Runtime requirements and what they imply

- **Java 25.** The kernel (Paper 26.3) runs on Java 25; our Java side (bootstrap +
  plugin) targets `-release 21` bytecode per `AGENTS.md`, which Java 25 loads
  happily. CI already pins JDK 25 for the paper-api compile (commit `8ce92e6`).
  No change needed; recorded so nobody "fixes" it back to 21.
- **Protocol 777** is spoken by Paper 26.3 to vanilla clients. The 1.21.1 layer never
  speaks 777: modded 1.21.1 clients that join through the layer's own protocol surface
  are a post-M4 topic (roadmap note), while vanilla 26.3 clients are the F2V v2
  audience from M4 on.
- **DataVersion 5023**: Paper owns the world file and the DFU upgrades. The layer
  never persists world data, so 3955→5023 never crosses our boundary — but any chunk
  snapshot the bridge serves to the relocated 1.21.1 stack must be **read through
  Paper's API in 26.3 form and translated**, never read from disk behind Paper's back.

## 6. Open items for M1 (analysis debt, deliberately deferred)

1. **Client decompile.** The client jar is present but not yet decompiled. Plan:
   CI job downloads the official 26.3 mappings (`client.txt`, proguard format, linked
   from the version manifest), remaps with a tiny-remapper-based toolchain, and
   decompiles the render/connection packages with Vineflower; result cached as an
   Actions artifact. Targets: `EntityRenderDispatcher`/`EntityRenderers`,
   display-entity renderers, `ItemRenderer`, `LevelRenderer` chunk pipeline,
   `Connection` + packet codec classes. This feeds F2V v2 (M4) with ground truth
   instead of folklore.
2. **Server class census.** Same pipeline against the server jar to enumerate the
   exact 26.3 API surface Paper builds on (registry classes, DFU, packet classes).
3. **Worldgen delta walk.** 1,234 worldgen JSONs vs the 1.21.1 set — only needed once
   a test mod touches worldgen (M7).
