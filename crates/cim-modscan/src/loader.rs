//! Full mods/ loading pipeline: scan → jarjar extraction → dedupe.
//!
//! Shared by the CRUSSTY module's loader thread (`imagine::modwatch`) and
//! the `cim-manifest` CLI so both produce byte-identical manifests.

use crate::scan_file;
use crate::{scan_mods_dir, ScannedMod};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

/// Scan `dir` and prepare the full mod set for manifest building:
///
/// 1. every top-level jar is scanned;
/// 2. nested jars (`META-INF/jarjar/`) are extracted to a temp dir and
///    scanned as standalone mods (the shadow loader reads top-level jars
///    only; FML treats jarjar entries as mods of their own);
/// 3. duplicate mod ids resolve first-wins in scan order (FML picks the
///    highest version; v1 logs the skipped jar loudly instead).
///
/// Returns `(mods, stats)`; inert jars (no mod ids) are kept — their classes
/// still participate in the relocation table.
/// Extract `META-INF/accesstransformer.cfg` lines from one jar.
fn read_at_entries(jar_path: &Path) -> Vec<String> {
    let Ok(file) = std::fs::File::open(jar_path) else {
        return Vec::new();
    };
    let Ok(mut zip) = zip::ZipArchive::new(file) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for name in ["META-INF/accesstransformer.cfg", "META-INF/accesstransformer.cfg.disabled"] {
        if let Ok(mut entry) = zip.by_name(name) {
            use std::io::Read;
            let mut text = String::new();
            if entry.read_to_string(&mut text).is_ok() {
                out.extend(text.lines().map(str::to_string));
            }
        }
    }
    out
}

pub fn scan_dir_full(dir: &Path) -> (Vec<ScannedMod>, ScanStats) {
    let results = scan_mods_dir(dir);
    let mut stats = ScanStats {
        jars: results.len(),
        ..ScanStats::default()
    };

    let mut scanned = Vec::new();
    for res in results {
        match res {
            Ok(m) => {
                if let Some(p) = &m.path {
                    stats.at_lines.extend(read_at_entries(p));
                }
                scanned.push(m);
            }
            Err(e) => {
                stats.failed += 1;
                eprintln!("WARN: failed to scan jar in {}: {e}", dir.display());
            }
        }
    }

    // Extract + scan nested jars (flat: one level; deeper nesting is skipped
    // loudly).
    let extract_dir = std::env::temp_dir().join("c-imagine-jarjar");
    let _ = std::fs::remove_dir_all(&extract_dir);
    let mut extracted: Vec<ScannedMod> = Vec::new();
    for m in &scanned {
        for (n, nested) in m.nested.iter().enumerate() {
            if !nested.nested.is_empty() {
                eprintln!(
                    "WARN: deeply nested jar skipped: {} in {}",
                    nested.file_name, m.file_name
                );
                continue;
            }
            if nested.mod_ids().is_empty() {
                continue;
            }
            match extract_nested(&extract_dir, m, n, nested) {
                Ok(Some(path)) => match scan_file(&path) {
                    Ok(sm) => {
                        stats.nested_extracted += 1;
                        extracted.push(sm);
                    }
                    Err(e) => eprintln!(
                        "WARN: nested scan failed ({}): {e}",
                        path.display()
                    ),
                },
                Ok(None) => {}
                Err(e) => eprintln!(
                    "WARN: nested extract failed ({}): {e}",
                    nested.file_name
                ),
            }
        }
    }
    scanned.extend(extracted);

    // Deduplicate mod ids (first wins in scan order).
    let mut seen_ids: HashSet<String> = HashSet::new();
    let mut deduped: Vec<ScannedMod> = Vec::new();
    for m in scanned {
        let ids = m.mod_ids();
        if ids.is_empty() {
            deduped.push(m);
            continue;
        }
        if ids.iter().any(|id| seen_ids.contains(id)) {
            eprintln!(
                "WARN: duplicate mod id(s) {ids:?} in {} — skipped (first jar wins)",
                m.file_name
            );
            stats.duplicates_skipped += 1;
            continue;
        }
        for id in &ids {
            seen_ids.insert(id.clone());
        }
        deduped.push(m);
    }

    stats.mods = deduped.len();
    (deduped, stats)
}

