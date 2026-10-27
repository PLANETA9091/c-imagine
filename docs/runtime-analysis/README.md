# Runtime analysis — upstream sources (M0.5)

This directory is the **deep upstream analysis** the project law requires before each
milestone: every runtime we build against, analyzed from its real artifacts, with
numbers measured in this workspace (not quoted from memory). Everything here was
verified on 2026-10-26 against primary sources.

## Sources inspected (and how to re-verify)

| Source | Artifact | How it was obtained |
|---|---|---|
| Minecraft 26.3 (vanilla) | `client.jar` (41,483,720 B), `server.jar` bundler (62,294,556 B) → inner `server-26.3.jar` (26,652,008 B, 7,762 classes) | Mojang piston-meta manifest → piston-data object URLs; `version.json` extracted from client jar |
| Paper 26.3 | `paper-26.3-223.jar` (55,250,707 B) | PaperMC **fill API v3** (`api.papermc.io/v2` is retired, HTTP 410) |
| NeoForge 1.21.1 | `git clone -b 1.21.1` of `neoforged/NeoForge` (956 java files + patches) | GitHub |
| CRUSSTY engine | `git clone` of `PLANETA9091/CRUSSTY` (runtime, cplug-abi v3, SDK) | GitHub |
| F2V (reference, read-only) | `git clone` of `jefthebuilder/F2V` (718 files, ~226k lines) | GitHub (private, token access) |
| Registry dumps | `misode/mcmeta` `registries` branch (26.3) and commit `0eaeabd259` (1.21.1 release day) | raw.githubusercontent + GitHub API |

Local clones used for this analysis live in the agent workspace (`workspace/`):
`mc263-client.jar`, `mc263-server.jar`, `mc263-server-inner.jar`, `paper-26.3-223.jar`,
`f2v/`, `crussty/`, `neoforge1211/`, `reg/*.json`, `registries-*.json`,
`tree-*.json`, `mc263-version.json`, `version-1211.json`.

## Documents

1. [`minecraft-26-3.md`](minecraft-26-3.md) — vanilla 26.3: identity card, versioning
   scheme, runtime requirements, measured registries/data, what it means for us.
2. [`paper-26-3.md`](paper-26-3.md) — Paper 26.3: builds/API artifacts, what the kernel
   gives the module, lifecycle facts the boot sequence depends on.
3. [`neoforge-1-21-1.md`](neoforge-1-21-1.md) — NeoForge 21.1.x internals from source:
   runtime stack versions, mappings policy, event/network/registry surface the
   mini-NeoForge layer must reproduce.
4. [`vanilla-client-26-3.md`](vanilla-client-26-3.md) — vanilla client 26.3: what F2V v2
   can rely on (display entities, item components, maps, packs), decompile plan.
5. [`cross-version-gap.md`](cross-version-gap.md) — the 1.21.1 ↔ 26.3 delta, measured:
   protocol/DataVersion/pack formats, registry-by-registry diff, implications.
6. [`f2v2-client-capabilities.md`](f2v2-client-capabilities.md) — the F2V 2.0 visual
   surface measured in the 26.3 client (post-effect channel, item model
   definitions, equipment/atlas/font registries) and what the pack builder emits.
7. [`common-packet-drift-767-777.md`](common-packet-drift-767-777.md) — the common
   packet family 767 ↔ 777 (bytecode-proven tables) incl. the wire format of
   `ClientboundPostEffectsPacket`; the M4 translation policy rows.

## Headline facts (TL;DR)

- **1.21.1**: protocol `767`, DataVersion `3955`, datapack v48, resourcepack v34,
  Java 21, released 2024-08-08.
- **26.3**: protocol `777`, DataVersion `5023`, datapack v121.0, resourcepack v97.1,
  **Java 25** (`java-runtime-epsilon`), released 2026-09-15. Paper 26.3 build 223
  (2026-10-25) exists and is the integration target; `paper-api` artifact
  `26.3.build.223-stable` is on `repo.papermc.io`.
- The versioning scheme changed from `1.x.y` to **year-based drops** (`26.1`, `26.2`,
  `26.3`; `26.4-snapshot-5` already in flight as of 2026-10-20). The layer pins
  **26.3 exactly**; Paper API artifacts track `26.3.build.N`.
- Registry delta (measured, full table in `cross-version-gap.md`): blocks
  **1060 → 1294**, items **1333 → 1668**, entity types **130 → 163**,
  data component types **57 → 122**, sound events **1611 → 2040**; **69 registries
  added**, 14 removed; the `fluid` registry **shrank** (`flowing_*` entries gone).
- NeoForge 1.21.1 ships mods in **official Mojang mappings** (no reobf pipeline exists
  anywhere in the NeoForge build) — the relocation story in `ARCHITECTURE.md §4.2`
  is a pure mechanical rename, which is what makes the shadow-namespace approach viable.
- F2V v2 prerequisites are all present in 26.3: `block_display`/`item_display`/
  `text_display` entity types, `custom_model_data` + new `item_model` components,
  `filled_map`, server-push resource packs.
