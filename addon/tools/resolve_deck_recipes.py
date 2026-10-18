"""Resolve physical quantities against reviewed membership, then normalize to 40 main cards.

Early 50-card decks and auxiliary packs are not silently claimed as physical 40-card products.
Every adaptation is written to a reviewable audit; booster strengthening packs stay boosters.
"""
import json,re,urllib.request,urllib.parse,collections,math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'generated/deck-composition'
data=json.loads((ROOT/'generated/content/resources/data/yugioh_mdpro_addon/historical-content.json').read_text(encoding='utf-8'))
pages=json.loads((OUT/'set-lists.json').read_text(encoding='utf-8'))
titles=json.loads((OUT/'product-titles.json').read_text(encoding='utf-8'))
cards={c['id']:c for c in data['cards']}
def query(args):
    url='https://yugipedia.com/api.php?'+urllib.parse.urlencode(dict(action='query',format='json',**args))
    return json.load(urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'MinecraftYugiohMetadataImport/0.5 (noncommercial local resource research)'}),timeout=30))
for title,row in list(pages.items()):
    if row.get('text'):continue
    prefix=re.sub(r' \((?:TCG-EN|OCG-JP)\)$','',title)
    prefix=prefix.replace(' (OCG)','').replace(' (TCG)','').replace(' Special Edition (Japanese)','')
    matches=query(dict(list='allpages',apnamespace=3006,apprefix=prefix.split(':',1)[1],aplimit=50))['query']['allpages']
    candidates=[p['title'] for p in matches if 'TCG-EN' in p['title'] or 'OCG-JP' in p['title']]
    (OUT/'missing-title-search.json').write_text(json.dumps({**(json.loads((OUT/'missing-title-search.json').read_text(encoding='utf-8')) if (OUT/'missing-title-search.json').exists() else {}),title:candidates},ensure_ascii=False,indent=2),encoding='utf-8')
    preferred='TCG-EN' if 'TCG-EN' in title else 'OCG-JP'
    candidate=next((p for p in candidates if preferred in p),None)
    if candidate:
        obj=query(dict(titles=candidate,prop='revisions',rvprop='content',redirects='1'))
        value=next(iter(obj['query']['pages'].values()))
        pages[title]=dict(title=value['title'],text=value.get('revisions',[{}])[0].get('*',''),resolved_from=title)
