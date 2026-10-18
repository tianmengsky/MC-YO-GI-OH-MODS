"""Map official CID to MDPro3 passcode using explicit third-party double IDs,
corroborated by official language name and the installed MDPro3 card database.
Exact-name-only fallbacks stay candidates and never become verified IDs.
"""
import csv, gzip, hashlib, json, sqlite3, sys, unicodedata
from collections import defaultdict, Counter
from datetime import datetime, timezone
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parent
MDPRO=Path(r'F:\MyCardLibrary\mdpro3')
API_URL='https://db.ygoprodeck.com/api/v7/cardinfo.php?misc=yes'

def norm(s):
    return ' '.join(unicodedata.normalize('NFKC',s or '').split()).casefold()

def write_csv(name,rows,fields):
    with (ROOT/name).open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)

def main():
    api_path=ROOT/'ygoprodeck-cardinfo-misc.json'
    compressed=ROOT/'cache/ygoprodeck-cardinfo-misc.json.gz'
    if api_path.exists():
        raw=api_path.read_bytes();compressed.write_bytes(gzip.compress(raw))
        metadata={'url':API_URL,'retrieved_at':datetime.fromtimestamp(api_path.stat().st_mtime,timezone.utc).isoformat(),
                  'sha256':hashlib.sha256(raw).hexdigest(),'publisher':'YGOPRODeck (third-party, not KONAMI)',
                  'purpose':'Explicit misc_info.konami_id paired with id (engine card password); no card image requests'}
        (ROOT/'cache/ygoprodeck-cardinfo-misc.meta.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),'utf-8')
    elif compressed.exists(): raw=gzip.decompress(compressed.read_bytes())
    else: raise RuntimeError('Download API JSON from documented endpoint first; do not infer passwords from CID.')
    api=json.loads(raw)['data']
    bycid=defaultdict(list)
    for card in api:
        for misc in card.get('misc_info',[]):
            if misc.get('konami_id'):bycid[int(misc['konami_id'])].append(card)
    local_names={};local_desc={};byname=defaultdict(set);local_sources={}
    for language in ['ja-JP','en-US','zh-CN']:
        p=MDPRO/'Data/locales'/language/'cards.cdb'
        con=sqlite3.connect(p)
        names=dict(con.execute('SELECT id,name FROM texts'))
        local_desc[language]=dict(con.execute('SELECT id,desc FROM texts'))
        local_names[language]=names
        if language!='zh-CN':
            for code,name in names.items():byname[(language,norm(name))].add(code)
        local_sources[language]={'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'texts_count':len(names)}
        if language=='zh-CN':
            datas={r[0]:r for r in con.execute('SELECT id,ot,alias,setcode,type,atk,def,level,race,attribute,category FROM datas')}
        con.close()
    official=list(csv.DictReader((ROOT/'cards.csv').open(encoding='utf-8-sig')))
    def canonical(code):
        visited=set()
        while code in datas and code not in visited:
            visited.add(code);alias=datas[code][2]
            if alias not in datas:break
            # Alias also represents rule name substitution. Collapse it only
            # when names, complete current Chinese effect text, and all core
            # rule stats are identical, as required for artwork variants.
            same_name=norm(local_names['zh-CN'].get(code))==norm(local_names['zh-CN'].get(alias))
            same_text=norm(local_desc['zh-CN'].get(code))==norm(local_desc['zh-CN'].get(alias))
            same_stats=datas[code][4:10]==datas[alias][4:10]
            if not (same_name and same_text and same_stats):break
            code=alias
        return code
    mappings=[];candidates=[];engine_bycode={};stats=Counter()
    fields=['konami_cid','engine_passcode','api_primary_id','api_artwork_ids','mdpro3_resource_id','alias_resolution',
        'status','evidence_type','name_corrob_locale','official_name','api_name','mdpro3_name_ja','mdpro3_name_en','name_zh_cn','description_zh_cn','mdpro3_alias','api_source_url','api_card_url']
    for card in official:
        cid=int(card['konami_cid']);api_records=bycid.get(cid,[])
        codes={int(a['id']) for a in api_records}
        record={k:None for k in fields};record['konami_cid']=cid
        if len(codes)==1:
            primary=next(iter(codes));a=api_records[0]
            artwork_ids={primary}|{int(image['id']) for image in a.get('card_images',[])}
            def has_name_evidence(image):
                return norm(local_names['en-US'].get(image))==norm(a['name']) or any(
                    card.get('name_'+loc) and norm(card['name_'+loc])==norm(local_names[local].get(image))
                    for loc,local in [('ja','ja-JP'),('en','en-US')])
            available={image for image in artwork_ids if image in datas and has_name_evidence(image)}
            roots={canonical(image) for image in available}
            record.update(api_primary_id=primary,api_artwork_ids=','.join(map(str,sorted(artwork_ids))),
                evidence_type='explicit_api_cid_artwork_ids_plus_mdpro3_rules_identity',api_source_url=API_URL,
                api_name=a['name'],api_card_url=a.get('ygoprodeck_url'))
            if len(roots)!=1:
                record['status']='api_identifier_not_in_mdpro3' if not roots else 'conflicting_local_artwork_roots'
                mappings.append(record);stats[record['status']]+=1
                for image in sorted(available):candidates.append({'konami_cid':cid,'candidate_passcode':canonical(image),'locale':'en','official_name':card.get('name_en'),'candidate_name':local_names['en-US'].get(image),'evidence_type':'explicit_api_artwork_with_conflicting_local_root','status':'manual_review'})
                continue
            code=next(iter(roots));resource=primary if primary in available else min(available)
            record.update(engine_passcode=code,mdpro3_resource_id=resource,
                alias_resolution='same_name_same_effect_same_rule_stats_local_alias' if code!=resource else 'native_local_id',
                mdpro3_name_ja=local_names['ja-JP'].get(code),mdpro3_name_en=local_names['en-US'].get(code),
                name_zh_cn=local_names['zh-CN'].get(code),description_zh_cn=local_desc['zh-CN'].get(code),mdpro3_alias=datas[resource][2])
            corroboration=[]
            for loc,local in [('ja','ja-JP'),('en','en-US')]:
                official_name=card.get('name_'+loc)
                if official_name and norm(official_name)==norm(local_names[local].get(code)):
                    corroboration.append(loc)
                    if not record['official_name']:record['official_name']=official_name
            if code not in datas:record['status']='api_identifier_not_in_mdpro3'
            elif corroboration:
                record['status']='verified_two_sources';record['name_corrob_locale']=','.join(corroboration)
                d=datas[code]
                engine_bycode[code]=dict(zip(['engine_passcode','ot','alias','setcode','type','atk','def','level_packed','race','attribute','category'],d),
                    konami_cid=cid,name_zh_cn=local_names['zh-CN'].get(code),description_zh_cn=local_desc['zh-CN'].get(code),
                    name_ja=local_names['ja-JP'].get(code),name_en=local_names['en-US'].get(code))
            else:record['status']='api_identifier_needs_name_review'
        elif len(codes)>1:
            record['status']='conflicting_api_passcodes';record['evidence_type']='explicit_api_id_conflict'
            for a in api_records:candidates.append({'konami_cid':cid,'candidate_passcode':a['id'],'locale':'en','official_name':card.get('name_en'),'candidate_name':a['name'],'evidence_type':'conflicting_api_identifier','status':'manual_review'})
        else:record['status']='no_api_identifier';record['evidence_type']='unmapped'
        if record['status']!='verified_two_sources':
            for loc,local in [('ja','ja-JP'),('en','en-US')]:
                name=card.get('name_'+loc)
                if not name:continue
                for code in sorted(byname.get((local,norm(name)),[])):
                    candidates.append({'konami_cid':cid,'candidate_passcode':code,'locale':loc,'official_name':name,
                        'candidate_name':local_names[local][code],'evidence_type':'exact_normalized_name_only','status':'candidate_not_verified'})
        mappings.append(record);stats[record['status']]+=1
    engine=sorted(engine_bycode.values(),key=lambda r:r['engine_passcode'])
    write_csv('card_passcode_mapping.csv',mappings,fields)
    candidate_fields=['konami_cid','candidate_passcode','locale','official_name','candidate_name','evidence_type','status']
    write_csv('mapping_candidates.csv',candidates,candidate_fields)
    if engine:write_csv('engine_cards.csv',engine,list(engine[0]))
    con=sqlite3.connect(ROOT/'inventory.sqlite')
    for name,rows,cols in [('card_passcode_mapping',mappings,fields),('mapping_candidates',candidates,candidate_fields),('engine_cards',engine,list(engine[0]) if engine else ['engine_passcode'])]:
        con.execute('DROP TABLE IF EXISTS '+name)
        con.execute('CREATE TABLE '+name+' ('+','.join('"'+c+'" '+('INTEGER' if c in ['konami_cid','engine_passcode','candidate_passcode','mdpro3_alias'] else 'TEXT') for c in cols)+')')
        if rows:con.executemany('INSERT INTO '+name+' VALUES ('+','.join('?' for c in cols)+')',[[r.get(c) for c in cols] for r in rows])
    con.commit();con.close()
    summary={'generated_at':datetime.now(timezone.utc).isoformat(),'status_counts':dict(stats),'official_card_count':len(official),
        'api_records_with_konami_id':sum(bool(c.get('misc_info',[{}])[0].get('konami_id')) for c in api),
        'verified_meaning':'Explicit YGOPRODeck konami_id plus id/card_images IDs, available MDPro3 artwork ID, and official Japanese or English name corroboration. Local alias is followed only when name, full current Chinese effect text and core rule stats are identical; API primary ID, resource ID and canonical engine passcode remain separate fields. This is corroborated third-party mapping, not an official KONAMI password API.',
        'verified_canonical_engine_card_count':len(engine),
        'artwork_alias_resolutions':sum(r['status']=='verified_two_sources' and r['alias_resolution']!='native_local_id' for r in mappings),
        'local_sources':local_sources,'candidate_count':len(candidates),'name_only_candidates_are_authoritative':False}
    (ROOT/'mapping_coverage.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
