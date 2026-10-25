//! Registry bookkeeping for the mini-NeoForge layer.
//!
//! Model (docs/ARCHITECTURE.md §4.5):
//! - modded registrations allocate ids from [`MODDED_ID_BASE`] upward per
//!   registry — disjoint from every vanilla id space, collision-free;
//! - a bidirectional [`RemapTable`] connects layer (1.21.1) ids ↔ Paper (26.3)
//!   ids for shared registries;
//! - [`RegistrySnapshot`] + [`SnapshotDiff`] detect drift across reloads and
//!   drive F2V v2 translation tables.

use cim_core::{CimError, MODDED_ID_BASE};
use std::collections::HashMap;

/// Per-registry id allocator for modded content.
#[derive(Debug, Default)]
pub struct IdAllocator {
    registry: String,
    next_id: u32,
    by_key: HashMap<String, u32>,
    by_id: HashMap<u32, String>,
}

impl IdAllocator {
    pub fn new(registry: &str) -> Self {
        Self {
            registry: registry.to_string(),
            next_id: MODDED_ID_BASE,
            by_key: HashMap::new(),
            by_id: HashMap::new(),
        }
    }

    pub fn registry(&self) -> &str {
        &self.registry
    }

    /// Allocate (or fetch) the modded id for `key`. Ids are assigned in call
    /// order; call in load order for determinism.
    pub fn alloc(&mut self, key: &str) -> Result<u32, CimError> {
        if let Some(id) = self.by_key.get(key) {
            return Ok(*id);
        }
        if self.next_id == u32::MAX {
            return Err(CimError::InvalidData(format!(
                "registry {} exhausted modded id space",
                self.registry
            )));
        }
        let id = self.next_id;
        self.next_id += 1;
        self.by_key.insert(key.to_string(), id);
        self.by_id.insert(id, key.to_string());
        Ok(id)
    }

    pub fn id_of(&self, key: &str) -> Option<u32> {
        self.by_key.get(key).copied()
    }

    pub fn key_of(&self, id: u32) -> Option<&str> {
        self.by_id.get(&id).map(String::as_str)
    }

    pub fn len(&self) -> usize {
        self.by_key.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_key.is_empty()
    }

    pub fn iter(&self) -> impl Iterator<Item = (&str, u32)> {
        self.by_key.iter().map(|(k, v)| (k.as_str(), *v))
    }
}

/// Bidirectional layer(1.21.1) ↔ paper(26.3) id remap for one registry.
#[derive(Debug, Default)]
pub struct RemapTable {
    registry: String,
    layer_to_paper: HashMap<u32, u32>,
    paper_to_layer: HashMap<u32, u32>,
}

impl RemapTable {
    pub fn new(registry: &str) -> Self {
        Self {
            registry: registry.to_string(),
            layer_to_paper: HashMap::new(),
            paper_to_layer: HashMap::new(),
        }
    }

    pub fn registry(&self) -> &str {
        &self.registry
    }

    /// Insert a mapping; errors instead of silently overwriting (a duplicate
    /// means a bookkeeping bug we want surfaced, not hidden).
    pub fn insert(&mut self, layer_id: u32, paper_id: u32) -> Result<(), CimError> {
        if let Some(existing) = self.layer_to_paper.get(&layer_id) {
            if *existing != paper_id {
                return Err(CimError::InvalidData(format!(
                    "registry {}: layer id {layer_id} already maps to paper id {existing} (new {paper_id})",
                    self.registry
                )));
            }
        }
        if let Some(existing) = self.paper_to_layer.get(&paper_id) {
            if *existing != layer_id {
                return Err(CimError::InvalidData(format!(
                    "registry {}: paper id {paper_id} already maps to layer id {existing} (new {layer_id})",
                    self.registry
                )));
            }
        }
        self.layer_to_paper.insert(layer_id, paper_id);
        self.paper_to_layer.insert(paper_id, layer_id);
        Ok(())
    }

    pub fn layer_to_paper(&self, layer_id: u32) -> Option<u32> {
        self.layer_to_paper.get(&layer_id).copied()
    }

    pub fn paper_to_layer(&self, paper_id: u32) -> Option<u32> {
        self.paper_to_layer.get(&paper_id).copied()
    }

    pub fn len(&self) -> usize {
        self.layer_to_paper.len()
    }

    pub fn is_empty(&self) -> bool {
        self.layer_to_paper.is_empty()
    }
}

/// Registry names the layer mirrors. Order matters: it is the registration
/// order used when booting registries, matching NeoForge's vanilla order.
pub const CORE_REGISTRIES: [&str; 10] = [
    "block",
    "item",
    "entity_type",
    "block_entity_type",
    "fluid",
    "sound_event",
    "particle_type",
    "potion",
    "enchantment",
    "menu",
];

/// Point-in-time key→id view of one registry.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RegistrySnapshot {
    pub registry: String,
    pub entries: HashMap<String, u32>,
}

impl RegistrySnapshot {
    pub fn new(registry: &str) -> Self {
        Self {
            registry: registry.to_string(),
            entries: HashMap::new(),
        }
    }

