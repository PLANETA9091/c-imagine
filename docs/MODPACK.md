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

## Tier 2 — third-party (nightly matrix, M7)

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
