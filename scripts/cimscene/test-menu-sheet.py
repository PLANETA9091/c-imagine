#!/usr/bin/env python3
"""Checks the menu verdict: only an identical menu area may be GREEN."""
import importlib.util
import os
import sys
import tempfile

from PIL import Image

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("menu_sheet", os.path.join(here, "menu-sheet.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

d = tempfile.mkdtemp()
a, b, c, e = (os.path.join(d, n) for n in ("a.png", "b.png", "c.png", "e.png"))
base = Image.new("RGB", (1280, 720), (10, 20, 30))
base.save(a)
base.save(b)
one = base.copy()
one.putpixel((640, 360), (10, 20, 31))
one.save(c)
outside = base.copy()
outside.putpixel((2, 2), (255, 0, 0))
outside.save(e)
slots = [(8, 18), (152, 124)]
art = os.path.join(d, "art.png")
mask = Image.new("RGBA", (1280, 720), (0, 0, 0, 0))
mask.paste((200, 200, 200, 255), (600, 300, 700, 400))
mask.save(art)
bad = 0
for label, got, want in [
    ("identical frames", m.judge("x", True, "", m.compare(a, b, slots, 3))[0], "GREEN"),
    ("one pixel off by one", m.judge("x", True, "", m.compare(a, c, slots, 3))[0], "RED"),
    ("difference outside the menu", m.judge("x", True, "", m.compare(a, e, slots, 3))[0], "GREEN"),
    ("art mask sees a changed pixel", m.judge("x", True, "", m.compare(a, c, slots, 3, art))[0], "RED"),
    ("art mask ignores the world around it", m.judge("x", True, "", m.compare(a, e, slots, 3, art))[0], "GREEN"),
    ("menu that did not open", m.judge("x", False, "no-opener", m.compare(a, b, slots, 3))[0], "RED"),
    ("missing frame", m.judge("x", True, "", m.compare(a, "/nonexistent", slots, 3))[0], "RED"),
]:
    ok = got == want
    bad += not ok
    print(("ok   " if ok else "FAIL ") + label + ": " + got)
sys.exit(1 if bad else 0)
