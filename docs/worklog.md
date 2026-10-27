# c-imagine worklog

> Single shared multi-agent work log. Append-only.
> Mirrored into the repo as `docs/worklog.md` on every push (sandbox dies → git survives).

---
Task ID: 1
Agent: main
Task: M0.5 — full upstream runtime analysis (MC 26.3, Paper 26.3, NeoForge 1.21.1, vanilla client 26.3, cross-version gap)

Work Log:
- Pulled ground truth from mojang piston-meta, PaperMC fill v3, maven.neoforged.net, mcmeta (branch `registries`), vanilla client/server jars.
- Wrote docs/runtime-analysis/{README,minecraft-26-3,paper-26-3,neoforge-1-21-1,vanilla-client-26-3,cross-version-gap}.md.
- Verified F2V v2 primitives exist in 26.3 (block/item/text_display, custom_model_data + item_model, filled_map, server resourcepacks fmt 97.1).
- Built CI matrix (ci/integration/profiling/visual) — green.

Stage Summary:
- Paper 26.3: protocol 777, DataVersion 5023, datapack v121.0, resourcepack v97.1, Java 25, build 223.
- NeoForge 1.21.1: 21.1.265, protocol 767, DataVersion 3955, Java 21, FML 4.0.45, eventbus 8.0.2, mixin 0.16.4.
- Mojmap runtime everywhere in NeoForge 1.21.1 → relocation is a pure mechanical rename.
- Cross-version gap measured (registries table in cross-version-gap.md).

---
Task ID: 2
Agent: main
Task: M1 — embedded vanilla 1.21.1 layer boots (shadow namespace relocation of the real NeoForge stack)

Work Log:
- crates/cim-reloc: relocation tables + full class-file rewriter (constant pool + member declaration descriptors, appended-UTF8 strategy).
- JNI bridge NativeReloc (Rust in libimagine.so); ShadowClassLoader (hermetic, child-first, central-dir index, MR-JAR-aware, legacyClassPath order).
- CI installs real NeoForge 21.1.265 server layer; relocated Bootstrap.bootStrap() runs inside Paper JVM headless; registry census 78 (local) / ≥70 asserted in CI.
- ground-truth.yml: server class census 26.3 vs 1.21.1 (7436 vs 6148) + 26.3 client decompile pipeline (client.txt → tiny v2 → tiny-remapper → Vineflower), validated locally.

Stage Summary:
- Commits 558eeb2 + 80f0a9c. All 5 workflows green on 80f0a9c.
- FmlStateShim seeds empty LoadingModList — replaced by real FML discovery in M2.

---
Task ID: 3
Agent: main
Task: M2 — mod execution: real FML discovery + mod relocation + cimtest registration through the layer; early real-mod integration (owner requirement)

Work Log:
- Sandbox reset detected (workspace lost); environment restored from GitHub (80f0a9c).
- Restored: rustup, NeoForge 21.1.265 server layer (installer), neoforged/NeoForge 1.21.1 source clone (FML sources for analysis), real NeoForge 1.21.1 mods from Modrinth.
- Analyzed FML 1.21.1: fml-loader discovery pipeline, fml-core ModContainer/FMLJavaModLoadingContext/eventbus wiring (see repo docs/runtime-analysis updates + M2 design in ARCHITECTURE).
- Implemented M2 (see subsequent entries).

Stage Summary:
- (in progress)

---
Task ID: 3.1
Agent: main
Task: M2 deep analysis — real FML 1.21.1 hosting design (loader-4.0.45 + bus-8.0.5 + neoforge-21.1.265 sources)

Work Log:
- Downloaded + extracted FML sources: maven.neoforged.net fancymodloader/loader-4.0.45, bus-8.0.5, modlauncher-11.0.5, neoforge-21.1.265 sources.
- Traced the real server mod-loading pipeline: ServerModLoader.load → CommonModLoader.begin/load/finish → ModLoader.gatherAndInitializeMods → RegistryManager.postNewRegistryEvent (mod buses) → GameData.unfreezeData/postRegisterEvents/freezeData → FMLCommonSetupEvent/FMLDedicatedServerSetupEvent → NeoForge.EVENT_BUS.start().
- Key facts: FMLModContainer(IModInfo, entrypoints, ModFileScanData, ModuleLayer) — allowed ctor args {IEventBus, ModContainer, FMLModContainer, Dist}; exactly 1 public ctor. AutomaticEventSubscriber.inject uses scan data + FMLLoader.getBindings().getGameBus(). BusBuilder: mod bus = markerType(IModBusEvent)+allowPerPhasePost; game bus NeoForge.EVENT_BUS = startShutdown + IModBusEvent checker; post() on shutdown bus is a no-op; start() after registration.
- FMLLoader statics are package-private but reflective-seedable: dist, gameLayer, loadingModList, bindings (IBindingsProvider → Proxy → NeoForge.EVENT_BUS), backgroundScanHandler.
- Real ModFile(SecureJar.from(modjar), parser, attrs) → ModFileParser.modsTomlParser (real NightConfig toml parsing!) → real ModFileInfo/ModInfo. ModList.of(modFiles, sortedInfos) + setLoadedMods (reflective).
- ModFileScanData.AnnotationData records + asm Type — we build from Rust scan (relocated class names).
- Discovery of entrypoints = scan data @Mod targets → we supply RELOCATED names from cim-modscan.
- Decision (M2.0): own orchestration loop calling the REAL FML pieces (ModFile/ModFileInfo/ModInfo/ModList/FMLModContainer/AutomaticEventSubscriber/RegistryManager/GameData/DeferredWorkQueue) on a real ModuleLayer built over ShadowClassLoader via ModuleLayer.defineModules with custom ModuleDescriptors (per-mod module, packages = original + relocated).
- Known limit M2.0: runtime annotation descriptors stay raw (JVM reflection sees no @Mod on shadow classes) — harmless for construct path; ASM-based mod self-scanning (JEI plugin discovery) unaffected. M2.1 toggle: relocate runtime-visible annotation descriptors.

Stage Summary:
- M2.0 = real FML on our runtime, glue ≈ 1 orchestrator class + ModuleLayer factory + loader jar extension + Rust manifest pipeline.


---
Task ID: 3.2
Agent: main
Task: M2.0 bring-up on real mods — registration pipeline diagnostics + fixes; CI-first workflow established

