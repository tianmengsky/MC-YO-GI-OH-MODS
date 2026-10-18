"""Extract explicit no-official-duel notices from cached Japanese/English rows.
No notice found is not a positive legality ruling. Collection source stays intact.
"""
import csv, gzip, html, json, re, sqlite3, sys
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parent
PATTERNS=[re.compile(r'公式[^。\n]{0,30}デュエル[^。\n]{0,30}使用できません'),
          re.compile(r'cannot be used in (?:official )?(?:duels?|a duel)',re.I)]

def clean(s):
    return re.sub(r'\s+',' ',re.sub('<[^>]*>','',html.unescape(s))).strip()

def main():
    products=list(csv.DictReader((ROOT/'products.csv').open(encoding='utf-8-sig')))
    cards=list(csv.DictReader((ROOT/'cards.csv').open(encoding='utf-8-sig')))
    flags={};scanned=set();evidence=[]
    for p in products:
        if p['locale'] not in ['ja','en'] or p['status']!='complete':continue
        s=gzip.decompress((ROOT/'cache'/('product_'+p['product_id'].replace(':','_')+'.html.gz')).read_bytes()).decode('utf-8')
        for chunk in re.split(r'<div\s+class="t_row\s',s)[1:]:
            cid=re.search(r'class="link_value"[^>]+value="[^"]*[?&]cid=(\d+)',chunk)
            if not cid:continue
            cid=int(cid.group(1));scanned.add(cid)
            descriptions=[clean(x) for x in re.findall(r'<dd class="box_card_text[^\"]*">(.*?)</dd>',chunk,re.S)]
            for text in descriptions:
                if any(pattern.search(text) for pattern in PATTERNS):
                    flags[cid]=True
                    evidence.append({'konami_cid':cid,'product_id':p['product_id'],'locale':p['locale'],'notice_text':text,
                        'source_url':p['source_url'],'status':'explicit_official_no_duel_notice'})
    fields=['konami_cid','name_ja','name_en','usage_notice_status','scanned_ja_en_source']
    rows=[{'konami_cid':int(c['konami_cid']),'name_ja':c.get('name_ja'),'name_en':c.get('name_en'),
        'usage_notice_status':'explicit_official_no_duel_notice' if int(c['konami_cid']) in flags else 'no_notice_detected_not_a_legality_certificate',
        'scanned_ja_en_source':int(int(c['konami_cid']) in scanned)} for c in cards]
    for filename,rs,cols in [('card_usage_flags.csv',rows,fields),('card_usage_notice_evidence.csv',evidence,
        ['konami_cid','product_id','locale','notice_text','source_url','status'])]:
        with (ROOT/filename).open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.DictWriter(f,fieldnames=cols);w.writeheader();w.writerows(rs)
    con=sqlite3.connect(ROOT/'inventory.sqlite');con.execute('DROP TABLE IF EXISTS card_usage_flags')
    con.execute('CREATE TABLE card_usage_flags(konami_cid INTEGER,name_ja TEXT,name_en TEXT,usage_notice_status TEXT,scanned_ja_en_source INTEGER)')
    con.executemany('INSERT INTO card_usage_flags VALUES(?,?,?,?,?)',[[r[c] for c in fields] for r in rows]);con.commit();con.close()
    summary={'scanned_ja_en_cids':len(scanned),'explicit_no_duel_notice_cids':len(flags),'evidence_rows':len(evidence),
             'limitation':'Positive restrictions only; no notice found is not proof of current or historical tournament legality. Do not remove these cards from historical collectible membership.'}
    (ROOT/'card_usage_notice_summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
