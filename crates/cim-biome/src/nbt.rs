//! Network-NBT writer + walker (unnamed root, 1.20.2+ wire format).
//!
//! Registry-data entries carry one optional unnamed NBT tag: a `u8` type byte
//! followed by the payload (no root name). This module writes the small
//! subset the biome codec needs and walks the subset needed to bound an
//! existing entry and read its climate fields.

use crate::BiomeError;

pub const TAG_END: u8 = 0;
pub const TAG_BYTE: u8 = 1;
pub const TAG_SHORT: u8 = 2;
pub const TAG_INT: u8 = 3;
pub const TAG_LONG: u8 = 4;
pub const TAG_FLOAT: u8 = 5;
pub const TAG_DOUBLE: u8 = 6;
pub const TAG_BYTE_ARRAY: u8 = 7;
pub const TAG_STRING: u8 = 8;
pub const TAG_LIST: u8 = 9;
pub const TAG_COMPOUND: u8 = 10;
pub const TAG_INT_ARRAY: u8 = 11;
pub const TAG_LONG_ARRAY: u8 = 12;

/// Cursor over a byte slice with the primitives the packet/NBT readers need.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    pub fn at(buf: &'a [u8], pos: usize) -> Self {
        Self { buf, pos }
    }

    pub fn pos(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> usize {
        self.buf.len().saturating_sub(self.pos)
    }

    pub fn is_empty(&self) -> bool {
        self.remaining() == 0
    }

    pub fn u8(&mut self) -> Result<u8, BiomeError> {
        let b = *self.buf.get(self.pos).ok_or(BiomeError::Truncated)?;
        self.pos += 1;
        Ok(b)
    }

    pub fn bytes(&mut self, n: usize) -> Result<&'a [u8], BiomeError> {
        let end = self.pos.checked_add(n).ok_or(BiomeError::Truncated)?;
        let s = self.buf.get(self.pos..end).ok_or(BiomeError::Truncated)?;
        self.pos = end;
        Ok(s)
    }

    pub fn u16(&mut self) -> Result<u16, BiomeError> {
        Ok(u16::from_be_bytes(self.bytes(2)?.try_into().unwrap()))
    }

    pub fn i32(&mut self) -> Result<i32, BiomeError> {
        Ok(i32::from_be_bytes(self.bytes(4)?.try_into().unwrap()))
    }

    pub fn i64(&mut self) -> Result<i64, BiomeError> {
        Ok(i64::from_be_bytes(self.bytes(8)?.try_into().unwrap()))
    }

    pub fn f32(&mut self) -> Result<f32, BiomeError> {
        Ok(f32::from_bits(u32::from_be_bytes(
            self.bytes(4)?.try_into().unwrap(),
        )))
    }

    /// Signed varint (5 bytes max, like the protocol's VarInt).
    pub fn varint(&mut self) -> Result<i32, BiomeError> {
        let mut value: u32 = 0;
        for shift in (0..35).step_by(7) {
            let b = self.u8()?;
            value |= u32::from(b & 0x7F) << shift;
            if b & 0x80 == 0 {
                return Ok(value as i32);
            }
        }
        Err(BiomeError::BadVarint)
    }

    pub fn string(&mut self, max: usize) -> Result<String, BiomeError> {
        let n = self.varint()?;
        if n < 0 || n as usize > max {
            return Err(BiomeError::BadString);
        }
        let raw = self.bytes(n as usize)?;
        String::from_utf8(raw.to_vec()).map_err(|_| BiomeError::BadString)
    }
}

/// Append a signed VarInt.
pub fn write_varint(out: &mut Vec<u8>, value: i32) {
    let mut v = value as u32;
    loop {
        if v & !0x7F == 0 {
            out.push(v as u8);
            return;
        }
        out.push((v as u8 & 0x7F) | 0x80);
        v >>= 7;
    }
}

/// Small builder for the biome definition blobs.
pub struct NbtWriter {
    out: Vec<u8>,
}

impl Default for NbtWriter {
    fn default() -> Self {
        Self::new()
    }
}

impl NbtWriter {
    pub fn new() -> Self {
        Self { out: Vec::new() }
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.out
    }

    /// Unnamed root: the tag type, then the payload.
    pub fn root_compound(mut self) -> NbtWriter {
        self.out.push(TAG_COMPOUND);
        self
    }

    fn key(&mut self, name: &str) {
        self.out
            .extend_from_slice(&(name.len() as u16).to_be_bytes());
        self.out.extend_from_slice(name.as_bytes());
    }

    pub fn string(mut self, name: &str, value: &str) -> Self {
        self.out.push(TAG_STRING);
        self.key(name);
        self.out
            .extend_from_slice(&(value.len() as u16).to_be_bytes());
        self.out.extend_from_slice(value.as_bytes());
        self
    }

    pub fn float(mut self, name: &str, value: f32) -> Self {
        self.out.push(TAG_FLOAT);
        self.key(name);
        self.out.extend_from_slice(&value.to_bits().to_be_bytes());
        self
    }

    pub fn byte(mut self, name: &str, value: i8) -> Self {
        self.out.push(TAG_BYTE);
        self.key(name);
        self.out.push(value as u8);
        self
    }

