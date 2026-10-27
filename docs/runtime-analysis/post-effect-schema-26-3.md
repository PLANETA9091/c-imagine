# Post-effect chain schema (26.3) — the F2V 2.0 composition vocabulary

Measured from `client-26.3.jar` (`e877b6a0...`), `assets/minecraft/post_effect/*.json`
(5 chains) and `assets/minecraft/shaders/**` (93 files). This is the grammar
the M5/M6 F2V 2.0 pack builder emits and the server pushes by id over
`ClientboundPostEffectsPacket` (wire format in
[`common-packet-drift-767-777.md`](common-packet-drift-767-777.md)).

## 1. Chain JSON grammar

```json
{
  "targets": { "<name>": {} },                    // named intermediate render targets
  "passes": [
    {
      "vertex_shader":   "minecraft:core/screenquad",   // resource-location string
      "fragment_shader": "minecraft:post/<program>",
      "inputs":  [ { "sampler_name": "In", "target": "minecraft:main" } ],
      "output":  "swap",                          // target name or "minecraft:main"
      "uniforms": {                               // optional; grouped struct blocks
        "ColorConfig": [
          { "name": "RedMatrix", "type": "vec3", "value": [0.0, 0.0, 0.0] }
        ]
      }
    }
  ]
}
```

Measured invariants across all 5 vanilla chains:

* every pass renders a full-screen quad — `vertex_shader` is always
  `minecraft:core/screenquad` (except `spider.json`, whose blit passes use
  the geometric `minecraft:post/rotscale` vertex program);
* the LAST pass writes `minecraft:main`; intermediate passes write named
  `targets` (ping-pong through a single `swap` target is the vanilla idiom);
* `inputs[].target` may reference `minecraft:main`, a chain-local target, or
  a **LevelTargetBundle** named target owned by the renderer
  (`minecraft:entity_outline` — consumed by `entity_outline.json`);
* uniforms are struct groups: `name/type/value` triplets, types measured:
  `float`, `vec2`, `vec3`, `vec4`.

## 2. The five vanilla chains (census)

| chain | targets | passes | fragment programs | uniforms |
|---|---|---|---|---|
| `blur` | swap | 6 | `post/box_blur` | BlurConfig(vec2, float) |
| `creeper` | swap | 2 | `post/color_convolve`, `post/bits` | ColorConfig(3×vec3), BitsConfig(2×float) |
| `entity_outline` | swap | 4 | `post/blit`, `post/entity_sobel`, `post/entity_outline_box_blur` | BlurConfig, BlitConfig(vec4) |
| `invert` | swap | 2 | `post/blit`, `post/invert` | InvertConfig(float), BlitConfig |
| `spider` | large_blur, small_blur, temp, swap | 10 | `post/blit`, `post/box_blur`, `post/spiderclip` (+`post/rotscale` vertex) | BlurConfig, RotScaleConfig(vec2,vec2,float), SpiderConfig(2×vec4), BlitConfig |

## 3. Composition vocabulary (what a pack can build from)

All GLSL that ships in the client — a chain JSON composes these **without
writing a single shader line**:

* post fragment programs (8): `bits`, `blit`, `box_blur`, `color_convolve`,
  `entity_outline_box_blur`, `entity_sobel`, `invert`, `spiderclip`;
* post vertex programs (1): `rotscale` (screenquad is always available);
* `core/` render programs (40 pairs) — for chains that feed custom passes
  into the world pipeline (entity, terrain, block, clouds, sky, oit_*, …);
* `include/` GLSL fragments (17) — only relevant when a pack DOES ship its
  own shader files (legal: the loader reads `shaders/**` from any resource
  pack; `ShaderManager` resolves via FileToIdConverter).

## 4. What this means for F2V 2.0 (deferred to M5/M6 per roadmap)

1. **Server-driven visuals without client mods**: the server picks any
   registered chain id and pushes it (`post_effects` packet). The 26.3
   registry carries `post_effect` as a *registry* — a resource pack (F2V
   pack) contributes new chains; the vanilla protocol applies them.
2. **The highlight/effect use case** the owner asked for is exactly the
   `entity_outline` shape: sobel over the outline target + blur + blit.
   A pack builder can clone/re-tint it with `BlitConfig`/`BlurConfig`
   uniforms only.
3. **Composition is data**: chains, targets and uniform values are plain
   JSON → the F2V pack builder can generate them from item/entity metadata
   (e.g. an ItemsAdder-style item declares `on_equip_post_effect`), no
   shader toolchain in the loop.
4. **Constraint**: a pushed chain id must exist client-side (pack-installed);
   failed loads surface via `failedPostEffects` (measured lifecycle) — the
   server can poll the same registry the client advertises.

## 5. Loader mechanics (client, measured)

* `ShaderManager` loads `post_effect/<id>.json` via `FileToIdConverter`;
* `LevelTargetBundle` builds the named targets (`entity_outline`,
  `always_on_top_depth`) via `withDefaultNamespace(...)`; `LevelRenderer`
  routes passes (`addPass`, `clear`);
* legacy `shaders/post/*.json` + `shaders/program/*` are GONE in 26.3
  (1.21.1 had 6 chain jsons + 9 program jsons); pipelines are declared in
  code (`RenderPipelines`, blaze3d rework) — chain JSON is the only
  data-driven surface left, which is why F2V 2.0 targets it.
