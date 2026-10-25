//! Translation plan: the complete, validated packet-rewrite program that the
//! M4 net hooks execute.
//!
//! A plan is built from three inputs (all produced elsewhere, all testable
//! without a server):
//! 1. layer (1.21.1) registry snapshot — what the modded side sees;
//! 2. paper (26.3) registry snapshot — what clients understand;
//! 3. visual mappings ([`crate::translate`]).
//!
//! Validity invariants checked at build time (so per-packet code stays
//! panic-free): every mapped modded key exists in the layer snapshot; every
//! replacement vanilla key exists in the paper snapshot; layer ids for mapped
//! keys have paper-side remaps when both registries share content.

use crate::translate::{BlockMappings, EntityMappings};
use crate::F2vError;
use cim_registry::RegistrySnapshot;
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TargetKind {
    Entity,
    Block,
}

/// One rewrite rule: modded layer id → vanilla paper-side id + presentation.
#[derive(Debug, Clone, PartialEq)]
pub struct RewriteRule {
    pub kind: TargetKind,
    /// Modded registry key (`cimtest:test_entity`).
    pub modded_key: String,
    /// Layer-side id (from the layer snapshot / allocator).
    pub layer_id: u32,
    /// Paper-side id the client receives (from the paper snapshot).
    pub paper_id: u32,
    /// Vanilla key used for rendering.
    pub vanilla_key: String,
    pub scale: f32,
    pub tint: Option<u32>,
}

/// Complete plan for one server session.
#[derive(Debug, Clone, Default)]
pub struct TranslationPlan {
    pub rules: HashMap<String, RewriteRule>,
}

impl TranslationPlan {
    pub fn rule_for_layer_id(&self, layer_id: u32) -> Option<&RewriteRule> {
        self.rules.values().find(|r| r.layer_id == layer_id)
    }

    pub fn entity_rules(&self) -> impl Iterator<Item = &RewriteRule> {
        self.rules.values().filter(|r| r.kind == TargetKind::Entity)
    }

    pub fn block_rules(&self) -> impl Iterator<Item = &RewriteRule> {
        self.rules.values().filter(|r| r.kind == TargetKind::Block)
    }
}

/// Build a validated plan.
pub fn build_plan(
    layer: &RegistrySnapshot,
    paper: &RegistrySnapshot,
    entities: &EntityMappings,
    blocks: &BlockMappings,
) -> Result<TranslationPlan, F2vError> {
    let mut plan = TranslationPlan::default();

    let add_rules = |kind: TargetKind,
                     mappings: &HashMap<String, crate::translate::VisualReplacement>,
                     plan: &mut TranslationPlan|
     -> Result<(), F2vError> {
        for (modded_key, repl) in mappings {
            let layer_id = layer.get(modded_key).ok_or_else(|| {
                F2vError::Unmapped(format!("{modded_key} missing from layer snapshot"))
            })?;
            let paper_id = paper.get(&repl.vanilla.to_registry_key()).ok_or_else(|| {
                F2vError::Unmapped(format!("{} missing from paper snapshot", repl.vanilla))
            })?;
            let rule = RewriteRule {
                kind: kind.clone(),
                modded_key: modded_key.clone(),
                layer_id,
                paper_id,
                vanilla_key: repl.vanilla.to_registry_key(),
                scale: repl.scale,
                tint: repl.tint,
            };
            plan.rules.insert(modded_key.clone(), rule);
        }
        Ok(())
    };

    let entity_map: HashMap<_, _> = entities
        .iter_items()
        .map(|(k, v)| (k.to_string(), v.clone()))
        .collect();
    let block_map: HashMap<_, _> = blocks
        .iter_items()
        .map(|(k, v)| (k.to_string(), v.clone()))
        .collect();

    add_rules(TargetKind::Entity, &entity_map, &mut plan)?;
    add_rules(TargetKind::Block, &block_map, &mut plan)?;
    Ok(plan)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::translate::VisualReplacement;

    fn layer_snapshot() -> RegistrySnapshot {
        let mut s = RegistrySnapshot::new("entity_type");
        s.insert("cimtest:test_entity", 1_000_000_000);
        s.insert("minecraft:pig", 900);
        s
    }

    fn paper_snapshot() -> RegistrySnapshot {
        let mut s = RegistrySnapshot::new("entity_type");
        s.insert("minecraft:pig", 901); // ids differ across versions — the point of remaps
        s.insert("minecraft:iron_golem", 950);
        s
    }

    fn entity_mappings() -> EntityMappings {
        let mut m = EntityMappings::new();
        m.set(
            "cimtest:test_entity",
            VisualReplacement::new("minecraft:iron_golem", 0.5, None).unwrap(),
        )
        .unwrap();
        m
    }

    #[test]
    fn plan_binds_layer_ids_to_paper_ids() {
        let plan = build_plan(
            &layer_snapshot(),
            &paper_snapshot(),
            &entity_mappings(),
            &BlockMappings::new(),
        )
        .unwrap();
        assert_eq!(plan.rules.len(), 1);
        let rule = plan.rule_for_layer_id(1_000_000_000).unwrap();
        assert_eq!(rule.paper_id, 950);
        assert_eq!(rule.vanilla_key, "minecraft:iron_golem");
        assert_eq!(rule.kind, TargetKind::Entity);
        assert_eq!(plan.entity_rules().count(), 1);
        assert_eq!(plan.block_rules().count(), 0);
    }

    #[test]
    fn unmapped_modded_key_fails_build() {
        let mut m = EntityMappings::new();
        m.set(
            "cimtest:ghost",
            VisualReplacement::new("minecraft:pig", 1.0, None).unwrap(),
        )
        .unwrap();
        let err = build_plan(
            &layer_snapshot(),
            &paper_snapshot(),
            &m,
            &BlockMappings::new(),
        )
        .unwrap_err();
        assert!(matches!(err, F2vError::Unmapped(_)), "got {err:?}");
    }

    #[test]
    fn vanilla_target_missing_in_paper_snapshot_fails() {
        let mut m = EntityMappings::new();
        m.set(
            "cimtest:test_entity",
            VisualReplacement::new("minecraft:dragon", 1.0, None).unwrap(),
        )
        .unwrap();
        let err = build_plan(
            &layer_snapshot(),
            &paper_snapshot(),
            &m,
            &BlockMappings::new(),
        )
        .unwrap_err();
        assert!(matches!(err, F2vError::Unmapped(_)));
    }

    #[test]
    fn block_rules_validate_too() {
        let mut layer = RegistrySnapshot::new("block");
        layer.insert("cimtest:test_block", 1_000_000_005);
        let mut paper = RegistrySnapshot::new("block");
        paper.insert("minecraft:iron_block", 120);
        let mut blocks = BlockMappings::new();
        blocks
            .set(
                "cimtest:test_block",
                VisualReplacement::new("minecraft:iron_block", 1.0, None).unwrap(),
            )
            .unwrap();
        let plan = build_plan(&layer, &paper, &EntityMappings::new(), &blocks).unwrap();
        assert_eq!(plan.block_rules().count(), 1);
        assert_eq!(plan.rule_for_layer_id(1_000_000_005).unwrap().paper_id, 120);
    }
}