    pub fn compound(mut self, name: &str, body: impl FnOnce(NbtWriter) -> NbtWriter) -> Self {
        self.out.push(TAG_COMPOUND);
        self.key(name);
        self = body(self);
        self.out.push(TAG_END);
        self
    }

    /// Empty list of `elem` (9 = list of lists for `features`).
    pub fn empty_list(mut self, name: &str, elem: u8) -> Self {
        self.out.push(TAG_LIST);
        self.key(name);
        self.out.push(elem);
        // NBT list length is a 4-byte int (names are u16, lengths int).
        self.out.extend_from_slice(&0i32.to_be_bytes());
        self
    }

    pub fn end(mut self) -> Self {
        self.out.push(TAG_END);
        self
    }
}

/// Skip one unnamed root tag, returning the position after it.
pub fn skip_root(buf: &[u8], pos: &mut usize) -> Result<(), BiomeError> {
    let mut r = Reader::at(buf, *pos);
    let ty = r.u8()?;
    skip_payload(&mut r, ty)?;
    *pos = r.pos();
    Ok(())
}

fn skip_payload(r: &mut Reader<'_>, ty: u8) -> Result<(), BiomeError> {
    match ty {
        TAG_END => Ok(()),
        TAG_BYTE => r.bytes(1).map(|_| ()),
        TAG_SHORT => r.bytes(2).map(|_| ()),
        TAG_INT | TAG_FLOAT => r.bytes(4).map(|_| ()),
        TAG_LONG | TAG_DOUBLE => r.bytes(8).map(|_| ()),
        TAG_BYTE_ARRAY => {
            let n = r.i32()?;
            if n < 0 {
                return Err(BiomeError::Truncated);
            }
            r.bytes(n as usize).map(|_| ())
        }
        TAG_INT_ARRAY => {
            let n = r.i32()?;
            if n < 0 {
                return Err(BiomeError::Truncated);
            }
            r.bytes(n as usize * 4).map(|_| ())
        }
        TAG_LONG_ARRAY => {
            let n = r.i32()?;
            if n < 0 {
                return Err(BiomeError::Truncated);
            }
            r.bytes(n as usize * 8).map(|_| ())
        }
        TAG_STRING => {
            let n = r.u16()?;
            r.bytes(n as usize).map(|_| ())
        }
        TAG_LIST => {
            let elem = r.u8()?;
            let n = r.i32()?;
            if n < 0 {
                return Err(BiomeError::Truncated);
            }
            for _ in 0..n {
                skip_payload(r, elem)?;
            }
            Ok(())
        }
        TAG_COMPOUND => loop {
            let t = r.u8()?;
            if t == TAG_END {
                return Ok(());
            }
            let n = r.u16()?;
            r.bytes(n as usize)?;
            skip_payload(r, t)?;
        },
        _ => Err(BiomeError::BadNbt(ty)),
    }
}

/// Read the top-level `temperature` / `downfall` floats of a biome compound.
///
/// Accepts a float or an int (mods mix them); everything else is skipped.
pub fn climate(buf: &[u8], pos: &mut usize) -> Result<(Option<f32>, Option<f32>), BiomeError> {
    let mut r = Reader::at(buf, *pos);
    let ty = r.u8()?;
    if ty != TAG_COMPOUND {
        skip_payload(&mut r, ty)?;
        *pos = r.pos();
        return Ok((None, None));
    }
    let (mut temp, mut downfall) = (None, None);
    loop {
        let t = r.u8()?;
        if t == TAG_END {
            break;
        }
        let n = r.u16()? as usize;
        let name = r.bytes(n)?;
        match (t, name) {
            (TAG_FLOAT, b"temperature") => temp = Some(r.f32()?),
            (TAG_FLOAT, b"downfall") => downfall = Some(r.f32()?),
            (TAG_INT, b"temperature") => temp = Some(r.i32()? as f32),
            (TAG_INT, b"downfall") => downfall = Some(r.i32()? as f32),
            _ => skip_payload(&mut r, t)?,
        }
    }
    *pos = r.pos();
    Ok((temp, downfall))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writer_roundtrips_through_the_walker() {
        let blob = NbtWriter::new()
            .root_compound()
            .float("temperature", 0.8)
            .float("downfall", 0.3)
            .byte("has_precipitation", 1)
            .compound("effects", |w| {
                w.string("water_color", "#3f76e4")
                    .string("grass_color", "#e4ea77")
            })
            .empty_list("features", TAG_LIST)
            .compound("carvers", |w| w)
            .end()
            .into_bytes();
        // skip must consume exactly the blob
        let mut pos = 0usize;
        skip_root(&blob, &mut pos).unwrap();
        assert_eq!(pos, blob.len());
        let mut pos = 0usize;
        let (t, d) = climate(&blob, &mut pos).unwrap();
        assert_eq!(t, Some(0.8));
        assert_eq!(d, Some(0.3));
        assert_eq!(pos, blob.len());
    }

    #[test]
    fn varint_roundtrip() {
        let mut out = Vec::new();
        for v in [0, 1, 127, 128, 300, 70000, -1, i32::MAX, i32::MIN] {
            out.clear();
            write_varint(&mut out, v);
            let mut r = Reader::new(&out);
            assert_eq!(r.varint().unwrap(), v);
        }
    }
}
