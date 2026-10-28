//! Measured 777 wire tables (Paper 26.3, Mojang-mapped server jar).
//!
//! Source of truth: `docs/runtime-analysis/protocol-767-777.md` +
//! `docs/runtime-analysis/protocol-777-tables.json`. Extracted from the
//! registration order of `addPacket`/`withBundlePacket` in the protocol
//! builders — never guessed.

// Wire-table catalog: constants are consumed incrementally as M4 packet
// translation implements each leg; unused entries stay as measured ground truth.
#![allow(dead_code)]

/// SharedConstants.getProtocolVersion() == `sipush 777` (bytecode-measured).
pub const PROTOCOL_VERSION: i32 = 777;

// --- play clientbound (777) -------------------------------------------------
pub const PC_BUNDLE_DELIMITER: i32 = 0;
pub const PC_ADD_ENTITY: i32 = 1;
pub const PC_BLOCK_UPDATE: i32 = 8;
pub const PC_CHUNK_BATCH_FINISHED: i32 = 11;
pub const PC_CHUNK_BATCH_START: i32 = 12;
pub const PC_DISCONNECT: i32 = 32;
pub const PC_ENTITY_POSITION_SYNC: i32 = 35;
pub const PC_KEEP_ALIVE: i32 = 45;
pub const PC_LEVEL_CHUNK_WITH_LIGHT: i32 = 46;
pub const PC_LOGIN: i32 = 50;
pub const PC_PING: i32 = 62;
pub const PC_PLAYER_POSITION: i32 = 73;
pub const PC_REMOVE_ENTITIES: i32 = 78;
pub const PC_POST_EFFECTS: i32 = 83;
pub const PC_SET_ENTITY_DATA: i32 = 101;
pub const PC_SYSTEM_CHAT: i32 = 124;
pub const PC_START_CONFIGURATION: i32 = 120;

/// Common-phase dialog packets as embedded in the PLAY protocol.
/// Bundle-227 static `GameProtocols` order: clear=141, show=142 (measured by
/// counting addPacket); the live CI Paper carries ONE extra clientbound packet
/// inserted past remove_entities (remove_entities 77->78, post_effects
/// 82->83 — the documented +1 drift), so the live ids are 142/143.
pub const PC_CLEAR_DIALOG: i32 = 142;
pub const PC_SHOW_DIALOG: i32 = 143;

// --- play serverbound (777) -------------------------------------------------
pub const PS_ACCEPT_TELEPORTATION: i32 = 0;
pub const PS_CHUNK_BATCH_RECEIVED: i32 = 11;
pub const PS_CLIENT_TICK_END: i32 = 13;
pub const PS_CLIENT_INFORMATION: i32 = 14;
pub const PS_CONFIGURATION_ACKNOWLEDGED: i32 = 16;
pub const PS_KEEP_ALIVE: i32 = 28;
pub const PS_MOVE_PLAYER_POS_ROT: i32 = 31;
pub const PS_PING_REQUEST: i32 = 38;
pub const PS_PLAYER_LOADED: i32 = 44;
pub const PS_PONG: i32 = 45;
pub const PS_CUSTOM_PAYLOAD: i32 = 22;

/// Common-phase serverbound custom click (the dialog button click — the
/// F2F v1 hit-testing replacement). Bundle-227: 69 serverbound play packets,
/// custom_click is the LAST (id 68) — serverbound has NO Paper drift
/// (keep_alive 28 / player_loaded 44 / pong 45 live-identical).
pub const PS_CUSTOM_CLICK_ACTION: i32 = 68;

// --- login clientbound (777) ------------------------------------------------
pub const LC_DISCONNECT: i32 = 0;
pub const LC_HELLO: i32 = 1;
pub const LC_LOGIN_FINISHED: i32 = 2;
pub const LC_LOGIN_COMPRESSION: i32 = 3;
pub const LC_CUSTOM_QUERY: i32 = 4;

