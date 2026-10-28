# F2F 2.0 parity matrix — F2V v1 render surface → 26.3 vanilla-client methods

Owner directive (2026-10-28): **modded content must render on the stock vanilla
26.3 client IDENTICALLY to the modded client — the F2V v1 surface, delivered by
the new 26.3 methods.** This matrix is the measured contract for that: every v1
pixel channel, its 26.3 replacement, c-imagine's current status, and the
verification leg that proves pixel identity.

Sources (measured, not guessed):
- v1: read-only clone `jefthebuilder/F2V` @ HEAD (forge-1.20.1 + neoforge-1.21.1
  + shared; no code pushed there).
- v2 surface: `docs/runtime-analysis/f2v2-client-capabilities.md` (26.3 client
  jar + registries), `f2v2-pack-synthesis-26-3.md` (pack format 97.1),
  `post-effect-schema-26-3.md` (chain grammar).
- Current implementation: `paper-plugin/src/main/java/dev/cim/f2v/*` (M5.2),
  CI: integration.yml (wire) + visual.yml (pixel harness).

## 1. v1 channel inventory (read-only measurement)

| # | channel | v1 mechanism (classes, measured size) | vanilla-client carrier |
|---|---|---|---|
| 1 | Items | `CustomModelDataRanges` (disjoint CMD intervals: ITEM 10k–100k, BLOCK 100k–500k, ENTITY 4M–8M, POSE 550k–600k, ARMOR_ANIM 600k–650k, SPATIAL 650k–700k, BLOCK_ANIM 700k–800k, PATH 850k, CRACK 900k×10, ATMOSPHERE 900_020/910k+512, TILE 800k, LOD palette 900_100) + `CarrierModelBuilder` (parent `item/generated` + `overrides`) + `CmdCarrierShards` (flat/generated carriers only) | legacy `models/item` + `overrides` chains on carrier items |
| 2 | Entities | `EntityModelBuilder` 1920 + `GeckoEntityModelBuilder` 2152: modded entity models → per-part item models (Cobblemon: ~1.7k appearances × ~40 models); `display/Rig` 516: server-side **ItemDisplay per part** + transforms | item_display entities carrying CMD carrier stacks |
| 3 | Blocks | `client/BlockDisplayManager` 2955/2907: **BlockDisplay** per virtual block, collision proxies, LOD budget 4000/8000, crack overlays (10-stage CMD 900k) | block_display entities |
| 4 | Armor | `ArmorModelBuilder`: humanoid armor layer as item-display models; wearables remapped to CMD carriers; head-node scale(0,0,0) suppression trick | item_display on head/chest/... via mapped stacks |
| 5 | Atmosphere | `AtmosphereAssetBuilder` + `DimensionEffectsBytecodeScanner`: modded dimension skies from bytecode evidence → CMD atlas-safe sky/cloud models (≤512 textures) | CMD carrier item models |
| 6 | HUD | `ClientOnlyHudSimulator` 2762: server-opened vanilla menus + synthetic widgets + bitmap/map tiles + `displayClientMessage`; cursor hit-testing server-side | vanilla menu screens (openMenu) |
| 7 | Particles | `ParticleRecreator` 296: vanilla-particle recreation (+ ITEM_DISPLAY for sprite particles) | vanilla particle packets |
| 8 | Contraptions | `client/ContraptionManager` 3863: Create trains/bogeys/contraptions as display rigs, sublevel transforms, server interpolation | item/block_display stacks |
| 9 | Delivery | `PacketSupport.resourcePack` → `ClientboundResourcePackPushPacket` (play phase) + `PackHttpServer` (HTTP/HTTPS keystore); `ResourcePackPartitioner`/`Variants`/fingerprints | resource pack push + HTTP |
| 10 | Asset pipeline | voxelizers (`Voxelizer`, `ColoredVoxelizer`, `ItemModelVoxelizer`, `ModelPartItemVoxelizer`), `ObjParser`+`ExactObjBaker` 1837, `AnimationBake`, `DynamicTextureSynthesizer`, `MapRasterFontAssets`, `ModTintScanner`, `ColorAtlas` | pack JSON/PNG |
| 11 | Skins | `FakePlayerManager` + `PackHttpServer.skinUrl` | fake-player skin URLs |

**Measured v1 defect to NOT reproduce:** `PackManager.FLUID_CMD_BASE = 600_000`
falls INSIDE the `ARMOR_ANIM` interval (600_000..650_000) — exactly the
collision class `CustomModelDataRanges`' javadoc warns about. v2 needs no
family intervals at all (see §2.1).

## 2. v2 replacement per channel (26.3-native methods)

### 2.1 Items — DONE (M5.2, wire-proven; pixel proof = visual run 7+)

- v1 needed a CARRIER item + legacy `overrides` per family. 26.3 makes the
  carrier architecture obsolete: `assets/minecraft/items/<host>.json`
  (registry `item_definition`, 1658 vanilla defs) wraps the host def in
  `range_dispatch` over `minecraft:custom_model_data` (0 vanilla usages —
  free hook), vanilla look preserved as the fallback branch.
