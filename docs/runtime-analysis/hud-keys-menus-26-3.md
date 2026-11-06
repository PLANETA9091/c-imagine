# HUD, menus and mod keys on the vanilla 26.3 client

Status 2026-11-06. Everything marked measured was seen on the real 26.3 client
against Paper with the real Create 6.0.10 jar. The CI gallery comparison is running
on `ci/menu-compare` and has no result yet.

## What limits this

- **No live mod behind any of it.** The layer builds registries but has no
  `MinecraftServer` or `ServerPlayer`, so no Create logic runs for a player. These
  channels deliver the surface. A key press, slot change or HUD value does not
  reach mod code.
- **Mod HUDs, screens and key handlers are client classes.** They never run on a
  server. Each surface below is a translation, not the mod's own code running.
- **Client-only mods.** The minimap here is still c-imagine's own map. The target is
  F2V's route: find a map mod's own colour code by scanning and call it with server
  chunks. That needs an adapter, because the code expects 1.21.1 `Level` objects.

## Keys

`KeyCensus` reads registrations out of mod jars, jar-in-jar included. Measured:
Create gives its 6 keys plus Ponder's, with the defaults in the bytecode; BOP and
Dynamic Trees give none.

`/keyboard` is a `multi_action` dialog with `pause: false` and
`after_action: none`: the world keeps running and typing does not reopen the screen.
A click is a custom click. Shift, Ctrl and Alt latch, as does any key in hold mode,
because a button has no key-up. Esc reaches the server through the exit action.
Latched keys stay shown on the HUD. Delivery is `VirtualKeyEvent`.

The dialog screen blurs the world and takes the mouse, and vanilla draws its warning
icon beside the title. None of that can be removed from the server.

## HUD

Technique from BetterHud (MIT, `third_party/betterhud`). A font ascent encodes a
layout id, `core/text.vsh` decodes it, and a boss bar with transparent yellow sprites
carries the text. Each run nets to zero advance so elements cannot push each other.

| Measured | Result |
|---|---|
| Nine anchors, text alignment | exact |
| Server tick, map on vs off | 2.2 ms vs 1.9 ms, walking, one player |
| Full map frame, deflated JSON | 263 B flat, 431 B terrain, 4.5 KB noise |
| Identical frames | not sent |

Limits: the map moves in whole-block steps; text is ASCII only (others print `?`); the
carrier takes the first boss bar line, so real boss bars sit 19 px lower; any real
yellow boss bar loses its bar. Compressed size is an estimate from deflated JSON, not
a wire capture.

Harness accounts (`cimviewer`, `cimbot`) get no HUD, so gallery shots stay comparable.
`-Dcim.hud.debug=true` turns it on for them and adds an anchor probe.

## Screens

Chest menus and the hand-written Create tables are gone. `ScreenScanner` reads every
jar in `mods/` and finds each Screen, its menu and its background without running
client code, following the approach of F2V (read-only reference). `ScreenPack` bakes
each background into one font glyph, and `ScreenHud` draws it with a backdrop and a
pointer. No mod is named anywhere in this path.

Measured: 50 Create screens found, 14 tied to a menu (Create registers 14 menu types),
39 baked with art, 6 of 6 backgrounds equal to hand-read values. Xaero: 30 screens
found. BOP and Dynamic Trees: none. Filter, attribute filter and toolbox were seen on
the real client with their original layout.

Free positioning: the glyph colour carries x and y, and `text.vsh` decodes it, so one
font reaches every pixel. Such glyphs draw white, and runs must disable shadows.

Font glyphs must fit the client's 256px atlas page. A larger bitmap draws nothing.
Scale small art up through the provider's height. Art over 256 wide is not handled.
The client also refuses a whole font if a bitmap's ascent exceeds its height.

Mouse: each tick the change in yaw and pitch moves the pointer, and attack and use are
the buttons. Measured on the real client: pointer follows the mouse, clicks arrive
with coordinates. Limits:

- it updates at most 20 times a second, plus network delay; felt smoothness is unmeasured
- the world turns behind the screen, because the camera really moves
- the pointer stalls while pitch sits at +-90
- Esc is not visible to the server, so sneak closes the screen

Not done yet: items and slots, widgets and text, opening by right-click, and any effect
on the mod. Clicks are only logged. Slot positions come from the CI walk on
`ci/menu-compare`.
