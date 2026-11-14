# c-imagine

![ci](https://github.com/PLANETA9091/c-imagine/actions/workflows/ci.yml/badge.svg)
![integration](https://github.com/PLANETA9091/c-imagine/actions/workflows/integration.yml/badge.svg)
![profiling](https://github.com/PLANETA9091/c-imagine/actions/workflows/profiling.yml/badge.svg)
![visual](https://github.com/PLANETA9091/c-imagine/actions/workflows/visual.yml/badge.svg)
![gallery](https://github.com/PLANETA9091/c-imagine/actions/workflows/gallery.yml/badge.svg)
![parity-diff](https://github.com/PLANETA9091/c-imagine/actions/workflows/parity-diff.yml/badge.svg)
![mod-matrix](https://github.com/PLANETA9091/c-imagine/actions/workflows/mod-matrix.yml/badge.svg)

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
# CI produces module-imagine.zip — extract it into the server's modules dir
# (the archive's top folder is imagine/, so this creates <server>/modules/imagine):
unzip module-imagine.zip -d <server>/modules
# put NeoForge 1.21.1 mods in the usual place:
cp mymod-1.21.1.jar <server>/mods/
# start (CRUSSTY launcher boots Paper + runtime + module):
cd <server> && crussty run
```

The zip also carries `cimlive-1.0.0.jar` (live menus and block place/break). When a
layer is configured (`crus/c-imagine.json` with `layerDir`), the loader copies it into
`<server>/mods/` before the mods scan, and only rewrites it when the bytes differ.
To keep it out of the server, set `"bundledMods": false` in `crus/c-imagine.json`.

Boot log markers to look for:

``[c-imagine] module init`` → `[c-imagine] bootstrap ready` →
`[c-imagine] mods (N): ...` → kernel "Done".

## Development

```bash
cargo test --workspace          # unit tests for every crate
cargo clippy --workspace --all-targets -- -D warnings
gradle build --no-daemon -p live-mod   # cimlive jar, required by the packager
./scripts/package-module.sh     # build the installable module zip (CI-equivalent)
```

CI (GitHub Actions):
- **ci** — fmt, clippy, tests, release build, Java bootstrap, Paper plugin, test mod.
- **integration** — real Paper 26.3 + CRUSSTY + this module + `cimtest` mod; asserts
  boot markers, mod discovery and clean logs.
- **profiling** — criterion baselines + bare-vs-modded boot/memory budget gate.
- **visual** — the calibrated manual harness (dispatch-only): the stock vanilla 26.3
  client renders the pack under xvfb and the pixel contract asserts per channel
  (range_dispatch, select, postfx, equipment, block-in-world, display rig, camera-lock,
  palette) — with the optional seed-v2 ladder legs (items/held/inv + the sound stream per
  ladder namespace).
- **gallery** — the real-mod pixel gate: real NeoForge 1.21.1 client vs c-imagine on the
  vanilla 26.3 client, every ladder namespace walked to exhaustion across the 12-class
  coverage ladder — block, entity (mobs/dyn/large), item, held, particle, fluid, sound,
  gui, biome, inv (gate-hard + diagnostic-first per class); coverage gate + per-shard
  pixel joins.
- **parity-diff** — the F2F identity proof (dispatch-only): a real modded client capture
  pixel-diffed against the same scene on the stock client — the calibrated icon and
  camera-lock block identity datums, plus the seed-v2 per-channel identity (ladder item
  crops, held first-person, the ordered sound stream via sound-diff.py).
- **mod-matrix** — the real-mod ladder: P0 Create census parity (truth vs layer), the P1
  ladder, and the Tier 2 census legs (AE2, Mekanism, Sophisticated Backpacks, Jade).

See [docs/ROADMAP.md](docs/ROADMAP.md) for milestones and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design, including the
shadow-namespace relocation pipeline that makes multi-version class isolation work
inside a single JVM.

## Status

Milestone detail and the open blockers live in [`docs/ROADMAP.md`](docs/ROADMAP.md);
this section is the short, honest summary (2026-11-14).

**Works (CI-proven on `main`):**
- M0/M1 — CRUSSTY module boots the relocated NeoForge 1.21.1 layer inside Paper 26.3.
- M2/M3 — real FML discovery, mod construction and registration (Tier 0 mods:
  Create, Biomes O' Plenty, Dynamic Trees, Combat Roll); a real 1.21.1
  `DedicatedServer` runs inside the layer, ticked from Paper's tick end.
- M4/M5 — vanilla 26.3 clients see mod items, blocks, entities and fluids through
  the F2V resource pack; the gallery compares them against a real NeoForge client.
- M6.2/M6.3 — with the `cimlive` mod, using a mod item, placing/breaking a mod block
  and clicking a mod menu run the mod's own code.

**Not yet survival-playable out of the box.** Known gaps:
- A mod block can only be placed when it has a note-block carrier. Boot allocates one
  per mod blockstate (Create first) over 27 instruments x notes 1-24 = 648 render slots,
  kept in `c-imagine/f2v/carriers.tsv`; blocks past 648 stay unplaceable until a second
  carrier family exists. Blocks without a blockstate JSON are not discovered.
- Placed mod block entities tick only while their menu is open.
- No mod world generation in the Paper world and no mod recipes in Paper's crafting.
- Mod block hardness, tool and collision are the note block's.
- The F2V pack server listens on loopback only and is not offered on join;
  `cimlive` ships in the module zip and is installed into `mods/` at boot (see Quick start).

Work on these is tracked on the `feat/survival` branch and in `docs/ROADMAP.md`.
