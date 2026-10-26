#!/usr/bin/env python3
"""Generate c-imagine cross-version remap tables (M3.2) from mcmeta snapshots.

Sources (pinned, reproducible — full SHAs verified via the GitHub API):
  OLD = Minecraft 1.21.1  registries 0eaeabd259d09369c6fc45b810cc5a675c3c1af4
                          summary   7cffbcdad5fa36f22acef6bd4b869ecad4ca3fd9
  NEW = Minecraft 26.3    registries 2240df2376509bfaf12becbb36e156e29f8ecb4d
                          summary   d96c75fec200c4580dd75e033a76341521165461

Outputs (written to BOTH consumers so the table has a single source of truth):
  java/cim-bootstrap/src/main/resources/cim/blockstate-drift.tsv   (Java StateMapper)
  crates/cim-registry/data/blockstate-drift.tsv                    (Rust mirror, include_str!)
  java/cim-bootstrap/src/main/resources/cim/registry-drift.tsv     (registry key drift)
  crates/cim-registry/data/registry-drift.tsv
  java/cim-bootstrap/src/main/resources/cim/remap-manifest.tsv     (generation metadata)
  crates/cim-registry/data/remap-manifest.tsv
  docs/runtime-analysis/cross-version-drift-<OLD>-<NEW>.md         (human report)

Usage: gen-remap-tables.py <old-mcmeta-root> <new-mcmeta-root> <repo-root>
  <root> holds registries/<reg>/data.json and summary-blocks.json (raw download
  of the pinned summary commit's blocks/data.json; a summary/ checkout with
  blocks/data.json is accepted too).
"""
import json
import sys
import os
import datetime
from pathlib import Path

OLD_LABEL = "1.21.1"
NEW_LABEL = "26.3"
OLD_REG_COMMIT = "0eaeabd259d09369c6fc45b810cc5a675c3c1af4"
OLD_SUM_COMMIT = "7cffbcdad5fa36f22acef6bd4b869ecad4ca3fd9"
NEW_REG_COMMIT = "2240df2376509bfaf12becbb36e156e29f8ecb4d"
NEW_SUM_COMMIT = "d96c75fec200c4580dd75e033a76341521165461"

# Registries the state bridge and F2F translation actually touch.
REPORT_REGISTRIES = [
    "block", "item", "entity_type", "block_entity_type", "fluid",
    "sound_event", "particle_type", "potion", "enchantment", "menu",
    "mob_effect", "game_event", "attribute", "position_source_type",
    "villager_profession", "villager_type", "point_of_interest_type",
    "memory_module_type", "sensor_type", "schedule", "activity", "data_component_type",
]

# Explicit old->new key pairs the auto-suffix matcher cannot derive (documented
# in the generated report). Keys are NAMESPACE-FREE (mcmeta registries format).
# Values with variant-dependent targets use the DEFAULT target here; runtime
# translation may refine (e.g. boat variant -> per-wood entity type).
PAIR_OVERRIDES = {
    ("entity_type", "boat"): "oak_boat",
    ("entity_type", "chest_boat"): "oak_chest_boat",
    ("entity_type", "potion"): "splash_potion",
    ("block", "chain"): "iron_chain",
    ("item", "chain"): "iron_chain",
}
# attribute registry flattening: generic.X / player.X / zombie.X -> <tail>
FLATTEN_TAIL_REGISTRIES = {"attribute"}


def load_registry(root: Path, name: str):
    p = root / "registries" / name / "data.json"
    if not p.is_file():
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def load_blocks_summary(root: Path):
    # raw download of the pinned summary commit OR a summary/ checkout
    for p in (root / "summary-blocks.json", root / "summary/blocks/data.json"):
        if p.is_file():
            with open(p, encoding="utf-8") as f:
                return json.load(f)
    raise SystemExit(f"missing summary blocks under {root}")


def block_default_props(entry) -> dict:
    """Default-state properties (summary format: [values_map, default_map])."""
    if isinstance(entry, list):
        return dict(entry[1]) if len(entry) > 1 else {}
    idx = entry.get("default", 0)
    states = entry.get("states") or []
    if 0 <= idx < len(states):
        return states[idx].get("properties") or {}
    return {}


def block_prop_values(entry, name: str):
    """Ordered distinct values of one property across all states."""
    if isinstance(entry, list):
        return list((entry[0] or {}).get(name) or [])
    vals = []
    for st in entry.get("states") or []:
        props = st.get("properties") or {}
        v = props.get(name)
        if v is not None and v not in vals:
            vals.append(v)
    return vals


