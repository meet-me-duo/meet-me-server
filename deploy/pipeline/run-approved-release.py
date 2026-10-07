"""Consume an existing immutable release through validated production facts."""

import base64
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import sys
import tarfile
import tempfile
import time
import urllib.request
import zlib

REPOSITORY = "meet-me-duo/meet-me-server"
BASE = Path(__file__).resolve().parent.parent
MAX_ARCHIVE = 4 * 1024 * 1024
CONTRACT_FILES = tuple(sorted((
    "scripts/host-release-guard.sh", "scripts/install-host-release-guard.sh", "scripts/deploy-release.sh",
    "scripts/rollback-release.sh", "scripts/refresh-database-credential.sh", "compose.guard.yml",
    "meet-me-guarded-restart.service", "pipeline/approved_release.py", "pipeline/host-approved-release.py",
    "pipeline/run-approved-release.py", "pipeline/publish_manifest.py", "preflight/host-preflight.sh",
    "preflight/run-preflight.py", "preflight/ReadOnlyFlywayProbe.java",
)))


def _require(condition):
    if not condition:
        raise ValueError("UNTRUSTED_RELEASE_STATE")


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _encoded(value):
    return base64.b64encode(_canonical(value)).decode("ascii")


def validate_workflow_event(event, context):
    try:
        _require(type(context) is dict and set(context) == {"repository", "ref", "eventName", "sourceSha"})
        _require(context["repository"] == REPOSITORY and context["ref"] == "refs/heads/main")
        _require(type(context["sourceSha"]) is str and re.fullmatch(r"[0-9a-f]{40}", context["sourceSha"]))
        _require(event["repository"]["full_name"] == REPOSITORY)
        if context["eventName"] == "workflow_run":
            run = event["workflow_run"]
            _require(all(run[key] == value for key, value in {
                "name": "CI", "event": "push", "conclusion": "success", "head_branch": "main",
                "head_sha": context["sourceSha"],
            }.items()))
            for key in ("repository", "head_repository"):
                if key in run:
                    _require(run[key]["full_name"] == REPOSITORY)
        else:
            _require(context["eventName"] == "workflow_dispatch")
        return context["sourceSha"]
    except (ValueError, KeyError, TypeError):
        raise ValueError("UNTRUSTED_WORKFLOW") from None


def validate_archive(archive_path):
    """Inspect bounded tar bytes only; never extract a member or follow a link."""
    try:
        path = Path(archive_path)
        _require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= MAX_ARCHIVE)
        with gzip.open(path, "rb") as source:
            raw = source.read(MAX_ARCHIVE + 1)
        _require(len(raw) <= MAX_ARCHIVE)
        seen, size = set(), 0
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
            members = archive.getmembers()
            _require(0 < len(members) <= 256)
            for member in members:
                name = member.name.rstrip("/")
                _require(re.fullmatch(r"[A-Za-z0-9._/-]+", name) is not None)
                parts = name.split("/")
                _require(not PurePosixPath(name).is_absolute() and all(part not in ("", ".", "..") for part in parts))
                _require(name not in seen and (member.isfile() or member.isdir()))
                seen.add(name)
                _require(0 <= member.size <= 512 * 1024)
                size += member.size
                _require(size <= MAX_ARCHIVE)
                if member.isfile():
                    stream = archive.extractfile(member)
                    _require(stream is not None and len(stream.read(512 * 1024 + 1)) == member.size)
    except (OSError, EOFError, ValueError, TypeError, tarfile.TarError):
        raise ValueError("UNTRUSTED_ARCHIVE") from None


