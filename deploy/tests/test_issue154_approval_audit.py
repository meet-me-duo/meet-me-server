"""Fresh independent audit cycle for issue154 approval and completion safety.

Original issue154 approval/completion tests remain frozen. Synthetic snapshots
exercise self-review policy and distinct GitHub job-resource identity.
"""
import copy
import unittest

from test_issue154_approval import GATE, fixture


class Issue154ApprovalAuditTests(unittest.TestCase):
    def test_live_prevent_self_review_rejects_missing_actor_identity(self):
        identity, config, evidence = fixture()
        evidence["environment"]["protection_rules"][0]["prevent_self_review"] = True
        with self.assertRaises(ValueError):
            GATE.verify_approval(identity, config, evidence)

    def test_live_prevent_self_review_rejects_initiator_or_triggering_actor(self):
        for initiator, triggering in ((17, 18), (18, 17), (17, 17)):
            identity, config, evidence = fixture()
            evidence["environment"]["protection_rules"][0]["prevent_self_review"] = True
            evidence["run"]["actor"] = {"id": initiator, "type": "User"}
            evidence["run"]["triggering_actor"] = {"id": triggering, "type": "User"}
            with self.subTest(initiator=initiator, triggering=triggering), self.assertRaises(ValueError):
                GATE.verify_approval(identity, config, evidence)

    def test_live_prevent_self_review_accepts_a_distinct_allowed_user(self):
        identity, config, evidence = fixture()
        evidence["environment"]["protection_rules"][0]["prevent_self_review"] = True
        evidence["run"]["actor"] = {"id": 18, "type": "User"}
        evidence["run"]["triggering_actor"] = {"id": 19, "type": "User"}
        self.assertIs(GATE.verify_approval(identity, config, evidence)["approved"], True)

    def test_one_github_job_resource_cannot_be_both_publication_and_approval(self):
        identity, config, evidence = fixture()
        evidence["jobs"]["jobs"][1]["id"] = evidence["jobs"]["jobs"][0]["id"]
        with self.assertRaises(ValueError):
            GATE.verify_approval(identity, config, evidence)

    def test_duplicate_job_resource_is_rejected_even_when_unrelated_name(self):
        identity, config, evidence = fixture()
        copied = copy.deepcopy(evidence["jobs"]["jobs"][0])
        copied["name"] = "Unrelated successful step"
        evidence["jobs"]["jobs"].append(copied)
        evidence["jobs"]["total_count"] = 3
        with self.assertRaises(ValueError):
            GATE.verify_approval(identity, config, evidence)

    def test_pre_yaml_helper_requires_real_existing_protection(self):
        identity, config, evidence = fixture()
        self.assertIs(GATE.verify_environment(config, evidence["environment"], evidence["branchPolicies"]), True)
        for changed in (
            evidence["environment"] | {"protection_rules": []},
            evidence["environment"] | {"id": 999},
            evidence["environment"] | {"can_admins_bypass": True},
        ):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                GATE.verify_environment(config, changed, evidence["branchPolicies"])

    def test_pre_yaml_helper_cannot_reference_an_unconfigured_environment(self):
        _, _, evidence = fixture()
        with self.assertRaises(ValueError):
            GATE.verify_environment(
                {"protocol": "meet-me-transition-gate-policy-v1", "state": "UNCONFIGURED"},
                evidence["environment"], evidence["branchPolicies"],
            )


if __name__ == "__main__":
    unittest.main()

