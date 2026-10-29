//! Packet framing: length-prefixed frames with optional zlib compression,
//! exactly the envelope the Minecraft TCP protocol uses between handshake
//! bytes and play payloads.

use crate::cursor::{ReadCursor, WriteBuf};
use crate::varint::{write_varint, MAX_VARINT_LEN};
use crate::ProtoError;
use flate2::read::ZlibDecoder;
use flate2::write::ZlibEncoder;
use flate2::Compression;
use std::io::{Read, Write};

/// Hard cap on a decompressed payload we are willing to produce (64 MiB,
/// matching vanilla's practical limits with margin for large chunk packets).
pub const MAX_PAYLOAD: usize = 64 * 1024 * 1024;

/// A decoded packet: protocol id + payload (uncompressed domain).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PacketFrame {
    pub id: i32,
    pub payload: Vec<u8>,
}

impl PacketFrame {
    pub fn new(id: i32, payload: Vec<u8>) -> Self {
        Self { id, payload }
    }

    pub fn cursor(&self) -> ReadCursor<'_> {
        ReadCursor::new(&self.payload)
    }
}

/// Encode `frame` into a wire-ready buffer.
///
/// - `compression = None`: `varint(len) | varint(id) | payload`.
/// - `compression = Some(threshold)`: `varint(len) | varint(uncompressed_len)
///   | body` where `body` is zlib(payload) when `payload >= threshold`,
///   otherwise raw payload with `uncompressed_len = 0`.
pub fn encode_frame(frame: &PacketFrame, compression: Option<u32>) -> Result<Vec<u8>, ProtoError> {
    if frame.payload.len() > MAX_PAYLOAD {
        return Err(ProtoError::FrameTooLarge(frame.payload.len()));
    }

    // id + payload body (possibly compressed).
    let mut body = WriteBuf::new();
    match compression {
        None => {
            body.write_varint(frame.id);
            body.write_bytes(&frame.payload);
        }
        Some(threshold) => {
            // Packet id lives INSIDE the compressed region (Minecraft protocol
            // semantics): body = varint(uncompressed_len) | zlib(varint(id) | payload).
            let mut inner = WriteBuf::new();
            inner.write_varint(frame.id);
            inner.write_bytes(&frame.payload);
            let inner = inner.into_vec();
            if inner.len() as u32 >= threshold {
                let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
                enc.write_all(&inner)
                    .map_err(|e| ProtoError::InvalidFrame(format!("zlib: {e}")))?;
                let compressed = enc
                    .finish()
                    .map_err(|e| ProtoError::InvalidFrame(format!("zlib: {e}")))?;
                body.write_varint(
                    i32::try_from(inner.len())
                        .map_err(|_| ProtoError::FrameTooLarge(inner.len()))?,
                );
                body.write_bytes(&compressed);
            } else {
                body.write_varint(0);
                body.write_bytes(&inner);
            }
        }
    }

    // Outer length prefix over the body.
    let mut out = Vec::with_capacity(body.len() + MAX_VARINT_LEN);
    let body = body.into_vec();
    write_varint(
        &mut out,
        i32::try_from(body.len()).map_err(|_| ProtoError::FrameTooLarge(body.len()))?,
    );
    out.extend_from_slice(&body);
    Ok(out)
}

