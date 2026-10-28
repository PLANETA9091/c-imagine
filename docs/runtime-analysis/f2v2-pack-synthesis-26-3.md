# F2V 2.0 pack synthesis (26.3) — measured surface + M5.2 v1 implementation

Ground truth for the visual pipeline that makes a STOCK 26.3 client render
modded item content identically to a modded client. Every shape below is
measured from the actual jars (never guessed):

- `client-26.3.jar` (piston `e877b6a0...`): `version.json`, embedded
  `data/minecraft/datapacks/*/pack.mcmeta`, `assets/minecraft/items/*.json`,
  `assets/minecraft/atlases/items.json`,
  `net.minecraft.client.renderer.item.properties.{numeric,select}.CustomModelDataProperty`,
  `net.minecraft.server.packs.metadata.pack.PackMetadataSection{,$1}`,
  `net.minecraft.server.packs.metadata.pack.PackFormat`.
- paper-api 26.3.build.226-stable (repo.papermc.io): `ItemMeta`,
  `CustomModelDataComponent`, `Player#setResourcePack` family,
  `PlayerResourcePackStatusEvent$Status`.

Implementation: `paper-plugin/src/main/java/dev/cim/f2v/F2vPack.java` (+
`PackHttp.java`), manifest `scripts/f2v/visual-items.tsv`.

## 1. pack.mcmeta (26.3)

The client's own `version.json` (inside the jar) is the authority:

```json
{"pack_version": {"resource_major": 97, "resource_minor": 1,
                  "data_major": 121, "data_minor": 0},
 "protocol_version": 777, "world_version": 5023, "java_version": 25}
```

`PackMetadataSection` reads `pack` → `description` (component) +
`PackFormat.packCodec(PackType)`; the embedded datapacks carry
`"min_format": 121, "max_format": 121` (ints). `PackFormat` supports BOTH
int form (minor=0) and `[major, minor]` pairs (`compactListCodec`), plus
legacy `supported_formats` / `pack_format` strings (`%d.%d` toString).
EMITTED shape (typed keys + legacy key tolerated — DFU ignores unknown keys):

```json
{"pack": {"description": "...", "pack_format": 97,
          "min_format": [97, 1], "max_format": [97, 1]}}
```

## 2. items/ definition + custom_model_data (the ItemsAdder/Oraxen surface)

- 1658 vanilla `items/*.json`; ZERO reference `custom_model_data` (re-measured
  by grep over the extracted client assets) — the hook is free.
- `range_dispatch` schema (vanilla `bow.json`): `entries[].threshold` (float)
  + `entries[].model` (node) + `fallback` (node) + `property`
  (`minecraft:use_duration` there; ours: `minecraft:custom_model_data`).
- `numeric.CustomModelDataProperty` bytecode: optional field `"index"`
  (`ExtraCodecs.NON_NEGATIVE_INT`, default 0), reads
  `DataComponents.CUSTOM_MODEL_DATA` — the property consumes the FIRST float
  of the component by default. `select.CustomModelDataProperty` exposes the
  same `index` for string variants (not needed for v1 floats).
- Host def emitted by the builder (vanilla look preserved as fallback):

```json
{"model": {"type": "minecraft:range_dispatch",
  "property": "minecraft:custom_model_data",
  "entries": [
    {"threshold": 1000.0,
     "model": {"type": "minecraft:model", "model": "cimtest:item/test_block"}}
  ],
  "fallback": {"type": "minecraft:model", "model": "minecraft:item/paper"}}}
```

- Constraint: the HOST item's vanilla def must be a plain
  `minecraft:model` ref (flat items) — the fallback branch re-points at the
  host's own model, which is only faithful for simple defs. Fancy hosts
  (bow/compass/...) are excluded by policy, not by the format.

## 3. Atlases (why no atlas merge is needed)

Vanilla `atlases/items.json` includes
`{"type": "minecraft:directory", "prefix": "item/", "source": "item"}` — the
directory source scans EVERY namespace. Any modded texture placed at
`assets/<ns>/textures/item/**` (or `textures/block/**` for the blocks atlas)
is auto-atlased. Textures outside those dirs would require a MERGED override
of `atlases/items.json` (full vanilla content + extra source) — v1 skips them
with a warning instead of risking a malformed atlas.

