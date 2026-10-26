//! Minimal JVM class-file reader: constant pool + class-level
//! `RuntimeVisibleAnnotations`.
//!
//! This exists so mod discovery never needs a JVM: `@Mod("modid")` is read
//! straight out of raw `.class` bytes inside the mod jar. The F2V invariant
//! applies — **we never load or execute mod classes**; this parser is pure
//! byte inspection with hard budget caps.
//!
//! Scope (deliberate): enough of the format to (a) resolve the constant pool,
//! (b) skip fields/methods structurally, (c) parse class-level annotations
//! with full element-value support. Method/field annotations, stack maps and
//! the full attribute graph are out of scope until F2V v2 discovery needs
//! them (M4+).

use crate::ScannerError;
use std::collections::HashMap;

/// A parsed annotation element value.
#[derive(Debug, Clone, PartialEq)]
pub enum ElementValue {
    Bool(bool),
    Byte(i8),
    Char(char),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    Str(String),
    Enum {
        type_descriptor: String,
        constant: String,
    },
    Class {
        descriptor: String,
    },
    Annotation(Annotation),
    Array(Vec<ElementValue>),
}

/// One class-level annotation.
#[derive(Debug, Clone, PartialEq)]
pub struct Annotation {
    /// Field descriptor, e.g. `Lnet/neoforged/fml/common/Mod;`.
    pub type_descriptor: String,
    /// Element values by name (order preserved).
    pub values: Vec<(String, ElementValue)>,
}

impl Annotation {
    /// Convenience: element `name` when it is a string.
    pub fn string_value(&self, name: &str) -> Option<&str> {
        self.values
            .iter()
            .find(|(k, _)| k == name)
            .and_then(|(_, v)| match v {
                ElementValue::Str(s) => Some(s.as_str()),
                _ => None,
            })
    }

    /// Convenience: element `name` when it is an array of strings.
    pub fn string_array_value(&self, name: &str) -> Option<Vec<&str>> {
        self.values
            .iter()
            .find(|(k, _)| k == name)
            .and_then(|(_, v)| match v {
                ElementValue::Array(items) => {
                    let mut out = Vec::with_capacity(items.len());
                    for item in items {
                        match item {
                            ElementValue::Str(s) => out.push(s.as_str()),
                            _ => return None,
                        }
                    }
                    Some(out)
                }
                _ => None,
            })
    }

    /// All string values of the `value` element, flattened:
    /// `"x"` → `["x"]`, `"value" = {"x","y"}` → `["x","y"]`.
    pub fn modid_values(&self) -> Vec<String> {
        let flat = |v: Option<Vec<&str>>| v.map(|xs| xs.into_iter().map(String::from).collect());
        match self.string_value("value") {
            Some(s) => vec![s.to_string()],
            None => flat(self.string_array_value("value")).unwrap_or_default(),
        }
    }
}

/// One method-level annotation hit (the scanner's interest set).
#[derive(Debug, Clone, PartialEq)]
pub struct MethodAnnotation {
    /// Method name, e.g. `commonSetup`.
    pub method: String,
    /// Method descriptor, e.g. `(Lnet/neoforged/fml/event/lifecycle/FMLCommonSetupEvent;)V`.
    pub descriptor: String,
    pub annotation: Annotation,
}

/// Class-level facts the scanner cares about.
#[derive(Debug, Default, Clone)]
pub struct ClassInfo {
    pub major_version: u16,
    /// Internal class name, e.g. `dev/cim/testmod/CimTestMod`.
    pub this_class: String,
    pub annotations: Vec<Annotation>,
    /// Runtime-visible method annotations (interest-filtered by the scanner;
    /// the parser collects all of them, capped).
    pub method_annotations: Vec<MethodAnnotation>,
}

const MAX_POOL_ENTRIES: usize = 65_535;
const MAX_ANNOTATIONS: usize = 256;
const MAX_ANNOTATION_DEPTH: usize = 8;
const MAX_METHODS_PARSED: usize = 2_000;

/// Resolved constant pool values the annotation parser needs.
#[derive(Debug, Default)]
struct Pool {
    utf8: HashMap<u16, String>,
    strings: HashMap<u16, String>,
    ints: HashMap<u16, i32>,
    longs: HashMap<u16, i64>,
    classes: HashMap<u16, String>,
    floats: HashMap<u16, f32>,
    doubles: HashMap<u16, f64>,
}

