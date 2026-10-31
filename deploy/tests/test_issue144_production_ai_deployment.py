"""OA01 production AI activation: immutable V1-V20 history and approved V21/V22 integration.

Only local temporary files and a fake aws executable are used; no credential,
SSM, provider, deployment, configuration activation or Gradle process is called.
Run with existing POSIX Python/Bash/jq. Values below are synthetic test data.
"""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "deploy"

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, DEPLOY / "pipeline" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()

def digest(raw):
    return hashlib.sha256(raw).hexdigest()

def configured_policy():
    profiles = {
        "PRECHECK_CLASSIFICATION": "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
        "PRECHECK_COUNT": "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
        "PRECHECK_GENERATION": "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
        "FINAL_PRIMARY": "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
        "FINAL_FALLBACK": "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
    }
    return {"protocol": "meetme-managed-ai-policy-v1", "state": "CONFIGURED",
            "unit": {"code": "SYNTHETIC_REVIEW_UNIT", "scale": 0}, "roomCap": "100",
            "minimumConfidence": 0.6, "operations": [
                {"operation": operation, "profileFingerprint": fingerprint,
                 "evidenceFingerprint": "e" * 64, "maximumAmount": "1", "validUntil": "2099-01-01T00:00:00Z"}
                for operation, fingerprint in profiles.items()]}

HISTORICAL_SHA256 = {
    "V1__create_core_domain.sql": "f840adc70a59de4f7085f419f4063c34f5ed96e87a54b2aa4f052c15df023515",
    "V2__add_anonymous_room_lifecycle.sql": "75535992de90d7eedc1700487503418a300df6ca668eb18d517646517ebd40e7",
    "V3__add_submission_gemini_pipeline.sql": "a1d7723e2461dbc6e483040f24fe9df8efc5d088b9ddc00ffaf1cdc842f12371",
    "V4__add_matching_results.sql": "14f90ca0642a4ee02242d334ebe542503907b509ee1a4fdfcc45f6c33905fe9d",
    "V5__remove_meeting_duration.sql": "7127e1ccc1284865cc3b0ea38be3582a1870f7d3ec05e3700d4577d1084c3146",
    "V6__add_reliable_outbox_processing.sql": "526c1cca68d23c1ee9fe4fad6c7cf0937eb53df5e306ee75b327223855f8af7a",
    "V7__resume_delayed_matching_from_structuring.sql": "d252715c33435f88498e8e0131ea5b35e6e081e63fdc91fab67b7f1b6235c747",
    "V8__add_input_revision_rounds.sql": "04ac022f09473e4d95128c765b2e274b7d1d1abb0fee4b722f56119638d22a92",
    "V9__add_time_recommendations.sql": "a6fc046611fb3cafa96d51f87de09687b3fdecc403988d211b7d86c4fad0585d",
    "V10__add_bounded_analysis_invocations.sql": "694940de9100b58bc4a1ca2889d5d44fbd86221dead6805082b2c7999ee002ab",
    "V11__add_accounts_and_participant_ownership.sql": "0431b2c18a5f03c341d3d4c4d1f3456e169db610cb5bd92e3681978623b3b92c",
    "V12__add_guest_reaccess.sql": "fce24fe4bb215ef2d40d0b2eddb2efd1fc98d57162681d5050236824a3cd11da",
    "V13__add_submission_clarifications.sql": "b6ea047b7aa87486941b06d5cfbd4873449821ba6d684a8f52946053aa53e3ff",
    "V14__add_operator_access_audits.sql": "cddce45ef10fc2eac99cb739a1b631f41634b2cd06905e19919692844ca40196",
    "V15__add_parser_transport_observations.sql": "fcbcd040cb232ca400cbfb24b944f8289428032daf6ce386c35dbc119f9b9f98",
    "V16__add_pre_submission_checks.sql": "65dd8b5831ced11f62bf00eb21fc5eb61bc34356c7d99da2228b8fdd80d8358e",
    "V17__add_durable_ai_room_budgets.sql": "912b8f227e6ea7c20fc05cf96d705982fb9737c5051c7c07e771494df482834b",
    "V18__align_recommendation_selection_attendance.sql": "67061520a8fb4a96412ec1b82e7b6a4d07a4ab7745187d0fc1c9c759b45eec27",
}

