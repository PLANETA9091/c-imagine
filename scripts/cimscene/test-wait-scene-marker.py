#!/usr/bin/env python3
"""Terminal-marker CLI regression for empty real mod registries."""
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('wait-scene-marker.sh')


class Marker(unittest.TestCase):
    def test_terminal_states(self):
        for markers, expected in ((('done',), 'done'), (('exhausted',), 'exhausted'),
                                  ((), None), (('done', 'exhausted'), None)):
            with self.subTest(markers=markers), tempfile.TemporaryDirectory() as directory:
                stem = Path(directory) / 'gallery-item-combat_roll-p0'
                for marker in markers:
                    Path(str(stem) + '.' + marker).touch()
                result = subprocess.run(['bash', str(SCRIPT), str(stem), '0'],
                                        capture_output=True, text=True)
                if expected is None:
                    self.assertNotEqual(result.returncode, 0)
                else:
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout.strip(), expected)

    def test_invalid_timeout_fails(self):
        result = subprocess.run(['bash', str(SCRIPT), 'missing', '-1'], capture_output=True)
        self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
