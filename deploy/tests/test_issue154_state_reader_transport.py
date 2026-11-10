"""Issue224: bounded read-state transport, using public code and synthetic data only.

The real command builder, SSM command boundary and bootstrap execute. AWS is a
fake and the host Python shim confines staging to a temporary directory and
observes the helper argv instead of reading any actual host or database state.
"""
import base64
import copy
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zlib


DEPLOY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue224_actual_driver", DEPLOY / "pipeline/run-approved-release.py")
DRIVER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = DRIVER
SPEC.loader.exec_module(DRIVER)
PATHS = ("pipeline/host-approved-release.py", "pipeline/approved_release.py")
COMMAND_LIMIT = 60 * 1024
BUNDLE_LIMIT = 128 * 1024
INSTANCE = "i-00000000000000000"
COMMAND_ID = "00000000-0000-4000-8000-000000000224"
SOURCE_MARKER = "SYNTHETIC_SOURCE_224_NEVER_EXECUTED"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def digest(value):
    return hashlib.sha256(value).hexdigest()


def public_catalog_manifest():
    """Independent actual SQL bytes plus wholly synthetic release identity."""
    migrations = DEPLOY.parent / "src/main/resources/db/migration"
    paths = sorted(migrations.glob("V*__*.sql"), key=lambda p: int(p.name.split("__")[0][1:]))
    rows, catalog = [], []
    for p in paths:
        raw = p.read_bytes()
        version = p.name.split("__")[0][1:]
        crc = zlib.crc32("".join(raw.decode("utf-8-sig").splitlines()).encode("utf-8"))
        rows.append({"version": version, "script": p.name, "checksum": crc if crc < 2**31 else crc - 2**32})
        catalog.append({"version": version, "script": p.name, "sha256": digest(raw)})
    return {
        "protocol": "meet-me-approved-release-v1", "sourceSha": "a" * 40,
        "buildRunId": "224000000001", "buildRunAttempt": "1",
        "imageRepository": "123456789012.dkr.ecr.us-east-1.amazonaws.com/synthetic/service",
        "imageDigest": "sha256:" + "b" * 64, "releaseId": "a" * 40 + "-224000000001-1",
        "archiveSha256": "c" * 64, "schemaFingerprint": digest(canonical(catalog)),
        "guardFingerprint": "d" * 64, "migrations": rows,
        "origin": {"repository": "meet-me-duo/meet-me-server", "ref": "refs/heads/main", "workflow": "CI",
                   "event": "push", "conclusion": "success", "ciRunId": "223000000001", "ciSha": "a" * 40},
    }


