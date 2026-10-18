"""Initialize every pre-cutoff candidate card using frozen native DLL + matching Lua."""
import json,sqlite3,subprocess,sys
from verify_native_mechanics import Harness
from verify_mdpro_abi import ROOT, MDPRO, DB, OUT

def worker():
 con=sqlite3.connect(DB.as_uri()+'?mode=ro',uri=True)
 con.execute('attach database ? as pk',((MDPRO/'Data/pack/pack.db').as_uri()+'?mode=ro',))
 codes=[r[0] for r in con.execute("select d.id from datas d join pk.pack p using(id) where p.date<='2017-03-24' and (d.type & 0x4000000)=0 order by d.id")]; con.close()
 h=Harness(); initialized=[]; failures=[]
 for offset in range(0,len(codes),100):
  duel=h.create(7)
  try:
   for seq,code in enumerate(codes[offset:offset+100]):
    h.errors=[]; h.new(duel,code,0,0,1,seq,8)
    if h.errors: failures.append({'passcode':code,'errors':h.errors[:]})
    else: initialized.append(code)
  finally: h.end(duel)
  if offset%1000==0: print('initialized',offset+len(codes[offset:offset+100]),flush=True)
 result={'candidate_count':len(codes),'initialized':len(initialized),'failures':failures,'scope':'initial_effect loading only; does not prove every effect resolution'}
 (OUT/'script-coverage.json').write_text(json.dumps(result,indent=2),encoding='utf-8'); print(json.dumps(result,indent=2)); return bool(failures)
if __name__=='__main__':
 if '--worker' in sys.argv: raise SystemExit(worker())
 p=subprocess.run([sys.executable,'-u',__file__,'--worker'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60)
 (OUT/'script-coverage.log').write_bytes(p.stdout); print(p.stdout.decode('utf-8','replace')); raise SystemExit(p.returncode)
