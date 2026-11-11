# F2V translation coverage — the M4 open-blocker audit (2026-11-10)

Measured answer to the roadmap blocker "M4: establish complete live translation
coverage" — which protocol paths are translated WHERE today, which are honestly
absent, and what actually requires packet-level handling. Every row cites the
run/leg that proves it; nothing here is inferred from plans alone (the blocker's
own warning: mapping tables and translation plans prove no translator).

## The architecture the audit measures

Two stacks, one contract (`docs/ROADMAP.md` M5.5):

- **truth / seed legs** — REAL NeoForge 1.21.1 servers + real modded clients.
  No translation involved; they define the picture and the manifests.
- **ours leg** — Paper 26.3 authoritative + c-imagine hosting the layer; a
  vanilla 26.3 client. EVERYTHING the client sees goes through Paper's own
  network stack — the layer has no network listener (the M2/M3 lifecycle slice
  binds `server-port=0` and never serves a client by design). Translation
  therefore happens BEFORE packet creation, in the facade, or in the pack
  channel — not on the wire.

## Coverage table (measured 2026-11-10)

| Protocol path | Mechanism on the ours leg | Evidence | Status |
| --- | --- | --- | --- |
| login → config → play (777) | Paper native; the protocol bot rides the full contract | `parity-diff`/M4 acceptance: 1995+ packets, zero kicks | **covered** (native) |
| outbound block updates (vanilla states) | Paper native over the authoritative world | every gallery run's setblock screenshots | **covered** (native) |
| outbound block updates (modded states) | facade: `KernelBridge.layerWrite` → Paper stand-in + sidecar journal; reads translate back | bridge smoke `ALL OK` (M3.1, CI) | **projected** — no visual (the pack channel carries the picture) |
| chunk/section updates | Paper native (stand-ins are real blocks) | every run | **covered** (native) |
| entity spawn/metadata/destroy | facade: M3.4 projection, `entity_type` remap through the M3.2 tables | `boat split ok` + `identity ok` (CI, polls=1) | **projected** |
| items/containers (the wire facts) | the pack channel's CMD rides vanilla container codecs; `NetWatch` deep-census logs slot/content CMD | M5.2 visual item leg; NetWatch `container slot ->` lines | **covered** (pack channel + census) |
| `post_effect` family | Paper native 26.3 channel; the bot asserts apply/clear | M4 acceptance: `minecraft:creeper` applied, `[]` cleared | **covered** (native + bot proof) |
| sounds | the replay channel: manifest-driven mapped vanilla stand-ins, registry-validated at replay | SOUND_FLIP=on, mapped-identity 148/148 (14-ds control, three runs) | **replayed (substituted)** — the mod's own assets are NOT played; audit item 1 |
| particles | the replay channel, same shape; the seed viewer fix (14-dw) precedes the measurement | mapped-identity 0/38 with the silent replay (14-ds forensics) | **replayed (substituted)** — the viewer fix must land first, then the 38 families map |
| biome tint surface | the fillbiome walk + the diag join | 14-du: 61 diag pages, uniform ~911.9K ae constant-offset delta, same geometry | **diagnostic** — the 14-cz captured-shade route is the named fix lane |
| block/gallery visuals | the pack channel: seed-captured meshes → composites → ItemDisplay + CMD | campaign ledger 98.45% (#310-era), the #311 verdicts | **covered** (pack channel) |
| BlockEntity renderer content | the pack channel carries the captured static surface; the BER runtime is M5.6 | nixie text: 256/256 restored streams (15:25 entry), residual named | **partial, named** (M5.6 owns the dynamic half) |
| modded custom payloads (mod net channels) | NOT translated — layer channels never reach the wire; `NetWatch` censuses 0 modded-id leaks (measured `modded_blocked=0` across 1995+ packets) | M4 acceptance bot runs | **absent, honest** — a 26.3 client cannot speak a mod channel; the surface the M5.6/M7 leg owns |
| incoming player actions → the layer | NO bridge path — Paper handles interactions against stand-ins; layer mods never see them | no code path exists (this audit) | **absent, named** — the M3/M7 blocker's mechanics slice |
| reconnects / mid-session state sync | unmeasured on the ours leg (the bot legs assert one clean session) | — | **unmeasured, named** |
| layer-served clients (the layer's own listener) | out of scope by architecture — the layer binds port 0; Paper is the only client-facing server | M2/M3 lifecycle slice (2026-11-10) | **not a translation path** |

## What actually requires packet handling (the blocker's second question)

1. **Nothing outbound for the current parity model.** Paper's world holds
   vanilla-expressible state (stand-ins + the pack channel), so every outbound
   packet is already vanilla-clean; the measured leak counter is zero. The
   Rust `cim-f2v` tables+plan stay the validated fallback for any future
   wire-level rewrite need; they are NOT a live translator today and nothing
   depends on them being one.
2. **Incoming player actions into the layer** is the first real bridge path to
   build (M3/M7): forward Paper's interaction events into the layer's server
   construct so mod code reacts (Create's wrench, doors, machines) — that is
   gameplay machinery, not packet translation, and it rides the lifecycle
   slice's real server.
3. **Custom payloads** become a translation path only if a mod must push
   mod-only data to a vanilla client's screen (JEI-style overlays). The pack
   channel + HUD channel (M6.2) are the carriers there, not raw packets.

## The audit's own next measurements

- the reconnect/state-sync leg: the protocol bot rejoining mid-session against
  a dirty world (named above, unmeasured);
- the particle replay's mapped-identity after 14-dw's live viewer (the 0/38
  baseline must move before any fidelity claim);
- `NetWatch`'s census snapshot shipped per gallery run (it is already logged;
  the verdict's artifact should retain it) so this table's wire rows stay
  measured, not assumed.