def _verify_package(archive_path, manifest):
    validate_archive(archive_path)
    with tarfile.open(archive_path, "r:gz") as archive:
        files = {member.name: archive.extractfile(member).read() for member in archive.getmembers() if member.isfile()}
    _require(set(CONTRACT_FILES) | {"migrations/" + row["script"] for row in manifest["migrations"]}
             | {"preflight/compiled/ReadOnlyFlywayProbe.class", "runtime-provider-mode", "compose.production.yml"} <= set(files))
    guard = [{"path": relative, "sha256": hashlib.sha256(files[relative]).hexdigest()} for relative in CONTRACT_FILES]
    catalog = []
    for row in manifest["migrations"]:
        raw = files["migrations/" + row["script"]]
        crc = zlib.crc32("".join(re.split(r"\r\n|\r|\n", raw.decode("utf-8-sig"))).encode("utf-8"))
        _require(row["checksum"] == (crc if crc < 2 ** 31 else crc - 2 ** 32))
        catalog.append({"version": row["version"], "script": row["script"], "sha256": hashlib.sha256(raw).hexdigest()})
    _require(hashlib.sha256(_canonical(guard)).hexdigest() == manifest["guardFingerprint"])
    _require(hashlib.sha256(_canonical(catalog)).hexdigest() == manifest["schemaFingerprint"])
    _require(files["preflight/compiled/ReadOnlyFlywayProbe.class"].startswith(bytes.fromhex("cafebabe")))


def aws_call(service, operation, *args):
    return _load("approved_preflight_aws", BASE / "preflight/run-preflight.py").aws_call(service, operation, *args)


def _aws(service, operation, *args):
    result = aws_call(service, operation, *args)
    _require(result.get("status") == "success" and type(result.get("data")) is dict)
    return result["data"]


def _download(bucket, key, target, maximum):
    metadata = _aws("s3api", "head-object", "--bucket", bucket, "--key", key)
    _require(type(metadata.get("ContentLength")) is int and 0 < metadata["ContentLength"] <= maximum)
    _aws("s3api", "get-object", "--bucket", bucket, "--key", key, str(target))
    _require(target.is_file() and not target.is_symlink() and target.stat().st_size == metadata["ContentLength"])


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, destination):
        raise ValueError("UNTRUSTED_WORKFLOW")


def _github_run(run_id, attempt=None):
    suffix = "/attempts/" + attempt if attempt is not None else ""
    request = urllib.request.Request(
        "https://api.github.com/repos/" + REPOSITORY + "/actions/runs/" + run_id + suffix,
        headers={"Accept": "application/vnd.github+json", "User-Agent": "meet-me-approved-release"},
    )
    with urllib.request.build_opener(_NoRedirect).open(request, timeout=10) as response:
        raw = response.read(256 * 1024 + 1)
    _require(len(raw) <= 256 * 1024)
    return json.loads(raw)


def _verify_origin(manifest, action):
    origin = manifest["origin"]
    run = _github_run(origin["ciRunId"])
    _require(type(run.get("id")) is int and str(run["id"]) == origin["ciRunId"])
    _require(run["repository"]["full_name"] == REPOSITORY and run["head_repository"]["full_name"] == REPOSITORY)
    _require(run.get("path") == ".github/workflows/ci.yml")
    validate_workflow_event({"repository": run["repository"], "workflow_run": run}, {
        "repository": REPOSITORY, "ref": "refs/heads/main", "eventName": "workflow_run", "sourceSha": manifest["sourceSha"],
    })
    producer = _github_run(manifest["buildRunId"], manifest["buildRunAttempt"])
    _require(type(producer.get("id")) is int and str(producer["id"]) == manifest["buildRunId"])
    _require(type(producer.get("run_attempt")) is int and str(producer["run_attempt"]) == manifest["buildRunAttempt"])
    _require(producer["repository"]["full_name"] == REPOSITORY and producer["head_repository"]["full_name"] == REPOSITORY)
    _require(producer.get("path") == ".github/workflows/deploy.yml" and producer.get("name") == "Deploy Production")
    _require(producer.get("event") == "workflow_run" and producer.get("head_branch") == "main")
    if producer.get("status") == "in_progress":
        _require(action == "automatic" and manifest["buildRunId"] == os.environ["GITHUB_RUN_ID"]
                 and manifest["buildRunAttempt"] == os.environ["GITHUB_RUN_ATTEMPT"])
    else:
        _require(producer.get("status") == "completed")


