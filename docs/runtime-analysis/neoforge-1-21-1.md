# NeoForge 1.21.1 — internals analysis

Analyzed from source: `neoforged/NeoForge` branch `1.21.1` (956 java files under
`src/main/java` + `patches/` + `coremods/` + `testframework/`), `gradle.properties`,
and the maven metadata. Measured 2026-10-26.

## 1. Identity card

| Field | Value | Source |
|---|---|---|
| Latest 1.21.1 line build | **21.1.265** | maven-metadata.xml (`net/neoforged/neoforge`) |
| MC version | 1.21.1 (`neoform 20240808.144430`) | `gradle.properties` |
| Java target | 21 | `gradle.properties: java_version` |
| Mod launcher stack | modlauncher **11.0.5**, bootstraplauncher 2.0.2, securejarhandler **3.0.8** | `gradle.properties` |
| Event bus | eventbus **8.0.2** (static `@SubscribeEvent`, MOD + GAME buses) | `gradle.properties` + source |
| Mixin | 0.16.4+mixin.0.8.7, mixinextras 0.5.5 | `gradle.properties` |
| FML | fancy-mod-loader **4.0.45**, jarjar **0.4.1** (Jar-in-Jar) | `gradle.properties` |
| Config | nightconfig 3.8.3 (TomlFeed) | `gradle.properties` |

## 2. Mappings policy — the fact the whole relocation design rests on

**There is no reobf pipeline anywhere in the NeoForge build** (checked all gradle
scripts). NeoForge 1.21.1 runtime and every distributed mod use **Mojang official
mappings**: mod classes reference `net.minecraft.world.item.Item` and friends
directly, mixin refmaps target Mojmap names, and no SRG translation happens at load.

Consequence for c-imagine: the shadow-namespace relocation
(`ARCHITECTURE.md §4.2`) is a **pure mechanical rename** of already-named classes —
no name-remapping stage is needed between "mod jar" and "relocated layer". This is
the single most important upstream fact for the design; if NeoForge ever switches
runtime mappings, `cim-modscan` gains a remap stage before relocation.

## 3. Source map — what the mini-NeoForge layer must reproduce

NeoForge's own surface (what mods actually import):

| Area | Location in NeoForge source | Layer obligation (roadmap) |
|---|---|---|
| Lifecycle events | `event/` (25 top-level classes + subpackages: `server/`, `entity/`, `item/`, `client/`...) | MOD+GAME bus semantics; server lifecycle, tick, login (M2); breadth by M7 |
| Registration | `registries/` (`DeferredRegister`, `RegisterEvent`, `NewRegistryEvent`), `items/` | real registries from the relocated MC stack; modded ids via `cim-registry` allocator (M2) |
| Networking | `network/` (`payload/`, `registration/`, `handling/`, `negotiation/`, `configuration/`, `PacketDistributor`, `DualStackUtils`) | payload registration + handshake/negotiation behavior of FML's mod channels (M7 for mod↔mod; M4 for anything F2V consumes) |
| Data | `attachment/` (AttachmentType system), `capabilities/` (interop: energy/items/fluids), `data/` (datagen) | attachments marshalled through bridge (M7); capabilities via relocated stack natively |
| Energy/items/fluids APIs | `energy/`, `fluids/`, `items/` | come free with the relocated NeoForge jar |
| Client surface | `client/` (screens, extensions, renderers registration) | M5+: only what the relocated logical server needs to satisfy class loading; client code paths stay inert server-side |
| Mixins/coremods | `mixins/`, `coremods/`, `patches/` | relocated stack applies its own mixins during ITS boot — inside the shadow namespace, invisible to Paper |

Key realization recorded at M0 and confirmed here: because the layer boots the
**real relocated NeoForge + FML** (not a re-implementation), the "mini NeoForge" work
is *not* "rewrite NeoForge's event system in Rust" — it is:

1. **boot orchestration** (Rust): drive the relocated stack's launch sequence inside
   the Paper JVM (modlauncher-equivalent steps executed by the bootstrap +
   ShadowClassLoader, per `ARCHITECTURE.md §4.3`);
2. **mod discovery** (Rust, `cim-modscan`): jars, `neoforge.mods.toml`, Jar-in-Jar,
   dependency sort — done natively **before** any class is defined;
3. **state facade** (Rust + Java shim): the relocated stack's world calls resolve to
   bridge frames instead of a second server;
4. **execution**: mod bytecode runs on the host JVM, inside `cim.shadow.*`,
   with real NeoForge behavior because it *is* real NeoForge.

## 4. Mod jar anatomy (what `cim-modscan` consumes)

From NeoForge 1.21.1 conventions (verified against the 21.1.211-based F2V workspace
and NeoForge source):

- `META-INF/neoforge.mods.toml` — mod metadata: `mods[]` (`modId`, `version`,
  `displayName`, ...), `dependencies.<modId>[]` (`type`, `mandatory`, `versionRange`,
  `ordering`, `side`).
- `@Mod(<modid>)` classes — entrypoints; multiple containers possible; static
  `@EventHandler`-annotated lifecycle callbacks (FML 4.x model).
- Jar-in-Jar: nested jars under `META-INF/jarjar/metadata.json` (jarjar 0.4.1).
- Resources: `assets/<ns>/...`, `data/<ns>/...`, pack.mcmeta with **datapack v48 /
  resourcepack v34** era formats (26.3 kernel expects v121/v97 — translation needed
  at the kernel boundary, M6+).
- Coremods: `coremods/` JS transforms + mixin configs — relocated stack applies
  these itself; `cim-modscan` only *detects* them to flag mods that need the M8
  compat mode.

## 5. Behavior-parity tests (the "100% identical" bar)

Every layer obligation above gets an upstream-behavior test: run the same scenario
on a real NeoForge 1.21.1 server (CI job, downloaded installer + `cimtest` mod) and
on Paper + c-imagine, and compare **observable behavior** — log markers, registry
dumps, packet transcripts (captured at the net hook), and later screenshots (M5).
The transcripts become fixtures; regressions are diffs against ground truth, not
opinions. This is the M2+ extension of the existing integration workflow.

## 6. Risks tracked against NeoForge

| Risk | Mitigation |
|---|---|
| Mixin refmap targets inside relocated packages | relocation rewrites refmaps along with class refs (same ASM pass; fixture-tested in `cim-modscan`) |
| Mods reflect over their own class **name strings** | `cim.shadow.ClassNames.decode` shim (already in ARCHITECTURE §4.2 limits) |
| Mods use `MethodHandles`/`invokedynamic` across the boundary | handled by relocation pass; conformance test in M2 |
| SecureJarHandler module-layer assumptions | ShadowClassLoader emulates the module read/opens the relocated stack needs; M1 acceptance covers "logical server constructs" |
| NeoForge 21.1.x patch updates (265+) | layer pins 21.1.265; bump is a conscious feat with the parity suite re-run |
