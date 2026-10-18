"""Downsample oversized legacy item sprites through an additive resource override."""
import hashlib
import io
import json
import zipfile
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'inputs/mods/ygostar-1.0.0-forge-1.20.1 (3).jar'
OUTPUT = ROOT / 'generated/content/resources'
MAX_EDGE = 256
rows = []
with zipfile.ZipFile(SOURCE) as jar:
    for name in sorted(jar.namelist()):
        if not name.startswith('assets/ygostar/textures/item/') or not name.endswith('.png'):
            continue
        raw = jar.read(name)
        with Image.open(io.BytesIO(raw)) as image:
            if max(image.size) <= MAX_EDGE:
                continue
            old_size = image.size
            reduced = image.convert('RGBA')
            reduced.thumbnail((MAX_EDGE, MAX_EDGE), Image.Resampling.LANCZOS)
            target = OUTPUT / name
            target.parent.mkdir(parents=True, exist_ok=True)
            reduced.save(target, optimize=True)
            rows.append(dict(path=name, source_size=old_size, size=reduced.size,
                             source_sha256=hashlib.sha256(raw).hexdigest(),
                             source_rgba_bytes=old_size[0]*old_size[1]*4,
                             rgba_bytes=reduced.width*reduced.height*4))
assert len(rows) == 88
report = dict(source=str(SOURCE.relative_to(ROOT)), max_edge=MAX_EDGE, sprites=len(rows),
              rgba_bytes_before=sum(r['source_rgba_bytes'] for r in rows),
              rgba_bytes_after=sum(r['rgba_bytes'] for r in rows), resources=rows)
(ROOT/'generated/content/item-memory-optimization.json').write_text(
    json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print({k:v for k,v in report.items() if k!='resources'})
