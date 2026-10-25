//! Modded→vanilla visual replacement tables.
//!
//! Every modded entity/block/item that a vanilla client must render gets a
//! **replacement**: a vanilla registry key plus presentation hints (scale,
//! tint). Mappings are pure data — the M4 net hooks look them up per packet.
//!
//! Defaults below follow F2V v1's conservative stand-ins: unknown entities
//! render as their closest vanilla analogue; blocks fall back to a
//! full-cube of a similar material until M6 ships model extraction.

use cim_core::CimError;
use cim_core::ResourceLocation;
use std::collections::HashMap;

/// How a modded thing renders on a vanilla client.
#[derive(Debug, Clone, PartialEq)]
pub struct VisualReplacement {
    /// Vanilla registry key to render instead (`minecraft:zombie` etc.).
    pub vanilla: ResourceLocation,
    /// Uniform render scale (1.0 = vanilla proportions).
    pub scale: f32,
    /// Optional ARGB tint (alpha 0 = no tint).
    pub tint: Option<u32>,
}

impl VisualReplacement {
    pub fn new(vanilla: &str, scale: f32, tint: Option<u32>) -> Result<Self, CimError> {
        Ok(Self {
            vanilla: ResourceLocation::parse(vanilla)?,
            scale,
            tint,
        })
    }
}

/// Entity replacements keyed by modded registry key (`modid:entity_name`).
#[derive(Debug, Default)]
pub struct EntityMappings {
    by_modded: HashMap<String, VisualReplacement>,
}

/// Block replacements keyed by modded registry key.
#[derive(Debug, Default)]
pub struct BlockMappings {
    by_modded: HashMap<String, VisualReplacement>,
}

impl EntityMappings {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register/replace the mapping for a modded entity.
    pub fn set(
        &mut self,
        modded_key: &str,
        replacement: VisualReplacement,
    ) -> Result<(), CimError> {
        // Validate the modded key shape as a resource location too.
        ResourceLocation::parse(modded_key)?;
        self.by_modded.insert(modded_key.to_string(), replacement);
        Ok(())
    }

    pub fn get(&self, modded_key: &str) -> Option<&VisualReplacement> {
        self.by_modded.get(modded_key)
    }

    pub fn len(&self) -> usize {
        self.by_modded.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_modded.is_empty()
    }

    /// Iterate (key, replacement) pairs; consumed by plan building.
    pub fn iter_items(&self) -> impl Iterator<Item = (&str, &VisualReplacement)> {
        self.by_modded.iter().map(|(k, v)| (k.as_str(), v))
    }

    /// Conservative defaults: F2V v1 stand-ins for the test mod's content.
    /// Real discovery feeds these tables from M4 onward.
    pub fn with_test_defaults() -> Self {
        let mut m = Self::new();
        let _ = m.set(
            "cimtest:test_entity",
            VisualReplacement::new("minecraft:iron_golem", 0.5, None)
                .expect("static mapping is valid"),
        );
        m
    }
}

impl BlockMappings {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set(
        &mut self,
        modded_key: &str,
        replacement: VisualReplacement,
    ) -> Result<(), CimError> {
        ResourceLocation::parse(modded_key)?;
        self.by_modded.insert(modded_key.to_string(), replacement);
        Ok(())
    }

    pub fn get(&self, modded_key: &str) -> Option<&VisualReplacement> {
        self.by_modded.get(modded_key)
    }

    pub fn len(&self) -> usize {
        self.by_modded.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_modded.is_empty()
    }

    pub fn iter_items(&self) -> impl Iterator<Item = (&str, &VisualReplacement)> {
        self.by_modded.iter().map(|(k, v)| (k.as_str(), v))
    }

    pub fn with_test_defaults() -> Self {
        let mut m = Self::new();
        let _ = m.set(
            "cimtest:test_block",
            VisualReplacement::new("minecraft:iron_block", 1.0, None)
                .expect("static mapping is valid"),
        );
        m
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replacement_validates_keys() {
        assert!(VisualReplacement::new("minecraft:pig", 1.0, None).is_ok());
        assert!(VisualReplacement::new("bad key", 1.0, None).is_err());
        assert!(VisualReplacement::new("minecraft:pig", 1.0, Some(0x80FF0000)).is_ok());
    }

    #[test]
    fn entity_mappings_set_get() {
        let mut m = EntityMappings::new();
        assert!(m.is_empty());
        m.set(
            "cimtest:test_entity",
            VisualReplacement::new("minecraft:zombie", 0.9, None).unwrap(),
        )
        .unwrap();
        assert_eq!(m.len(), 1);
        assert_eq!(
            m.get("cimtest:test_entity").unwrap().vanilla.to_string(),
            "minecraft:zombie"
        );
        assert!(m.get("cimtest:missing").is_none());
        // Bad modded key rejected.
        assert!(m
            .set(
                "Not A Key",
                VisualReplacement::new("minecraft:pig", 1.0, None).unwrap()
            )
            .is_err());
    }

    #[test]
    fn block_mappings_set_get() {
        let mut m = BlockMappings::new();
        m.set(
            "cimtest:test_block",
            VisualReplacement::new("minecraft:iron_block", 1.0, None).unwrap(),
        )
        .unwrap();
        assert_eq!(
            m.get("cimtest:test_block").unwrap().vanilla.to_string(),
            "minecraft:iron_block"
        );
    }

    #[test]
    fn test_defaults_cover_cimtest_content() {
        let e = EntityMappings::with_test_defaults();
        let b = BlockMappings::with_test_defaults();
        assert_eq!(
            e.get("cimtest:test_entity").unwrap().vanilla.to_string(),
            "minecraft:iron_golem"
        );
        assert_eq!(
            b.get("cimtest:test_block").unwrap().vanilla.to_string(),
            "minecraft:iron_block"
        );
    }
}
