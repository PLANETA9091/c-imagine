//! Class-hierarchy index over jar files.
//!
//! Reads every `.jar` (and nested `META-INF/jarjar/*.jar` / `*.jar` entries,
//! to a bounded depth), parses only each class file's header
//! ([`header::parse_header`]) and records `name -> (super, interfaces, flags,
//! source)`. Jars are processed in parallel with std scoped threads. The
//! result can be cached on disk keyed by the jar paths, sizes and mtimes, so a
//! second boot loads the index without touching any jar.
//!
//! Subclass semantics: [`Index::is_subclass`] is strict and transitive over
//! superclasses and superinterfaces (`is_subclass(a, a)` is false).
//! [`Index::is_a`] is the reflexive form.

pub mod header;

use std::collections::{HashMap, HashSet, VecDeque};
use std::fs;
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

use serde::{Deserialize, Serialize};

pub use header::{parse_header, Header, HeaderError};

/// Maximum nesting depth followed for jar-in-jar archives.
pub const MAX_NESTING: u32 = 4;

/// Per-class record stored in the index.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClassInfo {
    pub super_name: Option<String>,
    pub interfaces: Vec<String>,
    pub access: u16,
    /// Index into [`Index::sources`].
    pub source: u32,
}

/// Name -> record map plus the source labels (jar path, or `outer!/inner` for nested jars).
#[derive(Debug, Default, Clone)]
pub struct Index {
    classes: HashMap<String, ClassInfo>,
    sources: Vec<String>,
}

/// Errors surfaced by the cache layer. Parse failures of individual class
/// files are skipped, never fatal.
#[derive(Debug, thiserror::Error)]
pub enum IndexError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("cache: {0}")]
    Cache(#[from] serde_json::Error),
}

impl Index {
    pub fn len(&self) -> usize {
        self.classes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.classes.is_empty()
    }

    pub fn get(&self, name: &str) -> Option<&ClassInfo> {
        self.classes.get(name)
    }

    /// True when `name` is an indexed class.
    pub fn knows(&self, name: &str) -> bool {
        self.classes.contains_key(name)
    }

    pub fn source_of(&self, name: &str) -> Option<&str> {
        self.classes.get(name).map(|c| self.sources[c.source as usize].as_str())
    }

    pub fn sources(&self) -> &[String] {
        &self.sources
    }

    /// Direct supertypes (superclass first, then interfaces) of `name`.
    fn direct_supers(&self, name: &str) -> impl Iterator<Item = &str> {
        self.classes
            .get(name)
            .into_iter()
            .flat_map(|c| c.super_name.iter().chain(c.interfaces.iter()))
            .map(|s| s.as_str())
    }

    /// Transitive ancestors of `name` in BFS order, excluding `name`. Cycle-safe.
    /// Ancestors that are not in the index (vanilla types, when their jar is not
    /// indexed) are still reported; they just have no further parents here.
    pub fn ancestors(&self, name: &str) -> Vec<String> {
        let mut seen: HashSet<&str> = HashSet::new();
        let mut out = Vec::new();
        let mut queue: VecDeque<&str> = self.direct_supers(name).collect();
        while let Some(cur) = queue.pop_front() {
            if !seen.insert(cur) {
                continue;
            }
            out.push(cur.to_string());
            queue.extend(self.direct_supers(cur));
        }
        out
    }

    /// Strict transitive subtype test: true when `target` is a proper ancestor of `class`.
    pub fn is_subclass(&self, class: &str, target: &str) -> bool {
        if class == target {
            return false;
        }
        let mut seen: HashSet<&str> = HashSet::new();
        let mut queue: VecDeque<&str> = self.direct_supers(class).collect();
        while let Some(cur) = queue.pop_front() {
            if cur == target {
                return true;
            }
            if !seen.insert(cur) {
                continue;
            }
            queue.extend(self.direct_supers(cur));
        }
        false
    }

    /// Reflexive subtype test (`class == target` counts).
    pub fn is_a(&self, class: &str, target: &str) -> bool {
        class == target || self.is_subclass(class, target)
    }

    /// Every indexed class that is a proper subtype of `target`. Linear scan with
    /// memoised walks; intended for queries, not per-class hot paths.
    pub fn subclasses_of(&self, target: &str) -> Vec<String> {
        let mut memo: HashMap<&str, bool> = HashMap::new();
        let mut out: Vec<String> = self
            .classes
            .keys()
            .filter(|k| k.as_str() != target && reaches(self, k, target, &mut memo))
            .cloned()
            .collect();
        out.sort();
        out
    }

