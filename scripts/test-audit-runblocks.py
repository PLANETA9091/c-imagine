#!/usr/bin/env python3
"""CLI checks for nonempty workflow discovery and failure propagation."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('audit_runblocks.py')


class Audit(unittest.TestCase):
    def run_audit(self, content):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            if content is not None:
                (root / 'fixture.yml').write_text(content)
            return subprocess.run([sys.executable, str(SCRIPT), '--workflow-dir', str(root)],
                                  cwd=root, capture_output=True, text=True)

    def test_valid_block(self):
        result = self.run_audit('steps:\n  - name: valid\n    run: |\n      echo hello\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('WORKFLOWS: 1; BLOCKS: 1; FAILURES: 0', result.stdout)

    def test_invalid_block_fails(self):
        result = self.run_audit('steps:\n  - name: invalid\n    run: |\n      if true; then\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('FAIL fixture.yml:', result.stdout)

    def test_empty_discovery_and_no_blocks_fail(self):
        for content in (None, 'name: no shell blocks\n'):
            with self.subTest(content=content):
                self.assertNotEqual(self.run_audit(content).returncode, 0)

    def test_default_path_independent_of_caller_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(SCRIPT)], cwd=directory,
                                    capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('WORKFLOWS:', result.stdout)
        self.assertNotIn('BLOCKS: 0;', result.stdout)


if __name__ == '__main__':
    unittest.main()
