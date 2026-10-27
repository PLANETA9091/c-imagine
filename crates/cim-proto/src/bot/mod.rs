//! M4 vanilla protocol bot (777): joins a real Paper 26.3 server and
//! observes the c-imagine net-contract legs on the wire.
//!
//! Contract (docs/runtime-analysis/protocol-767-777.md §6):
//! 1. full login: handshake → hello → login_ack → configuration (client info,
//!    known packs, registry_data parse → dynamic-registry census) → play;
//! 2. play maintenance: accept teleportation (echoing pos — 777 format),
//!    player_loaded, client_tick_end + move_player.pos_rot ticks, keep_alive,
//!    chunk_batch_received, resource-pack declined;
//! 3. observation legs asserted by the integration matrix:
//!    add_entity(oak_boat via the measured table), set_entity_data for that
//!    id, post_effects apply `[minecraft:creeper]` + clear `[]`,
//!    block_update at the leg position (asserting the translated
//!    `iron_chain[axis=y…` state), remove_entities;
//! 4. frame-skip everything unknown — a bot that kicks on ANY packet fails.

use crate::frame::PacketFrame;
use crate::ProtoError;

mod conn;
mod ids;
mod nbt;
mod packets;
mod vanilla;

use conn::Conn;
use ids as wire;
use packets as pk;

use std::collections::HashMap;
use std::io::Write as _;
use std::time::{Duration, Instant};

/// One observed protocol event (for the JSON report).
#[derive(Debug, Clone, serde::Serialize)]
pub struct Observation {
    pub kind: String,
    pub detail: String,
    pub at_ms: u64,
}

/// Result of the whole run.
#[derive(Debug, serde::Serialize)]
pub struct BotReport {
    pub host: String,
    pub port: u16,
    pub name: String,
    pub joined: bool,
    pub protocol: i32,
    pub registries_parsed: usize,
    pub entity_type_count: usize,
    pub observations: Vec<Observation>,
    pub checks: Vec<Check>,
    pub errors: Vec<String>,
    pub packets_read: u64,
    pub packets_written: u64,
    /// play-phase packet ids the bot frame-skipped (id -> count).
    /// Ground truth for the live wire table (M4 translation layer input).
    pub unknown_ids: std::collections::HashMap<i32, u64>,
    /// every dynamic registry key streamed during configuration (live census,
    /// M5.3 design input: which asset systems are server-synced vs pack-resolved).
    pub registry_names: Vec<String>,
    pub ok: bool,
}

/// One-shot report consumer (used by integration harnesses to forward results).
pub type ReportSink = Box<dyn FnOnce(&BotReport)>;

#[derive(Debug, serde::Serialize)]
pub struct Check {
    pub name: String,
    pub passed: bool,
    pub detail: String,
}

/// What the bot must observe (integration contract).
#[derive(Debug, Clone)]
pub struct Expectations {
    pub entity_type: String, // e.g. "minecraft:oak_boat"
    pub block_at: Option<(i32, i32, i32)>,
    pub post_fx_effect: Option<String>, // e.g. "minecraft:creeper"
    /// Join-only mode: exit ok after `join_grace` seconds of stable play.
    pub join_only: bool,
    pub join_grace: Duration,
}

impl Default for Expectations {
    fn default() -> Self {
        Self {
            entity_type: "minecraft:oak_boat".into(),
            block_at: Some((0, 100, 2)),
            post_fx_effect: Some("minecraft:creeper".into()),
            join_only: false,
            join_grace: Duration::from_secs(10),
        }
    }
}

