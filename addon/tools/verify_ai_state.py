"""Compile and replay captured production AI failures against the packaged managed host."""
from pathlib import Path
import os, shutil, subprocess, sys

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'generated/ai-feed-repair/smoke-build'
BUILD.mkdir(parents=True, exist_ok=True)
HOME = ROOT / 'generated/mdpromod-engine/duelhost'
DOTNET = ROOT / 'generated/dotnet-sdk/dotnet.exe'
refs = [p for p in (HOME/'shared/Microsoft.NETCore.App/8.0.31').glob('*.dll')
        if (p.name.startswith('System.') and '.Native.' not in p.name)
        or p.name in ('mscorlib.dll','netstandard.dll','Microsoft.CSharp.dll')]
args = ['/nologo','/target:exe','/nullable:enable','/out:'+str(BUILD/'ai-smoke.dll')]
args += ['/reference:'+str(p) for p in refs]
args += [str(ROOT/'addon/duel-host-adapter/AiStateSmoke.cs')]
rsp = BUILD/'ai-smoke.rsp'
rsp.write_text('\n'.join('"'+arg+'"' for arg in args),'utf-8')
env = dict(os.environ, DOTNET_CLI_HOME=str(BUILD), TEMP=str(BUILD), TMP=str(BUILD))
subprocess.run([str(DOTNET),str(ROOT/'generated/tools/microsoft.net.compilers.toolset/tasks/netcore/bincore/csc.dll'),'@'+str(rsp)],check=True,env=env)
shutil.copyfile(HOME/'duel_host.runtimeconfig.json',BUILD/'ai-smoke.runtimeconfig.json')
for session in ('085461b1-34a0-48d1-b323-04005ca99c71','2b2fa56f-f7f9-4847-94d4-30dc2417eefc'):
    for baseline in (True,False):
        command = [str(DOTNET),str(BUILD/'ai-smoke.dll'),str(HOME),str(ROOT/'generated/ai-feed-repair'/session),str(ROOT/'generated/mdpromod-engine/runtime')]
        if baseline: command.append('--baseline')
        result = subprocess.run(command,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8')
        label = session + ('-baseline' if baseline else '-fixed')
        (BUILD/(label+'.log')).write_text(result.stdout,'utf-8')
        if baseline:
            assert result.returncode != 0 and '3235D09003004004080004000400080000' in result.stdout and 'NullReferenceException' in result.stdout,result.stdout
            print('PASS reproduced production null reference with old pile sizes:',session)
        else:
            assert result.returncode == 0,result.stdout
            print(result.stdout.splitlines()[-1])
