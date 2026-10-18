"""Assemble the Linux (Termux/arm64) reference host zip from the ported artifacts.

The Windows package lives in generated/reference-host-resources and ships the win-x64
binaries plus the full .NET runtime. The Linux package reuses only the shared data files
(cards database, scripts, declaration index) and adds the arm64 core, a proot launcher and
the AnyCPU-patched host assemblies.
"""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'generated/reference-host-resources/data/yugioh_mdpro_addon'
WIN_ZIP = RES / 'reference-host.zip'
OUT_ZIP = RES / 'reference-host-linux.zip'
BUILD = ROOT / 'generated/ocgcore-build'
DIST = BUILD / 'dist'

SHARED_FROM_WINDOWS = [
    'declaration-cards.bin',
    'runtime/cards.cdb',
    'runtime/cards.json',
    'runtime/lflist.conf',
]
SHARED_PREFIXES = ['runtime/Data/']

LAUNCHER = '''#!/bin/sh
# MDPro reference host launcher for Linux/arm64 (Termux + proot Ubuntu + .NET 8).
# Override the container / runtime with MDPRO_LINUX_ROOT and MDPRO_DOTNET.
unset LD_PRELOAD 2>/dev/null || true
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
lib="$root/runtime"
termux_home=${HOME:-/data/data/com.termux/files/home}
guest=${MDPRO_LINUX_ROOT:-$termux_home/ubuntu}
dotnet=${MDPRO_DOTNET:-/opt/dotnet/dotnet}
if ! command -v proot >/dev/null 2>&1; then
  echo '[MDPro] Missing proot: install the Termux proot package.' >&2
  exit 1
fi
if [ ! -x "$guest$dotnet" ]; then
  echo "[MDPro] Missing Linux ARM64 .NET 8 runtime: $guest$dotnet" >&2
  exit 1
fi
exec proot -0 -r "$guest" -b "$termux_home" -b /dev -b /proc -b /sys -w "$lib" \\
  /usr/bin/env -i DOTNET_ROOT=/opt/dotnet PATH=/opt/dotnet:/usr/bin:/bin \\
  LD_LIBRARY_PATH="$lib" DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1 \\
  DOTNET_gcServer=0 DOTNET_GCHeapHardLimit=0xC000000 \\
  "$dotnet" "$here/adapter.dll" "$@"
'''


