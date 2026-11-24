#!/usr/bin/env python3
"""Tests for parallel lanes: the coordinate offset transform (run.py), the lane config and the shared
work queue (lanes.py), and the per-lane stack construction (stacks.py).

Pure Python, no game, no network, no servers: fake work functions stand in for scenario runs.
Run: python3 -I -m unittest test_lanes   (from this directory)
"""
import glob
import json
import os
import shutil
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import lanes  # noqa: E402
import run  # noqa: E402
import stacks  # noqa: E402

ORIGIN = (64, 0, 0)


def fake_work(item, lane):
    """Module level (pickled into pool workers). Sleeps so every lane gets work; fails on 'bad'."""
    time.sleep(0.2)
    if item == "bad":
        raise RuntimeError("scenario broke")
    return {"item": item, "lane": lane["lane"]}


def lane_entry(i, origin_x=None):
    return {"lane": i, "ours_player": "Lane_%d" % i, "hz_slot": 3 + i,
            "truth_player": "TruthLane%d" % i, "truth_port": 25599 + i,
            "origin": [64 * i if origin_x is None else origin_x, 0, 0]}


class OffsetCommandTests(unittest.TestCase):
    def test_zero_origin_is_identity(self):
        t = "fill 304 121 306 312 135 314 minecraft:air"
        self.assertEqual(run.offset_command(t, (0, 0, 0)), t)

    def test_setblock_and_fill(self):
        self.assertEqual(run.offset_command("setblock 308 120 310 minecraft:grass_block", ORIGIN),
                         "setblock 372 120 310 minecraft:grass_block")
        self.assertEqual(run.offset_command("fill 304 121 306 312 135 314 minecraft:air", ORIGIN),
                         "fill 368 121 306 376 135 314 minecraft:air")

    def test_relative_coordinates_stay(self):
        self.assertEqual(run.offset_command("setblock ~ ~-1 ~ minecraft:stone", ORIGIN),
                         "setblock ~ ~-1 ~ minecraft:stone")
        self.assertEqual(run.offset_command("fill ~1 ~ ~ 308 121 308 minecraft:stone", ORIGIN),
                         "fill ~1 ~ ~ 372 121 308 minecraft:stone")

    def test_tp_shapes(self):
        # tp {player} x y z | tp {player} x y z yaw pitch | tp x y z yaw pitch | tp target destination
        self.assertEqual(run.offset_command("tp {player} 308 121 308", ORIGIN), "tp {player} 372 121 308")
        self.assertEqual(run.offset_command("tp {player} 308 121 308 90 0", ORIGIN),
                         "tp {player} 372 121 308 90 0")
        self.assertEqual(run.offset_command("tp 308 121 308 90 0", ORIGIN), "tp 372 121 308 90 0")
        self.assertEqual(run.offset_command("tp {player} ~ ~ ~", ORIGIN), "tp {player} ~ ~ ~")
        self.assertEqual(run.offset_command("tp {player} @s", ORIGIN), "tp {player} @s")

    def test_summon_coordinates_follow_the_entity_id(self):
        self.assertEqual(run.offset_command("summon minecraft:item 310.5 121.5 310.5 {Item:{id:\"minecraft:stone\"}}",
                                            ORIGIN),
                         "summon minecraft:item 374.5 121.5 310.5 {Item:{id:\"minecraft:stone\"}}")
        self.assertEqual(run.offset_command("summon minecraft:item ~ ~1 ~", ORIGIN), "summon minecraft:item ~ ~1 ~")

    def test_forceload_has_no_y(self):
        self.assertEqual(run.offset_command("forceload add 300 300 310 310", ORIGIN),
                         "forceload add 364 300 374 310")
        self.assertEqual(run.offset_command("forceload query 300 300", ORIGIN), "forceload query 364 300")

    def test_selector_coordinates_and_distances(self):
        self.assertEqual(run.offset_command("kill @e[type=item,x=308,y=121,z=308,distance=..3]", ORIGIN),
                         "kill @e[type=item,x=372,y=121,z=308,distance=..3]")

    def test_non_coordinates_untouched(self):
        for t in ("give {player} minecraft:torch 1", "effect give {player} minecraft:hunger 300 3",
                  "kill {player}"):
            self.assertEqual(run.offset_command(t, ORIGIN), t)

    def test_unsupported_commands_raise(self):
        for head in ("clone", "execute", "locate", "spreadplayers"):
            with self.assertRaises(ValueError):
                run.offset_command("%s 1 2 3" % head, ORIGIN)

    def test_offset_point(self):
        self.assertEqual(run.offset_point([3, 63, 3], ORIGIN, "pos"), [67, 63, 3])
        self.assertEqual(run.offset_point([1.5, 2.0, 3.0], (0, 0, 64), "pos"), [1.5, 2.0, 67.0])
        for bad in ([1, 2], [1, 2, "3"], [1, 2, True], "x"):
            with self.assertRaises(ValueError):
                run.offset_point(bad, ORIGIN, "pos")


