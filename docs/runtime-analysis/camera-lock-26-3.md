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
kill @e[type=item_display]                                   # cim side: remove the entity rig
tp cimviewer 0.5 ~ 0.5 0 -30                                 # LOCK: cell + yaw 0 + pitch -30
execute at cimviewer run fill -4 ~ 1 4 ~10 6 air             # sweep the scene in front
Key F1                                                       # hideGui — no HUD, no hand
Shot 13-locked-pre.png  (truth: t03-locked-pre.png)          # clean scene, locked camera
tp cimviewer 0.5 ~ 0.5 0 0                                   # pitch 0 ONLY for caret semantics
execute at cimviewer run setblock ^-1 ^ ^3 <block>           # ABSOLUTE cell: floor, 3 south
tp cimviewer 0.5 ~ 0.5 0 -30                                 # back to the locked pose
Shot 14-locked.png      (truth: t04-block-locked.png)        # the identity shot
Key F1                                                       # un-hide for any later leg
```

Plus the boot-time pins on BOTH servers (parity-diff.yml already had the
daylight pair; visual.yml now matches it):

```
gamerule doDaylightCycle false
time set noon
gamerule doWeatherCycle false
weather clear
```

### 2.1 Why each piece is load-bearing

- **`tp <player> 0.5 ~ 0.5 0 -30`** — the vanilla teleport with explicit
  rotation is SERVER-authoritative: the client MUST apply yaw/pitch exactly
  (no UI path exists to override it under xvfb). `~` keeps each side's own
  surface height: superflat is flat, so the RELATIVE geometry eye→floor→cube
  is bit-identical even if the 1.21.1 and 26.3 default flat presets differ in
  absolute Y (plains grass top at y=-60 measured on the 1.21.1 truth side,
  run 3 log: "logged in with entity id 1 at (-0.5,-60.0,1.5)").
  yaw 0 = facing +Z (south) in BOTH versions — the vanilla rotation
  convention did not drift.
- **pitch -30** — the camera looks DOWN enough that the whole vertical FOV
  (70° default → ±35°) stays below the horizon: top frame ray = -30° + 35°
  = +5° above the look-down axis... concretely the topmost region row
  (y=110 px) sits at 24.3° above the view center = 5.7° BELOW the horizon
  ⇒ the region sees only GROUND: no sky gradient, no clouds (clouds render
  above the horizon; the earlier display-rig leg had to subtract cloud
  drift — the locked rig eliminates it structurally).
- **`fill -4 ~ 1 4 ~10 6 air`** — sweeps the previous legs' leftovers out of
  the new camera's view (the t02/10 eye-level stand-in projects into cells
  x∈[-1..0], z∈[3..4], y=feet+1 for both measured spawn sets — inside the
  sweep box; Y starts AT feet so the grass floor under the viewer is never
  touched, and z starts at 1 so the player's own cell is untouched).
- **`^-1 ^ ^3` at pitch 0** — caret coordinates are FACING-DEPENDENT (the
  forward axis includes pitch): at pitch 0, `^-1 ^ ^3` from the eye
  (feet+1.62) = the point (x, feet+0.62, z+3) → block cell (floor(x),
  feet, floor(z+3)) — the cube STANDS ON THE FLOOR, 3 cells south, sharing
  the viewer's cell column (x=0 for the locked (0.5,·,0.5) pose). Standing
  on the floor (not the run-4 eye cell) means the client renders the
  vanilla ground-shadow/AO contact the modded block gets in real worlds on
  BOTH stacks.
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
