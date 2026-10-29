"""Independent counterexamples found while auditing the approved recovery fence.

These supplement the frozen original inspection tests without changing an oracle.
All metadata and AWS ports are synthetic; no remote operation is performed.
"""
import unittest

import test_issue154_recovery_inspection as spec


class RecoveryInspectionNamespaceAuditTests(unittest.TestCase):
    def fixture(self):
        fixture = spec.RecoveryInspectionTests("test_fresh_namespace_is_the_only_missing_owner_case_allowed")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        return fixture

    def test_positive_or_mistyped_key_count_cannot_be_empty_with_omitted_or_empty_contents(self):
        responses = ({"IsTruncated": False, "KeyCount": 1},
                     {"IsTruncated": False, "KeyCount": 1, "Contents": []},
                     {"IsTruncated": False, "KeyCount": "0", "Contents": []},
                     {"IsTruncated": False, "KeyCount": False, "Contents": []})
        for response in responses:
            f = self.fixture()
            f.put(spec.ROOT_KEY + spec.op_id(spec.identity()) + ".json", spec.transition())
            f.driver.fault = lambda operation, key, response=response: response if key == spec.ROOT_KEY else None
            with self.subTest(response=response):
                f.guard(False)

    def test_explicit_zero_count_and_omitted_contents_remains_a_valid_empty_namespace(self):
        f = self.fixture()
        f.driver.fault = lambda operation, key: {"IsTruncated": False, "KeyCount": 0} if key == spec.ROOT_KEY else None
        self.assertEqual("FRESH_NAMESPACE", f.guard(True)["reason"])


if __name__ == "__main__":
    unittest.main()
