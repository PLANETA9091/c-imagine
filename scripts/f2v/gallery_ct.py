#!/usr/bin/env python3
"""14-ak: the Create connected-texture (CT) channel for the gallery pack.

Create's pillar family (ConnectedPillarBlock + RotatedPillarCTBehaviour,
create 6.0.10 — locked in mods.lock) renders its faces through CT sprite
shifting: per FACE, an 8-neighbourhood context is built and mapped to a
TILE INDEX; the quad's UVs are then remapped from the plain sprite into
that tile of the ``*_cut_pillar_connected`` (RECTANGLE, 4x4) or
``*_cut_cap_connected`` (OMNIDIRECTIONAL, 8x8) sheet. The truth leg (the
real client) runs CTModel.getQuads; our leg renders emitted pack models —
the shift must be BAKED into the emitted UVs.

Measured ground truth (this campaign, 14-ak):
- catnip SpriteShiftEntry.getUnInterpolatedU = (u-u0)/(u1-u0) — a FRACTION.
- 1.21.1 TextureAtlasSprite.getU (real bytecode) = u0 + (u1-u0)*u — also a
  fraction. So target px = (unU + off/sheetSize sheet px): the tile offset
  is off*16 px and the model-space remap is (u + offX*16)/sheetSize.
- the pillar side sheet's isolated tile (index x=0,y=3) is BYTE-IDENTICAL
  to the plain texture (ae=0) — the index semantics verified numerically.
- the connection properties are NOT compass directions for rotated axes
  (axis=X: UP->east, DOWN->west; axis=Z: UP->west, DOWN->east,
  EAST->north, WEST->south, N/S -> null).

This module is the SINGLE context implementation: the seed computes each
cell's per-face indices here; the replay reads them from the side files;
CTProbe.java verifies this port against the REAL create 6.0.10 classes.

Scenes contract: cell rows are ``cell<TAB>x<TAB>y<TAB>z<TAB>state``.
Missing positions are AIR. In the gallery scene class that is exact: the
truth world holds only the walk's placements (+ the flat ground, which is
connectivity-equivalent to air — never a same-block pillar).
"""
import pathlib
import re
import sys
from collections import defaultdict

# ---------------------------------------------------------------------------
# directions (vanilla get3DDataValue order — the ctx face order everywhere)
# ---------------------------------------------------------------------------
DIRECTIONS = ["down", "up", "north", "south", "west", "east"]
DIR_INDEX = {d: i for i, d in enumerate(DIRECTIONS)}

AXIS_OF = {"down": "y", "up": "y", "north": "z", "south": "z", "west": "x", "east": "x"}
AXIS_DIR = {"down": "-", "up": "+", "north": "-", "south": "+", "west": "-", "east": "+"}

_OPPOSITE = {"down": "up", "up": "down", "north": "south",
             "south": "north", "west": "east", "east": "west"}
_CLOCKWISE = {"north": "east", "east": "south", "south": "west", "west": "north"}


def opposite(d):
    return _OPPOSITE[d]


def clock_wise(d):
    """Direction.getClockWise() — horizontal only."""
    return _CLOCKWISE[d]


def from_axis_and_direction(axis, positive):
    if axis == "x":
        return "east" if positive else "west"
    if axis == "y":
        return "up" if positive else "down"
    return "south" if positive else "north"


def face_axis(d):
    return AXIS_OF[d]


def face_positive(d):
    return AXIS_DIR[d] == "+"


# ---------------------------------------------------------------------------
# the pillar family (create 6.0.10, verified against the jar in 14-ak)
# ---------------------------------------------------------------------------
PILLAR_STONES = [
    "andesite", "asurine", "calcite", "crimsite", "deepslate", "diorite",
    "dripstone", "granite", "limestone", "ochrum", "scorchia", "scoria",
    "tuff", "veridium",
]
PILLAR_BLOCKS = {f"create:{s}_pillar" for s in PILLAR_STONES}

# CT sheet sizes per face role (AllCTTypes sheetSize)
SHEET_SIDE = 4    # RECTANGLE,  *_cut_pillar_connected
SHEET_CAP = 8     # OMNIDIRECTIONAL, *_cut_cap_connected


