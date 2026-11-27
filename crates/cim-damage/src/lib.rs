//! Modded damage types as real Paper registry entries (1.21.1 -> 26.3).
//!
//! Mods ship data-driven damage types under `data/<ns>/damage_type/*.json`
//! (Create's crushing wheels and fans, Biomes O' Plenty's bramble and
//! fumarole, Dynamic Trees' falling tree). The server must know the damage
//! type before a mod hurts a player with it, and the death message the
//! client shows is `death.attack.<message_id>` from the pack lang.
//!
//! This crate reads those files out of the mod jars and converts them to the
//! 26.3 form `DamageType.DIRECT_CODEC` accepts. The plugin registers the result
//! into Paper's live damage type registry at enable, the same way the modded
//! enchantments are registered (`crates/cim-enchant` + `EnchantRegistry`).
//!
//! Differences checked against the 26.3 damage type schema: the fields are the
//! same in 1.21.1 and 26.3 (`message_id`, `scaling`, `exhaustion`, optional
//! `effects` and `death_message_type`). The converter keeps those fields,
//! validates their values, and drops anything else (logged). It still needs a
//! real 26.3 boot to confirm the codec accepts every converted entry.

use std::path::Path;

use serde_json::{json, Map, Value};

/// `DamageScaling` names (the same set in 1.21.1 and 26.3).
const SCALING: &[&str] = &["never", "when_caused_by_living_non_player", "always"];

/// `DamageEffects` names.
const EFFECTS: &[&str] = &[
    "hurt", "thorns", "drowning", "burning", "poking", "freezing",
];

/// `DeathMessageType` names.
const DEATH_MESSAGE_TYPE: &[&str] = &["default", "fall_variants", "intentional_game_design"];

/// Fields a damage type definition may carry. Anything else is dropped.
const FIELDS: &[&str] = &[
    "message_id",
    "scaling",
    "exhaustion",
    "effects",
    "death_message_type",
];

/// One converted damage type: `id` is `ns:path`, `definition` the 26.3 JSON.
#[derive(Debug, Clone, PartialEq)]
pub struct DamageDef {
    pub id: String,
    pub definition: Value,
}

/// Everything read from the mods: converted damage types in jar order, and
/// one line per entry that was dropped or skipped.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct Converted {
    pub damage_types: Vec<DamageDef>,
    pub dropped: Vec<String>,
}

/// Convert one 1.21.1 damage type. `Err` carries the reason it was dropped.
pub fn convert(id: &str, json: &Value) -> Result<Value, String> {
    let obj = json
        .as_object()
        .ok_or_else(|| format!("{id}: not a JSON object"))?;
    let message_id = obj
        .get("message_id")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| format!("{id}: message_id missing"))?;
    let scaling = obj
        .get("scaling")
        .and_then(Value::as_str)
        .ok_or_else(|| format!("{id}: scaling missing"))?;
    if !SCALING.contains(&scaling) {
        return Err(format!("{id}: scaling {scaling} unknown"));
    }
    let exhaustion = obj
        .get("exhaustion")
        .and_then(Value::as_f64)
        .filter(|e| e.is_finite() && *e >= 0.0)
        .ok_or_else(|| format!("{id}: exhaustion missing or negative"))?;

    let mut out = Map::new();
    out.insert("message_id".into(), json!(message_id));
    out.insert("scaling".into(), json!(scaling));
    out.insert("exhaustion".into(), json!(exhaustion));
    if let Some(effects) = obj.get("effects") {
        match effects.as_str() {
            Some(e) if EFFECTS.contains(&e) => {
                out.insert("effects".into(), json!(e));
            }
            _ => return Err(format!("{id}: effects {effects} unknown")),
        }
    }
    if let Some(kind) = obj.get("death_message_type") {
        match kind.as_str() {
            Some(k) if DEATH_MESSAGE_TYPE.contains(&k) => {
                out.insert("death_message_type".into(), json!(k));
            }
            _ => return Err(format!("{id}: death_message_type {kind} unknown")),
        }
    }
    // Fields outside FIELDS are not copied; build() logs them.
    Ok(Value::Object(out))
}

