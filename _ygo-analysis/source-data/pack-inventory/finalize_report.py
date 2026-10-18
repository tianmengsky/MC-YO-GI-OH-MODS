"""Build human-readable coverage report and frozen top-level checksums."""
import collections, csv, hashlib, json, sqlite3, sys
from pathlib import Path
from datetime import datetime, timezone
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parent

def read(name):return json.loads((ROOT/name).read_text('utf-8'))

def main():
    cover=read('coverage.json');verify=read('verification.json');mapping=read('mapping_coverage.json')
    pool=read('pool_comparison.json');usage=read('card_usage_notice_summary.json')
    products=list(csv.DictReader((ROOT/'products.csv').open(encoding='utf-8-sig')))
    cards=list(csv.DictReader((ROOT/'cards.csv').open(encoding='utf-8-sig')))
    regional={}
    baseline={x['konami_pid'] for x in products if x['locale'] in ['ja','en']}
    for loc,stats in cover['locales'].items():
        ps=[p for p in products if p['locale']==loc]
        regional[loc]=dict(stats,**verify['index_audits'][loc],
            year_only_in_scope=sum(p['date_quality']=='year_only' for p in ps),
            pids_absent_from_ja_en=len({p['konami_pid'] for p in ps if p['konami_pid'] not in baseline}),
            status='complete_relative_to_official_catalog' if stats['products']==stats['complete'] else 'incomplete',
            source_url='https://www.db.yugioh-card.com/yugiohdb/card_list.action?clm=1&request_locale='+loc)
    (ROOT/'region_catalog_coverage.json').write_text(json.dumps(regional,ensure_ascii=False,indent=2),'utf-8')
    # The preliminary missing-region list remains as a provenance record, but
    # its current resolution is attached so it cannot be mistaken for an open gap.
    gap_file=ROOT/'regional_product_gaps.csv'
    if gap_file.exists():
        rows=list(csv.DictReader(gap_file.open(encoding='utf-8-sig')))
        status={p['product_id']:p['status'] for p in products}
        for row in rows:row['current_membership_status']=status.get(row['product_id'],'outside_selected_ja_en_scope_reference_only')
        with gap_file.open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.DictWriter(f,fieldnames=list(rows[0]) if rows else ['product_id','current_membership_status']);w.writeheader();w.writerows(rows)
    kinds=collections.Counter(p['product_kind'] for p in products)
    composite_kinds={'special_edition_box','tin','product_bundle','special_set','other'}
    composition=[{'product_id':p['product_id'],'name':p['name'],'product_kind':p['product_kind'],
        'composition_status':'retail_nested_contents_not_resolved',
        'official_membership_status':p['status'],'source_url':p['source_url'],
        'note':'Official card list can enumerate exclusive promo cards only; sealed booster/deck components inside retail packaging need separate product-page verification.'}
        for p in products if p['product_kind'] in composite_kinds]
    with (ROOT/'product_composition_review.csv').open('w',encoding='utf-8-sig',newline='') as f:
        cols=['product_id','name','product_kind','composition_status','official_membership_status','source_url','note']
        w=csv.DictWriter(f,fieldnames=cols);w.writeheader();w.writerows(composition)
    con=sqlite3.connect(ROOT/'inventory.sqlite')
    link_count=con.execute('SELECT count(*) FROM engine_cards WHERE CAST(type AS INTEGER) & 67108864 != 0').fetchone()[0]
    con.close()
    unmapped={r['konami_cid'] for r in csv.DictReader((ROOT/'card_passcode_mapping.csv').open(encoding='utf-8-sig')) if r['status']!='verified_two_sources'}
    explicit_restricted={r['konami_cid'] for r in csv.DictReader((ROOT/'card_usage_flags.csv').open(encoding='utf-8-sig')) if r['usage_notice_status']=='explicit_official_no_duel_notice'}
    lines=['# 连接时代前官方产品与卡牌盘点报告','',
        '盘点日期：2026-10-15。范围：发行日期不晚于 2017-03-24；官网占位日期仅在官方分类年份不晚于 2016 时按年份纳入。',
        '',f"本次正式源库盘点官方日文、英文目录中的 **{cover['product_count']} 条产品/语言发行记录**，其中 **{cover['status_counts'].get('complete',0)} 条**完成完整收录页计数核对；保存 **{cover['membership_rows']} 条产品—卡片源行关系**，跨产品/语言去重得到 **{cover['distinct_konami_cids']} 个 KONAMI CID**。",'',
        '按用户最终范围，仅使用英文、日文、中文交叉验证：日英官方目录提供历史产品及收录关系，简体中文官方目录核查此范围内为零，中文名称/效果/规则字段来自本地 MDPro3 zh-CN 卡库。其他语言已有缓存仅保留为研究参考，不纳入本正式主库及统计。全量仅指本次选定官方目录快照，不代表已证明全世界历史地区发行物无遗漏。不同语言发行版本分别计数，表项数不是独立补充包品种数。','',
        '| 官方目录语言/市场 | 范围内产品 | 完整收录 | 独立卡牌 CID | 仅知年份的产品 |',
        '| --- | ---: | ---: | ---: | ---: |']
    labels={'ja':'日文 OCG','en':'英文 TCG','ko':'韩文 OCG','ae':'亚洲英文 OCG','de':'德文 TCG','fr':'法文 TCG','it':'意文 TCG','es':'西文 TCG','pt':'葡文 TCG','cn':'简体中文 OCG'}
    for loc,s in regional.items():lines.append(f"| {labels[loc]} | {s['products']} | {s['complete']} | {s['distinct_cids']} | {s['year_only_in_scope']} |")
    lines+=['','## 产品类别','', '| 标准类别 | 产品发行记录数 |','| --- | ---: |']
    for k,v in sorted(kinds.items()):lines.append(f'| {k} | {v} |')
    lines+=['','预组、铁盒、出版物赠卡、赛事/宣传记录不会被当作普通随机补充包。完整收录是页面身份/行关系完整，不等于预组实体张数、卡片印刷编号或开包概率已核实。',
        f"Special Edition、铁盒、复合套装等官网收录页可能只列专属促销卡，不展开零售包装中的密封补充包。已将 {len(composition)} 条可能涉及零售组成的发行记录标入 `product_composition_review.csv`；这些复合零售品的嵌套产品组成未核实，不能称作实物盒内全部卡片概率空间。普通补充包的收录与零售复合品组成应分开验收。",'',
        '## 映射覆盖','', '| CID → MDPro3 状态 | 卡牌数 |','| --- | ---: |']
    for k,v in sorted(mapping['status_counts'].items()):lines.append(f'| {k} | {v} |')
    lines+=['',mapping['verified_meaning'],'',
        f"已验证映射的本地 MDPro3 卡片中，连接怪兽类型数量为 **{link_count}**。未确认项保存在 `card_passcode_mapping.csv` 和 `mapping_candidates.csv`，不得以名称候选直接作为内核 ID。",'',
        '## 与既有 MDPro3 日期集合的差异','',
        f"既有 MDPro3 单日期筛选得到 {pool['md_single_date_pool_count']} 个密码；官方日本集合已确认 {pool['official_jp_verified_passcodes']} 个密码、尚有 {pool['official_jp_unmapped_cids']} 个 CID 未确认映射；官方日英联合集合已确认 {pool['official_global_verified_passcodes']} 个密码、尚有 {pool['official_global_unmapped_cids']} 个 CID 未确认映射。",'',
        f"按可信密码比较，官方日本集合独有 {pool['official_jp_verified_only_vs_md']} 个、MD 集合相对日本可信集合独有 {pool['md_only_vs_official_jp_verified']} 个；官方日英联合集合独有 {pool['official_global_verified_only_vs_md']} 个、MD 集合相对日英可信集合独有 {pool['md_only_vs_official_global_verified']} 个。未映射 CID 可能对应 MD 独有密码，不能据此自动删除旧卡。详见 `pool_differences.csv`（其中 global/all_locales 旧字段名指本次所选主库范围，不指全球所有语言）。",'',
        f"日英官方源页中检测到 {usage['explicit_no_duel_notice_cids']} 个 CID 明确提示不能用于正式决斗；它们仍保留为历史收藏收录记录，游戏卡池须另作用途选择。未检测到提示不等于确认正式比赛可用。详见 `card_usage_flags.csv` 和证据表。",'',
        f"未确认内核号的 CID 集合与明确不可用于正式决斗的 CID 集合{'完全一致' if unmapped==explicit_restricted else '尚有差异，须检查'}（双方差集 {len(unmapped^explicit_restricted)} 个）。这是本次源资料核对结果，不能把未映射的奖品/纪念卡凭空赋予效果脚本。",'',
        '## 完整性核验','',
        f"- 索引原始 `t_row` 与 catalog 导出逐语言核对；未解析行数合计：{sum(s['index_parser_skipped_rows'] for s in verify['index_audits'].values())}。",
        f"- 已核对产品缓存：{verify['product_cache_provenance_checked']}；缓存/产品身份/语言问题：{len(verify['cache_failures'])}。",
        f"- 官网总行数不一致或未完成产品：{len(verify['bad_product_counts'])}。",
        f"- 重复产品主键：{len(verify['duplicate_product_primary_keys'])}；重复产品—行次主键：{len(verify['duplicate_membership_ordinal_keys'])}。",
        f"- 同一产品内同 CID 多行记录：{len(verify['same_card_multiple_rows_within_product'])} 组，原样保留，不推断实体数量。",
        f"- 标题待人工复核：{len(verify['title_review'])}；SQLite 完整性：{verify['sqlite_integrity']}。",
        f"- 自动核验总结果：{'通过' if verify['passed'] else '存在待处理项目，详见 verification.json'}。",'',
        '## 仍需补充的资料/边界','',
        '- 官方现行目录可能遗漏早期、撤下或特定地区产品；全量结论仅限选定日英官方目录快照，中文目录核查零项。',
        '- 语言目录不能证明美国、欧洲、澳洲等所有国家的首发日差异；请保留地区/语言源记录。',
        '- 本轮未下载包装图或卡图。后续资源清单需要逐产品补官方包装来源，卡面/透明立绘从 MDPro3 资源清单核验。',
        '- 逐卡收录编号、实体预组同卡张数、卡包封入概率未取得时保持空值，不编造。',
        '- 复合零售品内含的密封补充包/预组组件尚未展开；官方仅列专属促销卡时不会擅自补成整盒收录。',
        '- 现行数据库名称和当前 MDPro3 脚本/勘误不等于 2017 年规则文本快照。',
        '- 同产品的不同语言版、同卡不同印刷与重印关系尚未自动合并；此处保留所有独立源记录。',
        '- 这些源数据尚未写入模组，旧物品/旧卡包兼容映射和投放配置由后续实施任务另行生成。','',
        '复现和字段说明见 `README-字段与复现.md`；逐页证据见 `cache/` 的 HTML gzip 与元数据。']
    if cover['gaps']:
        lines+=['','## 未完成产品页','']
        lines.extend('- '+json.dumps(g,ensure_ascii=False) for g in cover['gaps'])
    (ROOT/'盘点覆盖与缺口报告.md').write_text('\n'.join(lines)+'\n','utf-8')
    manifest={'created_at':datetime.now(timezone.utc).isoformat(),'files':{}}
    for path in sorted(ROOT.iterdir()):
        if path.is_file() and path.name not in ['manifest.json','crawl.log']:
            manifest['files'][path.name]={'size':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    (ROOT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),'utf-8')
    print('Final report generated; verified:',verify['passed'],'products:',cover['product_count'],'cards:',cover['distinct_konami_cids'],'mapped:',mapping['status_counts'])

if __name__=='__main__':main()