def block_prop_names(entry):
    """Sorted property names of a summary entry (both known formats)."""
    if isinstance(entry, list):
        return sorted((entry[0] or {}).keys())
    return sorted(entry.get("properties") or {})


def parse_state(s: str):
    """'minecraft:foo[a=b,c=d]' -> ('minecraft:foo', {'a':'b','c':'d'})"""
    if "[" not in s:
        return s, {}
    key, rest = s.split("[", 1)
    rest = rest.rstrip("]")
    props = {}
    if rest:
        for pair in rest.split(","):
            k, v = pair.split("=", 1)
            props[k] = v
    return key, props


def fmt_state(key: str, props: dict) -> str:
    if not props:
        return key
    inner = ",".join(f"{k}={props[k]}" for k in sorted(props))
    return f"{key}[{inner}]"


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    old_root, new_root, repo_root = (Path(a) for a in sys.argv[1:4])

    # ---- registry key-level drift -------------------------------------
    reg_drift = {}       # registry -> list of (old_key, new_key_or_"")
    reg_census = {}      # registry -> (old_count, new_count, added, removed)
    for reg in REPORT_REGISTRIES:
        old = load_registry(old_root, reg)
        new = load_registry(new_root, reg)
        if old is None or new is None:
            print(f"  ! registry {reg}: missing on one side (old={old is not None}, new={new is not None})")
            continue
        old_keys, new_keys = set(old), set(new)
        removed = sorted(old_keys - new_keys)
        added = sorted(new_keys - old_keys)
        pairs = []
        for k in removed:
            bare = k.split(":", 1)[-1]  # keys are namespace-free; tolerate ns anyway
            cand = PAIR_OVERRIDES.get((reg, bare))
            if cand is None and reg in FLATTEN_TAIL_REGISTRIES:
                tail = bare.rsplit(".", 1)[-1]
                cand = tail if tail in new_keys else None
            if cand is None:
                cand = bare if bare in new_keys else ""
            pairs.append((k, cand))
        reg_drift[reg] = pairs
        reg_census[reg] = (len(old_keys), len(new_keys), len(added), len(removed))

    # ---- blockstate property drift ------------------------------------
    old_blocks = load_blocks_summary(old_root)
    new_blocks = load_blocks_summary(new_root)

    # Rename pairs from the block registry drift get the SAME property-diff
    # comparison as shared keys (chain -> iron_chain etc.).
    block_renames = {ok: nk for ok, nk in reg_drift.get("block", []) if nk}

    def property_diff(oe, ne):
        """Drift row for a block pair, or None when property-identical."""
        oprops = block_prop_names(oe)
        nprops = block_prop_names(ne)
        oset, nset = set(oprops), set(nprops)
        removed = [{"name": p, "oldDefault": block_default_props(oe).get(p),
                    "values": block_prop_values(oe, p)} for p in oset - nset]
        added = [{"name": p, "newDefault": block_default_props(ne).get(p),
                  "values": block_prop_values(ne, p)} for p in nset - oset]
        changed = []
        for p in sorted(oset & nset):
            ov = block_prop_values(oe, p)
            nv = block_prop_values(ne, p)
            if ov != nv:
                vmap = {v: v for v in ov if v in nv}
                for v in ov:
                    if v not in nv:
                        vmap[v] = ""  # no counterpart -> new default on forward
                changed.append({"name": p, "old": ov, "new": nv, "map": vmap,
                                "oldDefault": block_default_props(oe).get(p),
                                "newDefault": block_default_props(ne).get(p)})
                stats["value_drift"] += 1
        if removed or added or changed:
            stats["prop_drift"] += 1
            return {"removed": removed, "added": added, "changed": changed}
        return None

    drift_rows = []          # (block, json_row)
    renamed_blocks = list(block_renames.items())
    stats = {"both": 0, "added": 0, "removed": 0, "prop_drift": 0, "value_drift": 0}
    for key in sorted(set(old_blocks) | set(new_blocks)):
        if key not in new_blocks:
            stats["removed"] += 1
            continue
        if key not in old_blocks:
            stats["added"] += 1
            continue
        stats["both"] += 1
        row = property_diff(old_blocks[key], new_blocks[key])
        if row:
            drift_rows.append((key, row))
    # renamed blocks: diff the PAIR (old key under old data, new key under new)
    for ok, nk in block_renames.items():
        if ok in old_blocks and nk in new_blocks:
            row = property_diff(old_blocks[ok], new_blocks[nk])
            if row:
                row["renamedTo"] = nk
                drift_rows.append((ok, row))
                # prop_drift/value_drift counters incremented inside property_diff

    # ---- write TSVs -----------------------------------------------------
    java_res = repo_root / "java/cim-bootstrap/src/main/resources/cim"
    rust_data = repo_root / "crates/cim-registry/data"
    java_res.mkdir(parents=True, exist_ok=True)
    rust_data.mkdir(parents=True, exist_ok=True)

    manifest = (f"# generated {datetime.datetime.now(datetime.UTC):%Y-%m-%dT%H:%M:%SZ} by scripts/gen-remap-tables.py\n"
                f"# old {OLD_LABEL}: registries {OLD_REG_COMMIT} summary {OLD_SUM_COMMIT}\n"
                f"# new {NEW_LABEL}: registries {NEW_REG_COMMIT} summary {NEW_SUM_COMMIT}\n")

    bsd = [manifest + "# block\tjson drift row\n"]
    for key, row in drift_rows:
        bsd.append(f"{key}\t{json.dumps(row, separators=(',', ':'), sort_keys=True)}\n")
    bsd_text = "".join(bsd)
    (java_res / "blockstate-drift.tsv").write_text(bsd_text, encoding="utf-8")
    (rust_data / "blockstate-drift.tsv").write_text(bsd_text, encoding="utf-8")

    rd = [manifest + "# registry\told_key\tnew_key (empty = removed in " + NEW_LABEL + ")\n"]
    for reg, pairs in sorted(reg_drift.items()):
        for ok, nk in pairs:
            rd.append(f"{reg}\t{ok}\t{nk}\n")
    rd_text = "".join(rd)
    (java_res / "registry-drift.tsv").write_text(rd_text, encoding="utf-8")
    (rust_data / "registry-drift.tsv").write_text(rd_text, encoding="utf-8")
    (java_res / "remap-manifest.tsv").write_text(manifest, encoding="utf-8")
    (rust_data / "remap-manifest.tsv").write_text(manifest, encoding="utf-8")

    # ---- human report ----------------------------------------------------
    docs = repo_root / "docs/runtime-analysis"
    docs.mkdir(parents=True, exist_ok=True)
    lines = [
        f"# Cross-version drift: {OLD_LABEL} -> {NEW_LABEL} (M3.2 generated report)\n",
        f"- sources: mcmeta registries {OLD_REG_COMMIT} -> {NEW_REG_COMMIT};\n"
        f"  summary {OLD_SUM_COMMIT} -> {NEW_SUM_COMMIT}\n",
        f"- generated: {datetime.datetime.now(datetime.UTC):%Y-%m-%d %H:%M:%SZ}\n",
        "\n## registry census (old -> new, added/removed keys)\n",
        "| registry | old | new | added | removed |",
        "|---|---|---|---|---|",
    ]
    for reg, (oc, nc, na, nr) in reg_census.items():
        lines.append(f"| {reg} | {oc} | {nc} | {na} | {nr} |")
    lines += [
        "\n## blockstate drift\n",
        f"- blocks present in both: {stats['both']} (added {stats['added']}, removed {stats['removed']})",
        f"- blocks with property-set drift: {stats['prop_drift']}",
        f"- individual value-set drifts: {stats['value_drift']}",
        "\n### per-block drift detail\n",
    ]
    for key, row in drift_rows:
        lines.append(f"#### {key}")
        for r in row.get("removed", []):
            lines.append(f"- removed prop `{r['name']}` (old default `{r['oldDefault']}`, values {r['values']})")
        for r in row.get("added", []):
            lines.append(f"- added prop `{r['name']}` (new default `{r['newDefault']}`, values {r['values']})")
        for r in row.get("changed", []):
            lines.append(f"- value drift `{r['name']}`: {r['old']} -> {r['new']} (map {r['map']})")
        lines.append("")
    if renamed_blocks:
        lines.append("\n## confirmed renames\n")
        for o, n in renamed_blocks:
            lines.append(f"- `{o}` -> `{n}`")
    (docs / f"cross-version-drift-{OLD_LABEL}-{NEW_LABEL}.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # ---- console summary -------------------------------------------------
    print(f"registry census: " + ", ".join(f"{r} {oc}->{nc}(+{na}/-{nr})" for r, (oc, nc, na, nr) in reg_census.items()))
    print(f"blocks: {stats['both']} shared, {stats['added']} added, {stats['removed']} removed, "
          f"{stats['prop_drift']} with prop drift, {stats['value_drift']} value drifts")
    print(f"wrote: {java_res} and {rust_data} (blockstate-drift, registry-drift, remap-manifest) + {docs}")


if __name__ == "__main__":
    main()