class OffsetScenarioTests(unittest.TestCase):
    def sc(self):
        return {"id": "t", "yaw": 90.0, "pitch": 10.0,
                "setup": ["gamemode survival", "fill 304 121 306 312 135 314 minecraft:air",
                          "give {player} minecraft:torch 1"],
                "steps": [
                    {"do": "tp", "pos": [308, 121, 308], "yaw": 180.0, "pitch": 5.0},
                    {"do": "place", "pos": [309, 122, 308], "item": "minecraft:stone"},
                    {"do": "break", "pos": [309, 122, 308], "timeout": 20},
                    {"do": "collect", "pos": [309, 122, 308]},
                    {"do": "use_at", "point": [310, 122, 308]},
                    {"do": "interact", "pos": [1, 2, 3], "face": "up"},
                    {"do": "command", "text": "setblock 308 120 310 minecraft:stone"},
                    {"observe": "block", "pos": [309, 122, 308]},
                    {"observe": "inventory"},
                    {"do": "wait", "ticks": 20},
                ]}

    def test_every_position_key_shifted_exactly_once(self):
        src = self.sc()
        out = run.offset_scenario(src, ORIGIN)
        self.assertEqual(out["steps"][0]["pos"], [372, 121, 308])
        self.assertEqual(out["steps"][1]["pos"], [373, 122, 308])
        self.assertEqual(out["steps"][3]["pos"], [373, 122, 308])
        self.assertEqual(out["steps"][4]["point"], [374, 122, 308])
        self.assertEqual(out["steps"][5]["pos"], [65, 2, 3])
        self.assertEqual(out["steps"][7]["pos"], [373, 122, 308])

    def test_non_position_fields_untouched(self):
        out = run.offset_scenario(self.sc(), ORIGIN)
        self.assertEqual(out["yaw"], 90.0)
        self.assertEqual(out["steps"][0]["yaw"], 180.0)
        self.assertEqual(out["steps"][0]["pitch"], 5.0)
        self.assertEqual(out["steps"][2]["timeout"], 20)
        self.assertEqual(out["steps"][9]["ticks"], 20)
        self.assertEqual(out["steps"][1]["item"], "minecraft:stone")
        self.assertEqual(out["steps"][5]["face"], "up")
        self.assertEqual(out["setup"][2], "give {player} minecraft:torch 1")  # {player} kept for sub()

    def test_setup_and_command_steps_shifted(self):
        out = run.offset_scenario(self.sc(), ORIGIN)
        self.assertEqual(out["setup"][1], "fill 368 121 306 376 135 314 minecraft:air")
        self.assertEqual(out["steps"][6]["text"], "setblock 372 120 310 minecraft:stone")

    def test_input_not_mutated_and_zero_origin_is_copy(self):
        src = self.sc()
        before = json.dumps(src, sort_keys=True)
        run.offset_scenario(src, ORIGIN)
        self.assertEqual(json.dumps(src, sort_keys=True), before)
        same = run.offset_scenario(src, (0, 0, 0))
        self.assertEqual(same, src)
        self.assertIsNot(same, src)

    def test_real_scenarios_offset_into_the_lane_area(self):
        """Every shipped scenario (top level and census) offsets cleanly; every pos moves by exactly the origin."""
        paths = [p for p in glob.glob(os.path.join(HERE, "scenarios", "**", "*.json"), recursive=True)
                 if not os.path.basename(p).startswith("_") and "survival" not in p]
        self.assertTrue(paths)
        checked = 0
        for p in paths:
            with open(p, encoding="utf-8") as f:
                sc = json.load(f)
            if not isinstance(sc, dict) or "steps" not in sc:
                continue
            out = run.offset_scenario(sc, ORIGIN)
            for a, b in zip(sc["steps"], out["steps"]):
                for key in run.POSITION_KEYS:
                    if key in a:
                        self.assertEqual(b[key], [a[key][0] + 64, a[key][1], a[key][2]], p)
                        checked += 1
        self.assertGreater(checked, 100)


