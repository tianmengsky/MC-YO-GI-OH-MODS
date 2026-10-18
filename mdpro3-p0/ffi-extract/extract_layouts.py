exec(open('extract_signatures.py',encoding='utf-8-sig').read().split('lines=[]')[0])
for td in t.TypeDef.rows:
 n=names[id(td)]
 if n.startswith('Percy.CardData') or n=='YGOSharp.OCGWrapper.Card+CardData':
  print('TYPE',n)
  for cl in (t.ClassLayout.rows if t.ClassLayout else []):
   if cl.Parent.row is td:print('LAYOUT',cl.PackingSize,cl.ClassSize)
  for f in td.FieldList:
   s=Sig(f.row.Signature.value);s.num();print('FIELD',str(f.row.Name),s.ty())
   for ca in t.CustomAttribute.rows:
    if ca.Parent.row is f.row:print('ATTRIBUTE',str(ca.Type.row.Name),ca.Value.value.hex())
