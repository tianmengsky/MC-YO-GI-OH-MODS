"""Offline post-migration checks. Does not load native engines or write game files."""
from pathlib import Path
import ast, hashlib, json, shutil, sqlite3, subprocess, sys
from datetime import datetime, timezone
sys.stdout.reconfigure(encoding='utf-8')
WS=Path(__file__).resolve().parents[1]
M=WS/'migration'
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
    return h.hexdigest()
def load(p):return json.loads(p.read_text('utf-8'))
transfer=load(M/'transfer-manifest.json')
assert transfer.get('completed_at') and all(j['verified'] for j in transfer['jobs'])
root=WS/'_ygo-analysis'/'source-data'/'pack-inventory'
for name in ['verification.json','manifest.json','region_catalog_coverage.json','regional_product_gaps.csv','product_composition_review.csv','盘点覆盖与缺口报告.md']:
    p=root/name
    if p.exists():
        b=M/'original-text'/p.relative_to(WS)
        if not b.exists():b.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,b)
p=WS/'_ygo-analysis'/'execution-plan'/'source-data-independent-audit.json'
b=M/'original-text'/p.relative_to(WS)
if not b.exists():b.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,b)
scripts=[root/'verify_inventory.py', root/'finalize_report.py', WS/'_ygo-analysis'/'execution-plan'/'audit_source_inventory.py']
for script in scripts:
    r=subprocess.run([sys.executable,'-u',str(script)],cwd=WS,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (M/(script.stem+'.log')).write_bytes(r.stdout)
    print(script.name,'exit',r.returncode,flush=True)
    assert r.returncode==0,r.stdout.decode('utf-8',errors='replace')

checks={}
checks['all_transfers_sha256_verified_before_path_updates']=all(j['verified'] for j in transfer['jobs'])
checks['moved_sources_absent']=all(not Path(j['source']).exists() for j in transfer['jobs'] if j['operation']=='move')
runtime=load(M/'runtime-jars-before.json')
checks['installed_runtime_jars_unchanged']=all(Path(p).stat().st_size==v['size'] and sha(Path(p))==v['sha256'] for p,v in runtime.items())
checks['runtime_mods_contains_only_jars']=all(p.is_file() and p.suffix.lower()=='.jar' for p in Path(next(iter(runtime))).parent.iterdir())
baseline=load(WS/'_ygo-analysis'/'execution-plan'/'baseline-manifest.json')
checks['baseline_snapshot_hashes_match']=all(Path(e['path']).stat().st_size==e['bytes'] and sha(Path(e['path']))==e['sha256'] for e in baseline['files'])
checks['source_inventory_verification_passes']=load(root/'verification.json')['passed']
audit=load(WS/'_ygo-analysis'/'execution-plan'/'source-data-independent-audit.json')
checks['independent_inventory_checks_pass']=audit['all_checks_pass']
manifest=load(root/'manifest.json')
checks['source_manifest_hashes_match']=all((root/name).stat().st_size==e['size'] and sha(root/name)==e['sha256'] for name,e in manifest['files'].items())
checks['acl_archive_unchanged']=all(sha(Path(j['destination'])/e['relative_path'])==e['sha256'] for j in transfer['jobs'] if Path(j['destination']).name=='acl-recovery' for e in j['files'])
syntax=[]
for folder in [WS/'mdpro3-p0', WS/'_ygo-analysis'/'execution-plan',WS/'_ygo-analysis'/'compatibility-plan',root]:
    for p in folder.rglob('*.py'):
        if any(x in {'cache','__pycache__'} for x in p.relative_to(folder).parts):continue
        ast.parse(p.read_text('utf-8-sig'),filename=str(p));syntax.append(str(p.relative_to(WS)))
checks['relocated_python_syntax_valid']=True
report={'checked_at':datetime.now(timezone.utc).isoformat(),'workspace':str(WS),'checks':checks,'all_checks_pass':all(checks.values()),'independent_check_count':len(audit['checks']),'transfer_files':sum(len(j['files']) for j in transfer['jobs']),'transfer_bytes':sum(e['size'] for j in transfer['jobs'] for e in j['files']),'python_syntax_files':len(syntax),'source_counts':audit['counts'],'not_run':['native probes','Gradle build','Minecraft client/server','network collection']}
(M/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
# Capture final edited text hashes as distinct from byte-identical transferred inputs.
updated=load(M/'path-updates.json')
for entry in updated['changed_files']:entry['updated_sha256']=sha(WS/entry['path'])
(M/'path-updates.json').write_text(json.dumps(updated,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
assert report['all_checks_pass']
