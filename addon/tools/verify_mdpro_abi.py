"""Isolated verification of frozen MDPro3 Percy ABI; never imports step*.py."""
import ctypes as C
import hashlib
import json
import sqlite3
import struct
import subprocess
import sys
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
MDPRO = ROOT / 'inputs/mdpro3'
DLL = MDPRO / 'MDPro3_Data/Plugins/x86_64/ocgcore.dll'
DB = MDPRO / 'Data/locales/zh-CN/cards.cdb'
ZIP = MDPRO / 'Data/script.zip'
OUT = ROOT / 'generated/mdpro-abi'
SR = C.CFUNCTYPE(C.c_void_p, C.c_char_p, C.POINTER(C.c_int32))
CR = C.CFUNCTYPE(C.c_uint32, C.c_uint32, C.c_void_p)
MH = C.CFUNCTYPE(C.c_uint32, C.c_void_p, C.c_uint32)

def card_bytes(row):
    packed = row['setcode'] & 0xffffffffffffffff
    codes = []
    while packed:
        if packed & 0xffff: codes.append(packed & 0xffff)
        packed >>= 16
    level = row['level']
    link = bool(row['type'] & 0x4000000)
    return struct.pack('<II16HIIIIiiIII', row['id'], row['alias'],
        *(codes+[0]*(16-len(codes))), row['type'], level & 0xff,
        row['attribute'], row['race'], row['atk'], 0 if link else row['def'],
        (level>>24)&0xff, (level>>16)&0xff, row['def'] if link else 0)

def bind(core, name, result, *args):
    f = getattr(core, name); f.restype = result; f.argtypes = list(args); return f

def worker():
    con = sqlite3.connect(DB.as_uri()+'?mode=ro', uri=True)
    con.row_factory = sqlite3.Row
    cards = {r['id']: r for r in con.execute('SELECT * FROM datas')}; con.close()
    scripts = zipfile.ZipFile(ZIP)
    keep, missing, logs = {}, [], []
    hits = {'cards':0, 'scripts':0}
    core = C.CDLL(str(DLL))
    log_buffer = C.create_string_buffer(0x40000)
    log_message = bind(core, 'get_log_message', None, C.c_void_p, C.c_void_p)
    def script(name, size):
        key = name.decode('utf-8').lstrip('./').replace('\\','/')
        if '/' not in key: key = 'script/'+key
        try: data = scripts.read(key)
        except KeyError:
            missing.append(key); size[0] = 0; return None
        if key not in keep: keep[key] = C.create_string_buffer(data)
        hits['scripts'] += 1; size[0] = len(data); return C.addressof(keep[key])
    def card(code, target):
        row = cards.get(code)
        if row is None: missing.append('card:'+str(code)); return 0
        data = card_bytes(row); C.memmove(target,data,len(data)); hits['cards']+=1; return 0
    def message(duel, kind):
        log_message(duel,log_buffer)
        logs.append({'kind':kind,'text':log_buffer.value.decode('utf-8','replace')}); return 0
    callbacks = (SR(script),CR(card),MH(message))
    for name, callback, typ in zip(('set_script_reader','set_card_reader','set_message_handler'),callbacks,(SR,CR,MH)):
        bind(core,name,None,typ)(callback)
    create = bind(core,'create_duel',C.c_void_p,C.c_uint32)
    end = bind(core,'end_duel',None,C.c_void_p)
    player = bind(core,'set_player_info',None,C.c_void_p,*([C.c_int32]*4))
    new = bind(core,'new_card',None,C.c_void_p,C.c_uint32,*([C.c_uint8]*5))
    count = bind(core,'query_field_count',C.c_int32,C.c_void_p,C.c_uint8,C.c_uint8)
    query = bind(core,'query_field_info',C.c_int32,C.c_void_p,C.c_void_p)
    start = bind(core,'start_duel',None,C.c_void_p,C.c_uint32)
    process = bind(core,'process',C.c_uint32,C.c_void_p)
    get = bind(core,'get_message',C.c_int32,C.c_void_p,C.c_void_p)
    ri = bind(core,'set_responsei',None,C.c_void_p,C.c_int32)
    rb = bind(core,'set_responseb',None,C.c_void_p,C.c_void_p)
    buf = C.create_string_buffer(0x40000)
    codes = [89631139,46986414,70781052,91152256,40640057,30090452,28279543,
             15025844,69247929,12482652,34460851,40453765,47025270,87564352]
    deck = [c for c in codes for _ in range(3)][:40]
    packets, turns, draws, retries = [],[],0,0
    duel = create(7)
    if not duel: raise RuntimeError('null duel')
    try:
        for p in (0,1):
            player(duel,p,8000,5,1)
            for seq,code in enumerate(deck): new(duel,code,p,p,1,seq,8)
        before = [count(duel,p,1) for p in (0,1)]
        assert before == [40,40], before
        start(duel,(3<<16)|0x10)
        query(duel,buf); rule = buf.raw[1]
        assert buf.raw[0] == 162 and rule == 3, buf.raw[:2].hex()
        for step in range(1200):
            status = process(duel); length = get(duel,buf); data = buf.raw[:length]
            packets.append({'step':step,'status':status,'hex':data.hex()})
            if not data:
                if status & 0x20000000: break
                continue
            if data[0] == 40: turns.append(data[1])
            if data[0] == 90: draws += data[2]
            if data[0] == 1:
                retries += 1; raise AssertionError('core rejected fixture response')
            if status & 0x10000000:
                if data[0] == 11: ri(duel,7)
                elif data[0] == 16 and data[2] == 0: ri(duel,-1)
                elif data[0] == 15:
                    minimum = data[3]
                    reply = bytes([minimum]+list(range(minimum)))
                    rb(duel,C.create_string_buffer(reply.ljust(64,b'\0')))
                else: raise AssertionError('unsupported fixture prompt: '+data.hex())
            if len(turns) >= 6: break
        assert len(turns) >= 6 and draws >= 10, (turns,draws)
        assert not logs and not missing, (logs,missing)
    finally: end(duel)
    duel = create(7)
    try:
        player(duel,0,8000,5,1); new(duel,89631139,0,0,1,0,8)
        assert count(duel,0,1) == 1
    finally: end(duel)
    result = {'passed':True,'dll_sha256':hashlib.sha256(DLL.read_bytes()).hexdigest(),
        'abi':'Percy','card_data_size':76,'master_rule':rule,'deck_count_before_start':before,
        'turns':turns,'drawn_cards':draws,'retry_count':retries,'callback_hits':hits,
        'logs':logs,'missing':missing,
        'scope':'native insertion, MR3 query, six turns, discards, handle reuse'}
    (OUT/'packets.json').write_text(json.dumps(packets,indent=2),encoding='utf-8')
    (OUT/'abi-validation.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result,indent=2))
if __name__ == '__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    if '--worker' in sys.argv: worker()
    else:
        p = subprocess.run([sys.executable,'-u',__file__,'--worker'],cwd=ROOT,
            stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45)
        (OUT/'trace.log').write_bytes(p.stdout)
        print(p.stdout.decode('utf-8','replace'))
        if p.returncode: print('worker exit: '+hex(p.returncode))
        raise SystemExit(p.returncode)
