import dnfile
p=dnfile.dnPE(r"F:\MyCardLibrary\mdpro3\MDPro3_Data\Managed\Assembly-CSharp.dll")
print('loaded',p.net)
for tabname in ['ImplMap','ModuleRef','TypeDef','Field','Param','CustomAttribute']:
 t=getattr(p.net.mdtables,tabname,None)
 print(tabname, t and len(t.rows))
 if t and t.rows:
  r=t.rows[0]
  print(type(r), [x for x in dir(r) if not x.startswith('_')])
  for x in ['MappingFlags','MemberForwarded','ImportName','ImportScope','Name','Flags','Signature','Parent','Type','Value']:
   if hasattr(r,x):
    try: print(' ',x,repr(getattr(r,x)))
    except Exception as e: print(' ',x,e)
