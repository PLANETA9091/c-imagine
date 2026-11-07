# Test modpack plan

Goal: every subsystem of c-imagine (loader, registries, events, state bridge, packet
translation, resource pack, GUI scenes, watchdog budgets) is exercised by at least one
mod, in CI, on every push. Two tiers: first-party mods we own, and third-party mods
pinned by version.

## Tier 1 — first-party (`test-mods/`, NeoForge 1.21.1)

| Mod | Exercises | Status |
|---|---|---|
| `cimtest` | registration (block/item/BE/entity), lifecycle events, login hooks, custom mods.toml metadata, dependency declarations | M0 ✔ (source) / M2 (layer execution) |
| `cimtest-net` | custom payload/channel both directions (1.21.1 `PayloadRegistrar`/`StreamCodec`): wire-codec round trips, S2C hello on player login, C2S echo from the client | source ✔; codec legs asserted in the layer boot + M2 harness; registration + full wire round trip asserted on real stacks (parity-real-neoforge / parity-diff) |
| `cimtest-cap` | custom DataComponent (`cimtest_cap:charge`) + item capability (`RegisterCapabilitiesEvent`, 21.1 API — AttachCapabilitiesEvent is removed in 1.21) | source ✔; component legs asserted in the layer boot + M2 harness; capability legs asserted on real stacks (parity-real-neoforge / parity-diff) |
| `cimtest-heavy` | the deterministic allocation + tick-load generator the profiling budget gate measures against (/cimheavy load msPerTick ticks; markers `[cimtest_heavy] load armed/running/done`) | DONE — the multi-mod jar, 4th [[mods]] entry; the layer constructs it (per-mod FML containers) |

Layout note: `cimtest-net` and `cimtest-cap` use the runtime mod IDs
`cimtest_net` and `cimtest_cap` (NeoForge forbids hyphens in mod IDs) and ride the SAME
`cimtest-1.0.0.jar` (multi-mod jar — one `[[mods]]` entry each in
`test-mods/src/main/resources/META-INF/neoforge.mods.toml`, `@Mod` class per
modId). Integration staging stays single-jar
(`test-mods/build/libs/cimtest-1.0.0.jar` everywhere in CI); real FML,
cim-modscan (parses every `[[mods]]` entry) and the F2V pack builder
(asset-scan only) all tolerate the shape.

Planned additions (each tiny, each one subsystem):
- `cimtest-asset` — custom model/texture/OBJ for pack synthesis + visual diff.
- `cimtest-gui` — menu/HUD surface for GUI scene translation.
- `cimtest-heavy` — allocation/tick-load generator as the profiling stressor.

### Tier-1 marker contracts (expect/actual, the CI grep law)

Lifecycle fact the contracts encode (neoforge-21.1.265 sources): common setup
runs BEFORE the registration-events stage (`RegisterCapabilitiesEvent`) and
before the network-registry lock (`RegisterPayloadHandlersEvent`); the
c-imagine layer's FmlHost runs construction → registration → common setup
only. Therefore the common-setup legs fire in the LAYER boot, while the
registration / capability / wire-round-trip legs fire on REAL stacks only —
their absence in a layer boot.log is the honest measurement of that layer gap
(not a mod defect).

