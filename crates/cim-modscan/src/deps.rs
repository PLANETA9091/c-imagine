//! Mandatory-dependency graph → deterministic load order (Kahn's algorithm).
//!
//! Built-in ids (`minecraft`, `neoforge`, `java`, `fml`) are treated as
//! provided by the layer and never require a jar. Ordering is total: ties are
//! broken lexicographically by mod id so the same set of jars always loads in
//! the same order on every machine — a requirement for reproducible
//! registration ids (cim-registry) and event delivery (cim-bus).

use crate::scanner::ScannedMod;

/// Ids the layer itself provides.
pub const BUILTIN_MOD_IDS: [&str; 4] = ["minecraft", "neoforge", "java", "fml"];

/// Dependency resolution failure with enough context to fix the modpack.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum DepsError {
    #[error("missing required dependency {missing} (required by {required_by})")]
    Missing {
        missing: String,
        required_by: String,
    },
    #[error("dependency cycle: {0}")]
    Cycle(String),
    #[error("duplicate mod id {0}")]
    Duplicate(String),
}

/// Compute the load order: prerequisite-first list of mod ids.
pub fn build_load_order(mods: &[ScannedMod]) -> Result<Vec<String>, DepsError> {
    // id -> edges (ids this mod depends on, mandatory only).
    let mut ids: Vec<String> = Vec::new();
    let mut edges: Vec<(String, String)> = Vec::new(); // (mod, dependency)
    let mut seen = std::collections::BTreeSet::new();

    for m in mods {
        for id in m.mod_ids() {
            if !seen.insert(id.clone()) {
                return Err(DepsError::Duplicate(id));
            }
            ids.push(id);
        }
    }
    ids.sort();

    for m in mods {
        let Some(toml) = &m.toml else { continue };
        let declared = m.mod_ids();
        for (owner, dep) in toml.mandatory_dependencies() {
            if BUILTIN_MOD_IDS.contains(&dep.mod_id.as_str()) {
                continue;
            }
            if !ids.contains(&dep.mod_id) {
                return Err(DepsError::Missing {
                    missing: dep.mod_id,
                    required_by: owner,
                });
            }
            for owner_id in &declared {
                if *owner_id == owner {
                    edges.push((owner.clone(), dep.mod_id.clone()));
                }
            }
        }
    }

    // Kahn's algorithm with a lexicographic ready set.
    use std::collections::{BTreeSet, HashMap, HashSet};
    let mut remaining_deps: HashMap<&str, HashSet<&str>> = HashMap::new();
    let mut dependents: HashMap<&str, Vec<&str>> = HashMap::new();
    for id in &ids {
        remaining_deps.insert(id.as_str(), HashSet::new());
    }
    for (mod_id, dep) in &edges {
        remaining_deps
            .entry(mod_id.as_str())
            .or_default()
            .insert(dep.as_str());
        dependents
            .entry(dep.as_str())
            .or_default()
            .push(mod_id.as_str());
    }

    let mut ready: BTreeSet<&str> = ids
        .iter()
        .filter(|id| {
            remaining_deps
                .get(id.as_str())
                .map(|s| s.is_empty())
                .unwrap_or(true)
        })
        .map(|id| id.as_str())
        .collect();
    let mut order = Vec::with_capacity(ids.len());
    let mut placed: HashSet<&str> = HashSet::new();

    while let Some(next) = ready.pop_first() {
        order.push(next.to_string());
        placed.insert(next);
        if let Some(waiters) = dependents.get(next) {
            for waiter in waiters.clone() {
                if let Some(deps) = remaining_deps.get_mut(waiter) {
                    deps.remove(next);
                    if deps.is_empty() && !placed.contains(waiter) {
                        ready.insert(waiter);
                    }
                }
            }
        }
    }

    if order.len() != ids.len() {
        let stuck: Vec<String> = ids
            .iter()
            .filter(|id| !placed.contains(id.as_str()))
            .cloned()
            .collect();
        // Find an actual cycle path for the error message.
        let cycle = find_cycle(&edges, &stuck);
        return Err(DepsError::Cycle(
            cycle.unwrap_or_else(|| stuck.join(" -> ")),
        ));
    }
    Ok(order)
}

