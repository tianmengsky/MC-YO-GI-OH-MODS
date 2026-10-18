"""Audit and deploy the built addon to the explicitly authorized workspace instance."""
import hashlib
import json
import shutil
import struct
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'addon/build/libs/yugioh-mdpro-addon-0.6.1-alpha.jar'
TARGET_DIR = ROOT / 'MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods'
TARGET = TARGET_DIR / SOURCE.name
assert SOURCE.is_file() and TARGET_DIR.is_dir()
assert not list(TARGET_DIR.glob('yugioh-mdpro-integrated-*.jar')), (
    'Integrated release installed: do not reintroduce standalone duplicates. '
    'Use package_integrated.py and its reviewed integration manifest.')
assert TARGET.resolve().is_relative_to(ROOT.resolve())
previous_addons=list(TARGET_DIR.glob('yugioh-mdpro-addon-*.jar'))
assert len(previous_addons)<=1, 'Inspect duplicate installed addons before deployment'

def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()

with zipfile.ZipFile(SOURCE) as jar:
    assert jar.testzip() is None
    data = json.loads(jar.read('data/yugioh_mdpro_addon/historical-content.json'))
    names = set(jar.namelist())
    allowed = {'booster_pack', 'core_booster_pack', 'special_booster_pack', 'duelist_pack',
               'duel_terminal', 'starter_deck', 'structure_deck', 'preconstructed_deck'}
    assert len(data['cards']) == 8231 and len(data['products']) == 249 and len(data['memorial']) == 30
    retired = data['retired_products']
    assert len(retired)==232
    visible=data['products']+[p for p in retired if 'legacy_item' in p]
    assert len(visible)==267 and all(p['locale']=='ja' for p in visible)
    assert len({p['product_id'] for p in visible})==267
    assert {p['product_id']:p['legacy_item'] for p in retired if 'legacy_item' in p}==data['product_policy']['legacy_reuse']
    assert all(p['cover'] for p in data['products'])
    assert all(p['product_kind'] in allowed for p in data['products'])
    assert all(p['cover'] for p in visible)
    products = {p['id'] for p in data['products']}
    retired_ids = {p['id'] for p in retired}
    assert not products & retired_ids
    model_prefix = 'assets/yugioh_mdpro_addon/models/item/product_'
    assert {Path(n).stem for n in names if n.startswith(model_prefix) and n.endswith('.json')} == products | retired_ids
    for c in data['cards']:
        assert f"assets/yugioh/textures/card/{c['id']}.png" in names
    for p in data['products']:
        if p['cover']:
            assert f"assets/yugioh_mdpro_addon/textures/product/{p['id']}.png" in names
            assert f"assets/yugioh_mdpro_addon/textures/item/{p['id']}.png" in names
            model=json.loads(jar.read(f"assets/yugioh_mdpro_addon/models/item/{p['id']}.json"))
            assert model['parent']=='minecraft:item/generated'
    for c in data['memorial']:
        assert f"assets/yugioh_mdpro_addon/textures/memorial/{c['id']}.png" in names
    assert 'dev/codex/yugiohmdpro/mixin/HistoricalDeckCopiesMixin.class' in names
    assert 'dev/codex/yugiohmdpro/mixin/ServerBanlistMixin.class' in names
    assert 'dev/codex/yugiohmdpro/rules/BanlistCommands.class' in names
    config=json.loads(jar.read('yugioh_mdpro_addon.mixins.json'))
    assert 'ServerBanlistMixin' in config['mixins']
    assert 'JapaneseProductLanguageMixin' in config['client']
    assert 'CardTextureCacheMixin' in config['client']
    optimized=[n for n in names if n.startswith('assets/ygostar/textures/item/') and n.endswith('.png')]
    assert len(optimized)==88
    assert all(struct.unpack('>II',jar.read(n)[16:24])==(256,256) for n in optimized)
    gui=['dev/codex/yugiohmdpro/client/NativeDuelScreen.class']+[
        'dev/codex/yugiohmdpro/client/ui/'+n+'.class' for n in ('DuelLayout','ChoiceModel','DuelUi','DuelWidgets')]
    assert all(n in names for n in gui)
    assert 'dev/codex/yugiohmdpro/content/ProductVisibility.class' in names
    for p in visible:
        if 'legacy_item' in p:
            model=json.loads(jar.read("assets/yugioh/models/item/"+p['legacy_item'].split(':')[1]+'.json'))
            assert model['textures']['layer0']=='yugioh_mdpro_addon:item/'+p['id']
    if previous_addons:
        with zipfile.ZipFile(previous_addons[0]) as old:
            previous = json.loads(old.read('data/yugioh_mdpro_addon/historical-content.json'))
            for n in gui:
                if n in old.namelist():assert jar.read(n)==old.read(n), 'Memory optimization must preserve the deployed parallel GUI implementation'
        assert data['cards'] == previous['cards'], 'Display/deck changes must preserve card identities and data'
        previous_products = {p['id']: p for p in previous['products'] + previous.get('retired_products', [])}
        assert set(previous_products)==products|retired_ids, 'Every previous product must remain registered'
        for p in data['products']+retired:
            old=previous_products[p['id']]
            assert all(p[k]==old[k] for k in ('id','product_id','cards','locale','product_kind','release_date')), 'Product identity/membership must remain unchanged'
            assert p['name_original']==old.get('name_original',old['name']), 'Original product evidence name must survive'
            if 'deck_recipe' in old:assert p['deck_recipe']==old['deck_recipe'], 'Existing product recipes must not change'
    assert not any(n.startswith('assets/yugioh_mdpro_addon/textures/card/') for n in names), 'Obsolete handmade faces must not be bundled'
    decks=[p for p in data['products']+retired if 'deck_recipe' in p]
    assert len(decks)==119 and all(sum(r['count'] for r in p['deck_recipe']['main'])==40 for p in decks)

backups = ROOT / 'generated/deployment-backup'
backups.mkdir(exist_ok=True)
staged=ROOT/'generated'/SOURCE.name
shutil.copy2(SOURCE,staged)
assert sha(staged)==sha(SOURCE)
for previous_path in previous_addons:
    if sha(previous_path)!=sha(SOURCE) or previous_path!=TARGET:
        backup=backups/(previous_path.stem+'-'+sha(previous_path)[:12]+'.jar')
        shutil.copy2(previous_path,backup)
        assert sha(previous_path)==sha(backup)
        previous_path.unlink()
shutil.move(staged,TARGET)
assert sha(SOURCE) == sha(TARGET)
addons = list(TARGET_DIR.glob('yugioh-mdpro-addon-*.jar'))
assert addons == [TARGET], f'Duplicate addons require inspection: {addons}'
result = dict(version='0.6.1-alpha', path=str(TARGET), sha256=sha(TARGET), bytes=TARGET.stat().st_size,
              gui_interaction_optimization=True,optimized_legacy_item_sprites=88,legacy_item_max_edge=256,
              card_texture_cache_budget_bytes=67108864,card_texture_cache_max_count=192,
              cards=8231, memorial=30, products=249, hidden_compat_products=232, visible_products=267,reused_legacy_products=18,market='ja',covers=267,
              membership_rows=sum(len(p['cards']) for p in data['products']),
              card_resolution=[176,257],product_icon_resolution=[64,64],visible_decks=61,visible_boosters=206,compatible_deck_recipes=119,
              dynamic_banlist=True,banlist_op_level=2,network_protocol=5,
              installed_jars=sorted(p.name for p in TARGET_DIR.glob('*.jar')))
(ROOT / 'generated/content-deployment.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