def _ssm(instance, command, reader, execution_seconds=60):
    _require(type(command) is str and len(command.encode("utf-8")) <= 60 * 1024)
    sent = _aws("ssm", "send-command", "--instance-ids", instance,
                "--document-name", "AWS-RunShellScript", "--comment", "Meet-me validated release operation",
                "--timeout-seconds", "120", "--parameters", json.dumps({
                    "commands": [command], "executionTimeout": [str(execution_seconds)],
                }))
    command_id = sent["Command"]["CommandId"]
    _require(type(command_id) is str and re.fullmatch(r"[0-9a-f-]{36}", command_id))
    deadline = time.monotonic() + execution_seconds + 60
    while time.monotonic() < deadline:
        call = aws_call("ssm", "get-command-invocation", "--command-id", command_id, "--instance-id", instance)
        if call.get("status") != "success":
            time.sleep(2)
            continue
        data = call["data"]
        if data.get("Status") in ("Pending", "InProgress", "Delayed"):
            time.sleep(2)
            continue
        return reader(data.get("Status"), data.get("StandardOutputContent", ""), data.get("StandardErrorContent", ""))
    raise ValueError("BOUNDED_SSM_UNAVAILABLE")


def _strict_state(status, stdout, stderr):
    _require(status == "Success" and not stderr.strip() and len(stdout) <= 65536)
    state = json.loads(stdout)
    _require(type(state) is dict and set(state) == {"protocol", "automaticContract", "guardTrusted", "phase", "currentImage"})
    _require(state["protocol"] == "meet-me-release-state-v1" and type(state["guardTrusted"]) is bool)
    _require(state["phase"] in ("UNINSTALLED", "PRE_V8", "V8_STARTED", "READY"))
    return state


def _host_preflight(instance):
    probe = BASE / "preflight/compiled/ReadOnlyFlywayProbe.class"
    payload = base64.b64encode(probe.read_bytes()).decode("ascii")
    _require(len(payload) <= 32768)
    script = (BASE / "preflight/host-preflight.sh").read_text()
    command = "bash -s -- " + payload + " <<'MEETME_APPROVED_PREFLIGHT'\n" + script + "\nMEETME_APPROVED_PREFLIGHT"
    reader = _load("approved_preflight_reader", BASE / "preflight/run-preflight.py")
    return _ssm(instance, command, reader.summarize_invocation)


def _host_state(instance, manifest):
    files = {}
    for relative in ("pipeline/host-approved-release.py", "pipeline/approved_release.py"):
        files[relative] = base64.b64encode((BASE / relative).read_bytes()).decode("ascii")
    # Reviewed source bytes only, in a root-owned /run directory; managed host state is untouched.
    bootstrap = """import base64,json,pathlib,subprocess,tempfile,shutil,sys,os
os.umask(0o077)
root=pathlib.Path(tempfile.mkdtemp(prefix='meet-me-read-state.',dir='/run'))
try:
 for relative,payload in json.loads(sys.argv[1]).items():
  target=root/relative;target.parent.mkdir(parents=True,exist_ok=True)
  target.write_bytes(base64.b64decode(payload,validate=True));target.chmod(0o600)
 result=subprocess.run(['python3',str(root/'pipeline/host-approved-release.py'),'--read-state',sys.argv[2]],capture_output=True,text=True,timeout=55)
 if result.returncode or result.stderr.strip():sys.exit(2)
 sys.stdout.write(result.stdout)
finally:shutil.rmtree(root)
"""
    command = "set +x\numask 077\npython3 - " + shlex.quote(json.dumps(files)) + " " + _encoded(manifest) + " <<'MEETME_STATE_READER'\n" + bootstrap + "\nMEETME_STATE_READER"
    _require(len(command.encode("utf-8")) <= 60 * 1024)
    return _ssm(instance, command, _strict_state)


