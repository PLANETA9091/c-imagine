# Wire protocol 767 ↔ 777 — measured ground truth (M4)

Method (never guesses): the Mojang-mapped Paper 26.3 server jar (paperclip b226,
`paper-26.3.jar` in `versions/26.3/` after patching) is itself the mapping source —
Mojang dropped public `client_mappings`/`server_mappings` from 26.x version
metadata, but Paper's runtime is Mojang-mapped, so `net/minecraft/network/**`
decompiles directly (Vineflower 1.11.1) and `SharedConstants.getProtocolVersion()`
is literally `sipush 777` in bytecode.

- Extractor: `scripts/extract-protocol-tables.py` (parses `addPacket`/`withBundlePacket`
  registration order per protocol state — wire id = registration index).
- Full tables: `runtime-analysis/protocol-777-tables.json`
  (handshake 1, status 2/2, login 6/5, configuration 21/10, play 144/69) —
  regenerate via the extractor + `scripts/fetch-decompile-inputs.sh` inputs.
- Entity-data serializers: 1.21.1 order read from the relocated NeoForge
  21.1.265 server jar the same way.
- Static registry ids (entity_type 161, blockstates 35723): measured by
  `scripts/probe-registry-ids.java` against the mojang-mapped server jar —
  `crates/cim-proto/data/*.tsv`, embedded into the bot via `include_str!`.

## 1. Protocol constants

| constant | 1.21.1 (767) | 26.3 (777) | measured |
|---|---|---|---|
| protocol version | 767 | **777** | `sipush 777` in `SharedConstants` |
| play clientbound packets | 139 | **144** | registration order |
| play serverbound packets | 67 | **69** | registration order |
| entity data serializers | 21 | **25** | `<clinit>` order |
| entity_type registry | 161 | 161 | probe (static, NOT streamed) |
| blockstate ids | ~26k | **35723** | probe (client-side derivation) |

## 2. Registration mechanics (why id = index)

Every protocol state builds two tables (clientbound/serverbound) via
`ProtocolInfoBuilder.<flow>Protocol(state, builder -> builder.addPacket(TYPE, CLASS.CODEC)...)`.
The wire id of a packet is its **position in that chain**. Special case:
`builder.withBundlePacket(CLIENTBOUND_BUNDLE, Bundler::new, new Delimiter())`
**consumes an id at its chain position** — in play clientbound it is FIRST,
so `bundle_delimiter` = play clientbound id 0 and everything after shifts
by one relative to a naive addPacket count. The extractor merges both call
shapes into one ordered stream.

Multi-codec packets exist (`ServerboundCommandSuggestionPacket` has a
CHAT_ONLY variant selected by a protocol Context) — the primary codec is what
the bot/translator uses.

## 3. M4-relevant ids (play)

clientbound (777): `bundle_delimiter`=0, `add_entity`=1, `block_update`=8,
`chunk_batch_finished`=11, `chunk_batch_start`=12, `disconnect`=32 (common),
`entity_position_sync`=35, `keep_alive`=45 (common), `level_chunk_with_light`=46,
`login`=50, `ping`=62, `player_position`=73, `remove_entities`=78,
**`post_effects`=83**, `set_entity_data`=101, `start_configuration`=120,
`system_chat`=124.

serverbound (777): `accept_teleportation`=0, `chunk_batch_received`=11,
`client_tick_end`=13, `client_information`=14 (common),
`configuration_acknowledged`=16, `keep_alive`=28 (common),
`move_player.pos`=30, `move_player.pos_rot`=31, `move_player.rot`=32,
`move_player.status_only`=33, `custom_payload`=22, `ping_request`=38,
**`player_loaded`=44**, `pong`=45.

NEW in 777 vs 767: `player_loaded` (server waits for it before treating the
client as in-world), `punch`, `game_rule_values`, `mount_screen_open`,
`player_rotation`, `tracked_waypoint`, `clear/show_dialog` family,
`low_disk_space_warning`, `projectile_power`.

configuration (777) clientbound: `cookie_request`=0, `custom_payload`=1,
`disconnect`=2, `finish_configuration`=3, `keep_alive`=4, `ping`=5,
`reset_chat`=6, `registry_data`=7, `resource_pack_pop`=8,
`resource_pack_push`=9, **`post_effects`=10**, `store_cookie`=11,
`transfer`=12, `update_enabled_features`=13, `update_tags`=14,
`select_known_packs`=15, `custom_report_details`=16, `server_links`=17,
`clear_dialog`=18, `show_dialog`=19, **`code_of_conduct`=20** (NEW state).
serverbound: `client_information`=0, `cookie_response`=1, `custom_payload`=2,
`finish_configuration`=3, `keep_alive`=4, `pong`=5, `resource_pack`=6,
`select_known_packs`=7, `custom_click_action`=8, **`accept_code_of_conduct`=9**.

