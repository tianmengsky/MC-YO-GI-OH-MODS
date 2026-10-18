import pathlib,urllib.request,json,base64,concurrent.futures
root=pathlib.Path('generated/mdpro-source'); tree=json.loads((root/'tree.json').read_text()); paths=['Tools/YGO Classes/ocgcore/interpreter.cpp','Tools/YGO Classes/ocgcore/card.cpp','Assets/Scripts/YGOSharp/YGOSharp.OCGWrapper/CardsManager.cs','Assets/Scripts/MDPro3/Duel/MDPro3.YGOSharp/CardsManager.cs']
def f(path):
 x=next((x for x in tree['tree'] if x['path']==path),None)
 if not x:return path+' missing'
 d=json.load(urllib.request.urlopen('https://api.github.com/repos/SethPDA/MDPro3/git/blobs/'+x['sha'],timeout=20)); (root/(('MDPro-' if 'MDPro3.YGOSharp' in path else '')+pathlib.Path(path).name)).write_bytes(base64.b64decode(d['content'])); return path
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as p:print(list(p.map(f,paths)))
