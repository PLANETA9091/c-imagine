# Camera-lock identity rig — pixel-identical block comparison across the two parity stacks

Owner directive (2026-10-28): **modded content must render on the stock vanilla
26.3 client IDENTICALLY to the modded client.** The items channel has its
identity datum (parity-diff run 4: AE(icon) = 2300 of 2304 px). The BLOCK
channel's run 4 measurement (AE 50281, ungated) was polluted by THREE
uncontrolled variables — this doc locks all of them and defines the reusable
camera-lock rig every future in-world identity measurement rides.

## 1. Why run 4's block region could not gate (the three pollutants)

| # | pollutant | run 4 mechanism | effect on the AE |
|---|---|---|---|
| 1 | **camera pose** | both sides placed the block RELATIVE to the look direction (`^ ^ ^3` / `placeVisualBlock` eye+3·dir), but the viewer's spawn pose is whatever the server picks (`cimviewer` truth spawn measured at (-0.5, -60.0, 1.5) — x/z are floor-of-half-block picks); the c-imagine leg additionally used a DIFFERENT horizontal offset (Bukkit eye+3·dir, y = eye cell) than the truth leg (caret 3-ahead) | the cube projects to a DIFFERENT screen rect on each side → every pixel of the cube and its AO shadow contributes even when the renders are identical |
| 2 | **sky/sun/weather** | c-imagine side had NO daylight pin (world ticked from time 0 during the whole 12-minute llvmpipe join); truth pinned noon only after boot — plus weather cycles | sky light angle + fog/sky color differ → floor gradient + fog band differ |
| 3 | **GUI chrome** | full-frame-ish region (280x220) around screen center — the hotbar, the crosshair, and the hand sit near the same rows on both clients but their pixel rows are version-exact | any GUI row drift inside the region adds constant noise independent of the world render |

## 2. The locked rig (measured command sequence, mirrored on both sides)

Both parity-diff.yml (truth side, REAL NeoForge 1.21.1 client + cimtest) and
visual.yml (c-imagine side, stock 26.3 client + F2vPack) now run the SAME
sequence after their existing legs:

```
kill @e[type=!player]                                        # sweep mobs AND display rigs
tp cimviewer 0.5 -60 0.5 0 -30                               # LOCK: cell + yaw 0 + pitch -30 (ABSOLUTE — both surfaces measured y=-60)
fill -9 -60 1 4 -50 6 air                                    # sweep the scene in front
Key F5                                                       # cim side ONLY: front→first (run-45 F5-cycle lesson)
Key F1                                                       # hideGui — no HUD, no hand
Shot 13-locked-pre.png  (truth: t03-locked-pre.png)          # clean scene, locked camera
tp cimviewer 0.5 -60 0.5 0 0                                 # pitch 0 for the caret-free absolute placement
setblock 0 -60 3 <block>                                     # ABSOLUTE cell: floor, 3 south
tp cimviewer 0.5 -60 0.5 0 -30                               # back to the locked pose
Shot 14-locked.png      (truth: t04-block-locked.png)        # the identity shot
Key F1                                                       # un-hide for any later leg
```

Plus the boot-time pins on BOTH servers (parity-diff.yml already had the
`doDaylightCycle`+noon pair; visual.yml now matches it, and BOTH carry
`doWeatherCycle false` + `weather clear` + `doMobSpawning false`):

```
gamerule doDaylightCycle false
time set noon
gamerule doWeatherCycle false
weather clear
gamerule doMobSpawning false
```

### 2.0 Run-45 autopsy (the first dispatch — four measured lessons)

1. **Console `execute at <player> run ...` SILENTLY DOES NOTHING on the
   Paper 26.3 console** — no feedback, no error, while plain commands log
   theirs (the run-45 boot.log carries `Teleported...`, `Gave...`, but NOT
   ONE word from the execute-wrapped fill/setblock/kill). Every rig command
   is PLAIN + ABSOLUTE now: `setblock 0 -60 3 ...`, `fill -9 -60 1 4 -50 6
   air`, `kill @e[type=!player]`. Both superflat surfaces are MEASURED at
   y=-60 (cim: the tp feedback prints feet Y -60; truth: the run-3 spawn
   log) — the absolute Y is a pinned measurement, not a guess.
2. **The armor leg's F5 cycle ends at THIRD-PERSON FRONT** (first→back @07,
   →front @08; the post-armor press moved back→front, never to first).
   Run 45's locked shots were taken from the front camera, from which the
   locked block cell (0,-60,3) is BEHIND the camera — AE(13,14)=568 (pure
   pose noise). The locked leg presses F5 ONCE MORE (front→first,
   deterministic cycle position); legs 09-12 stay on the front view their
   gates were calibrated on.
3. **Peaceful mobs walk into the frame** (a pig photobombed run 45's locked
   pre/shot pair) — `doMobSpawning false` at boot + `kill @e[type=!player]`
   before the pre shot.
4. **The tp rotation lock WORKS**: run 45's shot 13 shows the pitch -30
   camera (horizon pinned at the top of the frame) exactly as designed —
   the server-authoritative rotation is reliable; only the placement
   commands were broken.

