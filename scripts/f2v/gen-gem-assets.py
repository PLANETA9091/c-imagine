#!/usr/bin/env python3
"""Generate the cimtest palette-channel assets (M6 palette channel).

Produces THREE deterministic RGBA PNGs + the item model json in
test-mods/src/main/resources/assets/cimtest/:

  textures/item/test_gem.png        — 16x16 modded gem sprite (3 flat colors
                                      + transparent), the PALETTE BASE
  textures/palettes/gem_key.png     — 3x1 palette KEY: exactly the gem's
                                      three opaque colors (the paletted_
                                      permutations matcher is EXACT ARGB)
  textures/palettes/gem_red.png     — 3x1 permutation: the red recolor
  models/item/test_gem.json         — layer0 = cimtest:item/test_gem

The pixel layout is deterministic (no RNG) so the F2vPack sha1 stays stable.
Pure stdlib (zlib + struct) — no PIL on the sandbox.
"""
import pathlib
import struct
import zlib

OUT = pathlib.Path(__file__).resolve().parents[2] / "test-mods" / "src" / "main" / "resources" / "assets" / "cimtest"

# ARGB colors (alpha 255): the THREE flat colors of the gem + the recolor
DARK = (0x1F, 0x7E, 0x73, 0xFF)
MID = (0x2B, 0xB3, 0xA3, 0xFF)
LIGHT = (0x4A, 0xED, 0xD9, 0xFF)
TRANSPARENT = (0, 0, 0, 0)
RED_DARK = (0x8F, 0x1F, 0x1F, 0xFF)
RED_MID = (0xC2, 0x2F, 0x2F, 0xFF)
RED_LIGHT = (0xFF, 0x5A, 0x5A, 0xFF)


def png(width: int, height: int, pixels: list[list[tuple[int, int, int, int]]]) -> bytes:
    """Minimal deterministic RGBA8 PNG writer (filter 0 scanlines)."""

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(bytes(c) for c in row)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def gem_sprite() -> list[list[tuple[int, int, int, int]]]:
    """16x16 manhattan-diamond gem, deterministic 3-color layout."""
    px: list[list[tuple[int, int, int, int]]] = []
    for y in range(16):
        row = []
        for x in range(16):
            d = abs(x - 7) + abs(y - 7)
            if d > 7:
                row.append(TRANSPARENT)
            elif d == 7:
                row.append(DARK)      # outline
            elif d >= 5:
                row.append(MID)       # mid facets
            else:
                row.append(LIGHT)     # core
        px.append(row)
    return px


def swatch(colors: list[tuple[int, int, int, int]]) -> list[list[tuple[int, int, int, int]]]:
    """One-row palette PNG: column i = colors[i] (the palette-key contract)."""
    return [[c for c in colors]]


def main() -> int:
    (OUT / "textures" / "item").mkdir(parents=True, exist_ok=True)
    (OUT / "textures" / "palettes").mkdir(parents=True, exist_ok=True)
    (OUT / "models" / "item").mkdir(parents=True, exist_ok=True)
    (OUT / "textures" / "item" / "test_gem.png").write_bytes(png(16, 16, gem_sprite()))
    # the KEY lists the gem's opaque colors in a FIXED order; the PERM maps
    # position i -> the recolored color (exact ARGB matcher)
    key = [DARK, MID, LIGHT]
    red = [RED_DARK, RED_MID, RED_LIGHT]
    (OUT / "textures" / "palettes" / "gem_key.png").write_bytes(png(3, 1, swatch(key)))
    (OUT / "textures" / "palettes" / "gem_red.png").write_bytes(png(3, 1, swatch(red)))
    (OUT / "models" / "item" / "test_gem.json").write_text(
        '{\n  "parent": "minecraft:item/generated",\n'
        '  "textures": {"layer0": "cimtest:item/test_gem"}\n}\n',
        encoding="utf-8")
    print("gem assets written to", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
