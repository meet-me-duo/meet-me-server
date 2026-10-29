#!/usr/bin/env python3
"""Root-only host boundary for immutable, explicitly approved releases.

The existing installer and launcher own the lifecycle lock and sticky V8 phase.
This helper never resets their phase or attempts an earlier image on failure.
"""

import base64
import datetime
from contextlib import contextmanager
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import zlib

ROOT = Path('/opt/meet-me')
STATE = ROOT / 'state'
LAUNCHER = Path('/opt/meet-me/guard/host-release-guard.sh')
UNIT = Path('/etc/systemd/system/meet-me-guarded-restart.service')
CONTRACT = STATE / 'automatic-release-contract.json'
HISTORICAL_BUNDLE = (
    'scripts/host-release-guard.sh', 'scripts/install-host-release-guard.sh',
    'scripts/deploy-release.sh', 'scripts/rollback-release.sh',
    'scripts/refresh-database-credential.sh', 'compose.guard.yml',
    'meet-me-guarded-restart.service', 'pipeline/approved_release.py',
    'pipeline/host-approved-release.py', 'pipeline/run-approved-release.py',
    'pipeline/publish_manifest.py', 'preflight/host-preflight.sh',
    'preflight/run-preflight.py', 'preflight/ReadOnlyFlywayProbe.java',
    'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml',
    'runtime-ai-policy.json',
)
PIPELINE_FILES = (
    'pipeline/release_completion.py', 'pipeline/approval_gate.py',
    'pipeline/transition_orchestrator.py', 'pipeline/release_pipeline.py', 'transition-approval.json',
)
BUNDLE = HISTORICAL_BUNDLE + PIPELINE_FILES
AUTH_BUNDLE = tuple(name for name in HISTORICAL_BUNDLE if name != 'runtime-ai-policy.json')
LEGACY_BUNDLE = tuple(name for name in AUTH_BUNDLE if name not in (
    'runtime-auth-mode', 'scripts/render-runtime-env.sh', 'compose.production.yml'))
ENV = {'PATH': '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
       'HOME': '/root', 'LANG': 'C.UTF-8'}


def _require(value):
    if not value:
        raise ValueError('UNSAFE_HOST_RELEASE')