/// Decode exactly one frame from `buf` (must start at the length prefix).
/// Returns the frame and the number of bytes consumed.
pub fn decode_frame(
    buf: &[u8],
    compression: Option<u32>,
) -> Result<(PacketFrame, usize), ProtoError> {
    let mut cursor = ReadCursor::new(buf);
    let total = cursor.read_varint()?;
    if total < 0 {
        return Err(ProtoError::InvalidFrame(format!(
            "negative frame length {total}"
        )));
    }
    let total = total as usize;
    if total > MAX_PAYLOAD {
        return Err(ProtoError::FrameTooLarge(total));
    }
    let body = cursor.read_bytes(total)?;
    let consumed = cursor.position();

    match compression {
        None => {
            let mut c = ReadCursor::new(body);
            let id = c.read_varint()?;
            let payload = body[c.position()..].to_vec();
            Ok((PacketFrame::new(id, payload), consumed))
        }
        Some(_) => {
            let mut c = ReadCursor::new(body);
            let uncompressed_len = c.read_varint()?;
            if uncompressed_len < 0 {
                return Err(ProtoError::InvalidFrame(format!(
                    "negative uncompressed length {uncompressed_len}"
                )));
            }
            let rest = &body[c.position()..];
            if uncompressed_len == 0 {
                let mut inner = ReadCursor::new(rest);
                let id = inner.read_varint()?;
                let payload = rest[inner.position()..].to_vec();
                return Ok((PacketFrame::new(id, payload), consumed));
            }
            let expected = uncompressed_len as usize;
            if expected > MAX_PAYLOAD {
                return Err(ProtoError::FrameTooLarge(expected));
            }
            let dec = ZlibDecoder::new(rest);
            let mut payload = Vec::with_capacity(expected.min(1024 * 1024));
            dec.take(MAX_PAYLOAD as u64)
                .read_to_end(&mut payload)
                .map_err(|e| ProtoError::InvalidFrame(format!("zlib: {e}")))?;
            if payload.len() != expected {
                return Err(ProtoError::DecompressFailed { expected });
            }
            let mut inner = ReadCursor::new(&payload);
            let id = inner.read_varint()?;
            let rest_payload = payload[inner.position()..].to_vec();
            Ok((PacketFrame::new(id, rest_payload), consumed))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_payload() -> Vec<u8> {
        let mut p = WriteBuf::new();
        p.write_string("hello c-imagine");
        p.write_position(-1_000_000, -64, 987_654);
        p.into_vec()
    }

    #[test]
    fn roundtrip_uncompressed() {
        let frame = PacketFrame::new(0x2E, sample_payload());
        let wire = encode_frame(&frame, None).unwrap();
        let (back, used) = decode_frame(&wire, None).unwrap();
        assert_eq!(back, frame);
        assert_eq!(used, wire.len());
    }

    #[test]
    fn roundtrip_compressed() {
        let frame = PacketFrame::new(0x27, sample_payload());
        let wire = encode_frame(&frame, Some(16)).unwrap();
        let (back, used) = decode_frame(&wire, Some(16)).unwrap();
        assert_eq!(back, frame);
        assert_eq!(used, wire.len());
    }

    #[test]
    fn roundtrip_compressed_below_threshold_keeps_raw() {
        let frame = PacketFrame::new(0x01, sample_payload());
        let wire = encode_frame(&frame, Some(4096)).unwrap();
        let (back, used) = decode_frame(&wire, Some(4096)).unwrap();
        assert_eq!(back, frame);
        assert_eq!(used, wire.len());
    }

    #[test]
    fn big_payload_compression_roundtrip() {
        let payload = vec![7u8; 300_000];
        let frame = PacketFrame::new(0x55, payload.clone());
        let wire = encode_frame(&frame, Some(1024)).unwrap();
        assert!(
            wire.len() < payload.len() / 10,
            "compressible data must shrink"
        );
        let (back, _) = decode_frame(&wire, Some(1024)).unwrap();
        assert_eq!(back.payload, payload);
    }

    #[test]
    fn trailing_bytes_are_not_consumed() {
        let frame = PacketFrame::new(0x10, sample_payload());
        let mut wire = encode_frame(&frame, None).unwrap();
        wire.extend_from_slice(&[0xDE, 0xAD]);
        let (_, used) = decode_frame(&wire, None).unwrap();
        assert_eq!(used, wire.len() - 2);
    }

    #[test]
    fn corrupt_frames_error_cleanly() {
        // Bad length prefix.
        let mut buf = Vec::new();
        write_varint(&mut buf, -5);
        assert!(decode_frame(&buf, None).is_err());

        // Truncated body.
        let frame = PacketFrame::new(0x01, vec![1, 2, 3, 4]);
        let mut wire = encode_frame(&frame, None).unwrap();
        wire.truncate(wire.len() - 1);
        assert!(decode_frame(&wire, None).is_err());

        // Compression size mismatch.
        let wire = encode_frame(&PacketFrame::new(0x01, vec![1, 2, 3]), Some(1)).unwrap();
        let mut tampered = wire.clone();
        let last = tampered.len() - 1;
        tampered[last] ^= 0xFF;
        assert!(
            decode_frame(&tampered, Some(1)).is_err(),
            "flipping a byte inside a compressed frame must be rejected"
        );
        assert!(
            decode_frame(&wire, Some(1)).is_ok(),
            "the untampered control frame must still decode"
        );
    }

    #[test]
    fn oversized_uncompressed_len_is_rejected() {
        // uncompressed_len just over MAX_PAYLOAD → FrameTooLarge, regardless
        // of what the (tiny) zlib stream actually contains.
        let wire = compressed_frame_with_declared_len(MAX_PAYLOAD + 1, b"tiny");
        let err = decode_frame(&wire, Some(1)).unwrap_err();
        assert!(matches!(err, ProtoError::FrameTooLarge(_)), "got {err:?}");
    }

    #[test]
    fn decompression_bomb_cannot_materialize() {
        // Declares 64 MiB - 1 (under the cap, so the FrameTooLarge guard does
        // not fire) but the stream only holds a few bytes → the decoder must
        // fail on the length mismatch instead of fabricating data.
        let wire = compressed_frame_with_declared_len(MAX_PAYLOAD - 1, b"tiny");
        let err = decode_frame(&wire, Some(1)).unwrap_err();
        assert!(
            matches!(err, ProtoError::DecompressFailed { .. }),
            "got {err:?}"
        );
    }

    #[test]
    fn negative_uncompressed_len_is_rejected() {
        let mut body = Vec::new();
        write_varint(&mut body, -1);
        body.extend_from_slice(&[0x78, 0x9C, 0x03, 0x00, 0x00, 0x00, 0x00, 0x01]); // zlib header
        let mut wire = Vec::new();
        write_varint(&mut wire, body.len() as i32);
        wire.extend_from_slice(&body);
        let err = decode_frame(&wire, Some(1)).unwrap_err();
        assert!(
            matches!(&err, ProtoError::InvalidFrame(m) if m.contains("negative uncompressed")),
            "got {err:?}"
        );
    }

    /// Hand-build a compressed frame declaring `declared` uncompressed bytes
    /// while the zlib stream only holds `actual`.
    fn compressed_frame_with_declared_len(declared: usize, actual: &[u8]) -> Vec<u8> {
        let mut inner = WriteBuf::new();
        inner.write_varint(i32::try_from(declared).unwrap_or(i32::MAX));
        let mut body = inner.into_vec();
        let mut enc = ZlibEncoder::new(Vec::new(), Compression::default());
        enc.write_all(actual).unwrap();
        body.extend_from_slice(&enc.finish().unwrap());
        let mut wire = Vec::new();
        write_varint(&mut wire, body.len() as i32);
        wire.extend_from_slice(&body);
        wire
    }
}
