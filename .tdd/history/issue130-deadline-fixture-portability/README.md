# Deadline fixture portability repair

The original test source is preserved byte-for-byte. Only `deadline_execution` now creates disposable regular executable metadata and sets its own `JAVA_HOME`; subprocess commands remain mocked. All assertion calls are AST-identical to the original. Product `java_tools`, 300-second legacy timeout, call counts and created-child cleanup expectations are unchanged.

The remote CI job exposed only four fixed assertion IDs. Its actual JDK filesystem metadata was not captured, so the controlled missing/symlink reproduction is not proof of the precise remote cause and does not resolve the historical b677 failure by inference.

`check_portability.py` replays synthetic fixtures using an explicitly supplied existing runtime classpath and JDK. It can run `--full` for the original24 Python methods or `--mutant fixture_unpin|extended_deadline|skip_startup_kill` in a new process. Supply `--source-root`, `--classpath-file`, `--java` and a new `--output` scratch directory. The classpath must contain unchanged compiled production and original synthetic Java fixture classes. It never receives actual cases, gold or credentials. FI is in-memory only. This replay driver is diagnostic evidence, not another mandatory CI entry; the repaired original tests already run through the unchanged mandatory launcher.

Raw logs remain in ignored `.codex/tdd-evidence/issue130-deadline-fixture-portability`.