def is_pillar_state(state):
    blk = state.split("[", 1)[0]
    return blk in PILLAR_BLOCKS


def parse_state(state):
    """ns:block[k=v,...] -> (block_id, props dict)."""
    blk = state.split("[", 1)[0]
    props = {}
    if "[" in state:
        body = state.split("[", 1)[1].rstrip("]")
        for pair in body.split(","):
            if "=" in pair:
                k, v = pair.split("=", 1)
                props[k.strip()] = v.strip()
    return blk, props


# ---------------------------------------------------------------------------
# ConnectedPillarBlock.connection — the property mapping (decompiled 6.0.10)
# ---------------------------------------------------------------------------
def connection_prop(axis, side):
    """The BooleanProperty name getConnection(state, side) reads, or None."""
    if face_axis(side) == axis:
        return None
    if axis == "x":
        return {"up": "east", "down": "west",
                "north": "north", "south": "south"}.get(side)
    if axis == "y":
        return {"north": "north", "south": "south",
                "east": "east", "west": "west"}.get(side)
    if axis == "z":
        return {"up": "west", "down": "east",
                "east": "north", "west": "south"}.get(side)
    return None


def get_connection(state_info, side):
    """ConnectedPillarBlock.getConnection(state, side)."""
    blk, props, axis = state_info
    prop = connection_prop(axis, side)
    if prop is None:
        return False
    return props.get(prop, "false") == "true"


# ---------------------------------------------------------------------------
# RotatedPillarCTBehaviour orientation overrides (decompiled 6.0.10)
# ---------------------------------------------------------------------------
def get_up_direction(state_axis, face):
    if state_axis == "y":
        return "up" if face_axis(face) in ("x", "z") else "north"
    along_x = state_axis == "x"
    if face_axis(face) in ("y",) and along_x:
        # base(vertical) = NORTH -> clockWise = EAST
        return "east"
    if face_axis(face) != state_axis and face_axis(face) != "y":
        return from_axis_and_direction(state_axis, along_x)
    return "up" if face_axis(face) in ("x", "z") else "north"


def get_right_direction(state_axis, face):
    if state_axis == "y":
        return "south" if face_axis(face) == "x" else "west"
    if face_axis(face) in ("y",) and state_axis == "x":
        # base for a vertical face: face-axis Y -> WEST; clockWise(WEST)=NORTH
        return "north"
    if face_axis(face) != state_axis and face_axis(face) != "y":
        return from_axis_and_direction("y", face_positive(face))
    return "south" if face_axis(face) == "x" else "west"


def reverse_uvs(state_axis, face):
    if state_axis == "x":
        return AXIS_DIR[face] == "-" and face_axis(face) != "x"
    if state_axis != "z":
        return False
    return face != "north" and AXIS_DIR[face] != "+"


def reverse_uvs_vertically(state_axis, face):
    if state_axis == "x" and face == "north":
        return False
    if state_axis == "z" and face == "west":
        return False
    return reverse_uvs(state_axis, face)


# ---------------------------------------------------------------------------
# the neighbourhood reader: pos -> state-info or None (air)
# ---------------------------------------------------------------------------
class World:
    def __init__(self):
        self.cells = {}  # (x,y,z) -> (block, props, axis)

    @staticmethod
    def state_info(state):
        blk, props = parse_state(state)
        axis = props.get("axis", "y")
        return (blk, props, axis)

    def add(self, x, y, z, state):
        self.cells[(x, y, z)] = self.state_info(state)

    def at(self, x, y, z):
        return self.cells.get((x, y, z))

    def block_at(self, x, y, z):
        c = self.cells.get((x, y, z))
        return c[0] if c else None


