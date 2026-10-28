//! cim-reloc — shadow-namespace relocation for the c-imagine layer.
//!
//! The layer loads the real Minecraft 1.21.1 + NeoForge 21.1.x stack inside
//! the Paper 26.3 JVM under relocated names (`ARCHITECTURE.md §4.2`):
//!
//! ```text
//! net/minecraft/**   → cim/shadow/mc1211/net/minecraft/**
//! com/mojang/**      → cim/shadow/mojang/com/mojang/**
//! net/neoforged/**   → cim/shadow/neoforge/net/neoforged/**
//! ```
//!
//! Because NeoForge 1.21.1 ships mods and runtime in **Mojang official
//! mappings** (no reobf pipeline exists upstream), the relocation is a pure
//! mechanical rename: no name-remapping stage is needed between "mod jar"
//! and "relocated layer".
//!
//! What is rewritten, and why exactly this subset:
//!
//! - `CONSTANT_Class` entries — superclasses, interfaces, `new`, `checkcast`,
//!   `instanceof`, `athrow`, array class literals. Required for linking.
//! - `CONSTANT_NameAndType` descriptors and `CONSTANT_MethodType` descriptors
//!   — field/method signatures that name relocatable classes. Required for
//!   linking and for `invokedynamic`.
//!
//! What is deliberately **not** rewritten (loading never needs it, and
//! keeping originals preserves FML's own raw-byte annotation scanning):
//!
//! - generic `Signature` attribute contents,
//! - runtime annotation descriptors (`Lnet/neoforged/...;` strings),
//! - `CONSTANT_String` literals (reflection over own names is served by the
//!   `ClassNames.decode` shim, tracked in the roadmap).
//!
//! Everything else in the class file — every attribute, every original
//! constant-pool slot — is preserved byte-identical. Rewrites are appended
//! as fresh UTF-8 entries; original slots are never mutated in place, so
//! attribute-referenced strings stay valid by construction.

use cim_core::CimError;
use std::collections::HashMap;

pub mod at;
pub mod classfile;

/// One prefix rewrite rule over internal (slash-separated) names.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    /// Source prefix, e.g. `net/minecraft/`.
    pub from: String,
    /// Replacement prefix, e.g. `cim/shadow/mc1211/net/minecraft/`.
    pub to: String,
}

impl Rule {
    pub fn new(from: &str, to: &str) -> Self {
        Self {
            from: from.to_string(),
            to: to.to_string(),
        }
    }
}

/// Ordered relocation rules with longest-prefix matching, plus an
/// **exact-name** map that takes priority over prefix rules.
///
/// Exact entries exist for the M2 mod space: when two mods share a top-level
/// package, a single prefix rule cannot split them apart, so every class of
/// the losing jars gets a per-class entry. The lookup is O(1) for exact
/// names and scans the (small) prefix rule list otherwise.
#[derive(Debug, Clone, Default)]
pub struct RelocationTable {
    rules: Vec<Rule>,
    exact: HashMap<String, String>,
}

/// Guard prefix: names under the shadow space are never re-relocated
/// (idempotency by construction, not by convention).
const SHADOW_PREFIX: &str = "cim/shadow/";

impl RelocationTable {
    /// The M1 layer table: the whole 1.21.1 stack (MC, Mojang libraries,
    /// NeoForge) into the `cim.shadow.*` namespace.
    pub fn layer_default() -> Self {
        Self {
            rules: vec![
                Rule::new("net/minecraft/", "cim/shadow/mc1211/net/minecraft/"),
                Rule::new("com/mojang/", "cim/shadow/mojang/com/mojang/"),
                Rule::new("net/neoforged/", "cim/shadow/neoforge/net/neoforged/"),
            ],
            exact: HashMap::new(),
        }
    }

    /// Empty table (passthrough). Used by tests and the M2 mod-relocation
    /// builder, which assembles per-mod tables explicitly.
    pub fn empty() -> Self {
        Self {
            rules: Vec::new(),
            exact: HashMap::new(),
        }
    }

    pub fn add_rule(&mut self, from: &str, to: &str) {
        self.rules.push(Rule::new(from, to));
    }

    /// Register an exact internal-name mapping (highest priority).
    /// The key must not be a shadow name (idempotency guard below).
    pub fn add_exact(&mut self, from: &str, to: &str) {
        if from.starts_with(SHADOW_PREFIX) {
            return;
        }
        self.exact.insert(from.to_string(), to.to_string());
    }

    pub fn rules(&self) -> &[Rule] {
        &self.rules
    }

