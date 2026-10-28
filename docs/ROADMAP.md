# c-imagine roadmap

Each milestone has acceptance criteria enforced by CI. The loop is strict:
**analyze → implement → test (CI matrix) → analyze → fix**; nothing advances while
the matrix is red.

## M0 — Foundation (this repository)

- [x] Repo `c-imagine`, EN language, conventional commits, noreply author.
- [x] Architecture docs + F2V analysis + modpack plan.
- [x] Workspace: cim-core, cim-bus, cim-proto, cim-modscan, cim-registry,
      cim-bridge, cim-f2v, imagine (CRUSSTY module skeleton).
- [x] Java bootstrap (child-first ShadowClassLoader) + Paper plugin skeleton.
- [x] First-party test mod `cimtest` (NeoForge 1.21.1, ModDevGradle).
- [x] CI: ci / integration / profiling / visual workflows.

**Accept:** integration.yml boots real Paper 26.3 with the module and the test mod
and asserts discovery + markers; profiling gate green; all unit tests green.

## M1 — Embedded vanilla 1.21.1 layer boots (shadow namespace)

- [x] Relocation stage: `cim-reloc` (relocation tables + full class-file rewriter:
      constant pool **and** member declaration descriptors; appended-UTF8 strategy
      keeps annotation/signature strings raw; fixture-tested + real-class smoke test).
- [x] Relocation runtime: JNI bridge (`NativeReloc`), shadow loader (hermetic,
      central-dir index, MR-JAR-aware, `legacyClassPath` ordering), layer config
      (`crus/c-imagine.json`), module lib discovery (`/proc/self/maps`).
- [x] Relocated MC 1.21.1 + NeoForge 21.1.265 installed by CI (actions cache,
      content-addressed by version), booted headless inside Paper JVM.
- [x] Logical server construct: real `Bootstrap.bootStrap()` runs in the shadow
      space; registry census snapshot reported (`vanilla registries: 78` locally).
- [x] Upstream ground-truth jobs: `ground-truth.yml` — vanilla server class census
      (26.3 + 1.21.1, diffed; verified locally: 7436 vs 6148 classes) and the
      dispatch-only 26.3 client decompile (official mappings → tiny v2 →
      tiny-remapper → Vineflower).
- [ ] M1 minimal FML statics (`FmlStateShim`) replaced by the real FML discovery
      pipeline inside the shadow space (first M2 task).

**Accept:** integration log shows `[c-imagine] layer boot ok (vanilla registries: N)`
(N ≥ 70 asserted in CI); RSS delta within M1 budget.

## M2 — Mod execution: real FML hosting (DONE — status 2026-10-26)

Implemented as **M2.0 real-FML-on-shadow** (design: worklog Task 3.1):

- Mod classes keep their ORIGINAL binary names (refs-only design) and resolve
  layer namespaces through the ShadowClassLoader reference-rewriting stage;
  the neoforge universal jar participates as a real (fully relocated) layer mod.
- Real FML 1.21.1 pipeline inside the shadow space: real mods.toml parsing
  (NightConfig), `ModFile`/`ModFileInfo`/`ModInfo`, `ModList` + `LoadingModList`,
  real JPMS `ModuleLayer` over the ShadowClassLoader, real `FMLModContainer`
  (constructor-injection rule + `AutomaticEventSubscriber` via native scan data),
  `NewRegistryEvent` → `GameData.unfreezeData/postRegisterEvents/freezeData`,
  FMLCommonSetup/FMLDedicatedServerSetup, game bus start.
- Real mods from Modrinth ride along: JEI 19.70 (incl. jarjar mezz_config),
  Jade 15.10.6, AppleSkin 3.0.9, FerriteCore 7.0.3, spark 1.10.124.
- Shadow-space slf4j→log4j2 stack scoped to the shadow loader (multi-jar
  `getResources` fix + packaged `log4j2.component.properties`), producing
  `[cim-layer]` lines with the first-party `[cimtest]` markers.

**Accept (all green on CI):** `m2-real-mods` job — 8 mods constructed, cimtest
registers `{ITEM=1, BLOCK=1, BLOCK_ENTITY_TYPE=1, ENTITY_TYPE=1}` through the real
`RegisterEvent` pipeline (~0.7–0.9 s); live `boot` job — the same mod set inside
the Paper 26.3 JVM via CRUSSTY; `parity-real-neoforge` job — a REAL NeoForge
21.1.265 server boots the identical mod set and prints the same `[cimtest]`
markers (cross-boot parity contract). Remaining M2.1 toggle: runtime-visible
annotation descriptors.

