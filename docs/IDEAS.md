# Idea backlog

## Post effects for lighting fixes on the our-side render (suggested 2026-11-08)

Minecraft 26.3 ships **post effects** (the `post_effect` / shader pipeline, loaded
from `post_effect/*.json` and driven by `PostChain` — e.g. entity/entity_outline,
transparency, the Fabulous graphics chain). They can fix visual problems that a
plain block/model/textures change cannot: the block texture stays exactly the
same, but the lighting around/over it can be re-graded by a post pass.

Where this helps the gallery gate (the captured-mesh route):

- Ours replays captured meshes as item_display composites of unlit models
  (`assets/c-imagine/models/gallery/unlit/*.json`, fullbright). Truth renders the
  real blocks through the vanilla lightmap (block light ~8 → ~0.5 warm deficit,
  smooth-light/AO gradients). A post chain applied to our rendered frame
  (per-page: the pages are separate screenshots, so a per-scene post chain is
  trivially scoped) could re-create the lightmap grade WITHOUT touching any
  texture: a multiply/tint pass (R≥G≥B warm curve, level ≈ lightmap(8,15)) or a
  selective darkening over the machine area.
- Cases where a post pass may still be needed even after the 14-cy brightness
  pin: per-vertex smooth-light/AO structure (the capture keeps only one light
  sample per quad — the dominant (block, sky) pair), the ae2 fluix NEUTRAL tint
  drift (0.90–0.915), any future class whose delta is a color grade rather than
  geometry.
- Mechanics to use: a custom `post_effect` JSON (input = main target, passes =
  color-multiply + optional mask), registered/enabled only while the gallery
  pages render; the client can toggle `GameRenderer` post chains per frame. The
  texture sheets in the pack stay byte-identical to the captured ones.

Status: the first move for the mekanism machines was the 14-cy captured-light
pin (Brightness override on the display), which uses the engine's own lightmap
and needs no shader work. Keep the post-effects route as the follow-up if any
class turns out to need a grade the lightmap can't express, or a spatially
limited correction (a mask over part of the frame).
