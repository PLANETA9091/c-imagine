//! Layer manifest: the serializable hand-off between the native scanner and
//! the Java shadow-space loader.
//!
//! The manifest is produced by [`build_layer_manifest`] after jar scanning and
//! dependency resolution. It carries, per mod (in load order):
//!
//! - identity (id, version, jar path),
//! - `@Mod` entrypoint classes (original internal names),
//! - every class entry (drives the exact/prefix relocation table),
//! - top-level package ownership (drives the prefix relocation table),
//! - interest-set annotation hits (`@Mod`, `@EventBusSubscriber`,
//!   `@SubscribeEvent`) — the raw material for FML's `ModFileScanData`.
//!
//! [`build_relocation_table`] folds the manifest into the final
//! [`RelocationTable`]: the layer prefixes first, then one prefix rule per
//! owned top-level package, then exact per-class entries wherever a package
//! is owned by more than one jar (longest-prefix/exact resolution in
//! `cim-reloc` does the rest).

use crate::classfile::ElementValue;
use crate::scanner::ScannedMod;
use crate::{build_load_order, ScannerError};
use cim_reloc::RelocationTable;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Shadow namespace prefix for mod classes:
/// `cim/shadow/maven<modid>/<original internal name>`.
pub const MOD_SHADOW_PREFIX: &str = "cim/shadow/maven";

/// The full scan result handed to the Java side (one JSON string).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LayerManifest {
    /// Mod ids in dependency-resolved load order (prerequisite-first).
    pub load_order: Vec<String>,
    /// One entry per scanned jar (order: jar scan order, not load order).
    pub mods: Vec<ModEntry>,
    /// Access-transformer directives (all jars + the neoforge layer jar),
    /// applied by the relocation stage.
    #[serde(default)]
    pub at_entries: Vec<String>,
}

/// One mod jar in the manifest.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ModEntry {
    /// Primary mod id (first toml id, else first code id).
    pub id: String,
    /// Jar file name (display).
    pub file: String,
    /// Absolute jar path ("" for nested jars — M2.0 defers nested loading).
    pub jar: String,
    /// Declared version, or "unknown".
    pub version: String,
    /// `@Mod` entrypoint classes, original internal names.
    pub entrypoints: Vec<String>,
    /// Top-level package segments present in the jar (class entries).
    pub packages: Vec<String>,
    /// Every class entry, original internal names (relocation table input).
    pub classes: Vec<String>,
    /// Interest-set annotation hits.
    pub annotations: Vec<AnnEntry>,
    /// Mixin config entry names (M8 surface, reported for visibility).
    pub mixin_configs: Vec<String>,
    /// Full-depth package set (original names, derived from class entries) —
    /// the JPMS module descriptor's package list for this mod. Computed at
    /// scan time because the module layer is defined BEFORE the layer boot
    /// (package-claim ordering) and must not depend on Java-side rewriting.
    #[serde(default, rename = "modulePackages")]
    pub module_packages: Vec<String>,
    /// `META-INF/services/` declarations: service → provider class names.
    #[serde(default)]
    pub services: Vec<ServiceEntry>,
    /// True when the jar is already part of the layer jar set (the NeoForge
    /// universal jar): its classes were fully shadow-relocated at M1, so the
    /// loader must NOT re-add it as a mod jar and entrypoints/module packages
    /// are the RELOCATED names.
    #[serde(default)]
    pub layer: bool,
}

/// One `META-INF/services/<service>` declaration.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ServiceEntry {
    /// Service interface binary name (file name).
    pub service: String,
    /// Provider class binary names (file lines).
    pub providers: Vec<String>,
}

/// One annotation hit in scan-data form.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AnnEntry {
    /// Declaring class, original internal name.
    pub class: String,
    /// `None` = class-level; `Some` = method-level.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub member: Option<AnnMember>,
    /// Annotation type descriptor, e.g. `Lnet/neoforged/fml/common/Mod;`.
    pub descriptor: String,
    /// Element values in declaration order.
    #[serde(default)]
    pub values: Vec<AnnPair>,
}

/// Method-level annotation target.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AnnMember {
    pub method: String,
    pub descriptor: String,
}

/// One annotation element `(name, value)`.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AnnPair {
    pub name: String,
    pub value: AnnValue,
}