Work Log:
- Sandbox survived partially this time; uncommitted M2 glue (~1500 lines) committed and pushed FIRST (afaa438) per owner's push-often directive.
- LocalHarness end-to-end bring-up: init (relocation armed, layer boot, mod jars into shadow loader, defineLayerEarly) -> loadMods (FmlHost.run).
- First failure: NewRegistryEvent left neoforge:* registries pending — root cause: manifest lacked the layer "neoforge" mod (prepend_layer_neoforge requires CIM_LAYER_DIR env; cim-manifest invocation missed it).
- Traced real NeoForge 21.1.265 sources: NeoForgeRegistries.<clinit> eagerly builds registries (RegistryBuilder.create -> trackModdedRegistry); NeoForgeMod ctor attaches NeoForgeRegistriesSetup handlers to its MOD bus; postNewRegistryEvent posts in mod order -> registration clears pending set.
- After CIM_LAYER_DIR fix: all 7 containers constructed (5 real Modrinth mods + jarjar mezz_config + neoforge 21.1.265 universal + ClientNeoForgeMod dist-skipped).
- Registration looked empty (registered={}, moddedEntries=[]) — diagRegisterProbe added to FmlHost (-Dcim.diag=true): ModList identity ok, buses ok, manual RegisterEvent(ITEM) post ok. Insight: the 5 real mods register NOTHING via RegisterEvent (behavioral mods) — empty delta is CORRECT.
- Added cimtest (rebuilt via javac against the mojmap layer jars — offline path) -> full registration verified: registered={ITEM=1,BLOCK=1,BLOCK_ENTITY_TYPE=1,ENTITY_TYPE=1}, moddedEntries=[cimtest:test_block, cimtest:test_block_entity, cimtest:test_entity]. Real FML pipeline (NewRegistryEvent -> unfreeze -> RegisterEvent per registry in mod order -> freeze) works end-to-end in the shadow space.
- Fixed FmlHost index-pairing bug: mods skipped by dist filter desynced (mods, containers) parallel loops (IndexOutOfBoundsException) -> containerMods paired list.
- Full composite run: 8 mods (JEI 19.70.0.460, Jade 15.10.6, AppleSkin 3.0.9, FerriteCore 7.0.3, spark 1.10.124 + jarjar mezz_config + neoforge universal + cimtest) — mod execution ok in ~720 ms.

Stage Summary:
- M2.0 = real FML hosting works on real NeoForge 1.21.1 mods: discovery, JPMS layer, containers, ctor injection, AutomaticEventSubscriber, NewRegistryEvent, per-registry RegisterEvent, freeze, setup events, game bus start.
- Next: CI job for the M2 harness (all builds/tests on GitHub CI), push, then M2 parity (real NeoForge ground-truth server) + M2.1 runtime-visible annotations.

---
Task ID: 3.3
Agent: main
Task: CI-first hardening — clippy/-D-warnings clean, fmt clean, harness script, live-boot real-mod job

Work Log:
- ci.yml failure diagnosed: cargo fmt --check + clippy -D warnings on the uncommitted-to-legacy M2 glue.
- Fixed 10 clippy warnings (needless unwrap after is_some, too_many_arguments allowances with rationale, unused imports/variables, unused doc comment -> plain comment, complex type -> MethodAnnSpec alias, dead_code allowance for symmetric writer).
- cargo test --workspace: 81 tests green.
- scripts/run-m2-harness.sh: single entry point (cim-manifest + LocalHarness) for local + CI.
- integration.yml: m2-real-mods CI job (real Modrinth mods + cimtest, marker assertions, timing) + boot job now loads real mods through the LIVE Paper/CRUSSTY boot with registration assertions.
- Verified harness after rewriter edits: mod execution ok, 716 ms, same registrations.

Stage Summary:
- Pushed a09b861 (live-boot job) + clippy/fmt hardening. All builds/tests now owned by GitHub CI; sandbox does only light edits.
- Watching: ci + integration (boot w/ real mods, harness) on a09b861.

---
Task ID: 3.4
Agent: main
Task: CI validation round 1 — m2-real-mods harness job GREEN; boot assertion format fix

Work Log:
- m2-real-mods job (Modrinth mods + cimtest through real FML in shadow space): SUCCESS on first run.
- boot job failed on a STALE marker assertion: ready line is now nested JSON
  {"init":{...,"layer":{...}},"mods":{...}} but the check expected the old flat {"state":"ok"}.
  The live boot itself PASSED: layer registries=78, mods=8 in 736 ms, registered={ITEM=1,BLOCK=1,BLOCK_ENTITY_TYPE=1,ENTITY_TYPE=1}, moddedEntries=[cimtest:*].
- Rewrote the assertion step to parse the nested JSON robustly (raw_decode + per-key assertions), validated against the real CI boot.log artifact.
- ci.yml green on 8775439 (fmt/clippy/test/release).

Stage Summary:
- M2 is effectively proven end-to-end on CI: real mods load through the real FML pipeline both in the harness and inside the live Paper 26.3 boot.
- Next: green boot on 8775439-fix, then M2 parity ground-truth (real NeoForge 21.1.265 server run) + M2.1 annotation descriptors.

---
Task ID: 3.5
Agent: main
Task: shadow-space log pipeline fix + M2 parity ground-truth job

Work Log:
- Shadow log4j was silent: (a) getResources returned only the FIRST jar hit — log4j aggregates plugin caches per jar (Log4j2Plugins.dat lives in META-INF/org/apache/logging/log4j/core/config/plugins/ in 2.22.1) and ServiceLoader needs all providers — without log4j-core's dat, 0 core converters load, layouts fail, log lines drop; (b) no config → DefaultConfiguration.
- Fixes: ShadowClassLoader.getResources now enumerates ALL jars (hermetic, MR-aware); scoped log4j2 config via log4j2.component.properties packaged in cim-bootstrap.jar (visible only to the shadow loader) + scripts/log4j2-layer.xml at process CWD.
- Result: [cim-layer] INFO lines in harness output incl. first-party markers "[cimtest] mod constructed (registration queues armed)" and "[cimtest] common setup ok (block=cimtest:test_block, ...)". Harness re-verified: mod execution ok, 946 ms.
- integration.yml: [cim-layer] marker assertions in boot + m2-real-mods jobs; NEW parity-real-neoforge job — REAL NeoForge 21.1.265 dedicated server boots the SAME mod set (real Modrinth mods + cimtest); the [cimtest] markers are the cross-boot parity contract.

Stage Summary:
- Shadow space now logs through its own (scoped) slf4j→log4j2 stack; parity contract established with the real server.
- Pushed: f8eec68 (assert format fix) + log-pipeline + parity job.

---
Task ID: 3.6
Agent: main
Task: CI round 2 — boot + parity GREEN, harness path fix

