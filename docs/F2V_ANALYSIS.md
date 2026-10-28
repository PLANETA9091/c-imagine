# F2V analysis → c-imagine port plan

Source: private repo `jefthebuilder/F2V` (cloned read-only for analysis; **no code or
test changes are ever pushed there** — all work lands in `c-imagine`).

## 1. What F2V is

F2V is a **server-side** translation layer (vanilla clients install nothing) built as a
dual-target mod workspace:

- `forge-1.20.1` — Forge 47.4.10, Java 17 (behavioral reference implementation).
- `neoforge-1.21.1` — NeoForge 21.1.211, Java 21 (the target c-imagine mirrors).
- `shared/` — common code, must stay Java 17 compatible.

Scale: **718 Java files, ~226k lines**. It runs *inside* the modded server and rewrites
outgoing packets so vanilla clients can render modded content.

## 2. Subsystem map (measured)

| Subsystem | Location (examples) | Size | Function |
|---|---|---|---|
| Bytecode discovery | `shared/.../vm/discovery/ClientSurfaceScanner.java` | 10,867 lines | static analysis of mod bytecode: packet operands through stack ops/locals/control flow, cross-class + cross-jar linkage, recursive call graphs, no class loading |
| Renderer discovery | `shared/.../vm/discovery/RendererBytecodeScanner.java` | 5,585 lines | finds render paths, models, animation hooks from bytecode evidence |
| Resource pack builder | `*/pack/PackBuilder.java` | 4,865 / 4,817 lines | extracts models/textures (incl. OBJ), builds client resource pack + Bedrock pack foundations |
| Contraption visuals | `*/client/ContraptionManager.java` | 3,863 lines | Create: moving contraptions, trains, bogeys, sublevel transforms, interpolation |
| Block display | `*/client/BlockDisplayManager.java` | 2,955 / 2,907 lines | virtual blocks via display entities, collision proxies, LOD |
| Model capture | `*/capture/IsolatedModelCapture.java` | 3,231 / 3,223 lines | isolated model rendering/capture pipeline |
| Packet sanitation | `*/bridge/PacketSanitizer.java` | 3,055 / 2,754 lines | registry-safe chunk/item/recipe/entity/particle/movement/metadata translation |
| GUI scenes | `shared/.../gui/ClientOnlyHudSimulator.java` | 2,762 lines | server-authored HUD scenes, image tiles, cursor hit-testing, inventory bridges |

Translation coverage already implemented upstream: chunk, item, recipe, entity,
particle, movement, entity-metadata packets; multipart/dynamic renderer discovery;
Create tracks/belts/cogwheels/contraptions; lighting; six-chunk sublevel visibility;
jar-in-jar nested mod asset discovery.

## 3. Known open bugs / verification debt (from repo docs)

From `CREATE_TODO.md` (Create mod focus):
- **OPEN** — Creative motor speed-control UI not 1:1. Leads: `gui/BlockConfigurationOverlay.java`,
  `generic/SubLevelInteraction.java`, Create's `ScrollValueBehaviour`.
- Recently fixed upstream (context for regression tests when porting): belt end caps on
  EAST-facing vertical belts; belts shipped encased / strip parts dropped by rig ranking;
  cogwheel spin axis (`cogwheel_shaftless` misclassified as shaft); belt transport velocity
  unit bug (RPM consumed as blocks/tick → server-side rubber-banding; fixed to sign-only
  scan + `RPM/480` conversion).

From `COMPATIBILITY_STATUS.md` — implemented but **awaiting in-game verification**
(port these with fresh tests, don't trust blindly):
- engine fuel transfer through inventory bridge; sublevel shaft/cog/propeller animation;
  lever/button state on moving structures; creative middle-click item return;
  linked-controller press/refresh/release; movement/teleport/entity-data decoder
  sanitization under fast motion; shulker collision proxy head protrusion; HUD close on
  vanilla container-close packet; multiblock render-state reconstruction, tank liquid
  bounds, dedicated-server water colouring; belt item slope orientation, pulley parts,
  pipe rims, funnel flap animation; runtime-tinted polarity particles; value-setting HUD
  submissions.

From `mods.md` — third-party coverage intentionally incomplete: JEI (recipe transfer,
bookmarks), AE2 (terminals, crafting sync), Mekanism (machine actions, chemical
presentation), Jade (runtime overlays), JourneyMap (map/network), SophisticatedBackpacks
(menus/upgrades). These define M7 nightly matrix targets.

## 4. Why a Rust rewrite (v2), and what changes

| Aspect | F2V (v1) | c-imagine F2V v2 |
|---|---|---|
| Host | inside NeoForge/Forge server | inside Paper 26.3, CRUSSTY module |
| Packet access | mod/mixin interception | CRUSSTY net pipeline hooks (Rust, Pass/Drop verdicts) |
| Discovery language | Java static analysis | Rust analysis on `cim-modscan` classfile parser (M4+ stack simulation port) |
| Hot paths | JVM allocation per packet | native rewrite, zero-copy ring handoff |
| Ground truth | manual in-game checks | CI visual diff harness (M5) vs NeoForge renders |
| Duplicated loaders | forge 1.20.1 + neoforge 1.21.1 twin implementations | single implementation targeting 26.3 kernel |

v1 stays the **behavioral reference**: forge-1.20.1 target is not deleted; its outputs
are used as expected-values in M5+ visual contracts where assets match.

## 5. Port matrix (v1 subsystem → v2 crate/component)

| v1 | v2 home | Milestone |
|---|---|---|
| mods.toml/jar scanning, nested jars | `cim-modscan` (already: parser, @Mod scan, jar-in-jar, load order) | M0 ✔ / M2 hardening |
| registry-safe packet sanitation | `cim-f2v::translate` + CRUSSTY net hooks | M4 |
| registry snapshots/diffing | `cim-registry` (M0) | M1 |
| entity/block visual mapping | `cim-f2v::translate` mapping tables | M4 |
| ClientSurfaceScanner (bytecode evidence) | Rust analysis in `cim-f2v::discover` (new, M4+) | M4–M6 |
| PackBuilder | `cim-f2v::pack` (Rust zip + PNG/OBJ processing) | M6 |
| ContraptionManager/BlockDisplayManager | `cim-f2v::contraption` (protocol-level display entities; Paper display-entity APIs reachable via bridge) | M5–M6 |
| ClientOnlyHudSimulator (GUI scenes) | `cim-f2v::hud` (map/image-tile protocol) | M6 |
| PacketSanitizer edge hardening | `cim-f2v::translate::sanitize` + fuzz corpus | M4, continuous |

## 6. Rules inherited from F2V's own docs

- Keep the "does not load or execute mod classes during discovery" invariant — our
  static analysis must stay side-effect free.
- Ambiguity budgets: reflection, native code, escaped arrays → record
  `analysisWarnings` instead of guessing; provide vanilla fallbacks.
- Every translated surface needs a paired in-game (later: CI-visual) verification entry;
  upstream's "awaiting verification" list is treated as *unverified* and re-tested.
