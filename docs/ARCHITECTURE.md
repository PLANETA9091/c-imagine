# c-imagine architecture

Status: living document, M0 baseline.

## 1. Problem statement

Run **any NeoForge 1.21.1 mod** on a **Paper 26.3** server with:

1. **No second-server economics** — no duplicated heap, no duplicated world state,
   no second tick loop, no meaningful RSS/CPU overhead versus what the same mods
   would cost on a plain NeoForge 1.21.1 server.
2. **Full mod compatibility** as the north star — mods are real NeoForge mods, loaded
   from `mods/`, unchanged.
3. **Vanilla-client visibility** — clients on Paper 26.3 (vanilla protocol) must see
   modded blocks, entities, GUIs and effects through translation, not crashes.

The direction matters: this is *not* "run Paper plugins on NeoForge". It is
"run NeoForge mods on Paper", with Paper as the unchallenged kernel.

## 2. Non-goals (M0)

- Performance parity *claims* without measurement — the budget is enforced, not assumed.
- Client-side mod installation: F2V v2 is server-side; vanilla clients install nothing.
- Running NeoForge *itself* unmodified as a plugin container: we re-implement the
  NeoForge surface our loaders need and delegate the rest to the embedded 1.21.1 layer.

## 3. Composition: CRUSSTY as the engine

c-imagine is a **CRUSSTY module** (`modules/imagine/`, `module.json` → `libimagine.so`,
built by `crates/imagine`). We deliberately inherit, rather than rebuild:

| CRUSSTY primitive | Use in c-imagine |
|---|---|
| JVMTI agent, loads modules **before** kernel classes | bootstrap insertion point |
| `ClassFileLoadHook` hot-patch pipeline | shadow-namespace relocation + layer weaving |
| `on_kernel_ready` / main-thread queue | boot sequencing against Paper lifecycle |
| platform bricks: events, scheduler, telemetry, storage | substrate for cim-bus + watchdog |
| **net packet hooks** (Pass/Drop/Disconnect verdicts) | F2V v2 outbound packet rewriting |
| module ownership/reload isolation | safe hot-reload story for the module itself |

The module exports the single ABI function `cplugin_init(const CPluginApi*, JavaVM*, const char* options)`
and returns `0`. Everything else is raw JVM access — no artificial limits, matching the
"no boilerplate" contract of cplug-abi CPAPI v3.

## 4. The embedded 1.21.1 layer (mini-NeoForge)

### 4.1 Why in-process, not a child JVM

A child JVM (separate NeoForge 1.21.1 process) is the easy route and the expensive one:
double heap, double GC, IPC for every state read. The requirement "не жрало как будто
запущено 2 сервера" excludes it as the default. In-process classloader isolation inside
the Paper JVM is chosen:

- one heap: modded objects live in the same GC-managed heap, sized by the existing
  Paper memory settings; no second Xmx to budget;
- one scheduler: the layer's ticks are dispatched onto Paper's main thread (via
  CRUSSTY scheduler brick / main-thread queue), so no second tick loop thread;
- one state: Paper owns chunks/entities; the layer is a **facade** over that state.

Fallback: `compat` child-JVM mode stays on the roadmap (M8) for mods that cannot
tolerate shared-heap constraints, but the default is in-process.

### 4.2 Shadow namespace relocation (the core trick)

Paper 26.3 and Minecraft 1.21.1 both ship `net.minecraft.**` classes. Loading both in
one JVM collides. The layer therefore loads a **relocated copy** of the entire
1.21.1 stack — Minecraft 1.21.1 server, NeoForge 21.1.x, and every mod jar — through
CRUSSTY's class-load hook pipeline:

```
raw class bytes (1.21.1 stack or mod jar)
        │
        ▼
relocation stage (bytecode transform, ASM on the Java side,
driven by Rust-side mapping tables)
  net.minecraft.**         → cim.shadow.mc1211.**
  com.mojang.** (conflict) → cim.shadow.mojang.**
  net.neoforged.**         → cim.shadow.neoforge.**
  <mod package>.**         → cim.shadow.mods.<modid>.**
        │
        ▼
ShadowClassLoader (child-first, parent = bootstrap only)
        │
        ▼
layer classes live in cim.shadow.*, invisible to Paper classes
and vice versa
```

Because NeoForge 1.21.1 runs mods against **Mojang official mappings** at runtime, and
Paper 26.3 also runs Mojmap-named classes, the relocation is a pure mechanical rename:
descriptors, inheritance links, annotations, refmaps and coremod targets are all
rewritten by the same pass. Mods never see Paper's classes; Paper never sees layer
classes. Cross-boundary calls happen only through the bridge (§4.4) with explicit
data marshalling.

Known limits (tracked): bytecode that reflects over its own class *name strings*
needs a name-mapping shim (`cim.shadow.ClassNames.decode`); agents that attach JVMTI
themselves are unsupported in-process and get the M8 child-JVM mode.

### 4.3 Boot sequence

```
crussty launcher (-agentpath libcrussty_runtime.so)
 └─ runtime: claims JVMTI capabilities, scans modules/
     └─ imagine.cplugin_init(api, vm, options)          [before kernel classes]
         ├─ cplug_sdk::init, register class hooks       (relocation stage arms here)
         ├─ spawn loader thread:
         │    ├─ write embedded cim-bootstrap.jar, URLClassLoader it
         │    ├─ Bootstrap.init(opts) — ShadowClassLoader online
         │    └─ scan mods/ → cim-modscan → load order → report
         └─ return 0 (module stays resident)
 JVM continues booting Paper 26.3
     └─ org.bukkit.Bukkit defined → on_kernel_ready
         ├─ Paper plugin c-imagine enables (bridge lifecycle)
         └─ layer boot: relocated MC 1.21.1 "logical server" constructs
             (headless; no port, no EULA, no tick thread of its own)
```

