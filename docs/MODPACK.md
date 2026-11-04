# Test modpack plan

Goal: every subsystem of c-imagine (loader, registries, events, state bridge, packet
translation, resource pack, GUI scenes, watchdog budgets) is exercised by at least one
mod, in CI, on every push. Two tiers: first-party mods we own, and third-party mods
pinned by version.

## Tier 1 — first-party (`test-mods/`, NeoForge 1.21.1)

| Mod | Exercises | Status |
|---|---|---|
| `cimtest` | registration (block/item/BE/entity), lifecycle events, login hooks, custom mods.toml metadata, dependency declarations | M0 ✔ (source) / M2 (layer execution) |

Planned additions (each tiny, each one subsystem):
- `cimtest-net` — custom payload/channel both directions (net pipeline + bridge).
- `cimtest-cap` — capabilities/data components round-trip through the bridge.
- `cimtest-asset` — custom model/texture/OBJ for pack synthesis + visual diff.
- `cimtest-gui` — menu/HUD surface for GUI scene translation.
- `cimtest-heavy` — allocation/tick-load generator as the profiling stressor.

Rules: server-side only (no client classes) until M5; deterministic logging with
`[cimtest]` markers that CI greps; every marker pair (expect/actual) documented here.

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
`scripts/cimscene/mods.lock`). Status per mod (marked honestly — the first
full multi-ns measurement happens after this branch merges, since PRs do
not trigger this repo's push-to-main CI):

| Mod | CI wired (branch) | Note |
|---|---|---|
| Create | CI wired (branch): the full walk all classes, as on main | the measured baseline |
| Biomes O' Plenty | CI wired (branch): gallery walk ns=biomesoplenty, all classes + the biome-tint diagnostic; companions glitchcore + terrablender pinned (mandatory deps, checked in the jar) | biome-tint class = DIAGNOSTIC-first proof surface |
| Dynamic Trees | CI wired (branch): gallery walk ns=dynamictrees, all classes | BE bridge channel proof stays the M5.6 surface |
| Combat Roll | CI wired (branch): gallery walk ns=combat_roll, all classes; playeranimator companion pinned (mandatory dep) | sound/held diagnostic classes cover its sound/animation surface |

Companion mods (playeranimator, glitchcore, terrablender) ride the server
but their namespaces are deliberately not ladder namespaces — they are
libraries/providers, not gallery content.

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
  (placed; contents NOT filled — the fill API is mod-specific and
  unverified from this workspace, the row note says so), (d) the
  texture-model surface — the pack builder synthesizes a flat item model
  from the fluid's still texture (`textures/fluid/*.png`, 400000+i CMD
  space); truth has no native surface there (the join compares honestly,
  dyn class). A fluid with NO renderable surface on either side keeps the
  honest-RED note `no-truth-surface:<reason>`.
- **The `inv` class** (see the table above): the open-inventory view on
  both legs.

### Tier 2 CI wiring status (branch `ci/full-content-coverage`, 2026-11-04)

`mod-matrix.yml` gained `tier2-truth` matrix legs for the server-loadable
set — each boots a real NeoForge 21.1.265 server + the locked mod and
asserts the mod's namespace lands census rows:

| Mod | CI wired (branch) | Note |
|---|---|---|
| AE2 | CI wired (branch): tier2-truth census leg | server_side=required per Modrinth |
| Mekanism | CI wired (branch): tier2-truth census leg | server_side=required per Modrinth |
| Sophisticated Backpacks | CI wired (branch): tier2-truth census leg; rides its REQUIRED companion sophisticated-core (checked in the jar) | server_side=required per Modrinth |
| Jade | CI wired (branch): tier2-truth boot leg, assert-rows=no | a HUD provider registers no content — the rung proves boot + census file, honestly not rows |
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
