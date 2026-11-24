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


class CarriedCursorTests(unittest.TestCase):
    """The cursor stack of a modded item is a carrier (paper) on the client; /cim inspect inv has the id."""

    def _stack(self, carried, server):
        st = stacks.OursStack.__new__(stacks.OursStack)
        st.ctl = lambda *a, **k: {"screen": "playing", "slots": [], "carried": carried}
        st._server_inv = lambda: server
        return st

    def test_cursor_modded_id_replaces_paper(self):
        st = self._stack({"item": "minecraft:paper", "count": 1},
                         [{"slot": "cursor", "index": -1, "id": "create:shaft", "count": 1}])
        _, cid = st._screen_map()
        self.assertEqual(cid, "create:shaft")

    def test_cursor_count_mismatch_keeps_client_id(self):
        st = self._stack({"item": "minecraft:paper", "count": 2},
                         [{"slot": "cursor", "index": -1, "id": "create:shaft", "count": 1}])
        _, cid = st._screen_map()
        self.assertEqual(cid, "minecraft:paper")

    def test_vanilla_cursor_untouched(self):
        st = self._stack({"item": "minecraft:stick", "count": 3},
                         [{"slot": "cursor", "index": -1, "id": "minecraft:stick", "count": 3}])
        _, cid = st._screen_map()
        self.assertEqual(cid, "minecraft:stick")


class GiveSyntaxTests(unittest.TestCase):
    def test_enchanted_book_levels_wrapper_is_26_3_form(self):
        st = stacks.OursStack.__new__(stacks.OursStack)
        self.assertEqual(
            st.translate('give {player} minecraft:enchanted_book[stored_enchantments={levels:{"combat_roll:acrobat":1}}] 1'),
            'give {player} minecraft:enchanted_book[stored_enchantments={"combat_roll:acrobat":1}] 1')

    def test_modded_give_still_maps_to_cim(self):
        st = stacks.OursStack.__new__(stacks.OursStack)
        self.assertEqual(st.translate("give {player} create:shaft 2"), "cim give %s create:shaft 2" % stacks.OURS_PLAYER)


if __name__ == "__main__":
    unittest.main()
