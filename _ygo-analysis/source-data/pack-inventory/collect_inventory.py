"""Reproducible KONAMI product membership inventory. Python 3 stdlib only.
Default scope: official Japanese/English plus Chinese catalog check, release <= 2017-03-24.
No image fetching. Throttled, restartable HTML gzip cache. Current card text is
NOT a historical 2017 ruling/text snapshot. See README and coverage report.
"""
import argparse, collections, concurrent.futures, csv, gzip, hashlib, html, json
import re, sqlite3, sys, threading, time, urllib.error, urllib.request
from pathlib import Path
from datetime import datetime, timezone

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parent
BASE = 'https://www.db.yugioh-card.com'
CUTOFF = '2017-03-24'
CACHE = ROOT / 'cache'
CACHE.mkdir(exist_ok=True)
lock = threading.Lock()
access_blocked = threading.Event()
last_request = 0.0
INTERVAL = 0.5
LOCALES = ['ja','en','ko','ae','de','fr','it','es','pt','cn']
ACTIVE_LOCALES = ['ja','en','cn']
MARKETS = {'ja':'OCG-JP','ko':'OCG-KR','ae':'OCG-Asian-English','cn':'OCG-Simplified-Chinese',
           'en':'TCG-English','de':'TCG-German','fr':'TCG-French','it':'TCG-Italian','es':'TCG-Spanish','pt':'TCG-Portuguese'}

def now():
    return datetime.now(timezone.utc).isoformat()

def clean(s):
    return re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]*>', '', s))).strip()

def fetch(url, key):
    global last_request
    dest = CACHE / (key + '.html.gz')
    meta = CACHE / (key + '.meta.json')
    if dest.exists() and meta.exists():
        return gzip.decompress(dest.read_bytes()).decode('utf-8'), json.loads(meta.read_text('utf-8'))
    if access_blocked.is_set():
        raise RuntimeError('Network collection stopped after an access-denied or rate-limited response; cached pages remain available.')
    for attempt in range(3):
        with lock:
            if access_blocked.is_set():
                raise RuntimeError('Network collection stopped after an access-denied or rate-limited response.')
            delay = max(0, last_request + INTERVAL - time.monotonic())
            if delay: time.sleep(delay)
            last_request = time.monotonic()
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (historical card product research)'})
            with urllib.request.urlopen(req, timeout=40) as response:
                raw = response.read()
                details = {'url': url, 'retrieved_at': now(), 'sha256': hashlib.sha256(raw).hexdigest(), 'status': response.status}
            if b'card_list' not in raw and b'card_search' not in raw:
                raise ValueError('Unexpected HTML, not a card database page')
            dest.write_bytes(gzip.compress(raw))
            meta.write_text(json.dumps(details, ensure_ascii=False, indent=2), 'utf-8')
            return raw.decode('utf-8'), details
        except urllib.error.HTTPError as exc:
            if exc.code in (401, 403, 429):
                access_blocked.set()
                raise RuntimeError(f'Official server denied/rate-limited access (HTTP {exc.code}); stop this page without bypass') from exc
            if attempt == 2: raise
            time.sleep(2 * (attempt + 1))
        except Exception:
            if attempt == 2: raise
            time.sleep(2 * (attempt + 1))

