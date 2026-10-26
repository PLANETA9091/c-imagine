//! JVM class-file reader + rebuild for relocation.
//!
//! Two relocation surfaces, both required for linking:
//!
//! 1. **Constant pool** — `CONSTANT_Class` entries (superclasses, interfaces,
//!    `new`, `checkcast`, arrays), `CONSTANT_NameAndType` and
//!    `CONSTANT_MethodType` descriptors (field/method references and
//!    `invokedynamic`). Rewritten by appending fresh UTF-8 entries; original
//!    slots are never mutated, so attribute-referenced strings stay valid.
//! 2. **Member declarations** — every `field_info` and `method_info` carries
//!    its own `descriptor_index` into the pool. HotSpot's native verifier
//!    resolves declared field/method types from these strings at define/link
//!    time, so declarations must be relocated too (this is what bit us when
//!    only the pool was rewritten: `putstatic` verification asked for
//!    `net.minecraft.WorldVersion` by its original name).
//!
//! What is deliberately **not** rewritten (loading never needs it, and
//! keeping originals preserves FML's own raw-byte annotation scanning):
//! generic `Signature` attributes, runtime annotation descriptors,
//! `CONSTANT_String` literals. Everything else is preserved byte-identical.

use super::{RelocationTable, RewriteStats, Utf8Intern};

#[derive(Debug, PartialEq, Eq)]
pub enum ParseError {
    /// Not a class file (bad magic).
    BadMagic,
    /// Truncated or structurally invalid structure.
    Truncated(&'static str),
    /// Rebuild would exceed the 65,535-entry pool limit.
    PoolOverflow,
}

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ParseError::BadMagic => write!(f, "bad magic (not a class file)"),
            ParseError::Truncated(what) => write!(f, "truncated: {what}"),
            ParseError::PoolOverflow => write!(f, "constant pool would exceed 65535 entries"),
        }
    }
}

/// Tags (JVM spec §4.4).
const TAG_UTF8: u8 = 1;
const TAG_INTEGER: u8 = 3;
const TAG_FLOAT: u8 = 4;
const TAG_LONG: u8 = 5;
const TAG_DOUBLE: u8 = 6;
const TAG_CLASS: u8 = 7;
const TAG_STRING: u8 = 8;
const TAG_FIELDREF: u8 = 9;
const TAG_METHODREF: u8 = 10;
const TAG_INTERFACE_METHODREF: u8 = 11;
const TAG_NAME_AND_TYPE: u8 = 12;
const TAG_METHOD_HANDLE: u8 = 15;
const TAG_METHOD_TYPE: u8 = 16;
const TAG_DYNAMIC: u8 = 17;
const TAG_INVOKE_DYNAMIC: u8 = 18;
const TAG_MODULE: u8 = 19;
const TAG_PACKAGE: u8 = 20;

/// Payload sizes for the fixed-size tags (`tag => payload bytes`).
const FIXED_PAYLOAD: &[(u8, usize)] = &[
    (TAG_INTEGER, 4),
    (TAG_FLOAT, 4),
    (TAG_LONG, 8),   // takes two pool slots
    (TAG_DOUBLE, 8), // takes two pool slots
    (TAG_FIELDREF, 4),
    (TAG_METHODREF, 4),
    (TAG_INTERFACE_METHODREF, 4),
    (TAG_METHOD_HANDLE, 3),
    (TAG_DYNAMIC, 4),
    (TAG_INVOKE_DYNAMIC, 4),
    (TAG_CLASS, 2),
    (TAG_STRING, 2),
    (TAG_NAME_AND_TYPE, 4), // name u2 + descriptor u2
    (TAG_METHOD_TYPE, 2),
    (TAG_MODULE, 2),
    (TAG_PACKAGE, 2),
];

/// One constant-pool slot: tag + payload bytes (excluding the tag byte).
#[derive(Debug, Clone)]
struct RawEntry {
    tag: u8,
    data: Vec<u8>,
}