**`post_effects` is registered in BOTH configuration (10) and play (83)** — one
packet class (`CommonPacketTypes.CLIENTBOUND_POST_EFFECTS`), two wire ids.

## 4. Measured codecs (M4 target set)

| packet | fields (wire order) |
|---|---|
| `ClientboundPostEffectsPacket` | `list<Identifier>`: varint count + (varint len + utf8) each. Empty list = clear all; non-empty = the requested set. |
| `ClientboundAddEntityPacket` | varint id, uuid, **entity_type = registry varint** (client-side registry), x/y/z f64, movement **LpVec3** (u8 lowest — 0 ⇒ zero; u8 mid, u32 high; scale = low 2 bits; continuation bit 0x04 extends scale via varint; each axis 15 bits ⇒ `min(v&32767,32766)*2/32766-1`), xRot i8, yRot i8, yHeadRot i8, varint data |
| `ClientboundSetEntityDataPacket` | varint id, then values: per value `u8 index, varint serializer_id, <serializer value>`, terminator `0xFF` |
| `ClientboundRemoveEntitiesPacket` | varint count + varint ids |
| `ClientboundBlockUpdatePacket` | BlockPos (i64: x 26 \| z 26 \| y 12 signed), **blockstate = registry varint** |
| `ClientboundPlayerPositionPacket` | varint teleportId, PositionMoveRotation (pos f64×3, delta f64×3, yRot f32, xRot f32), relatives i32 bitmask (X0 Y1 Z2 Y_ROT3 X_ROT4 DELTA_X5..ROTATE_DELTA8) |
| `ServerboundAcceptTeleportationPacket` | **CHANGED vs 767**: varint teleportId, x/y/z f64 (echoed absolute), yRot f32, xRot f32 |
| `ServerboundMovePlayerPacket.*` | flags u8 (bit0 onGround, bit1 horizontalCollision); pos: f64×3; pos_rot: +f32 yRot, f32 xRot; status_only: flags only |
| `ClientboundLoginPacket` | i32 playerId, bool hardcore, set<Identifier> levels, varint maxPlayers, varint chunkRadius, varint simDistance, bool reducedDebug, bool showDeathScreen, bool doLimitedCrafting, CommonPlayerSpawnInfo (dim-type codec, Identifier dim, i64 seed, varint gametype, optional gametype, bool debug, bool flat, optional GlobalPos, varint portalCooldown, **varint seaLevel — NEW**), bool onlineMode, bool enforcesSecureChat |
| `ClientboundRegistryDataPacket` | Identifier registryKey, list< PackedRegistryEntry = (Identifier id, optional unnamed NBT tag) >. **Entry order = registry network id**. |
| configuration lifecycle | `select_known_packs` (c→s empty list ok) → `registry_data`×N → `update_tags` → `finish_configuration` → s acks `finish_configuration` → PLAY. `code_of_conduct` (if sent) must be answered with `accept_code_of_conduct`. |
| `ClientboundLoginCompressionPacket` | varint threshold (login state, id 3) |
| keep-alive / ping | keep_alive: i64; config ping: i32 → s `pong` i32; status ping: i64 |
| `ServerboundResourcePackPacket` | uuid + varint enum Action: `successfully_loaded`=0, **`declined`=1**, `failed_download`=2, `accepted`=3, `downloaded`=4, `invalid_url`=5, `failed_reload`=6, `discarded`=7 |
| `ServerboundClientInformationPacket` | utf language, u8 viewDistance, varint chatVisibility, bool chatColors, u8 modelCustomisation, varint mainHand, bool textFiltering, bool allowsListing, varint particleStatus |
| `ServerboundChunkBatchReceivedPacket` | f32 desiredChunksPerTick |
| `ServerboundPlayerLoadedPacket` | unit (send after accepting the post-login teleport) |
| `ClientboundEntityPositionSyncPacket` | varint id, PositionPath (NEW), f32 yRot, f32 xRot, bool onGround |

Text components (`system_chat`, `disconnect`) ride the NBT component codec
(`ComponentSerialization.TRUSTED(_CONTEXT_FREE)_STREAM_CODEC`) — the bot skips
them structurally (NBT walker).

**MEASURED at runtime (local bare-Paper boot): only 32 DYNAMIC registries are
streamed via registry_data.** Static registries (block, item, entity_type, …)
are built into the client jar — the server never sends them. The bot resolves
static registry ids from `crates/cim-proto/data/*.tsv` (probe output).

## 5. Entity-data serializer drift 767 → 777 (measured `<clinit>` order)