# Accepted review release inventory, fixed independently of production catalogs.
# V1-V20 remain immutable history; reviewed V21/V22 preserve input evidence and place uncertainty.
# See docs/verification/confirmed-review-release-20261028.md and ADR-062.
CURRENT_SUPPORTED_SHA256 = {
    **HISTORICAL_SHA256,
    "V19__add_confirmed_result_reviews.sql": "b5e55df55f97f754239653252862e6631c570f1a724b05b063d8320826d5109f",
    "V20__add_account_room_history_index.sql": "99062f8dfb8bbca178c204be4eb5a297e99868f4193b290dbb3f4261d711cc8a",
    "V21__add_submission_input_evidence.sql": "e641594b3637bb1979030da05336ae1aa3307e3d7a6f6f813fde7673bd34f3ed",
    "V22__preserve_unverified_candidate_place.sql": "2e2aa1711a33676ea41801b64f93c0ed2d946105797724a288399097ffc6fcd6",
}

AWS_FIXTURE = '''#!/usr/bin/env python3
import json,os,sys
args=sys.argv[1:]
def arg(name):return args[args.index(name)+1] if name in args else None
with open(os.environ['ISSUE144_CALLS'],'a') as stream:stream.write(json.dumps(args)+'\\n')
if args[:2]==['secretsmanager','get-secret-value']:
 print(json.dumps({'password':'synthetic-only-password'}));sys.exit(0)
if args[:2]!=['ssm','get-parameter']:sys.exit(98)
name=arg('--name');key=name.rsplit('/',1)[-1]
if key=='operations-enabled':sys.exit(9)
if key=='typesafe-api-key' and os.environ.get('ISSUE144_TYPESAFE_MISSING')=='1':sys.exit(9)
values={'precheck-provider-enabled':os.environ['ISSUE144_ENABLED'],'database-url':'jdbc:postgresql://fixture.invalid/fixture','redis-port':'6379','redis-ssl-enabled':'true'}
value=values.get(key,'synthetic-only-value')
if arg('--output')=='json':print(json.dumps({'Parameter':{'Type':'SecureString' if '/secret/' in name else 'String','Value':value}}))
else:print(value)
'''

class RendererFixture:
    def __init__(self, raw, enabled):
        self.temporary = tempfile.TemporaryDirectory(prefix="issue144-production-render-")
        self.root = Path(self.temporary.name)
        self.release = self.root / "release"
        (self.release / "scripts").mkdir(parents=True)
        (self.root / "bin").mkdir()
        shutil.copyfile(DEPLOY / "scripts/render-runtime-env.sh", self.release / "scripts/render-runtime-env.sh")
        (self.release / "runtime-provider-mode").write_text("gemini-luna-required\n")
        (self.release / "runtime-auth-mode").write_text("oauth-rs256-required\n")
        (self.release / "runtime-ai-policy.json").write_bytes(raw)
        executable = self.root / "bin/aws"
        executable.write_text(AWS_FIXTURE)
        executable.chmod(0o755)
        self.destination = self.root / ".env.runtime"
        self.destination.write_bytes(b"PREVIOUS_SYNTHETIC_ENV=preserved\n")
        self.destination.chmod(0o600)
        jq_directory = str(Path(shutil.which("jq")).parent)
        self.environment = {"PATH": str(self.root / "bin") + ":" + jq_directory + ":/usr/bin:/bin", "AWS_REGION": "synthetic-region",
                            "MEETME_PARAMETER_ROOT": "/synthetic/issue144-production", "ISSUE144_CALLS": str(self.root / "calls.jsonl"),
                            "ISSUE144_ENABLED": "true" if enabled else "false"}

    def render(self):
        return subprocess.run(["bash", str(self.release / "scripts/render-runtime-env.sh"), str(self.destination)],
                              env=self.environment, capture_output=True, text=True, timeout=10)

    def close(self):
        self.temporary.cleanup()