/// Convert a set of `(id, json)` pairs. The first definition of an id wins;
/// a later duplicate (another jar) is logged and skipped.
pub fn build(types: &[(String, Value)]) -> Converted {
    let mut out = Converted::default();
    for (id, json) in types {
        if out.damage_types.iter().any(|d| &d.id == id) {
            out.dropped
                .push(format!("{id}: duplicate, first definition kept"));
            continue;
        }
        let extra: Vec<&String> = json
            .as_object()
            .map(|o| o.keys().filter(|k| !FIELDS.contains(&k.as_str())).collect())
            .unwrap_or_default();
        if !extra.is_empty() {
            out.dropped.push(format!("{id}: fields dropped {extra:?}"));
        }
        match convert(id, json) {
            Ok(definition) => out.damage_types.push(DamageDef {
                id: id.clone(),
                definition,
            }),
            Err(why) => out.dropped.push(why),
        }
    }
    out
}

/// The `data/<ns>/damage_type/<path>.json` entry name as `ns:path`.
pub fn damage_type_id(entry: &str) -> Option<String> {
    let rest = entry.strip_prefix("data/")?;
    let (ns, tail) = rest.split_once('/')?;
    let path = tail.strip_prefix("damage_type/")?.strip_suffix(".json")?;
    if ns.is_empty() || path.is_empty() || path.contains('/') {
        return None;
    }
    Some(format!("{ns}:{path}"))
}

/// Read every damage type JSON out of the mod jars in `mods`. Unreadable jars
/// and files are skipped.
pub fn load_mod_damage_types(mods: &Path) -> Converted {
    let mut types = Vec::new();
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
            let Some(id) = damage_type_id(&name) else {
                continue;
            };
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
            types.push((id, json));
        }
    }
    build(&types)
}