impl RawEntry {
    fn u2_at(&self, off: usize) -> Option<u16> {
        if off + 2 > self.data.len() {
            return None;
        }
        Some(u16::from_be_bytes([self.data[off], self.data[off + 1]]))
    }
}

/// A `field_info` or `method_info` (identical layout).
#[derive(Debug, Clone)]
struct Member {
    access: u16,
    name: u16,
    descriptor: u16,
    /// Raw attribute block (attribute_count + entries), copied verbatim.
    attrs: Vec<u8>,
}

/// The class body: everything from `access_flags` to EOF, structured just
/// enough to reach the member declaration descriptors.
#[derive(Debug, Clone)]
struct Body {
    access: u16,
    this_class: u16,
    super_class: u16,
    interfaces: Vec<u16>,
    fields: Vec<Member>,
    methods: Vec<Member>,
    /// Raw class-level attribute block (count + entries), verbatim.
    class_attrs: Vec<u8>,
}

/// A parsed class file.
#[derive(Debug, Clone)]
pub struct ClassFile {
    minor_version: u16,
    major_version: u16,
    /// `entries[0]` is the invalid slot; long/double take two slots with a
    /// placeholder after them, exactly as in the on-disk layout.
    entries: Vec<Option<RawEntry>>,
    body: Body,
}

impl ClassFile {
    /// Parse the full class file.
    pub fn parse(bytes: &[u8]) -> Result<Self, ParseError> {
        const MAGIC: [u8; 4] = [0xCA, 0xFE, 0xBA, 0xBE];
        if bytes.len() < 10 || bytes[0..4] != MAGIC {
            return Err(ParseError::BadMagic);
        }
        let mut pos = 4usize;
        let minor_version = read_u16(bytes, &mut pos).ok_or(ParseError::Truncated("minor"))?;
        let major_version = read_u16(bytes, &mut pos).ok_or(ParseError::Truncated("major"))?;
        let count = read_u16(bytes, &mut pos)
            .ok_or(ParseError::Truncated("cp count"))?
            .max(1);

        let mut entries: Vec<Option<RawEntry>> = vec![None; count as usize];
        let mut i = 1usize;
        while i < count as usize {
            let tag = *bytes.get(pos).ok_or(ParseError::Truncated("tag"))?;
            pos += 1;
            let data: Vec<u8> = match tag {
                TAG_UTF8 => {
                    let len = read_u16(bytes, &mut pos).ok_or(ParseError::Truncated("utf8 len"))?
                        as usize;
                    let out = bytes
                        .get(pos..pos + len)
                        .ok_or(ParseError::Truncated("utf8 bytes"))?
                        .to_vec();
                    pos += len;
                    out
                }
                _ => {
                    let size = FIXED_PAYLOAD
                        .iter()
                        .find(|(t, _)| *t == tag)
                        .map(|(_, n)| *n)
                        .ok_or(ParseError::Truncated("unknown tag"))?;
                    let out = bytes
                        .get(pos..pos + size)
                        .ok_or(ParseError::Truncated("payload"))?
                        .to_vec();
                    pos += size;
                    out
                }
            };
            entries[i] = Some(RawEntry { tag, data });
            i += 1;
            // Long/Double occupy two slots.
            if tag == TAG_LONG || tag == TAG_DOUBLE {
                if i >= count as usize {
                    return Err(ParseError::Truncated("long/double tail slot"));
                }
                entries[i] = None;
                i += 1;
            }
        }
        if pos > bytes.len() {
            return Err(ParseError::Truncated("pool overruns file"));
        }

        let body = parse_body(bytes, &mut pos)?;
        Ok(Self {
            minor_version,
            major_version,
            entries,
            body,
        })
    }

    fn utf8(&self, idx: u16) -> Option<&[u8]> {
        let e = self.entries.get(idx as usize)?.as_ref()?;
        if e.tag != TAG_UTF8 {
            return None;
        }
        Some(&e.data)
    }