PUBLISHER = load("issue144_production_publisher", "publish_manifest.py")
DRIVER = load("issue144_production_driver", "run-approved-release.py")
SENTINEL = {"protocol": "meetme-managed-ai-policy-v1", "state": "UNCONFIGURED"}
FEATURE_FLAGS = ("MEETME_PRECHECK_PROVIDER_ENABLED", "MEETME_PRECHECK_DECISIONROUTING_ENABLED",
                 "MEETME_PRECHECK_CLASSIFIER_ENABLED")
BUDGET_FLAG = "MEETME_AI_BUDGET_ENABLED"
THRESHOLD = "MEETME_PRECHECK_CLASSIFIER_MINIMUMCONFIDENCE"


def observed(fixture):
    return dict(line.split("=", 1) for line in fixture.destination.read_text().splitlines())


def typesafe_calls(fixture):
    calls = fixture.root / "calls.jsonl"
    return [row for row in map(json.loads, calls.read_text().splitlines())
            if "/synthetic/issue144-production/secret/typesafe-api-key" in row]


class Issue144ProductionRendererTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        for executable in ("bash", "python3", "jq"):
            if shutil.which(executable) is None:
                raise RuntimeError("Missing existing " + executable + "; dependency failure is invalid RED")

    def assert_quiet(self, result):
        self.assertEqual("", result.stdout)
        self.assertNotIn("synthetic-only-value", result.stderr)
        self.assertNotIn("synthetic-only-password", result.stderr)

    def assert_preserved(self, fixture, result):
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(b"PREVIOUS_SYNTHETIC_ENV=preserved\n", fixture.destination.read_bytes())
        self.assertEqual(0o600, fixture.destination.stat().st_mode & 0o777)
        self.assert_quiet(result)

    def test_OA01_unconfigured_on_enables_three_features_without_money_or_threshold(self):
        raw = canonical(SENTINEL)
        fixture = RendererFixture(raw, True)
        try:
            result = fixture.render()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assert_quiet(result)
            values = observed(fixture)
            self.assertEqual(["true"] * 3, [values[key] for key in FEATURE_FLAGS])
            self.assertEqual("false", values[BUDGET_FLAG])
            self.assertNotIn(THRESHOLD, values)
            self.assertEqual("synthetic-only-value", values["TYPESAFE_API_KEY"])
            self.assertEqual(1, len(typesafe_calls(fixture)))
            self.assertEqual(digest(raw), values["MEETME_AI_BUDGET_POLICYSHA256"])
            self.assertEqual("/run/meetme-ai/runtime-ai-policy.json", values["MEETME_AI_BUDGET_POLICYPATH"])
            self.assertEqual(raw, (fixture.release / "runtime-ai-policy.json").read_bytes())
            self.assertEqual(0o600, fixture.destination.stat().st_mode & 0o777)
        finally:
            fixture.close()

    def test_OA01_off_preserves_all_four_off_and_no_typesafe_or_threshold(self):
        for policy in (SENTINEL, configured_policy()):
            with self.subTest(state=policy["state"]):
                fixture = RendererFixture(canonical(policy), False)
                try:
                    result = fixture.render()
                    self.assertEqual(0, result.returncode, result.stderr)
                    values = observed(fixture)
                    self.assertEqual(["false"] * 4, [values[key] for key in FEATURE_FLAGS + (BUDGET_FLAG,)])
                    self.assertNotIn(THRESHOLD, values)
                    self.assertNotIn("TYPESAFE_API_KEY", values)
                    self.assertEqual([], typesafe_calls(fixture))
                finally:
                    fixture.close()

    def test_OA01_configured_on_preserves_four_on_exact_reviewed_threshold_and_typesafe(self):
        policy = configured_policy()
        policy["minimumConfidence"] = 0.73
        fixture = RendererFixture(canonical(policy), True)
        try:
            result = fixture.render()
            self.assertEqual(0, result.returncode, result.stderr)
            values = observed(fixture)
            self.assertEqual(["true"] * 4, [values[key] for key in FEATURE_FLAGS + (BUDGET_FLAG,)])
            self.assertEqual("0.73", values[THRESHOLD])
            self.assertEqual(1, len(typesafe_calls(fixture)))
            self.assert_quiet(result)
        finally:
            fixture.close()

    def test_OA01_configured_on_still_rejects_expired_financial_evidence(self):
        policy = configured_policy()
        for operation in policy["operations"]:
            operation["validUntil"] = "2000-01-01T00:00:00Z"
        fixture = RendererFixture(canonical(policy), True)
        try:
            self.assert_preserved(fixture, fixture.render())
            self.assertEqual([], typesafe_calls(fixture))
        finally:
            fixture.close()

    def test_OA01_unconfigured_on_still_requires_the_existing_typesafe_credential(self):
        fixture = RendererFixture(canonical(SENTINEL), True)
        fixture.environment["ISSUE144_TYPESAFE_MISSING"] = "1"
        try:
            result = fixture.render()
            self.assert_preserved(fixture, result)
            self.assertIn("authentication runtime parameter", result.stderr)
            self.assertEqual(1, len(typesafe_calls(fixture)))
        finally:
            fixture.close()

    def test_OA01_malformed_policy_is_rejected_before_typesafe_lookup(self):
        for raw in (b"[]", canonical(SENTINEL | {"minimumConfidence": 0.5}),
                    b'{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED","state":"UNCONFIGURED"}'):
            with self.subTest(raw=raw):
                fixture = RendererFixture(raw, True)
                try:
                    self.assert_preserved(fixture, fixture.render())
                    self.assertEqual([], typesafe_calls(fixture))
                finally:
                    fixture.close()


