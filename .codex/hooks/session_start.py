#!/usr/bin/env python3
"""Provide concise, repository-specific guidance when a Codex session starts."""

from __future__ import annotations

import json
import sys
from typing import Any


GUIDANCE = """meet-me-server repository guidance:
- Never edit or commit on develop. Before creating a feature branch, require a clean worktree, switch to develop, and run git pull --ff-only origin develop.
- If develop cannot fast-forward or pull reports a conflict, do not merge or rebase automatically; present resolution options and tradeoffs to the user.
- Before changing backend code, infrastructure, or project documentation, read the relevant files under .agents/rules/ listed in AGENTS.md.
- Use .agents/skills/project-architecture/SKILL.md for architecture, external integration, data-boundary, or deployment work.
- Treat docs/PRD.md, docs/ARCHITECTURE.md, and docs/ADR.md as the product, technical, and long-lived decision sources of truth.
- Use implementation_plan.md to track execution order and progress, but never as a replacement for the source-of-truth documents.
- Keep implementation_plan.md synchronized when conversation changes scope, priority, order, or decisions.
- Before a user-requested commit and push, first update the plan's completed items, current status, last-updated date, and progress log to match the verified work.
- Keep confirmed decisions separate from TBD items; do not resolve a TBD without user agreement.
- When meaningful constraints or multiple implementation options exist, present each option and its tradeoffs before implementation, then ask the user to choose.
- Follow risk-based TDD option B: use strict Red-Green-Refactor for ordinary changes and separate test-design and implementation roles for deterministic domain rules, security/authentication, external contracts, and risky persistence changes.
- A valid RED must compile and fail for the expected missing behavior. Do not treat syntax, configuration, dependency, or fixture failures as RED, and never weaken a test merely to reach GREEN.
- Follow .agents/rules/user-intervention.md: split external setup into [AGENT], [USER], and [SHARED] work and give the user an actionable step-by-step guide before dependent work.
- Never ask the user to paste API keys, client secrets, access tokens, passwords, or recovery codes into chat or repository files.
- After changes, run the smallest relevant test first, and never persist or print secrets, personal data, OAuth tokens, or external API keys.
"""


def read_event() -> dict[str, Any]:
    try:
        raw_event = sys.stdin.buffer.read()
        encoding = "utf-16" if raw_event.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
        event = json.loads(raw_event.decode(encoding))
    except (UnicodeDecodeError, json.JSONDecodeError, OSError) as error:
        raise SystemExit(f"Invalid Codex hook input: {error}") from error

    if not isinstance(event, dict):
        raise SystemExit("Invalid Codex hook input: expected a JSON object")
    return event


def main() -> None:
    event = read_event()
    if event.get("hook_event_name") != "SessionStart":
        return

    output = {
        "hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": GUIDANCE,
        }
    }
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
