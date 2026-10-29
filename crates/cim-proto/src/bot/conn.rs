//! Blocking TCP connection with the Minecraft frame envelope
//! (length prefix + optional zlib compression after Set Compression).

use crate::frame::{decode_frame, encode_frame, PacketFrame, MAX_PAYLOAD};
use crate::varint::read_varint;
use crate::ProtoError;
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

/// Hard cap on bytes buffered while waiting for a full frame. A server that
/// trickles bytes without ever completing a frame must not grow the buffer
/// without bound.
const MAX_BUFFERED_BYTES: usize = 4 * 1024 * 1024;

pub struct Conn {
    stream: TcpStream,
    buf: Vec<u8>,
    /// zlib threshold (None until login compression arrives).
    pub compression: Option<u32>,
    pub packets_read: u64,
    pub packets_written: u64,
}

impl Conn {
    pub fn connect(addr: &str, timeout: Duration) -> Result<Self, ProtoError> {
        // Honor `timeout` during connect too: a SYN blackhole must not block
        // for the OS-default (~2 min) TCP handshake timeout.
        let addrs: Vec<std::net::SocketAddr> = addr
            .to_socket_addrs()
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp resolve: {e}")))?
            .collect();
        let mut last_err: Option<String> = None;
        let mut stream = None;
        for a in addrs {
            match TcpStream::connect_timeout(&a, timeout) {
                Ok(s) => {
                    stream = Some(s);
                    break;
                }
                Err(e) => last_err = Some(e.to_string()),
            }
        }
        let stream = stream.ok_or_else(|| {
            ProtoError::InvalidFrame(format!(
                "tcp connect: {}",
                last_err.unwrap_or_else(|| "no addresses resolved".into())
            ))
        })?;
        stream
            .set_read_timeout(Some(timeout))
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp timeout: {e}")))?;
        stream
            .set_write_timeout(Some(timeout))
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp timeout: {e}")))?;
        // Minecraft is latency-sensitive (keep-alive/pong): disable Nagle.
        let _ = stream.set_nodelay(true);
        Ok(Self {
            stream,
            buf: Vec::with_capacity(16 * 1024),
            compression: None,
            packets_read: 0,
            packets_written: 0,
        })
    }

    /// Blocking frame read. Returns `Ok(None)` on a clean EOF at a frame
    /// boundary (server closed the connection with nothing buffered).
    ///
    /// An incomplete frame envelope simply asks for more bytes; but once the
    /// full envelope is present, ANY decode failure is a real protocol error —
    /// the body length is already fixed, so more bytes cannot fix it. This is
    /// what prevents the "truncated inner varint → read forever" loop.
    pub fn read_frame(&mut self) -> Result<Option<PacketFrame>, ProtoError> {
        loop {
            if !self.buf.is_empty() {
                if let Some(envelope_len) = frame_envelope_len(&self.buf)? {
                    let (frame, consumed) = decode_frame(&self.buf, self.compression)?;
                    debug_assert_eq!(
                        consumed, envelope_len,
                        "decode_frame must consume exactly the envelope"
                    );
                    self.buf.drain(..consumed);
                    self.packets_read += 1;
                    return Ok(Some(frame));
                }
            }
            if self.buf.len() >= MAX_BUFFERED_BYTES {
                return Err(ProtoError::InvalidFrame(format!(
                    "frame buffer exceeded {MAX_BUFFERED_BYTES} bytes without a complete frame"
                )));
            }
            // need more bytes
            let mut chunk = [0u8; 16 * 1024];
            let n = self
                .stream
                .read(&mut chunk)
                .map_err(|e| ProtoError::InvalidFrame(format!("tcp read: {e}")))?;
            if n == 0 {
                if self.buf.is_empty() {
                    return Ok(None); // clean EOF at a frame boundary
                }
                // EOF mid-frame: not a clean close — surface the desync.
                return Err(ProtoError::InvalidFrame(format!(
                    "connection closed mid-frame with {} buffered bytes",
                    self.buf.len()
                )));
            }
            self.buf.extend_from_slice(&chunk[..n]);
        }
    }

