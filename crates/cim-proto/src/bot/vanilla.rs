//! Frozen vanilla 26.3 registry id tables, measured by
//! `scripts/probe-registry-ids.java` against the Mojang-mapped Paper 26.3
//! server jar (the client builds the SAME static registries from its own jar
//! in the same Bootstrap order — static registries are NOT streamed via
//! registry_data, so the bot needs this table to name wire ids).

use std::collections::HashMap;
use std::sync::OnceLock;

/// id \t minecraft:key
const ENTITY_TYPES: &str = include_str!("../../data/entity-type-777.tsv");
/// state_id \t minecraft:key \t prop=v,prop=v
const BLOCK_STATES: &str = include_str!("../../data/blockstate-777.tsv");

fn entity_map() -> &'static HashMap<i32, String> {
    static MAP: OnceLock<HashMap<i32, String>> = OnceLock::new();
    MAP.get_or_init(|| {
        let mut m = HashMap::new();
        for line in ENTITY_TYPES.lines() {
            let (id, key) = line.split_once('\t').unwrap_or((line, ""));
            if let Ok(id) = id.parse::<i32>() {
                m.insert(id, key.to_string());
            }
        }
        m
    })
}

fn state_map() -> &'static HashMap<i32, (String, String)> {
    static MAP: OnceLock<HashMap<i32, (String, String)>> = OnceLock::new();
    MAP.get_or_init(|| {
        let mut m = HashMap::new();
        for line in BLOCK_STATES.lines() {
            let parts: Vec<&str> = line.splitn(3, '\t').collect();
            if parts.len() >= 2 {
                if let Ok(id) = parts[0].parse::<i32>() {
                    m.insert(
                        id,
                        (
                            parts[1].to_string(),
                            parts.get(2).copied().unwrap_or_default().to_string(),
                        ),
                    );
                }
            }
        }
        m
    })
}

/// Wire entity_type id -> `minecraft:key` (unknown -> `<unknown#id>`).
pub fn entity_type_name(id: i32) -> String {
    entity_map()
        .get(&id)
        .cloned()
        .unwrap_or_else(|| format!("<unknown#{id}>"))
}

/// Wire blockstate id -> `minecraft:key[props]` (None = unknown id).
pub fn block_state_string(id: i32) -> Option<String> {
    state_map().get(&id).map(|(key, props)| {
        if props.is_empty() {
            key.clone()
        } else {
            format!("{key}[{props}]")
        }
    })
}

pub fn entity_type_count() -> usize {
    entity_map().len()
}

pub fn block_state_count() -> usize {
    state_map().len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn oak_boat_is_present_and_161_types() {
        assert_eq!(entity_type_count(), 161);
        assert!(entity_map().values().any(|v| v == "minecraft:oak_boat"));
    }

    #[test]
    fn iron_chain_axis_y_waterlogged_false() {
        assert!(block_state_count() > 35000);
        let mut found = false;
        for id in 0..block_state_count() as i32 {
            if let Some(s) = block_state_string(id) {
                if s == "minecraft:iron_chain[axis=y,waterlogged=false]" {
                    found = true;
                    break;
                }
            }
        }
        assert!(found, "iron_chain[axis=y,waterlogged=false] missing");
    }

    #[test]
    fn air_is_state_zero() {
        assert_eq!(block_state_string(0).as_deref(), Some("minecraft:air"));
    }
}
