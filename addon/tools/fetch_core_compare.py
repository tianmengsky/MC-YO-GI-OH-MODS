import urllib.request,json,base64,pathlib,hashlib
root=pathlib.Path('generated/mdpro-source'); tree=json.loads((root/'tree.json').read_text()); x=next(x for x in tree['tree'] if x['path']=='Assets/Plugins/YGO Classes/ocgcore.dll'); print(x)
d=json.load(urllib.request.urlopen('https://api.github.com/repos/SethPDA/MDPro3/git/blobs/'+x['sha'],timeout=25)); b=base64.b64decode(d['content']); (root/'upstream-ocgcore.dll').write_bytes(b); print(hashlib.sha256(b).hexdigest())
