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

## M3 — State bridge v1

- Block read/write, entity projection, scheduler riding Paper's tick.
- cimtest TestBlock becomes placeable/breakable in the Paper world with state visible
  to both worlds (Paper API + layer API read the same block).

**Accept:** integration scenario: place via layer → Paper API sees it; break via
Paper → layer event fires; zero orphan state after restart (world reopened cleanly).

## M4 — F2V v2 packet translation v1

- Net hooks: outbound entity spawn/metadata/destroy, block update, chunk-batch
  rewriting modded ids → vanilla stand-ins; registry-driven mapping tables.
- Vanilla client (protocol bot, `cim-proto` based) joins and observes cimtest entity
  as a mapped vanilla type with correct metadata.

**Accept:** protocol bot asserts observed entity type/metadata; no client kicks for
unknown ids; profiling budget tightened (RSS ≤ modpack×1.15 gate enabled).

## M5 — Visual ground truth (real client harness)

- `cim-visual-harness`: headless vanilla client under xvfb in CI, screenshot capture.
- Comparison pipeline: NeoForge 1.21.1 render (ground truth) vs c-imagine view —
  pixel-diff report artifact, threshold gate; per-mod visual contracts from the modpack.

**Accept:** visual.yml uploads diff report; cimtest block+entity within threshold.

## M6 — Resource pack synthesis (shapes, not cubes)

- PackBuilder port: model/texture extraction from mod jars, OBJ support, server
  resource-pack push, connected-texture basics.

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