class LaneConfigTests(unittest.TestCase):
    def test_shipped_lanes_file_is_valid(self):
        lst = lanes.load_lanes(lanes.DEFAULT_LANES_FILE)
        self.assertEqual([l["lane"] for l in lst], list(range(len(lst))))
        self.assertGreaterEqual(len(lst), 2)
        self.assertEqual(lanes.first_k(lst, 2), lst[:2])

    def test_duplicate_names_ports_slots_rejected(self):
        for key in ("ours_player", "truth_port", "hz_slot", "truth_player"):
            a, b = lane_entry(0), lane_entry(1, 64)
            b[key] = a[key]  # lane 1 takes lane 0's value for this field
            norm = [lanes.normalize(a), lanes.normalize(b)]
            with self.assertRaises(lanes.LaneError, msg=key):
                lanes.validate_lanes(norm)

    def test_origin_separation_rejected(self):
        norm = [lanes.normalize(lane_entry(0)), lanes.normalize(lane_entry(1, 32))]
        with self.assertRaises(lanes.LaneError):
            lanes.validate_lanes(norm)

    def test_ids_must_be_contiguous(self):
        norm = [lanes.normalize(lane_entry(0)), lanes.normalize(lane_entry(2, 128))]
        with self.assertRaises(lanes.LaneError):
            lanes.validate_lanes(norm)

    def test_missing_field_and_bad_origin(self):
        e = lane_entry(0)
        del e["truth_port"]
        with self.assertRaises(lanes.LaneError):
            lanes.normalize(e)
        e = lane_entry(0)
        e["origin"] = [0, 0]
        with self.assertRaises(lanes.LaneError):
            lanes.normalize(e)

    def test_first_k_bounds(self):
        lst = [lanes.normalize(lane_entry(i)) for i in range(3)]
        self.assertEqual(len(lanes.first_k(lst, 3)), 3)
        with self.assertRaises(lanes.LaneError):
            lanes.first_k(lst, 4)
        with self.assertRaises(lanes.LaneError):
            lanes.first_k(lst, 0)

    def test_load_from_file(self):
        with tempfile.TemporaryDirectory(dir=HERE) as d:
            p = os.path.join(d, "lanes.json")
            with open(p, "w", encoding="utf-8") as f:
                json.dump([lane_entry(0), lane_entry(1, 64)], f)
            self.assertEqual(len(lanes.load_lanes(p)), 2)
            with open(p, "w", encoding="utf-8") as f:
                json.dump({"lane": 0}, f)
            with self.assertRaises(lanes.LaneError):
                lanes.load_lanes(p)


class StackPerLaneTests(unittest.TestCase):
    def test_truth_and_ours_take_lane_names_and_ports(self):
        lane = lanes.normalize(lane_entry(2, 128))
        t = stacks.make_stack("truth", lane)
        o = stacks.make_stack("ours", lane)
        self.assertEqual((t.port, t.player), (25601, "TruthLane2"))
        self.assertEqual((o.player, o.hz_n), ("Lane_2", "5"))

    def test_no_lane_keeps_single_stand_defaults(self):
        t = stacks.make_stack("truth", None)
        o = stacks.make_stack("ours", None)
        self.assertEqual((t.port, t.player), (stacks.TRUTH_CTL_PORT, stacks.TRUTH_PLAYER))
        self.assertEqual((o.player, o.hz_n), (stacks.OURS_PLAYER, str(stacks.HZ_N)))

    def test_player_substitution_uses_lane_player(self):
        lane = lanes.normalize(lane_entry(1, 64))
        o = stacks.make_stack("ours", lane)
        self.assertEqual(run.sub("give {player} minecraft:torch 1", o.player), "give Lane_1 minecraft:torch 1")

    def test_inspect_lock_is_exclusive_and_releases(self):
        with tempfile.TemporaryDirectory(dir=HERE) as d:
            old = stacks.INSPECT_LOCK
            stacks.INSPECT_LOCK = os.path.join(d, "inspect.lock")
            try:
                with stacks.inspect_lock():
                    pass
                with stacks.inspect_lock():
                    pass  # released on exit, so a second acquire does not block
            finally:
                stacks.INSPECT_LOCK = old


