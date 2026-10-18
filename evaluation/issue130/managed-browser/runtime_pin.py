#!/usr/bin/env python3
"""Immutable, test-only managed browser runtime validation. Never calls a provider."""
import argparse
import copy
import hashlib
import json
import os
import signal
import shutil
import subprocess
import time
from pathlib import Path, PurePosixPath
import re
import stat
import zipfile

SERVER_HEAD = '6046baf66e49e9382248cd3fb8adb0a8879a7e40'
POLICY_ORIGIN = '27de97e137d23a2920d425ea521ee33ec0996a8d'
OVERLAYS = {
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt': 'a723af8f453ad56770d0d141e6f222f21bae3729b245f735bb67e68fe794ece5',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt': '7e898fcdc0476739ff855f8ae2f62f0d32d5b4660ac58527efe55312cf7af53f',
}
GRAPH = {
    'provider': 'com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider',
    'classifier': 'com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier',
    'budget': 'com.meetme.server.shared.application.service.DurableAiBudgetService',
    'repository': 'com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository',
    'gate': 'com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate',
    'worker': 'com.meetme.server.precheck.application.service.PreSubmissionCheckWorker',
    'fence': 'com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence',
}
FIXTURE = 'issue130-managed-browser-v1'
SCHEMA = {'schemaVersion', 'fixture', 'serverHead', 'policyOriginHead', 'overlays', 'sourceFiles',
          'runtimeFiles', 'classpath', 'fixtureClasses', 'graph'}
FIXTURE_PREFIXES = (
    'com/meetme/integration/managed/Issue130ManagedBrowserServer',
    'com/meetme/integration/managed/ManagedBrowserLoopbackGuard',
    'com/meetme/server/account/adapter/input/web/AuthEndToEndPostgresTest',
    'com/meetme/server/coordination/adapter/input/web/Issue131CanonicalTlsProxy',
)
REQUIRED_CLASSES = {'fixtures/' + name + '.class' for name in FIXTURE_PREFIXES[:2]}
DIGEST_KEYS = ('manifestSha256', 'sourceSha256', 'runtimeSha256', 'classpathSha256')


def fail(code='INVALID_MANIFEST'):
    raise ValueError(code)


def digest(path):
    hasher = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(block)
    return hasher.hexdigest()


def projection(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail()
        result[key] = value
    return result


def _json(path):
    if any(part.is_symlink() for part in (path.absolute(), *path.absolute().parents)):
        fail()
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
        fail()
    try:
        return json.loads(path.read_bytes(), object_pairs_hook=_object, parse_constant=lambda _: fail())
    except (ValueError, UnicodeError, OSError):
        fail()


def _relative(value):
    if not isinstance(value, str) or not value or '\\' in value or '\x00' in value:
        return False
    p = PurePosixPath(value)
    return not p.is_absolute() and value == p.as_posix() and all(x not in ('', '.', '..') for x in value.split('/'))


def _file_map(value):
    if not isinstance(value, dict) or not value or len(value) > 30000:
        fail()
    for name, expected in value.items():
        if not _relative(name) or not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected):
            fail()


def _inventory(root):
    if root.is_symlink() or not root.is_dir():
        fail()
    result = {}
    for p in root.rglob('*'):
        if p.is_symlink():
            fail()
        mode = p.stat().st_mode
        if stat.S_ISREG(mode):
            if p.stat().st_size > 512 * 1024 * 1024:
                fail()
            result[p.relative_to(root).as_posix()] = digest(p)
        elif not stat.S_ISDIR(mode):
            fail()
    return result


def _fixture_class(name):
    return any(name == prefix + '.class' or (name.startswith(prefix + '$') and name.endswith('.class'))
               for prefix in FIXTURE_PREFIXES)


def _forbidden_class(name):
    return name.endswith('.class') and any(bad in name for bad in (
        'NativePrecheckProvider', 'PrecheckNativeBeans', 'PreSubmissionCheckNativeBrowserServer',
        'HostIntegrationServer', 'PreSubmissionCheckNativeBeans'))


