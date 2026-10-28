"""Independent immutable public API shape proof tests (issue154).

The projection intentionally proves operation/property presence, not full OpenAPI
response types/security semantics. Runtime source/image/DB proof remains separate.
"""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch


BASE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue154_public_contract_pipeline", BASE / "pipeline/release_pipeline.py")
PIPELINE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PIPELINE)
HEALTH = "https://api.meet-me.co.kr/actuator/health"
NATIVE = "https://api.meet-me.co.kr/v3/api-docs"


def spec():
    return {
        "openapi": "3.0.1",
        "info": {"title": "synthetic-service", "version": "synthetic"},
        "paths": {
            "/api/rooms/{inviteCode}/review-prompt": {
                "post": {"operationId": "claimReviewPrompt", "responses": {"200": {"description": "synthetic-description"}}},
            },
            "/api/rooms/{inviteCode}": {
                "parameters": [{"name": "inviteCode", "in": "path", "required": True}],
                "get": {"operationId": "readRoom", "responses": {"200": {"description": "synthetic-description"}}},
            },
        },
        "components": {"schemas": {
            "RoomResponse": {"type": "object", "properties": {
                "review_prompt_supported": {"type": "boolean"},
                "invite_code": {"type": "string", "example": "synthetic-raw-example"},
            }},
            "ReferencedInput": {"$ref": "#/components/schemas/RoomResponse"},
        }},
    }


def contract():
    return {
        "protocol": "meet-me-public-api-contract-v1",
        "operations": [
            {"path": "/api/rooms/{inviteCode}", "method": "get", "operationId": "readRoom"},
            {"path": "/api/rooms/{inviteCode}/review-prompt", "method": "post", "operationId": "claimReviewPrompt"},
        ],
        "schemaProperties": {"ReferencedInput": [], "RoomResponse": ["invite_code", "review_prompt_supported"]},
    }


class Response:
    def __init__(self, value, status=200):
        self.raw = value if type(value) is bytes else json.dumps(value).encode()
        self.status = status
        self.read_limits = []

    def read(self, limit):
        self.read_limits.append(limit)
        return self.raw[:limit]

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class Opener:
    def __init__(self, health=None, native=None, failure=None):
        self.health = [Response({"status": "UP"})] if health is None else health
        self.native = [Response(spec())] if native is None else native
        self.calls = []
        self.failure = failure

    def open(self, url, **kwargs):
        self.calls.append((url, kwargs))
        if self.failure is not None:
            raise self.failure
        responses = self.health if url == HEALTH else self.native if url == NATIVE else None
        if responses is None:
            raise AssertionError("arbitrary public verification URL")
        return responses.pop(0) if len(responses) > 1 else responses[0]