struct BotState {
    start: Instant,
    phase: Phase,
    entity_types: HashMap<i32, String>,
    registries: usize,
    self_id: i32,
    pos: (f64, f64, f64),
    rot: (f32, f32),
    on_ground: bool,
    joined: bool,
    joined_at: Option<Instant>,
    observations: Vec<Observation>,
    checks: Vec<Check>,
    errors: Vec<String>,
    exp: Expectations,
    // observation legs
    boat: Option<(i32, uuid::Uuid)>, // (wire id, uuid)
    boat_seen_metadata: bool,
    post_fx_seen: Vec<Vec<String>>,
    block_update_seen: bool,
    block_state_seen: String,
    boat_removed: bool,
    /// config-phase resource pack push answered with accepted+loaded (F2V).
    pack_accepted: bool,
    /// every dynamic registry key streamed in the config phase (live census).
    registry_names: Vec<String>,
    unknown_ids: std::collections::HashMap<i32, u64>,
}

enum Phase {
    Login,
    Configuration,
    Play,
}

impl BotState {
    fn check(&mut self, name: &str, passed: bool, detail: String) {
        self.checks.push(Check {
            name: name.into(),
            passed,
            detail,
        });
    }

    fn observe(&mut self, kind: &str, detail: String) {
        self.observations.push(Observation {
            kind: kind.into(),
            detail,
            at_ms: self.start.elapsed().as_millis() as u64,
        });
    }

    fn type_name(&self, id: i32) -> String {
        // static registries are built into the client, not streamed — the
        // measured 777 table is authoritative (vanilla.rs)
        vanilla::entity_type_name(id)
    }

    fn all_expectations_met(&self) -> bool {
        if self.exp.join_only {
            return self
                .joined_at
                .map(|t| t.elapsed() >= self.exp.join_grace)
                .unwrap_or(false);
        }
        let fx_apply = self
            .exp
            .post_fx_effect
            .as_deref()
            .map(|want| {
                self.post_fx_seen
                    .iter()
                    .any(|l| l.len() == 1 && l[0] == want)
            })
            .unwrap_or(true);
        let fx_clear = self.post_fx_seen.iter().any(|l| l.is_empty());
        let block_ok = self
            .exp
            .block_at
            .map(|_p| self.block_update_seen)
            .unwrap_or(true);
        let boat_ok = self
            .boat
            .map(|(_id, _)| self.boat_removed && self.boat_seen_metadata)
            .unwrap_or(false);
        fx_apply && fx_clear && block_ok && boat_ok
    }
}

fn tick_interval() -> Duration {
    Duration::from_millis(50)
}

