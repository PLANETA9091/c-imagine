# Common packet family drift: 767 (NeoForge 1.21.1) ↔ 777 (Paper 26.3)

Measured ground truth for the M4 packet translation layer. Every row below
comes from either official client mappings (1.21.1, `client.txt` sha1
`2244b6f0...`) or Mojang-mapped 26.3 bytecode (`client-26.3.jar` sha1
`e877b6a0...`, nested server `META-INF/versions/26.3/server-26.3.jar`).
Nothing here is guessed; fixtures and scripts live in `artifacts/`.

## 1. Why the common family matters first

`ClientCommonPacketListener` is the shared parent of the configuration and
play listeners, so every common packet travels in BOTH phases — the
translation layer must map the family once and reuse it for both directions
of both phases. It also contains the two systems F2V 2.0 leans on
(resource packs, post effects), which makes it the highest-value slice of
the 767→777 diff.

## 2. Clientbound (server → modded client)

| # | 767 (1.21.1) | 777 (26.3) | Verdict for the translator |
|---|--------------|------------|----------------------------|
| 1 | `custom_payload` | `custom_payload` | pass-through (both directions; FML handshake rides here) |
| 2 | `custom_report_details` | `custom_report_details` | pass-through |
| 3 | `disconnect` | `disconnect` | pass-through |
| 4 | `keep_alive` | `keep_alive` | pass-through |
| 5 | `ping` | `ping` | pass-through |
| 6 | `resource_pack_pop` | `resource_pack_pop` | pass-through |
| 7 | `resource_pack_push` | `resource_pack_push` | pass-through (F2V pack push) |
| 8 | `server_links` | `server_links` | pass-through |
| 9 | `store_cookie` | `store_cookie` | pass-through |
| 10 | `transfer` | `transfer` | pass-through |
| 11 | `update_tags` | `update_tags` | pass-through |
| — | *(absent)* | `post_effects` | **777-only. Drop with log by default; F2V postfx emulation is the M5/M6 consumer** |
| — | *(absent)* | `show_dialog` | **777-only. Drop with log** (1.21.1 has no dialog system) |
| — | *(absent)* | `clear_dialog` | **777-only. Drop with log** |

Counts: 767 = 11, 777 = 14. The three newcomers are all 26.3 features that
did not exist in 1.21.1; none has a native 767 equivalent to translate into.

## 3. Serverbound (modded client → Paper)

| 767 (1.21.1) | 777 (26.3) | Verdict |
|--------------|------------|---------|
| `client_information` | `client_information` | pass-through |
| `custom_payload` | `custom_payload` | pass-through (FML handshake) |
| `keep_alive` | `keep_alive` | pass-through |
| `pong` | `pong` | pass-through |
| `resource_pack` | `resource_pack` | pass-through (pack acceptance status) |
| *(absent)* | `custom_click_action` | 767 client never sends it; irrelevant for the embedded-client direction |

Counts: 767 = 5, 777 = 6. The serverbound direction is fully compatible.

## 4. `ClientboundPostEffectsPacket` wire format (bytecode-proven)

`STREAM_CODEC = StreamCodec.composite(Identifier.STREAM_CODEC.list(), ...)`:

```
varint count
count × Identifier        // Identifier.STREAM_CODEC is string-backed
                          // (Codec.STRING comapFlatMap), i.e. varint-len UTF-8
```

* `ByteBufCodecs.list()` (no-arg) → unlimited element count; empty list
  (varint 0) means "clear all active post effects" (measured lifecycle:
  `applyPostEffects` / `clearPostEffects` in the client handler chain).
* Packet type name: `minecraft:post_effects` (CommonPacketTypes static init,
  `createClientbound("post_effects")`).
* Phase reach: both configuration and play (common family).
* `cimbot`'s `parse_post_effects` (varint + strings) matches this exactly —
  no change needed; it is now bytecode-validated rather than inferred.

Server-side hooks measured earlier (see `f2v2-client-capabilities.md`):
`ServerPlayer.post_effects`, `sendPostEffects`, `SuggestionProviders.POST_EFFECTS`,
datafix rename `minecraft:post_effect` → `minecraft:post_effects`.

## 5. Translation policy for M4 v1

1. Identity-map the 11 shared clientbound + 5 shared serverbound packets.
2. Drop-with-log `post_effects`, `show_dialog`, `clear_dialog` toward a 767
   client. The log line is the audit trail; a counter per packet id feeds the
   F2V 2.0 emulation work (M5/M6), where `post_effects` becomes the channel
   the pack builder subscribes to.
3. Serverbound needs no new handling for v1.

The registry-driven `update_tags` and the resource-pack family carry the
actual F2V content pipeline (atlases/equipment/item definitions ride the
resource pack), so their pass-through status is what keeps F2V 2.0 viable on
the 1.21.1 runtime: the pack format bridge (48↔121 repack) stays the only
content-side transform, exactly as measured in `cross-version-gap.md`.