    /// Number of pool slots (including the invalid slot 0 and the trailing
    /// long/double placeholders, matching the on-disk layout).
    pub fn pool_slot_count(&self) -> usize {
        self.entries.len()
    }

    /// Raw content of one UTF-8 slot (diagnostics/tests).
    pub fn utf8_at(&self, idx: u16) -> Option<&[u8]> {
        self.utf8(idx)
    }

    /// Rebuild the class file with relocation applied.
    ///
    /// Returns the new bytes plus rewrite statistics. Never mutates the
    /// original slot contents; rewritten entries point at appended UTF-8s.
    pub fn rewrite(&self, table: &RelocationTable) -> Result<(Vec<u8>, RewriteStats), ParseError> {
        let mut stats = RewriteStats::default();
        let mut intern = Utf8Intern::new();
        // New entries start after the original pool (index == entries.len()).
        let mut next_index =
            u16::try_from(self.entries.len()).map_err(|_| ParseError::PoolOverflow)?;

        let intern_descriptor = |content: &str,
                                 stats: &mut RewriteStats,
                                 intern: &mut Utf8Intern,
                                 next_index: &mut u16|
         -> Result<u16, ParseError> {
            let idx = intern.intern(content.as_bytes(), next_index);
            stats.descriptors_rewritten += 1;
            Ok(idx)
        };

        // Slot-level rewrites: (slot index) -> replacement entry.
        let mut rewritten: HashMap<u16, RawEntry> = HashMap::new();

        for (slot, entry) in self.entries.iter().enumerate() {
            let Some(e) = entry else { continue };
            match e.tag {
                TAG_CLASS => {
                    let name_idx = e.u2_at(0).ok_or(ParseError::Truncated("class name idx"))?;
                    let name = self
                        .utf8(name_idx)
                        .ok_or(ParseError::Truncated("class name utf8"))?;
                    let name = std::str::from_utf8(name)
                        .map_err(|_| ParseError::Truncated("non-utf8 class name"))?;
                    // Class entries carry either a plain internal name or an
                    // array descriptor ("[L...;").
                    let mapped = if name.starts_with('[') {
                        table.map_descriptor(name)
                    } else {
                        match table.map_internal(name) {
                            Some(m) => m,
                            None => continue,
                        }
                    };
                    if mapped != name {
                        let idx = intern.intern(mapped.as_bytes(), &mut next_index);
                        rewritten.insert(
                            slot as u16,
                            RawEntry {
                                tag: TAG_CLASS,
                                data: idx.to_be_bytes().to_vec(),
                            },
                        );
                        stats.classes_renamed += 1;
                    }
                }
                TAG_NAME_AND_TYPE => {
                    let desc_idx = e.u2_at(2).ok_or(ParseError::Truncated("nat desc idx"))?;
                    if let Some(idx) = self.rewrite_descriptor(table, desc_idx)? {
                        let idx =
                            intern_descriptor(&idx, &mut stats, &mut intern, &mut next_index)?;
                        let name_idx = e.u2_at(0).ok_or(ParseError::Truncated("nat name idx"))?;
                        let mut data = Vec::with_capacity(4);
                        data.extend_from_slice(&name_idx.to_be_bytes());
                        data.extend_from_slice(&idx.to_be_bytes());
                        rewritten.insert(
                            slot as u16,
                            RawEntry {
                                tag: TAG_NAME_AND_TYPE,
                                data,
                            },
                        );
                    }
                }
                TAG_METHOD_TYPE => {
                    let desc_idx = e.u2_at(0).ok_or(ParseError::Truncated("mt desc idx"))?;
                    if let Some(idx) = self.rewrite_descriptor(table, desc_idx)? {
                        let idx =
                            intern_descriptor(&idx, &mut stats, &mut intern, &mut next_index)?;
                        rewritten.insert(
                            slot as u16,
                            RawEntry {
                                tag: TAG_METHOD_TYPE,
                                data: idx.to_be_bytes().to_vec(),
                            },
                        );
                    }
                }
                _ => {}
            }
        }

        // Member declaration descriptors (field_info / method_info).
        let mut body = self.body.clone();
        for member in body.fields.iter_mut().chain(body.methods.iter_mut()) {
            if let Some(mapped) = self.rewrite_descriptor(table, member.descriptor)? {
                let idx = intern_descriptor(&mapped, &mut stats, &mut intern, &mut next_index)?;
                member.descriptor = idx;
            }
        }

        let total = self.entries.len() + intern.len();
        if total > u16::MAX as usize {
            return Err(ParseError::PoolOverflow);
        }

        // ---- rebuild -----------------------------------------------------
        let mut out: Vec<u8> = Vec::with_capacity(64 * 1024);
        out.extend_from_slice(&[0xCA, 0xFE, 0xBA, 0xBE]);
        out.extend_from_slice(&self.minor_version.to_be_bytes());
        out.extend_from_slice(&self.major_version.to_be_bytes());
        out.extend_from_slice(&(total as u16).to_be_bytes());

        for slot in 1..self.entries.len() {
            match rewritten.get(&(slot as u16)) {
                Some(e) => {
                    out.push(e.tag);
                    out.extend_from_slice(&e.data);
                }
                None => {
                    if let Some(e) = &self.entries[slot] {
                        out.push(e.tag);
                        if e.tag == TAG_UTF8 {
                            // Parsed utf8 payload is the raw content; the
                            // on-disk form carries a u2 length prefix.
                            out.extend_from_slice(&(e.data.len() as u16).to_be_bytes());
                        }
                        out.extend_from_slice(&e.data);
                    }
                    // Placeholder slot after long/double: write nothing.
                }
            }
        }
        for content in &intern.appended {
            out.push(TAG_UTF8);
            out.extend_from_slice(&(content.len() as u16).to_be_bytes());
            out.extend_from_slice(content);
        }

        // Body with updated member descriptors.
        out.extend_from_slice(&body.access.to_be_bytes());
        out.extend_from_slice(&body.this_class.to_be_bytes());
        out.extend_from_slice(&body.super_class.to_be_bytes());
        out.extend_from_slice(&(body.interfaces.len() as u16).to_be_bytes());
        for iface in &body.interfaces {
            out.extend_from_slice(&iface.to_be_bytes());
        }
        write_members(&mut out, &body.fields);
        write_members(&mut out, &body.methods);
        out.extend_from_slice(&body.class_attrs);

        stats.utf8_added = intern.len();
        Ok((out, stats))
    }

