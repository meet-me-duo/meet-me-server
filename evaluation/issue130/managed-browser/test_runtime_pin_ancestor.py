"""Supplemental immutable-root contract: canonical path control and ancestor symlink denial."""
import unittest
from pathlib import Path
import test_runtime_pin as original


class AncestorPathContract(unittest.TestCase):
    def setUp(self):
        self.fixture = original.PinContract('test_MLP01_positive_is_file_only_and_binds_all_four_digests')
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)

    def test_MAP01_canonical_manifest_control(self):
        self.assertTrue(original.MODULE.validate_manifest(self.fixture.path)['manifestSha256'])

    def test_MAP02_manifest_ancestor_symlink_is_not_immutable_source(self):
        alias = self.fixture.root / 'ancestor-link'
        alias.symlink_to(self.fixture.root, target_is_directory=True)
        try:
            original.MODULE.validate_manifest(alias / 'manifest.json')
        except Exception as failure:
            self.assertIsInstance(failure, ValueError)
            self.assertEqual('INVALID_MANIFEST', str(failure))
        else:
            self.fail('MANIFEST_EXISTING_ANCESTOR_SYMLINK_MUST_BE_REJECTED')


if __name__ == '__main__':
    unittest.main(verbosity=2)