| Marker | Where it must appear | What proves it | Expect/actual pair |
|---|---|---|---|
| `[cimtest-net] mod constructed (payload handlers armed)` | layer boot + M2 harness + every real boot | the mod constructs through real FML in both stacks | — |
| `[cimtest-net] s2c codec round-trip ok (token=4242 decoded=4242)` | layer boot + M2 harness + real server | the S2C payload's StreamCodec encode/decode round trips — the deterministic net-pipeline leg the layer reaches | `decoded=4242` == `token=4242` |
| `[cimtest-net] c2s codec round-trip ok (token=4242 decoded=4242)` | same | same, for the C2S payload | `decoded=4242` == `token=4242` |
| `[cimtest-net] payloads registered (c2s=cimtest_net:echo s2c=cimtest_net:hello)` | real server (parity-real-neoforge, parity-diff) — NOT in the layer yet | `RegisterPayloadHandlersEvent` fired: both directions registered through the 21.1 network registry | — |
| `[cimtest-net] s2c hello sent player=cimviewer token=4242` | real server with a join (parity-diff) | the S2C send leg fired on player login | EXPECT: `token=4242` |
| `[cimtest-net] s2c hello received token=4242 (client echo follows)` | truth client (`client.out`, parity-diff) | the payload crossed the net pipeline inbound to the modded client | the EXPECT token re-read client-side |
| `[cimtest-net] c2s echo received player=cimviewer token=4242 (round-trip ok)` | real server with a join (parity-diff) | the C2S leg closed the loop through the net pipeline | ACTUAL: `token=4242` == expect → round trip |
| `[cimtest-cap] mod constructed (component/capability queues armed)` | layer boot + M2 harness + every real boot | constructs through real FML | — |
| `[cimtest-cap] data component registered (cimtest_cap:charge)` | layer boot + M2 harness + real server | the DataComponentType resolved from the registry (registry phase ran in the layer) | — |
| `[cimtest-cap] data component round-trip ok (item=cimtest:test_block set=7 got=7)` | layer boot + M2 harness + real server | server-side component write/read round trip | `got=7` == `set=7` |
| `[cimtest-cap] component codec round-trip ok (charge=7)` | layer boot + M2 harness + real server | the persistent codec (the serialization the §4.4 bridge marshalling rides) round trips through NBT | `charge=7` decoded == encoded |
| `[cimtest-cap] capabilities registered (item=cimtest:test_block cap=energy)` | real server — NOT in the layer yet | `RegisterCapabilitiesEvent.registerItem` (21.1 API) | — |
| `[cimtest-cap] capability round-trip ok (cap=energy item=cimtest:test_block set=42 got=42)` | real server — NOT in the layer yet | the capability resolved off the stack and carried a write→read round trip (`FMLLoadCompleteEvent` leg; a `FAILED` variant logs honestly if unresolved) | `got=42` == `set=42` |

CI wiring: the boot job + M2 harness assert the layer-reachable legs; the
`parity-real-neoforge` job asserts the real-server legs; the
`parity-diff` modded-truth job asserts the FULL `cimtest-net` wire round trip
(hello → client → echo → receipt, token equality) — the round trip is
asserted, not just booted. The token is CONSTANT (`4242`) so every boot
produces identical greppable pairs.

## Tier 0 — the parity ladder (owner directive 2026-10-29, pulled forward NOW)

**Real mods are the proof surface, effective immediately** — first-party rigs
(Tier 1) stay as fast scaffolding, but a rig we write ourselves can accidentally
encode c-imagine-friendly assumptions that diverge on real NeoForge. Every
comparison is dual-stack: real NeoForge 1.21.1 client (screenshot + profile
everything) vs c-imagine on the vanilla 26.3 client (same scenes, same
captures) — judged by profile numbers AND by eye. Harness design:
`runtime-analysis/mod-parity-harness.md` (roadmap M5.5).

| Priority | Mod | Why it is on the ladder | Gate |
|---|---|---|---|
| **P0** | **Create** | The flagship: kinetic network, contraptions, fluids, particles, sounds, UIs. **100% identical is the single top priority.** | **GALLERY PIXEL PARITY** (`gallery.yml`, owner directive 2026-10-30): every Create block/state pixel-GREEN at the datum — RED on anything less — plus calibrated identity datum on EVERY Create channel |
| P1 | Biomes O' Plenty | Block palette + biome-tint channels at scale (worldgen surface) | GALLERY PIXEL PARITY on the BOP registry set (100% of cells at the datum, both stacks) + biome-tint channel proof |
| P1 | Dynamic Trees | Block entities + incremental growth ticks (BE bridge + event loop) | GALLERY PIXEL PARITY on the DT registry set + BE bridge channel proof |
| P1 | Combat Roll | Client animation/sound surface — smallest mod, first full ladder walk | animation + sound channels green on both stacks + registry gallery green |
| P2 | Aged modpack (full) | The "every mod supported" end-state check; version pinned from its manifest when CI wiring lands | full gallery boots + RED-cells-bounded cleanup to 100% |

