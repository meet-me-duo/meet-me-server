#!/usr/bin/env bash
set -euo pipefail
set +x
[[ "$#" == 1 && ${#1} -le 32768 && "$1" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || exit 2
python3 - "$1" <<'PY'
import base64,sys
try:
    data=base64.b64decode(sys.argv[1],validate=True)
    assert data[:4] == bytes.fromhex('cafebabe') and len(data)<=24576
except Exception:
    sys.exit(2)
PY

# Read selected metadata only, never cmdline, app logs, env or secret files.
host_metadata=$(python3 - <<'PY'
import os,stat,hashlib,json,subprocess
root='/opt/meet-me'
def safe_file(path):
    try:
        parent=os.path.dirname(path)
        while True:
            s=os.lstat(parent)
            if not stat.S_ISDIR(s.st_mode) or s.st_uid!=0 or stat.S_IMODE(s.st_mode)&0o022: return False
            if parent=='/': break
            parent=os.path.dirname(parent)
        s=os.lstat(path)
        return stat.S_ISREG(s.st_mode) and s.st_uid==0 and not stat.S_IMODE(s.st_mode)&0o022 and s.st_size<=65536
    except OSError: return False
def metadata(path):
    try:
        s=os.lstat(path)
        result={'exists':True,'uid':s.st_uid,'mode':oct(stat.S_IMODE(s.st_mode)), 'regular':stat.S_ISREG(s.st_mode)}
        if safe_file(path):
            with open(path,'rb') as f: result['sha256']=hashlib.sha256(f.read()).hexdigest()
        return result
    except OSError: return {'exists':False}
def container():
    try:
        r=subprocess.run(['docker','inspect','--format','{{json .State.Running}}|{{.State.Pid}}|{{.RestartCount}}|{{.HostConfig.RestartPolicy.Name}}|{{.Config.Image}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}','meet-me-app'],capture_output=True,text=True,timeout=5)
        if r.returncode: return {'present':False}
        a=r.stdout.strip().split('|')
        if len(a)!=6: return {'present':False}
        return {'present':True,'running':a[0]=='true','pid':int(a[1]),'restartCount':int(a[2]),'restartPolicy':a[3],'image':a[4],'health':a[5]}
    except Exception: return {'present':False}
paths=['guard/host-release-guard.sh','guard/compose.guard.yml','state/installation-manifest.json','state/prerequisites.json','state/compatible-images.tsv','state/phase','state/minimum-contract','guard/meet-me-guarded-restart.service']
data={'containerBefore':container(),'files':{p:metadata(root+'/'+p) for p in paths}}
release=os.path.realpath(root+'/current')
data['currentRelease']=release if release.startswith(root+'/releases/') else 'UNAVAILABLE'
for name in ['phase','minimum-contract']:
    p=root+'/state/'+name
    try:
        if safe_file(p):
            value=open(p).read(64).strip()
            if value in ['PRE_V8','V8_STARTED','READY','input_revision_v8']: data[name]=value
    except OSError: pass
try:
    p=root+'/state/compatible-images.tsv'
    if safe_file(p): data['approvedImages']=open(p).read(16384).splitlines()
except OSError: pass
try:
    r=subprocess.run(['systemctl','show','meet-me-guarded-restart.service','--property=LoadState,ActiveState,UnitFileState'],capture_output=True,text=True,timeout=5)
    if r.returncode==0:
        data['service']={line.split('=',1)[0]:line.split('=',1)[1] for line in r.stdout.splitlines()
                         if line.split('=',1)[0] in ('LoadState','ActiveState','UnitFileState') and line.split('=',1)[1]}
except Exception: pass
print(json.dumps(data))
PY
)

# Bounded standalone child JVM; existing application process is untouched.
flyway=$(docker exec -i meet-me-app sh -s -- "$1" 2>/dev/null <<'SH' || true
set -eu
set +x
probe_directory=$(mktemp -d /tmp/meetme-readonly.XXXXXX)
trap 'rm -rf "$probe_directory"' EXIT
umask 077
printf '%s' "$1" | base64 -d > "$probe_directory/ReadOnlyFlywayProbe.class"
: > "$probe_directory/empty.properties"
env -i PATH=/opt/java/openjdk/bin:/usr/bin:/bin HOME="$probe_directory" \
  DATABASE_URL="${DATABASE_URL:-}" DATABASE_USERNAME="${DATABASE_USERNAME:-}" DATABASE_PASSWORD="${DATABASE_PASSWORD:-}" \
  timeout 15s java -Xms8m -Xmx32m \
    -Dloader.config.location="file:$probe_directory/empty.properties" \
    -Dloader.home="$probe_directory" -Dloader.path="$probe_directory" \
    -Dloader.main=ReadOnlyFlywayProbe -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher 2>/dev/null
SH
)
python3 - "$host_metadata" "$flyway" <<'PY'
import json,subprocess,sys
host=json.loads(sys.argv[1])
try: flyway=json.loads(sys.argv[2])
except Exception: flyway={'status':'unavailable','reason':'HELPER_FAILED'}
try:
    r=subprocess.run(['docker','inspect','--format','{{json .State.Running}}|{{.State.Pid}}|{{.RestartCount}}','meet-me-app'],capture_output=True,text=True,timeout=5)
    a=r.stdout.strip().split('|')
    host['containerAfter']={'running':a[0]=='true','pid':int(a[1]),'restartCount':int(a[2])} if r.returncode==0 and len(a)==3 else {'present':False}
except Exception: host['containerAfter']={'present':False}
print(json.dumps({'protocol':'meetme-readonly-preflight-v1','host':host,'flyway':flyway}))
PY
