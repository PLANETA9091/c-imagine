# Dialog HUD channel (26.3) — the measured contract for M6

F2V v1 burned 2762 LOC (`ClientOnlyHudSimulator`) because 1.21.1 had NO
server-authored screens: it faked menus with `openMenu` + synthetic widgets +
server-side cursor hit-testing. 26.3 has a native, data-driven, server-authored
screen system — the `dialog` registry + the common-phase show/clear packets —
and Paper exposes it end-to-end (including a server-side callback path). This
document pins the whole surface for the M6 HUD channel: every shape below was
decompiled from the REAL binaries (paper-api 26.3.build.227 + the mojang-mapped
paper-26.3.jar bundle), not guessed.

## 1. Wire surface (all COMMON-phase packets — not play!)

| packet | class | codec shape |
|---|---|---|
| show_dialog | `ClientboundShowDialogPacket` | `Holder<Dialog>` via `Dialog.STREAM_CODEC` = `ByteBufCodecs.holder(Registries.DIALOG, fromCodecWithRegistriesTrusted(DIRECT_CODEC))` — the FULL dialog object is serialized inline (registry-holder: either an inlined object or a registry id); `CONTEXT_FREE_STREAM_CODEC` = id-free full-object variant |
| clear_dialog | `ClientboundClearDialogPacket` | unit codec — singleton INSTANCE, no fields |
| custom_click (serverbound) | `ServerboundCustomClickActionPacket` | `Identifier.STREAM_CODEC` + `Optional<Tag>` (UNTRUSTED: NbtAccounter(32768,16), length-prefixed 65536) |

Being common-phase packets means dialogs work in the CONFIG phase too — the
same phase the resource pack push rides (M5.2). A login screen can be a
server-authored form before the player ever reaches the world. The
`common-packet-drift-767-777.md` "drop with log" policy for 767 (1.21.1 has no
dialog system) stays correct for the embedded-layer direction.

## 2. Vanilla data model (mojang-mapped, paper-26.3.jar)

### 2.1 Registry dispatch

`Dialog.DIRECT_CODEC` dispatches over `BuiltInRegistries.DIALOG_TYPE`
(byNameCodec). Registered types (`DialogTypes.bootstrap`):

| type id | class | extra fields (beyond CommonDialogData) |
|---|---|---|
| `minecraft:notice` | `NoticeDialog` | `action` (ActionButton, optional — default "ok" button) |
| `minecraft:confirmation` | `ConfirmationDialog` | `yes` + `no` ActionButtons |
| `minecraft:multi_action` | `MultiActionDialog` | `actions` (non-empty list), `exit_action` (optional), `columns` (positive int, default 2) |
| `minecraft:dialog_list` | `DialogListDialog` | `dialogs` (HolderSet — links to OTHER dialogs), `exit_action` (optional), `columns`, `button_width` (1..1024) |
| `minecraft:server_links` | `ServerLinksDialog` | `exit_action` (optional), `columns`, `button_width` |

`CommonDialogData.MAP_CODEC`: `title` (Component), `external_title` (optional),
`can_close_with_escape` (default true), `pause` (default true),
`after_action` (DialogAction enum, default CLOSE; **validation: pause=true
requires an after_action that unpauses** — a pack-authored dialog violating
this fails registry load), `body` (COMPACT_LIST_CODEC, default []), `inputs`
(list, default []).

### 2.2 Bodies (`net/minecraft/server/dialog/body`)

| body id | class | fields |
|---|---|---|
| `plain_message` | `PlainMessage` | `contents` (Component), `width` (1..1024, default 200) |
| `item` | `ItemBody` | `item` (ItemStack — full component-ful stack!), `description` (optional Component or PlainMessage) |

ItemBody is the HUD mirror of the item channel: a server-authored screen can
render an arbitrary modded item (component stack) inline — the same
`items/<host>.json` range_dispatch graphics apply.

### 2.3 Inputs (`net/minecraft/server/dialog/input`)

`Input.CODEC` = `{ key` (template variable, `ParsedTemplate.VARIABLE_CODEC`),
`control` (InputControl.MAP_CODEC dispatch) `}`. Controls:

| control id | class | notable fields |
|---|---|---|
| `text` | `TextInput` | `width` (default 200), `label`, `label_max_width`, `initial` (string), `max_length` (default 32), `multiline` (MultilineOptions: max_lines, height) |
| `boolean` | `BooleanInput` | `label`, `initial` (bool), `on_true`/`on_false` (component render labels) |
| `number_range` | `NumberRangeInput` | `start`, `end`, `initial` (within range), `step` (float, optional), `label` |
| `single_option` | `SingleOptionInput` | `entries` (list of {id, label, initial}), `label`, `label_max_width` |

Input values become template parameters: the submit action's command template
is interpolated with `$(key)` before execution (ParsedTemplate).

### 2.4 Actions (`net/minecraft/server/dialog/action`)

`Action.CODEC` dispatches over `DIALOG_ACTION_TYPE`:

| action id | class | fields / behavior |
|---|---|---|
| `dynamic/run_command` | `CommandTemplate` | `template` (ParsedTemplate: `$(key)` placeholders) — executed as the player |
| `dynamic/custom` | `CustomAll` | `id` (Identifier) + `additions` (CompoundTag, optional) — sent back as `ServerboundCustomClickActionPacket` |
| static family | `StaticAction` | every `ClickEvent.Action.isAllowedFromServer()` enum value (open_url, run_command, suggest_command, copy_to_clipboard, change_page, show_dialog, custom) — click event executed client-side |

