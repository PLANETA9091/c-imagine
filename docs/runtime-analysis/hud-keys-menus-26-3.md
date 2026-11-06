# HUD, menus and mod keys on the vanilla 26.3 client

Status 2026-11-06. Everything marked measured was seen on the real 26.3 client
against Paper with the real Create 6.0.10 jar. There has been no truth-client
comparison and no CI run yet.

## What limits this

- **No live mod behind any of it.** The layer builds registries but has no
  `MinecraftServer` or `ServerPlayer`, so no Create logic runs for a player. These
  channels deliver the surface. A key press, slot change or HUD value does not
  reach mod code.
- **Mod HUDs, screens and key handlers are client classes.** They never run on a
  server. Each surface below is a translation, not the mod's own code running.
- **Client-only mods.** A minimap mod does nothing on a server, so the minimap here
  is c-imagine's own map, not a port of Xaero's or JourneyMap.

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

## Menus

A server cannot move slots, so `MenuFit` moves the art to the chest grid, and the
picture is one glyph drawn as the chest title. Labels draw before slots, so items sit
on top. Titles use a shader anchor: the client rejects a bitmap with ascent above
height, which also rejects the whole font (`MenuPack --selftest` guards this).

| Menu | Slot error | Panel error | State |
|---|---|---|---|
| filter | 0 | 22 | good |
| package_port | 0 | 15 | good, not seen on client |
| schematic_table | 1 | 17 | good |
| attribute_filter | 1 | 39 | not seen on client |
| crafting_blueprint | 7 | 10 | slot art visibly patched |
| linked_controller | 36 | 10 | slots moved off their art |
| toolbox | 36 | 14 | slots moved off their art |

Errors are pixels. Panel error is how far the mod panel and the inventory panel moved
relative to each other. Not 1:1: the panels move, chest cells that are not slots still
highlight on hover, and gaps show a dark fill because the shared chest texture cannot
be changed.

The other 7 Create menus are listed in `MenuCatalog.UNSUPPORTED`: they are widget
screens with no chest form. Dialogs are the likely route. Geometry is hand-read from
Create's classes, so other mods have no menus yet; a truth-client dump is the generic
path.

Right-click opening uses the pack's carriers: measured for an item, not yet for a
block. Slot contents are not saved.
