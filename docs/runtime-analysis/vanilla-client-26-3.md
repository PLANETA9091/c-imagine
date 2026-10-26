# Vanilla client 26.3 — analysis for F2V v2

Analyzed from `client.jar` (41,483,720 B, Mojang piston-data) and the 26.3 registry
dumps. Measured 2026-10-26. The client is the render target of F2V v2: everything
below is either "confirmed usable by F2V v2" or "planned ground-truth extraction".

## 1. Identity card

| Field | Value |
|---|---|
| Version | 26.3 (`version.json` inside client jar) |
| Protocol | 777 |
| Resource pack format | **97.1** (`resource_major 97`, `resource_minor 1`) |
| Data pack format | 121.0 |
| Java | 25 |
| Display entity types | `block_display`, `item_display`, `text_display` — **all present** |
| Item components of interest | `custom_model_data`, `custom_data`, `custom_name`, **`item_model` (new component on the 1.21.4+ line)** |
| Map items | `map`, `filled_map` — present (GUI scene tiles keep working) |

## 2. What F2V v2 can rely on (confirmed in 26.3 registries)

These are the client-side primitives the entire F2V v2 pipeline builds on. All are
verified present in the 26.3 `entity_type` / `data_component_type` / item registries:

1. **Display entity rig** — `item_display`, `block_display`, `text_display` with
   interpolation + transforms. This is how F2V v1 renders modded blocks, contraption
   parts, and GUI surfaces (2,955-line `BlockDisplayManager` + 3,863-line
   `ContraptionManager` upstream). v2 keeps the technique, moves the orchestration
   into Rust and drives it from the CRUSSTY net pipeline.
2. **Item model override chain** — `custom_model_data` plus the newer `item_model`
   component. Pack synthesis (M6) emits resources in the **97.1** pack format
   (format moved 34 → 97.1 since 1.21.1; generated packs must be schema-targeted).
3. **Map widgets** — `filled_map` for server-authored GUI scenes (image tiles),
   cursor hit-testing stays server-side.
4. **Server resource packs** — push flow unchanged; pack schema version is the only
   visible delta.
5. **Sound events** — 2,040 in 26.3 (1,611 in 1.21.1); F2V v1 deliberately did not
   translate sounds (pack `sounds.json` handles mapping); v2 inherits that decision.

## 3. What the client must NEVER be asked to do

From F2V v1's documented invariants (inherited by v2, `F2V_ANALYSIS.md §6`):

- no client mod installation, no arbitrary client code execution;
- no client-side class loading from mod jars;
- every visual is composed **only** from vanilla-renderable primitives above;
- every translated surface ships with a verification entry (M5 visual contracts).

## 4. Ground-truth extraction plan (M1 analysis debt)

The client jar ships **without sources** and its classes are obfuscated with official
Mojang mappings published alongside (`client.txt`, proguard format, URL in the
version manifest). To answer rendering questions with facts instead of folklore, CI
will (M1, `ground-truth.yml`, cached artifact):

1. download `client.jar` + `client.txt` from the version manifest;
2. convert mappings (proguard → tiny v2 via mapping-io) and remap with
   tiny-remapper — direction note: Mojang's proguard maps **named → obf**, and the
   client jar is obf, so the remap runs `target → source` (validated locally:
   the full named client, e.g. `net/minecraft/client/Minecraft.class`, comes out);
3. decompile with Vineflower; publish the sources as a CI artifact; docs cite it.

**26.x mappings caveat (verified 2026-10-26):** Mojang stopped publishing
`client_mappings` in the 26.x version metadata (downloads carry client+server only).
Until the new distribution channel is identified, the ground-truth decompile falls
back to the newest version that still ships official mappings (1.21.1) and records
the resolved version in its artifact. Finding the 26.x mappings channel is a small
open analysis item.

The same pipeline runs against the 1.21.1 client (DataVersion 3955 mappings) so that
visual contracts (M5) can compare client behavior **across versions**, not only
against screenshots.

## 5. Versioning pressure

`26.4-snapshot-1..5` are already published (latest 2026-10-20). F2V v2 targets the
**26.3 client exactly** — the protocol smoke in `visual.yml` uses a 26.3-protocol
bot, and the M5 harness boots a real 26.3 client. Client-version bumping is a
deliberate, analyzed change (new pack schema, possibly new display semantics), never
an accidental side effect of "latest".
