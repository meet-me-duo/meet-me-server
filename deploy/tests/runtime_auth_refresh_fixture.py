"""Real copied READY host guard + real renderer; synthetic AWS/Docker only.

Extends the existing logical-root/flock guard sandbox without changing any of
its tests. No installed /opt path, credentials, AWS account or service is used.
"""
import json
import os
from pathlib import Path
import shutil
import sys
import unittest

from test_v8_release_guard import REPOSITORY, Sandbox


def main():
    sandbox = Sandbox(unittest.TestCase())
    try:
        sandbox.deploy(check=True)
        # Keep real guard/Compose decision paths. Replace only its outbound AWS
        # executable with the independent parameter fixture; Docker remains stubbed.
        shutil.copyfile(os.environ["AUTH_TEST_AWS"], sandbox.bin / "aws")
        (sandbox.bin / "aws").chmod(0o755)
        shutil.copyfile(REPOSITORY / "deploy/scripts/render-runtime-env.sh",
                        sandbox.new / "scripts/render-runtime-env.sh")
        (sandbox.new / "scripts/render-runtime-env.sh").chmod(0o755)
        (sandbox.new / "runtime-auth-mode").write_text("oauth-rs256-required\n")
        shutil.copyfile(os.environ["AUTH_TEST_POLICY"], sandbox.new / "runtime-ai-policy.json")
        parameters = json.loads(Path(os.environ["FAKE_AUTH_SCENARIO"]).read_text())["parameters"]
        variables = json.loads(os.environ["AUTH_TEST_VARIABLES"])
        before = "DATABASE_PASSWORD=synthetic-old\n" + "".join(
            f"{variable}={parameters[name]['Value']}\n" for variable, name in variables)
        runtime = sandbox.new / ".env.runtime"
        runtime.write_text(before)
        runtime.chmod(0o600)
        sandbox.update(password="synthetic-old", health="healthy")
        for name in ("FAKE_AUTH_SCENARIO", "FAKE_AUTH_CALLS", "FAKE_AUTH_DEST",
                     "MEETME_PARAMETER_ROOT"):
            sandbox.env[name] = os.environ[name]
        sandbox.clear_calls()
        result = sandbox.action("refresh")
        output = runtime.read_text()
        values = dict(line.split("=", 1) for line in output.splitlines() if "=" in line)
        complete = all(values.get(variable) == parameters[name]["Value"]
                       for variable, name in variables)
        fixed_paths = (values.get("AUTH_SIGNING_PRIVATE_KEY_PATH") ==
                       "/run/meetme-auth/signing-private.pem" and
                       values.get("AUTH_VERIFICATION_JWKS_PATH") ==
                       "/run/meetme-auth/verification.jwks.json")
        starts = sandbox.starts()
        secrets = [row.get("Value") for row in parameters.values()
                   if isinstance(row.get("Value"), str)] + ["synthetic-fresh", "SYNTHETIC_AWS_DIAGNOSTIC"]
        quiet = all(value not in result.stdout + result.stderr for value in secrets)
        print(json.dumps({"returncode": result.returncode, "auth_complete": complete,
                          "fixed_paths": fixed_paths,
                          "database_updated": values.get("DATABASE_PASSWORD") == "synthetic-fresh",
                          "precheck_enabled": values.get("MEETME_PRECHECK_PROVIDER_ENABLED"),
                          "previous_bytes_preserved": output == before,
                          "mode0600": runtime.stat().st_mode & 0o777 == 0o600,
                          "app_recreates": len(starts), "quiet": quiet,
                          "compose_guard_override_last": all(
                              call["args"][:5] == ["compose", "-f", "compose.production.yml", "-f",
                                                   str(sandbox.host / "guard/compose.guard.yml")]
                              for call in starts)}))
        return 0
    finally:
        sandbox.close()


if __name__ == "__main__":
    sys.exit(main())
