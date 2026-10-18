"""Deploy validated 0.4 artifacts only to the user's explicitly named workspace profile."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile
from datetime import datetime

ROOT=Path(__file__).resolve().parents[2]
DEST=ROOT/'MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods'
BACKUP=ROOT/'generated/deployment-backup/0.3.0-alpha'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    log=(ROOT/'generated/features-final-validation.log').read_text(encoding='utf-8',errors='replace')
    assert 'All 4 required tests passed' in log and 'BUILD SUCCESSFUL' in log
    visual=(ROOT/'generated/visual-smoke-result.txt').read_text(encoding='utf-8')
    assert visual.startswith('PASS:') and 'restored catalog' in visual
    assert DEST.resolve().is_relative_to(ROOT.resolve()) and DEST.is_dir()
    names={
        'yugioh-mdpro-addon-0.4.0-alpha.jar': ROOT/'addon/build/libs/yugioh-mdpro-addon-0.4.0-alpha.jar',
        'yugioh-interaction-fix-0.103.2-mdpro.jar':ROOT/'generated/deps/yugioh-interaction-fix-0.103.2-mdpro.jar',
        'ygostar-1.0.0.jar':ROOT/'generated/deps/ygostar-1.0.0.jar',
        'geckolib-4.8.4.jar':ROOT/'generated/deps/geckolib-4.8.4.jar',
    }
    base=DEST/'yugioh-1.0.0-简中重译.jar'
    assert sha(base)==sha(ROOT/'inputs/mods/yugioh-1.0.0-简中重译.jar')
    old=DEST/'yugioh-mdpro-addon-0.3.0-alpha.jar'
    assert old.is_file() and sha(old)=='153a805161a53a52ef80aae20d02ea2251420aacd47ee634192c0b340c7ad4fb'
    assert not any((DEST/name).exists() for name in names)
    assert not any(p.name.startswith('yugioh-interaction-fix') for p in DEST.glob('*.jar'))
    for name,src in names.items():
        with zipfile.ZipFile(src) as jar:
            assert jar.testzip() is None, name
        pending=DEST/(name+'.pending')
        assert not pending.exists()
        shutil.copy2(src,pending)
        assert sha(pending)==sha(src)
    BACKUP.mkdir(parents=True,exist_ok=True)
    assert not (BACKUP/old.name).exists()
    moved=[]
    try:
        shutil.move(str(old),str(BACKUP/old.name))
        for name in names:
            (DEST/(name+'.pending')).rename(DEST/name)
            moved.append(name)
    except Exception:
        failed=ROOT/'generated/deployment-backup/failed-0.4.0'
        failed.mkdir(parents=True,exist_ok=True)
        for name in moved:
            shutil.move(str(DEST/name),str(failed/name))
        if not old.exists() and (BACKUP/old.name).exists():
            shutil.move(str(BACKUP/old.name),str(old))
        raise
    entries=[]
    for name,src in names.items():
        assert sha(DEST/name)==sha(src)
        entries.append({'name':name,'bytes':(DEST/name).stat().st_size,'sha256':sha(DEST/name)})
    report={'deployed_at':datetime.now().isoformat(),'target':str(DEST),'backup':str(BACKUP/old.name),
            'base_unchanged':True,'files':entries,'validation':'4 Forge GameTests; native/gameplay tests; restored UI fixtures; ZIP resource preservation audit',
            'target_game_acceptance':'User runs Forge 47.4.28; automated checks used isolated Forge 47.4.10.'}
    (ROOT/'generated/features-deployment.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
