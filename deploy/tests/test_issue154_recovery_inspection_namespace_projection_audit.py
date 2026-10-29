"""An uninspected namespace must not imply that its journal is missing."""
import unittest

import test_issue154_recovery_inspection as spec


class RecoveryInspectionNamespaceProjectionTests(unittest.TestCase):
    def fixture(self):
        f = spec.RecoveryInspectionTests("test_fresh_namespace_is_the_only_missing_owner_case_allowed")
        f.setUp()
        self.addCleanup(f.doCleanups)
        return f

    def test_existing_orphan_journal_is_unavailable_rather_than_falsely_missing(self):
        f = self.fixture()
        f.put(spec.ROOT_KEY + spec.op_id(spec.identity()) + ".json", spec.transition())
        report = f.guard(False)
        self.assertEqual("UNAVAILABLE", report["journal"]["status"])

    def test_namespace_read_denial_does_not_prove_that_a_journal_is_missing(self):
        f = self.fixture()
        def denied(operation, key):
            if key == spec.ROOT_KEY:
                raise OSError(spec.SECRET)
        f.driver.fault = denied
        self.assertEqual("UNAVAILABLE", f.guard(False)["journal"]["status"])


if __name__ == "__main__":
    unittest.main()
