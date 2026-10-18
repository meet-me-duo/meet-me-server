#!/usr/bin/env python3
"""Offline reproduction of the managed-browser test runtime. Does not start the app."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('managed_browser_runtime_pins', HERE / 'runtime_pin.py')
PINS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PINS)
AUTH = 'com/meetme/server/account/adapter/input/web/AuthEndToEndPostgresTest'
PROXY = 'com/meetme/server/coordination/adapter/input/web/Issue131CanonicalTlsProxy'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def files(root):
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(root.rglob('*')) if p.is_file()}


def checked(command, environment, cwd=None):
    result = subprocess.run(command, env=environment, cwd=cwd, capture_output=True)
    if result.returncode:
        raise ValueError('OFFLINE_BUILD_FAILED')
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server-source', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--gradle-user-home', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    source = args.server_source.resolve(strict=True)
    java = (args.java_home / 'bin/java').resolve(strict=True)
    javac = (args.java_home / 'bin/javac').resolve(strict=True)
    output = args.output.absolute()
    if output.exists() or java.parent != javac.parent:
        raise ValueError('NEW_OUTPUT_AND_SELECTED_JDK_REQUIRED')
    env = {'PATH': str(java.parent) + ':/usr/bin:/bin', 'HOME': '/tmp',
           'JAVA_HOME': str(java.parent.parent), 'GRADLE_USER_HOME': str(args.gradle_user_home.resolve(strict=True)),
           'OPENAI_API_KEY': '', 'GEMINI_API_KEY': '', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8'}
    version = subprocess.run([str(java), '-version'], env=env, capture_output=True, check=True).stderr.decode().splitlines()
    if not version or '"17.' not in version[0]:
        raise ValueError('JDK17_REQUIRED')
    head = checked(['git', 'rev-parse', 'HEAD'], env, source).decode().strip()
    changed = set(checked(['git', 'diff', '--name-only', 'HEAD'], env, source).decode().splitlines())
    if head != PINS.SERVER_HEAD or changed != set(PINS.OVERLAYS):
        raise ValueError('EXACT_MANAGED_COMBINATION_REQUIRED')
    if any(sha(source / name) != digest for name, digest in PINS.OVERLAYS.items()):
        raise ValueError('EXACT_MANAGED_COMBINATION_REQUIRED')
    with open('/tmp/meetme-gradle.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        output.mkdir(mode=0o700)
        metadata = output / 'build-metadata'
        metadata.mkdir(mode=0o700)
        snapshot = output / 'snapshot'
        snapshot.mkdir(mode=0o700)
        before = {**{'src/main/' + n: h for n, h in files(source / 'src/main').items()},
                  **{'src/test/' + n: h for n, h in files(source / 'src/test').items()}}
        before.update({n: sha(source / n) for n in ('build.gradle.kts', 'settings.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties')})
        helper_before = {p.name: sha(p) for p in (HERE / 'runtime_pin.py', HERE / 'Issue130ManagedBrowserServer.java', HERE / 'ManagedBrowserLoopbackGuard.java', HERE / 'BACKEND_CONTRACT.md')}
        classpath = metadata / 'test.classpath'
        init = metadata / 'classpaths.init.gradle'
        # All paths are locally generated; JSON encoding is also a valid Groovy double-quoted string here.
        init.write_text('allprojects { afterEvaluate { tasks.register("managedBrowserClasspath") { dependsOn tasks.named("testClasses"); doLast { file(' + json.dumps(str(classpath)) + ').text=sourceSets.test.runtimeClasspath.asPath } } } }\n')
        command = [str(source / 'gradlew'), '--offline', '--no-daemon', '--rerun-tasks', '-I', str(init), 'managedBrowserClasspath']
        compile_run = subprocess.run(command, env=env, cwd=source, capture_output=True)
        (metadata / 'production-compile.log').write_bytes(compile_run.stdout + compile_run.stderr)
        if compile_run.returncode:
            raise ValueError('OFFLINE_BUILD_FAILED')
        for name, digest in before.items():
            if sha(source / name) != digest:
                raise ValueError('BUILD_SOURCE_DRIFT')
        for name, digest in helper_before.items():
            if sha(HERE / name) != digest:
                raise ValueError('BUILD_HELPER_DRIFT')
        sr = snapshot / 'source'
        rt = snapshot / 'runtime'
        sr.mkdir(); rt.mkdir()
        for name in before:
            if name.startswith('src/test/') and not any(name.endswith('/' + suffix) for suffix in ('AuthEndToEndPostgresTest.kt', 'Issue131CanonicalTlsProxy.kt')):
                continue
            target = sr / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, target)
        for name in helper_before:
            target = sr / 'evaluation/issue130/managed-browser' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(HERE / name, target)
        for origin, name in ((source / 'build/classes/kotlin/main', 'main'), (source / 'build/resources/main', 'resources')):
            shutil.copytree(origin, rt / name)
        (rt / 'fixtures').mkdir()
        cp = ['fixtures', 'main', 'resources']
        for name in (AUTH, PROXY):
            parent = Path(name).parent
            for item in (source / 'build/classes/kotlin/test' / parent).glob(Path(name).name + '*.class'):
                stem = item.relative_to(source / 'build/classes/kotlin/test').as_posix()
                if not PINS._fixture_class(stem):
                    continue
                target = rt / 'fixtures' / stem
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(item, target)
        for raw in classpath.read_text().split(os.pathsep):
            item = Path(raw)
            if item.suffix != '.jar':
                continue
            name = 'lib/' + sha(item) + '-' + item.name
            target = rt / name
            target.parent.mkdir(exist_ok=True)
            shutil.copyfile(item, target)
            cp.append(name)
        command_java = [str(javac), '-cp', os.pathsep.join(str(rt / name) for name in cp), '-d', str(rt / 'fixtures'),
                        str(HERE / 'Issue130ManagedBrowserServer.java'), str(HERE / 'ManagedBrowserLoopbackGuard.java')]
        compile_java = subprocess.run(command_java, env=env, capture_output=True)
        (metadata / 'launcher-compile.log').write_bytes(compile_java.stdout + compile_java.stderr)
        if compile_java.returncode:
            raise ValueError('LAUNCHER_COMPILE_FAILED')
        # Public synthetic authority copied from MB09, never a tariff/operating approval.
        fingerprints = ['308b2fd0b2d0892f59aa7f953247ea9c855597d0da5a30a6923070596149ec81', '658a65563b34d43f854cc47dcd93d274d23789745e718b5f7ef49e1b59e8e307', 'b9ce9802d9dc3f4396f49c796cfe173fda708c6a35ac40c79d5bdc5091419363', '0a3fc94ba00d368b4e7c9a41d0b231faab5058aa893dc11082ef786fc0a08d04', '9c5021916c80178f58d235e55bbbad4889875560b710a505fbde5dedd1a824ab']
        operations = ['PRECHECK_CLASSIFICATION', 'PRECHECK_COUNT', 'PRECHECK_GENERATION', 'FINAL_PRIMARY', 'FINAL_FALLBACK']
        policy = dict(protocol='meetme-managed-ai-policy-v1', state='CONFIGURED', unit=dict(code='TEST_SUBUNIT', scale=0),
                      roomCap='1000', minimumConfidence=.73,
                      operations=[dict(operation=op, profileFingerprint=fp, evidenceFingerprint=hashlib.sha256(('synthetic:' + op).encode()).hexdigest(), maximumAmount=str(i + 3), validUntil='2030-01-01T00:00:00Z') for i, (op, fp) in enumerate(zip(operations, fingerprints))])
        (rt / 'resources/managed-browser-synthetic-policy.json').write_text(json.dumps(policy, separators=(',', ':')) + '\n')
        toolchain = {'java': {'path': str(java), 'sha256': sha(java)}, 'javac': {'path': str(javac), 'sha256': sha(javac)},
                     'version': version, 'compileCommand': command}
        (rt / 'resources/managed-browser-toolchain.json').write_text(json.dumps(toolchain, indent=2) + '\n')
        runtime_files = files(rt)
        manifest = dict(schemaVersion=1, fixture=PINS.FIXTURE, serverHead=head, policyOriginHead=PINS.POLICY_ORIGIN,
                        overlays=PINS.OVERLAYS, sourceFiles=files(sr), runtimeFiles=runtime_files, classpath=cp,
                        fixtureClasses=sorted(n for n in runtime_files if n.startswith('fixtures/') and n.endswith('.class')), graph=PINS.GRAPH)
        manifest_path = snapshot / 'manifest.json'
        manifest_path.write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n')
        verified = PINS.validate_manifest(manifest_path)
        receipt = dict(status='PASS_BUILD_AND_PREFLIGHT_NOT_ACTUAL_STARTUP', builderSha256=sha(Path(__file__)),
                       sourceBefore=before, sourceAfter={n: sha(source / n) for n in before}, helperBefore=helper_before,
                       helperAfter={n: sha(HERE / n) for n in helper_before}, toolchain=toolchain,
                       compileCommand=command, launcherCompileCommand=command_java, productionCompileExitCode=0,
                       launcherCompileExitCode=0, manifestSha256=verified['manifestSha256'], runtimeFiles=runtime_files,
                       actualStartup=False, modelCalls=0)
        if receipt['sourceBefore'] != receipt['sourceAfter'] or receipt['helperBefore'] != receipt['helperAfter']:
            raise ValueError('BUILD_SOURCE_DRIFT')
        (metadata / 'receipt.json').write_text(json.dumps(receipt, sort_keys=True, indent=2) + '\n')
        print(json.dumps({'prepared': True, 'manifest': str(manifest_path), 'manifestSha256': verified['manifestSha256']}))


if __name__ == '__main__':
    main()