Per-mod, per-block, per-state coverage is the universal gallery (`/cimscene gallery`):
EVERY registered block from every loaded mod + ALL its states + a vanilla control
group, placed as real blocks in front of the test player on BOTH stacks in one CI
run; the pixel join is red unless 100% of cells match at the calibrated datum and
the red cells ship as triptych artifacts. Census counts, construct and mixin
markers are DIAGNOSTICS — they never green a rank (owner directive 2026-10-30).

### Ladder CI wiring status (branch `ci/full-content-coverage`, 2026-11-04 — nothing here is measured GREEN yet)

The gallery walks **four ladder namespaces** (`LADDER_NS` in `gallery.yml`:
`create biomesoplenty dynamictrees combat_roll` — registry namespaces
verified from each jar's `neoforge.mods.toml`; pins in
`scripts/cimscene/mods.lock`). Status per mod (ON MAIN, measured — gallery
#114 is the baseline: 39.7% gated green-share by the flat count, the fixes
below landed after it; the first full 12-class measurement is the first
post-fix gallery run):

| Mod | CI status (main) | Measured baseline (#114) + fixes landed after it |
|---|---|---|
| Create | full walk all 12 classes on main | blocks 48.9% (31,389 states, full page coverage), items 20.3% — the CMD-space collision fix (`a83ec48f`) re-owns the duplicated thresholds; particles 13 types now spawn (programmatic send), the sound stream channel is live (14 built-in + mapped vanilla), 14 GUI menus walked diagnostic |
| Biomes O' Plenty | full walk all classes on main; companions glitchcore + terrablender + cloth-config pinned (mandatory deps, measured: a missing one is an FML boot crash) | blocks 16.4% — 44 of ~118 pages shot EMPTY on ours (the down-stretches lane, 14-bb); the vanilla-template parent fix (14-ay) resolves 12/13 red families; biome-tint = DIAGNOSTIC (69 biomes in the ledger) |
| Dynamic Trees | full walk all classes on main | blocks 13.7% gated (the tree mass rides the DYNAMIC class), items 12.9% — the same CMD fix applies |
| Combat Roll | full walk on main; playeranimator companion pinned | registers NO visual content (0 blocks/items/entities) — its ladder value is the sound class (2 sounds walked) + held |

Companion mods (playeranimator, glitchcore, terrablender) ride the server
but their namespaces are deliberately not ladder namespaces — they are
libraries/providers, not gallery content.

### Identity pipelines re-armed for the modern contract (branch `feat/parity-modern`, 2026-11-05 — NOTHING here is measured GREEN yet)

The two dispatch-only identity pipelines last ran 2026-10-28 and still tested the
pre-ladder, cimtest-only contract. They now ride the same reality the gallery walks:

- **visual.yml** (stock vanilla 26.3 client, the calibrated manual harness — STAYS
  dispatch-only): an optional `gallery_run_id` input stages a gallery seed-pack's seed v2
  manifests; the ladder legs then walk page 0 of every `LADDER_NS` namespace through the
  same replay surfaces the gallery ours leg uses — per-ns hotbar/first-person/inventory
  parity shots (`gal-item/gal-held/gal-inv-<ns>.png`) plus the ordered sound replay logs
  the plugin records post-rewrite. Without the input the job runs exactly the calibrated
  cimtest harness. Item CMD carriers are assigned by the seed's own law (300000+i over
  the concatenated rows, `a83ec48f`); the cimtest pack defs resolve identically
  (self-contained graphs; `--assets` only adds the 14-ay vanilla fallback).
- **parity-diff.yml** (real NeoForge 1.21.1 client truth): the truth side now also builds
  cimscene, carries the locked ladder jars, re-walks item/sound page 0 per ladder ns, and
  shoots the real-client mirror shots (`t-item/t-held-<ns>.png`) plus the SoundCapture
  windows (`cimscene gallery sound`, the packet-capture mixin). The identity-diff job
  measures per channel — ladder item hotbar crops (pairing-guarded by both sides'
  manifest first rows), held first-person full-frame joins (`framediff.py`), and the
  ordered sound-stream identity (`sound-diff.py --map`) — all REPORT-FIRST: no new gate
  is locked until a dispatch measured the datum.

