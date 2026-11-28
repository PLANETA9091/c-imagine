# Coverage walks completion — the four incomplete-walk reds of run #46049855366

Task C design agent. Repo `c-imagine` @ `e2cbbbe3` (working tree only — this file; no
code, workflow, or worklog changes).

**The law**: "content that never reached a page is RED, not green" —
`scripts/cimscene/coverage-gate.py:47-50` (COVERAGE-INCOMPLETE), final verdict
`coverage-gate.py:627-633`. The gate BLOCKS the verdict independently of the ~1225 pixel
reds (`/tmp/ci-logs/verdict_632.log` `=== THE SHARD GATES ===` block, line 671+).

**Measured inputs** (all opened, zero guesses):
- gate log `/tmp/ci-logs/verdict_632.log` (run 46049855366, 2026-11-27T19:16Z),
- gate `scripts/cimscene/coverage-gate.py` (638 lines), selftest
  `scripts/cimscene/test-coverage-gate.py` (771 lines),
- scene-mod `scene-mod/src/main/java/dev/cim/scenemod/CimSceneMod.java` (7076 lines),
- ours plugin `paper-plugin/src/main/java/dev/cim/paper/CimPlugin.java` (walk-facing
  particle replay only),
- workflow `.github/workflows/gallery.yml` (seed walk 555-760, per-shard walk
  1676-2340, verdict 4738-4780),
- worklog receipts e271-e280 (docs/worklog.md lines 9652-9667) and the 14-ea pin
  receipts (lines 8763, 8777).

## 0. Correction to the task brief (measured, not argued)

The brief maps `particle, dimension_type` to biomesoplenty. The run says otherwise:
**biomesoplenty printed COVERAGE: GREEN** (verdict_632.log:696); the
`particle, dimension_type` red belongs to **ae2** (log:766, :785, :786). The four
failing namespaces are exactly:

| ns | log line | incomplete classes |
|---|---|---|
| create | :641, :666 | entity-dyn |
| dynamictrees | :702, :726 | entity-dyn |
| ae2 | :766, :785, :786 | particle, dimension_type |
| supplementaries | :883, :889, :908 | entity-dyn, blockentity |

**None of these is a walk that never ran.** Every flagged class shows
`missing_manifests=0 missing_truth_shots=0 missing_ours_shots=0` with the full
`pages_needed` walked (log:641, :702, :766, :883) — the walks reached exhaustion and the
honest-RED channel fired on manifest content. Receipt e272's "exercised-census law"
(worklog:9654) was a **flip-report.py** census fix (the `diag-e-*` join-file glob hole);
the coverage gate reads the **ledger + manifests + shot lists + boot markers**
(`gallery.yml:4760-4777`, `coverage-gate.py:371-403`), a different mechanism — nothing
to re-land from e272, but its **GATED/demoted reporting pattern** is the template this
proposal reuses.

---

## 1. Per-namespace gap table (class → why uncovered, with anchors)

