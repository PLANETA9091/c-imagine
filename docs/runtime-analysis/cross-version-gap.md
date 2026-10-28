# Cross-version gap: 1.21.1 ↔ 26.3 (measured)

The mod stack lives in 1.21.1 (2024-08-08). The kernel lives in 26.3 (2026-09-15).
Everything the bridge and F2F v2 do is governed by this delta. All numbers below are
**measured in this workspace** from primary artifacts (Mojang `version.json` files,
`misode/mcmeta` registry dumps at `registries` branch vs commit `0eaeabd259` —
1.21.1 release day), 2026-10-26.

## 1. Platform-level delta

| Dimension | 1.21.1 | 26.3 | Δ / impact |
|---|---|---|---|
| Protocol | 767 | 777 | 10 protocol bumps; F2V v2 only speaks 777 outward; 767 is the layer's internal world |
| DataVersion (world format) | 3955 | 5023 | 1,068 world-format revisions; irrelevant inside the module (Paper owns persistence), decisive for any 1.21.1-era world import story |
| Data pack format | 48 | 121.0 | mod `data/` resources are v48-era; kernel-facing datapack synthesis must target 121 |
| Resource pack format | 34 | 97.1 | F2V pack synthesis (M6) targets 97.x |
| Java | 21 | 25 | host JVM is Java 25; layer bytecode stays `-release 21`-compatible by repo law |
| Registry count (mcmeta) | 136 | 191 | +69 new registry types, −14 removed (full name diff §3) |

## 2. Content registries (entries), measured

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
| painting_variant | 50 | 51 | +1 |
| villager_profession | 15 | 15 | 0 |
| recipe_serializer | 23 | 22 | −1 |
| chat_type | 7 | 7 | 0 |
| **total (16 key registries)** | **4,581** | **5,712** | **+1,131** |

Structural observations:

- **Boat split**: 26.3 has per-wood `acacia_boat`, `bamboo_chest_raft`, ... where
  1.21.1 has generic `boat`/`chest_boat` (split happened on the 1.21.2 line). The
  entity remap table must map generically, not 1:1.
- **Fluid shrink**: `flowing_water`/`flowing_lava` gone in 26.3. Block-state remap
  handles flowingness via state properties, not registry ids.
- **Data components nearly doubled** (57 → 122): item marshalling through the bridge
  is its own tested subsystem; unknown-component policy: preserve as `custom_data`
  payload, never silently drop.
- `recipe_serializer` lost one entry; treat recipe translation as "translate by
  1.21.1 semantics, emit v121 datapack JSON" (M6+, not a runtime remap).

## 3. Registry types added / removed (name-level, mcmeta)

**Added in 26.3 (69):** `dialog`, `dialog_action_type`, `dialog_body_type`,
`dialog_type` (dialogs data-driven); `world_clock`, `timeline` (world time systems);
`game_rule` (data-driven gamerules); `permission_type`, `permission_check_type`,
`incoming_rpc_methods`, `outgoing_rpc_methods` (declared permissions + RPC tables —
new network surface); `sulfur_cube_archetype`; `villager_trade`, `trade_set`
(data-driven trades); `environment_attribute`; `input_control_type`;
`block_transformer`, `block_sound_set`; variant registries `cat_variant`,
`chicken_variant`, `cow_variant`, `pig_variant` + `*_sound_variant` families,
`zombie_nautilus_variant`; `item_definition`, `slot_display`, `slot_source_type`,
`recipe_display`, `recipe_book_category`; `model`, `font`, `texture`, `sound`,
`lang`, `resourcepack`, `datapack` (asset-side registries); `test_instance`,
`test_environment` (+ types), `test_function` (data-driven gametests);
`ticket_type`, `spawn_condition_type`, `experiment/*` bundles; worldgen registries
(`worldgen/feature_type`, `carver_type`, `block_state_provider`,
`material_condition/rule_type`); `attribute_type`, `consume_effect_type`,
`context_*_provider*`, `data_component_predicate_type`, `debug_subscription`,
`post_effect`, `predicate`, `equipment`, `rule_test_type`, plus tag entries.

**Removed vs 1.21.1 (14):** `armor_material`, `block_type`, `dimension`,
`item_sub_predicate_type`, `loot_number_provider_type`, `rule_test`, `schedule`,
`worldgen/configured_carver`, `worldgen/configured_feature`, tag/potion,
tag/cat_variant, and two experiment bundles.

Reading of the direction: **MoJang moved everything possible into data**. For the
layer this cuts two ways:

- Kernel side: Paper 26.3 exposes data-driven content natively — good for F2V pack
  synthesis and for serving vanilla stand-ins.
- Facade side: a 1.21.1 mod's expectations (hard-coded trades, gamerule semantics,
  feature placement) are satisfied **inside the relocated 1.21.1 stack**, and only
  the *observable world effects* cross the bridge. We never try to express "1.21.1
  behavior" by editing 26.3 datapacks — that path ends in behavioral drift.

## 4. Translation policy derived from this gap

1. **Registry remap tables** (`cim-registry::RemapTable`) are generated per kernel
   version from snapshots: layer id ↔ kernel id for shared registries; modded ids in
   the disjoint `1_000_000_000+` space (design D5).
2. **Split/merge handling**: explicit, table-driven (boats; fluids) with a CI
   conformance fixture per structural change — a silent 1:1 assumption must never
   pass CI.
3. **Unknown-on-either-side policy**: kernel-only content (the +234 blocks, +335
   items) is invisible to the layer by default (layer clients see what 1.21.1 can
   see); layer-only content (modded ids) is projected by F2V v2 for vanilla clients.
4. **Formats never cross raw**: datapack/resource-pack JSON is re-emitted in the
   target format (48↔121, 34↔97.1) by the synthesis pipeline; NBT/components are
   marshalled as typed frames (design §4.4), not shipped as opaque blobs.
5. **Drift tripwire**: snapshot diff on every CI run; a kernel registry change fails
   the matrix until tables are regenerated — kernel drift can never enter silently.

## 5. What this gap means for milestones

| Milestone | Gap exposure |
|---|---|
| M1 (layer boots) | none — the relocated stack is self-consistent 1.21.1 |
| M2 (mods run) | registration ids, items/blocks/events — remap tables v1 |
| M3 (state bridge) | blockstate 1.21.1↔26.3 incl. fluid shrink + boat-class splits |
| M4 (F2F packets) | 777 outbound rewriting; entity metadata dialects |
| M5 (visual) | renderer-level semantics (decompiled ground truth, both versions) |
| M6 (packs) | pack format 97.1 synthesis + datapack 121 re-emission |
| M7 (breadth) | components/capabilities marshalling; mod channels over the gap |
