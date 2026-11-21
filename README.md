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
To keep it out of the server, set `"bundledMods": false` in `crus/c-imagine.json` and
delete `<server>/mods/cimlive-1.0.0.jar`: the loader never removes a jar it installed, and
while bundled mods stay on it reinstalls the jar on every boot.

Resource pack for players: the module serves the F2V pack on port 25580 on all
interfaces and offers it on join. Set `CIM_F2V_PUBLIC_URL` (or `-Dcim.f2v.publicUrl`)
to the address players reach (or `server-ip` in `server.properties`); the offer is
then required. `CIM_F2V_BIND`, `CIM_F2V_PORT`, `CIM_F2V_AUTO_OFFER=false` and
`CIM_F2V_AUTO_OFFER_REQUIRED` override the defaults. A `resource-pack=` line in
`server.properties` turns the module's own offer off.

Create's Empty Blaze Burner and Flywheel use the normals authored in their OBJ
models. The server reads them directly from the installed Create JAR when it
builds the resource pack. These items need no NeoForge client capture. The
gallery uses a NeoForge client as the visual reference.

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
this section is the short, honest summary (2026-11-17).

**Works (CI-proven on `main`):**
- M0/M1 — CRUSSTY module boots the relocated NeoForge 1.21.1 layer inside Paper 26.3.
- M2/M3 — real FML discovery, mod construction and registration (Tier 0 mods:
  Create, Biomes O' Plenty, Dynamic Trees, Combat Roll); a real 1.21.1
  `DedicatedServer` runs inside the layer, ticked from Paper's tick end.
- M4/M5 — vanilla 26.3 clients see mod items, blocks, entities and fluids through
  the F2V resource pack; the gallery compares them against a real NeoForge client.
- M6.2/M6.3 — with the `cimlive` mod, using a mod item, placing/breaking a mod block
  and clicking a mod menu run the mod's own code.

**Survival on `feat/survival`** (gated by `.github/workflows/survival.yml`: Paper 26.3 +
module + Create 6.0.10 + Sophisticated Backpacks, two boots, no manual install step):
- `cimlive` ships in the module zip and installs itself into `mods/` at boot.
- The F2V pack is served on all interfaces and offered on join (required once
  `CIM_F2V_PUBLIC_URL` or `server-ip` names the address; see Quick start).
- Every mod block gets a carrier at boot: 648 note-block slots first, then the
  unbounded display family (a barrier plus an `ItemDisplay` of the block's own model).
  The mushroom family (186 more slots) exists behind `-Dcim.f2v.mushroomCarriers=true`;
  it stays off because vanilla worldgen produces some of those states. Carriers are
  guarded against note changes, redstone and neighbour updates.
- Every mod item gets a Paper stack (`item_model` + identity), so inventories,
  drops and recipes hold the real mod item.
- Placed mod block entities tick in loaded chunks with no menu open, persist in chunk
  data and are adopted again after a restart.
- Breaking takes the mod block's own time and needs its correct tool for drops
  (note-family carriers); the mod's own drop is spilled.
- Mod recipes: shaped, shapeless, smelting, blasting, smoking, campfire and stonecutting
  register in Paper (1075 of 1607 for Create + Backpacks) and unlock in the recipe book.
- Mod ores from NeoForge biome modifiers generate in new overworld chunks (Create zinc),
  and spawn chunks made before the layer was ready are backfilled once.

**Known gaps:**
- Machine-only recipes (Create's crushing, mixing, pressing and the like) run only in
  the machines, as on NeoForge; smithing and special crafting recipes are not on Paper yet.
- Display-family blocks break instantly (a barrier never progresses in a dig).
- Worldgen covers mod features in the overworld only: no nether/end, no new biomes
  (Biomes O' Plenty) and veins are cut at chunk borders; about 8 ms per new chunk.
- Mod item names come from the id, not the mod's lang file; collision is the
  carrier's, not the mod block's shape.
- Mod entities and their AI in the Paper world are not covered by the survival gate.
