from pathlib import Path
import hashlib, json, shutil, subprocess, sys
root=Path(__file__).resolve().parent
name,target=sys.argv[1],Path(sys.argv[2]).resolve()
entry=json.loads((root/'manifest.json').read_text(encoding='utf-8'))['repositories'][name]
def git(*args): return subprocess.check_output(['git','-C',str(target),*args]).decode().strip()
assert git('rev-parse','HEAD')==entry['base_sha'], 'Wrong base SHA'
assert not git('status','--porcelain=v1','--untracked-files=all'), 'Target must be a clean new checkout'
for record in entry['files']:
    relative=record['path']; source=root/name/'files'/relative
    content=source.read_bytes()
    assert hashlib.sha256(content).hexdigest()==record['sha256'], relative
    destination=(target/relative).resolve()
    assert destination.is_relative_to(target) and '.git' not in destination.relative_to(target).parts
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.write_bytes(content)
    assert hashlib.sha256(destination.read_bytes()).hexdigest()==record['sha256'], relative
print(name, 'files verified:', len(entry['files']))
print(git('status','--porcelain=v1','--untracked-files=all'))
