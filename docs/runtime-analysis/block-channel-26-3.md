# Block channel on the 26.3 stock client — bytecode-measured design

Date: 2026-10-28 (post-M5.3). Method: `javap` over the piston client.jar 26.3
(`e877b6a07acd633fb3bb475002175cec036e7b87`, 41,483,720 bytes) + the cached
paper-api 26.3 (`26.3.build.227-stable`). Goal: the parity matrix §2.3 leg —
a modded block placed via the layer must render IDENTICALLY on the vanilla
client (F2F owner directive: "just identical").

## 1. Why the stand-in block matters (measured dead ends)

The M3.1 sidecar projects modded blocks as `structure_void` stand-ins in the
Paper world. For the VISUAL channel the stand-in must RENDER as the modded
cube on the stock client. Candidate measurement — the "invisible" blocks:

- `StructureVoidBlock#getRenderShape` (javap -c): returns
  `RenderShape.INVISIBLE` — CODE-side, not asset-side.
- `BarrierBlock#getRenderShape` (javap -c): `RenderShape.INVISIBLE` too.

Consequence: a pack-side `blockstates/structure_void.json` override pointing
at a real cube model CANNOT make these blocks render — the section renderer
skips INVISIBLE states before any model lookup (the small visible cube of
vanilla is special-case debug rendering, not the block model). Both blocks'
vanilla `models/block/*.json` are particle-only (`{"textures":{"particle":
"minecraft:item/..."}}`) — confirming they have no renderable model.

## 2. The measured stand-in: note_block (the Oraxen pattern)

`minecraft:note_block` measured properties (26.3 client + paper-api):

- renders via `MODEL` (no getRenderShape override — plain full cube);
- `NoteBlockInstrument` enum = **27 values** (26.3): harp, basedrum, snare,
  hat, bass, flute, bell, guitar, chime, xylophone, iron_xylophone,
  cow_bell, didgeridoo, bit, banjo, pling, **trumpet, trumpet_exposed,
  trumpet_oxidized, trumpet_weathered** (new 26.3 family), zombie,
  skeleton, creeper, dragon, wither_skeleton, piglin, custom_head;
- state space: instrument (27) x note (0..24) x powered (2) = **1350 slots**;
- vanilla `blockstates/note_block.json` uses a single `""` variant — the
  full property space is FREE for pack overrides;
- graceful degradation: a client WITHOUT the pack sees a normal note block
  (all instruments share the same note_block model in vanilla);
- `instrument`/`note` have no gameplay side effects in a peaceful harness
  world (powered stays false without redstone).

## 3. 26.3 blockstate loader (restructured but format-stable)

26.3 rebuilt the blockstate→model path around a record:

- `client.renderer.block.dispatch.BlockStateModelDispatcher` (record + CODEC):
  components `Optional<SimpleModelSelectors>` + `Optional<MultiPartDefinition>`;
  loader strings measured in the constant pool: `variants`, `multipart`,
  `"Neither 'variants' nor 'multipart' found"`,
  `"Exception loading blockstate definition: '{}' for variant: '{}'"` —
  the classic file format survives.
- `SimpleModelSelectors` = `Map<String, BlockStateModel$Unbaked>` + 
  `instantiate(StateDefinition, ...)`: for EVERY (key, model) pair it builds
  `VariantSelector.predicate(StateDefinition, key)` and accepts EVERY possible
  state matching it — many states -> one variant is native.
- `VariantSelector.predicate` (javap -c): COMMA_SPLITTER then
  EQUAL_SPLITTER; only the SPECIFIED properties enter the match map —
  **partial selector keys are wildcards for unspecified properties**
  (measured, not guessed). `instrument=creeper,note=13` matches both
  powered values.
- vanilla multi-property blockstates (oak_stairs.json) ship composite keys
  (`facing=east,half=bottom,shape=inner_left`) — the format the client
  itself consumes is exactly what the pack can emit.

## 4. The v2 block channel (implementation contract)

- **Stand-in**: `minecraft:note_block[instrument=<I>, note=<N>]` — each
  manifest row allocates one slot (1350 available; manifest-driven, like
  visual-items.tsv; no global intervals, no v1-style CMD collisions).
- **Pack emission**: ONE merged `assets/minecraft/blockstates/note_block.json`
  with `variants` keys `instrument=<I>,note=<N>` pointing at the MODDED model
  reference (`<ns>:block/<path>`) — the modded model graph + modded textures
  are copied from the mod jars at original paths (same auto-atlas rules as
  the items channel; cimtest's test_block references
  `minecraft:block/iron_block`, which needs no copy).
- **Placement leg** (paper-api 26.3 javap-proven):
  `org.bukkit.block.data.type.NoteBlock` (extends Powerable):
  `getInstrument()/setInstrument(org.bukkit.Instrument)` +
  `getNote()/setNote(org.bukkit.Note)`; `org.bukkit.Instrument.CREEPER`
  exists; `new org.bukkit.Note(int)` is public. The harness places the
  stand-in IN FRONT of the viewer: `player.getLocation().getDirection()`
  (horizontal projection), 2.5 blocks ahead at feet level — a full cube
  centered in a level (pitch 0) join camera view.
- **Identity contract**: the modded client renders the real
  `cimtest:test_block` (`blockstates/test_block.json` ->
  `cimtest:block/test_block` -> cube_all/iron_block); the stock client
  renders note_block[creeper,13] whose pack override points at the SAME
  model JSON — model-identical, texture-identical, lighting from the same
  client-side engine. Pixel diff vs the NeoForge ground truth = the M5/M6
  parity-diff job.
- **Virtual blocks** (F2F §2.3 budget note): `block_display` entities
  showing `note_block[instrument=I,note=N]` ride the SAME override — the
  display-entity block channel inherits this surface for free (M6).

## 5. Wire safety (no drift involvement)

The visual harness boots bare Paper 26.3 + the vanilla 26.3 client — both
ends share ONE blockstate table (DataVersion 5023); the blockstate id for
note_block[instrument=creeper,note=13] needs NO translation. The M3.2 drift
tables (1.21.1 <-> 26.3) matter only when the SHADOW layer projects blocks —
26.3's instrument enum grew (trumpet family) vs 1.21.1's 23 values, so a
1.21.1 mod asking for `trumpet` has no 1.21.1 state; the allocator must only
hand out instruments the SOURCE version knows (harness uses creeper — known
to both).
