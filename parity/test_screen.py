"""Screen normalization: a modded menu is compared by registry id on both stacks."""
import unittest

import stacks


class ScreenKindTests(unittest.TestCase):
    def test_truth_modded_menu_is_registry_id(self):
        r = {"screen": "ToolboxScreen", "menu_type": "create:toolbox", "slots": []}
        self.assertEqual(stacks.norm_screen(r, "truth")["kind"], "create:toolbox")

    def test_ours_server_menu_matches_truth(self):
        ours = {"screen": "toolboxscreen", "menu_type": "create:toolbox", "slots": []}
        truth = {"screen": "ToolboxScreen", "menu_type": "create:toolbox", "slots": []}
        self.assertEqual(stacks.norm_screen(ours, "ours")["kind"], stacks.norm_screen(truth, "truth")["kind"])

    def test_vanilla_menu_keeps_screen_kind(self):
        r = {"screen": "CraftingScreen", "menu_type": "minecraft:crafting", "slots": []}
        self.assertEqual(stacks.norm_screen(r, "truth")["kind"], "crafting")

    def test_playing_has_no_menu(self):
        r = {"screen": "playing", "menu_type": None, "slots": [{"index": 1, "item": "paper", "count": 1}]}
        self.assertEqual(stacks.norm_screen(r, "truth"), {"kind": "playing", "slots": []})

    def test_no_menu_type_keeps_name(self):
        r = {"screen": "toolboxscreen", "menu_type": None, "slots": []}
        self.assertEqual(stacks.norm_screen(r, "ours")["kind"], "toolboxscreen")


if __name__ == "__main__":
    unittest.main()
