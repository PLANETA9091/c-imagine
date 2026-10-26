# Paper 26.3 — kernel analysis

Analyzed from primary artifacts: PaperMC fill API v3, the `paper-26.3-223.jar`
download, and the `repo.papermc.io` maven metadata. Measured 2026-10-26.

## 1. Identity card

| Field | Value | Source |
|---|---|---|
| Kernel | Paper for MC **26.3**, build **223**, published 2026-10-25 | `fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest` |
| Jar | `paper-26.3-223.jar`, 55,250,707 B (paperclip-style bundler) | fill-data object URL |
| API artifact | `io.papermc.paper:paper-api:26.3.build.223-stable` on `repo.papermc.io` | maven-metadata.xml (grep `26.3`) |
| Version metadata API | **fill v3**; the legacy `api.papermc.io/v2` returns **HTTP 410 Gone** | measured |
| Java | 25 (Minecraft 26.3 requirement; Paper follows the vanilla requirement) | MC manifest + CI setup-java pin |

CI facts already encoded in this repo: the `ci.yml` Java stages resolve the Paper API
version via `scripts/resolve-paper-version.sh` and compile against JDK 25
(commit `8ce92e6`: "paper-api needs release 25; drop removed MDG DSL"). Do not
downgrade.

## 2. Role in c-imagine: kernel, not peer

Paper is the **single authority** for world state, chunk lifecycle, entity storage,
tick ordering, player connections and the 777 network session. The module never
mutates world state except through Paper-side execution (bridge §4.4 of
`ARCHITECTURE.md`), never reads chunks behind Paper's back, and never opens a second
listening port. This is what makes the performance law enforceable: there is exactly
one heap, one scheduler, one tick loop, one world.

What the module consumes from the kernel, stage by stage:

| Stage | Kernel service used | Mechanism |
|---|---|---|
| Boot (before kernel classes) | class-load pipeline | CRUSSTY JVMTI `ClassFileLoadHook` — no Paper involvement yet |
| Kernel ready | plugin lifecycle | `paper-plugin/` enables on Paper's own lifecycle; bridge ring comes up |
| Layer boot | thread scheduling | relocated 1.21.1 logical server constructs on the CRUSSTY scheduler, riding Paper's main thread for state calls |
| State bridge | world/entity/blockstate APIs | Paper API via the Java bootstrap (`java/cim-bootstrap`) |
| F2V v2 (M4+) | outbound packet stream + display entities + resource pack push | CRUSSTY net hooks for rewriting; Paper APIs for displays/packs |
| Watchdog (M2+) | none | JVMTI heap sampling of the shadow classloader domain |

## 3. What Paper gives F2V v2 (verified present in 26.3)

F2V v2 renders modded content for **vanilla 26.3 clients**. The client-side
primitives are confirmed present in the 26.3 registries (measured, see
`vanilla-client-26-3.md`); the server-side Paper APIs to drive them are the classic
Bukkit/Paper surface this repo's plugin already targets:

- **Display entities** (`block_display`, `item_display`, `text_display` exist in the
  26.3 `entity_type` registry) — the backbone of modded block/entity rendering and
  of contraption rigs; spawn/metadata driven through the packet path or Paper APIs.
- **Item components**: `custom_model_data` exists in the 26.3 data component registry,
  **plus a new `item_model` component** (1.21.4+ line) — pack synthesis (M6) should
  emit both styles and let the client path decide.
- **Filled maps** (`filled_map` present) — the GUI-scene subsystem (map/image-tile
  HUDs) keeps working.
- **Server resource packs** — pack push remains a login/late-join flow; pack format
  moved 34 → 97.1, so generated packs must target the 97.x schema (M6 task).

Paper-API deltas between the 1.21-era API our muscle memory knows and
`26.3.build.223-stable` are catalogued in M1 (analysis debt item 2 in
`minecraft-26-3.md §6` — server class census). Nothing in M0–M3 depends on a
specific delta.

## 4. Boot/lifecycle facts the module depends on

- Paper 26.3 boots as a bundler jar (paperclip): it unpacks/patches vanilla + paper
  patches at first run. CRUSSTY's launcher wraps the same process with the JVMTI
  runtime and injects our module **before kernel classes load** — verified in the
  integration workflow (`integration.yml`, green at `165f499`), which boots real
  Paper 26.3 with the module and the `cimtest` mod and asserts the marker chain
  `module init → bootstrap ready → mods (N) → kernel Done`.
- The plugin stage (`paper-plugin/`) is intentionally minimal: bridge lifecycle +
  `/cim status`. Anything that can live in Rust **must** live in Rust; the Java side
  stays a thin shim (repo law, `AGENTS.md`).
- Kernel upgrades (26.3 → 26.4 when the owner decides) touch exactly one CI variable
  (the resolved Paper version) plus the remap-table regeneration — by design; the
  relocated 1.21.1 stack does not care about kernel minor bumps as long as the bridge
  remaps stay correct. The remap snapshot/diff tooling in `cim-registry` is the
  tripwire for that day.

## 5. Risks tracked against the kernel

| Risk | Mitigation |
|---|---|
| Paper changes net pipeline internals; CRUSSTY net hooks break | hooks are CRUSSTY-owned, tested in `visual.yml` protocol smoke; CRUSSTY upstream tracks Paper versions |
| Paper API artifact alpha→stable renames | version resolved at CI time from fill v3 + maven metadata, not hard-coded |
| Kernel drift silently changing registry ids | `cim-registry` snapshot diff fails CI on drift (design §4.5) |
| Paper 26.3 build 223 is a moving target | CI pins `26.3` exactly; bump is a conscious `feat:` |
