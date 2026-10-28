//! `neoforge.mods.toml` parsing (plus the legacy `META-INF/mods.toml`).

use crate::ScannerError;
use cim_core::validate_mod_id;
use std::collections::HashMap;

/// Parsed mods.toml. Version fields may be templated (`${file.jarVersion}`)
/// or numbers, so they stay raw.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ModsToml {
    pub mod_loader: Option<String>,
    pub loader_version: Option<String>,
    pub license: Option<String>,
    pub mods: Vec<ModEntry>,
    /// `[[dependencies.<modid>]]` tables keyed by declaring mod id.
    pub dependencies: HashMap<String, Vec<Dependency>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ModEntry {
    pub mod_id: String,
    /// Raw version string (may be a template like `${file.jarVersion}`).
    pub version: Option<String>,
    pub display_name: Option<String>,
    pub description: Option<String>,
    /// `BOTH` / `CLIENT` / `SERVER` (case-insensitive when present).
    pub side: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct Dependency {
    pub mod_id: String,
    pub version_range: Option<String>,
    pub mandatory: bool,
    pub ordering: Option<String>,
    pub side: Option<String>,
}

impl ModsToml {
    /// Validate mod ids and return the declared mod ids.
    pub fn declared_mod_ids(&self) -> Result<Vec<String>, ScannerError> {
        let mut ids = Vec::with_capacity(self.mods.len());
        for m in &self.mods {
            validate_mod_id(&m.mod_id)
                .map_err(|e| ScannerError::Toml(format!("mods.toml: {e}")))?;
            ids.push(m.mod_id.clone());
        }
        Ok(ids)
    }

    /// Dependencies considered mandatory for server-side loading:
    /// `type = "required"` and not `side = "client"`.
    pub fn mandatory_dependencies(&self) -> Vec<(String, Dependency)> {
        let mut out = Vec::new();
        for (owner, deps) in &self.dependencies {
            for d in deps {
                let client_only = d
                    .side
                    .as_deref()
                    .map(|s| s.eq_ignore_ascii_case("client"))
                    .unwrap_or(false);
                if d.mandatory && !client_only {
                    out.push((owner.clone(), d.clone()));
                }
            }
        }
        out.sort();
        out
    }
}

// --- serde wire format -----------------------------------------------------

#[derive(serde::Deserialize, Default)]
#[serde(rename_all = "camelCase")]
struct ModsTomlWire {
    mod_loader: Option<String>,
    loader_version: Option<String>,
    license: Option<String>,
    #[serde(default)]
    mods: Vec<ModEntryWire>,
    #[serde(default)]
    dependencies: HashMap<String, Vec<DependencyWire>>,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct ModEntryWire {
    mod_id: String,
    version: Option<toml::Value>,
    display_name: Option<String>,
    description: Option<String>,
    #[serde(default)]
    #[allow(dead_code)] // informational; kept for lossless round-trip future
    side: Option<String>,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct DependencyWire {
    mod_id: String,
    #[serde(default)]
    version_range: Option<toml::Value>,
    #[serde(default)]
    #[serde(rename = "type")]
    dep_type: Option<String>,
    #[serde(default)]
    mandatory: Option<bool>,
    #[serde(default)]
    ordering: Option<String>,
    #[serde(default)]
    side: Option<String>,
}

fn value_to_string(v: &toml::Value) -> Option<String> {
    match v {
        toml::Value::String(s) => Some(s.clone()),
        toml::Value::Integer(i) => Some(i.to_string()),
        toml::Value::Float(f) => Some(f.to_string()),
        toml::Value::Boolean(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Parse from raw bytes (lossy UTF-8 tolerated the same way the JVM does).
pub fn parse_mods_toml(bytes: &[u8]) -> Result<ModsToml, ScannerError> {
    let text = String::from_utf8_lossy(bytes);
    let wire: ModsTomlWire =
        toml::from_str(&text).map_err(|e| ScannerError::Toml(format!("mods.toml parse: {e}")))?;

    let mods = wire
        .mods
        .into_iter()
        .map(|m| ModEntry {
            mod_id: m.mod_id,
            version: m.version.as_ref().and_then(value_to_string),
            display_name: m.display_name,
            description: m.description,
            side: None,
        })
        .collect();

    let mut dependencies = HashMap::new();
    for (owner, deps) in wire.dependencies {
        let list = deps
            .into_iter()
            .map(|d| Dependency {
                mod_id: d.mod_id,
                version_range: d.version_range.as_ref().and_then(value_to_string),
                mandatory: d
                    .dep_type
                    .as_deref()
                    .map(|t| t.eq_ignore_ascii_case("required"))
                    .unwrap_or(false)
                    || d.mandatory.unwrap_or(false),
                ordering: d.ordering,
                side: d.side,
            })
            .collect();
        dependencies.insert(owner, list);
    }

    Ok(ModsToml {
        mod_loader: wire.mod_loader,
        loader_version: wire.loader_version,
        license: wire.license,
        mods,
        dependencies,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"
modLoader = "javafml"
loaderVersion = "[4,)"
license = "MIT"

[[mods]]
modId = "cimtest"
version = "1.0.0"
displayName = "CIM Test Mod"
description = '''
Exercises registration + events on the mini-NeoForge layer.
'''

[[dependencies.cimtest]]
modId = "neoforge"
type = "required"
versionRange = "[21.1.0,)"
ordering = "NONE"
side = "BOTH"

[[dependencies.cimtest]]
modId = "minecraft"
type = "required"
versionRange = "[1.21.1,]"
ordering = "NONE"
side = "BOTH"

[[dependencies.cimtest]]
modId = "client_only_thing"
type = "required"
versionRange = "[0,)"
side = "CLIENT"
"#;

    #[test]
    fn parses_reference_layout() {
        let toml = parse_mods_toml(SAMPLE.as_bytes()).unwrap();
        assert_eq!(toml.mod_loader.as_deref(), Some("javafml"));
        assert_eq!(toml.loader_version.as_deref(), Some("[4,)"));
        assert_eq!(toml.declared_mod_ids().unwrap(), ["cimtest"]);
        assert_eq!(toml.mods[0].display_name.as_deref(), Some("CIM Test Mod"));
        assert!(toml.mods[0]
            .description
            .as_deref()
            .unwrap()
            .contains("registration"));
    }

    #[test]
    fn mandatory_dependencies_filter_client_side() {
        let toml = parse_mods_toml(SAMPLE.as_bytes()).unwrap();
        let deps = toml.mandatory_dependencies();
        let ids: Vec<&str> = deps.iter().map(|(_, d)| d.mod_id.as_str()).collect();
        assert_eq!(
            ids,
            ["minecraft", "neoforge"],
            "client-side dep filtered, sorted"
        );
        assert!(deps.iter().all(|(_, d)| d.mandatory));
    }

    #[test]
    fn numeric_versions_are_stringified() {
        let src = r#"
[[mods]]
modId = "a"
version = 3
"#;
        let toml = parse_mods_toml(src.as_bytes()).unwrap();
        assert_eq!(toml.mods[0].version.as_deref(), Some("3"));
    }

    #[test]
    fn invalid_mod_id_is_rejected() {
        let src = r#"
[[mods]]
modId = "Bad Id"
"#;
        let toml = parse_mods_toml(src.as_bytes()).unwrap();
        assert!(toml.declared_mod_ids().is_err());
    }
}
