"""Replay independent issue154 faults in disposable deploy copies only."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
PIPELINE = "deploy/pipeline/release_pipeline.py"
DRIVER = "deploy/pipeline/run-approved-release.py"
PUBLIC = "test_issue154_public_contract.Issue154PublicContractTests."
AUDIT = "test_issue154_public_contract_audit.Issue154PublicContractAuditTests."
RAW = ROOT / ".codex/tdd-evidence"


def fingerprint(path):
    return {"path": path, "sha256": hashlib.sha256((ROOT / path).read_bytes()).hexdigest()}


def fault(name, path, before, after, test):
    return {"name": name, "path": path, "before": before, "after": after, "test": test}


FAULTS = [
    fault("wrong_public_health_url", PIPELINE,
          'opener.open("https://api.meet-me.co.kr/healthz", timeout=10)',
          'opener.open("https://api.meet-me.co.kr/actuator/health", timeout=10)',
          PUBLIC + "test_exact_public_native_projection_and_nginx_healthz_satisfy_public_shape_proof"),
    fault("accept_arbitrary_health_body", PIPELINE,
          '_require(response.read(8193) == b"ok\\n")', 'response.read(8193)',
          PUBLIC + "test_healthz_requires_exact_nginx_bytes_not_json_up_or_near_matches"),
    fault("accept_json_up_instead_of_declared_healthz", PIPELINE,
          '_require(response.read(8193) == b"ok\\n")',
          'raw = response.read(8193)\n                _require(raw == b"ok\\n" or strict_json(raw, 8192).get("status") == "UP")',
          PUBLIC + "test_healthz_requires_exact_nginx_bytes_not_json_up_or_near_matches"),
    fault("truncate_health_body_to_ok_prefix", PIPELINE,
          '_require(response.read(8193) == b"ok\\n")', '_require(response.read(3) == b"ok\\n")',
          AUDIT + "test_oversized_healthz_valid_ok_prefix_does_not_bypass_body_limit"),
    fault("bypass_health_status", PIPELINE,
          'with opener.open("https://api.meet-me.co.kr/healthz", timeout=10) as response:\n                _require(response.status == 200)',
          'with opener.open("https://api.meet-me.co.kr/healthz", timeout=10) as response:\n                pass',
          PUBLIC + "test_public_non200_or_unhealthy_status_cannot_be_verified"),
    fault("bypass_native_status", PIPELINE,
          'with opener.open("https://api.meet-me.co.kr/v3/api-docs", timeout=10) as response:\n                _require(response.status == 200)',
          'with opener.open("https://api.meet-me.co.kr/v3/api-docs", timeout=10) as response:\n                pass',
          PUBLIC + "test_public_non200_or_unhealthy_status_cannot_be_verified"),
    fault("follow_public_redirects", PIPELINE,
          'raise ValueError("UNTRUSTED_PUBLIC_CONTRACT")',
          'return super().redirect_request(*args, **kwargs)',
          AUDIT + "test_real_redirect_request_is_rejected_instead_of_followed"),
    fault("drop_native_body_limit_sentinel", PIPELINE,
          'strict_json(response.read(1048577), 1048576)', 'strict_json(response.read(1048576), 1048576)',
          AUDIT + "test_oversized_native_valid_json_prefix_does_not_bypass_body_limit"),
    fault("omit_final_host_health_requirement", PIPELINE,
          'auth_required=r["auth"], require_healthy=True)', 'auth_required=r["auth"], require_healthy=False)',
          AUDIT + "test_actual_collector_cannot_certify_unhealthy_starting_or_missing_app_health"),
    fault("omit_rule_restore_health_requirement", PIPELINE,
          'facts, ready = self._facts(require_healthy=True)', 'facts, ready = self._facts()',
          AUDIT + "test_unhealthy_app_cannot_authorize_rule_restoration_with_unchanged_runtime_and_database"),
    fault("disable_collector_health_guard", DRIVER,
          '_require(not maintenance_resume and before.get("health") == "healthy")', 'pass',
          AUDIT + "test_actual_collector_cannot_certify_unhealthy_starting_or_missing_app_health"),
    fault("substitute_latest_migration_for_full_history", PIPELINE,
          'facts["history"] == expected', 'facts["history"][-1:] == expected[-1:]',
          AUDIT + "test_healthz_and_native_success_cannot_replace_runtime_or_whole_database_history"),
    fault("omit_bound_image_check", PIPELINE,
          'facts["currentImage"] == r["validated"]["imageRef"]', 'True',
          AUDIT + "test_healthz_and_native_success_cannot_replace_runtime_or_whole_database_history"),
    fault("omit_fresh_runtime_source_check", PIPELINE,
          'record.get("sourceSha") == self.identity["sourceSha"]', 'True',
          AUDIT + "test_healthz_and_native_success_cannot_replace_runtime_or_whole_database_history"),
    fault("omit_native_projection_check", PIPELINE,
          '_require(project_public_api(spec) == expected)', 'pass',
          PUBLIC + "test_health_success_does_not_replace_missing_operation_field_or_schema"),
]


def main():
    RAW.mkdir(parents=True, exist_ok=True)
    red = json.loads((ROOT / ".tdd/deployment/issue154-public-health-contract-red.json").read_text())
    for expected in red["testFiles"]:
        assert fingerprint(expected["path"]) == expected, "Frozen test changed: renew RED first"
    sources = [fingerprint(path) for path in (PIPELINE, DRIVER)]
    rows = []
    for mutation in FAULTS:
        with tempfile.TemporaryDirectory(prefix="issue154-public-health-fi-") as temporary:
            sandbox = Path(temporary)
            shutil.copytree(ROOT / "deploy", sandbox / "deploy",
                            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".env.runtime"))
            target = sandbox / mutation["path"]
            original = target.read_text()
            assert original.count(mutation["before"]) == 1, mutation["name"]
            changed = original.replace(mutation["before"], mutation["after"], 1)
            compile(changed, str(target), "exec")
            target.write_text(changed)
            env = os.environ.copy()
            env["PYTHONPATH"] = str(sandbox / "deploy/tests")
            env["PYTHONDONTWRITEBYTECODE"] = "1"
            command = ["python3", "-B", "-m", "unittest", mutation["test"], "-v"]
            run = subprocess.run(command, cwd=sandbox, env=env,
                                 stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            log = RAW / ("issue154-public-health-fi-" + mutation["name"] + ".log")
            log.write_text(run.stdout.replace(str(sandbox), "<isolated-copy>"))
            killed = run.returncode == 1 and "FAIL:" in run.stdout and "ERROR:" not in run.stdout
            assert killed, mutation["name"] + " survived or had a setup/runtime error"
            rows.append(dict(mutation, command=command, detected=True,
                             failedAssertions=int(re.search(r"FAILED \(failures=(\d+)\)", run.stdout).group(1)),
                             rawLog=str(log.relative_to(ROOT)),
                             mutatedSha256=hashlib.sha256(changed.encode()).hexdigest()))
    assert sources == [fingerprint(path) for path in (PIPELINE, DRIVER)], "Active source changed during FI"
    for expected in red["testFiles"]:
        assert fingerprint(expected["path"]) == expected, "Active frozen test changed during FI"
    summary = {"workItem": "issue154-public-health-contract", "kind": "INDEPENDENT_ISOLATED_FAULT_INJECTION",
               "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
               "sourceRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
               "testFiles": red["testFiles"], "sourceFiles": sources, "script": fingerprint(str(Path(__file__).relative_to(ROOT))),
               "faults": rows, "detected": len(rows), "survivors": 0, "setupErrors": 0,
               "activeSourceMutated": False, "networkCalls": 0, "awsCalls": 0, "secretReads": 0}
    output = ROOT / ".tdd/verification/issue154-public-health-faults.json"
    output.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"detected": len(rows), "survivors": 0, "summary": str(output.relative_to(ROOT))}))


if __name__ == "__main__":
    main()
