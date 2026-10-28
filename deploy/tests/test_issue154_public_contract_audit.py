"""Independent public read audit: bounded valid JSON and strict projection proof."""
import copy
import json
import unittest
from unittest.mock import patch

from test_issue154_public_contract import PIPELINE, HEALTH, NATIVE, Opener, Response, contract, spec


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


if __name__ == "__main__":
    unittest.main()

