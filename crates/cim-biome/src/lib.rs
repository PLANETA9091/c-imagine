//! Modded-biome wire channel (F2V v2, outbound).
//!
//! The layer's biomes exist only in the relocated 1.21.1 registry; Paper's
//! 26.3 world cannot hold them. Rather than teaching the server registry a
//! modded id space, this crate translates the CLIENT's view at the protocol
//! boundary — the same model F2V uses for blocks:
//!
//! 1. the biome `RegistryData` packet gets the modded biome definitions
//!    appended (1.21.1 JSON → 26.3 codec NBT), so the client's dynamic
//!    registry knows them;
//! 2. every chunk packet's biome palettes are remapped from a vanilla biome
//!    id to the modded biome that stands in for it.
//!
//! The alias (vanilla → modded) is derived from climate: each modded biome
//! claims the vanilla surface biome nearest in (temperature, downfall), one
//! to one. No datapack, no server-side registry change.

pub mod nbt;
pub mod packet;

use std::collections::{HashMap, HashSet};
use std::path::Path;

/// Packet ids (measured, `cim-proto` 777 tables). The state at encode time is
/// unknown (no connection object there), so dispatch validates the body shape
/// as well.
pub const ID_CONFIG_REGISTRY_DATA: i32 = 7;
pub const ID_PLAY_LEVEL_CHUNK: i32 = 46;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum BiomeError {
    #[error("truncated packet")]
    Truncated,
    #[error("malformed VarInt")]
    BadVarint,
    #[error("malformed string")]
    BadString,
    #[error("unsupported NBT tag {0}")]
    BadNbt(u8),
    #[error("not a biome registry packet")]
    BadRegistry,
    #[error("mod jar read failed: {0}")]
    Jar(String),
}

/// One modded biome ready to serve.
#[derive(Debug, Clone)]
pub struct BiomeDef {
    /// Namespaced id, e.g. `biomesoplenty:prairie`.
    pub name: String,
    pub temperature: f32,
    pub downfall: f32,
    /// 26.3 codec blob (unnamed NBT root) — the wire path.
    pub nbt: Vec<u8>,
    /// 26.3 definition as JSON — the server-registry path.
    pub json: serde_json::Value,
}

/// Vanilla overworld surface biomes the alias may stand in for. Oceans,
/// rivers, caves, peaks and the nether/end are deliberately absent: replacing
/// them changes water/fog colours without adding a modded surface.
const SURFACE_VANILLA: &[&str] = &[
    "plains",
    "sunflower_plains",
    "snowy_plains",
    "ice_spikes",
    "desert",
    "savanna",
    "savanna_plateau",
    "windswept_savanna",
    "forest",
    "flower_forest",
    "birch_forest",
    "old_growth_birch_forest",
    "dark_forest",
    "pale_garden",
    "jungle",
    "sparse_jungle",
    "bamboo_jungle",
    "taiga",
    "snowy_taiga",
    "old_growth_pine_taiga",
    "old_growth_spruce_taiga",
    "swamp",
    "mangrove_swamp",
    "windswept_hills",
    "windswept_gravelly_hills",
    "windswept_forest",
    "meadow",
    "cherry_grove",
    "grove",
    "snowy_slopes",
    "jagged_peaks",
    "frozen_peaks",
    "stony_peaks",
    "stony_shore",
    "beach",
    "snowy_beach",
    "badlands",
    "eroded_badlands",
    "wooded_badlands",
    "mushroom_fields",
];

/// Read every `data/<ns>/worldgen/biome/<name>.json` out of the mod jars.
pub fn load_mod_biomes(mods: &Path) -> Vec<BiomeDef> {
    let mut out = Vec::new();
    let Ok(dir) = std::fs::read_dir(mods) else {
        return out;
    };
    let mut jars: Vec<_> = dir
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "jar"))
        .collect();
    jars.sort();
    for jar in jars {
        let Ok(file) = std::fs::File::open(&jar) else {
            continue;
        };
        let Ok(mut zip) = zip::ZipArchive::new(file) else {
            continue;
        };
        let names: Vec<String> = (0..zip.len())
            .filter_map(|i| zip.by_index(i).ok().map(|f| f.name().to_string()))
            .collect();
        for name in names {
            let Some((ns, path)) = biome_path(&name) else {
                continue;
            };
            let Ok(mut entry) = zip.by_name(&name) else {
                continue;
            };
            let mut raw = Vec::new();
            if std::io::Read::read_to_end(&mut entry, &mut raw).is_err() {
                continue;
            }
            let Ok(json) = serde_json::from_slice::<serde_json::Value>(&raw) else {
                continue;
            };
            let temp = json.get("temperature").and_then(|v| v.as_f64());
            let downfall = json.get("downfall").and_then(|v| v.as_f64());
            let precip = json
                .get("has_precipitation")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            let (Some(temp), Some(downfall)) = (temp, downfall) else {
                continue;
            };
            // Overworld surface only: the modded nether/end biomes carry no
            // precipitation (and 2.0 temperature); they have no vanilla
            // surface counterpart to stand in for.
            if !precip || !(-1.0..=1.5).contains(&temp) {
                continue;
            }
            let Some(nbt) = packet::biome_blob(&json) else {
                continue;
            };
            let Some(definition) = packet::biome_json(&json) else {
                continue;
            };
            out.push(BiomeDef {
                name: format!("{ns}:{path}"),
                temperature: temp as f32,
                downfall: downfall as f32,
                nbt,
                json: definition,
            });
        }
    }
    out.sort_by(|a, b| a.name.cmp(&b.name));
    out.dedup_by(|a, b| a.name == b.name);
    out
}