def product_kind(category):
    translations = {
        '정규 부스터 팩':'基本ブースターパック','기타 부스터 팩':'その他ブースターパック','대회':'Tournaments',
        '동봉':'商品同梱','서적':'書籍','스타터 덱':'Starter Decks','스트럭처 덱':'Structure Decks',
        '스페셜 세트':'special_set','프로모션':'Promotional Cards','Booster Packs (Basic)':'基本ブースターパック',
        'Promokarten':'Promotional Cards','Sonstiges':'Others','Special Edition Boxen':'Special Edition Boxes',
        'Turnier':'Tournaments','Videospiel-Bundles':'Video Game Bundles','Zeitschriften, Bücher, Comics':'Magazines, Books, Comics',
        'Autres':'Others','Booster':'Booster Packs','Boîtes':'Tins','Cartes promotionnelles':'Promotional Cards',
        'Deck de Démarrage':'Starter Decks','Deck de Structure':'Structure Decks','Jeux vidéo':'Video Game Bundles',
        'Magazines, Livres, Mangas':'Magazines, Books, Comics','Pack du Duelliste':'Duelist Packs','Tournois':'Tournaments',
        'Édition Spéciale':'Special Edition Boxes','Altro':'Others','Bundle videogiochi':'Video Game Bundles',
        'Buste di Espansione':'Booster Packs','Carte promozionali':'Promotional Cards','Edizioni Speciali':'Special Edition Boxes',
        'Tornei':'Tournaments','Barajas de Estructura':'Structure Decks','Barajas de Principiante':'Starter Decks',
        'Cajas Edición Especial':'Special Edition Boxes','Cartas promocionales':'Promotional Cards','Latas':'Tins',
        'Otros':'Others','Sobres de Cartas':'Booster Packs','Sobres de Duelista':'Duelist Packs','Torneos':'Tournaments',
        'Videojuegos':'Video Game Bundles','Decks Estruturais':'Structure Decks','Decks Iniciais':'Starter Decks',
        'Estampas Promocionais':'Promotional Cards','Outros':'Others','Pacotes':'Booster Packs',
        'Pacotes do Duelista':'Duelist Packs','Torneios':'Tournaments',
    }
    category = translations.get(category,category)
    return {
        'special_set':'special_set',
        'Booster Packs':'booster_pack', 'Special Edition Boxes':'special_edition_box',
        'Starter Decks':'starter_deck', 'Structure Decks':'structure_deck',
        'Tins':'tin', 'Duelist Packs':'duelist_pack', 'Duel Terminal Cards':'duel_terminal',
        'Magazines, Books, Comics':'publication_promo', 'Tournaments':'tournament',
        'Promotional Cards':'promo', 'Video Game Bundles':'video_game_promo',
        'ブースターパック':'booster_pack', '基本ブースターパック':'core_booster_pack',
        'その他ブースターパック':'special_booster_pack', '構築済みデッキ':'preconstructed_deck',
        'デュエリストパック':'duelist_pack', 'デュエルターミナル':'duel_terminal',
        '書籍・コミック':'publication_promo', '大会':'tournament',
        'ゲーム':'video_game_promo', 'ゲーム同梱':'video_game_promo', '書籍':'publication_promo',
        'プロモーション':'promo', '商品同梱':'product_bundle', 'その他':'other', 'Others':'other',
    }.get(category, 'unclassified')

def parse_index(locale):
    url = BASE + '/yugiohdb/card_list.action?clm=1&request_locale=' + locale
    s, meta = fetch(url, 'index_' + locale)
    products = []
    catalog = []
    for chunk in re.split(r'<div\s+class="t_row\s', s)[1:]:
        d = re.search(r'\b(\d{4}/\d{2}/\d{2})\b', chunk)
        n = re.search(r'<p>(.*?)</p>', chunk, re.S)
        link = re.search(r'<input[^>]+class="link_value"[^>]+value="([^"]+)"', chunk)
        if not (d and n and link): continue
        pid = re.search(r'[?&]pid=(\d+)', html.unescape(link.group(1)))
        if not pid: continue
        date_raw = d.group(1).replace('/', '-')
        cm = re.search(r'<div class="catergory">(.*?)</div>', chunk, re.S)
        spans = re.findall(r'<span[^>]*>(.*?)</span>', cm.group(1), re.S) if cm else []
        category = clean(spans[0]).strip('【】').strip() if spans else ''
        year = next((int(clean(x)) for x in spans[1:] if re.fullmatch(r'\d{4}', clean(x))), None)
        sentinel = date_raw.startswith('1990-')
        if sentinel:
            eligible = year is not None and year <= 2016
            date = None
            quality = 'year_only' if year else 'unknown'
        else:
            eligible = date_raw <= CUTOFF
            date = date_raw
            quality = 'exact_index_date'
        product_id = locale + ':' + pid.group(1)
        record={'product_id':product_id,'locale':locale,'market':MARKETS[locale],
            'konami_pid':pid.group(1),'name':clean(n.group(1)),'category_official':category,
            'product_kind':product_kind(category),'index_year':year,'release_date':date,
            'release_date_raw':date_raw,'date_quality':quality,'cutoff_evidence':'index_category_year_before_2017' if sentinel else 'index_date',
            'source_url':BASE+'/yugiohdb/card_search.action?ope=1&pid='+pid.group(1)+'&rp=99999&request_locale='+locale,
            'index_source_url':url,'index_retrieved_at':meta['retrieved_at'],'status':'pending',
            'expected_card_rows':None,'parsed_card_rows':0,'distinct_cids':0,'error':None}
        catalog.append(dict(record,in_scope=int(eligible),excluded_reason=None if eligible else ('date_uncertain' if sentinel else 'after_cutoff')))
        if eligible: products.append(record)
    write_csv('catalog_'+locale+'.csv',catalog)
    return products

