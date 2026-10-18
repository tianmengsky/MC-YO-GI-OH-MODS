import json,urllib.request,base64,pathlib,concurrent.futures
root=pathlib.Path('generated/mdpro-source'); tree=json.loads((root/'tree.json').read_text())
paths=['Tools/YGO Classes/ocgcore/playerop.cpp','Tools/YGO Classes/ocgcore/processor.cpp','Assets/Scripts/YGOSharp/YGOSharp.OCGWrapper/Duel.cs','Assets/Scripts/YGOSharp/YGOSharp.OCGWrapper/Api.cs']
def get(path):
 item=next(x for x in tree['tree'] if x['path']==path)
 d=json.load(urllib.request.urlopen('https://api.github.com/repos/SethPDA/MDPro3/git/blobs/'+item['sha'],timeout=25)); (root/pathlib.Path(path).name).write_bytes(base64.b64decode(d['content'])); return path
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as p: print(list(p.map(get,paths)))
