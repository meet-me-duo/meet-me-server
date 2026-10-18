"""Additive well-formed unknown identifier boundary; synthetic metadata only."""
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('a24_unknown_id_target', Path(__file__).with_name('run_candidate_a24_tests.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class UnknownIdContract(unittest.TestCase):
    def test_APRX01_well_formed_unlisted_id_is_not_public(self):
        name = 'test_A24PY99_SYNTHETIC_PRIVATE'
        text = f'FAIL: {name} (__main__.CandidateA24Contract.{name})\n'
        self.assertEqual([], MODULE.safe_python_failures(text))

if __name__ == '__main__':
    unittest.main(verbosity=2)
