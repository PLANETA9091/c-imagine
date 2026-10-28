# The real-mod parity harness (`cimscene`) — design contract

Status: DESIGN — owner directive 2026-10-29 ("test real mods now, not only your
own test mods; always compare with NeoForge; per mod, per block, per sound, per
particle; Create 100% identical is the top priority"). No new measurements in
this doc — every mechanism cited is already measured in the linked docs and is
re-used as-is. Roadmap: M5.5. Ladder: `MODPACK.md` Tier 0.

## 1. The anti-bias property (why an ordinary mod, and why real mods)

First-party rigs (cimtest) stay for fast unit-grade feedback, but they can no
longer be the proof surface: a rig we write ourselves can — without anyone
noticing — encode assumptions that only hold inside c-imagine (a workaround
here, a relaxed predicate there), so the rig passes while real NeoForge behaves
differently. The owner's fix is two-fold:

1. **Prove on real mods** — Create (P0), Biomes O' Plenty / Dynamic Trees /
   Combat Roll (P1), the full Aged modpack (P2). Their content is authored
   against real NeoForge and is adversarial by construction: nothing in it
   knows or cares that c-imagine exists.
2. **The harness itself must be an ordinary mod.** `cimscene` is a first-party
   NeoForge 1.21.1 mod built with the same ModDevGradle setup as cimtest, but:
   - zero c-imagine APIs, zero client classes, standard vanilla/NeoForge server
     surface only;
   - it runs UNMODIFIED on both stacks — real NeoForge 21.1.265 and inside the
     c-imagine layer.

   If cimscene + the target mod behave identically on both stacks, any observed
   divergence is by construction a c-imagine defect — the rig cannot absorb the
   blame. That is the whole point.

## 2. Dual-stack run model (parity-diff.yml extension)

| leg | stack | what runs | captures |
|---|---|---|---|
| truth | real NeoForge 21.1.265 server + real NeoForge 1.21.1 client under xvfb (official-launcher composition measured in parity-diff.yml job 1) | target mod jar(s) + cimscene | per-scene screenshots, per-scene profiler spans, `[cimscene]` markers, wire census |
| ours | c-imagine on Paper 26.3 + vanilla 26.3 client (stock-client join path, `visual-harness-join.md`) | the SAME mod jar(s) + cimscene in the layer | same |

Both legs pin, in this order, before any scene runs:

- fixed-seed void/superflat world (worldgen parity of the TEST world is
  trivial; the target mod's own worldgen content is reached through pinned
  cells, see §7);
- noon + clear weather (the camera-lock pins);
- bot player teleported to the fixed camera cell with explicit yaw/pitch, the
  absolute `^-1 ^ ^3` floor cell in front (`camera-lock-26-3.md`);
- resource pack state identical on both legs (the F2vPack channel on ours; the
  mod's own assets on truth — they must RESOLVE to the same pixels, which is
  exactly what the datums measure).

## 3. Scene model — "empty world, the player looks forward"

A SCENE is one deterministic server-side arrangement plus one synchronized
capture. Contract:

1. **reset** — clear the scene region, kill stray entities, re-pin time/weather;
2. **arrange** — place/spawn the scene's content (blocks, entities, displays,
   particles) inside the locked camera footprint;
3. **settle** — a fixed tick count (determinism across stacks, §7);
4. **mark** — `[cimscene] scene=<id> state=armed` on stdout (CI greps);
5. **capture** — client screenshot on BOTH legs, server profiler span closed
   with the scene id, cimbot wire census of the packet families the scene
   touched;
6. **teardown**, next scene.

Scene ids are stable strings (`create.kinetic.cogwheel`,
`bop.block.mahogany_log`, `dt.tree.birch_growth_100t`, ...) so the diff job can
join truth vs ours row by row. The scene list is DATA (emitted at runtime from
the census, §4), not code — adding a mod to the ladder adds census rows, not
harness code.

## 4. Census-driven sweep — "everything a mod ships"

On boot, cimscene reads the server-side registry census of every loaded mod
(`ForgeRegistries` / built-in registries: blocks, items, entity types, particle
types, sound events, block entity types, menus) and emits:

- `scene-manifest.tsv` — per-registry per-namespace COUNTS
  (`registry\tnamespace\tcount`), the coarse join shape;
- `scene-manifest-entries.tsv` — the FULL id listing
  (`registry\tnamespace\tid`): per-namespace counts can match while the
  underlying sets differ (one block missing, one extra sound) — the exact
  set join in census-diff.py is the "every block, every sound, every
  particle" gate (baseline datum 2026-10-29: 3316 ids, exact set identity
  across stacks);
- one scene per entry for the visual registries (blocks; items via the
  display-entity rig; entities; particles), grouped scenes per family for
  sounds (play N events at a pinned position, census the wire);
