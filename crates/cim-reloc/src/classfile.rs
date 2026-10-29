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

    /// True when the member carries `@OnlyIn(Dist.CLIENT)`.
    ///
    /// The real dedicated server's class transform removes these members
    /// before verification (12-q: the ElevatorContactBlock.displayScreen
    /// chain proves the verifier never sees them there — otherwise Screen
    /// would resolve and its Renderable superinterface would NCDFE the
    /// construct). The annotation is checked under its ORIGINAL name — this
    /// runs before any pool rewrite, and the check must be identical for
    /// layer classes and refs-only mod classes.
    fn member_is_client_only(&self, m: &Member) -> bool {
        // m.attrs: (attribute_count u2) { attribute_name_index u2,
        //            attribute_length u4, info[length] }
        let b = &m.attrs;
        if b.len() < 2 {
            return false;
        }
        let Some(count) = read_u16_at(b, 0) else {
            return false;
        };
        let mut off = 2usize;
        for _ in 0..count {
            if off + 6 > b.len() {
                return false;
            }
            let Some(name_idx) = read_u16_at(b, off) else {
                return false;
            };
            let len = u32::from_be_bytes([b[off + 2], b[off + 3], b[off + 4], b[off + 5]]) as usize;
            let info_start = off + 6;
            if info_start + len > b.len() {
                return false;
            }
            let is_rva = self
                .utf8(name_idx)
                .map(|n| n == b"RuntimeVisibleAnnotations")
                .unwrap_or(false);
            if is_rva && self.annos_contain_client_only(&b[info_start..info_start + len]) {
                return true;
            }
            off = info_start + len;
        }
        false
    }

    /// Parse a `RuntimeVisibleAnnotations` payload (annotations_count u2,
    /// then `annotation` structures) and report whether ANY annotation is
    /// `@OnlyIn` with element value `Dist.CLIENT` (enum tag 'e').
    fn annos_contain_client_only(&self, payload: &[u8]) -> bool {
        if payload.len() < 2 {
            return false;
        }
        let Some(count) = read_u16_at(payload, 0) else {
            return false;
        };
        let mut off = 2usize;
        for _ in 0..count {
            // type_index u2
            if off + 2 > payload.len() {
                return false;
            }
            let Some(type_idx) = read_u16_at(payload, off) else {
                return false;
            };
            let is_only_in = self
                .utf8(type_idx)
                .map(|t| t == b"Lnet/neoforged/api/distmarker/OnlyIn;")
                .unwrap_or(false);
            off += 2;
            if off + 2 > payload.len() {
                return false;
            }
            let Some(pairs) = read_u16_at(payload, off) else {
                return false;
            };
            off += 2;
            for _ in 0..pairs {
                // element_name_index u2, then element_value
                if off + 2 > payload.len() {
                    return false;
                }
                let Some(_elem_name) = read_u16_at(payload, off) else {
                    return false;
                };
                off += 2;
                if off >= payload.len() {
                    return false;
                }
                let tag = payload[off];
                off += 1;
                match tag {
                    // enum constant: type_name_index u2 + const_name_index u2
                    b'e' => {
                        if off + 4 > payload.len() {
                            return false;
                        }
                        let tn = read_u16_at(payload, off).unwrap_or(0);
                        let cn = read_u16_at(payload, off + 2).unwrap_or(0);
                        off += 4;
                        if is_only_in
                            && self
                                .utf8(tn)
                                .map(|t| t == b"Lnet/neoforged/api/distmarker/Dist;")
                                .unwrap_or(false)
                            && self.utf8(cn).map(|c| c == b"CLIENT").unwrap_or(false)
                        {
                            return true;
                        }
                    }
                    // primitive/String: one u2
                    b'Z' | b'B' | b'C' | b'S' | b'I' | b'F' | b'J' | b'D' | b's' => off += 2,
                    // class: one u2
                    b'c' => off += 2,
                    // nested annotation: parse structurally (rare; keep the
                    // walker total so a malformed member cannot loop us)
                    b'@' => {
                        // treat like a fresh annotation header: type + pairs
                        if off + 2 > payload.len() {
                            return false;
                        }
                        off += 2;
                        if off + 2 > payload.len() {
                            return false;
                        }
                        let np = read_u16_at(payload, off).unwrap_or(0);
                        off += 2;
                        // conservatively skip nested pairs as name+u2 slots
                        for _ in 0..np {
                            off += 2; // element name
                            if off >= payload.len() {
                                return false;
                            }
                            let inner = payload[off];
                            off += 1;
                            match inner {
                                b'e' => off += 4,
                                b'Z' | b'B' | b'C' | b'S' | b'I' | b'F' | b'J' | b'D' | b's'
                                | b'c' => off += 2,
                                b'[' => {
                                    if off + 2 > payload.len() {
                                        return false;
                                    }
                                    let n = read_u16_at(payload, off).unwrap_or(0);
                                    off += 2 + 2 * n as usize; // shallow: array of scalars
                                }
                                b'@' => return false, // deeper nesting: bail, keep total
                                _ => return false,
                            }
                        }
                    }
                    // array of element values
                    b'[' => {
                        if off + 2 > payload.len() {
                            return false;
                        }
                        let n = read_u16_at(payload, off).unwrap_or(0);
                        off += 2;
                        for _ in 0..n {
                            if off >= payload.len() {
                                return false;
                            }
                            let itag = payload[off];
                            off += 1;
                            match itag {
                                b'e' => off += 4,
                                b'Z' | b'B' | b'C' | b'S' | b'I' | b'F' | b'J' | b'D' | b's'
                                | b'c' => off += 2,
                                _ => return false, // nested annotation inside array: bail
                            }
                        }
                    }
                    _ => return false,
                }
            }
        }
        false
    }

    /// Rebuild the class file with relocation applied.
    ///
    /// Returns the new bytes plus rewrite statistics. Never mutates the
    /// original slot contents; rewritten entries point at appended UTF-8s.
    pub fn rewrite(&self, table: &RelocationTable) -> Result<(Vec<u8>, RewriteStats), ParseError> {
        self.rewrite_at(table, &crate::at::AccessTransformTable::default(), "")
    }

    /// Full rewrite with an access-transform database: matching classes and
    /// members get their access flags widened before emission. Directives
    /// are keyed by the ORIGINAL (on-disk) class name.
    pub fn rewrite_at(
        &self,
        table: &RelocationTable,
        at: &crate::at::AccessTransformTable,
        original_name: &str,
    ) -> Result<(Vec<u8>, RewriteStats), ParseError> {
        self.rewrite_internal_at(table, None, at, original_name)
    }

    /// Rewrite references only (M2 mod classes): the class keeps its original
    /// binary name; every reference into the layer namespaces is rewritten.
    pub fn rewrite_refs_only(
        &self,
        table: &RelocationTable,
        self_name: &str,
    ) -> Result<(Vec<u8>, RewriteStats), ParseError> {
        self.rewrite_internal(table, Some(self_name))
    }

    /// Refs-only rewrite with an access-transform database.
    pub fn rewrite_refs_only_at(
        &self,
        table: &RelocationTable,
        at: &crate::at::AccessTransformTable,
        self_name: &str,
    ) -> Result<(Vec<u8>, RewriteStats), ParseError> {
        self.rewrite_internal_at(table, Some(self_name), at, self_name)
    }

    fn rewrite_internal(
        &self,
        table: &RelocationTable,
        self_exempt: Option<&str>,
    ) -> Result<(Vec<u8>, RewriteStats), ParseError> {
        self.rewrite_internal_at(
            table,
            self_exempt,
            &crate::at::AccessTransformTable::default(),
            "",
        )
    }

    fn rewrite_internal_at(
        &self,
        table: &RelocationTable,
        self_exempt: Option<&str>,
        at: &crate::at::AccessTransformTable,
        original_name: &str,
    ) -> Result<(Vec<u8>, RewriteStats), ParseError> {
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
                    if self_exempt == Some(name) {
                        // self-reference: never rename the class's own name
                        continue;
                    }
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
        // Dist parity (12-q): the real dedicated server's class transform
        // STRIPS members carrying @OnlyIn(Dist.CLIENT) before verification,
        // so client-only references (e.g. ElevatorContactBlock.displayScreen
        // -> new ElevatorContactScreen -> Screen) never reach the verifier
        // and client classes are never resolved from server-executed paths.
        // Mirror that here for BOTH layer and mod classes.
        let before_f = body.fields.len();
        let before_m = body.methods.len();
        // The names REMOVED by the annotation pass (the lambda-host hunt
        // needs the REMOVED hosts, not the survivors).
        let mut removed_names: Vec<String> = Vec::new();
        body.fields.retain(|m| {
            let drop = self.member_is_client_only(m);
            if drop {
                if let Some(n) = self
                    .utf8(m.name)
                    .and_then(|b| String::from_utf8(b.to_vec()).ok())
                {
                    removed_names.push(n);
                }
            }
            !drop
        });
        body.methods.retain(|m| {
            let drop = self.member_is_client_only(m);
            if drop {
                if let Some(n) = self
                    .utf8(m.name)
                    .and_then(|b| String::from_utf8(b.to_vec()).ok())
                {
                    removed_names.push(n);
                }
            }
            !drop
        });
        // Synthetic lambda hosts (12-s): javac does NOT copy @OnlyIn onto the
        // `lambda$<hostName>$<n>` synthetic methods, so a stripped client-only
        // host leaves its lambda body behind — with client-typed descriptors
        // (CreateRegistrate.lambda$registerCTBehviour$25 carries
        // (…BakedModel)BakedModel) that make the verifier resolve client
        // classes during class loading. The real pipeline removes them by the
        // naming convention; mirror that, iterating to a fixpoint because a
        // stripped lambda may host further lambdas.
        loop {
            let before = body.methods.len();
            body.methods.retain(|m| {
                let keep = match self.utf8(m.name).and_then(|b| std::str::from_utf8(b).ok()) {
                    Some(name) => match lambda_host(name) {
                        Some(host) => !removed_names.iter().any(|s| s == host),
                        None => true,
                    },
                    None => true,
                };
                if !keep {
                    if let Some(n) = self
                        .utf8(m.name)
                        .and_then(|b| String::from_utf8(b.to_vec()).ok())
                    {
                        removed_names.push(n);
                    }
                }
                keep
            });
            if body.methods.len() == before {
                break;
            }
        }
        let stripped = (before_f - body.fields.len() + before_m - body.methods.len()) as u64;
        stats.client_members_stripped += stripped;
        crate::add_client_members_stripped(stripped);
        if stripped > 0 {
            eprintln!("[cim-reloc] dist-strip: {original_name} removed {stripped} @OnlyIn(CLIENT) member(s)");
        }
        // Access transformers (M2): widen class + member access exactly like
        // a real AT-patched runtime. Lookups use the ORIGINAL names — the
        // pool is untouched at this point.
        if let Some(class_ats) = at.for_class(original_name) {
            for a in class_ats.iter() {
                body.access = crate::at::apply(body.access, *a);
            }
        }
        for member in body.fields.iter_mut() {
            if let Some(name) = self
                .utf8(member.name)
                .and_then(|b| std::str::from_utf8(b).ok())
            {
                if let Some(a) = at.for_member(original_name, name) {
                    member.access = crate::at::apply(member.access, a);
                }
            }
        }
        for member in body.methods.iter_mut() {
            if let (Some(name), Some(desc)) = (
                self.utf8(member.name)
                    .and_then(|b| std::str::from_utf8(b).ok()),
                self.utf8(member.descriptor)
                    .and_then(|b| std::str::from_utf8(b).ok()),
            ) {
                let key = format!("{name}{desc}");
                if let Some(a) = at
                    .for_member(original_name, &key)
                    .or_else(|| at.for_member(original_name, name))
                {
                    member.access = crate::at::apply(member.access, a);
                }
            }
        }
        for member in body.fields.iter_mut().chain(body.methods.iter_mut()) {
            if let Some(mapped) = self.rewrite_descriptor(table, member.descriptor)? {
                let idx = intern_descriptor(&mapped, &mut stats, &mut intern, &mut next_index)?;
                member.descriptor = idx;
            }
        }

        // ---- rebuild -----------------------------------------------------
        // The pool is emitted into its own buffer and the cp_count is written
        // only after the whole body was built: the refs-only annotation pass
        // interns fresh UTF-8s while re-emitting the body, and the header
        // must count them.
        let mut pool: Vec<u8> = Vec::with_capacity(48 * 1024);
        let mut out: Vec<u8> = Vec::with_capacity(64 * 1024);
        out.extend_from_slice(&[0xCA, 0xFE, 0xBA, 0xBE]);
        out.extend_from_slice(&self.minor_version.to_be_bytes());
        out.extend_from_slice(&self.major_version.to_be_bytes());
        // cp_count is written after the body (see below).

        for slot in 1..self.entries.len() {
            match rewritten.get(&(slot as u16)) {
                Some(e) => {
                    pool.push(e.tag);
                    pool.extend_from_slice(&e.data);
                }
                None => {
                    if let Some(e) = &self.entries[slot] {
                        pool.push(e.tag);
                        if e.tag == TAG_UTF8 {
                            // Parsed utf8 payload is the raw content; the
                            // on-disk form carries a u2 length prefix.
                            pool.extend_from_slice(&(e.data.len() as u16).to_be_bytes());
                        }
                        pool.extend_from_slice(&e.data);
                    }
                    // Placeholder slot after long/double: write nothing.
                }
            }
        }
        // NOTE: the appended-UTF8 block is emitted AFTER the body is built —
        // the refs-only annotation pass interns fresh UTF-8s while re-emitting
        // the body, and they must land in the pool too.

        // Body with updated member descriptors (and, in refs-only mode,
        // rewritten runtime annotation descriptors so JVM reflection sees the
        // relocated annotation classes — the eventbus's @SubscribeEvent check
        // depends on this).
        let rewrite_annos = true;
        let mut body_out: Vec<u8> = Vec::with_capacity(16 * 1024);
        body_out.extend_from_slice(&body.access.to_be_bytes());
        body_out.extend_from_slice(&body.this_class.to_be_bytes());
        body_out.extend_from_slice(&body.super_class.to_be_bytes());
        body_out.extend_from_slice(&(body.interfaces.len() as u16).to_be_bytes());
        for iface in &body.interfaces {
            body_out.extend_from_slice(&iface.to_be_bytes());
        }
        // Signatures AND runtime annotations are rewritten in BOTH modes:
        // JVM reflection resolves annotation type/enum/class descriptors by
        // name, and the eventbus's @SubscribeEvent check (plus
        // AnnotationParser default values) breaks on raw descriptors. FML's
        // own raw-byte annotation scanning is replaced by the cim-modscan
        // Rust scanner, so raw preservation is no longer required.
        write_members_annos(
            &mut body_out,
            &body.fields,
            self,
            table,
            &mut intern,
            &mut next_index,
            &mut stats,
            rewrite_annos,
        )?;
        write_members_annos(
            &mut body_out,
            &body.methods,
            self,
            table,
            &mut intern,
            &mut next_index,
            &mut stats,
            rewrite_annos,
        )?;
        rewrite_attr_block(
            &body.class_attrs,
            &mut body_out,
            self,
            table,
            &mut intern,
            &mut next_index,
            &mut stats,
            rewrite_annos,
        )?;

        // Emit the appended UTF-8s (member descriptors AND annotation
        // descriptors, both interned while building the body).
        for content in &intern.appended {
            pool.push(TAG_UTF8);
            pool.extend_from_slice(&(content.len() as u16).to_be_bytes());
            pool.extend_from_slice(content);
        }

        // Final pool count: member descriptors AND annotation descriptors
        // both appended while building the body.
        let total = self.entries.len() + intern.len();
        if total > u16::MAX as usize {
            return Err(ParseError::PoolOverflow);
        }
        out.extend_from_slice(&(total as u16).to_be_bytes());
        out.extend_from_slice(&pool);
        out.extend_from_slice(&body_out);

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

#[allow(dead_code)] // symmetric writer kept alongside the reader for tests
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

/// Non-advancing variant used by the annotation walker.
fn read_u16_at(bytes: &[u8], off: usize) -> Option<u16> {
    if off + 2 > bytes.len() {
        return None;
    }
    Some(u16::from_be_bytes([bytes[off], bytes[off + 1]]))
}

/// `lambda$<hostName>$<n>` -> Some(hostName); anything else -> None.
/// javac names synthetic lambda implementations after their host method; the
/// host name may itself contain '$' (nested classes use different naming),
/// so the host part is everything between the leading `lambda$` and the
/// LAST `$`.
fn lambda_host(name: &str) -> Option<&str> {
    let rest = name.strip_prefix("lambda$")?;
    let idx = rest.rfind('$')?;
    if idx == 0 {
        return None;
    }
    Some(&rest[..idx])
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

/// Attribute names carrying runtime annotations whose type descriptors the
/// JVM resolves by name at reflection time (refs-only mode rewrites them).
const RUNTIME_ANNOTATION_ATTRS: [&str; 4] = [
    "RuntimeVisibleAnnotations",
    "RuntimeInvisibleAnnotations",
    "RuntimeVisibleParameterAnnotations",
    "RuntimeInvisibleParameterAnnotations",
];

/// Annotation-element payload rewritten in BOTH modes: annotation DEFAULT
/// values (e.g. `@SubscribeEvent(priority = EventPriority.NORMAL)`) are read
/// by the JVM's AnnotationParser on the annotation interface itself — a layer
/// class — and its enum/class element descriptors must point at the
/// relocated names or reflection blows up with wrong-name NoClassDefFound.
const ANNOTATION_DEFAULT_ATTR: &str = "AnnotationDefault";

/// Rewrite one raw attribute block (count + entries), re-emitting runtime
/// annotation payloads with relocated type descriptors.
#[allow(clippy::too_many_arguments)] // raw attribute rewrite needs full context
fn rewrite_attr_block(
    block: &[u8],
    out: &mut Vec<u8>,
    cf: &ClassFile,
    table: &RelocationTable,
    intern: &mut Utf8Intern,
    next_index: &mut u16,
    stats: &mut RewriteStats,
    rewrite_annotations: bool,
) -> Result<(), ParseError> {
    if block.len() < 2 {
        out.extend_from_slice(block);
        return Ok(());
    }
    let count = u16::from_be_bytes([block[0], block[1]]);
    let mut pos = 2usize;
    let mut new_block: Vec<u8> = Vec::with_capacity(block.len());
    new_block.extend_from_slice(&count.to_be_bytes());
    for _ in 0..count {
        if pos + 6 > block.len() {
            // malformed tail: copy verbatim
            new_block.extend_from_slice(&block[pos..]);
            break;
        }
        let name_idx = u16::from_be_bytes([block[pos], block[pos + 1]]);
        let len = u32::from_be_bytes([
            block[pos + 2],
            block[pos + 3],
            block[pos + 4],
            block[pos + 5],
        ]) as usize;
        let content = block
            .get(pos + 6..pos + 6 + len)
            .ok_or(ParseError::Truncated("attr content"))?;
        pos += 6 + len;

        let attr_name = cf
            .utf8(name_idx)
            .and_then(|b| std::str::from_utf8(b).ok().map(String::from));
        let anno_kind = attr_name.as_deref().and_then(|n| {
            if n.ends_with("ParameterAnnotations") {
                Some(true)
            } else if RUNTIME_ANNOTATION_ATTRS.contains(&n) {
                Some(false)
            } else {
                None
            }
        });
        let is_annotation_default = attr_name.as_deref() == Some(ANNOTATION_DEFAULT_ATTR);
        if attr_name.as_deref() == Some("Signature") && content.len() >= 2 {
            // Generic signatures: one utf8 index; the JVM resolves class-type
            // segments through reflection by NAME, so they must be rewritten
            // in BOTH modes (layer classes' signatures reference relocated
            // types too — the eventbus's generic listener plumbing hits it).
            let sig_idx = u16::from_be_bytes([content[0], content[1]]);
            let orig = cf
                .utf8(sig_idx)
                .ok_or(ParseError::Truncated("signature utf8"))?;
            let orig = std::str::from_utf8(orig)
                .map_err(|_| ParseError::Truncated("non-utf8 signature"))?;
            let mapped = table.map_signature(orig);
            let new_idx = if mapped == orig {
                sig_idx
            } else {
                let idx = intern.intern(mapped.as_bytes(), next_index);
                stats.descriptors_rewritten += 1;
                idx
            };
            new_block.extend_from_slice(&name_idx.to_be_bytes());
            new_block.extend_from_slice(&2u32.to_be_bytes());
            new_block.extend_from_slice(&new_idx.to_be_bytes());
        } else if is_annotation_default {
            // AnnotationDefault: a bare element_value (no leading count).
            let mut r = ByteCursor::new(content);
            let mut o: Vec<u8> = Vec::with_capacity(content.len());
            rewrite_element_value(cf, table, intern, next_index, stats, &mut r, &mut o)?;
            o.extend_from_slice(r.rest());
            new_block.extend_from_slice(&name_idx.to_be_bytes());
            new_block.extend_from_slice(&(o.len() as u32).to_be_bytes());
            new_block.extend_from_slice(&o);
        } else if rewrite_annotations && anno_kind.is_some() {
            let is_param = matches!(anno_kind, Some(true));
            let rewritten = if is_param {
                // Runtime*ParameterAnnotations: num_params u1, then per param
                // (num_annotations u2 + annotations).
                let mut r = ByteCursor::new(content);
                let mut o: Vec<u8> = Vec::with_capacity(content.len());
                let num_params = r.u1().ok_or(ParseError::Truncated("param count"))?;
                o.push(num_params);
                for _ in 0..num_params {
                    let num = r.u2().ok_or(ParseError::Truncated("param anno count"))?;
                    o.extend_from_slice(&num.to_be_bytes());
                    for _ in 0..num {
                        rewrite_annotation(cf, table, intern, next_index, stats, &mut r, &mut o)?;
                    }
                }
                o.extend_from_slice(r.rest());
                o
            } else {
                rewrite_annotation_payload(cf, table, intern, next_index, stats, content)?
            };
            new_block.extend_from_slice(&name_idx.to_be_bytes());
            new_block.extend_from_slice(&(rewritten.len() as u32).to_be_bytes());
            new_block.extend_from_slice(&rewritten);
        } else {
            new_block.extend_from_slice(&block[pos - len - 6..pos]);
        }
    }
    out.extend_from_slice(&new_block);
    Ok(())
}

/// Re-emit one Runtime*Annotations payload with relocated annotation type
/// descriptors (and enum/class element descriptors).
fn rewrite_annotation_payload(
    cf: &ClassFile,
    table: &RelocationTable,
    intern: &mut Utf8Intern,
    next_index: &mut u16,
    stats: &mut RewriteStats,
    content: &[u8],
) -> Result<Vec<u8>, ParseError> {
    let mut r = ByteCursor::new(content);
    let mut out: Vec<u8> = Vec::with_capacity(content.len());
    let num = r.u2().ok_or(ParseError::Truncated("anno count"))?;
    out.extend_from_slice(&num.to_be_bytes());
    for _ in 0..num {
        rewrite_annotation(cf, table, intern, next_index, stats, &mut r, &mut out)?;
    }
    out.extend_from_slice(r.rest());
    Ok(out)
}

fn rewrite_annotation(
    cf: &ClassFile,
    table: &RelocationTable,
    intern: &mut Utf8Intern,
    next_index: &mut u16,
    stats: &mut RewriteStats,
    r: &mut ByteCursor<'_>,
    out: &mut Vec<u8>,
) -> Result<(), ParseError> {
    let type_idx = r.u2().ok_or(ParseError::Truncated("anno type idx"))?;
    out.extend_from_slice(
        &rewrite_utf8_desc(cf, table, intern, next_index, stats, type_idx)?.to_be_bytes(),
    );
    let num_pairs = r.u2().ok_or(ParseError::Truncated("anno pairs"))?;
    out.extend_from_slice(&num_pairs.to_be_bytes());
    for _ in 0..num_pairs {
        let name_idx = r.u2().ok_or(ParseError::Truncated("anno pair name"))?;
        out.extend_from_slice(&name_idx.to_be_bytes());
        rewrite_element_value(cf, table, intern, next_index, stats, r, out)?;
    }
    Ok(())
}

fn rewrite_element_value(
    cf: &ClassFile,
    table: &RelocationTable,
    intern: &mut Utf8Intern,
    next_index: &mut u16,
    stats: &mut RewriteStats,
    r: &mut ByteCursor<'_>,
    out: &mut Vec<u8>,
) -> Result<(), ParseError> {
    let tag = r.u1().ok_or(ParseError::Truncated("element tag"))?;
    out.push(tag);
    match tag {
        b'e' => {
            // enum: type descriptor (rewritten) + constant name (kept)
            let type_idx = r.u2().ok_or(ParseError::Truncated("enum type"))?;
            out.extend_from_slice(
                &rewrite_utf8_desc(cf, table, intern, next_index, stats, type_idx)?.to_be_bytes(),
            );
            let const_idx = r.u2().ok_or(ParseError::Truncated("enum const"))?;
            out.extend_from_slice(&const_idx.to_be_bytes());
        }
        b'c' => {
            // class element: descriptor rewritten (handles [L...;)
            let idx = r.u2().ok_or(ParseError::Truncated("class element"))?;
            out.extend_from_slice(
                &rewrite_utf8_desc(cf, table, intern, next_index, stats, idx)?.to_be_bytes(),
            );
        }
        b'@' => {
            rewrite_annotation(cf, table, intern, next_index, stats, r, out)?;
        }
        b'[' => {
            let num = r.u2().ok_or(ParseError::Truncated("array element"))?;
            out.extend_from_slice(&num.to_be_bytes());
            for _ in 0..num {
                rewrite_element_value(cf, table, intern, next_index, stats, r, out)?;
            }
        }
        _ => {
            // every other tag carries exactly one u2 constant-pool index —
            // primitive consts and string consts are never relocated.
            let idx = r.u2().ok_or(ParseError::Truncated("element const"))?;
            out.extend_from_slice(&idx.to_be_bytes());
        }
    }
    Ok(())
}

/// Map one utf8 slot holding an `L…;` descriptor (or array descriptor);
/// returns the NEW utf8 index (original when unchanged).
fn rewrite_utf8_desc(
    cf: &ClassFile,
    table: &RelocationTable,
    intern: &mut Utf8Intern,
    next_index: &mut u16,
    stats: &mut RewriteStats,
    idx: u16,
) -> Result<u16, ParseError> {
    let orig = cf
        .utf8(idx)
        .ok_or(ParseError::Truncated("descriptor utf8"))?;
    let orig =
        std::str::from_utf8(orig).map_err(|_| ParseError::Truncated("non-utf8 descriptor"))?;
    let mapped = table.map_descriptor(orig);
    if mapped == orig {
        return Ok(idx);
    }
    let new_idx = intern.intern(mapped.as_bytes(), next_index);
    stats.descriptors_rewritten += 1;
    Ok(new_idx)
}

#[allow(clippy::too_many_arguments)] // attribute re-emission needs full context
fn write_members_annos(
    out: &mut Vec<u8>,
    members: &[Member],
    cf: &ClassFile,
    table: &RelocationTable,
    intern: &mut Utf8Intern,
    next_index: &mut u16,
    stats: &mut RewriteStats,
    rewrite_annotations: bool,
) -> Result<(), ParseError> {
    out.extend_from_slice(&(members.len() as u16).to_be_bytes());
    for m in members {
        out.extend_from_slice(&m.access.to_be_bytes());
        out.extend_from_slice(&m.name.to_be_bytes());
        out.extend_from_slice(&m.descriptor.to_be_bytes());
        rewrite_attr_block(
            &m.attrs,
            out,
            cf,
            table,
            intern,
            next_index,
            stats,
            rewrite_annotations,
        )?;
    }
    Ok(())
}

/// Minimal big-endian cursor over a byte slice (annotation payloads).
struct ByteCursor<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> ByteCursor<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn u1(&mut self) -> Option<u8> {
        let b = *self.buf.get(self.pos)?;
        self.pos += 1;
        Some(b)
    }

    fn u2(&mut self) -> Option<u16> {
        if self.pos + 2 > self.buf.len() {
            return None;
        }
        let v = u16::from_be_bytes([self.buf[self.pos], self.buf[self.pos + 1]]);
        self.pos += 2;
        Some(v)
    }

    fn rest(&self) -> &[u8] {
        &self.buf[self.pos.min(self.buf.len())..]
    }
}

#[cfg(test)]
mod tests {
    use super::lambda_host;

    #[test]
    fn lambda_host_names_parse_like_javac() {
        // javac: lambda$<hostMethod>$<counter> — the counter is the LAST $.
        assert_eq!(
            lambda_host("lambda$registerCTBehviour$25"),
            Some("registerCTBehviour")
        );
        assert_eq!(lambda_host("lambda$accept$0"), Some("accept"));
        // a lambda hosted by ANOTHER lambda (fixpoint chain)
        assert_eq!(
            lambda_host("lambda$lambda$connectedTextures$24$3"),
            Some("lambda$connectedTextures$24")
        );
        // non-lambda members are untouched
        assert_eq!(lambda_host("create"), None);
        assert_eq!(lambda_host("lambda$"), None);
        // nested-class style names keep their host part
        assert_eq!(lambda_host("lambda$new$2"), Some("new"));
    }
}
