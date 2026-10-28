"""Independent Nginx readiness/native API audit with separate runtime/DB proof."""
import copy
import json
import tempfile
import unittest
from unittest.mock import patch

from test_issue154_public_contract import PIPELINE, HEALTH, NATIVE, Opener, Response, contract, spec
import test_issue154_adapter as host_fixtures
import test_issue131_runtime_readiness as runtime_fixtures
from test_issue154_completion import evidence


class Issue154PublicContractAuditTests(unittest.TestCase):
    def verify(self, opener):
        with patch.object(PIPELINE.urllib.request, "build_opener", return_value=opener), patch.object(PIPELINE.time, "sleep"):
            return PIPELINE.verify_public_contract(contract())

    def test_oversized_native_valid_json_prefix_does_not_bypass_body_limit(self):
        raw = json.dumps(spec()).encode() + b" " * (1048576 + 1)
        self.assertIs(self.verify(Opener(native=[Response(raw)])), False)

    def test_oversized_health_valid_json_prefix_does_not_bypass_body_limit(self):
        raw = b'{"status":"UP"}' + b" " * 8193
        self.assertIs(self.verify(Opener(health=[Response(raw)])), False)

    def test_oversized_healthz_valid_ok_prefix_does_not_bypass_body_limit(self):
        response = Response(b"ok\n" + b" " * 8193)
        self.assertIs(self.verify(Opener(health=[response])), False)
        self.assertEqual([8193] * 3, response.read_limits)

    def test_healthz_reads_remain_bounded_for_very_large_response(self):
        response = Response(b"ok\n" + b"x" * (1024 * 1024))
        self.assertIs(self.verify(Opener(health=[response])), False)
        self.assertEqual([8193] * 3, response.read_limits)

    def test_real_redirect_request_is_rejected_instead_of_followed(self):
        handlers = []

        def build(*args):
            handlers.extend(args)
            return Opener()

        with patch.object(PIPELINE.urllib.request, "build_opener", side_effect=build), patch.object(PIPELINE.time, "sleep"):
            self.assertIs(PIPELINE.verify_public_contract(contract()), True)
        handler = handlers[0]() if type(handlers[0]) is type else handlers[0]
        request = PIPELINE.urllib.request.Request(HEALTH)
        with self.assertRaises(ValueError):
            handler.redirect_request(request, None, 302, "redirect", {}, "https://untrusted.example/")

    def test_unexpected_extra_operations_are_not_the_bound_candidate_contract(self):
        changed = spec()
        changed["paths"]["/api/unexpected"] = {"get": {"operationId": "unexpectedOperation"}}
        self.assertIs(self.verify(Opener(native=[Response(changed)])), False)

    def test_operation_ids_are_unique_across_different_routes(self):
        candidate = contract()
        candidate["operations"][1]["operationId"] = candidate["operations"][0]["operationId"]
        with self.assertRaises(ValueError):
            PIPELINE.validate_public_contract(candidate)

    def host_fixture(self, directory):
        driver = host_fixtures.FakeAwsDriver()
        release = host_fixtures.release(driver, directory)
        # Three rows distinguish exact whole history from latest-only proof.
        release["manifest"]["migrations"] = [
            {"version": str(version), "script": f"V{version}__synthetic.sql", "checksum": version * 10}
            for version in (17, 18, 19)
        ]
        driver.facts["history"] = [dict(row, success=True) for row in release["manifest"]["migrations"]]
        driver.facts["automaticContract"]["history"] = copy.deepcopy(driver.facts["history"])
        release["facts"] = copy.deepcopy(driver.facts)
        return driver, release, PIPELINE.AwsReleaseAdapter(release, "deploy", approved=True)

    def completion(self, release, adapter):
        observed = evidence()
        observed["host"] = adapter.host_proof
        observed["publicContractVerified"] = self.verify(Opener())
        self.assertIs(observed["publicContractVerified"], True)
        return PIPELINE._module("release_completion").evaluate_completion(release["identity"], observed)

    def test_healthz_and_native_success_still_require_independent_host_parameter_readiness(self):
        with tempfile.TemporaryDirectory(prefix="issue154-healthz-audit-") as directory:
            driver, release, adapter = self.host_fixture(directory)
            payload = {"sourceSha": release["identity"]["sourceSha"], "phase": "READY"}
            self.assertIs(adapter.verify_host(payload), True)
            self.assertIs(self.completion(release, adapter)["deploymentComplete"], True)
            driver.ready = False
            self.assertIs(adapter.verify_host(payload), False)
            result = self.completion(release, adapter)
            self.assertIs(result["hostVerified"], False)
            self.assertIs(result["deploymentComplete"], False)
            with self.assertRaises(ValueError):
                PIPELINE._module("release_completion").require_complete(result, release["identity"])

    def test_actual_collector_cannot_certify_unhealthy_starting_or_missing_app_health(self):
        with tempfile.TemporaryDirectory(prefix="issue154-app-health-audit-") as directory:
            _, release, _ = self.host_fixture(directory)
            driver = runtime_fixtures.DRIVER
            release["driver"] = driver
            state = {key: release["facts"][key] for key in
                     ("currentImage", "phase", "guardTrusted", "automaticContract")}
            payload = {"sourceSha": release["identity"]["sourceSha"], "phase": "READY"}
            for health in ("healthy", "unhealthy", "starting", "", None):
                observed = runtime_fixtures.host_observation()
                observed["host"]["containerBefore"]["image"] = state["currentImage"]
                observed["flyway"]["history"] = copy.deepcopy(release["facts"]["history"])
                if health is None:
                    observed["host"]["containerBefore"].pop("health")
                else:
                    observed["host"]["containerBefore"]["health"] = health
                adapter = PIPELINE.AwsReleaseAdapter(release, "deploy", approved=True)
                with self.subTest(app_health=health), \
                     patch.object(driver, "_host_preflight", return_value=observed), \
                     patch.object(driver, "_host_state", return_value=state), \
                     patch.object(driver, "_aws_facts", return_value=(["ENABLED", "ENABLED"], 0, True)):
                    if health == "healthy":
                        self.assertIs(adapter.verify_host(payload), True)
                        continue
                    try:
                        verified = adapter.verify_host(payload)
                    except ValueError:
                        verified = False
                    self.assertIs(verified, False, "Running app is insufficient without internal healthcheck success")
                    self.assertIsNone(adapter.host_proof)

    def test_unhealthy_app_cannot_authorize_rule_restoration_with_unchanged_runtime_and_database(self):
        with tempfile.TemporaryDirectory(prefix="issue154-restore-health-audit-") as directory:
            _, release, _ = self.host_fixture(directory)
            driver = runtime_fixtures.DRIVER
            release["driver"] = driver
            state = {key: release["facts"][key] for key in
                     ("currentImage", "phase", "guardTrusted", "automaticContract")}
            for health in ("healthy", "unhealthy", "starting", "", None):
                observed = runtime_fixtures.host_observation()
                observed["host"]["containerBefore"]["image"] = state["currentImage"]
                observed["flyway"]["history"] = copy.deepcopy(release["facts"]["history"])
                if health is None:
                    observed["host"]["containerBefore"].pop("health")
                else:
                    observed["host"]["containerBefore"]["health"] = health
                adapter = PIPELINE.AwsReleaseAdapter(release, "deploy", approved=True)
                with self.subTest(app_health=health), \
                     patch.object(driver, "_host_preflight", return_value=observed), \
                     patch.object(driver, "_host_state", return_value=state), \
                     patch.object(driver, "_aws_facts", return_value=(["ENABLED", "ENABLED"], 0, True)), \
                     patch.object(adapter, "active_commands", return_value=0):
                    if health == "healthy":
                        self.assertIs(adapter.safe_to_restore(), True)
                        continue
                    try:
                        safe = adapter.safe_to_restore()
                    except ValueError:
                        safe = False
                    self.assertIs(safe, False, "Rule restoration requires fresh internal app health")

    def test_healthz_and_native_success_cannot_replace_runtime_or_whole_database_history(self):
        with tempfile.TemporaryDirectory(prefix="issue154-healthz-audit-") as directory:
            driver, release, adapter = self.host_fixture(directory)
            original = copy.deepcopy(driver.facts)
            histories = []
            histories.append(original["history"][-1:])  # Latest version alone is insufficient.
            histories.append(original["history"][1:])  # Missing older migration.
            histories.append(original["history"] + [{"version": "20", "script": "V20__extra.sql", "checksum": 200, "success": True}])
            histories.append(list(reversed(original["history"])))
            for field, value in (("checksum", 999), ("script", "V17__wrong.sql"), ("success", False)):
                changed = copy.deepcopy(original["history"])
                changed[0][field] = value
                histories.append(changed)
            mutations = [{"history": history} for history in histories] + [
                {"currentImage": release["manifest"]["imageRepository"] + "@sha256:" + "f" * 64},
                {"guardTrusted": False}, {"phase": "V8_STARTED"},
                {"automaticContract": original["automaticContract"] | {"sourceSha": "f" * 40}},
            ]
            payload = {"sourceSha": release["identity"]["sourceSha"], "phase": "READY"}
            for mutation in mutations:
                with self.subTest(mutation=mutation):
                    driver.facts = copy.deepcopy(original)
                    self.assertIs(adapter.verify_host(payload), True)
                    driver.facts.update(mutation)
                    self.assertIs(adapter.verify_host(payload), False)
                    result = self.completion(release, adapter)
                    self.assertIs(result["publicContractVerified"], True)
                    self.assertIs(result["hostVerified"], False)
                    self.assertIs(result["deploymentComplete"], False)
                    with self.assertRaises(ValueError):
                        PIPELINE._module("release_completion").require_complete(result, release["identity"])


if __name__ == "__main__":
    unittest.main()

