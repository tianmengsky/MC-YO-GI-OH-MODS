import sqlite3
c=sqlite3.connect('file:inputs/mdpro3/Data/locales/zh-CN/cards.cdb?mode=ro',uri=True)
for name in ['废品同调士','X-剑士 安娜佩瑞拉','调音战士','黑混沌之魔术师','混沌-黑魔术的仪式','黑混沌之仪式','暗之诱惑','闪光之骑士','傅科魔炮石','龙穴之魔术师','龙脉之魔术师','红齿轮','银齿轮','金齿轮']:
 for r in c.execute('select d.id,t.name,d.level,d.type from datas d join texts t using(id) where t.name like ?',(name,)): print(ascii(r))
print('tuners',list(c.execute('select id,level from datas where type=4113 and level=3 limit 10')))
