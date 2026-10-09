"""Bounded metadata preflight using only the existing production role."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

REPOSITORY = "meet-me-duo/meet-me-server"
PROTOCOL = "meetme-readonly-preflight-v1"


def validate_target(pr, event):
    try:
        sha = pr["head"]["sha"]
        valid = (
            pr["number"] == 100 and pr["user"]["login"] == "jinhyeongpark"
            and pr["head"]["repo"]["full_name"] == REPOSITORY
            and pr["head"]["ref"] == "feature/integrate-recommendations-fallback"
            and pr["base"]["ref"] == "develop"
            and event["repository"] == REPOSITORY
            and event["actor"] == "jinhyeongpark"
            and event["triggeringActor"] == "jinhyeongpark"
            and sha == event["sha"] and re.fullmatch(r"[0-9a-f]{40}", sha)
        )
        if valid:
            return sha
    except (KeyError, TypeError):
        pass
    raise ValueError("UNTRUSTED_TARGET")


def _reject_secrets(value):
    if isinstance(value, dict):
        for key, nested in value.items():
            if re.search(r"password|credential|token|secret|database_url|api_key|stderr|stdout|parameters|inputtransformer", key, re.I):
                raise ValueError("UNTRUSTED_OUTPUT")
            _reject_secrets(nested)
    elif isinstance(value, list):
        for nested in value:
            _reject_secrets(nested)

IMAGE_PATTERN = r"[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(?:\.cn)?/[A-Za-z0-9._/-]+@sha256:[0-9a-f]{64}"
FILE_PATHS = {"guard/host-release-guard.sh", "guard/compose.guard.yml",
              "state/installation-manifest.json", "state/prerequisites.json",
              "state/compatible-images.tsv", "state/phase", "state/minimum-contract",
              "guard/meet-me-guarded-restart.service"}


def _require(condition):
    if not condition:
        raise ValueError()


def _validate_host(host):
    if "files" in host:
        _require(isinstance(host["files"], dict) and set(host["files"]) <= FILE_PATHS)
        for item in host["files"].values():
            _require(isinstance(item, dict) and type(item.get("exists")) is bool)
            if not item["exists"]:
                _require(set(item) == {"exists"})
                continue
            _require(set(item) in ({"exists", "uid", "mode", "regular"},
                                  {"exists", "uid", "mode", "regular", "sha256"}))
            _require(type(item["uid"]) is int and item["uid"] >= 0)
            _require(isinstance(item["mode"], str) and re.fullmatch(r"0o[0-7]{3,4}", item["mode"]))
            _require(type(item["regular"]) is bool)
            if "sha256" in item:
                _require(isinstance(item["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]))
    for key in ("containerBefore", "containerAfter"):
        if key not in host:
            continue
        item = host[key]
        _require(isinstance(item, dict))
        if item == {"present": False}:
            continue
        expected = {"running", "pid", "restartCount"}
        if key == "containerBefore":
            expected |= {"present", "restartPolicy", "image", "health"}
            _require(item.get("present") is True)
            _require(item.get("restartPolicy") in ("no", "always", "on-failure", "unless-stopped"))
            _require(item.get("health") in ("", "healthy", "unhealthy", "starting"))
            _require(isinstance(item.get("image"), str) and re.fullmatch(IMAGE_PATTERN, item["image"]))
        _require(set(item) == expected and type(item["running"]) is bool)
        _require(type(item["pid"]) is int and item["pid"] >= 0)
        _require(type(item["restartCount"]) is int and item["restartCount"] >= 0)
    if "approvedImages" in host:
        _require(isinstance(host["approvedImages"], list) and len(host["approvedImages"]) <= 20)
        for entry in host["approvedImages"]:
            _require(isinstance(entry, str) and re.fullmatch(
                IMAGE_PATTERN + r"\tinput_revision_v8\t[0-9a-fA-F]{64}", entry))
    if "currentRelease" in host:
        _require(isinstance(host["currentRelease"], str) and (
            host["currentRelease"] == "UNAVAILABLE" or re.fullmatch(
                r"/opt/meet-me/releases/[0-9a-f]{40}-[0-9]{1,20}(?:-[0-9]{1,20})?", host["currentRelease"])))
    if "phase" in host:
        _require(host["phase"] in ("PRE_V8", "V8_STARTED", "READY"))
    if "minimum-contract" in host:
        _require(host["minimum-contract"] == "input_revision_v8")
    if "service" in host:
        states = {"LoadState": ("loaded", "not-found", "error", "masked", "bad-setting"),
                  "ActiveState": ("active", "reloading", "inactive", "failed", "activating", "deactivating", "maintenance"),
                  "UnitFileState": ("enabled", "enabled-runtime", "disabled", "static", "masked", "masked-runtime",
                                    "indirect", "generated", "transient", "linked", "linked-runtime", "alias", "not-found")}
        _require(isinstance(host["service"], dict) and set(host["service"]) <= set(states))
        for key, value in host["service"].items():
            _require(isinstance(value, str) and value in states[key])


def summarize_invocation(status, stdout, stderr):
    try:
        if status != "Success" or stderr.strip() or len(stdout) > 20000:
            raise ValueError()
        data = json.loads(stdout)
        if set(data) != {"protocol", "host", "flyway"} or data["protocol"] != PROTOCOL:
            raise ValueError()
        _reject_secrets(data)
        host, flyway = data["host"], data["flyway"]
        host_keys = {"api", "files", "currentRelease", "phase", "minimum-contract",
                     "approvedImages", "containerBefore", "containerAfter", "service"}
        if not isinstance(host, dict) or not set(host) <= host_keys:
            raise ValueError()
        _validate_host(host)
        if "api" in host:
            if set(host["api"]) != {"healthz", "openapi"}:
                raise ValueError()
            if any(type(x) is not int or not 0 <= x <= 599 for x in host["api"].values()):
                raise ValueError()
        if flyway.get("status") == "success":
            if set(flyway) != {"status", "database", "serverVersion", "history"}:
                raise ValueError()
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", flyway["database"]):
                raise ValueError()
            if not re.fullmatch(r"[A-Za-z0-9 .()_-]{1,160}", flyway["serverVersion"]):
                raise ValueError()
            if not isinstance(flyway["history"], list) or len(flyway["history"]) > 100:
                raise ValueError()
            for row in flyway["history"]:
                if set(row) != {"version", "script", "checksum", "success"}:
                    raise ValueError()
                if row["version"] is not None and not re.fullmatch(r"[0-9.]{1,32}", row["version"]):
                    raise ValueError()
                if not re.fullmatch(r"V[0-9._]+__[A-Za-z0-9_]+\.sql", row["script"]):
                    raise ValueError()
                if type(row["success"]) is not bool or (row["checksum"] is not None and type(row["checksum"]) is not int):
                    raise ValueError()
        elif set(flyway) != {"status", "reason"} or flyway["status"] != "unavailable" or not re.fullmatch(
                r"SQL_[A-Z0-9_]{5,7}|HELPER_FAILED|MISSING_CONNECTION|ROLLBACK_FAILED|CLOSE_FAILED", flyway["reason"]):
            raise ValueError()
        return {"status": "success", "host": host, "flyway": flyway}
    except (ValueError, TypeError, KeyError, AttributeError):
        raise ValueError("UNTRUSTED_OUTPUT") from None


def event_target():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    context = {"repository": os.environ["GITHUB_REPOSITORY"],
               "actor": os.environ["GITHUB_ACTOR"],
               "triggeringActor": os.environ["GITHUB_TRIGGERING_ACTOR"],
               "sha": event["pull_request"]["head"]["sha"]}
    sha = validate_target(event["pull_request"], context)
    # Public fixed endpoint; no stored token or user-supplied destination.
    request = urllib.request.Request(
        "https://api.github.com/repos/" + REPOSITORY + "/pulls/100",
        headers={"Accept": "application/vnd.github+json", "User-Agent": "meetme-readonly-preflight"})
    with urllib.request.urlopen(request, timeout=10) as response:
        current = json.load(response)
    validate_target(current, context)
    return sha


def main_target():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    sha = os.environ.get("SOURCE_SHA", "")
    if not (
        os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
        and os.environ.get("GITHUB_REPOSITORY") == REPOSITORY
        and os.environ.get("GITHUB_REF") == "refs/heads/main"
        and event.get("repository", {}).get("full_name") == REPOSITORY
        and sha == os.environ.get("GITHUB_SHA")
        and re.fullmatch(r"[0-9a-f]{40}", sha)
    ):
        raise ValueError("UNTRUSTED_TARGET")
    return sha


def aws_call(service, operation, *args):
    command = ["aws", "--region", os.environ["AWS_REGION"], "--output", "json", service, operation, *args]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=25)
        if result.returncode:
            return {"status": "unavailable", "reason": "AWS_DENIED_OR_FAILED"}
        return {"status": "success", "data": json.loads(result.stdout)}
    except subprocess.TimeoutExpired:
        return {"status": "unavailable", "reason": "AWS_TIMEOUT"}
    except json.JSONDecodeError:
        return {"status": "unavailable", "reason": "AWS_INVALID_JSON"}
    except (OSError, UnicodeError):
        return {"status": "unavailable", "reason": "AWS_EXECUTION_FAILED"}
    except Exception:
        return {"status": "unavailable", "reason": "AWS_UNAVAILABLE"}


def active_commands(instance):
    """Bound active reads; Pending cannot safely be filtered by managed node."""
    active = {}
    query = ("{Commands: Commands[].{CommandId:CommandId,Status:Status,"
             "RequestedDateTime:RequestedDateTime,DocumentName:DocumentName,"
             "InstanceIds:InstanceIds,Targets:Targets},NextToken:NextToken}")
    for key, value in (("Status", "Pending"), ("ExecutionStage", "Executing")):
        result = aws_call("ssm", "list-commands", "--filters", json.dumps([{"key": key, "value": value}]),
                          "--page-size", "50", "--max-items", "50", "--query", query)
        if result["status"] != "success":
            return result
        try:
            data = result["data"]
            if data.get("NextToken"):
                return {"status": "unavailable", "reason": "COMMANDS_TRUNCATED"}
            if type(data["Commands"]) is not list:
                raise ValueError()
            for command in data["Commands"]:
                if type(command) is not dict or not re.fullmatch(r"[0-9a-f-]{36}", command["CommandId"]):
                    raise ValueError()
                if command["Status"] not in ("Pending", "InProgress", "Delayed", "Cancelling", "Success", "Cancelled", "Failed", "TimedOut"):
                    raise ValueError()
                ids, targets = command["InstanceIds"], command["Targets"]
                if (type(ids) is not list or type(targets) is not list or not (ids or targets)
                        or any(type(item) is not str or not re.fullmatch(r"(?:i-[0-9a-f]{8,17}|mi-[0-9a-f]{17})", item) for item in ids)):
                    raise ValueError()
                matches = instance in ids
                for target in targets:
                    if (type(target) is not dict or set(target) != {"Key", "Values"}
                            or target["Key"] != "InstanceIds" or type(target["Values"]) is not list
                            or not target["Values"] or any(type(item) is not str or (item != "*" and not re.fullmatch(
                                r"(?:i-[0-9a-f]{8,17}|mi-[0-9a-f]{17})", item)) for item in target["Values"])):
                        raise ValueError()
                    matches = matches or instance in target["Values"] or "*" in target["Values"]
                if matches and command["Status"] in ("Pending", "InProgress", "Delayed", "Cancelling"):
                    active[command["CommandId"]] = {k: command.get(k) for k in
                        ("CommandId", "Status", "RequestedDateTime", "DocumentName")}
        except (ValueError, TypeError, KeyError, AttributeError):
            return {"status": "unavailable", "reason": "UNEXPECTED_COMMAND_METADATA"}
    return {"status": "success", "data": list(active.values())}


def refresh_target_metadata(data):
    return [{k: target.get(k) for k in ("Id", "Arn", "RoleArn")}
            | {"RunCommandTargets": target["RunCommandParameters"]["RunCommandTargets"]}
            for target in data["Targets"]]


def project(call, mapper):
    if call["status"] != "success":
        return call
    try:
        return {"status": "success", "data": mapper(call["data"])}
    except (KeyError, IndexError, TypeError):
        return {"status": "unavailable", "reason": "UNEXPECTED_METADATA"}


def main():
    arguments = sys.argv[1:]
    main_entry = arguments in (["--main"], ["--main", "--validate"])
    target = main_target if main_entry else event_target
    sha = target()
    if arguments in (["--validate"], ["--main", "--validate"]):
        print("Approved source identity validated.")
        return
    if arguments not in ([], ["--main"]):
        raise ValueError("UNTRUSTED_TARGET")
    instance = os.environ["RUNTIME_INSTANCE_ID"]
    if not re.fullmatch(r"i-[0-9a-f]{8,17}", instance):
        raise ValueError("UNTRUSTED_TARGET")
    summary = {"sourceSha": sha, "instanceId": instance, "reads": {}}
    reads = summary["reads"]
    reads["instance"] = project(aws_call("ec2", "describe-instances", "--instance-ids", instance),
        lambda d: {k: d["Reservations"][0]["Instances"][0].get(k)
                   for k in ("InstanceId", "State", "InstanceType", "IamInstanceProfile")})
    reads["ssmInstance"] = project(aws_call("ssm", "describe-instance-information", "--filters",
        "Key=InstanceIds,Values=" + instance),
        lambda d: [{k: item.get(k) for k in ("InstanceId", "PingStatus", "PlatformType")}
                   for item in d["InstanceInformationList"]])
    reads["rds"] = project(aws_call("rds", "describe-db-instances", "--db-instance-identifier", "meet-me-production"),
        lambda d: {k: d["DBInstances"][0].get(k) for k in
                   ("DBInstanceIdentifier", "DBInstanceStatus", "Engine", "EngineVersion", "Endpoint",
                    "BackupRetentionPeriod", "LatestRestorableTime", "DeletionProtection")})
    reads["commandsBefore"] = active_commands(instance)
    for suffix in ("rotated", "reconcile"):
        name = "meet-me-production-db-credential-" + suffix
        reads[name] = project(aws_call("events", "describe-rule", "--name", name),
            lambda d: {k: d.get(k) for k in ("Name", "State", "ScheduleExpression")})
        reads[name + "-targets"] = project(aws_call("events", "list-targets-by-rule", "--rule", name),
            refresh_target_metadata)
    parameter = "/meet-me/production/secret/openai-api-key"
    reads["openaiParameterMetadata"] = project(aws_call("ssm", "describe-parameters", "--parameter-filters",
            "Key=Name,Option=Equals,Values=" + parameter),
        lambda d: [{k: p.get(k) for k in ("Name", "Type", "Version")} for p in d["Parameters"]])
    base = Path(__file__).resolve().parent
    compiled = base / "compiled" / "ReadOnlyFlywayProbe.class"
    payload = base64.b64encode(compiled.read_bytes()).decode("ascii")
    if len(payload) > 32768:
        raise ValueError("UNTRUSTED_TARGET")
    # Recheck exact head immediately before the single configured-instance call.
    if target() != sha:
        raise ValueError("UNTRUSTED_TARGET")
    script = base.joinpath("host-preflight.sh").read_text()
    parameters = json.dumps({"commands": ["bash -s -- " + payload + " <<'MEETME_READONLY_SCRIPT'\n"
                                          + script + "\nMEETME_READONLY_SCRIPT"],
                             "executionTimeout": ["60"]})
    sent = aws_call("ssm", "send-command", "--instance-ids", instance,
        "--document-name", "AWS-RunShellScript", "--comment", "Meet-me authorized read-only preflight",
        "--timeout-seconds", "120", "--parameters", parameters)
    if sent["status"] != "success":
        reads["host"] = sent
    else:
        command_id = sent["data"]["Command"]["CommandId"]
        reads["host"] = {"status": "unavailable", "reason": "SSM_PENDING_OR_FAILED"}
        for _ in range(30):
            result = aws_call("ssm", "get-command-invocation", "--command-id", command_id, "--instance-id", instance)
            if result["status"] != "success":
                time.sleep(2)
                continue
            data = result["data"]
            if data["Status"] in ("Pending", "InProgress", "Delayed"):
                time.sleep(2)
                continue
            try:
                reads["host"] = summarize_invocation(data["Status"], data.get("StandardOutputContent", ""),
                                                    data.get("StandardErrorContent", ""))
            except ValueError:
                reads["host"] = {"status": "unavailable", "reason": "UNTRUSTED_OUTPUT"}
            break
    # Public GET metadata only, with accurate HTTP errors.
    api = {}
    for key, path in (("healthz", "/healthz"), ("openapi", "/v3/api-docs")):
        try:
            with urllib.request.urlopen("https://api.meet-me.co.kr" + path, timeout=10) as response:
                api[key] = response.status
        except Exception as failure:
            api[key] = getattr(failure, "code", 0)
    summary["publicApi"] = api
    _reject_secrets(reads.get("host", {}))
    Path("production-preflight-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("Preflight finished. Sanitized metadata is in the workflow artifact; no raw output was logged.")
    if any(read["status"] != "success" for read in reads.values()):
        sys.exit(2)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Preflight unavailable; target validation or bounded metadata read failed.")
        sys.exit(2)