#[derive(Debug, Default, Clone)]
pub struct ScanStats {
    /// Top-level jars found in the directory.
    pub jars: usize,
    /// Jars that failed to scan.
    pub failed: usize,
    /// Nested (jarjar) jars extracted and scanned.
    pub nested_extracted: usize,
    /// Jars skipped as duplicate mod ids.
    pub duplicates_skipped: usize,
    /// Final mod count (post dedupe, including inert library jars).
    pub mods: usize,
    /// Access transformer lines collected from mod jars.
    pub at_lines: Vec<String>,
}

/// Extract one nested jar to `<extract_dir>/<outer-stem>_<n>_<basename>`.
fn extract_nested(
    extract_dir: &Path,
    outer: &ScannedMod,
    n: usize,
    nested: &ScannedMod,
) -> Result<Option<PathBuf>, String> {
    let Some(outer_path) = &outer.path else {
        return Ok(None);
    };
    let file = std::fs::File::open(outer_path).map_err(|e| e.to_string())?;
    let mut zip = zip::ZipArchive::new(file).map_err(|e| e.to_string())?;
    let mut bytes = Vec::new();
    {
        use std::io::Read;
        let mut entry = zip.by_name(&nested.file_name).map_err(|e| e.to_string())?;
        entry.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    }
    let stem = outer
        .path
        .as_ref()
        .and_then(|p| p.file_stem())
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| "outer".to_string());
    let base = nested
        .file_name
        .rsplit('/')
        .next()
        .unwrap_or("nested.jar")
        .to_string();
    let out_path = extract_dir.join(format!("{stem}_{n}_{base}"));
    std::fs::create_dir_all(extract_dir).map_err(|e| e.to_string())?;
    std::fs::write(&out_path, bytes).map_err(|e| e.to_string())?;
    Ok(Some(out_path))
}

/// Discover the NeoForge universal jar inside the layer's libraries dir and
/// prepend it as a layer-flavoured mod entry: the universal jar IS a mod
/// (`modId = "neoforge"`, own mods.toml) whose @Mod class registers the
/// NeoForge DeferredRegisters — without it, `NewRegistryEvent` leaves the
/// neoforge:* registries pending and registration aborts.
///
/// Its classes are already part of the shadow layer (M1 full relocation), so
/// the entry is marked `layer = true` with an empty jar path (the loader must
/// not re-add it) and the Java side resolves relocated names for it.
///
/// The layer dir comes from the `CIM_LAYER_DIR` env contract (set by the
/// module's loader thread); missing/absent → no neoforge entry (mod execution
/// then fails loudly at registration, which is the correct signal).
pub fn prepend_layer_neoforge(mods: &mut Vec<ScannedMod>) -> Vec<String> {
    let Some(layer_dir) = std::env::var("CIM_LAYER_DIR").ok().filter(|v| !v.is_empty()) else {
        return Vec::new();
    };
    let root = std::path::Path::new(&layer_dir);
    let base = root.join("net").join("neoforged").join("neoforge");
    let Ok(entries) = std::fs::read_dir(&base) else {
        return Vec::new();
    };
    let mut candidates: Vec<PathBuf> = entries.flatten().map(|e| e.path()).filter(|p| p.is_dir()).collect();
    candidates.sort();
    // pick the highest version dir (last) — matching the installer layout
    for vdir in candidates.iter().rev() {
        let mut jars: Vec<PathBuf> = std::fs::read_dir(vdir)
            .map(|es| es.flatten().map(|e| e.path()).collect())
            .unwrap_or_default();
        jars.sort();
        if let Some(universal) = jars.iter().find(|p| {
            p.file_name()
                .map(|n| {
                    let n = n.to_string_lossy();
                    n.starts_with("neoforge-") && n.ends_with("-universal.jar")
                })
                .unwrap_or(false)
        }) {
            match scan_file(universal) {
                Ok(mut sm) => {
                    sm.layer = true;
                    sm.jar_override = String::new();
                    sm.file_name = universal
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    let at = read_at_entries(universal);
                    mods.insert(0, sm);
                    eprintln!("neoforge layer mod: {} ({} AT lines)", universal.display(), at.len());
                    return at;
                }
                Err(e) => eprintln!("WARN: neoforge universal scan failed: {e}"),
            }
            return Vec::new();
        }
    }
    Vec::new()
}
