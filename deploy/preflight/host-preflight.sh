#!/usr/bin/env bash
set -euo pipefail
set +x
[[ ( "$#" == 1 || ( "$#" == 2 && "$2" == oauth-rs256-required ) ) && ${#1} -le 32768 && "$1" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || exit 2
python3 - "$1" <<'PY'
import base64,sys
try:
    data=base64.b64decode(sys.argv[1],validate=True)
    assert data[:4] == bytes.fromhex('cafebabe') and len(data)<=24576
except Exception:
    sys.exit(2)
PY

# Read selected metadata only, never cmdline, app logs, env or secret files.
host_metadata=$(python3 - "${2:-}" <<'PY'
import os,stat,hashlib,json,subprocess,sys
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
if sys.argv[1]=='oauth-rs256-required':
    auth=root+'/auth'
    def auth_metadata(path,directory=False):
        try:
            s=os.lstat(path)
            return {'exists':True,'directory' if directory else 'regular':stat.S_ISDIR(s.st_mode) if directory else stat.S_ISREG(s.st_mode),
                    'noSymlink':not stat.S_ISLNK(s.st_mode),'uid':s.st_uid,'gid':s.st_gid,
                    'mode':oct(stat.S_IMODE(s.st_mode)),'readableByApp':False}
        except OSError:return {'exists':False}
    files={'directory':auth_metadata(auth,True),'jwks':auth_metadata(auth+'/verification.jwks.json'),
           'signingKey':auth_metadata(auth+'/signing-private.pem')}
    # Resolve the bind source as root, then check access relative to its mounted root as the actual app UID.
    # No file is opened; outer host-directory traversal is not part of the container bind's permissions.
    access_code="""import json,os,sys
os.chdir(sys.argv[1])
if os.getuid()==0:
 os.setgroups([]);os.setgid(10001);os.setuid(10001)
else:
 assert os.getuid()==10001 and os.getgid()==10001 and set(os.getgroups())<={10001}
print(json.dumps({'directory':os.access('.',os.R_OK|os.X_OK),'jwks':os.access('verification.jwks.json',os.R_OK),
                  'signingKey':os.access('signing-private.pem',os.R_OK)}))
"""
    try:
        if not all(item.get('noSymlink') is True for item in files.values()):raise ValueError()
        checked=subprocess.run([sys.executable,'-I','-c',access_code,auth],capture_output=True,text=True,timeout=5)
        access=json.loads(checked.stdout) if checked.returncode==0 else {}
        if set(access)==set(files) and all(type(value) is bool for value in access.values()):
            for name,item in files.items():
                if item.get('exists'):item['readableByApp']=access[name]
    except Exception:pass
    data['authFiles']=files
    mount={key:False for key in ('required','present','bind','sourceMatches','targetMatches','readOnly')}
    marker=release+'/runtime-auth-mode'
    if os.path.lexists(marker):
        # An unsafe/unknown current marker cannot be treated as a legacy exemption.
        mount['required']=True
        if safe_file(marker):
            with open(marker,'rb') as f:mode=f.read(64)
            if mode not in (b'oauth-rs256-required',b'oauth-rs256-required\n'):
                data['authMount']=mount
                raise SystemExit(2)
    try:
        template='{{range .Mounts}}{{if eq .Destination "/run/meetme-auth"}}{{.Type}}|{{.Source}}|{{.Destination}}|{{.RW}}{{"\\n"}}{{end}}{{end}}'
        observed=subprocess.run(['docker','inspect','--format',template,'meet-me-app'],capture_output=True,text=True,timeout=5)
        lines=observed.stdout.strip().splitlines() if observed.returncode==0 else []
        mount['present']=bool(lines)
        if len(lines)==1:
            parts=lines[0].split('|')
            if len(parts)==4:
                mount.update({'bind':parts[0]=='bind','sourceMatches':parts[1]==auth,
                              'targetMatches':parts[2]=='/run/meetme-auth','readOnly':parts[3]=='false'})
    except Exception:pass
    data['authMount']=mount
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