Work Log:
- integration 4071465: boot Paper (8 real mods, [cim-layer] markers) GREEN; parity-real-neoforge (real NeoForge 21.1.265 server, same mod set, [cimtest] markers) GREEN on first run; m2-real-mods harness failed on a relative-path bug in run-m2-harness.sh (cd into OUT broke the manifest path).
- Fix: script resolves LAYER/MODS/OUT to absolute paths up front. Verified locally with a nested relative out dir.
- docs/ROADMAP.md: M2 section rewritten to DONE status with the M2.0 design summary + acceptance evidence.

Stage Summary:
- M2 effectively complete and proven on CI from three angles: harness, live Paper boot, real NeoForge parity server. Remaining: green harness job on the path fix + M2.1 annotation toggle.

---
Task ID: 3.7
Agent: main
Task: /cim status bridge — module persists status.json, Paper plugin renders it

Work Log:
- lib.rs: run_bootstrap result (combined nested status JSON) persisted to <server_root>/c-imagine/status.json (non-fatal on failure).
- paper-plugin CimPlugin: /cim status renders the file (regex extraction, no JSON deps on the Paper side); plugin.yml untouched.
- integration boot job: python assertion on srv/c-imagine/status.json (init + mods ok).
- cargo check/clippy -D warnings clean.

Stage Summary:
- M2 acceptance surface complete: mods run (harness + live boot), parity server green, /cim status bridge file contract in place.

---
Task ID: 3.8
Agent: main
Task: M2 closure — all integration jobs green on CI

Work Log:
- integration 8954c6c + e88c713: FULLY GREEN (ci, visual, integration).
- Three-way proof of M2: (1) m2-real-mods harness — 8 mods through real FML in the shadow space; (2) live boot — same set inside the Paper 26.3 JVM via CRUSSTY with status.json bridge asserted; (3) parity-real-neoforge — real NeoForge 21.1.265 server prints identical [cimtest] markers.

Stage Summary:
- M2 DONE (M2.1 annotation toggle tracked as follow-up).
- Next: M3 — state bridge v1 (block read/write through Paper, entity projection, Paper tick scheduler).

---
Task ID: 3.9
Agent: main
Task: M3.1 kernel state bridge — place/break across the two worlds (facade v1)

