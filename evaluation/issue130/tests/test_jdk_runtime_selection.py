"""Independent JDK portability oracle; fake executables only, no network/key/corpus.

Calls the public runner main. Plan validation is isolated because this contract is
runtime selection, not another frozen-plan oracle. Actual subprocesses run local
stand-ins. Only legacy/PATH launches are redirected at the process boundary so
this test never changes or executes the shared /tmp JDK or a host PATH program.
Legacy filesystem metadata is projected to its stand-in as well, independently
of whether the shared default installation exists on this machine or in CI.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

REPO = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('issue130_jdk_selection_runner', REPO / 'evaluation/issue130/run_representative_decisions.py')
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)
ABSENT = object()
LEGACY = Path('/tmp/meetme-jdk17')


class JdkRuntimeSelectionContract(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='issue130-jdk-selection-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.trace = self.root / 'executed.jsonl'
        self.configured = self.create_jdk('configured JDK', 'CONFIGURED')
        self.legacy = self.create_jdk('disposable-legacy', 'LEGACY')
        self.path_jdk = self.create_jdk('disposable-path', 'PATH')
        self.plan = self.root / 'plan.json'
        self.plan.write_text('{"fixture":"jdk-selection-only"}')
        self.index = self.root / 'index.json'
        self.index.write_text('{}')
        self.output = self.root / 'output'
        self.output.mkdir()
        self.cp = self.root / 'runtime.classpath'
        self.cp.write_text(str(self.root / 'synthetic-no-classes'))
        self.digest = hashlib.sha256(self.plan.read_bytes()).hexdigest()
        self.args = ['--plan', str(self.plan), '--index', str(self.index), '--output', str(self.output), '--classpath-file', str(self.cp), '--execute-frozen']
        self.requested = []
        self.real_run, self.real_popen = subprocess.run, subprocess.Popen

    def create_jdk(self, directory, marker):
        home = self.root / directory
        (home / 'bin').mkdir(parents=True)
        body = (f'#!{sys.executable}\n'
                'import json, os\n'
                'from pathlib import Path\n'
                'tool = Path(__file__).name\n'
                f'record = {{"home": {marker!r}, "tool": tool}}\n'
                'if tool == "javac":\n'
                '    record["compilerCredentialsBlank"] = os.environ.get("OPENAI_API_KEY") == "" and os.environ.get("GEMINI_API_KEY") == ""\n'
                'with open(os.environ["ISSUE130_JDK_TRACE"], "a") as stream:\n'
                '    stream.write(json.dumps(record) + "\\n")\n'
                'if tool == "java":\n'
                '    print(json.dumps({"status":"COMPLETED", "physicalDecisionsCalls":0}))\n')
        for name in ('javac', 'java'):
            target = home / 'bin' / name
            target.write_text(body)
            target.chmod(0o700)
        return home

    def dispatch(self, command):
        requested = str(command[0])
        self.requested.append(requested)
        # Observe the requested installation before safely executing a stand-in.
        # A wrong old fallback therefore produces an assertion failure, not a
        # setup error or accidental execution of a real evaluation JVM.
        routes = {str(LEGACY / 'bin' / name): str(self.legacy / 'bin' / name) for name in ('javac', 'java')}
        routes.update({name: str(self.path_jdk / 'bin' / name) for name in ('javac', 'java')})
        configured_tools = {str(self.configured / 'bin' / name) for name in ('javac', 'java')}
        if requested in configured_tools:
            return command
        if requested in routes:
            return [routes[requested], *command[1:]]
        raise AssertionError('Unexpected process invocation; no host executable permitted')

    def execute(self, home=ABSENT, dry_run=False):
        environment = {
            'PATH': str(self.path_jdk / 'bin'),
            'OPENAI_API_KEY': 'synthetic-not-an-api-key',
            'GEMINI_API_KEY': 'synthetic-not-an-api-key',
            'ISSUE130_FROZEN_PLAN_SHA256': self.digest,
            'ISSUE130_EXECUTE_FROZEN': self.digest,
            'ISSUE130_JDK_TRACE': str(self.trace),
        }
        if home is not ABSENT:
            environment['JAVA_HOME'] = str(home)
        def compile_process(command, *args, **kwargs):
            return self.real_run(self.dispatch(command), *args, **kwargs)
        def java_process(command, *args, **kwargs):
            return self.real_popen(self.dispatch(command), *args, **kwargs)
        isolated = types.SimpleNamespace(run=compile_process, Popen=java_process, TimeoutExpired=subprocess.TimeoutExpired, PIPE=subprocess.PIPE)
        plan = {'sourceRoot': str(self.root), 'runtimeFiles': {str(self.cp): hashlib.sha256(self.cp.read_bytes()).hexdigest()}, 'suiteSeconds': 300, 'caseCount': 16}
        real_stat, real_lstat, real_access = os.stat, os.lstat, os.access
        def metadata_alias(path):
            # Preserve all configured/invalid paths. Only the shared legacy
            # installation is represented by a disposable actual filesystem.
            if not isinstance(path, (str, bytes, os.PathLike)):
                return path
            text = os.fsdecode(path)
            prefix = str(LEGACY)
            if text == prefix or text.startswith(prefix + os.sep):
                mapped = str(self.legacy) + text[len(prefix):]
                return os.fsencode(mapped) if isinstance(path, bytes) else mapped
            return path
        def selected_stat(path, *args, **kwargs):
            return real_stat(metadata_alias(path), *args, **kwargs)
        def selected_lstat(path, *args, **kwargs):
            return real_lstat(metadata_alias(path), *args, **kwargs)
        def selected_access(path, *args, **kwargs):
            return real_access(metadata_alias(path), *args, **kwargs)
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, environment, clear=True), mock.patch.object(RUNNER, 'validate_plan', return_value=plan), mock.patch.object(RUNNER, 'subprocess', isolated), mock.patch('os.stat', selected_stat), mock.patch('os.lstat', selected_lstat), mock.patch('os.access', selected_access), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = RUNNER.main([] if dry_run else self.args)
        self.assertEqual('', err.getvalue(), 'JDK diagnostics must not leak subprocess stderr')
        text = out.getvalue()
        self.assertNotIn(str(self.root), text)
        self.assertNotIn('synthetic-not-an-api-key', text)
        self.assertNotIn('Traceback', text)
        records = [json.loads(line) for line in self.trace.read_text().splitlines()] if self.trace.exists() else []
        return code, json.loads(text), records

    def assert_rejected_without_invocation(self, home):
        code, report, records = self.execute(home)
        self.assertEqual(2, code, 'Explicit invalid JAVA_HOME must fail closed, not use legacy or PATH')
        self.assertEqual({'accepted': False, 'failureCode': 'CONFIGURATION'}, report)
        self.assertEqual([], self.requested, 'Validate both tools before any compiler/JVM invocation')
        self.assertEqual([], records)

    def test_JDK01_explicit_canonical_home_selects_both_real_child_standins(self):
        code, report, records = self.execute(self.configured)
        self.assertEqual(0, code)
        self.assertEqual('COMPLETED', report['status'])
        self.assertEqual([str(self.configured / 'bin' / name) for name in ('javac', 'java')], self.requested)
        self.assertEqual([{'home': 'CONFIGURED', 'tool': 'javac', 'compilerCredentialsBlank': True}, {'home': 'CONFIGURED', 'tool': 'java'}], records)

    def test_JDK02_absent_home_preserves_legacy_pair_without_PATH_selection(self):
        code, report, records = self.execute()
        self.assertEqual(0, code)
        self.assertEqual('COMPLETED', report['status'])
        self.assertEqual([str(LEGACY / 'bin' / name) for name in ('javac', 'java')], self.requested)
        self.assertEqual([{'home': 'LEGACY', 'tool': 'javac', 'compilerCredentialsBlank': True}, {'home': 'LEGACY', 'tool': 'java'}], records)

    def test_JDK03_explicit_empty_is_not_absent(self):
        self.assert_rejected_without_invocation('')

    def test_JDK04_relative_home_is_rejected_even_with_working_PATH_tools(self):
        self.assert_rejected_without_invocation('relative-jdk')

    def test_JDK05_missing_home_is_rejected(self):
        self.assert_rejected_without_invocation(self.root / 'missing-jdk')

    def test_JDK06_missing_java_rejects_before_compiler(self):
        (self.configured / 'bin/java').unlink()
        self.assert_rejected_without_invocation(self.configured)

    def test_JDK07_missing_javac_is_rejected(self):
        (self.configured / 'bin/javac').unlink()
        self.assert_rejected_without_invocation(self.configured)

    def test_JDK08_nonexecutable_java_rejects_before_compiler(self):
        (self.configured / 'bin/java').chmod(0o600)
        self.assert_rejected_without_invocation(self.configured)

    def test_JDK09_nonexecutable_javac_is_rejected(self):
        (self.configured / 'bin/javac').chmod(0o600)
        self.assert_rejected_without_invocation(self.configured)

    def test_JDK10_directory_instead_of_binary_is_rejected(self):
        (self.configured / 'bin/java').unlink()
        (self.configured / 'bin/java').mkdir()
        self.assert_rejected_without_invocation(self.configured)

    def test_JDK11_symlink_home_is_rejected(self):
        link = self.root / 'linked-jdk'
        link.symlink_to(self.configured, target_is_directory=True)
        self.assert_rejected_without_invocation(link)

    def test_JDK12_symlink_ancestor_is_rejected(self):
        link = self.root / 'linked-parent'
        link.symlink_to(self.root, target_is_directory=True)
        self.assert_rejected_without_invocation(link / self.configured.name)

    def test_JDK13_symlink_tool_is_rejected(self):
        (self.configured / 'bin/java').unlink()
        (self.configured / 'bin/java').symlink_to(self.legacy / 'bin/java')
        self.assert_rejected_without_invocation(self.configured)

    def test_JDK14_noncanonical_parent_traversal_is_rejected(self):
        self.assert_rejected_without_invocation(str(self.configured) + '/../' + self.configured.name)

    def test_JDK16_absent_default_missing_java_rejects_before_any_tool(self):
        (self.legacy / 'bin/java').unlink()
        self.assert_rejected_without_invocation(ABSENT)

    def test_JDK15_no_execution_dry_run_does_not_start_jdk_even_for_invalid_home(self):
        code, report, records = self.execute('', dry_run=True)
        self.assertEqual(0, code)
        self.assertEqual('DRY_RUN_NO_NETWORK', report['mode'])
        self.assertEqual([], self.requested)
        self.assertEqual([], records)


if __name__ == '__main__':
    unittest.main(verbosity=2)
