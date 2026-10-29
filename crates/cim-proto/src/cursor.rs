//! Reading cursor and write buffer with the primitives the translators need.

use crate::varint::{read_varint, write_varint};
use crate::ProtoError;
use std::net::Ipv4Addr;

/// Cursor over an immutable packet payload.
#[derive(Debug)]
pub struct ReadCursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> ReadCursor<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    pub fn position(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    /// The not-yet-consumed tail of the payload (sub-cursor walking).
    pub fn remaining_slice(&self) -> &'a [u8] {
        &self.buf[self.pos..]
    }

    /// Direct cursor position access for external sub-walkers (NBT skipper).
    pub fn position_mut(&mut self) -> &mut usize {
        &mut self.pos
    }

    pub fn read_u8(&mut self) -> Result<u8, ProtoError> {
        let b = *self.buf.get(self.pos).ok_or(ProtoError::UnexpectedEnd {
            needed: 1,
            offset: self.pos,
        })?;
        self.pos += 1;
        Ok(b)
    }

    pub fn read_bytes(&mut self, n: usize) -> Result<&'a [u8], ProtoError> {
        if self.remaining() < n {
            return Err(ProtoError::UnexpectedEnd {
                needed: n,
                offset: self.pos,
            });
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    pub fn read_varint(&mut self) -> Result<i32, ProtoError> {
        read_varint(self.buf, &mut self.pos)
    }

    /// Varint-length-prefixed UTF-8 string with a hard length guard.
    pub fn read_string(&mut self, max_len: u32) -> Result<String, ProtoError> {
        let len = self.read_varint()?;
        if len < 0 || len as u32 > max_len {
            return Err(ProtoError::StringTooLong {
                len: len as u32,
                limit: max_len,
            });
        }
        let bytes = self.read_bytes(len as usize)?;
        String::from_utf8(bytes.to_vec())
            .map_err(|_| ProtoError::InvalidFrame("invalid utf-8 in string".into()))
    }

    pub fn read_uuid(&mut self) -> Result<uuid::Uuid, ProtoError> {
        let bytes = self.read_bytes(16)?;
        Ok(uuid::Uuid::from_bytes(bytes.try_into().expect("16 bytes")))
    }

    /// Block position packed into an i64 (26/26/12 bit split used since 1.14):
    /// x 26 bits at shift 38, z 26 bits at shift 12, y 12 bits at shift 0 —
    /// mirrors [`WriteBuf::write_position`].
    pub fn read_position(&mut self) -> Result<(i32, i32, i32), ProtoError> {
        let bytes = self.read_bytes(8)?;
        let raw = u64::from_be_bytes(bytes.try_into().expect("8 bytes"));
        let px = sign_extend(raw >> 38, 26);
        let pz = sign_extend(raw >> 12, 26);
        let py = sign_extend(raw, 12);
        Ok((px as i32, py as i32, pz as i32))
    }

    /// Read the legacy server address field: a varint length followed by
    /// either a hostname or an IPv4 literal (no tailing null byte here).
    pub fn read_legacy_address(&mut self) -> Result<String, ProtoError> {
        let len = self.read_varint()?;
        if !(0..=255).contains(&len) {
            return Err(ProtoError::InvalidFrame(format!("address len {len}")));
        }
        let bytes = self.read_bytes(len as usize)?;
        Ok(String::from_utf8_lossy(bytes).into_owned())
    }

    /// Read an IPv4 literal from 4 raw bytes (used in some handshake forms).
    pub fn read_ipv4(&mut self) -> Result<Ipv4Addr, ProtoError> {
        let b = self.read_bytes(4)?;
        Ok(Ipv4Addr::new(b[0], b[1], b[2], b[3]))
    }
}

fn sign_extend(value: u64, bits: u32) -> i64 {
    let shift = 64 - bits;
    ((value << shift) as i64) >> shift
}

/// Growable write buffer with protocol helpers.
#[derive(Debug, Default)]
pub struct WriteBuf {
    inner: Vec<u8>,
}

impl WriteBuf {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn into_vec(self) -> Vec<u8> {
        self.inner
    }

    pub fn len(&self) -> usize {
        self.inner.len()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.is_empty()
    }

    pub fn write_u8(&mut self, v: u8) {
        self.inner.push(v);
    }

    pub fn write_bytes(&mut self, v: &[u8]) {
        self.inner.extend_from_slice(v);
    }