def _is_being_blocked(state_info, world, pos, other_pos, face):
    """RotatedPillarCTBehaviour.isBeingBlocked (6.0.10, decompiled).

    The override: only the AXIS-PARALLEL faces block (the side faces'
    in-plane connections are never occluded). The base chain: a full-face
    block at otherPos+face is a blocker iff the axis-coherence check
    passes AND the block at the blocking pos CONNECTS via the base 6-arg
    connectsTo (whose isBeingBlocked dispatches VIRTUALLY to this same
    override — the recursion walks the +face chain until air). MEASURED
    (14-ak, CTProbe vs the real classes): a same-block pillar BEHIND the
    candidate (as seen through the face) kills the connection.

    The gallery scene class: absent positions are AIR (the truth world
    holds only the walk's placements) — never full-face. A PRESENT cell
    is treated as full-face: the full-face check only matters when the
    blocking pos carries a SAME-BLOCK pillar (always a full cube); for
    any other block the recursion fails on the block-identity gate and
    the verdict matches the real shape path.
    """
    axis = state_info[2]
    if axis != face_axis(face):
        return False
    return _is_being_blocked_base(state_info, world, pos, other_pos, face,
                                  0)


def _is_being_blocked_base(state_info, world, pos, other_pos, face, depth):
    """ConnectedTextureBehaviour.isBeingBlocked (the base impl)."""
    if depth > 64:  # the chain ends at air long before this
        return False
    dx, dy, dz = AXIS_DELTA[face]
    bpos = (other_pos[0] + dx, other_pos[1] + dy, other_pos[2] + dz)
    blocking = world.at(*bpos)
    if blocking is None:
        return False  # air — Block.isFaceFull(empty) == false
    fa = face_axis(face)
    ci = {"x": 0, "y": 1, "z": 2}[fa]
    if pos[ci] != other_pos[ci]:
        return False
    # the base 6-arg connectsTo: !isBeingBlocked(VIRTUAL) && same block
    if _is_being_blocked_base(state_info, world, pos, bpos, face, depth + 1):
        return False
    return blocking[0] == state_info[0]


AXIS_DELTA = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1),
              "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}


def _connects_to(world, state_info, pos, other_pos, face, primary, secondary):
    """RotatedPillarCTBehaviour.connectsTo (6.0.10, decompiled)."""
    blk, props, axis = state_info
    other = world.at(*other_pos)
    if other is None:
        return False
    if other[0] != blk:
        return False
    if other[2] != axis:
        return False
    if _is_being_blocked(state_info, world, pos, other_pos, face):
        return False
    # copycat branches omitted — the gallery scene class never places one
    if primary is not None and face_axis(primary) != axis \
            and not get_connection(state_info, primary):
        return False
    if secondary is not None and face_axis(secondary) != axis:
        if not get_connection(state_info, secondary):
            return False
        if not get_connection(other, opposite(secondary)):
            return False
    return True


def _test(world, state_info, pos, face, h, v, sh, sv):
    """ConnectedTextureBehaviour.testConnection."""
    dx = AXIS_DELTA[h][0] * sh + AXIS_DELTA[v][0] * sv
    dy = AXIS_DELTA[h][1] * sh + AXIS_DELTA[v][1] * sv
    dz = AXIS_DELTA[h][2] * sh + AXIS_DELTA[v][2] * sv
    target = (pos[0] + dx, pos[1] + dy, pos[2] + dz)
    primary = None if sh == 0 else (opposite(h) if sh == -1 else h)
    secondary = None if sv == 0 else (opposite(v) if sv == -1 else v)
    return _connects_to(world, state_info, pos, target, face, primary, secondary)


def build_context(world, state_info, pos, face):
    """ConnectedTextureBehaviour.buildContext -> the 8-way context dict."""
    blk, props, axis = state_info
    positive = face_positive(face)
    h = get_right_direction(axis, face)
    v = get_up_direction(axis, face)
    if positive:
        h = opposite(h)
    if face == "down":
        v = opposite(v)
        h = opposite(h)
    flip_h = reverse_uvs(axis, face)          # reverseUVsHorizontally
    flip_v = reverse_uvs_vertically(axis, face)
    sh = -1 if flip_h else 1
    sv = -1 if flip_v else 1
    ctx = {}
    if True:  # requirement.up — both types require the axis-aligned set
        ctx["up"] = _test(world, state_info, pos, face, h, v, 0, sv)
        ctx["down"] = _test(world, state_info, pos, face, h, v, 0, -sv)
        ctx["left"] = _test(world, state_info, pos, face, h, v, -sh, 0)
        ctx["right"] = _test(world, state_info, pos, face, h, v, sh, 0)
    ctx["top_left"] = ctx["up"] and ctx["left"] and \
        _test(world, state_info, pos, face, h, v, -sh, sv)
    ctx["top_right"] = ctx["up"] and ctx["right"] and \
        _test(world, state_info, pos, face, h, v, sh, sv)
    ctx["bottom_left"] = ctx["down"] and ctx["left"] and \
        _test(world, state_info, pos, face, h, v, -sh, -sv)
    ctx["bottom_right"] = ctx["down"] and ctx["right"] and \
        _test(world, state_info, pos, face, h, v, sh, -sv)
    return ctx


