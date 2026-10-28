//! `mods/` directory scanning, manifest building and boot-log reporting.
//!
//! Pure static analysis (cim-modscan): no mod class is loaded here. The boot
//! log lines this module emits are CI integration-test assertions:
//!
//! ```text
//! [c-imagine] mods (N): a, b, c (load order)
//! [c-imagine] mod a: version=1.0.0 file=a-1.0.0.jar
//! [c-imagine] manifest: N mod(s), M class(es), K annotation hit(s)
//! ```
//!
//! The manifest JSON is the single channel into the Java shadow space:
//! `Bootstrap.loadMods(json)` receives it via JNI after the layer boot.

use cim_modscan::build_layer_manifest;
use std::path::Path;

/// Scan `dir`, report to stderr, and build the layer manifest.
///
/// Nested jars (jar-in-jar under `META-INF/jarjar/`) are extracted to a temp
/// dir and scanned as standalone mods — the shadow loader can only read
/// top-level jar files, and FML treats jarjar entries as mods of their own
/// anyway. Duplicate mod ids across jars are resolved first-wins in scan
/// order (FML picks the highest version; v1 here logs loudly instead).
///
/// Returns `(manifest_json, scanned_jar_count)`; the manifest is `None` when
/// nothing usable was found (empty dir / missing dir / total failure) — the
/// Java side then skips mod loading entirely (M0 behavior).
pub fn scan_and_build_manifest(dir: &Path) -> (Option<String>, usize) {
    let (mut deduped, stats) = cim_modscan::scan_dir_full(dir);
    let layer_at = cim_modscan::loader::prepend_layer_neoforge(&mut deduped);
    if stats.jars == 0 {
        eprintln!(
            "{} mods (0): (no jars in {})",
            cim_core::LOG_PREFIX,
            dir.display()
        );
        return (None, 0);
    }
    if stats.failed > 0 || stats.duplicates_skipped > 0 || stats.nested_extracted > 0 {
        eprintln!(
            "{} loader stats: jars={} failed={} nestedExtracted={} duplicatesSkipped={}",
            cim_core::LOG_PREFIX,
            stats.jars,
            stats.failed,
            stats.nested_extracted,
            stats.duplicates_skipped
        );
    }

    match build_layer_manifest(&deduped) {
        Ok(mut manifest) => {
            manifest.at_entries = stats.at_lines.clone();
            manifest.at_entries.extend(layer_at);
            // jarjar libraries (no mod ids): shadow class index only.
            manifest.classpath = stats.libraries.clone();
            if !manifest.at_entries.is_empty() {
                eprintln!(
                    "{} access transformers: {} directive(s)",
                    cim_core::LOG_PREFIX,
                    manifest.at_entries.len()
                );
            }
            eprintln!(
                "{} mods ({}): {} (load order)",
                cim_core::LOG_PREFIX,
                manifest.load_order.len(),
                manifest.load_order.join(", ")
            );
            for m in &manifest.mods {
                eprintln!(
                    "{} mod {}: version={} file={} entrypoints={} classes={} annotations={}{}",
                    cim_core::LOG_PREFIX,
                    m.id,
                    m.version,
                    m.file,
                    m.entrypoints.len(),
                    m.classes.len(),
                    m.annotations.len(),
                    if m.mixin_configs.is_empty() {
                        String::new()
                    } else {
                        format!(" mixinConfigs={:?}", m.mixin_configs)
                    }
                );
            }
            let json = serde_json::to_string(&manifest).ok();
            if json.is_none() {
                eprintln!(
                    "{} ERROR: manifest serialization failed",
                    cim_core::LOG_PREFIX
                );
            }
            (json, stats.jars)
        }
        Err(e) => {
            eprintln!("{} ERROR: manifest build: {}", cim_core::LOG_PREFIX, e);
            (None, stats.jars)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_dir_reports_zero() {
        let tmp = std::env::temp_dir().join(format!("cim-modwatch-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        let (manifest, count) = scan_and_build_manifest(&tmp);
        assert_eq!(count, 0);
        assert!(manifest.is_none());
        std::fs::remove_dir_all(&tmp).ok();
    }

    #[test]
    fn missing_dir_reports_zero() {
        let (manifest, count) = scan_and_build_manifest(Path::new("/nonexistent-cim-dir"));
        assert_eq!(count, 0);
        assert!(manifest.is_none());
    }
}