`ActionButton.MAP_CODEC` = `CommonButtonData` (`label`, `tooltip` optional,
`width` default 150) + `action` (optional — no action = close).

`Dialog.onCancel()` — dialogs may have a cancel action (escape path).

## 3. Paper API surface (the plugin-side channel, 26.3.build.227)

- `io.papermc.paper.dialog.Dialog` (Keyed + adventure `DialogLike`):
  `Dialog.create(Consumer<RegistryBuilderFactory<Dialog, DialogRegistryEntry.Builder>>)`
  — INLINED registry entry creation (no data pack file needed), plus the three
  vanilla instances (custom_options, quick_actions, server_links).
- `DialogRegistryEntry.Builder` (registry data DSL): `base(DialogBase)`,
  `type(...)` etc. `DialogType.*` statics: notice(action), confirmation(yes,no),
  multiAction(actions, exit, columns), dialogList(dialogs, ...), serverLinks(...).
- `DialogBase.builder(title)`: externalTitle, canCloseWithEscape, pause,
  afterAction (DialogBase.DialogAfterAction: CLOSE("close"), NONE("none"),
  WAIT_FOR_RESPONSE("wait_for_response") — mirrors vanilla
  `net.minecraft.server.dialog.DialogAction` BY_ID 0/1/2 with
  `willUnpause() == close || wait_for_response`), body(...), inputs(...).
- Bodies: `DialogBody.plainMessage(Component, width)` / `item(ItemStack,
  description)`; Inputs: `TextDialogInput`, `BooleanDialogInput`,
  `SingleOptionDialogInput`, `NumberRangeDialogInput` builders.
- Show: **`Audience.showDialog(DialogLike)`** (adventure 5.2.0, default no-op;
  Paper's CraftPlayer overrides it → `ClientboundShowDialogPacket`). Any
  Player/Audience target. Clear: Paper exposes the packet via... the
  show-again-or-leave flows; direct clear rides the packet path (M4 bot surface).
- Response: `DialogResponseView` (`payload()` BinaryTagHolder + getText/
  getBoolean/getFloat) — delivered through `ServerboundCustomClickActionPacket`
  handling; **`DialogActionCallback`** (`customClick(callback, options)` —
  Paper registers a callback id server-side and funnels the click) and
  `io.papermc.paper.event.player.PaperPlayerCustomClickEvent` (the event
  surface for raw custom click ids — the F2V v1 cursor-hit-testing
  replacement: the vanilla client does the hit-testing, the server gets a
  semantic event).

## 4. What this replaces from F2F v1 (parity matrix "HUD")

| v1 mechanism | 26.3 replacement |
|---|---|
| synthetic widgets on vanilla menus | dialog inputs (text/boolean/single_option/number_range) — native widgets, native validation |
| bitmap/map tiles in HUD | `ItemDialogBody` (renders component stacks — the items channel graphics) + plain_message |
| server-side cursor hit-testing | vanilla button handling + `custom` click action → server event (semantic, no coordinates) |
| openMenu screens | stay for inventory-grid UIs; dialogs cover form UIs |
| bitmap/map raster fonts (MapRasterFontAssets) | font `reference`/`include` composition (separate M6 surface) |

## 5. c-imagine implementation plan (M6 HUD leg)

1. `F2vPack` emits `data/minecraft/dialog/<ns>/<id>.json` (the SAME
   registry the vanilla data pack path uses — dialogs ride the resource/data
   pack channel already proven in M5.2; no Java needed for static HUDs).
2. Plugin surface `/cim dialog <player> <id>` → paper-api `Registry.DIALOG`
   lookup + `player.showDialog(...)`; a second flavor builds an inlined
   `Dialog.create(...)` from the manifest (proof of the no-file path).
3. Wire leg (cimbot): the config phase can carry show_dialog — assert the
   common-phase id (from the live wire table, dialogs = 140/141 in the play
   census, common ids differ) and the vanilla accept path client-side.
4. Pixel leg: xvfb screenshot of an open dialog (notice + multi_action with
   an ItemDialogBody rendering the cimtest cube stack) — the HUD channel
   joins the pixel contract.
5. Response leg: `dynamic/custom` action with a namespaced id →
   `PaperPlayerCustomClickEvent` → plugin logs `[cim-hud] dialog response` —
   the v1 hit-testing replacement proven live.

## 6. Risks / notes

- `pause=true` + `after_action` validation error is a pack-authoring footgun:
  F2vPack should default to `pause=false, after_action=CLOSE` for HUD dialogs
  (they are overlays, not pause menus).
- The Holder<Dialog> inline wire form means LARGE dialogs ride the packet
  (trust: `fromCodecWithRegistriesTrusted` — components are trusted because
  the SERVER built them; a malicious path cannot occur server-side).
- Single player screen stack: showing a dialog while a menu is open replaces
  the interaction context — the translation layer must serialize HUD frames
  (the v1 rig used the same discipline).
- 1.21.1 (the embedded layer) has NO dialog system: dialogs are a 26.3-only
  CLIENT capability served by c-imagine to STOCK clients — exactly the
  direction of the owner directive (the layer never needs to render them).