def validate_manifest(path: Path) -> dict:
    try:
        path = Path(path)
        value = _json(path)
        if not isinstance(value, dict) or set(value) != SCHEMA:
            fail()
        if type(value['schemaVersion']) is not int or value['schemaVersion'] != 1 or value['fixture'] != FIXTURE:
            fail()
        if value['serverHead'] != SERVER_HEAD or value['policyOriginHead'] != POLICY_ORIGIN or value['overlays'] != OVERLAYS:
            fail()
        if value['graph'] != GRAPH:
            fail('MANAGED_GRAPH_REQUIRED')
        _file_map(value['sourceFiles'])
        _file_map(value['runtimeFiles'])
        if any(value['sourceFiles'].get(name) != expected for name, expected in OVERLAYS.items()):
            fail('SOURCE_PIN_MISMATCH')
        if _inventory(path.parent / 'source') != value['sourceFiles']:
            fail('SOURCE_PIN_MISMATCH')
        runtime = path.parent / 'runtime'
        actual = _inventory(runtime)
        if actual != value['runtimeFiles']:
            extras = set(actual) - set(value['runtimeFiles'])
            fail('FORBIDDEN_FIXTURE_CLASS' if any(n.endswith('.class') for n in extras) else 'RUNTIME_PIN_MISMATCH')
        fixtures = value['fixtureClasses']
        actual_fixtures = sorted(n for n in actual if n.startswith('fixtures/') and n.endswith('.class'))
        if fixtures != actual_fixtures or not REQUIRED_CLASSES.issubset(set(actual_fixtures)):
            fail('FORBIDDEN_FIXTURE_CLASS')
        if any(not _fixture_class(n.removeprefix('fixtures/')) for n in actual_fixtures):
            fail('FORBIDDEN_FIXTURE_CLASS')
        for name in actual:
            if _forbidden_class(name):
                fail('FORBIDDEN_FIXTURE_CLASS')
        cp = value['classpath']
        if not isinstance(cp, list) or not cp or len(cp) > 1024 or any(not _relative(n) for n in cp) or len(cp) != len(set(cp)):
            fail('CLASSPATH_PIN_MISMATCH')
        for entry in cp:
            target = runtime / entry
            if target.is_dir():
                if not any(n.startswith(entry + '/') for n in actual):
                    fail('CLASSPATH_PIN_MISMATCH')
            elif entry not in actual or not entry.endswith('.jar'):
                fail('CLASSPATH_PIN_MISMATCH')
            else:
                try:
                    with zipfile.ZipFile(target) as jar:
                        if any(_forbidden_class(n) or _fixture_class(n) for n in jar.namelist()):
                            fail('FORBIDDEN_FIXTURE_CLASS')
                except zipfile.BadZipFile:
                    fail('CLASSPATH_PIN_MISMATCH')
        if not all(any(name == entry or name.startswith(entry + '/') for entry in cp) for name in actual):
            fail('CLASSPATH_PIN_MISMATCH')
        return dict(copy.deepcopy(value), manifestSha256=digest(path), sourceSha256=projection(value['sourceFiles']),
                    runtimeSha256=projection(value['runtimeFiles']), classpathSha256=projection(cp))
    except ValueError:
        raise
    except (OSError, TypeError, KeyError, zipfile.LargeZipFile):
        fail()


def validate_readiness(manifest: dict, observed: dict) -> dict:
    expected_keys = {'fixture', 'production', 'managed', 'primaryProviderFake', 'managedEnabled', 'providerEnabled',
                     'routingEnabled', 'classifierEnabled', 'graph', 'outbound', 'credentials', *DIGEST_KEYS}
    if not isinstance(observed, dict) or set(observed) != expected_keys:
        fail()
    booleans = {'production': False, 'managed': True, 'primaryProviderFake': False,
                'managedEnabled': True, 'providerEnabled': True, 'routingEnabled': True, 'classifierEnabled': True}
    if (observed['fixture'] != FIXTURE or observed['graph'] != GRAPH or
            any(type(observed[k]) is not bool or observed[k] is not v for k, v in booleans.items())):
        fail('MANAGED_GRAPH_REQUIRED')
    if any(not isinstance(observed[k], str) or observed[k] != manifest.get(k) for k in DIGEST_KEYS):
        fail('READINESS_PIN_MISMATCH')
    outbound = observed['outbound']
    if not isinstance(outbound, dict) or set(outbound) != {'mode', 'installedBeforeApplication', 'negativeProbeDenied', 'nonLoopbackConnections'}:
        fail()
    if (outbound['mode'] != 'LOOPBACK_DENY_ALL_OTHER' or outbound['installedBeforeApplication'] is not True or
            outbound['negativeProbeDenied'] is not True or type(outbound['nonLoopbackConnections']) is not int or
            outbound['nonLoopbackConnections'] != 0):
        fail('OUTBOUND_DENY_REQUIRED')
    credentials = observed['credentials']
    if not isinstance(credentials, dict) or set(credentials) != {'syntheticOnly', 'realEnvironmentKeysInherited'}:
        fail()
    if credentials['syntheticOnly'] is not True or credentials['realEnvironmentKeysInherited'] is not False:
        fail('SYNTHETIC_CREDENTIALS_REQUIRED')
    return {'ready': True}


