//! Class-file header parser: reads the constant pool only far enough to resolve
//! `this_class`, `super_class` and the interface list. Method, field and
//! attribute tables are never touched, so cost is proportional to the constant
//! pool size, not to the class body.

use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum HeaderError {
    #[error("truncated class file")]
    Truncated,
    #[error("bad magic")]
    BadMagic,
    #[error("unknown constant pool tag {0} at index {1}")]
    BadTag(u8, usize),
    #[error("constant pool index {0} does not name a class")]
    NotAClass(u16),
}

/// Parsed class header. Names are JVM internal names (`java/lang/String`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub name: String,
    pub super_name: Option<String>,
    pub interfaces: Vec<String>,
    pub access: u16,
}

#[derive(Clone, Copy)]
enum Entry {
    /// Utf8 payload as a byte range inside the class file.
    Utf8(usize, usize),
    /// Class entry pointing at a Utf8 index.
    Class(u16),
    Other,
}

struct Cursor<'a> {
    b: &'a [u8],
    pos: usize,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], HeaderError> {
        let end = self.pos.checked_add(n).ok_or(HeaderError::Truncated)?;
        let s = self.b.get(self.pos..end).ok_or(HeaderError::Truncated)?;
        self.pos = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8, HeaderError> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16, HeaderError> {
        let s = self.take(2)?;
        Ok(u16::from_be_bytes([s[0], s[1]]))
    }
    fn u32(&mut self) -> Result<u32, HeaderError> {
        let s = self.take(4)?;
        Ok(u32::from_be_bytes([s[0], s[1], s[2], s[3]]))
    }
}

/// Parse only the class header. `bytes` is the whole class file.
pub fn parse_header(bytes: &[u8]) -> Result<Header, HeaderError> {
    let mut c = Cursor { b: bytes, pos: 0 };
    if c.u32()? != 0xCAFE_BABE {
        return Err(HeaderError::BadMagic);
    }
    c.take(4)?; // minor, major
    let count = c.u16()? as usize;
    let mut pool: Vec<Entry> = Vec::with_capacity(count);
    pool.push(Entry::Other); // index 0 is unused
    let mut i = 1usize;
    while i < count {
        let tag = c.u8()?;
        let entry = match tag {
            1 => {
                let len = c.u16()? as usize;
                let start = c.pos;
                c.take(len)?;
                Entry::Utf8(start, len)
            }
            7 => Entry::Class(c.u16()?),
            8 | 16 | 19 | 20 => {
                c.take(2)?;
                Entry::Other
            }
            3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => {
                c.take(4)?;
                Entry::Other
            }
            15 => {
                c.take(3)?;
                Entry::Other
            }
            5 | 6 => {
                c.take(8)?;
                pool.push(Entry::Other);
                // Long/Double occupy two pool slots; the second stays unusable.
                i += 1;
                Entry::Other
            }
            t => return Err(HeaderError::BadTag(t, i)),
        };
        pool.push(entry);
        i += 1;
    }
    let access = c.u16()?;
    let this_idx = c.u16()?;
    let super_idx = c.u16()?;
    let icount = c.u16()? as usize;
    let mut iface_idx = Vec::with_capacity(icount);
    for _ in 0..icount {
        iface_idx.push(c.u16()?);
    }
    let resolve = |idx: u16| -> Result<String, HeaderError> {
        let class_entry = pool.get(idx as usize).copied().ok_or(HeaderError::NotAClass(idx))?;
        let Entry::Class(name_idx) = class_entry else {
            return Err(HeaderError::NotAClass(idx));
        };
        match pool.get(name_idx as usize).copied() {
            Some(Entry::Utf8(start, len)) => Ok(String::from_utf8_lossy(&bytes[start..start + len]).into_owned()),
            _ => Err(HeaderError::NotAClass(name_idx)),
        }
    };
    let name = resolve(this_idx)?;
    let super_name = if super_idx == 0 { None } else { Some(resolve(super_idx)?) };
    let mut interfaces = Vec::with_capacity(iface_idx.len());
    for idx in iface_idx {
        interfaces.push(resolve(idx)?);
    }
    Ok(Header { name, super_name, interfaces, access })
}
