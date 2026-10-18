"""Reproduce the display-only source port; never load the old rule mixins."""
from pathlib import Path
import re
import shutil
import zipfile

root = Path(__file__).resolve().parents[2]
mapping = {}
for line in (root / 'addon/build/createMcpToSrg/output.tsrg').read_text().splitlines():
    if line.startswith('\t') and not line.startswith('\t\t'):
        parts = line.split()
        if len(parts) >= 2 and re.fullmatch(r'[mf]_\d+_', parts[-1]):
            mapping[parts[-1]] = parts[0]
names = {
    'client': ['ClientBoardAnchor', 'InWorldBoardRenderer', 'DuelScreenHolograms',
               'HologramArt', 'HologramActions'],
    'board': ['BoardLayout', 'BoardPlan', 'HologramMotion', 'ScreenPanel', 'PinnedAnchor'],
}
for folder, classes in names.items():
    for name in classes:
        source = root / f'generated/fix-source/dev/codex/yugiohfix/{folder}/{name}.java'
        text = source.read_text(encoding='utf-8')
        text = re.sub(r'\b[mf]_\d+_\b', lambda m: mapping.get(m[0], m[0]), text)
        text = text.replace('dev.codex.yugiohfix', 'dev.codex.yugiohmdpro.visual')
        text = text.replace('modid="yugioh_interaction_fix"', 'modid="yugioh_mdpro_addon"')
        text = re.sub(r'case HologramMotion.Action\.', 'case ', text)
        text = re.sub(r'case Action\.', 'case ', text)
        text = text.replace('Object object = vertices.iterator();', 'java.util.Iterator<float[]> object = vertices.iterator();')
        text = text.replace('for (Object element : declared)', 'for (JsonElement element : declared)')
        text = text.replace('Object result = new float[][]', 'float[][] result = new float[][]')
        text = text.replace('            break;\n        }\n        for (int i = 0; i < cornerPositions.size();', '        }\n        for (int i = 0; i < cornerPositions.size();')
        target = root / f'addon/src/main/java/dev/codex/yugiohmdpro/visual/{folder}/{name}.java'
        target.parent.mkdir(parents=True, exist_ok=True)
        # Keep reviewed native-display adaptations when refreshing assets.
        if not target.exists():
            target.write_text('// Display port from the frozen yugioh-interaction-fix-0.103.2 input.\n' + text, encoding='utf-8')

# The original packs are read-only sources. Bundle their display resources so the
# test profile does not require enabling optional packs or loading the old engine.
packs = Path('F:/Minecarft/MC游戏王1.20.1/.minecraft/versions/1.20.1-Forge_47.4.10/resourcepacks')
resources = root / 'addon/src/main/resources'
for name in ['yugioh-monster-art.zip']:
    with zipfile.ZipFile(packs / name) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not entry.filename.startswith('assets/') or entry.filename.startswith('assets/yugioh_interaction_fix/3d/'):
                continue
            target = (resources / entry.filename).resolve()
            if not target.is_relative_to(resources.resolve()):
                raise ValueError(entry.filename)
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry) as source, target.open('wb') as output:
                shutil.copyfileobj(source, output)
print('Ported display classes and original monster resource packs.')
