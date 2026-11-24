# Parity harness: truth vs ours

The harness runs the same scenario on two stacks and diffs what a player can observe, so that
c-imagine (Paper 26.3 + CRUSSTY + c-imagine + mods) can be checked against the real thing.

| | TRUTH | OURS |
|---|---|---|
| server | NeoForge 21.1.265 (MC 1.21.1) + mods | Paper 26.3 + CRUSSTY + c-imagine + the same mods in the layer |
| client | real NeoForge 21.1.265 client + same mods + `truth-ctl` mod | Horizon 26.3 client driven by `horizon-ctl` |
| control | truth-ctl, TCP 127.0.0.1:25599 (`-Dtruthctl.port`) | horizon-ctl over a unix socket, via `hz.sh 3 ctl` |
| game port | 25566 | 25565 |
| seed / mods | seed 4242; Create 6.0.10, BOP 21.1.0.14, Dynamic Trees 1.7.2, Combat Roll 2.0.6 (+ cloth-config, GlitchCore, TerraBlender, playeranimator) | same |

Both stacks use the same test area: a stone platform at y=120 (x 300..316, z 300..316), air above it,
player at 308 121 308. Each run starts with a reset prelude (see below), so scenarios are independent.

Files:
- `stacks.py`: the two adapters (`TruthStack`, `OursStack`) and the normalizers. Every observation is
  reduced to ids, counts and states before the diff.
- `run.py`: runs one scenario on one stack, writes JSON (`--stack truth|ours SCENARIO -o OUT [--shots DIR]`).
- `diff.py`: compares two run JSONs step by step (`truth.json ours.json -o diff.md`). Exit 0 only when every step is equal.
- `all.py`: runs every `scenarios/*.json` on both stacks, diffs each, writes `results/<id>/` and `results/SUMMARY.md`.
- `scenarios/`: one JSON per scenario (`id`, optional `notes` and `todo`, `setup` commands, `steps`).

Python 3 standard library only. Run with `python3 -I`.

## Environment variables

All paths and ports default to the current stand layout; set these to run from another checkout or host.

| variable | default | used by |
|---|---|---|
| `PARITY_TRUTH_PORT` | `25599` | truth-ctl TCP port (must match `-Dtruthctl.port` of the client) |
| `PARITY_HZ` | `~/hud-work/horizon-play/hz.sh` | horizon-ctl wrapper |
| `PARITY_HZ_N` | `3` | horizon client number passed to `hz.sh` (its Xvfb is `:9N`) |
| `PARITY_OURS_LOG_DIR` | `~/hud-work/horizon-play/srv` | directory with the OURS `boot-*.log` (source of `CIM-INSPECT` lines) |
| `PARITY_RESULTS` | `parity/results` | output directory of `all.py` (not committed) |
| `PARITY_INSPECT_LOCK` | `~/hud-work/horizon-play/scratch/parity-inspect.lock` | flock serializing `/cim inspect` across lane processes on one host |

## Starting the stacks

Stand scripts used with these defaults (`~/hud-work/truth/` and `~/hud-work/horizon-play/`). Adjust the
paths if your layout differs. Nothing here should open a window on a shared display: the clients run on
private Xvfb displays with `WAYLAND_DISPLAY` unset.

TRUTH:
- `~/hud-work/truth/start-server.sh`: starts the NeoForge 1.21.1 server from `~/hud-work/truth/srv` (JVM from `unix_args.txt`), log in `srv/console.log`, pid in `srv.pid`. Game port 25566, RCON 127.0.0.1:25576.
- `~/hud-work/truth/start-client.sh [nvidia|llvmpipe]`: starts a private Xvfb `:94`, copies the server mod set plus `extra-mods/` into the client, and launches the real NeoForge client as `TruthA` with quick-join to 25566 and truth-ctl on 25599. The default GL mode is `nvidia`; `llvmpipe` is the software fallback. Pids in `client.pid` and `xvfb.pid`.
- `~/hud-work/truth/stop-client.sh` and `stop-server.sh`: stop by pid only (the server first gets RCON `stop`).

