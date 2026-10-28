# Vanilla client 26.3 — measured visual capabilities for F2V 2.0

Method: ground-truth measurement of the actual jars (never guesses).
- `client-26.3.jar` (40 MB, piston `e877b6a07acd633fb3bb475002175cec036e7b87`)
- `client-1.21.1.jar` (26 MB, piston `30c73b1c5da787909b2f73340419fdf13b9def88`)
- Scripts: `scripts/analyze-client-visual.py`, `scripts/scan-client-anchors.py`,
  `scripts/read-263-registries.py` (raw JSON: `artifacts/analysis/client-visual-inventory.json`).
- Registry side: mcmeta `registries` branch @ 26.3 snapshot `2240df23` (136 registries).
- Original F2V reference clone: `artifacts/f2v-original` (jefthebuilder/F2V, read-only).

This doc feeds ROADMAP M4/M5/M6 supplements. Per owner directive the F2V 2.0 vision
(post effects + ItemsAdder/Oraxen-style items) is IMPLEMENTED ONLY WHEN THE ROADMAP
REACHES IT; this is the measured groundwork captured now so M5/M6 start hot.

## 1. Headline: client asset systems became registries in 26.3

mcmeta `registries` @ 26.3 (each entry = one loadable, overridable unit):

| registry | entries | note |
|---|---|---|
| `item_definition` | 1658 | item model definitions (`assets/<ns>/items/*.json`) |
| `model` | 4192 | `models/**/*.json` |
| `texture` | 3964 | every texture is a registered unit |
| `block_definition` | 1288 | blockstates |
| `equipment` | 45 | armor/worn assets (`equipment/*.json`) |
| `atlas` | 12 | atlases |
| `font` | 7 | incl. `include/*` fragments |
| `post_effect` | 5 | **blur, creeper, entity_outline, invert, spider** |
| `timeline` | 4 | day, early_game, moon, villager_schedule |
| `world_clock` | 2 | overworld, the_end |
| `environment_attribute` | 51 | audio/ambient_sounds, gameplay/bed_rule, ... |
| `dialog` | 3 | custom_options, quick_actions, server_links |
| `debug_subscription` | 16 | client debug overlays |

In 1.21.1 NONE of these were registries — they were loose asset folders. This is the
single biggest F2F-relevant delta: the client resolves all of them through the same
"registry key → json" machinery (`ShaderManager` + `FileToIdConverter("post_effect", ".json")`
proven in bytecode), so packs address them uniformly and cleanly.

## 2. Server → client post-effect channel (the big one)

Bytecode evidence in the 26.3 client:

- `ClientboundPostEffectsPacket` in `net/minecraft/network/protocol/common/CommonPacketTypes`
  (sibling of `resource_pack_push`, `server_links`) with a full lifecycle in the client:
  `handlePostEffects`, `applyPostEffects`, `clearPostEffects`, `requestedPostEffects`,
  `appliedPostEffects`, `failedPostEffects`, `getAvailablePostEffects`, `getKnownPostEffects`,
  `shouldResetFailedPostEffects`.
- `net/minecraft/server/level/ServerPlayer` holds a `post_effects` field; `sendPostEffects`
  on the server side; datafixer renamed `minecraft:post_effect` → `minecraft:post_effects`.
- `SuggestionProviders.POST_EFFECTS` — command-suggestion provider over the registry,
  i.e. server commands can address post effects by id.
- `DebugEntryPostEffects` — F3 debug entry listing active client post effects.

Loader mechanics: `net/minecraft/client/renderer/ShaderManager` loads
`post_effect/<id>.json`; `LevelTargetBundle` builds named render targets
(`entity_outline`, `always_on_top_depth`) via `withDefaultNamespace(...)`; `LevelRenderer`
routes passes (`addPass`, `clear`). Legacy `shaders/post/*.json` + `shaders/program/*`
are GONE in 26.3 (1.21.1 had 6 chain jsons + 9 program jsons); 26.3 keeps only GLSL
(`shaders/core/*.vsh|fsh` 29/34, `shaders/post/*.vsh|fsh` 1/8, `shaders/include/*.glsl` 17)
and declares pipelines in code: `net/minecraft/client/renderer/RenderPipelines`
(blaze3d pipeline rework; refs: `pipeline/entity_outline_blit`, `core/screenquad`,
`core/blit_screen`, explicit `BlendFactor`s).

