#!/usr/bin/env python3
"""mesh-diff selftest: the loader contracts + the delta notes."""
import importlib.util
import json
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "mesh_diff", os.path.join(HERE, "mesh-diff.py"))
m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m)

bad = 0

d = tempfile.mkdtemp()
# The 42-col capture row (the hourglass shape): sprite, shade, tint,
# 4x(x,y,z,u,v), 4x rgb, 4x alpha, material, block, sky.
row = ("fixture:block/glass\t1\t-1"
       + "".join(f"\t{x}\t{y}\t{z}\t{u}\t{v}" for x, y, z, u, v in
                 [(0, 0, 0, 0, 0), (16, 0, 0, 16, 0), (16, 16, 0, 16, 16), (0, 16, 0, 0, 16)])
       + "".join("\t1\t1\t1" for _ in range(4))
       + "".join("\t1.0" for _ in range(4))
       + "\tcutout\t13\t15")
mesh = os.path.join(d, "m.tsv")
open(mesh, "w").write(row + "\n")
quads = m.load_mesh(mesh)
ok = len(quads) == 1 and quads[0]["sprite"] == "fixture:block/glass" \
    and quads[0]["material"] == "cutout" and quads[0]["light"] == (13, 15) \
    and quads[0]["bounds"] == [0, 0, 0, 16, 16, 0]
bad += not ok
print(("ok   " if ok else "FAIL ") + "42-col row loads with material+light+bounds")

# The legacy 39-col row (no material, no light).
legacy = row.split("\tcutout\t13\t15")[0]
open(mesh, "w").write(legacy + "\n")
quads = m.load_mesh(mesh)
ok = len(quads) == 1 and quads[0]["material"] is None and quads[0]["light"] is None
bad += not ok
print(("ok   " if ok else "FAIL ") + "legacy row keeps None material/light")

# The composite model contract.
model = os.path.join(d, "g0.json")
json.dump({
    "parent": "minecraft:block/block",
    "render_type": "minecraft:cutout",
    "elements": [
        {"from": [0, 0, 0], "to": [16, 16, 0],
         "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#g"}}},
        {"from": [4, 4, 8], "to": [12, 12, 9], "faces": {}},
    ],
}, open(model, "w"))
parsed = m.load_model(model)
ok = parsed["render_type"] == "minecraft:cutout" and len(parsed["elements"]) == 2 \
    and parsed["elements"][0]["faces"]["north"] == [0, 0, 16, 16]
bad += not ok
print(("ok   " if ok else "FAIL ") + "model json loads elements+faces+render_type")

# The delta notes.
ok = "ZERO elements" in m._note(3, 0, quads, parsed)
bad += not ok
print(("ok   " if ok else "FAIL ") + "empty-composite note names the invisible class")
ok = "collapsed" in m._note(25, 5, quads, parsed)
bad += not ok
print(("ok   " if ok else "FAIL ") + "collapse note names the survivor question")
ok = "1:1" in m._note(2, 2, quads, parsed)
bad += not ok
print(("ok   " if ok else "FAIL ") + "1:1 note for equal counts")

print("FAILURES:", bad)
sys.exit(1 if bad else 0)
