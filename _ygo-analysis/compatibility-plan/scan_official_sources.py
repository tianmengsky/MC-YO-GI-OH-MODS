import sys
sys.stdout.reconfigure(encoding='utf-8')
import json,re,urllib.request
from html import unescape
from pathlib import Path
from urllib.parse import urljoin
BASE='https://www.db.yugioh-card.com'
INDEX=BASE+'/yugiohdb/card_list.action?clm=1&request_locale=ja'
CUTOFF='2017/03/24'
def fetch(url):
    req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'})
    with urllib.request.urlopen(req,timeout=25) as r:
        return r.read().decode('utf-8')
s=fetch(INDEX)
entries=[]
for chunk in re.split(r'<div\s+class="t_row\s',s)[1:]:
    date=re.search(r'\b(\d{4}/\d{2}/\d{2})\b',chunk)
    name=re.search(r'<p>(.*?)</p>',chunk,re.S)
    link=re.search(r'<input[^>]+class="link_value"[^>]+value="([^"]+)"',chunk)
    if not(date and name and link) or date.group(1)>CUTOFF: continue
    url=urljoin(BASE,unescape(link.group(1)))+'&request_locale=ja'
    pid=re.search(r'[?&]pid=(\d+)',url)
    entries.append({'release_date':date.group(1).replace('/','-'),'name_ja':unescape(re.sub('<[^>]+>','',name.group(1))).strip(),'konami_pid':pid.group(1) if pid else None,'source_url':url})
archive=fetch('https://www.yugioh-card.com/japan/products/?past')
print('anniversary_archive_links',[(u,unescape(re.sub('<[^>]+>','',label))) for u,label in re.findall(r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>',archive,re.S) if 'ANNIVERSARY PACK' in label or '20th ANNIVERSARY' in label])
examples=[]
for key,pid in [('macr','1119003'),('sr04','2201048')]:
    url='https://www.yugioh-card.com/japan/products/?past='+key
    html=fetch(url)
    assets=[urljoin(url,x) for x in re.findall(r'<img[^>]+src="([^"]+)"',html) if '/products/package/' in x and 'loading' not in x]
    detail_url=BASE+'/yugiohdb/card_search.action?ope=1&pid='+pid+'&rp=99999&request_locale=ja'
    detail=fetch(detail_url)
    cids=sorted(set(re.findall(r'cid=(\d+)',detail)),key=int)
    examples.append({'key':key,'product_page':url,'package_image_urls':assets,'card_list_url':detail_url,'konami_card_ids':cids,'distinct_card_count':len(cids),'note':'KONAMI cid is not the ocgcore card passcode; requires mapping. Distinct count is not physical deck quantity.'})
Path(__file__).with_name('konami-pack-sources-pre-link.json').write_text(json.dumps({'checked_date':'2026-10-15','scope':'Japanese OCG product index through 2017-03-24; not global TCG completeness','index_url':INDEX,'products':entries,'verified_examples':examples},ensure_ascii=False,indent=2),encoding='utf-8')
print('saved_products',len(entries),'verified_examples',[(x['key'],x['distinct_card_count'],x['package_image_urls']) for x in examples])
