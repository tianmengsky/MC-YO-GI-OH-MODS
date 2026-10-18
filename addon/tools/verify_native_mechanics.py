"""Deterministic Lua/engine integration puzzles, all run in an isolated process."""
import ctypes as C
import itertools
import json
import sqlite3
import struct
import subprocess
import sys
import zipfile
from verify_mdpro_abi import DLL, DB, ZIP, OUT, ROOT, SR, CR, MH, bind, card_bytes

class Harness:
    def __init__(self):
        con=sqlite3.connect(DB.as_uri()+'?mode=ro',uri=True); con.row_factory=sqlite3.Row
        self.cards={r['id']:r for r in con.execute('select * from datas')}; con.close()
        self.zip=zipfile.ZipFile(ZIP); self.keep={}; self.errors=[]; self.trace=[]
        self.api=C.CDLL(str(DLL)); self.buf=C.create_string_buffer(0x40000)
        self.create=bind(self.api,'create_duel',C.c_void_p,C.c_uint32)
        self.end=bind(self.api,'end_duel',None,C.c_void_p)
        self.player=bind(self.api,'set_player_info',None,C.c_void_p,*([C.c_int32]*4))
        self.new=bind(self.api,'new_card',None,C.c_void_p,C.c_uint32,*([C.c_uint8]*5))
        self.start=bind(self.api,'start_duel',None,C.c_void_p,C.c_uint32)
        self.process=bind(self.api,'process',C.c_uint32,C.c_void_p)
        self.get=bind(self.api,'get_message',C.c_int32,C.c_void_p,C.c_void_p)
        self.ri=bind(self.api,'set_responsei',None,C.c_void_p,C.c_int32)
        self.rb=bind(self.api,'set_responseb',None,C.c_void_p,C.c_void_p)
        self.query=bind(self.api,'query_field_card',C.c_int32,C.c_void_p,C.c_uint8,C.c_uint8,C.c_uint32,C.c_void_p,C.c_int32)
        self.log=bind(self.api,'get_log_message',None,C.c_void_p,C.c_void_p)
        self.callbacks=(SR(self.script),CR(self.card),MH(self.message))
        for n,cb,t in zip(('set_script_reader','set_card_reader','set_message_handler'),self.callbacks,(SR,CR,MH)): bind(self.api,n,None,t)(cb)
    def script(self,name,size):
        key=name.decode().lstrip('./')
        if '/' not in key: key='script/'+key
        try: b=self.zip.read(key)
        except KeyError: size[0]=0; self.errors.append('missing:'+key); return None
        if key not in self.keep: self.keep[key]=C.create_string_buffer(b)
        size[0]=len(b); return C.addressof(self.keep[key])
    def card(self,code,target):
        if code not in self.cards: self.errors.append('card:'+str(code)); return 0
        b=card_bytes(self.cards[code]); C.memmove(target,b,len(b)); return 0
    def message(self,duel,kind):
        b=C.create_string_buffer(0x40000); self.log(duel,b); self.errors.append(b.value.decode('utf-8','replace')); return 0
    def send(self,d,v):
        if isinstance(v,int): self.ri(d,v)
        else: self.rb(d,C.create_string_buffer(bytes(v).ljust(64,b'\0')))
    def field(self,d):
        n=self.query(d,0,4,1,self.buf,0); data=self.buf.raw[:n]; codes=[]; off=0
        while off+4<=len(data):
            size=struct.unpack_from('<I',data,off)[0]
            if size<=0 or off+size>len(data): raise ValueError('query format')
            if size>=12: codes.append(struct.unpack_from('<I',data,off+8)[0])
            off+=size
        return codes
    def choose(self,data,scenario,used):
        kind=data[0]
        if kind==11:
            off=2; groups=[]
            for g in range(6):
                count=data[off]; off+=1; width=11 if g==5 else 7
                groups.append([struct.unpack_from('<I',data,off+i*width)[0]&0x7fffffff for i in range(count)]); off+=count*width
            if scenario.get('activate'):
                for i,code in enumerate(groups[5]):
                    if code in (scenario['activate'] if isinstance(scenario['activate'],list) else [scenario['activate']]) and ('activate:'+str(code)) not in used:
                        used.add('activate:'+str(code)); return (i<<16)|5
            if scenario.get('special'):
                for i,code in enumerate(groups[1]):
                    if (code==scenario['special'] or scenario['special']==-1) and 'special' not in used:
                        used.add('special'); return (i<<16)|1
            if scenario.get('normal') and 'normal' not in used:
                for i,code in enumerate(groups[0]):
                    if code==scenario['normal']: used.add('normal'); return i<<16
            return 7
        # SHA-pinned DLL: no spe_count; forced is byte 3, not timing's first byte.
        if kind==16: return 0 if data[3] else -1
        if kind in (12,13): return 1
        if kind==14: return 0
        if kind in (18,24):
            forbidden=struct.unpack_from('<I',data,3)[0]; reply=[]
            for bit in range(32):
                if not forbidden&(1<<bit):
                    relative=bit//16; loc=4 if bit%16<8 else 8; seq=bit%8
                    reply += [data[1]^relative,loc,seq]
                    if len(reply)//3==max(1,data[2]): return reply
            raise AssertionError('no available zone')
        if kind==19:
            positions=data[6]; return next(p for p in (1,4,8,2) if positions&p)
        if kind in (15,20): return [data[3]]+list(range(data[3]))
        if kind==26:
            if data[2]: return -1
            return [1,0]
        if kind==23:
            mode=data[1]; target=struct.unpack_from('<I',data,3)[0]; minimum=data[7]; maximum=data[8]; mandatory=data[9]; off=10
            must=[struct.unpack_from('<I',data,off+i*11+7)[0] for i in range(mandatory)]; off+=mandatory*11
            count=data[off]; off+=1; weights=[struct.unpack_from('<I',data,off+i*11+7)[0] for i in range(count)]
            def values(w): return [w&0xffff]+([w>>16] if w>>16 else [])
            for amount in range(minimum,(maximum if not mode else count)+1):
                for chosen in itertools.combinations(range(count),amount):
                    ws=must+[weights[i] for i in chosen]
                    if any((sum(v)==target if not mode else sum(v)>=target and sum(v)-min(v)<target) for v in itertools.product(*(values(w) for w in ws))):
                        return [len(ws)]+list(range(mandatory))+list(chosen)
            raise AssertionError('no legal sum')
        raise AssertionError('unsupported fixture prompt '+data.hex())
    def run(self,name,s):
        self.errors=[]; self.trace=[]; used=set(); duel=self.create(7)
        try:
            for p in (0,1):
                self.player(duel,p,8000,0,0)
                for i in range(40): self.new(duel,91152256,p,p,1,i,8)
            for i,c in enumerate(s.get('extra',[])): self.new(duel,c,0,0,64,i,8)
            self.start(duel,3<<16|0x10)
            for i,c in enumerate(s.get('hand',[])): self.new(duel,c,0,0,2,i,8)
            for i,c in enumerate(s.get('field',[])): self.new(duel,c,0,0,4,i,1)
            for seq,c in s.get('scales',[]): self.new(duel,c,0,0,8,seq,1)
            for step in range(400):
                status=self.process(duel); n=self.get(duel,self.buf); data=self.buf.raw[:n]
                self.trace.append({'step':step,'status':status,'hex':data.hex()})
                if data and data[0]==1: raise AssertionError('MSG_RETRY')
                if self.errors: raise AssertionError(self.errors)
                field=self.field(duel)
                if s.get('target') in field and (not s.get('need_chain') or any(x['hex'].startswith('46') for x in self.trace)):
                    return {'passed':True,'steps':step+1,'field':field,'requests_used':sorted(used)}
                if s.get('need_draw') and any(x['hex'].startswith('5a0002') for x in self.trace):
                    return {'passed':True,'steps':step+1,'requests_used':sorted(used)}
                if status&0x10000000: self.send(duel,self.choose(data,s,used))
                if status&0x20000000: break
            raise AssertionError('fixture did not reach target')
        finally:
            self.end(duel)
            (OUT/(name+'-trace.json')).write_text(json.dumps(self.trace,indent=2),encoding='utf-8')

