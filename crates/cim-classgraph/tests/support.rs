//! Hand-assembled class files and in-memory jars. No JDK is needed.
#![allow(dead_code)]

use std::io::{Cursor, Write};

use zip::write::SimpleFileOptions;

pub struct Pool {
    entries: Vec<Vec<u8>>,
}

impl Default for Pool {
    fn default() -> Self {
        Self::new()
    }
}

impl Pool {
    pub fn new() -> Self {
        Pool { entries: Vec::new() }
    }
    fn push(&mut self, bytes: Vec<u8>, slots: usize) -> u16 {
        self.entries.push(bytes);
        let idx = self.count_slots() - slots + 1;
        idx as u16
    }
    fn count_slots(&self) -> usize {
        // Long/Double entries are recorded with a trailing marker, see `long`.
        self.entries.iter().map(|e| if e.first() == Some(&0xFE) { 2 } else { 1 }).sum()
    }
    pub fn utf8(&mut self, s: &str) -> u16 {
        let mut b = vec![1u8];
        b.extend((s.len() as u16).to_be_bytes());
        b.extend(s.as_bytes());
        self.push(b, 1)
    }
    pub fn class(&mut self, name: &str) -> u16 {
        let n = self.utf8(name);
        let mut b = vec![7u8];
        b.extend(n.to_be_bytes());
        self.push(b, 1)
    }
    /// A Long constant (two pool slots); stored with a marker so slot counting works.
    pub fn long(&mut self) -> u16 {
        let mut b = vec![0xFEu8, 5];
        b.extend([0u8; 8]);
        let idx = self.count_slots() + 1;
        self.entries.push(b);
        idx as u16
    }
    fn bytes(&self) -> Vec<u8> {
        let mut out = Vec::new();
        let total: usize = self.count_slots();
        out.extend(((total + 1) as u16).to_be_bytes());
        for e in &self.entries {
            if e.first() == Some(&0xFE) {
                out.extend(&e[1..]);
            } else {
                out.extend(e);
            }
        }
        out
    }
}

/// Assemble a class file: header, constant pool, the given flags, this/super/interfaces,
/// then empty field/method/attribute tables (so the parser must stop at the header).
pub fn class_file(name: &str, super_name: Option<&str>, ifaces: &[&str], access: u16, with_long: bool) -> Vec<u8> {
    let mut pool = Pool::new();
    if with_long {
        pool.long(); // occupies two slots before the names, exercising slot accounting
    }
    let this = pool.class(name);
    let sup = super_name.map(|s| pool.class(s)).unwrap_or(0);
    let ifs: Vec<u16> = ifaces.iter().map(|i| pool.class(i)).collect();
    let mut out = vec![0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 65];
    out.extend(pool.bytes());
    out.extend(access.to_be_bytes());
    out.extend(this.to_be_bytes());
    out.extend(sup.to_be_bytes());
    out.extend((ifs.len() as u16).to_be_bytes());
    for i in ifs {
        out.extend(i.to_be_bytes());
    }
    // fields_count, methods_count, attributes_count
    out.extend([0u8; 6]);
    out
}

/// A zip (jar) with the given (name, bytes) entries, stored uncompressed.
pub fn jar(entries: &[(&str, Vec<u8>)]) -> Vec<u8> {
    let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let opts = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for (name, data) in entries {
        w.start_file(*name, opts).unwrap();
        w.write_all(data).unwrap();
    }
    w.finish().unwrap().into_inner()
}
