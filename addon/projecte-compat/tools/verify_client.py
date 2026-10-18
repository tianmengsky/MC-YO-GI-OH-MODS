"""Production Forge client smoke. All outputs stay in the workspace."""
import importlib.util
import os
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('integrated_check', ROOT / 'addon/tools/verify_integrated_client.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)
mods = ROOT / 'run-client/mods'
sources = [ROOT / 'generated/deps/ProjectE-1.20.1-PE1.0.1.jar',
           ROOT / 'addon/projecte-compat/build/libs/yugioh-projecte-compat-1.0.0.jar']
with zipfile.ZipFile(sources[1]) as jar:
    assert 'dev/codex/yugiohprojecte/CompatSmoke.class' in jar.namelist(), 'Build with -PcompatSmoke before running this test'
for source in sources:
    shutil.copyfile(source, mods / source.name)
os.environ['YUGIOH_EXTRA_ISOLATED_MODS'] = ';'.join(p.name for p in mods.glob('*.jar') if 'integrated' not in p.name and p.name != 'geckolib-4.8.4.jar')
os.environ['YUGIOH_INTEGRATION_HEAP_MIB'] = '2048'
# Reuse the reviewed launch logic; select our test instead of the MDPro visual fixture.
original = check.subprocess.Popen
def launch(args, **kwargs):
    args = [a for a in args if not a.startswith('-Dyugioh.mdpro.visualSmoke=')]
    args.insert(1, '-Dyugioh.projecte.smoke=true')
    return original(args, **kwargs)
check.subprocess.Popen = launch
check.main('projecte')
shutil.copyfile(ROOT / 'generated/projecte-smoke-result.txt', ROOT / 'generated/projecte-compat/runtime-result.txt')
shutil.copyfile(ROOT / 'generated/integrated-projecte-client.log', ROOT / 'generated/projecte-compat/runtime-client.log')
