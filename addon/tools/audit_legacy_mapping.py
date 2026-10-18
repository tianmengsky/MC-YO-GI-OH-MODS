"""Audit legacy card names against English and Chinese frozen MDPro data.
Ambiguous aliases are reported, never silently folded. No registration changes.
"""
import collections,hashlib,json,re,sqlite3,unicodedata,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]; OUT=ROOT/'generated/legacy-mapping'; OUT.mkdir(parents=True,exist_ok=True)
def normalize(name): return re.sub(r'[^a-z0-9\u3400-\u9fff]','',unicodedata.normalize('NFKC',name).lower())
langs={}; cardnames=collections.defaultdict(set)
for lang in ('en-US','zh-CN'):
 db=ROOT/'inputs/mdpro3/Data/locales'/lang/'cards.cdb'; con=sqlite3.connect(db.as_uri()+'?mode=ro',uri=True)
 for code,name in con.execute('select id,name from texts'): cardnames[(lang,normalize(name))].add(code)
 if lang=='zh-CN': data={r[0]:r for r in con.execute('select id,alias,type,atk,def,level,race,attribute from datas')}
 con.close()
allcards={}
for path in sorted((ROOT/'inputs/mods').glob('*.jar')):
 with zipfile.ZipFile(path) as z:
  available={}
  for lang,mdlang in (('en_us','en-US'),('zh_cn','zh-CN')):
   for entry in z.namelist():
    if entry.endswith('/lang/'+lang+'.json'):
     try: available.setdefault(mdlang,{}).update(json.loads(z.read(entry)))
     except Exception: continue
  for lang,values in available.items():
   for key,name in values.items():
    if not key.startswith('item.yugioh.'): continue
    item=key[len('item.yugioh.'):]
    # Confirm it has a card description, not merely an item localization.
    if not any('yugioh.card.'+item+'.desc' in v for v in available.values()): continue
    r=allcards.setdefault(item,{'registry':'yugioh:'+item,'names':{},'sources':[]})
    r['names'][lang]=name
    if path.name not in r['sources']: r['sources'].append(path.name)
results=[]; accepted={}; counts=collections.Counter()
for item,r in sorted(allcards.items()):
 sets=[cardnames[(lang,normalize(name))] for lang,name in r['names'].items() if cardnames[(lang,normalize(name))]]
 candidates=set.intersection(*sets) if sets else set()
 if not candidates: candidates=set.union(*sets) if sets else set()
 # Art variants: only near-alias records whose complete engine rule fields match.
 canonical=set()
 for code in candidates:
  row=data.get(code)
  if not row: continue
  alias=row[1]; base=data.get(alias)
  if alias and abs(alias-code)<20 and base and row[2:]==base[2:]: code=alias
  canonical.add(code)
 r['candidates']=sorted(candidates); r['canonical_candidates']=sorted(canonical)
 r['status']='mapped' if len(canonical)==1 else 'missing' if not canonical else 'ambiguous'
 if r['status']=='mapped': r['passcode']=next(iter(canonical)); accepted[r['registry']]=r['passcode']
 counts[r['status']]+=1; results.append(r)
(OUT/'mapping-audit.json').write_text(json.dumps({'summary':dict(counts),'cards':results},ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'accepted.json').write_text(json.dumps(accepted,ensure_ascii=False,indent=2),encoding='utf-8')
print(dict(counts))
print('unresolved',[(x['registry'],x['names'],x['canonical_candidates']) for x in results if x['status']!='mapped'][:35])
