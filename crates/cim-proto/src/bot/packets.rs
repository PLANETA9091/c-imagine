//! Measured 777 packet codecs for the M4 target set.
//!
//! Decode: the packets the bot OBSERVES. Encode: the packets the bot SENDS.
//! Anything unlisted is frame-skipped by the connection layer (the frame
//! length prefix makes every packet skippable — no desync, no kick).

use crate::bot::ids::serializers;
use crate::bot::nbt;
use crate::ProtoError;
use crate::{ReadCursor, WriteBuf};

// ---------------------------------------------------------------------------
// decode helpers
// ---------------------------------------------------------------------------

pub fn rd_f32(c: &mut ReadCursor) -> Result<f32, ProtoError> {
    Ok(f32::from_be_bytes(c.read_bytes(4)?.try_into().unwrap()))
}

pub fn rd_f64(c: &mut ReadCursor) -> Result<f64, ProtoError> {
    Ok(f64::from_be_bytes(c.read_bytes(8)?.try_into().unwrap()))
}

pub fn rd_i32(c: &mut ReadCursor) -> Result<i32, ProtoError> {
    Ok(i32::from_be_bytes(c.read_bytes(4)?.try_into().unwrap()))
}

pub fn rd_i64(c: &mut ReadCursor) -> Result<i64, ProtoError> {
    Ok(i64::from_be_bytes(c.read_bytes(8)?.try_into().unwrap()))
}

pub fn rd_bool(c: &mut ReadCursor) -> Result<bool, ProtoError> {
    Ok(c.read_u8()? != 0)
}

/// LpVec3 (26.3 bit-packed movement vector) — decode only, value discarded.
pub fn skip_lp_vec3(c: &mut ReadCursor) -> Result<(), ProtoError> {
    let lowest = c.read_u8()?;
    if lowest == 0 {
        return Ok(());
    }
    let _middle = c.read_u8()?;
    let _highest = c.read_bytes(4)?; // unsigned int
    let continuation = lowest & 0x04 != 0;
    if continuation {
        c.read_varint()?;
    }
    Ok(())
}

/// Skip one entity-data VALUE given its serializer id. Complex codecs
/// (particle/component/item_stack/profile) are unsupported — the caller stops
/// the entry walk (frame-safe: the packet boundary is known).
pub fn skip_entity_data_value(c: &mut ReadCursor, serializer: i32) -> Result<bool, ProtoError> {
    use serializers::*;
    match serializer {
        BYTE | BOOLEAN => {
            c.read_u8()?;
        }
        INT
        | BLOCK_STATE
        | DIRECTION
        | POSE
        | ARMADILLO_STATE
        | SNIFFER_STATE
        | WEATHERING_COPPER_STATE
        | COPPER_GOLEM_STATE
        | HUMANOID_ARM
        | DYE_COLOR => {
            c.read_varint()?;
        }
        LONG => {
            rd_i64(c)?;
        }
        FLOAT => {
            rd_f32(c)?;
        }
        STRING => {
            c.read_string(1 << 16)?;
        }
        BLOCK_POS => {
            c.read_position()?;
        }
        ROTATIONS | VECTOR3 => {
            rd_f32(c)?;
            rd_f32(c)?;
            rd_f32(c)?;
        }
        QUATERNION => {
            rd_f32(c)?;
            rd_f32(c)?;
            rd_f32(c)?;
            rd_f32(c)?;
        }
        OPTIONAL_UNSIGNED_INT => {
            if rd_bool(c)? {
                c.read_varint()?;
            }
        }
        VILLAGER_DATA => {
            c.read_varint()?;
            c.read_varint()?;
            c.read_varint()?;
        }
        // unsupported complex values
        COMPONENT | ITEM_STACK | PARTICLE | RESOLVABLE_PROFILE => return Ok(false),
        other => return Err(ProtoError::InvalidFrame(format!("serializer {other}"))),
    }
    Ok(true)
}

/// A parsed set_entity_data entry (value omitted — structural observation).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EntityDataEntry {
    pub index: u8,
    pub serializer: i32,
}

