"""Reproduce the matched example engine and checked GUI/MR3/AI adapter patches."""
from pathlib import Path
import hashlib, json, shutil, subprocess, zipfile, sqlite3, struct

ROOT = Path(__file__).resolve().parents[2]
ENGINE = ROOT / 'generated/mdpromod-engine'
TOOLS = ROOT / 'generated/tools'
SOURCE_SHA = '093b13079e4b48dd21b4f91a8c0fb832479dc4a37996c641b3d94440ab7cb59c'

def main():
    home = ENGINE / 'duelhost'
    original = ROOT / 'generated/mdpromod-host-original.dll'
    if not original.exists(): shutil.copyfile(home / 'duel_host.dll', original)
    assert hashlib.sha256(original.read_bytes()).hexdigest() == SOURCE_SHA
    compiler = TOOLS / 'microsoft.net.compilers.toolset/tasks/netcore/bincore/csc.dll'
    dotnet = home / 'dotnet.exe'  # build tool only; not distributed
    runtime = home / 'shared/Microsoft.NETCore.App/8.0.31'
    refs = [p for p in runtime.glob('*.dll') if (p.name.startswith('System.') and '.Native.' not in p.name) or p.name in
            ('mscorlib.dll', 'netstandard.dll', 'Microsoft.CSharp.dll')]
    build = ROOT / 'generated/reference-host-build'; build.mkdir(exist_ok=True)
    def compile(name, source, extra=()):
        rsp = build / (name + '.rsp')
        args = ['/nologo', '/target:exe', '/deterministic+', '/nullable:enable',
                '/out:' + str(build / (name + '.dll'))]
        args += ['/reference:' + str(p) for p in refs] + list(extra) + [str(source)]
        rsp.write_text('\n'.join('"' + a + '"' for a in args), encoding='utf-8')
        subprocess.run([str(dotnet), str(compiler), '@' + str(rsp)], check=True, cwd=ROOT)
    src = ROOT / 'addon/duel-host-adapter'
    compile('adapter', src / 'Adapter.cs')
    cecil = TOOLS / 'mono.cecil/lib/netstandard2.0/Mono.Cecil.dll'
    compile('patch', src / 'PatchHost.cs', ['/reference:' + str(cecil)])
    config = home / 'duel_host.runtimeconfig.json'
    shutil.copyfile(config, build / 'patch.runtimeconfig.json')
    shutil.copyfile(cecil, build / 'Mono.Cecil.dll')
    import os
    env = dict(os.environ, DOTNET_ROOT=str(home), DOTNET_CLI_HOME=str(build), TEMP=str(build), TMP=str(build))
    subprocess.run([str(dotnet), str(build / 'patch.dll'), str(original),
                    str(build / 'adapter.dll'), str(home / 'duel_host.dll')], check=True, env=env)
    shutil.copyfile(build / 'adapter.dll', home / 'adapter.dll')
    shutil.copyfile(config, home / 'adapter.runtimeconfig.json')
    apphost = (home / 'duel_host.exe').read_bytes()
    old = b'duel_host.dll\0'
    assert apphost.count(old) == 1
    (home / 'adapter.exe').write_bytes(apphost.replace(old, b'adapter.dll\0\0\0'))
    from verify_mdpro_abi import card_bytes
    with sqlite3.connect((ENGINE / 'runtime/Data/locales/zh-CN/cards.cdb').as_uri() + '?mode=ro', uri=True) as con:
        con.row_factory = sqlite3.Row
        rows = con.execute('SELECT * FROM datas ORDER BY id').fetchall()
        with (ENGINE / 'declaration-cards.bin').open('wb') as stream:
            stream.write(struct.pack('>II', 0x4D445031, len(rows)))
            for row in rows: stream.write(struct.pack('>I', row['id']) + card_bytes(row))
    # Do not package artwork, the unused network server, or Windbot's separate runtime.
    selected = []
    for p in ENGINE.rglob('*'):
        if not p.is_file(): continue
        rel = p.relative_to(ENGINE).as_posix()
        if rel.startswith('runtime/Data/Windbot/Decks/') or rel in ('duelhost/dotnet.exe', 'runtime/ygoserver.dll', 'provenance.json'): continue
        selected.append((rel, p))
    files = [{'path': rel, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for rel, p in sorted(selected)]
    manifest = json.dumps({'source': 'mdpromod-0.1.0.jar', 'hostOriginalSha256': SOURCE_SHA,
                           'adapter': 'mr3-ai-state-v3', 'files': files}, indent=2).encode()
    out = ROOT / 'generated/reference-host-resources/data/yugioh_mdpro_addon'; out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out / 'reference-host.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        for rel, p in sorted(selected): z.write(p, rel)
        z.writestr('manifest.json', manifest)
    (ROOT / 'generated/reference-host-manifest.json').write_bytes(manifest)
    print('Reference host staged:', len(files), 'files;', (out / 'reference-host.zip').stat().st_size, 'compressed bytes')

if __name__ == '__main__': main()