OURS:
- `~/hud-work/horizon-play/start-ours.sh`: starts the Paper 26.3 + CRUSSTY server from `srv/` (`crussty run`), writes `srv/boot-MMDD-HHMM.log` and waits up to 8 min for `Done (`. Java 25 from `JAVA_HOME`.
- `~/hud-work/horizon-play/hz.sh 3 start [launch args]`: starts the Horizon client 3 headless (private Xvfb `:93`, own `XDG_RUNTIME_DIR`, its control socket under `c3/run/`). Launch arguments: see the horizon-play notes (not verified here).
- `hz.sh 3 ctl CMD ...`: sends one horizon-ctl command. `hz.sh 3 stop` quits the client.
- `~/hud-work/horizon-play/stop-ours.sh`: stops the server by the pid listening on 25565, then its launcher.

Check the stands before a run: a `hello` on each control (the command name is the same on both; unverified for horizon-ctl here) and
a `/cim inspect` on OURS (see below).

## Running

```bash
cd <checkout>/parity
python3 -I all.py                          # all scenarios, both stacks, diffs, shots
python3 -I all.py --only 23 24 --no-shots  # a subset by id substring
python3 -I run.py --stack truth scenarios/23-orient-bop-fir-stairs-floor-north.json -o out.json  # one run
python3 -I diff.py truth.json ours.json -o diff.md
```

Read `results/SUMMARY.md` (verdict per scenario: EQUAL, DIFFERENT, NOT-DIFFED) and the per-scenario
`results/<id>/diff.md` (the differing steps, with both sides and the screenshot paths). Screenshots
(`results/<id>/shots/{truth,ours}/`) are for eye review: a green diff is necessary but not sufficient.
Record the findings in a triage file (the local `results/TRIAGE.md`), with each row marked EQUAL, REAL (a game
difference with evidence) or ARTIFACT (a runner difference, with the reason). Results, logs and screenshots
are artifacts and are not committed.

## Scenario format

```json
{"id": "vanilla-break-stone-wooden-pickaxe",
 "notes": "why this scenario exists, what it checks",
 "setup": ["gamemode survival", "fill 0 63 0 6 63 6 minecraft:stone", "give {player} minecraft:wooden_pickaxe"],
 "steps": [{"do": "select", "item": "minecraft:wooden_pickaxe"},
           {"do": "break", "pos": [3, 63, 3]},
           {"observe": "block", "pos": [3, 63, 3]}, {"observe": "inventory"}]}
```

- `do`: `select` (item or 1-based slot), `place` (pos, item), `interact` (pos, optional face name), `break`,
  `collect` (walk onto a pos to pick up drops), `use_item` (optional hold), `use_at` (`point` [x, y, z],
  block-space doubles: aim at the point, right-click the first block hit from the eye; TRUTH `use-at`, OURS
  `look-at` + `mouse right down/up`; the result is `hit_pos`, `hit_face`, `block_after`, `inventory_change`),
  `key` (`key` name, `ticks`, default 1: TRUTH `key NAME tap --ticks N`; OURS a vanilla KeyMapping map to
  horizon-ctl `key`/`mouse` down, wait, up, with key.jump -> space, key.sneak -> shift, key.sprint -> ctrl,
  key.attack/use -> mouse left/right, key.hotbar.N -> N; a mod KeyMapping fails on OURS with a clear error),
  `click_slot` (`in`, `slot`, `mode`),
  `tp` (pos, yaw, pitch), `wait` (ticks or seconds), `command`, `open_inventory`, `close`.
  A `do` step may set `"block_only": true` to compare only the block after the action (random outcomes).
- `observe`: `block` (pos), `inventory`, `screen`, `player` (health, food, saturation), `motion`
  (`position` and `velocity`, [x, y, z] rounded to 0.1; TRUTH `state.player`, OURS horizon `state`).
- Slot names are abstract: `hotbar:H`, `main:M`, `grid:N`, `result`, `input`, `fuel`, `output`, `container:N`, `armor:K`, `offhand`.
- Modded ids in `give`/`setblock` are translated to `/cim give` on OURS; a modded block is placed with a `place` step (no `setblock`).
- `todo`: a scenario that cannot be run fully with the current adapters; the field says what is missing.