    /// Map a descriptor utf8 slot; returns `Some(mapped)` when it changed.
    fn rewrite_descriptor(
        &self,
        table: &RelocationTable,
        desc_idx: u16,
    ) -> Result<Option<String>, ParseError> {
        let desc = self
            .utf8(desc_idx)
            .ok_or(ParseError::Truncated("desc utf8"))?;
        let desc = std::str::from_utf8(desc).map_err(|_| ParseError::Truncated("non-utf8 desc"))?;
        let mapped = table.map_descriptor(desc);
        Ok((mapped != desc).then_some(mapped))
    }

    /// Declared field/method descriptors (diagnostics/tests): the
    /// `field_info`/`method_info` descriptor strings, in declaration order.
    pub fn member_descriptors(&self) -> Vec<String> {
        let mut out = Vec::new();
        for m in self.body.fields.iter().chain(self.body.methods.iter()) {
            if let Some(u) = self.utf8(m.descriptor) {
                if let Ok(s) = std::str::from_utf8(u) {
                    out.push(s.to_string());
                }
            }
        }
        out
    }

    /// Introspection for tests and diagnostics: all class names in the pool.
    pub fn class_names(&self) -> Vec<String> {
        let mut names = Vec::new();
        for entry in self.entries.iter().flatten() {
            if entry.tag != TAG_CLASS {
                continue;
            }
            if let Some(idx) = entry.u2_at(0) {
                if let Some(name) = self.utf8(idx) {
                    if let Ok(s) = std::str::from_utf8(name) {
                        names.push(s.to_string());
                    }
                }
            }
        }
        names
    }
}

