//! Modded enchantments as real Paper registry entries (1.21.1 -> 26.3).
//!
//! Mods ship data-driven enchantments under `data/<ns>/enchantment/*.json`
//! and add entries to vanilla enchantment tags under
//! `data/minecraft/tags/enchantment/*.json`. This crate reads both out of the
//! mod jars and translates the 1.21.1 definitions into the 26.3 form that
//! Paper's `Enchantment.DIRECT_CODEC` accepts. The plugin registers the result
//! into the live enchantment registry at enable, the same way modded biomes are
//! registered (`crates/cim-biome` + `BiomeRegistry`).
//!
//! Differences found by diffing the vanilla 1.21.1 data against the 26.3 data
//! (see `convert` for the rules applied):
//! - effect requirements: `"condition": "minecraft:x"` became `"type": "minecraft:x"`;
//! - damage-source tag predicates: tag ids are `#`-prefixed in 26.3;
//! - `#minecraft:enchantable/sword` was renamed `#minecraft:enchantable/melee_weapon`;
//! - attribute ids lost the `generic.` / `player.` group prefix
//!   (`minecraft:player.sneaking_speed` -> `minecraft:sneaking_speed`);
//! - effect component types unknown to 26.3 (none in vanilla 26.3 apart from
//!   the new `post_piercing_attack`, which 1.21.1 never used) are dropped;
//! - attribute modifiers naming a non-vanilla attribute (a mod's own attribute,
//!   e.g. `combat_roll:recharge`) cannot resolve on the server: those entries
//!   are dropped and logged. The layer still runs the mod's real effect.

use std::collections::BTreeMap;
use std::path::Path;

use serde_json::{json, Map, Value};

/// Effect component types 26.3 vanilla enchantment data uses. Anything else is
/// dropped (the codec would reject the whole enchantment).
pub const KNOWN_EFFECTS_263: &[&str] = &[
    "minecraft:ammo_use",
    "minecraft:armor_effectiveness",
    "minecraft:attributes",
    "minecraft:block_experience",
    "minecraft:crossbow_charge_time",
    "minecraft:crossbow_charging_sounds",
    "minecraft:damage",
    "minecraft:damage_immunity",
    "minecraft:damage_protection",
    "minecraft:equipment_drops",
    "minecraft:fishing_luck_bonus",
    "minecraft:fishing_time_reduction",
    "minecraft:hit_block",
    "minecraft:item_damage",
    "minecraft:knockback",
    "minecraft:location_changed",
    "minecraft:post_attack",
    "minecraft:post_piercing_attack",
    "minecraft:prevent_armor_change",
    "minecraft:prevent_equipment_drop",
    "minecraft:projectile_count",
    "minecraft:projectile_piercing",
    "minecraft:projectile_spawned",
    "minecraft:projectile_spread",
    "minecraft:repair_with_xp",
    "minecraft:smash_damage_per_fallen_block",
    "minecraft:tick",
    "minecraft:trident_return_acceleration",
    "minecraft:trident_sound",
    "minecraft:trident_spin_attack_strength",
];

/// Vanilla enchantment tags whose 26.3 membership is nested: the enchanting
/// table tag is `[#minecraft:non_treasure]` in 26.3 (and was the flattened
/// list in 1.21.1), so a mod enchantment added to `non_treasure` must also
/// reach `in_enchanting_table`.
const NESTED_IN: &[(&str, &str)] = &[("minecraft:non_treasure", "minecraft:in_enchanting_table")];

/// One converted enchantment: `id` is `ns:path`, `definition` the 26.3 JSON.
#[derive(Debug, Clone, PartialEq)]
pub struct EnchantDef {
    pub id: String,
    pub definition: Value,
}

/// Everything the plugin needs: converted enchantments, the enchantment tag
/// memberships (tag id -> enchantment ids), and log lines for dropped content.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Converted {
    pub enchantments: Vec<EnchantDef>,
    pub tags: BTreeMap<String, Vec<String>>,
    pub dropped: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum EnchantError {
    #[error("not a JSON object")]
    NotObject,
}

/// Translate one 1.21.1 enchantment definition into the 26.3 form. Pure: the
/// caller decides what to do with `dropped` (log lines are appended to it).
pub fn convert(id: &str, json: &Value, dropped: &mut Vec<String>) -> Result<Value, EnchantError> {
    let Value::Object(obj) = json else {
        return Err(EnchantError::NotObject);
    };
    let mut out = Map::new();
    for (key, value) in obj {
        match key.as_str() {
            "effects" => {
                let effects = convert_effects(id, value, dropped);
                out.insert(key.clone(), Value::Object(effects));
            }
            "supported_items" | "primary_items" => {
                out.insert(key.clone(), rename_item_tag(value));
            }
            _ => {
                out.insert(key.clone(), convert_node(value));
            }
        }
    }
    Ok(Value::Object(out))
}