    pub fn insert(&mut self, key: &str, id: u32) {
        self.entries.insert(key.to_string(), id);
    }

    pub fn get(&self, key: &str) -> Option<u32> {
        self.entries.get(key).copied()
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Diff `self` (old) against `newer`.
    pub fn diff(&self, newer: &RegistrySnapshot) -> SnapshotDiff {
        assert_eq!(
            self.registry, newer.registry,
            "diff must compare the same registry"
        );
        let mut added: Vec<(String, u32)> = Vec::new();
        let mut removed: Vec<String> = Vec::new();
        let mut moved: Vec<(String, u32, u32)> = Vec::new();
        for (key, new_id) in &newer.entries {
            match self.entries.get(key) {
                None => added.push((key.clone(), *new_id)),
                Some(old_id) if old_id != new_id => moved.push((key.clone(), *old_id, *new_id)),
                Some(_) => {}
            }
        }
        for key in self.entries.keys() {
            if !newer.entries.contains_key(key) {
                removed.push(key.clone());
            }
        }
        added.sort();
        removed.sort();
        moved.sort();
        SnapshotDiff {
            registry: self.registry.clone(),
            added,
            removed,
            moved,
        }
    }
}

/// Result of comparing two snapshots of the same registry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SnapshotDiff {
    pub registry: String,
    pub added: Vec<(String, u32)>,
    pub removed: Vec<String>,
    /// Keys present in both, with (old_id, new_id).
    pub moved: Vec<(String, u32, u32)>,
}

impl SnapshotDiff {
    pub fn is_empty(&self) -> bool {
        self.added.is_empty() && self.removed.is_empty() && self.moved.is_empty()
    }

    pub fn total_changes(&self) -> usize {
        self.added.len() + self.removed.len() + self.moved.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modded_ids_start_at_base_and_are_disjoint() {
        let mut alloc = IdAllocator::new("block");
        let a = alloc.alloc("cimtest:test_block").unwrap();
        let b = alloc.alloc("cimtest:machine").unwrap();
        assert_eq!(a, MODDED_ID_BASE);
        assert_eq!(b, MODDED_ID_BASE + 1);
        assert!(a >= MODDED_ID_BASE);
        assert_eq!(alloc.key_of(a), Some("cimtest:test_block"));
        assert_eq!(alloc.id_of("cimtest:machine"), Some(b));
        assert_eq!(alloc.len(), 2);
    }

    #[test]
    fn alloc_is_idempotent_per_key() {
        let mut alloc = IdAllocator::new("item");
        let a1 = alloc.alloc("cimtest:wand").unwrap();
        let a2 = alloc.alloc("cimtest:wand").unwrap();
        assert_eq!(a1, a2);
        assert_eq!(alloc.len(), 1);
        assert!(!alloc.is_empty());
    }

    #[test]
    fn remap_table_is_bidirectional_and_strict() {
        let mut t = RemapTable::new("block");
        t.insert(1_000_000_000, 512).unwrap();
        assert_eq!(t.layer_to_paper(1_000_000_000), Some(512));
        assert_eq!(t.paper_to_layer(512), Some(1_000_000_000));
        assert_eq!(t.len(), 1);

        // Idempotent same-pair insert is fine.
        assert!(t.insert(1_000_000_000, 512).is_ok());
        // Conflicting inserts are surfaced, not hidden.
        assert!(t.insert(1_000_000_000, 999).is_err());
        assert!(t.insert(1_000_000_001, 512).is_err());
    }

    #[test]
    fn snapshot_diff_detects_added_removed_moved() {
        let mut old = RegistrySnapshot::new("item");
        old.insert("minecraft:stone", 1);
        old.insert("minecraft:dirt", 2);
        old.insert("cimtest:wand", 1_000_000_000);

        let mut new = RegistrySnapshot::new("item");
        new.insert("minecraft:stone", 1);
        new.insert("minecraft:dirt", 7); // moved
        new.insert("cimtest:wand", 1_000_000_000);
        new.insert("cimtest:machine", 1_000_000_001); // added
                                                      // "removed": nothing else; add one key only in old:
        old.insert("cimtest:legacy", 1_000_000_002);

        let d = old.diff(&new);
        assert_eq!(d.added, [("cimtest:machine".to_string(), 1_000_000_001)]);
        assert_eq!(d.removed, ["cimtest:legacy"]);
        assert_eq!(d.moved, [("minecraft:dirt".to_string(), 2, 7)]);
        assert_eq!(d.total_changes(), 3);
        assert!(!d.is_empty());
    }

    #[test]
    fn identical_snapshots_have_empty_diff() {
        let mut s1 = RegistrySnapshot::new("entity_type");
        s1.insert("minecraft:pig", 10);
        let s2 = s1.clone();
        assert!(s1.diff(&s2).is_empty());
    }

    #[test]
    fn core_registry_list_is_stable() {
        assert_eq!(CORE_REGISTRIES[0], "block");
        assert_eq!(CORE_REGISTRIES.len(), 10);
    }
}