def _aws_facts(instance, region, repository):
    account = repository.split(".", 1)[0]
    partition = "aws-cn" if ".amazonaws.com.cn/" in repository else "aws"
    reservations = _aws("ec2", "describe-instances", "--instance-ids", instance)["Reservations"]
    _require(len(reservations) == 1 and len(reservations[0]["Instances"]) == 1)
    host = reservations[0]["Instances"][0]
    _require(host["InstanceId"] == instance and host["State"]["Name"] == "running")
    info = _aws("ssm", "describe-instance-information", "--filters", "Key=InstanceIds,Values=" + instance)["InstanceInformationList"]
    _require(len(info) == 1 and info[0]["InstanceId"] == instance and info[0]["PingStatus"] == "Online")
    states = []
    for suffix, target_id in (("rotated", "RefreshDatabaseCredential"), ("reconcile", "ReconcileDatabaseCredential")):
        name = "meet-me-production-db-credential-" + suffix
        rule = _aws("events", "describe-rule", "--name", name)
        _require(rule["Name"] == name and rule["State"] in ("ENABLED", "DISABLED"))
        targets = _aws("events", "list-targets-by-rule", "--rule", name)
        _require(not targets.get("NextToken") and len(targets["Targets"]) == 1)
        target = targets["Targets"][0]
        _require(target["Id"] == target_id and target["Arn"] == f"arn:{partition}:ssm:{region}::document/AWS-RunShellScript")
        _require(target["RoleArn"] == f"arn:{partition}:iam::{account}:role/meet-me-production-db-credential-refresh")
        _require(target["RunCommandTargets"] == [{"Key": "InstanceIds", "Values": [instance]}])
        states.append(rule["State"])
    # Pending may not yet appear in a node-scoped query. Supported global active filters preserve that drain boundary.
    active = {}
    for key, value in (("Status", "Pending"), ("ExecutionStage", "Executing")):
        result = _aws("ssm", "list-commands", "--filters", json.dumps([{"key": key, "value": value}]),
                      "--page-size", "50", "--max-items", "50")
        _require(not result.get("NextToken") and type(result.get("Commands")) is list)
        for command in result["Commands"]:
            _require(type(command) is dict and type(command.get("CommandId")) is str
                     and re.fullmatch(r"[0-9a-f-]{36}", command["CommandId"]))
            _require(command.get("Status") in ("Pending", "InProgress", "Delayed", "Cancelling", "Success", "Cancelled", "Failed", "TimedOut"))
            ids, targets = command.get("InstanceIds"), command.get("Targets")
            _require(type(ids) is list and all(type(item) is str and re.fullmatch(r"(?:i-[0-9a-f]{8,17}|mi-[0-9a-f]{17})", item) for item in ids))
            _require(type(targets) is list and bool(ids or targets))
            matches = instance in ids
            for target in targets:
                _require(type(target) is dict and set(target) == {"Key", "Values"} and target["Key"] == "InstanceIds")
                _require(type(target["Values"]) is list and bool(target["Values"])
                         and all(type(item) is str and (item == "*" or re.fullmatch(r"(?:i-[0-9a-f]{8,17}|mi-[0-9a-f]{17})", item)) for item in target["Values"]))
                matches = matches or instance in target["Values"] or "*" in target["Values"]
            if matches and command["Status"] in ("Pending", "InProgress", "Delayed", "Cancelling"):
                active[command["CommandId"]] = True
    pending = len(active)
    name = "/meet-me/production/secret/openai-api-key"
    parameters = _aws("ssm", "describe-parameters", "--parameter-filters", "Key=Name,Option=Equals,Values=" + name)
    _require(not parameters.get("NextToken"))
    entries = parameters["Parameters"]
    valid_parameter = (len(entries) == 1 and entries[0].get("Name") == name and entries[0].get("Type") == "SecureString"
                       and type(entries[0].get("Version")) is int and entries[0]["Version"] > 0)
    return states, pending, valid_parameter


def _collect_facts(instance, region, manifest):
    observed = _host_preflight(instance)
    _require(observed["flyway"]["status"] == "success")
    before, after = observed["host"]["containerBefore"], observed["host"]["containerAfter"]
    _require(before.get("present") is True and before["running"] is True)
    _require(all(before[key] == after[key] for key in ("running", "pid", "restartCount")))
    state = _host_state(instance, manifest)
    _require(state["currentImage"] == before["image"])
    states, pending, parameter_ready = _aws_facts(instance, region, manifest["imageRepository"])
    return {"history": observed["flyway"]["history"], "phase": state["phase"], "currentImage": state["currentImage"],
            "guardTrusted": state["guardTrusted"], "automaticContract": state["automaticContract"],
            "refreshRuleStates": states, "pendingCommands": pending}, parameter_ready