/// Parse entity-data entries until the 0xFF terminator. Returns the entries
/// walked so far; a complex value stops the walk (frame-safe).
pub fn parse_entity_data_entries(c: &mut ReadCursor) -> Result<Vec<EntityDataEntry>, ProtoError> {
    let mut out = Vec::new();
    loop {
        let index = c.read_u8()?;
        if index == 0xFF {
            return Ok(out);
        }
        let serializer = c.read_varint()?;
        out.push(EntityDataEntry { index, serializer });
        if !skip_entity_data_value(c, serializer)? {
            return Ok(out); // walk stopped at a complex value (frame-safe)
        }
    }
}

// ---------------------------------------------------------------------------
// observed packets (decoded structs)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct AddEntity {
    pub id: i32,
    pub uuid: uuid::Uuid,
    /// entity_type registry id (resolved via the measured 777 vanilla table).
    pub type_id: i32,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

pub fn parse_add_entity(payload: &[u8]) -> Result<AddEntity, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let id = c.read_varint()?;
    let uuid = c.read_uuid()?;
    let type_id = c.read_varint()?;
    let x = rd_f64(&mut c)?;
    let y = rd_f64(&mut c)?;
    let z = rd_f64(&mut c)?;
    skip_lp_vec3(&mut c)?;
    let _x_rot = c.read_u8()?;
    let _y_rot = c.read_u8()?;
    let _y_head = c.read_u8()?;
    let _data = c.read_varint()?;
    Ok(AddEntity {
        id,
        uuid,
        type_id,
        x,
        y,
        z,
    })
}

pub struct PlayerPosition {
    pub teleport_id: i32,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub y_rot: f32,
    pub x_rot: f32,
    /// relatives bitmask (i32).
    pub relatives: i32,
}

pub fn parse_player_position(payload: &[u8]) -> Result<PlayerPosition, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let teleport_id = c.read_varint()?;
    let x = rd_f64(&mut c)?;
    let y = rd_f64(&mut c)?;
    let z = rd_f64(&mut c)?;
    // delta f64 x3
    rd_f64(&mut c)?;
    rd_f64(&mut c)?;
    rd_f64(&mut c)?;
    let y_rot = rd_f32(&mut c)?;
    let x_rot = rd_f32(&mut c)?;
    let relatives = rd_i32(&mut c)?;
    Ok(PlayerPosition {
        teleport_id,
        x,
        y,
        z,
        y_rot,
        x_rot,
        relatives,
    })
}

pub struct BlockUpdate {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub state_id: i32,
}

pub fn parse_block_update(payload: &[u8]) -> Result<BlockUpdate, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let (x, y, z) = c.read_position()?;
    let state_id = c.read_varint()?;
    Ok(BlockUpdate { x, y, z, state_id })
}

pub fn parse_remove_entities(payload: &[u8]) -> Result<Vec<i32>, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let n = c.read_varint()?;
    let mut ids = Vec::with_capacity(n.clamp(0, 4096) as usize);
    for _ in 0..n {
        ids.push(c.read_varint()?);
    }
    Ok(ids)
}

/// MC unsigned varlong (7 bits per byte, MSB = continuation, little-endian
/// order of groups, up to 10 bytes for 64 bits).
fn rd_varlong(c: &mut ReadCursor) -> Result<i64, ProtoError> {
    let mut value: i64 = 0;
    for i in 0..10 {
        let b = c.read_u8()?;
        value |= ((b & 0x7F) as i64) << (7 * i);
        if b & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(ProtoError::InvalidFrame("varlong too long".into()))
}

/// One block change inside a section update (26.3 format, bytecode-proven:
/// entries are varLong packed `stateId << 12 | localPos & 0xFFF`).
#[derive(Debug, Clone)]
pub struct SectionBlock {
    pub state_id: i32,
    /// local index in the section: `(y & 15) << 8 | (z & 15) << 4 | (x & 15)`.
    pub local_index: u16,
}

#[derive(Debug, Clone)]
pub struct SectionBlocksUpdate {
    /// section coords packed as 26.3 SectionPos.asLong: `x << 42 | y | z << 20`
    /// (22-bit x/z masks, 20-bit y mask — NEW packing vs 1.21.1).
    pub section_pos_long: i64,
    pub blocks: Vec<SectionBlock>,
}

/// ClientboundSectionBlocksUpdatePacket (26.3): SectionPos.LONG + varint count
/// + count × varLong(state << 12 | local).
pub fn parse_section_blocks_update(payload: &[u8]) -> Result<SectionBlocksUpdate, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let section_pos_long = rd_i64(&mut c)?;
    let n = c.read_varint()?;
    let mut blocks = Vec::with_capacity(n.clamp(0, 4096) as usize);
    for _ in 0..n.max(0) {
        let packed = rd_varlong(&mut c)?;
        blocks.push(SectionBlock {
            state_id: ((packed >> 12) & 0x1F_FFFF) as i32,
            local_index: (packed & 0xFFF) as u16,
        });
    }
    Ok(SectionBlocksUpdate {
        section_pos_long,
        blocks,
    })
}