    pub fn write_varint(&mut self, v: i32) {
        write_varint(&mut self.inner, v);
    }

    pub fn write_string(&mut self, s: &str) {
        self.write_varint(i32::try_from(s.len()).unwrap_or(i32::MAX));
        self.write_bytes(s.as_bytes());
    }

    pub fn write_uuid(&mut self, u: uuid::Uuid) {
        self.write_bytes(u.as_bytes());
    }

    /// Block position packed into an i64: x 26 bits (shift 38), z 26 bits
    /// (shift 12), y 12 bits (shift 0) — mirrors [`ReadCursor::read_position`].
    pub fn write_position(&mut self, x: i32, y: i32, z: i32) {
        let raw = ((i64::from(x) & 0x3FF_FFFF) << 38)
            | ((i64::from(z) & 0x3FF_FFFF) << 12)
            | (i64::from(y) & 0xFFF);
        self.write_bytes(&raw.to_be_bytes());
    }
}

// uuid is used by read_uuid/write_uuid; keep it a light, well-vetted dep.

#[cfg(test)]
mod tests {
    use super::*;

    /// The 1.14+ block position layout: x(26)@38, z(26)@12, y(12)@0.
    /// Regression guard for the read/write layout mismatch.
    #[test]
    fn position_roundtrip_matches_vanilla_layout() {
        let cases = [
            (0, 100, 2),
            (1, -60, 3),
            (-1, 319, -1),
            (-33_554_432, -2048, 33_554_431),
            (33_554_431, 2031, -33_554_432),
            (123_456, 0, -98_765),
        ];
        for (x, y, z) in cases {
            let mut w = WriteBuf::new();
            w.write_position(x, y, z);
            let raw = w.into_vec();
            assert_eq!(raw.len(), 8, "packed position must be exactly 8 bytes");
            let mut r = ReadCursor::new(&raw);
            let (rx, ry, rz) = r.read_position().expect("decode packed position");
            assert_eq!(
                (rx, ry, rz),
                (x, y, z),
                "position roundtrip diverged for ({x},{y},{z})"
            );
        }
    }

    /// Byte-level check against the canonical vanilla encoding of a known
    /// coordinate, independent of our own writer (guards both directions).
    #[test]
    fn position_decode_known_bytes() {
        // encode(0, 100, 2) by hand: x<<38 | z<<12 | y
        #[allow(clippy::identity_op)] // keep the x<<38 | z<<12 | y shape explicit
        let raw: u64 = ((0i64 << 38) | (2i64 << 12) | 100i64) as u64;
        let raw_bytes = raw.to_be_bytes();
        let mut r = ReadCursor::new(&raw_bytes);
        assert_eq!(
            r.read_position().unwrap(),
            (0, 100, 2),
            "y must come from the low 12 bits, z from bits 12..38"
        );
    }

    #[test]
    fn position_read_rejects_short_buffer() {
        let mut r = ReadCursor::new(&[0u8; 7]);
        assert!(r.read_position().is_err(), "7 bytes must not decode");
    }

    #[test]
    fn uuid_roundtrip() {
        let u = uuid::Uuid::from_u128(0x0123_4567_89ab_cdef_feed_face_dead_beef);
        let mut w = WriteBuf::new();
        w.write_uuid(u);
        let raw = w.into_vec();
        let mut r = ReadCursor::new(&raw);
        assert_eq!(r.read_uuid().unwrap(), u);
    }

    #[test]
    fn read_string_rejects_negative_and_oversized_lengths() {
        // negative length prefix
        let mut r = ReadCursor::new(&[0xFF, 0xFF, 0xFF, 0xFF, 0x0F, b'a']);
        assert!(r.read_string(16).is_err());
        // length over the limit
        let mut w = WriteBuf::new();
        w.write_varint(32);
        w.write_bytes(&[b'x'; 32]);
        let raw = w.into_vec();
        let mut r = ReadCursor::new(&raw);
        assert!(r.read_string(16).is_err());
        // exactly at the limit is fine
        let mut r = ReadCursor::new(&raw);
        assert_eq!(r.read_string(32).unwrap().len(), 32);
    }

    #[test]
    fn read_u8_rejects_eof() {
        let mut r = ReadCursor::new(&[]);
        assert!(r.read_u8().is_err());
    }
}