    pub fn write_frame(&mut self, frame: &PacketFrame) -> Result<(), ProtoError> {
        let wire = encode_frame(frame, self.compression)?;
        self.stream
            .write_all(&wire)
            .map_err(|e| ProtoError::InvalidFrame(format!("tcp write: {e}")))?;
        self.packets_written += 1;
        Ok(())
    }
}

/// Decide from the buffered bytes whether a complete frame envelope is present.
///
/// - `Ok(None)`: the length prefix (or the body it announces) is still
///   incomplete — more bytes are needed.
/// - `Ok(Some(len))`: the full envelope is present; `len` is its total size
///   including the length prefix.
/// - `Err(_)`: the length prefix itself is malformed (negative or oversized).
fn frame_envelope_len(buf: &[u8]) -> Result<Option<usize>, ProtoError> {
    let mut pos = 0usize;
    match read_varint(buf, &mut pos) {
        Ok(total) => {
            if total < 0 {
                return Err(ProtoError::InvalidFrame(format!(
                    "negative frame length {total}"
                )));
            }
            let total = total as usize;
            if total > MAX_PAYLOAD {
                return Err(ProtoError::FrameTooLarge(total));
            }
            if buf.len() < pos + total {
                Ok(None)
            } else {
                Ok(Some(pos + total))
            }
        }
        // The prefix varint itself is still streaming in.
        Err(ProtoError::UnexpectedEnd { .. }) => Ok(None),
        // e.g. VarTooLarge: a >5-byte "varint" can never become valid.
        Err(e) => Err(e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cursor::WriteBuf;

    fn server_thread() -> (std::net::SocketAddr, std::sync::mpsc::Sender<Vec<u8>>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let (tx, rx) = std::sync::mpsc::channel::<Vec<u8>>();
        std::thread::spawn(move || {
            if let Ok((mut sock, _)) = listener.accept() {
                for data in rx {
                    if sock.write_all(&data).is_err() {
                        break;
                    }
                }
                let _ = sock.shutdown(std::net::Shutdown::Write);
                // keep the socket open until the test drops the Conn
                loop {
                    let mut sink = [0u8; 4096];
                    match sock.read(&mut sink) {
                        Ok(0) | Err(_) => break,
                        Ok(_) => {}
                    }
                }
            }
        });
        (addr, tx)
    }

    #[test]
    fn envelope_len_incomplete_then_complete() {
        let frame = PacketFrame::new(0x01, vec![1, 2, 3, 4]);
        let wire = encode_frame(&frame, None).unwrap();
        // prefix only, body missing
        assert_eq!(frame_envelope_len(&wire[..2]).unwrap(), None);
        // one byte missing
        assert_eq!(frame_envelope_len(&wire[..wire.len() - 1]).unwrap(), None);
        // complete
        assert_eq!(frame_envelope_len(&wire).unwrap(), Some(wire.len()));
        // truncated varint prefix
        assert_eq!(frame_envelope_len(&[0x80]).unwrap(), None);
        // negative length prefix
        let mut neg = Vec::new();
        crate::varint::write_varint(&mut neg, -5);
        assert!(frame_envelope_len(&neg).is_err());
    }

    #[test]
    fn read_frame_rejects_malformed_body_immediately() {
        // A complete envelope whose inner varint is truncated: the old code
        // looped forever ("need more bytes"); the fix must error at once.
        let mut wire = Vec::new();
        crate::varint::write_varint(&mut wire, 2); // body claims 2 bytes
        wire.push(0x80); // varint continuation byte, no terminator
        let (addr, tx) = server_thread();
        tx.send(wire).unwrap();
        let mut conn = Conn::connect(&addr.to_string(), Duration::from_secs(5)).unwrap();
        let (tx_res, rx_res) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let res = conn.read_frame();
            tx_res.send(res).unwrap();
        });
        let res = rx_res
            .recv_timeout(Duration::from_secs(10))
            .expect("read_frame must NOT hang on a malformed body");
        assert!(res.is_err(), "malformed body must be an error, got {res:?}");
    }

    #[test]
    fn read_frame_returns_none_on_clean_eof() {
        let (addr, tx) = server_thread();
        drop(tx); // accept, then immediately EOF
        let mut conn = Conn::connect(&addr.to_string(), Duration::from_secs(5)).unwrap();
        assert!(conn.read_frame().unwrap().is_none());
    }

    #[test]
    fn read_frame_errors_on_eof_mid_frame() {
        let frame = PacketFrame::new(0x01, vec![9; 64]);
        let mut wire = encode_frame(&frame, None).unwrap();
        wire.truncate(wire.len() - 10); // incomplete body, then EOF
        let (addr, tx) = server_thread();
        tx.send(wire).unwrap();
        drop(tx); // shutdown(Write) after the partial frame
        let mut conn = Conn::connect(&addr.to_string(), Duration::from_secs(5)).unwrap();
        let res = conn.read_frame();
        assert!(
            matches!(&res, Err(ProtoError::InvalidFrame(m)) if m.contains("mid-frame")),
            "EOF mid-frame must surface as desync, got {res:?}"
        );
    }

    #[test]
    fn read_frame_caps_runaway_buffer() {
        // A frame claiming ~63MB but only 4.5MB ever arrives: the buffer cap
        // must trip instead of buffering forever.
        let mut wire = Vec::new();
        crate::varint::write_varint(&mut wire, MAX_PAYLOAD as i32 - 1);
        wire.extend_from_slice(&vec![0u8; 4 * 1024 * 1024 + 512 * 1024]);
        let (addr, tx) = server_thread();
        tx.send(wire).unwrap();
        let mut conn = Conn::connect(&addr.to_string(), Duration::from_secs(5)).unwrap();
        let res = conn.read_frame();
        assert!(
            matches!(&res, Err(ProtoError::InvalidFrame(m)) if m.contains("buffer exceeded")),
            "runaway buffer must be capped, got {res:?}"
        );
    }

    #[test]
    fn read_frame_drains_sequential_frames() {
        let f1 = PacketFrame::new(0x01, b"one".to_vec());
        let f2 = PacketFrame::new(0x02, b"two".to_vec());
        let mut wire = encode_frame(&f1, None).unwrap();
        wire.extend_from_slice(&encode_frame(&f2, None).unwrap());
        let (addr, tx) = server_thread();
        tx.send(wire).unwrap();
        let mut conn = Conn::connect(&addr.to_string(), Duration::from_secs(5)).unwrap();
        assert_eq!(conn.read_frame().unwrap().unwrap(), f1);
        assert_eq!(conn.read_frame().unwrap().unwrap(), f2);
        assert_eq!(conn.packets_read, 2);
    }

    #[test]
    fn connect_timeout_is_honored() {
        let started = std::time::Instant::now();
        // RFC 5737 TEST-NET-1: reserved, reliably non-connectable.
        let res = Conn::connect("192.0.2.1:25565", Duration::from_millis(300));
        let elapsed = started.elapsed();
        assert!(res.is_err(), "connect to TEST-NET must fail");
        assert!(
            elapsed < Duration::from_secs(10),
            "connect timeout must be honored, took {elapsed:?}"
        );
    }

    #[test]
    fn zero_length_write_reaches_the_wire() {
        let (addr, tx) = server_thread();
        let mut conn = Conn::connect(&addr.to_string(), Duration::from_secs(5)).unwrap();
        conn.write_frame(&PacketFrame::new(0x05, Vec::new()))
            .expect("empty payload frame must encode+send");
        assert_eq!(conn.packets_written, 1);
        drop(tx);
    }

    #[test]
    fn write_buf_helpers_still_work() {
        let mut w = WriteBuf::new();
        w.write_string("x");
        assert_eq!(w.len(), 2); // varint(1) + 'x'
        assert!(!w.is_empty());
    }
}
