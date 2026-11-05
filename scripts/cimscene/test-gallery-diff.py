#!/usr/bin/env python3
"""The verdict classes of gallery-diff.py, without any images."""
import importlib.util
import pathlib
import sys
import unittest

sys.dont_write_bytecode = True
_spec = importlib.util.spec_from_file_location(
    "gallery_diff", pathlib.Path(__file__).with_name("gallery-diff.py"))
gd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gd)


def verdict(over=True, dyn=False, gated=False, air=False, animated=False, spill=False):
    return gd.classify(over, dyn, gated, air, animated, spill)


class Classify(unittest.TestCase):
    def test_under_the_gate_is_green_whatever_the_flags(self):
        self.assertEqual(verdict(over=False, dyn=True, spill=True), "GREEN")

    def test_ungated_dyn_is_excused(self):
        self.assertEqual(verdict(dyn=True), "DYNAMIC")

    def test_gated_dyn_is_red(self):
        self.assertEqual(verdict(dyn=True, gated=True), "RED")

    def test_a_dyn_neighbour_cannot_excuse_a_gated_dyn_cell(self):
        self.assertEqual(verdict(dyn=True, gated=True, spill=True), "RED")

    def test_a_static_cell_keeps_its_spill_excuse(self):
        self.assertEqual(verdict(spill=True), "SPILL")

    def test_static_red_stays_red(self):
        self.assertEqual(verdict(), "RED")

    def test_air_and_animation_keep_their_classes(self):
        self.assertEqual(verdict(air=True), "AIR")
        self.assertEqual(verdict(dyn=True, gated=True, animated=True), "ANIMATED")


if __name__ == "__main__":
    unittest.main()