    /// Batch classification: for each class name, a bitmask whose bit `i` is set
    /// when the class is a proper subtype of `roots[i]`. At most 32 roots.
    /// One call covers a whole jar, so callers pay one JNI crossing per jar.
    pub fn classify(&self, classes: &[&str], roots: &[&str]) -> Vec<u32> {
        let roots = &roots[..roots.len().min(32)];
        classes
            .iter()
            .map(|c| {
                let mut mask = 0u32;
                if !self.classes.contains_key(*c) {
                    return mask;
                }
                let supers: HashSet<&str> = self.walk_supers(c);
                for (i, r) in roots.iter().enumerate() {
                    if supers.contains(r) {
                        mask |= 1 << i;
                    }
                }
                mask
            })
            .collect()
    }

    fn walk_supers(&self, class: &str) -> HashSet<&str> {
        let mut seen: HashSet<&str> = HashSet::new();
        let mut queue: VecDeque<&str> = self.direct_supers(class).collect();
        while let Some(cur) = queue.pop_front() {
            if !seen.insert(cur) {
                continue;
            }
            queue.extend(self.direct_supers(cur));
        }
        seen
    }

    /// Insert a record; first insertion wins (callers order jars by priority).
    fn insert(&mut self, name: String, info: ClassInfo) {
        self.classes.entry(name).or_insert(info);
    }

    fn intern_source(&mut self, label: String) -> u32 {
        self.sources.push(label);
        (self.sources.len() - 1) as u32
    }
}

fn reaches<'a>(idx: &'a Index, class: &'a str, target: &str, memo: &mut HashMap<&'a str, bool>) -> bool {
    if let Some(v) = memo.get(class) {
        return *v;
    }
    // Iterative to avoid recursion depth issues on deep hierarchies.
    let mut seen: HashSet<&str> = HashSet::new();
    let mut queue: VecDeque<&str> = idx.direct_supers(class).collect();
    let mut found = false;
    while let Some(cur) = queue.pop_front() {
        if cur == target {
            found = true;
            break;
        }
        if !seen.insert(cur) {
            continue;
        }
        queue.extend(idx.direct_supers(cur));
    }
    memo.insert(class, found);
    found
}

/// Parsed classes from one archive (recursively including nested jars), labelled.
struct JarResult {
    entries: Vec<(String, ClassInfo)>,
    labels: Vec<String>,
}

/// Read the header prefix of a class entry: grows the read until the parser has
/// the constant pool, so only the inflated prefix of each class is decompressed.
fn read_class_header(file: &mut impl Read) -> Option<header::Header> {
    let mut buf: Vec<u8> = Vec::new();
    let mut limit = 4096usize;
    loop {
        let want = limit - buf.len();
        let got = (&mut *file).take(want as u64).read_to_end(&mut buf).ok()?;
        match parse_header(&buf) {
            Ok(h) => return Some(h),
            Err(HeaderError::Truncated) if got == want => limit *= 2,
            Err(_) => return None,
        }
    }
}

fn scan_archive_file<R: Read + std::io::Seek>(reader: R, label: &str, depth: u32, out: &mut JarResult) -> Result<(), zip::result::ZipError> {
    let mut zip = zip::ZipArchive::new(reader)?;
    let src = out.labels.len() as u32;
    out.labels.push(label.to_string());
    let mut nested: Vec<(String, Vec<u8>)> = Vec::new();
    for i in 0..zip.len() {
        let mut file = zip.by_index(i)?;
        if file.is_dir() {
            continue;
        }
        let name = file.name().to_string();
        if name.ends_with(".class") && !name.starts_with("META-INF/") && name != "module-info.class" {
            let Some(h) = read_class_header(&mut file) else { continue };
            // Multi-release copies under META-INF/versions are not indexed.
            out.entries.push((
                h.name,
                ClassInfo { super_name: h.super_name, interfaces: h.interfaces, access: h.access, source: src },
            ));
        } else if name.ends_with(".jar") && depth < MAX_NESTING {
            let mut buf = Vec::new();
            if file.read_to_end(&mut buf).is_ok() {
                nested.push((format!("{label}!/{name}"), buf));
            }
        }
    }
    drop(zip);
    for (child_label, bytes) in nested {
        // A broken nested jar must not poison its parent.
        let _ = scan_archive_file(Cursor::new(bytes), &child_label, depth + 1, out);
    }
    Ok(())
}

