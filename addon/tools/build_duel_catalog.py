"""Create runtime identity/text data without changing frozen inputs or registrations."""
import json, sqlite3
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
out = ROOT/'addon/src/main/resources/data/yugioh_mdpro_addon'
out.mkdir(parents=True, exist_ok=True)
mapping = json.loads((ROOT/'generated/legacy-mapping/accepted.json').read_text(encoding='utf-8'))
# Explicit reviewed identities: these are the original definitions, not similarly named variants.
mapping.update({'yugioh:dark_magician':46986414, 'yugioh:black_luster_soldier':5405694,
                'yugioh:nimble_momonga':22567609, 'yugioh:raimei':56260110})
con = sqlite3.connect((ROOT/'inputs/mdpro3/Data/locales/zh-CN/cards.cdb').as_uri()+'?mode=ro', uri=True)
con.execute('attach database ? as pk', ((ROOT/'inputs/mdpro3/Data/pack/pack.db').as_uri()+'?mode=ro',))
allowed = {r[0] for r in con.execute("select d.id from datas d join pk.pack p using(id) where p.date<='2017-03-24' and (d.type&0x4000000)=0")}
blocked = {27927359,54415063,80316585,91932350}
cards = {}
for row in con.execute('select d.id,t.name,t.desc,t.str1,t.str2,t.str3,t.str4,t.str5,t.str6,t.str7,t.str8,t.str9,t.str10,t.str11,t.str12,t.str13,t.str14,t.str15,t.str16 from datas d join texts t using(id)'):
    code,name,desc,*strings = row
    cards[str(code)] = {'name':name, 'text':desc, 'strings':strings, 'allowed':code in allowed and code not in blocked}
con.close()
(out/'duel-catalog.json').write_text(json.dumps({'mapping':mapping,'cards':cards},ensure_ascii=False,separators=(',',':')),encoding='utf-8')
print('catalog:',len(mapping),'identities,',len(cards),'engine cards')