F2V 2.0 consequences:
1. Post-effect chains are a **registry** — modded content can contribute new chains
   (registry entry + GLSL-free json composition of existing passes/programs), and
2. the **server requests them by id over the wire** and the client applies/clears them.
   That is exactly the owner's "render everything through post effects" hook — the
   vanilla 26.3 protocol carries it natively. M4 packet translation must map this
   packet family (767↔777), and the Paper plugin gains a first-class
   post-effect control surface for F2F.

Chain inventory (26.3, measured): `blur`, `creeper`, `entity_outline`, `invert`,
`spider` (legacy `transparency.json` dropped; 26.3 has an "improved-transparency"
toggle instead — `SubmitNodeStorage`, `ChunkSectionLayer.transparency`).

## 3. Item model definitions — the ItemsAdder/Oraxen pipeline, now first-class

`assets/minecraft/items/*.json`: **1658 definitions** (1.21.1: zero — it used
`models/item/*.json` + overrides: 1754 there; 26.3 keeps 1309 `models/item` as parents).

Maker histogram across all 1658 defs (total type occurrences, measured):

| type | count | role |
|---|---|---|
| `minecraft:model` | 2253 | plain model ref |
| `minecraft:special` | 91 | BE-style renderers |
| `minecraft:select` | 71 | property string dispatch |
| `minecraft:dye` | 50 | **dye-tinted overlays** |
| `minecraft:composite` | 34 | stacked sub-models |
| `minecraft:copper_golem_statue` | 32 | NEW special renderer |
| `minecraft:condition` | 26 | bool conditions |
| `minecraft:bundle/selected_item` | 17 | nested item render |
| `minecraft:shulker_box` | 17 | special |
| `minecraft:banner` | 16 | special |
| `minecraft:chest` | 13 | special |
| `minecraft:constant` | 11 | property value source |
| `minecraft:range_dispatch` | 8 | **numeric dispatch (CMR floats)** |
| `minecraft:head`, `potion`, `shield`, `trident`, `conduit`, `decorated_pot`, `firework`, `player_head`, `grass` | 1–6 each | specials |

Special renderers available: `copper_golem_statue`(32), `shulker_box`(17), `banner`(16),
`chest`(13), `head`(6), `shield`(2), `trident`(2), `conduit`, `decorated_pot`,
`player_head`, `firework`.

`custom_model_data` is referenced by **zero** vanilla defs — the property hook is
entirely free for modded content, same contract ItemsAdder/Oraxen use (float ranges →
`range_dispatch`, string variants → `select` on `custom_model_data`), but now via the
clean `items/` definition system instead of legacy `overrides` chains.

## 4. Equipment assets (custom armor/worn visuals)

45 `equipment/*.json` (1.21.1: none — hardcoded): `iron/diamond/gold/netherite/
chainmail/copper/leather/elytra/saddle/turtle_scute/armadillo_scute` + 16 dyed
carpets + 16 harnesses + `trader_llama[,_baby]`. Paired with
`textures/entity/equipment/*` (85 pngs) and the client palette system.
F2F emits one `equipment/<asset_id>.json` per modded armor set; the item's
`equippable` component points at it.

## 5. Atlases, palettes, fonts

- Atlases 26.3: `banner_patterns, blocks, celestials(!), chests, decorated_pot, gui,
  items, map_decorations, paintings, particles, shield_patterns, shulker_boxes`
  (1.21.1's `armor_trims, beds, mob_effects, signs` atlases removed — merged
  elsewhere; `celestials` new — sun/moon/sky). Source types: `directory` 14,
  `single` 2, `paletted_permutations` 1.
- **New client palette system**: `net/minecraft/client/resources/palette/Palette`
  loads `textures/palettes/*.png`; `TrimMaterials$Palette` + `AtlasProvider` show
  trims are palette-driven. Data-driven recoloring (dye variants from one texture)
  is a client feature now, not a texture-grid trick.
- Fonts: 7 defs with `reference` provider + `include/*` fragments — packs compose
  fonts cleanly (Oraxen-style glyph pages without copy-pasting provider blobs).

## 6. Waypoints & world environment

- `waypoint_style/` client assets (`default`, `bowtie`) + `WaypointStyleManager`
  + `WaypointStyleAssets` registry key — data-driven waypoint locator visuals.
- `timeline` (day, early_game, moon, villager_schedule), `world_clock`
  (overworld, the_end), `environment_attribute` (51: ambient audio, bed_rule, ...)
  — sky/day-night/environment behavior is data-driven per dimension.

