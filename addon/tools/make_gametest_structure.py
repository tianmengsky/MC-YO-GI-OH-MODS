import struct,gzip,pathlib
# Minimal uncompressed NBT compound: DataVersion, size[1,1,1], empty palette/blocks/entities.
def name(s): b=s.encode(); return struct.pack('>H',len(b))+b
b=b'\x0a\0\0'+b'\x03'+name('DataVersion')+struct.pack('>i',3465)
b+=b'\x09'+name('size')+b'\x03'+struct.pack('>i3i',3,1,1,1)
b+=b'\x09'+name('palette')+b'\x0a'+struct.pack('>i',1)+b'\x08'+name('Name')+name('minecraft:air')+b'\0'
for key in ('blocks','entities'): b+=b'\x09'+name(key)+b'\x0a'+struct.pack('>i',0)
b+=b'\0'; pathlib.Path('addon/src/main/resources/data/yugioh_mdpro_addon/structures/empty.nbt').write_bytes(gzip.compress(b,mtime=0))