fn scan_jar_file(path: &Path) -> JarResult {
    let mut out = JarResult { entries: Vec::new(), labels: Vec::new() };
    let label = path.to_string_lossy().into_owned();
    if let Ok(file) = fs::File::open(path) {
        let _ = scan_archive_file(std::io::BufReader::new(file), &label, 0, &mut out);
    }
    out
}

/// Build an index over `jars` (priority order: earlier jars win on duplicate names).
/// Missing or unreadable jars are skipped.
pub fn build(jars: &[PathBuf]) -> Index {
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).min(jars.len().max(1));
    let mut results: Vec<Option<JarResult>> = (0..jars.len()).map(|_| None).collect();
    if threads <= 1 || jars.len() <= 1 {
        for (i, j) in jars.iter().enumerate() {
            results[i] = Some(scan_jar_file(j));
        }
    } else {
        let next = std::sync::atomic::AtomicUsize::new(0);
        let slots = std::sync::Mutex::new(&mut results);
        std::thread::scope(|s| {
            for _ in 0..threads {
                s.spawn(|| loop {
                    let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    if i >= jars.len() {
                        break;
                    }
                    let r = scan_jar_file(&jars[i]);
                    slots.lock().unwrap()[i] = Some(r);
                });
            }
        });
    }
    let mut index = Index::default();
    for slot in results.into_iter() {
        let Some(res) = slot else { continue };
        // Remap per-jar source ids into the index-global source table.
        let base: Vec<u32> = res.labels.iter().map(|l| index.intern_source(l.clone())).collect();
        for (name, mut info) in res.entries {
            info.source = base[info.source as usize];
            index.insert(name, info);
        }
    }
    index
}

// ---------------------------------------------------------------- cache

#[derive(Serialize, Deserialize)]
struct CacheFile {
    key: String,
    sources: Vec<String>,
    classes: Vec<(String, ClassInfo)>,
}

/// Cache key over every jar's path, size and mtime (nanoseconds). Any change to
/// an input jar yields a different key.
pub fn cache_key(jars: &[PathBuf]) -> String {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325; // FNV-1a 64
    let mut feed = |bytes: &[u8]| {
        for b in bytes {
            h ^= *b as u64;
            h = h.wrapping_mul(0x0000_0100_0000_01b3);
        }
        h ^= 0xff;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    };
    feed(format!("v{}", env!("CARGO_PKG_VERSION")).as_bytes());
    for j in jars {
        feed(j.to_string_lossy().as_bytes());
        match fs::metadata(j) {
            Ok(m) => {
                feed(&m.len().to_le_bytes());
                let mt = m
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_nanos())
                    .unwrap_or(0);
                feed(&mt.to_le_bytes());
            }
            Err(_) => feed(b"missing"),
        }
    }
    format!("{h:016x}")
}

/// Load the index for `jars` from `cache_dir` when a matching cache exists;
/// otherwise build it and write the cache (atomically, via a temp file). Cache
/// corruption or write failure falls back to a fresh build.
pub fn load_or_build(jars: &[PathBuf], cache_dir: &Path) -> Index {
    let key = cache_key(jars);
    let file = cache_dir.join(format!("classgraph-{key}.json"));
    if let Some(idx) = read_cache(&file, &key) {
        return idx;
    }
    let idx = build(jars);
    let _ = write_cache(&file, &key, &idx);
    idx
}

fn read_cache(file: &Path, key: &str) -> Option<Index> {
    let bytes = fs::read(file).ok()?;
    let cf: CacheFile = serde_json::from_slice(&bytes).ok()?;
    if cf.key != key {
        return None;
    }
    let mut classes = HashMap::with_capacity(cf.classes.len());
    for (name, info) in cf.classes {
        classes.entry(name).or_insert(info);
    }
    Some(Index { classes, sources: cf.sources })
}

fn write_cache(file: &Path, key: &str, idx: &Index) -> Result<(), IndexError> {
    if let Some(dir) = file.parent() {
        fs::create_dir_all(dir)?;
    }
    let cf = CacheFile {
        key: key.to_string(),
        sources: idx.sources.clone(),
        classes: idx.classes.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
    };
    let tmp = file.with_extension(format!("json.tmp{}", std::process::id()));
    fs::write(&tmp, serde_json::to_vec(&cf)?)?;
    fs::rename(&tmp, file)?;
    Ok(())
}
