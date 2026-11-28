#!/usr/bin/env python3
"""Unit tests for the Easton r4/r5 fixes: offhand as dict or list, reply wrapping, refused commands,
peaceful setup and the chat recovery in heal. Pure Python, no stand. Run: python3 -I test_r5_fixes.py"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import heal  # noqa: E402
import stacks  # noqa: E402

REFUSED = "Chat disabled in client options."


def slot(item, count=1):
    return {"slot": 0, "index": 0, "item": item, "count": count, "damage": 0}


class NormInventory(unittest.TestCase):
    def test_offhand_dict_and_list_agree(self):
        as_dict = stacks.norm_inventory({"hotbar": [slot("minecraft:stone", 3)], "main": [], "armor": [],
                                         "offhand": slot("minecraft:shield", 1)})
        as_list = stacks.norm_inventory({"hotbar": [slot("minecraft:stone", 3)], "main": [], "armor": [],
                                         "offhand": [slot("minecraft:shield", 1)]})
        self.assertEqual(as_dict, as_list)
        self.assertEqual(as_dict["counts"].get("minecraft:shield") or as_dict["counts"].get("shield"), 1)

    def test_offhand_empty_and_missing(self):
        self.assertEqual(stacks.norm_inventory({"hotbar": [], "main": [], "armor": [], "offhand": None})["counts"], {})
        self.assertEqual(stacks.norm_inventory({"hotbar": [], "main": [], "armor": []})["counts"], {})

    def test_non_dict_slot_is_skipped(self):
        self.assertEqual(stacks.norm_inventory({"hotbar": [], "main": [], "armor": [], "offhand": ["x", None]})["counts"], {})


class ParseReply(unittest.TestCase):
    def test_bare_text_and_json_string(self):
        self.assertEqual(stacks.parse_reply("done"), {"raw": "done"})
        self.assertEqual(stacks.parse_reply('"done"'), {"raw": "done"})

    def test_json_object_passes_and_list_is_wrapped(self):
        self.assertEqual(stacks.parse_reply('{"a": 1}'), {"a": 1})
        self.assertEqual(stacks.parse_reply("[1, 2]"), {"raw": "[1, 2]"})
        self.assertEqual(stacks.parse_reply(""), {})


class FakeOurs(stacks.OursStack):
    """OursStack with scripted ctl replies. Records every ctl call."""

    def __init__(self, chat_log_seq, command_reply=None, options_fail=False):
        self.hz_n = "9"
        self.player = "Lane_B"
        self.calls = []
        self._chat = list(chat_log_seq)
        self._command_reply = command_reply if command_reply is not None else {"ok": True}
        self._options_fail = options_fail

    def ctl(self, *args, timeout=300):
        self.calls.append(args)
        if args[0] == "chat-log":
            return self._chat.pop(0) if len(self._chat) > 1 else self._chat[0]
        if args[0] == "command":
            return self._command_reply
        if args[0] == "options-set" and self._options_fail:
            raise RuntimeError("options refused")
        return {}

    def translate(self, text):
        return text


class RefusedCommand(unittest.TestCase):
    def test_accepted_command_is_ok(self):
        s = FakeOurs([{"messages": []}, {"messages": []}])
        s.setup_command("difficulty peaceful")

    def test_new_refusal_line_is_error(self):
        s = FakeOurs([{"messages": []}, {"messages": [REFUSED]}])
        with self.assertRaises(RuntimeError):
            s.setup_command("give Lane_B stone")

    def test_refusal_in_reply_is_error(self):
        s = FakeOurs([{"messages": []}], command_reply={"raw": REFUSED})
        with self.assertRaises(RuntimeError):
            s.setup_command("give Lane_B stone")

    def test_old_refusal_does_not_repeat_as_error(self):
        old = {"messages": [REFUSED]}
        s = FakeOurs([old, old])
        s.setup_command("difficulty peaceful")


class HealSetup(unittest.TestCase):
    def test_truth_like_stack_without_setup_hooks(self):
        class Plain:
            def heal_probe(self):
                return {"kind": "playing", "buttons": []}

            def wait_ready(self, s):
                return {}
        r = heal.heal(Plain())
        self.assertTrue(r["ok"])
        self.assertEqual(r["actions"], ["wait-ready"])

    def test_setup_refused_then_options_recovery(self):
        class S:
            def __init__(self):
                self.n = 0

            def heal_probe(self):
                return {"kind": "playing", "buttons": []}

            def heal_setup(self):
                self.n += 1
                if self.n == 1:
                    raise RuntimeError("client refused command")

            def heal_chat_recover(self):
                return {"ok": True, "actions": ["options chatVisibility 2 then 0"], "relaunch": False}

            def wait_ready(self, s):
                return {}
        r = heal.heal(S())
        self.assertTrue(r["ok"], r)
        self.assertIn("options chatVisibility 2 then 0", r["actions"])
        self.assertIn("difficulty peaceful", r["actions"])

    def test_relaunch_reruns_heal_once(self):
        class S:
            def __init__(self):
                self.setups = 0
                self.relaunches = 0

            def heal_probe(self):
                return {"kind": "playing", "buttons": []}

            def heal_setup(self):
                self.setups += 1
                if self.setups == 1:
                    raise RuntimeError("client refused command")

            def heal_chat_recover(self):
                self.relaunches += 1
                return {"ok": True, "actions": ["relaunch lane 9 client"], "relaunch": True}

            def wait_ready(self, s):
                return {}
        s = S()
        r = heal.heal(s)
        self.assertTrue(r["ok"], r)
        self.assertEqual(s.relaunches, 1)
        self.assertEqual(r["actions"][0].split(" (")[0], "setup refused")
        self.assertIn("relaunch lane 9 client", r["actions"])
        self.assertEqual(r["actions"][-2:], ["difficulty peaceful", "wait-ready"])

    def test_recovery_failure_is_infra_error(self):
        class S:
            def heal_probe(self):
                return {"kind": "playing", "buttons": []}

            def heal_setup(self):
                raise RuntimeError("client refused command")

            def heal_chat_recover(self):
                return {"ok": False, "actions": ["relaunch of lane 9 failed rc=1"], "relaunch": False}
        r = heal.heal(S())
        self.assertFalse(r["ok"])
        self.assertIn("client refused", r["error"])

    def test_teardown_has_no_setup(self):
        class S:
            def heal_probe(self):
                return {"kind": "playing", "buttons": []}

            def heal_setup(self):
                raise AssertionError("teardown must not set difficulty")
        r = heal.heal(S(), ready=False)
        self.assertTrue(r["ok"])
        self.assertEqual(r["actions"], [])


if __name__ == "__main__":
    unittest.main()