def texture_index(ctx, kind):
    """AllCTTypes.RECTANGLE / OMNIDIRECTIONAL.getTextureIndex."""
    if kind == "side":  # RECTANGLE (sheetSize 4)
        x = 2 if (ctx["left"] and ctx["right"]) else (
            3 if ctx["left"] else (1 if ctx["right"] else 0))
        y = 1 if (ctx["up"] and ctx["down"]) else (
            2 if ctx["up"] else (0 if ctx["down"] else 3))
        return x + y * 4
    # OMNIDIRECTIONAL (sheetSize 8) — verbatim from AllCTTypes$4
    tile_x = 0
    tile_y = 0
    borders = (0 if ctx["up"] else 1) + (0 if ctx["down"] else 1) + \
        (0 if ctx["left"] else 1) + (0 if ctx["right"] else 1)
    if ctx["up"]:
        tile_x += 1
    if ctx["down"]:
        tile_x += 2
    if ctx["left"]:
        tile_y += 1
    if ctx["right"]:
        tile_y += 2
    if borders == 0:
        if ctx["top_right"]:
            tile_x += 1
        if ctx["top_left"]:
            tile_x += 2
        if ctx["bottom_right"]:
            tile_y += 2
        if ctx["bottom_left"]:
            tile_y += 1
    if borders == 1:
        if not ctx["right"] and (ctx["top_left"] or ctx["bottom_left"]):
            tile_y = 4
            tile_x = -1 + (1 if ctx["bottom_left"] else 0) + \
                (2 if ctx["top_left"] else 0)
        if not ctx["left"] and (ctx["top_right"] or ctx["bottom_right"]):
            tile_y = 5
            tile_x = -1 + (1 if ctx["bottom_right"] else 0) + \
                (2 if ctx["top_right"] else 0)
        if not ctx["down"] and (ctx["top_left"] or ctx["top_right"]):
            tile_y = 6
            tile_x = -1 + (1 if ctx["top_left"] else 0) + \
                (2 if ctx["top_right"] else 0)
        if not ctx["up"] and (ctx["bottom_left"] or ctx["bottom_right"]):
            tile_y = 7
            tile_x = -1 + (1 if ctx["bottom_left"] else 0) + \
                (2 if ctx["bottom_right"] else 0)
    if borders == 2 and (
        (ctx["up"] and ctx["left"] and ctx["top_left"]) or
        (ctx["down"] and ctx["left"] and ctx["bottom_left"]) or
        (ctx["up"] and ctx["right"] and ctx["top_right"]) or
        (ctx["down"] and ctx["right"] and ctx["bottom_right"])
    ):
        tile_x += 3
    return tile_x + 8 * tile_y


def face_kind(state_axis, face):
    """RotatedPillarCTBehaviour.getShift: the axis-parallel face -> CAP."""
    return "cap" if face_axis(face) == state_axis else "side"


def compute_cell_ctx(world, pos, state):
    """The 6 per-face tile indices in DIRECTIONS order (down,up,n,s,w,e)."""
    state_info = world.state_info(state)
    axis = state_info[2]
    out = []
    for face in DIRECTIONS:
        kind = face_kind(axis, face)
        ctx = build_context(world, state_info, pos, face)
        out.append(texture_index(ctx, kind))
    return out


def ctx_token(indices):
    return ",".join(str(i) for i in indices)


# ---------------------------------------------------------------------------
# the seed pass
# ---------------------------------------------------------------------------
CELL_RE = re.compile(r"^cell\t(-?\d+)\t(-?\d+)\t(-?\d+)\t([^\t]+)")