## Lessons learned

These are the failure modes that cost the most time. Keep them in mind when writing a scenario.

- Drop sampling: after a break, wait 10 ticks, then count item entities within 3 blocks of the BLOCK centre.
  TRUTH uses `entities 5` (radius from the player) and filters; OURS uses `/cim inspect entities x y z 3`
  and counts dropped items only, not placed-block carriers. Break drops are compared by id from the break reply.
- Break position: break a block on the platform at y=121 (not the y=120 floor), standing 3 blocks away.
  At 2 blocks a drop can land inside pickup reach and get picked up during the sample window.
  Use `collect` to walk onto the spot before reading the inventory, so pickup happens on both stacks.
- Reset prelude: forceload the area, survival, clear the inventory and the nearby items and item displays
  (left from earlier scenarios), rebuild the platform, teleport, then VERIFY empty: the screen must be
  `playing` and the inventory and cursor empty (retry up to 3 times). Without this, one scenario's items
  leak into the next (carry-over ARTIFACTs in run 1).
- Slot maps: a slot name means the same thing on both stacks, but the indices differ. TRUTH uses the vanilla
  menu order (crafting 0 result, 1-9 grid; furnace 0 input, 1 fuel, 2 output). OURS (horizon-ctl) uses
  hotbar 0-8, main 9-35, grid 100+n, result 200 (furnace input/fuel/output 300-302). Check a new menu with a
  raw dump of each side before trusting a map.
- Menus: a `playing` screen has no meaningful slot list (stale data on the client), so it is not compared.
  `observe screen` waits 5 ticks first; a click is read 5 ticks after it.
- Modded selection and truth on OURS: a vanilla client sees mod items as paper and mod blocks as carriers.
  For mod ids the OURS adapter takes server truth from `/cim inspect`: `inspect block x y z` (real block
  state), `inspect inv <player>` (slot to modded id and count), `inspect entities x y z r`. Output is one
  JSON line prefixed `CIM-INSPECT ` in the newest `boot-*.log`. A modded `select` finds the hotbar slot on the
  server and selects it on the client; `place` takes no item argument for mod ids.
- Place and interact limits: `place` clicks the solid neighbour's face that touches the target, and the
  adapter picks the neighbour. To control the face, leave air around the target except the one neighbour
  (the scenarios do this with `setblock ... minecraft:air`). `interact` takes only a face NAME (face centre); no
  hit point exists on either adapter.
- Vanilla use of a placed block (stripping, bone meal) is not covered by `break`: use `interact` with the item selected.

## Coverage status (scenarios 23-40)

Scenarios 23-40 cover orientation and hit-point cases (stairs, slab, shaft, wall torch, cogwheel beside shaft,
saplings), Create GUI and content (depot, item vault, hand crank, water wheel, schematic table, green toolbox),
a census recipe craft, BOP flowers and log stripping, Dynamic Trees bone meal, Combat Roll (observe-only) and
a furnace with a modded input. Open items (`todo` fields):

- Slab upper half (24): needs a hit-point argument in `place` (truth-ctl and horizon-ctl both lack it).
- Combat Roll roll action (39): needs a key-press command in both adapters.

Not yet run on the stands: all of 23-40 were written and checked for format only.

## Census scenarios (generated) and batch mode

`gen_census.py` makes parity scenarios from the census (`blocks.csv`, `items.csv`, `menus.csv`, `recipes.csv`)
into `scenarios/census/<family>/<id>.json`: `place-break` (every placeable block item, `pb-*`), `interact`
(every block opener in `menus.csv`, `ia-*`), `item-use` (food, `fu-*`; tools are skipped, see `_skipped.csv`),
`craft` (shaped recipes, sampled per mod, `cr-*`). Shaped recipe keys come from the mod jars (the census keeps
no key mapping). Rules, skips and the reasons are in `_skipped.csv`. Each scenario uses the runner's reset
prelude and refills its own target cell.

