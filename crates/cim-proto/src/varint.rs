//! VarInt / VarLong codec (LEB128 as used by the Minecraft protocol).

use crate::ProtoError;

/// Max on-wire bytes of a VarInt (32 bits / 7 bits per byte).
pub const MAX_VARINT_LEN: usize = 5;
/// Max on-wire bytes of a VarLong (64 bits / 7 bits per byte).
pub const MAX_VARLONG_LEN: usize = 10;

/// Read a VarInt starting at `*offset`; advances `*offset` past the value.
pub fn read_varint(buf: &[u8], offset: &mut usize) -> Result<i32, ProtoError> {
    let mut value: u32 = 0;
    for i in 0..MAX_VARINT_LEN {
        let byte = *buf.get(*offset).ok_or(ProtoError::UnexpectedEnd {
            needed: 1,
            offset: *offset,
        })?;
        *offset += 1;
        value |= u32::from(byte & 0x7F) << (7 * i);
        if byte & 0x80 == 0 {
            return Ok(value as i32);
        }
    }
    Err(ProtoError::VarTooLarge {
        max: MAX_VARINT_LEN,
    })
}

/// Read a VarLong starting at `*offset`; advances `*offset` past the value.
pub fn read_varlong(buf: &[u8], offset: &mut usize) -> Result<i64, ProtoError> {
    let mut value: u64 = 0;
    for i in 0..MAX_VARLONG_LEN {
        let byte = *buf.get(*offset).ok_or(ProtoError::UnexpectedEnd {
            needed: 1,
            offset: *offset,
        })?;
        *offset += 1;
        value |= u64::from(byte & 0x7F) << (7 * i);
        if byte & 0x80 == 0 {
            return Ok(value as i64);
        }
    }
    Err(ProtoError::VarTooLarge {
        max: MAX_VARLONG_LEN,
    })
}

/// Append a VarInt to `out`. Returns the number of bytes written.
pub fn write_varint(out: &mut Vec<u8>, value: i32) -> usize {
    let mut v = value as u32;
    let start = out.len();
    loop {
        if v & !0x7F == 0 {
            out.push(v as u8);
            break;
        }
        out.push(((v & 0x7F) | 0x80) as u8);
        v >>= 7;
    }
    out.len() - start
}

/// Append a VarLong to `out`. Returns the number of bytes written.
pub fn write_varlong(out: &mut Vec<u8>, value: i64) -> usize {
    let mut v = value as u64;
    let start = out.len();
    loop {
        if v & !0x7F == 0 {
            out.push(v as u8);
            break;
        }
        out.push(((v & 0x7F) | 0x80) as u8);
        v >>= 7;
    }
    out.len() - start
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_roundtrip_common_values() {
        for v in [0i32, 1, 127, 128, 255, 256, 65_535, i32::MAX, i32::MIN, -1] {
            let mut buf = Vec::with_capacity(MAX_VARINT_LEN);
            let n = write_varint(&mut buf, v);
            let mut off = 0usize;
            let back = read_varint(&buf, &mut off).unwrap();
            assert_eq!(back, v, "roundtrip failed for {v}");
            assert_eq!(off, n);
        }
    }

    #[test]
    fn varlong_roundtrip_common_values() {
        for v in [0i64, -1, 1, i64::MIN, i64::MAX, 1 << 40, -(1 << 40)] {
            let mut buf = Vec::with_capacity(MAX_VARLONG_LEN);
            let n = write_varlong(&mut buf, v);
            let mut off = 0usize;
            let back = read_varlong(&buf, &mut off).unwrap();
            assert_eq!(back, v, "roundtrip failed for {v}");
            assert_eq!(off, n);
        }
    }

    #[test]
    fn varint_wire_sizes_match_protocol() {
        let mut buf = Vec::new();
        assert_eq!(write_varint(&mut buf, 0), 1);
        assert_eq!(write_varint(&mut buf, 127), 1);
        assert_eq!(write_varint(&mut buf, 128), 2);
        assert_eq!(write_varint(&mut buf, i32::MAX), 5);
        assert_eq!(write_varint(&mut buf, -1), 5, "-1 encodes as 5 bytes");
    }

    #[test]
    fn truncated_varint_is_an_error_not_a_panic() {
        let mut buf = Vec::new();
        write_varint(&mut buf, i32::MAX);
        for cut in 0..buf.len() {
            let mut off = 0usize;
            assert!(read_varint(&buf[..cut], &mut off).is_err());
        }
    }

    #[test]
    fn oversized_varint_is_rejected() {
        // 6 continuation bytes: longer than any legal VarInt.
        let buf = [0x80u8; 6];
        let mut off = 0usize;
        assert!(matches!(
            read_varint(&buf, &mut off),
            Err(ProtoError::VarTooLarge { max: 5 })
        ));
    }
}