/// `data/<ns>/worldgen/biome/<name>.json` → (`ns`, `name`).
fn biome_path(path: &str) -> Option<(&str, &str)> {
    let rest = path.strip_prefix("data/")?;
    let (ns, rest) = rest.split_once('/')?;
    let rest = rest.strip_prefix("worldgen/biome/")?;
    let name = rest.strip_suffix(".json")?;
    if name.is_empty() || name.contains('/') {
        return None;
    }
    Some((ns, name))
}

/// Greedy one-to-one climate match: vanilla `(name, temperature, downfall)`
/// → modded biome name.
pub fn alias(
    vanilla: &[(String, Option<f32>, Option<f32>)],
    mods: &[BiomeDef],
) -> Vec<(String, String)> {
    let mut candidates: Vec<(&str, f32, f32)> = vanilla
        .iter()
        .filter(|(name, _, _)| {
            name.strip_prefix("minecraft:")
                .is_some_and(|n| SURFACE_VANILLA.contains(&n))
        })
        .filter_map(|(name, t, d)| Some((name.as_str(), (*t)?, (*d)?)))
        .collect();
    let mut used: HashSet<usize> = HashSet::new();
    let mut out = Vec::new();
    // All pairs, nearest first; a pair survives only if both sides are free.
    let mut pairs: Vec<(f32, usize, usize)> = Vec::new();
    for (vi, (_, vt, vd)) in candidates.iter().enumerate() {
        for (mi, m) in mods.iter().enumerate() {
            let dt = vt - m.temperature;
            let dd = vd - m.downfall;
            pairs.push((dt * dt + dd * dd, vi, mi));
        }
    }
    pairs.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    let mut taken_vanilla: HashSet<usize> = HashSet::new();
    for (_, vi, mi) in pairs {
        if taken_vanilla.contains(&vi) || used.contains(&mi) {
            continue;
        }
        taken_vanilla.insert(vi);
        used.insert(mi);
        out.push((candidates[vi].0.to_string(), mods[mi].name.clone()));
    }
    // Deterministic output order.
    out.sort();
    let _ = &mut candidates;
    out
}

/// The stateful packet translator registered on the runtime's rewrite seam.
pub struct Rewriter {
    mods: Vec<BiomeDef>,
    /// vanilla registry id → modded registry id (valid after the registry
    /// packet has been translated).
    remap: Option<HashMap<u32, u32>>,
    alias: Vec<(String, String)>,
    /// One line per connection-level event, for the boot log.
    pub appends: u64,
    pub chunks: u64,
}

impl Rewriter {
    pub fn new(mods: Vec<BiomeDef>) -> Self {
        Self {
            mods,
            remap: None,
            alias: Vec::new(),
            appends: 0,
            chunks: 0,
        }
    }

    pub fn mod_count(&self) -> usize {
        self.mods.len()
    }

    pub fn alias_len(&self) -> usize {
        self.alias.len()
    }

    /// Translate one outbound payload (packet-id VarInt included).
    /// `None` = leave the bytes untouched.
    pub fn rewrite(&mut self, payload: &[u8]) -> Option<Vec<u8>> {
        let mut r = nbt::Reader::new(payload);
        let id = r.varint().ok()?;
        let body = payload.get(r.pos()..)?;
        match id {
            ID_CONFIG_REGISTRY_DATA => {
                let reg = packet::parse_registry(body).ok()??;
                let (new_body, mod_ids) = packet::append_biomes(body, &reg, &self.mods).ok()?;
                let vanilla: Vec<(String, Option<f32>, Option<f32>)> = reg
                    .entries
                    .iter()
                    .map(|e| (e.name.clone(), e.temperature, e.downfall))
                    .collect();
                let alias = alias(&vanilla, &self.mods);
                let by_name: HashMap<&str, u32> = reg
                    .entries
                    .iter()
                    .enumerate()
                    .map(|(i, e)| (e.name.as_str(), i as u32))
                    .collect();
                let mut remap = HashMap::new();
                for (vanilla_name, mod_name) in &alias {
                    let (Some(&from), Some(&to)) =
                        (by_name.get(vanilla_name.as_str()), mod_ids.get(mod_name))
                    else {
                        continue;
                    };
                    remap.insert(from, to);
                }
                if remap.is_empty() {
                    return None;
                }
                self.remap = Some(remap);
                self.alias = alias;
                self.appends += 1;
                let mut out = Vec::with_capacity(new_body.len() + 2);
                nbt::write_varint(&mut out, id);
                out.extend_from_slice(&new_body);
                Some(out)
            }
            ID_PLAY_LEVEL_CHUNK => {
                let remap = self.remap.as_ref()?;
                let new_body = packet::rewrite_chunk(body, remap)?;
                self.chunks += 1;
                let mut out = Vec::with_capacity(new_body.len() + 2);
                nbt::write_varint(&mut out, id);
                out.extend_from_slice(&new_body);
                Some(out)
            }
            _ => None,
        }
    }