```bash
python3 -I gen_census.py --census ~/hud-work/horizon-play/census --mods ~/hud-work/horizon-play/mods   # rewrites scenarios/census
python3 -I test_gen_census.py      # schema check against run.py (ast), synthetic + real census, batch mode on fake stacks
python3 -I all.py --dir scenarios/census/place-break --shard 2/8 --no-shots --csv results/pb-2of8.csv --resume
```

`all.py --dir DIR` runs every `*.json` under DIR (recursive); `--shard i/n` runs slice i of n (no overlap);
`--csv` appends one row per scenario (`scenario, verdict, first_diff_step, reason`); `--resume` skips rows
already in the CSV. Infrastructure problems are `ERROR`, never `DIFFERENT`: run.py failing or timing out
(1800 s), a failed setup command (heal, wait-ready, verify-empty, give), or a step error that reads like a
connection failure. `diff.py --json PATH` writes the verdict and the first differing step (used by `all.py`).

## Lanes (parallel clients on the same two servers)

A lane = one OURS player (a Horizon client on its own `hz.sh` slot) + one TRUTH player (a truth client on its
own truth-ctl port) + one area origin. Lanes build in their own area, so several shards run at once on the
same Paper and NeoForge servers. The lane table is `parity/lanes.json` (see `lanes.py` for the rules):

| lane | OURS player | hz slot | TRUTH player | truth-ctl port | origin (area) |
|---|---|---|---|---|---|
| 0 | `Lane_B` | 3 | `TruthLane0` | 25599 | `[0,0,0]` (scenario coordinates as written) |
| 1 | `Lane_C` | 4 | `TruthLane1` | 25600 | `[64,0,0]` |
| 2 | `Lane_D` | 5 | `TruthLane2` | 25601 | `[128,0,0]` |
| 3 | `Lane_E` | 6 | `TruthLane3` | 25602 | `[192,0,0]` |

- `run.py --lane N` (with `--lanes-file`) runs one scenario as lane N. `offset_scenario` shifts every absolute
  coordinate by the origin, once, in one place: setup `setblock`/`fill`/`tp`/`forceload` and selector `x=/y=/z=`,
  step `pos` and `point` keys, and `command` step text. Relative (`~`, `^`) and non-position numbers are left alone,
  and `clone`/`execute`/`summon`/... raise instead of building in the wrong place. Results record `lane`, `origin`, `player`.
- `all.py --lanes K` runs K lane processes on one shared queue (`lanes.run_queue`, `imap_unordered`): a lane that
  finishes early takes the next scenario. The main process is the only CSV writer. A worker exception becomes one
  ERROR row. `--lanes` can be combined with `--dir`, `--shard`, `--csv` and `--resume`.
- The default with no `--lane` is the single stand, unchanged (`TruthA`, port 25599, hz slot 3, `Lane_B`).
- Tests (no game): `python3 -I test_lanes.py` covers the offset transform, the lane config rules, the queue and an end-to-end `--lanes 2` run on a fake `run.py`.

### Truth lanes

`~/hud-work/truth/start-truth-lane.sh K [nvidia|llvmpipe]` starts truth lane K: user `TruthLane<K>`, `-Dtruthctl.port=25599+K`,
private Xvfb `:(80+K)` (hz slots use `:9N`, so lanes avoid them), own gameDir `~/hud-work/truth/lanes/K/game`
(mods are one shared copy in `lanes/mods`, symlinked into each lane). The client heap is `-Xmx2500M`. Stop a lane with
`kill "$(cat ~/hud-work/truth/lanes/K/client.pid)"`. The script is written but was not run in the swarm.

### OURS lanes

Join each extra Horizon client with its own slot and name, then run its ctl (the name is kept in that slot's `horizon-player.txt`):

```bash
~/hud-work/horizon-play/hz.sh 4 start --name Lane_C --server 127.0.0.1:25565
~/hud-work/horizon-play/hz.sh 4 ctl click-button Proceed   # the resource-pack prompt (see LESSON in the status log)
```

The OURS server allows 8 players and the truth server 4 (`max-players` in both `server.properties`), so the truth
side is the limit: the single stand's `TruthA` takes one truth slot, so 3 lanes at most while it is joined and 4 without it.
The join command above is not run yet (the `--server` flag and the world choice are unverified on this stand).

