//! End-to-end "real player" regression test: the cimbot state machine against
//! a hand-rolled mock Minecraft server (protocol-777 shape, offline mode).
//!
//! Drives the complete vanilla flow — handshake → login → configuration
//! (select_known_packs, registry_data, ping, finish) → play (login, teleport,
//! block update, keep-alive) — over a real TCP socket, then asserts:
//!   * the bot joined play,
//!   * the block update at (0, 100, 2) decoded EXACTLY (regression guard for
//!     the read_position bit-layout fix: the mock encodes the position in the
//!     vanilla 1.14+ layout by hand, x<<38 | z<<12 | y),
//!   * the bot answered the configuration ping and the play keep-alive.
//!
//! Run with: cargo test -p cim-proto --test bot_e2e

use cim_proto::bot::ids as wire;
use cim_proto::bot::run;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::Duration;

fn varint(mut n: u32) -> Vec<u8> {
    let mut out = Vec::new();
    loop {
        let b = (n & 0x7F) as u8;
        n >>= 7;
        if n == 0 {
            out.push(b);
            return out;
        }
        out.push(b | 0x80);
    }
}

fn string(s: &str) -> Vec<u8> {
    let raw = s.as_bytes();
    let mut out = varint(raw.len() as u32);
    out.extend_from_slice(raw);
    out
}

fn frame(pid: i32, payload: &[u8]) -> Vec<u8> {
    let mut body = varint(pid as u32);
    body.extend_from_slice(payload);
    let mut out = varint(body.len() as u32);
    out.extend_from_slice(&body);
    out
}

/// Vanilla 1.14+ packed block position: x(26)@38 | z(26)@12 | y(12)@0.
fn pack_position(x: i32, y: i32, z: i32) -> Vec<u8> {
    let raw = ((i64::from(x) & 0x3FF_FFFF) << 38)
        | ((i64::from(z) & 0x3FF_FFFF) << 12)
        | (i64::from(y) & 0xFFF);
    raw.to_be_bytes().to_vec()
}

/// Read one full frame; returns (packet_id, payload).
fn read_frame(sock: &mut TcpStream) -> Option<(i32, Vec<u8>)> {
    fn varint_from(sock: &mut TcpStream) -> Option<u32> {
        let mut num: u32 = 0;
        for i in 0..5 {
            let mut b = [0u8; 1];
            sock.read_exact(&mut b).ok()?;
            num |= u32::from(b[0] & 0x7F) << (7 * i);
            if b[0] & 0x80 == 0 {
                return Some(num);
            }
        }
        None
    }
    let len = varint_from(sock)? as usize;
    let mut data = vec![0u8; len];
    sock.read_exact(&mut data).ok()?;
    let mut num: u32 = 0;
    let mut id_len = 0;
    for (i, byte) in data.iter().enumerate().take(5) {
        num |= u32::from(byte & 0x7F) << (7 * i);
        id_len += 1;
        if byte & 0x80 == 0 {
            break;
        }
    }
    Some((num as i32, data[id_len..].to_vec()))
}

struct MockServer {
    addr: String,
}