/// Run the bot against `host:port`. Returns the report (ok flag = all checks).
pub fn run(
    host: &str,
    port: u16,
    name: &str,
    overall_timeout: Duration,
    exp: Expectations,
    report_sink: Option<ReportSink>,
) -> BotReport {
    let start = Instant::now();
    let mut st = BotState {
        start,
        phase: Phase::Login,
        entity_types: HashMap::new(),
        registries: 0,
        self_id: -1,
        pos: (0.0, 0.0, 0.0),
        rot: (0.0, 0.0),
        on_ground: false,
        joined: false,
        joined_at: None,
        observations: Vec::new(),
        checks: Vec::new(),
        errors: Vec::new(),
        boat: None,
        boat_seen_metadata: false,
        post_fx_seen: Vec::new(),
        block_update_seen: false,
        block_state_seen: String::new(),
        boat_removed: false,
        pack_accepted: false,
        registry_names: Vec::new(),
        unknown_ids: std::collections::HashMap::new(),
        exp,
    };

    let addr = format!("{host}:{port}");
    let mut conn = match Conn::connect(&addr, Duration::from_secs(30)) {
        Ok(c) => c,
        Err(e) => {
            st.errors.push(format!("connect: {e}"));
            return finish(st, host, port, name, report_sink);
        }
    };

    // handshake → login
    let hs = PacketFrame::new(0x00, pk::en_handshake(host, port, 2));
    if let Err(e) = conn.write_frame(&hs) {
        st.errors.push(format!("handshake: {e}"));
        return finish(st, host, port, name, report_sink);
    }
    let client_uuid = uuid::Uuid::new_v4();
    let hello = PacketFrame::new(wire::LS_HELLO, pk::en_hello(name, client_uuid));
    if let Err(e) = conn.write_frame(&hello) {
        st.errors.push(format!("hello: {e}"));
        return finish(st, host, port, name, report_sink);
    }
    eprintln!(
        "[cimbot] handshake sent (protocol {}, next=login)",
        wire::PROTOCOL_VERSION
    );

    let deadline = Instant::now() + overall_timeout;
    let mut last_tick = Instant::now();

    'main: loop {
        if Instant::now() > deadline {
            st.errors.push("overall timeout".into());
            break;
        }
        // periodic play maintenance
        if st.joined && last_tick.elapsed() >= tick_interval() {
            last_tick = Instant::now();
            let move_pkt = PacketFrame::new(
                wire::PS_MOVE_PLAYER_POS_ROT,
                pk::en_move_player_pos_rot(
                    st.pos.0,
                    st.pos.1,
                    st.pos.2,
                    st.rot.0,
                    st.rot.1,
                    st.on_ground,
                ),
            );
            let tick_end = PacketFrame::new(wire::PS_CLIENT_TICK_END, pk::en_empty());
            if conn.write_frame(&move_pkt).is_err() || conn.write_frame(&tick_end).is_err() {
                st.errors.push("play write failed (connection lost)".into());
                break;
            }
            // all legs observed -> done
            if st.all_expectations_met() {
                break 'main;
            }
        }
        let frame = match conn.read_frame() {
            Ok(Some(f)) => f,
            Ok(None) => {
                st.errors.push("connection closed by server".into());
                break;
            }
            Err(ProtoError::UnexpectedEnd { .. }) => {
                st.errors.push("frame desync (unexpected end)".into());
                break;
            }
            Err(e) => {
                st.errors.push(format!("read: {e}"));
                break;
            }
        };

        if let Err(e) = handle_frame(&mut st, &mut conn, frame) {
            st.errors.push(e);
            break;
        }
    }

    let mut rep = finish(st, host, port, name, report_sink);
    rep.packets_read = conn.packets_read;
    rep.packets_written = conn.packets_written;
    rep
}

fn handle_frame(st: &mut BotState, conn: &mut Conn, frame: PacketFrame) -> Result<(), String> {
    match st.phase {
        Phase::Login => handle_login(st, conn, frame),
        Phase::Configuration => handle_configuration(st, conn, frame),
        Phase::Play => handle_play(st, conn, frame),
    }
}

fn handle_login(st: &mut BotState, conn: &mut Conn, frame: PacketFrame) -> Result<(), String> {
    match frame.id {
        wire::LC_LOGIN_COMPRESSION => {
            let threshold = pk::parse_login_compression(&frame.payload)
                .map_err(|e| format!("compression: {e}"))?;
            conn.compression = Some(threshold as u32);
            eprintln!("[cimbot] compression enabled (threshold={threshold})");
            Ok(())
        }
        wire::LC_LOGIN_FINISHED => {
            eprintln!("[cimbot] login finished -> configuration");
            st.phase = Phase::Configuration;
            send(conn, wire::LS_LOGIN_ACKNOWLEDGED, pk::en_empty())?;
            send(
                conn,
                wire::CS_CLIENT_INFORMATION,
                pk::en_client_information(),
            )
        }
        wire::LC_DISCONNECT => Err(format!(
            "kicked in login: {}",
            skimmable_text(&frame.payload)
        )),
        wire::LC_HELLO => Err("server asked for encryption (expected offline mode)".into()),
        wire::LC_CUSTOM_QUERY => Err("custom query in login (unexpected)".into()),
        other => Err(format!("unexpected login packet {other}")),
    }
}