def main():
    files = {}

    with zipfile.ZipFile(WIN_ZIP) as win:
        for name in win.namelist():
            if name in SHARED_FROM_WINDOWS or any(name.startswith(p) for p in SHARED_PREFIXES):
                if not name.endswith('/'):
                    files[name] = win.read(name)
        # Rebuild from the reviewed Windows package, never an ad-hoc patched phone DLL.
        build = ROOT / 'generated/linux-host-build'
        build.mkdir(exist_ok=True)
        original = build / 'windows-host.dll'
        original.write_bytes(win.read('duelhost/duel_host.dll'))
        deps = json.loads(win.read('duelhost/duel_host.deps.json').decode('utf-8-sig'))

    dotnet = ROOT / 'generated/dotnet-sdk/dotnet.exe'
    compiler = ROOT / 'generated/tools/microsoft.net.compilers.toolset/tasks/netcore/bincore/csc.dll'
    runtime = ROOT / 'generated/mdpromod-engine/duelhost/shared/Microsoft.NETCore.App/8.0.31'
    refs = [p for p in runtime.glob('*.dll') if (p.name.startswith('System.') and '.Native.' not in p.name)
            or p.name in ('mscorlib.dll', 'netstandard.dll', 'Microsoft.CSharp.dll')]
    cecil = ROOT / 'generated/tools/mono.cecil/lib/netstandard2.0/Mono.Cecil.dll'
    env = dict(os.environ, DOTNET_ROOT=str(dotnet.parent), DOTNET_CLI_HOME=str(build), TEMP=str(build), TMP=str(build))
    def compile(name, source, extra=()):
        rsp = build / (name + '.rsp')
        args = ['/nologo', '/target:exe', '/deterministic+', '/nullable:enable', '/out:' + str(build / (name + '.dll'))]
        args += ['/reference:' + str(p) for p in refs] + list(extra) + [str(ROOT / 'addon/duel-host-adapter' / source)]
        rsp.write_text('\n'.join('"' + a + '"' for a in args), encoding='utf-8')
        subprocess.run([str(dotnet), str(compiler), '@' + str(rsp)], check=True, cwd=ROOT, env=env)
    compile('adapter', 'Adapter.cs')
    compile('patch-linux', 'PatchLinuxHost.cs', ['/reference:' + str(cecil)])
    src = ROOT / 'generated/mdpromod-engine/duelhost'
    shutil.copyfile(src / 'duel_host.runtimeconfig.json', build / 'patch-linux.runtimeconfig.json')
    shutil.copyfile(cecil, build / 'Mono.Cecil.dll')
    subprocess.run([str(dotnet), str(build / 'patch-linux.dll'), str(original), str(build / 'adapter.dll'),
                    str(build / 'duel_host.dll')], check=True, cwd=ROOT, env=env)
    compile('protocol-smoke', 'LinuxHostProtocolSmoke.cs')
    shutil.copyfile(src / 'duel_host.runtimeconfig.json', build / 'protocol-smoke.runtimeconfig.json')
    subprocess.run([str(dotnet), str(build / 'protocol-smoke.dll'), str(build / 'duel_host.dll')],
                   check=True, cwd=ROOT, env=env)
    target = deps['targets'][deps['runtimeTarget']['name']]
    target['adapter/1.0.0'] = {'runtime': {'adapter.dll': {}}}
    deps['libraries']['adapter/1.0.0'] = {'type': 'project', 'serviceable': False, 'sha512': ''}

    files['duelhost/adapter'] = LAUNCHER.encode('utf-8')
    files['duelhost/adapter.dll'] = build.joinpath('adapter.dll').read_bytes()
    files['duelhost/duel_host.dll'] = build.joinpath('duel_host.dll').read_bytes()
    files['duelhost/duel_host.deps.json'] = json.dumps(deps, indent=2).encode('utf-8')
    files['duelhost/duel_host.runtimeconfig.json'] = src.joinpath('duel_host.runtimeconfig.json').read_bytes()
    files['duelhost/adapter.runtimeconfig.json'] = src.joinpath('adapter.runtimeconfig.json').read_bytes()
    files['runtime/libocgcore.so'] = BUILD.joinpath('out/ocgcore.dll').read_bytes()
    files['runtime/libsqlite3.so'] = DIST.joinpath('sqlite/usr/lib/aarch64-linux-gnu/libsqlite3.so.0.8.6').read_bytes()

    core = files['runtime/libocgcore.so']
    assert core[:4] == b'\x7fELF' and core[4:6] == b'\x02\x01' and int.from_bytes(core[18:20], 'little') == 183, 'Expected ELF64 little-endian AArch64 core'
    manifest = {'platform': 'linux-arm64-termux-proot', 'adapter': 'mr3-ai-state-linux-v3',
                'windowsPackageSha256': hashlib.sha256(WIN_ZIP.read_bytes()).hexdigest(), 'files': [
        {'path': name, 'bytes': len(blob), 'sha256': hashlib.sha256(blob).hexdigest()}
        for name, blob in sorted(files.items())
    ]}
    files['manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8')

    OUT_ZIP.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT_ZIP, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, blob in sorted(files.items()):
            info = zipfile.ZipInfo(name)
            info.external_attr = (0o755 if name.endswith('/adapter') else 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, blob)
    print('wrote', OUT_ZIP, OUT_ZIP.stat().st_size // (1024 * 1024), 'MB', len(files), 'entries')
    (ROOT / 'generated/termux-repair/linux-package-audit.json').write_text(json.dumps({
        'sha256': hashlib.sha256(OUT_ZIP.read_bytes()).hexdigest(), 'manifest': manifest,
        'windowsHostSha256': hashlib.sha256(original.read_bytes()).hexdigest(),
        'linuxHostSha256': hashlib.sha256(files['duelhost/duel_host.dll']).hexdigest(),
    }, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
