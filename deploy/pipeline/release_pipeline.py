"""Production pipeline orchestration boundary for issue154.

The driver and host guard still own immutable publication and writer authority.
This module coordinates their bounded operations; it never accepts shell input.
"""

import importlib.util
import copy
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import tarfile
import time
import urllib.request
import uuid


BASE = Path(__file__).resolve().parent
MARKER = "MEETME_DEPLOYMENT_RESULT_V1 "


def _require(condition):
    if not condition:
        raise ValueError("UNTRUSTED_PIPELINE_RESULT")


def aws_call(read_fallback, service, operation, *args):
    """Decode the two no-output controls here; keep readonly transport unchanged.

    Successful transport is only an acknowledgement. The owning adapter must
    still validate authority/targets and read back the exact resulting states.
    """
    if service != "events" or operation not in ("enable-rule", "disable-rule"):
        return read_fallback(service, operation, *args)
    try:
        command = ["aws", "--region", os.environ["AWS_REGION"], "--output", "json", service, operation, *args]
        result = subprocess.run(command, capture_output=True, text=True, timeout=25)
        if result.returncode:
            reason = "AWS_DENIED_OR_FAILED"
        elif type(result.stdout) is not str or type(result.stderr) is not str:
            reason = "AWS_UNEXPECTED_OUTPUT"
        elif result.stderr != "":
            reason = "AWS_UNEXPECTED_STDERR"
        elif not result.stdout.strip():
            return {"status": "success", "data": {}}
        else:
            data = json.loads(result.stdout)
            if type(data) is dict and not data:
                return {"status": "success", "data": {}}
            reason = "AWS_UNEXPECTED_OUTPUT"
    except subprocess.TimeoutExpired:
        reason = "AWS_TIMEOUT"
    except json.JSONDecodeError:
        reason = "AWS_INVALID_JSON"
    except (OSError, UnicodeError):
        reason = "AWS_EXECUTION_FAILED"
    except Exception:
        reason = "AWS_UNAVAILABLE"
    print("MEETME_AWS_CALL_FAILURE_V1 " + _canonical({
        "service": "events", "operation": operation, "reason": reason}).decode("utf-8"))
    return {"status": "unavailable", "reason": reason}


def _module(name):
    spec = importlib.util.spec_from_file_location("issue154_" + name, BASE / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def strict_json(raw, maximum=65536):
    _require(type(raw) is bytes and len(raw) <= maximum)

    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result)
            result[key] = value
        return result

    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    except (UnicodeError, json.JSONDecodeError, TypeError):
        raise ValueError("UNTRUSTED_PIPELINE_RESULT") from None


def _file_json(path, maximum=65536):
    path = Path(path)
    _require(path.is_file() and not path.is_symlink())
    with path.open("rb") as stream:
        return strict_json(stream.read(maximum + 1), maximum)


def validate_pipeline_action(driver, event, context, action, approve,
                             identity=None, gate_config=None, gate_evidence=None):
    _require(type(action) is str and action in ("automatic", "preflight", "deploy", "resume"))
    _require(type(approve) is bool)
    source = driver.validate_workflow_event(event, context)
    if context["eventName"] == "workflow_dispatch":
        _require(action != "automatic")
        _require(approve if action in ("deploy", "resume") else not approve)
        return source
    if action == "automatic":
        _require(not approve)
        return source
    _require(action in ("deploy", "resume") and approve)
    fields = set(_module("release_completion").IDENTITY_FIELDS) | {"manifestSha256"}
    _require(type(identity) is dict and set(identity) == fields)
    _require(identity["sourceSha"] == source)
    _require(type(gate_config) is dict and gate_config.get("state") == "CONFIGURED")
    _require(type(gate_evidence) is dict)
    receipt = _module("approval_gate").verify_approval(identity, gate_config, gate_evidence)
    _require(type(receipt) is dict and receipt.get("approved") is True)
    _require(all(receipt.get(field) == identity[field] for field in fields))
    return source


def read_completion_marker(raw, identity):
    _require(type(raw) is bytes and len(raw) <= 65536)
    try:
        text = raw.decode("utf-8")
    except UnicodeError:
        raise ValueError("UNTRUSTED_PIPELINE_RESULT") from None
    pattern = re.compile(r"^(?:[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?Z )?"
                         + re.escape(MARKER) + r"(.*)$")
    entries = [match.group(1) for line in text.splitlines() if (match := pattern.fullmatch(line))]
    _require(len(entries) == 1)
    result = strict_json(entries[0].encode("utf-8"), 8192)
    _module("release_completion").require_complete(result, identity)
    return result


