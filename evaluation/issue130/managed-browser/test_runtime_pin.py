"""Independent protocol-only pins/readiness tests; no claim of actual Spring/PG/browser execution."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('managed_runtime_pin_target', ROOT / 'runtime_pin.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SOURCE = ROOT.parents[2]
SERVER = '6046baf66e49e9382248cd3fb8adb0a8879a7e40'
POLICY = '27de97e137d23a2920d425ea521ee33ec0996a8d'
OVERLAYS = {
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt': 'a723af8f453ad56770d0d141e6f222f21bae3729b245f735bb67e68fe794ece5',
    'src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt': '7e898fcdc0476739ff855f8ae2f62f0d32d5b4660ac58527efe55312cf7af53f',
}
GRAPH = dict(
    provider='com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider',
    classifier='com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier',
    budget='com.meetme.server.shared.application.service.DurableAiBudgetService',
    repository='com.meetme.server.shared.adapter.output.persistence.JdbcDurableAiBudgetRepository',
    gate='com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate',
    worker='com.meetme.server.precheck.application.service.PreSubmissionCheckWorker',
    fence='com.meetme.server.shared.adapter.output.persistence.JdbcAiBudgetExecutionFence')
SERVER_CLASS = 'fixtures/com/meetme/integration/managed/Issue130ManagedBrowserServer.class'
GUARD_CLASS = 'fixtures/com/meetme/integration/managed/ManagedBrowserLoopbackGuard.class'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def projection(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


class PinContract(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='issue130-managed-pin-synthetic-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'source'
        self.runtime = self.root / 'runtime'
        self.source.mkdir()
        self.runtime.mkdir()
        for name, digest in OVERLAYS.items():
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(SOURCE / name, target)
            self.assertEqual(digest, sha(target), 'public preapproved source overlay fixture must match')
        for name in ('classes/com/meetme/server/MeetMeServerApplication.class', SERVER_CLASS, GUARD_CLASS,
                     'fixtures/com/meetme/server/account/adapter/input/web/AuthEndToEndPostgresTest.class',
                     'fixtures/com/meetme/server/coordination/adapter/input/web/Issue131CanonicalTlsProxy.class'):
            target = self.runtime / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b'PROTOCOL_ONLY_SYNTHETIC_NOT_EXECUTABLE_CLASS')
        jar = self.runtime / 'lib/dependency.jar'
        jar.parent.mkdir()
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('synthetic/Dependency.class', b'PROTOCOL_ONLY')
        self.manifest = dict(schemaVersion=1, fixture='issue130-managed-browser-v1', serverHead=SERVER,
                             policyOriginHead=POLICY, overlays=copy.deepcopy(OVERLAYS), sourceFiles={},
                             runtimeFiles={}, classpath=['classes', 'fixtures', 'lib/dependency.jar'],
                             fixtureClasses=[], graph=copy.deepcopy(GRAPH))
        self.path = self.root / 'manifest.json'
        self.refresh()

    def refresh(self):
        self.manifest['sourceFiles'] = {str(p.relative_to(self.source)): sha(p) for p in sorted(self.source.rglob('*')) if p.is_file()}
        self.manifest['runtimeFiles'] = {str(p.relative_to(self.runtime)): sha(p) for p in sorted(self.runtime.rglob('*')) if p.is_file()}
        self.manifest['fixtureClasses'] = sorted(k for k in self.manifest['runtimeFiles'] if k.startswith('fixtures/') and k.endswith('.class'))
        self.write()

    def write(self):
        self.path.write_text(json.dumps(self.manifest, sort_keys=True, indent=2) + '\n')

    def validated(self):
        try:
            return MODULE.validate_manifest(self.path)
        except Exception:
            self.fail('MLB_PROTOCOL_VALID_MANIFEST_MUST_VALIDATE_NOT_START_APP')

    def reject(self, code):
        try:
            MODULE.validate_manifest(self.path)
        except Exception as error:
            self.assertIsInstance(error, ValueError, 'missing behavior must become a typed safe rejection')
            self.assertEqual(code, str(error))
            self.assertNotIn(str(self.root), str(error))
        else:
            self.fail('MLB_INVALID_MANIFEST_MUST_FAIL_CLOSED')

    def readiness(self):
        return dict(fixture='issue130-managed-browser-v1', production=False, managed=True, primaryProviderFake=False,
                    managedEnabled=True, providerEnabled=True, routingEnabled=True, classifierEnabled=True,
                    graph=copy.deepcopy(GRAPH), manifestSha256=sha(self.path),
                    sourceSha256=projection(self.manifest['sourceFiles']), runtimeSha256=projection(self.manifest['runtimeFiles']),
                    classpathSha256=projection(self.manifest['classpath']),
                    outbound=dict(mode='LOOPBACK_DENY_ALL_OTHER', installedBeforeApplication=True,
                                  negativeProbeDenied=True, nonLoopbackConnections=0),
                    credentials=dict(syntheticOnly=True, realEnvironmentKeysInherited=False))

    def reject_readiness(self, value, code):
        expected = dict(self.manifest, manifestSha256=sha(self.path),
                        sourceSha256=projection(self.manifest['sourceFiles']), runtimeSha256=projection(self.manifest['runtimeFiles']),
                        classpathSha256=projection(self.manifest['classpath']))
        try:
            MODULE.validate_readiness(expected, value)
        except Exception as error:
            self.assertIsInstance(error, ValueError)
            self.assertEqual(code, str(error))
        else:
            self.fail('MLB_UNPROVEN_MANAGED_READINESS_MUST_BE_REJECTED')

    def test_MLP01_positive_is_file_only_and_binds_all_four_digests(self):
        with mock.patch.object(subprocess, 'Popen', side_effect=AssertionError('NO_PROCESS_IN_VERIFY')), \
                mock.patch.object(socket, 'create_connection', side_effect=AssertionError('NO_NETWORK_IN_VERIFY')):
            result = self.validated()
        self.assertEqual(dict(self.manifest, manifestSha256=sha(self.path),
                              sourceSha256=projection(self.manifest['sourceFiles']), runtimeSha256=projection(self.manifest['runtimeFiles']),
                              classpathSha256=projection(self.manifest['classpath'])), result)
        self.assertIsNot(self.manifest, result)

    def test_MLP02_positive_readiness_is_attestation_consistency_only(self):
        value = self.validated()
        try:
            result = MODULE.validate_readiness(value, self.readiness())
        except Exception:
            self.fail('MLB_EXACT_PINNED_MANAGED_ATTESTATION_MUST_VALIDATE')
        self.assertEqual({'ready': True}, result)

    def test_MLP03_verify_only_cli_safe_exact_fields_and_no_start(self):
        stdout = io.StringIO()
        try:
            with contextlib.redirect_stdout(stdout), mock.patch.object(subprocess, 'Popen', side_effect=AssertionError('VERIFY_NO_CHILD')):
                code = MODULE.main(['--manifest', str(self.path), '--verify-only'])
        except Exception:
            self.fail('MLB_VERIFY_ONLY_CLI_MUST_NOT_START_A_PROCESS')
        result = json.loads(stdout.getvalue())
        self.assertEqual(0, code)
        self.assertEqual({'verified', 'fixture', 'serverHead', 'policyOriginHead', 'manifestSha256', 'sourceSha256', 'runtimeSha256', 'classpathSha256'}, set(result))
        self.assertTrue(result['verified'])
        self.assertNotIn(str(self.root), stdout.getvalue())

    def test_MLP04_invalid_cli_is_typed_safe_exit2_without_traceback(self):
        stdout = io.StringIO()
        try:
            with contextlib.redirect_stdout(stdout):
                code = MODULE.main(['--SYNTHETIC_PRIVATE_PATH'])
        except Exception:
            self.fail('MLB_INVALID_CLI_MUST_RETURN_SAFE_EXIT2')
        self.assertEqual(2, code)
        self.assertEqual({'verified': False, 'error': 'INVALID_MANIFEST'}, json.loads(stdout.getvalue()))
        self.assertNotIn('SYNTHETIC_PRIVATE', stdout.getvalue())

    def test_MLP05_fixed_identity_and_exact_top_schema(self):
        self.manifest['serverHead'] = '0' * 40
        self.write()
        self.reject('INVALID_MANIFEST')

    def test_MLP06_bool_version_is_not_integer_version(self):
        self.manifest['schemaVersion'] = True
        self.write()
        self.reject('INVALID_MANIFEST')

    def test_MLP07_unknown_field_and_duplicate_json_keys_are_rejected(self):
        text = self.path.read_text()
        self.path.write_text(text[:-2] + ',"schemaVersion":1}\n')
        self.reject('INVALID_MANIFEST')

    def test_MLP08_relative_path_traversal_is_not_a_pin(self):
        self.manifest['runtimeFiles']['../SYNTHETIC_PRIVATE'] = '0' * 64
        self.write()
        self.reject('INVALID_MANIFEST')

    def test_MLP09_changed_source_byte_is_not_approved_overlay(self):
        (self.source / next(iter(OVERLAYS))).write_bytes(b'SOURCE_CHANGED')
        self.reject('SOURCE_PIN_MISMATCH')

    def test_MLP10_changed_runtime_byte_is_not_immutable_runtime(self):
        (self.runtime / SERVER_CLASS).write_bytes(b'RUNTIME_CHANGED')
        self.reject('RUNTIME_PIN_MISMATCH')

    def test_MLP11_unlisted_runtime_file_is_not_allowed(self):
        (self.runtime / 'unlisted-resource.txt').write_text('synthetic')
        self.reject('RUNTIME_PIN_MISMATCH')

    def test_MLP12_missing_runtime_file_is_not_allowed(self):
        (self.runtime / SERVER_CLASS).unlink()
        self.reject('RUNTIME_PIN_MISMATCH')

    def test_MLP13_external_classpath_and_duplicates_are_not_allowed(self):
        self.manifest['classpath'].append('/SYNTHETIC_PRIVATE/classes')
        self.write()
        self.reject('CLASSPATH_PIN_MISMATCH')

    def test_MLP14_empty_classpath_directory_is_not_complete(self):
        (self.runtime / 'empty').mkdir()
        self.manifest['classpath'].append('empty')
        self.write()
        self.reject('CLASSPATH_PIN_MISMATCH')

    def test_MLP15_old_primary_fake_class_forbidden_in_main_classpath(self):
        target = self.runtime / 'classes/com/meetme/server/submission/adapter/input/web/NativePrecheckProvider.class'
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'OLD_FAKE')
        self.refresh()
        self.reject('FORBIDDEN_FIXTURE_CLASS')

    def test_MLP16_old_primary_fake_class_forbidden_in_jar(self):
        with zipfile.ZipFile(self.runtime / 'lib/dependency.jar', 'a') as jar:
            jar.writestr('com/meetme/server/submission/adapter/input/web/PrecheckNativeBeans.class', b'OLD_FAKE')
        self.refresh()
        self.reject('FORBIDDEN_FIXTURE_CLASS')

    def test_MLP17_required_fixture_guard_cannot_be_omitted(self):
        (self.runtime / GUARD_CLASS).unlink()
        self.refresh()
        self.reject('FORBIDDEN_FIXTURE_CLASS')

    def test_MLP18_symlink_source_cannot_be_canonical(self):
        target = self.source / next(iter(OVERLAYS))
        replacement = self.root / 'public-copy'
        shutil.copyfile(target, replacement)
        target.unlink()
        target.symlink_to(replacement)
        self.reject('INVALID_MANIFEST')

    def test_MLP19_all_on_flags_and_real_provider_required(self):
        for key, value in [('managed', False), ('primaryProviderFake', True), ('managedEnabled', False),
                           ('providerEnabled', False), ('routingEnabled', False), ('classifierEnabled', False)]:
            observed = self.readiness()
            observed[key] = value
            self.reject_readiness(observed, 'MANAGED_GRAPH_REQUIRED')

    def test_MLP20_real_ultimate_target_graph_required(self):
        observed = self.readiness()
        observed['graph']['provider'] = 'NativePrecheckProvider'
        self.reject_readiness(observed, 'MANAGED_GRAPH_REQUIRED')

    def test_MLP21_readiness_digest_must_match_validated_manifest(self):
        observed = self.readiness()
        observed['runtimeSha256'] = '0' * 64
        self.reject_readiness(observed, 'READINESS_PIN_MISMATCH')

    def test_MLP22_egress_barrier_must_precede_application_and_negative_probe(self):
        for key, value in [('installedBeforeApplication', False), ('negativeProbeDenied', False), ('nonLoopbackConnections', 1), ('nonLoopbackConnections', True)]:
            observed = self.readiness()
            observed['outbound'][key] = value
            self.reject_readiness(observed, 'OUTBOUND_DENY_REQUIRED')

    def test_MLP23_synthetic_credentials_must_be_explicit(self):
        observed = self.readiness()
        observed['credentials']['realEnvironmentKeysInherited'] = True
        self.reject_readiness(observed, 'SYNTHETIC_CREDENTIALS_REQUIRED')

    def test_MLP24_old_health_alias_cannot_impersonate_readiness(self):
        observed = self.readiness()
        observed['fixture'] = 'issue123-host-current-v1'
        self.reject_readiness(observed, 'MANAGED_GRAPH_REQUIRED')

    def test_MLP25_readiness_extra_fields_are_not_a_private_channel(self):
        observed = self.readiness()
        observed['SYNTHETIC_PRIVATE_PATH'] = 'SYNTHETIC_PRIVATE_KEY'
        self.reject_readiness(observed, 'INVALID_MANIFEST')

    def test_MLP26_unknown_manifest_field_rejected(self):
        self.manifest['SYNTHETIC_PRIVATE'] = True
        self.write()
        self.reject('INVALID_MANIFEST')

    def test_MLP27_classpath_duplicates_not_counted_twice(self):
        self.manifest['classpath'].append('classes')
        self.write()
        self.reject('CLASSPATH_PIN_MISMATCH')

    def test_MLP28_fixture_class_list_is_exact_and_sorted(self):
        self.manifest['fixtureClasses'] = list(reversed(self.manifest['fixtureClasses']))
        self.write()
        self.reject('FORBIDDEN_FIXTURE_CLASS')


if __name__ == '__main__':
    unittest.main(verbosity=2)