/// Unpack a 26.3 SectionPos long into (x, y, z) section coords.
pub fn unpack_section_pos(v: i64) -> (i32, i32, i32) {
    let x = (v >> 42) as i32;
    let y = (v & 0xF_FFFF) as i32;
    let z = ((v >> 20) & 0x3F_FFFF) as i32;
    // sign-extend the 22/20-bit fields
    let x = if x & 0x20_0000 != 0 {
        x | !0x3F_FFFF
    } else {
        x
    };
    let z = if z & 0x20_0000 != 0 {
        z | !0x3F_FFFF
    } else {
        z
    };
    let y = if y & 0x8_0000 != 0 { y | !0xF_FFFF } else { y };
    (x, y, z)
}

pub fn parse_post_effects(payload: &[u8]) -> Result<Vec<String>, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let n = c.read_varint()?;
    let mut ids = Vec::with_capacity(n.clamp(0, 4096) as usize);
    for _ in 0..n {
        ids.push(c.read_string(1024)?);
    }
    Ok(ids)
}

/// One registry_data packet: key + entry ids (order = network id).
pub struct RegistryData {
    pub key: String,
    pub entry_ids: Vec<String>,
}

pub fn parse_registry_data(payload: &[u8]) -> Result<RegistryData, ProtoError> {
    if std::env::var_os("CIMBOT_DUMP").is_some() {
        let n: usize = payload.len().min(160);
        let hex: Vec<String> = payload[..n].iter().map(|b| format!("{b:02x}")).collect();
        eprintln!(
            "[cimbot-dump] registry_data len={} hex={}",
            payload.len(),
            hex.join(" ")
        );
        let _ = std::fs::write("/tmp/regdata-last.bin", payload);
    }
    let mut c = ReadCursor::new(payload);
    let key = c.read_string(256)?;
    let n = c.read_varint()?;
    let mut entry_ids = Vec::with_capacity(n.clamp(0, 4096) as usize);
    for i in 0..n {
        let before = c.position();
        let name = c.read_string(1024).map_err(|e| {
            ProtoError::InvalidFrame(format!("registry {key} entry[{i}] name @off{before}: {e}"))
        })?;
        let name_dbg = name.clone();
        entry_ids.push(name);
        // optional NBT tag (bool presence byte, then unnamed root)
        let before = c.position();
        let present = c.read_u8().map_err(|e| {
            ProtoError::InvalidFrame(format!(
                "registry {key} entry[{i}] presence @off{before}: {e}"
            ))
        })?;
        if present != 0 {
            // skip within the REMAINING slice (relative coords!) then fold back
            let mut nbt_pos = 0usize;
            nbt::skip_unnamed_root(c.remaining_slice(), &mut nbt_pos).map_err(|e| {
                ProtoError::InvalidFrame(format!(
                    "registry {key} entry[{i}] ({name_dbg}) nbt @rel{nbt_pos}: {e}"
                ))
            })?;
            *c.position_mut() += nbt_pos;
        }
    }
    Ok(RegistryData { key, entry_ids })
}

/// play login (join game): only the fields the bot needs.
pub struct PlayLogin {
    pub player_id: i32,
    pub levels: Vec<String>,
}

