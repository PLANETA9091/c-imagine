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
- Long actions (`break`, `place`, `interact`, `use-item`, `click-slot`, `screenshot`,
  `wait`, `wait-ready`) run as tick-driven tasks from `ClientTickEvent.Post`; one at a
  time (`busy` otherwise). Every game access runs on the client main thread.

Commands: hello, state, wait-ready, block, blocks, inventory, look, look-at-block,
select, break, place, interact, use-item, attack, screen (alias menu), click-slot,
open-inventory, close, command, chat, messages, entities, screenshot, wait.

## Python client

```bash
python3 truthctl.py hello
python3 truthctl.py --port 25599 --timeout 20 break 10 64 10
```

Stdlib only. Prints the reply JSON; exit status 1 if the reply has `error`.