/// Recursively apply the node-level renames: `condition` -> `type`, and
/// `#`-prefixing of damage-source tag ids.
fn convert_node(value: &Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(items.iter().map(convert_node).collect()),
        Value::Object(map) => {
            let is_damage_source = map.get("condition").and_then(Value::as_str)
                == Some("minecraft:damage_source_properties")
                || map.get("type").and_then(Value::as_str)
                    == Some("minecraft:damage_source_properties");
            let mut out = Map::new();
            for (key, v) in map {
                if key == "condition" && v.is_string() && !map.contains_key("type") {
                    out.insert("type".to_string(), convert_node(v));
                } else {
                    out.insert(key.clone(), convert_node(v));
                }
            }
            if is_damage_source {
                prefix_damage_tags(&mut out);
            }
            Value::Object(out)
        }
        other => other.clone(),
    }
}

/// `predicate.tags[].id`: 1.21.1 wrote damage-type tags without `#`; 26.3
/// writes them as tag references.
fn prefix_damage_tags(obj: &mut Map<String, Value>) {
    let Some(Value::Object(pred)) = obj.get_mut("predicate") else {
        return;
    };
    let Some(Value::Array(tags)) = pred.get_mut("tags") else {
        return;
    };
    for tag in tags.iter_mut() {
        if let Some(Value::String(id)) = tag.get_mut("id") {
            if !id.starts_with('#') {
                *id = format!("#{id}");
            }
        }
    }
}

/// `#minecraft:enchantable/sword` (1.21.1) is `#minecraft:enchantable/melee_weapon` in 26.3.
fn rename_item_tag(value: &Value) -> Value {
    match value.as_str() {
        Some("#minecraft:enchantable/sword") => json!("#minecraft:enchantable/melee_weapon"),
        _ => value.clone(),
    }
}

fn convert_effects(id: &str, value: &Value, dropped: &mut Vec<String>) -> Map<String, Value> {
    let mut out = Map::new();
    let Value::Object(effects) = value else {
        return out;
    };
    for (component, list) in effects {
        if !KNOWN_EFFECTS_263.contains(&component.as_str()) {
            dropped.push(format!(
                "{id}: effect {component} unknown to 26.3 (dropped)"
            ));
            continue;
        }
        if component != "minecraft:attributes" {
            out.insert(component.clone(), convert_node(list));
            continue;
        }
        let Value::Array(entries) = list else {
            continue;
        };
        let mut kept = Vec::new();
        for entry in entries {
            let attr = entry.get("attribute").and_then(Value::as_str).unwrap_or("");
            match attribute_263(attr) {
                Some(name) => {
                    let mut e = convert_node(entry).as_object().cloned().unwrap_or_default();
                    e.insert("attribute".to_string(), json!(name));
                    kept.push(Value::Object(e));
                }
                None => dropped.push(format!(
                    "{id}: attribute {attr} is not a vanilla attribute (effect dropped; layer keeps it)"
                )),
            }
        }
        if !kept.is_empty() {
            out.insert(component.clone(), Value::Array(kept));
        }
    }
    out
}

/// 1.21.1 `minecraft:generic.x` / `minecraft:player.x` -> 26.3 `minecraft:x`.
/// Non-vanilla namespaces resolve to `None`.
fn attribute_263(attr: &str) -> Option<String> {
    let (ns, path) = attr.split_once(':')?;
    if ns != "minecraft" {
        return None;
    }
    let path = path
        .strip_prefix("generic.")
        .or_else(|| path.strip_prefix("player."))
        .unwrap_or(path);
    Some(format!("minecraft:{path}"))
}

/// The first `supported_items` / `primary_items` value (tag `#` stripped) that
/// is not in the `minecraft` namespace.
fn foreign_item(definition: &Value) -> Option<String> {
    for key in ["supported_items", "primary_items"] {
        if let Some(v) = definition.get(key).and_then(Value::as_str) {
            let id = v.trim_start_matches('#');
            if !id.starts_with("minecraft:") {
                return Some(id.to_string());
            }
        }
    }
    None
}