### 4.4 State bridge

The layer never stores authoritative state. It translates:

| Layer call | Bridge action |
|---|---|
| `level.getBlockState(pos)` | Paper chunk query, block-state remap 26.3→1.21.1 |
| `level.setBlock(pos, st, flags)` | Paper set-block, remap 1.21.1→26.3, flag semantics mapped |
| entity iteration / spawn | Paper entity list projected as layer `Entity` proxies |
| registries | snapshot from Paper + modded ids from `cim-registry` allocator |
| capabilities/data components | marshalled through `cim-bridge` ring as typed frames |

Transport: `cim-bridge` SPSC byte ring (u32-length-prefixed frames, cache-padded
head/tail, zero allocation in steady state) between Paper threads and the loader
thread. The ring is the only shared mutable memory; everything else is message-passing.
This keeps the GC of both worlds decoupled despite the shared heap.

### 4.5 Registry model

- Vanilla 1.21.1 registry contents: ids are the layer's own (relocated) vanilla ids.
- Modded registrations allocate from `MODDED_ID_BASE` (1_000_000_000) upward per
  registry type (`cim-registry::IdAllocator`) — disjoint from any vanilla space,
  collision-free by construction.
- A bidirectional remap table (`RemapTable`) connects layer ids ↔ Paper 26.3 ids for
  shared registries; snapshots + diffs (`SnapshotDiff`) detect drift on reloads and
  drive F2V translation tables.

### 4.6 Memory & CPU budget (enforced, not aspirational)

| Metric | Budget (CI gate, `profiling.yml`) |
|---|---|
| Boot time delta vs bare Paper | ≤ 25% (relaxed until M3; tightened each milestone) |
| Steady RSS delta, no mods loaded | ≤ 400 MiB absolute gate at M0; target ≤ 3% at M4+ |
| Steady RSS delta, modpack loaded | ≤ modpack's own NeoForge RSS × 1.15 |
| Steady CPU delta, idle | ≤ 2% of one core |
| Native codec paths | criterion baseline: no regression > 10% vs cached baseline |

The module ships a JVMTI-based watchdog (M2): heap sampling of the layer's classloader
domain, GC-pressure hints, and hard aborts when the budget is breached — the "не жрёт"
guarantee is runtime-enforced, not only CI-enforced.

## 5. F2V v2 — visual translation (Rust rewrite)

Original F2V (Java, private repo `jefthebuilder/F2V`, 718 files / ~226k lines) proved
that vanilla clients can render modded content. v2 ports the *pipeline* into
`cim-f2v` and moves execution from inside the modded server to inside Paper:

1. **Discovery** (server-side, static): the original `ClientSurfaceScanner` /
   `RendererBytecodeScanner` bytecode analysis becomes a Rust pipeline built on
   `cim-modscan`'s classfile parser (constant-pool + annotation level first; the
   stack-simulation analysis follows in M4+).
2. **Registry & id projection**: modded entities/blocks/items get vanilla-protocol
   representations via `cim-registry` snapshots + F2V mapping tables.
3. **Packet rewriting**: CRUSSTY net hooks intercept outbound packets; spawn/metadata/
   chunk/blocks-update packets carrying modded ids are rewritten to their vanilla
   stand-ins before leaving the server (`NET_DROP`+inject or in-place rewrite).
4. **Resource pack synthesis**: model/texture extraction (PackBuilder port) generates
   a server-sent resource pack so vanilla clients render modded *shapes*, not just
   stand-in cubes.
5. **GUI scenes**: server-authored HUD surfaces (ClientOnlyHudSimulator port) for
   modded menus.

Milestone mapping is in `ROADMAP.md`; the subsystem-by-subsystem port matrix is in
`F2V_ANALYSIS.md`.

## 6. Testing strategy (CI-first)

- **Unit**: every crate (`cargo test --workspace`), including a hand-assembled class
  file fixture for the bytecode parser — no JDK needed to test JVM bytecode logic.
- **Integration** (`integration.yml`): real Paper 26.3 downloaded by the CRUSSTY CLI,
  module installed, `cimtest` mod in `mods/`; asserts boot markers, mod discovery,
  clean logs, no crash reports.
- **Performance** (`profiling.yml`): criterion benches + bare-vs-modded boot/RSS gate
  with a step-summary report; nightly.
- **Visual** (`visual.yml`): M0 = protocol-level smoke of visual data; M5 = xvfb real
  client harness comparing modded-view screenshots against NeoForge-rendered ground
  truth (planned harness: `cim-visual-harness` driving a headless vanilla client).
- **Loop discipline**: bug found in one subsystem → fix → full matrix re-run; no
  forward progress on a red matrix (per project rule).

## 7. Decision log

| # | Decision | Rationale | Rejected alternative |
|---|---|---|---|
| D1 | CRUSSTY module, not standalone agent | inherit hooks/scheduler/net-pipeline, repo convention `c-<id>` | custom JVMTI agent (duplicates engine) |
| D2 | In-process layer via shadow relocation | single heap/thread/state | child JVM (double economics), fork Paper (unmaintainable) |
| D3 | Rust for orchestration/codec/registry, Java for bytecode transform + layer runtime | JVM bytecode tools (ASM) are JVM-native; Rust owns hot paths | all-Java (loses native speed & engine), all-Rust (no ASM) |
| D4 | Paper owns world state | no sync fights, no duplicate saves | mirrored worlds |
| D5 | modded ids from 1_000_000_000 | disjoint, no vanilla drift | interleaving with vanilla ids |
| D6 | vendored cplug-abi/cplug-sdk | CRUSSTY repo has no workspace manifest for git deps | git dependency (impossible), crates.io (unpublished) |