- **the two legs' censuses are themselves the FIRST parity gate** — if the mod
  does not register the same sets on real NeoForge and inside c-imagine, every
  later row is meaningless. Registration parity is asserted before any pixel
  work.

Coverage artifact = registered vs exercised per registry per mod. A mod is
"green" only when every registry entry has a scene row and the row is green on
both legs.

Channel proofs (each reuses already-measured machinery):

| channel | pixel proof | wire/numeric proof | measured machinery reused |
|---|---|---|---|
| block | in-world AE datum on the cube-only region | block-update census | runs 5–9: PixelDiffNorm, luminance factor 0.93432, gate ≤ 2500 |
| item | hotbar icon crop + in-hand shot | give/inventory verify | run 10 + run 43 pattern (2303/2304 px datum) |
| entity | locked-region AE datum; sky-park subtract for movers | add_entity/metadata census, `--expect-extra-entity` | M5.4 display-entity rig (feet+2.5, hold-shot subtraction) |
| particle | locked-region AE datum at a pinned tick | particle packet census | camera-lock footprint; keep the emission inside the measured region |
| sound | — (cannot be seen) | ClientboundSoundPacket census per scene + client log markers | cimbot wire census |
| BE / screen | screenshot | menu/dialog channel markers | M6.1 HUD/dialog channel |

## 5. Comparison — numbers AND eyes

- **Pixel**: PixelDiffNorm with the documented version-level luminance factor
  (26.3 renders block faces a constant 1.0703x brighter — a client-VERSION
  property that exists for vanilla content too, therefore normalized, not
  chased; runs 5–9). Per-scene gate calibrated from the first stable locked run
  of that channel.
- **Profile**: per-scene named spans (vanilla server `ProfilerFiller`
  push/pop scoped to the scene) captured on both legs → tick-time/alloc deltas
  per scene; the performance law (AGENTS.md) applies to parity legs too.
  spark flamegraph on the truth leg when a delta is red.
- **EYES**: both legs' screenshot sets are attached to every run summary; the
  owner reviews them side by side. A gate can be green and still be wrong —
  the eye pass is mandatory on every NEW channel and on every gate
  recalibration. This is the owner's explicit instruction: compare not only by
  the numbers in profiles but also visually.
- **Debug bundle per red row**: truth screenshot + ours screenshot + manifest
  row + wire-census diff + profiler span in one artifact directory — a red row
  must be debuggable from the artifact alone, no live reproduction needed.

## 6. The ladder (implementation order)

- **P0 — Create ("100% identical")**: first scenes — kinetic network at a
  pinned RPM + tick count (cogwheel/shaft rotation), one contraption move, one
  fluid, one UI, Create's particles/sounds. The measurable form of the owner's
  "100% identical": every Create channel passes its calibrated identity datum
  (the block-identity-datum standard — 93.0% identical at tol 4 after
  normalization, gate locked ≤ 2500). Raw pixel equality across client
  versions is already proven impossible at the VANILLA level (the 1.0703
  factor) — the calibrated metric is the honest maximal claim, and it is what
  "identical to the modded client" means operationally.
- **P1**: Biomes O' Plenty (block palette sweep + biome-tint scenes, pinned
  seed), Dynamic Trees (BE + growth scenes at pinned tick counts), Combat Roll
  (animation/sound surface — the smallest mod, first full ladder walk).
- **P2**: the full Aged modpack — boot + census + first sweep; per-mod failures
  become targeted tasks.
- Long-term: EVERY supported mod gets a ladder row — "every mod must be
  supported" is the end state, the ladder is how it is reached mod by mod.

## 7. Open questions (resolve during implementation, each with a measured answer)

- **BOP worldgen**: biome placement varies by seed/shape; the visual channel is
  biome tint + block palette. Plan: pin a seed, locate a fixed biome cell per
  scene on both legs, assert the biomes match before capturing.
- **Dynamic Trees determinism**: growth is tick-driven. Plan: pin start state
  (sapling) + exact tick count; cimscene asserts identical block-set sequences
  (its markers) before the pixel capture.
- **Create rotation determinism**: fix the rotation source (pinned RPM) +
  tick count; cimscene markers assert the kinetic network state (speed in the
  network) matches across legs before capture.
- **Aged pack pinning**: manifest source (Modrinth/CurseForge) + snapshot pin;
  pack boot cost vs the profiling budgets (ARCHITECTURE.md §4.6).
- **Scene fan-out budget**: large registries (BOP ~thousands of entries) →
  batch scenes per family (one shot covers a grid of N blocks placed in the
  measured footprint) with per-entry rows joined from one capture — the grid
  geometry must stay inside the calibrated region; measured region bounds come
  first.
