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

    /// Block position packed into an i64 (26/12/38 bit split used since 1.14).
    pub fn read_position(&mut self) -> Result<(i32, i32, i32), ProtoError> {
        let bytes = self.read_bytes(8)?;
        let raw = u64::from_be_bytes(bytes.try_into().expect("8 bytes"));
        let x = 26;
        let z = 12;
        let y = 38;
        let px = sign_extend(raw >> (z + y), x);
        let pz = sign_extend(raw >> y, z);
        let py = sign_extend(raw, y);
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
use uuid;