    /// Number of exact-name entries (diagnostics).
    pub fn exact_count(&self) -> usize {
        self.exact.len()
    }

    /// Map an internal (slash) class name. Returns `None` when the name is
    /// not relocatable (including already-shadow names).
    pub fn map_internal(&self, name: &str) -> Option<String> {
        if name.starts_with(SHADOW_PREFIX) {
            return None;
        }
        // Exact-name entries win over prefix rules (they exist precisely to
        // override an ambiguous shared prefix).
        if let Some(to) = self.exact.get(name) {
            return Some(to.clone());
        }
        let mut best: Option<&Rule> = None;
        for rule in &self.rules {
            if name.starts_with(&rule.from) {
                match best {
                    Some(b) if b.from.len() >= rule.from.len() => {}
                    _ => best = Some(rule),
                }
            }
        }
        best.map(|r| format!("{}{}", r.to, &name[r.from.len()..]))
    }

    /// Map a binary (dot-separated) class name, e.g. for `Class.forName`
    /// shims. Returns the input unchanged when not relocatable.
    pub fn map_binary(&self, name: &str) -> String {
        let slashed = name.replace('.', "/");
        match self.map_internal(&slashed) {
            Some(mapped) => mapped.replace('/', "."),
            None => name.to_string(),
        }
    }

    /// Map a generic signature (JVMS §4.7.9.1), rewriting every class-type
    /// signature segment (`L<name>;`) it can. Type variables (`T…;`) and
    /// segments with type arguments (`…<…>;`) pass through unchanged — the
    /// first `;` scan is only trusted for plain class segments, which is
    /// exactly what relocation needs (generic hierarchies of layer types are
    /// resolved through reflection by these strings).
    pub fn map_signature(&self, signature: &str) -> String {
        let bytes = signature.as_bytes();
        let mut out = String::with_capacity(signature.len() + 8);
        let mut i = 0usize;
        while i < bytes.len() {
            if bytes[i] == b'L' {
                if let Some(end) = bytes[i + 1..].iter().position(|&b| b == b';') {
                    let segment = &signature[i + 1..i + 1 + end];
                    if !segment.contains('<') {
                        match self.map_internal(segment) {
                            Some(mapped) => {
                                out.push('L');
                                out.push_str(&mapped);
                                out.push(';');
                                i += end + 2;
                                continue;
                            }
                            None => {
                                // copy the whole segment verbatim (an 'L'
                                // inside a class name can't mislead: the
                                // segment is consumed either way)
                                out.push_str(&signature[i..=i + 1 + end]);
                                i += end + 2;
                                continue;
                            }
                        }
                    }
                }
            }
            out.push(signature[i..].chars().next().unwrap_or('\u{fffd}'));
            i += signature[i..].chars().next().map_or(1, char::len_utf8);
        }
        out
    }

    /// True when the internal name belongs to the layer's relocation space.
    pub fn needs_relocation(&self, internal_name: &str) -> bool {
        self.map_internal(internal_name).is_some()
    }

    /// Rewrite a field/method descriptor (`(Lnet/minecraft/Item;I)V`) or an
    /// array class name (`[Lnet/minecraft/Level;`). Non-class segments and
    /// non-relocatable classes pass through untouched; the output differs
    /// from the input only where a rewrite applies.
    pub fn map_descriptor(&self, descriptor: &str) -> String {
        let bytes = descriptor.as_bytes();
        let mut out = String::with_capacity(descriptor.len() + 16);
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'L' {
                if let Some(end) = bytes[i + 1..].iter().position(|&b| b == b';') {
                    let class = &descriptor[i + 1..i + 1 + end];
                    match self.map_internal(class) {
                        Some(mapped) => {
                            out.push('L');
                            out.push_str(&mapped);
                            out.push(';');
                        }
                        // `end` is relative to i+1, so the ';' sits at
                        // i+1+end; the inclusive slice must reach it.
                        None => out.push_str(&descriptor[i..=i + 1 + end]),
                    }
                    i += end + 2;
                    continue;
                }
            }
            // Copy the byte (ASCII-only domain per JVM spec; multi-byte UTF-8
            // continuation bytes never collide with 'L'/';').
            out.push(descriptor[i..].chars().next().unwrap_or('\u{fffd}'));
            i += descriptor[i..].chars().next().map_or(1, char::len_utf8);
        }
        out
    }

    /// The table as JSON (debugging / Java-side diagnostics). Exact entries
    /// are summarized as a count — the full set can be tens of thousands of
    /// names for a real modpack.
    pub fn to_json(&self) -> String {
        let rules: Vec<String> = self
            .rules
            .iter()
            .map(|r| {
                format!(
                    "{{\"from\":\"{}\",\"to\":\"{}\"}}",
                    json_escape(&r.from),
                    json_escape(&r.to)
                )
            })
            .collect();
        format!(
            "{{\"rules\":[{}],\"exactCount\":{}}}",
            rules.join(","),
            self.exact.len()
        )
    }
}