/// Tagged annotation element value (Java-side `MiniJson` reads `t`/`v`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "t", content = "v")]
pub enum AnnValue {
    Bool(bool),
    /// Int/Long/Char/Short/Byte — widened.
    Int(i64),
    Float(f64),
    Str(String),
    Enum { desc: String, constant: String },
    Class(String),
    List(Vec<AnnValue>),
}

impl Default for AnnValue {
    fn default() -> Self {
        AnnValue::Str(String::new())
    }
}

impl From<&ElementValue> for AnnValue {
    fn from(v: &ElementValue) -> Self {
        match v {
            ElementValue::Bool(b) => AnnValue::Bool(*b),
            ElementValue::Byte(b) => AnnValue::Int(*b as i64),
            ElementValue::Char(c) => AnnValue::Int(*c as i64),
            ElementValue::Short(s) => AnnValue::Int(*s as i64),
            ElementValue::Int(i) => AnnValue::Int(*i as i64),
            ElementValue::Long(l) => AnnValue::Int(*l),
            ElementValue::Float(f) => AnnValue::Float(*f as f64),
            ElementValue::Double(d) => AnnValue::Float(*d),
            ElementValue::Str(s) => AnnValue::Str(s.clone()),
            ElementValue::Enum {
                type_descriptor,
                constant,
            } => AnnValue::Enum {
                desc: type_descriptor.clone(),
                constant: constant.clone(),
            },
            ElementValue::Class { descriptor } => AnnValue::Class(descriptor.clone()),
            ElementValue::Annotation(_) => AnnValue::Str("<nested-annotation>".into()),
            ElementValue::Array(items) => {
                AnnValue::List(items.iter().map(AnnValue::from).collect())
            }
        }
    }
}

/// Build the layer manifest from a scanned `mods/` directory batch.
///
/// Jars that failed to scan are reported as errors by the caller (they are
/// skipped here — the loader must never see a half-described mod).
pub fn build_layer_manifest(scanned: &[ScannedMod]) -> Result<LayerManifest, ScannerError> {
    let order = build_load_order(scanned).map_err(|e| {
        ScannerError::Zip(format!("dependency resolution: {e}"))
    })?;

    // id → jar (first jar declaring the id wins).
    let mut jar_by_id: HashMap<String, &ScannedMod> = HashMap::new();
    for m in scanned {
        for id in m.mod_ids() {
            jar_by_id.entry(id).or_insert(m);
        }
    }

    let mut mods = Vec::with_capacity(scanned.len());
    let mut seen_jars: Vec<&ScannedMod> = Vec::new();
    for id in &order {
        let Some(m) = jar_by_id.get(id.as_str()) else {
            continue;
        };
        let m: &ScannedMod = m;
        if seen_jars.iter().any(|s| std::ptr::eq(*s, m)) {
            continue; // one jar may declare several ids; emit once
        }
        seen_jars.push(m);
        mods.push(mod_entry(m));
    }

    Ok(LayerManifest {
        load_order: order,
        mods,
        at_entries: Vec::new(),
    })
}

