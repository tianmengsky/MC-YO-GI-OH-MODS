"""Read the user's MDPro3 bundles; export native card sprites and provenance locally."""
import sys,json,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'generated/python-libs'))
import UnityPy
from UnityPy.classes import PPtr
SRC=Path('F:/MyCardLibrary/mdpro3/MDPro3_Data/StandaloneWindows64/MDPro3')
OUT=ROOT/'generated/mdpro-card-layout';OUT.mkdir(exist_ok=True)
bundles=[SRC/n for n in ('prefab_assets_scriptableobjects.bundle','texture_assets_all.bundle','prefab_assets_prefab.bundle')]
env=UnityPy.load(*(str(p) for p in bundles)); exports={}
for obj in env.objects:
    if obj.type.name!='MonoBehaviour':continue
    tree=obj.read_typetree()
    if 'cardFrameNormal' in tree:
        (OUT/'container.json').write_text(json.dumps(tree,ensure_ascii=False,indent=2),encoding='utf-8')
        for key,v in tree.items():
            if not (key.startswith(('cardFrame','attribute','type')) and isinstance(v,dict) and v.get('m_PathID')):continue
            try:
                sprite=PPtr(m_FileID=v['m_FileID'],m_PathID=v['m_PathID'],assetsfile=obj.assets_file).read()
                sprite.image.save(OUT/(key+'.png'))
                exports[key]=dict(size=sprite.image.size,path_id=v['m_PathID'])
            except (AttributeError,ValueError):pass
    if 'cardArt' in tree and 'cardFrame' in tree:
        (OUT/'renderer-prefab.json').write_text(json.dumps(tree,ensure_ascii=False,indent=2),encoding='utf-8')
        rects={}
        for key,v in tree.items():
            if not isinstance(v,dict) or not v.get('m_PathID'):continue
            try:
                ptr=PPtr(m_FileID=v['m_FileID'],m_PathID=v['m_PathID'],assetsfile=obj.assets_file)
                comp=ptr.read_typetree(); go=ptr.read().m_GameObject.read()
                for c in go.m_Component:
                    t=c.component.read_typetree()
                    if 'm_AnchoredPosition' in t:rects[key]=t
            except Exception:pass
        (OUT/'renderer-rects.json').write_text(json.dumps(rects,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'provenance.json').write_text(json.dumps(dict(bundles={p.name:hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in bundles},sprites=exports,unitypy=UnityPy.__version__),ensure_ascii=False,indent=2),encoding='utf-8')
print('Native sprites:',len(exports))
