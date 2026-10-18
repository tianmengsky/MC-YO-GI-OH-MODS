"""Compose Chinese faces with sprites extracted verbatim from MDPro3.

No frame, attribute, level or rank symbol is drawn by this tool. Only card text
and numeric statistics/scales are typeset, as in MDPro3's CardRenderer.
"""
from pathlib import Path
import re
from PIL import Image,ImageDraw,ImageFont,ImageOps
ROOT=Path(__file__).resolve().parents[2]
LAYOUT=ROOT/'generated/mdpro-card-layout'
REVISION='mdpro-native-frame-v1'
SPRITES={p.stem:Image.open(p).convert('RGBA') for p in LAYOUT.glob('*.png') if p.stem.startswith(('cardFrame','attribute','type'))}
FONT='C:/Windows/Fonts/msyh.ttc'
FONTS={n:ImageFont.truetype(FONT,n) for n in range(9,65)}
RACES={1:'战士',2:'魔法师',4:'天使',8:'恶魔',16:'不死',32:'机械',64:'水',128:'炎',256:'岩石',512:'鸟兽',1024:'植物',2048:'昆虫',4096:'雷',8192:'龙',16384:'兽',32768:'兽战士',65536:'恐龙',131072:'鱼',262144:'海龙',524288:'爬虫类',1048576:'念动力',2097152:'幻神兽',4194304:'创造神',8388608:'幻龙'}
def wrap(text,font,width):
    lines=[]
    for para in text.splitlines():
        line=''
        for ch in para:
            if font.getlength(line+ch)>width and line:lines.append(line);line=''
            line+=ch
        lines.append(line)
    return lines
def text_block(im,text,box,maxsize=22,color='black'):
    x,y,w,h=box
    for size in range(maxsize,8,-1):
        f=FONTS[size];lines=wrap(text,f,w)
        if len(lines)*(size+2)<=h:break
    layer=Image.new('RGBA',(w,max(h,len(lines)*(size+2))), (0,0,0,0));d=ImageDraw.Draw(layer)
    for i,line in enumerate(lines):d.text((0,i*(size+2)),line,font=f,fill=color)
    if layer.height>h:layer=layer.resize((w,h),Image.Resampling.LANCZOS)
    im.alpha_composite(layer,(x,y))
def label(im,text,pos,size,width=None,color='black'):
    f=FONTS[size];w=max(1,int(f.getlength(text))+4)
    layer=Image.new('RGBA',(w,size+16));ImageDraw.Draw(layer).text((0,0),text,font=f,fill=color)
    if width and w>width:layer=layer.resize((width,size+16),Image.Resampling.LANCZOS)
    im.alpha_composite(layer,pos)
def frame_name(card):
    t=card['type'];pend=bool(t&0x1000000)
    if t&2:return 'Spell'
    if t&4:return 'Trap'
    if card['code'] in (10000000,10000010,10000020):return {10000000:'Obelisk',10000010:'Ra',10000020:'Osiris'}[card['code']]
    kind='Xyz' if t&0x800000 else 'Synchro' if t&0x2000 else 'Fusion' if t&0x40 else 'Ritual' if t&0x80 else 'Effect' if t&0x20 else 'Normal'
    return ('Pendulum' if pend else '')+kind
def compose(card):
    t=card['type'];pend=bool(t&0x1000000);kind=frame_name(card)
    frame=SPRITES['cardFrame'+kind+'OF']
    im=Image.new('RGBA',(704,1024),(0,0,0,255))
    src=ROOT/f"inputs/mdpro3/Picture/Art/{card['code']}.jpg"
    art=Image.open(src).convert('RGBA')
    box=(49,186,656,642) if pend else (85,189,619,723)
    im.alpha_composite(ImageOps.fit(art,(box[2]-box[0],box[3]-box[1]),method=Image.Resampling.LANCZOS),box[:2])
    im.alpha_composite(frame)
    white=kind in ('Spell','Trap','Xyz')
    label(im,card['name'],(52,48),50,520,'white' if white else 'black')
    attr='Spell' if t&2 else 'Trap' if t&4 else {1:'Earth',2:'Water',4:'Fire',8:'Wind',16:'Light',32:'Dark',64:'Divine'}.get(card['attribute'])
    if attr:im.alpha_composite(SPRITES['attribute'+attr].resize((58,58),Image.Resampling.LANCZOS),(595,49))
    if t&1:
        n=card['level_packed']&255
        if card['code'] in (1686814,90884403,26973555,43490025,65305468,52653092):n=0
        n=min(n,13);star=SPRITES['typeRank' if t&0x800000 else 'typeLevel'].resize((40,40),Image.Resampling.LANCZOS)
        for i in range(n):im.alpha_composite(star,((82+i*41) if t&0x800000 else (580-i*41),130))
    else:
        icons=[(0x100000,'Counter'),(0x80000,'Field'),(0x40000,'Equip'),(0x20000,'Continuous'),(0x10000,'QuickPlay'),(0x80,'Ritual')]
        icon=next((name for flag,name in icons if t&flag),None)
        label(im,'【魔法卡】' if t&2 else '【陷阱卡】',(445 if icon else 492,129),27,170,'black')
        if icon:im.alpha_composite(SPRITES['type'+icon].resize((36,36),Image.Resampling.LANCZOS),(608,137))
    text=card['text'].replace('\\n','\n')
    if pend:
        # Native Chinese table uses explicit Pendulum/monster description markers.
        parts=text.split('【怪兽效果】',1)
        if len(parts)==1:parts=text.split('【怪獸效果】',1)
        if len(parts)==1:parts=text.split('----------------------------------------',1)
        ptext=re.sub(r'^←[^\n]*\n?', '', parts[0]).replace('【灵摆效果】','').replace('【靈擺效果】','').strip() if len(parts)>1 else ''
        text=parts[-1].strip()
        text_block(im,ptext,(110,648,485,105),19)
        label(im,str((card['level_packed']>>24)&255),(64,717),30,34)
        label(im,str((card['level_packed']>>16)&255),(618,717),30,34)
    if t&1:
        tags=[RACES.get(card['race'],'怪兽')+'族']
        for flag,name in ((0x40,'融合'),(0x80,'仪式'),(0x2000,'同调'),(0x800000,'超量'),(0x1000000,'灵摆'),(0x1000,'调整'),(0x200000,'反转'),(0x800,'二重'),(0x200,'灵魂'),(0x400,'同盟')):
            if t&flag:tags.append(name)
        tags.append('效果' if t&0x20 else '通常')
        text='【'+'／'.join(tags)+'】\n'+text
    text_block(im,text,(54,774,596,166 if t&1 else 180),22)
    if t&1:
        a='?' if card['atk']<0 else str(card['atk']);b='?' if card['def']<0 else str(card['def'])
        label(im,f'ATK/{a} DEF/{b}',(377,943),25,265)
    label(im,f"{card['code']:08d}",(29,982),16,140,'white' if kind=='Xyz' else 'black')
    return im.convert('RGB').resize((176,257),Image.Resampling.LANCZOS)