fn handle_configuration(
    st: &mut BotState,
    conn: &mut Conn,
    frame: PacketFrame,
) -> Result<(), String> {
    match frame.id {
        wire::CC_SELECT_KNOWN_PACKS => {
            eprintln!("[cimbot] config: select_known_packs -> empty reply");
            send(
                conn,
                wire::CS_SELECT_KNOWN_PACKS,
                pk::en_select_known_packs_empty(),
            )
        }
        wire::CC_REGISTRY_DATA => {
            let data = pk::parse_registry_data(&frame.payload)
                .map_err(|e| format!("registry_data: {e}"))?;
            st.registries += 1;
            // Full dynamic-registry NAME census: ground truth for which asset
            // systems (post_effect? item_definition? model?) are server-synced
            // vs pack-resolved on the client (M5.3 design input, live measured).
            st.registry_names.push(data.key.clone());
            if data.key == "minecraft:entity_type" {
                for (i, name) in data.entry_ids.iter().enumerate() {
                    st.entity_types.insert(i as i32, name.clone());
                }
                eprintln!(
                    "[cimbot] registry entity_type: {} entries (static registries are not streamed; the measured table is authoritative)",
                    data.entry_ids.len()
                );
            }
            Ok(())
        }
        wire::CC_KEEP_ALIVE => {
            let id = pk::parse_keep_alive(&frame.payload).map_err(|e| e.to_string())?;
            send(conn, wire::CS_KEEP_ALIVE, pk::en_keep_alive(id))
        }
        wire::CC_PING => {
            let id = pk::parse_ping(&frame.payload).map_err(|e| e.to_string())?;
            send(conn, wire::CS_PONG, pk::en_pong(id))
        }
        wire::CC_RESOURCE_PACK_PUSH => {
            // uuid (16 bytes) + url + hash + forced + prompt — only the uuid
            // is needed to answer. The F2V 2.0 visual contract REQUIRES the
            // pack: a vanilla client with server packs enabled walks
            // ACCEPTED -> SUCCESSFULLY_LOADED (decline was the M4-era stub).
            let mut c = crate::ReadCursor::new(&frame.payload);
            let id = c.read_uuid().map_err(|e| e.to_string())?;
            eprintln!("[cimbot] config: resource pack push -> accepted (F2V visual pack)");
            send(
                conn,
                wire::CS_RESOURCE_PACK,
                pk::en_resource_pack_action(id, wire::RESOURCE_PACK_ACCEPTED),
            )?;
            send(
                conn,
                wire::CS_RESOURCE_PACK,
                pk::en_resource_pack_action(id, wire::RESOURCE_PACK_SUCCESSFULLY_LOADED),
            )?;
            st.pack_accepted = true;
            st.check(
                "resource pack accepted (F2V)",
                st.pack_accepted,
                "config-phase push -> accepted+successfully_loaded".into(),
            );
            Ok(())
        }
        wire::CC_CODE_OF_CONDUCT => {
            eprintln!("[cimbot] config: code of conduct -> accepted");
            send(conn, wire::CS_ACCEPT_CODE_OF_CONDUCT, pk::en_empty())
        }
        wire::CC_FINISH_CONFIGURATION => {
            let types = st.entity_types.len();
            eprintln!(
                "[cimbot] config finished (registries={}, entity_types={types}) -> play",
                st.registries
            );
            st.check(
                "config dynamic registries parsed",
                st.registries >= 25,
                format!("{}", st.registries),
            );
            st.check(
                "entity_type vanilla table loaded",
                vanilla::entity_type_count() >= 161,
                format!(
                    "{types} streamed (static table: {})",
                    vanilla::entity_type_count()
                ),
            );
            st.phase = Phase::Play;
            send(conn, wire::CS_FINISH_CONFIGURATION, pk::en_empty())
        }
        wire::CC_POST_EFFECTS => {
            let list = pk::parse_post_effects(&frame.payload).map_err(|e| e.to_string())?;
            eprintln!("[cimbot] config post_effects: {list:?}");
            st.post_fx_seen.push(list);
            Ok(())
        }
        wire::CC_DISCONNECT => Err(format!(
            "kicked in configuration: {}",
            skimmable_text(&frame.payload)
        )),
        // cookie_request / custom_payload / update_tags / reset_chat / dialogs:
        // ignorable for the bot contract
        _ => Ok(()),
    }
}