pub fn parse_play_login(payload: &[u8]) -> Result<PlayLogin, ProtoError> {
    let mut c = ReadCursor::new(payload);
    let player_id = rd_i32(&mut c)?;
    let _hardcore = rd_bool(&mut c)?;
    let n_levels = c.read_varint()?;
    let mut levels = Vec::new();
    for _ in 0..n_levels.max(0) {
        levels.push(c.read_string(256)?);
    }
    Ok(PlayLogin { player_id, levels })
}

pub fn parse_login_compression(payload: &[u8]) -> Result<i32, ProtoError> {
    let mut c = ReadCursor::new(payload);
    c.read_varint()
}

pub fn parse_keep_alive(payload: &[u8]) -> Result<i64, ProtoError> {
    let mut c = ReadCursor::new(payload);
    rd_i64(&mut c)
}

pub fn parse_ping(payload: &[u8]) -> Result<i32, ProtoError> {
    let mut c = ReadCursor::new(payload);
    rd_i32(&mut c)
}

// ---------------------------------------------------------------------------
// serverbound encoders
// ---------------------------------------------------------------------------

pub fn en_hello(name: &str, uuid: uuid::Uuid) -> Vec<u8> {
    let mut w = WriteBuf::new();
    w.write_string(name);
    w.write_uuid(uuid);
    w.into_vec()
}

pub fn en_handshake(addr: &str, port: u16, next_state: i32) -> Vec<u8> {
    let mut w = WriteBuf::new();
    w.write_varint(crate::bot::ids::PROTOCOL_VERSION);
    w.write_string(addr);
    w.write_u8((port >> 8) as u8);
    w.write_u8((port & 0xFF) as u8);
    w.write_varint(next_state);
    w.into_vec()
}

pub fn en_client_information() -> Vec<u8> {
    // ClientInformation.write: utf, u8, varint, bool, u8, varint, bool, bool, varint
    let mut w = WriteBuf::new();
    w.write_string("en_us");
    w.write_u8(8); // view distance (server may clamp)
    w.write_varint(0); // chat visibility FULL
    w.write_u8(1); // chat colors true
    w.write_u8(0); // model customisation
    w.write_varint(1); // main hand RIGHT
    w.write_u8(0); // text filtering false
    w.write_u8(0); // allows listing false
    w.write_varint(0); // particle status ALL
    w.into_vec()
}

pub fn en_select_known_packs_empty() -> Vec<u8> {
    let mut w = WriteBuf::new();
    w.write_varint(0);
    w.into_vec()
}

/// `ServerboundResourcePackPacket`: uuid + varint action (777 measured).
/// The F2V visual contract sends ACCEPTED then SUCCESSFULLY_LOADED — the
/// same state machine walk a vanilla client with server packs enabled does.
pub fn en_resource_pack_action(id: uuid::Uuid, action: i32) -> Vec<u8> {
    let mut w = WriteBuf::new();
    w.write_uuid(id);
    w.write_varint(action);
    w.into_vec()
}

/// 777 format: teleport id + echoed absolute pos/rot (measured — CHANGED vs 767).
pub fn en_accept_teleportation(
    teleport_id: i32,
    x: f64,
    y: f64,
    z: f64,
    y_rot: f32,
    x_rot: f32,
) -> Vec<u8> {
    let mut w = WriteBuf::new();
    w.write_varint(teleport_id);
    w.write_bytes(&x.to_be_bytes());
    w.write_bytes(&y.to_be_bytes());
    w.write_bytes(&z.to_be_bytes());
    w.write_bytes(&y_rot.to_be_bytes());
    w.write_bytes(&x_rot.to_be_bytes());
    w.into_vec()
}

pub fn en_keep_alive(id: i64) -> Vec<u8> {
    id.to_be_bytes().to_vec()
}

pub fn en_pong(id: i32) -> Vec<u8> {
    id.to_be_bytes().to_vec()
}

pub fn en_ping_request(id: i32) -> Vec<u8> {
    id.to_be_bytes().to_vec()
}

pub fn en_chunk_batch_received(desired: f32) -> Vec<u8> {
    desired.to_be_bytes().to_vec()
}