def _deploy_command(bucket, key, region, manifest, attestation):
    # Every interpolated token is typed/validated first; archive extraction happens only after SHA/path verification.
    source = base64.b64encode(Path(__file__).read_bytes()).decode("ascii")
    remote = """import base64,hashlib,importlib.util,json,os,pathlib,subprocess,sys,tempfile,shutil,stat
os.umask(0o077)
manifest=json.loads(base64.b64decode(sys.argv[1],validate=True))
release=pathlib.Path('/opt/meet-me/releases')/manifest['releaseId']
if release.exists() or release.is_symlink():sys.exit(2)
parent=release.parent
while True:
 metadata=parent.lstat()
 if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid!=0 or stat.S_IMODE(metadata.st_mode)&0o022:sys.exit(2)
 if parent==parent.parent:break
 parent=parent.parent
work=pathlib.Path(tempfile.mkdtemp(prefix='meet-me-read-state.',dir='/run'))
archive=work/'release.tar.gz'
try:
 result=subprocess.run(['aws','--region',sys.argv[4],'s3api','get-object','--bucket',sys.argv[2],'--key',sys.argv[3],str(archive)],capture_output=True,timeout=30)
 if result.returncode:sys.exit(2)
 archive.chmod(0o600)
 if archive.stat().st_size>4194304 or hashlib.sha256(archive.read_bytes()).hexdigest()!=manifest['archiveSha256']:sys.exit(2)
 validator=work/'archive-validator.py';validator.write_bytes(base64.b64decode(sys.argv[6],validate=True));validator.chmod(0o600)
 spec=importlib.util.spec_from_file_location('trusted_archive_validator',validator);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
 module._verify_package(archive,manifest)
 release.mkdir(mode=0o700)
 retained=release/'.approved-release.tar.gz';shutil.copyfile(archive,retained);retained.chmod(0o600)
 extracted=subprocess.run(['tar','-xzf',str(archive),'--no-same-owner','--no-same-permissions','-C',str(release)],capture_output=True,timeout=20)
 if extracted.returncode:sys.exit(2)
 # Only the archive-verified reviewed host entrypoint may perform installation/deployment.
 deployed=subprocess.run(['timeout','--signal=TERM','--kill-after=15s','1440s','python3',str(release/'pipeline/host-approved-release.py'),'--deploy',sys.argv[1],sys.argv[5]],capture_output=True,text=True,timeout=1460)
 if deployed.returncode or deployed.stderr.strip():sys.exit(2)
 sys.stdout.write(deployed.stdout)
finally:shutil.rmtree(work)
"""
    tokens = [_encoded(manifest), bucket, key, region, _encoded(attestation), source]
    return "set +x\numask 077\npython3 - " + " ".join(shlex.quote(value) for value in tokens) + " <<'MEETME_APPROVED_DEPLOY'\n" + remote + "\nMEETME_APPROVED_DEPLOY"


def _deploy_result(status, stdout, stderr):
    _require(status == "Success" and not stderr.strip() and len(stdout) <= 65536)
    result = json.loads(stdout)
    _require(type(result) is dict and set(result) == {"protocol", "mode", "sourceSha", "currentImage", "phase"})
    _require(result["protocol"] == "meet-me-approved-release-result-v1")
    _require(result["mode"] in ("AUTO_READY", "TRANSITION_APPROVED") and result["phase"] == "READY")
    _require(type(result["sourceSha"]) is str and re.fullmatch(r"[0-9a-f]{40}", result["sourceSha"]))
    return result