| ns | class | why the subject never reaches the picture | anchors |
|---|---|---|---|
| create | entity-dyn | `create:potato_projectile(dissolved)` — 1 of 9 types. It spawns configured+ pinned (reflection `setItem`), but a ThrowableProjectile re-applies gravity INSIDE its own tick and dies on the first block hit; the 14-ea motion pin zeroes velocity only post-tick, so the position still drifts ~1 block/tick-window and/or clips the row-0 platform and dies before the 40-tick settle re-read. | log:641; spawn config `CimSceneMod.java:4826-4828`, call :6349; pin map/grace :1982-1985, drain :1987-2004 (:6514 call), pin put :6413-6414; settle drift/death → `dissolved` :6941-6950 |
| dynamictrees | entity-dyn | `falling_tree(dissolved)`, `lingering_effector(dissolved)` — 2 of 2 (the whole series). Both are self-timing animators: FallingTreeEntity converts itself to blocks on landing; the effector expires on its own tick timer. Receipt 8777 (14-ea measured): "the self-removing animators discard on their own tick timers, ungated by velocity … the named next lever is a settle-window for the animator kinds or their own public API". | log:702; worklog:8777; dissolve note `CimSceneMod.java:6944-6945` |
| ae2 | particle | `ae2:lightning_arc_fx(no-simple-options:filler-cursor-exhausted:Not a list: "north")` — 1 of 6. The programmatic options ladder (simple → empty-compound codec → error-driven default filler) has NO list-shaped candidate: the codec wants a LIST where the filler offered `"north"` (candidate 12, the last — hintIndex starts direction/facing keys at 12), so the cursor cannot advance and rung 3 exhausts. Particle was flipped OUT of DIAGNOSTIC_CLASSES (14-e36), so the skip row is now gate-hard RED. | log:766; ladder `CimSceneMod.java:3002-3091` (exhausted note :3072-3076), candidates :3105-3124 (`"north"` :3122), hintIndex :3202-3212; flip `coverage-gate.py:234-245`; ABSENCE_NOTES particle :314; post-flip test V test-coverage-gate.py:652-664 |
| ae2 | dimension_type | `total=1 reason=no-walk-yet` — the ledger SEES the class (ae2 registers 1 dimension type, the spatial-storage dimension) and the gate's `NO_WALK` set hard-reds any positive total. The named lane ("a per-dimension camera rig") was never shipped. | log:785; NO_WALK `coverage-gate.py:260-274`; ledger row `CimSceneMod.java:2567` (cellsPerPage=0); count source :2509 |
| supplementaries | entity-dyn | 7 of 16 dissolve: bomb, brick_projectile, cannonball, falling_ash, pearl_marker, slimeball_projectile, slingshot_projectile. Receipt 8777 measured the pin's reach: 16→8 (the impact/gravity half settles as cells), the fuse classes bomb/cannonball and the self-timers die inside the window. The current 7 = fuse/self-timer + the row-0 platform-clip gravity half the pin cannot hold. | log:883; worklog:8777; same anchors as create |
| supplementaries | blockentity | `uncovered-remaining` = 5 types nothing instantiated: moving_pulley_block, spring_launcher_arm, block_generator, structure_temp, moving_slidy_block. The 14-ec lane walks ALL 44 BE types (one page, 77 cells) and the gate requires every reconciliation name to appear as a `cell` row subject (`coverage-gate.py:464-481`). Measured split from the DIAG line (log:890): moving_pulley_block / moving_slidy_block / spring_launcher_arm carry `be-missing` (placed, but the settle's BE-type assert failed — internal/moving types their items cannot place, worklog:8777), while block_generator / structure_temp appear in the remainder WITHOUT a DIAG name — their rows carry note=`dissolved` (placed state popped by settle, `CimSceneMod.java:2281-2286`), and **`dissolved` is missing from `ABSENCE_NOTES["variant-blockentity"]`** (`coverage-gate.py:340`) — a gate-side honesty hole this proposal fixes first. | log:889-890; lane `CimSceneMod.java:1720-1796` (no-valid-state :1749-1755, item route :1758-1772, setblock :1773-1777, failed:place :1783), settle re-read :2269-2300, BE assert :2411-2418; reconciliation writer :2574-2603 |

Why these classes specifically: the gate fans the ledger's single `entity` row into
mobs/dyn/large (`coverage-gate.py:555-558`) — only the dyn series (non-Mob subjects,
`CimSceneMod.java:6259`, :6979) lacks a freeze instrument that beats the subject's own
tick. particle is gate-hard since 14-e36. dimension_type is the last NO_WALK class
(`coverage-gate.py:98-106`, :274). blockentity's instantiation receipts gate since 14-ec
(`coverage-gate.py:435-535`, docstring :204-214).

---

## 2. The wiring design (per class)

### 2.1 entity-dyn (create, dynamictrees, supplementaries)

**(a) The position re-pin — extends 14-ea.** `drainMotionPin`
(`CimSceneMod.java:1987-2004`) currently re-zeros `deltaMovement` post-tick; gravity is
re-applied INSIDE the subject's tick, so position still accumulates one tick of fall per
tick (~0.04-0.08 b/tick → >1.0 drift by the 40-tick settle, or the box clips the row-0
platform and the impact kills it). Change:

- `MOTION_PINNED` becomes `Map<Entity, PinSpec(Vec3 cell, int deadline)>`
  (:1982); the spawn put (:6413-6414, plus the cart/seat vehicle pins :6369, :6374)
  stores the cell (`GALLERY_X0 + col*ENTITY_PITCH, GALLERY_Y0 + row*ENTITY_PITCH + 0.25,
  GALLERY_Z + 0.5` — the +0.25 y keeps a 0.25-high projectile box clear of the platform;
  the manifest cell coords at :6981-6983 stay the design cell, so the crop contract is
  untouched).
- each drain tick: `setDeltaMovement(ZERO)` **and** `e.moveTo(cell)` for non-Mob
  subjects only (mobs already hold via NoAI + the settle yaw re-assert :6954-6964).
- honesty contract unchanged: the subject's own state (item, fuse counter) is real; only
  position/velocity are pinned — the same law as the yaw re-assert and the NoAI freeze.
  The pixel fidelity of a pinned projectile stays demoted report-only via the manifest
  `dyn=1` flag (:6979, :6983) — the COVERAGE gate needs only "alive + in cell"
  (:6941-6943), which the re-pin makes holdable.
- expected rescues (measured-by-first-flight, honest): potato_projectile,
  brick_projectile, slimeball_projectile, slingshot_projectile, pearl_marker,
  falling_ash. NOT rescued: fuse/self-timer subjects (below).

**(b) The dissolve-note split.** `CimSceneMod.java:6944-6945` writes bare
`dissolved` for both death and drift. Split the note: `!alive` →
`dissolved:own-timer`; `drift > 1.0` → `dissolved:moved`. Prefix matching keeps both
RED-eligible for non-gated subjects (`coverage-gate.py:401` matches `note == pre or
note.startswith(pre)`; ABSENCE_NOTES entity-dyn :311 stays `("dissolved", …)`), and the
suffix is the reason the GATED ledger (section 3) demotes on.

**(c) The GATED ledger for the fuse/self-timer class** — exact spec in section 3.
Initial static rows (each already measured across ≥2 full galleries — 42988643975
(worklog:8777), 45865564587 (worklog:9654), 46049855366 (log:641/:702/:883)):

| class | subject | reason |
|---|---|---|
| entity-dyn | dynamictrees:falling_tree | own-timer (converts-to-blocks self-discard) |
| entity-dyn | dynamictrees:lingering_effector | own-timer (expiry self-discard) |
| entity-dyn | supplementaries:bomb | own-timer (fuse) |
| entity-dyn | supplementaries:cannonball | own-timer (fuse) |

`create:potato_projectile` and the 5 rescued supplementaries projectiles stay **UNGATED**
— they must settle as cells on the first flight with the re-pin; if any still dissolves
for x2 consecutive runs, a receipted commit adds its row (the biome-flip x2 stability
law, `coverage-gate.py:88-96` precedent), never auto-derived.

### 2.2 particle (ae2:lightning_arc_fx) — filler rung 4: the list candidates

The refusal is DFU's `Not a list: "north"`: the key got the Direction-enum string
(candidate 12) where the codec wants a list. Deterministic fix, zero per-mod code:

- `OPTION_FILL_CANDIDATES_LENGTH` 13 → 15 (`CimSceneMod.java:3105`): candidate 13 =
  an **empty `ListTag`**; candidate 14 = a **one-element `ListTag` wrapping candidate
  12's scalar** (`[ "north" ]` — a codec that wants a non-empty list of enum strings
  accepts it). Appending keeps every existing resolution byte-identical (candidates
  0-12 unchanged, same order), so only previously-exhausted types change behavior —
  the determinism law (:2995-2996 "both legs run the same codec over the same candidate
  ladder") holds, and the round cap at :3023 (`LENGTH + 2`) stays valid because each
  round still advances ≥1 cursor or exhausts.
- the fill rule gains the wrap: in the missing-key fill (:3039-3045) and the cursor
  advance (:3060-3079), when the round's refusal matches `Not a list` for the target
  path, write the candidate **wrapped in a ListTag** (the same `findFillTarget`
  node-targeting machinery, :3164-3193).
- gate side: nothing changes — particle is gate-hard (14-e36, `coverage-gate.py:245`
  excludes it); a successful spawn converts the row to `cell … ok` (:2929-2932) and the
  class goes `OK`. The ours leg needs no change: Paper cannot spawn mod particles — the
  replay resolves through `visual-particles.tsv` or marks `nochannel`
  (`CimPlugin.java:4272-4280`, :5014-5033), and the manifest note rides the truth
  manifest on both legs.
- fallback: a codec the extended ladder still cannot construct for x2 runs gets a GATED
  row (`particle`, subject, `reason=codec-unconstructible`) — section 3.

### 2.3 dimension_type (ae2) — the per-dimension camera rig, the named lane shipped

- **New page kind** `cimscene gallery dimension <ns> <p>`: dispatcher literal next to
  `biome` (:440) / `variant` (:464). Walk body mirrors `galleryBiome`/`galleryVariant`:
  enumerate the namespace's dimension types from `registryAccess` (the ledger already
  reads `Registries.DIMENSION_TYPE` at `CimSceneMod.java:2509` via `dynCount` :2632);
  for the page's type find the live `ServerLevel` whose dimensionType key matches; build
  a 3x3 vantage platform + camera 8 blocks out in THAT dimension (the gallery geometry,
  `scripts/cimscene/gallery-walk.py:14-16` focal contract), teleport the dump client
  (`cimviewer`) into the level, settle, and write
  `gallery-dimension-<ns>-p<p>.tsv` (one cell row per type, `subject=<type id>`); the
  settle asserts the viewer's level dimensionType == subject (note=`ok`, else
  `teleport-failed`); done marker `gallery-dimension-<ns>-p<p>` (the `writeDoneMarkerFile`
  contract, :7030).
