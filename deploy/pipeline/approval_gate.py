"""Read-only proof of a protected, attempt-bound GitHub transition gate.

The workflow must never create an environment in order to verify it. Only a
configured, already protected environment with an actual allowed-user approval
can authorize the transition. API availability errors are not approvals.
"""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re


REPOSITORY = "meet-me-duo/meet-me-server"
PROTOCOL = "meet-me-transition-gate-policy-v1"
COMMON_FIELDS = frozenset(("sourceSha", "buildRunId", "buildRunAttempt", "workflowRunId",
                           "workflowRunAttempt", "releaseId", "imageDigest"))
EVIDENCE_FIELDS = frozenset(("manifest", "run", "jobs", "approvals", "environment", "branchPolicies"))


def _require(condition):
    if not condition:
        raise ValueError("UNTRUSTED_TRANSITION_APPROVAL")


def _positive(value):
    return type(value) is int and 0 < value < 2 ** 63


def _matches(value, pattern):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def validate_config(config):
    _require(type(config) is dict and config.get("protocol") == PROTOCOL)
    if config.get("state") == "UNCONFIGURED":
        _require(set(config) == {"protocol", "state"})
        return None
    _require(set(config) == {"protocol", "state", "environment", "reviewerIds", "gateJobName"})
    _require(config["state"] == "CONFIGURED")
    environment = config["environment"]
    _require(type(environment) is dict and set(environment) == {"id", "name"})
    _require(_positive(environment["id"]))
    _require(_matches(environment["name"], r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
    reviewers = config["reviewerIds"]
    _require(type(reviewers) is list and 1 <= len(reviewers) <= 6)
    _require(all(_positive(item) for item in reviewers) and len(set(reviewers)) == len(reviewers))
    _require(_matches(config["gateJobName"], r"[A-Za-z0-9][A-Za-z0-9 ._-]{0,99}"))
    _require(config["gateJobName"] != "Build and Publish")
    return copy.deepcopy(config)


def verify_environment(config, environment, branch_policies):
    """Verify existing protection before a YAML environment is ever referenced."""
    configured = validate_config(config)
    _require(configured is not None)
    _require(type(environment) is dict)
    target = configured["environment"]
    _require(_positive(environment.get("id")) and environment["id"] == target["id"])
    _require(environment.get("name") == target["name"])
    if "can_admins_bypass" in environment:
        _require(environment["can_admins_bypass"] is False)
    rules = environment.get("protection_rules")
    _require(type(rules) is list and 1 <= len(rules) <= 20)
    required = [row for row in rules if type(row) is dict and row.get("type") == "required_reviewers"]
    _require(len(required) == 1)
    rule = required[0]
    _require(type(rule.get("prevent_self_review")) is bool)
    reviewers = rule.get("reviewers")
    _require(type(reviewers) is list and 1 <= len(reviewers) <= 6)
    ids = []
    for item in reviewers:
        _require(type(item) is dict and item.get("type") == "User")
        reviewer = item.get("reviewer")
        _require(type(reviewer) is dict and _positive(reviewer.get("id")))
        ids.append(reviewer["id"])
    _require(len(set(ids)) == len(ids) and set(ids) == set(configured["reviewerIds"]))
    policy = environment.get("deployment_branch_policy")
    _require(type(policy) is dict and set(policy) == {"protected_branches", "custom_branch_policies"})
    _require(policy["protected_branches"] is False and policy["custom_branch_policies"] is True)
    _require(type(branch_policies) is dict and type(branch_policies.get("total_count")) is int)
    branches = branch_policies.get("branch_policies")
    _require(type(branches) is list and branch_policies["total_count"] == len(branches) == 1)
    branch = branches[0]
    _require(type(branch) is dict and _positive(branch.get("id")))
    _require(branch.get("name") == "main" and branch.get("type") == "branch")
    return True


def _identity(identity):
    _require(type(identity) is dict and set(identity) == COMMON_FIELDS | {"manifestSha256"})
    _require(_matches(identity["manifestSha256"], r"[0-9a-f]{64}"))
    spec = importlib.util.spec_from_file_location("transition_completion_contract", Path(__file__).with_name("release_completion.py"))
    completion = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(completion)
    completion.validate_identity({key: identity[key] for key in COMMON_FIELDS})
    # Approval history has no attempt binding. Never reuse it for rerun attempts.
    _require(identity["workflowRunAttempt"] == identity["buildRunAttempt"] == "1")
    _require(identity["workflowRunId"] == identity["buildRunId"])


def _job(jobs, name, identity):
    found = [job for job in jobs if type(job) is dict and job.get("name") == name]
    _require(len(found) == 1)
    job = found[0]
    _require(_positive(job.get("id")) and type(job.get("run_id")) is int)
    _require(str(job["run_id"]) == identity["workflowRunId"])
    _require(job.get("head_sha") == identity["sourceSha"])
    _require(job.get("status") == "completed" and job.get("conclusion") == "success")
    if "run_attempt" in job:
        _require(type(job["run_attempt"]) is int and job["run_attempt"] == 1)
    return job


def _verify(identity, config, evidence):
    _identity(identity)
    configured = validate_config(config)
    _require(configured is not None)
    _require(type(evidence) is dict and set(evidence) == EVIDENCE_FIELDS)
    manifest = evidence["manifest"]
    _require(type(manifest) is dict)
    raw = _canonical(manifest)
    _require(len(raw) <= 65536 and hashlib.sha256(raw).hexdigest() == identity["manifestSha256"])
    for field in ("sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest"):
        _require(manifest.get(field) == identity[field])
    origin = manifest.get("origin")
    _require(type(origin) is dict and origin.get("repository") == REPOSITORY)
    _require(origin.get("ref") == "refs/heads/main" and origin.get("workflow") == "CI")
    _require(origin.get("event") == "push" and origin.get("conclusion") == "success")
    _require(origin.get("ciSha") == identity["sourceSha"])
    run = evidence["run"]
    _require(type(run) is dict and type(run.get("id")) is int and str(run["id"]) == identity["workflowRunId"])
    _require(type(run.get("run_attempt")) is int and run["run_attempt"] == 1)
    for field, expected in (("head_sha", identity["sourceSha"]), ("head_branch", "main"),
                            ("event", "workflow_run"), ("path", ".github/workflows/deploy.yml"),
                            ("name", "Deploy Production"), ("status", "in_progress"), ("conclusion", None)):
        _require(field in run and run[field] == expected)
    for field in ("repository", "head_repository"):
        _require(type(run.get(field)) is dict and run[field].get("full_name") == REPOSITORY)
    listing = evidence["jobs"]
    _require(type(listing) is dict and type(listing.get("total_count")) is int)
    jobs = listing.get("jobs")
    _require(type(jobs) is list and 2 <= len(jobs) <= 100 and listing["total_count"] == len(jobs))
    _require(all(type(job) is dict and _positive(job.get("id")) for job in jobs))
    _require(len({job["id"] for job in jobs}) == len(jobs))
    _job(jobs, "Build and Publish", identity)
    gate_job = _job(jobs, configured["gateJobName"], identity)
    environment, branches = evidence["environment"], evidence["branchPolicies"]
    verify_environment(configured, environment, branches)
    approvals = evidence["approvals"]
    _require(type(approvals) is list and 1 <= len(approvals) <= 100)
    accepted = []
    expected_environment = configured["environment"]
    for row in approvals:
        _require(type(row) is dict and type(row.get("environments")) is list)
        matches = []
        for item in row["environments"]:
            _require(type(item) is dict and _positive(item.get("id")) and type(item.get("name")) is str)
            if item["id"] == expected_environment["id"] or item["name"] == expected_environment["name"]:
                _require(item["id"] == expected_environment["id"] and item["name"] == expected_environment["name"])
                matches.append(item)
        if not matches:
            continue
        _require(len(matches) == 1 and row.get("state") == "approved")
        user = row.get("user")
        _require(type(user) is dict and user.get("type") == "User" and _positive(user.get("id")))
        _require(user["id"] in configured["reviewerIds"])
        required = next(rule for rule in environment["protection_rules"] if rule.get("type") == "required_reviewers")
        if required["prevent_self_review"]:
            actors = (run.get("actor"), run.get("triggering_actor"))
            _require(all(type(actor) is dict and _positive(actor.get("id")) for actor in actors))
            _require(all(actor["id"] != user["id"] for actor in actors))
        accepted.append(user["id"])
    _require(bool(accepted))
    return {"approved": True, **dict(identity), "environmentId": expected_environment["id"],
            "reviewerId": min(accepted), "gateJobId": gate_job["id"]}


def verify_approval(identity, config, evidence):
    try:
        return _verify(identity, config, evidence)
    except Exception:
        # Raw API fields, comments, URLs or credentials never become error text.
        raise ValueError("UNTRUSTED_TRANSITION_APPROVAL") from None