def _runtime_policy_bytes(raw):
    """Nonsecret reviewed artifact shape only; historical reading never adopts an operating policy or clock."""
    def unique(pairs):
        result = {}
        for key, value in pairs:
            _require(key not in result)
            result[key] = value
        return result
    _require(type(raw) is bytes and len(raw) <= 65536)
    data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    _require(type(data) is dict and data.get("protocol") == "meetme-managed-ai-policy-v1")
    if data.get("state") == "UNCONFIGURED":
        _require(set(data) == {"protocol", "state"})
        return
    _require(data.get("state") == "CONFIGURED"
             and set(data) == {"protocol", "state", "unit", "roomCap", "minimumConfidence", "operations"})
    unit = data["unit"]
    _require(type(unit) is dict and set(unit) == {"code", "scale"})
    code = unit["code"]
    _require(type(code) is str and bool(code.strip()) and code == code.strip()
             and len(code.encode("utf-16-le")) // 2 <= 32 and not any(ch in code for ch in ("\x00", "\r", "\n")))
    _require(type(unit["scale"]) is int and 0 <= unit["scale"] <= 38)
    amount = lambda value: type(value) is str and re.fullmatch(r"0|[1-9][0-9]{0,37}", value)
    _require(amount(data["roomCap"]) and int(data["roomCap"]) > 0)
    threshold = data["minimumConfidence"]
    _require(type(threshold) in (float, int) and 0 <= threshold <= 1)
    operations = data["operations"]
    expected = {
        "PRECHECK_CLASSIFICATION": "c8d01d4a04aa077e0488400dc3b4791753b8428db48c6712aa78f903608a4dfc",
        "PRECHECK_COUNT": "658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307",
        "PRECHECK_GENERATION": "86e4593b78ec903e1c9b4bbbffe204d2b8343e1752d2b6353703ce3fb9b65714",
        "FINAL_PRIMARY": "0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04",
        "FINAL_FALLBACK": "9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab",
    }
    _require(type(operations) is list and len(operations) == len(expected))
    seen = set()
    for row in operations:
        _require(type(row) is dict and set(row) == {
            "operation", "profileFingerprint", "evidenceFingerprint", "maximumAmount", "validUntil"})
        _require(type(row["operation"]) is str and row["operation"] in expected and row["operation"] not in seen)
        seen.add(row["operation"])
        for key in ("profileFingerprint", "evidenceFingerprint"):
            _require(type(row[key]) is str and re.fullmatch(r"[0-9a-f]{64}", row[key]))
        _require(row["profileFingerprint"] == expected[row["operation"]])
        _require(amount(row["maximumAmount"]) and type(row["validUntil"]) is str
                 and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z", row["validUntil"]))
        datetime.datetime.fromisoformat(row["validUntil"].replace("Z", "+00:00"))


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def _hash(raw):
    return hashlib.sha256(raw).hexdigest()


def _safe(path, directory=False):
    """Check every ancestor without following an unsafe link."""
    path = Path(path)
    _require(path.is_absolute())
    for ancestor in reversed(path.parents):
        info = ancestor.lstat()
        _require(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
    info = path.lstat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    _require(kind(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
    return path


def _bytes(path, maximum=512 * 1024):
    _safe(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        _require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
        _require(info.st_size <= maximum)
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            data = stream.read(maximum + 1)
        _require(len(data) <= maximum)
        return data
    finally:
        os.close(fd)


def _json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            _require(key not in result)
            result[key] = value
        return result
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('UNSAFE_HOST_RELEASE')))


def _module(name, path):
    _safe(path)
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _validated(manifest):
    pure = _module('approved_release', Path(__file__).absolute().parent / 'approved_release.py')
    raw = {key: value for key, value in manifest.items() if key != 'imageRef'}
    validated = pure.validate_manifest(raw, {key: raw[key] for key in
                                             ('buildRunId', 'buildRunAttempt', 'imageRepository')})
    ENV['AWS_REGION'] = validated['imageRepository'].split('.')[3]
    return validated


def validate_deploy_facts(manifest, facts, action, approve_first_transition=False):
    pure = _module('approved_release', Path(__file__).absolute().parent / 'approved_release.py')
    return pure.validate_deploy_facts(manifest, facts, action, approve_first_transition)


def _run(arguments, timeout=30, strict_stderr=False):
    process = subprocess.Popen(arguments, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               env=ENV, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except BaseException:
        # Both an inner timeout and the outer SSM/GNU timeout terminate the subtree.
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            pass
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=5)
        raise
    _require(process.returncode == 0 and len(stdout) <= 65536)
    _require(not strict_stderr or not stderr)
    return stdout


def _interrupted(_signum, _frame):
    raise InterruptedError('UNSAFE_HOST_RELEASE')


def _current_release():
    _safe(ROOT, directory=True)
    _safe(ROOT / 'releases', directory=True)
    current = ROOT / 'current'
    _require(stat.S_ISLNK(current.lstat().st_mode) and current.lstat().st_uid == 0)
    target = current.resolve(strict=True)
    _require(target.parent == ROOT / 'releases')
    _require(re.fullmatch(r'[0-9a-f]{40}-[1-9][0-9]{0,19}(?:-[1-9][0-9]{0,19})?', target.name))
    return _safe(target, directory=True)


def _current_image():
    image = _run(['docker', 'inspect', '--format', '{{.Config.Image}}', 'meet-me-app'], 10).decode('utf-8').strip()
    _require(re.fullmatch(r'[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(?:\.cn)?/[A-Za-z0-9][A-Za-z0-9._/-]*@sha256:[0-9a-f]{64}', image))
    return image


def _unit(expected):
    _require(_bytes(UNIT) == expected)
    values = _run(['systemctl', 'show', 'meet-me-guarded-restart.service',
                   '--property=LoadState,UnitFileState,FragmentPath,DropInPaths'], 10).decode('utf-8')
    data = dict(line.split('=', 1) for line in values.splitlines() if '=' in line)
    _require(data == {'LoadState': 'loaded', 'UnitFileState': 'enabled',
                      'FragmentPath': str(UNIT), 'DropInPaths': ''})


def _release_bundle(release):
    marker = release / 'runtime-auth-mode'
    policy = release / 'runtime-ai-policy.json'
    pipeline_present = [os.path.lexists(release / name) for name in PIPELINE_FILES]
    if any(pipeline_present):
        _require(all(pipeline_present))
        for name in PIPELINE_FILES:
            _bytes(release / name, 131072)
        _bytes(release / 'public-api-contract.json', 131072)
    if not os.path.lexists(marker):
        _require(not os.path.lexists(policy) and not any(pipeline_present))
        return LEGACY_BUNDLE
    _require(_bytes(marker, 64) in (b'oauth-rs256-required', b'oauth-rs256-required\n'))
    if os.path.lexists(policy):
        _runtime_policy_bytes(_bytes(policy, 65536))
        return BUNDLE if any(pipeline_present) else HISTORICAL_BUNDLE
    _require(not any(pipeline_present))
    return AUTH_BUNDLE


def _installed_catalog(release):
    mapped = {
        'scripts/host-release-guard.sh': LAUNCHER,
        'scripts/install-host-release-guard.sh': ROOT / 'guard/install-host-release-guard.sh',
        'compose.guard.yml': ROOT / 'guard/compose.guard.yml',
        'meet-me-guarded-restart.service': ROOT / 'guard/meet-me-guarded-restart.service',
    }
    catalog = [{'path': name, 'sha256': _hash(_bytes(mapped.get(name, release / name)))}
               for name in sorted(_release_bundle(release))]
    _unit(_bytes(ROOT / 'guard/meet-me-guarded-restart.service'))
    return catalog


def _record():
    if not os.path.lexists(CONTRACT):
        return None
    record = _json(_bytes(CONTRACT, 65536))
    _require(type(record) is dict and set(record) == {
        'protocol', 'schemaFingerprint', 'guardFingerprint', 'history', 'currentImage',
        'sourceSha', 'buildRunId', 'buildRunAttempt'})
    _require(record['protocol'] == 'meet-me-automatic-release-v1')
    _require(type(record['sourceSha']) is str and re.fullmatch(r'[0-9a-f]{40}', record['sourceSha']))
    for key in ('schemaFingerprint', 'guardFingerprint'):
        _require(type(record[key]) is str and re.fullmatch(r'[0-9a-f]{64}', record[key]))
    for key in ('buildRunId', 'buildRunAttempt'):
        _require(type(record[key]) is str and re.fullmatch(r'[1-9][0-9]{0,19}', record[key]))
    _require(type(record['currentImage']) is str and re.fullmatch(
        r'[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(?:\.cn)?/[A-Za-z0-9][A-Za-z0-9._/-]*@sha256:[0-9a-f]{64}', record['currentImage']))
    _require(type(record['history']) is list)
    versions = tuple(row.get('version') if type(row) is dict else None for row in record['history'])
    catalogs = (tuple(map(str, range(1, 11))), tuple(map(str, range(1, 12))),
                tuple(map(str, range(1, 13))), tuple(map(str, range(1, 12))) + ('13',),
                tuple(map(str, range(1, 11))) + ('14', '15'),
                tuple(map(str, range(1, 12))) + ('14', '15'), tuple(map(str, range(1, 17))),
                tuple(map(str, range(1, 18))), tuple(map(str, range(1, 19))), tuple(map(str, range(1, 20))), tuple(map(str, range(1, 21))))
    _require(versions in catalogs)
    sparse = '14' in versions
    names = ('create_core_domain', 'add_anonymous_room_lifecycle', 'add_submission_gemini_pipeline',
             'add_matching_results', 'remove_meeting_duration', 'add_reliable_outbox_processing',
             'resume_delayed_matching_from_structuring', 'add_input_revision_rounds',
             'add_time_recommendations', 'add_bounded_analysis_invocations', 'add_accounts_and_participant_ownership')
    scripts = {str(version): f'V{version}__{name}.sql' for version, name in enumerate(names, 1)}
    scripts.update({
        '12': 'V12__add_guest_reaccess.sql', '13': 'V13__add_submission_clarifications.sql',
        '14': 'V14__add_operator_access_audits.sql', '15': 'V15__add_parser_transport_observations.sql',
        '16': 'V16__add_pre_submission_checks.sql',
        '17': 'V17__add_durable_ai_room_budgets.sql',
        '18': 'V18__align_recommendation_selection_attendance.sql',
        '19': 'V19__add_confirmed_result_reviews.sql',
        '20': 'V20__add_account_room_history_index.sql',
    })
    for version, row in zip(versions, record['history']):
        _require(type(row) is dict and set(row) == {'version', 'script', 'checksum', 'success'})
        _require(row['success'] is True)
        _require(row['script'] == scripts[version] if sparse else
                 type(row['script']) is str and re.fullmatch(rf'V{version}__[A-Za-z0-9_]+\.sql', row['script']))
        _require(type(row['checksum']) is int and -(2 ** 31) <= row['checksum'] < 2 ** 31)
    return record


def _schema(release, rows):
    _safe(release / 'migrations', directory=True)
    _require({entry.name for entry in (release / 'migrations').iterdir()} == {row['script'] for row in rows})
    catalog = []
    for row in rows:
        raw = _bytes(release / 'migrations' / row['script'])
        crc = 0
        for line in re.split(r'\r\n|\r|\n', raw.decode('utf-8-sig')):
            crc = zlib.crc32(line.encode('utf-8'), crc)
        signed = crc if crc < 2 ** 31 else crc - 2 ** 32
        _require(signed == row['checksum'])
        catalog.append({'version': row['version'], 'script': row['script'], 'sha256': _hash(raw)})
    return _hash(_canonical(catalog))


def _read_state(manifest, verify_record=True):
    _require(os.geteuid() == 0)
    manifest = _validated(manifest)
    _safe(ROOT, directory=True)
    record = _record()
    installed = STATE / 'installation-manifest.json'
    if not os.path.lexists(installed):
        _require(record is None)
        for path in (STATE / 'phase', STATE / 'minimum-contract', LAUNCHER, ROOT / 'guard/compose.guard.yml'):
            _require(not os.path.lexists(path))
        phase, trusted = 'UNINSTALLED', False
    else:
        inventory = _json(_bytes(installed, 65536))
        _require(type(inventory) is dict and inventory.get('host_root') == str(ROOT) and inventory.get('version') == 1)
        _require(_hash(_bytes(LAUNCHER)) == inventory.get('launcher_sha256'))
        _require(_hash(_bytes(ROOT / 'guard/compose.guard.yml')) == inventory.get('override_sha256'))
        # The pinned existing launcher validates every stored wrapper and retired inode.
        phase = _run(['bash', '-c', 'set -euo pipefail; source "$1"; guard_manifest "$2"; guard_policy "$2/state/compatible-images.tsv"; guard_phase "$2"',
                      'approved-release-state', str(LAUNCHER), str(ROOT)], 30).decode('utf-8').strip()
        _require(phase in {'PRE_V8', 'V8_STARTED', 'READY'})
        trusted = True
        if record is not None and verify_record:
            release = _current_release()
            _require(_hash(_canonical(_installed_catalog(release))) == record['guardFingerprint'])
            _require(_schema(release, record['history']) == record['schemaFingerprint'])
    return {'protocol': 'meet-me-release-state-v1', 'automaticContract': record,
            'guardTrusted': trusted, 'phase': phase, 'currentImage': _current_image()}


def read_state(manifest):
    return _read_state(manifest)


def _archive(release, manifest):
    archive = release / '.approved-release.tar.gz'
    raw = _bytes(archive, 4 * 1024 * 1024)
    _require(_hash(raw) == manifest['archiveSha256'])
    release_bundle = _release_bundle(release)
    required = set(release_bundle) | {'migrations/' + row['script'] for row in manifest['migrations']}
    required.add('preflight/compiled/ReadOnlyFlywayProbe.class')
    total, seen, files = 0, set(), set()
    with tarfile.open(archive, 'r:gz') as bundle:
        for member in bundle:
            name = member.name.rstrip('/')
            path = PurePosixPath(name)
            _require(name and not path.is_absolute() and '..' not in path.parts and '.' not in path.parts)
            _require(str(path) == name and name not in seen and len(seen) < 256)
            seen.add(name)
            _require(member.isdir() or member.isfile())
            _require(not member.islnk() and not member.issym())
            _require(0 <= member.size <= 512 * 1024)
            total += member.size
            _require(total <= 4 * 1024 * 1024)
            if member.isfile():
                files.add(name)
                stream = bundle.extractfile(member)
                _require(stream is not None)
                _require(_bytes(release / name) == stream.read(512 * 1024 + 1))
            else:
                _safe(release / name, directory=True)
    _require(required <= files)
    catalog = [{'path': name, 'sha256': _hash(_bytes(release / name))} for name in sorted(release_bundle)]
    _require(_hash(_canonical(catalog)) == manifest['guardFingerprint'])
    _require(_schema(release, manifest['migrations']) == manifest['schemaFingerprint'])
    probe = _bytes(release / 'preflight/compiled/ReadOnlyFlywayProbe.class', 24576)
    _require(probe[:4] == bytes.fromhex('cafebabe'))


def _require_executable_helpers(release):
    for name in ('render-runtime-env.sh', 'export-certificate.sh'):
        path = _safe(release / 'scripts' / name)
        _require(path.stat().st_mode & stat.S_IXUSR and os.access(path, os.X_OK))


def _facts(manifest, attestation, release, post_deploy=False, maintenance_resume=False):
    _require(type(maintenance_resume) is bool)
    _require(not (post_deploy and maintenance_resume))
    if post_deploy:
        _require(_current_release() == release)
        _require(_hash(_canonical(_installed_catalog(release))) == manifest['guardFingerprint'])
        _require(_schema(release, manifest['migrations']) == manifest['schemaFingerprint'])
    state = _read_state(manifest, verify_record=not post_deploy)
    if maintenance_resume:
        _require(state['guardTrusted'] and state['phase'] == 'V8_STARTED'
                 and attestation['action'] == 'resume' and attestation['approveFirstTransition']
                 and attestation['refreshRuleStates'] == ['DISABLED', 'DISABLED']
                 and attestation['pendingCommands'] == 0)
    probe = base64.b64encode(_bytes(release / 'preflight/compiled/ReadOnlyFlywayProbe.class', 24576)).decode('ascii')
    command = ['bash', str(_safe(release / 'preflight/host-preflight.sh')), probe]
    if maintenance_resume:
        command += ['oauth-rs256-required', 'maintenance-resume', manifest['imageRef']]
    output = _run(command, 55, strict_stderr=True)
    runner = _module('release_preflight', release / 'preflight/run-preflight.py')
    observation = runner.summarize_invocation('Success', output.decode('utf-8'), '')
    _require(observation['flyway']['status'] == 'success')
    before = observation['host'].get('containerBefore', {})
    after = observation['host'].get('containerAfter', {})
    _require(before.get('present') is True and before.get('running') is (not maintenance_resume))
    _require(after.get('running') is (not maintenance_resume)
             and all(before.get(key) == after.get(key) for key in ('pid', 'restartCount')))
    if maintenance_resume:
        _require(observation['host'].get('phase') == 'V8_STARTED'
                 and observation['host'].get('minimum-contract') == 'input_revision_v8')
    _require(before.get('image') == state['currentImage'])
    # State is reread after the bounded reader so a concurrent transition fails closed.
    _require(_read_state(manifest, verify_record=not post_deploy) == state)
    return {key: state[key] for key in ('automaticContract', 'guardTrusted', 'phase', 'currentImage')} | {
        'history': observation['flyway']['history'],
        'refreshRuleStates': attestation['refreshRuleStates'], 'pendingCommands': attestation['pendingCommands']}


def _attestation(value, manifest):
    _require(type(value) is dict and set(value) == {
        'action', 'approveFirstTransition', 'refreshRuleStates', 'pendingCommands', 'manifestSha256'})
    _require(value['action'] in {'automatic', 'deploy', 'resume'} and type(value['action']) is str)
    _require(type(value['approveFirstTransition']) is bool)
    _require(type(value['pendingCommands']) is int and value['pendingCommands'] == 0)
    _require(type(value['refreshRuleStates']) is list and len(value['refreshRuleStates']) == 2)
    _require(all(type(state) is str and state in {'DISABLED', 'ENABLED'} for state in value['refreshRuleStates']))
    raw = {key: item for key, item in manifest.items() if key != 'imageRef'}
    _require(value['manifestSha256'] == _hash(_canonical(raw)))
    return value


def _atomic(path, data, mode=0o600):
    _safe(path.parent, directory=True)
    if os.path.lexists(path):
        _safe(path)
    fd, temporary = tempfile.mkstemp(prefix='.approved-release.', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(parent_fd)
        finally:
            os.close(parent_fd)
    finally:
        if os.path.lexists(temporary):
            os.unlink(temporary)


@contextmanager
def _lock(path):
    if path == Path('/run/lock/meet-me-release.lock'):
        # Ubuntu's fixed /var/lock -> /run/lock may be root-owned sticky 1777.
        # Sticky ownership protects this root-owned inode; no other path gets this exception.
        _safe(Path('/run'), directory=True)
        _safe(Path('/var'), directory=True)
        alias = Path('/var/lock').lstat()
        _require(stat.S_ISLNK(alias.st_mode) and alias.st_uid == 0)
        _require(Path('/var/lock').resolve(strict=True) == path.parent)
        directory = path.parent.lstat()
        _require(stat.S_ISDIR(directory.st_mode) and directory.st_uid == 0)
        mode = stat.S_IMODE(directory.st_mode)
        _require(not mode & 0o022 or mode == 0o1777)
        if os.path.lexists(path):
            info = path.lstat()
            _require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
    else:
        _safe(path.parent, directory=True)
        if os.path.lexists(path):
            _safe(path)
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        _require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
        deadline = time.monotonic() + 120
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                _require(time.monotonic() < deadline)
                time.sleep(0.1)
        yield
    finally:
        os.close(fd)


def _legacy_matches(release):
    mapping = {'scripts/host-release-guard.sh': LAUNCHER,
               'compose.guard.yml': ROOT / 'guard/compose.guard.yml',
               'scripts/install-host-release-guard.sh': ROOT / 'guard/install-host-release-guard.sh',
               'meet-me-guarded-restart.service': ROOT / 'guard/meet-me-guarded-restart.service'}
    for name, installed in mapping.items():
        if os.path.lexists(installed):
            _require(_bytes(installed) == _bytes(release / name))
    for candidate in (ROOT / 'releases').iterdir():
        _safe(candidate, directory=True)
        for name in ('deploy-release.sh', 'rollback-release.sh', 'refresh-database-credential.sh'):
            if os.path.lexists(STATE / 'installation-manifest.json'):
                _require(_bytes(candidate / 'scripts' / name) == _bytes(release / 'scripts' / name))
    if os.path.lexists(UNIT):
        _require(_bytes(UNIT) == _bytes(release / 'meet-me-guarded-restart.service'))


def deploy(manifest, attestation):
    _require(os.geteuid() == 0)
    manifest = _validated(manifest)
    attestation = _attestation(attestation, manifest)
    release = _safe(ROOT / 'releases' / manifest['releaseId'], directory=True)
    _archive(release, manifest)
    _require_executable_helpers(release)
    maintenance_resume = attestation['action'] == 'resume'
    facts = (_facts(manifest, attestation, release, maintenance_resume=True)
             if maintenance_resume else _facts(manifest, attestation, release))
    plan = validate_deploy_facts(manifest, facts, attestation['action'], attestation['approveFirstTransition'])
    _require(plan['mode'] in {'AUTO_READY', 'TRANSITION_APPROVED', 'MAINTENANCE_RESUME_APPROVED'})
    # This lock serializes helpers; it is deliberately different from the lifecycle lock.
    with _lock(Path('/run/meet-me-approved-release.lock')):
        facts = (_facts(manifest, attestation, release, maintenance_resume=True)
                 if maintenance_resume else _facts(manifest, attestation, release))
        plan = validate_deploy_facts(manifest, facts, attestation['action'], attestation['approveFirstTransition'])
        _require(plan['mode'] in {'AUTO_READY', 'TRANSITION_APPROVED', 'MAINTENANCE_RESUME_APPROVED'})
        first = plan['mode'] in {'TRANSITION_APPROVED', 'MAINTENANCE_RESUME_APPROVED'}
        if first:
            _legacy_matches(release)
        if not os.path.lexists(STATE):
            STATE.mkdir(mode=0o700)
        _safe(STATE, directory=True)
        evidence = _hash(_canonical(attestation))
        # Only short state writes hold the lifecycle lock; release before either shell entrypoint.
        with _lock(Path('/run/lock/meet-me-release.lock')):
            _require((_facts(manifest, attestation, release, maintenance_resume=True)
                      if maintenance_resume else _facts(manifest, attestation, release)) == facts)
            images = [manifest['imageRef']]
            if not first and facts['currentImage'] != manifest['imageRef']:
                images.append(facts['currentImage'])
            policy = ''.join(image + '\tinput_revision_v8\t' + evidence + '\n' for image in images)
            _atomic(STATE / 'compatible-images.tsv', policy.encode('utf-8'))
        if first:
            prerequisites = {'version': 1, 'host_root': str(ROOT), 'automation_paused': True,
                             'legacy_invocations_drained': True, 'restart_policy_reviewed': True,
                             'operator_evidence_sha256': evidence}
            prerequisite_path = STATE / 'approved-release-prerequisites.json'
            _atomic(prerequisite_path, _canonical(prerequisites) + b'\n')
            # Actual FD absence, writer stop and restart disabling are enforced by this installer.
            _run(['bash', str(release / 'scripts/install-host-release-guard.sh'), str(ROOT),
                  str(release), str(prerequisite_path)], 180)
            _atomic(ROOT / 'guard/install-host-release-guard.sh',
                    _bytes(release / 'scripts/install-host-release-guard.sh'), 0o750)
            _atomic(UNIT, _bytes(release / 'meet-me-guarded-restart.service'), 0o644)
            _run(['systemctl', 'daemon-reload'], 30)
            _run(['systemctl', 'enable', 'meet-me-guarded-restart.service'], 30)
            _unit(_bytes(release / 'meet-me-guarded-restart.service'))
        _run(['/opt/meet-me/guard/host-release-guard.sh', 'deploy', str(release), manifest['imageRef']], 900)
        # A failure here leaves the real guard phase and deployed image untouched for review.
        with _lock(Path('/run/lock/meet-me-release.lock')):
            final = _facts(manifest, attestation, release, post_deploy=True)
            history = [dict(row, success=True) for row in manifest['migrations']]
            _require(final['phase'] == 'READY' and final['guardTrusted'])
            _require(final['currentImage'] == manifest['imageRef'] and final['history'] == history)
            _require(_current_release() == release)
            _require(_hash(_canonical(_installed_catalog(release))) == manifest['guardFingerprint'])
            _require(_schema(release, manifest['migrations']) == manifest['schemaFingerprint'])
            record = {'protocol': 'meet-me-automatic-release-v1', 'schemaFingerprint': manifest['schemaFingerprint'],
                      'guardFingerprint': manifest['guardFingerprint'], 'history': history,
                      'currentImage': manifest['imageRef'], 'sourceSha': manifest['sourceSha'],
                      'buildRunId': manifest['buildRunId'], 'buildRunAttempt': manifest['buildRunAttempt']}
            _atomic(CONTRACT, _canonical(record) + b'\n')
    return {'protocol': 'meet-me-approved-release-result-v1', 'mode': plan['mode'],
            'sourceSha': manifest['sourceSha'], 'currentImage': manifest['imageRef'], 'phase': 'READY'}


def _decode(value, maximum):
    _require(type(value) is str and len(value) <= ((maximum + 2) // 3) * 4)
    raw = base64.b64decode(value, validate=True)
    _require(len(raw) <= maximum and base64.b64encode(raw).decode('ascii') == value)
    return _json(raw)


def main():
    _require(os.geteuid() == 0)
    _safe(Path(__file__).absolute())
    os.umask(0o077)
    signal.signal(signal.SIGTERM, _interrupted)
    signal.signal(signal.SIGINT, _interrupted)
    arguments = sys.argv[1:]
    if len(arguments) == 2 and arguments[0] == '--read-state':
        result = read_state(_decode(arguments[1], 65536))
    elif len(arguments) == 3 and arguments[0] == '--deploy':
        result = deploy(_decode(arguments[1], 65536), _decode(arguments[2], 8192))
    else:
        raise ValueError('UNSAFE_HOST_RELEASE')
    print(_canonical(result).decode('utf-8'))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        # Subprocess diagnostics, environment and provider/user data are never emitted.
        print('UNSAFE_HOST_RELEASE', file=sys.stderr)
        sys.exit(1)