def _launch(path, work, verified):
    if not work.is_absolute() or not any(work.is_relative_to(root) for root in (Path('/tmp'), Path('/workspace'))) or work.exists() or work.is_symlink():
        fail()
    runtime = path.absolute().parent / 'runtime'
    toolchain = _json(runtime / 'resources/managed-browser-toolchain.json')
    java = Path(toolchain['java']['path'])
    javac = Path(toolchain['javac']['path'])
    for tool, record in ((java, toolchain['java']), (javac, toolchain['javac'])):
        if not tool.is_absolute() or not tool.is_file() or tool.is_symlink() or digest(tool) != record['sha256']:
            fail('RUNTIME_PIN_MISMATCH')
    if java.parent != javac.parent:
        fail('RUNTIME_PIN_MISMATCH')
    work.mkdir(mode=0o700)
    home = work / 'home'
    temporary = work / 'tmp'
    home.mkdir(mode=0o700)
    temporary.mkdir(mode=0o700)
    (home / '.testcontainers.properties').write_text('pull.policy=com.meetme.integration.managed.Issue130ManagedBrowserServer$LocalOnlyPullPolicy\n')
    environment = {
        'PATH': str(java.parent) + ':/usr/bin:/bin', 'HOME': str(home), 'TMPDIR': str(temporary),
        'DOCKER_HOST': 'unix:///var/run/docker.sock', 'TESTCONTAINERS_HOST_OVERRIDE': '127.0.0.1',
        'TESTCONTAINERS_RYUK_DISABLED': 'true', 'TESTCONTAINERS_CHECKS_DISABLE': 'true',
        'OPENAI_API_KEY': '', 'GEMINI_API_KEY': '',
    }
    # Verify again after preparing only the separate work directory, immediately before spawn.
    if validate_manifest(path) != verified:
        fail('RUNTIME_PIN_MISMATCH')
    command = [str(java), '-Djava.security.manager=com.meetme.integration.managed.ManagedBrowserLoopbackGuard',
               '-Djava.net.preferIPv4Stack=true', '-Duser.home=' + str(home), '-Djava.io.tmpdir=' + str(temporary),
               '-Dmeetme.fixture.manifest-sha256=' + verified['manifestSha256'],
               '-cp', os.pathsep.join(str(runtime / entry) for entry in verified['classpath']),
               'com.meetme.integration.managed.Issue130ManagedBrowserServer', str(path.absolute()), str(work)]
    old_handlers = {}
    child = None
    stopping_at = None
    def stop(signum, frame):
        nonlocal stopping_at
        (work / 'stop').touch(exist_ok=True)
        if stopping_at is None:
            stopping_at = time.monotonic()
            if child is not None and child.poll() is None:
                child.terminate()
    try:
        for signum in (signal.SIGTERM, signal.SIGINT):
            old_handlers[signum] = signal.signal(signum, stop)
        descriptor = os.open(work / 'backend.log', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'wb') as log:
            child = subprocess.Popen(command, cwd=work, env=environment, stdin=subprocess.DEVNULL,
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            (work / 'launcher-process.json').write_text(json.dumps({'pid': child.pid, 'manifestSha256': verified['manifestSha256']}))
            while child.poll() is None:
                if stopping_at is not None and time.monotonic() - stopping_at > 20:
                    os.killpg(child.pid, signal.SIGKILL)
                    break
                time.sleep(.2)
            return child.wait()
    finally:
        if child is not None and child.poll() is None:
            (work / 'stop').touch(exist_ok=True)
            try:
                child.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
        for signum, handler in old_handlers.items():
            signal.signal(signum, handler)
        shutil.rmtree(home, ignore_errors=True)
        shutil.rmtree(temporary, ignore_errors=True)


class _Parser(argparse.ArgumentParser):
    def error(self, message):
        fail()


def main(argv=None) -> int:
    try:
        parser = _Parser(add_help=False)
        parser.add_argument('--manifest', required=True, type=Path)
        parser.add_argument('--verify-only', action='store_true')
        parser.add_argument('--readiness', type=Path)
        parser.add_argument('--work-dir', type=Path)
        args = parser.parse_args(argv)
        if args.verify_only == bool(args.work_dir) or (args.readiness and not args.verify_only):
            fail()
        result = validate_manifest(args.manifest)
        safe = {k: result[k] for k in ('fixture', 'serverHead', 'policyOriginHead', *DIGEST_KEYS)}
        if args.verify_only:
            if args.readiness:
                safe.update(validate_readiness(result, _json(args.readiness)))
            print(json.dumps(dict(verified=True, **safe), sort_keys=True))
            return 0
        return _launch(args.manifest, args.work_dir, result)
    except (ValueError, OSError, KeyError, TypeError) as error:
        allowed = {'INVALID_MANIFEST','SOURCE_PIN_MISMATCH','RUNTIME_PIN_MISMATCH','CLASSPATH_PIN_MISMATCH',
                   'FORBIDDEN_FIXTURE_CLASS','MANAGED_GRAPH_REQUIRED','OUTBOUND_DENY_REQUIRED',
                   'SYNTHETIC_CREDENTIALS_REQUIRED','READINESS_PIN_MISMATCH'}
        code = str(error) if isinstance(error, ValueError) and str(error) in allowed else 'INVALID_MANIFEST'
        print(json.dumps({'verified': False, 'error': code}, sort_keys=True))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