## M3 — State bridge v1 (DONE — M3.1–M3.4, 2026-10-26)

- [x] **M3.1 facade** — block read/write through Paper: vanilla states round-trip;
      modded ids recorded in a sidecar with a `structure_void` stand-in in the
      Paper world; break via Paper observed by the layer. Proven live on CI
      (`bridge smoke: ALL OK`).
- [x] **M3.2 state-translation tables** — `scripts/gen-remap-tables.py` pins
      mcmeta registries+summary snapshots (1.21.1 `0eaeabd2`, 26.3 `2240df23`)
      and emits drift TSVs consumed byte-identically by the Java
      `dev.cim.bridge.StateMapper` and the Rust `cim-registry::drift` mirror.
      Measured 1.21.1->26.3 drift (release-pinned): blocks 1060->1286 with ONE
      rename (chain->iron_chain) and one value-set growth (note_block
      instrument +trumpet*), attributes flattened (generic./player. -> bare),
      entity_type boat/chest_boat split per wood (default oak), thrown potion
      entity -> splash_potion, fluid registry UNCHANGED (5->5 — the earlier
      "fluid shrink" was a measurement against an unpinned snapshot).
      Live-boot legs assert chain->iron_chain + note_block through Paper.
- [x] **M3.3 persistent sidecar** — `dev.cim.bridge.SidecarStore`: write-through
      TSV journal at `<cwd>/c-imagine/sidecar.tsv`, last-wins replay, torn-tail
      tolerant, tombstone erases; cull-on-read when a Paper stand-in vanished
      (self-healing, zero orphan state); restore census logged per boot. CI
      boots the server TWICE and asserts `sidecar restore: cimtest:test_block @
      ... (stand-in intact)` + `1 live, 0 culled` on the second boot.
- [x] **M3.4 entity projection** — layer entity spawn/list/remove projected as
      Paper entities with entity_type remap through the M3.2 registry tables
      (1.21.1 boat -> 26.3 oak_boat, pig identity). CI-proven live:
      `boat split ok` (uuid-channel + box-scan, polls=1) + `identity ok`;
      paper-api 26.3 surfaces pinned by javap (`setChunkForceLoaded` —
      `setForceLoaded` removed). Entity legs own a force ticket around the
      chunk: spawn chunks without players are block-ticking only (1.20.5+
      ticket semantics), spawned entities stay untracked otherwise.

**Accept:** integration scenario: place via layer -> Paper API sees it; break
via Paper -> layer event fires; restart -> sidecar restore with zero orphan
state (second boot asserts restore markers).

## M4 — F2V v2 packet translation v1

- Net hooks: outbound entity spawn/metadata/destroy, block update, chunk-batch
  rewriting modded ids → vanilla stand-ins; registry-driven mapping tables.
- [x] **Protocol bot (cim-proto/cimbot) joins the live boot and observes the
  contract — DONE 2026-10-27 (integration GREEN, all 3 jobs)**: full 777
  login→config→play, projected oak_boat add_entity + glowing metadata +
  remove_entities, post_effects apply `[minecraft:creeper]` + clear `[]`
  (the F2V 2.0 channel proven end-to-end), drift block proven at netty level
  (`BlockPos{x=0,y=100,z=2} minecraft:iron_chain` ×2 outbound), zero
  modded-id leaks (`modded_blocked=0`), zero kicks across 1995+ packets.
  Bot censuses unknown ids → live wire-table ground truth (7 ids confirmed
  by live parse; see `runtime-analysis/protocol-767-777.md` §9-§10).
- Owner directive (supplemented 2026-10-26, measured in
  `runtime-analysis/f2v2-client-capabilities.md`): 26.3 carries a native
  server→client post-effect channel (`ClientboundPostEffectsPacket` +
  `post_effect` registry, 5 vanilla chains). Packet translation must map this
  family 767↔777 and expose a post-effect control surface to the F2F layer
  (implementation deferred to M4/M5 as reached).

**Accept:** protocol bot asserts observed entity type/metadata; no client kicks for
unknown ids; profiling budget tightened (RSS ≤ modpack×1.15 gate enabled).