/// move_player.pos_rot: x,y,z f64, yRot,xRot f32, flags u8 (bit0 onGround).
pub fn en_move_player_pos_rot(
    x: f64,
    y: f64,
    z: f64,
    y_rot: f32,
    x_rot: f32,
    on_ground: bool,
) -> Vec<u8> {
    let mut w = WriteBuf::new();
    w.write_bytes(&x.to_be_bytes());
    w.write_bytes(&y.to_be_bytes());
    w.write_bytes(&z.to_be_bytes());
    w.write_bytes(&y_rot.to_be_bytes());
    w.write_bytes(&x_rot.to_be_bytes());
    w.write_u8(if on_ground { 0x01 } else { 0x00 });
    w.into_vec()
}

pub fn en_empty() -> Vec<u8> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 26.3 SectionPos packing (bytecode-proven asLong): x<<42 | y | z<<20.
    #[test]
    fn section_pos_packing_matches_bytecode() {
        // (x, y, z) = (0, 6, 0) — the section containing y=100
        #[allow(clippy::identity_op)]
        let packed = (0i64 << 42) | (6i64 & 0xF_FFFF) | (0i64 << 20);
        assert_eq!(unpack_section_pos(packed), (0, 6, 0));
        // negative section x: -1 << 42 with sign extension back
        #[allow(clippy::identity_op, clippy::erasing_op)]
        let packed = ((-1i64 & 0x3F_FFFF) << 42) | ((0i64 & 0x3F_FFFF) << 20);
        assert_eq!(unpack_section_pos(packed), (-1, 0, 0));
    }

    /// SectionBlocksUpdate round trip: long sectionPos + varint count +
    /// varLong(state << 12 | local). Encoded by hand in the measured format.
    #[test]
    fn section_blocks_update_parse() {
        #[allow(clippy::identity_op, clippy::erasing_op)]
        let section_long = (0i64 << 42) | 6i64 | (0i64 << 20); // section (0,6,0)
        let state: i64 = 900; // arbitrary state id
        #[allow(clippy::identity_op)]
        let local: u64 = ((4u64) << 8) | (2 << 4) | 0; // block (0,100,2)
        let mut payload = Vec::new();
        payload.extend_from_slice(&section_long.to_be_bytes());
        // varint count = 1
        payload.push(1);
        // varLong packed — 900 << 12 | 1056
        let packed = (state << 12) | (local as i64);
        let mut v = packed as u64;
        loop {
            if v < 0x80 {
                payload.push(v as u8);
                break;
            }
            payload.push((v as u8 & 0x7F) | 0x80);
            v >>= 7;
        }
        let sec = parse_section_blocks_update(&payload).expect("parse");
        assert_eq!(sec.section_pos_long, section_long);
        assert_eq!(sec.blocks.len(), 1);
        assert_eq!(sec.blocks[0].state_id, 900);
        assert_eq!(sec.blocks[0].local_index, 1056);
        // the block (0,100,2) maps to local 1056 in section (0,6,0)
        #[allow(clippy::identity_op, clippy::erasing_op)]
        let local_check = ((100 & 15) << 8) | ((2 & 15) << 4) | (0 & 15);
        assert_eq!(local_check, 1056);
    }

    /// Resource pack answer: uuid BE + varint action. The F2V visual contract
    /// sends ACCEPTED(3) then SUCCESSFULLY_LOADED(0) — the vanilla-client walk.
    #[test]
    fn resource_pack_action_encoding() {
        let id = uuid::Uuid::from_bytes([7u8; 16]);
        let acc = en_resource_pack_action(id, crate::bot::ids::RESOURCE_PACK_ACCEPTED);
        assert_eq!(&acc[..16], &[7u8; 16]);
        assert_eq!(acc[16], 3);
        assert_eq!(acc.len(), 17);
        let ok = en_resource_pack_action(id, crate::bot::ids::RESOURCE_PACK_SUCCESSFULLY_LOADED);
        assert_eq!(ok[16], 0);
        // declined stays available through the shared encoder (M4-era stub)
        let dec = en_resource_pack_action(id, crate::bot::ids::RESOURCE_PACK_DECLINED);
        assert_eq!(dec[16], 1);
    }
}
