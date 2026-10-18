"""Stage frozen legacy features with an explicit, reviewed non-rule mixin allowlist."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'generated/deps'
COMMON = '''CardsAccess CardItemAccess CardItemLimitMixin CardsBootstrapMixin MonsterTypeMixin
ExpansionsAccess StructureDecksAccess DuelAccess DuelManagerMixin
DeckBoxItemMixin DeckLimitMixin DeckQuickMoveMixin DeckLimitValidateMixin
DuelManagerStarMixin MatchStarMixin GloveStakeLockMixin SurrenderLockMixin
DuelVoiceRoomMixin DuelManagerVoiceRoomMixin'''.split()
CLIENT = '''ClientDuelHandlerMixin CardCatalogScreenSoundMixin CardCatalogScreenGoldCardMixin
CardCatalogSearchMixin CardCatalogRulesTextMixin CardCatalogAccess CardCatalogBinderMixin
DeckBoxBinderMixin DeckBoxScreenAccess DeckBoxScreenSoundMixin
MatchFormatScreenSoundMixin MatchFormatScreenStarMixin MatchFormatScreenRuleMixin
PlayerDuelPartMixin PlayerDuelModelMixin PlayerDuelArmorMixin PlayerDuelRenderMixin'''.split()


def remove_old_effect_install(data):
    # Resolve the exact constant-pool method reference, then NOP the single static call.
    pool, offset, index = {}, 10, 1
    count = struct.unpack_from('>H', data, 8)[0]
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            size = struct.unpack_from('>H', data, offset)[0]
            pool[index] = (tag, data[offset + 2:offset + 2 + size].decode('utf-8'))
            offset += size + 2
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack_from('>H', data, offset)[0])
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, *struct.unpack_from('>HH', data, offset))
            offset += 4
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f'Unknown constant pool tag {tag}')
        index += 1
    refs = [i for i, v in pool.items() if v[0] == 10
            and pool[pool[v[1]][1]][1] == 'dev/codex/yugiohfix/cards/NewEffects'
            and pool[pool[v[2]][1]][1] == 'install']
    assert len(refs) == 1, refs
    call = b'\xb8' + struct.pack('>H', refs[0])
    body = data[offset:]
    assert body.count(call) == 1
    return data[:offset] + body.replace(call, b'\x00\x00\x00')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'inputs/mods/yugioh-interaction-fix-0.103.2.jar'
    target = OUT / 'yugioh-interaction-fix-0.103.2-mdpro.jar'
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as dst:
        for entry in src.infolist():
            data = src.read(entry)
            if entry.filename == 'yugioh_interaction_fix.mixins.json':
                config = json.loads(data)
                assert set(COMMON) <= set(config['mixins'])
                assert set(CLIENT) <= set(config['client'])
                config['mixins'], config['client'] = COMMON, CLIENT
                data = json.dumps(config, indent=2).encode()
            elif entry.filename == 'dev/codex/yugiohfix/mixin/CardsBootstrapMixin.class':
                data = remove_old_effect_install(data)
            elif entry.filename == 'META-INF/mods.toml':
                text = data.decode('utf-8').replace('version="0.103.2"', 'version="0.103.2-mdpro"')
                text += '\n[[dependencies.yugioh_interaction_fix]]\nmodId="yugioh_mdpro_addon"\nmandatory=true\nversionRange="[0.4.0-alpha,)"\nordering="NONE"\nside="BOTH"\n'
                data = text.encode('utf-8')
            dst.writestr(entry, data)
    shutil.copyfile(ROOT / 'inputs/mods/ygostar-1.0.0-forge-1.20.1 (3).jar', OUT / 'ygostar-1.0.0.jar')
    original = Path('F:/Minecarft/MC游戏王1.20.1/.minecraft/versions/1.20.1-Forge_47.4.10/mods')
    shutil.copyfile(original / 'geckolib-forge-1.20.1-4.8.4.jar', OUT / 'geckolib-4.8.4.jar')
    report = {'source': str(source), 'output': str(target), 'common': COMMON, 'client': CLIENT,
              'old_effect_install_removed': True, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
    (ROOT / 'generated/legacy-feature-staging.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