fn parse_body(bytes: &[u8], pos: &mut usize) -> Result<Body, ParseError> {
    let access = read_u16(bytes, pos).ok_or(ParseError::Truncated("access"))?;
    let this_class = read_u16(bytes, pos).ok_or(ParseError::Truncated("this"))?;
    let super_class = read_u16(bytes, pos).ok_or(ParseError::Truncated("super"))?;
    let icount = read_u16(bytes, pos).ok_or(ParseError::Truncated("ifaces"))? as usize;
    let mut interfaces = Vec::with_capacity(icount);
    for _ in 0..icount {
        interfaces.push(read_u16(bytes, pos).ok_or(ParseError::Truncated("iface idx"))?);
    }
    let fcount = read_u16(bytes, pos).ok_or(ParseError::Truncated("fields"))? as usize;
    let mut fields = Vec::with_capacity(fcount);
    for _ in 0..fcount {
        fields.push(parse_member(bytes, pos)?);
    }
    let mcount = read_u16(bytes, pos).ok_or(ParseError::Truncated("methods"))? as usize;
    let mut methods = Vec::with_capacity(mcount);
    for _ in 0..mcount {
        methods.push(parse_member(bytes, pos)?);
    }
    let class_attrs = read_attr_block(bytes, pos)?;
    Ok(Body {
        access,
        this_class,
        super_class,
        interfaces,
        fields,
        methods,
        class_attrs,
    })
}

fn parse_member(bytes: &[u8], pos: &mut usize) -> Result<Member, ParseError> {
    let access = read_u16(bytes, pos).ok_or(ParseError::Truncated("member access"))?;
    let name = read_u16(bytes, pos).ok_or(ParseError::Truncated("member name"))?;
    let descriptor = read_u16(bytes, pos).ok_or(ParseError::Truncated("member desc"))?;
    let attrs = read_attr_block(bytes, pos)?;
    Ok(Member {
        access,
        name,
        descriptor,
        attrs,
    })
}

/// `attribute_count` + entries, kept as raw bytes INCLUDING the count prefix
/// (attribute UTF-8 references must stay untouched — that is the
/// annotation/signature contract).
fn read_attr_block(bytes: &[u8], pos: &mut usize) -> Result<Vec<u8>, ParseError> {
    let start = *pos;
    let count = read_u16(bytes, pos).ok_or(ParseError::Truncated("attr count"))? as usize;
    for _ in 0..count {
        *pos += 2; // attribute_name_index
        let len = read_u32(bytes, pos).ok_or(ParseError::Truncated("attr len"))? as usize;
        *pos += len;
        if *pos > bytes.len() {
            return Err(ParseError::Truncated("attr overruns file"));
        }
    }
    Ok(bytes[start..*pos].to_vec())
}

fn write_members(out: &mut Vec<u8>, members: &[Member]) {
    out.extend_from_slice(&(members.len() as u16).to_be_bytes());
    for m in members {
        out.extend_from_slice(&m.access.to_be_bytes());
        out.extend_from_slice(&m.name.to_be_bytes());
        out.extend_from_slice(&m.descriptor.to_be_bytes());
        out.extend_from_slice(&m.attrs);
    }
}

fn read_u16(bytes: &[u8], pos: &mut usize) -> Option<u16> {
    if *pos + 2 > bytes.len() {
        return None;
    }
    let v = u16::from_be_bytes([bytes[*pos], bytes[*pos + 1]]);
    *pos += 2;
    Some(v)
}

fn read_u32(bytes: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos + 4 > bytes.len() {
        return None;
    }
    let v = u32::from_be_bytes([
        bytes[*pos],
        bytes[*pos + 1],
        bytes[*pos + 2],
        bytes[*pos + 3],
    ]);
    *pos += 4;
    Some(v)
}

use std::collections::HashMap;