fn mod_entry(m: &ScannedMod) -> ModEntry {
    let id = m
        .mod_ids()
        .first()
        .cloned()
        .unwrap_or_else(|| "unknown".to_string());
    let version = m
        .toml
        .as_ref()
        .and_then(|t| t.mods.iter().find(|e| e.mod_id == id))
        .and_then(|e| e.version.clone())
        .unwrap_or_else(|| "unknown".to_string());
    let packages: Vec<String> = {
        let mut pkgs: Vec<String> = m
            .classes
            .iter()
            .filter_map(|c| c.split('/').next().map(str::to_string))
            .filter(|s| !s.is_empty())
            .collect();
        pkgs.sort();
        pkgs.dedup();
        pkgs
    };
    let annotations = m
        .annotation_hits
        .iter()
        .map(|hit| AnnEntry {
            class: hit.class_internal.clone(),
            member: hit.member.as_ref().map(|(method, descriptor)| AnnMember {
                method: method.clone(),
                descriptor: descriptor.clone(),
            }),
            descriptor: hit.annotation.type_descriptor.clone(),
            values: hit
                .annotation
                .values
                .iter()
                .map(|(name, value)| AnnPair {
                    name: name.clone(),
                    value: AnnValue::from(value),
                })
                .collect(),
        })
        .collect();
    ModEntry {
        id,
        file: m.file_name.clone(),
        // Layer-flavoured entries keep their real path (SecureJar proxies and
        // ModFileParser read resources through it); the JAVA loader skips
        // re-adding them by the `layer` flag (their classes already ride the
        // fully-relocated layer jar set).
        jar: if m.jar_override.is_empty() {
            m.path
                .as_ref()
                .map(|p| p.to_string_lossy().into_owned())
                .unwrap_or_default()
        } else {
            m.jar_override.clone()
        },
        layer: m.layer,
        version,
        entrypoints: m.entrypoint_classes.clone(),
        packages,
        classes: m.classes.clone(),
        annotations,
        mixin_configs: m.mixin_configs.clone(),
        module_packages: {
            let mut pkgs: Vec<String> = m
                .classes
                .iter()
                .filter_map(|c| {
                    let slash = c.rfind('/')?;
                    if slash == 0 {
                        None
                    } else {
                        Some(c[..slash].to_string())
                    }
                })
                .collect();
            if m.layer {
                // Layer-flavoured entries (the neoforge universal jar) were
                // fully shadow-relocated at M1: their classes live under
                // cim/shadow/neoforge/<original>. Mirror the layer table.
                pkgs = pkgs
                    .into_iter()
                    .map(|p| {
                        if let Some(rest) = p.strip_prefix("net/neoforged/") {
                            format!("cim/shadow/neoforge/net/neoforged/{rest}")
                        } else if let Some(rest) = p.strip_prefix("net/minecraft/") {
                            format!("cim/shadow/mc1211/net/minecraft/{rest}")
                        } else if let Some(rest) = p.strip_prefix("com/mojang/") {
                            format!("cim/shadow/mojang/com/mojang/{rest}")
                        } else {
                            p
                        }
                    })
                    .collect();
            }
            pkgs.sort();
            pkgs.dedup();
            pkgs
        },
        services: m
            .services
            .iter()
            .map(|(service, providers)| ServiceEntry {
                service: service.clone(),
                providers: providers.clone(),
            })
            .collect(),
    }
}

/// Shadow internal name for a mod class.
pub fn shadow_internal(modid: &str, internal: &str) -> String {
    format!("{MOD_SHADOW_PREFIX}{modid}/{internal}")
}

