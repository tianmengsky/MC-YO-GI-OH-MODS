"""Verify generated assets, deck contents and preserved source membership before packaging."""
import json,re,struct,collections,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];RES=ROOT/'generated/content/resources'
data=json.loads((RES/'data/yugioh_mdpro_addon/historical-content.json').read_text(encoding='utf-8'))
cards={c['id']:c for c in data['cards']};products={p['product_id']:p for p in data['products']+data['retired_products']}
def size(path):
    h=path.read_bytes()[:24];assert h[:8]==b'\x89PNG\r\n\x1a\n',path
    return struct.unpack('>II',h[16:24])
zh=json.loads((RES/'assets/yugioh/lang/zh_cn.json').read_text(encoding='utf-8'))
latin_names=[]
for c in cards.values():
    path=RES/f"assets/yugioh/textures/card/{c['id']}.png"
    assert size(path)==(176,257),path
    assert zh['item.yugioh.'+c['id']]==c['name']
    if not re.search('[\u4e00-\u9fff]',c['name']):latin_names.append(c['name'])
    if c['id'].startswith('mdpro_c'):
        model=json.loads((RES/f"assets/yugioh/models/item/{c['id']}.json").read_text())
        assert model['gui_light']=='front'
assert sorted(latin_names)==sorted(['TGX3-DX2','TGX1-HL','TG-SX1','TGX300','Z-ONE','7','TG1-EM1','D3']),latin_names
deck_count=boosters=adapted=extra_count=0
for p in products.values():
    assert re.search('[\u4e00-\u9fff]',p['name']) and 'name_original' in p
    if p['cover']:assert size(RES/f"assets/yugioh_mdpro_addon/textures/item/{p['id']}.png")==(64,64)
    if p['cover']:assert max(size(RES/f"assets/yugioh_mdpro_addon/textures/product/{p['id']}.png"))<=64
    model=json.loads((RES/f"assets/yugioh_mdpro_addon/models/item/{p['id']}.json").read_text())
    assert model['parent']=='minecraft:item/generated'
    recipe=p.get('deck_recipe')
    if not recipe:boosters+=1;continue
    deck_count+=1;adapted+=not recipe['basis'].startswith('physical set list')
    allowed={r['id'] for source in recipe['source_products'] for r in products[source]['cards'] if r['code']}
    assert sum(r['count'] for r in recipe['main'])==40
    assert sum(r['count'] for r in recipe['extra'])<=15
    family=collections.Counter()
    for section in ('main','extra'):
        for row in recipe[section]:
            c=cards[row['id']];assert c['id'] in allowed
            assert bool(c['type']&(0x40|0x2000|0x800000))==(section=='extra')
            assert 1<=row['count']<=3
            family[c['alias'] or c['code']]+=row['count']
    assert max(family.values())<=3,(p['product_id'],family)
    extra_count+=sum(r['count'] for r in recipe['extra'])
assert deck_count==119 and boosters==362
visible=data['products']+[p for p in data['retired_products'] if 'legacy_item' in p]
assert len(visible)==267 and all(p['locale']=='ja' for p in visible)
assert len({p['product_id'] for p in visible})==267
assert sum('deck_recipe' in p for p in visible)==61
assert sum('deck_recipe' not in p for p in visible)==206
policy_audit=dict(market='ja',cutoff=data['cutoff'],cards_preserved=len(cards),registered_imported_products=len(products),
    visible_total=len(visible),visible_boosters=206,visible_decks=61,
    hidden_reasons=dict(collections.Counter(p['hidden_reason'] for p in data['retired_products'])),
    visible=[dict(product_id=p['product_id'],name=p['name'],item=p.get('legacy_item','yugioh_mdpro_addon:'+p['id']),
        kind='deck' if 'deck_recipe' in p else 'booster',legacy_recipe_preserved='legacy_item' in p and 'deck_recipe' in p) for p in visible],
    hidden=[dict(product_id=p['product_id'],name=p['name'],reason=p['hidden_reason']) for p in data['retired_products']])
(ROOT/'generated/content/japanese-product-policy-audit.json').write_text(json.dumps(policy_audit,ensure_ascii=False,indent=2),encoding='utf-8')
assert not list((RES/'assets/yugioh_mdpro_addon/textures/card').glob('*.png'))
provenance=json.loads((ROOT/'generated/mdpro-card-layout/provenance.json').read_text(encoding='utf-8'))
result=dict(cards=len(cards),card_resolution=[176,257],products=len(data['products']),visible_products=len(visible),visible_boosters=206,visible_decks=61,hidden_compat_products=len(data['retired_products']),product_icon_resolution=[64,64],
            deck_boxes=deck_count,main_cards_per_box=40,boosters=boosters,game_adapted_decks=adapted,
            total_extra_cards=extra_count,unchanged_proper_names=latin_names,native_bundle_hashes=provenance['bundles'])
(ROOT/'generated/content/display-and-deck-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False,indent=2))
