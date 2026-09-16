#!/usr/bin/env python3
"""Block Codex-initiated commits that fail the repository quality gate."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


GIT_COMMIT_PATTERN = re.compile(
    r"(?:^|[\r\n;&|]\s*)git(?:\.exe)?"
    r"(?:\s+(?:-C|-c|--git-dir|--work-tree)\s+(?:\"[^\"]+\"|'[^']+'|\S+))*"
    r"\s+commit(?:\s|$)",
    re.IGNORECASE,
)
PRODUCTION_PREFIX = "src/main/kotlin/"
TEST_PREFIX = "src/test/kotlin/"
GRADLE_TASKS = ("ktlintCheck", "assemble", "test")


def read_event() -> dict[str, Any]:
    raw_event = sys.stdin.buffer.read()
    encoding = "utf-16" if raw_event.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
    try:
        event = json.loads(raw_event.decode(encoding))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError(f"invalid Codex hook input: {error}") from error

    if not isinstance(event, dict):
        raise RuntimeError("invalid Codex hook input: expected a JSON object")
    return event


def is_git_commit(command: str) -> bool:
    return GIT_COMMIT_PATTERN.search(command) is not None


def run_git(repo_root: Path, *arguments: str) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(repo_root), *arguments],
        check=True,
        capture_output=True,
        text=True,
    )
    return [line.strip().replace("\\", "/") for line in result.stdout.splitlines() if line.strip()]


def find_repo_root(cwd: str) -> Path:
    result = subprocess.run(
        ["git", "-C", cwd, "rev-parse", "--show-toplevel"],
        check=True,
        capture_output=True,
        text=True,
    )
    return Path(result.stdout.strip()).resolve()


def changed_files(repo_root: Path) -> set[str]:
    staged = run_git(repo_root, "diff", "--cached", "--name-only", "--diff-filter=ACMR")
    unstaged = run_git(repo_root, "diff", "--name-only", "--diff-filter=ACMR")
    untracked = run_git(repo_root, "ls-files", "--others", "--exclude-standard")
    return set(staged + unstaged + untracked)


def require_test_change(paths: set[str]) -> None:
    production_changes = sorted(
        path for path in paths if path.startswith(PRODUCTION_PREFIX) and path.endswith(".kt")
    )
    test_changes = sorted(path for path in paths if path.startswith(TEST_PREFIX) and path.endswith(".kt"))
    if production_changes and not test_changes:
        changed = "\n  - ".join(production_changes)
        raise RuntimeError(
            "production Kotlin changed without a corresponding Kotlin test change:\n"
            f"  - {changed}\n"
            "Add or update a focused test under src/test/kotlin before committing."
        )


def gradle_command(repo_root: Path) -> list[str]:
    executable = repo_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not executable.is_file():
        raise RuntimeError(f"Gradle wrapper not found: {executable}")
    return [str(executable)] if os.name == "nt" else ["sh", str(executable)]


def run_quality_gate(repo_root: Path) -> None:
    hook_tests = subprocess.run(
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            ".codex/hooks/tests",
            "-p",
            "test_*.py",
        ],
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if hook_tests.returncode != 0:
        test_output = "\n".join(
            part.strip() for part in (hook_tests.stdout, hook_tests.stderr) if part.strip()
        )
        raise RuntimeError(f"TDD guard self-tests failed.\n{test_output[-6000:]}")

    command = [*gradle_command(repo_root), "--no-daemon", *GRADLE_TASKS]
    result = subprocess.run(
        command,
        cwd=repo_root,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return

    combined_output = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
    output_tail = combined_output[-6000:] if combined_output else "Gradle produced no output."
    raise RuntimeError(
        f"pre-commit quality gate failed with exit code {result.returncode}.\n{output_tail}"
    )


def main() -> None:
    try:
        event = read_event()
        tool_input = event.get("tool_input")
        command = tool_input.get("command", "") if isinstance(tool_input, dict) else ""

        if event.get("hook_event_name") != "PreToolUse" or event.get("tool_name") != "Bash":
            return
        if not isinstance(command, str) or not is_git_commit(command):
            return

        repo_root = find_repo_root(str(event.get("cwd") or os.getcwd()))
        require_test_change(changed_files(repo_root))
        run_quality_gate(repo_root)
    except (OSError, subprocess.SubprocessError, RuntimeError) as error:
        print(f"Commit blocked by meet-me-server TDD guard: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