/// Fold the manifest into the final relocation table.
///
/// Algorithm (deterministic, load-order-driven):
/// 1. start from the M1 layer prefixes (net.minecraft / com.mojang / net.neoforged);
/// 2. walk mods in load order; the FIRST jar that contains a top-level package
///    owns it — one prefix rule `pkg/ → cim/shadow/maven<id>/pkg/`;
/// 3. every later jar's class in a package owned by someone else gets an exact
///    per-class entry (its own shadow namespace);
/// 4. root-level classes (no package) always get exact entries.
pub fn build_relocation_table(manifest: &LayerManifest) -> RelocationTable {
    let mut table = RelocationTable::layer_default();
    let mut owner: HashMap<&str, &str> = HashMap::new();

    for mod_id in &manifest.load_order {
        let Some(entry) = manifest.mods.iter().find(|m| &m.id == mod_id) else {
            continue;
        };
        for pkg in &entry.packages {
            match owner.get(pkg.as_str()) {
                None => {
                    owner.insert(pkg, mod_id);
                    table.add_rule(
                        &format!("{pkg}/"),
                        &format!("{MOD_SHADOW_PREFIX}{mod_id}/{pkg}/"),
                    );
                }
                Some(existing) if *existing == mod_id.as_str() => {}
                Some(_) => {
                    // Package collision: this jar's classes in `pkg` get exact
                    // entries (handled below in the class walk).
                }
            }
        }
        // Exact entries: collisions + root-level classes.
        for class in &entry.classes {
            let needs_exact = match class.split_once('/') {
                None => true, // root-level class: no prefix rule can reach it
                Some((top, _)) => owner.get(top).map(|o| *o != mod_id.as_str()).unwrap_or(false),
            };
            if needs_exact {
                table.add_exact(class, &shadow_internal(mod_id, class));
            }
        }
    }
    table
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scanner::AnnotationHit;
    use crate::classfile::Annotation;

    fn scanned(id: &str, _packages: &[&str], classes: &[&str]) -> ScannedMod {
        let toml = format!("[[mods]]\nmodId = \"{id}\"\nversion = \"1.0.0\"\n");
        ScannedMod {
            file_name: format!("{id}.jar"),
            toml: crate::modstoml::parse_mods_toml(toml.as_bytes()).ok(),
            classes: classes.iter().map(|s| s.to_string()).collect(),
            ..ScannedMod::default()
        }
    }

    #[test]
    fn manifest_and_table_are_load_order_driven() {
        // Two mods sharing the package root "shared": first in load order owns
        // the prefix rule, second gets exact entries.
        let a = scanned("alpha", &["alpha", "shared"], &["alpha/A", "shared/S1"]);
        let b = scanned("beta", &["beta", "shared"], &["beta/B", "shared/S2"]);
        let manifest = build_layer_manifest(&[a, b]).unwrap();
        assert_eq!(manifest.load_order, ["alpha", "beta"]);

        let table = build_relocation_table(&manifest);
        assert_eq!(table.exact_count(), 1); // beta's shared/S2 (collision) only
        assert_eq!(
            table.map_internal("shared/S1").unwrap(),
            "cim/shadow/mavenalpha/shared/S1"
        );
        assert_eq!(
            table.map_internal("shared/S2").unwrap(),
            "cim/shadow/mavenbeta/shared/S2"
        );
        assert_eq!(
            table.map_internal("alpha/A").unwrap(),
            "cim/shadow/mavenalpha/alpha/A"
        );
        assert_eq!(
            table.map_internal("beta/B").unwrap(),
            "cim/shadow/mavenbeta/beta/B"
        );
        // Layer rules survive.
        assert_eq!(
            table.map_internal("net/minecraft/world/item/Item").unwrap(),
            "cim/shadow/mc1211/net/minecraft/world/item/Item"
        );
    }

    #[test]
    fn root_level_classes_get_exact_entries() {
        let a = scanned("rooty", &[], &["RootClass", "pkg/Klass"]);
        let manifest = build_layer_manifest(&[a]).unwrap();
        let table = build_relocation_table(&manifest);
        assert_eq!(
            table.map_internal("RootClass").unwrap(),
            "cim/shadow/mavenrooty/RootClass"
        );
        assert_eq!(
            table.map_internal("pkg/Klass").unwrap(),
            "cim/shadow/mavenrooty/pkg/Klass"
        );
    }

    #[test]
    fn annotation_values_convert() {
        let ann = Annotation {
            type_descriptor: "Lnet/neoforged/fml/common/EventBusSubscriber;".into(),
            values: vec![
                (
                    "modid".into(),
                    ElementValue::Str("cimtest".into()),
                ),
                (
                    "value".into(),
                    ElementValue::Array(vec![ElementValue::Enum {
                        type_descriptor: "Lnet/neoforged/api/distmarker/Dist;".into(),
                        constant: "DEDICATED_SERVER".into(),
                    }]),
                ),
            ],
        };
        let hit = AnnotationHit {
            class_internal: "dev/cim/Test".into(),
            member: None,
            annotation: ann,
        };
        let entry = crate::scanner::ScannedMod {
            file_name: "x.jar".into(),
            annotation_hits: vec![hit],
            toml: crate::modstoml::parse_mods_toml(b"[[mods]]\nmodId = \"annmod\"\n").ok(),
            ..ScannedMod::default()
        };
        let manifest = build_layer_manifest(&[entry]).unwrap();
        let e = &manifest.mods[0];
        assert_eq!(e.id, "annmod");
        assert_eq!(e.annotations.len(), 1);
        assert_eq!(
            e.annotations[0].descriptor,
            "Lnet/neoforged/fml/common/EventBusSubscriber;"
        );
        match &e.annotations[0].values[1].value {
            AnnValue::List(items) => match &items[0] {
                AnnValue::Enum { constant, .. } => assert_eq!(constant, "DEDICATED_SERVER"),
                other => panic!("expected enum, got {other:?}"),
            },
            other => panic!("expected list, got {other:?}"),
        }
    }
}
