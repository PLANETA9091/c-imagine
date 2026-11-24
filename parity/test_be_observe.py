#!/usr/bin/env python3
"""Unit tests for be_observe.py (SNBT parser, chat matching, projection, truth/ours observe). Run: python3 -I test_be_observe.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import be_observe as bo  # noqa: E402


class FakeTruth(object):
    name = "truth"

    def __init__(self, replies):
        self.replies = list(replies)  # one list of chat lines per messages call
        self.calls = []

    def call(self, cmd, args=None, opts=None, timeout=300.0):
        self.calls.append((cmd, list(args or [])))
        if cmd == "messages":
            return {"ok": True, "lines": self.replies.pop(0) if self.replies else []}
        return {"ok": True}


class FakeOurs(object):
    name = "ours"

    def __init__(self, obj):
        self.obj = obj
        self.args = None

    def inspect(self, *args, wait=8.0):
        self.args = args
        return self.obj


class ParseSnbtTest(unittest.TestCase):
    def test_compound_with_typed_values(self):
        got = bo.parse_snbt('{id:"create:mechanical_press",x:3,Speed:16.5f,L:7l,b:1b,d:2.0d,Items:[{id:"minecraft:iron_ingot",Count:3b}],ia:[I;1,2],ba:[B;5b],empty:{}}')
        self.assertEqual(got["id"], "create:mechanical_press")
        self.assertEqual(got["x"], 3)
        self.assertEqual(got["Speed"], 16.5)
        self.assertEqual(got["L"], 7)
        self.assertEqual(got["b"], 1)
        self.assertEqual(got["d"], 2.0)
        self.assertEqual(got["Items"], [{"id": "minecraft:iron_ingot", "Count": 3}])
        self.assertEqual(got["ia"], [1, 2])
        self.assertEqual(got["ba"], [5])
        self.assertEqual(got["empty"], {})

    def test_quoted_escapes_and_negative_and_exponent(self):
        got = bo.parse_snbt('{name:"iron \\"ingot\\"",n:-4,e:1.5e2f,s:\'single\',l:[]}')
        self.assertEqual(got["name"], 'iron "ingot"')
        self.assertEqual(got["n"], -4)
        self.assertEqual(got["e"], 150.0)
        self.assertEqual(got["s"], "single")
        self.assertEqual(got["l"], [])

    def test_bad_input_raises(self):
        for bad in ["{x:1", "{x 1}", '{x:"open}', "{x:1} trailing"]:
            with self.assertRaises(bo.SnbtError, msg=bad):
                bo.parse_snbt(bad)


class FindTest(unittest.TestCase):
    def test_newest_matching_line_wins(self):
        lines = [
            "Block at 308, 123, 310 has the following block data: {id:\"create:a\",Speed:1.0f}",
            "unrelated chat",
            "Block at 308, 123, 310 has the following block data: {id:\"create:a\",Speed:8.0f}",
            "Block at 1, 2, 3 has the following block data: {id:\"x\"}",
        ]
        self.assertEqual(bo.find_be_snbt(lines, (308, 123, 310)), '{id:"create:a",Speed:8.0f}')

    def test_no_match(self):
        self.assertIsNone(bo.find_be_snbt(["Block at 1, 2, 3 has the following block data: {}"], (308, 123, 310)))
        self.assertIsNone(bo.find_be_snbt(["Unable to get block data at 308, 123, 310"], (308, 123, 310)))


class ProjectTest(unittest.TestCase):
    NBT = {
        "id": "create:depot",
        "Speed": 16.123456,
        "Item": {"id": "create:iron_sheet", "Count": 1},
        "Tank": {"id": "minecraft:water", "Amount": 4000},
        "Items": [{"id": "minecraft:iron_ingot", "count": 2}],
        "x": 1,
    }

    def test_projection_normalises_stacks_and_floats(self):
        got = bo.project(self.NBT, ["id", "Speed", "Item", "Tank", "Items", "Items.0.count", "missing"])
        self.assertEqual(got["id"], "create:depot")
        self.assertEqual(got["Speed"], 16.1235)
        self.assertEqual(got["Item"], {"id": "create:iron_sheet", "count": 1})
        self.assertEqual(got["Tank"], {"id": "minecraft:water", "amount": 4000})
        self.assertEqual(got["Items"], [{"id": "minecraft:iron_ingot", "count": 2}])
        self.assertEqual(got["Items.0.count"], 2)
        self.assertIsNone(got["missing"])

    def test_slot_kept_and_discovery_star(self):
        nbt = {"id": "minecraft:chest", "Items": [{"Slot": 0, "id": "minecraft:iron_ingot", "count": 1}]}
        self.assertEqual(bo.project(nbt, ["Items"]), {"Items": [{"id": "minecraft:iron_ingot", "count": 1, "slot": 0}]})
        self.assertEqual(bo.project(nbt, ["*"]), {"*": bo.normalise(nbt)})

    def test_only_listed_fields_are_compared(self):
        self.assertEqual(bo.project(self.NBT, ["Speed"]), {"Speed": 16.1235})

    def test_absent_block_entity(self):
        self.assertEqual(bo.result_of(None, ["Speed"]), {"present": False, "id": None, "fields": {}})


class ObserveTest(unittest.TestCase):
    def test_truth_sends_data_get_and_parses_newest(self):
        t = FakeTruth([["noise", "Block at 308, 121, 310 has the following block data: {id:\"create:depot\",Item:{id:\"create:iron_sheet\",Count:1b}}"]])
        res = bo.observe(t, {"observe": "be", "pos": [308, 121, 310], "fields": ["Item"]})
        self.assertEqual(res, {"present": True, "id": "create:depot",
                               "fields": {"Item": {"id": "create:iron_sheet", "count": 1}}})
        self.assertEqual(t.calls[0], ("command", ["data", "get", "block", "308", "121", "310"]))
        self.assertEqual(t.calls[-1][0], "messages")

    def test_truth_retries_then_reports_absent(self):
        t = FakeTruth([[], [], []])
        import unittest.mock as um
        with um.patch.object(bo, "DATA_GET_TRIES", 3):
            res = bo.observe(t, {"observe": "be", "pos": [1, 2, 3]})
        self.assertEqual(res, {"present": False, "id": None, "fields": {}})
        self.assertEqual(sum(1 for c, _ in t.calls if c == "messages"), 3)

    def test_ours_uses_inspect_be_nbt(self):
        o = FakeOurs({"kind": "be", "pos": [308, 123, 310], "id": "create:encased_fan", "nbt": {"id": "create:encased_fan", "Speed": 8.0}})
        res = bo.observe(o, {"observe": "be", "pos": [308, 123, 310], "fields": ["Speed"]})
        self.assertEqual(o.args, ("be", 308, 123, 310))
        self.assertEqual(res, {"present": True, "id": "create:encased_fan", "fields": {"Speed": 8.0}})

    def test_ours_none_is_absent(self):
        o = FakeOurs({"kind": "be", "pos": [1, 2, 3], "id": None, "nbt": None})
        self.assertEqual(bo.observe(o, {"observe": "be", "pos": [1, 2, 3]})["present"], False)

    def test_ours_without_inspect_fails_loudly(self):
        o = FakeOurs(None)
        with self.assertRaises(RuntimeError):
            bo.observe(o, {"observe": "be", "pos": [1, 2, 3]})

    def test_same_projection_on_both_stacks(self):
        snbt = '{id:"create:encased_fan",Speed:8.0f,x:1}'
        t = FakeTruth([["Block at 1, 2, 3 has the following block data: " + snbt]])
        o = FakeOurs({"nbt": {"id": "create:encased_fan", "Speed": 8.0, "x": 1}})
        step = {"observe": "be", "pos": [1, 2, 3], "fields": ["Speed"]}
        self.assertEqual(bo.observe(t, step), bo.observe(o, step))


if __name__ == "__main__":
    unittest.main()