- **Ledger row**: `CimSceneMod.java:2567` `cellsPerPage` 0 → 1 (one dimension type per
  page).
- **Gate law**: remove `"dimension_type"` from `NO_WALK` (`coverage-gate.py:274`); add
  to `CLASSES` (`coverage-gate.py:134-215`) the row
  `("gallery-dimension-{ns}-p{p}.tsv", "gal-dim", "ours-gal-dim", "gallery-dimension done ns={ns} page={p} cells=0")`
  and map the ledger name onto the walk class in `VARIANT_CLASS`
  (:284-297 — it is the generic ledger→walk indirection the :555-556 fan-out consults).
  The `total < 0` unreadable-registry red moves with the class into the walked branch
  (:577-583) — the law survives.
- **DIAGNOSTIC-first** (the 2026-11-04 pattern, :79-96): add `"dimension"` to
  `DIAGNOSTIC_CLASSES` (:245-251). The ours leg has NO dimension projection channel
  (Paper has no modded dimension to point a camera at — the same F2V-shaped hole the
  variant classes live in, :104-106), so `missing_ours_shots` reports loudly and never
  reds; missing manifests/truth shots/terminal markers DO red (the walked-class law,
  :597-601). Flip precondition: x2 full galleries with stable truth pages — the biome
  flip's precedent (:88-96).