impl Pool {
    fn str_at(&self, idx: u16) -> Result<&str, ScannerError> {
        self.utf8
            .get(&idx)
            .map(String::as_str)
            .ok_or_else(|| ScannerError::ClassFile(format!("dangling utf8 index {idx}")))
    }
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], ScannerError> {
        if self.buf.len() < self.pos + n {
            return Err(ScannerError::ClassFile(format!(
                "truncated at {} (wanted {n} bytes)",
                self.pos
            )));
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    fn u1(&mut self) -> Result<u8, ScannerError> {
        Ok(self.take(1)?[0])
    }

    fn u2(&mut self) -> Result<u16, ScannerError> {
        let b = self.take(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    fn u4(&mut self) -> Result<u32, ScannerError> {
        let b = self.take(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }
}

/// Skip a field_info table (name/descriptor/attributes) without parsing.
fn skip_members(r: &mut Reader<'_>) -> Result<(), ScannerError> {
    let count = r.u2()? as usize;
    for _ in 0..count {
        r.u2()?; // access
        r.u2()?; // name
        r.u2()?; // descriptor
        let attrs = r.u2()? as usize;
        for _ in 0..attrs {
            r.u2()?; // attr name index
            let len = r.u4()? as usize;
            r.take(len)?;
        }
    }
    Ok(())
}

/// Parse a class file. Errors are descriptive, never panics.
pub fn parse_class(bytes: &[u8]) -> Result<ClassInfo, ScannerError> {
    let mut r = Reader { buf: bytes, pos: 0 };
    let magic = r.u4()?;
    if magic != 0xCAFEBABE {
        return Err(ScannerError::ClassFile(format!("bad magic {magic:#010x}")));
    }
    let _minor = r.u2()?;
    let major = r.u2()?;

    let cp_count = r.u2()? as usize;
    if cp_count == 0 || cp_count > MAX_POOL_ENTRIES {
        return Err(ScannerError::ClassFile(format!("bad cp_count {cp_count}")));
    }

    // Raw pool first (String/Class entries stay as indices), then resolve.
    enum Raw {
        Utf8(String),
        Str(u16),
        Class(u16),
        Int(i32),
        Long(i64),
        Float(f32),
        Double(f64),
        Skip,
    }
    let mut raw: Vec<Option<Raw>> = (0..cp_count).map(|_| None).collect();
    let mut i = 1usize;
    while i < cp_count {
        let tag = r.u1()?;
        let entry = match tag {
            1 => {
                let len = r.u2()? as usize;
                Raw::Utf8(String::from_utf8_lossy(r.take(len)?).into_owned())
            }
            3 => Raw::Int(i32::from_be_bytes(r.take(4)?.try_into().expect("4"))),
            4 => Raw::Float(f32::from_be_bytes(r.take(4)?.try_into().expect("4"))),
            5 => Raw::Long(i64::from_be_bytes(r.take(8)?.try_into().expect("8"))),
            6 => Raw::Double(f64::from_be_bytes(r.take(8)?.try_into().expect("8"))),
            7 => Raw::Class(r.u2()?),
            8 => Raw::Str(r.u2()?),
            9 | 10 | 11 | 12 | 17 | 18 => {
                r.u2()?;
                r.u2()?;
                Raw::Skip
            }
            15 => {
                r.u1()?;
                r.u2()?;
                Raw::Skip
            }
            16 => {
                r.u2()?;
                Raw::Skip
            }
            19 | 20 => {
                r.u2()?;
                Raw::Skip
            }
            other => {
                return Err(ScannerError::ClassFile(format!(
                    "unknown cp tag {other} at entry {i}"
                )))
            }
        };
        raw[i] = Some(entry);
        if matches!(raw[i], Some(Raw::Long(_)) | Some(Raw::Double(_))) {
            i += 1; // two-slot entries
        }
        i += 1;
    }

    let mut pool = Pool::default();
    // Resolution is two-pass: UTF-8 entries first (forward references from
    // Class/String constants are legal — javac emits `cp[2]=Class -> cp[4]=Utf8`
    // routinely), then the dependent entries.
    for (idx, entry) in raw.iter().enumerate().skip(1) {
        let idx = u16::try_from(idx).map_err(|_| ScannerError::ClassFile("cp overflow".into()))?;
        if let Some(Raw::Utf8(s)) = entry {
            pool.utf8.insert(idx, s.clone());
        }
    }
    for (idx, entry) in raw.iter().enumerate().skip(1) {
        let idx = u16::try_from(idx).map_err(|_| ScannerError::ClassFile("cp overflow".into()))?;
        match entry {
            Some(Raw::Str(u)) => {
                let s = pool.utf8.get(u).cloned().ok_or_else(|| {
                    ScannerError::ClassFile(format!("string {idx} -> utf8 {u} missing"))
                })?;
                pool.strings.insert(idx, s);
            }
            Some(Raw::Class(u)) => {
                let s = pool.utf8.get(u).cloned().ok_or_else(|| {
                    ScannerError::ClassFile(format!("class {idx} -> utf8 {u} missing"))
                })?;
                pool.classes.insert(idx, s);
            }
            _ => {}
        }
    }
    for (idx, entry) in raw.iter().enumerate().skip(1) {
        let idx = u16::try_from(idx).map_err(|_| ScannerError::ClassFile("cp overflow".into()))?;
        match entry {
            Some(Raw::Int(v)) => {
                pool.ints.insert(idx, *v);
            }
            Some(Raw::Long(v)) => {
                pool.longs.insert(idx, *v);
            }
            Some(Raw::Float(v)) => {
                pool.floats.insert(idx, *v);
            }
            Some(Raw::Double(v)) => {
                pool.doubles.insert(idx, *v);
            }
            _ => {}
        }
    }

    // Access flags, this, super, interfaces.
    let _access = r.u2()?;
    let this_idx = r.u2()?;
    let _super_idx = r.u2()?;
    let this_class = pool.classes.get(&this_idx).cloned().unwrap_or_default();
    let iface_count = r.u2()? as usize;
    r.take(iface_count * 2)?;

    // Fields: skip structurally (field annotations are not in the scanner's
    // interest set and their names/descriptors are irrelevant).
    skip_members(&mut r)?;

    // Methods: parse names + RuntimeVisibleAnnotations (@SubscribeEvent).
    let method_count = r.u2()? as usize;
    let mut method_annotations = Vec::new();
    for _ in 0..method_count.min(MAX_METHODS_PARSED) {
        let _access = r.u2()?;
        let name_idx = r.u2()?;
        let desc_idx = r.u2()?;
        let attr_count = r.u2()? as usize;
        for _ in 0..attr_count {
            let attr_name_idx = r.u2()?;
            let len = r.u4()? as usize;
            let data = r.take(len)?;
            if pool.str_at(attr_name_idx)? == "RuntimeVisibleAnnotations" {
                let mut ar = Reader { buf: data, pos: 0 };
                let count = ar.u2()? as usize;
                if count > MAX_ANNOTATIONS {
                    return Err(ScannerError::ClassFile(format!(
                        "too many method annotations: {count}"
                    )));
                }
                for _ in 0..count {
                    let annotation = parse_annotation(&mut ar, &pool, 0)?;
                    method_annotations.push(MethodAnnotation {
                        method: pool.str_at(name_idx)?.to_string(),
                        descriptor: pool.str_at(desc_idx)?.to_string(),
                        annotation,
                    });
                }
            }
        }
    }

    // Class attributes.
    let mut info = ClassInfo {
        major_version: major,
        this_class,
        annotations: Vec::new(),
        method_annotations,
    };
    let attr_count = r.u2()? as usize;
    for _ in 0..attr_count {
        let name_idx = r.u2()?;
        let len = r.u4()? as usize;
        let data = r.take(len)?;
        if pool.str_at(name_idx)? == "RuntimeVisibleAnnotations" {
            let mut ar = Reader { buf: data, pos: 0 };
            let count = ar.u2()? as usize;
            if count > MAX_ANNOTATIONS {
                return Err(ScannerError::ClassFile(format!(
                    "too many annotations: {count}"
                )));
            }
            for _ in 0..count {
                info.annotations.push(parse_annotation(&mut ar, &pool, 0)?);
            }
        }
    }
    Ok(info)
}

fn parse_annotation(
    r: &mut Reader<'_>,
    pool: &Pool,
    depth: usize,
) -> Result<Annotation, ScannerError> {
    if depth > MAX_ANNOTATION_DEPTH {
        return Err(ScannerError::ClassFile(
            "annotation nesting too deep".into(),
        ));
    }
    let type_idx = r.u2()?;
    let type_descriptor = pool.str_at(type_idx)?.to_string();
    let num_pairs = r.u2()? as usize;
    let mut values = Vec::with_capacity(num_pairs.min(64));
    for _ in 0..num_pairs {
        let name_idx = r.u2()?;
        let name = pool.str_at(name_idx)?.to_string();
        let value = parse_element_value(r, pool, depth + 1)?;
        values.push((name, value));
    }
    Ok(Annotation {
        type_descriptor,
        values,
    })
}

fn parse_element_value(
    r: &mut Reader<'_>,
    pool: &Pool,
    depth: usize,
) -> Result<ElementValue, ScannerError> {
    let tag = r.u1()?;
    Ok(match tag {
        b'Z' => ElementValue::Bool(pool_int(r, pool)? != 0),
        b'B' => ElementValue::Byte(pool_int(r, pool)? as i8),
        b'C' => {
            let v = u32::try_from(pool_int(r, pool)?)
                .map_err(|_| ScannerError::ClassFile("char value out of range".into()))?;
            ElementValue::Char(char::from_u32(v).unwrap_or('\u{0}'))
        }
        b'S' => ElementValue::Short(pool_int(r, pool)? as i16),
        b'I' => ElementValue::Int(pool_int(r, pool)?),
        b'J' => {
            let idx = r.u2()?;
            let v = *pool
                .longs
                .get(&idx)
                .ok_or_else(|| ScannerError::ClassFile(format!("dangling long index {idx}")))?;
            ElementValue::Long(v)
        }
        b'F' => {
            let idx = r.u2()?;
            let v = *pool
                .floats
                .get(&idx)
                .ok_or_else(|| ScannerError::ClassFile(format!("dangling float index {idx}")))?;
            ElementValue::Float(v)
        }
        b'D' => {
            let idx = r.u2()?;
            let v = *pool
                .doubles
                .get(&idx)
                .ok_or_else(|| ScannerError::ClassFile(format!("dangling double index {idx}")))?;
            ElementValue::Double(v)
        }
        b's' => {
            let idx = r.u2()?;
            // JVMS says 's' references a CONSTANT_String, but real javac
            // output (verified via javap on production mods, e.g. Jade
            // 15.10.x compiled with JDK 21) sometimes references the Utf8
            // entry directly. Accept both; fail loudly otherwise.
            let s = pool
                .strings
                .get(&idx)
                .cloned()
                .or_else(|| pool.utf8.get(&idx).cloned())
                .unwrap_or_else(|| format!("<unresolved-string-{idx}>"));
            ElementValue::Str(s)
        }
        b'e' => {
            let type_idx = r.u2()?;
            let const_idx = r.u2()?;
            ElementValue::Enum {
                type_descriptor: pool.str_at(type_idx)?.to_string(),
                constant: pool.str_at(const_idx)?.to_string(),
            }
        }
        b'c' => {
            let idx = r.u2()?;
            ElementValue::Class {
                descriptor: pool.str_at(idx)?.to_string(),
            }
        }
        b'@' => ElementValue::Annotation(parse_annotation(r, pool, depth)?),
        b'[' => {
            let count = r.u2()? as usize;
            if count > MAX_ANNOTATIONS {
                return Err(ScannerError::ClassFile(format!(
                    "array value too long: {count}"
                )));
            }
            let mut items = Vec::with_capacity(count.min(256));
            for _ in 0..count {
                items.push(parse_element_value(r, pool, depth)?);
            }
            ElementValue::Array(items)
        }
        other => {
            return Err(ScannerError::ClassFile(format!(
                "unknown element value tag {other:#x}"
            )))
        }
    })
}

fn pool_int(r: &mut Reader<'_>, pool: &Pool) -> Result<i32, ScannerError> {
    let idx = r.u2()?;
    pool.ints
        .get(&idx)
        .copied()
        .ok_or_else(|| ScannerError::ClassFile(format!("dangling int index {idx}")))
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Sequential constant-pool builder producing the binary pool section.
    struct PoolBuilder {
        bytes: Vec<u8>,
        count: u16,
    }

    impl PoolBuilder {
        fn new() -> Self {
            Self {
                bytes: Vec::new(),
                count: 0,
            }
        }

        fn utf8(&mut self, s: &str) -> u16 {
            self.count += 1;
            let idx = self.count;
            self.bytes.push(1);
            self.bytes
                .extend_from_slice(&(s.len() as u16).to_be_bytes());
            self.bytes.extend_from_slice(s.as_bytes());
            idx
        }

        fn class(&mut self, name_idx: u16) -> u16 {
            self.count += 1;
            let idx = self.count;
            self.bytes.push(7);
            self.bytes.extend_from_slice(&name_idx.to_be_bytes());
            idx
        }

        fn string_const(&mut self, utf8_idx: u16) -> u16 {
            self.count += 1;
            let idx = self.count;
            self.bytes.push(8);
            self.bytes.extend_from_slice(&utf8_idx.to_be_bytes());
            idx
        }

        fn int(&mut self, v: i32) -> u16 {
            self.count += 1;
            let idx = self.count;
            self.bytes.push(3);
            self.bytes.extend_from_slice(&v.to_be_bytes());
            idx
        }
    }

    /// Serialize one annotation element value, appending needed pool entries.
    fn element_value_bytes(p: &mut PoolBuilder, v: &ElementValue) -> Vec<u8> {
        match v {
            ElementValue::Str(s) => {
                let u = p.utf8(s);
                let c = p.string_const(u);
                vec![b's', (c >> 8) as u8, c as u8]
            }
            ElementValue::Int(i) => {
                let c = p.int(*i);
                vec![b'I', (c >> 8) as u8, c as u8]
            }
            ElementValue::Bool(b) => {
                let c = p.int(i32::from(*b));
                vec![b'Z', (c >> 8) as u8, c as u8]
            }
            ElementValue::Array(items) => {
                let mut out = vec![b'[', (items.len() >> 8) as u8, items.len() as u8];
                for item in items {
                    out.extend(element_value_bytes(p, item));
                }
                out
            }
            other => panic!("fixture builder does not support {other:?}"),
        }
    }

    /// Assemble a valid class file with a single class-level annotation and
    /// optional methods carrying runtime-visible annotations.
    fn fixture_class_full(
        descriptor: &str,
        pairs: &[(&str, ElementValue)],
        methods: &[(&str, &str, &str, &[(&str, ElementValue)])], // (name, desc, ann-desc, pairs)
    ) -> Vec<u8> {
        let mut p = PoolBuilder::new();
        let attr_name = p.utf8("RuntimeVisibleAnnotations");
        let ann_type = p.utf8(descriptor);
        let this_utf = p.utf8("dev/cim/Fixture");
        let this_class = p.class(this_utf);
        let super_utf = p.utf8("java/lang/Object");
        let super_class = p.class(super_utf);

        // Class-level annotation payload.
        let mut payload = Vec::new();
        payload.extend_from_slice(&1u16.to_be_bytes());
        payload.extend_from_slice(&ann_type.to_be_bytes());
        payload.extend_from_slice(&(pairs.len() as u16).to_be_bytes());
        for (name, value) in pairs {
            let name_idx = p.utf8(name);
            payload.extend_from_slice(&name_idx.to_be_bytes());
            payload.extend(element_value_bytes(&mut p, value));
        }

        let mut attr = Vec::new();
        attr.extend_from_slice(&attr_name.to_be_bytes());
        attr.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        attr.extend_from_slice(&payload);

        // Method_info table with one annotated method per fixture entry.
        let mut methods_bytes = Vec::new();
        methods_bytes.extend_from_slice(&(methods.len() as u16).to_be_bytes());
        for (name, desc, mdesc, mpairs) in methods {
            let name_idx = p.utf8(name);
            let desc_idx = p.utf8(desc);
            let mdesc_type = p.utf8(mdesc);
            let mut mpayload = Vec::new();
            mpayload.extend_from_slice(&1u16.to_be_bytes());
            mpayload.extend_from_slice(&mdesc_type.to_be_bytes());
            mpayload.extend_from_slice(&(mpairs.len() as u16).to_be_bytes());
            for (mname, mvalue) in *mpairs {
                let mname_idx = p.utf8(mname);
                mpayload.extend_from_slice(&mname_idx.to_be_bytes());
                mpayload.extend(element_value_bytes(&mut p, mvalue));
            }
            let mut mattr = Vec::new();
            mattr.extend_from_slice(&attr_name.to_be_bytes());
            mattr.extend_from_slice(&(mpayload.len() as u32).to_be_bytes());
            mattr.extend_from_slice(&mpayload);
            methods_bytes.extend_from_slice(&0x0009u16.to_be_bytes()); // public static
            methods_bytes.extend_from_slice(&name_idx.to_be_bytes());
            methods_bytes.extend_from_slice(&desc_idx.to_be_bytes());
            methods_bytes.extend_from_slice(&1u16.to_be_bytes()); // 1 attribute
            methods_bytes.extend_from_slice(&mattr);
        }

        // Class assembly.
        let mut out = Vec::new();
        out.extend_from_slice(&0xCAFE_BABEu32.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes()); // minor
        out.extend_from_slice(&52u16.to_be_bytes()); // major
        out.extend_from_slice(&(p.count + 1).to_be_bytes()); // cp_count
        out.extend_from_slice(&p.bytes);
        out.extend_from_slice(&0x0021u16.to_be_bytes()); // public super
        out.extend_from_slice(&this_class.to_be_bytes());
        out.extend_from_slice(&super_class.to_be_bytes());
        out.extend_from_slice(&0u16.to_be_bytes()); // interfaces
        out.extend_from_slice(&0u16.to_be_bytes()); // fields
        out.extend_from_slice(&methods_bytes);
        out.extend_from_slice(&1u16.to_be_bytes()); // class attributes
        out.extend_from_slice(&attr);
        out
    }

    fn fixture_class(descriptor: &str, pairs: &[(&str, ElementValue)]) -> Vec<u8> {
        fixture_class_full(descriptor, pairs, &[])
    }

    /// A class annotated with NeoForge's `@Mod("modid")`.
    pub(crate) fn fixture_mod_class(modid: &str) -> Vec<u8> {
        fixture_class(
            "Lnet/neoforged/fml/common/Mod;",
            &[("value", ElementValue::Str(modid.to_string()))],
        )
    }

    /// A class annotated with `@Mod(value = {"a", "b"})`.
    pub(crate) fn fixture_mod_class_array(modids: &[&str]) -> Vec<u8> {
        fixture_class(
            "Lnet/neoforged/fml/common/Mod;",
            &[(
                "value",
                ElementValue::Array(
                    modids
                        .iter()
                        .map(|s| ElementValue::Str(s.to_string()))
                        .collect(),
                ),
            )],
        )
    }

    #[test]
    fn parses_fixture_mod_class() {
        let info = parse_class(&fixture_mod_class("cimcoded")).unwrap();
        assert_eq!(info.this_class, "dev/cim/Fixture");
        assert_eq!(info.major_version, 52);
        assert_eq!(info.annotations.len(), 1);
        assert_eq!(
            info.annotations[0].type_descriptor,
            "Lnet/neoforged/fml/common/Mod;"
        );
        assert_eq!(info.annotations[0].modid_values(), ["cimcoded"]);
    }

    #[test]
    fn parses_method_annotations() {
        let info = parse_class(&fixture_class_full(
            "Lnet/neoforged/fml/common/Mod;",
            &[("value", ElementValue::Str("m".into()))],
            &[
                (
                    "commonSetup",
                    "(Lnet/neoforged/fml/event/lifecycle/FMLCommonSetupEvent;)V",
                    "Lnet/neoforged/bus/api/SubscribeEvent;",
                    &[],
                ),
                ("unannotated", "()V", "Lother/Ann;", &[]),
            ],
        ))
        .unwrap();
        assert_eq!(info.method_annotations.len(), 2);
        let hit = &info.method_annotations[0];
        assert_eq!(hit.method, "commonSetup");
        assert_eq!(
            hit.descriptor,
            "(Lnet/neoforged/fml/event/lifecycle/FMLCommonSetupEvent;)V"
        );
        assert_eq!(
            hit.annotation.type_descriptor,
            "Lnet/neoforged/bus/api/SubscribeEvent;"
        );
        assert_eq!(info.method_annotations[1].method, "unannotated");
    }

    #[test]
    fn parses_numeric_and_bool_values() {
        let info = parse_class(&fixture_class(
            "Lcom/example/Cfg;",
            &[
                ("count", ElementValue::Int(42)),
                ("enabled", ElementValue::Bool(true)),
            ],
        ))
        .unwrap();
        let ann = &info.annotations[0];
        assert_eq!(ann.string_array_value("count"), None);
        let pairs: Vec<(String, i32)> = ann
            .values
            .iter()
            .map(|(k, v)| {
                (
                    k.clone(),
                    match v {
                        ElementValue::Int(i) => *i,
                        ElementValue::Bool(b) => i32::from(*b),
                        other => panic!("unexpected {other:?}"),
                    },
                )
            })
            .collect();
        assert_eq!(pairs, [("count".into(), 42), ("enabled".into(), 1)]);
    }

    #[test]
    fn truncated_class_errors() {
        let full = fixture_mod_class("x");
        for cut in [0usize, 3, 9, 20, full.len() / 2] {
            assert!(parse_class(&full[..cut]).is_err(), "cut={cut} must error");
        }
    }

    #[test]
    fn garbage_is_rejected() {
        assert!(parse_class(&[0u8; 16]).is_err());
        assert!(parse_class(&[0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 50, 0, 2, 99]).is_err());
    }
}