/// The `<root>/c-imagine/damage-defs.json` document the plugin reads:
/// `{"damage_types":[{"id","definition"}], "dropped":[...]}`.
pub fn defs_document(converted: &Converted) -> Value {
    let types: Vec<Value> = converted
        .damage_types
        .iter()
        .map(|d| json!({"id": d.id, "definition": d.definition}))
        .collect();
    json!({
        "damage_types": types,
        "dropped": converted.dropped,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Create 6.0.10 `data/create/damage_type/mechanical_saw.json` (1.21.1).
    const SAW: &str = r#"{
  "exhaustion": 0.0,
  "message_id": "create.mechanical_saw",
  "scaling": "when_caused_by_living_non_player"
}"#;

    /// Create 6.0.10 `data/create/damage_type/fan_fire.json` (1.21.1).
    const FAN_FIRE: &str = r#"{
  "effects": "burning",
  "exhaustion": 0.0,
  "message_id": "create.fan_fire",
  "scaling": "when_caused_by_living_non_player"
}"#;

    fn parse(s: &str) -> Value {
        serde_json::from_str(s).unwrap()
    }

    #[test]
    fn plain_definition_is_kept() {
        let out = build(&[("create:mechanical_saw".into(), parse(SAW))]);
        assert_eq!(out.damage_types.len(), 1, "{:?}", out.dropped);
        let def = &out.damage_types[0].definition;
        assert_eq!(def["message_id"], "create.mechanical_saw");
        assert_eq!(def["scaling"], "when_caused_by_living_non_player");
        assert_eq!(def["exhaustion"].as_f64(), Some(0.0));
        assert!(def.get("effects").is_none());
    }

    #[test]
    fn effects_are_kept_when_known() {
        let out = build(&[("create:fan_fire".into(), parse(FAN_FIRE))]);
        assert_eq!(out.damage_types[0].definition["effects"], "burning");
    }

    #[test]
    fn unknown_effects_drop_the_entry() {
        let json = parse(
            r#"{"message_id":"x.y","scaling":"always","exhaustion":0.1,"effects":"sparkle"}"#,
        );
        let out = build(&[("x:y".into(), json)]);
        assert!(out.damage_types.is_empty());
        assert!(out.dropped[0].contains("effects"), "{:?}", out.dropped);
    }

    #[test]
    fn missing_message_id_is_rejected() {
        let json = parse(r#"{"scaling":"always","exhaustion":0.1}"#);
        let out = build(&[("x:y".into(), json)]);
        assert!(out.damage_types.is_empty());
        assert!(out.dropped[0].contains("message_id"));
    }

    #[test]
    fn negative_exhaustion_is_rejected() {
        let json = parse(r#"{"message_id":"a","scaling":"always","exhaustion":-1.0}"#);
        assert!(convert("a:b", &json).is_err());
    }

    #[test]
    fn unknown_fields_are_dropped_and_logged() {
        let json = parse(r#"{"message_id":"a","scaling":"never","exhaustion":0.0,"tags":["x"]}"#);
        let out = build(&[("a:b".into(), json)]);
        assert_eq!(out.damage_types.len(), 1);
        assert!(out.dropped.iter().any(|d| d.contains("fields dropped")));
        assert!(out.damage_types[0].definition.get("tags").is_none());
    }

    #[test]
    fn duplicate_id_keeps_the_first() {
        let a = parse(SAW);
        let b = parse(FAN_FIRE);
        let out = build(&[("create:x".into(), a.clone()), ("create:x".into(), b)]);
        assert_eq!(out.damage_types.len(), 1);
        assert_eq!(
            out.damage_types[0].definition,
            convert("create:x", &a).unwrap()
        );
        assert!(out.dropped[0].contains("duplicate"));
    }

    #[test]
    fn entry_name_maps_to_id() {
        assert_eq!(
            damage_type_id("data/create/damage_type/mechanical_saw.json").as_deref(),
            Some("create:mechanical_saw")
        );
        assert_eq!(damage_type_id("data/create/damage_type/a/b.json"), None);
        assert_eq!(damage_type_id("data/create/enchantment/x.json"), None);
        assert_eq!(damage_type_id("data/create/damage_type/x.txt"), None);
    }

    #[test]
    fn document_shape_matches_plugin_reader() {
        let out = build(&[("create:mechanical_saw".into(), parse(SAW))]);
        let doc = defs_document(&out);
        let first = &doc["damage_types"][0];
        assert_eq!(first["id"], "create:mechanical_saw");
        assert_eq!(first["definition"]["message_id"], "create.mechanical_saw");
        assert!(doc["dropped"].is_array());
    }

    #[test]
    fn load_reads_jar_entries() {
        use std::io::Write;
        let dir = std::env::temp_dir().join(format!("cim-damage-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("create-test.jar");
        {
            let file = std::fs::File::create(&jar).unwrap();
            let mut zw = zip::ZipWriter::new(file);
            let opts = zip::write::SimpleFileOptions::default();
            zw.start_file("data/create/damage_type/mechanical_saw.json", opts)
                .unwrap();
            zw.write_all(SAW.as_bytes()).unwrap();
            zw.start_file("data/create/damage_type/fan_fire.json", opts)
                .unwrap();
            zw.write_all(FAN_FIRE.as_bytes()).unwrap();
            zw.start_file("assets/create/lang/en_us.json", opts)
                .unwrap();
            zw.write_all(b"{}").unwrap();
            zw.finish().unwrap();
        }
        let out = load_mod_damage_types(&dir);
        assert_eq!(out.damage_types.len(), 2);
        assert!(out.damage_types.iter().any(|d| d.id == "create:fan_fire"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Smoke test against a real mods directory:
    /// `CIM_DAMAGE_MODS=/path/to/mods cargo test -p cim-damage real_mods_dir`.
    #[test]
    fn real_mods_dir_if_set() {
        let Some(dir) = std::env::var_os("CIM_DAMAGE_MODS") else {
            return;
        };
        let out = load_mod_damage_types(Path::new(&dir));
        eprintln!(
            "damage types: {:?}",
            out.damage_types.iter().map(|d| &d.id).collect::<Vec<_>>()
        );
        eprintln!("dropped: {:?}", out.dropped);
        assert!(out.damage_types.iter().any(|d| d.id == "create:crush"));
    }
}