(OUT/'set-lists.json').write_text(json.dumps(pages,ensure_ascii=False,indent=2),encoding='utf-8')
def norm(s):return re.sub(r'[^a-z0-9]','',re.sub(r' \(card\)$','',s.lower()))
recipes={};audit=[]
for p in data['products']:
    if p['product_kind'] not in ('starter_deck','structure_deck','preconstructed_deck'):continue
    pid=p['product_id'];name=p.get('name_original',p['name']);title=titles.get(pid,'');page=pages.get(title,{})
    if pid in ('en:13301000','en:13301001') and ' Evolution ' in page.get('title',''):page={}
    if 'Power-Up Pack' in name or '強化パック' in name:
        recipes[pid]=dict(opening_kind='booster',basis='strengthening booster, not a preconstructed deck');continue
    companion={'ja:2201024':'ja:2201023','ja:2201028':'ja:2201016','ja:2201029':'ja:2201017','ja:2201030':'ja:2201018'}.get(pid)
    source_products=[pid]
    member_rows=list(p['cards'])
    if companion:
        base=next(b for b in data['products'] if b['product_id']==companion)
        member_rows=list(base['cards'])+member_rows
        source_products.append(companion)
        page=pages.get(titles[companion],{})
    members={r['id']:r for r in member_rows if r['code']}
    byname={norm(cards[c]['name_en']):c for c in members}
    bynumber={r['collection_number']:r['id'] for r in members.values()}
    physical=collections.OrderedDict();unmatched=[]
    for line in page.get('text','').splitlines():
        line=line.split('//')[0].strip()
        parts=[x.strip() for x in line.split(';')]
        if len(parts)<2 or not re.match(r'^[A-Z0-9]+-[A-Z0-9]+$',parts[0]):continue
        cid=bynumber.get(parts[0]) or byname.get(norm(parts[1]))
        if not cid:
            unmatched.append(parts[:2]);continue
        qty=next((int(x) for x in reversed(parts[3:]) if x.isdigit()),1)
        # Reprints/region sections may repeat; never add duplicate listings.
        physical[cid]=max(physical.get(cid,0),qty)
    main=collections.OrderedDict((c,min(3,q)) for c,q in physical.items() if not cards[c]['type']&(0x40|0x2000|0x800000))
    extra=collections.OrderedDict((c,min(3,q)) for c,q in physical.items() if cards[c]['type']&(0x40|0x2000|0x800000))
    raw=sum(main.values());basis='physical set list' if raw==40 else 'game adaptation of physical set list'
    if not main:
        main=collections.OrderedDict((c,1) for c in members if not cards[c]['type']&(0x40|0x2000|0x800000))
        extra=collections.OrderedDict((c,1) for c in members if cards[c]['type']&(0x40|0x2000|0x800000))
        basis='game adaptation of reviewed product membership (quantities unavailable)'
    original_main=dict(main)
    # Normalize oversized historical decks proportionally across monster/spell/
    # trap sections so shortening a 50-card deck cannot strip all its traps.
    def category(c):return 1 if cards[c]['type']&1 else 2 if cards[c]['type']&2 else 4
    total=sum(main.values())
    if total>40:
        categories={t:sum(q for c,q in main.items() if category(c)==t) for t in (1,2,4)}
        targets={t:math.floor(q*40/total) for t,q in categories.items()}
        for t in sorted(categories,key=lambda t:categories[t]*40/total-targets[t],reverse=True)[:40-sum(targets.values())]:targets[t]+=1
        for t in categories:
            while sum(q for c,q in main.items() if category(c)==t)>targets[t]:
                key=next(c for c in reversed(main) if category(c)==t and main[c]>1) if any(category(c)==t and q>1 for c,q in main.items()) else next(c for c in reversed(main) if category(c)==t)
                main[key]-=1
                if not main[key]:del main[key]
    # Product recipes never consult a server's banlist. Restrictions only
    # affect deck validation, never which real cards a player receives.
    candidates=list(main)+[c for c in members if c not in main and not cards[c]['type']&(0x40|0x2000|0x800000)]
    while sum(main.values())<40:
        advanced=False
        for c in candidates:
            family=cards[c]['alias'] or cards[c]['code']
            count=sum(q for k,q in list(main.items())+list(extra.items()) if (cards[k]['alias'] or cards[k]['code'])==family)
            if count<3:
                main[c]=main.get(c,0)+1;advanced=True
                if sum(main.values())==40:break
        if not advanced:raise ValueError(('Insufficient product main cards',pid,len(main)))
    assert sum(main.values())==40 and sum(extra.values())<=15,(pid,extra)
    adjusted=original_main!=dict(main)
    if adjusted:basis='40-card game adaptation of product membership; independent of server restrictions'
    if companion:basis+='; special retail edition includes base structure deck'
    recipes[pid]=dict(main=[dict(id=c,count=q) for c,q in main.items()],extra=[dict(id=c,count=q) for c,q in extra.items()],physical_cards=[dict(id=c,count=q) for c,q in physical.items()],basis=basis,source_products=source_products,source_title=page.get('title',title),physical_main_count=raw)
    audit.append(dict(product_id=pid,name=name,source_title=page.get('title',title),physical_main_count=raw,basis=basis,unmatched=unmatched))
dest=ROOT/'addon/content/deck-recipes.json';dest.write_text(json.dumps(recipes,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'recipe-audit.json').write_text(json.dumps(audit,ensure_ascii=False,indent=2),encoding='utf-8')
print('Decks',len(audit),'physical40',sum(a['physical_main_count']==40 for a in audit),'adapted',sum(a['physical_main_count']!=40 for a in audit),flush=True)
