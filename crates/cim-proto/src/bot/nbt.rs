//! Minimal NBT structural walker — skip-only, no allocation.
//!
//! Registry-data entries carry an optional (unnamed-root) NBT tag; the bot
//! never needs the contents, only its BYTE LENGTH so the entry list can be
//! walked. Names inside compounds are length-prefixed and skipped raw.

use crate::ProtoError;

/// Skip one unnamed-root tag: `u8 type` followed by the payload (no root name).
pub fn skip_unnamed_root(buf: &[u8], pos: &mut usize) -> Result<(), ProtoError> {
    let ty = read_u8(buf, pos)?;
    skip_payload(buf, pos, ty)
}

fn read_u8(buf: &[u8], pos: &mut usize) -> Result<u8, ProtoError> {
    let b = *buf.get(*pos).ok_or(ProtoError::UnexpectedEnd {
        needed: 1,
        offset: *pos,
    })?;
    *pos += 1;
    Ok(b)
}

fn need<'a>(buf: &'a [u8], pos: &mut usize, n: usize) -> Result<&'a [u8], ProtoError> {
    if buf.len() - *pos < n {
        return Err(ProtoError::UnexpectedEnd {
            needed: n,
            offset: *pos,
        });
    }
    let s = &buf[*pos..*pos + n];
    *pos += n;
    Ok(s)
}

fn read_u16(buf: &[u8], pos: &mut usize) -> Result<u16, ProtoError> {
    Ok(u16::from_be_bytes(need(buf, pos, 2)?.try_into().unwrap()))
}

fn read_i32(buf: &[u8], pos: &mut usize) -> Result<i32, ProtoError> {
    Ok(i32::from_be_bytes(need(buf, pos, 4)?.try_into().unwrap()))
}

fn skip_fixed(buf: &[u8], pos: &mut usize, n: usize) -> Result<(), ProtoError> {
    need(buf, pos, n)?;
    Ok(())
}

/// Skip a named tag entry inside a compound: `u8 type, u16 nameLen, name, payload`.
/// `ty` is the ALREADY-READ tag type (must not be TAG_End).
fn skip_named(buf: &[u8], pos: &mut usize, ty: u8) -> Result<(), ProtoError> {
    let name_len = read_u16(buf, pos)? as usize;
    skip_fixed(buf, pos, name_len)?;
    skip_payload(buf, pos, ty)
}

fn skip_payload(buf: &[u8], pos: &mut usize, ty: u8) -> Result<(), ProtoError> {
    match ty {
        0 => Ok(()),                              // end (payload-less)
        1 => skip_fixed(buf, pos, 1),             // byte
        2 => skip_fixed(buf, pos, 2),             // short
        3 | 5 => skip_fixed(buf, pos, 4),         // int | float
        4 | 6 => skip_fixed(buf, pos, 8),         // long | double
        7 => {                                    // byte array
            let n = read_i32(buf, pos)?;
            skip_fixed(buf, pos, n.max(0) as usize)
        }
        8 => {                                    // string
            let n = read_u16(buf, pos)? as usize;
            skip_fixed(buf, pos, n)
        }
        9 => {                                    // list
            let elem = read_u8(buf, pos)?;
            let n = read_i32(buf, pos)?;
            for _ in 0..n.max(0) {
                skip_payload(buf, pos, elem)?;
            }
            Ok(())
        }
        10 => {                                   // compound
            loop {
                let entry_ty = read_u8(buf, pos)?;
                if entry_ty == 0 {
                    return Ok(());
                }
                skip_named(buf, pos, entry_ty)?;
            }
        }
        11 => {                                   // int array
            let n = read_i32(buf, pos)?;
            skip_fixed(buf, pos, n.max(0) as usize * 4)
        }
        12 => {                                   // long array
            let n = read_i32(buf, pos)?;
            skip_fixed(buf, pos, n.max(0) as usize * 8)
        }
        other => Err(ProtoError::InvalidFrame(format!(
            "unknown NBT tag type {other}"
        ))),
    }
}
