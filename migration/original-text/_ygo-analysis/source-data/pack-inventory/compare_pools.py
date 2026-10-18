"""Compare verified official memberships with MDPro3 single-date filter.
Differences are review input; this script never changes the default mod pool.
"""
import collections, csv, hashlib, json, sqlite3, sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parent
MDPRO=Path(r'F:\MyCardLibrary\mdpro3')

def write_csv(name,rows,fields):
    with (ROOT/name).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)

def main():
    packcon=sqlite3.connect(MDPRO/'Data/pack/pack.db')
    packrows={int(r[0]):{'pack_id':r[1],'pack_name':r[2],'first_record_date':r[4]} for r in packcon.execute('SELECT id,pack_id,pack,rarity,date FROM pack')}
    packcon.close()
    localcon=sqlite3.connect(MDPRO/'Data/locales/zh-CN/cards.cdb')
    names=dict(localcon.execute('SELECT id,name FROM texts'));data={r[0]:r for r in localcon.execute('SELECT id,alias,type FROM datas')};localcon.close()
    mdset={code for code,p in packrows.items() if p['first_record_date'] and len(p['first_record_date'])==10 and p['first_record_date']<='2017-03-24' and code in data}
    maps=list(csv.DictReader((ROOT/'card_passcode_mapping.csv').open(encoding='utf-8-sig')))
    verified={int(r['konami_cid']):int(r['engine_passcode']) for r in maps if r['status']=='verified_two_sources'}
    members=list(csv.DictReader((ROOT/'product_cards.csv').open(encoding='utf-8-sig')))
    jp_cids={int(r['konami_cid']) for r in members if r['locale']=='ja'}
    global_cids={int(r['konami_cid']) for r in members}
    official_jp={verified[cid] for cid in jp_cids if cid in verified}
    official_global=set(verified.values())
    bycode=collections.defaultdict(list)
    for row in members:
        cid=int(row['konami_cid'])
        if cid in verified:bycode[verified[cid]].append(row)
    products={r['product_id']:r for r in csv.DictReader((ROOT/'products.csv').open(encoding='utf-8-sig'))}
    diff=[]
    for code in sorted(mdset|official_global):
        md=code in mdset;jp=code in official_jp;glob=code in official_global
        if md and jp and glob:continue
        sources=bycode.get(code,[])
        dates=[products[r['product_id']]['release_date'] for r in sources if products[r['product_id']]['release_date']]
        diff.append({'engine_passcode':code,'name_zh_cn':names.get(code),'in_md_single_date_8201_pool':int(md),
            'in_official_jp_verified_pool':int(jp),'in_official_all_locales_verified_pool':int(glob),
            'md_single_record_date':packrows.get(code,{}).get('first_record_date'),'md_pack_id':packrows.get(code,{}).get('pack_id'),
            'md_alias':data.get(code,[None,None,None])[1],'md_type':data.get(code,[None,None,None])[2],
            'official_cids':','.join(str(n) for n in sorted({int(r['konami_cid']) for r in sources})),
            'official_locales':','.join(sorted({r['locale'] for r in sources})),
            'earliest_exact_official_product_date':min(dates) if dates else None,
            'official_product_ids':','.join(sorted({r['product_id'] for r in sources})),
            'review_status':'difference_requires_review_not_auto_pool_change'})
    fields=['engine_passcode','name_zh_cn','in_md_single_date_8201_pool','in_official_jp_verified_pool','in_official_all_locales_verified_pool',
        'md_single_record_date','md_pack_id','md_alias','md_type','official_cids','official_locales','earliest_exact_official_product_date','official_product_ids','review_status']
    write_csv('pool_differences.csv',diff,fields)
    unresolved=[r for r in maps if r['status']!='verified_two_sources']
    write_csv('pool_unmapped_official_cards.csv',unresolved,list(maps[0]))
    summary={'cutoff':'2017-03-24','selected_locales':['ja','en','cn'],'global_field_scope':'Union of selected official Japanese and English products; Chinese catalog has no in-scope releases. Not all global locales.','md_single_date_pool_count':len(mdset),
        'md_pack_source':str(MDPRO/'Data/pack/pack.db'),
        'md_pack_source_sha256':hashlib.sha256((MDPRO/'Data/pack/pack.db').read_bytes()).hexdigest(),
        'official_jp_cids':len(jp_cids),'official_global_cids':len(global_cids),
        'official_jp_verified_passcodes':len(official_jp),'official_global_verified_passcodes':len(official_global),
        'md_only_vs_official_jp_verified':len(mdset-official_jp),'official_jp_verified_only_vs_md':len(official_jp-mdset),
        'md_only_vs_official_global_verified':len(mdset-official_global),'official_global_verified_only_vs_md':len(official_global-mdset),
        'official_jp_unmapped_cids':len(jp_cids-set(verified)),'official_global_unmapped_cids':len(global_cids-set(verified)),
        'difference_rows':len(diff),
        'warning':'Unmapped official CID may correspond to an MD-only passcode; missing from verified set is not proof of historical ineligibility. Do not change default pool automatically.',
        'md_filter_limitation':'pack.db has one row per card and is not a complete historical print/product membership relation.'}
    (ROOT/'pool_comparison.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