    /// The current alias table (vanilla name → modded name), for logging.
    pub fn alias(&self) -> &[(String, String)] {
        &self.alias
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nbt::NbtWriter;

    fn def(name: &str, t: f32, d: f32) -> BiomeDef {
        BiomeDef {
            name: name.to_string(),
            temperature: t,
            downfall: d,
            nbt: NbtWriter::new()
                .root_compound()
                .float("temperature", t)
                .float("downfall", d)
                .end()
                .into_bytes(),
            json: serde_json::json!({"temperature": t, "downfall": d}),
        }
    }

    #[test]
    fn alias_is_one_to_one_and_prefers_climate() {
        let vanilla = vec![
            ("minecraft:plains".to_string(), Some(0.8), Some(0.4)),
            ("minecraft:taiga".to_string(), Some(0.25), Some(0.8)),
            ("minecraft:ocean".to_string(), Some(0.5), Some(0.5)),
        ];
        let mods = vec![def("mod:prairie", 0.8, 0.3), def("mod:pine", 0.2, 0.7)];
        let a = alias(&vanilla, &mods);
        assert_eq!(a.len(), 2);
        assert!(a.contains(&("minecraft:plains".into(), "mod:prairie".into())));
        assert!(a.contains(&("minecraft:taiga".into(), "mod:pine".into())));
        // oceans are never replaced
        assert!(!a.iter().any(|(v, _)| v == "minecraft:ocean"));
    }

    #[test]
    fn rewriter_extends_registry_then_rewrites_a_chunk() {
        let mods = vec![def("mod:prairie", 0.8, 0.4)];
        let mut rw = Rewriter::new(mods);
        // registry: plains(0.8/0.4) + taiga(0.25/0.8)
        let mut reg = Vec::new();
        nbt::write_varint(&mut reg, 7); // config registry id
        nbt::write_varint(&mut reg, "minecraft:worldgen/biome".len() as i32);
        reg.extend_from_slice(b"minecraft:worldgen/biome");
        nbt::write_varint(&mut reg, 2);
        for (name, t, d) in [
            ("minecraft:plains", 0.8f32, 0.4f32),
            ("minecraft:taiga", 0.25f32, 0.8f32),
        ] {
            nbt::write_varint(&mut reg, name.len() as i32);
            reg.extend_from_slice(name.as_bytes());
            reg.push(1);
            reg.extend_from_slice(
                &NbtWriter::new()
                    .root_compound()
                    .float("temperature", t)
                    .float("downfall", d)
                    .end()
                    .into_bytes(),
            );
        }
        let out = rw.rewrite(&reg).expect("registry rewritten");
        assert!(out.len() > reg.len());
        assert_eq!(rw.alias_len(), 1);
        assert_eq!(
            rw.alias()[0],
            ("minecraft:plains".to_string(), "mod:prairie".to_string())
        );

        // chunk: 24 sections, block container single air(0), biome container
        // single plains(0) -> must become the modded id (2).
        let mut buf = Vec::new();
        for _ in 0..packet::OVERWORLD_SECTIONS {
            buf.extend_from_slice(&0u16.to_be_bytes()); // nonEmpty
            buf.extend_from_slice(&0u16.to_be_bytes()); // fluid
                                                        // block: bits=0, single value 0 (26.3 writes no long array for it)
            buf.push(0);
            nbt::write_varint(&mut buf, 0);
            // biome: bits=0, single value 0
            buf.push(0);
            nbt::write_varint(&mut buf, 0);
        }
        let mut chunk = Vec::new();
        nbt::write_varint(&mut chunk, 46);
        chunk.extend_from_slice(&0i32.to_be_bytes());
        chunk.extend_from_slice(&0i32.to_be_bytes());
        nbt::write_varint(&mut chunk, 0); // no heightmaps
        nbt::write_varint(&mut chunk, buf.len() as i32);
        chunk.extend_from_slice(&buf);
        let out = rw.rewrite(&chunk).expect("chunk rewritten");
        assert_eq!(rw.chunks, 1);
        // locate the first biome single value: it must be id 2, not 0
        let mut r = nbt::Reader::new(&out);
        assert_eq!(r.varint().unwrap(), 46);
        r.i32().unwrap();
        r.i32().unwrap();
        let maps = r.varint().unwrap();
        assert_eq!(maps, 0);
        let len = r.varint().unwrap() as usize;
        let b = r.bytes(len).unwrap();
        // section: 2+2 + (1+1) block + (1+1) biome
        assert_eq!(b[4 + 2 + 1], 2);
    }
}
