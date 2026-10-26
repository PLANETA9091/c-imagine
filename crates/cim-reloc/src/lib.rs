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

/// Ordered relocation rules with longest-prefix matching.
#[derive(Debug, Clone, Default)]
pub struct RelocationTable {
    rules: Vec<Rule>,
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
        }
    }

    /// Empty table (passthrough). Used by tests and the M2 mod-relocation
    /// builder, which assembles per-mod tables explicitly.
    pub fn empty() -> Self {
        Self { rules: Vec::new() }
    }

    pub fn add_rule(&mut self, from: &str, to: &str) {
        self.rules.push(Rule::new(from, to));
    }

    pub fn rules(&self) -> &[Rule] {
        &self.rules
    }

    /// Map an internal (slash) class name. Returns `None` when the name is
    /// not relocatable (including already-shadow names).
    pub fn map_internal(&self, name: &str) -> Option<String> {
        if name.starts_with(SHADOW_PREFIX) {
            return None;
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

    /// The table as JSON (debugging / Java-side diagnostics).
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
        format!("{{\"rules\":[{}]}}", rules.join(","))
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
    if !table.needs_relocation(internal_name) {
        return Ok(bytes.to_vec());
    }
    let file = classfile::ClassFile::parse(bytes)
        .map_err(|e| CimError::InvalidData(format!("reloc {internal_name}: {e}")))?;
    let (out, _stats) = file
        .rewrite(table)
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
        assert!(json.ends_with("]}"));
        assert!(json.contains("cim/shadow/mc1211"));
    }
}