- CMD rides the vanilla data-component codec (SLOT/container wire — proven by
  the M5.2 net-watch: `container content -> cimbot cmd=1000.0`).
- No intervals, no collisions: ids are namespaced (`cimtest:test_block` →
  host `minecraft:paper` + cmd 1000), allocation is manifest-driven.
- **Identity contract:** hotbar pixel diff — plain paper vs CMD paper must
  differ (run 7+), and the CMD render must match the modded client's item
  render (NeoForge ground-truth leg, M5 accept).

### 2.2 Entities — design measured, implementation next (M5.4)

The v1 MECHANISM (item_display rigs with CMD item stacks) survives unchanged
in 26.3 — display entities are vanilla, the CMD hook is the component codec.
What changes: everything becomes packet-level (no mod classes):
- modded entity spawn → `add_entity` with `minecraft:item_display` +
  stack carrying the modded model's CMD component (M3.2 remap tables own the
  id translation; M4 wire proven for iron_golem stand-ins).
- per-part transforms: v1 used one display per part; packet-level alternative:
  a single item_display with the pre-assembled model (voxelizer bakes parts
  into one graph) — same pixels, 1 entity instead of N.
- wire budget note (measured): v1's 4M–8M sparse CMD family handled ~68k
  entity models; v2 items/ defs scale the same way (one def per CMD entry).
- **Verification:** visual leg spawns cimtest:test_entity via the translation
  layer; screenshot diff vs NeoForge render of the same entity.

### 2.3 Blocks — wire legs live (M4), pixel leg live, IDENTITY rig locked (camera-lock-26-3.md)

- v1 BlockDisplay budget (4000/8000) translates to a packet-level display
  budget; collision proxies stay Paper-API-side (M3 layer already owns block
  placement).
- crack overlays: keep as 10-stage CMD models on the block_display stack
  (26.3 has no native virtual-block break overlay).
- **Identity (2026-10-29):** run 4's ungated block region (50281) was
  camera-pose-polluted (relative placements + no daylight pin on the c-imagine
  side + GUI rows inside the region). The camera-lock rig
  (`camera-lock-26-3.md`) removes all three pollutants: server `tp` with
  explicit yaw/pitch into a fixed cell, absolute `^-1 ^ ^3` floor cell for
  the block, scene sweep + F1 + noon/clear pins — mirrored on BOTH stacks.
  visual.yml legs 13/14 (stand-in via pack note_block override) vs
  parity-diff.yml t03/t04 (REAL cimtest:test_block): appearance gates live
  (>3000 each side), THE BLOCK IDENTITY DATUM prints ungated until the
  first locked run calibrates the gate.

### 2.4 Armor — 26.3 FIRST-CLASS replacement (M6, pull-forward candidate)

v1's whole ArmorModelBuilder/suppression-trick family is replaced by the
measured 26.3 equipment surface: `equipment/<asset_id>.json` (45 vanilla defs)
+ item `equippable` component `asset_id`. One JSON per modded armor set, no
display rigs, no head-scale hacks. Humanoid + carpets/harnesses covered by
vanilla renderers (constraint doc §9.3).
- **Verification:** give a cimtest armor piece → client screenshot of the
  worn model vs NeoForge render.

### 2.5 Atmosphere — the post_effect registry KILLS the v1 hack family

v1 burned ~30k lines (scanner + builders + 512-texture sky packs + CMD
carriers) because 1.21.1 had NO server→client atmosphere channel. 26.3 has a
native one, proven end-to-end in M4: `post_effect` registry (pack-contributed
chains, GLSL-free composition of vanilla passes) + `ClientboundPostEffectsPacket`
requests by id. The M5.2 pack already emits `c-imagine:cimsepia`; the bot
received it on the wire (`POST_EFFECTS: ["c-imagine:cimsepia"]`).
- Remaining proof (run 7+): the xvfb client actually RESOLVES the
  pack-contributed chain (client-side registry read of the pack's
  `post_effect/cimsepia.json`) — pixel diff tinting the whole frame.
- v1's DimensionEffectsBytecodeScanner family shrinks to: "which chain should
  be requested when player X is in dimension Y" — a server-side decision
  riding a native packet.

### 2.6 HUD — dialogs are the native hook (M6; measured contract `dialog-hud-26-3.md`)

v1's ClientOnlyHudSimulator (openMenu + synthetic widgets) is replaced on 26.3
by the **dialog registry** — data-driven server-authored screens with NATIVE
widgets: inputs (text/boolean/single_option/number_range), bodies
(plain_message + item — the item channel graphics inline), actions
(dynamic/run_command template, dynamic/custom → `PaperPlayerCustomClickEvent`,
static click events). All three packets are COMMON-phase (show_dialog /
clear_dialog / serverbound custom_click) — server forms work even in the
config phase. Paper surface measured end-to-end: `Dialog.create(...)` builder
(no file needed) + `Audience.showDialog` (adventure 5.2.0 default no-op,
CraftPlayer override sends the packet) + `DialogResponseView` payload getters.
Static HUDs ride `data/minecraft/dialog/<ns>/<id>.json` through F2vPack (the
M5.2 data-pack channel). v1's server-side cursor hit-testing dies: the vanilla
client hit-tests its own buttons and the server receives a semantic event.
- **Verification:** M6 leg — F2vPack dialog json + `/cim dialog` +
  ItemDialogBody rendering the cimtest stack → xvfb screenshot + custom-click
  response marker.