| id 767 | id 777 | serializer |
|---|---|---|
| 0–12 | 0–12 | BYTE, INT, LONG, FLOAT, STRING, COMPONENT, ITEM_STACK, BLOCK_STATE, BOOLEAN, PARTICLE, ROTATIONS, BLOCK_POS, DIRECTION — **unchanged** |
| 13 | — | COMPOUND_TAG **REMOVED in 777** |
| 14 | 13 | VILLAGER_DATA |
| 15 | 14 | OPTIONAL_UNSIGNED_INT |
| 16 | 15 | POSE |
| 17 | 16 | ARMADILLO_STATE |
| 18 | 17 | SNIFFER_STATE |
| — | 18 | WEATHERING_COPPER_STATE **NEW** |
| — | 19 | COPPER_GOLEM_STATE **NEW** |
| 19 | 20 | VECTOR3 |
| 20 | 21 | QUATERNION |
| — | 22 | RESOLVABLE_PROFILE **NEW** |
| — | 23 | HUMANOID_ARM **NEW** |
| — | 24 | DYE_COLOR **NEW** |

Translator rule: entity metadata blobs written by the 1.21.1 layer (767
serializer ids) must re-map ids 13..20 → 777 ids (drop COMPOUND_TAG, shift by
the measured table) before Paper forwards them to a 777 client. COMPOUND_TAG
payloads have no 777 serializer — mods using it get their metadata entry
dropped (with a census log line), not a client kick.

## 6. M4 bot contract (`cim-proto` bot, enforced in integration.yml)

1. connect → handshake (intention 777, next=login) → hello → login_finished →
   login_ack → configuration: client_information, select_known_packs(empty),
   answer keep_alive/ping/code_of_conduct, parse `registry_data` (dynamic
   registries, ~32), ack finish → PLAY.
2. play: parse login, accept teleportation (echoing pos/rot — 777 format),
   send `player_loaded`, tick `client_tick_end` + `move_player.pos_rot`,
   answer keep_alive, reply `chunk_batch_received(50.0)`, decline resource
   packs, **frame-skip every unknown packet** (frame length prefix makes any
   packet skippable — no desync, no kick).
3. observe + assert (drives CI): `add_entity` with type
   `minecraft:oak_boat` (1.21.1 `minecraft:boat` projected through the M3.2
   tables), `set_entity_data` for that id (structural parse), `post_effects`
   `[minecraft:creeper]` then `[]`, `block_update` at the leg position with
   state `minecraft:iron_chain[axis=y…`, `remove_entities` for the boat.
4. exit 0 iff all expected legs observed; JSON report + human log lines.

LOCAL PROOF (2026-10-27, bare Paper 26.3 b226 + full CRUSSTY stack boot):
join-only contract green end-to-end — handshake→login→config(32 registries)→
play→teleport accepted→player_loaded→12 s stable→clean exit ok=true.

## 7. Translator consequences (implementation notes for M4/M5)

- Frame skip + registry-order parsing means ANY future 777 packet addition is
  non-fatal for the bot and for the rewriter — add ids, don't re-parse.
- `post_effects` carries ids only; the client applies its OWN chain jsons.
  Server-side enforcement of "which effects exist" = registry census
  (`getKnownPostEffects`), which the Paper-side control surface mirrors.
- Blockstate ids are NOT in registry_data (client rebuilds them from block
  properties). The bot asserts block updates via the measured 35723-row state
  table (probe output) — `iron_chain[axis=y,waterlogged=false]` asserted by
  exact id.
- Registry data arrives per registry; a bot joining an EMPTY server parses
  32 dynamic registries in well under a second.

## 8. KNOWN BLOCKER — CRUSSTY runtime 0.2.0 net-hooks weave bug (upstream)

M4's first real-client boot hit a latent runtime bug (dormant through M0–M3
because no client ever connected; `ServerHandshakePacketListenerImpl` loads
lazily on the FIRST socket):

- `java.lang.VerifyError: Bad instruction @34: tableswitch` in
  `ServerHandshakePacketListenerImpl.handleIntention`.
- The runtime 0.2.0 (`libcrussty_runtime.so`, catalog `PLANETA9091/crussty-catalog`)
  weaves its built-in NetHooks rules into
  `net/minecraft/server/network/ServerHandshakePacketListenerImpl.handleIntention`
  (also `Connection`, `PacketDecoder`, `PacketEncoder`).
- Measured corruption (byte-diff woven vs disk): the injector prepends a
  3-byte `invokestatic` but shifts every absolute offset by **+1** (not +3)
  and keeps the original switch padding. Result: ALL branch targets land
  2 bytes before the intended instruction; switches additionally fail the
  structural padding check at verify time.
- Evidence: `SchedulerHooks`-woven methods (MinecraftServer.tickServer) verify
  and run fine — the bug is specific to the NetHooks weave path.
- Module-side byte hooks never see the class (the runtime short-circuits
  classes its own rules matched — verified with a dump hook registered via
  `hooks::register_bytes`: zero invocations for this class).
- Upstream fix (owner): in the NetHooks weave path, shift offsets by the true
  prefix length and re-emit switch padding (`pad(new_pc)`), mirroring what
  `cplug-sdk::weave::emit_switch` already does correctly.
- Workaround tracked separately (disk-class pre-shift compensation) until the
  runtime ships the fix.
