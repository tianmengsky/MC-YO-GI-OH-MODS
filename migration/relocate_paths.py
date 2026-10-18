"""Update active development paths, retaining exact pre-edit bytes."""
from pathlib import Path
import json, sys, hashlib
sys.stdout.reconfigure(encoding='utf-8')
WS=Path(__file__).resolve().parents[1]
OLD=Path(r'F:\Minecarft\MC游戏王1.20.1\.minecraft\versions\1.20.1-Forge_47.4.10')
pairs=[
    (str(OLD/'mods'/'游戏王重构-HANDOVER-初步诊断与目标清单-v1.md'),str(WS/'docs'/'游戏王重构-HANDOVER-初步诊断与目标清单-v1.md')),
    (str(OLD/'_ygo-analysis'),str(WS/'_ygo-analysis')),
    (r'F:\MyCardLibrary\mdpro3-p0',str(WS/'mdpro3-p0')),
    (r'F:\MyCardLibrary\yugioh-mdcore-addon',str(WS/'addon')),
    (r'F:\MyCardLibrary\mdpro3',str(WS/'inputs'/'mdpro3')),
    (str(OLD/'mods'),str(WS/'inputs'/'mods')),
]
def replace(s):
    for old,new in pairs:
        for o,n in [(old,new),(old.replace('\\','/'),new.replace('\\','/'))]: s=s.replace(o,n)
    return s
def transform(obj):
    if isinstance(obj,str):return replace(obj)
    if isinstance(obj,list):return [transform(x) for x in obj]
    if isinstance(obj,dict):return {k:transform(v) for k,v in obj.items()}
    return obj

roots=[WS/'docs',WS/'mdpro3-p0',WS/'_ygo-analysis'/'execution-plan',WS/'_ygo-analysis'/'compatibility-plan',WS/'_ygo-analysis'/'source-data'/'pack-inventory']
changed=[]
for root in roots:
    for p in sorted(root.rglob('*')):
        if not p.is_file() or p.suffix.lower() not in {'.md','.py','.json','.cs','.csproj','.txt'}:continue
        if any(x in {'cache','__pycache__','bin','obj'} for x in p.relative_to(root).parts):continue
        if p.name in {'manifest.json','raw-signatures.txt','decoded-signatures.txt','decoded-layouts.txt'}:continue
        raw=p.read_bytes()
        try:s=raw.decode('utf-8-sig')
        except UnicodeDecodeError:continue
        if p.suffix=='.json':
            obj=json.loads(s)
            updated=transform(obj)
            if updated==obj:continue
            if p.name=='baseline-manifest.json':
                for before,after in zip(obj['files'],updated['files']):after['original_path']=before['path']
            s2=json.dumps(updated,ensure_ascii=False,indent=2)+'\n'
        else:s2=replace(s)
        if s2==s:continue
        backup=WS/'migration'/'original-text'/p.relative_to(WS)
        assert not backup.exists(), str(backup)
        backup.parent.mkdir(parents=True,exist_ok=True)
        backup.write_bytes(raw)
        p.write_text(s2,encoding='utf-8')
        changed.append({'path':str(p.relative_to(WS)),'original_sha256':hashlib.sha256(raw).hexdigest(),'updated_sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
(WS/'migration'/'path-updates.json').write_text(json.dumps({'path_map':dict(pairs),'changed_files':changed,'historical_logs_and_acl_untouched':True},ensure_ascii=False,indent=2),encoding='utf-8')
for name in ['addon','generated','run-client','run-server']:(WS/name).mkdir(exist_ok=True)
print('Updated paths in',len(changed),'files; exact originals retained.')
