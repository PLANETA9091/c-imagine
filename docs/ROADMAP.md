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

- Relocation stage over the class-load hook pipeline (relocation tables in Rust,
  ASM transform on the Java side).
- Relocated MC 1.21.1 + NeoForge 21.1.x downloaded/installed by CI (content-addressed
  cache), booted headless inside Paper JVM.
- Logical server constructs without a tick thread; registries snapshot into cim-registry.
- Upstream ground-truth jobs (per `docs/runtime-analysis/`): decompile vanilla 26.3
  client (official mappings + Vineflower) and census the 26.3 server classes; results
  cached as per-version artifacts — F2V v2 and the remap tables cite these, not folklore.

**Accept:** integration log shows `[c-imagine] layer boot ok (vanilla registries: N)`;
RSS delta within M1 budget.

## M2 — Mod execution: registries + events

- `mods/` jars relocated into `cim.shadow.mods.<modid>.*` and loaded by
  ShadowClassLoader; `@Mod` entrypoints constructed; `cim-bus` wired to NeoForge
  event surfaces (game bus subset): registry events, server lifecycle, tick, login.
- cimtest mod registers block/item/BE/entity **through the layer** and logs.

**Accept:** integration asserts cimtest registration markers + `/cim status` reports
loaded mods; watchdog (JVMTI heap sampling) reports layer usage in step summary.

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