def main():
    if sys.argv[1:] == ["--validate"]:
        validate_workflow_event(json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text()), {
            "repository": os.environ["GITHUB_REPOSITORY"], "ref": os.environ["GITHUB_REF"],
            "eventName": os.environ["GITHUB_EVENT_NAME"], "sourceSha": os.environ["SOURCE_SHA"],
        })
        print("Trusted production workflow event validated.")
        return
    summary = {"protocol": "meet-me-approved-release-summary-v1", "status": "unavailable"}
    try:
        _require(not sys.argv[1:])
        os.umask(0o077)
        env = os.environ
        action = env["RELEASE_ACTION"]
        approve = env.get("APPROVE_FIRST_TRANSITION", "false")
        _require(action in ("preflight", "automatic", "deploy") and approve in ("true", "false"))
        event = json.loads(Path(env["GITHUB_EVENT_PATH"]).read_text())
        context = {"repository": env["GITHUB_REPOSITORY"], "ref": env["GITHUB_REF"],
                   "eventName": env["GITHUB_EVENT_NAME"], "sourceSha": env["SOURCE_SHA"]}
        validate_workflow_event(event, context)
        _require((action == "automatic") == (context["eventName"] == "workflow_run"))
        instance, region, bucket = env["RUNTIME_INSTANCE_ID"], env["AWS_REGION"], env["DEPLOYMENT_ARTIFACT_BUCKET"]
        _require(re.fullmatch(r"i-[0-9a-f]{8,17}", instance) and re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-[0-9]", region))
        _require(re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket))
        expected = {"buildRunId": env["BUILD_RUN_ID"], "buildRunAttempt": env["BUILD_RUN_ATTEMPT"], "imageRepository": env["ECR_REPOSITORY_URL"]}
        _require(all(re.fullmatch(r"[1-9][0-9]{0,19}", expected[key]) for key in ("buildRunId", "buildRunAttempt")))
        prefix = f"releases/builds/{expected['buildRunId']}/{expected['buildRunAttempt']}/"
        approved = _load("approved_release_policy", BASE / "pipeline/approved_release.py")
        with tempfile.TemporaryDirectory(prefix="meet-me-release-") as temporary:
            directory = Path(temporary)
            _download(bucket, prefix + "manifest.json", directory / "manifest.json", 65536)
            manifest = json.loads((directory / "manifest.json").read_bytes())
            validated = approved.validate_manifest(manifest, expected)
            _require(f".ecr.{region}." in manifest["imageRepository"])
            _verify_origin(manifest, action)
            if action == "automatic":
                _require(manifest["sourceSha"] == context["sourceSha"] and str(event["workflow_run"]["id"]) == manifest["origin"]["ciRunId"])
            archive = directory / "release.tar.gz"
            _download(bucket, prefix + "release.tar.gz", archive, MAX_ARCHIVE)
            _require(hashlib.sha256(archive.read_bytes()).hexdigest() == manifest["archiveSha256"])
            _verify_package(archive, manifest)
            facts, parameter_ready = _collect_facts(instance, region, manifest)
            plan = approved.validate_deploy_facts(validated, facts, action, approve == "true")
            summary.update({"action": action, "releaseId": manifest["releaseId"], "mode": plan["mode"],
                            "refreshRuleStates": facts["refreshRuleStates"], "pendingCommands": facts["pendingCommands"],
                            "parameterMetadataReady": parameter_ready})
            if action != "preflight" and plan["mode"] != "TRANSITION_REQUIRED":
                _verify_origin(manifest, action)
                fresh, parameter_ready = _collect_facts(instance, region, manifest)
                summary.update({"refreshRuleStates": fresh["refreshRuleStates"], "pendingCommands": fresh["pendingCommands"],
                                "parameterMetadataReady": parameter_ready})
                fresh_plan = approved.validate_deploy_facts(validated, fresh, action, approve == "true")
                summary["mode"] = fresh_plan["mode"]
                if fresh_plan["mode"] != "TRANSITION_REQUIRED":
                    _require(parameter_ready)
                    attestation = {"action": action, "approveFirstTransition": approve == "true", "refreshRuleStates": fresh["refreshRuleStates"],
                                   "pendingCommands": fresh["pendingCommands"], "manifestSha256": hashlib.sha256(_canonical(manifest)).hexdigest()}
                    result = _ssm(instance, _deploy_command(bucket, prefix + "release.tar.gz", region, manifest, attestation), _deploy_result, 1500)
                    _require(result["currentImage"] == validated["imageRef"] and result["sourceSha"] == manifest["sourceSha"])
                    summary["deployed"] = True
        summary["status"] = "success"
    finally:
        Path("approved-release-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("Validated release operation complete; sanitized metadata saved.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print("Release operation unavailable; immutable metadata or fresh production facts failed validation.")
        sys.exit(2)
