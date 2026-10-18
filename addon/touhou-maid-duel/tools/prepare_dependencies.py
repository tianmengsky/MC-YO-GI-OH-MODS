"""Stage read-only local mod inputs as small, named compile dependencies."""
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[3]
DEPS = ROOT / 'generated/deps'
DEPS.mkdir(parents=True, exist_ok=True)
maid = ROOT / 'MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods/[车万女仆] touhoulittlemaid-1.5.3-forge+mc1.20.1.jar'
shutil.copyfile(maid, DEPS / 'touhoulittlemaid-1.5.3.jar')
with zipfile.ZipFile(ROOT / 'addon/build/libs/yugioh-mdpro-addon-0.6.28-alpha.jar') as source:
    with zipfile.ZipFile(DEPS / 'yugioh-mdpro-api-0.6.28.jar', 'w', zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            if entry.filename.endswith('.class'):
                target.writestr(entry.filename, source.read(entry))
print('Staged TLM 1.5.3 and class-only MDPro compile API in generated/deps')
