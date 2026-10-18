"""Reproducible Forge Jar-in-Jar release; original archives and identities stay intact."""
import argparse
import hashlib
import json
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = json.loads((ROOT / 'addon/content/integrated-release.json').read_text('utf-8'))
OUTPUT = ROOT / CONFIG['output']
REPORT = ROOT / 'generated/integrated-release-audit.json'
TARGET_DIR = ROOT / 'MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods'
METADATA = 'META-INF/jarjar/metadata.json'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def stream_sha(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def nested_path(row):
    return f"META-INF/jarjar/{row['artifact']}-{row['version']}.jar"


def metadata():
    return {'jars': [dict(identifier=dict(group='dev.codex.yugioh.bundle', artifact=r['artifact']),
                          version=dict(range=f"[{r['version']}]", artifactVersion=r['version']),
                          path=nested_path(r), isObfuscated=True) for r in CONFIG['nested']]}


def build():
    for row in [CONFIG['outer']] + CONFIG['nested']:
        assert sha(ROOT / row['source']) == row['sha256'], f"Unreviewed input: {row['source']}"
    with zipfile.ZipFile(ROOT / CONFIG['outer']['source']) as z:
        assert METADATA not in z.namelist(), 'Review pre-existing nested dependencies first'
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    staged = OUTPUT.with_suffix('.jar.partial')
    # Preserve the original compressed entries rather than decoding all the artwork.
    shutil.copyfile(ROOT / CONFIG['outer']['source'], staged)
    with zipfile.ZipFile(staged, 'a') as z:
        info = zipfile.ZipInfo(METADATA, (1980, 1, 1, 0, 0, 0))
        z.writestr(info, json.dumps(metadata(), ensure_ascii=False, indent=2).encode('utf-8'))
        for row in CONFIG['nested']:
            info = zipfile.ZipInfo(nested_path(row), (1980, 1, 1, 0, 0, 0))
            # Nested JARs are already compressed. Store and copy them in bounded buffers.
            info.file_size = (ROOT / row['source']).stat().st_size
            with (ROOT / row['source']).open('rb') as src, z.open(info, 'w') as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
    staged.replace(OUTPUT)
    return verify()


def verify():
    with zipfile.ZipFile(OUTPUT) as new, zipfile.ZipFile(ROOT / CONFIG['outer']['source']) as old:
        assert new.testzip() is None
        assert len(new.namelist()) == len(set(new.namelist())), 'Duplicate archive entries'
        assert not any(n.startswith('assets/yugioh_interaction_fix/3d/') for n in new.namelist()), 'Obsolete monster mesh resources returned'
        assert not any(n.startswith('dev/codex/yugiohmdpro/visual/client/Hologram3D')
                       or n.startswith('dev/codex/yugiohmdpro/visual/client/HologramAnim')
                       or n.startswith('dev/codex/yugiohmdpro/visual/client/HologramGeometry')
                       for n in new.namelist()), 'Obsolete addon monster mesh loader returned'
        assert json.loads(new.read(METADATA)) == metadata()
        additions = {METADATA} | {nested_path(r) for r in CONFIG['nested']}
        assert set(new.namelist()) == set(old.namelist()) | additions
        # Compare every original class/resource/config, not only selected GUI classes.
        for name in old.namelist():
            with old.open(name) as a, new.open(name) as b:
                assert stream_sha(a) == stream_sha(b), f'Outer payload changed: {name}'
        for row in CONFIG['nested']:
            with new.open(nested_path(row)) as stream:
                assert stream_sha(stream) == row['sha256'], f"Nested payload changed: {row['artifact']}"
        result = dict(version=CONFIG['version'], path=str(OUTPUT), bytes=OUTPUT.stat().st_size,
                      sha256=sha(OUTPUT), format='Forge Jar-in-Jar', original_outer_entries=len(old.namelist()),
                      all_original_entries_identical=True, nested_inputs=CONFIG['nested'],
                      mod_ids=['yugioh', 'ygostar', 'yugioh_interaction_fix', 'yugioh_mdpro_addon'],
                      external_dependencies=['geckolib'] + ([] if
                          'data/yugioh_mdpro_addon/reference-host.zip' in new.namelist()
                          else ['mdpro-runtime/']))
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


def deploy():
    result = verify()
    acceptance = json.loads((ROOT / 'generated/integrated-client-acceptance.json').read_text('utf-8'))
    assert acceptance['sha256'] == result['sha256'] and acceptance['passed'], 'Test this exact release first'
    assert (ROOT / 'generated/integrated-native-smoke.log').read_text('utf-8-sig').startswith('PASS:')
    if CONFIG.get('network_protocol') == 6:
        assert (ROOT/'generated/public-projection-smoke-result.txt').read_text('utf-8').startswith('PASS:')
        assert 'All 12 required tests passed' in (ROOT/'generated/public-projection-gametests.log').read_text('utf-8',errors='replace')
        assert json.loads((ROOT/'generated/public-projection-content-audit.json').read_text('utf-8'))['outer_sha256']==CONFIG['outer']['sha256']
    assert TARGET_DIR.resolve().is_relative_to(ROOT.resolve())
    # Only replace reviewed archives; unrelated mods and runtime/config/saves remain in place.
    rows = [CONFIG['previous_integrated']] if 'previous_integrated' in CONFIG else [CONFIG['outer']] + CONFIG['nested']
    previous = [TARGET_DIR / r['installed_name'] for r in rows]
    for p, row in zip(previous, rows):
        assert p.is_file() and sha(p) == row['sha256'], f'Installed file changed: {p}'
    target = TARGET_DIR / OUTPUT.name
    assert not target.exists(), 'An integrated release is already installed; inspect before updating'
    if 'previous_integrated' in CONFIG:
        assert not any((TARGET_DIR/r['installed_name']).exists() for r in [CONFIG['outer']]+CONFIG['nested']), 'Standalone duplicate mods detected'
    for name, digest in CONFIG['external'].items():
        assert sha(TARGET_DIR / name) == digest
    backups = ROOT / ('generated/deployment-backup/integration-'+CONFIG['version'])
    backups.mkdir(parents=True, exist_ok=True)
    previous_report = ROOT / 'generated/content-deployment.json'
    if previous_report.exists():
        shutil.copyfile(previous_report, backups / 'content-deployment-before-integration.json')
    for p, row in zip(previous, rows):
        backup = backups / p.name
        if backup.exists():
            assert sha(backup) == row['sha256']
        else:
            shutil.copyfile(p, backup)
        assert sha(backup) == row['sha256']
    staged = TARGET_DIR / (OUTPUT.name + '.partial')
    shutil.copyfile(OUTPUT, staged)
    assert sha(staged) == result['sha256']
    try:
        for p in previous:
            p.unlink()
        staged.replace(target)
        assert sha(target) == result['sha256']
    except BaseException:
        if target.exists():
            target.unlink()
        for p in previous:
            if not p.exists():
                shutil.copyfile(backups / p.name, p)
        raise
    result.update(path=str(target), backup=str(backups),
                  installed_jars=sorted(p.name for p in TARGET_DIR.glob('*.jar')), client_validation=acceptance)
    (ROOT / 'generated/integrated-deployment.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    if previous_report.exists():
        content = json.loads(previous_report.read_text('utf-8'))
        content.update(version=CONFIG['version'], path=str(target), sha256=result['sha256'], bytes=result['bytes'],
                       installed_jars=result['installed_jars'], packaging='Forge Jar-in-Jar',
                       network_protocol=CONFIG.get('network_protocol',content.get('network_protocol')),
                       integration_report='generated/integrated-deployment.json')
        previous_report.write_text(json.dumps(content, ensure_ascii=False, indent=2), encoding='utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'verify', 'deploy'])
    args = parser.parse_args()
    print(json.dumps(globals()[args.action](), ensure_ascii=False, indent=2))
