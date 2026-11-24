"""place takes an explicit face and hit point on both stacks (truth-ctl --face/--hit, OURS pinned click)."""
import contextlib
import unittest

import stacks


class TruthPlaceArgsTests(unittest.TestCase):
    def _stack(self, reply):
        st = stacks.TruthStack.__new__(stacks.TruthStack)
        st.calls = []
        st.call = lambda cmd, args=None, **k: (st.calls.append((cmd, list(args or []))) or reply)
        st.holding = lambda holds: contextlib.nullcontext()
        st._counts = lambda: {}
        st._server_block_id = lambda pos: None
        return st

    def test_face_and_hit_are_passed_through(self):
        st = self._stack({"block": "minecraft:stone", "inventory_change": {}})
        st.place([1, 2, 3], "minecraft:stone", "north", [0.5, 0.25, 0.0])
        cmd, args = st.calls[0]
        self.assertEqual(cmd, "place")
        self.assertIn("--face", args)
        self.assertEqual(args[args.index("--face") + 1], "north")
        self.assertEqual(args[args.index("--hit") + 1:], ["0.5", "0.25", "0.0"])

    def test_no_face_keeps_plain_place(self):
        st = self._stack({"block": "minecraft:stone", "inventory_change": {}})
        st.place([1, 2, 3], "minecraft:stone")
        self.assertNotIn("--face", st.calls[0][1])
        self.assertNotIn("--hit", st.calls[0][1])


if __name__ == "__main__":
    unittest.main()