### 2.1 Why each piece is load-bearing

- **`tp <player> 0.5 -60 0.5 0 -30`** — the vanilla teleport with explicit
  rotation is SERVER-authoritative: the client MUST apply yaw/pitch exactly
  (no UI path exists to override it under xvfb; MEASURED in run 45: the
  pitch -30 camera renders). The ABSOLUTE Y=-60 is the measured superflat
  surface shared by BOTH stacks (see 2.0-1) — the RELATIVE geometry
  eye→floor→cube is bit-identical.
- **pitch -30** — the camera looks DOWN enough that the whole vertical FOV
  (70° default → ±35°) stays below the horizon: top frame ray = -30° + 35°
  = +5° above the look-down axis... concretely the topmost region row
  (y=110 px) sits at 24.3° above the view center = 5.7° BELOW the horizon
  ⇒ the region sees only GROUND: no sky gradient, no clouds (clouds render
  above the horizon; the earlier display-rig leg had to subtract cloud
  drift — the locked rig eliminates it structurally).
- **`fill -9 -60 1 4 -50 6 air`** — sweeps the previous legs' leftovers out
  of the new camera's view (the t02/10 eye-level stand-in projects into
  cells x∈[-9..0], z∈[3..4], y=-59..-60 for both measured spawn sets —
  inside the sweep box; Y starts AT feet so the grass floor under the viewer
  is never touched, and z starts at 1 so the player's own cell is
  untouched).
- **`setblock 0 -60 3 <block>`** — the ABSOLUTE floor cell 3 south of the
  locked pose (no caret semantics involved — run-45 lesson: plain commands
  only). Standing on the floor (not the run-4 eye cell) means the client
  renders the vanilla ground-shadow/AO contact the modded block gets in
  real worlds on BOTH stacks.
- **F1 (hideGui)** — Robot-pressed (Key.java grew F1); kills the hotbar,
  crosshair, and the first-person hand row from both sides. The remaining
  frame is pure world render.
- **noon + clear + both cycles off** — identical sun angle (max sky light,
  shortest shadows) and no weather state machine on either stack.

### 2.2 The region (measure-first contract)

Region `x=460 y=110 w=360 h=320` (px, 1280x720 scale-1 world render):

- projected cube center at pitch -30 / fov 70(vert): atan(1.12 eye→cube-center
  vertical / 3 horizontal) = 20.5° below horizon; camera -30° ⇒ cube center
  9.5° ABOVE the view center = 9.5·(720/70) = 98 px ⇒ (640, 262).
- cube angular size 2·atan(0.5/3) = 18.9° ⇒ ~195 px; the rect gives ≥51 px
  margin on every side (covers the contact shadow and a bit of floor).
- the rect's top row stays 5.7° below the horizon (see 2.1) ⇒ fog band and
  sky excluded.

Calibration protocol (owner "measure a lot"): the FIRST camera-lock run
prints the appearance AEs and the identity AE ungated; the artifact shots
then verify the projected rect by eye (the pre/shot pair makes the cube
extractable as `shot XOR pre`); the SECOND run locks the gate to the
measured identity value + a small tolerance. The appearance gates (>3000)
are live from the first run — they only assert the block appeared (the
footprint is ~19-38k px depending on face visibility, 3000 is 2 orders
below).

## 3. Known residual risks (each detectable in the first locked run's artifact)

| risk | detection | mitigation |
|---|---|---|
| 26.3 superflat default preset drift (biome/surface ≠ plains grass) | the PRE shots' floor color/tint differs between sides; identity AE polluted UNIFORMLY across the floor | compare floor tint on the two pre shots; if drifted, pin the surface explicitly (`setblock` a 9x13 grass slab patch) or move the region tighter around the cube |
| version shading drift (ambient occlusion / face shade factors changed between 1.21.1 and 26.3) | the cube FACES differ in luminance while the TEXTURE pixels align; icon identity (GUI path, no lighting) stays 2300 | extract the cube as shot-XOR-pre, compute per-face mean-luminance ratios; if a constant factor, the identity gate carries a documented offset (NOT silently loosened) |
| default option drift (gamma/fov/mipmap defaults changed between versions) | uniform luminance or sharpness delta everywhere | pin the options keys explicitly in both harness options.txt files (bytecode-check the 26.3 keys first) |
| llvmpipe nondeterminism | pre-shot re-run differs frame-to-frame | the run-43/44 evidence says frames are stable; the hold-shot drift was clouds, not GL noise |

## 4. Status

- 2026-10-29: rig implemented on BOTH sides (visual.yml 13/14 + parity-diff.yml
  t03/t04), appearance gates live, identity measurement ungated pending the
  first locked run. The matrix row "blocks" points here for the identity leg.
- 2026-10-29 (run 45 autopsy, §2.0): the first dispatch measured the rig's
  OWN failure modes (silent execute, the F5 cycle, mobs) and FIXED them;
  the second dispatch must produce the datum. Feedback greps
  ("Changed the block at 0, -60, 3") guard both stacks' setblock legs.
