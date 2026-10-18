"""Build the reviewed pre-Link pool and compressed display assets from frozen sources.

No web crawling, guessing of passwords, or mutation of source tables is performed.
Run again after pack-art/index.json is updated to incorporate newly verified covers.
"""
import csv
import io
import json
import re
import sqlite3
import zipfile
from collections import defaultdict, Counter
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / '_ygo-analysis/source-data/pack-inventory'
OUT = ROOT / 'generated/content'
RES = OUT / 'resources'
ASSETS = RES / 'assets'
DATA = RES / 'data/yugioh_mdpro_addon'
for d in (OUT, DATA): d.mkdir(parents=True, exist_ok=True)
def rows(name):
    return list(csv.DictReader((SOURCE / name).open(encoding='utf-8-sig', newline='')))
def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')

mapping = json.loads((ROOT / 'generated/legacy-mapping/accepted.json').read_text(encoding='utf-8'))
mapping.update({'yugioh:dark_magician':46986414, 'yugioh:black_luster_soldier':5405694,
                'yugioh:nimble_momonga':22567609, 'yugioh:raimei':56260110})
legacy = {}
for k, v in mapping.items(): legacy.setdefault(v, k.split(':', 1)[1])
engine = {int(r['engine_passcode']):r for r in rows('engine_cards.csv')}
verified = {r['konami_cid']:int(r['engine_passcode']) for r in rows('card_passcode_mapping.csv')
            if r['status'] == 'verified_two_sources'}
assert len(engine) == len(verified) == 8231
all_products = rows('products.csv')
# Game items are limited to boosters and the deck products already represented
# by the original mod. Tins, boxes, publications, events and loose promos stay
# in the source inventory/audit but are not registered as packs.
PACK_KINDS = {'booster_pack','core_booster_pack','special_booster_pack','duelist_pack','duel_terminal',
              'starter_deck','structure_deck','preconstructed_deck'}
policy=json.loads((ROOT/'addon/content/product-policy.json').read_text(encoding='utf-8'))
legacy_reuse=policy['legacy_reuse']
vendor_ids={f'ja:33030{i:02d}' for i in range(1,13)}
compat_products=[p for p in all_products if p['product_kind'] in PACK_KINDS]
RETIRED_PRODUCT_IDS = {p['product_id'] for p in compat_products if p['locale']!='ja' or
    p['product_id'] in vendor_ids or p['product_id'] in legacy_reuse or p['product_id'] in policy['bundled_products']}
products = [p for p in compat_products if p['product_id'] not in RETIRED_PRODUCT_IDS]
# Save compatibility only: excluded from visible products and source mappings.
retired_products = [p for p in all_products if p['product_id'] in RETIRED_PRODUCT_IDS]
assert all(p['locale'] in ('ja','en','cn') and p['status'] == 'complete' and
           (p['release_date'] and p['release_date'] <= '2017-03-24' or
            p['date_quality']=='year_only' and int(p['index_year'])<=2016) for p in products)
official = {r['konami_cid']:r for r in rows('cards.csv')}
flags = {r['konami_cid']:r['usage_notice_status'] for r in rows('card_usage_flags.csv')}
ids = {code:legacy.get(code, f'mdpro_c{code:08d}') for code in engine}
for code, cid in ids.items(): mapping.setdefault('yugioh:'+cid, code)
# Displayed effect text is taken verbatim from the frozen MDPro3 locale databases, so each
# shipped locale shows the official wording instead of the Simplified Chinese one. The
# description carried in historical-content.json stays Chinese on purpose: the native effect
# engine parses it (fusion material lists, summon rites, activation costs, FLIP flags), so it
# must remain byte-identical to the text those parsers expect.
LOCALE_DB={'zh':'zh-CN','en':'en-US','ja':'ja-JP'}
def locale_descriptions(locale):
    path=ROOT/f'inputs/mdpro3/Data/locales/{LOCALE_DB[locale]}/cards.cdb'
    con=sqlite3.connect(path.as_uri()+'?mode=ro',uri=True)
    rows={code:(text or '') for code,text in con.execute('select id,desc from texts')}
    con.close()
    return rows
