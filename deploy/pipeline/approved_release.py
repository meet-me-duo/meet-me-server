"""Offline approval checks for immutable release metadata and observed host facts."""

from copy import deepcopy
import re


MANIFEST_FIELDS = {
    "protocol", "sourceSha", "buildRunId", "buildRunAttempt", "imageRepository",
    "imageDigest", "releaseId", "archiveSha256", "schemaFingerprint",
    "guardFingerprint", "migrations", "origin",
}
EXPECTED_FIELDS = {"buildRunId", "buildRunAttempt", "imageRepository"}
FACT_FIELDS = {
    "history", "phase", "currentImage", "guardTrusted", "automaticContract",
    "refreshRuleStates", "pendingCommands",
}
RECORD_FIELDS = {
    "protocol", "schemaFingerprint", "guardFingerprint", "history", "currentImage",
    "sourceSha", "buildRunId", "buildRunAttempt",
}
REPOSITORY_PATTERN = (
    r"[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(?:\.cn)?/"
    r"[A-Za-z0-9][A-Za-z0-9._/-]*"
)


def _require(condition):
    if not condition:
        raise ValueError()


def _object(value, fields):
    _require(type(value) is dict and set(value) == fields)


def _matches(value, pattern):
    return type(value) is str and re.fullmatch(pattern, value) is not None


def _run_identity(value):
    _require(_matches(value["sourceSha"], r"[0-9a-f]{40}"))
    for field in ("buildRunId", "buildRunAttempt"):
        _require(_matches(value[field], r"[1-9][0-9]{0,19}"))


def _fingerprints(value):
    for field in ("schemaFingerprint", "guardFingerprint"):
        _require(_matches(value[field], r"[0-9a-f]{64}"))


def _migration_rows(rows, history=False):
    _require(type(rows) is list and (7 <= len(rows) <= 10 if history else len(rows) == 10))
    fields = {"version", "script", "checksum"}
    if history:
        fields |= {"success"}
    for index, row in enumerate(rows, 1):
        _object(row, fields)
        _require(row["version"] == str(index))
        _require(_matches(row["script"], rf"V{index}__[A-Za-z0-9_]+\.sql"))
        _require(type(row["checksum"]) is int and -(2 ** 31) <= row["checksum"] < 2 ** 31)
        if history:
            _require(row["success"] is True)


def _image(value, repository):
    _require(_matches(value, re.escape(repository) + r"@sha256:[0-9a-f]{64}"))


def validate_manifest(manifest, expected):
    """Bind trusted immutable storage metadata to its requested publication identity."""
    try:
        _object(manifest, MANIFEST_FIELDS)
        _object(expected, EXPECTED_FIELDS)
        _require(manifest["protocol"] == "meet-me-approved-release-v1")
        _run_identity(manifest)
        _fingerprints(manifest)
        _require(_matches(manifest["imageRepository"], REPOSITORY_PATTERN))
        _require(all(manifest[field] == expected[field] for field in EXPECTED_FIELDS))
        _require(_matches(manifest["imageDigest"], r"sha256:[0-9a-f]{64}"))
        _require(_matches(manifest["archiveSha256"], r"[0-9a-f]{64}"))
        _require(manifest["releaseId"] == "-".join(
            manifest[field] for field in ("sourceSha", "buildRunId", "buildRunAttempt")
        ))
        _migration_rows(manifest["migrations"])
        origin = manifest["origin"]
        _object(origin, {"repository", "ref", "workflow", "event", "conclusion", "ciRunId", "ciSha"})
        _require(origin["repository"] == "meet-me-duo/meet-me-server")
        _require(origin["ref"] == "refs/heads/main" and origin["workflow"] == "CI")
        _require(origin["event"] == "push" and origin["conclusion"] == "success")
        _require(_matches(origin["ciRunId"], r"[1-9][0-9]{0,19}"))
        _require(origin["ciSha"] == manifest["sourceSha"])
        result = deepcopy(manifest)
        result["imageRef"] = manifest["imageRepository"] + "@" + manifest["imageDigest"]
        return result
    except (ValueError, TypeError, KeyError):
        raise ValueError("UNTRUSTED_RELEASE") from None


def validate_deploy_facts(manifest, facts, action, approve_first_transition=False):
    """Judge observed host facts without invoking services or mutating approval state."""
    try:
        _require(type(manifest) is dict and set(manifest) in (MANIFEST_FIELDS, MANIFEST_FIELDS | {"imageRef"}))
        raw_manifest = {key: value for key, value in manifest.items() if key != "imageRef"}
        validated = validate_manifest(raw_manifest, {field: manifest[field] for field in EXPECTED_FIELDS})
        if "imageRef" in manifest:
            _require(manifest["imageRef"] == validated["imageRef"])
        _object(facts, FACT_FIELDS)
        _require(type(action) is str and action in {"automatic", "preflight", "deploy"})
        _require(type(approve_first_transition) is bool)
        _require(type(facts["pendingCommands"]) is int and facts["pendingCommands"] == 0)
        _require(type(facts["guardTrusted"]) is bool)
        _require(type(facts["phase"]) is str and facts["phase"] in {"UNINSTALLED", "PRE_V8", "V8_STARTED", "READY"})
        _require(not (facts["phase"] == "UNINSTALLED" and facts["guardTrusted"]))
        _image(facts["currentImage"], manifest["imageRepository"])
        states = facts["refreshRuleStates"]
        _require(type(states) is list and len(states) == 2)
        _require(all(type(state) is str and state in {"ENABLED", "DISABLED"} for state in states))
        _migration_rows(facts["history"], history=True)
        expected_history = [dict(row, success=True) for row in manifest["migrations"]]
        _require(facts["history"] == expected_history[:len(facts["history"])])

        record = facts["automaticContract"]
        if record is not None:
            _object(record, RECORD_FIELDS)
            _require(record["protocol"] == "meet-me-automatic-release-v1")
            _run_identity(record)
            _fingerprints(record)
            _migration_rows(record["history"], history=True)
            _require(len(record["history"]) == 10)
            _image(record["currentImage"], manifest["imageRepository"])

        same_contract = (
            record is not None and facts["guardTrusted"] and facts["phase"] == "READY"
            and facts["currentImage"] == record["currentImage"]
            and facts["history"] == record["history"] == expected_history
            and manifest["schemaFingerprint"] == record["schemaFingerprint"]
            and manifest["guardFingerprint"] == record["guardFingerprint"]
        )
        if same_contract:
            return {"mode": "AUTO_READY"}
        if action in {"automatic", "preflight"}:
            return {"mode": "TRANSITION_REQUIRED"}

        _require(approve_first_transition and states == ["DISABLED", "DISABLED"])
        if facts["guardTrusted"]:
            _require(facts["phase"] in {"PRE_V8", "V8_STARTED", "READY"})
        else:
            _require(facts["phase"] == "UNINSTALLED" and record is None)
        return {"mode": "TRANSITION_APPROVED"}
    except (ValueError, TypeError, KeyError):
        raise ValueError("UNSAFE_RELEASE_STATE") from None
