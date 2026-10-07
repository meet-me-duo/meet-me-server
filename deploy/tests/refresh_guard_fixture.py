"""Installed READY guard fixture for the unchanged credential behavior assertions.

Logical root ownership and projected /proc only; real files/flock. No real AWS,
Docker, credentials, network, service installation, or operator evidence is used.
"""
import os
from pathlib import Path
import subprocess
import sys
import unittest

from test_v8_release_guard import NEW, STUB, Sandbox

DOCKER = r'''#!/usr/bin/env python3
import os,pathlib,sys
root=pathlib.Path(os.environ['FAKE_STATE_DIR']);args=sys.argv[1:]
def read(name):return (root/name).read_text()
def write(name,value):(root/name).write_text(value)
if args[0]=='exec':print(read('app-password'));sys.exit(0)
if args[0]=='info' or args[:2]==['container','inspect']:sys.exit(0)
if args[0]=='inspect':
 fmt=args[args.index('--format')+1] if '--format' in args else ''
 if 'State.Running' in fmt:print(read('running'))
 elif 'Config.Image' in fmt:print(read('image'))
 elif 'RestartPolicy' in fmt:print(read('restart'))
 elif 'Health.Status' in fmt:print(read('health'))
 else:sys.exit(93)
 sys.exit(0)
if args[0]=='update':write('restart','no');sys.exit(0)
if args[0]=='stop':write('running','false');sys.exit(0)
if args[0]=='login':sys.stdin.read();sys.exit(0)
if args[0]=='pull' or args[0]=='run' and args[-1]=='migrate':sys.exit(0)
if args[0]=='compose' and 'up' in args:
 with (root/'calls').open('a') as f:f.write('compose\n')
 write('app-password','new-password');write('running','true')
 write('image',os.environ['APP_IMAGE']);write('restart','no')
 write('health','unhealthy' if (root/'fail-health').exists() else 'healthy')
 sys.exit(0)
sys.exit(93)
'''


def main():
    s = Sandbox(unittest.TestCase())
    try:
        # Establish genuine installed READY metadata using real copied entrypoints.
        s.deploy(check=True)
        state = s.root / 'state'
        state.mkdir()
        for name, value in {'running': 'true', 'image': NEW, 'restart': 'no',
                            'health': 'healthy', 'app-password': 'new-password'}.items():
            (state / name).write_text(value)
        (s.root / 'release').symlink_to(s.new)
        s.executable(s.bin / 'docker', DOCKER)
        s.executable(s.bin / 'aws', STUB.replace('synthetic-fresh', 'new-password'))
        s.executable(s.new / 'scripts/render-runtime-env.sh', '''#!/usr/bin/env bash
printf 'render\\n' >>"$FAKE_STATE_DIR/calls"
printf 'DATABASE_PASSWORD=new-password\\n' >"$1"
''')
        env = {**s.env, 'FAKE_STATE_DIR': str(state),
               'MEETME_REFRESH_PENDING_FILE': str(s.root / 'pending'),
               'V8_REFRESH_FIXTURE_ROOT': str(s.root),
               'V8_REFRESH_FIXTURE_LAUNCHER': str(s.launcher),
               'V8_REFRESH_FIXTURE_RELEASE': str(s.new),
               'V8_REFRESH_FIXTURE_IMAGE': NEW}
        return subprocess.run(['bash', sys.argv[1]], env=env).returncode
    finally:
        s.close()


if __name__ == '__main__':
    sys.exit(main())