DISPLAY_TEXT={locale:locale_descriptions(locale) for locale in LOCALE_DB}
for locale,texts in DISPLAY_TEXT.items():
    absent=[code for code in engine if not texts.get(code)]
    assert not absent, f'Missing official {locale} effect text for {len(absent)} cards'
card_rows=[]
zh, en, ja = {}, {}, {}
for code, r in engine.items():
    assert not int(r['type']) & 0x4000000
    card = {k:int(r[k]) for k in ('type','atk','def','level_packed','race','attribute','alias','ot','setcode')}
    card.update(code=code,id=ids[code],cid=int(r['konami_cid']),name=r['name_zh_cn'],
                name_en=r['name_en'] or r['name_ja'],name_ja=r['name_ja'],text=r['description_zh_cn'])
    card_rows.append(card)
    for locale,lang,name in (('zh',zh,card['name']),('en',en,card['name_en']),('ja',ja,card['name_ja'])):
        lang['item.yugioh.'+card['id']]=name
        lang['yugioh.card.'+card['id']+'.desc']=DISPLAY_TEXT[locale].get(code) or card['text']

relations=defaultdict(list)
for r in rows('product_cards.csv'):
    code=verified.get(r['konami_cid'])
    relations[r['product_id']].append(dict(cid=int(r['konami_cid']),code=code or 0,
        id=ids.get(code, f"memorial_cid{r['konami_cid']}"),ordinal=int(r['row_ordinal']),
        rarity_ids=r['rarity_ids'],physical_quantity=r['physical_quantity'],
        collection_number=r['collection_number']))
pack_index=ROOT/'generated/pack-art/index.json'
covers={p['product_id']:p for p in json.loads(pack_index.read_text(encoding='utf-8')).get('products',[])} if pack_index.exists() else {}
supplement=ROOT/'generated/pack-art/supplement-final/index.json'
if supplement.exists():
    covers.update({p['product_id']:p for p in json.loads(supplement.read_text(encoding='utf-8'))['products']})