class Issue144ProductionPublicationTests(unittest.TestCase):
    def test_OA01_artifact_remains_unconfigured_and_current_v22_preserves_v18_history(self):
        self.assertEqual(SENTINEL, json.loads((DEPLOY / "runtime-ai-policy.json").read_bytes()))
        directory = ROOT / "src/main/resources/db/migration"
        self.assertEqual(set(CURRENT_SUPPORTED_SHA256), {p.name for p in directory.iterdir()})
        self.assertEqual(18, len(HISTORICAL_SHA256))
        self.assertEqual(22, len(CURRENT_SUPPORTED_SHA256))
        for name, fingerprint in CURRENT_SUPPORTED_SHA256.items():
            with self.subTest(script=name):
                self.assertEqual(fingerprint, digest((directory / name).read_bytes().replace(b"\r\n", b"\n")))

    def test_OA01_real_publisher_and_package_consumer_preserve_both_policies_with_exact_v22(self):
        environment = {"SOURCE_SHA": "a" * 40, "BUILD_RUN_ID": "144", "BUILD_RUN_ATTEMPT": "1",
                       "ECR_REPOSITORY_URL": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/meet-me/server",
                       "IMAGE_DIGEST": "sha256:" + "b" * 64, "CI_RUN_ID": "143", "CI_SHA": "a" * 40}
        for policy in (SENTINEL, configured_policy()):
            with self.subTest(state=policy["state"]), tempfile.TemporaryDirectory(prefix="issue144-production-publish-") as temporary:
                root = Path(temporary)
                shutil.copytree(DEPLOY, root / "deploy", ignore=shutil.ignore_patterns("tests", "compiled", "__pycache__"))
                shutil.copytree(ROOT / "src/main/resources/db/migration", root / "src/main/resources/db/migration")
                # This synthetic release uses the canonical Git/LF marker, independent
                # of Windows core.autocrlf conversion in the source checkout.
                (root / "deploy/runtime-auth-mode").write_bytes(b"oauth-rs256-required\n")
                raw = canonical(policy)
                (root / "deploy/runtime-ai-policy.json").write_bytes(raw)
                archive = root / "release.tar.gz"
                files = {name: (root / "deploy" / name).read_bytes() for name in DRIVER.CONTRACT_FILES}
                files.update({"runtime-provider-mode": b"gemini-luna-required\n",
                              "public-api-contract.json": (root / "deploy/public-api-contract.json").read_bytes(),
                              "preflight/compiled/ReadOnlyFlywayProbe.class": bytes.fromhex("cafebabe")})
                for helper in ("scripts/render-runtime-env.sh", "scripts/export-certificate.sh"):
                    files[helper] = (root / "deploy" / helper).read_bytes()
                for name in CURRENT_SUPPORTED_SHA256:
                    files["migrations/" + name] = (root / "src/main/resources/db/migration" / name).read_bytes()
                def pack(contents):
                    with tarfile.open(archive, "w:gz") as packed:
                        for name, body in contents.items():
                            item = tarfile.TarInfo(name)
                            item.size = len(body)
                            item.mode = 0o755 if name in ("scripts/render-runtime-env.sh", "scripts/export-certificate.sh") else 0o644
                            packed.addfile(item, io.BytesIO(body))

                pack(files)
                try:
                    artifact = PUBLISHER.build_manifest(root, archive, environment)
                except ValueError as error:
                    self.fail("Approved exact V22 publication must accept either policy: " + str(error))
                self.assertEqual(list(CURRENT_SUPPORTED_SHA256), [row["script"] for row in artifact["migrations"]])
                self.assertEqual([str(n) for n in range(1, 23)], [row["version"] for row in artifact["migrations"]])
                expected_catalog = []
                for row, (name, fingerprint) in zip(artifact["migrations"], CURRENT_SUPPORTED_SHA256.items()):
                    body = files["migrations/" + name]
                    self.assertEqual(fingerprint, digest(body.replace(b"\r\n", b"\n")))
                    checksum = zlib.crc32("".join(body.decode("utf-8-sig").splitlines()).encode("utf-8"))
                    self.assertEqual(checksum if checksum < 2 ** 31 else checksum - 2 ** 32, row["checksum"])
                    expected_catalog.append({"version": row["version"], "script": name, "sha256": digest(body)})
                self.assertEqual(digest(canonical(expected_catalog)), artifact["schemaFingerprint"])
                self.assertTrue(DRIVER._verify_package(archive, artifact))
                self.assertEqual(raw, files["runtime-ai-policy.json"])

                directory = root / "src/main/resources/db/migration"
                v19 = "V19__add_confirmed_result_reviews.sql"
                for fault in ("missing19", "unknown20", "renamed19"):
                    with self.subTest(catalog_fault=fault):
                        source = directory / v19
                        original = source.read_bytes()
                        extra = directory / ("V20__unknown.sql" if fault == "unknown20" else "V19__unknown.sql")
                        try:
                            if fault != "unknown20":
                                source.unlink()
                            if fault != "missing19":
                                extra.write_bytes(original)
                            with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE$"):
                                PUBLISHER.build_manifest(root, archive, environment)
                        finally:
                            source.write_bytes(original)
                            if extra.exists():
                                extra.unlink()

                for fault in ("missing19", "tampered19"):
                    with self.subTest(package_fault=fault):
                        changed = dict(files)
                        entry = "migrations/" + v19
                        if fault == "missing19":
                            del changed[entry]
                        else:
                            changed[entry] += b"\n-- synthetic package tamper\n"
                        try:
                            pack(changed)
                            with self.assertRaisesRegex(ValueError, "^UNTRUSTED_RELEASE_STATE$"):
                                DRIVER._verify_package(archive, artifact)
                        finally:
                            pack(files)
                self.assertTrue(DRIVER._verify_package(archive, artifact))


if __name__ == "__main__":
    unittest.main()