HOST_SHIM = r'''import base64,hashlib,json,os,pathlib,subprocess,sys,tempfile
oracle=json.load(open(os.environ['T224_ORACLE']))
run_root=pathlib.Path(os.environ['T224_RUN']).resolve()
audit_path=os.environ['T224_AUDIT']
events=[]
original_temp=tempfile.mkdtemp
original_write=pathlib.Path.write_bytes

def staging(*args,**kwargs):
    kwargs['dir']=str(run_root)
    result=original_temp(*args,**kwargs)
    events.append({'kind':'stage-created','mode':os.stat(result).st_mode & 0o777})
    return result

def source_write(target,body):
    actual=target.resolve()
    if run_root not in actual.parents:
        events.append({'kind':'outside-stage-write-attempt'})
        raise RuntimeError('Synthetic host blocked a write outside its temporary stage')
    events.append({'kind':'file-write','name':target.name,'sha256':hashlib.sha256(body).hexdigest()})
    return original_write(target,body)

def helper(argv,**kwargs):
    if not (isinstance(argv,(list,tuple)) and len(argv)==4 and argv[0]=='python3' and argv[2]=='--read-state'):
        events.append({'kind':'unexpected-subprocess'})
        raise RuntimeError('Synthetic host blocked an unexpected process')
    stage=pathlib.Path(argv[1]).parent.parent
    files={relative:(stage/relative).read_bytes() for relative in oracle['sources']}
    matches=all(body==base64.b64decode(oracle['sources'][relative],validate=True) for relative,body in files.items())
    manifest_raw=base64.b64decode(argv[3],validate=True)
    manifest=json.loads(manifest_raw)
    row={'kind':'helper-call','sourcesExact':matches,'manifestExact':manifest==oracle['manifest'],
         'manifestArgvExact':argv[3]==oracle['manifestEncoded'],
         'fileModes':{relative:os.stat(stage/relative).st_mode & 0o777 for relative in files},
         'sourceSha256':{relative:hashlib.sha256(body).hexdigest() for relative,body in files.items()}}
    events.append(row)
    if not (matches and row['manifestExact'] and row['manifestArgvExact']):
        return subprocess.CompletedProcess(argv,2,'','Synthetic restored-input mismatch')
    return subprocess.CompletedProcess(argv,0,json.dumps(oracle['state']),'')

tempfile.mkdtemp=staging
pathlib.Path.write_bytes=source_write
subprocess.run=helper
sys.argv=sys.argv[1:]
program=sys.stdin.buffer.read()
try:
    exec(compile(program,'<actual-state-bootstrap>','exec'),{'__name__':'__main__'})
finally:
    with open(audit_path,'w') as output:json.dump(events,output)
'''


class StateTransportFixture:
    def __init__(self, sources, manifest=None):
        self.temporary = tempfile.TemporaryDirectory(prefix="issue224-offline-state-")
        self.root = Path(self.temporary.name)
        self.base = self.root / "public-source"
        self.sources = dict(sources)
        self.manifest = public_catalog_manifest() if manifest is None else copy.deepcopy(manifest)
        self.before_manifest = copy.deepcopy(self.manifest)
        for relative, raw in self.sources.items():
            target = self.base / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        shim = self.bin / "python3"
        shim.write_text("#!" + sys.executable + "\n" + HOST_SHIM)
        shim.chmod(0o755)
        self.run = self.root / "fake-run"
        self.run.mkdir()
        self.audit = self.root / "audit.json"
        self.managed = self.root / "managed-state"
        self.managed.mkdir()
        (self.managed / "phase").write_bytes(b"SYNTHETIC_READY\n")
        (self.managed / "immutable-contract").write_bytes(b"SYNTHETIC_EXISTING_CONTRACT_224\x00\n")
        self.state = {"protocol": "meet-me-release-state-v1", "automaticContract": None,
                      "guardTrusted": True, "phase": "READY",
                      "currentImage": self.manifest["imageRepository"] + "@" + self.manifest["imageDigest"]}
        oracle = {"sources": {name: base64.b64encode(body).decode("ascii") for name, body in self.sources.items()},
                  "manifest": self.manifest, "manifestEncoded": base64.b64encode(canonical(self.manifest)).decode("ascii"),
                  "state": self.state}
        self.oracle = self.root / "oracle.json"
        self.oracle.write_bytes(canonical(oracle))
        self.before_managed = self.snapshot()
        self.commands = []
        self.aws_reads = []
        self.reply = None

    def snapshot(self):
        return {str(p.relative_to(self.managed)): (digest(p.read_bytes()), p.stat().st_mode & 0o777)
                for p in sorted(self.managed.rglob("*")) if p.is_file()}

    def execute(self, command):
        self.audit.unlink(missing_ok=True)
        result = subprocess.run(["bash", "-c", command], cwd=self.root, capture_output=True, text=True, timeout=10,
                                env={"PATH": str(self.bin) + ":/usr/bin:/bin", "T224_RUN": str(self.run),
                                     "T224_AUDIT": str(self.audit), "T224_ORACLE": str(self.oracle)})
        events = json.loads(self.audit.read_text()) if self.audit.exists() else None
        return result, events

    def send(self, service, operation, *args):
        if (service, operation) != ("ssm", "send-command"):
            raise AssertionError("Only the synthetic SSM read command may be submitted")
        parameters = json.loads(args[args.index("--parameters") + 1])
        command = parameters["commands"][0]
        self.commands.append(command)
        self.reply = self.execute(command)
        return {"Command": {"CommandId": COMMAND_ID}}

    def invocation(self, service, operation, *args):
        if (service, operation) != ("ssm", "get-command-invocation"):
            raise AssertionError("Only synthetic state invocation polling is permitted")
        self.aws_reads.append((service, operation))
        result, _ = self.reply
        return {"status": "success", "data": {"Status": "Success" if result.returncode == 0 else "Failed",
                                               "StandardOutputContent": result.stdout,
                                               "StandardErrorContent": result.stderr}}

    def read(self):
        with patch.object(DRIVER, "BASE", self.base), patch.object(DRIVER, "_aws", side_effect=self.send), \
                patch.object(DRIVER, "aws_call", side_effect=self.invocation):
            return DRIVER._host_state(INSTANCE, self.manifest)

    def unchanged(self, testcase):
        testcase.assertEqual(self.before_manifest, self.manifest)
        testcase.assertEqual(self.before_managed, self.snapshot())
        for name, raw in self.sources.items():
            testcase.assertEqual(digest(raw), digest((self.base / name).read_bytes()))
        testcase.assertEqual([], list(self.run.iterdir()), "Read staging must always be cleaned up")

    def close(self):
        self.temporary.cleanup()


