"""Remove only the obsolete monster mesh resources from the editable addon.

Frozen inputs and previous releases remain recovery evidence. The active release
must contain no assets/yugioh_interaction_fix/3d/ entries.
"""
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'addon/src/main/resources'
TARGET = RES / 'assets/yugioh_interaction_fix/3d'


def main():
    assert TARGET.resolve() == (ROOT / 'addon/src/main/resources/assets/yugioh_interaction_fix/3d').resolve()
    assert TARGET.resolve().is_relative_to(RES.resolve()) and TARGET.resolve().is_relative_to(ROOT.resolve())
    if not TARGET.exists():
        print('Monster mesh resource directory already removed.')
        return
    files = [p for p in TARGET.rglob('*') if p.is_file()]
    # Other resources must not depend on the directory being removed.
    for p in RES.rglob('*.json'):
        if p.is_relative_to(TARGET):
            continue
        assert 'yugioh_interaction_fix:3d/' not in p.read_text('utf-8'), p
    report = dict(files=len(files), expanded_bytes=sum(p.stat().st_size for p in files),
                  removed=[dict(path=p.relative_to(RES).as_posix(), bytes=p.stat().st_size,
                                sha256=hashlib.file_digest(p.open('rb'), 'sha256').hexdigest())
                           for p in sorted(files)],
                  preserved=['dueldisk/', 'puppet/', 'ygostar/geo/', 'textures/cardart/', 'textures/closeup/'])
    (ROOT / 'generated/removed-monster-3d-0.6.12.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    shutil.rmtree(TARGET)
    print(f"Removed {len(files)} monster mesh resource files ({report['expanded_bytes']} bytes).")


if __name__ == '__main__':
    main()