/// `data/<ns>/enchantment/<name>.json` -> `ns:name`.
fn enchantment_id(path: &str) -> Option<String> {
    let rest = path.strip_prefix("data/")?;
    let (ns, rest) = rest.split_once('/')?;
    let name = rest.strip_prefix("enchantment/")?.strip_suffix(".json")?;
    if name.is_empty() || name.contains('/') {
        return None;
    }
    Some(format!("{ns}:{name}"))
}

/// `data/<ns>/tags/enchantment/<name>.json` -> `ns:name` (the tag id).
fn enchantment_tag_id(path: &str) -> Option<String> {
    let rest = path.strip_prefix("data/")?;
    let (ns, rest) = rest.split_once('/')?;
    let name = rest
        .strip_prefix("tags/enchantment/")?
        .strip_suffix(".json")?;
    if name.is_empty() || name.contains('/') {
        return None;
    }
    Some(format!("{ns}:{name}"))
}

/// Tag memberships a mod declares: `values` strings (plain ids only; nested
/// `#tag` references are not expanded and are logged by the caller).
fn tag_values(json: &Value) -> Vec<String> {
    json.get("values")
        .and_then(Value::as_array)
        .map(|vals| {
            vals.iter()
                .filter_map(|v| match v {
                    Value::String(s) => Some(s.clone()),
                    Value::Object(o) => o.get("id").and_then(Value::as_str).map(str::to_string),
                    _ => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Build the conversion from already-parsed sources (testable without jars).
///
/// `enchantments`: `(path-in-jar, json)`; `tags`: `(path-in-jar, json)`.
/// Enchantments with an unparseable or unconvertible body are dropped; tag
/// entries that name a dropped or unknown enchantment are dropped too.
pub fn build(enchantments: &[(String, Value)], tag_files: &[(String, Value)]) -> Converted {
    let mut out = Converted::default();
    for (path, json) in enchantments {
        let Some(id) = enchantment_id(path) else {
            continue;
        };
        match convert(&id, json, &mut out.dropped) {
            Ok(definition) => match foreign_item(&definition) {
                // The 26.3 item registry holds vanilla items and tags only (mod
                // items live in the layer), so the codec would reject the entry.
                Some(item) => out.dropped.push(format!(
                    "{id}: supported item {item} is not a vanilla item or tag (enchantment dropped)"
                )),
                None => out.enchantments.push(EnchantDef { id, definition }),
            },
            Err(e) => out.dropped.push(format!("{id}: {e} (enchantment dropped)")),
        }
    }
    out.enchantments.sort_by(|a, b| a.id.cmp(&b.id));
    out.enchantments.dedup_by(|a, b| a.id == b.id);
    let known: Vec<String> = out.enchantments.iter().map(|e| e.id.clone()).collect();

    for (path, json) in tag_files {
        let Some(tag) = enchantment_tag_id(path) else {
            continue;
        };
        for value in tag_values(json) {
            if value.starts_with('#') {
                out.dropped
                    .push(format!("{tag}: nested tag {value} not expanded"));
                continue;
            }
            if !known.contains(&value) {
                continue;
            }
            let members = out.tags.entry(tag.clone()).or_default();
            if !members.contains(&value) {
                members.push(value);
            }
        }
    }
    // A 26.3 nested tag reference (in_enchanting_table -> #non_treasure) needs
    // the same additions as the tag it nests.
    for (inner, outer) in NESTED_IN {
        if let Some(members) = out.tags.get(*inner).cloned() {
            let target = out.tags.entry((*outer).to_string()).or_default();
            for m in members {
                if !target.contains(&m) {
                    target.push(m);
                }
            }
        }
    }
    for members in out.tags.values_mut() {
        members.sort();
    }
    out
}

/// Read every enchantment and enchantment-tag JSON out of the mod jars in
/// `mods`. Unreadable jars and files are skipped.
pub fn load_mod_enchantments(mods: &Path) -> Converted {
    let mut enchantments = Vec::new();
    let mut tags = Vec::new();
    let Ok(dir) = std::fs::read_dir(mods) else {
        return Converted::default();
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
            let is_ench = enchantment_id(&name).is_some();
            let is_tag = enchantment_tag_id(&name).is_some();
            if !is_ench && !is_tag {
                continue;
            }
            let Ok(mut entry) = zip.by_name(&name) else {
                continue;
            };
            let mut raw = Vec::new();
            if std::io::Read::read_to_end(&mut entry, &mut raw).is_err() {
                continue;
            }
            let Ok(json) = serde_json::from_slice::<Value>(&raw) else {
                continue;
            };
            if is_ench {
                enchantments.push((name, json));
            } else {
                tags.push((name, json));
            }
        }
    }
    build(&enchantments, &tags)
}

/// The `<root>/c-imagine/enchant-defs.json` document the plugin reads.
pub fn defs_document(converted: &Converted) -> Value {
    let enchantments: Vec<Value> = converted
        .enchantments
        .iter()
        .map(|e| json!({"id": e.id, "definition": e.definition}))
        .collect();
    json!({
        "enchantments": enchantments,
        "tags": converted.tags,
        "dropped": converted.dropped,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Combat Roll 2.0.6 `data/combat_roll/enchantment/acrobat.json` (1.21.1).
    const ACROBAT: &str = r##"{
      "anvil_cost": 5,
      "description": {"translate": "enchantment.combat_roll.acrobat"},
      "effects": {"minecraft:attributes": [{
        "amount": {"type": "minecraft:linear", "base": 0.2, "per_level_above_first": 0.2},
        "attribute": "combat_roll:recharge",
        "id": "combat_roll:enchantment.acrobat",
        "operation": "add_multiplied_base"}]},
      "max_cost": {"base": 20, "per_level_above_first": 7},
      "max_level": 5,
      "min_cost": {"base": 10, "per_level_above_first": 5},
      "slots": ["chest"],
      "supported_items": "#minecraft:enchantable/chest_armor",
      "weight": 5
    }"##;

    fn parse(s: &str) -> Value {
        serde_json::from_str(s).expect("fixture parses")
    }

    #[test]
    fn acrobat_keeps_definition_and_drops_mod_attribute() {
        let mut dropped = Vec::new();
        let out = convert("combat_roll:acrobat", &parse(ACROBAT), &mut dropped).unwrap();
        assert_eq!(out["max_level"], 5);
        assert_eq!(out["weight"], 5);
        assert_eq!(out["anvil_cost"], 5);
        assert_eq!(out["min_cost"]["per_level_above_first"], 5);
        assert_eq!(out["max_cost"]["base"], 20);
        assert_eq!(out["slots"], parse(r#"["chest"]"#));
        assert_eq!(out["supported_items"], "#minecraft:enchantable/chest_armor");
        assert_eq!(
            out["description"]["translate"],
            "enchantment.combat_roll.acrobat"
        );
        // The attribute belongs to the mod: no vanilla attribute, so no entry.
        assert_eq!(out["effects"], json!({}));
        assert_eq!(dropped.len(), 1, "{dropped:?}");
        assert!(dropped[0].contains("combat_roll:recharge"));
    }

    #[test]
    fn longfooted_and_multi_roll_have_no_vanilla_effects() {
        let longfooted = parse(
            r##"{"anvil_cost":5,"description":{"translate":"x"},"effects":{"minecraft:attributes":[
              {"amount":{"type":"minecraft:linear","base":1.0,"per_level_above_first":1.0},
               "attribute":"combat_roll:distance","id":"combat_roll:enchantment.longfooted","operation":"add_value"}]},
              "max_cost":{"base":20,"per_level_above_first":10},"max_level":4,
              "min_cost":{"base":12,"per_level_above_first":8},"slots":["feet"],
              "supported_items":"#minecraft:enchantable/foot_armor","weight":3}"##,
        );
        let mut dropped = Vec::new();
        let out = convert("combat_roll:longfooted", &longfooted, &mut dropped).unwrap();
        assert_eq!(out["effects"], json!({}));
        assert_eq!(out["max_level"], 4);
        assert_eq!(dropped.len(), 1);
    }

    #[test]
    fn vanilla_attribute_loses_its_group_prefix() {
        let json = parse(
            r##"{"effects":{"minecraft:attributes":[{"amount":{"type":"minecraft:linear","base":0.15,"per_level_above_first":0.15},
              "attribute":"minecraft:player.sneaking_speed","id":"minecraft:enchantment.swift_sneak","operation":"add_value"},
              {"amount":{"type":"minecraft:linear","base":1.0,"per_level_above_first":1.0},
              "attribute":"minecraft:generic.oxygen_bonus","id":"minecraft:enchantment.respiration","operation":"add_value"}]}}"##,
        );
        let mut dropped = Vec::new();
        let out = convert("minecraft:swift_sneak", &json, &mut dropped).unwrap();
        let list = out["effects"]["minecraft:attributes"].as_array().unwrap();
        assert_eq!(list[0]["attribute"], "minecraft:sneaking_speed");
        assert_eq!(list[1]["attribute"], "minecraft:oxygen_bonus");
        assert!(dropped.is_empty());
    }

    #[test]
    fn condition_becomes_type_and_damage_tags_get_hash() {
        let json = parse(
            r##"{"effects":{"minecraft:damage_protection":[{"effect":{"type":"minecraft:add","value":1.0},
              "requirements":{"condition":"minecraft:damage_source_properties",
                "predicate":{"tags":[{"expected":false,"id":"minecraft:bypasses_invulnerability"}]}}}]}}"##,
        );
        let mut dropped = Vec::new();
        let out = convert("minecraft:protection", &json, &mut dropped).unwrap();
        let req = &out["effects"]["minecraft:damage_protection"][0]["requirements"];
        assert_eq!(req["type"], "minecraft:damage_source_properties");
        assert!(req.get("condition").is_none());
        assert_eq!(
            req["predicate"]["tags"][0]["id"],
            "#minecraft:bypasses_invulnerability"
        );
        assert_eq!(req["predicate"]["tags"][0]["expected"], false);
    }

    #[test]
    fn match_tool_condition_is_renamed_inside_effects() {
        let json = parse(
            r##"{"effects":{"minecraft:item_damage":[{"effect":{"type":"minecraft:set","value":1.0},
              "requirements":{"condition":"minecraft:match_tool","predicate":{"items":"#minecraft:enchantable/armor"}}}]}}"##,
        );
        let mut dropped = Vec::new();
        let out = convert("minecraft:unbreaking", &json, &mut dropped).unwrap();
        let req = &out["effects"]["minecraft:item_damage"][0]["requirements"];
        assert_eq!(req["type"], "minecraft:match_tool");
        assert_eq!(req["predicate"]["items"], "#minecraft:enchantable/armor");
    }

    #[test]
    fn unknown_effect_component_is_dropped_and_logged() {
        let json = parse(
            r##"{"anvil_cost":1,"effects":{"minecraft:made_up_effect":[{"effect":{"type":"minecraft:set"}}],
              "minecraft:tick":[{"effect":{"type":"minecraft:set"}}]}}"##,
        );
        let mut dropped = Vec::new();
        let out = convert("ns:thing", &json, &mut dropped).unwrap();
        assert!(out["effects"].get("minecraft:made_up_effect").is_none());
        assert!(out["effects"].get("minecraft:tick").is_some());
        assert_eq!(dropped.len(), 1);
        assert!(dropped[0].contains("minecraft:made_up_effect"));
    }

    #[test]
    fn sword_tag_becomes_melee_weapon() {
        let json = parse(
            r##"{"supported_items":"#minecraft:enchantable/sword","primary_items":"#minecraft:enchantable/sword"}"##,
        );
        let mut dropped = Vec::new();
        let out = convert("minecraft:knockback", &json, &mut dropped).unwrap();
        assert_eq!(
            out["supported_items"],
            "#minecraft:enchantable/melee_weapon"
        );
        assert_eq!(out["primary_items"], "#minecraft:enchantable/melee_weapon");
    }

    #[test]
    fn non_object_is_rejected() {
        let mut dropped = Vec::new();
        assert_eq!(
            convert("ns:x", &json!([1]), &mut dropped),
            Err(EnchantError::NotObject)
        );
    }

    #[test]
    fn build_binds_mod_tags_and_nests_into_enchanting_table() {
        let enchantments = vec![
            (
                "data/combat_roll/enchantment/acrobat.json".to_string(),
                parse(ACROBAT),
            ),
            (
                "data/combat_roll/enchantment/gone.json".to_string(),
                json!([1, 2]),
            ),
        ];
        let tags = vec![(
            "data/minecraft/tags/enchantment/non_treasure.json".to_string(),
            parse(r##"{"values":["combat_roll:multi_roll","combat_roll:acrobat"]}"##),
        )];
        let out = build(&enchantments, &tags);
        assert_eq!(out.enchantments.len(), 1);
        assert_eq!(out.enchantments[0].id, "combat_roll:acrobat");
        // multi_roll is not converted in this fixture: it is not a tag member.
        assert_eq!(
            out.tags["minecraft:non_treasure"],
            vec!["combat_roll:acrobat"]
        );
        assert_eq!(
            out.tags["minecraft:in_enchanting_table"],
            vec!["combat_roll:acrobat"]
        );
        assert!(out.dropped.iter().any(|d| d.contains("combat_roll:gone")));
    }

    #[test]
    fn nested_tag_values_are_not_expanded() {
        let tags = vec![(
            "data/minecraft/tags/enchantment/in_enchanting_table.json".to_string(),
            parse(r##"{"values":["#minecraft:non_treasure","combat_roll:acrobat"]}"##),
        )];
        let enchantments = vec![(
            "data/combat_roll/enchantment/acrobat.json".to_string(),
            parse(ACROBAT),
        )];
        let out = build(&enchantments, &tags);
        assert_eq!(
            out.tags["minecraft:in_enchanting_table"],
            vec!["combat_roll:acrobat"]
        );
        assert!(out
            .dropped
            .iter()
            .any(|d| d.contains("#minecraft:non_treasure")));
    }

    #[test]
    fn document_shape_matches_plugin_reader() {
        let enchantments = vec![(
            "data/combat_roll/enchantment/acrobat.json".to_string(),
            parse(ACROBAT),
        )];
        let out = build(&enchantments, &[]);
        let doc = defs_document(&out);
        assert_eq!(doc["enchantments"][0]["id"], "combat_roll:acrobat");
        assert_eq!(doc["enchantments"][0]["definition"]["max_level"], 5);
        assert!(doc["tags"].as_object().unwrap().is_empty());
    }

    #[test]
    fn load_reads_jar_entries() {
        use std::io::Write;
        let dir = std::env::temp_dir().join(format!("cim-enchant-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("combat_roll-test.jar");
        {
            let file = std::fs::File::create(&jar).unwrap();
            let mut zw = zip::ZipWriter::new(file);
            let opts = zip::write::SimpleFileOptions::default();
            zw.start_file("data/combat_roll/enchantment/acrobat.json", opts)
                .unwrap();
            zw.write_all(ACROBAT.as_bytes()).unwrap();
            zw.start_file("data/minecraft/tags/enchantment/non_treasure.json", opts)
                .unwrap();
            zw.write_all(br##"{"values":["combat_roll:acrobat"]}"##)
                .unwrap();
            zw.start_file("assets/combat_roll/lang/en_us.json", opts)
                .unwrap();
            zw.write_all(b"{}").unwrap();
            zw.finish().unwrap();
        }
        let out = load_mod_enchantments(&dir);
        assert_eq!(out.enchantments.len(), 1);
        assert_eq!(
            out.tags["minecraft:non_treasure"],
            vec!["combat_roll:acrobat"]
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Smoke test against a real mods directory: `CIM_ENCHANT_MODS=/path/to/mods
    /// cargo test -p cim-enchant real_mods_dir`. Skipped when unset (CI has no mods).
    #[test]
    fn real_mods_dir_if_set() {
        let Some(dir) = std::env::var_os("CIM_ENCHANT_MODS") else {
            return;
        };
        let out = load_mod_enchantments(Path::new(&dir));
        let acrobat = out
            .enchantments
            .iter()
            .find(|e| e.id == "combat_roll:acrobat");
        assert!(
            acrobat.is_some(),
            "combat_roll:acrobat converted: {:?}",
            out.dropped
        );
        assert!(out.tags["minecraft:non_treasure"].len() >= 3);
        eprintln!(
            "enchantments: {:?}",
            out.enchantments.iter().map(|e| &e.id).collect::<Vec<_>>()
        );
        eprintln!("tags: {:?}", out.tags);
        eprintln!("dropped: {:?}", out.dropped);
    }

    #[test]
    fn mod_items_drop_the_enchantment() {
        let enchantments = vec![
            (
                "data/create/enchantment/potato_recovery.json".to_string(),
                parse(
                    r##"{"anvil_cost":1,"description":{"translate":"x"},"effects":{},"max_cost":{"base":45,"per_level_above_first":15},"max_level":3,"min_cost":{"base":15,"per_level_above_first":15},"slots":["mainhand"],"supported_items":"create:potato_cannon","weight":10}"##,
                ),
            ),
            (
                "data/create/enchantment/capacity.json".to_string(),
                parse(
                    r##"{"anvil_cost":1,"description":{"translate":"x"},"max_cost":{"base":45,"per_level_above_first":15},"max_level":3,"min_cost":{"base":15,"per_level_above_first":15},"slots":["mainhand"],"supported_items":"#create:pressurized_air_sources","weight":10}"##,
                ),
            ),
        ];
        let out = build(&enchantments, &[]);
        assert!(out.enchantments.is_empty());
        assert_eq!(out.dropped.len(), 2, "{:?}", out.dropped);
        assert!(out.dropped[1].contains("create:pressurized_air_sources"));
    }
}
