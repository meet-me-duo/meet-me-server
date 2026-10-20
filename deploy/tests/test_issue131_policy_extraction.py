"""Actual generated extraction in an isolated synthetic release; no host installation, AWS or secret reads."""
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("issue131_policy_extraction_driver", ROOT / "pipeline/run-approved-release.py")
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


class Issue131PolicyExtractionTests(unittest.TestCase):
    def test_DX04_generated_verified_extraction_makes_only_nonsecret_policy_readable_for_UID10001_file_bind(self):
        with tempfile.TemporaryDirectory(prefix="issue131-extraction-") as temporary:
            root = Path(temporary); (root / "releases").mkdir(); (root / "run").mkdir(); (root / "bin").mkdir()
            policy = b'{"protocol":"meetme-managed-ai-policy-v1","state":"UNCONFIGURED"}\n'
            # The immutable archive's synthetic entrypoint observes extraction only; it never deploys anything.
            entrypoint = b'''import json,pathlib,stat,hashlib
root=pathlib.Path(__file__).resolve().parents[1]
def mode(name):return stat.S_IMODE((root/name).stat().st_mode)
print(json.dumps({'policyMode':mode('runtime-ai-policy.json'),'neighborMode':mode('synthetic-private-neighbor'),
 'retainedMode':mode('.approved-release.tar.gz'),'policySha256':hashlib.sha256((root/'runtime-ai-policy.json').read_bytes()).hexdigest()}))
'''
            files = {name: (ROOT / name).read_bytes() for name in DRIVER.CONTRACT_FILES}
            files.update({"pipeline/host-approved-release.py": entrypoint, "runtime-ai-policy.json": policy,
                          "preflight/compiled/ReadOnlyFlywayProbe.class": bytes.fromhex("cafebabe"),
                          "runtime-provider-mode": b"gemini-luna-required\n", "synthetic-private-neighbor": b"SYNTHETIC_ONLY\n"})
            guard = [{"path": name, "sha256": digest(files[name])} for name in DRIVER.CONTRACT_FILES]
            manifest = {"releaseId": "a" * 40 + "-1-1", "migrations": [], "guardFingerprint": digest(canonical(guard)),
                        "schemaFingerprint": digest(canonical([]))}
            archive = root / "synthetic-release.tar.gz"
            with tarfile.open(archive, "w:gz") as package:
                for name, body in files.items():
                    info = tarfile.TarInfo(name); info.size = len(body); info.mode = 0o644
                    package.addfile(info, io.BytesIO(body))
            manifest["archiveSha256"] = digest(archive.read_bytes())
            # Validate the package with the actual verifier before invoking the actual generated extraction.
            DRIVER._verify_package(archive, manifest)
            fake = root / "bin/aws"
            fake.write_text("#!/usr/bin/env python3\nimport os,shutil,sys\n"
                            "assert sys.argv[3:5]==['s3api','get-object']\n"
                            "shutil.copyfile(os.environ['SYNTHETIC_ARCHIVE'],sys.argv[-1])\n")
            fake.chmod(0o755)
            command = DRIVER._deploy_command("synthetic-bucket", "synthetic-release.tar.gz", "synthetic-region", manifest, {})
            # Rebase only fixed host roots and their ownership/trust anchor into this private temporary directory.
            # Archive bytes, validation, extraction/chmod and subprocess sequence remain the actual implementation.
            command = command.replace("pathlib.Path('/opt/meet-me/releases')", "pathlib.Path(" + repr(str(root / "releases")) + ")")
            command = command.replace("dir='/run'", "dir=" + repr(str(root / "run")))
            command = command.replace("metadata.st_uid!=0", "metadata.st_uid!=" + str(os.getuid()))
            command = command.replace("if parent==parent.parent:break", "if parent==pathlib.Path(" + repr(str(root)) + "):break")
            environment = {"PATH": str(root / "bin") + ":/usr/bin:/bin", "SYNTHETIC_ARCHIVE": str(archive),
                           "OPENAI_API_KEY": "", "GEMINI_API_KEY": ""}
            result = subprocess.run(["bash", "-c", command], env=environment, capture_output=True, text=True, timeout=40)
            self.assertEqual(result.returncode, 0, "Synthetic verified extraction must complete without actual deployment")
            self.assertEqual(result.stderr, "")
            actual = json.loads(result.stdout)
            self.assertEqual(actual["policyMode"], 0o644, "Only the nonsecret policy must be readable through the app's file bind")
            self.assertEqual(actual["neighborMode"], 0o600, "Do not broaden other extracted files")
            self.assertEqual(actual["retainedMode"], 0o600, "Keep the retained release archive restricted")
            self.assertEqual(actual["policySha256"], digest(policy))
            extracted = root / "releases" / manifest["releaseId"] / "runtime-ai-policy.json"
            # Docker mounts the source inode directly: host root0700 parent traversal is irrelevant inside the file bind.
            proof = subprocess.run(["docker", "run", "--rm", "--pull=never", "--network=none", "--user", "10001:10001",
                                    "--mount", "type=bind,source=" + str(extracted) + ",target=/policy.json,readonly",
                                    "alpine:3.22", "sh", "-c", "test -r /policy.json && ! sh -c ': > /policy.json' 2>/dev/null"],
                                   capture_output=True, text=True, timeout=20)
            self.assertEqual(proof.returncode, 0, "Actual UID10001 must read the exact file bind and be unable to modify it")
            self.assertEqual(proof.stdout, "")


if __name__ == "__main__":
    unittest.main()
