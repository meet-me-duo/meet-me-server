"""Offline regression tests for bounded, secret-free preflight command reads."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "preflight", Path(__file__).resolve().parents[1] / "preflight/run-preflight.py")
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)
INSTANCE = "i-" + "a" * 17
COMMAND = "a" * 8 + "-" + "b" * 4 + "-" + "c" * 4 + "-" + "d" * 4 + "-" + "e" * 12


def row(**overrides):
    return {"CommandId": COMMAND, "Status": "Pending", "RequestedDateTime": "2026-10-09T07:00:00Z",
            "DocumentName": "AWS-RunShellScript", "InstanceIds": [INSTANCE], "Targets": [], **overrides}


class CommandReadTests(unittest.TestCase):
    def test_aws_failure_categories_are_distinct_and_never_emit_captured_data(self):
        cases = [
            (subprocess.TimeoutExpired(["aws"], 25, output="synthetic-secret", stderr="synthetic-secret"), "AWS_TIMEOUT"),
            (subprocess.CompletedProcess([], 0, "synthetic-secret", "synthetic-secret"), "AWS_INVALID_JSON"),
            (subprocess.CompletedProcess([], 254, "synthetic-secret", "synthetic-secret"), "AWS_DENIED_OR_FAILED"),
            (OSError("synthetic-secret"), "AWS_EXECUTION_FAILED"),
        ]
        for value, reason in cases:
            with self.subTest(reason=reason), patch.dict(os.environ, AWS_REGION="ap-northeast-2"):
                options = {"side_effect": value} if isinstance(value, Exception) else {"return_value": value}
                with patch.object(preflight.subprocess, "run", **options), contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()) as err:
                    result = preflight.aws_call("ssm", "list-commands")
                self.assertEqual(result, {"status": "unavailable", "reason": reason})
                self.assertEqual(out.getvalue() + err.getvalue(), "")

    def test_both_supported_active_filters_are_bounded_and_project_only_metadata(self):
        with patch.object(preflight, "aws_call", return_value={"status": "success", "data": {"Commands": []}}) as call:
            self.assertEqual(preflight.active_commands(INSTANCE), {"status": "success", "data": []})
        self.assertEqual(call.call_count, 2)
        filters = []
        for invocation in call.call_args_list:
            args = invocation.args
            self.assertEqual(args[:2], ("ssm", "list-commands"))
            filters.extend(json.loads(args[args.index("--filters") + 1]))
            self.assertNotIn("--instance-id", args, "Pending commands may not be node-scoped")
            self.assertIn("--page-size", args)
            self.assertIn("--max-items", args)
            self.assertIn("--query", args)
            self.assertEqual(args[args.index("--page-size") + 1], "50")
            self.assertEqual(args[args.index("--max-items") + 1], "50")
            query = args[args.index("--query") + 1]
            self.assertIn("NextToken", query)
            self.assertNotIn("Parameters", query)
            self.assertNotIn("Comment", query)
        self.assertEqual(filters, [{"key": "Status", "value": "Pending"}, {"key": "ExecutionStage", "value": "Executing"}])

    def test_active_commands_deduplicate_and_preserve_targeted_pending_delayed_and_cancelling(self):
        first = row()
        delayed = row(CommandId="f" * 8 + COMMAND[8:], Status="Delayed", InstanceIds=[], Targets=[{"Key": "InstanceIds", "Values": [INSTANCE]}])
        cancelling = row(CommandId="0" * 8 + COMMAND[8:], Status="Cancelling", InstanceIds=[], Targets=[{"Key": "InstanceIds", "Values": ["*"]}])
        other = row(CommandId="1" * 8 + COMMAND[8:], InstanceIds=["i-" + "b" * 17])
        completed = row(CommandId="2" * 8 + COMMAND[8:], Status="Success")
        responses = [{"status": "success", "data": {"Commands": rows}} for rows in ([first], [first, delayed, cancelling, other, completed])]
        with patch.object(preflight, "aws_call", side_effect=responses):
            result = preflight.active_commands(INSTANCE)
        self.assertEqual(result["status"], "success")
        self.assertEqual([r["Status"] for r in result["data"]], ["Pending", "Delayed", "Cancelling"])
        self.assertTrue(all(set(r) == {"CommandId", "Status", "RequestedDateTime", "DocumentName"} for r in result["data"]))

    def test_truncated_or_unresolvable_targets_fail_closed_instead_of_reporting_zero(self):
        cases = [
            {"Commands": [], "NextToken": "synthetic-token"},
            {"Commands": [row(InstanceIds=[], Targets=[{"Key": "tag:App", "Values": ["MeetMe"]}])]},
            {"Commands": [row(InstanceIds=[], Targets=[])]},
            {"Commands": [row(InstanceIds="synthetic-secret")]},
            {"Commands": [row(Targets=[{"Key": "InstanceIds", "Values": "synthetic-secret"}])]},
            {"Commands": [row(Status="unknown")]},
            {"Commands": [row(CommandId="synthetic-secret")]},
            {"Commands": "synthetic-secret"},
        ]
        for data in cases:
            with self.subTest(data=data), patch.object(preflight, "aws_call", return_value={"status": "success", "data": data}):
                result = preflight.active_commands(INSTANCE)
                self.assertEqual(result["status"], "unavailable")
                self.assertNotIn("synthetic-secret", json.dumps(result))

    def test_aws_read_failure_remains_unavailable(self):
        failure = {"status": "unavailable", "reason": "AWS_TIMEOUT"}
        with patch.object(preflight, "aws_call", return_value=failure):
            self.assertEqual(preflight.active_commands(INSTANCE), failure)


if __name__ == "__main__":
    unittest.main()
