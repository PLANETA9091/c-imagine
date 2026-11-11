#!/usr/bin/env python3
"""Run the real workflow loop: an empty item registry still has sound proof."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


class Channels(unittest.TestCase):
    def test_empty_item_namespace_still_compares_sounds(self):
        source = (ROOT / '.github/workflows/parity-diff.yml').read_text()
        start = source.index('            for NS in $LADDER_NS; do')
        end = source.index('            echo "F2F parity diff: ladder identity measured', start)
        loop = textwrap.dedent(source[start:end])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'scripts').symlink_to(ROOT / 'scripts', target_is_directory=True)
            truth, ours = root / 'truth-scenes', root / 'ours-scenes'
            truth.mkdir()
            ours.mkdir()
            (root / 'parity-identity').mkdir()
            for scenes in (truth, ours):
                (scenes / 'gallery-sound-combat_roll-p0.log').write_text('combat_roll:roll\n')
            mapping = root / 'sounds.tsv'
            mapping.write_text('combat_roll:roll\tcombat_roll:roll\n')
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', loop], cwd=root,
                                    env={**os.environ, 'LADDER_NS': 'combat_roll',
                                         'TC': str(truth), 'SC': str(ours), 'SMAP': str(mapping)},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            report = root / 'parity-identity/sound-combat_roll.json'
            self.assertTrue(report.is_file(), result.stdout)
            self.assertEqual(json.loads(report.read_text())['verdict'], 'GREEN')


if __name__ == '__main__':
    unittest.main()
