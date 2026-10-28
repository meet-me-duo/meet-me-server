"""Coordinate one approved release without guessing uncertain external effects.

The adapter binds its concrete AWS operations to the validated release. This module
has no network, shell, credential, rollback or arbitrary-command capability. Journal
writes must be durable and ownership-fenced by the adapter before they return.
"""

import copy
import hashlib
import json
import re


RULE_NAMES = (
    "meet-me-production-db-credential-reconcile",
    "meet-me-production-db-credential-rotated",
)

IDENTITY_FIELDS = {
    "sourceSha", "buildRunId", "buildRunAttempt", "workflowRunId",
    "workflowRunAttempt", "releaseId", "imageDigest",
}
RECORD_FIELDS = {
    "protocol", "operationId", "identity", "action", "sequence", "phase",
    "originalRules", "changedRules", "deploymentState", "rulesState",
    "outcome", "commandId", "reason",
}
PROTOCOL = "meet-me-transition-operation-v1"
PHASES = {
    "SNAPSHOT", "PAUSE_INTENT", "PAUSED", "DEPLOY_INTENT", "SUBMITTED",
    "APPLIED", "RESTORE_INTENT", "COMPLETE", "FAILED", "RECOVERY",
}
OUTCOMES = {
    "APPROVAL_REQUIRED", "APPLIED", "FAILED", "RECOVERY_REQUIRED",
    "RESTORATION_REQUIRED",
}
RULE_STATES = {"UNTOUCHED", "RESTORED", "PAUSED", "PARTIAL", "UNKNOWN"}
REASONS = {
    "APPROVAL_REQUIRED", "IN_PROGRESS", "OWNERSHIP_UNAVAILABLE",
    "PRECONDITION_FAILED", "SUBMISSION_UNCERTAIN", "COMMAND_UNCONFIRMED",
    "JOURNAL_UNAVAILABLE", "HOST_UNVERIFIED", "RESTORATION_UNCONFIRMED",
    "APPLIED", "UNFINISHED_OPERATION",
}


def _require(condition):
    if not condition:
        raise ValueError("UNTRUSTED_TRANSITION_OPERATION")


def _identity(value):
    _require(type(value) is dict and set(value) == IDENTITY_FIELDS)
    _require(type(value["sourceSha"]) is str
             and re.fullmatch(r"[0-9a-f]{40}", value["sourceSha"]))
    for name in ("buildRunId", "buildRunAttempt", "workflowRunId", "workflowRunAttempt"):
        _require(type(value[name]) is str and re.fullmatch(r"[1-9][0-9]{0,19}", value[name]))
    _require(type(value["imageDigest"]) is str
             and re.fullmatch(r"sha256:[0-9a-f]{64}", value["imageDigest"]))
    _require(value["releaseId"] == "-".join(value[name] for name in (
        "sourceSha", "buildRunId", "buildRunAttempt")))
    return copy.deepcopy(value)


