//! Jar scanning: mods.toml + bytecode `@Mod` discovery + jar-in-jar + mixins.

use crate::classfile::parse_class;
use crate::modstoml::{parse_mods_toml, ModsToml};
use crate::ScannerError;
use std::io::{Cursor, Read, Seek};
use std::path::{Path, PathBuf};

/// Mod discovery result for one jar (or nested jar).
#[derive(Debug, Clone, Default)]
pub struct ScannedMod {
    /// Filesystem path; `None` for nested (jar-in-jar) scans.
    pub path: Option<PathBuf>,
    pub file_name: String,
    pub size: u64,
    /// Parsed `neoforge.mods.toml` / legacy `mods.toml`, when present.
    pub toml: Option<ModsToml>,
    /// Which entry the toml was read from.
    pub toml_path: Option<String>,
    /// Mod ids discovered from `@Mod(...)` bytecode annotations.
    pub code_mod_ids: Vec<String>,
    /// Classes annotated with `@Mod` (internal names).
    pub entrypoint_classes: Vec<String>,
    /// `*.mixins.json` entry names found in the jar.
    pub mixin_configs: Vec<String>,
    /// Nested jars discovered under `META-INF/jarjar/`.
    pub nested: Vec<ScannedMod>,
}

impl ScannedMod {
    /// Union of declared + code-discovered mod ids, sorted, deduped.
    pub fn mod_ids(&self) -> Vec<String> {
        let mut ids: Vec<String> = Vec::new();
        if let Some(toml) = &self.toml {
            ids.extend(toml.mods.iter().map(|m| m.mod_id.clone()));
        }
        ids.extend(self.code_mod_ids.iter().cloned());
        ids.sort();
        ids.dedup();
        ids
    }

    /// Recursively collect every mod id (self + nested).
    pub fn all_mod_ids(&self) -> Vec<String> {
        let mut ids = self.mod_ids();
        for n in &self.nested {
            ids.extend(n.all_mod_ids());
        }
        ids.sort();
        ids.dedup();
        ids
    }
}

/// `@Mod` annotation descriptors (NeoForge + legacy Forge).
pub const MOD_ANNOTATION_DESCRIPTORS: [&str; 2] = [
    "Lnet/neoforged/fml/common/Mod;",
    "Lnet/minecraftforge/fml/common/Mod;",
];

const NEOFORGE_MODS_TOML: &str = "META-INF/neoforge.mods.toml";
const LEGACY_MODS_TOML: &str = "META-INF/mods.toml";
const JARJAR_PREFIX: &str = "META-INF/jarjar/";
const MAX_NESTED_DEPTH: usize = 3;
const MAX_CLASS_BYTES: usize = 256 * 1024;
const MAX_CLASSES_SCANNED: usize = 20_000;

/// Scan a jar file on disk.
pub fn scan_file(path: &Path) -> Result<ScannedMod, ScannerError> {
    let file = std::fs::File::open(path)?;
    let size = file.metadata().map(|m| m.len()).unwrap_or(0);
    let mut inner = scan_jar(file)?;
    inner.path = Some(path.to_path_buf());
    inner.file_name = path
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    inner.size = size;
    Ok(inner)
}

/// Scan a jar from any reader (also used for nested jars).
pub fn scan_jar<R: Read + Seek>(reader: R) -> Result<ScannedMod, ScannerError> {
    scan_jar_depth(reader, 0)
}

