//! `mods/` directory scanning and boot-log reporting.
//!
//! Pure static analysis (cim-modscan): no mod class is loaded here. The boot
//! log lines this module emits are CI integration-test assertions:
//!
//! ```text
//! [c-imagine] mods (N): a, b, c (load order)
//! [c-imagine] mod a: version=1.0.0 file=a-1.0.0.jar
//! ```

use cim_modscan::{build_load_order, scan_mods_dir};
use std::path::Path;

/// Scan `dir`, report to stderr, return the number of scanned jars.
pub fn scan_and_report(dir: &Path) -> usize {
    let results = scan_mods_dir(dir);
    let total = results.len();
    if total == 0 {
        eprintln!(
            "{} mods (0): (no jars in {})",
            cim_core::LOG_PREFIX,
            dir.display()
        );
        return 0;
    }

    let mut scanned = Vec::new();
    for (idx, res) in results.iter().enumerate() {
        match res {
            Ok(m) => scanned.push(m.clone()),
            Err(e) => {
                let name = jar_name_at(dir, idx);
                eprintln!(
                    "{} WARN: failed to scan {}: {e}",
                    cim_core::LOG_PREFIX,
                    name
                );
            }
        }
    }

    match build_load_order(&scanned) {
        Ok(order) => {
            eprintln!(
                "{} mods ({}): {} (load order)",
                cim_core::LOG_PREFIX,
                order.len(),
                order.join(", ")
            );
            for m in &scanned {
                for id in m.mod_ids() {
                    let version = m
                        .toml
                        .as_ref()
                        .and_then(|t| t.mods.iter().find(|e| e.mod_id == id))
                        .and_then(|e| e.version.clone())
                        .unwrap_or_else(|| "unknown".to_string());
                    eprintln!(
                        "{} mod {id}: version={version} file={}",
                        cim_core::LOG_PREFIX,
                        m.file_name
                    );
                }
            }
        }
        Err(e) => {
            eprintln!("{} ERROR: load order: {e}", cim_core::LOG_PREFIX);
        }
    }
    total
}

fn jar_name_at(dir: &Path, idx: usize) -> String {
    // scan_mods_dir sorts by path, so rebuild the same order for a friendly name.
    let mut jars: Vec<String> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.path().extension().map(|x| x == "jar").unwrap_or(false))
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .collect()
        })
        .unwrap_or_default();
    jars.sort();
    jars.get(idx)
        .cloned()
        .unwrap_or_else(|| format!("jar#{idx}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_dir_reports_zero() {
        let tmp = std::env::temp_dir().join(format!("cim-modwatch-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        assert_eq!(scan_and_report(&tmp), 0);
        std::fs::remove_dir_all(&tmp).ok();
    }

    #[test]
    fn missing_dir_reports_zero() {
        assert_eq!(scan_and_report(Path::new("/nonexistent-cim-dir")), 0);
    }
}
