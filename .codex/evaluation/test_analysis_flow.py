"""Synthetic, provider-free preflight tests for the reusable analysis-flow runner."""

import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


RUNNER_PATH = Path(__file__).with_name("run-analysis-flow.py")
MODULE_SPEC = importlib.util.spec_from_file_location("meetme_analysis_flow_under_test", RUNNER_PATH)
analysis_flow = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(analysis_flow)


class AnalysisFlowCaseLinksTest(unittest.TestCase):
    @staticmethod
    def fixture():
        payload = {
            "rooms": [{
                "roomId": "synthetic-preflight",
                "analysisContract": "DSL_STRUCTURAL_V5",
                "participants": [
                    {"participantId": "synthetic-p1", "input": "  Synthetic alpha input\n"},
                    {"participantId": "synthetic-p2", "input": "Synthetic beta input 🙂"},
                ],
            }],
        }
        answers = {
            "answerPolicy": {"mode": "PRECOMMITTED_ORACLE", "maximumQuestionsPerParticipant": 1},
            "rooms": [{
                "roomId": "synthetic-preflight",
                "participants": {
                    "synthetic-p1": {
                        "directAnswer": "  Synthetic alpha answer\n",
                        "offTopicResponse": "Synthetic alpha unrelated response",
                    },
                    "synthetic-p2": {
                        "directAnswer": "Synthetic beta answer",
                        "offTopicResponse": "Synthetic beta unrelated response",
                    },
                },
            }],
        }
        return payload, answers

    def test_valid_linked_v5_case_preserves_all_original_input_and_answer_text(self):
        payload, answers = self.fixture()
        before = copy.deepcopy((payload, answers))
        self.assertIsNone(analysis_flow.validate_case_links(payload, answers))
        self.assertEqual(before, (payload, answers))

    def test_room_and_participant_ownership_must_match_exactly(self):
        for change in ("wrong-room", "missing-participant", "extra-participant", "different-participant"):
            with self.subTest(change=change):
                payload, answers = self.fixture()
                parties = answers["rooms"][0]["participants"]
                if change == "wrong-room":
                    answers["rooms"][0]["roomId"] = "synthetic-other-room"
                elif change == "missing-participant":
                    del parties["synthetic-p2"]
                elif change == "extra-participant":
                    parties["synthetic-p3"] = copy.deepcopy(parties["synthetic-p2"])
                else:
                    parties["synthetic-p3"] = parties.pop("synthetic-p2")
                with self.assertRaises(ValueError):
                    analysis_flow.validate_case_links(payload, answers)

    def test_duplicate_or_multiple_rooms_and_duplicate_participant_ids_are_rejected(self):
        for change in ("duplicate-payload-room", "duplicate-answer-room", "second-room", "duplicate-participant"):
            with self.subTest(change=change):
                payload, answers = self.fixture()
                if change == "duplicate-payload-room":
                    payload["rooms"].append(copy.deepcopy(payload["rooms"][0]))
                elif change == "duplicate-answer-room":
                    answers["rooms"].append(copy.deepcopy(answers["rooms"][0]))
                elif change == "second-room":
                    room = copy.deepcopy(payload["rooms"][0])
                    room["roomId"] = "synthetic-second-room"
                    payload["rooms"].append(room)
                else:
                    payload["rooms"][0]["participants"].append(
                        copy.deepcopy(payload["rooms"][0]["participants"][0])
                    )
                with self.assertRaises(ValueError):
                    analysis_flow.validate_case_links(payload, answers)

    def test_one_explicit_v5_room_requires_nonblank_ids_and_raw_participant_input(self):
        for change in ("no-room", "no-participant", "wrong-contract", "blank-room", "blank-participant", "blank-input"):
            with self.subTest(change=change):
                payload, answers = self.fixture()
                room = payload["rooms"][0]
                if change == "no-room":
                    payload["rooms"] = []
                elif change == "no-participant":
                    room["participants"] = []
                elif change == "wrong-contract":
                    room["analysisContract"] = "LEGACY"
                elif change == "blank-room":
                    room["roomId"] = " \n"
                elif change == "blank-participant":
                    room["participants"][0]["participantId"] = " \n"
                else:
                    room["participants"][0]["input"] = " \n"
                with self.assertRaises(ValueError):
                    analysis_flow.validate_case_links(payload, answers)

    def test_precommitted_single_question_policy_requires_two_nonblank_answer_fields(self):
        for change in ("mode", "question-count", "direct-answer", "off-topic", "missing-field"):
            with self.subTest(change=change):
                payload, answers = self.fixture()
                party = answers["rooms"][0]["participants"]["synthetic-p1"]
                if change == "mode":
                    answers["answerPolicy"]["mode"] = "TEST_SYNTHETIC"
                elif change == "question-count":
                    answers["answerPolicy"]["maximumQuestionsPerParticipant"] = 2
                elif change == "direct-answer":
                    party["directAnswer"] = " \n"
                elif change == "off-topic":
                    party["offTopicResponse"] = ""
                else:
                    del party["offTopicResponse"]
                with self.assertRaises(ValueError):
                    analysis_flow.validate_case_links(payload, answers)

    def test_generated_synthetic_host_display_name_accepts_50_characters_and_rejects_51(self):
        payload, answers = self.fixture()
        room_id = "r" * (50 - len("Synthetic live host "))
        payload["rooms"][0]["roomId"] = room_id
        answers["rooms"][0]["roomId"] = room_id
        self.assertIsNone(analysis_flow.validate_case_links(payload, answers))
        payload["rooms"][0]["roomId"] += "r"
        answers["rooms"][0]["roomId"] += "r"
        with self.assertRaises(ValueError):
            analysis_flow.validate_case_links(payload, answers)

    def test_prepare_rejects_mismatched_case_before_manifest_or_execution_metadata(self):
        payload, answers = self.fixture()
        answers["rooms"][0]["roomId"] = "synthetic-unrelated-room"
        config = {
            "schema-coherence": True,
            "provider-parity": True,
            "common-finite-schema": True,
            "selected-choice-context": True,
        }
        with tempfile.TemporaryDirectory(prefix="meetme-flow-preflight-") as directory:
            root = Path(directory)
            for name, value in (("payload", payload), ("answers", answers), ("config", config)):
                (root / f"{name}.json").write_text(json.dumps(value), encoding="utf-8")
            spec = {
                "caseId": "synthetic-preflight-only",
                "payloadPath": str(root / "payload.json"),
                "answersPath": str(root / "answers.json"),
                "configPath": str(root / "config.json"),
                "outputDirectory": "/workspace/scratch/live-eval/synthetic-preflight-only",
                "maximumPhysicalCalls": 9,
                "expected": {"kind": "SYNTHETIC_PREFLIGHT_ONLY"},
                "settingsSource": "SYNTHETIC_UNIT_TEST",
                "authorizationReference": "synthetic-no-provider-execution",
                "dataClassification": "SYNTHETIC",
            }
            spec_path = root / "run-spec.json"
            spec_path.write_text(json.dumps(spec), encoding="utf-8")
            with (
                mock.patch.object(analysis_flow, "source_sha", return_value="synthetic-source") as source_sha,
                mock.patch.object(analysis_flow.subprocess, "check_output", return_value="synthetic-head\n") as metadata,
                mock.patch.object(analysis_flow.subprocess, "run") as execution,
            ):
                with self.assertRaises(ValueError):
                    analysis_flow.prepare(spec_path)
                self.assertFalse((root / "manifest.json").exists())
                source_sha.assert_not_called()
                metadata.assert_not_called()
                execution.assert_not_called()


if __name__ == "__main__":
    unittest.main()
