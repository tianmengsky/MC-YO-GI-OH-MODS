import dnfile
from pathlib import Path
p=dnfile.dnPE(r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Managed\Assembly-CSharp.dll")
t=p.net.mdtables
owners={id(m.row):str(td.TypeNamespace)+'.'+str(td.TypeName) for td in t.TypeDef.rows for m in td.MethodList}
for r in t.ImplMap.rows:
 m=r.MemberForwarded.row
 print(str(r.ImportScope.row.Name), str(r.ImportName), owners.get(id(m)), str(m.Name), m.Signature.value.hex(), [(x.row.Sequence,str(x.row.Name)) for x in m.ParamList])
for td in t.TypeDef.rows:
 name=str(td.TypeNamespace)+'.'+str(td.TypeName)
 if any(x in name.lower() for x in ['ocgcore','corewrapper','card_data','carddata','newcard','dueloption']):
  print('TYPE', name, td.struct.Flags)
  for f in td.FieldList: print(' FIELD',str(f.row.Name),f.row.Signature.value.hex())
  for m in td.MethodList: print(' METHOD',str(m.row.Name),m.row.Signature.value.hex())