fn handle_play(st: &mut BotState, conn: &mut Conn, frame: PacketFrame) -> Result<(), String> {
    match frame.id {
        wire::PC_LOGIN => {
            let login = pk::parse_play_login(&frame.payload).map_err(|e| e.to_string())?;
            st.self_id = login.player_id;
            st.joined = true;
            st.joined_at = Some(Instant::now());
            eprintln!(
                "[cimbot] joined play (playerId={}, levels={:?})",
                login.player_id, login.levels
            );
            st.observe("join", format!("player_id={}", login.player_id));
            st.check(
                "joined play",
                true,
                format!("player_id={}", login.player_id),
            );
            Ok(())
        }
        wire::PC_PLAYER_POSITION => {
            let tp = pk::parse_player_position(&frame.payload).map_err(|e| e.to_string())?;
            let absolute = tp.relatives == 0;
            if absolute {
                st.pos = (tp.x, tp.y, tp.z);
                st.rot = (tp.y_rot, tp.x_rot);
                st.on_ground = false;
            }
            send(
                conn,
                wire::PS_ACCEPT_TELEPORTATION,
                pk::en_accept_teleportation(
                    tp.teleport_id,
                    st.pos.0,
                    st.pos.1,
                    st.pos.2,
                    st.rot.0,
                    st.rot.1,
                ),
            )?;
            send(conn, wire::PS_PLAYER_LOADED, pk::en_empty())?;
            eprintln!(
                "[cimbot] teleport #{} accepted at ({:.1}, {:.1}, {:.1}) + player_loaded",
                tp.teleport_id, st.pos.0, st.pos.1, st.pos.2
            );
            Ok(())
        }
        wire::PC_KEEP_ALIVE => {
            let id = pk::parse_keep_alive(&frame.payload).map_err(|e| e.to_string())?;
            send(conn, wire::PS_KEEP_ALIVE, pk::en_keep_alive(id))
        }
        wire::PC_PING => {
            let id = pk::parse_ping(&frame.payload).map_err(|e| e.to_string())?;
            send(conn, wire::PS_PING_REQUEST, pk::en_ping_request(id))
        }
        wire::PC_CHUNK_BATCH_FINISHED => send(
            conn,
            wire::PS_CHUNK_BATCH_RECEIVED,
            pk::en_chunk_batch_received(50.0),
        ),
        wire::PC_DISCONNECT => Err(format!(
            "kicked in play: {}",
            skimmable_text(&frame.payload)
        )),
        wire::PC_ADD_ENTITY => {
            let a = pk::parse_add_entity(&frame.payload).map_err(|e| e.to_string())?;
            let ty = st.type_name(a.type_id);
            if ty == st.exp.entity_type {
                eprintln!(
                    "[cimbot] ADD_ENTITY {} uuid={} type={ty} at ({:.1},{:.1},{:.1})",
                    a.id, a.uuid, a.x, a.y, a.z
                );
                st.observe(
                    "add_entity",
                    format!(
                        "id={} uuid={} type={ty} pos=({:.1},{:.1},{:.1})",
                        a.id, a.uuid, a.x, a.y, a.z
                    ),
                );
                if st.boat.is_none() {
                    st.boat = Some((a.id, a.uuid));
                }
            }
            Ok(())
        }
        wire::PC_SET_ENTITY_DATA => {
            let mut c = crate::ReadCursor::new(&frame.payload);
            let id = c.read_varint().map_err(|e| e.to_string())?;
            if st.boat.map(|(bid, _)| bid == id).unwrap_or(false) {
                match pk::parse_entity_data_entries(&mut c) {
                    Ok(entries) => {
                        eprintln!("[cimbot] SET_ENTITY_DATA id={id} entries={}", entries.len());
                        st.boat_seen_metadata = true;
                        st.observe(
                            "set_entity_data",
                            format!(
                                "id={id} entries={:?}",
                                entries
                                    .iter()
                                    .map(|e| (e.index, e.serializer))
                                    .collect::<Vec<_>>()
                            ),
                        );
                    }
                    Err(e) => return Err(format!("entity data walk: {e}")),
                }
            }
            Ok(())
        }
        wire::PC_REMOVE_ENTITIES => {
            let idsv = pk::parse_remove_entities(&frame.payload).map_err(|e| e.to_string())?;
            if let Some((bid, buid)) = st.boat {
                if idsv.contains(&bid) {
                    eprintln!("[cimbot] REMOVE_ENTITIES id={bid} (boat leg observed)");
                    st.observe("remove_entities", format!("id={bid} uuid={buid}"));
                    st.boat_removed = true;
                }
            }
            Ok(())
        }
        wire::PC_POST_EFFECTS => {
            let list = pk::parse_post_effects(&frame.payload).map_err(|e| e.to_string())?;
            eprintln!("[cimbot] POST_EFFECTS: {list:?}");
            st.observe("post_effects", format!("{list:?}"));
            st.post_fx_seen.push(list);
            Ok(())
        }
        wire::PC_BLOCK_UPDATE => {
            let b = pk::parse_block_update(&frame.payload).map_err(|e| e.to_string())?;
            if let Some((ex, ey, ez)) = st.exp.block_at {
                if (b.x, b.y, b.z) == (ex, ey, ez) {
                    let state = vanilla::block_state_string(b.state_id)
                        .unwrap_or_else(|| format!("<unknown#{}>", b.state_id));
                    eprintln!(
                        "[cimbot] BLOCK_UPDATE at ({},{},{}) state={} {state} (leg observed)",
                        b.x, b.y, b.z, b.state_id
                    );
                    st.observe(
                        "block_update",
                        format!("pos=({},{},{}) state={} {state}", b.x, b.y, b.z, b.state_id),
                    );
                    st.block_update_seen = true;
                    st.block_state_seen = state;
                }
            }
            Ok(())
        }
        // Paper 26.3 build drift: SECTION_BLOCKS_UPDATE sits at 84..=86
        // depending on the build (measured: 2557 pkts on id 85 live). The
        // FORMAT is fixed (bytecode-proven), so try-parse and act only when
        // the leg block is actually inside the section.
        id @ 84..=86 => {
            match pk::parse_section_blocks_update(&frame.payload) {
                Ok(sec) => {
                    let (sx, sy, sz) = pk::unpack_section_pos(sec.section_pos_long);
                    if let Some((ex, ey, ez)) = st.exp.block_at {
                        if (ex >> 4, ey >> 4, ez >> 4) == (sx, sy, sz) {
                            let local = ((ey & 15) << 8) | ((ez & 15) << 4) | (ex & 15);
                            for b in &sec.blocks {
                                if (b.local_index as i32) == local {
                                    let state = vanilla::block_state_string(b.state_id)
                                        .unwrap_or_else(|| format!("<unknown#{}>", b.state_id));
                                    eprintln!(
                                        "[cimbot] SECTION_BLOCKS_UPDATE id={id} section=({sx},{sy},{sz}) block ({ex},{ey},{ez}) state={state} (leg observed)"
                                    );
                                    st.observe(
                                        "section_blocks_update",
                                        format!("id={id} section=({sx},{sy},{sz}) state={state}"),
                                    );
                                    st.block_update_seen = true;
                                    st.block_state_seen = state;
                                }
                            }
                        }
                    }
                    Ok(())
                }
                Err(_) => {
                    // not a section packet on this id (build drift) — census it
                    *st.unknown_ids.entry(id).or_insert(0) += 1;
                    Ok(())
                }
            }
        }
        _ => {
            // frame-skip: chunks, light, syncs, chat... — and CENSUS the id:
            // the unknown-id histogram is the live wire-table measurement
            *st.unknown_ids.entry(frame.id).or_insert(0) += 1;
            Ok(())
        }
    }
}