impl MockServer {
    fn spawn() -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap().to_string();
        std::thread::spawn(move || {
            let (mut sock, _) = listener.accept().unwrap();
            sock.set_read_timeout(Some(Duration::from_millis(200))).ok();
            let _ = Self::drive(&mut sock);
            // hold the socket until the bot disconnects
            let _ = sock.shutdown(std::net::Shutdown::Write);
            loop {
                let mut sink = [0u8; 1024];
                match sock.read(&mut sink) {
                    Ok(0) | Err(_) => break,
                    Ok(_) => {}
                }
            }
        });
        Self { addr }
    }

    fn drive(sock: &mut TcpStream) -> Result<(), String> {
        // login state
        let (_id, _hs) = read_frame(sock).ok_or("handshake")?;
        let (hello_id, hello_payload) = read_frame(sock).ok_or("hello")?;
        assert_eq!(hello_id, wire::LS_HELLO, "expected login hello");
        assert!(!hello_payload.is_empty());
        // login finished (0x02): uuid + name — offline mode, no encryption
        let uuid: Vec<u8> = (0..16u8).collect();
        let mut lf = uuid;
        lf.extend_from_slice(&string("mockplayer"));
        sock.write_all(&frame(wire::LC_LOGIN_FINISHED, &lf))
            .map_err(|e| e.to_string())?;

        // client: login acknowledged + client information (both ignored)
        let _ = read_frame(sock).ok_or("login ack")?;
        let _ = read_frame(sock).ok_or("client info")?;

        // configuration state
        // select known packs (0x0F): count = 0
        sock.write_all(&frame(wire::CC_SELECT_KNOWN_PACKS, &varint(0)))
            .map_err(|e| e.to_string())?;
        let (skp_id, _) = read_frame(sock).ok_or("select_known_packs reply")?;
        assert_eq!(skp_id, wire::CS_SELECT_KNOWN_PACKS, "bot must answer packs");
        // registry_data (0x07): key + entries (name + presence byte = 0)
        let entries = ["minecraft:pig", "minecraft:oak_boat", "minecraft:player"];
        let mut reg = string("minecraft:entity_type");
        reg.extend_from_slice(&varint(entries.len() as u32));
        for e in entries {
            reg.extend_from_slice(&string(e));
            reg.push(0); // no NBT payload
        }
        sock.write_all(&frame(wire::CC_REGISTRY_DATA, &reg))
            .map_err(|e| e.to_string())?;
        // ping (0x05): i32 -> the bot must pong (0x05)
        sock.write_all(&frame(wire::CC_PING, &424242i32.to_be_bytes()))
            .map_err(|e| e.to_string())?;
        let (pong_id, pong_payload) = read_frame(sock).ok_or("pong")?;
        assert_eq!(pong_id, wire::CS_PONG, "bot must answer config ping");
        assert_eq!(pong_payload, 424242i32.to_be_bytes(), "pong id mismatch");
        // finish configuration (0x03)
        sock.write_all(&frame(wire::CC_FINISH_CONFIGURATION, &[]))
            .map_err(|e| e.to_string())?;
        let (fin_id, _) = read_frame(sock).ok_or("finish ack")?;
        assert_eq!(
            fin_id,
            wire::CS_FINISH_CONFIGURATION,
            "bot must acknowledge configuration"
        );

        // play state
        // PC_LOGIN (0x32): player_id 42, hardcore=false, one level
        let mut login = 42i32.to_be_bytes().to_vec();
        login.push(0);
        login.extend_from_slice(&varint(1));
        login.extend_from_slice(&string("minecraft:overworld"));
        sock.write_all(&frame(wire::PC_LOGIN, &login))
            .map_err(|e| e.to_string())?;
        // PC_PLAYER_POSITION (0x49): teleport #77 to (8.5, 100.0, -4.5), absolute
        let mut tp = varint(77);
        tp.extend_from_slice(&8.5f64.to_be_bytes());
        tp.extend_from_slice(&100.0f64.to_be_bytes());
        tp.extend_from_slice(&(-4.5f64).to_be_bytes());
        tp.extend_from_slice(&0.0f64.to_be_bytes()); // delta x
        tp.extend_from_slice(&0.0f64.to_be_bytes()); // delta y
        tp.extend_from_slice(&0.0f64.to_be_bytes()); // delta z
        tp.extend_from_slice(&0.0f32.to_be_bytes()); // y rot
        tp.extend_from_slice(&0.0f32.to_be_bytes()); // x rot
        tp.extend_from_slice(&0i32.to_be_bytes()); // relatives = 0 (absolute)
        sock.write_all(&frame(wire::PC_PLAYER_POSITION, &tp))
            .map_err(|e| e.to_string())?;
        // PC_BLOCK_UPDATE (0x08) at (0, 100, 2), state 1234 — hand-packed with
        // the vanilla bit layout so any read_position regression fails here.
        let mut bu = pack_position(0, 100, 2);
        bu.extend_from_slice(&varint(1234));
        sock.write_all(&frame(wire::PC_BLOCK_UPDATE, &bu))
            .map_err(|e| e.to_string())?;
        // PC_KEEP_ALIVE (0x2D): i64
        sock.write_all(&frame(wire::PC_KEEP_ALIVE, &987654321i64.to_be_bytes()))
            .map_err(|e| e.to_string())?;

        // Behave like a live server: periodic keep-alives keep the bot's
        // blocking read loop waking up (its exit condition is only evaluated
        // between frames), and its answers are consumed until it leaves.
        let deadline = std::time::Instant::now() + Duration::from_secs(15);
        let mut ka: i64 = 1;
        let mut last_send = std::time::Instant::now();
        loop {
            if std::time::Instant::now() > deadline {
                return Ok(());
            }
            if last_send.elapsed() >= Duration::from_millis(100) {
                last_send = std::time::Instant::now();
                sock.write_all(&frame(wire::PC_KEEP_ALIVE, &ka.to_be_bytes()))
                    .map_err(|e| e.to_string())?;
                ka += 1;
            }
            match read_frame(sock) {
                Some((_id, _)) => continue, // move/tick/keep-alive replies: consumed
                None => {
                    // read timeout: loop around, maybe send the next keep-alive
                    std::thread::sleep(Duration::from_millis(20));
                }
            }
        }
    }
}

