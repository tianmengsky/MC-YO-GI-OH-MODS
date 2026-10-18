"""Copy unchanged raw ZIP entries from a pinned old release; transfer only changed bytes."""
import hashlib,json,struct,sys,zipfile
from pathlib import Path

def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    old,new,out=map(Path,sys.argv[1:])
    def records(path):
        with zipfile.ZipFile(path) as z:
            infos=sorted(z.infolist(),key=lambda i:i.header_offset)
            return [(i.filename,i.header_offset,(infos[k+1].header_offset if k+1<len(infos) else z.start_dir)-i.header_offset) for k,i in enumerate(infos)],z.start_dir
    previous,_=records(old); current,footer=records(new)
    mapping={n:(o,s) for n,o,s in previous}
    copied=literal=0
    with old.open('rb') as before,new.open('rb') as after,out.open('xb') as patch:
        patch.write(struct.pack('>I',0x4D445044))
        for digest in (sha(old),sha(new)):patch.write(struct.pack('>H',len(digest))+digest.encode())
        def raw(data):
            nonlocal literal
            patch.write(b'\x02'+struct.pack('>q',len(data))+data);literal+=len(data)
        for name,offset,size in current:
            after.seek(offset);data=after.read(size)
            source=mapping.get(name)
            if source and source[1]==size:
                before.seek(source[0])
                if before.read(size)==data:
                    patch.write(b'\x01'+struct.pack('>qq',source[0],size));copied+=size;continue
            raw(data)
        after.seek(footer);raw(after.read());patch.write(b'\0')
    result={'source':str(old),'target':str(new),'source_sha256':sha(old),'target_sha256':sha(new),'copied_bytes':copied,'transferred_bytes':literal,'delta_bytes':out.stat().st_size}
    out.with_suffix('.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),'utf-8');print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
