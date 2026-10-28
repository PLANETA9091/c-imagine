//! c-imagine core — shared primitives for every other crate.
//!
//! Small on purpose: this crate must stay dependency-light because it is a
//! transitive dependency of the CRUSSTY module binary that lives inside the
//! server JVM process.

use std::fmt;

/// Kernel version this module targets (Paper, Mojang year-based versioning).
pub const PAPER_VERSION: &str = "26.3";

/// Minecraft version the embedded mini-NeoForge layer executes.
pub const LAYER_MC_VERSION: &str = "1.21.1";

/// NeoForge line the layer implements (21.1.x tracks MC 1.21.1).
pub const LAYER_NEOFORGE_LINE: &str = "21.1";

/// Java bytecode release the layer and mods are compiled for.
pub const LAYER_CLASS_RELEASE: u32 = 21;

/// Native bridge ABI version. Bump on any change to `cim-bridge` headers or
/// frame encoding; Paper plugin and module must agree on this value.
pub const BRIDGE_ABI_VERSION: u32 = 1;

/// First registry id handed out to modded registrations. Disjoint from any
/// vanilla space (vanilla registry ids stay far below this on 1.21.1), so a
/// modded id can never collide with a vanilla one by construction.
pub const MODDED_ID_BASE: u32 = 1_000_000_000;

/// Log prefix every c-imagine component uses. CI integration tests grep for
/// it; keep it stable.
pub const LOG_PREFIX: &str = "[c-imagine]";

/// Errors shared across c-imagine crates.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum CimError {
    #[error("invalid resource location: {0}")]
    InvalidResourceLocation(String),
    #[error("invalid mod id: {0}")]
    InvalidModId(String),
    #[error("not found: {0}")]
    NotFound(String),
    #[error("invalid data: {0}")]
    InvalidData(String),
}

/// Namespaced identifier, `namespace:path` (Minecraft resource location).
///
/// Validation follows vanilla rules: lowercase letters, digits, underscore,
/// dot, dash; the namespace does not allow `/`, the path does.
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ResourceLocation {
    namespace: String,
    path: String,
}

impl ResourceLocation {
    pub const DEFAULT_NAMESPACE: &'static str = "minecraft";

    /// Parse and validate `namespace:path`. A value without `:` defaults to
    /// the `minecraft` namespace, matching vanilla behavior.
    pub fn parse(s: &str) -> Result<Self, CimError> {
        let (namespace, path) = match s.split_once(':') {
            Some((ns, p)) => (ns, p),
            None => (Self::DEFAULT_NAMESPACE, s),
        };
        Self::from_parts(namespace, path)
    }

    /// Build from already-separated parts.
    pub fn from_parts(namespace: &str, path: &str) -> Result<Self, CimError> {
        if namespace.is_empty() || !valid_namespace(namespace) {
            return Err(CimError::InvalidResourceLocation(format!(
                "bad namespace {namespace:?} in {namespace:?}:{path:?}"
            )));
        }
        if path.is_empty() || !valid_path(path) {
            return Err(CimError::InvalidResourceLocation(format!(
                "bad path {path:?} in {namespace:?}:{path:?}"
            )));
        }
        Ok(Self {
            namespace: namespace.to_string(),
            path: path.to_string(),
        })
    }

    pub fn namespace(&self) -> &str {
        &self.namespace
    }

    pub fn path(&self) -> &str {
        &self.path
    }

    /// Full string form, `namespace:path`.
    pub fn to_registry_key(&self) -> String {
        format!("{}:{}", self.namespace, self.path)
    }
}

impl fmt::Display for ResourceLocation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}:{}", self.namespace, self.path)
    }
}

impl std::str::FromStr for ResourceLocation {
    type Err = CimError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Self::parse(s)
    }
}

fn valid_namespace(s: &str) -> bool {
    !s.is_empty()
        && s.chars().all(|c| {
            c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.' || c == '-'
        })
}

fn valid_path(s: &str) -> bool {
    s.chars().all(|c| {
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.' || c == '-' || c == '/'
    })
}

/// Validate a NeoForge mod id. Must be `[a-z][a-z0-9_.-]{1,63}`.
pub fn validate_mod_id(id: &str) -> Result<(), CimError> {
    let ok = (2..=64).contains(&id.len())
        && id.starts_with(|c: char| c.is_ascii_lowercase())
        && id.chars().all(|c| {
            c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.' || c == '-'
        });
    if ok {
        Ok(())
    } else {
        Err(CimError::InvalidModId(id.to_string()))
    }
}

/// Standard marker for log lines; CI greps `{LOG_PREFIX}`.
pub fn log_marker() -> &'static str {
    LOG_PREFIX
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resource_location_parses() {
        let rl = ResourceLocation::parse("cimtest:test_block").unwrap();
        assert_eq!(rl.namespace(), "cimtest");
        assert_eq!(rl.path(), "test_block");
        assert_eq!(rl.to_registry_key(), "cimtest:test_block");
        assert_eq!(rl.to_string(), "cimtest:test_block");
    }

    #[test]
    fn resource_location_defaults_to_minecraft() {
        let rl = ResourceLocation::parse("stone").unwrap();
        assert_eq!(rl.namespace(), "minecraft");
        assert_eq!(rl.path(), "stone");
    }

    #[test]
    fn resource_location_rejects_bad_input() {
        assert!(ResourceLocation::parse("").is_err());
        assert!(ResourceLocation::parse(":path").is_err());
        assert!(ResourceLocation::parse("ns:").is_err());
        assert!(ResourceLocation::parse("Bad_ns:path").is_err());
        assert!(ResourceLocation::parse("ns:Bad Path").is_err());
        assert!(ResourceLocation::parse("ns/sub:path").is_err());
        assert!(ResourceLocation::parse("ns:a/b/c").is_ok());
    }

    #[test]
    fn mod_id_validation() {
        assert!(validate_mod_id("cimtest").is_ok());
        assert!(validate_mod_id("jei").is_ok());
        assert!(validate_mod_id("sophisticatedbackpacks").is_ok());
        assert!(validate_mod_id("a").is_err(), "too short");
        assert!(validate_mod_id("1abc").is_err(), "must start with a letter");
        assert!(validate_mod_id("HasUpper").is_err());
        assert!(validate_mod_id("has space").is_err());
        assert!(validate_mod_id(&"x".repeat(65)).is_err(), "too long");
    }

    #[test]
    fn constants_are_consistent() {
        // Compile-time guarantee; runtime check kept as a doc'd invariant.
        const _: () = assert!(MODDED_ID_BASE > 900_000_000);
        assert_eq!(MODDED_ID_BASE, 1_000_000_000);
        assert_eq!(log_marker(), "[c-imagine]");
        assert_eq!(LAYER_CLASS_RELEASE, 21);
    }
}
