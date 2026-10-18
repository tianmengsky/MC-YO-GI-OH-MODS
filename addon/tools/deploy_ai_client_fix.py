"""Replace only the explicitly authorized Tianmeng distribution's integrated mod."""
from pathlib import Path
import hashlib,json,shutil
ROOT=Path(__file__).resolve().parents[2]
PACK=Path('F:/Minecarft/MC游戏王Tianmeng版')
GAME=PACK/'.minecraft/versions/1.20.1-游戏王整合包Tianmeng版'
BACKUP=ROOT/'generated/deployment-backup/tianmeng-ai-state-0.6.33'
NEW=ROOT/'addon/build/libs/yugioh-mdpro-integrated-0.6.33-alpha.jar'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    expected='6662d14a52316b1d6f59235937fd40f804e40ffbe00ce23aad0437d9b97440ee'
    assert sha(NEW)==expected
    old=GAME/'mods/yugioh-mdpro-integrated-0.6.32-alpha.jar'
    target=GAME/'mods'/NEW.name
    assert sha(old)=='411f5dd998980a31240d22b5dd3580dc42277060bbd8c0b95a9bc9b75a550658'
    assert not target.exists()
    BACKUP.mkdir(parents=True,exist_ok=False)
    shutil.copy2(old,BACKUP/old.name)
    assert sha(BACKUP/old.name)==sha(old)
    staged=target.with_suffix('.jar.partial')
    shutil.copyfile(NEW,staged)
    assert sha(staged)==expected
    try:
        old.unlink()
        staged.replace(target)
    except Exception:
        if not old.exists():shutil.copy2(BACKUP/old.name,old)
        raise
    note=PACK/'发行修复说明.txt'
    if note.exists():shutil.copy2(note,BACKUP/note.name)
    with note.open('a',encoding='utf-8') as f:
        f.write('\n2026-10-18 / 0.6.33：修复 AI 将自身卡组张数套用双方，导致对手从额外特殊召唤时对局取消；AI 初始化与内核统一大师规则 3。保留 0.6.32 的 Java 17 与启动内存修复。正在运行的客户端需下次启动才加载新模组。\n')
    result={'target':str(target),'sha256':sha(target),'backup':str(BACKUP),'runtime_restart_required_for_client':True,'copied_whole_pack':False}
    (ROOT/'generated/ai-feed-repair/client-deployment.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