pack_rows=[]
retired_rows=[]
chinese_products=json.loads((ROOT/'addon/content/product-names-zh.json').read_text(encoding='utf-8'))
chinese_memorial=json.loads((ROOT/'addon/content/memorial-names-zh.json').read_text(encoding='utf-8'))
recipes=json.loads((ROOT/'addon/content/deck-recipes.json').read_text(encoding='utf-8'))
# The downloaded scans have darker midtones than original mod pack icons.
# Brighten midtones without clipping whites/blacks or changing transparency.
PRODUCT_GAMMA=0.82
PRODUCT_LUT=[round(255*(v/255)**PRODUCT_GAMMA) for v in range(256)]
for p in products+retired_products:
    pid=p['product_id']; item='product_'+pid.replace(':','_')
    pr={k:p[k] for k in ('product_id','name','locale','market','product_kind','release_date','date_quality','index_year','cutoff_evidence','source_url')}
    pr.update(id=item,cards=relations[pid],cover=False)
    pr['name_original']=pr['name'];pr['name']=chinese_products[item]
    if pid in legacy_reuse:pr['legacy_item']=legacy_reuse[pid]
    if pid in RETIRED_PRODUCT_IDS:
        pr['hidden_reason']=('legacy_reuse' if pid in legacy_reuse else 'non_japanese_release' if p['locale']!='ja' else
            'retail_bundle' if pid in policy['bundled_products'] else 'removed_vendor_pack')
    recipe=recipes.get(pid)
    if recipe:
        if recipe.get('opening_kind')=='booster':pr['opening_kind']='booster'
        else:pr['deck_recipe']=recipe
    cover=covers.get(pid,{}).get('compressed_file')
    if cover and (ROOT/cover).is_file():
        dst=ASSETS/f'yugioh_mdpro_addon/textures/product/{item}.png';dst.parent.mkdir(parents=True,exist_ok=True)
        im=Image.open(ROOT/cover).convert('RGBA');alpha=im.getchannel('A')
        im=im.convert('RGB').point(PRODUCT_LUT*3).convert('RGBA');im.putalpha(alpha)
        im.thumbnail((64,64),Image.Resampling.LANCZOS)
        im.save(dst,optimize=True)
        icon=Image.new('RGBA',(64,64));icon.alpha_composite(im,((64-im.width)//2,(64-im.height)//2))
        iconpath=ASSETS/f'yugioh_mdpro_addon/textures/item/{item}.png';iconpath.parent.mkdir(parents=True,exist_ok=True);icon.save(iconpath,optimize=True)
        pr['cover']=True
    # Product items carry the reviewed Chinese name only. The source locale stays in metadata;
    # a （日语版）/（英语版） marker is not part of the in-game product name.
    for lang in (zh,en,ja): lang['item.yugioh_mdpro_addon.'+item]=pr['name']
    (retired_rows if pid in RETIRED_PRODUCT_IDS else pack_rows).append(pr)
    write_json(ASSETS/f'yugioh_mdpro_addon/models/item/{item}.json',{'parent':'minecraft:item/generated',
      'textures':{'layer0':'yugioh_mdpro_addon:item/'+item if pr['cover'] else 'yugioh:item/booster'}})
    if pid in legacy_reuse:
        old_id=legacy_reuse[pid].split(':')[1]
        for lang in (zh,en,ja):lang['item.yugioh.'+old_id]=pr['name']
        write_json(ASSETS/f'yugioh/models/item/{old_id}.json',{'parent':'minecraft:item/generated',
            'textures':{'layer0':'yugioh_mdpro_addon:item/'+item}})

# All 30 unmatched records are explicitly non-duel commemorative prize cards.
memorial=[]
memorial_index=ROOT/'generated/pack-art/memorial-images.json'
memorial_images={str(c['konami_cid']):c for c in json.loads(memorial_index.read_text(encoding='utf-8')).get('cards',[])} if memorial_index.exists() else {}
for cid,r in official.items():
    if cid in verified: continue
    assert flags[cid]=='explicit_official_no_duel_notice'
    mid='memorial_cid'+cid
    image=memorial_images.get(cid,{}).get('compressed_file')
    has_image=bool(image and (ROOT/image).is_file())
    memorial.append(dict(id=mid,cid=int(cid),name=chinese_memorial[mid],name_original=r['name_en'] or r['name_ja'],name_ja=r['name_ja'],image=has_image))
    if has_image:
        dst=ASSETS/f'yugioh_mdpro_addon/textures/memorial/{mid}.png';dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes((ROOT/image).read_bytes())
    for lang in (zh,en,ja): lang['item.yugioh_mdpro_addon.'+mid]=chinese_memorial[mid]
    write_json(ASSETS/f'yugioh_mdpro_addon/models/item/{mid}.json',{'parent':'minecraft:builtin/entity','gui_light':'front'})

# Read native text/string table; admission now comes from the verified official inventory.
con=sqlite3.connect((ROOT/'inputs/mdpro3/Data/locales/zh-CN/cards.cdb').as_uri()+'?mode=ro',uri=True)
native={}
for row in con.execute('select * from texts'):
    code,name,text,*strings=row
    native[str(code)]=dict(name=name,text=text,strings=strings,allowed=code in engine)
con.close()
write_json(DATA/'duel-catalog.json',dict(mapping=mapping,cards=native))
write_json(DATA/'historical-content.json',dict(schema=2,cutoff='2017-03-24',cards=card_rows,products=pack_rows,
                                               product_policy=policy,
                                               retired_products=retired_rows,memorial=memorial))
write_json(ASSETS/'yugioh/lang/zh_cn.json',{k:v for k,v in zh.items() if k.startswith(('item.yugioh.','yugioh.card.'))})
for locale,lang in [('zh_cn',zh),('en_us',en),('ja_jp',ja)]:
    lang['itemGroup.yugioh_mdpro_addon.products']={'zh_cn':'卡包与预组','en_us':'Booster Packs & Preconstructed Decks','ja_jp':'パック・構築済みデッキ'}[locale]
    lang['itemGroup.yugioh_mdpro_addon.cards']={'zh_cn':'卡片','en_us':'Cards','ja_jp':'カード'}[locale]
    write_json(ASSETS/f'yugioh_mdpro_addon/lang/{locale}.json',lang)

# Canonical original-mod path also serves DuelScreen's direct hand lookup.
from native_card_faces import compose, REVISION

def compress(im,path,colors=192):
    path.parent.mkdir(parents=True,exist_ok=True)
    if 'A' in im.mode: im=im.convert('RGBA').quantize(colors=colors,method=Image.Quantize.FASTOCTREE,dither=Image.Dither.NONE)
    else: im=im.convert('RGB').quantize(colors=colors,method=Image.Quantize.MEDIANCUT,dither=Image.Dither.FLOYDSTEINBERG)
    im.save(path,optimize=True,compress_level=9)

def face(card):
    path=ASSETS/f"yugioh/textures/card/{card['id']}.png"
    if not path.exists():compress(compose(card),path)
    return path.stat().st_size

missing_closeup=[]; art_bytes=closeup_bytes=closeup_count=0
def convert(card):
    size=face(card)
    src=ROOT/f"inputs/mdpro3/Picture/Closeup/{card['code']}.png"
    dst=ASSETS/f"yugioh_mdpro_addon/textures/closeup/{card['code']}.png"
    if src.exists():
        if not dst.exists():
            im=Image.open(src).convert('RGBA'); im.thumbnail((384,384),Image.Resampling.LANCZOS);compress(im,dst,256)
        return size,dst.stat().st_size,None
    return size,0,card['code'] if card['type']&1 else None
with ThreadPoolExecutor(max_workers=6) as pool:
    for i,(a,c,missing) in enumerate(pool.map(convert,card_rows),1):
        art_bytes+=a;closeup_bytes+=c
        closeup_count+=bool(c)
        if missing is not None:missing_closeup.append(missing)
        if i%1000==0: print(f'compressed {i}/{len(card_rows)}',flush=True)

# Item models use BEWLR; card faces and cutouts stay outside the block/item atlas.
for card in card_rows:
    if card['code'] not in legacy:
        write_json(ASSETS/f"yugioh/models/item/{card['id']}.json",{'parent':'minecraft:builtin/entity','gui_light':'front','display':{
          'gui':{'rotation':[0,0,0],'scale':[1,1,1]},'ground':{'translation':[0,3,0],'scale':[0.5,0.5,0.5]},
          'thirdperson_righthand':{'translation':[0,3,1],'scale':[0.7,0.7,0.7]},'firstperson_righthand':{'rotation':[0,-20,0],'scale':[0.8,0.8,0.8]}}})

audit=dict(schema=1,cutoff='2017-03-24',official_cards=len(official),engine_cards=len(card_rows),
    reused_legacy_products=len(legacy_reuse),visible_products=len(pack_rows)+len(legacy_reuse),market='ja',
    product_cover_gamma=PRODUCT_GAMMA,
    newly_registered_cards=sum(c not in legacy for c in engine),preserved_legacy_ids=len(mapping)-sum(c not in legacy for c in engine),
    commemorative_non_duel=len(memorial),commemorative_images=sum(m['image'] for m in memorial),all_products=len(all_products),products=len(products),
    product_card_rows=sum(len(p['cards']) for p in pack_rows),
    playable_product_card_rows=sum(bool(r['code']) for p in pack_rows for r in p['cards']),
    compressed_card_faces=len(card_rows),card_face_bytes=art_bytes,
    compressed_closeups=closeup_count,closeup_bytes=closeup_bytes,missing_monster_closeups=missing_closeup,
    products_hidden_compat=len(retired_rows),product_covers=sum(p['cover'] for p in pack_rows),missing_product_covers=[p['product_id'] for p in pack_rows if not p['cover']],
    product_kinds=dict(Counter(p['product_kind'] for p in products)),
    notes=['Chinese display faces use extracted native MDPro3 frames, attributes and stars, reduced to original 176x257 resolution.',
      'Commemorative records have no trusted native identity and cannot be used in duels.',
      '40-card game deck recipes retain physical quantities where verified; adaptations and companion editions are audited in generated/deck-composition/recipe-audit.json.'])
write_json(OUT/'audit.json',audit)
print(json.dumps({k:v for k,v in audit.items() if k not in ('missing_product_covers','notes')},ensure_ascii=False),flush=True)
