"""Read frozen inputs; stage a reproducible native runtime only inside the workspace."""
import hashlib
import json
import shutil
import sqlite3
import struct
from pathlib import Path
from verify_mdpro_abi import ROOT, MDPRO, DLL, DB, ZIP, card_bytes


def main():
    output = ROOT / 'generated/native-runtime'
    output.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB.as_uri()+'?mode=ro', uri=True)
    con.row_factory = sqlite3.Row
    rows = con.execute('SELECT * FROM datas ORDER BY id').fetchall()
    with (output/'cards.bin').open('wb') as stream:
        stream.write(struct.pack('>II', 0x4D445031, len(rows)))
        for row in rows:
            stream.write(struct.pack('>I', row['id']))
            stream.write(card_bytes(row))
    con.close()
    for source, name in ((DLL, 'ocgcore.dll'), (ZIP, 'script.zip')):
        shutil.copy2(source, output/name)
    records = []
    for path in sorted(output.iterdir()):
        if path.name == 'manifest.json': continue
        records.append({'path':path.name,'bytes':path.stat().st_size,
                        'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
    (output/'manifest.json').write_text(json.dumps({'files':records}, indent=2), encoding='utf-8')
    print(output)


if __name__ == '__main__': main()