def parse_product(p):
    result = dict(p)
    try:
        s, meta = fetch(p['source_url'], 'product_' + p['product_id'].replace(':','_'))
        total_box = re.search(r'<div class="sort_set">\s*<div class="text">(.*?)</div>',s,re.S)
        expected = re.search(r'(\d[\d,]*)',clean(total_box.group(1))) if total_box else None
        if expected: result['expected_card_rows'] = int(expected.group(1).replace(',',''))
        result['retrieved_at'] = meta['retrieved_at']
        result['html_sha256'] = meta['sha256']
        rows=[]
        for chunk in re.split(r'<div\s+class="t_row\s',s)[1:]:
            cid = re.search(r'class="link_value"[^>]+value="[^"]*[?&]cid=(\d+)',chunk)
            name = re.search(r'<span class="card_name">(.*?)</span>',chunk,re.S)
            if not (cid and name): continue
            name = clean(name.group(1))
            rarity = sorted(set(re.findall(r't_rid_(\d+)',chunk.split('>')[0])),key=int)
            rows.append({'product_id':p['product_id'],'row_ordinal':len(rows)+1,'konami_cid':int(cid.group(1)),
                'card_name':name,'locale':p['locale'],'rarity_ids':','.join(rarity),
                'collection_number':None,'physical_quantity':None,
                'source_url':p['source_url'],'source_retrieved_at':meta['retrieved_at']})
        result['parsed_card_rows']=len(rows)
        result['distinct_cids']=len(set(r['konami_cid'] for r in rows))
        result['status']='complete' if len(rows)>0 and result['expected_card_rows']==len(rows) else 'count_mismatch_or_missing_count'
        if result['status']!='complete': result['error']='Expected row count missing or differs; cannot certify full membership.'
        return result, rows
    except Exception as exc:
        result['status']='fetch_failed'
        result['error']=str(exc)
        return result, []

def write_csv(name, records, fields=None):
    fields = fields or list(records[0]) if records else fields or []
    with (ROOT/name).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');w.writeheader();w.writerows(records)