class Issue154PublicContractTests(unittest.TestCase):
    def verify(self, opener):
        with patch.object(PIPELINE.urllib.request, "build_opener", return_value=opener), patch.object(PIPELINE.time, "sleep"):
            return PIPELINE.verify_public_contract(contract())

    def test_projection_is_sorted_detached_and_allows_only_public_shape_fields(self):
        original = spec()
        before = copy.deepcopy(original)
        projected = PIPELINE.project_public_api(original)
        self.assertEqual(contract(), projected)
        self.assertEqual(before, original)
        self.assertEqual({"protocol", "operations", "schemaProperties"}, set(projected))
        raw = json.dumps(projected)
        self.assertNotIn("synthetic-raw-example", raw)
        self.assertNotIn("synthetic-description", raw)
        self.assertEqual([], projected["schemaProperties"]["ReferencedInput"])

    def test_projection_documents_shape_scope_without_claiming_type_semantic_equality(self):
        changed = spec()
        changed["components"]["schemas"]["RoomResponse"]["properties"]["invite_code"]["type"] = "integer"
        changed["paths"]["/api/rooms/{inviteCode}"]["get"]["responses"] = {"403": {"description": "different"}}
        self.assertEqual(PIPELINE.project_public_api(spec()), PIPELINE.project_public_api(changed))

    def test_all_standard_http_methods_are_projected_but_path_metadata_is_not(self):
        original = spec()
        original["paths"] = {"/api/synthetic": {
            method: {"operationId": "synthetic" + method.title()} for method in
            ("get", "post", "put", "patch", "delete", "head", "options", "trace")
        }}
        original["paths"]["/api/synthetic"]["parameters"] = []
        projected = PIPELINE.project_public_api(original)
        self.assertEqual(sorted(("get", "post", "put", "patch", "delete", "head", "options", "trace")),
                         [row["method"] for row in projected["operations"]])

    def test_invalid_source_spec_cannot_produce_an_empty_or_ambiguous_contract(self):
        cases = [
            None, True, {}, spec() | {"paths": []}, spec() | {"paths": {}},
            spec() | {"components": {"schemas": []}},
        ]
        missing = spec()
        missing["paths"]["/api/rooms/{inviteCode}"]["get"].pop("operationId")
        cases.append(missing)
        malformed = spec()
        malformed["components"]["schemas"]["RoomResponse"]["properties"] = []
        cases.append(malformed)
        for candidate in cases:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                PIPELINE.project_public_api(candidate)

    def test_contract_validation_preserves_a_valid_canonical_detached_value(self):
        value = contract()
        normalized = PIPELINE.validate_public_contract(value)
        self.assertEqual(value, normalized)
        normalized["operations"][0]["operationId"] = "changed-local-copy"
        self.assertEqual("readRoom", value["operations"][0]["operationId"])

    def test_contract_schema_types_unknown_fields_and_empty_operations_fail_closed(self):
        cases = [
            None, True, {}, contract() | {"protocol": "old"},
            contract() | {"extra": "arbitrary-output"}, contract() | {"operations": []},
            contract() | {"operations": "true"}, contract() | {"schemaProperties": []},
        ]
        for candidate in cases:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                PIPELINE.validate_public_contract(candidate)

    def test_missing_or_duplicated_route_method_identity_is_not_a_valid_contract(self):
        original = contract()
        for mutation in ({"path": "https://untrusted.example/api"}, {"path": ""},
                         {"method": "GET"}, {"method": "connect"},
                         {"operationId": ""}, {"operationId": True}):
            candidate = copy.deepcopy(original)
            candidate["operations"][0].update(mutation)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                PIPELINE.validate_public_contract(candidate)
        candidate = copy.deepcopy(original)
        candidate["operations"].insert(0, copy.deepcopy(candidate["operations"][0]))
        with self.assertRaises(ValueError):
            PIPELINE.validate_public_contract(candidate)

    def test_unsorted_or_duplicate_property_names_and_operations_are_rejected(self):
        cases = []
        reversed_ops = contract()
        reversed_ops["operations"].reverse()
        cases.append(reversed_ops)
        for properties in (["review_prompt_supported", "invite_code"], ["invite_code", "invite_code"],
                           [True], "invite_code"):
            candidate = contract()
            candidate["schemaProperties"]["RoomResponse"] = properties
            cases.append(candidate)
        for candidate in cases:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                PIPELINE.validate_public_contract(candidate)

    def test_large_or_control_character_contract_fields_are_rejected(self):
        for field, value in (("path", "/" + "a" * 10000),
                             ("path", "/api/room\nInjected"),
                             ("operationId", "a" * 10000),
                             ("operationId", "synthetic\nInjected")):
            candidate = contract()
            candidate["operations"][0][field] = value
            with self.subTest(field=field, value_length=len(value)), self.assertRaises(ValueError):
                PIPELINE.validate_public_contract(candidate)
        candidate = contract()
        candidate["schemaProperties"]["RoomResponse"] = ["a" * 10000]
        with self.assertRaises(ValueError):
            PIPELINE.validate_public_contract(candidate)

    def test_exact_public_native_projection_and_health_up_satisfy_public_shape_proof(self):
        opener = Opener()
        self.assertIs(self.verify(opener), True)
        self.assertEqual([HEALTH, NATIVE], [url for url, _ in opener.calls])
        self.assertTrue(all(kwargs == {"timeout": 10} for _, kwargs in opener.calls))
        self.assertEqual([8193], opener.health[0].read_limits)
        self.assertEqual([1048577], opener.native[0].read_limits)

    def test_health_success_does_not_replace_missing_operation_field_or_schema(self):
        for mutation in ("operation", "property", "schema"):
            original = spec()
            if mutation == "operation":
                original["paths"].pop("/api/rooms/{inviteCode}/review-prompt")
            elif mutation == "property":
                original["components"]["schemas"]["RoomResponse"]["properties"].pop("review_prompt_supported")
            else:
                original["components"]["schemas"].pop("RoomResponse")
            with self.subTest(mutation=mutation):
                self.assertIs(self.verify(Opener(native=[Response(original)])), False)

    def test_public_non200_or_unhealthy_status_cannot_be_verified(self):
        for opener in (Opener(health=[Response({"status": "UP"}, status=503)]),
                       Opener(health=[Response({"status": "DOWN"})]),
                       Opener(native=[Response(spec(), status=503)])):
            self.assertIs(self.verify(opener), False)
            self.assertLessEqual(len(opener.calls), 6)

    def test_transient_public_read_can_recover_within_three_bounded_attempts(self):
        opener = Opener(health=[Response({"status": "DOWN"}), Response({"status": "UP"})])
        with patch.object(PIPELINE.urllib.request, "build_opener", return_value=opener), patch.object(PIPELINE.time, "sleep") as sleep:
            self.assertIs(PIPELINE.verify_public_contract(contract()), True)
        self.assertEqual(3, len(opener.calls))
        self.assertEqual(1, sleep.call_count)
        self.assertEqual((2,), sleep.call_args.args)

    def test_public_errors_and_oversized_response_do_not_leak_or_become_success(self):
        for opener in (Opener(failure=OSError("synthetic-sensitive-payload")),
                       Opener(native=[Response(b" " * (1048576 + 1))]),
                       Opener(health=[Response(b" " * (8192 + 1))])):
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertIs(self.verify(opener), False)
            self.assertEqual("", stdout.getvalue())
            self.assertEqual("", stderr.getvalue())
            self.assertLessEqual(len(opener.calls), 6)

    def test_public_transport_rejects_redirects_without_an_unsafe_retry_path(self):
        handlers = []

        def build(*args):
            handlers.extend(args)
            return Opener()

        with patch.object(PIPELINE.urllib.request, "build_opener", side_effect=build), patch.object(PIPELINE.time, "sleep"):
            self.assertIs(PIPELINE.verify_public_contract(contract()), True)
        self.assertTrue(handlers)
        handler = handlers[0]() if type(handlers[0]) is type else handlers[0]
        with self.assertRaises(ValueError):
            handler.redirect_request(None, None, 302, "redirect", {}, "https://untrusted.example/")

    def test_invalid_expected_contract_is_rejected_before_any_public_request(self):
        with patch.object(PIPELINE.urllib.request, "build_opener") as build:
            with self.assertRaises(ValueError):
                PIPELINE.verify_public_contract(contract() | {"extra": "not-allowed"})
            build.assert_not_called()


if __name__ == "__main__":
    unittest.main()

