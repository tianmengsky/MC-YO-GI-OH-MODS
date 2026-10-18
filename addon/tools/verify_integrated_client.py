"""Launch the actual integrated release using the installed Forge 47.4.28 runtime.

No Gradle userdev or loose mod classes: only the release JAR and GeckoLib are
placed in run-client/mods. All test writes remain in the workspace.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSTANCE = ROOT / 'MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28'
GAME = ROOT / 'run-client'
CONFIG = json.loads((ROOT / 'addon/content/integrated-release.json').read_text('utf-8'))
RELEASE = ROOT / CONFIG['output']
LIBS = ROOT / 'MCYoGiOh/.minecraft/libraries'
JAVA = Path('C:/Program Files/jdk-17.0.19+10/bin/java.exe')


def allowed(row):
    if 'rules' not in row:
        return True
    result = False
    for rule in row['rules']:
        if 'features' in rule:
            continue
        target = rule.get('os', {})
        if target.get('name', 'windows') != 'windows':
            continue
        if target.get('arch', 'amd64') not in ('amd64', 'x86_64'):
            continue
        result = rule['action'] == 'allow'
    return result


def main(mode):
    mods = GAME / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    expected = {RELEASE.name, 'geckolib-4.8.4.jar'}
    extra_mods = {name for name in os.environ.get('YUGIOH_EXTRA_ISOLATED_MODS', '').split(';') if name}
    expected.update(extra_mods)
    previous = CONFIG.get('previous_integrated')
    if previous:
        old=mods/previous['installed_name']
        if old.exists():
            with old.open('rb') as stream:
                assert hashlib.file_digest(stream,'sha256').hexdigest()==previous['sha256']
            old.unlink()
    assert all(p.name in expected for p in mods.glob('*.jar')), 'Inspect unexpected isolated mods'
    for source in (RELEASE, ROOT / 'generated/deps/geckolib-4.8.4.jar'):
        target = mods / source.name
        if target.exists():
            with source.open('rb') as a,target.open('rb') as b:
                identical=hashlib.file_digest(a,'sha256').digest()==hashlib.file_digest(b,'sha256').digest()
        else:
            identical=False
        if not identical:
            shutil.copyfile(source, target)
        with source.open('rb') as a, target.open('rb') as b:
            assert hashlib.file_digest(a, 'sha256').digest() == hashlib.file_digest(b, 'sha256').digest()
    runtime = GAME / 'mdpro-runtime'
    if not runtime.exists():
        shutil.copytree(INSTANCE / 'mdpro-runtime', runtime)
    version = json.loads((INSTANCE / '1.20.1-Forge_47.4.28.json').read_text('utf-8'))
    classpath = []
    for row in version['libraries']:
        if not allowed(row):
            continue
        p = LIBS / row['downloads']['artifact']['path']
        assert p.is_file(), p
        classpath.append(str(p))
    classpath.append(str(INSTANCE / '1.20.1-Forge_47.4.28.jar'))
    natives = GAME / 'integration-natives'
    if not natives.exists():
        shutil.copytree(INSTANCE / '1.20.1-Forge_47.4.28-natives', natives)
    values = dict(natives_directory=str(natives),
                  launcher_name='WorkspaceIntegrationCheck', launcher_version='1',
                  classpath=';'.join(classpath), library_directory=str(LIBS), classpath_separator=';',
                  version_name=version['id'], auth_player_name='IntegrationCheck',
                  game_directory=str(GAME), assets_root=str(ROOT / 'MCYoGiOh/.minecraft/assets'),
                  assets_index_name=version['assets'], auth_uuid='00000000000000000000000000000001',
                  auth_access_token='0', clientid='0', auth_xuid='0', user_type='legacy', version_type='release')

    def expand(arg):
        return re.sub(r'\$\{([^}]+)\}', lambda m: values[m[1]], arg)

    def arguments(rows):
        result = []
        for row in rows:
            if isinstance(row, dict):
                if not allowed(row):
                    continue
                row = row['value']
            result.extend(expand(a) for a in (row if isinstance(row, list) else [row]))
        return result

    fixture = mode
    result_file = ROOT / ('generated/deck-editor-layout.txt' if mode == 'deck' else f'generated/{mode}-smoke-result.txt')
    if result_file.exists():
        shutil.copyfile(result_file, ROOT / f'generated/pre-integration-{mode}-result.txt')
        result_file.unlink()
    log = ROOT / f'generated/integrated-{mode}-client.log'
    max_heap_mib=int(os.environ.get('YUGIOH_INTEGRATION_HEAP_MIB','2048'))
    args = [str(JAVA), '-Xms128m', f'-Xmx{max_heap_mib}m', '-XX:ActiveProcessorCount=4', '-XX:TieredStopAtLevel=1',
            '-Dfile.encoding=UTF-8', '-Duser.language=zh', '-Duser.country=CN',
            f'-Dyugioh.mdpro.{fixture}Smoke=true',
            '-Dyugioh.mdpro.deckGuiScale=' + os.environ.get('YUGIOH_DECK_GUI_SCALE', '2'),
            '-javaagent:' + str(ROOT / 'generated/diagnostics/bound-hardware-probe.jar')]
    args += arguments(version['arguments']['jvm']) + [version['mainClass']]
    args += arguments(version['arguments']['game']) + (['--width', os.environ.get('YUGIOH_DECK_WIDTH','1276'), '--height', os.environ.get('YUGIOH_DECK_HEIGHT','728')] if mode=='deck' else ['--width', '1280', '--height', '900'])
    environment = os.environ.copy()
    environment.pop('JAVA_TOOL_OPTIONS', None)
    environment['TEMP'] = environment['TMP'] = str(ROOT / 'generated/tmp')
    print(f'Launching Forge 47.4.28 production {mode} fixture; command chars={len(subprocess.list2cmdline(args))}', flush=True)
    with log.open('w', encoding='utf-8') as out:
        process = subprocess.Popen(args, cwd=GAME, env=environment, stdout=out, stderr=subprocess.STDOUT)
        try:
            status = process.wait(timeout=300)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=30)
            raise RuntimeError(f'Production client timed out; see {log}')
    text = result_file.read_text('utf-8') if result_file.exists() else 'MISSING RESULT'
    assert status == 0 and text.startswith('PASS'), f'Client failed ({status}): {text}; see {log}'
    shutil.copyfile(result_file, ROOT / f'generated/integrated-{mode}-result.txt')
    # Forge must discover the three embedded mods through JarInJarDependencyLocator.
    logs = log.read_text('utf-8', errors='replace')
    debug_path = GAME / 'logs/debug.log'
    debug = debug_path.read_text('utf-8', errors='replace') if debug_path.exists() else ''
    assert 'InvalidMixinException' not in logs+debug, 'A required GUI mixin failed to load; inspect client debug log'
    discovery = [line for line in debug.splitlines() if 'JarInJarDependencyLocator' in line
                 or 'Found valid mod file' in line]
    assert any('JarInJarDependencyLocator' in line and 'Found 4 dependencies' in line for line in discovery)
    for row in CONFIG['nested']:
        name = f"{row['artifact']}-{row['version']}.jar"
        assert any(name in line for line in discovery), f'No embedded discovery evidence: {name}'
    assert '47.4.28' in logs
    with RELEASE.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    result = dict(passed=True, forge='47.4.28', java=17, max_heap_mib=max_heap_mib,
                  sha256=digest, installed_jars=sorted(expected), mode=mode, result=text, log=str(log),
                  embedded_mod_discovery=discovery)
    (ROOT / f'generated/integrated-{mode}-acceptance.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    if mode == 'ui':
        visual = json.loads((ROOT / 'generated/integrated-visual-acceptance.json').read_text('utf-8'))
        assert visual['passed'] and visual['sha256'] == digest
        result = dict(passed=True, sha256=digest, visual=visual, ui=result)
        (ROOT / 'generated/integrated-client-acceptance.json').write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['visual', 'ui', 'deck'])
    main(parser.parse_args().mode)