fn find_cycle(edges: &[(String, String)], stuck: &[String]) -> Option<String> {
    let stuck_set: std::collections::HashSet<&String> = stuck.iter().collect();
    let mut adj: std::collections::HashMap<&String, Vec<&String>> =
        std::collections::HashMap::new();
    for (from, to) in edges {
        if stuck_set.contains(from) && stuck_set.contains(to) {
            adj.entry(from).or_default().push(to);
        }
    }
    let mut visited = std::collections::HashSet::new();
    for start in stuck {
        let mut path: Vec<&String> = Vec::new();
        let mut on_path = std::collections::HashSet::new();
        let mut node = start;
        loop {
            if on_path.contains(node) {
                let pos = path.iter().position(|n| *n == node).unwrap_or(0);
                let cycle: Vec<String> = path[pos..].iter().map(|s| (*s).clone()).collect();
                return Some(cycle.join(" -> "));
            }
            if !visited.insert(node) {
                return None;
            }
            on_path.insert(node);
            path.push(node);
            match adj.get(node).and_then(|v| v.first()) {
                Some(next) => node = next,
                None => break,
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modstoml::{parse_mods_toml, Dependency, ModEntry, ModsToml};

    fn mod_with_deps(id: &str, deps: &[(&str, bool, Option<&str>)]) -> ScannedMod {
        let mut toml = ModsToml {
            mod_loader: Some("javafml".into()),
            loader_version: None,
            license: None,
            mods: vec![ModEntry {
                mod_id: id.to_string(),
                version: None,
                display_name: None,
                description: None,
                side: None,
            }],
            dependencies: std::collections::HashMap::new(),
        };
        let list: Vec<Dependency> = deps
            .iter()
            .map(|(dep, mandatory, side)| Dependency {
                mod_id: dep.to_string(),
                version_range: None,
                mandatory: *mandatory,
                ordering: None,
                side: side.map(String::from),
            })
            .collect();
        toml.dependencies.insert(id.to_string(), list);
        ScannedMod {
            toml: Some(toml),
            ..ScannedMod::default()
        }
    }

    #[test]
    fn dependency_chain_orders_prerequisites_first() {
        // c -> b -> a
        let mods = vec![
            mod_with_deps("c", &[("b", true, None)]),
            mod_with_deps("b", &[("a", true, None)]),
            mod_with_deps("a", &[]),
        ];
        let order = build_load_order(&mods).unwrap();
        assert_eq!(order, ["a", "b", "c"]);
    }

    #[test]
    fn diamond_resolves_deterministically() {
        let mods = vec![
            mod_with_deps("top", &[("left", true, None), ("right", true, None)]),
            mod_with_deps("left", &[("base", true, None)]),
            mod_with_deps("right", &[("base", true, None)]),
            mod_with_deps("base", &[]),
        ];
        let order = build_load_order(&mods).unwrap();
        assert_eq!(order, ["base", "left", "right", "top"]);
    }

    #[test]
    fn missing_dependency_is_reported() {
        let mods = vec![mod_with_deps("lonely", &[("ghost", true, None)])];
        let err = build_load_order(&mods).unwrap_err();
        assert_eq!(
            err,
            DepsError::Missing {
                missing: "ghost".into(),
                required_by: "lonely".into()
            }
        );
    }

    #[test]
    fn builtin_ids_never_require_a_jar() {
        let mods = vec![mod_with_deps(
            "plain",
            &[("neoforge", true, None), ("minecraft", true, None)],
        )];
        let order = build_load_order(&mods).unwrap();
        assert_eq!(order, ["plain"]);
    }

    #[test]
    fn client_side_required_dependency_is_ignored() {
        let mods = vec![mod_with_deps(
            "server_mod",
            &[("clientonly", true, Some("CLIENT"))],
        )];
        let order = build_load_order(&mods).unwrap();
        assert_eq!(order, ["server_mod"]);
    }

    #[test]
    fn cycle_is_detected_with_path() {
        let mods = vec![
            mod_with_deps("a", &[("b", true, None)]),
            mod_with_deps("b", &[("a", true, None)]),
        ];
        let err = build_load_order(&mods).unwrap_err();
        assert!(matches!(err, DepsError::Cycle(_)), "got {err:?}");
    }

    #[test]
    fn duplicate_mod_id_fails() {
        let mods = vec![mod_with_deps("dup", &[]), mod_with_deps("dup", &[])];
        assert_eq!(
            build_load_order(&mods).unwrap_err(),
            DepsError::Duplicate("dup".into())
        );
    }

    #[test]
    fn parse_real_mods_toml_and_order() {
        let toml_bytes = br#"
[[mods]]
modId = "cimtest"
[[dependencies.cimtest]]
modId = "neoforge"
type = "required"
"#;
        let parsed = parse_mods_toml(toml_bytes).unwrap();
        let scanned = ScannedMod {
            toml: Some(parsed),
            ..ScannedMod::default()
        };
        assert_eq!(build_load_order(&[scanned]).unwrap(), ["cimtest"]);
    }
}