def small_sources():
    return {PATHS[0]: ("# " + SOURCE_MARKER + " 한글🙂\r\n# literal '$HOME' `ignored` <<'END'\r\n").encode(),
            PATHS[1]: ("# " + SOURCE_MARKER + " peer with no final newline").encode()}


def bundle(sources):
    return json.dumps({name: base64.b64encode(body).decode("ascii") for name, body in sources.items()},
                      separators=(",", ":")).encode("utf-8")


def wire(raw):
    return base64.b64encode(gzip.compress(raw, mtime=0)).decode("ascii")


def source_argument(command):
    # The existing stdin-Python argv protocol is preserved; the source wire arg changes only its encoding.
    prefix = command.split("<<", 1)[0]
    tokens = shlex.split(prefix)
    at = tokens.index("python3")
    if tokens[at + 1] != "-":
        raise AssertionError("The actual command must retain its stdin Python bootstrap protocol")
    return tokens[at + 2]


def replaced_source(command, payload):
    old = shlex.quote(source_argument(command))
    if command.count(old) != 1:
        raise AssertionError("Synthetic test cannot uniquely locate the source wire argument")
    return command.replace(old, shlex.quote(payload), 1)


class Issue224StateReaderTransportTests(unittest.TestCase):
    def fixture(self, sources=None):
        value = StateTransportFixture(small_sources() if sources is None else sources)
        self.addCleanup(value.close)
        return value

    def accepted(self, value):
        try:
            result = value.read()
        except ValueError:
            self.fail("A valid bounded read-state input was rejected before a successful synthetic read")
        self.assertEqual(value.state, result)
        self.assertEqual(1, len(value.commands))
        self.assertLessEqual(len(value.commands[0].encode("utf-8")), COMMAND_LIMIT)
        self.assertIsNotNone(value.reply[1], "Fake-host setup must observe the actual bootstrap")
        calls = [e for e in value.reply[1] if e["kind"] == "helper-call"]
        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0]["sourcesExact"])
        self.assertTrue(calls[0]["manifestExact"])
        self.assertTrue(calls[0]["manifestArgvExact"])
        self.assertEqual({name: 0o600 for name in PATHS}, calls[0]["fileModes"])
        value.unchanged(self)
        return value.commands[0]

    def rejected_host(self, value, command):
        result, events = value.execute(command)
        self.assertIsNotNone(events, "A Python/bootstrap setup failure is not valid rejection evidence")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], events, "Validate the entire bundle before any staging directory, file write or helper call")
        self.assertEqual("", result.stdout)
        self.assertNotIn(SOURCE_MARKER, result.stderr)
        self.assertNotIn(base64.b64encode(canonical(value.manifest)).decode(), result.stderr)
        value.unchanged(self)

    def test_ST01_exact_small_source_bytes_manifest_argv_and_managed_state_remain_unchanged(self):
        value = self.fixture()
        self.accepted(value)

    def test_ST02_current_public_reader_and_public_catalog_fit_the_existing_command_boundary(self):
        sources = {name: (DEPLOY / name).read_bytes() for name in PATHS}
        value = self.fixture(sources)
        self.assertEqual([str(n) for n in range(1, 32)], [r["version"] for r in value.manifest["migrations"]])
        self.assertLess(len(bundle(sources)), BUNDLE_LIMIT)
        self.accepted(value)

    def test_ST03_large_repetitive_sources_are_transported_without_rewriting_or_truncation(self):
        source = ("# " + SOURCE_MARKER + "🙂\r\n").encode() * 1500
        sources = {PATHS[0]: source, PATHS[1]: b"# second approved source\n"}
        self.assertGreater(len(bundle(sources)), COMMAND_LIMIT)
        self.assertLessEqual(len(bundle(sources)), BUNDLE_LIMIT)
        self.accepted(self.fixture(sources))

    def test_ST04_the_wire_is_gzip_base64_of_exactly_the_two_approved_source_paths(self):
        value = self.fixture()
        command = self.accepted(value)
        try:
            decoded = gzip.decompress(base64.b64decode(source_argument(command), validate=True))
        except (ValueError, OSError):
            self.fail("The state source wire must implement the approved gzip/base64 contract")
        restored = json.loads(decoded)
        self.assertEqual(set(PATHS), set(restored))
        self.assertEqual(value.sources, {name: base64.b64decode(body, validate=True) for name, body in restored.items()})

    def test_ST05_oversized_expanded_bundle_is_rejected_locally_before_any_aws_submission(self):
        value = self.fixture({PATHS[0]: b"# repeated source\n" * 7000, PATHS[1]: b"# peer\n"})
        self.assertGreater(len(bundle(value.sources)), BUNDLE_LIMIT)
        with self.assertRaises(ValueError):
            value.read()
        self.assertEqual([], value.commands)
        value.unchanged(self)

    def test_ST06_incompressible_bounded_input_cannot_bypass_the_existing_wire_command_hard_cap(self):
        raw = b"# " + base64.b64encode(hashlib.shake_256(b"issue224 synthetic entropy").digest(65000)) + b"\n"
        value = self.fixture({PATHS[0]: raw, PATHS[1]: b"# peer\n"})
        self.assertLessEqual(len(bundle(value.sources)), BUNDLE_LIMIT)
        self.assertGreater(len(wire(bundle(value.sources)).encode()), COMMAND_LIMIT)
        with self.assertRaises(ValueError):
            value.read()
        self.assertEqual([], value.commands)
        value.unchanged(self)

    def test_ST07_ssm_accepts_exact_60kib_but_rejects_one_more_utf8_byte_before_aws(self):
        for length in (COMMAND_LIMIT, COMMAND_LIMIT + 1):
            with self.subTest(byte_length=length):
                command = "#" + "가" * ((length - 1) // 3) + "x" * ((length - 1) % 3)
                self.assertEqual(length, len(command.encode()))
                with patch.object(DRIVER, "_aws", return_value={"Command": {"CommandId": COMMAND_ID}}) as sent, \
                        patch.object(DRIVER, "aws_call", return_value={"status": "success", "data": {
                            "Status": "Success", "StandardOutputContent": "synthetic-read", "StandardErrorContent": ""}}):
                    if length == COMMAND_LIMIT:
                        self.assertEqual("synthetic-read", DRIVER._ssm(INSTANCE, command, lambda status, out, err: out))
                        self.assertEqual(1, sent.call_count)
                    else:
                        with self.assertRaises(ValueError):
                            DRIVER._ssm(INSTANCE, command, lambda status, out, err: out)
                        sent.assert_not_called()

    def test_ST08_host_accepts_exact_128kib_expanded_json_and_rejects_one_more_byte_before_staging(self):
        value = self.fixture()
        command = self.accepted(value)
        original = bundle(value.sources)
        for size in (BUNDLE_LIMIT, BUNDLE_LIMIT + 1):
            with self.subTest(expanded_bytes=size):
                raw = original + b" " * (size - len(original))
                changed = replaced_source(command, wire(raw))
                if size == BUNDLE_LIMIT:
                    result, events = value.execute(changed)
                    self.assertEqual(0, result.returncode, "Exactly 128KiB is inside the inclusive expansion bound")
                    self.assertEqual(value.state, json.loads(result.stdout))
                    self.assertEqual(1, len([e for e in events if e["kind"] == "helper-call"]))
                    value.unchanged(self)
                else:
                    self.rejected_host(value, changed)

    def test_ST09_gzip_crc_truncation_and_extra_json_documents_fail_before_staging(self):
        value = self.fixture()
        command = self.accepted(value)
        encoded = gzip.compress(bundle(value.sources), mtime=0)
        bad_crc = bytearray(encoded)
        bad_crc[-8] ^= 1
        cases = {"crc": base64.b64encode(bad_crc).decode(), "truncated": base64.b64encode(encoded[:-5]).decode(),
                 "extra-document": wire(bundle(value.sources) + b"{}"), "not-gzip": base64.b64encode(b"not gzip").decode()}
        for name, payload in cases.items():
            with self.subTest(fault=name):
                self.rejected_host(value, replaced_source(command, payload))

    def test_ST10_invalid_wire_base64_is_rejected_before_staging(self):
        value = self.fixture()
        command = self.accepted(value)
        for payload in ("%INVALID%", "", wire(bundle(value.sources)) + "\nINJECTED"):
            with self.subTest(fault=repr(payload[-12:])):
                self.rejected_host(value, replaced_source(command, payload))

    def test_ST11_added_missing_absolute_and_traversing_paths_are_rejected_before_staging(self):
        value = self.fixture()
        command = self.accepted(value)
        mapping = json.loads(bundle(value.sources))
        cases = []
        for path in ("pipeline/unapproved.py", "../../managed-state/phase", "/tmp/issue224-unapproved.py"):
            cases.append({**mapping, path: base64.b64encode(b"unapproved").decode()})
        cases.extend([{PATHS[0]: mapping[PATHS[0]]}, {PATHS[1]: mapping[PATHS[1]]}])
        for index, changed in enumerate(cases):
            with self.subTest(fault=index):
                self.rejected_host(value, replaced_source(command, wire(canonical(changed))))

    def test_ST12_wrong_json_shapes_source_types_and_invalid_source_base64_fail_before_staging(self):
        value = self.fixture()
        command = self.accepted(value)
        mapping = json.loads(bundle(value.sources))
        cases = [b"[]", b"null", b"{bad json", canonical({**mapping, PATHS[1]: 224}),
                 canonical({**mapping, PATHS[1]: None}), canonical({**mapping, PATHS[1]: "%INVALID%"})]
        for index, raw in enumerate(cases):
            with self.subTest(fault=index):
                self.rejected_host(value, replaced_source(command, wire(raw)))


    def test_ST13_high_expansion_payload_within_wire_cap_is_rejected_before_staging(self):
        value = self.fixture()
        command = self.accepted(value)
        raw = bundle(value.sources) + b" " * (8 * 1024 * 1024)
        changed = replaced_source(command, wire(raw))
        self.assertLess(len(changed.encode("utf-8")), COMMAND_LIMIT)
        self.rejected_host(value, changed)


if __name__ == "__main__":
    unittest.main()
