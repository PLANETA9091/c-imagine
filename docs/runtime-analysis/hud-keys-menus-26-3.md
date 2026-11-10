# HUD, menus and mod keys on the vanilla 26.3 client

Status 2026-11-06. Everything marked measured was seen on the real 26.3 client
against Paper with the real Create 6.0.10 jar. The CI gallery comparison is running
on `ci/menu-compare` and has no result yet.

## What limits this

- **Live mod behaviour is M6.3.** The layer builds registries but has no session
  `ServerPlayer` yet, so a key press, slot change or button does not reach mod code.
  M6.3 in the roadmap is the plan that makes every click run the mod's own code.
- **Mod HUDs, screens and key handlers are client classes.** They never run on a
  server. Each surface below is a translation, not the mod's own code running.
- **Client-only mods.** There is no minimap: c-imagine's own was removed on the owner's
  instruction, as it was not a real map mod. The target is F2V's route: find a map mod's
  own colour code by scanning and call it with server chunks. That needs an adapter,
  because the code expects 1.21.1 `Level` objects.

## Keys

`KeyCensus` reads registrations out of mod jars, jar-in-jar included. Measured:
Create gives its 6 keys plus Ponder's, with the defaults in the bytecode; BOP and
Dynamic Trees give none.

`/keyboard` toggles a keyboard floating on the HUD: keycaps over the live game with no
screen open, so the world stays sharp and the player keeps walking. The look direction
moves a pointer and attack and use packets click, with mining and placing blocked while
it is open. `KeyboardLayout` holds the geometry for both drawing and clicking. Modifiers
latch, as does any key in hold mode, because a click has no key-up. Held keys light up,
and stay listed on the HUD after closing. Mod bindings have their own page, twelve at a
time. Delivery is `VirtualKeyEvent`.

## HUD

Technique from BetterHud (MIT, `third_party/betterhud`). A font ascent encodes a
layout id, `core/text.vsh` decodes it, and a boss bar with transparent yellow sprites
carries the text. Each run nets to zero advance so elements cannot push each other.

| Measured | Result |
|---|---|
| Nine anchors, text alignment | exact |
| Identical frames | not sent |

Limits: text is ASCII only (others print `?`); the carrier takes the first boss bar
line, so real boss bars sit 19 px lower; any real yellow boss bar loses its bar.

Harness accounts (`cimviewer`, `cimbot`) get no HUD, so gallery shots stay comparable.
`-Dcim.hud.debug=true` turns it on for them and adds an anchor probe.

## Screens

Chest menus and hand-written tables are gone. The scanners are F2V's own, copied into
`paper-plugin/src/main/java/dev/f2v` (`third_party/f2v/NOTICE.md`). They read every jar
in `mods/` without running client code, and need only ASM. `ScreenPack` bakes each
screen's background and fixed layers into font glyphs, tiled to fit the font atlas,
and caches the result by jar stamp in `c-imagine/f2v/screens.tsv`. `ScreenHud` draws
them with a backdrop, slots, items and a pointer. No mod is named in this path.

Slots, openers and menu classes come from the automatic gallery walk, which opens each
menu through the mod's own code. A layer the bytecode cannot place, such as a shared
inventory panel, is anchored to the captured player slots.

Measured on Create: 38 screens baked, 6 of 6 backgrounds equal to hand-read values, and
the full filter (art, inventory panel, real items) drawn on the real client from inside
Paper. Clicks move real stacks, and closing returns them.

Xaero: the scanner finds the colour pipeline in Xaero's World Map
(`MapBlock.write`, `getPixelColour`), not in the Minimap jar. Calling it needs 1.21.1
`BlockState` and `Level` objects, which the layer does not have yet.

Free positioning: the glyph colour carries x and y, and `text.vsh` decodes it, so one
font reaches every pixel. Such glyphs draw white, and runs must disable shadows.

Font glyphs must fit the client's 256px atlas page. A larger bitmap draws nothing, so
art is tiled at 240px.
The client also refuses a whole font if a bitmap's ascent exceeds its height.

Mouse: each tick the change in yaw and pitch moves the pointer, and attack and use are
the buttons. Measured on the real client: pointer follows the mouse, clicks arrive
with coordinates. Limits:

- it updates at most 20 times a second, plus network delay; felt smoothness is unmeasured
- `CameraLock` holds the view still: the player sits on an invisible seat and the client's
  camera moves to a fixed display entity. A passenger still sends its look every tick
  (`LocalPlayer.sendChanges`), which a player with a foreign camera does not. Measured:
  look 0 to 39 degrees, 0 world pixels moved, with a screen and with the keyboard open.
  Walking on the keyboard is the server moving the seat from the movement keys
- while locked the client hides the first-person hand or hotbar in some states
- the pointer stalls while pitch sits at +-90
- Esc is not visible to the server, so sneak closes the screen and the keyboard

The gallery gates menus. `scripts/cimscene/menu-sheet.py` gives each one GREEN or RED
through the same `join-*.json` the shard verdict reads: GREEN only when every pixel of
the menu's area is identical on both legs. A menu that did not open or a missing frame
is RED. The tutorial toast is off on every gallery client.

Bytecode alone does not cover every mod: AE2's screens are JSON files and Mekanism's are
drawn by code, so the scanner finds art for 0 of AE2's 34 menu screens and 1 of
Mekanism's 67. The next step is F2V's other half, capturing each real screen once with a
real modded client.

`ScreenArt` in the harness mod is that capture. Screens are matched by menu id, because
whole mods share one menu class.

`scripts/hud/click-test.sh` drives a real vanilla client with real mouse input, in the
Easton no-window sandbox, and judges by what the server saw: opening, picking up and
placing stacks, bare panel, one event per click, buttons, returning items on close,
and the floating keyboard, including walking with it open. `screen-sweep.sh` opens every
baked screen and clicks a slot with the real mouse. An empty hand in open air sends no packet, so right click needs a held
item or a block in reach.

Not done yet: live widgets, conditional layers, and any effect on the mod.
