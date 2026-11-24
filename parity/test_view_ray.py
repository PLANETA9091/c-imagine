"""The aim ray of OursStack.use_at (view_ray.py): direction, eye, voxel walk, reach, faces, and the use_at fallback."""
import math
import random
import unittest

import stacks
import view_ray


def solid_set(cells):
    cells = {tuple(c) for c in cells}
    return lambda cell: cell in cells


class ViewDirectionTests(unittest.TestCase):
    def assertVec(self, got, want):
        for g, w in zip(got, want):
            self.assertAlmostEqual(g, w, places=9)

    def test_minecraft_convention(self):
        self.assertVec(view_ray.view_direction(0, 0), (0, 0, 1))      # yaw 0 looks at +Z
        self.assertVec(view_ray.view_direction(90, 0), (-1, 0, 0))    # yaw 90 looks at -X
        self.assertVec(view_ray.view_direction(180, 0), (0, 0, -1))
        self.assertVec(view_ray.view_direction(0, 90), (0, -1, 0))    # pitch +90 looks down
        self.assertVec(view_ray.view_direction(0, -90), (0, 1, 0))

    def test_unit_length(self):
        for yaw, pitch in [(13.5, -22.0), (-170.0, 61.0), (400.0, 7.0)]:
            d = view_ray.view_direction(yaw, pitch)
            self.assertAlmostEqual(math.sqrt(sum(c * c for c in d)), 1.0, places=9)


class EyeTests(unittest.TestCase):
    def test_standing_and_sneaking_eye_height(self):
        self.assertEqual(view_ray.eye_position((1, 64, 2)), (1.0, 64 + 1.62, 2.0))
        self.assertEqual(view_ray.eye_position((1, 64, 2), sneaking=True), (1.0, 64 + 1.27, 2.0))


class FirstSolidCellTests(unittest.TestCase):
    def test_looking_down_hits_the_top_face(self):
        hit = view_ray.first_solid_cell((0.5, 3.0, 0.5), (0, -1, 0), solid_set([(0, 1, 0)]))
        self.assertEqual(hit["pos"], [0, 1, 0])
        self.assertEqual(hit["face"], "up")
        self.assertAlmostEqual(hit["distance"], 1.0)  # the block at y 1..2 is entered at its top, y = 2

    def test_looking_plus_z_hits_the_north_face(self):
        hit = view_ray.first_solid_cell((0.5, 1.5, -2.5), (0, 0, 1), solid_set([(0, 1, 0)]))
        self.assertEqual((hit["pos"], hit["face"]), ([0, 1, 0], "north"))
        self.assertAlmostEqual(hit["distance"], 2.5)

    def test_looking_minus_x_hits_the_east_face(self):
        hit = view_ray.first_solid_cell((3.5, 1.5, 0.5), (-1, 0, 0), solid_set([(0, 1, 0)]))
        self.assertEqual((hit["pos"], hit["face"]), ([0, 1, 0], "east"))

    def test_entry_faces_for_every_direction(self):
        for direction, face, origin in [((0, 1, 0), "down", (0.5, -2.5, 0.5)), ((1, 0, 0), "west", (-2.5, 0.5, 0.5)),
                                        ((0, 0, -1), "south", (0.5, 0.5, 3.5))]:
            hit = view_ray.first_solid_cell(origin, direction, solid_set([(0, 0, 0)]))
            self.assertEqual(hit["face"], face, direction)

    def test_reach_is_five_blocks(self):
        near = view_ray.first_solid_cell((0.5, 1.5, -4.5), (0, 0, 1), solid_set([(0, 1, 0)]))
        far = view_ray.first_solid_cell((0.5, 1.5, -5.5), (0, 0, 1), solid_set([(0, 1, 0)]))
        self.assertEqual(near["pos"], [0, 1, 0])
        self.assertIsNone(far)

    def test_negative_coordinates(self):
        hit = view_ray.first_solid_cell((-0.5, 1.5, -2.5), (0, 0, 1), solid_set([(-1, 1, 0)]))
        self.assertEqual((hit["pos"], hit["face"]), ([-1, 1, 0], "north"))

    def test_eye_inside_a_block_skips_its_own_cell(self):
        hit = view_ray.first_solid_cell((0.5, 1.5, 0.5), (0, 0, 1), solid_set([(0, 1, 0), (0, 1, 1)]))
        self.assertEqual((hit["pos"], hit["face"]), ([0, 1, 1], "north"))

    def test_air_only_ray_has_no_hit(self):
        self.assertIsNone(view_ray.first_solid_cell((0.5, 1.5, 0.5), (0, 0, 1), solid_set([])))

    def test_unreadable_cell_stops_the_walk(self):
        self.assertIsNone(view_ray.first_solid_cell((0.5, 1.5, -2.5), (0, 0, 1),
                                                    lambda cell: None if cell[2] == 0 else False))

    def test_matches_fine_sampling(self):
        """Oracle: step along the ray in 1e-4 blocks and take the first solid cell and the cell entered before it."""
        rng = random.Random(4242)
        for _ in range(150):
            eye = (rng.uniform(-3, 3), rng.uniform(-3, 3), rng.uniform(-3, 3))
            d = view_ray.view_direction(rng.uniform(-180, 180), rng.uniform(-89, 89))
            cells = {(rng.randint(-4, 4), rng.randint(-4, 4), rng.randint(-4, 4)) for _ in range(60)}
            is_solid = solid_set(cells)
            got = view_ray.first_solid_cell(eye, d, is_solid)
            want = None
            prev = tuple(math.floor(c) for c in eye)
            t = 0.0
            while t <= view_ray.REACH:
                p = tuple(eye[a] + d[a] * t for a in range(3))
                cell = tuple(math.floor(c) for c in p)
                if cell != prev and is_solid(cell):
                    axis = [a for a in range(3) if cell[a] != prev[a]][0]
                    step = cell[axis] - prev[axis]
                    want = {"pos": list(cell), "face": view_ray._FACE_ON_ENTRY[(axis, step)]}
                    break
                prev = cell
                t += 1e-4
            if want is None:
                self.assertIsNone(got)
            else:
                self.assertIsNotNone(got)
                self.assertEqual((got["pos"], got["face"]), (want["pos"], want["face"]))


