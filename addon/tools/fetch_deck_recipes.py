"""Cache English/Japanese set-list evidence; never changes frozen metadata."""
import json, urllib.request, urllib.parse, concurrent.futures, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'generated/deck-composition';OUT.mkdir(exist_ok=True)
data=json.loads((ROOT/'generated/content/resources/data/yugioh_mdpro_addon/historical-content.json').read_text(encoding='utf-8'))
index=json.loads((ROOT/'generated/pack-art/index.json').read_text(encoding='utf-8'))['products']
supp=ROOT/'generated/pack-art/supplement-final/index.json'
if supp.exists():index+=json.loads(supp.read_text(encoding='utf-8'))['products']
art={p['product_id']:p for p in index}
cache=OUT/'set-lists.json'
pages=json.loads(cache.read_text(encoding='utf-8')) if cache.exists() else {}
titles={}
for p in data['products']:
    if p['product_kind'] not in ('starter_deck','structure_deck','preconstructed_deck'):continue
    title=art[p['product_id']].get('page_title','')
    if title:titles[p['product_id']]='Set Card Lists:'+title+(' (TCG-EN)' if p['locale']=='en' else ' (OCG-JP)')
def fetch(title):
    if title in pages:return title,pages[title]
    url='https://yugipedia.com/api.php?'+urllib.parse.urlencode(dict(action='query',format='json',titles=title,prop='revisions',rvprop='content',redirects='1'))
    for attempt in range(3):
        try:
            req=urllib.request.Request(url,headers={'User-Agent':'MinecraftYugiohMetadataImport/0.5 (noncommercial local resource research)'})
            obj=json.load(urllib.request.urlopen(req,timeout=25))
            row=next(iter(obj['query']['pages'].values()))
            return title,dict(title=row['title'],text=row.get('revisions',[{}])[0].get('*',''),url=url)
        except Exception as e:
            if attempt==2:return title,dict(error=str(e))
            time.sleep(1)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    for i,(title,row) in enumerate(pool.map(fetch,sorted(set(titles.values()))),1):
        pages[title]=row
        cache.write_text(json.dumps(pages,ensure_ascii=False,indent=2),encoding='utf-8')
        if i%20==0:print(i,len(pages),flush=True)
(OUT/'product-titles.json').write_text(json.dumps(titles,ensure_ascii=False,indent=2),encoding='utf-8')
print('Found',sum(bool(p.get('text')) for p in pages.values()),'of',len(pages),flush=True)
