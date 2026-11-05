#!/usr/bin/env python3
"""Rebuild 26.3 entity textures from the 1.21.1 art.

26.3 moved the cow and pig onto new 64x64 layouts, so the old PNGs cannot be dropped
in. entity-uv-map.tsv (measured from both clients' createBodyLayer) gives every cube
face's old and new rectangle. Boxes 26.3 added are cleared: the old art had them
painted on the head. Nothing Mojang-owned is stored in the repo: the art is read from
the 1.21.1 client resources at build time.

Usage: entity-remap.py <old-client-extra.jar> <map.tsv> <pack-overlay-dir>
"""
import io
import pathlib
import sys
import zipfile

from PIL import Image

PREFIX = "assets/minecraft/textures/entity/"


def main() -> int:
    jar = zipfile.ZipFile(sys.argv[1])
    overlay = pathlib.Path(sys.argv[3])
    out = None
    src = None
    target = None

    def flush():
        if out is not None:
            path = overlay / (PREFIX + target)
            path.parent.mkdir(parents=True, exist_ok=True)
            out.save(path)

    for line in open(sys.argv[2], encoding="utf-8"):
        c = line.rstrip("\n").split("\t")
        if c[0] == "T":
            flush()
            target = c[1]
            src = Image.open(io.BytesIO(jar.read(PREFIX + c[2]))).convert("RGBA")
            out = Image.new("RGBA", (int(c[5]), int(c[6])), (0, 0, 0, 0))
        elif c[0] == "R":
            s = [int(v) for v in c[1:5]]
            d = [int(v) for v in c[5:9]]
            tile = src.crop(s)
            if c[9] == "1":
                tile = tile.transpose(Image.FLIP_LEFT_RIGHT)
            out.paste(tile, (d[0], d[1]))
        elif c[0] == "C":
            d = [int(v) for v in c[1:5]]
            out.paste((0, 0, 0, 0), d)
    flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