fn json_escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('"', "\\\"")
}

/// Statistics from one class rewrite (boot-log + tests).
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct RewriteStats {
    pub classes_renamed: usize,
    pub descriptors_rewritten: usize,
    pub utf8_added: usize,
}

/// Relocate one class file.
///
/// Classes outside the relocation space are returned as an owned copy
/// unchanged; classes inside it get their constant pool rebuilt with
/// rewritten `Class`/`NameAndType`/`MethodType` entries.
pub fn relocate_class(
    table: &RelocationTable,
    internal_name: &str,
    bytes: &[u8],
) -> Result<Vec<u8>, CimError> {
    relocate_class_at(
        table,
        &at::AccessTransformTable::default(),
        internal_name,
        bytes,
    )
}

/// Relocate with an access-transform database applied (M2): matching
/// classes/members get widened access exactly like a real AT-patched runtime.
pub fn relocate_class_at(
    table: &RelocationTable,
    at: &at::AccessTransformTable,
    internal_name: &str,
    bytes: &[u8],
) -> Result<Vec<u8>, CimError> {
    if !table.needs_relocation(internal_name) {
        return Ok(bytes.to_vec());
    }
    let file = classfile::ClassFile::parse(bytes)
        .map_err(|e| CimError::InvalidData(format!("reloc {internal_name}: {e}")))?;
    let (out, _stats) = file
        .rewrite_at(table, at, internal_name)
        .map_err(|e| CimError::InvalidData(format!("reloc {internal_name}: {e}")))?;
    Ok(out)
}

/// Relocate one class file from the M2 mod space: the class keeps its
/// original binary name; only its references into the layer namespaces
/// (`net.minecraft` / `com.mojang` / `net.neoforged`) are rewritten.
/// Classes with no layer references pass through byte-identical.
pub fn relocate_class_refs_only(
    table: &RelocationTable,
    internal_name: &str,
    bytes: &[u8],
) -> Result<Vec<u8>, CimError> {
    relocate_class_refs_only_at(
        table,
        &at::AccessTransformTable::default(),
        internal_name,
        bytes,
    )
}

/// Refs-only relocation with an access-transform database (mod ATs widen
/// vanilla/MC members they touch).
pub fn relocate_class_refs_only_at(
    table: &RelocationTable,
    at: &at::AccessTransformTable,
    internal_name: &str,
    bytes: &[u8],
) -> Result<Vec<u8>, CimError> {
    let file = classfile::ClassFile::parse(bytes)
        .map_err(|e| CimError::InvalidData(format!("reloc {internal_name}: {e}")))?;
    let (out, _stats) = file
        .rewrite_refs_only_at(table, at, internal_name)
        .map_err(|e| CimError::InvalidData(format!("reloc {internal_name}: {e}")))?;
    Ok(out)
}

/// Intern pool for new UTF-8 entries appended during a rewrite.
#[derive(Debug, Default)]
struct Utf8Intern {
    map: HashMap<Vec<u8>, u16>,
    appended: Vec<Vec<u8>>,
}

impl Utf8Intern {
    fn new() -> Self {
        Self::default()
    }

    /// Intern `content`, returning the future pool index for it. New entries
    /// get indices after the original pool (`next_index` onward).
    fn intern(&mut self, content: &[u8], next_index: &mut u16) -> u16 {
        if let Some(&idx) = self.map.get(content) {
            return idx;
        }
        let idx = *next_index;
        *next_index = next_index
            .checked_add(1)
            .expect("constant pool index overflow is rejected by the caller");
        self.map.insert(content.to_vec(), idx);
        self.appended.push(content.to_vec());
        idx
    }