def run_automatic(identity, adapter):
    identity = _module("release_completion").validate_identity(identity)
    op_id = _module("transition_orchestrator").operation_id(identity)
    _require(adapter.claim_operation(op_id, identity) is True)

    def owned():
        _require(adapter.owns_operation(op_id) is True)

    def save(record):
        owned()
        record["sequence"] += 1
        adapter.save_operation(op_id, copy.deepcopy(record))

    def result(record):
        return {"operationId": op_id, "deploymentState": record["deploymentState"],
                "rulesState": record["rulesState"], "outcome": record["outcome"],
                "commandId": record["commandId"]}

    owned()
    record = adapter.load_operation(op_id)
    if record is None:
        rules = adapter.read_rules()
        names = set(_module("release_completion").RULE_NAMES)
        _require(type(rules) is dict and set(rules) == names)
        _require(all(value in ("ENABLED", "DISABLED") for value in rules.values()))
        record = {"protocol": "meet-me-automatic-operation-v1", "identity": identity,
                  "operationId": op_id, "sequence": 0, "deploymentState": "NOT_ATTEMPTED",
                  "commandId": None, "originalRules": dict(rules), "outcome": "NOT_DEPLOYED",
                  "rulesState": "UNTOUCHED"}
        save(record)
        try:
            owned()
            pending = adapter.active_commands()
            _require(type(pending) is int and pending == 0)
            _require(adapter.validate_facts() is None)
            _require(adapter.read_rules() == record["originalRules"])
        except BaseException:
            save(record)
            owned()
            adapter.release_operation(op_id)
            return result(record)
        record["deploymentState"] = "UNKNOWN"
        record["outcome"] = "RECOVERY_REQUIRED"
        save(record)  # A durable intent is required before the one send call.
        try:
            owned()
            command_id = adapter.submit_deploy()
            _require(type(command_id) is str and re.fullmatch(
                r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", command_id))
            record["commandId"] = command_id
            save(record)
        except BaseException:
            try:
                save(record)
            except BaseException:
                pass
            return result(record)
    else:
        fields = {"protocol", "identity", "operationId", "sequence", "deploymentState", "commandId",
                  "originalRules", "outcome", "rulesState"}
        _require(type(record) is dict and set(record) == fields)
        _require(record["protocol"] == "meet-me-automatic-operation-v1"
                 and record["identity"] == identity and record["operationId"] == op_id)
        _require(type(record["sequence"]) is int and record["sequence"] > 0)
        _require(record["deploymentState"] in ("NOT_ATTEMPTED", "UNKNOWN", "APPLIED"))
        _require(record["commandId"] is None or record["deploymentState"] != "NOT_ATTEMPTED")
        _require(record["deploymentState"] != "APPLIED" or record["commandId"] is not None)
        if record["commandId"] is None:
            # An old intent cannot be proved unsent by starting another runner.
            record.update(deploymentState="UNKNOWN", outcome="RECOVERY_REQUIRED")
            save(record)
            return result(record)

    try:
        owned()
        reply = adapter.command_result(record["commandId"])
        _require(type(reply) is dict and set(reply) == {"status", "result"} and reply["status"] == "Success")
        payload = reply["result"]
        _require(type(payload) is dict and payload.get("sourceSha") == identity["sourceSha"]
                 and payload.get("phase") == "READY")
        _require(adapter.verify_host(payload) is True)
        record.update(deploymentState="APPLIED", outcome="APPLIED")
        current = adapter.read_rules()
        record["rulesState"] = "RESTORED" if current == record["originalRules"] else "PARTIAL"
        if record["rulesState"] != "RESTORED":
            record["outcome"] = "RESTORATION_REQUIRED"
        save(record)
        if record["rulesState"] == "RESTORED":
            owned()
            adapter.release_operation(op_id)
    except BaseException:
        if record["deploymentState"] != "APPLIED":
            record["deploymentState"] = "UNKNOWN"
        record["outcome"] = "RECOVERY_REQUIRED"
        try:
            save(record)
        except BaseException:
            pass
    return result(record)


def reconcile_known_operation(identity, operation_id, adapter):
    completion = _module("release_completion")
    current = completion.validate_identity(identity)
    _require(type(operation_id) is str and re.fullmatch(r"[0-9a-f]{64}", operation_id))
    record = adapter.read_operation_for_reconcile(operation_id)
    _require(type(record) is dict and type(record.get("identity")) is dict)
    original = completion.validate_identity(record["identity"])
    _require(_module("transition_orchestrator").operation_id(original) == operation_id == record.get("operationId"))
    candidate = ("sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest")
    _require(all(original[field] == current[field] for field in candidate))
    try:
        if record.get("protocol") == "meet-me-transition-operation-v1":
            _module("transition_orchestrator")._validate_record(record, original, operation_id)
        else:
            fields = {"protocol", "identity", "operationId", "sequence", "deploymentState", "commandId",
                      "originalRules", "outcome", "rulesState"}
            _require(set(record) == fields and record["protocol"] == "meet-me-automatic-operation-v1")
            _require(type(record["sequence"]) is int and record["sequence"] > 0)
            _require(type(record["deploymentState"]) is str and record["deploymentState"] in ("NOT_ATTEMPTED", "UNKNOWN", "APPLIED"))
            _require(type(record["outcome"]) is str and record["outcome"] in
                     ("NOT_DEPLOYED", "APPLIED", "RECOVERY_REQUIRED", "RESTORATION_REQUIRED"))
            _require(type(record["rulesState"]) is str and record["rulesState"] in
                     ("UNTOUCHED", "RESTORED", "PAUSED", "PARTIAL", "UNKNOWN"))
            _require(type(record["originalRules"]) is dict and set(record["originalRules"]) == set(completion.RULE_NAMES))
            _require(all(type(state) is str and state in ("ENABLED", "DISABLED") for state in record["originalRules"].values()))
    except (TypeError, KeyError, AttributeError):
        raise ValueError("UNTRUSTED_RECONCILIATION_RECORD") from None
    command_id = record["commandId"]
    _require(command_id is None or (type(command_id) is str and re.fullmatch(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", command_id)))
    _require(command_id is None or record["deploymentState"] != "NOT_ATTEMPTED")
    _require(record["deploymentState"] != "APPLIED" or command_id is not None)
    status, host = "UNKNOWN", False
    if command_id is not None:
        try:
            reply = adapter.command_result(command_id)
            allowed = {"Success", "Pending", "InProgress", "Delayed", "Failed", "Cancelled", "TimedOut", "Cancelling"}
            _require(type(reply) is dict and set(reply) == {"status", "result"}
                     and type(reply["status"]) is str and reply["status"] in allowed)
            status = reply["status"]
            payload = reply["result"]
            if (status == "Success" and type(payload) is dict and payload.get("sourceSha") == original["sourceSha"]
                    and payload.get("phase") == "READY"):
                host = adapter.verify_host(payload) is True
        except BaseException:
            host = False
    try:
        rules = adapter.read_rules()
        _require(type(rules) is dict and set(rules) == set(completion.RULE_NAMES)
                 and all(type(state) is str and state in ("ENABLED", "DISABLED") for state in rules.values()))
    except BaseException:
        rules = None
    try:
        pending = adapter.active_commands()
        _require(type(pending) is int and 0 <= pending <= 1000000)
    except BaseException:
        pending = None
    return {"protocol": "meet-me-release-reconciliation-v1", "identity": original, "operationId": operation_id,
            "commandStatus": status, "hostVerified": host, "rulesObserved": rules, "pendingCommands": pending,
            "deploymentComplete": False, "outcome": "RECOVERY_REQUIRED"}


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


class RecoverySafetyError(ValueError):
    """Carry only the sanitized prior-state observation, never an AWS exception."""

    def __init__(self, report):
        super().__init__("UNRESOLVED_PRIOR_OPERATION")
        self.report = copy.deepcopy(report)


def _recovery_journal(record, op_id):
    """Validate the durable wire record before projecting any of its fields."""
    _require(type(record) is dict)
    identity = _module("release_completion").validate_identity(record.get("identity"))
    core = _module("transition_orchestrator")
    _require(record.get("operationId") == op_id == core.operation_id(identity))
    if record.get("protocol") == core.PROTOCOL:
        return core._validate_record(record, identity, op_id)
    fields = {"protocol", "identity", "operationId", "sequence", "deploymentState", "commandId",
              "originalRules", "outcome", "rulesState"}
    _require(set(record) == fields and record["protocol"] == "meet-me-automatic-operation-v1")
    _require(type(record["sequence"]) is int and record["sequence"] > 0)
    _require(type(record["deploymentState"]) is str and record["deploymentState"] in {"NOT_ATTEMPTED", "UNKNOWN", "APPLIED"})
    _require(type(record["outcome"]) is str and record["outcome"] in
             {"NOT_DEPLOYED", "APPLIED", "RECOVERY_REQUIRED", "RESTORATION_REQUIRED"})
    _require(type(record["rulesState"]) is str and record["rulesState"] in core.RULE_STATES)
    core._rules(record["originalRules"])
    command = record["commandId"]
    _require(command is None or core._command_id(command))
    _require(command is None or record["deploymentState"] != "NOT_ATTEMPTED")
    _require(record["deploymentState"] != "APPLIED" or command is not None)
    return copy.deepcopy(record)


def _inspection_report(op_id):
    return {"protocol": "meet-me-recovery-inspection-v1", "operationId": op_id,
            "journal": {"status": "UNAVAILABLE", **{key: None for key in (
                "protocol", "sequence", "phase", "deploymentState", "rulesState", "outcome", "reason", "commandIdPresent")}},
            "owner": {"status": "MISSING", "released": None, "identityMatches": None},
            "safeToProceed": False, "reason": "UNFINISHED_OPERATION"}


def _applied_prior_host_matches(record, adapter, facts):
    """Use only the host facts already read by release preparation; no new probe."""
    original = record["identity"]
    image = adapter.release["manifest"]["imageRepository"] + "@" + original["imageDigest"]
    contract = facts.get("automaticContract")
    history = facts.get("history")
    if not (facts.get("phase") == "READY" and facts.get("guardTrusted") is True
            and facts.get("currentImage") == image and type(contract) is dict
            and contract.get("protocol") == "meet-me-automatic-release-v1"
            and contract.get("currentImage") == image and type(history) is list and history
            and contract.get("history") == history
            and all(contract.get(key) == original[key] for key in ("sourceSha", "buildRunId", "buildRunAttempt"))):
        return False
    return all(type(row) is dict and set(row) == {"version", "script", "checksum", "success"}
               and row["success"] is True and type(row["version"]) is str
               and re.fullmatch(r"[0-9.]{1,32}", row["version"])
               and type(row["script"]) is str and re.fullmatch(r"V[0-9._]+__[A-Za-z0-9_]+\.sql", row["script"])
               and type(row["checksum"]) is int for row in history)


def _inspect_recovery(adapter, requested=None):
    core = _module("transition_orchestrator")
    selected = requested if requested is not None else core.operation_id(adapter.identity)
    report = _inspection_report(selected)
    try:
        first, etag = adapter.read_owner_for_inspection()
    except Exception:
        report["owner"]["status"], report["reason"] = "UNAVAILABLE", "OWNER_UNAVAILABLE"
        return report

    if first is None and requested is None:
        try:
            empty = adapter.inspection_namespace_empty()
            _require(type(empty) is bool)
            last, last_etag = adapter.read_owner_for_inspection()
            if last is not None or last_etag != etag:
                report["reason"] = "SNAPSHOT_CHANGED"
            elif empty:
                report["journal"]["status"] = "MISSING"
                report.update(safeToProceed=True, reason="FRESH_NAMESPACE")
            else:
                report["reason"] = "NONEMPTY_NAMESPACE"
        except Exception:
            report["reason"] = "OWNER_UNAVAILABLE"
        return report

    if first is not None:
        report["owner"].update(status="PRESENT", released=first["released"])
        if requested is None:
            selected = first["operationId"]
            report["operationId"] = selected

    record = None
    try:
        raw = adapter.read_operation_for_reconcile(selected)
        if raw is None:
            report["journal"]["status"] = "MISSING"
            report["reason"] = "JOURNAL_MISSING"
        else:
            record = _recovery_journal(raw, selected)
            report["journal"] = {"status": "VALID", **{key: record.get(key) for key in (
                "protocol", "sequence", "phase", "deploymentState", "rulesState", "outcome", "reason")},
                "commandIdPresent": record["commandId"] is not None}
    except Exception:
        report["journal"]["status"], report["reason"] = "UNAVAILABLE", "JOURNAL_UNAVAILABLE"

    if record is not None:
        matches = (first is not None and first["operationId"] == selected and first["identity"] == record["identity"])
        report["owner"]["identityMatches"] = matches if first is not None else None
        if first is None:
            report["reason"] = "OWNER_MISSING"
        elif not matches:
            report["reason"] = "IDENTITY_MISMATCH"
        elif not first["released"]:
            report["reason"] = "OWNER_ACTIVE"
        else:
            try:
                if requested is not None:
                    candidate = _module("release_completion").validate_identity(adapter.identity)
                    _require(all(candidate[key] == record["identity"][key] for key in (
                        "sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest")))
            except Exception:
                report["reason"] = "IDENTITY_MISMATCH"
            else:
                transition = record["protocol"] == core.PROTOCOL
                state = record["deploymentState"]
                terminal = (state == "NOT_ATTEMPTED" and record["commandId"] is None
                            and record["rulesState"] in {"RESTORED", "UNTOUCHED"}
                            and ((transition and record["phase"] == "FAILED" and record["outcome"] == "FAILED")
                                 or (not transition and record["outcome"] == "NOT_DEPLOYED")))
                applied = (state == "APPLIED" and record["commandId"] is not None
                           and record["rulesState"] == "RESTORED" and record["outcome"] == "APPLIED"
                           and (not transition or record["phase"] == "COMPLETE"))
                if not terminal and not applied:
                    report["reason"] = "UNFINISHED_OPERATION"
                else:
                    try:
                        facts = adapter.inspection_facts()
                        _require(type(facts) is dict and facts.get("ruleStates") == record["originalRules"])
                    except Exception:
                        report["reason"] = "RULES_UNVERIFIED"
                    else:
                        if applied and not _applied_prior_host_matches(record, adapter, facts):
                            report["reason"] = "HOST_UNVERIFIED"
                        else:
                            report.update(safeToProceed=True, reason="SAFE_TERMINAL")
    try:
        last, last_etag = adapter.read_owner_for_inspection()
        if last != first or last_etag != etag:
            report.update(safeToProceed=False, reason="SNAPSHOT_CHANGED")
            report["owner"] = {"status": "UNAVAILABLE", "released": None, "identityMatches": None}
    except Exception:
        report.update(safeToProceed=False, reason="SNAPSHOT_CHANGED")
        report["owner"] = {"status": "UNAVAILABLE", "released": None, "identityMatches": None}
    return report


def inspect_recovery_state(operation_id, adapter):
    """Project a requested operation without claiming, releasing or polling it."""
    _require(type(operation_id) is str and re.fullmatch(r"[0-9a-f]{64}", operation_id))
    return _inspect_recovery(adapter, operation_id)


def _emit_inspection(report):
    data = _canonical(report)
    _require(len(data) <= 8192)
    Path("recovery-inspection.json").write_bytes(data + b"\n")
    print("MEETME_RECOVERY_INSPECTION_V1 " + data.decode("utf-8"))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("### Read-only prior operation inspection\n\n")
            stream.write("- Prior state safe to proceed: **" + str(report["safeToProceed"]).lower() + "**\n")
            stream.write("- Inspection reason: **" + report["reason"] + "**\n")
            stream.write("- This is not deployment completion and does not release ownership.\n\n")


def require_recovery_safe(adapter):
    report = _inspect_recovery(adapter)
    _emit_inspection(report)
    if report["safeToProceed"] is not True:
        raise RecoverySafetyError(report)
    return report


def _github(driver, suffix):
    _require(type(suffix) is str and re.fullmatch(r"[A-Za-z0-9_./?=&-]+", suffix))
    request = urllib.request.Request("https://api.github.com/repos/" + driver.REPOSITORY + "/" + suffix,
                                     headers={"Accept": "application/vnd.github+json", "User-Agent": "meet-me-release-pipeline"})
    with urllib.request.build_opener(driver._NoRedirect).open(request, timeout=10) as response:
        return strict_json(response.read(262145), 262144)


def _context(env):
    return {"repository": env["GITHUB_REPOSITORY"], "ref": env["GITHUB_REF"],
            "eventName": env["GITHUB_EVENT_NAME"], "sourceSha": env["SOURCE_SHA"]}


@contextmanager
def prepare_release(driver, env, observe=True):
    """Read the exact published package and current host; never pause or deploy."""
    event = _file_json(env["GITHUB_EVENT_PATH"], 262144)
    context = _context(env)
    requested, approve = env["RELEASE_ACTION"], env.get("APPROVE_FIRST_TRANSITION", "false")
    action = "preflight" if requested == "reconcile" else requested
    if requested == "reconcile":
        _require(context["eventName"] == "workflow_dispatch")
        approve = "false"
    _require(approve in ("true", "false"))
    validate_pipeline_action(driver, event, context, action, approve == "true")
    instance, region, bucket = (env[name] for name in ("RUNTIME_INSTANCE_ID", "AWS_REGION", "DEPLOYMENT_ARTIFACT_BUCKET"))
    _require(re.fullmatch(r"i-[0-9a-f]{8,17}", instance) and re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-[0-9]", region))
    _require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket))
    expected = {"buildRunId": env["BUILD_RUN_ID"], "buildRunAttempt": env["BUILD_RUN_ATTEMPT"],
                "imageRepository": env["ECR_REPOSITORY_URL"]}
    _require(all(re.fullmatch(r"[1-9][0-9]{0,19}", expected[name]) for name in ("buildRunId", "buildRunAttempt")))
    prefix = "releases/builds/" + expected["buildRunId"] + "/" + expected["buildRunAttempt"] + "/"
    policy = driver._load("pipeline_approved_policy", driver.BASE / "pipeline/approved_release.py")
    with tempfile.TemporaryDirectory(prefix="meet-me-pipeline-") as temporary:
        directory = Path(temporary)
        driver._download(bucket, prefix + "manifest.json", directory / "manifest.json", 65536)
        manifest = _file_json(directory / "manifest.json")
        validated = policy.validate_manifest(manifest, expected)
        _require(".ecr." + region + "." in manifest["imageRepository"])
        driver._verify_origin(manifest, action)
        if context["eventName"] == "workflow_run":
            _require(manifest["sourceSha"] == context["sourceSha"]
                     and str(event["workflow_run"]["id"]) == manifest["origin"]["ciRunId"])
            tip = _github(driver, "branches/main")
            _require(type(tip) is dict and tip.get("commit", {}).get("sha") == manifest["sourceSha"])
        archive = directory / "release.tar.gz"
        driver._download(bucket, prefix + "release.tar.gz", archive, driver.MAX_ARCHIVE)
        _require(hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["archiveSha256"])
        auth = driver._verify_package(archive, manifest)
        with tarfile.open(archive, "r:gz") as package:
            public_contract = (validate_public_contract(strict_json(package.extractfile("public-api-contract.json").read(131073), 131072))
                               if "public-api-contract.json" in package.getnames() else None)
        facts, parameter_ready = (driver._collect_facts(instance, region, manifest, auth_required=auth,
                                                         maintenance_resume=action == "resume") if observe else (None, False))
        identity = {key: manifest[key] for key in ("sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest")}
        identity.update(workflowRunId=env["GITHUB_RUN_ID"], workflowRunAttempt=env["GITHUB_RUN_ATTEMPT"])
        identity = _module("release_completion").validate_identity(identity)
        mode = (policy.validate_deploy_facts(validated, facts, "automatic" if action == "automatic" else "preflight")["mode"]
                if observe else "TRANSITION_REQUIRED")
        yield {"driver": driver, "env": env, "directory": directory, "event": event, "context": context,
               "action": action, "requestedAction": requested, "approve": approve == "true", "instance": instance, "region": region,
               "bucket": bucket, "prefix": prefix, "manifest": manifest, "validated": validated,
               "identity": identity, "facts": facts, "parameterReady": parameter_ready,
               "auth": auth, "mode": mode, "policy": policy, "publicContract": public_contract}


def _rule_map(states):
    _require(type(states) is list and len(states) == 2 and all(s in ("ENABLED", "DISABLED") for s in states))
    return {"meet-me-production-db-credential-rotated": states[0],
            "meet-me-production-db-credential-reconcile": states[1]}


def _gate_config():
    path = BASE.parent / "transition-approval.json"
    _require(path.is_file() and not path.is_symlink())
    return _file_json(path)


def _gate_environment(driver, config):
    checked = _module("approval_gate").validate_config(config)
    _require(checked is not None)
    name = checked["environment"]["name"]
    _require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", name))
    environment = _github(driver, "environments/" + name)
    policies = _github(driver, "environments/" + name + "/deployment-branch-policies?per_page=100")
    _require(_module("approval_gate").verify_environment(config, environment, policies) is True)
    return name, environment, policies


def _gate_evidence(release, config):
    driver, identity = release["driver"], release["identity"]
    name, environment, policies = _gate_environment(driver, config)
    run_id, attempt = identity["workflowRunId"], identity["workflowRunAttempt"]
    return {"manifest": release["manifest"], "run": _github(driver, "actions/runs/" + run_id + "/attempts/" + attempt),
            "jobs": _github(driver, "actions/runs/" + run_id + "/attempts/" + attempt + "/jobs?per_page=100"),
            "approvals": _github(driver, "actions/runs/" + run_id + "/approvals"),
            "environment": environment, "branchPolicies": policies}


class AwsReleaseAdapter:
    """Exact-target adapter; journal uses conditional writes, never DeleteObject."""

    def __init__(self, release, action, approved=False, command_wait_seconds=1560):
        self.release = release
        self.driver = release["driver"]
        self.action, self.approved = action, approved
        _require(type(command_wait_seconds) is int and 0 < command_wait_seconds <= 1560)
        self.command_wait_seconds = command_wait_seconds
        self.holder = uuid.uuid4().hex
        self.identity = release["identity"]
        self.root_key = "releases/operations/" + hashlib.sha256(release["instance"].encode("ascii")).hexdigest() + "/"
        self.owner_key = self.root_key + "owner.json"
        self.host_proof = None
        self.original_rules = None
        self.before = ({key: release["facts"][key] for key in ("history", "phase", "currentImage", "guardTrusted", "automaticContract")}
                       if release["facts"] is not None else {})

    def _read_object(self, key):
        aws, bucket = self.driver._aws, self.release["bucket"]
        listing = aws("s3api", "list-objects-v2", "--bucket", bucket, "--prefix", key, "--max-keys", "2")
        _require(listing.get("IsTruncated") is False)
        objects = listing.get("Contents", [])
        _require(type(objects) is list and len(objects) <= 1)
        if not objects:
            return None, None
        _require(objects[0].get("Key") == key)
        target = self.release["directory"] / (uuid.uuid4().hex + ".json")
        metadata = aws("s3api", "get-object", "--bucket", bucket, "--key", key,
                       "--range", "bytes=0-131072", str(target))
        _require(type(metadata) is dict)
        _require(type(metadata.get("ETag")) is str and re.fullmatch(r'"[A-Za-z0-9-]+"', metadata["ETag"]))
        _require(target.is_file() and not target.is_symlink())
        size = target.stat().st_size
        _require(type(metadata.get("ContentLength")) is int and 0 < size <= 131072
                 and metadata["ContentLength"] == size)
        if "ContentRange" in metadata:
            content_range = metadata["ContentRange"]
            _require(type(content_range) is str)
            match = re.fullmatch(r"bytes 0-([0-9]+)/([0-9]+)", content_range)
            _require(match is not None and int(match[1]) + 1 == int(match[2]) == size)
        return _file_json(target, 131072), metadata["ETag"]

    def _write_object(self, key, value, etag):
        data = _canonical(value)
        _require(len(data) <= 131072)
        target = self.release["directory"] / (uuid.uuid4().hex + ".json")
        target.write_bytes(data)
        args = ("--if-none-match", "*") if etag is None else ("--if-match", etag)
        self.driver._aws("s3api", "put-object", "--bucket", self.release["bucket"], "--key", key,
                         "--body", str(target), "--server-side-encryption", "AES256", *args)

    def _owner(self):
        value, etag = self._read_object(self.owner_key)
        if value is not None:
            _require(type(value) is dict and set(value) == {"protocol", "operationId", "identity", "holder", "released"})
            _require(value["protocol"] == "meet-me-release-owner-v1" and type(value["released"]) is bool)
            _module("release_completion").validate_identity(value["identity"])
            _require(type(value["holder"]) is str and re.fullmatch(r"[0-9a-f]{32}", value["holder"]))
            _require(value["operationId"] == _module("transition_orchestrator").operation_id(value["identity"]))
        return value, etag

    def read_owner_for_inspection(self):
        return self._owner()

    def inspection_namespace_empty(self):
        listing = self.driver._aws("s3api", "list-objects-v2", "--bucket", self.release["bucket"],
                                   "--prefix", self.root_key, "--max-keys", "1")
        _require(type(listing) is dict and type(listing.get("IsTruncated")) is bool)
        objects = listing.get("Contents", [])
        _require(type(objects) is list and len(objects) <= 1)
        _require(type(listing.get("KeyCount")) is int and listing["KeyCount"] == len(objects))
        _require(all(type(item) is dict and type(item.get("Key")) is str
                     and item["Key"].startswith(self.root_key) for item in objects))
        return listing["IsTruncated"] is False and not objects

    def inspection_facts(self):
        return dict(copy.deepcopy(self.before), ruleStates=self.read_rules())

    def claim_operation(self, op_id, identity):
        _require(identity == self.identity and op_id == _module("transition_orchestrator").operation_id(identity))
        owner, etag = self._owner()
        if owner is not None and not owner["released"]:
            return owner["operationId"] == op_id and owner["holder"] == self.holder
        self._write_object(self.owner_key, {"protocol": "meet-me-release-owner-v1", "operationId": op_id,
                           "identity": identity, "holder": self.holder, "released": False}, etag)
        return self.owns_operation(op_id)

    def owns_operation(self, op_id):
        owner, _ = self._owner()
        return (owner is not None and not owner["released"] and owner["operationId"] == op_id
                and owner["identity"] == self.identity and owner["holder"] == self.holder)

    def load_operation(self, op_id):
        _require(self.owns_operation(op_id))
        value, _ = self._read_object(self.root_key + op_id + ".json")
        if value is not None:
            _require(type(value) is dict and value.get("identity") == self.identity and value.get("operationId") == op_id)
            _require(value.get("protocol") in ("meet-me-automatic-operation-v1", "meet-me-transition-operation-v1"))
        if type(value) is dict and type(value.get("originalRules")) is dict:
            self.original_rules = dict(value["originalRules"])
        return value

    def read_operation_for_reconcile(self, op_id):
        _require(type(op_id) is str and re.fullmatch(r"[0-9a-f]{64}", op_id))
        value, _ = self._read_object(self.root_key + op_id + ".json")
        return value

    def save_operation(self, op_id, record):
        _require(self.owns_operation(op_id))
        _require(type(record) is dict and record.get("identity") == self.identity and record.get("operationId") == op_id)
        if type(record.get("originalRules")) is dict:
            self.original_rules = dict(record["originalRules"])
        prior, etag = self._read_object(self.root_key + op_id + ".json")
        _require(type(record.get("sequence")) is int and record["sequence"] > 0)
        if prior is not None:
            _require(prior.get("identity") == self.identity and prior.get("operationId") == op_id)
            _require(type(prior.get("sequence")) is int and record["sequence"] > prior["sequence"])
            _require(not (prior.get("deploymentState") in ("UNKNOWN", "APPLIED")
                          and record.get("deploymentState") == "NOT_ATTEMPTED"))
            _require(prior.get("originalRules") == record.get("originalRules"))
        _require(self.owns_operation(op_id))
        self._write_object(self.root_key + op_id + ".json", record, etag)

    def release_operation(self, op_id):
        owner, etag = self._owner()
        _require(owner is not None and owner["operationId"] == op_id and owner["holder"] == self.holder)
        owner["released"] = True
        self._write_object(self.owner_key, owner, etag)
        observed, _ = self._owner()
        _require(observed == owner)

    def read_rules(self):
        aws, r = self.driver._aws, self.release
        account = r["manifest"]["imageRepository"].split(".", 1)[0]
        partition = "aws-cn" if ".amazonaws.com.cn/" in r["manifest"]["imageRepository"] else "aws"
        result = {}
        for suffix, target_id in (("rotated", "RefreshDatabaseCredential"), ("reconcile", "ReconcileDatabaseCredential")):
            name = "meet-me-production-db-credential-" + suffix
            rule = aws("events", "describe-rule", "--name", name)
            _require(rule.get("Name") == name and rule.get("State") in ("ENABLED", "DISABLED"))
            targets = aws("events", "list-targets-by-rule", "--rule", name)
            _require(not targets.get("NextToken") and type(targets.get("Targets")) is list and len(targets["Targets"]) == 1)
            target = targets["Targets"][0]
            _require(target.get("Id") == target_id
                     and target.get("Arn") == f"arn:{partition}:ssm:{r['region']}::document/AWS-RunShellScript"
                     and target.get("RoleArn") == f"arn:{partition}:iam::{account}:role/meet-me-production-db-credential-refresh"
                     and target.get("RunCommandParameters", {}).get("RunCommandTargets") == [{"Key": "InstanceIds", "Values": [r["instance"]]}])
            result[name] = rule["State"]
        return result

    def write_rule(self, name, state):
        _require(name in _module("release_completion").RULE_NAMES and state in ("ENABLED", "DISABLED"))
        op_id = _module("transition_orchestrator").operation_id(self.identity)
        _require(self.owns_operation(op_id) and self.approved is True and self.action in ("deploy", "resume"))
        self.read_rules()  # Revalidate target binding before exact-rule mutation.
        if state == "ENABLED":
            safe = (self.verify_host({"sourceSha": self.identity["sourceSha"], "phase": "READY"})
                    if self.host_proof is not None else self.safe_to_restore())
            _require(safe is True)
        self.driver._aws("events", "enable-rule" if state == "ENABLED" else "disable-rule", "--name", name)

    def active_commands(self):
        probe = self.driver._load("pipeline_active_commands", self.driver.BASE / "preflight/run-preflight.py")
        for index in range(4):
            result = probe.active_commands(self.release["instance"])
            _require(result.get("status") == "success" and type(result.get("data")) is list)
            if not result["data"]:
                return 0
            if index != 3:
                time.sleep(2)
        return len(result["data"])

    def _facts(self, require_healthy=False):
        r = self.release
        options = {"auth_required": r["auth"], "maintenance_resume": self.action == "resume"}
        if require_healthy:
            options["require_healthy"] = True
        return self.driver._collect_facts(r["instance"], r["region"], r["manifest"], **options)

    def validate_facts(self):
        r = self.release
        fresh, ready = self._facts()
        _require(ready and self.provider_ready())
        plan = r["policy"].validate_deploy_facts(r["validated"], fresh, self.action, self.approved)
        _require(plan["mode"] == ("AUTO_READY" if self.action == "automatic" else
                 "MAINTENANCE_RESUME_APPROVED" if self.action == "resume" else "TRANSITION_APPROVED"))
        self.attestation = {"action": self.action, "approveFirstTransition": self.approved,
                            "refreshRuleStates": fresh["refreshRuleStates"], "pendingCommands": fresh["pendingCommands"],
                            "manifestSha256": hashlib.sha256(_canonical(r["manifest"])).hexdigest()}
        self.before = {key: fresh[key] for key in self.before}

    def provider_ready(self):
        name = "/meet-me/production/config/precheck-provider-enabled"
        response = self.driver._aws("ssm", "describe-parameters", "--parameter-filters", "Key=Name,Option=Equals,Values=" + name)
        _require(not response.get("NextToken") and type(response.get("Parameters")) is list)
        entries = response["Parameters"]
        if not entries:
            return True  # The managed renderer defaults an absent switch to OFF.
        _require(len(entries) == 1 and type(entries[0]) is dict and entries[0].get("Name") == name
                 and entries[0].get("Type") == "String" and type(entries[0].get("Version")) is int
                 and entries[0]["Version"] > 0)
        parameter = self.driver._aws("ssm", "get-parameter", "--name", name).get("Parameter")
        _require(type(parameter) is dict and parameter.get("Name") == name and parameter.get("Type") == "String"
                 and parameter.get("Version") == entries[0]["Version"] and type(parameter.get("Version")) is int)
        _require(parameter.get("Value") in ("true", "false"))
        if parameter["Value"] == "false":
            return True
        key = "/meet-me/production/secret/typesafe-api-key"
        metadata = self.driver._aws("ssm", "describe-parameters", "--parameter-filters", "Key=Name,Option=Equals,Values=" + key)
        _require(not metadata.get("NextToken") and type(metadata.get("Parameters")) is list)
        rows = metadata["Parameters"]
        _require(len(rows) <= 1 and all(type(row) is dict and row.get("Name") == key for row in rows))
        projected = {key: {field: rows[0].get(field) for field in ("Type", "Version")}} if rows else {}
        return _module("release_completion").provider_metadata_ready(True, projected)

    def submit_deploy(self):
        r = self.release
        op_id = _module("transition_orchestrator").operation_id(self.identity)
        _require(self.owns_operation(op_id))
        journal = self.load_operation(op_id)
        _require(type(journal) is dict and journal.get("deploymentState") == "UNKNOWN"
                 and journal.get("commandId") is None)
        _require(journal.get("protocol") == ("meet-me-automatic-operation-v1" if self.action == "automatic"
                 else "meet-me-transition-operation-v1"))
        if self.action != "automatic":
            _require(journal.get("phase") == "DEPLOY_INTENT")
        _require(type(getattr(self, "attestation", None)) is dict)
        self.driver._verify_origin(r["manifest"], self.action,
                                   approved_pipeline=self.approved and r["context"]["eventName"] == "workflow_run")
        command = self.driver._deploy_command(r["bucket"], r["prefix"] + "release.tar.gz", r["region"],
                                              r["manifest"], self.attestation)
        # This immutable send claim also fences accidental direct adapter reentry.
        send_key = self.root_key + op_id + ".send.json"
        existing, _ = self._read_object(send_key)
        _require(existing is None and self.owns_operation(op_id))
        self._write_object(send_key, {"protocol": "meet-me-release-send-v1", "operationId": op_id,
                           "identity": self.identity, "holder": self.holder}, None)
        _require(self.owns_operation(op_id))
        sent = self.driver._aws("ssm", "send-command", "--instance-ids", r["instance"], "--document-name", "AWS-RunShellScript",
                                "--comment", "Meet-me immutable release " + self.identity["workflowRunId"],
                                "--timeout-seconds", "120", "--parameters", json.dumps({"commands": [command], "executionTimeout": ["1500"]}))
        return sent["Command"]["CommandId"]

    def command_result(self, command_id):
        _require(type(command_id) is str and re.fullmatch(r"[0-9a-f-]{36}", command_id))
        deadline = time.monotonic() + self.command_wait_seconds
        while time.monotonic() < deadline:
            result = self.driver.aws_call("ssm", "get-command-invocation", "--command-id", command_id,
                                           "--instance-id", self.release["instance"])
            if result.get("status") == "success":
                data = result["data"]
                status = data.get("Status")
                if status not in ("Pending", "InProgress", "Delayed"):
                    if status == "Success":
                        payload = self.driver._deploy_result(status, data.get("StandardOutputContent", ""), data.get("StandardErrorContent", ""))
                        _require(payload["currentImage"] == self.release["validated"]["imageRef"])
                        return {"status": status, "result": {"sourceSha": payload["sourceSha"], "phase": payload["phase"]}}
                    return {"status": status, "result": None}
            time.sleep(2)
        return {"status": "Unknown", "result": None}

    def verify_host(self, payload):
        r = self.release
        self.host_proof = None
        _require(payload.get("sourceSha") == self.identity["sourceSha"] and payload.get("phase") == "READY")
        # A prior command's successful stdout is not evidence of the current host.
        facts, ready = self.driver._collect_facts(r["instance"], r["region"], r["manifest"],
                                                auth_required=r["auth"], require_healthy=True)
        expected = [dict(row, success=True) for row in r["manifest"]["migrations"]]
        record = facts["automaticContract"]
        valid = (ready is True and facts["phase"] == "READY" and facts["guardTrusted"] is True
                 and facts["currentImage"] == r["validated"]["imageRef"] and facts["history"] == expected
                 and type(record) is dict and record.get("sourceSha") == self.identity["sourceSha"]
                 and record.get("buildRunId") == self.identity["buildRunId"]
                 and record.get("buildRunAttempt") == self.identity["buildRunAttempt"]
                 and record.get("guardFingerprint") == r["manifest"]["guardFingerprint"]
                 and record.get("schemaFingerprint") == r["manifest"]["schemaFingerprint"])
        if valid:
            self.host_proof = {"sourceSha": self.identity["sourceSha"], "releaseId": self.identity["releaseId"],
                               "phase": "READY", "guardTrusted": True, "imageMatches": True, "historyMatches": True}
        return valid

    def safe_to_restore(self):
        if self.before.get("phase") != "READY" or self.before.get("guardTrusted") is not True:
            return False
        facts, ready = self._facts(require_healthy=True)
        return ready is True and self.active_commands() == 0 and all(facts[key] == value for key, value in self.before.items())


def project_public_api(spec):
    _require(type(spec) is dict and type(spec.get("paths")) is dict)
    components = spec.get("components", {})
    _require(type(components) is dict)
    schemas = components.get("schemas", {})
    _require(type(schemas) is dict)
    operations = []
    methods = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
    for path, entries in spec["paths"].items():
        _require(type(entries) is dict)
        for method, operation in entries.items():
            if method not in methods:
                continue
            _require(type(operation) is dict)
            operations.append({"path": path, "method": method, "operationId": operation.get("operationId")})
    properties = {}
    for name, schema in schemas.items():
        _require(type(schema) is dict)
        fields = schema.get("properties", {})
        _require(type(fields) is dict)
        _require(all(type(field) is str for field in fields))
        properties[name] = sorted(fields)
    operations.sort(key=lambda item: (str(item["path"]), item["method"], str(item["operationId"])))
    return validate_public_contract({"protocol": "meet-me-public-api-contract-v1", "operations": operations,
                                     "schemaProperties": properties})


def validate_public_contract(contract):
    _require(type(contract) is dict and set(contract) == {"protocol", "operations", "schemaProperties"})
    _require(contract["protocol"] == "meet-me-public-api-contract-v1")

    def text(value, maximum):
        return (type(value) is str and 0 < len(value) <= maximum
                and value.strip() == value and re.search(r"[\x00-\x1f\x7f]", value) is None)

    operations, schemas = contract["operations"], contract["schemaProperties"]
    _require(type(operations) is list and 0 < len(operations) <= 4096)
    _require(type(schemas) is dict and len(schemas) <= 4096)
    keys, ids = [], set()
    for row in operations:
        _require(type(row) is dict and set(row) == {"path", "method", "operationId"})
        _require(text(row["path"], 1024) and row["path"].startswith("/") and not row["path"].startswith("//"))
        _require(type(row["method"]) is str and row["method"] in {"get", "post", "put", "patch", "delete", "head", "options", "trace"})
        _require(text(row["operationId"], 256) and row["operationId"] not in ids)
        ids.add(row["operationId"])
        keys.append((row["path"], row["method"]))
    _require(len(keys) == len(set(keys)) and keys == sorted(keys))
    for name, properties in schemas.items():
        _require(text(name, 256) and type(properties) is list and len(properties) <= 4096)
        _require(all(text(prop, 256) for prop in properties))
        _require(properties == sorted(set(properties)))
    _require(len(_canonical(contract)) <= 131072)
    return copy.deepcopy(contract)


def verify_public_contract(expected):
    """Bounded edge/API reads; app health belongs to the separate host proof."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            raise ValueError("UNTRUSTED_PUBLIC_CONTRACT")

    expected = validate_public_contract(expected)
    for attempt in range(3):
        try:
            opener = urllib.request.build_opener(NoRedirect)
            with opener.open("https://api.meet-me.co.kr/healthz", timeout=10) as response:
                _require(response.status == 200)
                _require(response.read(8193) == b"ok\n")
            with opener.open("https://api.meet-me.co.kr/v3/api-docs", timeout=10) as response:
                _require(response.status == 200)
                spec = strict_json(response.read(1048577), 1048576)
            _require(project_public_api(spec) == expected)
            return True
        except Exception:
            if attempt != 2:
                time.sleep(2)
    return False


def _emit(result, filename="deployment-result.json"):
    data = _canonical(result)
    _require(len(data) <= 8192)
    Path(filename).write_bytes(data + b"\n")
    print(MARKER + data.decode("utf-8"))
    print("MEETME_OPERATION_ID " + _module("transition_orchestrator").operation_id(result["identity"]))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("### Production deployment outcome\n\n")
            stream.write("- Outcome: **" + result["outcome"] + "**\n")
            stream.write("- Deployment complete: **" + str(result["deploymentComplete"]).lower() + "**\n")
            stream.write("- Candidate release: `" + result["identity"]["releaseId"] + "`\n")
            stream.write("- Command effect: " + result["deploymentState"] + "\n")
            stream.write("- Rules restored: " + str(result["rulesRestored"]).lower() + "\n\n")


def _read_plan(env):
    path = Path("release-plan.json")
    _require(path.is_file() and not path.is_symlink())
    plan = _file_json(path, 131072)
    fields = {"protocol", "identity", "manifest", "action", "mode", "gateReady", "gateEnvironment", "originalRules", "parameterReady"}
    _require(type(plan) is dict and set(plan) == fields and plan["protocol"] == "meet-me-release-plan-v1")
    _require(plan["action"] in ("automatic", "preflight", "deploy", "resume", "reconcile"))
    _require(plan["mode"] in ("AUTO_READY", "TRANSITION_REQUIRED"))
    event = _file_json(env["GITHUB_EVENT_PATH"], 262144)
    requested = "automatic" if env["GITHUB_EVENT_NAME"] == "workflow_run" else event.get("inputs", {}).get("action")
    _require(plan["action"] == requested)
    identity = _module("release_completion").validate_identity(plan["identity"])
    _require(identity["workflowRunId"] == env["GITHUB_RUN_ID"] and identity["workflowRunAttempt"] == env["GITHUB_RUN_ATTEMPT"])
    _require(identity["buildRunId"] == env["BUILD_RUN_ID"] and identity["buildRunAttempt"] == env["BUILD_RUN_ATTEMPT"])
    if env["GITHUB_EVENT_NAME"] == "workflow_run":
        _require(identity["sourceSha"] == env["SOURCE_SHA"])
    manifest = plan["manifest"]
    _require(type(manifest) is dict and all(manifest.get(key) == identity[key]
             for key in ("sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest")))
    _module("approved_release").validate_manifest(manifest, {"buildRunId": env["BUILD_RUN_ID"],
        "buildRunAttempt": env["BUILD_RUN_ATTEMPT"], "imageRepository": manifest.get("imageRepository")})
    _require(type(plan["gateReady"]) is bool and type(plan["parameterReady"]) is bool)
    _require(plan["gateEnvironment"] is None or (plan["gateReady"] and type(plan["gateEnvironment"]) is str))
    return plan


def _outputs(plan):
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    fields = {"mode": plan["mode"], "gate_ready": str(plan["gateReady"]).lower(),
              "gate_environment": plan["gateEnvironment"] or "", "action": plan["action"]}
    _require(all(type(value) is str and re.fullmatch(r"[A-Za-z0-9_-]*", value) for value in fields.values()))
    with Path(path).open("a", encoding="utf-8") as stream:
        for key, value in fields.items():
            stream.write(key + "=" + value + "\n")


def plan_pipeline(driver, env):
    with prepare_release(driver, env) as release:
        adapter = AwsReleaseAdapter(release, "automatic")
        require_recovery_safe(adapter)
        release["parameterReady"] = release["parameterReady"] and adapter.provider_ready()
        config = _gate_config()
        gate_ready, gate_name = False, None
        if release["mode"] == "TRANSITION_REQUIRED" and release["action"] == "automatic":
            if (release["identity"]["workflowRunAttempt"] == "1"
                    and _module("approval_gate").validate_config(config) is not None):
                gate_name, _, _ = _gate_environment(driver, config)
                gate_ready = True
        plan = {"protocol": "meet-me-release-plan-v1", "identity": release["identity"],
                "manifest": release["manifest"], "action": release["action"], "mode": release["mode"],
                "gateReady": gate_ready, "gateEnvironment": gate_name,
                "originalRules": _rule_map(release["facts"]["refreshRuleStates"]), "parameterReady": release["parameterReady"]}
        Path("release-plan.json").write_bytes(_canonical(plan) + b"\n")
        _outputs(plan)
        result = _module("release_completion").evaluate_completion(release["identity"], {
            "action": "preflight" if release["action"] == "preflight" else "automatic",
            "mode": release["mode"], "deploymentState": "NOT_ATTEMPTED", "host": None,
            "publicContractVerified": False, "originalRuleStates": plan["originalRules"],
            "ruleStates": plan["originalRules"], "pendingCommands": release["facts"]["pendingCommands"]})
        _emit(result, "approved-release-summary.json")
        _require(release["parameterReady"] is True)
        if release["mode"] == "TRANSITION_REQUIRED" and release["action"] == "automatic":
            _require(gate_ready)  # Missing protected setup never creates an environment by reference.


def _emit_reconciliation(report):
    data = _canonical(report)
    _require(len(data) <= 8192)
    Path("recovery-report.json").write_bytes(data + b"\n")
    print("MEETME_RECONCILIATION_V1 " + data.decode("utf-8"))
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("### Read-only release reconciliation\n\n")
            stream.write("- Deployment complete: **false**\n")
            stream.write("- Outcome: **RECOVERY_REQUIRED**\n")
            stream.write("- Command observation: " + report["commandStatus"] + "\n")
            stream.write("- Fresh host verified: " + str(report["hostVerified"]).lower() + "\n")
            stream.write("- Original operation: `" + report["operationId"] + "`\n\n")


def reconcile_pipeline(driver, env):
    op_id = env.get("OPERATION_ID")
    _require(type(op_id) is str and re.fullmatch(r"[0-9a-f]{64}", op_id))
    event = _file_json(env["GITHUB_EVENT_PATH"], 262144)
    _require(env["GITHUB_EVENT_NAME"] == "workflow_dispatch" and event.get("inputs", {}).get("action") == "reconcile"
             and event["inputs"].get("operation_id") == op_id)
    with prepare_release(driver, env, observe=False) as release:
        adapter = AwsReleaseAdapter(release, "preflight", command_wait_seconds=30)
        report = reconcile_known_operation(release["identity"], op_id, adapter)
        plan = {"protocol": "meet-me-release-plan-v1", "identity": release["identity"], "manifest": release["manifest"],
                "action": "reconcile", "mode": "TRANSITION_REQUIRED", "gateReady": False, "gateEnvironment": None,
                "originalRules": None, "parameterReady": False}
        Path("release-plan.json").write_bytes(_canonical(plan) + b"\n")
        _outputs(plan)
        _emit_reconciliation(report)
        _emit_inspection(inspect_recovery_state(op_id, adapter))


def _validate_reconciliation_report(report, plan, env):
    fields = {"protocol", "identity", "operationId", "commandStatus", "hostVerified", "rulesObserved",
              "pendingCommands", "deploymentComplete", "outcome"}
    _require(type(report) is dict and set(report) == fields and report["protocol"] == "meet-me-release-reconciliation-v1")
    original = _module("release_completion").validate_identity(report["identity"])
    _require(all(original[key] == plan["identity"][key]
                 for key in ("sourceSha", "buildRunId", "buildRunAttempt", "releaseId", "imageDigest")))
    event = _file_json(env["GITHUB_EVENT_PATH"], 262144)
    _require(report["operationId"] == event.get("inputs", {}).get("operation_id")
             == _module("transition_orchestrator").operation_id(original))
    _require(type(report["commandStatus"]) is str and report["commandStatus"] in
             ("UNKNOWN", "Success", "Pending", "InProgress", "Delayed", "Failed", "Cancelled", "TimedOut", "Cancelling"))
    _require(type(report["hostVerified"]) is bool and (not report["hostVerified"] or report["commandStatus"] == "Success"))
    _require(report["deploymentComplete"] is False and report["outcome"] == "RECOVERY_REQUIRED")
    rules = report["rulesObserved"]
    _require(rules is None or (type(rules) is dict and set(rules) == set(_module("release_completion").RULE_NAMES)
             and all(type(value) is str and value in ("ENABLED", "DISABLED") for value in rules.values())))
    pending = report["pendingCommands"]
    _require(pending is None or (type(pending) is int and 0 <= pending <= 1000000))
    return report


def _observed_completion(result, identity):
    """Validate an attestation even when its honest outcome is incomplete."""
    completion = _module("release_completion")
    _require(type(result) is dict and set(result) == completion.RESULT_FIELDS)
    _require(result["protocol"] == completion.PROTOCOL
             and completion.validate_identity(result["identity"]) == completion.validate_identity(identity))
    completion._routing(result["action"], result["mode"], result["deploymentState"])
    _require(all(type(result[field]) is bool for field in
                 ("deploymentComplete", "hostVerified", "publicContractVerified", "rulesRestored")))
    pending = result["pendingCommands"]
    _require(pending is None or (type(pending) is int and 0 <= pending <= 1000000))
    outcome = completion._outcome(result["action"], result["mode"], result["deploymentState"],
                                  result["hostVerified"], result["publicContractVerified"], result["rulesRestored"], pending)
    _require(result["outcome"] == outcome and result["deploymentComplete"] is (outcome == "DEPLOYED_VERIFIED"))
    return result


def _executor_completion(result, plan, env):
    """Bind a host observation to the unique lifecycle job that could produce it."""
    normal, transition = env.get("NORMAL_RELEASE_RESULT"), env.get("TRANSITION_RELEASE_RESULT")
    ran = ("success", "failure", "cancelled")
    if plan["mode"] == "AUTO_READY":
        _require(normal in ran and transition == "skipped")
        _require(result["action"] == "automatic" and result["mode"] == "AUTO_READY")
        executor = normal
    else:
        _require(normal == "skipped" and transition in ran)
        expected = "resume" if plan["action"] == "resume" else "deploy"
        _require(result["action"] == expected and result["mode"] ==
                 ("MAINTENANCE_RESUME_APPROVED" if expected == "resume" else "TRANSITION_APPROVED"))
        executor = transition
    if executor != "success":
        # An upload/cleanup failure cannot erase a verified host effect. The
        # final pipeline outcome is nevertheless incomplete, without falsifying
        # any of the separately observed host/API/rule booleans.
        return dict(result, deploymentComplete=False, outcome="RECOVERY_REQUIRED"), "EXECUTOR_NOT_SUCCESS"
    return result, None


def validate_transition_entry(driver, env):
    plan = _read_plan(env)
    event = _file_json(env["GITHUB_EVENT_PATH"], 262144)
    if env["GITHUB_EVENT_NAME"] == "workflow_dispatch":
        return validate_pipeline_action(driver, event, _context(env), env["RELEASE_ACTION"],
                                        env.get("APPROVE_FIRST_TRANSITION") == "true")
    config = _gate_config()
    release = {"driver": driver, "identity": plan["identity"], "manifest": plan["manifest"]}
    identity = dict(plan["identity"], manifestSha256=hashlib.sha256(_canonical(plan["manifest"])).hexdigest())
    return validate_pipeline_action(driver, event, _context(env), "deploy", True, identity, config, _gate_evidence(release, config))


def execute_pipeline(driver, env, transition):
    saved_plan = _read_plan(env)
    if transition:
        validate_transition_entry(driver, env)  # Also run in the workflow before AWS credentials.
    with prepare_release(driver, env) as release:
        _require(release["identity"] == saved_plan["identity"] and release["manifest"] == saved_plan["manifest"])
        _require(release["publicContract"] is not None)
        if transition:
            action = "resume" if release["action"] == "resume" else "deploy"
            approved = release["approve"] if release["context"]["eventName"] == "workflow_dispatch" else True
            _require(approved)
            adapter = AwsReleaseAdapter(release, action, approved=True)
        else:
            _require(release["mode"] == "AUTO_READY")
            action, adapter = "automatic", AwsReleaseAdapter(release, "automatic")
        state, outcome = "UNKNOWN", "RECOVERY_REQUIRED"
        operation_reason = "OPERATION_UNAVAILABLE"
        try:
            require_recovery_safe(adapter)
            operation = {"identity": release["identity"], "action": action, "approvalVerified": True}
            response = (_module("transition_orchestrator").run_transition(operation, adapter) if transition
                        else run_automatic(release["identity"], adapter))
            state, outcome = response["deploymentState"], response["outcome"]
            allowed_reasons = _module("transition_orchestrator").REASONS | {"NOT_DEPLOYED", "RECOVERY_REQUIRED", "RESTORATION_REQUIRED"}
            candidate_reason = response.get("reason", outcome)
            if type(candidate_reason) is str and candidate_reason in allowed_reasons:
                operation_reason = candidate_reason
        except RecoverySafetyError as failure:
            operation_reason = "UNFINISHED_OPERATION"
            prior = failure.report
            current_op = _module("transition_orchestrator").operation_id(release["identity"])
            if prior["operationId"] != current_op:
                state = "NOT_ATTEMPTED"
            elif prior["journal"]["status"] == "VALID":
                state = prior["journal"]["deploymentState"]
            raise
        finally:
            safe_operation = {"operationId": _module("transition_orchestrator").operation_id(release["identity"]),
                              "reason": operation_reason}
            print("MEETME_OPERATION_OUTCOME_V1 " + _canonical(safe_operation).decode("utf-8"))
            if env.get("GITHUB_STEP_SUMMARY"):
                with Path(env["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as stream:
                    stream.write("- Operation reason: **" + operation_reason + "**\n\n")
            try:
                rules, pending = adapter.read_rules(), adapter.active_commands()
            except Exception:
                rules, pending = None, None
            public = state == "APPLIED" and outcome == "APPLIED" and verify_public_contract(release["publicContract"])
            result = _module("release_completion").evaluate_completion(release["identity"], {
                "action": action, "mode": ("AUTO_READY" if not transition else
                  "MAINTENANCE_RESUME_APPROVED" if action == "resume" else "TRANSITION_APPROVED"),
                "deploymentState": state, "host": adapter.host_proof, "publicContractVerified": public,
                "originalRuleStates": adapter.original_rules, "ruleStates": rules, "pendingCommands": pending})
            _emit(result)
        _module("release_completion").require_complete(result, release["identity"])


def main(driver, arguments):
    os.umask(0o077)
    _require(type(arguments) is list and len(arguments) == 1)
    env = os.environ
    driver.validate_workflow_event(_file_json(env["GITHUB_EVENT_PATH"], 262144), _context(env))
    phase = arguments[0]
    if phase == "--pipeline-plan":
        return plan_pipeline(driver, env)
    if phase == "--pipeline-reconcile":
        return reconcile_pipeline(driver, env)
    if phase == "--pipeline-validate-transition":
        return validate_transition_entry(driver, env)
    if phase in ("--pipeline-automatic", "--pipeline-transition"):
        return execute_pipeline(driver, env, phase == "--pipeline-transition")
    _require(phase == "--pipeline-complete")
    plan = _read_plan(env)
    if plan["action"] == "reconcile":
        report = _validate_reconciliation_report(_file_json("recovery-report.json", 8192), plan, env)
        _emit_reconciliation(report)
        return
    if plan["action"] == "preflight":
        result = _file_json("approved-release-summary.json", 8192)
        expected = _module("release_completion").evaluate_completion(plan["identity"], {
            "action": "preflight", "mode": plan["mode"], "deploymentState": "NOT_ATTEMPTED", "host": None,
            "publicContractVerified": False, "originalRuleStates": plan["originalRules"],
            "ruleStates": plan["originalRules"], "pendingCommands": result.get("pendingCommands")})
        _require(result == expected)
        _emit(result)
        return
    conflicting, provenance_reason = False, None
    try:
        raw = _file_json("deployment-result.json", 8192)
        _require(type(raw) is dict)
        # A well-formed observation for another source/job is a conflict, not
        # evidence that no command ran. Malformed/missing bytes remain absent.
        _observed_completion(raw, raw.get("identity"))
        conflicting = True
        result = _observed_completion(raw, plan["identity"])
        result, provenance_reason = _executor_completion(result, plan, env)
        conflicting = False
    except Exception:
        never_started = (env.get("NORMAL_RELEASE_RESULT") == "skipped"
                         and env.get("TRANSITION_RELEASE_RESULT") == "skipped" and not conflicting)
        # Missing executor output is uncertain unless both jobs provably never ran.
        # This is a pipeline completion assessment, not an inferred approval mode.
        result = _module("release_completion").evaluate_completion(plan["identity"], {
            "action": "automatic", "mode": plan["mode"],
            "deploymentState": "NOT_ATTEMPTED" if never_started else "UNKNOWN", "host": None,
            "publicContractVerified": False, "originalRuleStates": plan["originalRules"],
            "ruleStates": None, "pendingCommands": None})
    if provenance_reason:
        print("MEETME_COMPLETION_REASON " + provenance_reason)
    _emit(result)
    _module("release_completion").require_complete(result, plan["identity"])