## 4. Model graph copying

The modded model chain (e.g. `cimtest:item/test_block` → parent
`cimtest:block/test_block` → parent `minecraft:block/cube_all` +
`textures.all = minecraft:block/iron_block`) is copied at ORIGINAL paths:

- entry model: `assets/<ns>/models/item/<path>.json` (fallback candidate:
  `assets/<ns>/models/<path>.json`);
- modded parents (ns ≠ minecraft) copied recursively (depth ≤ 16);
- vanilla parents stop the walk (the client owns them);
- modded textures referenced by copied models are copied from the jar;
  `minecraft:`-namespace textures are NOT copied.

## 5. Server-side item identity (paper-api 26.3, javap-verified)

- `ItemMeta#getCustomModelDataComponent()` /
  `setCustomModelDataComponent(...)` with
  `CustomModelDataComponent.setFloats(List<Float>)` — the full 26.3
  component (floats/flags/strings/colors). `setCustomModelData(Integer)`
  legacy surface still exists.
- `ItemStack#editMeta(Consumer)` present.
- Give leg: host `Material` + CMD float = resolved manifest row; verified
  by scanning the inventory for the host item carrying the exact CMD.
  Wire identity: Paper serializes the component through the vanilla
  `container_set_content` codec — the client sees the CMD the pack
  dispatches on.

## 6. Pack delivery (config-phase push — measured path)

- 26.3 pushes server packs during the CONFIG phase:
  `resource_pack_push` = configuration clientbound id **9**
  (`CommonPacketTypes`, play-family twin exists but is NOT used by v1).
- `server.properties` carries `resource-pack=<url>` +
  `resource-pack-sha1=<hex>` — the client enforces the sha1 on download.
- v1 URL = loopback `http://127.0.0.1:25580/cim-f2v-pack.zip`
  (`PackHttp`, JDK `com.sun.net.httpserver`, fixed payload snapshot, 2
  daemon threads). CI/stand topology = same host; production points the
  properties at a real pack host (out of scope).
- Client answer (`ServerboundResourcePackPacket`, config serverbound id 6):
  uuid + varint action — ordinals measured: successfully_loaded=0,
  declined=1, failed_download=2, accepted=3, downloaded=4, invalid_url=5,
  failed_reload=6, discarded=7. cimbot walks ACCEPTED → SUCCESSFULLY_LOADED
  (the vanilla-client-with-packs-enabled walk; the M4-era decline was a stub).
- MEASURED on 3cab5d8 (live CI boot): the properties pack push ARRIVES and the
  bot's accept walk completes (server proceeds to play without a kick), but
  **`PlayerResourcePackStatusEvent` does NOT fire for config-phase packs** —
  paper-api 26.3 has exactly one pack status event class and Paper fires it
  for play-phase pushes only. The WIRE accept contract is therefore proven
  bot-side (ServerboundResourcePackPacket flow, asserted in the report
  checks); the event handler stays as play-phase diagnostics (M5.3 dynamic
  push will exercise it), and the apply-side proof rides the M5 xvfb real
  client (a real client downloads + applies and its render is the ground
  truth).
