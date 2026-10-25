//! c-imagine mod scanner.
//!
//! Discovers NeoForge mods in `mods/` **without loading or executing any mod
//! class** (the invariant inherited from F2V's discovery analysis):
//!
//! - [`modstoml`]: `META-INF/neoforge.mods.toml` (and legacy `META-INF/mods.toml`).
//! - [`classfile`]: a minimal JVM class-file reader extracting class-level
//!   runtime-visible annotations — enough to find `@Mod("modid")` entrypoints
//!   from raw bytes, no JVM required.
//! - [`scanner`]: jar scanning incl. jar-in-jar nesting and mixin configs.
//! - [`deps`]: mandatory-dependency graph → deterministic load order with
//!   cycle/missing detection.
//!
//! The scanner is the first stage of the mini-NeoForge loader (M2 wires the
//! results into the shadow classloader) and feeds F2V v2 discovery.

pub mod classfile;
pub mod deps;
pub mod modstoml;
pub mod scanner;

pub use deps::{build_load_order, DepsError};
pub use scanner::{scan_file, scan_jar, ScannedMod};

use std::path::Path;

/// Scan every `.jar` under `dir` (non-recursive; NeoForge does the same).
/// Unreadable files are reported as errors but do not abort the batch.
pub fn scan_mods_dir(dir: &Path) -> Vec<Result<ScannedMod, ScannerError>> {
    let mut out = Vec::new();
    let entries = match std::fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return out,
    };
    let mut jars: Vec<_> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().map(|e| e == "jar").unwrap_or(false))
        .collect();
    jars.sort();
    for jar in jars {
        out.push(scan_file(&jar));
    }
    out
}

/// Scanner errors.
#[derive(Debug, thiserror::Error)]
pub enum ScannerError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("zip error: {0}")]
    Zip(String),
    #[error("toml error: {0}")]
    Toml(String),
    #[error("class file error: {0}")]
    ClassFile(String),
    #[error("nesting limit exceeded (jar-in-jar deeper than {0})")]
    TooDeep(usize),
}

impl From<zip::result::ZipError> for ScannerError {
    fn from(e: zip::result::ZipError) -> Self {
        Self::Zip(e.to_string())
    }
}
