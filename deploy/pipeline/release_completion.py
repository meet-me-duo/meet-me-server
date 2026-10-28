"""Allowlisted operating proof, separate from command/operation success.

No AWS calls, secret values, caller exceptions or arbitrary output enter this
contract. A missing observation is never replaced with an assumed success.
"""

import re


IDENTITY_FIELDS = frozenset(("sourceSha", "buildRunId", "buildRunAttempt", "workflowRunId",
                             "workflowRunAttempt", "releaseId", "imageDigest"))
RULE_NAMES = ("meet-me-production-db-credential-reconcile", "meet-me-production-db-credential-rotated")
ACTIONS = frozenset(("automatic", "preflight", "deploy", "resume"))
MODES = frozenset(("AUTO_READY", "TRANSITION_REQUIRED", "TRANSITION_APPROVED", "MAINTENANCE_RESUME_APPROVED"))
STATES = frozenset(("NOT_ATTEMPTED", "APPLIED", "UNKNOWN"))
EVIDENCE_FIELDS = frozenset(("action", "mode", "deploymentState", "host", "publicContractVerified",
                            "originalRuleStates", "ruleStates", "pendingCommands"))
HOST_FIELDS = frozenset(("sourceSha", "releaseId", "phase", "guardTrusted", "imageMatches", "historyMatches"))
RESULT_FIELDS = frozenset(("protocol", "identity", "action", "mode", "deploymentState", "deploymentComplete",
                          "outcome", "hostVerified", "publicContractVerified", "rulesRestored", "pendingCommands"))
PROTOCOL = "meet-me-deployment-completion-v1"


def _require(condition):
    if not condition:
        raise ValueError("UNTRUSTED_DEPLOYMENT_PROOF")


def _matches(value, pattern):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def validate_identity(identity):
    """Return a bounded, detached identity; callers cannot add logging fields."""
    _require(type(identity) is dict and set(identity) == IDENTITY_FIELDS)
    _require(_matches(identity["sourceSha"], r"[0-9a-f]{40}"))
    for field in ("buildRunId", "buildRunAttempt", "workflowRunId", "workflowRunAttempt"):
        _require(_matches(identity[field], r"[1-9][0-9]{0,19}"))
    _require(_matches(identity["imageDigest"], r"sha256:[0-9a-f]{64}"))
    _require(identity["releaseId"] == "-".join(identity[field] for field in ("sourceSha", "buildRunId", "buildRunAttempt")))
    return dict(identity)


def _rules(value):
    if value is None:
        return None
    _require(type(value) is dict and set(value) == set(RULE_NAMES))
    _require(all(type(state) is str and state in ("ENABLED", "DISABLED") for state in value.values()))
    return dict(value)


def _host_verified(host, identity):
    if host is None:
        return False
    _require(type(host) is dict and set(host) == HOST_FIELDS)
    _require(_matches(host["sourceSha"], r"[0-9a-f]{40}"))
    _require(_matches(host["releaseId"], r"[0-9a-f]{40}-[1-9][0-9]{0,19}-[1-9][0-9]{0,19}"))
    _require(type(host["phase"]) is str and host["phase"] in ("UNINSTALLED", "PRE_V8", "V8_STARTED", "READY"))
    _require(all(type(host[field]) is bool for field in ("guardTrusted", "imageMatches", "historyMatches")))
    return (host["sourceSha"] == identity["sourceSha"] and host["releaseId"] == identity["releaseId"]
            and host["phase"] == "READY" and host["guardTrusted"] and host["imageMatches"] and host["historyMatches"])


def _routing(action, mode, state):
    _require(type(action) is str and action in ACTIONS)
    _require(type(mode) is str and mode in MODES)
    _require(type(state) is str and state in STATES)
    _require(not (state == "APPLIED" and (action == "preflight" or mode == "TRANSITION_REQUIRED")))
    _require(mode != "MAINTENANCE_RESUME_APPROVED" or action == "resume")
    _require(mode != "TRANSITION_APPROVED" or action == "deploy")
    _require(action != "resume" or mode == "MAINTENANCE_RESUME_APPROVED")


def _outcome(action, mode, state, host, public, restored, pending):
    if state == "UNKNOWN":
        return "RECOVERY_REQUIRED"
    if action == "preflight":
        return "PREFLIGHT_ONLY"
    if mode == "TRANSITION_REQUIRED":
        return "TRANSITION_REQUIRED"
    if state == "NOT_ATTEMPTED":
        return "NOT_DEPLOYED"
    if not restored:
        return "RESTORATION_REQUIRED"
    if pending is None or pending:
        return "RECOVERY_REQUIRED"
    return "DEPLOYED_VERIFIED" if host and public else "DEPLOYED_UNVERIFIED"


def evaluate_completion(identity, evidence):
    bound = validate_identity(identity)
    _require(type(evidence) is dict and set(evidence) == EVIDENCE_FIELDS)
    action, mode, state = (evidence[field] for field in ("action", "mode", "deploymentState"))
    _routing(action, mode, state)
    _require(type(evidence["publicContractVerified"]) is bool)
    pending = evidence["pendingCommands"]
    _require(pending is None or (type(pending) is int and 0 <= pending <= 1000000))
    host = _host_verified(evidence["host"], bound)
    original, observed = _rules(evidence["originalRuleStates"]), _rules(evidence["ruleStates"])
    restored = original is not None and observed is not None and original == observed
    public = evidence["publicContractVerified"]
    outcome = _outcome(action, mode, state, host, public, restored, pending)
    return {"protocol": PROTOCOL, "identity": bound, "action": action, "mode": mode, "deploymentState": state,
            "deploymentComplete": outcome == "DEPLOYED_VERIFIED", "outcome": outcome, "hostVerified": host,
            "publicContractVerified": public, "rulesRestored": restored, "pendingCommands": pending}


def require_complete(result, identity):
    expected = validate_identity(identity)
    _require(type(result) is dict and set(result) == RESULT_FIELDS)
    _require(type(result["protocol"]) is str and result["protocol"] == PROTOCOL)
    _require(validate_identity(result["identity"]) == expected)
    _routing(result["action"], result["mode"], result["deploymentState"])
    for field in ("deploymentComplete", "hostVerified", "publicContractVerified", "rulesRestored"):
        _require(type(result[field]) is bool and result[field])
    _require(type(result["pendingCommands"]) is int and result["pendingCommands"] == 0)
    _require(type(result["outcome"]) is str and result["outcome"] == "DEPLOYED_VERIFIED")
    _require(result["deploymentState"] == "APPLIED" and result["action"] != "preflight")
    return dict(result)


def provider_metadata_ready(enabled, metadata):
    _require(type(enabled) is bool and type(metadata) is dict)
    if not enabled:
        return True
    item = metadata.get("/meet-me/production/secret/typesafe-api-key")
    return (type(item) is dict and item.get("Type") == "SecureString"
            and type(item.get("Version")) is int and item["Version"] > 0)
