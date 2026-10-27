# c-imagine

![ci](https://github.com/PLANETA9091/c-imagine/actions/workflows/ci.yml/badge.svg)
![integration](https://github.com/PLANETA9091/c-imagine/actions/workflows/integration.yml/badge.svg)
![profiling](https://github.com/PLANETA9091/c-imagine/actions/workflows/profiling.yml/badge.svg)
![visual](https://github.com/PLANETA9091/c-imagine/actions/workflows/visual.yml/badge.svg)

**Run NeoForge 1.21.1 mods inside Paper 26.3 — one process, one server, one world.**

`c-imagine` is a [CRUSSTY](https://github.com/PLANETA9091/CRUSSTY) native module that
embeds a *mini NeoForge 1.21.1* runtime into a **Paper 26.3** server. Paper stays the
kernel (authoritative world, ticks, network); the embedded layer executes real NeoForge
mods in an isolated class space while sharing the host JVM's heap, threads and scheduler.
Vanilla clients see modded content through **F2V v2** — the packet-level visual
translation layer rewritten in Rust as part of this module.

```
┌─────────────────────────────────────────────────────────────────┐
│                     Paper 26.3 (kernel, Java 25)                │
│                                                                 │
│   ┌────────────────────────── CRUSSTY runtime (JVMTI) ───────┐  │
│   │  modules/imagine — this module (Rust, cdylib)            │  │
│   │                                                          │  │
│   │  ┌───────────────┐  ┌──────────────┐  ┌──────────────┐   │  │
│   │  │ mod loader    │  │ mini-NeoForge│  │ F2V v2       │   │  │
│   │  │ mods/*.jar    │  │ 1.21.1 layer │  │ packet trans │   │  │
│   │  │ scan/sort/    │  │ (shadow ns,  │  │ modded→van.  │   │  │
│   │  │ load order    │  │  isolated)   │  │ (net hooks)  │   │  │
│   │  └───────┬───────┘  └──────┬───────┘  └──────┬───────┘   │  │
│   │          └─────────┬───────┴──────────────────┘           │  │
│   │             cim-bridge (zero-copy ring, native)           │  │
│   └──────────────────────────┬────────────────────────────────┘  │
│                              │ state bridge (registry/entity/bk) │
│   ┌──────────────────────────┴────────────────────────────────┐  │
│   │        Paper world state (single source of truth)         │  │
│   └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

## Why one process

Classic "hybrid" servers run two kernels side by side and pay twice: two heaps,
two tick loops, duplicated world state. `c-imagine` refuses that model:

| Resource | Hybrid servers | c-imagine |
|---|---|---|
| Heap | 2 JVM heaps | 1 shared heap, modded layer capped by budget |
| World state | duplicated, sync fights | Paper only; 1.21.1 layer is a facade |
| Threads | 2 schedulers | Paper scheduler; modded work scheduled onto it |
| Ticks | 2 tick loops | 1 loop; layer hooks ride the kernel tick |

The performance budget is enforced in CI: `profiling.yml` boots Paper bare and
Paper + `c-imagine`, compares boot time and resident memory, and fails the run
when the module exceeds its budget. Criterion benchmarks guard the hot native
paths (varint codec, registry remaps, bridge ring).

## Repository layout

```
crates/
  cim-core/       ids, ResourceLocation, versions, shared errors
  cim-bus/        event bus (priorities, cancellation) for the mini-NeoForge layer
  cim-proto/      Minecraft protocol primitives: varint, framing, compression
  cim-modscan/    jar scanner: neoforge.mods.toml, @Mod bytecode scan, jar-in-jar,
                  dependency graph + deterministic load order
  cim-registry/   registry snapshots, diffing, modded id allocation, 1.21.1↔26.3 remaps
  cim-bridge/     versioned native ABI + SPSC zero-copy ring (Paper plugin ↔ Rust)
  cim-f2v/        F2V v2: modded→vanilla visual translation tables and plans
  imagine/        the CRUSSTY module itself (cplugin_init, bootstrap loader,
                  mods/ watcher) — packaged as modules/imagine/
java/cim-bootstrap/   Java bootstrap loaded into the host JVM by the module
paper-plugin/         Paper-side plugin (bridge lifecycle, /cim command)
test-mods/            first-party NeoForge 1.21.1 test mod (cimtest)
third_party/          vendored cplug-abi + cplug-sdk (MIT, from CRUSSTY)
docs/                 ARCHITECTURE / ROADMAP / F2V_ANALYSIS / MODPACK
docs/runtime-analysis/  deep upstream analysis: vanilla MC 26.3 (+client), Paper 26.3,
                        NeoForge 1.21.1, CRUSSTY, cross-version gap — all measured;
                        f2f2-parity-matrix = the F2V v1→2.0 visual parity contract
```

## Quick start (module authors / server admins)

```bash
# CI produces module-imagine.zip — drop it into the server:
unzip module-imagine.zip -d <server>/modules/imagine
# put NeoForge 1.21.1 mods in the usual place:
cp mymod-1.21.1.jar <server>/mods/
# start (CRUSSTY launcher boots Paper + runtime + module):
cd <server> && crussty run
```

Boot log markers to look for:

``[c-imagine] module init`` → `[c-imagine] bootstrap ready` →
`[c-imagine] mods (N): ...` → kernel "Done".

## Development

```bash
cargo test --workspace          # unit tests for every crate
cargo clippy --workspace --all-targets -- -D warnings
./scripts/package-module.sh     # build the installable module zip (CI-equivalent)
```

CI (GitHub Actions):
- **ci** — fmt, clippy, tests, release build, Java bootstrap, Paper plugin, test mod.
- **integration** — real Paper 26.3 + CRUSSTY + this module + `cimtest` mod; asserts
  boot markers, mod discovery and clean logs.
- **profiling** — criterion baselines + bare-vs-modded boot/memory budget gate.
- **visual** — protocol-level visual smoke (F2V v2 groundwork; real-client harness lands in M5).

See [docs/ROADMAP.md](docs/ROADMAP.md) for milestones and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design, including the
shadow-namespace relocation pipeline that makes multi-version class isolation work
inside a single JVM.

## Status

M0 — foundation: repository, architecture, core crates, CRUSSTY module skeleton,
CI/integration/profiling pipelines. ✅
M1 — embedded 1.21.1 layer boots: `cim-reloc` (shadow-namespace relocation of the
real MC 1.21.1 + NeoForge 21.1.265 stack), shadow classloader, layer boot inside
Paper JVM with vanilla registry snapshot; ground-truth CI jobs. ✅ (matrix green)
M2 — mod execution: real FML discovery pipeline + mod relocation, `cimtest`
registration through the layer. ⏳ next
