"""Audit catalog row coverage, membership keys, counts, and cached provenance.
Does not contact the network or edit source records.
"""
import collections, csv, gzip, hashlib, html, json, re, sqlite3, sys, unicodedata
from pathlib import Path
from datetime import datetime, timezone
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parent
EXPECTED_LOCALE={'ja':'ja_JP','en':'en_US','ko':'ko_KR','ae':'en_SG','de':'de_DE','fr':'fr_FR','it':'it_IT','es':'es_ES','pt':'pt_PT','cn':'zh_CN'}

def norm(s):
    s=html.unescape(re.sub('<[^>]*>','',s))
    return re.sub(r'\s+','',unicodedata.normalize('NFKC',s)).casefold()

def main():
    audits={};failures=[];title_review=[]
    coverage=json.loads((ROOT/'coverage.json').read_text('utf-8'))
    for loc in coverage['locales']:
        cached=ROOT/'cache'/('index_'+loc+'.html.gz')
        if not cached.exists():failures.append({'kind':'missing_index_cache','locale':loc});continue
        raw=gzip.decompress(cached.read_bytes());s=raw.decode('utf-8')
        meta=json.loads((ROOT/'cache'/('index_'+loc+'.meta.json')).read_text('utf-8'))
        if hashlib.sha256(raw).hexdigest()!=meta['sha256']:failures.append({'kind':'index_hash_mismatch','locale':loc})
        ogloc=re.search(r'<meta property="og:locale" content="([^"]+)"',s)
        if not ogloc or ogloc.group(1)!=EXPECTED_LOCALE[loc]:failures.append({'kind':'index_locale_mismatch','locale':loc})
        raw_rows=re.findall(r'<div\b[^>]*\bclass="[^"]*\bt_row\b[^"]*"',s)
        cat=list(csv.DictReader((ROOT/('catalog_'+loc+'.csv')).open(encoding='utf-8-sig')))
        audits[loc]={'raw_t_row_count':len(raw_rows),'catalog_rows':len(cat),'index_parser_skipped_rows':len(raw_rows)-len(cat),
            'in_scope':sum(x['in_scope']=='1' for x in cat),
            'excluded_after_cutoff':sum(x['excluded_reason']=='after_cutoff' for x in cat),
            'excluded_uncertain_date':sum(x['excluded_reason']=='date_uncertain' for x in cat),
            'cached_sha256':meta['sha256'],'cached_retrieved_at':meta['retrieved_at']}
        if len(raw_rows)!=len(cat):failures.append({'kind':'index_parser_skipped_rows','locale':loc,'count':len(raw_rows)-len(cat)})
        unknown=[x for x in cat if x['excluded_reason']=='date_uncertain']
        if unknown:
            with (ROOT/('uncertain_date_products_'+loc+'.json')).open('w',encoding='utf-8') as f:json.dump(unknown,f,ensure_ascii=False,indent=2)
    products=list(csv.DictReader((ROOT/'products.csv').open(encoding='utf-8-sig')))
    duplicate_ids=[k for k,n in collections.Counter(x['product_id'] for x in products).items() if n>1]
    memberships=list(csv.DictReader((ROOT/'product_cards.csv').open(encoding='utf-8-sig')))
    duplicate_rows=[k for k,n in collections.Counter((x['product_id'],x['row_ordinal']) for x in memberships).items() if n>1]
    duplicate_identity_rows=[{'product_id':k[0],'konami_cid':k[1],'row_count':n} for k,n in collections.Counter((x['product_id'],x['konami_cid']) for x in memberships).items() if n>1]
    badcounts=[{'product_id':x['product_id'],'expected':x['expected_card_rows'],'parsed':x['parsed_card_rows'],'status':x['status']} for x in products if x['status']!='complete' or x['expected_card_rows']!=x['parsed_card_rows']]
    cache_checked=0
    for x in products:
        key='product_'+x['product_id'].replace(':','_')
        cache=ROOT/'cache'/(key+'.html.gz');meta=ROOT/'cache'/(key+'.meta.json')
        if not cache.exists() or not meta.exists():failures.append({'kind':'missing_product_cache','product_id':x['product_id']});continue
        raw=gzip.decompress(cache.read_bytes());s=raw.decode('utf-8');m=json.loads(meta.read_text('utf-8'));cache_checked+=1
        if hashlib.sha256(raw).hexdigest()!=m['sha256']:failures.append({'kind':'hash_mismatch','product_id':x['product_id']})
        ogloc=re.search(r'<meta property="og:locale" content="([^"]+)"',s)
        ogurl=re.search(r'<meta property="og:url" content="([^"]+)"',s)
        title=re.search(r'<title>(.*?)</title>',s,re.S)
        if not ogloc or ogloc.group(1)!=EXPECTED_LOCALE[x['locale']]:failures.append({'kind':'locale_mismatch','product_id':x['product_id'],'actual':ogloc.group(1) if ogloc else None})
        if not ogurl or not re.search(r'[?&]pid='+re.escape(x['konami_pid'])+r'(?:&|$)',html.unescape(ogurl.group(1))):failures.append({'kind':'pid_mismatch','product_id':x['product_id']})
        if not title or norm(x['name']) not in norm(title.group(1)):
            title_review.append({'product_id':x['product_id'],'index_name':x['name'],'page_title':html.unescape(title.group(1)) if title else None})
    con=sqlite3.connect(ROOT/'inventory.sqlite')
    sqlite_integrity=con.execute('PRAGMA integrity_check').fetchone()[0]
    db_counts={t:con.execute('SELECT count(*) FROM '+t).fetchone()[0] for t in ['products','cards','product_cards']}
    con.close()
    report={'generated_at':datetime.now(timezone.utc).isoformat(),'index_audits':audits,'products_count':len(products),'membership_rows':len(memberships),
        'product_cache_provenance_checked':cache_checked,'duplicate_product_primary_keys':duplicate_ids,'duplicate_membership_ordinal_keys':duplicate_rows,
        'same_card_multiple_rows_within_product':duplicate_identity_rows,'bad_product_counts':badcounts,'cache_failures':failures,
        'title_review':title_review,'sqlite_integrity':sqlite_integrity,'sqlite_counts':db_counts,
        'passed':not (duplicate_ids or duplicate_rows or badcounts or failures or title_review) and sqlite_integrity=='ok',
        'note':'Multiple source rows for a CID in one product are retained rather than collapsed; they can represent alternate art. They do not establish physical quantities.'}
    (ROOT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps({k:v for k,v in report.items() if k not in ['same_card_multiple_rows_within_product','title_review']},ensure_ascii=False,indent=2))
    print('title_review_count',len(title_review),'multiple_rows_same_card',len(duplicate_identity_rows))

if __name__=='__main__':main()
