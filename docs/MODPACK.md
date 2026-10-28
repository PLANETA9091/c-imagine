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
| **P0** | **Create** | The flagship: kinetic network, contraptions, fluids, particles, sounds, UIs. **100% identical is the single top priority.** | calibrated identity datum on EVERY Create channel (camera-locked rig) |
| P1 | Biomes O' Plenty | Block palette + biome-tint channels at scale (worldgen surface) | per-registry census sweep green on both stacks |
| P1 | Dynamic Trees | Block entities + incremental growth ticks (BE bridge + event loop) | per-registry census sweep green on both stacks |
| P1 | Combat Roll | Client animation/sound surface — smallest mod, first full ladder walk | animation + sound channels green on both stacks |
| P2 | Aged modpack (full) | The "every mod supported" end-state check; version pinned from its manifest when CI wiring lands | boot + census + first sweep green; failures become targeted tasks |

Per-mod, per-block, per-sound, per-particle coverage is automated via the
census-driven `cimscene` harness: registered vs exercised per registry is a
published artifact; a mod is green only when every registry entry has a scene
row green on BOTH stacks.

## Tier 2 — third-party (nightly matrix, M7; Tier 0 is the pulled-forward core)

Pinned versions, downloaded by CI into `mods/` next to `cimtest`:

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