def read_scenes(scenes_dir, ns):
    """All pages -> {page -> [(x,y,z,state)]}.

    14-al: the world is PER PAGE. Every gallery page reuses the SAME wall
    coordinates (GALLERY_X0..5 / GALLERY_Y0..-54 / Z=9), and the truth leg
    clears + rebuilds the wall per page — cross-page neighbours never
    coexist. The previous global World let the LAST page's cells overwrite
    every earlier page's neighbourhood (measured: p202's granite axis=y
    east=true cell computed its north-face context against p218's ochrum
    cells and emitted the fully-isolated tile 12 where the per-page wall
    connects), silently isolating every non-final page's CT context.
    """
    pages = {}
    for p in sorted(pathlib.Path(scenes_dir).glob(f"gallery-{ns}-p*.tsv")):
        page = int(p.name.rsplit("-p", 1)[1].split(".")[0])
        rows = []
        for line in p.read_text(encoding="utf-8").splitlines():
            m = CELL_RE.match(line)
            if not m:
                continue
            x, y, z, state = int(m.group(1)), int(m.group(2)), \
                int(m.group(3)), m.group(4).strip()
            rows.append((x, y, z, state))
        pages[page] = rows
    return pages


def world_for_page(rows):
    """The page's own wall world (the manifest's placed cells = the world)."""
    world = World()
    for (x, y, z, state) in rows:
        world.add(x, y, z, state)
    return world


def write_side_files(pages, scenes_dir, ns):
    """gallery-<ns>-p<page>.ctx.tsv: x<TAB>y<TAB>z<TAB>i0,i1,...,i5."""
    out_dir = pathlib.Path(scenes_dir)
    written = 0
    for page, rows in sorted(pages.items()):
        world = world_for_page(rows)
        lines = ["# 14-ak CT tile indices per face (down,up,north,south,west,east)"]
        for (x, y, z, state) in sorted(rows):
            if not is_pillar_state(state):
                continue
            idx = compute_cell_ctx(world, (x, y, z), state)
            lines.append(f"{x}\t{y}\t{z}\t{ctx_token(idx)}")
            written += 1
        if len(lines) > 1:
            (out_dir / f"gallery-{ns}-p{page}.ctx.tsv").write_text(
                "\n".join(lines) + "\n", encoding="utf-8")
    return written


def suffixed_states(pages):
    """The distinct state#ctx rows replacing plain pillar rows."""
    seen = set()
    rows = []
    for page, cells in sorted(pages.items()):
        world = world_for_page(cells)
        for (x, y, z, state) in cells:
            if not is_pillar_state(state):
                continue
            idx = compute_cell_ctx(world, (x, y, z), state)
            key = f"{state}#{ctx_token(idx)}"
            if key not in seen:
                seen.add(key)
                rows.append(key)
    return sorted(rows)


def main(argv):
    if len(argv) >= 2 and argv[1] == "--selftest":
        return selftest()
    if len(argv) < 4:
        print(__doc__)
        print("usage: gallery_ct.py <scenes-dir> <ns> <visual-gallery.tsv>")
        print("       gallery_ct.py --selftest")
        return 2
    scenes_dir, ns, tsv = argv[1], argv[2], pathlib.Path(argv[3])
    pages = read_scenes(scenes_dir, ns)
    n_ctx = write_side_files(pages, scenes_dir, ns)
    rows = suffixed_states(pages)

    # enrich the gallery tsv: the plain pillar rows STAY (the replay's
    # fallback when a ctx side file is missing) and the suffixed
    # (state, ctx) variants are APPENDED (one pack composite each).
    out = ["# generated by gallery-manifest.py + gallery_ct.py (14-ak CT)"]
    seen = set()
    for line in tsv.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        if s not in seen:
            seen.add(s)
            out.append(s)
    out.extend(r for r in rows if r not in seen)
    tsv.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"gallery_ct: pages={len(pages)} pillar_ctx_rows={n_ctx} "
          f"distinct_states={len(out) - 1} (ct variants={len(rows)})")
    return 0


