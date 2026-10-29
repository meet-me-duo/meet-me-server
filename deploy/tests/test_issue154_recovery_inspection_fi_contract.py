"""Supplemental approved-boundary oracles for independent fault injection.

These already pass the implementation. Their mutant failures are explicitly
post-implementation FI evidence, not chronological production RED evidence.
"""
import unittest

import test_issue154_recovery_inspection as spec


class RecoveryInspectionIndependentFenceTests(unittest.TestCase):
    def fixture(self):
        f = spec.RecoveryInspectionTests("test_fresh_namespace_is_the_only_missing_owner_case_allowed")
        f.setUp()
        self.addCleanup(f.doCleanups)
        return f

    def test_owner_and_journal_agreeing_on_the_same_wrong_operation_hash_still_stop(self):
        f = self.fixture()
        wrong = "f" * 64
        f.put(spec.ROOT_KEY + wrong + ".json", spec.transition(operationId=wrong))
        f.put(spec.ROOT_KEY + "owner.json", spec.owner(operationId=wrong))
        f.guard(False)

    def test_valid_holder_change_with_unchanged_etag_still_stops(self):
        f = self.fixture()
        f.install()
        key = spec.ROOT_KEY + "owner.json"
        original_etag = f.driver.objects[key][1]
        def changed(count):
            if count == 1:
                raw = spec.canonical(spec.owner(holder="8" * 32))
                f.driver.objects[key] = (raw, original_etag)
        f.driver.after_owner_read = changed
        self.assertEqual("SNAPSHOT_CHANGED", f.guard(False)["reason"])

    def test_owner_etag_change_with_unchanged_valid_body_still_stops(self):
        f = self.fixture()
        f.install()
        key = spec.ROOT_KEY + "owner.json"
        def changed(count):
            if count == 1:
                f.driver.objects[key] = (f.driver.objects[key][0], '"changed-etag"')
        f.driver.after_owner_read = changed
        self.assertEqual("SNAPSHOT_CHANGED", f.guard(False)["reason"])


if __name__ == "__main__":
    unittest.main()
