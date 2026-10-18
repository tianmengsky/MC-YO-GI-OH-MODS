import dnfile, json
from pathlib import Path
p=dnfile.dnPE(r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Managed\Assembly-CSharp.dll")
t=p.net.mdtables
names={id(td):'.'.join(filter(None,[str(td.TypeNamespace),str(td.TypeName)])) for td in t.TypeDef.rows}
for n in t.NestedClass.rows:
 names[id(n.NestedClass.row)]=names[id(n.EnclosingClass.row)]+'+'+str(n.NestedClass.row.TypeName)
def typeref(v):
 tag=v&3; rid=v>>2
 tab=[t.TypeDef,t.TypeRef,t.TypeSpec][tag]
 r=tab.rows[rid-1]
 if tag==0:return names[id(r)]
 if tag==1:return '.'.join(filter(None,[str(r.TypeNamespace),str(r.TypeName)]))
 return '<typespec>'
class Sig:
 def __init__(self,b):self.b=b;self.i=0
 def num(self):
  x=self.b[self.i];self.i+=1
  if x<128:return x
  n=1 if x<192 else 3;v=x & (63 if n==1 else 31)
  for _ in range(n):v=(v<<8)|self.b[self.i];self.i+=1
  return v
 def ty(self):
  e=self.num(); prim={1:'void',2:'bool',3:'char',4:'int8',5:'uint8',6:'int16',7:'uint16',8:'int32',9:'uint32',10:'int64',11:'uint64',12:'float',13:'double',14:'string',24:'intptr',25:'uintptr',28:'object'}
  if e in prim:return prim[e]
  if e in (17,18):return typeref(self.num())
  if e==15:return self.ty()+'*'
  if e==16:return self.ty()+'&'
  if e==29:return self.ty()+'[]'
  if e==21:
   base=self.ty();n=self.num();return base+'<'+','.join(self.ty() for _ in range(n))+'>'
  return f'ET{e:02x}'
 def method(self):
  cc=self.num()
  if cc&16:self.num()
  n=self.num();ret=self.ty();return ret,[self.ty() for _ in range(n)]
def ms(m):
 ret,ps=Sig(m.Signature.value).method()
 pn={x.row.Sequence:str(x.row.Name) for x in m.ParamList}
 return ret+' '+str(m.Name)+'('+', '.join(x+' '+pn.get(i+1,'') for i,x in enumerate(ps))+')'
owners={id(m.row):names[id(td)] for td in t.TypeDef.rows for m in td.MethodList}
lines=[]
for r in t.ImplMap.rows:
 if str(r.ImportScope.row.Name)!='ocgcore':continue
 lines.append(owners[id(r.MemberForwarded.row)]+': '+ms(r.MemberForwarded.row)+' [flags='+hex(r.struct.MappingFlags)+']')
for td in t.TypeDef.rows:
 name=names[id(td)]
 if name in ['Percy.CardData','Percy.Dll','YGOSharp.OCGWrapper.Api','CardData'] or ('Percy.Dll+' in name) or ('YGOSharp.OCGWrapper.Api+' in name):
  lines.append('\nTYPE '+name+' [flags='+hex(td.struct.Flags)+']')
  for cl in (t.ClassLayout.rows if t.ClassLayout else []):
   if cl.Parent.row is td:lines.append(' LAYOUT pack='+str(cl.PackingSize)+' size='+str(cl.ClassSize))
  for f in td.FieldList:
   s=Sig(f.row.Signature.value);s.num();lines.append(' FIELD '+s.ty()+' '+str(f.row.Name))
  for m in td.MethodList:
   if str(m.row.Name)=='Invoke':lines.append(' CALLBACK '+ms(m.row))
  for ca in t.CustomAttribute.rows:
   if ca.Parent.row is td:lines.append(' ATTRIBUTE constructor='+str(ca.Type.row.Name)+' value='+ca.Value.value.hex())
text='\n'.join(lines)
Path('decoded-signatures.txt').write_text(text,encoding='utf-8')
print(text)
