"""A journal that was not read is unavailable, not known to be missing."""
import unittest

import test_issue154_recovery_inspection as spec


class RecoveryInspectionUnavailableProjectionTests(unittest.TestCase):
    def fixture(self):
        f = spec.RecoveryInspectionTests("test_fresh_namespace_is_the_only_missing_owner_case_allowed")
        f.setUp()
        self.addCleanup(f.doCleanups)
        f.install()
        return f

    def assert_unknown_journal(self, report):
        self.assertEqual("UNAVAILABLE", report["owner"]["status"])
        self.assertEqual("UNAVAILABLE", report["journal"]["status"],
                         "owner read failure provides no evidence that the existing journal is missing")
        self.assertTrue(all(value is None for key, value in report["journal"].items() if key != "status"))

    def test_denied_owner_read_does_not_claim_unread_existing_journal_is_missing(self):
        f = self.fixture()
        def denied(operation, key):
            if key == spec.ROOT_KEY + "owner.json":
                raise OSError(spec.SECRET)
        f.driver.fault = denied
        self.assert_unknown_journal(f.guard(False))

    def test_malformed_owner_does_not_claim_unread_existing_journal_is_missing(self):
        f = self.fixture()
        f.put(spec.ROOT_KEY + "owner.json", b'{"untrusted":')
        self.assert_unknown_journal(f.guard(False))


if __name__ == "__main__":
    unittest.main()
