# Codex hooks

`hooks.json` registers two project-local hooks:

- `SessionStart` restores the repository's core development guardrails as
  concise developer context.
- `PreToolUse` runs `hooks/tdd_guard.py` before Codex executes a shell command.
  It does nothing for ordinary commands. Before `git commit`, it requires a
  changed Kotlin test when production Kotlin changed, then runs
  the guard's unit tests followed by `ktlintCheck`, `assemble`, and `test` with
  the Gradle wrapper.

The hooks do not modify files or send data outside the repository. The TDD
guard reads only Git's changed-file lists and captures Gradle output. It prints
the tail of that output only when the quality gate fails.

After cloning or changing the hook definition, open `/hooks` in Codex, review
the project-local hook, and trust its current definition. Codex skips untrusted
project hooks.

## Manual check

From the repository root:

```powershell
'{"hook_event_name":"SessionStart"}' |
  python .codex/hooks/session_start.py
```

To exercise the TDD guard without creating a commit, pipe a synthetic
`PreToolUse` event whose command is `git commit --dry-run`:

```powershell
'{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"git commit --dry-run"},"cwd":"."}' |
  python .codex/hooks/tdd_guard.py
```
