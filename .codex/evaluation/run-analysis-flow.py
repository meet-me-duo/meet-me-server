#!/usr/bin/env python3
"""Run the existing one-room HTTP evaluator with frozen private inputs; never answer for a user."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
RECIPIENTS = {"TypeSafeAI", "OpenAI", "GoogleGemini"}
CONFIG_KEYS = {"schema-coherence", "provider-parity", "common-finite-schema", "selected-choice-context"}
OPTIONAL_CONFIG_KEYS = {"scoped-term-evidence", "place-constraints", "relative-date-context", "union-preferred-availability", "server-evidence-references", "search-relative-dates", "calendar-month-facts", "indexed-evidence-selections", "weekly-schedule-rules"}


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_sha():
    digest = hashlib.sha256()
    for path in sorted([REPO / "build.gradle.kts", *(REPO / "src").rglob("*")]):
        if path.is_file():
            digest.update(str(path.relative_to(REPO)).encode() + b"\0")
            digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def private_path(value):
    path = Path(value).resolve()
    if path.is_relative_to(REPO) or path.is_relative_to(REPO.parent / "meet-me-web"):
        raise ValueError("Private evidence must remain outside Git repositories")
    return path


def validate_case_links(payload, answers):
    """Validate input/oracle ownership and local synthetic host bounds before any execution."""
    def nonblank(value):
        return isinstance(value, str) and bool(value.strip())

    if not isinstance(payload, dict) or not isinstance(answers, dict):
        raise ValueError("Supply one private payload and a matching precommitted answer file")
    rooms, answer_rooms = payload.get("rooms"), answers.get("rooms")
    if not isinstance(rooms, list) or len(rooms) != 1 or not isinstance(answer_rooms, list) or len(answer_rooms) != 1:
        raise ValueError("Exactly one payload room and one matching answer room are required")
    room, answer_room = rooms[0], answer_rooms[0]
    if not isinstance(room, dict) or not isinstance(answer_room, dict):
        raise ValueError("Invalid room mapping")
    room_id = room.get("roomId")
    if not nonblank(room_id) or room.get("analysisContract") != "DSL_STRUCTURAL_V5":
        raise ValueError("One named explicit V5 room is required")
    if len("Synthetic live host " + room_id) > 50:
        raise ValueError("Room label exceeds the evaluator's native synthetic host-name limit")
    if answer_room.get("roomId") != room_id:
        raise ValueError("Answer file belongs to a different room")
    participants = room.get("participants")
    if not isinstance(participants, list) or not 2 <= len(participants) <= 50:
        raise ValueError("Supply the supported participant cohort")
    if any(not isinstance(p, dict) or not nonblank(p.get("participantId")) or not nonblank(p.get("input")) for p in participants):
        raise ValueError("Participants require nonempty IDs and original input")
    identities = [p["participantId"] for p in participants]
    oracles = answer_room.get("participants")
    if len(set(identities)) != len(identities) or not isinstance(oracles, dict) or set(oracles) != set(identities):
        raise ValueError("Payload and answer participant ownership must match exactly")
    policy = answers.get("answerPolicy", {})
    if not isinstance(policy, dict) or policy.get("mode") != "PRECOMMITTED_ORACLE" or type(policy.get("maximumQuestionsPerParticipant")) is not int or policy["maximumQuestionsPerParticipant"] != 1:
        raise ValueError("The runner requires precommitted answers and one question per participant")
    fields = {"directAnswer", "offTopicResponse"}
    for oracle in oracles.values():
        if not isinstance(oracle, dict) or set(oracle) != fields or any(not nonblank(oracle[field]) for field in fields):
            raise ValueError("Each participant needs both nonempty precommitted answer fields")


def prepare(spec_path):
    """Freeze supplied files and expectations; never derive missing participant facts."""
    spec = read(spec_path)
    validate_case_links(read(private_path(spec["payloadPath"])), read(private_path(spec["answersPath"])))
    if not spec.get("expected") or not spec.get("settingsSource") or not spec.get("authorizationReference"):
        raise ValueError("Supply expectations, settings provenance and current user authorization")
    manifest = {key: spec[key] for key in ["caseId", "outputDirectory", "maximumPhysicalCalls", "expected",
                                          "settingsSource", "authorizationReference"]}
    manifest.update({"approved": True, "maximumRooms": 1,
                     "dataClassification": spec["dataClassification"],
                     "sourceSha256": source_sha(),
                     "sourceBaseRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
                     "runnerSha256": sha(__file__),
                     "initScriptSha256": sha(REPO / ".codex/evaluation/analysis-flow.gradle")})
    for field, fingerprint in [("payloadPath", "payloadSha256"), ("answersPath", "answersSha256"),
                               ("configPath", "configSha256")]:
        path = private_path(spec[field])
        manifest[field], manifest[fingerprint] = str(path), sha(path)
    manifest["recipientApprovals"] = [{"recipient": recipient, "messageId": spec["authorizationReference"],
                                       "payloadSha256": manifest["payloadSha256"], "purpose": "MEETME_EVALUATION"}
                                      for recipient in sorted(RECIPIENTS)]
    destination = spec_path.parent / "manifest.json"
    with destination.open("x") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    validate(destination)
    print(json.dumps({"prepared": str(destination), "providerCalls": 0}))


def validate(manifest_path):
    manifest = read(manifest_path)
    for field, fingerprint in [("payloadPath", "payloadSha256"), ("answersPath", "answersSha256"),
                               ("configPath", "configSha256")]:
        if sha(private_path(manifest[field])) != manifest[fingerprint]:
            raise ValueError(f"Frozen file changed: {field}")
    if manifest["sourceSha256"] != source_sha():
        raise ValueError("Source changed; prepare a new manifest and compare the intended change")
    for key, path in [("runnerSha256", Path(__file__)),
                      ("initScriptSha256", REPO / ".codex/evaluation/analysis-flow.gradle")]:
        if key in manifest and manifest[key] != sha(path):
            raise ValueError("Execution tooling changed; prepare a new manifest")
    if manifest.get("approved") is not True:
        raise ValueError("Current user authorization must be recorded")
    if not 1 <= manifest["maximumPhysicalCalls"] <= 17 or manifest["maximumRooms"] != 1:
        raise ValueError("One bounded room per execution is required")
    receipts = manifest["recipientApprovals"]
    if {item["recipient"] for item in receipts} != RECIPIENTS or len(receipts) != len(RECIPIENTS):
        raise ValueError("Record the current authorization for each actual recipient")
    for item in receipts:
        if (not item["messageId"] or item["payloadSha256"] != manifest["payloadSha256"]
                or item["purpose"] != "MEETME_EVALUATION"):
            raise ValueError("Invalid authorization reference")
    config = read(manifest["configPath"])
    if (not CONFIG_KEYS.issubset(config) or set(config) - CONFIG_KEYS - OPTIONAL_CONFIG_KEYS
            or any(type(value) is not bool for value in config.values())):
        raise ValueError("Invalid frozen experiment switches")
    payload = read(manifest["payloadPath"])
    validate_case_links(payload, read(manifest["answersPath"]))
    if len(payload["rooms"]) != 1 or payload["rooms"][0]["analysisContract"] != "DSL_STRUCTURAL_V5":
        raise ValueError("An explicit V5 one-room case is required")
    output = private_path(manifest["outputDirectory"])
    if not output.is_relative_to(Path("/workspace/scratch/live-eval")):
        raise ValueError("Use the existing evaluator's private output root")
    return manifest, output


def answer(manifest, output, question_id, field):
    import uuid

    question_id = str(uuid.UUID(question_id))
    routing = output / "question-routing"
    pending = read(routing / (question_id + ".pending.json"))
    if pending["questionId"] != question_id or field not in pending["allowedDecisionFields"]:
        raise ValueError("Question/answer mode does not match the actual pending question")
    if pending["oracleFileSha256"] != sha(manifest["answersPath"]):
        raise ValueError("Supplemental answer file changed")
    answers = read(manifest["answersPath"])
    room = next(item for item in answers["rooms"] if item["roomId"] == pending["roomId"])
    text = room["participants"][pending["participantId"]][field]
    if not isinstance(text, str) or not text.strip():
        raise ValueError("The selected supplemental answer is empty")
    decision = routing / (question_id + ".decision.json")
    with decision.open("x") as handle:
        json.dump({"questionId": question_id, "field": field}, handle)
    print(json.dumps({"questionId": question_id, "field": field, "decision": str(decision),
                      "note": "The running evaluator verifies the frozen answer and submits the HTTP request."}))


def report(manifest_path):
    """Read original evidence, including historical sources; never invoke a provider."""
    manifest = read(manifest_path)
    output = private_path(manifest["outputDirectory"])
    started = read(output / "runner-started.json")
    if started["manifestSha256"] != sha(manifest_path):
        raise ValueError("The original run manifest changed")
    evidence = read(output / "results.json")
    journal = read(output / "native-physical-calls.json")
    rooms = evidence.get("rooms", [])
    physical_counts = {}
    for call in journal.get("calls", []):
        stage = call["operation"]
        physical_counts[stage] = physical_counts.get(stage, 0) + 1
    summary = {"case": manifest["caseId"], "sourceSha256": manifest["sourceSha256"],
               "expected": manifest["expected"], "settingsSource": manifest.get("settingsSource", "AGENT_STATED_INITIAL_ASSUMPTION"),
               "failureKind": evidence.get("failureKind"), "infrastructureBlock": evidence.get("infrastructureBlock"),
               "observedPhysicalCalls": journal.get("physicalCount"), "physicalStageCounts": physical_counts,
               "unknownPhysicalOutcomes": journal.get("unknownCount"),
               "initialJevLabels": [item["result"].get("semanticAssessment") for item in evidence.get("classifierResults", [])],
               "semanticAssessment": "REQUIRES_REVIEW_AGAINST_PRECOMMITTED_EXPECTATIONS",
               "note": "Use native wire observations for final calls. HTTP200/CLEAR/exit0 do not establish semantic success.",
               "rooms": []}
    for room in rooms:
        recommendations = room.get("recommendations") or {}
        candidates = room.get("candidates") or {}
        summary["rooms"].append({"sourceRoomId": room["sourceRoomId"],
                                 "publicStatus": (room.get("roomAfterAnalysis") or {}).get("public_status"),
                                 "quality": candidates.get("quality"), "appliedInputs": candidates.get("applied_submissions"),
                                 "totalInputs": candidates.get("total_submissions"),
                                 "answerCount": sum(p["answerSent"] for p in room.get("participants", [])),
                                 "freshJdbcRecalculationAvailable": "freshJdbcRestoreAndRecalculation" in room,
                                 "windows": [{"range": item["time_range"],
                                              "attendanceCounts": [v["attendance_count"] for v in item["variants"]]}
                                             for item in recommendations.get("options", [])]})
    text = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    destination = output / "execution-report.json"
    if destination.exists():
        if destination.read_text() != text:
            raise ValueError("An existing execution report differs; preserve it and investigate")
    else:
        with destination.open("x") as handle:
            handle.write(text)
    print(json.dumps({"report": str(destination), "physicalCalls": journal.get("physicalCount"),
                      "statuses": [room["publicStatus"] for room in summary["rooms"]], "providerCallsByThisCommand": 0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--run", action="store_true", help="Run one actual HTTP evaluation; default validates only")
    mode.add_argument("--prepare", action="store_true", help="Freeze a supplied private run specification into a new manifest.json")
    mode.add_argument("--report", action="store_true", help="Summarize existing evidence without providers or source rebuilding")
    mode.add_argument("--answer", metavar="QUESTION_ID", help="Select one frozen supplemental answer after inspecting the actual question")
    parser.add_argument("--field", choices=["directAnswer", "offTopicResponse"])
    args = parser.parse_args()
    manifest_path = private_path(args.manifest)
    if bool(args.answer) != bool(args.field):
        parser.error("Use --answer QUESTION_ID and --field together")
    if args.prepare:
        prepare(manifest_path)
        return 0
    if args.report:
        report(manifest_path)
        return 0
    manifest, output = validate(manifest_path)
    if args.answer:
        answer(manifest, output, args.answer, args.field)
        return 0
    if not args.run:
        print(json.dumps({"validated": True, "case": manifest["caseId"], "physicalCallCap": manifest["maximumPhysicalCalls"],
                          "providerCalls": 0, "output": str(output)}))
        return 0
    if any(not os.environ.get(key) for key in ["TYPESAFE_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY"]):
        raise ValueError("Configure the three provider keys in Cloud environment settings")
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    with (output / "runner-started.json").open("x") as handle:
        json.dump({"manifestSha256": sha(manifest_path), "sourceSha256": source_sha()}, handle)
    env = dict(os.environ)
    for key in ["MEETME_NATIVE_SCHEMA_COMPARE", "MEETME_LOCAL_FIRST_POST_DIAG"]:
        env.pop(key, None)
    env.update({"MEETME_LIVE_EVAL": "true", "MEETME_LIVE_MANIFEST": str(manifest_path),
                "MEETME_LIVE_PAYLOAD": manifest["payloadPath"], "MEETME_LIVE_ANSWERS": manifest["answersPath"],
                "MEETME_LIVE_OUTPUT": str(output), "MEETME_FLOW_CONFIG": manifest["configPath"],
                "MEETME_LIVE_MAX_PHYSICAL_CALLS": str(manifest["maximumPhysicalCalls"])})
    command = ["./gradlew", "--offline", "--no-daemon", "--max-workers=2", "-I",
               ".codex/evaluation/analysis-flow.gradle", "c5HttpCandidateTest", "--tests",
               "*ExperimentalLiveHttpEvaluationTest.explicitly approved blind payload traverses actual HTTP workers and providers once"]
    print(json.dumps({"running": manifest["caseId"], "output": str(output), "questionRouting": str(output / "question-routing")}), flush=True)
    with (output / "gradle.log").open("x") as log:
        result = subprocess.run(command, cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
    with (output / "runner-finished.json").open("x") as handle:
        json.dump({"exitCode": result.returncode, "manifestSha256": sha(manifest_path)}, handle)
    print(json.dumps({"case": manifest["caseId"], "exitCode": result.returncode,
                      "resultsAvailable": (output / "results.json").exists(), "output": str(output)}))
    if (output / "results.json").exists():
        report(manifest_path)
    return result.returncode


if __name__ == "__main__":
    os.umask(0o077)
    try:
        sys.exit(main())
    except (ValueError, KeyError, OSError) as error:
        print(f"Evaluation preparation failed: {error}", file=sys.stderr)
        sys.exit(2)
