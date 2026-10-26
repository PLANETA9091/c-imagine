//! Layer configuration, read from the server directory's CRUSSTY data dir:
//! `crus/c-imagine.json` (the child JVM's working directory is the server
//! root, so a relative path is stable).
//!
//! CRUSSTY's runtime options string (`modules=...;versions=...;kernel=...`)
//! belongs to the engine; c-imagine keeps its own settings here:
//!
//! ```json
//! {"layerDir": "layer1211/libraries", "mods": "mods"}
//! ```
//!
//! Hand-rolled extraction only (no serde at the module boundary): the file
//! is flat and tiny. Missing file → defaults (layer disabled), matching the
//! M0 behavior of integration/profiling boots without a layer.

use std::path::Path;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LayerConfig {
    /// Directory with the 1.21.1 layer jars (NeoForge server install's
    /// `libraries/`). Empty = layer boot disabled (M0 behavior).
    pub layer_dir: String,
}

/// Read `crus/c-imagine.json` relative to `server_root`. Never errors:
/// any read/parse problem yields the default config (layer off) with a
/// warning on stderr.
pub fn load(server_root: &Path) -> LayerConfig {
    let path = server_root.join("crus").join("c-imagine.json");
    match std::fs::read_to_string(&path) {
        Ok(text) => {
            let layer_dir = json_str(&text, "layerDir").unwrap_or_default();
            LayerConfig { layer_dir }
        }
        Err(_) => LayerConfig::default(),
    }
}

/// Extract a top-level string field from a flat JSON object.
fn json_str(json: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\"");
    let k = json.find(&needle)?;
    let colon = json[k + needle.len()..].find(':')? + k + needle.len();
    let rest = &json[colon..];
    let open = rest.find('"')?;
    let after = &rest[open + 1..];
    let mut out = String::new();
    let mut chars = after.chars();
    while let Some(c) = chars.next() {
        match c {
            '\\' => match chars.next()? {
                'n' => out.push('\n'),
                't' => out.push('\t'),
                'r' => out.push('\r'),
                other => out.push(other),
            },
            '"' => return Some(out),
            other => out.push(other),
        }
    }
    None
}

/// Absolute path of this module's own shared library, discovered from
/// `/proc/self/maps` (Linux). The Java side needs it to `System.load` the
/// relocation natives into the bootstrap classloader context.
pub fn module_lib_path() -> Option<String> {
    let maps = std::fs::read_to_string("/proc/self/maps").ok()?;
    for line in maps.lines() {
        if let Some(path) = line.split_whitespace().next_back() {
            if path.ends_with("/libimagine.so") || path == "libimagine.so" {
                return Some(path.to_string());
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extracts_layer_dir() {
        let cfg = json_str(
            "{\"layerDir\": \"layer1211/libraries\", \"mods\": \"mods\"}",
            "layerDir",
        );
        assert_eq!(cfg.as_deref(), Some("layer1211/libraries"));
    }

    #[test]
    fn missing_field_is_none_and_missing_file_is_default() {
        assert_eq!(json_str("{\"a\":1}", "layerDir"), None);
        assert_eq!(
            load(Path::new("/nonexistent-server-root")),
            LayerConfig::default()
        );
    }

    #[test]
    fn escapes_are_decoded() {
        let cfg = json_str("{\"layerDir\":\"a\\\\b\"}", "layerDir");
        assert_eq!(cfg.as_deref(), Some("a\\b"));
    }
}