- **Workflow**: mirror the biome walk loop in BOTH walk branches — seed
  `.github/workflows/gallery.yml:746-760` and per-shard :2230-2248 — with the receiver
  pin replaced by the dimension teleport (the walk command moves the viewer itself), and
  the shot line `truth-shots/gal-dim-$NS$p.png` (gallery.yml:2250 shot idiom; the
  SHOT NAME LAW row is the `gal-dim` stem + `-<ns>` infix, `coverage-gate.py:52-77`).
- **GATED fallback**: if the spatial level refuses the camera (teleport loops, client
  crash — measured-by-first-flight), the explicit demotion row (`dimension_type`,
  `ae2:spatial_storage`, `reason=dimension-unreachable`) replaces the current
  `no-walk-yet` red with a named `GATED` line — section 3.

### 2.4 blockentity (supplementaries) — the 5 internal/moving types

1. **Gate honesty fix (first, zero-walk)**: add `"dissolved"` to
   `ABSENCE_NOTES["variant-blockentity"]` (`coverage-gate.py:340`) so a popped placement
   NAMES itself in the DIAG line (measured: block_generator + structure_temp ride this
   note today — log:889 remainder vs. log:890 DIAG list differ by exactly those two).
2. **The controller placement strategy** — the 14-ec plan gains a per-type strategy
   field next to `BePlacement(state, item)` (`CimSceneMod.java:2036-2037`):
   `strategy=item|setblock|controller`. `controller` = the named per-subject table (the
   `spawnSupportFor` precedent :2006-2025, the `configurePotatoProjectile` precedent
   :4826-4828): place the type's REAL instantiation surface and assert the BE the world
   holds. First candidates (measured-by-first-flight, honest):
   - `supplementaries:spring_launcher_arm` ← place `spring_launcher` + redstone power
     (the arm is the launcher's extended state);
   - `supplementaries:block_generator` ← the generator's own placement + activation
     route.
   The 3 remaining (`moving_pulley_block`, `moving_slidy_block`, `structure_temp`) are
   **transient-motion/structure-internal states**: they exist only mid-contraption /
   mid-transfer and cannot hold a still cell — receipt 8777 named exactly this class
   ("the internal/moving BE types their items cannot place and the setBlock route did
   not instantiate"). They go straight to the GATED ledger:
   - `blockentity` / `supplementaries:moving_pulley_block` / `transient-motion-state`
   - `blockentity` / `supplementaries:moving_slidy_block` / `transient-motion-state`
   - `blockentity` / `supplementaries:structure_temp` / `structure-internal`
   If spring_launcher_arm / block_generator also fail x2, their receipts add gated rows
   the same way. Covered-beats-gated: a gated name that a future strategy instantiates
   prints `cell … ok`, the gate ignores the stale row, and a receipted commit drops it.

---

## 3. The GATED ledger — exact gate-law change (receipt e272's demotion pattern)

e272 (worklog:9654) landed the pattern for the pixel census: demoted = report-only, the
names never leave the verdict, "0-red-with-demoted never reads GATED-GREEN". The
coverage gate gets the same shape, explicitly and never silently:

**Writer (scene-mod)** — `writeGalleryIndex` (:2596-2603, the same write point as the
reconciliation file) emits `scenes/gallery-<ns>-gated.tsv` from the STATIC code table
`GATED_SUBJECTS` (records `GatedSubject(cls, id, reason)`, each row citing its receipts
in the javadoc; registry-independent — a stale row is inert, never a silent demote):

```
class\tsubject\treason
entity-dyn\tdynamictrees:falling_tree\town-timer
...
```

**Reader (coverage-gate.py)** — new `read_gated(scenes, ns)` alongside `read_index`
(:371-381); missing file = empty map (the honest default: no demotions, everything
stays RED). A demotion applies ONLY to the exact `(class, subject)` pair AND only to
the note family the row names (entity classes: note startswith `dissolved`; particle:
`no-simple-options`; blockentity: the remainder path; dimension_type: `teleport-failed`
/ no-level). Three application points:

1. **The series loop** (:599-625): partition `unshown` — a matched row whose subject is
   gated prints
   `GATED\t<sub>\t<subject>(<note>) (gated: <reason> — receipt e272 demotion, named report-only)`
   and is removed from the RED set; everything else REDs exactly as today
   (:624-625). A gated subject that settled as a cell prints nothing extra.
2. **The blockentity branch** (:479-481): `remainder` splits into gated vs red; gated
   names print the same `GATED\tblockentity\t<n> (gated: <reason> …)` line and drop out
   of the `uncovered-remaining` RED (:510-516).
3. **The final line** (:627-633): when `incomplete` is empty but demotions fired,
   `COVERAGE: GREEN — every class walked to exhaustion, every page manifested and shot
   on both legs (demoted=N: <class>/<subject>, … — named report-only, receipt e272
   pattern)`. The names are ON the verdict line every run — the law "the verdict must
   SEE its holes" is satisfied by naming, not by red forever.

**Selftest additions** (test-coverage-gate.py, the A-X2 idiom :71-179, :620-753): Y —
entity-dyn gated row → `GATED` printed, run GREEN, final line carries `demoted=`;
Z — blockentity remainder ∩ gated → GREEN, non-gated remainder stays RED; AA — particle
gated codec row → GATED; AB — dimension_type walked: full fixture → `DIAG-OK`, missing
truth shot → `DIAG-INCOMPLETE` GREEN; AC — a gated subject with a NON-gated note family
(`client-crash-banned`) stays RED.

---

## 4. CI proof (what the gate prints when complete)

Expected verdict output after the slice lands (names from log:641/:702/:766/:785/:883/:889):

```
coverage-gate: ns=create index=scenes/gallery-create-index.tsv
OK	entity-dyn	total=9	pages_needed=9 missing_manifests=0 missing_truth_shots=0 missing_ours_shots=0
COVERAGE: GREEN — every class walked to exhaustion, every page manifested and shot on both legs

coverage-gate: ns=dynamictrees index=scenes/gallery-dynamictrees-index.tsv
GATED	entity-dyn	dynamictrees:falling_tree(dissolved:own-timer) (gated: own-timer — receipt e272 demotion, named report-only)
GATED	entity-dyn	dynamictrees:lingering_effector(dissolved:own-timer) (gated: own-timer — receipt e272 demotion, named report-only)
OK	entity-dyn	total=2	pages_needed=2 missing_manifests=0 missing_truth_shots=0 missing_ours_shots=0
COVERAGE: GREEN — every class walked to exhaustion, every page manifested and shot on both legs (demoted=2: entity-dyn/dynamictrees:falling_tree, entity-dyn/dynamictrees:lingering_effector — named report-only, receipt e272 pattern)

coverage-gate: ns=ae2 index=scenes/gallery-ae2-index.tsv
OK	particle	total=6	pages_needed=1 missing_manifests=0 missing_truth_shots=0 missing_ours_shots=0
DIAG-OK	dimension_type	total=1	pages_needed=1 missing_manifests=0 missing_truth_shots=0 missing_ours_shots=1
COVERAGE: GREEN — every class walked to exhaustion, every page manifested and shot on both legs

coverage-gate: ns=supplementaries index=scenes/gallery-supplementaries-index.tsv
OK	entity-dyn	total=16	pages_needed=16 missing_manifests=0 missing_truth_shots=0 missing_ours_shots=0
GATED	entity-dyn	supplementaries:bomb(dissolved:own-timer) (gated: own-timer — receipt e272 demotion, named report-only)
GATED	entity-dyn	supplementaries:cannonball(dissolved:own-timer) (gated: own-timer — receipt e272 demotion, named report-only)
GATED	blockentity	supplementaries:moving_pulley_block (gated: transient-motion-state — receipt e272 demotion, named report-only)
GATED	blockentity	supplementaries:moving_slidy_block (gated: transient-motion-state — receipt e272 demotion, named report-only)
GATED	blockentity	supplementaries:structure_temp (gated: structure-internal — receipt e272 demotion, named report-only)
DIAG	blockentity	missing_ours_shots=0 unshown=supplementaries:bamboo_spikes(be-missing), … (absence notes are the honesty channel) (the variant replay channel is the named lane — reported, never red)
OK	blockentity	total=44 (every BE type instantiated by the blockentity lane or wall-reachable)
COVERAGE: GREEN — every class walked to exhaustion, every page manifested and shot on both legs (demoted=5: entity-dyn 2, blockentity 3 — named report-only, receipt e272 pattern)
```

First-flight honesty expectations (named, never assumed): potato_projectile and the 5
impact-class supplementaries projectiles must settle as `cell … ok` with the re-pin —
if any still dissolves x2 runs, a receipted commit adds its GATED row; if
spring_launcher_arm / block_generator still print `be-missing` x2 runs, their GATED
rows land the same way.

## 5. Risks

- **Client-copy physics** (entity-dyn re-pin): the pin is server-side; the truth
  client's own projectile copy may keep falling in-frame. This touches ONLY the
  `dyn=1` demoted pixel channel (report-only) — the coverage gate reads the settle's
  server-side alive+in-cell assert (:6941-6950). Measured-first on the next flight.
- **Row-0 y-lift** (+0.25): shifts a projectile half a block up inside the same page
  crop (108.6 px/block, `gallery-walk.py:14-16`); manifest cell coords unchanged so the
  crop contract holds; measured-by-first-flight on the pixel side.
- **GATED-table rot**: a mod update could make a gated subject placeable. Mitigations:
  covered-beats-gated (the walk's cell row wins, :464-481 unchanged for covered names),
  the row is dropped by a receipted commit once x2 runs show cells — the DIAGNOSTIC
  flip law's mirror.
- **The list rung changes a deterministic ladder**: appended candidates only
  (0-12 byte-identical); previously-resolved types cannot change; the ours leg never
  runs the ladder (`CimPlugin.java:4272-4280` nochannel).
- **The dimension walk**: chunk-load latency on llvmpipe may need a longer settle; a
  hostile level (spatial storage) may teleport-crash the dump client (the walk relaunch
  idiom, gallery.yml:694-712, is the recovery precedent); DIAGNOSTIC-first keeps the
  verdict safe while measured; the GATED row is the honest fallback.
- **Per-mod controller code** (spring_launcher_arm / block_generator): the named
  per-subject-table exception only; a strategy that cannot produce the BE goes to the
  x2-gated path, never to a silent skip.

## 6. Already landed — do NOT re-propose

- 14-ea motion pin + 14-eb spawn-support + 14-ec BE lane (worklog:8763, :8777) — the
  base this slice extends.
- e272 exercised-census law + menu ns + join-e naming (worklog:9654) — flip-report
  census, not the coverage gate.
- e273 poly sampler + BE-pose settle gate (worklog:9656); e279 QuadDump real-BE law
  (worklog:9666) — pixel-capture lanes, orthogonal to coverage.
- 14-e36 particle/gui/held flips (coverage-gate.py:234-244), 14-e31 biome flip
  (:88-96), 14-dx variant graduation (:98-106) — the diagnostic-law machinery reused.