### 2.7 Particles — M4 sanitizer territory (partially live)

Vanilla particle packets already flow through the M4 wire contract. Sprite
particles (v1's ITEM_DISPLAY trick) = item_display with CMD stack, same as
§2.2 — rides the entity rig machinery.

### 2.8 Contraptions — display rigs + interpolation (M6)

Rig mechanism identical to §2.2; the delta is server-side transform
interpolation (v1: 1-pixel jitter accumulation measured in ContraptionManager).
26.3 display entities accept full transform metadata + teleport/lerp — packet
level is strictly richer than v1's mod-side approach.

### 2.9 Delivery — DONE (M5.2)

v1: play-phase push + own HTTP(S). v2: config-phase properties push (zero
play-phase ids needed — bot walks the real accept state machine) +
`PackHttp` loopback (serves the sha1-covered bytes). Play-phase dynamic push
(add/remove packs at runtime) stays on the M5.3+ census list (`resource_pack_push`
= config id 9; play-phase id from the live wire table when needed).

### 2.10 Asset pipeline — Rust port (M6)

Port order by need (measured v1 sizes): ObjParser+ExactObjBaker (1837 — OBJ
is a 26.3 client feature, `Model.CubeFactory`?... measured: OBJ still parsed
by the 26.3 client), voxelizer family for entity/block bakes, ModTintScanner →
replaced by the palette system (`paletted_permutations` + `textures/palettes`),
MapRasterFontAssets → font composition, AnimationBake → per-tick CMD switches
or the new timeline/world_clock registries for dimension-time anims.

### 2.11 Skins — unchanged (M7)

FakePlayerManager + skin URLs survive as-is (vanilla mechanism).

## 3. Status rollup

| channel | v1 | v2 method | c-imagine status | proof leg |
|---|---|---|---|---|
| items | overrides+CMD carriers | items/ range_dispatch | **M5.2 LIVE** | visual run 7+ pixel diff |
| entities | ItemDisplay rigs | same, packet-level | **M5.4 LIVE**: wire GREEN + pixel leg GREEN (run 43/44 rig-pixels 123322, cloud-drift subtraction) | spawn leg + screenshot |
| blocks | BlockDisplay | same, packet-level | pixel leg live (run 43) + **camera-lock identity rig implemented both sides** (gate after the first locked run) | locked pre/shot + identity datum |
| armor | CMD humanoid rigs | equipment/ defs | **M6 PULLED FORWARD: pack emit + give/wear leg live** (visual-equipment.tsv; EquippableComponent.setModel javap-proven; pixel leg staged: F5 third-person + item replace + AE gate) | wear + screenshot |
| atmosphere | CMD sky models (30k LOC family) | **post_effect chains** | wire-proven (M4) + pack emit (M5.2) | tint pixel diff (run 7+) |
| HUD | menus+widgets | menus + dialog registry | **M6 measured: `dialog-hud-26-3.md` (packet/codec/API surface decompiled; common-phase proven)** | dialog screenshot + custom-click marker |
| particles | recreation + display sprites | sanitizer + rigs | partial (M4) | particle leg |
| contraptions | display rigs | display rigs (packet) | not started (M6) | move leg + screenshots |
| delivery | push+HTTP | config push + PackHttp | **M5.2 LIVE** | bot accept walk |
| asset pipeline | Java voxelizer/OBJ/Gecko | Rust pack synthesis | pack v1 live; M6 port | per-channel |
| skins | fake players | same | not started (M7) | skin leg |

## 4. Implementation order (status 2026-10-29 — 1–5 DONE, the identity rig is the frontier)

1. **M5.4 entity visual leg** — DONE (runs 43/44: rig-pixels 123322 over the
   cloud-drift control).
2. **Multi-item pack v2** — DONE (select-dispatch brick/wand, run 7+).
3. **Postfx pixel proof** — DONE (run 7+: AE(cmd,postfx-on)=842107).
4. **Equipment pull-forward** — DONE (run 10/11 wire+pixel green).
5. **Block visual leg** — DONE (run 43 first-dispatch 79211).
6. **Camera-lock identity rig** — IN FLIGHT (camera-lock-26-3.md): both
   sides implemented, first locked run calibrates THE BLOCK IDENTITY DATUM,
   then the gate locks. Every future in-world channel (entities, armor
   worn-in-world, contraptions) reuses this rig for its identity datum.
7. Then M6 asset-pipeline port (palettes → fonts → OBJ → voxelizer), each
   behind a pixel contract.