class LaneQueueTests(unittest.TestCase):
    def lanes_of(self, n):
        return [lanes.normalize(lane_entry(i)) for i in range(n)]

    def test_every_item_runs_once_and_all_lanes_work(self):
        items = ["s%d" % i for i in range(8)]
        results = lanes.run_queue(items, self.lanes_of(4), fake_work)
        self.assertEqual(sorted(r["item"] for r in results), sorted(items))
        self.assertTrue(all(r["error"] is None for r in results))
        self.assertTrue(all(r["result"]["lane"] == r["lane"] for r in results))
        self.assertEqual(set(r["lane"] for r in results), {0, 1, 2, 3})

    def test_lanes_run_in_parallel(self):
        t0 = time.time()
        lanes.run_queue(["s%d" % i for i in range(8)], self.lanes_of(4), fake_work)
        # 8 items x 0.2 s serial would be 1.6 s; 4 lanes need about 0.4 s
        self.assertLess(time.time() - t0, 1.2)

    def test_worker_exception_becomes_error_row_and_queue_goes_on(self):
        seen = []
        results = lanes.run_queue(["a", "bad", "b"], self.lanes_of(2), fake_work,
                                  on_result=lambda r: seen.append(r["item"]))
        self.assertEqual(len(results), 3)
        self.assertEqual(sorted(seen), ["a", "b", "bad"])
        bad = [r for r in results if r["item"] == "bad"][0]
        self.assertIsNone(bad["result"])
        self.assertIn("scenario broke", bad["error"])
        self.assertEqual(sorted(r["item"] for r in results if r["error"] is None), ["a", "b"])

    def test_empty_lane_list_rejected(self):
        with self.assertRaises(lanes.LaneError):
            lanes.run_queue(["a"], [], fake_work)


FAKE_LANE_RUN = """#!/usr/bin/env python3
# stands in for run.py: records the lane it was given in the scenario output, takes 0.1 s
import json, sys, time
args = sys.argv[1:]
lane = args[args.index("--lane") + 1] if "--lane" in args else None
sc = json.load(open(args[args.index("--stack") + 2], encoding="utf-8"))
time.sleep(0.1)
out = args[args.index("-o") + 1]
json.dump({"scenario": sc["id"], "lane": lane, "setup": [{"cmd": "heal", "ok": True}],
           "steps": [{"i": 0, "step": {"observe": "block", "pos": [1, 2, 3]}, "kind": "observe",
                      "result": {"v": 1}, "error": None}]}, open(out, "w"))
print("ran %s on lane %s" % (sc["id"], lane))
"""


class AllLanesEndToEnd(unittest.TestCase):
    """all.py --lanes K on a fake run.py: every scenario gets a verdict, lanes share the queue, CSV is written."""

    def test_two_lanes_share_the_queue(self):
        import subprocess
        tmp = tempfile.mkdtemp(prefix="lanes-e2e-", dir=HERE)
        try:
            for name in ("all.py", "diff.py", "lanes.py", "lanes.json"):
                shutil.copy(os.path.join(HERE, name), os.path.join(tmp, name))
            with open(os.path.join(tmp, "run.py"), "w", encoding="utf-8") as f:
                f.write(FAKE_LANE_RUN)
            sdir = os.path.join(tmp, "scen")
            os.makedirs(sdir)
            sids = ["s%d" % i for i in range(6)]
            for sid in sids:
                with open(os.path.join(sdir, sid + ".json"), "w", encoding="utf-8") as f:
                    json.dump({"id": sid, "setup": [], "steps": [{"observe": "block", "pos": [1, 2, 3]}]}, f)
            csv_path = os.path.join(tmp, "report.csv")
            env = dict(os.environ, PARITY_RESULTS=os.path.join(tmp, "res"))
            p = subprocess.run([sys.executable, "-I", os.path.join(tmp, "all.py"), "--dir", sdir,
                                "--lanes", "2", "--no-shots", "--csv", csv_path],
                               cwd=tmp, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=120)
            out = p.stdout.decode("utf-8", "replace")
            self.assertEqual(p.returncode, 0, out)
            verdict_lines = [l for l in out.splitlines() if l.startswith("VERDICT")]
            self.assertEqual(len(verdict_lines), 6, out)
            self.assertEqual(set(l.split("[lane ")[1][0] for l in verdict_lines), {"0", "1"}, out)
            with open(csv_path, encoding="utf-8") as f:
                rows = f.read()
            for sid in sids:
                self.assertIn(sid, rows)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
