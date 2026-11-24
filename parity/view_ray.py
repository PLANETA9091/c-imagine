"""The player's view ray, as the client aims it (pure Python, no game needed).

Used by OursStack.use_at when Horizon reports no crosshair target: the eye is the feet plus the standing eye
height, the direction comes from yaw and pitch with Minecraft's convention (yaw 0 looks at +Z, yaw 90 at -X,
pitch +90 looks straight down), and the first solid cell along the ray is found by the Amanatides-Woo voxel
walk. The hit face is the face of that cell the ray enters through (the side facing the viewer), named as
Minecraft names directions: down, up, north (-Z), south (+Z), west (-X), east (+X).

Limits (documented, not modelled): sneaking lowers the eye (1.27), and cells are judged by their full cube, so a
partial shape (a slab's top half, a fence post) is hit as its whole cell.
"""
import math

EYE_HEIGHT = 1.62
SNEAK_EYE_HEIGHT = 1.27
REACH = 5.0  # blocks; the runner's use reach (survival reach is 4.5, the margin keeps edge cases in)
AIR_IDS = ("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "air", "cave_air", "void_air")

# Entering a cell along an axis: stepping +1 enters through the cell's low face, -1 through its high face.
_FACE_ON_ENTRY = {
    (0, 1): "west", (0, -1): "east",
    (1, 1): "down", (1, -1): "up",
    (2, 1): "north", (2, -1): "south",
}


def view_direction(yaw, pitch):
    """Unit vector the player looks along, for yaw and pitch in degrees (Minecraft's Entity.getViewVector)."""
    yr = math.radians(yaw)
    pr = math.radians(pitch)
    return (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))


def eye_position(feet, sneaking=False):
    """The eye point of a player standing with its feet at {feet} (x, y, z)."""
    h = SNEAK_EYE_HEIGHT if sneaking else EYE_HEIGHT
    return (float(feet[0]), float(feet[1]) + h, float(feet[2]))


def first_solid_cell(eye, direction, is_solid, reach=REACH):
    """Walk the ray from eye along direction (a unit vector) for at most reach blocks.

    is_solid(cell) -> True / False / None (None: the cell cannot be read; the walk stops there, as a hit
    cannot be named). The eye's own cell is not tested (a player inside a block is not aiming at it).
    Returns {"pos": [x, y, z], "face": name, "distance": t} or None when nothing solid is in reach.
    """
    cell = [math.floor(eye[a]) for a in range(3)]
    step = [0, 0, 0]
    t_max = [math.inf] * 3
    t_delta = [math.inf] * 3
    for a in range(3):
        d = direction[a]
        if abs(d) < 1e-12:
            continue
        step[a] = 1 if d > 0 else -1
        t_delta[a] = abs(1.0 / d)
        boundary = cell[a] + 1 if d > 0 else cell[a]
        t_max[a] = (boundary - eye[a]) / d
    while True:
        axis = min(range(3), key=lambda a: t_max[a])
        t = t_max[axis]
        if t > reach or math.isinf(t):
            return None
        cell[axis] += step[axis]
        t_max[axis] += t_delta[axis]
        solid = is_solid(tuple(cell))
        if solid is None:
            return None
        if solid:
            return {"pos": list(cell), "face": _FACE_ON_ENTRY[(axis, step[axis])], "distance": t}


def aim_hit(feet, yaw, pitch, is_solid, sneaking=False, reach=REACH):
    """The block the player at feet, looking along yaw and pitch, would click: first_solid_cell from the eye."""
    return first_solid_cell(eye_position(feet, sneaking), view_direction(yaw, pitch), is_solid, reach)


def is_air_id(block_id):
    """True for an air id. The caller maps an unreadable cell to None itself (see OursStack.use_at)."""
    return str(block_id).split("[", 1)[0] in AIR_IDS