# ---------------------------------------------------------------------------
# selftest: the measured cases (each verified against the real 6.0.10
# classes by CTProbe — the numbers here are the port's own contract)
# ---------------------------------------------------------------------------
def selftest():
    ST_Y = ("create:tuff_pillar[axis=y,east=false,north=false,"
            "south=false,west=false]")
    ST_X = ("create:tuff_pillar[axis=x,east=false,north=false,"
            "south=false,west=false]")
    ST_X_UP = ("create:tuff_pillar[axis=x,east=true,north=false,"
               "south=false,west=false]")

    # 1. the y-pillar stack: the middle cell's NORTH face sees up+down
    #    (same-block+axis, no prop gate on the axis-parallel... for axis=y
    #    the vertical tests ARE the up/down tests of the side faces) ->
    #    x=0 (props false), y=1 -> index 4. The caps stay isolated (0).
    w = World()
    for y in (-1, 0, 1):
        w.add(0, y, 0, ST_Y)
    idx = compute_cell_ctx(w, (0, 0, 0), ST_Y)
    assert idx[DIR_INDEX["north"]] == 4, f"stack north: {idx[DIR_INDEX['north']]}"
    assert idx[DIR_INDEX["up"]] == 0 and idx[DIR_INDEX["down"]] == 0, \
        f"stack caps: {idx}"

    # 2. isolated pillar: all four side faces -> tile (0,3) = 12 (the tile
    #    the numeric probe proved byte-identical to the plain texture);
    #    caps -> OMNIDIRECTIONAL borders=4 -> (0,0) = 0.
    w2 = World()
    w2.add(0, 0, 0, ST_Y)
    iso = compute_cell_ctx(w2, (0, 0, 0), ST_Y)
    for f in ("north", "south", "west", "east"):
        assert iso[DIR_INDEX[f]] == 12, f"isolated {f}: {iso[DIR_INDEX[f]]}"
    for f in ("down", "up"):
        assert iso[DIR_INDEX[f]] == 0, f"isolated cap {f}: {iso[DIR_INDEX[f]]}"

    # 3. the x-pillar ROW along the axis: the middle cell's UP face (a SIDE
    #    face for axis=x — the caps are WEST/EAST) steps ±EAST along v —
    #    both row neighbours are same-block+axis -> up+down -> y=1, x=0
    #    (props false) -> index 4. THE CT SEES WHAT THE PROPS DO NOT ENCODE.
    w3 = World()
    for x in (-1, 0, 1):
        w3.add(x, 0, 0, ST_X)
    row = compute_cell_ctx(w3, (0, 0, 0), ST_X)
    assert row[DIR_INDEX["up"]] == 4, f"row up: {row[DIR_INDEX['up']]}"
    assert row[DIR_INDEX["down"]] == 4, f"row down: {row[DIR_INDEX['down']]}"

    # 4. the axis=X PROP TRAP: getConnection(state, UP) reads the EAST prop.
    #    A pillar ABOVE (0,1,0) + east=true: the NORTH side face's right
    #    test steps -h (h=DOWN for the north face) = UP -> gated on the
    #    east prop (TRUE) + the real neighbour -> right=true -> x=1, y=3
    #    -> index 13. With the plain-compass reading this would be 12.
    w4 = World()
    w4.add(0, 0, 0, ST_X_UP)
    w4.add(0, 1, 0, ST_X_UP)
    trap = compute_cell_ctx(w4, (0, 0, 0), ST_X_UP)
    assert trap[DIR_INDEX["north"]] == 13, \
        f"prop trap north: {trap[DIR_INDEX['north']]}"
    # the cell ABOVE: its DOWN face's left/right step ±h=NORTH/SOUTH gated
    # on the north/south props (false); the vertical neighbour is
    # PERPENDICULAR to every face plane (a stacked x-pillar is CT-invisible)
    # -> the DOWN face stays 12.
    assert trap is not None
    upper = compute_cell_ctx(w4, (0, 1, 0), ST_X_UP)
    assert upper[DIR_INDEX["down"]] == 12, \
        f"stacked-x down: {upper[DIR_INDEX['down']]}"

    # 5. the caps of the row cell: OMNIDIRECTIONAL on the WEST/EAST faces;
    #    their in-plane dirs span Y-Z — the row (along x) is perpendicular
    #    -> borders=4 -> 0.
    assert row[DIR_INDEX["west"]] == 0 and row[DIR_INDEX["east"]] == 0, \
        f"row caps: {row}"
    print("gallery_ct selftest: ALL OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))


def emit_cases(n_random, out_path):
    """The probe case file: the selftest neighbourhoods + randomized
    batteries. Format per line: center<TAB>dx,dy,dz=state;... (TSV)."""
    import random
    rnd = random.Random(140)
    cases = []

    def add(center, neighbors):
        cases.append((center, neighbors))

    ST = {ax: (f"create:tuff_pillar[axis={ax},east={{e}},north={{n}},"
               f"south={{s}},west={{w}}]") for ax in ("x", "y", "z")}

    def st(ax, e=False, n=False, s=False, w=False):
        # the state strings must use the MINECRAFT lowercase booleans —
        # the java probe's Boolean.parseBoolean accepts "True" (case-
        # insensitive) while the port (and the game) do not
        return ST[ax].format(e=str(e).lower(), n=str(n).lower(),
                             s=str(s).lower(), w=str(w).lower())

    # 1. the selftest neighbourhoods (the port's contract cases)
    add(st("y"), {(0, 1, 0): st("y"), (0, -1, 0): st("y")})
    add(st("y"), {})
    add(st("y"), {(1, 0, 0): st("y")})                       # east neighbour
    add(st("y"), {(0, 0, 1): st("y"), (0, 0, -1): st("y")})  # z-row
    add(st("x"), {(1, 0, 0): st("x"), (-1, 0, 0): st("x")})  # the row
    add(st("x"), {})
    add(st("x", e=True), {(0, 1, 0): st("x", e=True)})       # the prop trap
    add(st("x", e=True, w=True), {(0, 1, 0): st("x", e=True),
                                  (0, -1, 0): st("x", w=True)})
    add(st("z"), {(0, 0, 1): st("z"), (0, 0, -1): st("z")})  # the z-row
    add(st("z"), {})
    add(st("z", n=True, s=True), {(1, 0, 0): st("z", n=True, s=True),
                                  (-1, 0, 0): st("z", n=True, s=True)})
    # cross-shaped neighbourhoods (8-way with diagonals)
    add(st("y"), {(1, 0, 0): st("y"), (0, 1, 0): st("y"),
                  (1, 1, 0): st("y")})
    add(st("y"), {(1, 0, 0): st("y"), (0, 0, 1): st("y"),
                  (1, 0, 1): st("y"), (0, 0, -1): st("y")})
    # mixed axes (same block, other axis -> connectsTo axis gate)
    add(st("y"), {(1, 0, 0): st("x"), (0, 1, 0): st("x")})
    # different stone (same axis -> still connects! block equality only)
    add(st("y"), {(0, 1, 0): "create:ochrum_pillar[axis=y,east=false,"
                                "north=false,south=false,west=false]"})

    # 2. randomized batteries: every axis x props x up to 12 neighbours
    OFFS = [(dx, dy, dz) for dx in (-1, 0, 1) for dy in (-1, 0, 1)
            for dz in (-1, 0, 1) if (dx, dy, dz) != (0, 0, 0)]
    axes = ["x", "y", "z"]
    for i in range(n_random):
        ax = rnd.choice(axes)
        center = st(ax, e=rnd.random() < .5, n=rnd.random() < .5,
                    s=rnd.random() < .5, w=rnd.random() < .5)
        k = rnd.randint(0, 8)
        nbs = {}
        for off in rnd.sample(OFFS, k):
            nax = rnd.choice(axes)
            nbs[off] = st(nax, e=rnd.random() < .5, n=rnd.random() < .5,
                          s=rnd.random() < .5, w=rnd.random() < .5)
        add(center, nbs)

    lines = []
    for center, nbs in cases:
        nb = ";".join(f"{dx},{dy},{dz}={s}" for (dx, dy, dz), s
                      in sorted(nbs.items()))
        lines.append(center + "\t" + nb)
    pathlib.Path(out_path).write_text("\n".join(lines) + "\n", encoding="utf-8")

    # the expected values from the PORT
    exp = []
    for center, nbs in cases:
        w = World()
        for (dx, dy, dz), s in nbs.items():
            w.add(dx, dy, dz, s)
        idx = compute_cell_ctx(w, (0, 0, 0), center)
        exp.append(idx)
    return cases, exp