fn send(conn: &mut Conn, id: i32, payload: Vec<u8>) -> Result<(), String> {
    conn.write_frame(&PacketFrame::new(id, payload))
        .map_err(|e| format!("write {id}: {e}"))
}

/// Disconnect payloads are NBT text components; skim the raw bytes for the
/// human-readable marker (best-effort, never fails the run).
fn skimmable_text(payload: &[u8]) -> String {
    let printable: String = payload
        .iter()
        .filter(|b| b.is_ascii_graphic() || **b == b' ')
        .take(120)
        .map(|b| *b as char)
        .collect();
    printable
}

fn finish(
    mut st: BotState,
    host: &str,
    port: u16,
    name: &str,
    report_sink: Option<ReportSink>,
) -> BotReport {
    // final check aggregation
    let fx_apply = st
        .exp
        .post_fx_effect
        .as_deref()
        .map(|want| st.post_fx_seen.iter().any(|l| l.len() == 1 && l[0] == want))
        .unwrap_or(true);
    let fx_clear = st.post_fx_seen.iter().any(|l| l.is_empty());
    let block_ok = st
        .exp
        .block_at
        .map(|_p| st.block_update_seen)
        .unwrap_or(true);
    let boat_ok = st
        .boat
        .map(|(_id, _)| st.boat_removed && st.boat_seen_metadata)
        .unwrap_or(false);
    if st.exp.join_only {
        st.check(
            "join-only session",
            st.joined && st.errors.is_empty(),
            format!("stable {:?} in play", st.exp.join_grace),
        );
    } else {
        st.check(
            "entity leg: add_entity",
            st.boat.is_some(),
            format!("{:?}", st.boat),
        );
        st.check(
            "entity leg: set_entity_data",
            st.boat_seen_metadata,
            "".into(),
        );
        st.check("entity leg: remove_entities", st.boat_removed, "".into());
        st.check(
            "post_effects apply",
            fx_apply,
            format!("{:?}", st.post_fx_seen),
        );
        st.check(
            "post_effects clear",
            fx_clear,
            format!("{:?}", st.post_fx_seen),
        );
        st.check(
            "block_update leg",
            block_ok,
            format!("{:?}", st.exp.block_at),
        );
        // informational: only meaningful when the bot was told to expect the
        // block leg (--expect-block); with --no-expect-block the block fact is
        // proven server-side at the netty level (integration assert).
        if st.exp.block_at.is_some() {
            st.check(
                "block drift state iron_chain[axis=y",
                st.block_state_seen
                    .starts_with("minecraft:iron_chain[axis=y"),
                st.block_state_seen.clone(),
            );
        }
    }

    let ok = if st.exp.join_only {
        st.joined && st.errors.is_empty()
    } else {
        st.joined && st.errors.is_empty() && fx_apply && fx_clear && block_ok && boat_ok
    };
    let report = BotReport {
        host: host.into(),
        port,
        name: name.into(),
        joined: st.joined,
        protocol: wire::PROTOCOL_VERSION,
        registries_parsed: st.registries,
        entity_type_count: st.entity_types.len(),
        observations: st.observations,
        checks: st.checks,
        errors: st.errors,
        packets_read: 0,
        packets_written: 0,
        unknown_ids: st.unknown_ids,
        registry_names: st.registry_names,
        ok,
    };
    if let Some(sink) = report_sink {
        sink(&report);
    }
    let _ = std::io::stdout().flush();
    report
}