- paper-api fires `PlayerResourcePackStatusEvent` with
  `Status.ACCEPTED` / `Status.SUCCESSFULY_LOADED` (Bukkit's historic typo)
  for PLAY-phase pushes (see the measured config-phase caveat above); the
  plugin logs every status as a diagnostic line.
- M5.3a chain contribution: the pack also ships
  `assets/c-imagine/post_effect/cimsepia.json` — a sepia tint composed ONLY
  from vanilla programs (`post/color_convolve` + `post/blit`, ColorConfig 3×
  vec3 + BlitConfig, targets swap → minecraft:main, exactly the measured
  vanilla chain grammar). The net leg requests `c-imagine:cimsepia` over the
  post-effects channel (live-proven: the bot parsed the id at 1383a66).
- **MEASURED ANSWER (live registry-name census, integration@1383a66, 32
  dynamic registries)**: `post_effect` is NOT server-synced — the streamed
  set is worldgen/biome, chat_type, trim_pattern/material, the 10+ variant
  families (wolf/pig/frog/cat/cow/chicken + sound variants), painting,
  sulfur_cube_archetype, dimension_type, damage_type, banner_pattern,
  enchantment, jukebox_song, instrument, test_environment/test_instance,
  dialog, world_clock, timeline, decorated_pot_pattern, block_transformer,
  worldgen/block_state_provider. NEITHER post_effect NOR item_definition/
  model/texture/equipment are streamed — the client resolves ALL asset
  registries locally from the resource layer. CONSEQUENCE: the pack
  contribution path (chain json in the pack + server requests the id) is
  the ONLY correct delivery — and it is exactly what v1 implements. The
  remaining apply-side proof is the xvfb client render.
- WHY pre-boot: the properties hash must be final before the JVM starts, so
  the CI builds pack.zip BEFORE boot (deterministic zip → second build is
  byte-identical → the plugin's boot-time rebuild verifies the same sha1;
  integrity-first policy serves the stored zip on mismatch).

## 7. Builder determinism contract

`F2vPack.zip()`: `TreeMap`-sorted entries, fixed DOS-epoch timestamps,
deflate level 9 → same inputs produce identical bytes. CI runs the build
TWICE (pre-boot step) and diffs the sha1; the plugin logs
`integrity=ok (matches pre-boot build)` after its own rebuild. Both asserted.

## 8. v1 scope boundaries (deliberate)

| deferred | why | where |
|---|---|---|
| play-phase `resource_pack_push` handling in cimbot | config-phase push covers the contract; play-phase id enters via the unknown-ids census when first observed | M5.3 |
| in-world BLOCK visuals (block_definition/model registries + stand-in change) | block identity is the M3.1 sidecar contract (structure_void stand-in); changing it deserves its own leg | M6 |
| auto-assign sweep over all mod items | JEI-scale asset trees; manifest-driven rows keep the pack minimal | M6 |
| merged atlas overrides for non-item|block textures | riskier pack surgery; warning path exists | M6 |
| real-client screenshot diff (xvfb harness) | the wire + server contract is proven by cimbot; pixel ground truth needs the client harness | M5 visual.yml (dispatch-only) |
| OBJ/multipart models, equipment assets, paletted_permutations, post_effect chain contributions | measured surface exists (f2v2-client-capabilities.md §3-§5) | M6 |

## §12. Paletted permutations — 26.3 shape (measured 2026-10-28, M6 palette channel)

26.3 CHANGED the file format: the 1.19.4-era standalone `paletted_permutations/*.json`
files are GONE — the mechanism is now an ATLAS SOURCE (decompiled
`net.minecraft.client.renderer.texture.atlas.sources.PalettedPermutations` from the
obf 26.3 client + vanilla `atlases/items.json` uses it for trims):

```json
{ "type": "minecraft:paletted_permutations",
  "textures": ["minecraft:item/<base>", ...],        // list
  "palette_key": "minecraft:trim_base",              // palette PNG (textures/palettes/)
  "permutations": { "<suffix>": "minecraft:trim/<color>", ... },
  "separator": "_" }                                  // optional, default "_"
```

Output sprite per permutation: `<texture><separator><suffix>` (e.g.
`minecraft:item/armor_1_iron`). Palette semantics: per-pixel color match
against the palette-key PNG (exact ARGB), remapped through the permutation
PNG's colors — the 26.3-native replacement for F2F v1's ModTintScanner /
ColorAtlas family (recolor = two small PNGs + one source entry, no code).

c-imagine M6 plan: `visual-palettes.tsv` manifest (base texture, palette key,
permutations) -> F2vPack merges `atlases/items.json` sources (the SAME merge
discipline as `blockstates/note_block.json`), copies palette PNGs under
`textures/palettes/`. Pixel leg: recolored cimtest cube variant in hotbar.
