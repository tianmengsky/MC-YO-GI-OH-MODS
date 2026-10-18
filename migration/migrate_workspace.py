"""One-time, checked migration. Never execute ACL recovery or native probes."""
from pathlib import Path
import hashlib, json, shutil, sys
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
WS = Path(__file__).resolve().parents[1]
VERSION = Path(r'F:\Minecarft\MC游戏王1.20.1\.minecraft\versions\1.20.1-Forge_47.4.10')
MD = Path(r'F:\MyCardLibrary\mdpro3')
assert WS == Path(r'F:\Minecarft\游戏王mod工作区')

def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''): h.update(block)
    return h.hexdigest()

def save(name, obj):
    (WS/'migration'/name).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding='utf-8')

def files(p):
    return [p] if p.is_file() else sorted(x for x in p.rglob('*') if x.is_file())

jobs = [
    ('move', VERSION/'_ygo-analysis', WS/'_ygo-analysis'),
    ('move', VERSION/'mods'/'游戏王重构-HANDOVER-初步诊断与目标清单-v1.md', WS/'docs'/'游戏王重构-HANDOVER-初步诊断与目标清单-v1.md'),
    ('move', Path(r'F:\MyCardLibrary\mdpro3-p0'), WS/'mdpro3-p0'),
    ('move', VERSION/'acl-recovery', WS/'archives'/'acl-recovery'),
]
for name in ['yugioh-1.0.0-简中重译.jar', 'ygostar-1.0.0-forge-1.20.1 (3).jar', 'yugioh-interaction-fix-0.103.2.jar']:
    jobs.append(('copy', VERSION/'mods'/name, WS/'inputs'/'mods'/name))
for part in ['Data', 'Picture', 'MDPro3_Data/Managed', 'MDPro3_Data/Plugins', 'expansions', 'Deck', 'Puzzle']:
    jobs.append(('copy', MD/part, WS/'inputs'/'mdpro3'/part))

assert all(s.exists() and not d.exists() for _,s,d in jobs), 'Precondition failed; inspect before resuming.'
for mode, src, dst in jobs:
    assert dst.resolve().is_relative_to(WS)
    if mode == 'move':
        assert src.resolve().is_relative_to(VERSION) or src == Path(r'F:\MyCardLibrary\mdpro3-p0')

runtime = {str(p): {'size': p.stat().st_size, 'sha256': sha(p)} for p in sorted((VERSION/'mods').glob('*.jar'))}
save('runtime-jars-before.json', runtime)
manifest = {'started_at': datetime.now(timezone.utc).isoformat(), 'workspace': str(WS), 'jobs': []}
for mode, src, dst in jobs:
    print('Inventory:', src, flush=True)
    entries = []
    for p in files(src):
        rel = p.relative_to(src).as_posix() if src.is_dir() else ''
        entries.append({'relative_path': rel, 'size': p.stat().st_size, 'sha256': sha(p)})
    job = {'operation': mode, 'source': str(src), 'destination': str(dst), 'files': entries, 'verified': False}
    manifest['jobs'].append(job)
    save('transfer-manifest.json', manifest)
    dst.parent.mkdir(parents=True, exist_ok=True)
    if mode == 'move':
        shutil.move(str(src), str(dst))
    elif src.is_dir(): shutil.copytree(src, dst)
    else: shutil.copy2(src, dst)
    for e in entries:
        p = dst/e['relative_path'] if e['relative_path'] else dst
        assert p.stat().st_size == e['size'] and sha(p) == e['sha256'], str(p)
    job['verified'] = True
    save('transfer-manifest.json', manifest)
    print('Verified:', len(entries), 'files;', sum(e['size'] for e in entries), 'bytes', flush=True)

assert all(Path(p).stat().st_size == e['size'] and sha(Path(p)) == e['sha256'] for p,e in runtime.items())
manifest['completed_at'] = datetime.now(timezone.utc).isoformat()
manifest['runtime_jars_unchanged'] = True
save('transfer-manifest.json', manifest)
print('All transfers verified. Runtime JARs unchanged.', flush=True)