Honest status: the calibrated datums are preserved byte-identically (the icon sanity
gates, the camera-lock appearance gates, the luminance-normalized block identity datum —
measured 1531, gate 2500, region 545,170,190,115 tol 4). The new per-channel numbers do
not exist yet; the first post-merge dispatch produces them, and the sound channel is
expected to name missing rows on the ours side until the F2V sound rewrite resolves
beyond the stand-in mapping. Nothing in this section is a green claim.

### The new gallery classes (2026-11-04 — all DIAGNOSTIC-first: walked, reported, never red until the channel is measured stable)

| Class | What it measures | First-run expectation (honest) |
|---|---|---|
| `sound` | every registered SoundEvent played in a deterministic page order; the truth capture is a packet-level mixin log (`gallery-sound-<ns>-p<p>.log`), the ours replay writes the same artifact shape; `sound-diff.py` joins the ordered streams | ours empty (no mod-sound channel in the F2V pack/plugin — verified before wiring) → every page named missing |
| `gui` | one MenuType per page opened for the rig viewer via vanilla `openMenu`; menus needing block context become named `open-failed` rows | truth: context-free menus open, context menus fail honestly; ours: all nochannel (no menu translation) |
| `held` | the item walk's first manifest item staged into the viewer's hotbar on both legs, the `1` key pressed, first-person shot | item channel's first-person proof; unmeasured yet |
| `anim` | kinetic block pages (manifest dyn=1 cells) shot twice 2 s apart per leg; `anim-diff.py` joins the frame deltas | a frozen ours kinetic page shows as OURS-STATIC; unmeasured yet |
| `biome` | one biome per page via the vanilla `fillbiome` surface, fixed grass/leaves tint-sample rig in frame | the P1 BOP tint-channel proof surface; ours all nochannel (Paper cannot set modded biomes) |
| `inv` (round 2) | the open-inventory view: 36 items per page staged into the viewer's REAL inventory slots on both legs (truth: real modded items via `item replace`; ours: the pack's CMD carriers), `E` opens, `ESC` closes, `inv-diff.py` joins full-frame + per-slot crops | the GUI items channel proof; the crop geometry is scale-3 GUI math, FIRST-RUN-UNMEASURED (parameters in the script) |

The flip law: a class moves out of `coverage-gate.py`'s `DIAGNOSTIC_CLASSES`
once its channel is measured stable — from then on its absences are RED,
like every gate-hard class.

Round-2 status of the diagnostic joins: **sound/gui/held/anim/biome/inv
compare on EVERY run from now on** (the joins run in every gallery run's
inline step, not just on demand); each gate flips per the calibration law —
after its first measured run has pinned the channel's thresholds
(sound stream shape, gui open-rate, held crop, anim delta datum, biome
tint delta, inv GUI crop geometry).

## Tier 2 — third-party (nightly matrix, M7; Tier 0 is the pulled-forward core)

Pinned versions, downloaded by CI into `mods/` next to `cimtest`
(`scripts/cimscene/mods.lock`):

| Mod | Why | Known upstream gap (docs/F2V_ANALYSIS.md §3) |
|---|---|---|
| JEI | item overlay/search/bookmarks server-authoritative fallbacks | recipe transfer, bookmarks persistence |
| Jade | HUD data providers, plugin discovery | runtime overlay behavior |
| AE2 | registries, custom menus, networking breadth | terminals, crafting sync |
| Mekanism | capabilities, particles, fluids, GUIs | machine actions, chemical presentation |
| Sophisticated Backpacks | menus, upgrades, companion-jar (jar-in-jar) loading | menu rendering |
| JourneyMap | client/server split, dynamic textures | map/network behavior |

Acceptance per mod (M7): loads, registries land, no native/JVM crash, top-3 core
features function in the layer, visual diff within contract threshold.

### Round 2 changes (branch `ci/full-content-coverage`, 2026-11-04)