// --- login serverbound (777) ------------------------------------------------
pub const LS_HELLO: i32 = 0;
pub const LS_LOGIN_ACKNOWLEDGED: i32 = 3;

// --- configuration clientbound (777) ---------------------------------------
pub const CC_COOKIE_REQUEST: i32 = 0;
pub const CC_CUSTOM_PAYLOAD: i32 = 1;
pub const CC_DISCONNECT: i32 = 2;
pub const CC_FINISH_CONFIGURATION: i32 = 3;
pub const CC_KEEP_ALIVE: i32 = 4;
pub const CC_PING: i32 = 5;
pub const CC_REGISTRY_DATA: i32 = 7;
pub const CC_RESOURCE_PACK_PUSH: i32 = 9;
pub const CC_POST_EFFECTS: i32 = 10;
pub const CC_UPDATE_TAGS: i32 = 14;
pub const CC_SELECT_KNOWN_PACKS: i32 = 15;
pub const CC_CODE_OF_CONDUCT: i32 = 20;

// --- configuration serverbound (777) ---------------------------------------
pub const CS_CLIENT_INFORMATION: i32 = 0;
pub const CS_CUSTOM_PAYLOAD: i32 = 2;
pub const CS_FINISH_CONFIGURATION: i32 = 3;
pub const CS_KEEP_ALIVE: i32 = 4;
pub const CS_PONG: i32 = 5;
pub const CS_RESOURCE_PACK: i32 = 6;
pub const CS_SELECT_KNOWN_PACKS: i32 = 7;
pub const CS_ACCEPT_CODE_OF_CONDUCT: i32 = 9;

/// `ServerboundResourcePackPacket.Action` enum ordinals (measured):
/// successfully_loaded=0, declined=1, failed_download=2, accepted=3,
/// downloaded=4, invalid_url=5, failed_reload=6, discarded=7.
pub const RESOURCE_PACK_SUCCESSFULLY_LOADED: i32 = 0;
pub const RESOURCE_PACK_DECLINED: i32 = 1;
pub const RESOURCE_PACK_ACCEPTED: i32 = 3;

/// Entity data serializer ids, 26.3 (25 serializers, `<clinit>` order).
/// 1.21.1 (767) had 21: same 0..=12, then COMPOUND_TAG(13), VILLAGER_DATA(14),
/// OPTIONAL_UNSIGNED_INT(15), POSE(16), ARMADILLO_STATE(17), SNIFFER_STATE(18),
/// VECTOR3(19), QUATERNION(20) — see the protocol doc §5 for the drift table.
pub mod serializers {
    pub const BYTE: i32 = 0;
    pub const INT: i32 = 1;
    pub const LONG: i32 = 2;
    pub const FLOAT: i32 = 3;
    pub const STRING: i32 = 4;
    pub const COMPONENT: i32 = 5;
    pub const ITEM_STACK: i32 = 6;
    pub const BLOCK_STATE: i32 = 7;
    pub const BOOLEAN: i32 = 8;
    pub const PARTICLE: i32 = 9;
    pub const ROTATIONS: i32 = 10;
    pub const BLOCK_POS: i32 = 11;
    pub const DIRECTION: i32 = 12;
    pub const VILLAGER_DATA: i32 = 13;
    pub const OPTIONAL_UNSIGNED_INT: i32 = 14;
    pub const POSE: i32 = 15;
    pub const ARMADILLO_STATE: i32 = 16;
    pub const SNIFFER_STATE: i32 = 17;
    pub const WEATHERING_COPPER_STATE: i32 = 18;
    pub const COPPER_GOLEM_STATE: i32 = 19;
    pub const VECTOR3: i32 = 20;
    pub const QUATERNION: i32 = 21;
    pub const RESOLVABLE_PROFILE: i32 = 22;
    pub const HUMANOID_ARM: i32 = 23;
    pub const DYE_COLOR: i32 = 24;
}