Work Log:
- Design zero-RegisterNatives: module poll thread resolves dev.cim.paper.CimPlugin via JVMTI classes::find_class, resolves the plugin INSTANCE through Bukkit.getServer().getPluginManager().getPlugin, polls isWorldReady(), then invokes KernelBridge.runSmoke(pluginObj) on the bootstrap loader — the facade reflects on the plugin BY NAME (String/primitive values only; no shared types).
- crates/imagine/src/kernel_bridge.rs: poll loop + JNI plumbing (leaked global ref of the bootstrap URLClassLoader; 'static GlobalRef for the plugin object).
- dev/cim/bridge/KernelBridge.java: synchronous facade — vanilla states pass through; modded ids (cimtest:*) → sidecar record + Paper stand-in (minecraft:structure_void); air clears the sidecar (break).
- CimPlugin: isWorldReady (ServerLoadEvent), worldName, layerGetBlock/layerSetBlock/paperSetAir with a main-thread hop (callSyncMethod) since bridge callers run on module threads; /cim extended with bridge state.
- boot job: builds the paper plugin (mvn) and deploys it into srv/plugins/; assertions for all four smoke markers (vanilla roundtrip, modded stand-in, paper-side break observed by layer, ALL OK).
- clippy -D warnings clean; release build ok; harness still green (957 ms).

Stage Summary:
- M3.1 bridge ready for the live-boot CI validation (place via layer -> Paper sees it; break via Paper -> layer observes).

---
Task ID: 3.10
Agent: main
Task: CI round 3 — paper-api 26.3 surface drift fix

Work Log:
- paper-plugin compile failure on CI: Block#setAir(boolean) no longer exists in paper-api 26.3 — replaced with setType(Material.AIR) (void return wrapped).
- Harness + parity jobs stayed green; only the plugin surface drifted.

Stage Summary:
- Pushing fix; expecting full green on the next run (bridge smoke runs in the live boot).

---
Task ID: 3.11
Agent: main
Task: boot job artifact name fix

Work Log:
- pom finalName is cim-paper-plugin (no version suffix) — cp path corrected.

Stage Summary:
- Expecting full green; bridge smoke markers next.

---
Task ID: 3.12
Agent: main
Task: bridge race fix — Bukkit class loads before the server instance exists

Work Log:
- CI boot.log artifact: "getPluginManager: Null pointer in call_method obj argument" — the bridge thread resolved Bukkit the moment the CLASS loaded, before Bukkit.setServer()/plugin enable.
- Fix: plugin-instance resolution now retries (1s cadence, 5 min deadline) until getServer() and getPlugin("c-imagine") are both live; world-ready poll unchanged.

Stage Summary:
- Bridge lifecycle: class online -> instance online -> world ready -> smoke.

---
Task ID: 3.13
Agent: main
Task: bridge smoke — modded leg routed through the facade translation

Work Log:
- CI: bridge resolves plugin after retry, world-ready fires, vanilla leg PASSes (roundtrip ok); modded leg failed because runSmoke invoked plugin.layerSetBlock directly — createBlockData("cimtest:test_block") has no 26.3 registry entry.
- Fix: runSmoke now goes through KernelBridge.kernelSet/kernelGet (layerWrite -> stand-in -> Paper; layerRead -> sidecar resolution).

Stage Summary:
- Expecting ALL OK on the next boot run.

---
Task ID: 3.14
Agent: main
Task: M3.1 closure — bridge smoke ALL OK on live CI boot

Work Log:
- integration bc69607 FULLY GREEN (ci, visual, integration incl. parity + harness).
- Live-boot artifact evidence:
  * vanilla leg: layer write -> Paper stores yellow_terracotta -> roundtrip ok
  * modded leg: cimtest:test_block -> Paper stores structure_void (stand-in), layer view resolves sidecar=cimtest:test_block
  * break leg: Paper-side setType(AIR) -> layer facade observes minecraft:air
  * bridge smoke: ALL OK
- M2 + M3.1 acceptance met on GitHub CI, all builds/tests owned by CI per owner directive.

Stage Summary:
- M3.1 DONE (synchronous string-valued facade; sidecar for modded states).
- Next: M3.2 — blockstate remap tables (cim-registry) for vanilla drift (1.21.1 <-> 26.3 property diff, boat split, fluid shrink fixtures); M3.3 — persistent sidecar (survive restart, zero orphan state); M3.4 — entity projection; ring transport (cim-bridge) when throughput matters.

---
Task ID: 4
Agent: main
Task: M3.2 (state-translation tables) + M3.3 (persistent sidecar) — sandbox rebuild, implementation, local verification

Work Log:
- Sandbox was reset again; environment restored: rustup (1.99.1), portable JDK 21 (Adoptium, no sudo for apt), repo clone at cf68779.
- CI policy per owner: first economized (dispatch+weekly), then owner granted UNLIMITED Actions minutes -> restored push triggers on integration/visual + nightly profiling; local loop kept for speed (scripts/local-check.sh).
- Pinned mcmeta snapshots via GitHub API: 1.21.1 registries 0eaeabd259d0.../summary 7cffbcda..., 26.3 registries 2240df2376.../summary d96c75fe.... CORRECTED the M0.5 numbers: at the 26.3 RELEASE block=1286 (not 1294), item=1658, entity_type=161, fluid 5->5 (NO shrink — earlier figure was an unpinned-latest measurement error).
- scripts/gen-remap-tables.py: registry census + per-block property diff + explicit pairing rules (attribute flattening generic./player.->bare; boat->oak_boat, chest_boat->oak_chest_boat, potion->splash_potion; chain->iron_chain). Truly removed (no target): bed BE, 4 data components, 2 memories, 5 sensors, 6 sounds.
- Drift is TINY at release: 1059 shared blocks, 1 prop-drift block (note_block instrument +4 trumpet values), 1 renamed block (chain). Tables: blockstate-drift.tsv + registry-drift.tsv + remap-manifest.tsv written to BOTH java resources and crates/cim-registry/data (byte-equality unit-tested both sides).
- Java dev.cim.bridge.StateMapper: pure-string translation both directions + registry key translation; brace-balanced minified-JSON scan for the drift rows; --selftest main (15 fixtures) ALL OK. Found+fixed 3 bugs via selftest: nested-map brace tracking, split("\t",-1) for empty targets, block renames living in registry table.
- Rust cim-registry::drift: same tables via include_str!, same semantics, 8 tests (13 total in crate) green.
- KernelBridge: layerWrite/layerRead now route through StateMapper; smoke gained drift leg (minecraft:chain[axis=y] -> Paper holds iron_chain, layer reports chain) + note_block value leg.
- M3.3 dev.cim.bridge.SidecarStore: write-through TSV journal (<cwd>/c-imagine/sidecar.tsv), last-wins replay, torn-tail tolerant, tombstone erases, cull-on-read when the Paper stand-in vanished (zero orphan state, self-healing); --selftest ALL OK.
- KernelBridge smoke: restore-verify step (census "N live, M culled") + modded leg rides the store.
- integration.yml: level-seed=42, M3.2 assertions, SECOND boot + M3.3 restore assertions ("sidecar restore: cimtest:test_block ... (stand-in intact)", "1 live, 0 culled"). ci.yml: bridge selftests step. scripts/build-cimtest-offline.sh: javac against layer jars (2.1 s, replaces gradle in sandbox).
- Restored NeoForge 21.1.265 layer locally (installer had died mid-neoform twice; -Xmx1500m fixed it), Modrinth mods, offline cimtest.
- Harness re-verified after all M3 changes: 8 mods, mod execution ok in 664 ms, registrations intact, layer boot 6304 ms (78 registries), total 8.6 s.

Stage Summary:
- M3.2 + M3.3 implemented, locally verified (rust 13+ tests, 2 Java selftests, harness green, measurements logged). Pushing for full CI validation incl. the new two-boot restart contract.
- Next: M3.4 entity projection (entity_type remap via registry tables), then M4 packet translation.

---
Task ID: 5
Agent: main
Task: F2V 2.0 groundwork (owner vision) — measured 26.3 client visual capabilities; integration red fix (M3.4 boat leg); CI economy mode

Work Log:
- Sandbox partially survived (artifacts intact: jdk21, layer1211, mcmeta snapshots, mods; cargo 1.99.1 at ~/.cargo needs PATH). Repo confirmed at 198d88d, synced with origin.
- Owner directive: F2V 2.0 = render everything through post effects + ItemsAdder/Oraxen-style items; analyze the whole vanilla client jar; supplement the roadmap but implement ONLY when the roadmap reaches it. Original F2V repo is reference (cloned read-only).
- Downloaded vanilla client jars 26.3 (40MB) + 1.21.1 (26MB); scripts/analyze-client-visual.py + scan-client-anchors.py + read-263-registries.py (persisted, raw JSON in artifacts/analysis/).
- MEASURED headline: in 26.3 client asset systems became REGISTRIES (mcmeta 2240df23, 136 registries): post_effect(5: blur,creeper,entity_outline,invert,spider), item_definition(1658), model(4192), texture(3964), block_definition(1288), equipment(45), atlas(12), font(7), timeline(4), world_clock(2), environment_attribute(51), dialog(3).
- Server->client post-effect channel EXISTS: ClientboundPostEffectsPacket + handlePostEffects/apply/clear/requested/failed lifecycle in client bytecode; ServerPlayer.post_effects field; SuggestionProviders.POST_EFFECTS; datafix rename minecraft:post_effect->minecraft:post_effects. ShaderManager loads post_effect/<id>.json via FileToIdConverter; LevelTargetBundle/LevelRenderer named targets; blaze3d RenderPipelines rework (shaders/program/* GONE, core .json program specs GONE).
- Item pipeline: items/*.json 1658 defs, makers histogram (model 2253, special 91, select 71, dye 50, composite 34, condition 26, range_dispatch 8, constant 11); special renderers incl NEW copper_golem_statue(32); custom_model_data referenced by 0 vanilla defs (hook free). Equipment assets 45 (carpets/harnesses/copper). Client palette system (textures/palettes + TrimMaterials$Palette). Fonts compose via reference provider + include/* fragments. waypoint_style assets + WaypointStyleManager.
- Cloned jefthebuilder/F2V (read-only): pack/* 50 classes (CustomModelDataRanges, ItemModelVoxelizer, ArmorModelBuilder, PackHttpServer...) + vm/discovery/* 39 bytecode scanners — 1.21.1-era legacy overrides approach; 26.3 registry surface strictly cleaner.
- Wrote docs/runtime-analysis/f2v2-client-capabilities.md (+ F2F surface table, constraints); ROADMAP M4/M5/M6 supplemented (deferred until reached).
- CI: integration RED on 198d88d (boot job: "M3.4 boat split entity leg missing"). Root cause from CI log: kernel spawned oak_boat OK (uuid returned) but paperListEntities scan missed it — COORDINATE CONVENTION MISMATCH: paperSpawnEntity used getSpawnLocation().add(x,y,z) (spawn-relative) while paperListEntities and ALL block methods use absolute coordinates; smoke coords (0,100,0) absolute. Fixed paperSpawnEntity to absolute Location. No other getSpawnLocation uses remain.
- CI economy mode per owner: ci.yml push+paths(code only, PR trigger dropped), integration.yml push+paths + weekly net + dispatch, profiling/visual/ground-truth dispatch-only (nightly/weekly crons dropped). Stale "minutes are UNLIMITED" comments replaced.

Stage Summary:
- F2V 2.0 groundwork measured and pinned in docs; implementation deferred to M5/M6 per owner.
- M3.4 fix pushed for CI validation (ci+integration path-filtered triggers fire for this push by design).
- Next: green integration on the fix -> tick M3.4 -> M4 packet translation v1 (incl. ClientboundPostEffects family mapping).

---
Task ID: 6
Agent: main
Task: M3.4 CI validation marathon — integration matrix GREEN, milestone closed

Work Log:
- Round 1 (cd5c64e, with absolute-coord fix): still "M3.4 boat split entity leg missing" — spawned boat invisible to the same-tick box scan.
- Round 2 (7889e0c): smoke reworked to poll with diagnostics; evidence polls=60 lastRows=0 primary=false — NO channel ever sees the entity; pig invisible too; zero entities near spawn.
- Root cause (measured, not guessed): spawn chunks WITHOUT PLAYERS are block-ticking but NOT entity-ticking (Minecraft 1.20.5+ ticket semantics, kept in 26.3) — Bukkit-spawned entities stay untracked forever (getEntity AND getNearbyEntities blind).
- Round 3 (386ec8d): added paperForceChunk (chunk force ticket around each entity leg) -> BOTH workflows went Maven-red: paper-api 26.3 removed World#setForceLoaded. Fast path established: downloaded paper-api 26.3.build.223 jar from repo.papermc.io and javap'd it (artifacts/paper-api-26.3.jar) — the rename is setChunkForceLoaded; verified all other used surfaces (getEntity(UUID), spawnEntity(Location,EntityType), BoundingBox scan) exist.
- Round 4 (bd01c2a): rename fix -> ci GREEN; boot1 FULLY GREEN incl. "boat split ok" (polls=1! — force ticket makes the chunk entity-tick instantly) + "pig identity ok"; NEW latent failure surfaced: boot2 "crussty: server already running" — the two-boot M3.3 contract never executed past this point in any earlier run (boat leg failed first every time).
- crussty guard reverse-engineered from the engine binary (strings): run refuses while the pid recorded via -Donload.pidFile= is ALIVE; no `status` subcommand; `stop` signals but the Paper JVM outlives the CLI by seconds while saving.
- Round 5 (f4bfef7): stop+status-wait insufficient (status loop broke instantly — no such subcommand) -> boot2 still "already running".
- Round 6 (93d4a52): wait for the JVM PROCESS itself (pgrep -f onload.pidFile, up to 120 s) + pkill -9 fallback, in BOTH boot steps; boot windows 360s->240s (Done+smoke need ~100 s). FULLY GREEN.

Stage Summary:
- integration@93d4a52 GREEN end-to-end: boot1 ALL OK (vanilla/modded/drift/note legs + entity legs: boat split ok polls=1 primary+scan, pig identity ok), boot2 "sidecar restore: cimtest:test_block @ world,0,100,1 (stand-in intact)" + M3.3 restart contract green. M3 (3.1-3.4) CLOSED.
- ci@bd01c2a GREEN (last code-bearing commit).
- CI economy: docs/workflow-only pushes burn nothing (path filters); today's 6 integration runs were required matrix validation.
- Next: M4 — F2V v2 packet translation v1 (net hooks, protocol bot; ClientboundPostEffects family mapping per the F2V 2.0 doc).

---
Task ID: 7
Agent: main
Task: sandbox rebirth recovery + M4 NetHooks weave ClassFormatError root-caused and fixed + common-packet drift 767<->777 measured

Work Log:
- Sandbox was reset again (all local artifacts lost; repo recovered from GitHub). Toolchain restored: rustup 1.99.1 + rustfmt/clippy, portable JDK 21 + JDK 25 (Adoptium), Maven 3.9.9 (archive).
- CI red on f5f88e5 diagnosed from Actions logs (zero extra minutes): (1) cargo fmt diffs in 5 files, (2) 17 clippy -D warnings in new m4 bot code (dead-code catalog ids.rs, ReportSink type_complexity x2, cfg(test)-only helper), (3) paper-plugin: NetWatch.connectionField private access from CimPlugin:255.
- Fixed all three; local-check.sh ALL OK (fmt/clippy/80 tests/release), mvn compile BUILD SUCCESS. Pushed 09e63a7+1ace381: ci green on all 5 non-boot jobs; boot job still red.
- BOOT ROOT CAUSE (deep, measured): weave repair's strip_invokestatic_prefix removed 3 bytes but runtime 0.2.0 inserts 4 (invokestatic+nop; the nop keeps switch padding residue valid per CRUSSTY transform.rs:1401) AND shifts +4: exception pcs, LNT start_pc, LVT/LVTT start/length, ONE StackMapTable frame delta (first frame with abs>0). 3-byte splice -> LNT pc 13 >= code_len 11 -> "Invalid pc in LineNumberTable" on Connection (2 method-entry hooks: channelInactive, setupInboundProtocol).
- Evidence pipeline: local CRUSSTY v2.9.0 stand (srv-m4) reproduced CI 1:1 (48836->48830 + same error); CIM_DUMP_WOVEN env added to capture woven bytes; pristine = nested META-INF/versions/26.3/server-26.3.jar class from the launcher-patched paper jar; diff-woven.py (scripts/, class-file parser cf_parse.py) measured the weave to the byte (+4 code_len/attr_len, LNT entries>0 +4, LVT len+4 when start==0, SMT frame0 41->45 with frame1 riding relatively).
- strip_invokestatic_prefix REWRITTEN (cplug-sdk): plan/apply-reverse edit model; N=4 when code[3]==0x00 (nop) else 3 (legacy bare shape, old handshake fixture still green); full attribute mirror incl. SMT first-frame delta decode with extended(247/251)->inline re-encoding (attr shrink -2); Cp passed in, vti_len_at infallible walk. Bug found by the new tests themselves: attribute length u4 sits at body-4 (not body-6).
- NEW fixtures: woven-connection.class + orig-connection.class (48KB each). Tests: 82/82 methods' Code attribute bodies byte-identical post-repair; javap accepts; idempotent; legacy handshake intact. cplug-sdk 14/14.
- Local boot proof: full CRUSSTY v2.9.0 stack with module: "Done (15.455s)!", repair 48836->48828 (2x4), zero ClassFormatError. Pushed 7502ad5 (also dropped accidentally-tracked third_party/cplug-sdk/target binaries, 389 files).
- F2V 2.0 measurements while CI ran: ClientboundPostEffectsPacket wire format bytecode-proven (STREAM_CODEC = Identifier.STREAM_CODEC.list() = varint + UTF strings; cimbot parse_post_effects matches exactly); packet type name "post_effects", common family (both config+play). Common-packet drift 767<->777 measured from official 1.21.1 client mappings + 26.3 bytecode: clientbound 767=11 -> 777=14 (+post_effects, +show_dialog, +clear_dialog); serverbound 767=5 -> 777=6 (+custom_click_action).

Stage Summary:
- M4 boot blocker REMOVED (pending CI integration green on 7502ad5).
- Postfx wire format + common drift pinned for M4 translation tables.
- Next: CI integration validation -> cimbot full contract on live boot -> M4 packet translation v1.

---
Task ID: 8
Agent: main
Task: sandbox rebirth #2 recovery + M4 wire contract wired into CI (cimbot joins the live boot)

Work Log:
- Sandbox reset again: repo + artifacts/clients + srv-m4 stand + toolchain survived; worklog at repo root lost (docs/worklog.md tracked copy survived), analysis scripts + raw analysis JSON lost (distilled docs survived — the push-WIP policy paid off).
- Recovered state: origin/main at bbb0689 synced; UNPUSHED gem found — docs/runtime-analysis/post-effect-schema-26-3.md (chain grammar + 5 vanilla chains census). Pushed immediately as 23db776 (owner directive: push WIP constantly, sandbox can die any moment).
- CI read: integration@7502ad5 GREEN (weave fix validated end-to-end), ci@bbb0689 GREEN — M3 closed, M4 boot blocker REMOVED confirmed on CI.
- CI-minutes economy: checked ci.yml path filters do NOT include workflows — the bot wiring push triggers integration only (3 jobs), ci.yml stays asleep. Docs pushes trigger nothing (verified filters).
- M4.1 implemented (e58aac0): integration.yml boot step now launches cimbot in a background subshell inside the boot window — waits for the Done marker (90x2 s), joins 127.0.0.1:25565 as "cimbot", the plugin's async net leg (KernelBridge leg 7 -> CimPlugin.startNetLeg) drives the full wire contract; server window ends early ONLY when BOTH cimbot-exit exists AND "bridge smoke: ALL OK" is in boot.log (timing analysis: killing on bot-exit alone would truncate the smoke legs that assert boot markers need) with the 240 s hard cap intact.
- New assert step: cimbot JSON report (ok/joined/registries>=25/packets_read>200/all 7 contract checks), server-side markers (bot joined, net leg complete, netty net-watch attach, post_effects census >= 2), artifacts uploaded (cimbot.log, report, exit code) for red-run debugging.
- BOT CONTRACT PRE-FLIGHT (deep, javap -c against nested server-26.3.jar): every cimbot codec verified against the 26.3 STREAM_CODEC compositions BEFORE the first live run — ClientboundPlayerPositionPacket = composite(VAR_INT, PositionMoveRotation(Vec3,Vec3,FLOAT,FLOAT), Relative.SET_STREAM_CODEC); Relative.SET_STREAM_CODEC is ByteBufCodecs.INT.map = plain i32 BE bitmask (NOT varint — would have desynced a varint reader); AddEntity = varint/UUID/registry-varint/3xf64/Vec3.LP_STREAM_CODEC/3xbyte/varint; LpVec3 = u8 lowest (0->ZERO) + u8 middle + u32 BE highest + (lowest&0b100)->varint — bit-exact with skip_lp_vec3; AcceptTeleportation = VAR_INT + 3xDOUBLE + 2xFLOAT; PosRot = 3xf64 + 2xf32 + flags byte. ALL MATCH — pinned in protocol-767-777.md section 9.
- Local hygiene without heavy runs (owner banned local server boots — sandbox hang risk): cargo fmt clean, clippy -D warnings clean (warm dev cache, 0.44 s), cargo test --workspace (dev profile) = 108 passed / 0 failed across 23 suites. Release compilation left to CI (OOM-risk locally, cargo test --release died mid-compile twice).
- Live-wire risks that bytecode cannot prove (documented): registry_data NBT shapes (fixture-tested), chunk section formats (frame-skipped by design), Paper config-phase ordering. The integration run is the oracle.

Stage Summary:
- M4.1 (bot on live boot) pushed e58aac0; integration run in flight — expected outcome: either GREEN contract (M4 acceptance criterion 1+2 proven) or a precise red with bot artifacts to iterate on.
- Next: CI validation loop -> on green, tick M4 bot leg in ROADMAP, then M4 net-hooks deepening (rewrite paths for entity metadata / chunk-batch, registry-driven tables) and the RSS profiling gate.
- Lesson recorded: release-profile cargo builds can die in this sandbox — CI is the build farm now; local is for edit + fast checks only.

---
Task ID: 9
Agent: main
Task: M4 wire contract CI marathon — live table measured, five defects root-caused

Work Log:
- Owner policy update: NO local server runs (sandbox hang risk) — CI is the test farm; local is edit+light checks only. Honored: fmt/clippy/test (108 unit tests, dev profile) locally; everything heavier went to Actions.
- Pushed unpushed post_effect schema doc (23db776), then wired the bot into integration.yml (e58aac0). Bot joins INSIDE the boot window via a background subshell that waits for the Done marker; early window close only when BOTH cimbot-exit AND "bridge smoke: ALL OK" exist (240 s hard cap); new assert step parses cimbot-report.json + server-side markers; bot artifacts uploaded.
- Iteration 1 (e58aac0): bot kicked "You are not whitelisted" — Paper 26.3 defaults white-list=true now. Also found the window-loop path bug (grep'd srv/boot.log from cwd srv). Fixed both (f596b64). Cancelled a redundant integration run triggered by a scripts/ push (path filter includes scripts/**).
- Iteration 2 (f596b64): bot joined, 6/7 green. add_entity oak_boat + post_effects [[],[creeper],[]] + remove_entities LIVE. set_entity_data missing, block_update missing, then overall timeout.
- Wire-format pre-flight (javap -c, nested server-26.3.jar): player_position = VAR_INT + PositionMoveRotation(Vec3,Vec3,F32,F32) + Relative.SET_STREAM_CODEC (ByteBufCodecs.INT.map — plain i32 BE bitmask, NOT varint); add_entity = varint/UUID/registry-varint/3xf64/Vec3.LP_STREAM_CODEC/3xbyte/varint; LpVec3 = u8 lowest (0->ZERO) + u8 middle + u32 BE highest + (lowest&0b100)->varint; accept_teleportation = VAR_INT + 3xDOUBLE + 2xFLOAT. ALL MATCH cimbot codecs (protocol doc §9).
- set_entity_data root cause: a fresh boat with default data sends NO metadata — the leg needs a REAL change. Added paperSetEntityGlowing (javap-verified Entity#setGlowing in paper-api 26.3.build.223) -> PASS next iteration.
- NetWatch attach: PlayerJoinEvent never fired on the CI wire order (census stayed total=0) — attach moved into runNetLeg. Then iteration 5 exposed the 26.3 reflection path change: listener-level "channel" field GONE — Connection.channel is PUBLIC now (javap); attach walks ServerPlayer.connection (listener) -> .connection (Connection) -> .channel.
- LIVE WIRE TABLE measured: cimbot censuses every frame-skipped play id into BotReport.unknown_ids; run-3 histogram = 31920 packets. Live-confirmed: add_entity=1, chunk_batch_finished=11, login=50, player_position=73, remove_entities=78, post_effects=83, set_entity_data=101 (all proven by successful parse+server echo, not by assumption). CI Paper build differs from the local bundle build (142/67 static init in b223/225/226) — build drift documented; static init of GameProtocols is NOT the wire order source of truth; IdDispatchCodec.build() = insertion order (bytecode-proven, no sort).
- block_update: iterations 3-4 proved the double replay added ZERO packets on any id (census diff) — Paper 26.3 emits NO clientbound single block updates to late-joining bots. Leg moved to netty-level proof: NetWatch logs outbound ClientboundBlockUpdatePacket (pos + translated block key) and ClientboundSectionBlocksUpdatePacket (entries) at deep level; bot runs --no-expect-block; section parser (ids 84..=86, 26.3 SectionPos packing x<<42|y|z<<20, entries varLong state<<12|local) kept as an observation leg + 2 unit tests.
- Also: census-visual-263.py restored INTO the repo (raw inventory JSON + registry-migration proof: 26.3 client code has ZERO post_effect path literals vs 1.21.1 six hardcoded chains); protocol doc §10 (live-vs-static table drift); worklog updated every step; every push within minutes of the work (owner directive).

Stage Summary:
- Five defects root-caused from CI logs alone (whitelist, window path, join-event, channel field, block-update policy). Bot contract: 7/9 live-verified, block leg re-proven at netty level, attach fixed for 26.3 — iteration 6 (dc3255d+f6a...) pending CI.
- The unknown_ids census + the 7 live-confirmed ids are the foundation of the M4 translation tables (per common-packet-drift doc §5 policy).

Stage Summary (Task 9 final):
- **M4 wire contract CLOSED on the live line**: integration @ d80ce4e ALL 3 JOBS GREEN (boot incl. restart contract + the new assert-M4 step; ci GREEN incl. release build).
- Final bot report: ok=true, joined, 1995 packets, checks: registries=32, entity table, joined play, add_entity, set_entity_data, remove_entities, postfx apply+clear, block_update leg (server-proven). Server census: total=1808, post_effects=3, block_updates=4 (air->iron_chain x2 outbound at netty level), modded_blocked=0.
- Translation-layer inputs pinned: live wire table (7 ids proven), drift policy (identity 11+5, drop-with-log 3), postfx wire codec validated both directions.
- Next (M4 continuation): net hooks DEEPENING — rewrite paths (entity metadata serializers 767->777, chunk-batch section states, postfx emulation toward a 767 client), RSS profiling gate (dispatch-only), then M5 visual harness.

---
Task ID: 10
Agent: main
Task: M5.2 — F2V 2.0 item visual parity v1: pack synthesis + config-phase pack contract + visual item leg (owner directive: a stock vanilla client must render modded content IDENTICALLY to a modded client)

Work Log:
- Deep pre-measurement (all from real artifacts, zero guesses):
  * client-26.3.jar version.json: pack_version resource_major=97/minor=1 (confirms the ROADMAP pin), protocol 777, world_version 5023, java 25.
  * pack.mcmeta 26.3 schema measured from the client's embedded datapacks: "min_format"/"max_format" (int or [major,minor] via PackFormat compactListCodec); legacy "pack_format" tolerated (DFU ignores unknown keys). Emitted shape: pack_format 97 + min/max_format [97,1].
  * items/ def schema measured from vanilla bow/compass defs: range_dispatch entries[].threshold + model nodes + fallback. custom_model_data referenced by ZERO vanilla defs (re-grepped over extracted assets) — the hook is free.
  * numeric.CustomModelDataProperty bytecode: optional "index" (NON_NEGATIVE_INT, default 0), reads DataComponents.CUSTOM_MODEL_DATA (first float). select variant exposes the same index.
  * atlases/items.json: directory source "item" scans ALL namespaces -> assets/<ns>/textures/item|block/** auto-atlased; NO atlas merge needed for v1.
  * paper-api 26.3.build.226-stable javap: ItemMeta#getCustomModelDataComponent/setCustomModelDataComponent + CustomModelDataComponent.setFloats (full 26.3 component), ItemStack#editMeta, Player#setResourcePack family, PlayerResourcePackStatusEvent$Status (ACCEPTED/SUCCESSFULY_LOADED present).
  * DESIGN DECISION (play-phase id question sidestepped): the vanilla pack push rides the CONFIG phase (resource_pack_push = config clientbound id 9, measured) via server.properties resource-pack/resource-pack-sha1 -> bot needs NO play-phase id; plugin dynamic push deferred to M5.3.
- Implemented F2V pipeline v1 (paper-plugin):
  * dev.cim.f2v.F2vPack — manifest-driven pack builder: model graph copy at original paths (modded parents recursed, vanilla stop), texture copy for modded ns (item|block dirs only; outside -> warning + skip, selftest CAUGHT this bug: copy-without-atlas shipped dead bytes), items/<host>.json range_dispatch with vanilla fallback, pack.mcmeta 97.1, deterministic zip (sorted entries + DOS-epoch timestamps + level 9), sha1 + build-report + resolved-manifest artifacts; --build and --selftest mains.
  * dev.cim.f2v.PackHttp — loopback JDK httpserver serving a FIXED payload snapshot at /cim-f2v-pack.zip (integrity-first: the served bytes are the sha1 the properties cover).
  * CimPlugin: f2vInit() on enable (reuse pre-boot build + rebuild-verify; graceful degrade on failure), PlayerResourcePackStatusEvent logging, runNetLeg leg 5 = giveVisualItem("cimbot", "cimtest:test_block") — host item + CustomModelDataComponent floats via callSync (main-thread rule), inventory-verified marker.
- cimbot (cim-proto): config resource_pack_push now ACCEPTS (en_resource_pack_action: uuid + varint action; accepted=3 then successfully_loaded=0 — the vanilla-client walk; ordinals measured into ids.rs), report check "resource pack accepted (F2V)"; declined remains via the shared encoder; en_resource_pack_declined removed (dead code). 17/17 cim-proto tests.
- CI wiring: integration.yml pre-boot step (build pack BEFORE boot — the config-phase push requires the final sha1 at JVM start; determinism contract = second build diff), server.properties += resource-pack/resource-pack-sha1 (fixed loopback port 25580), new "assert F2V visual pipeline (M5.2)" step (built/integrity/serving/ACCEPTED/SUCCESSFULY_LOADED/inventory-verified + zip content greps); ci.yml paper-plugin job gained the selftest step.
- Local light checks (per owner policy — no server runs): mvn package OK (JDK 25, build 226), F2vPack --selftest ALL OK, real-cimtest build = 4 zip entries/0 warnings/sha1 stable across builds (18f6bb41...), cargo fmt/clippy clean, workspace tests 111/0.
- Docs: runtime-analysis/f2v2-pack-synthesis-26-3.md (all measurements + scope boundaries), README index, ROADMAP M5.2 tick (v1 done; equipment/palettes/OBJ/postfx-chains stay M6; xvfb screenshot harness stays the M5 accept remainder).

Stage Summary:
- The stock-client visual channel is now measurable end-to-end: pack built deterministically from mod jars -> config-phase push (vanilla mechanism) -> bot walks the real accept state machine -> host item + CMD rides the vanilla container codec -> pack range_dispatch turns CMD into the modded model on any vanilla 26.3 client.
- Postfx (M4 leg) + item CMD (M5.2) = the two measured vanilla channels the F2F vision rides; both proven on the live line once CI goes green.
- Next: CI validation -> M5.3 (play-phase push id via census, xvfb real-client screenshot harness for pixel ground truth, postfx chain contributions from packs), then M6 broadening (equipment, palettes, OBJ, block world visuals).

---
Task ID: 10-b
Agent: main
Task: M5.2 iteration 2 — red-run diagnosis + fixes + M5.3a groundwork (modded postfx chain, registry-name census)

Work Log:
- integration@3cab5d8 measured (from logs only, zero extra runs): pre-boot pack built TWICE byte-identical ON CI (sha1=18f6bb41... — SAME as the local sandbox build: cross-machine determinism PROVEN), plugin boot-time rebuild integrity=ok, PackHttp serving, bot ACCEPTED the properties pack push and completed its walk (check PASS), postfx census=3 (init-clear + creeper + clear), block legs intact.
- TWO defects measured precisely:
  1) "f2v item: player not online" — the give leg was LAST in the net leg; the bot exits as soon as its own contract completes (block legs took longer than the bot's patience). FIX: give moved to leg 0 (right after net-watch attach, before the boat).
  2) "bot never ACCEPTED the pack (server-side)" — WRONG hypothesis in my assert: the bot DID accept (wire packets sent, check PASSED). What never happened is the SERVER-SIDE PlayerResourcePackStatusEvent — MEASURED: paper-api 26.3 has exactly ONE pack status event and Paper fires it for PLAY-phase pushes only; config-phase (properties) packs do not fire it. FIX: dropped the two unreachable status greps from the assert (bot-side wire accept IS the contract); event handler stays as play-phase diagnostics; apply-side proof explicitly deferred to the M5 xvfb real client. Documented in f2v2-pack-synthesis-26-3.md §6.
- M5.3a groundwork implemented (measure-first):
  * F2vPack now emits assets/c-imagine/post_effect/cimsepia.json on every non-empty pack — a sepia chain composed PURELY from vanilla programs (color_convolve ColorConfig 3x vec3 + blit BlitConfig, swap->minecraft:main, the measured vanilla grammar). Real pack: 5 entries, sha1 fb898ca3..., selftest extended (composition + main-terminus invariants).
  * Net leg requests c-imagine:cimsepia between creeper apply and clear; assert: postfx census >= 3 + the request marker. Whether the CLIENT resolves pack-contributed chains is measured later by the xvfb harness.
  * cimbot registry_names census added: every dynamic registry key streamed in config now lands in the report (ground truth for post_effect sync-vs-pack question — the M5.3 design fork).
- NetWatch: container_set_content watcher (deep) — slot count + the first item carrying a custom_model_data component via DataComponents.CUSTOM_MODEL_DATA reflection; assert "net watch: container content -> cimbot .*cmd=" proves the CMD rides the vanilla container codec to the client.
- Local: mvn OK, selftest ALL OK, cargo fmt/clippy/test clean (17/17 cim-proto).

Stage Summary:
- The whole give->wire->pack dispatch chain is instrumented; the single red assert was an unmeasured server-side event assumption, now measured and documented.
- Next: CI green on iteration 2 -> registry_names census answers the postfx delivery question -> M5.3: xvfb real-client harness (download+apply+screenshot) for pixel ground truth.

---
Task ID: 10-c
Agent: main
Task: M5.2 iteration 3 — wire-CMD channel measured + fixed; M5.3 xvfb harness scaffolded

Work Log:
- integration@1a1a5ce measured: give leg PASS (marker present), modded chain c-imagine:cimsepia REACHED THE BOT on the wire (POST_EFFECTS: ["c-imagine:cimsepia"], checks PASS) — the M5.3a request leg is proven live. Single red: "container content" watcher saw nothing — census shows ClientboundContainerSetSlotPacket=3 and ZERO content packets: addItem syncs via the SLOT packet on 26.3. Watcher now covers both classes with a shared describeItemCmd helper (DataComponents.CUSTOM_MODEL_DATA reflection).
- visual.yml M5.3 harness scaffolded (dispatch-only): scripts/fetch-visual-client.py resolves the piston runtime (dry-run verified: client 41.5 MB + 66 libs 83.4 MB + assets[34] 485 MB, java 25, mainClass net.minecraft.client.main.Main, classpath + launch line with --quickPlayMultiplayer); the job stages bare Paper (crussty init, NO module — the pack pipeline is plugin-only), builds the pack pre-boot, boots with a console FIFO, joins with the real client under Xvfb (software GL), and enforces the 3-screenshot pixel contract: baseline (empty) vs vanilla paper vs CMD paper — AE(cmd,vanilla) > 0 is the range_dispatch proof; evidence uploaded.

Stage Summary:
- The full visual chain is now instrumented end-to-end: pack -> config push -> bot accept (wire) -> give -> SLOT packet carries CMD (wire) -> pack range_dispatch (pixel proof pending the xvfb run).
- Next: integration green -> dispatch visual.yml once -> fix harness first-run issues -> M5.3 done + registry_names census answers the postfx delivery fork.