class AirTests(unittest.TestCase):
    def test_air_ids(self):
        self.assertTrue(view_ray.is_air_id("minecraft:air"))
        self.assertTrue(view_ray.is_air_id("minecraft:cave_air[x=1]"))
        self.assertFalse(view_ray.is_air_id("minecraft:stone"))
        self.assertFalse(view_ray.is_air_id("dynamictrees:oak_branch[radius=1]"))


class OursUseAtTests(unittest.TestCase):
    """OursStack.use_at with a fake control channel: the look, the reply state, and a fake world."""

    def _stack(self, world, state):
        st = stacks.OursStack.__new__(stacks.OursStack)
        st.calls = []

        def ctl(cmd, *args, timeout=None):
            st.calls.append((cmd, list(args)))
            if cmd == "state":
                return dict(state)
            if cmd == "block":
                cell = tuple(int(a) for a in args)
                return {"block": world.get(cell, "minecraft:air"), "loaded": True}
            return {}

        st.ctl = ctl
        st.holding = lambda holds: __import__("contextlib").nullcontext()
        st._counts = lambda: {}
        st._delta = lambda before, after: {}
        st._settle = lambda ticks=6: None
        st._server_block_id = lambda pos: "server:" + ",".join(str(v) for v in pos)
        return st

    def test_target_wins_when_horizon_reports_one(self):
        state = {"yaw": 0, "pitch": 0, "player": {"position": [0.5, 1.0, -2.5]},
                 "target": {"pos": [9, 9, 9], "face": "up"}}
        out = self._stack({}, state).use_at([0.5, 2.0, 0.5])
        self.assertEqual((out["hit_pos"], out["hit_face"]), ([9, 9, 9], "up"))

    def test_ray_fallback_when_no_target(self):
        # Feet at y 1, eye 2.62, looking at +Z: the solid block at z 0 is hit through its -Z face.
        world = {(0, 2, 0): "dynamictrees:oak_seed_block"}
        state = {"yaw": 0, "pitch": 0, "player": {"position": [0.5, 1.0, -2.5]}, "target": None}
        out = self._stack(world, state).use_at([0.5, 2.62, 0.5])
        self.assertEqual((out["hit_pos"], out["hit_face"]), ([0, 2, 0], "north"))

    def test_ray_uses_the_pose_not_the_aim_point(self):
        # The aim point sits on a block plane (y = 1.0), the old fallback picked the cell above it. The view ray
        # from the eye (feet 0 + 1.62) looking 30 down enters the air cell (0,1,0) through its -Z face, then the
        # dirt at (0,0,0) through its top.
        world = {(0, 0, 0): "minecraft:dirt"}
        state = {"yaw": 0, "pitch": 30, "player": {"position": [0.5, 0.0, -1.0]}, "target": None}
        out = self._stack(world, state).use_at([0.5, 1.0, 0.5])
        self.assertEqual((out["hit_pos"], out["hit_face"]), ([0, 0, 0], "up"))

    def test_nothing_in_reach_gives_no_hit(self):
        state = {"yaw": 0, "pitch": 0, "player": {"position": [0.5, 1.0, 0.5]}, "target": None}
        out = self._stack({}, state).use_at([0.5, 1.0, 5.5])
        self.assertIsNone(out["hit_pos"])
        self.assertIsNone(out["hit_face"])

    def test_unloaded_cell_gives_no_hit(self):
        st = self._stack({}, {"yaw": 0, "pitch": 0, "player": {"position": [0.5, 1.0, -2.5]}, "target": None})
        st.ctl = lambda cmd, *a, timeout=None: ({"block": "minecraft:stone", "loaded": False}
                                                if cmd == "block" else
                                                ({"yaw": 0, "pitch": 0, "player": {"position": [0.5, 1.0, -2.5]}}
                                                 if cmd == "state" else {}))
        out = st.use_at([0.5, 2.0, 0.5])
        self.assertIsNone(out["hit_pos"])


if __name__ == "__main__":
    unittest.main()