- **Particles — the programmatic spawn ladder**: the walk no longer goes
  through the `/particle` console surface (whose ARGUMENT parser rejected
  every modded particle whose options are not string-constructible —
  measured #30, all 13 create particles). The scene spawns server-side via
  `ServerLevel.sendParticles` with options built programmatically: a
  SimpleParticleType IS its own options; a data-carrying type's options are
  decoded from an empty compound through the type's own codec. Whether a
  specific modded codec accepts empty data is FIRST-RUN-measured; only a
  particle whose programmatic spawn itself fails keeps the
  `no-simple-options:<reason>` note.
- **Fluids — the multi-surface ladder**: every fluid gets a manifest row
  and an attempted surface, in order: (a) placeable liquid in the sealed
  glass case, (b) the bucket item rig, (c) the mod's own `fluid_tank` block
  (placed AND its contents filled through NeoForge's BLOCK fluid
  capability — round 3b; zero create imports, the note records the
  accepted amount). Placeable liquid rows are re-read after at least two
  native fluid tick delays, retaining genuinely settled air states. Their
  native LiquidBlockRenderer capture uses the sealed-case neighbors,
  per-axis chunk-origin normalization, native directional shade, fluid tint
  including alpha, and the registered chunk material. The replay resolves
  the exact captured mesh rather than an approximate fluid cube. The
  optional 40th quad column names the homogeneous model material; legacy
  23/35/39-column meshes retain their original defaults. Unsupported or
  mixed explicit materials fail the pack build. Settled tank BE data is saved beside the manifest;
  native model/BER capture supplies a per-cell mesh including its contents,
  and ours resolves that exact mesh (a missing capture remains a failure).
  Pixel and coverage gates remain hard. (d) the
  texture-model surface — the pack builder synthesizes a flat item model
  from the fluid's still texture (`textures/fluid/*.png`, 400000+i CMD
  space); truth has no native surface there (the join compares honestly,
  dyn class). A fluid with NO renderable surface on either side keeps the
  honest-RED note `no-truth-surface:<reason>`.
- **The `inv` class** (see the table above): the open-inventory view on
  both legs.

### Tier 2 CI wiring status (ON MAIN, 2026-11-05)

`mod-matrix.yml` `tier2-truth` matrix legs run on every push — each boots a
real NeoForge 21.1.265 server + the locked mod and asserts the mod's
namespace lands census rows. Green on main since the guideme companion
fix (`fa70f92f`: ae2 requires guideme 21.1.1+ without shipping it —
measured FML crash 41896212806):

| Mod | CI status (main) | Note |
|---|---|---|
| AE2 | tier2-truth census leg, GREEN | rides guideme (pinned 21.1.22); server_side=required per Modrinth |
| Mekanism | tier2-truth census leg, GREEN | server_side=required per Modrinth |
| Sophisticated Backpacks | tier2-truth census leg, GREEN | rides its REQUIRED companion sophisticated-core (checked in the jar); server_side=required per Modrinth |
| Jade | tier2-truth boot leg, assert-rows=no | a HUD provider registers no content — the rung proves boot + census file, honestly not rows |
| JEI | NOT wired — excluded from the server census | Modrinth metadata: server_side=optional, ships no server content (nothing to census) |
| JourneyMap | NOT wired — excluded from the server census | Modrinth metadata: server_side=optional, ships no server content (nothing to census) |

## Testing loop (project law)

1. Land feature/mod change → full CI matrix must be green.
2. Any red (unit, integration, profiling, visual) → fix before advancing.
3. Bug found in one subsystem while testing another → same rule: fix now, re-run all.
4. Every fix ships with the test that would have caught it.
5. Visual parity: modded view on vanilla client vs NeoForge ground truth, screenshot
   diff (M5) — "looks right" is a CI assertion, not an opinion.
6. Real-mod law (2026-10-29): first-party rigs are scaffolding, real mods are the
   proof (Tier 0 first — Create top priority). Every check is dual-stack: NeoForge
   client screenshot + profile vs c-imagine vanilla-client screenshot + profile,
   judged by numbers AND by eye. CI budget is unrestricted (owner, 2026-10-29).