def operation_id(identity):
    """A retry or action change cannot disguise the same unresolved operation."""
    identity = _identity(identity)
    raw = json.dumps(identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _rules(value):
    _require(type(value) is dict and set(value) == set(RULE_NAMES))
    _require(all(type(state) is str and state in {"ENABLED", "DISABLED"}
                 for state in value.values()))
    return dict(value)


def _command_id(value):
    return type(value) is str and re.fullmatch(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", value) is not None


def _validate_record(record, identity, op_id):
    _require(type(record) is dict and set(record) == RECORD_FIELDS)
    _require(record["protocol"] == PROTOCOL and record["operationId"] == op_id)
    _require(_identity(record["identity"]) == identity)
    _require(type(record["action"]) is str and record["action"] in {"deploy", "resume"})
    _require(type(record["sequence"]) is int and record["sequence"] > 0)
    _require(type(record["phase"]) is str and record["phase"] in PHASES)
    _rules(record["originalRules"])
    changed = record["changedRules"]
    _require(type(changed) is list and all(type(name) is str for name in changed)
             and len(changed) == len(set(changed))
             and all(name in RULE_NAMES and record["originalRules"][name] == "ENABLED" for name in changed))
    _require(type(record["deploymentState"]) is str
             and record["deploymentState"] in {"NOT_ATTEMPTED", "APPLIED", "UNKNOWN"})
    _require(type(record["rulesState"]) is str and record["rulesState"] in RULE_STATES)
    _require(type(record["outcome"]) is str and record["outcome"] in OUTCOMES)
    _require(type(record["reason"]) is str and record["reason"] in REASONS)
    _require(record["commandId"] is None or _command_id(record["commandId"]))
    _require(record["commandId"] is None or record["deploymentState"] != "NOT_ATTEMPTED")
    _require(record["deploymentState"] != "APPLIED" or _command_id(record["commandId"]))
    _require(record["phase"] != "COMPLETE" or (
        record["deploymentState"] == "APPLIED" and record["rulesState"] == "RESTORED"
        and record["outcome"] == "APPLIED"))
    return copy.deepcopy(record)


def _owned(adapter, record):
    _require(adapter.owns_operation(record["operationId"]) is True)


def _save(adapter, record):
    _owned(adapter, record)
    record["sequence"] += 1
    adapter.save_operation(record["operationId"], copy.deepcopy(record))


def _best_effort_save(adapter, record):
    try:
        _save(adapter, record)
        return True
    except BaseException:
        # Do not reflect an exception string or claim that the prior write failed.
        return False


def _result(record):
    return {name: record[name] for name in (
        "operationId", "deploymentState", "rulesState", "outcome", "commandId", "reason")}


def _observed_rules_state(adapter, record):
    try:
        _owned(adapter, record)
        current = _rules(adapter.read_rules())
        if current == record["originalRules"]:
            return "RESTORED"
        return "PAUSED" if all(state == "DISABLED" for state in current.values()) else "PARTIAL"
    except BaseException:
        return "UNKNOWN"


def _restore(adapter, record):
    """Restore only this operation's potentially changed, originally enabled rules.

    The caller must have proven no deployment was submitted and the old host safe,
    or have verified the successful candidate host. Unknown commands never get here.
    """
    try:
        _owned(adapter, record)
        current = _rules(adapter.read_rules())
        for name in reversed(record["changedRules"]):
            _owned(adapter, record)
            if current[name] == record["originalRules"][name]:
                continue
            record["phase"] = "RESTORE_INTENT"
            _save(adapter, record)
            _owned(adapter, record)
            try:
                adapter.write_rule(name, record["originalRules"][name])
            except BaseException:
                # Read-back can resolve a lost write reply; never blindly repeat it.
                pass
            current = _rules(adapter.read_rules())
        _owned(adapter, record)
        current = _rules(adapter.read_rules())
        record["rulesState"] = "RESTORED" if current == record["originalRules"] else "PARTIAL"
    except BaseException:
        record["rulesState"] = "UNKNOWN"
    return record["rulesState"] == "RESTORED"


def _not_submitted_failure(adapter, record):
    record.update(deploymentState="NOT_ATTEMPTED", outcome="FAILED",
                  phase="FAILED", reason="PRECONDITION_FAILED")
    try:
        _owned(adapter, record)
        safe = adapter.safe_to_restore() is True
    except BaseException:
        safe = False
    if safe and _restore(adapter, record):
        record["phase"] = "FAILED"
        if _best_effort_save(adapter, record):
            try:
                _owned(adapter, record)
                adapter.release_operation(record["operationId"])
            except BaseException:
                record.update(outcome="RECOVERY_REQUIRED", reason="OWNERSHIP_UNAVAILABLE")
    else:
        if not safe:
            record["rulesState"] = _observed_rules_state(adapter, record)
        record.update(outcome="RECOVERY_REQUIRED", reason="RESTORATION_UNCONFIRMED", phase="RECOVERY")
        _best_effort_save(adapter, record)
    return _result(record)


def _resolve_command(adapter, record):
    try:
        _owned(adapter, record)
        reply = adapter.command_result(record["commandId"])
        _require(type(reply) is dict and set(reply) == {"status", "result"})
        _require(type(reply["status"]) is str and reply["status"] == "Success")
        payload = reply["result"]
        _require(type(payload) is dict and payload.get("sourceSha") == record["identity"]["sourceSha"]
                 and payload.get("phase") == "READY")
        _require(adapter.verify_host(payload) is True)
    except BaseException:
        record.update(deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED", phase="RECOVERY",
                      reason="COMMAND_UNCONFIRMED")
        _best_effort_save(adapter, record)
        return _result(record)

    record.update(deploymentState="APPLIED", outcome="RESTORATION_REQUIRED", phase="APPLIED",
                  reason="RESTORATION_UNCONFIRMED")
    if not _best_effort_save(adapter, record):
        record["reason"] = "JOURNAL_UNAVAILABLE"
        return _result(record)
    if not _restore(adapter, record):
        _best_effort_save(adapter, record)
        return _result(record)
    record.update(outcome="APPLIED", phase="COMPLETE", reason="APPLIED")
    if not _best_effort_save(adapter, record):
        record.update(outcome="RESTORATION_REQUIRED", reason="JOURNAL_UNAVAILABLE")
        return _result(record)
    try:
        _owned(adapter, record)
        adapter.release_operation(record["operationId"])
    except BaseException:
        record.update(outcome="RECOVERY_REQUIRED", reason="OWNERSHIP_UNAVAILABLE")
    return _result(record)


def run_transition(operation, adapter):
    """Return a safe operation result; actual release completion is separate.

    ``command_result`` may poll for a bounded interval. A non-success status is
    always uncertain here, even if the remote process reported failure: it may
    have changed the host before failing. Re-entry only queries a known command.
    """
    _require(type(operation) is dict and set(operation) == {"identity", "action", "approvalVerified"})
    identity = _identity(operation["identity"])
    _require(type(operation["action"]) is str and operation["action"] in {"deploy", "resume"})
    _require(type(operation["approvalVerified"]) is bool)
    op_id = operation_id(identity)
    record = {
        "protocol": PROTOCOL, "operationId": op_id, "identity": identity,
        "action": operation["action"], "sequence": 0, "phase": "SNAPSHOT",
        "originalRules": {}, "changedRules": [], "deploymentState": "NOT_ATTEMPTED",
        "rulesState": "UNTOUCHED", "outcome": "APPROVAL_REQUIRED", "commandId": None,
        "reason": "APPROVAL_REQUIRED",
    }
    if not operation["approvalVerified"]:
        return _result(record)
    _require(adapter.claim_operation(op_id, copy.deepcopy(identity)) is True)
    _owned(adapter, record)
    existing = adapter.load_operation(op_id)
    if existing is not None:
        record = _validate_record(existing, identity, op_id)
        if record["commandId"] is not None:
            return _resolve_command(adapter, record)
        if record["deploymentState"] == "UNKNOWN" or record["phase"] in {"DEPLOY_INTENT", "SUBMITTED"}:
            record.update(deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED", phase="RECOVERY",
                          reason="SUBMISSION_UNCERTAIN")
            _best_effort_save(adapter, record)
            return _result(record)
        # A persisted pre-submit operation may be cleaned up, never redeployed.
        return _not_submitted_failure(adapter, record)

    record["originalRules"] = _rules(adapter.read_rules())
    record.update(outcome="RECOVERY_REQUIRED", reason="IN_PROGRESS")
    _save(adapter, record)  # Failure here cannot have changed rules or submitted a deploy.
    try:
        for name in RULE_NAMES:
            if record["originalRules"][name] == "DISABLED":
                continue
            # Persist potential ownership of this change before making the call.
            record["changedRules"].append(name)
            record["phase"] = "PAUSE_INTENT"
            _save(adapter, record)
            _owned(adapter, record)
            adapter.write_rule(name, "DISABLED")
            expected = dict(record["originalRules"])
            expected.update({changed: "DISABLED" for changed in record["changedRules"]})
            _require(_rules(adapter.read_rules()) == expected)
        record.update(phase="PAUSED", rulesState="PAUSED")
        _save(adapter, record)
        _owned(adapter, record)
        pending = adapter.active_commands()
        _require(type(pending) is int and pending == 0)
        _require(adapter.validate_facts() is None)
        _require(_rules(adapter.read_rules()) == {name: "DISABLED" for name in RULE_NAMES})
        record.update(phase="DEPLOY_INTENT", deploymentState="UNKNOWN")
        _save(adapter, record)
        _owned(adapter, record)
    except BaseException:
        return _not_submitted_failure(adapter, record)

    # From this point a reply loss is indistinguishable from an applied command.
    try:
        command_id = adapter.submit_deploy()
        _require(_command_id(command_id))
        record.update(commandId=command_id, phase="SUBMITTED")
        _save(adapter, record)
    except BaseException:
        record.update(deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED", phase="RECOVERY",
                      reason="SUBMISSION_UNCERTAIN")
        _best_effort_save(adapter, record)
        return _result(record)
    return _resolve_command(adapter, record)
