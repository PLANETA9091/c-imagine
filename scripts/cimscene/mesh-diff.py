#!/usr/bin/env python3
"""The COMPOSITE-VS-CAPTURE geometry diff (lane 14-dg, report-only).

The 14-dg forensics kept hitting the same question by hand: a state's
QuadDump capture (quads/<hash>.tsv) carries N quads — the pack's baked
composite (models/gallery/gK.json) renders M elements — WHICH quads
survived, which got dropped, and where do the survivors' bounds/UV
windows disagree? This tool reads both sides and answers it:

  mesh side     the QuadDump TSV rows (42/41/40/39-col shapes): per quad —
                the sprite, the material, the light tail, the xyz bounds
                (the capture's local block space, 0..16 per cell), the uv
                window per sprite.
  composite side the model JSON the pack built (the GalleryPack
                synthesizeQuadModel output: the element boxes + the face
                UVs + the render_type + the per-element tint), OR any
                model json (the static composites too — the element array
                is the same contract).

The report: per side the quad/element count, the world bounds, the
per-sprite census, the material/render_type, and — when both sides
name the same sprite — the per-sprite uv-window deltas (the 14-dp/dl
fluid class's UV-phase instrument, generalized). Exit code 0 always:
this never gates, it magnifies.

Usage:
  mesh-diff.py --mesh quads/<hash>.tsv --model models/gallery/gK.json
               [--json out.json] [--max N]

  --mesh   the captured mesh tsv (the seed's quads/ dir)
  --model  the baked composite model json (the pack's models/ dir)
  --json   also write the machine-readable report
  --max    cap the per-element listing (default 12)
"""
import argparse
import json
import sys


def load_mesh(path):
    """The capture's quads: {sprite, material, light, bounds, uv_windows}."""
    quads = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            c = line.split("\t")
            if len(c) < 23:
                continue
            sprite = c[0]
            verts = []
            for i in range(4):
                o = 3 + i * 5
                verts.append((float(c[o]), float(c[o + 1]), float(c[o + 2])))
            us = [float(c[3 + i * 5 + 3]) for i in range(4)]
            vs = [float(c[3 + i * 5 + 4]) for i in range(4)]
            material = c[39] if len(c) >= 40 and not _num(c[39]) else None
            light = None
            if len(c) >= 42:
                try:
                    light = (int(c[-2]), int(c[-1]))
                except ValueError:
                    light = None
            xs = [v[0] for v in verts]
            ys = [v[1] for v in verts]
            zs = [v[2] for v in verts]
            quads.append({
                "sprite": sprite,
                "material": material,
                "light": light,
                "bounds": [min(xs), min(ys), min(zs), max(xs), max(ys), max(zs)],
                "uv_window": [min(us), min(vs), max(us), max(vs)],
            })
    return quads


def _num(s):
    try:
        float(s)
        return True
    except ValueError:
        return False


def load_model(path):
    """The composite's elements: {from,to,faces{uv,texture},render_type}."""
    model = json.load(open(path, encoding="utf-8"))
    elements = []
    for el in model.get("elements", []):
        faces = {}
        for name, face in (el.get("faces") or {}).items():
            uv = face.get("uv")
            faces[name] = uv
        elements.append({
            "from": el.get("from"),
            "to": el.get("to"),
            "faces": faces,
            "shade": el.get("shade"),
        })
    return {
        "render_type": model.get("render_type"),
        "parent": model.get("parent"),
        "elements": elements,
    }


def bounds_of(boxes):
    if not boxes:
        return None
    out = [min(b[0] for b in boxes), min(b[1] for b in boxes),
           min(b[2] for b in boxes), max(b[3] for b in boxes),
           max(b[4] for b in boxes), max(b[5] for b in boxes)]
    return [round(v, 4) for v in out]


def census(quads, key):
    out = {}
    for q in quads:
        k = q.get(key)
        if k is None:
            k = "(none)"
        out[k] = out.get(k, 0) + 1
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mesh", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--json", dest="out_json")
    ap.add_argument("--max", type=int, default=12)
    a = ap.parse_args()

    quads = load_mesh(a.mesh)
    model = load_model(a.model)
    report = {
        "mesh": {
            "path": a.mesh,
            "quads": len(quads),
            "bounds": bounds_of([q["bounds"] for q in quads]),
            "sprites": census(quads, "sprite"),
            "materials": census(quads, "material"),
            # the light pairs serialize as "block/sky" strings (tuples are
            # not JSON keys)
            "lights": {f"{k[0]}/{k[1]}" if isinstance(k, tuple) else str(k): v
                       for k, v in census(quads, "light").items()},
        },
        "composite": {
            "path": a.model,
            "render_type": model.get("render_type"),
            "parent": model.get("parent"),
            "elements": len(model["elements"]),
            "bounds": bounds_of([e["from"] + e["to"] for e in model["elements"]
                                 if e["from"] and e["to"]]),
            "faces": sum(len(e["faces"]) for e in model["elements"]),
        },
        "delta": {},
    }
    mq, me = len(quads), len(model["elements"])
    report["delta"] = {
        "quads_vs_elements": [mq, me],
        "survivor_ratio": round(me / mq, 3) if mq else None,
        "note": _note(mq, me, quads, model),
    }
    # The per-sprite uv windows on the mesh side: the 14-dp/dl UV-phase
    # instrument's raw data (the composite's sheet windows are the bake's
    # own allocation — the join compares pixels; this names the geometry).
    wins = {}
    for q in quads:
        w = wins.setdefault(q["sprite"], q["uv_window"])
        w[0] = min(w[0], q["uv_window"][0])
        w[1] = min(w[1], q["uv_window"][1])
        w[2] = max(w[2], q["uv_window"][2])
        w[3] = max(w[3], q["uv_window"][3])
    report["mesh"]["uv_windows"] = {k: [round(v, 4) for v in wins[k]]
                                    for k in sorted(wins)}
    # The element listing (capped) — the bounds the composite actually bakes.
    report["composite"]["listing"] = [
        {"from": e["from"], "to": e["to"], "faces": sorted(e["faces"])}
        for e in model["elements"][: a.max]]
    print(json.dumps(report["delta"], indent=1))
    print("mesh:", report["mesh"]["quads"], "quads",
          "sprites:", report["mesh"]["sprites"])
    print("composite:", report["composite"]["elements"], "elements",
          "render_type:", report["composite"]["render_type"])
    if a.out_json:
        with open(a.out_json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=1, default=str)
    return 0


def _note(mq, me, quads, model):
    if mq == 0:
        return "empty capture (the observed no-draw law) — an element-bearing composite would be the bug"
    if me == 0:
        return "the composite carries ZERO elements for a %d-quad capture — the invisible-composite class" % mq
    if me < mq:
        return "%d capture quads collapsed to %d elements — the survivor census names what the bake merged or dropped" % (mq, me)
    if me > mq:
        return "%d elements for %d captured quads — the bake added geometry (the sheet tiles split)" % (me, mq)
    return "1:1 — the composite keeps every captured quad"


if __name__ == "__main__":
    sys.exit(main())
