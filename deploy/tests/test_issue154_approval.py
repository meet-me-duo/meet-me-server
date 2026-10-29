"""Issue154: a real, bound pipeline approval is required before maintenance.

Independent contract tests. All API evidence is synthetic, shaped from GitHub's
documented public REST responses. No API, AWS, environment or provider calls run.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_approval_gate", ROOT / "pipeline/approval_gate.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)
REPOSITORY = "meet-me-duo/meet-me-server"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def fixture():
    source = "a" * 40
    manifest = {
        "protocol": "meet-me-approved-release-v1",
        "sourceSha": source, "buildRunId": "501", "buildRunAttempt": "1",
        "imageRepository": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/synthetic/server",
        "imageDigest": "sha256:" + "b" * 64,
        "releaseId": source + "-501-1", "archiveSha256": "c" * 64,
        "schemaFingerprint": "d" * 64, "guardFingerprint": "e" * 64,
        "migrations": [{"version": "19", "script": "V19__synthetic.sql", "checksum": 123}],
        "origin": {"repository": REPOSITORY, "ref": "refs/heads/main", "workflow": "CI",
                   "event": "push", "conclusion": "success", "ciRunId": "500", "ciSha": source},
    }
    identity = {
        "sourceSha": source, "buildRunId": "501", "buildRunAttempt": "1",
        "workflowRunId": "501", "workflowRunAttempt": "1",
        "releaseId": manifest["releaseId"], "imageDigest": manifest["imageDigest"],
        "manifestSha256": hashlib.sha256(canonical(manifest)).hexdigest(),
    }
    config = {
        "protocol": "meet-me-transition-gate-policy-v1", "state": "CONFIGURED",
        "environment": {"id": 31, "name": "production-transition-approval"},
        "reviewerIds": [17], "gateJobName": "Approve production transition",
    }
    evidence = {
        "manifest": manifest,
        "run": {
            "id": 501, "run_attempt": 1, "head_sha": source, "head_branch": "main",
            "event": "workflow_run", "path": ".github/workflows/deploy.yml",
            "name": "Deploy Production", "status": "in_progress", "conclusion": None,
            "repository": {"full_name": REPOSITORY},
            "head_repository": {"full_name": REPOSITORY},
        },
        "jobs": {"total_count": 2, "jobs": [
            {"id": 701, "run_id": 501, "name": "Build and Publish", "head_sha": source,
             "status": "completed", "conclusion": "success"},
            {"id": 702, "run_id": 501, "name": "Approve production transition", "head_sha": source,
             "status": "completed", "conclusion": "success"},
        ]},
        "approvals": [{
            "state": "approved", "comment": "Synthetic approval",
            "environments": [{"id": 31, "name": "production-transition-approval"}],
            "user": {"id": 17, "login": "synthetic-reviewer", "type": "User"},
        }],
        "environment": {
            "id": 31, "name": "production-transition-approval",
            "protection_rules": [{
                "id": 55, "type": "required_reviewers", "prevent_self_review": False,
                "reviewers": [{"type": "User", "reviewer": {"id": 17, "login": "synthetic-reviewer"}}],
            }],
            "deployment_branch_policy": {"protected_branches": False, "custom_branch_policies": True},
        },
        "branchPolicies": {"total_count": 1, "branch_policies": [{"id": 61, "name": "main", "type": "branch"}]},
    }
    return identity, config, evidence


def verify(identity=None, config=None, evidence=None):
    original_identity, original_config, original_evidence = fixture()
    return GATE.verify_approval(
        original_identity if identity is None else identity,
        original_config if config is None else config,
        original_evidence if evidence is None else evidence,
    )


class Issue154ApprovalTests(unittest.TestCase):
    def reject(self, identity, config, evidence):
        with self.assertRaises(ValueError):
            GATE.verify_approval(identity, config, evidence)

    def test_unconfigured_gate_has_no_environment_reference(self):
        self.assertIsNone(GATE.validate_config({
            "protocol": "meet-me-transition-gate-policy-v1", "state": "UNCONFIGURED",
        }))

    def test_configured_policy_is_validated_without_changing_its_values(self):
        _, config, _ = fixture()
        self.assertEqual(config, GATE.validate_config(config))
        self.assertEqual(31, config["environment"]["id"])

    def test_unconfigured_policy_cannot_satisfy_an_actual_transition_approval(self):
        identity, _, evidence = fixture()
        self.reject(identity, {"protocol": "meet-me-transition-gate-policy-v1", "state": "UNCONFIGURED"}, evidence)

    def test_configuration_rejects_unknown_or_partially_configured_gate(self):
        _, config, _ = fixture()
        cases = [
            None, True, {}, {"protocol": config["protocol"], "state": "UNCONFIGURED", "environment": config["environment"]},
            config | {"state": "UNKNOWN"}, config | {"protocol": "untrusted"},
            config | {"reviewerIds": []}, config | {"reviewerIds": [17, 17]},
            config | {"reviewerIds": [True]}, config | {"reviewerIds": ["17"]},
            config | {"reviewerIds": [0]}, config | {"reviewerIds": [-1]},
            config | {"gateJobName": ""}, config | {"gateJobName": "Approve\nInjected"},
            config | {"environment": {"id": True, "name": config["environment"]["name"]}},
            config | {"environment": {"id": 31, "name": ""}},
            config | {"environment": {"id": 31, "name": "../production"}},
            config | {"environment": config["environment"] | {"unexpected": "value"}},
            config | {"unexpected": "ignored"},
        ]
        for candidate in cases:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                GATE.validate_config(candidate)

    def test_exact_realistic_attempt_one_approval_satisfies_gate(self):
        identity, config, evidence = fixture()
        result = GATE.verify_approval(identity, config, evidence)
        self.assertIs(result["approved"], True)
        for field in identity:
            self.assertEqual(identity[field], result[field], field)
        self.assertEqual(31, result["environmentId"])
        self.assertEqual(17, result["reviewerId"])
        self.assertEqual(702, result["gateJobId"])

    def test_no_undocumented_approval_timestamp_or_attempt_field_is_required(self):
        identity, config, evidence = fixture()
        self.assertNotIn("run_attempt", evidence["approvals"][0])
        self.assertNotIn("created_at", evidence["approvals"][0])
        self.assertNotIn("job_id", evidence["approvals"][0])
        self.assertIs(GATE.verify_approval(identity, config, evidence)["approved"], True)

    def test_explicit_admin_bypass_permission_is_rejected_when_returned(self):
        identity, config, evidence = fixture()
        evidence["environment"]["can_admins_bypass"] = True
        self.reject(identity, config, evidence)

    def test_explicit_disabled_admin_bypass_is_accepted_with_all_other_evidence(self):
        identity, config, evidence = fixture()
        evidence["environment"]["can_admins_bypass"] = False
        self.assertIs(GATE.verify_approval(identity, config, evidence)["approved"], True)

    def test_missing_real_approval_cannot_be_replaced_by_job_success_or_boolean(self):
        for approvals in ([], True, {"approved": True}, None):
            identity, config, evidence = fixture()
            evidence["approvals"] = approvals
            with self.subTest(approvals=approvals):
                self.reject(identity, config, evidence)

    def test_attempt_two_cannot_reuse_run_scoped_history(self):
        identity, config, evidence = fixture()
        identity["workflowRunAttempt"] = "2"
        evidence["run"]["run_attempt"] = 2
        self.reject(identity, config, evidence)

    def test_identity_is_exact_typed_and_bound_to_full_manifest(self):
        identity, config, evidence = fixture()
        mutations = [
            {"sourceSha": "f" * 40}, {"buildRunId": "502"}, {"buildRunAttempt": "2"},
            {"workflowRunId": "502"}, {"workflowRunAttempt": "0"},
            {"manifestSha256": "0" * 64}, {"manifestSha256": True},
            {"releaseId": "different"}, {"imageDigest": "sha256:" + "f" * 64},
            {"sourceSha": True}, {"buildRunId": 501}, {"extra": "ignored"},
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.reject(identity | mutation, config, evidence)
        for removed in identity:
            candidate = identity.copy()
            candidate.pop(removed)
            with self.subTest(removed=removed):
                self.reject(candidate, config, evidence)

    def test_even_nonidentity_manifest_changes_invalidate_exact_approval_binding(self):
        identity, config, evidence = fixture()
        evidence["manifest"]["archiveSha256"] = "f" * 64
        self.reject(identity, config, evidence)

    def test_run_must_be_exact_repository_main_publication_and_source(self):
        for field, value in (
            ("id", 502), ("run_attempt", 2), ("run_attempt", True),
            ("head_sha", "f" * 40), ("head_branch", "feature/untrusted"),
            ("event", "pull_request"), ("event", "workflow_dispatch"),
            ("path", ".github/workflows/other.yml"), ("name", "Other workflow"),
            ("status", "completed"), ("conclusion", "failure"),
            ("repository", {"full_name": "untrusted/server"}),
            ("head_repository", {"full_name": "untrusted/fork"}),
        ):
            identity, config, evidence = fixture()
            evidence["run"][field] = value
            with self.subTest(field=field, value=value):
                self.reject(identity, config, evidence)

    def test_required_publication_and_gate_jobs_are_unique_same_run_and_successful(self):
        for index in (0, 1):
            for field, value in (
                ("run_id", 502), ("head_sha", "f" * 40),
                ("status", "queued"), ("conclusion", "failure"),
                ("conclusion", "skipped"), ("conclusion", None),
            ):
                identity, config, evidence = fixture()
                evidence["jobs"]["jobs"][index][field] = value
                with self.subTest(index=index, field=field, value=value):
                    self.reject(identity, config, evidence)
        for index in (0, 1):
            identity, config, evidence = fixture()
            evidence["jobs"]["jobs"].pop(index)
            evidence["jobs"]["total_count"] = 1
            with self.subTest(missing=index):
                self.reject(identity, config, evidence)
            identity, config, evidence = fixture()
            evidence["jobs"]["jobs"].append(copy.deepcopy(evidence["jobs"]["jobs"][index]))
            evidence["jobs"]["total_count"] = 3
            with self.subTest(duplicate=index):
                self.reject(identity, config, evidence)

    def test_truncated_job_list_or_unknown_job_count_is_not_proof(self):
        for count in (0, 1, 3, True, "2", None):
            identity, config, evidence = fixture()
            evidence["jobs"]["total_count"] = count
            with self.subTest(count=count):
                self.reject(identity, config, evidence)

    def test_environment_id_and_name_must_match_configuration(self):
        for field, value in (("id", 32), ("id", True), ("name", "production"), ("name", None)):
            identity, config, evidence = fixture()
            evidence["environment"][field] = value
            with self.subTest(field=field):
                self.reject(identity, config, evidence)

    def test_live_nonempty_required_user_reviewers_are_mandatory(self):
        mutations = [
            [], [{"id": 55, "type": "wait_timer", "wait_timer": 0}],
            [{"id": 55, "type": "required_reviewers", "prevent_self_review": False, "reviewers": []}],
            [{"id": 55, "type": "required_reviewers", "prevent_self_review": False,
              "reviewers": [{"type": "User", "reviewer": {"id": 18}}]}],
            [{"id": 55, "type": "required_reviewers", "prevent_self_review": False,
              "reviewers": [{"type": "Team", "reviewer": {"id": 17}}]}],
        ]
        for rules in mutations:
            identity, config, evidence = fixture()
            evidence["environment"]["protection_rules"] = rules
            with self.subTest(rules=rules):
                self.reject(identity, config, evidence)

    def test_reviewer_membership_cannot_be_forged_by_login_or_team_id(self):
        for user in ({"id": 18, "login": "synthetic-reviewer", "type": "User"},
                     {"id": True, "type": "User"}, {"id": "17", "type": "User"},
                     {"id": 17, "type": "Bot"}, {"id": 17, "type": "Team"}):
            identity, config, evidence = fixture()
            evidence["approvals"][0]["user"] = user
            with self.subTest(user=user):
                self.reject(identity, config, evidence)

    def test_approval_must_name_exact_gate_environment(self):
        for environment in ({"id": 32, "name": "production-transition-approval"},
                            {"id": 31, "name": "production"},
                            {"id": True, "name": "production-transition-approval"}):
            identity, config, evidence = fixture()
            evidence["approvals"][0]["environments"] = [environment]
            with self.subTest(environment=environment):
                self.reject(identity, config, evidence)

    def test_rejected_or_unknown_gate_review_is_not_approval(self):
        for state in ("rejected", "pending", "APPROVED", True, None):
            identity, config, evidence = fixture()
            evidence["approvals"][0]["state"] = state
            with self.subTest(state=state):
                self.reject(identity, config, evidence)
        identity, config, evidence = fixture()
        rejected = copy.deepcopy(evidence["approvals"][0])
        rejected["state"] = "rejected"
        evidence["approvals"].append(rejected)
        self.reject(identity, config, evidence)

    def test_unrestricted_or_nonmain_deployment_branch_policy_is_rejected(self):
        cases = [
            (None, {"total_count": 0, "branch_policies": []}),
            ({"protected_branches": True, "custom_branch_policies": False},
             {"total_count": 1, "branch_policies": [{"id": 61, "name": "main", "type": "branch"}]}),
            ({"protected_branches": False, "custom_branch_policies": True},
             {"total_count": 1, "branch_policies": [{"id": 61, "name": "*", "type": "branch"}]}),
            ({"protected_branches": False, "custom_branch_policies": True},
             {"total_count": 1, "branch_policies": [{"id": 61, "name": "main", "type": "tag"}]}),
            ({"protected_branches": False, "custom_branch_policies": True},
             {"total_count": 2, "branch_policies": [{"id": 61, "name": "main", "type": "branch"}]}),
        ]
        for policy, branches in cases:
            identity, config, evidence = fixture()
            evidence["environment"]["deployment_branch_policy"] = policy
            evidence["branchPolicies"] = branches
            with self.subTest(policy=policy, branches=branches):
                self.reject(identity, config, evidence)

    def test_unknown_or_missing_snapshot_sections_cannot_be_silently_ignored(self):
        identity, config, evidence = fixture()
        for section in evidence:
            candidate = copy.deepcopy(evidence)
            candidate.pop(section)
            with self.subTest(missing=section):
                self.reject(identity, config, candidate)
        self.reject(identity, config, evidence | {"approved": True})

    def test_input_evidence_and_configuration_are_not_mutated(self):
        identity, config, evidence = fixture()
        before = copy.deepcopy((identity, config, evidence))
        GATE.verify_approval(identity, config, evidence)
        self.assertEqual(before, (identity, config, evidence))


if __name__ == "__main__":
    unittest.main()