## M5 — Visual ground truth (real client harness) + F2V 2.0 visual direction

- `cim-visual-harness`: headless vanilla client under xvfb in CI, screenshot capture.
- Comparison pipeline: NeoForge 1.21.1 render (ground truth) vs c-imagine view —
  pixel-diff report artifact, threshold gate; per-mod visual contracts from the modpack.
- [x] **M5.2 item visual parity v1 (pulled forward from M6, 2026-10-27)**: the
      stock-client rendering channel — `dev.cim.f2v.F2vPack` builds a 26.3
      resource pack from mod-jar assets (measured surface:
      `f2v2-pack-synthesis-26-3.md`): `items/<host>.json` range_dispatch over the
      free `custom_model_data` hook (pack format 97.1 from the client's own
      version.json), modded model graphs + auto-atlased textures copied at
      original paths, deterministic zip (sha1 asserted stable by CI and by the
      plugin's boot-time rebuild). Delivery rides the CONFIG-phase
      `resource_pack_push` (id 9) via server.properties + loopback `PackHttp`;
      cimbot walks the real vanilla accept state machine (ACCEPTED →
      SUCCESSFULLY_LOADED, ServerboundResourcePackPacket ordinals measured); the
      give leg hands out the host item + CustomModelDataComponent (javap-verified
      paper-api 26.3 surface) and verifies the inventory. integration.yml asserts
      built + deterministic + served + accepted + loaded + inventory-verified.
- **F2V 2.0 visual direction (owner vision, partially reached — M5.2 done)**: modded
  content rendered on vanilla 26.3 clients via the measured 26.3 surface —
  post-effect chains requested server-side (`ClientboundPostEffectsPacket`, M4
  bot leg proven) for atmosphere/overlays, ItemsAdder/Oraxen-style item rendering
  through the registry-based `items/` definitions (`range_dispatch`/`select` on free
  `custom_model_data`) — **v1 implemented (M5.2)**, `equipment/` assets for armor,
  palette-driven recolors still M6. Original F2V (jefthebuilder/F2V, 1.21.1-era,
  read-only reference) mapped to legacy `models/item` overrides — the 26.3
  registry surface is strictly cleaner.
- **Owner directive 2026-10-28 ("identical to the modded client")** locked as the
  channel-by-channel parity contract: `runtime-analysis/f2f2-parity-matrix.md`
  (measured v1 surface incl. CMD interval scheme + display-entity rigs → 26.3
  replacements → status → per-channel pixel proof). Harness join path fixed by
  bytecode autopsy (`runtime-analysis/visual-harness-join.md`): quickPlay reads
  servers.dat (sync, RAW NBT), `acceptTextures`=1 → ENABLED → pack auto-accept.

- [x] **M5.3 harness GREEN — the stock client renders modded content
      (visual run 10, 2026-10-28, all pixel gates measured)**: nine-shot
      contract on a REAL vanilla 26.3 client under xvfb — join via
      bytecode-proven pre-conditions (onboardAccessibility:false + servers.dat
      acceptTextures:1 + explicit --width/--height), config-phase pack
      auto-accept + download + apply (the SECOND reload line), and five
      measured pixel gates: AE(baseline,item)=175834,
      AE(vanilla-paper,cmd-paper)=182043 (range_dispatch cube IN HAND AND
      HOTBAR), AE(cmd,postfx-on)=842107 (the pack-contributed cimsepia chain
      tints the whole frame — the atmosphere channel resolves client-side),
      AE(plain-brick,cmd-brick)=177483 (select dispatch on CMD strings — the
      wand), AE(thirdperson,armor)=92187 (F5 third-person + equippable helmet
      — equipment asset resolves). Harness docs:
      `runtime-analysis/visual-harness-join.md`.
- **M5.4 display-entity leg (entities channel)**: item_display rig with a CMD
  stack — plugin spawn + cimbot `--expect-extra-entity` wire check, integration
  GREEN (f137768). PIXEL leg added run 43/44: the rig parks in the sky
  (feet+2.5 — measured: feet+0.4 is swallowed by the block silhouette) and
  the gate subtracts cloud drift via a hold shot (rig-pixels =
  AE(10,11) - AE(11,12) > 3000).
- **Block channel (parity matrix "blocks") — run 43 GREEN first-dispatch**:
  `docs/runtime-analysis/block-channel-26-3.md` (javap-measured):
  structure_void/barrier are `RenderShape.INVISIBLE` in CODE (pack blockstate
  overrides cannot make them render) — the measured stand-in is
  `note_block[instrument=I,note=N]` (MODEL-rendered full cube, 27x25=1350
  slots, `VariantSelector` partial keys are wildcards, graceful vanilla
  degradation). F2vPack emits the merged `blockstates/note_block.json`
  override; `/cim placeblock` places the stand-in 3 blocks ahead (paper-api
  NoteBlock javap-proven). Run 43: AE(block-pre,block)=79211 (gate 3000) —
  the modded iron cube renders in-world on the stock client.
- **parity-diff (the "identical to the modded client" crown) — RUN 4 GREEN,
  identity datum measured (2026-10-28)**: `parity-diff.yml` — job 1 boots a
  REAL NeoForge 21.1.265 server + the REAL NeoForge 1.21.1 client (cimtest
  installed in gameDir/mods) under xvfb (launch contract decompiled from
  BootstrapLauncher; the OFFICIAL-LAUNCHER COMPOSITION lesson: universal/client
  jars ride ONLY libraryDirectory — cp carries just the version-json
  libraries); job 2 pixel-diffs the hotbar slot-0 icon crop (48x48 at scale 3)
  and the block region against the c-imagine run. Run 4 measured:
  AE(icon identity: truth vs c-imagine) = **2300** (48x48 = 2304 px — the
  stock client renders the modded item identically), with symmetric
  appearance deltas (AE 1532 both sides); block region 50281 (ungated,
  camera-alignment sensitive).
- **M6.1 HUD/dialog channel — wire-proven (integration green)**: the 26.3
  dialog registry as the native replacement for v1's ClientOnlyHudSimulator
  (contract: `runtime-analysis/dialog-hud-26-3.md`): CimPlugin builds an
  inlined `Dialog.create` (multi_action + ItemDialogBody rendering the modded
  cube stack + dynamic/custom click id), `Audience.showDialog` sends the
  COMMON-phase `ClientboundShowDialogPacket`; cimbot observes show_dialog
  (live play ids 142/143 — the documented +1 clientbound drift) and answers
  serverbound custom_click (id 68; absent NBT = single 0x00 EndTag); the
  plugin's `PlayerCustomClickEvent` closes the round-trip. Palette channel
  measured (`paletted_permutations` = atlas source in 26.3, standalone file
  format removed — f2v2-pack-synthesis-26-3.md §12).
- **M6 pull-forward (armor channel)**: equipment/<ns>/<id>.json emitted from
  LEGACY 1.21.1 armor texture locations (textures/models/armor/_layer_N.png ->
  entity/equipment/humanoid[_leggings]/ — path-only formula measured in
  `EquipmentClientInfo$Layer`), manifest `visual-equipment.tsv`, equippable
  helmet legs (wire GREEN + pixel GREEN; texture-path fix 4f7d5f9 rides run 11).
- **Camera-lock block identity rig (2026-10-29, the "identical" frontier)**:
  run 4's ungated block region (50281) was camera-pose-polluted; the measured
  rig (`runtime-analysis/camera-lock-26-3.md`) kills all three pollutants —
  server `tp` with explicit yaw/pitch into a FIXED cell, absolute `^-1 ^ ^3`
  floor cell, scene sweep, F1 (hideGui), noon/clear pins on BOTH stacks.
  visual.yml legs 13/14 + parity-diff.yml t03/t04: appearance gates live,
  THE BLOCK IDENTITY DATUM ungated until the first locked run calibrates it.

**Accept:** visual.yml pixel contract GREEN (done — run 10); cimtest
block-in-world leg + the NeoForge 1.21.1 ground-truth DIFF (render the same
content on a modded client and diff against c-imagine's view) stay the M6
remainder; M5.2 wire/server contract asserted green in integration.yml.

## M5.5 — Real-mod parity program (owner directive 2026-10-29 — pulled forward from M7)

**The directive:** first-party rigs (cimtest) remain scaffolding but are no longer
the proof surface — a self-built rig can unconsciously encode c-imagine-friendly
assumptions that diverge on real NeoForge. Testing moves NOW to real mods, and
every comparison is dual-stack with a REAL NeoForge client: boot the modded client
(screenshot + profile everything), then run the same content through c-imagine on
the vanilla client, and compare — by profile numbers AND by eye. Per mod, per
block, per sound, per particle: everything a mod ships gets an automated scene on
BOTH stacks. **Create mod 100% identical is the single top priority.** CI budget
is not a constraint (owner, 2026-10-29) — runs are no longer rationed.

- **Priority ladder (Tier 0 in `MODPACK.md`):**
  - **P0 — Create**: 100% identical target (kinetic network, contraptions,
    fluids, particles, sounds, UIs) — the flagship gate.
  - **P1 — Biomes O' Plenty** (block palette + biome-tint channels at scale),
    **Dynamic Trees** (block entities + incremental growth ticks),
    **Combat Roll** (client animation/sound surface — smallest full-ladder mod).
  - **P2 — the full Aged modpack** (version pinned from its manifest when CI
    wiring lands) — the "every mod supported" end-state check.
- **Dual-stack comparison contract (extends `parity-diff.yml`)**: truth leg =
  real NeoForge 21.1.265 server + real NeoForge 1.21.1 client (existing
  truth-job pattern); ours leg = c-imagine Paper 26.3 + vanilla 26.3 client
  (stock-client harness). Both legs run the SAME scene script from `cimscene`
  and emit per-scene screenshots + per-scene profiler spans + `[cimscene]`
  markers; the diff job joins truth vs ours per scene id: pixel datums
  (PixelDiffNorm — the calibrated identity metric) + profile deltas, and BOTH
  screenshot sets ship in the run summary for the owner's eye review —
  "looks identical" is signed off by a human on every channel, not inferred
  from gates alone. Design: `runtime-analysis/mod-parity-harness.md`.
- **Scene harness `cimscene`** (first-party NeoForge 1.21.1 mod, deliberately an
  ORDINARY mod — zero c-imagine APIs — so it loads identically on both stacks
  and cannot absorb the blame): void world, pinned noon/clear, bot player locked
  to the fixed camera cell + yaw/pitch (camera-lock rig), then a census-driven
  sweep: enumerate the target mod's registries (blocks → items → entities →
  particles → sounds → block entities) and emit one deterministic scene per
  entry in front of the camera. Coverage artifact = registered vs exercised per
  registry per mod. Sound is proven wire-level (cimbot
  ClientboundSoundPacket census) since screenshots cannot hear.

**Accept:** Create scene sweep green at the calibrated identity thresholds on the
camera-locked rig on every Create channel (the 100%-identical target, measured by
the documented cross-version metric); BOP / Dynamic Trees / Combat Roll sweeps
green per-registry; Aged pack boots + first census + first sweep; every run ships
both screenshot sets for eye comparison and per-scene debug bundles for reds.

## M6 — Resource pack synthesis (shapes, not cubes)

- PackBuilder port: model/texture extraction from mod jars, OBJ support, server
  resource-pack push, connected-texture basics.
- Emit the registry-based 26.3 surface (per `runtime-analysis/f2v2-client-capabilities.md` §8):
  `items/<id>.json` definitions + atlas directory sources, `equipment/<id>.json`,
  `font` composition (`reference`/`include/*`), `paletted_permutations` +
  `textures/palettes`, `post_effect` chains riding existing pass/program vocabulary;
  pack format 97.1.

**Accept:** visual diff improves for cimtest custom model; pack size capped; hot reload.

## M7 — API breadth sprint (toward "any mod")

- Capabilities/data components marshalling, network channels (mod↔mod), commands,
  configs (TomlFeed subset), JEI-style informational mods.
- Modpack matrix (MODPACK.md) runs nightly against the real mods; failures become
  targeted issues. **Pulled forward 2026-10-29**: the Tier 0 parity ladder
  (Create/BOP/Dynamic Trees/Combat Roll/Aged) is now M5.5 — real-mod parity is
  the active program, not an M7 TODO.

**Accept:** ≥ 5 real third-party mods load + run basic loops in CI.

## M8 — Compat escape hatches

- Child-JVM `compat` mode for JVMTI-attaching / namespace-hostile mods.
- Name-string reflection shims, coremod/mixin edge-case policies.

## M9 — Performance floor lock-down

- Budget: boot ≤ 10%, idle CPU ≤ 1% core, RSS ≤ modpack×1.05; allocation-free
  steady-state bridge; flamegraph regression tracking per release tag.

Ongoing at every milestone: the test→fix loop across the whole matrix, with the
profiling report driving optimization of whatever hurts most.