def export(products, membership):
    products=sorted(products,key=lambda p:p['product_id'])
    membership=sorted(membership,key=lambda r:(r['product_id'],r['row_ordinal']))
    cards = {}
    for row in membership:
        c=cards.setdefault(row['konami_cid'],dict(konami_cid=row['konami_cid'],**{'name_'+loc:None for loc in LOCALES}))
        c['name_'+row['locale']]=row['card_name']
    cardlist=sorted(cards.values(),key=lambda c:c['konami_cid'])
    write_csv('products.csv',products)
    write_csv('product_cards.csv',membership)
    write_csv('cards.csv',cardlist)
    con=sqlite3.connect(ROOT/'inventory.sqlite')
    for table,records in [('products',products),('cards',cardlist),('product_cards',membership)]:
        con.execute('DROP TABLE IF EXISTS '+table)
        if not records: continue
        fields=list(records[0]);integers={'konami_cid','row_ordinal','index_year','expected_card_rows','parsed_card_rows','distinct_cids','physical_quantity'}
        con.execute('CREATE TABLE '+table+' ('+','.join('"'+k+'" '+('INTEGER' if k in integers else 'TEXT') for k in fields)+')')
        con.executemany('INSERT INTO '+table+' VALUES ('+','.join('?' for _ in fields)+')',[[r.get(k) for k in fields] for r in records])
    if membership:
        con.execute('CREATE INDEX idx_membership_cid ON product_cards(konami_cid)')
        con.execute('CREATE INDEX idx_membership_product ON product_cards(product_id)')
    con.commit();con.close()
    status=collections.Counter(p['status'] for p in products)
    coverage={'generated_at':now(),'cutoff':CUTOFF,'scope':'Selected official Japanese and English product catalogs; Chinese official catalog checked and local MDPro3 Chinese database used for card cross-validation. Completeness is relative to cached selected catalog snapshots, not a global historical bibliography. Other locale caches are research references only.',
        'selected_locales':ACTIVE_LOCALES,
        'product_count':len(products),'status_counts':dict(status),'membership_rows':len(membership),'distinct_konami_cids':len(cards),
        'locales':{loc:{'products':sum(p['locale']==loc for p in products),'complete':sum(p['locale']==loc and p['status']=='complete' for p in products),
        'distinct_cids':len({r['konami_cid'] for r in membership if r['locale']==loc})} for loc in ACTIVE_LOCALES},
        'unknown_exact_dates':sum(p['date_quality']!='exact_index_date' for p in products),
        'gaps':[{k:p.get(k) for k in ['product_id','name','status','expected_card_rows','parsed_card_rows','error']} for p in products if p['status']!='complete'],
        'limitations':['Official catalogs can omit discontinued and region-specific products; completeness is only relative to fetched catalog snapshots.',
        'Language catalogs do not establish all North American/European/Oceanian country-specific product release dates. Preserve locale and market instead of merging regional printings.',
        'Official membership is distinct card identities/rows, not physical deck quantities or booster collation odds.',
        'Collection numbers and physical quantities are null until separate print/product verification.',
        'Current live text and product catalog are not a snapshot of 2017 card effect/ruling errata.',
        'Product membership cannot replace CID to engine passcode mapping; mappings are separate evidence records.']}
    (ROOT/'coverage.json').write_text(json.dumps(coverage,ensure_ascii=False,indent=2),'utf-8')
    return coverage

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--workers',type=int,default=3);parser.add_argument('--interval',type=float,default=.5)
    parser.add_argument('--locales',nargs='+',default=['ja','en','cn'],choices=LOCALES)
    parser.add_argument('--limit',type=int);args=parser.parse_args()
    global INTERVAL, ACTIVE_LOCALES
    ACTIVE_LOCALES=list(args.locales)
    INTERVAL=max(.5,args.interval)
    products=[]
    for locale in args.locales:
        ps=parse_index(locale);products.extend(ps);print(f'INDEX {locale}: {len(ps)} products',flush=True)
    if args.limit: products=products[:args.limit]
    results={p['product_id']:p for p in products};members={}
    export(products,[])
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(3,max(1,args.workers))) as pool:
        futures=[pool.submit(parse_product,p) for p in products]
        for i,future in enumerate(concurrent.futures.as_completed(futures),1):
            p,rows=future.result();results[p['product_id']]=p;members[p['product_id']]=rows
            if p['status']!='complete':print('GAP',p['product_id'],p['status'],p.get('expected_card_rows'),len(rows),p.get('error'),flush=True)
            if i%50==0 or i==len(products):
                report=export(list(results.values()),[r for rs in members.values() for r in rs])
                print(f"PROGRESS {i}/{len(products)} complete={report['status_counts'].get('complete',0)} rows={report['membership_rows']} cards={report['distinct_konami_cids']}",flush=True)
    print('FINISHED',json.dumps(report,ensure_ascii=False)[:500],flush=True)

if __name__=='__main__':main()
