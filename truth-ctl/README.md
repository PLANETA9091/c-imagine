# truth-ctl

NeoForge 1.21.1 **client-only** control socket for the parity harness. It lets a
harness drive the real client through vanilla input paths (`MultiPlayerGameMode`
start/continue destroy, `useItemOn`, `useItem`, `attack`, `handleInventoryMouseClick`,
commands/chat) and read back observable state. Mod id `truthctl`, `dist = CLIENT`,
dependencies: NeoForge and Minecraft only.

Protocol table (the spec): `~/hud-work/horizon-play/PLAN.md`, section "truth-ctl protocol (v1)".
Command names and reply keys mirror horizon-ctl.

## Build

```bash
cd truth-ctl
JAVA_HOME=/usr/lib/jvm/java-21-openjdk gradle build --no-daemon -q
# jar: build/libs/truthctl-1.0.0.jar
```

Gradle uses ModDevGradle 2.0.148 and NeoForge 21.1.265 (`gradle.properties`), the
same setup as `test-mods/` and `scene-mod/`.

## Enable

Put the jar in the client's `mods/` directory next to the other mods. Optional
port override: `-Dtruthctl.port=25599` (default). The socket binds `127.0.0.1` only.
The log prints `[truthctl] listening on 127.0.0.1:<port>` and every request/reply
(command name, arg count, byte sizes) with the same prefix.

## Protocol summary

- TCP, one JSON request per line, one JSON reply per line.
- Request: `{"cmd":"break","args":["10","64","10"],"opts":{"timeout":"20"}}`
  (`args` are strings; `--timeout S` inside `args` is accepted too).
- Reply: the command's keys with `"ok": true`, or `{"error": "...", "kind": "..."}`.
  Kinds: `bad_json`, `bad_request`, `unknown`, `no_world`, `not_found`, `busy`,
  `timeout`, `exception`.
- Long actions (`break`, `place`, `interact`, `use-item`, `respawn`, `click-slot`, `screenshot`,
  `wait`, `wait-ready`) run as tick-driven tasks from `ClientTickEvent.Post`; one at a
  time (`busy` otherwise). Every game access runs on the client main thread.

Commands: hello, state, wait-ready, block, blocks, inventory, look, look-at-block,
select, break, place, interact, use-item, key, use-at, respawn, attack, screen (alias menu),
click-slot, open-inventory, close, command, chat, messages, entities, screenshot, wait.

- `respawn`: if the client is on the DeathScreen (or the player is dead), calls
  `LocalPlayer.respawn()` (the Respawn button path), closes the death screen and waits up
  to 10 s for the player to be alive in world. Reply: `{ok, respawned:true, screen,
  player{position,health}, health}`. If not dead: `{ok:true, respawned:false}`.
- `use-item [--hold TICKS]`: without `--hold`, one `useItem` click (reply after 5 ticks,
  `inventory_change`). With `--hold TICKS` (default 40 if the number is omitted, 1..72000),
  a held right-click: `useItem` on the first tick, `keyUse` kept pressed, released with
  `releaseUsingItem` when the use finishes or TICKS elapse, then 5 settle ticks. Reply:
  `{ok, inventory_change, result, ended_by: finished|hold_elapsed|not_usable, held_ticks,
  food, saturation, health}`.
- `key NAME [press|release|tap] [--ticks N]`: presses a `KeyMapping` found by name (its
  translation key, e.g. `key.jump`, `key.sneak`, `keybinds.combat_roll.roll`) in
  `options.keyMappings`. Uses `KeyMapping.set(key, down)` and `KeyMapping.click(key)` on press,
  like vanilla input (so a shared physical key presses every mapping bound to it). Mode
  defaults to `tap`: held for N ticks (default 1, 1..1200), then released; reply after the
  release. `press` / `release` reply at once. Reply: `{ok, key, mode, ticks}` (`ticks` = held
  ticks for tap, 0 otherwise). Unknown name: `not_found`; unbound key: `bad_request`.
- `use-at X Y Z` (block-space doubles): aims the view at the point (`lookAt` from the eye),
  ray-traces from the eye through the point with `ClipContext.Block.OUTLINE` (ray extended one
  block past the point), and calls `MultiPlayerGameMode.useItemOn(player, MAIN_HAND, hit)` with
  that hit. No block on the ray: `not_found`. Reply after 5 ticks: `{ok, hit{pos[x,y,z], face,
  location[x,y,z]}, block_before, block, inventory_change, result}`; `block` is the state at the
  hit block position.

## Python client

```bash
python3 truthctl.py hello
python3 truthctl.py --port 25599 --timeout 20 break 10 64 10
```

Stdlib only. Prints the reply JSON; exit status 1 if the reply has `error`.
