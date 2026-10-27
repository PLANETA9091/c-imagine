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
- **F2V 2.0 visual direction (owner vision, deferred until reached)**: modded
  content rendered on vanilla 26.3 clients via the measured 26.3 surface —
  post-effect chains requested server-side (`ClientboundPostEffectsPacket`) for
  atmosphere/overlays, ItemsAdder/Oraxen-style item rendering through the
  registry-based `items/` definitions (`range_dispatch`/`select` on free
  `custom_model_data`), `equipment/` assets for armor, palette-driven recolors.
  Original F2V (jefthebuilder/F2V, 1.21.1-era, read-only reference) mapped to
  legacy `models/item` overrides — the 26.3 registry surface is strictly cleaner.

**Accept:** visual.yml uploads diff report; cimtest block+entity within threshold.

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
  targeted issues.

**Accept:** ≥ 5 real third-party mods load + run basic loops in CI.

## M8 — Compat escape hatches

- Child-JVM `compat` mode for JVMTI-attaching / namespace-hostile mods.
- Name-string reflection shims, coremod/mixin edge-case policies.

## M9 — Performance floor lock-down

- Budget: boot ≤ 10%, idle CPU ≤ 1% core, RSS ≤ modpack×1.05; allocation-free
  steady-state bridge; flamegraph regression tracking per release tag.

Ongoing at every milestone: the test→fix loop across the whole matrix, with the
profiling report driving optimization of whatever hurts most.