## 7. Asset-tree diff 1.21.1 → 26.3 (summary)

- asset files 8448 → 11813 (+40%).
- New top-level roots: `post_effect/`, `items/`, `equipment/`, `waypoint_style/`,
  `textures/palettes`, `texts/`, plus `shaders/post` GLSL retained while chain jsons
  moved to `post_effect/`.
- Removed: `atlases/{armor_trims,beds,mob_effects,signs}.json`, `shaders/program/`,
  `textures/models/` (→ `trims/entity` restructure), legacy `blockstates/chain.json`
  (renamed iron_chain — matches the M3.2 drift table).
- GUI sprites 422 → 522; wolf textures 33 → 59 (sound/variant families: cat/pig/cow/
  chicken/wolf sound-variant registries are new in 26.3).

## 8. F2F 2.0 surface (what the pack builder + protocol layer will emit/translate)

| modded artifact (NeoForge 1.21.1) | 26.3 vanilla-client surface | roadmap |
|---|---|---|
| item models / Bezier-override chains | `items/<id>.json` (`range_dispatch`/`select` on `custom_model_data`) + `atlases/items.json` directory source | M6 |
| armor sets | `equipment/<id>.json` + `equippable` component `asset_id` | M6 |
| block visuals | `block_definition` + `model` registries (blockstates jsons as today) | M6 |
| recolors | `paletted_permutations` + `textures/palettes` + `minecraft:dye` maker | M6 |
| glyph/emoji pages | `font` with `reference`/`include/*` composition | M6 |
| mod renderer post-processing | `post_effect` registry entries + `ClientboundPostEffectsPacket` requests | M4 (packet) + M5/M6 (chains) |
| waypoint HUD mods | `waypoint_style` assets | M6 (stretch) |

Original-F2V delta: the 1.21.1 pack engine (`pack/CustomModelDataRanges`,
`models/item` + `overrides`, 50 classes; `vm/discovery/*` 39 bytecode scanners)
mapped to legacy overrides; F2V 2.0 targets the registry-based surface above —
fewer hacks, cleaner overrides, and a native server→client post-effect channel
that simply did not exist in 1.21.1.

## 9. Constraints (measured, to respect in M4–M6)

1. `post_effect` registry has 5 vanilla chains; pack-contributed chains are new
   registry entries — client applies only what the server requests by id
   (`requestedPostEffects`), failed loads are tracked (`failedPostEffects`) and
   reset policy exists (`shouldResetFailedPostEffects`). F2F must ship GLSL only
   within existing pass/program vocabulary (no `shaders/program/` jsons anymore).
2. `items/` definitions are client-side (resource pack); the `custom_model_data`
   property hook is free (0 vanilla usages) but every def needs its atlas sprites.
3. Equipment assets cover humanoid + carpets/harnesses; any other worn slot needs
   the vanilla renderers that exist (no arbitrary new special renderers from packs).
4. Palette/dye paths rely on vanilla palette PNG format — verify exact schema at M6
   (sample: `textures/palettes` loader `Palette.class`).
5. Everything above must ride pack format **97.1** (Paper 26.3 resourcepack version).

## 10. Registry migration PROVEN by code triggers (census-visual-263.py)

`scripts/census-visual-263.py` (in-repo, rebuilds `data/visual-inventory.json`)
scans every class constant pool for resource-path literals:

* 1.21.1 client code hardcodes all 6 legacy chains:
  `shaders/post/{blur,creeper,entity_outline,invert,spider,transparency}.json`;
* the 26.3 client code hardcodes **ZERO** `post_effect/...` path literals —
  chain ids arrive through the registry/`ClientboundPostEffectsPacket` and
  resolve via `FileToIdConverter` at runtime.

That is the strongest possible proof of the migration claim in section 1:
in 26.3 the chain surface is purely data-driven, so a resource pack (F2V 2.0)
can add chains WITHOUT touching code, and the server can address any of them
by id on the wire.

Raw counts (26.3 / 1.21.1): post_effect chains 5 / 0 (legacy 6),
items defs 1658 / 0 (legacy models/item 1309 kept as parents),
equipment 45 / 0, atlases 12 / 8, fonts 7 / 5,
shaders tree core 63 / 166 + program 24 / 0 + post 9 / 6 + include 17 / 4,
asset files 11811 / 8447 (+40%). New top-level asset roots: `equipment`,
`items`, `post_effect`, `waypoint_style`.