    fn len(&self) -> usize {
        self.appended.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layer_default_maps_the_three_namespaces() {
        let t = RelocationTable::layer_default();
        assert_eq!(
            t.map_internal("net/minecraft/server/Main").unwrap(),
            "cim/shadow/mc1211/net/minecraft/server/Main"
        );
        assert_eq!(
            t.map_internal("com/mojang/brigadier/Command").unwrap(),
            "cim/shadow/mojang/com/mojang/brigadier/Command"
        );
        assert_eq!(
            t.map_internal("net/neoforged/fml/ModContainer").unwrap(),
            "cim/shadow/neoforge/net/neoforged/fml/ModContainer"
        );
    }

    #[test]
    fn non_layer_names_pass_through() {
        let t = RelocationTable::layer_default();
        assert!(t.map_internal("dev/cim/layer/LayerMain").is_none());
        assert!(t.map_internal("com/google/common/collect/Maps").is_none());
        assert!(t.map_internal("org/apache/logging/log4j/Logger").is_none());
        assert!(!t.needs_relocation("it/unimi/dsi/fastutil/ints/Int2ObjectMap"));
    }

    #[test]
    fn shadow_names_are_never_re_relocated() {
        let t = RelocationTable::layer_default();
        let relocated = "cim/shadow/mc1211/net/minecraft/server/Main";
        assert!(t.map_internal(relocated).is_none());
        assert!(!t.needs_relocation(relocated));
    }

    #[test]
    fn longest_prefix_wins() {
        let mut t = RelocationTable::empty();
        t.add_rule("net/", "cim/shadow/a/net/");
        t.add_rule("net/minecraft/", "cim/shadow/b/net/minecraft/");
        assert_eq!(
            t.map_internal("net/minecraft/server/Main").unwrap(),
            "cim/shadow/b/net/minecraft/server/Main"
        );
        assert_eq!(
            t.map_internal("net/neoforged/X").unwrap(),
            "cim/shadow/a/net/neoforged/X"
        );
    }

    #[test]
    fn binary_names_map_and_pass_through() {
        let t = RelocationTable::layer_default();
        assert_eq!(
            t.map_binary("net.minecraft.server.Main"),
            "cim.shadow.mc1211.net.minecraft.server.Main"
        );
        assert_eq!(
            t.map_binary("dev.cim.layer.LayerMain"),
            "dev.cim.layer.LayerMain"
        );
    }

    #[test]
    fn descriptors_rewrite_class_segments_only() {
        let t = RelocationTable::layer_default();
        assert_eq!(
            t.map_descriptor("(Lnet/minecraft/world/item/Item;I)V"),
            "(Lcim/shadow/mc1211/net/minecraft/world/item/Item;I)V"
        );
        assert_eq!(
            t.map_descriptor("[Lcom/mojang/brigadier/Builder;"),
            "[Lcim/shadow/mojang/com/mojang/brigadier/Builder;"
        );
        assert_eq!(
            t.map_descriptor("(Ljava/lang/String;[[I)Lcom/google/gson/JsonElement;"),
            "(Ljava/lang/String;[[I)Lcom/google/gson/JsonElement;"
        );
        // Unmatched classes stay byte-identical.
        assert_eq!(t.map_descriptor("(Ldev/cim/Layer;)V"), "(Ldev/cim/Layer;)V");
        // Primitives and void untouched.
        assert_eq!(t.map_descriptor("()V"), "()V");
        assert_eq!(t.map_descriptor("(JIJZBFS)V"), "(JIJZBFS)V");
    }

    #[test]
    fn json_export_is_wellformed() {
        let t = RelocationTable::layer_default();
        let json = t.to_json();
        assert!(json.starts_with("{\"rules\":["));
        assert!(json.contains("\"exactCount\":0"));
        assert!(json.contains("cim/shadow/mc1211"));
    }

    #[test]
    fn exact_entries_win_over_prefix_rules() {
        let mut t = RelocationTable::empty();
        t.add_rule("mezz/", "cim/shadow/mavenjei/mezz/");
        t.add_exact(
            "mezz/jei/shared/X",
            "cim/shadow/mavenjade/mezz/jei/shared/X",
        );
        assert_eq!(
            t.map_internal("mezz/jei/shared/X").unwrap(),
            "cim/shadow/mavenjade/mezz/jei/shared/X"
        );
        assert_eq!(
            t.map_internal("mezz/jei/other/Y").unwrap(),
            "cim/shadow/mavenjei/mezz/jei/other/Y"
        );
        assert_eq!(t.exact_count(), 1);
    }

    #[test]
    fn exact_entries_guard_against_shadow_keys() {
        let mut t = RelocationTable::empty();
        t.add_exact(
            "cim/shadow/mc1211/net/minecraft/A",
            "oops/should/not/happen",
        );
        assert!(t
            .map_internal("cim/shadow/mc1211/net/minecraft/A")
            .is_none());
        assert_eq!(t.exact_count(), 0);
    }
}
