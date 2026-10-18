"""Verify monster mesh removal without losing card, item, disk or portrait assets."""
import hashlib
import io
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREFIX = 'assets/yugioh_interaction_fix/3d/'


def audit():
    config = json.loads((ROOT / 'addon/content/integrated-release.json').read_text('utf-8'))
    old = ROOT / 'addon/build/libs' / config['previous_integrated']['installed_name']
    new = ROOT / config['output']
    with zipfile.ZipFile(old) as a, zipfile.ZipFile(new) as b:
        before = {i.filename: i for i in a.infolist()}
        after = {i.filename: i for i in b.infolist()}
        removed = sorted(set(before) - set(after))
        removed_assets = [p for p in removed if p.startswith('assets/') and not p.endswith('/')]
        assert len(removed_assets) == 1663 and all(p.startswith(PREFIX) for p in removed_assets)
        assert not any(p.startswith(PREFIX) for p in after)
        language_changes = {}
        preserved = 0
        for p in before:
            if not p.startswith(('assets/', 'data/')) or p.startswith(PREFIX):
                continue
            assert p in after, f'Missing preserved resource: {p}'
            if (before[p].CRC, before[p].file_size) == (after[p].CRC, after[p].file_size):
                preserved += 1
                continue
            # Preserve the pre-existing workspace change removing language suffixes
            # from product names; no rules-text, identifier or other resource may differ.
            assert '/lang/' in p and p.endswith('.json'), f'Unexpected resource change: {p}'
            original, current = json.loads(a.read(p)), json.loads(b.read(p))
            assert original.keys() == current.keys()
            changed = [k for k in original if original[k] != current[k]]
            for key in changed:
                assert key.startswith('item.yugioh.booster_') or key.startswith('item.yugioh.structure_deck_') or key.startswith('item.yugioh_mdpro_addon.product_'), key
                assert original[key] == current[key] or original[key] in [current[key] + suffix for suffix in ('（日版）', '（英语版）', '（日语版）')], key
            language_changes[p] = len(changed)
        nested = []
        for row in config['nested']:
            name = f"META-INF/jarjar/{row['artifact']}-{row['version']}.jar"
            payload = b.read(name)
            assert hashlib.sha256(payload).hexdigest() == row['sha256']
            with zipfile.ZipFile(io.BytesIO(payload)) as z:
                assert not any(p.startswith(PREFIX) for p in z.namelist())
            nested.append(name)
        return dict(version=config['version'], previous_bytes=old.stat().st_size, bytes=new.stat().st_size,
                    saved_bytes=old.stat().st_size-new.stat().st_size, removed_monster_files=len(removed_assets),
                    preserved_identical_asset_and_data_entries=preserved, language_name_suffix_changes=language_changes,
                    all_rules_text_and_card_item_disk_portrait_assets_preserved=True,
                    removed_classes=[p for p in removed if p.endswith('.class')],
                    nested_archives_identical=nested, no_monster_3d_in_outer_or_nested=True)


if __name__ == '__main__':
    result = audit()
    (ROOT / 'generated/removal-0612-assets-audit.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