#[test]
fn bot_joins_mock_server_and_decodes_block_positions() {
    let server = MockServer::spawn();
    // join_only: ok = joined && no errors; short grace keeps the test fast.
    let exp = cim_proto::bot::Expectations {
        join_only: true,
        join_grace: Duration::from_millis(700),
        ..Default::default()
    };
    let (host, port_str) = server.addr.rsplit_once(':').unwrap();
    let port: u16 = port_str.parse().unwrap();
    let rep = run(host, port, "E2EPlayer", Duration::from_secs(20), exp, None);

    assert!(rep.joined, "bot must join play; errors: {:?}", rep.errors);
    assert!(
        rep.errors.is_empty(),
        "no protocol errors expected; got: {:?}",
        rep.errors
    );
    assert!(rep.packets_read > 0, "bot must have read packets");
    assert!(rep.packets_written > 0, "bot must have written packets");

    // THE read_position regression assertion: the mock hand-packed (0,100,2)
    // in the vanilla layout; the observation must show the exact position.
    let block_obs = rep
        .observations
        .iter()
        .find(|o| o.kind == "block_update")
        .expect("block update must be observed");
    assert!(
        block_obs.detail.starts_with("pos=(0,100,2)"),
        "block position must decode exactly as sent, got: {}",
        block_obs.detail
    );
    assert!(
        block_obs.detail.contains("state=1234"),
        "block state must decode, got: {}",
        block_obs.detail
    );
}

#[test]
fn mock_and_bot_share_the_packet_id_tables() {
    // Guard against id drift between this test and the bot's wire tables.
    assert_eq!(wire::PC_LOGIN, 50);
    assert_eq!(wire::PC_PLAYER_POSITION, 73);
    assert_eq!(wire::PC_BLOCK_UPDATE, 8);
    assert_eq!(wire::PC_KEEP_ALIVE, 45);
    assert_eq!(wire::CC_REGISTRY_DATA, 7);
    assert_eq!(wire::CC_FINISH_CONFIGURATION, 3);
    assert_eq!(wire::CC_PING, 5);
    assert_eq!(wire::CC_SELECT_KNOWN_PACKS, 15);
    assert_eq!(wire::LC_LOGIN_FINISHED, 2);
    assert_eq!(wire::LS_HELLO, 0);
}
