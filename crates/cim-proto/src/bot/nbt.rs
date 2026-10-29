//! Minimal NBT structural walker — skip-only, no allocation.
//!
//! Registry-data entries carry an optional (unnamed-root) NBT tag; the bot
//! never needs the contents, only its BYTE LENGTH so the entry list can be
//! walked. Names inside compounds are length-prefixed and skipped raw.

use crate::ProtoError;

/// Maximum NBT nesting depth. Mirrors the vanilla decoder cap (512): deeper
/// structures are treated as malformed rather than allowed to overflow the
/// stack of the parsing thread.
const MAX_NBT_DEPTH: usize = 512;

/// Skip one unnamed-root tag: `u8 type` followed by the payload (no root name).
pub fn skip_unnamed_root(buf: &[u8], pos: &mut usize) -> Result<(), ProtoError> {
    let ty = read_u8(buf, pos)?;
    skip_payload(buf, pos, ty, 0)
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
    if *pos > buf.len() || buf.len() - *pos < n {
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
fn skip_named(buf: &[u8], pos: &mut usize, ty: u8, depth: usize) -> Result<(), ProtoError> {
    let name_len = read_u16(buf, pos)? as usize;
    skip_fixed(buf, pos, name_len)?;
    skip_payload(buf, pos, ty, depth)
}

fn skip_payload(buf: &[u8], pos: &mut usize, ty: u8, depth: usize) -> Result<(), ProtoError> {
    if depth > MAX_NBT_DEPTH {
        return Err(ProtoError::InvalidFrame(format!(
            "NBT nesting deeper than {MAX_NBT_DEPTH}"
        )));
    }
    match ty {
        0 => Ok(()),                      // end (payload-less)
        1 => skip_fixed(buf, pos, 1),     // byte
        2 => skip_fixed(buf, pos, 2),     // short
        3 | 5 => skip_fixed(buf, pos, 4), // int | float
        4 | 6 => skip_fixed(buf, pos, 8), // long | double
        7 => {
            // byte array
            let n = read_i32(buf, pos)?;
            skip_fixed(buf, pos, n.max(0) as usize)
        }
        8 => {
            // string
            let n = read_u16(buf, pos)? as usize;
            skip_fixed(buf, pos, n)
        }
        9 => {
            // list
            let elem = read_u8(buf, pos)?;
            let n = read_i32(buf, pos)?;
            // A list of TAG_End carries no payload per element; a nonzero
            // count would mean zero-progress iterations (up to 2^31), so
            // reject it the way the vanilla decoder does.
            if elem == 0 && n != 0 {
                return Err(ProtoError::InvalidFrame(format!(
                    "NBT list of TAG_End with nonzero count {n}"
                )));
            }
            for _ in 0..n.max(0) {
                skip_payload(buf, pos, elem, depth + 1)?;
            }
            Ok(())
        }
        10 => {
            // compound
            loop {
                let entry_ty = read_u8(buf, pos)?;
                if entry_ty == 0 {
                    return Ok(());
                }
                skip_named(buf, pos, entry_ty, depth + 1)?;
            }
        }
        11 => {
            // int array
            let n = read_i32(buf, pos)?;
            skip_fixed(buf, pos, n.max(0) as usize * 4)
        }
        12 => {
            // long array
            let n = read_i32(buf, pos)?;
            skip_fixed(buf, pos, n.max(0) as usize * 8)
        }
        other => Err(ProtoError::InvalidFrame(format!(
            "unknown NBT tag type {other}"
        ))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn name(s: &str) -> Vec<u8> {
        let mut v = (s.len() as u16).to_be_bytes().to_vec();
        v.extend_from_slice(s.as_bytes());
        v
    }

    #[test]
    fn skips_nested_compound_and_list() {
        // root compound { "a": list<compound{ "b": int 42 }> }
        let mut buf = vec![10]; // root: compound (unnamed)
        buf.push(9); // entry type: list
        buf.extend_from_slice(&name("a"));
        buf.push(10); // list elem type: compound
        buf.extend_from_slice(&1i32.to_be_bytes()); // one element
                                                    // element 0: compound payload
        buf.push(3); // entry type: int
        buf.extend_from_slice(&name("b"));
        buf.extend_from_slice(&42i32.to_be_bytes());
        buf.push(0); // end element compound
        buf.push(0); // end root compound
        let mut pos = 0;
        skip_unnamed_root(&buf, &mut pos).expect("valid nesting must parse");
        assert_eq!(pos, buf.len(), "walker must consume the whole tag");
    }

    #[test]
    fn rejects_depth_beyond_limit() {
        // 600 nested lists, each holding exactly one element of the same kind.
        let mut buf = vec![9]; // root: list
        for _ in 0..600 {
            buf.push(9); // elem type: list
            buf.extend_from_slice(&1i32.to_be_bytes()); // count = 1
        }
        // innermost list: one byte element
        buf.push(1); // elem type: byte
        buf.extend_from_slice(&1i32.to_be_bytes());
        buf.push(7); // the byte value
        let mut pos = 0;
        let err = skip_unnamed_root(&buf, &mut pos).expect_err("must reject deep nesting");
        assert!(
            err.to_string().contains("nesting deeper"),
            "wrong error: {err}"
        );
    }

    #[test]
    fn accepts_depth_within_limit() {
        // 100 nested lists — deep, but under the 512 cap.
        let mut buf = vec![9];
        for _ in 0..100 {
            buf.push(9);
            buf.extend_from_slice(&1i32.to_be_bytes());
        }
        buf.push(1);
        buf.extend_from_slice(&1i32.to_be_bytes());
        buf.push(7);
        let mut pos = 0;
        skip_unnamed_root(&buf, &mut pos).expect("100 levels must parse");
    }

    #[test]
    fn rejects_list_of_end_with_nonzero_count() {
        // root compound { "bad": list<TAG_End> x 2^30 }
        let mut buf = vec![10];
        buf.push(9); // entry type: list
        buf.extend_from_slice(&name("bad"));
        buf.push(0); // elem type: TAG_End
        buf.extend_from_slice(&(1i32 << 30).to_be_bytes());
        buf.push(0); // end compound
        let mut pos = 0;
        let started = std::time::Instant::now();
        let err = skip_unnamed_root(&buf, &mut pos).expect_err("must reject END list");
        assert!(
            started.elapsed().as_millis() < 100,
            "must fail fast, not spin 2^30 iterations"
        );
        assert!(err.to_string().contains("TAG_End"), "wrong error: {err}");
    }

    #[test]
    fn accepts_list_of_end_with_zero_count() {
        let mut buf = vec![10];
        buf.push(9);
        buf.extend_from_slice(&name("ok"));
        buf.push(0);
        buf.extend_from_slice(&0i32.to_be_bytes());
        buf.push(0);
        let mut pos = 0;
        skip_unnamed_root(&buf, &mut pos).expect("empty END list is legal");
    }

    #[test]
    fn rejects_truncated_and_unknown_types() {
        // truncated byte array length
        let mut pos = 0;
        assert!(skip_unnamed_root(&[7, 0, 0], &mut pos).is_err());
        // unknown tag type
        let mut pos = 0;
        assert!(skip_unnamed_root(&[99, 0, 0], &mut pos).is_err());
        // compound ends abruptly (no TAG_End terminator)
        let mut pos = 0;
        assert!(skip_unnamed_root(&[10], &mut pos).is_err());
    }
}
