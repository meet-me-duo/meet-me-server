"""Independent public reporting contract: disposable synthetic artifacts; no JDK/network/keys."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import types
import unittest
from unittest import mock

LAUNCHER_PATH = Path(__file__).with_name("run_all_synthetic_tests_observed.py")
SPEC = importlib.util.spec_from_file_location("issue130_observed_launcher", LAUNCHER_PATH)
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)
# Frozen from original PUBLIC literal registrations plus the two fixed public loops.
JAVA_IDS = ('N01_local_TLS_proxy_actual_production_classifier_control', 'P01_exact_production_serializer_and_whitespace', 'P02_unicode_500_codepoints', 'P03_reject_501', 'P04_reject_NUL', 'P05_reject_extra_gold_field', 'P06_reject_missing_locale', 'P07_reject_wrong_type', 'P08_reject_invalid_context', 'P09_existing_output_unchanged', 'P10_symlink_output_unchanged', 'P11_symlink_ancestor_rejected', 'P12_duplicate_input_key_rejected', 'P13_prepare_index_preserves_16_order', 'P14_prepare_index_duplicate_ID_rejected', 'P17_reverse_search_dates_rejected', 'P18_whole_wire_16KiB_includes_context', 'P15_actual_CLI_prepare_stdout_private', 'P16_actual_CLI_failure_no_raw_path_stack', 'R01_CLEAR16_real_canonical_transport', 'R02_review_routes_B_probe_no_real_B', 'R03_low_confidence_raw_CLEAR_is_normalized_hold', 'R04_model_uncertain_routes_B_probe', 'R05_refusal_safe_STOP', 'R06_missing_usage_preserved_unknown_STOP', 'R07_partial_usage_keeps_known_input', 'R08_over_proxy_stops_next', 'R09_output2049_not_silently_zeroed', 'R10_malformed_JSON_STOP', 'R11_wrong_model_STOP', 'R12_response_cap_16384', 'L01_missing_root_seal_outbound0', 'L02_wrong_root_seal_outbound0', 'L03_missing_key_outbound0', 'L04_mutated_frozen_wire_outbound0', 'L05_rehashed_mutated_prompt_outbound0', 'L06_duplicate_case_identity_outbound0', 'L07_case_count_not16_outbound0', 'L08_source_manifest_tamper_outbound0', 'L09_runtime_hash_mismatch_outbound0', 'L10_missing_cost_outbound0', 'L11_Decimal_ceiling_outbound0', 'L12_unknown_cost_profile_outbound0', 'L13_synthetic_hardcap_claim_rejected', 'L14_repeat_same_suite_zero_extra_calls', 'L15_concurrent_same_suite_atmost16_serial', 'L16_expired_suite_before_emission_outbound0', 'L17_slow_body_uses_remaining_case_budget', 'L18_backwards_wallclock_no_extra_budget', 'L19_response_persistence_failure_stops_before_next', 'L21_alternate_output_directory_rejected', 'L22_output_symlink_ancestor_rejected', 'L23_missing_remaining_budget_rejected', 'L24_zero_remaining_budget_rejected', 'L25_extended_remaining_budget_rejected', 'L26_late_CLEAR_after_case_deadline_rejected', 'L27_fresh_preopen_fence_after_validation', 'L28_index_gold_field_rejected', 'L29_required_production_source_pin_missing', 'L30_wire_byte_count_mismatch_outbound0', 'L20_valid_route_disagreement_continues_all16_no_gold', 'R_HTTP_401_one_attempt_no_followup', 'R_HTTP_402_one_attempt_no_followup', 'R_HTTP_407_one_attempt_no_followup', 'R_HTTP_429_one_attempt_no_followup', 'R_HTTP_503_one_attempt_no_followup', 'R_HTTP_302_one_attempt_no_followup', 'R_HTTP_307_one_attempt_no_followup', 'L_COST_null_inputUsdPerMillion_outbound0', 'L_COST_null_inputTokenProxy_outbound0', 'L_COST_null_margin_outbound0', 'L_COST_null_perCallReservedUsd_outbound0', 'L_COST_null_priorHeldUsd_outbound0', 'L_COST_null_maximumNewReservedUsd_outbound0', 'L_COST_null_maximumCumulativeReservedUsd_outbound0', 'L_COST_null_approvedAggregateUsd_outbound0', 'L_COST_null_review_outbound0')
# Frozen from original PUBLIC RunnerContract.test_CLI method definitions.
PYTHON_IDS = ('test_CLI01_validate_valid_synthetic_plan', 'test_CLI02_default_dry_run_no_java_key_or_consumption', 'test_CLI03_explicit_execution_without_root_seal_rejected', 'test_CLI04_missing_cost_rejected', 'test_CLI05_null_cost_rejected', 'test_CLI06_inexact_budget_amount_rejected', 'test_CLI07_over_approved_ceiling_rejected', 'test_CLI08_missing_cost_evidence_rejected', 'test_CLI09_wrong_cost_evidence_hash_rejected', 'test_CLI10_changed_source_manifest_rejected', 'test_CLI11_changed_runtime_helper_hash_rejected', 'test_CLI12_changed_index_hash_rejected', 'test_CLI13_wrong_output_binding_rejected', 'test_CLI14_output_symlink_rejected', 'test_CLI15_canonical_endpoint_override_rejected', 'test_CLI16_fixed_threshold_override_rejected', 'test_CLI17_larger_case_limit_rejected', 'test_CLI18_retry_redirect_flags_rejected', 'test_CLI20_freeze_validation_counts_in_whole300', 'test_CLI21_compilation_counts_before_java_spawn', 'test_CLI22_late_child_success_never_accepted_after300', 'test_CLI23_child_timeout_no_second_java_or_retry', 'test_CLI24_startup_crosses_deadline_kills_created_child', 'test_CLI19_malformed_plan_is_bounded_safe_failure')
PRIVATE = "SYNTHETIC_PRIVATE_KEY_PROXY_BODY_PATH_TRACE_MUST_NOT_APPEAR"
JID = "N01_local_TLS_proxy_actual_production_classifier_control"
PID = "test_CLI21_compilation_counts_before_java_spawn"


def unknown():
    return {"stage": "UNKNOWN", "productionCompileExitCode": None,
            "evaluationCompileExitCode": None, "javaExitCode": None,
            "pythonExitCode": None, "javaSummary": None,
            "javaFailures": [], "pythonFailures": []}


class BaseFailureReportingContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="issue130-observation-synthetic-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.base = self.root / "base"
        self.base.mkdir()

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value))
        return path

    def receipt(self, java=1, python=0, source=True, **overrides):
        value = {"status": "FAILED", "productionCompileExitCode": 0,
                 "evaluationCompileExitCode": 0, "javaExitCode": java,
                 "pythonExitCode": python, "sourceBeforeAfterEqual": source,
                 "javaSummary": {"tests": 77, "passed": 77 if java == 0 else 76,
                                 "assertionFailures": 0 if java == 0 else 1, "errors": 0, "private": PRIVATE}, "evidence": PRIVATE}
        value.update(overrides)
        self.write("base/receipt.json", value)
        return value

    def project(self):
        try:
            value = LAUNCHER.safe_base_failure(self.root)
        except Exception:
            self.fail("The reporting contract must return a bounded safe projection")
        self.assertIsInstance(value, dict)
        self.assertEqual(set(unknown()), set(value))
        text = json.dumps(value)
        for forbidden in (PRIVATE, str(self.root), "Traceback", "https://private.invalid"):
            self.assertNotIn(forbidden, text)
        return value

    def test_BF01_java_failure_exact_safe_projection(self):
        self.receipt()
        self.write("base/java-tests.jsonl", json.dumps({"test": JID, "status": "ASSERTION_FAILURE", "reason": PRIVATE, "body": PRIVATE}) + "\n")
        expected = unknown()
        expected.update(stage="JAVA_TESTS", productionCompileExitCode=0,
                        evaluationCompileExitCode=0, javaExitCode=1, pythonExitCode=0,
                        javaSummary={"tests": 77, "passed": 76, "assertionFailures": 1, "errors": 0},
                        javaFailures=[{"test": JID, "status": "ASSERTION_FAILURE", "type": "AssertionError"}])
        self.assertEqual(expected, self.project())

    def test_BF02_python_failure_distinguished_from_java(self):
        self.receipt(java=0, python=1, javaSummary={"tests": 77, "passed": 77, "assertionFailures": 0, "errors": 0})
        self.write("base/python-tests.log", f"FAIL: {PID} (__main__.RunnerContract.{PID})\n{PRIVATE}\n")
        value = self.project()
        self.assertEqual("PYTHON_TESTS", value["stage"])
        self.assertEqual([{"test": PID, "status": "FAIL", "type": "AssertionError"}], value["pythonFailures"])
        self.assertEqual([], value["javaFailures"])

    def test_BF03_combined_failure_keeps_both_fixed_identities(self):
        self.receipt(java=1, python=1)
        self.write("base/java-tests.jsonl", json.dumps({"test": JID, "status": "SETUP_OR_UNEXPECTED_ERROR", "type": "SSLHandshakeException", "reason": PRIVATE}) + "\n")
        self.write("base/python-tests.log", f"ERROR: {PID} (__main__.RunnerContract.{PID})\n{PRIVATE}\n")
        value = self.project()
        self.assertEqual("JAVA_AND_PYTHON_TESTS", value["stage"])
        self.assertEqual("SSLHandshakeException", value["javaFailures"][0]["type"])
        self.assertEqual([{"test": PID, "status": "ERROR", "type": "UNEXPECTED_ERROR"}], value["pythonFailures"])

    def test_BF04_production_compile_failure_has_no_test_diagnosis(self):
        self.write("base.log", json.dumps({"status": "SETUP_FAILURE", "stage": "production compile", "exitCode": 2, "evidence": PRIVATE}) + "\n")
        expected = unknown(); expected.update(stage="PRODUCTION_COMPILE", productionCompileExitCode=2)
        self.assertEqual(expected, self.project())

    def test_BF05_evaluation_compile_failure_is_separate(self):
        self.write("base.log", json.dumps({"status": "SETUP_FAILURE", "stage": "evaluation compile", "exitCode": 1, "evidence": PRIVATE}) + "\n")
        expected = unknown(); expected.update(stage="EVALUATION_COMPILE", evaluationCompileExitCode=1)
        self.assertEqual(expected, self.project())

    def test_BF06_source_drift_not_misreported_as_assertion(self):
        self.receipt(source=False)
        self.assertEqual("SOURCE_DRIFT", self.project()["stage"])

    def test_BF07_unlisted_java_id_status_and_pass_are_omitted(self):
        self.receipt()
        rows = [{"test": PRIVATE, "status": "ASSERTION_FAILURE"},
                {"test": JID, "status": "PASS"}, {"test": JID, "status": PRIVATE}]
        self.write("base/java-tests.jsonl", "\n".join(map(json.dumps, rows)))
        self.assertEqual([], self.project()["javaFailures"])

    def test_BF08_unknown_java_error_type_becomes_fixed_generic(self):
        self.receipt()
        self.write("base/java-tests.jsonl", json.dumps({"test": JID, "status": "SETUP_OR_UNEXPECTED_ERROR", "type": PRIVATE, "trace": PRIVATE}))
        self.assertEqual([{"test": JID, "status": "SETUP_OR_UNEXPECTED_ERROR", "type": "UNEXPECTED_ERROR"}], self.project()["javaFailures"])

    def test_BF09_python_private_headers_and_traceback_never_escape(self):
        self.receipt(java=0, python=1)
        self.write("base/python-tests.log", f"FAIL: {PRIVATE} (private.Class)\nTraceback {PRIVATE}\nhttps://private.invalid\nFAIL: {PID} (__main__.RunnerContract.{PID})\n{PRIVATE}\n")
        self.assertEqual([{"test": PID, "status": "FAIL", "type": "AssertionError"}], self.project()["pythonFailures"])

    def test_BF10_missing_observations_return_fixed_unknown(self):
        self.assertEqual(unknown(), self.project())

    def test_BF11_malformed_duplicate_wrongshape_receipt_return_unknown(self):
        for raw in ("{" + PRIVATE, '{"status":"FAILED","status":"PASS"}', "[]", "null"):
            with self.subTest(kind=raw[:1]):
                self.write("base/receipt.json", raw)
                self.assertEqual(unknown(), self.project())

    def test_BF12_oversized_and_invalid_utf8_receipt_return_unknown(self):
        for raw in (b"x" * (1048576 + 1), b"\xff\xfe"):
            self.write("base/receipt.json", "").write_bytes(raw)
            self.assertEqual(unknown(), self.project())

    def test_BF13_symlink_artifact_and_parent_are_not_read(self):
        outside = self.write("outside.json", {"status": "FAILED", "evidence": PRIVATE})
        (self.base / "receipt.json").symlink_to(outside)
        self.assertEqual(unknown(), self.project())
        (self.base / "receipt.json").unlink(); self.base.rmdir()
        other = self.root / "other"; other.mkdir(); (other / "receipt.json").write_text(PRIVATE)
        self.base.symlink_to(other, target_is_directory=True)
        self.assertEqual(unknown(), self.project())

    def test_BF14_nonregular_artifact_not_opened(self):
        (self.base / "receipt.json").mkdir()
        self.assertEqual(unknown(), self.project())

    def test_BF15_invalid_or_incoherent_summary_returns_unknown(self):
        for bad in ({"tests": True, "passed": 76, "assertionFailures": 1, "errors": 0},
                    {"tests": 78, "passed": 77, "assertionFailures": 1, "errors": 0},
                    {"tests": 77, "passed": 77, "assertionFailures": 1, "errors": 0},
                    {"tests": 77, "passed": "76", "assertionFailures": 1, "errors": 0}):
            self.receipt(javaSummary=bad)
            self.assertEqual(unknown(), self.project())

    def test_BF16_invalid_exit_codes_return_unknown(self):
        for bad in (True, "1", 256, -256, None):
            self.receipt(java=bad)
            self.assertEqual(unknown(), self.project())

    def test_BF17_inconsistent_success_receipt_not_failed_test_diagnosis(self):
        self.receipt(status="PASS")
        self.assertEqual(unknown(), self.project())

    def test_BF18_deduplicates_only_known_fixed_failure_records(self):
        self.receipt()
        row = json.dumps({"test": JID, "status": "ASSERTION_FAILURE", "reason": PRIVATE})
        self.write("base/java-tests.jsonl", row + "\n" + row + "\n")
        self.assertEqual([{"test": JID, "status": "ASSERTION_FAILURE", "type": "AssertionError"}], self.project()["javaFailures"])

    def test_BF19_original_public_id_allowlist_covers_static_and_loop_cases(self):
        self.assertEqual(77, len(JAVA_IDS)); self.assertEqual(24, len(PYTHON_IDS))
        self.receipt(java=1, python=1)
        self.write("base/java-tests.jsonl", "\n".join(json.dumps({"test": name, "status": "ASSERTION_FAILURE"}) for name in JAVA_IDS))
        self.write("base/python-tests.log", "\n".join(f"FAIL: {name} (__main__.RunnerContract.{name})" for name in PYTHON_IDS))
        value = self.project()
        self.assertEqual(set(JAVA_IDS), {x["test"] for x in value["javaFailures"]})
        self.assertEqual(set(PYTHON_IDS), {x["test"] for x in value["pythonFailures"]})

    def main_observation(self, child_code):
        output = self.root / "observed"
        calls = []
        file_modes = []
        def child(command, **kwargs):
            calls.append(command)
            self.assertIn("run_all_synthetic_tests.py", str(command))
            childout = Path(command[command.index("--output") + 1])
            childout.mkdir(parents=True, exist_ok=True)
            (childout / "base").mkdir(exist_ok=True)
            (childout / "base/receipt.json").write_text(json.dumps({"status": "FAILED", "productionCompileExitCode": 0, "evaluationCompileExitCode": 0, "javaExitCode": 1, "pythonExitCode": 0, "sourceBeforeAfterEqual": True, "javaSummary": {"tests": 77, "passed": 76, "assertionFailures": 1, "errors": 0}}))
            (childout / "base/java-tests.jsonl").write_text(json.dumps({"test": JID, "status": "ASSERTION_FAILURE", "reason": PRIVATE}))
            env = kwargs["env"]
            self.assertEqual("", env["OPENAI_API_KEY"]); self.assertEqual("", env["GEMINI_API_KEY"])
            stream = kwargs["stdout"]
            file_modes.append((stat.S_IMODE(os.fstat(stream.fileno()).st_mode), stat.S_IMODE(Path(os.readlink(f"/proc/self/fd/{stream.fileno()}")).parent.stat().st_mode)))
            stream.write(PRIVATE + "\n")
            self.assertEqual(subprocess.STDOUT, kwargs["stderr"])
            return subprocess.CompletedProcess(command, child_code)
        stdout, stderr = io.StringIO(), io.StringIO()
        fake = types.SimpleNamespace(run=child, STDOUT=subprocess.STDOUT)
        try:
            with mock.patch.object(LAUNCHER, "subprocess", fake, create=True), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = LAUNCHER.main(["--output", str(output)])
        except Exception:
            self.fail("Public observed main must execute the unchanged child once and publish only safe metadata")
        text = stdout.getvalue() + stderr.getvalue()
        self.assertNotIn(PRIVATE, text); self.assertNotIn(str(self.root), text)
        self.assertEqual(1, len(calls))
        return code, json.loads(stdout.getvalue()), file_modes

    def test_BF20_public_main_preserves_failure_and_real_output_projection(self):
        code, value, _ = self.main_observation(1)
        self.assertEqual(1, code); self.assertEqual("BASE_FAILED", value["status"])
        self.assertEqual(1, value["exitCode"])
        self.assertEqual("JAVA_TESTS", value["baseFailure"]["stage"])
        self.assertEqual(JID, value["baseFailure"]["javaFailures"][0]["test"])

    def test_BF21_public_main_success_does_not_replay_child_raw(self):
        code, value, _ = self.main_observation(0)
        self.assertEqual(0, code); self.assertEqual("PASS", value["status"])
        self.assertEqual(0, value["exitCode"])

    def test_BF22_child_capture_is_atomically0600_in0700_directory(self):
        _, _, modes = self.main_observation(1)
        self.assertEqual([(0o600, 0o700)], modes)


if __name__ == "__main__":
    unittest.main(verbosity=2)