def main():
    h=Harness(); cases={
        'normal':{'hand':[91152256],'normal':91152256,'target':91152256},
        'chain-draw':{'hand':[55144522],'activate':55144522,'need_draw':True},
        'fusion':{'hand':[24094653,89631139,89631139,89631139],'extra':[23995346],'activate':24094653,'target':23995346,'need_chain':True},
        'ritual':{'hand':[76792184,30208479,89631139],'activate':76792184,'target':30208479,'need_chain':True},
        'synchro':{'field':[57649113,28279543],'extra':[44508094],'special':44508094,'target':44508094},
        'xyz':{'field':[91152256,91152256],'extra':[84013237],'special':84013237,'target':84013237},
        'pendulum':{'hand':[51531505,15146890,91152256],'activate':[51531505,15146890],'special':-1,'target':91152256},
    }; results={}
    for name,s in cases.items():
        try: results[name]=h.run(name,s)
        except Exception as e: results[name]={'passed':False,'error':str(e)}
        print(name,results[name],flush=True)
    (OUT/'mechanics-validation.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
    return 0 if all(r['passed'] for r in results.values()) else 1
if __name__=='__main__':
    OUT.mkdir(exist_ok=True,parents=True)
    if '--worker' in sys.argv: raise SystemExit(main())
    p=subprocess.run([sys.executable,'-u',__file__,'--worker'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60)
    (OUT/'mechanics.log').write_bytes(p.stdout); print(p.stdout.decode('utf-8','replace')); raise SystemExit(p.returncode)