fn scan_jar_depth<R: Read + Seek>(reader: R, depth: usize) -> Result<ScannedMod, ScannerError> {
    if depth > MAX_NESTED_DEPTH {
        return Err(ScannerError::TooDeep(MAX_NESTED_DEPTH));
    }
    let mut archive =
        zip::ZipArchive::new(reader).map_err(|e| ScannerError::Zip(format!("open: {e}")))?;

    let mut out = ScannedMod {
        file_name: String::new(),
        ..ScannedMod::default()
    };

    // 1) mods.toml
    for (toml_path, key) in [
        (NEOFORGE_MODS_TOML, NEOFORGE_MODS_TOML),
        (LEGACY_MODS_TOML, LEGACY_MODS_TOML),
    ] {
        if let Ok(mut entry) = archive.by_name(toml_path) {
            let mut bytes = Vec::with_capacity(entry.size().min(1 << 20) as usize);
            entry.read_to_end(&mut bytes)?;
            out.toml = Some(parse_mods_toml(&bytes)?);
            out.toml_path = Some(key.to_string());
            break;
        }
    }

    // 2) classes, mixins, nested jars.
    let mut classes_scanned: usize = 0;
    let names: Vec<String> = archive.file_names().map(str::to_string).collect();
    for name in &names {
        if name.ends_with(".mixins.json") {
            out.mixin_configs.push(name.clone());
            continue;
        }
        if name.starts_with(JARJAR_PREFIX) && name.ends_with(".jar") {
            // Nested jar-in-jar (NeoForge ships these under META-INF/jarjar/).
            let mut bytes = Vec::new();
            {
                let mut entry = archive.by_name(name)?;
                entry.read_to_end(&mut bytes)?;
            }
            let nested = scan_jar_depth(Cursor::new(bytes), depth + 1)?;
            out.nested.push(ScannedMod {
                file_name: name.clone(),
                ..nested
            });
            continue;
        }
        if !name.ends_with(".class") || name.ends_with("module-info.class") {
            continue;
        }
        if classes_scanned >= MAX_CLASSES_SCANNED {
            continue;
        }
        let mut entry = match archive.by_name(name) {
            Ok(e) => e,
            Err(_) => continue,
        };
        if entry.size() as usize > MAX_CLASS_BYTES {
            continue;
        }
        let mut bytes = Vec::with_capacity(entry.size() as usize);
        if entry.read_to_end(&mut bytes).is_err() {
            continue;
        }
        classes_scanned += 1;
        match parse_class(&bytes) {
            Ok(info) => {
                for ann in &info.annotations {
                    if MOD_ANNOTATION_DESCRIPTORS.contains(&ann.type_descriptor.as_str()) {
                        for id in ann.modid_values() {
                            if !out.code_mod_ids.contains(&id) {
                                out.code_mod_ids.push(id);
                            }
                        }
                        out.entrypoint_classes.push(info.this_class.clone());
                    }
                }
            }
            // A malformed class must not fail the whole jar scan.
            Err(_) => continue,
        }
    }

    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build an in-memory jar from (name, content) pairs.
    pub(crate) fn build_jar(entries: &[(&str, &[u8])]) -> Vec<u8> {
        use std::io::Write as _;
        let mut buf = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buf);
            let opts: zip::write::SimpleFileOptions = Default::default();
            for (name, content) in entries {
                zip.start_file(*name, opts).unwrap();
                zip.write_all(content).unwrap();
            }
            zip.finish().unwrap();
        }
        buf.into_inner()
    }

    #[test]
    fn scans_toml_only_jar() {
        let toml = br#"
modLoader = "javafml"
loaderVersion = "[4,)"
[[mods]]
modId = "puretoml"
version = "1.0.0"
"#;
        let jar = build_jar(&[("META-INF/neoforge.mods.toml", toml)]);
        let scanned = scan_jar(Cursor::new(jar)).unwrap();
        assert_eq!(scanned.toml_path.as_deref(), Some(NEOFORGE_MODS_TOML));
        assert_eq!(scanned.mod_ids(), ["puretoml"]);
        assert!(scanned.code_mod_ids.is_empty());
    }

    #[test]
    fn detects_mod_annotation_from_bytecode() {
        let class = crate::classfile::tests::fixture_mod_class("cimcoded");
        let jar = build_jar(&[
            (
                "META-INF/neoforge.mods.toml",
                b"[[mods]]\nmodId = \"fromtoml\"\n".as_slice(),
            ),
            ("dev/cim/Fake.class", &class),
        ]);
        let scanned = scan_jar(Cursor::new(jar)).unwrap();
        let ids = scanned.mod_ids();
        assert_eq!(ids, ["cimcoded", "fromtoml"]);
        assert_eq!(scanned.code_mod_ids, ["cimcoded"]);
        assert_eq!(scanned.entrypoint_classes, ["dev/cim/Fixture"]);
    }

    #[test]
    fn collects_mixin_configs() {
        let jar = build_jar(&[
            ("dev/cim/A.class", &[0u8; 0]),
            ("mixins/cimtest.mixins.json", b"{}".as_slice()),
        ]);
        let scanned = scan_jar(Cursor::new(jar)).unwrap();
        assert_eq!(scanned.mixin_configs, ["mixins/cimtest.mixins.json"]);
    }

    #[test]
    fn scans_jar_in_jar() {
        let nested_toml = br#"[[mods]]
modId = "nestedmod"
version = "0.1"
"#;
        let nested = build_jar(&[("META-INF/neoforge.mods.toml", nested_toml)]);
        let outer = build_jar(&[
            (
                "META-INF/neoforge.mods.toml",
                b"[[mods]]\nmodId = \"outer\"\n".as_slice(),
            ),
            ("META-INF/jarjar/data/nested-0.1.jar", &nested),
        ]);
        let scanned = scan_jar(Cursor::new(outer)).unwrap();
        assert_eq!(scanned.all_mod_ids(), ["nestedmod", "outer"]);
        assert_eq!(scanned.nested.len(), 1);
        assert_eq!(
            scanned.nested[0].file_name,
            "META-INF/jarjar/data/nested-0.1.jar"
        );
    }

    #[test]
    fn malformed_class_does_not_break_scan() {
        let toml = br#"[[mods]]
modId = "okmod"
"#;
        let jar = build_jar(&[
            ("META-INF/neoforge.mods.toml", toml),
            ("broken.class", &[0xC, 0xAF, 0xE, 0xB, 0xAB, 0x1, 0x2, 0x3]),
        ]);
        let scanned = scan_jar(Cursor::new(jar)).unwrap();
        assert_eq!(scanned.mod_ids(), ["okmod"]);
    }

    #[test]
    fn element_value_array_is_read() {
        let class = crate::classfile::tests::fixture_mod_class_array(&["moda", "modb"]);
        let jar = build_jar(&[("A.class", &class)]);
        let scanned = scan_jar(Cursor::new(jar)).unwrap();
        assert_eq!(scanned.code_mod_ids, ["moda", "modb"]);
    }
}
