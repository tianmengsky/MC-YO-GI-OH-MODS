import urllib.request,json,pathlib,base64,concurrent.futures
root=pathlib.Path('generated/mdpro-source'); root.mkdir(exist_ok=True,parents=True)
paths=['LICENSE','Assets/Scripts/MDPro3/Duel/PercyOCG.cs','Assets/Scripts/MDPro3/Servants/OcgCore.cs','Tools/YGO Classes/ocgcore/ocgapi.h','Tools/YGO Classes/ocgcore/ocgapi.cpp','Tools/YGO Classes/ocgcore/card_data.h','Tools/YGO Classes/ocgcore/common.h']
def fetch(p):
 u='https://api.github.com/repos/SethPDA/MDPro3/contents/'+urllib.parse.quote(p)+'?ref=main'
 try:
  d=json.load(urllib.request.urlopen(u,timeout=20)); (root/pathlib.Path(p).name).write_bytes(base64.b64decode(d['content'])); return (p,d['sha'])
 except Exception as e: return (p,str(e))
with concurrent.futures.ThreadPoolExecutor(max_workers=7) as pool: results=list(pool.map(fetch,paths))
print(results,flush=True); (root/'source.json').write_text(json.dumps(results,indent=2))
