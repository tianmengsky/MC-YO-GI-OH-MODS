# 连接时代前产品与收录卡源数据

本目录用于保存源资料，不能直接当作模组的开包掉落配置。边界沿用项目约定：发行日期 **不晚于 2017-03-24**。产品按官方语言目录和发行记录分别保存；相同卡牌跨包重印保留多对多关系。补充包、预组、铁盒、赛事、出版物赠卡等分开分类。

按用户最终范围，正式主库仅使用英文、日文、中文交叉验证：KONAMI 日文 OCG 和英文 TCG 官方目录提供产品及历史收录关系；简体中文官方目录截至范围内为零；中文名称、效果与规则字段来自本地 MDPro3 `zh-CN/cards.cdb`。已采集的其他语言缓存和 `catalog_*.csv` 保留为研究参考，不纳入正式主库和完成度统计。完成度仅相对于所选官方目录快照，不能证明官方从未收录或已撤下的历史产品全部存在，也不等于所有国家独立发行日目录。

## 主要文件

| 文件 | 用途 |
| --- | --- |
| `inventory.sqlite` | 可查询的数据主库；基础三表和映射表 |
| `products.csv` | 截止范围内产品及逐页计数、来源、抓取状态 |
| `cards.csv` | 按 KONAMI CID 去重的卡牌及可取得的各语言官方名称 |
| `product_cards.csv` | 产品与卡牌多对多收录关系；保留页面行次与稀有度标记 |
| `product_composition_review.csv` | Special Edition/铁盒/套装等零售组成待核实清单；官网页可能只列专属促销卡，不含盒内密封补充包的展开收录 |
| `card_passcode_mapping.csv` | CID 到 MDPro3 卡号的证据与验证状态 |
| `mapping_candidates.csv` | 未确认映射的候选，不允许直接投入游戏 |
| `pool_differences.csv` / `pool_comparison.json` | 官方 JP/全部语言可信密码集合与 MDPro3 原 8201 日期集合的差集；只供复核，不自动变更卡池 |
| `card_usage_flags.csv` / `card_usage_notice_evidence.csv` | 官方日英原页明确的不可用于正式决斗提示；未检测到提示不等于确认合法 |
| `engine_cards.csv` | 已验证映射的 MDPro3 规则字段与中文名称，尚须模组侧复核脚本/卡图等资源 |
| `catalog_*.csv` | 各语言整个索引的解析记录，含截止后排除项及日期不确定项 |
| `coverage.json` | 逐产品收录抓取完成度、失败与计数差异 |
| `verification.json` | 索引行覆盖、重复键、缓存哈希、页面语言/产品身份核验 |
| `mapping_coverage.json` | 映射覆盖率与本地卡库哈希 |
| `region_catalog_coverage.json` | 各语言目录的收录覆盖汇总 |
| `盘点覆盖与缺口报告.md` | 面向人工复核的最终统计与限制 |
| `cache/` | gzip 原始 HTML、来源 URL、抓取时间、原文 SHA-256；可离线复核 |

CSV 使用带 BOM 的 UTF-8，便于 Windows/Excel 查看。SQLite 的卡号是整数；导出给游戏或制作卡面时，卡片密码显示可补齐八位。不要给产品 PID 或 KONAMI CID 做八位密码格式化。

`pool_comparison.json` 与差集 CSV 为保持脚本兼容，保留旧字段名 `global` / `all_locales`；本交付中它们只代表当前正式主库的日英联合集合，不代表全球所有语言。`regional_product_gaps.csv` 是之前宽范围研究留下的来源记录，已用状态标记其他语言为范围外参考，不能作为本轮未完成任务清单。

## 基础三表字段

`products`：

- `product_id`：本项目稳定源键，格式 `locale:konami_pid`。不同语言发行记录独立。
- `locale` / `market`：官方请求语言及目录市场标记。`ae` 是亚洲英文 OCG，`en` 是英文 TCG，二者不可合并。
- `konami_pid`：官方产品标识。与 MDPro3 `pack.db.pack_id` 的卡片收录编号不同。
- `name`、`category_official`、`product_kind`：原始官方品名/分类及标准化类别。分类不代表开包抽取机制。
- `release_date`：精确索引日期；未知时为空。`release_date_raw` 始终保留原值。
- `index_year`：官方目录分类年份。`date_quality=year_only` 表示官网用了 `1990-01-01` 占位，仅能按官方分类年份判定在范围内，不能把占位值当发行日。
- `cutoff_evidence`：精确日期或分类年份提供的边界依据。年份不晚于 2016 的占位日期记录可纳入；2017 年但无月日的记录不能自动纳入。
- `source_url`、`index_source_url`、`index_retrieved_at`、`retrieved_at`、`html_sha256`：可追溯原页与抓取证据。
- `expected_card_rows`：官方产品页显示的总卡牌行数。
- `parsed_card_rows` / `distinct_cids`：解析行数与去重卡牌数；二者均不代表实体套牌总张数。
- `status`：只有 `complete` 表示非空收录并与官网总行数相等；其余状态是待处理缺口。

`cards`：

- `konami_cid`：官方卡牌身份，用于跨语言、跨产品合并卡牌。
- `name_ja` / `name_en` / `name_ko` / `name_ae` / `name_de` / `name_fr` / `name_it` / `name_es` / `name_pt` / `name_cn`：产品收录页可取得的当前官方名称。某语言没有历史发行时允许为空。
- **CID 不是八位卡片密码**，不得拿 CID 直接寻找 `c<id>.lua` 或卡图。

`product_cards`：

- `product_id`、`row_ordinal`：原页的产品与卡片行次，组成行主键。
- `konami_cid`、`card_name`、`locale`：该发行记录中出现的官方卡牌身份和名称。
- `rarity_ids`：官方页面 CSS 稀有度标记 ID，原样保存；须按对应市场的官方稀有度图例解释，不是抽中概率。
- `collection_number`：本轮原页模式未可靠提供逐卡印刷编号，留空。不能把行次冒充 `LOB-001` 之类收录编号。
- `physical_quantity`：实体预组中同卡张数未知，留空。不能把去重卡种数当牌组总张数。
- `source_url`、`source_retrieved_at`：该条关系的直接来源证据。

## CID 与 MDPro3 卡号映射

官方产品页没有可直接用于内核的密码。本目录另保存 YGOPRODeck `cardinfo.php?misc=yes` 的原始 JSON 压缩缓存，以同条记录中的 `misc_info.konami_id` 和 `id` / `card_images[].id` 建立显式双标识关系，然后要求资源 ID 存在于本地 MDPro3 `datas`，并用官方日文或英文名称与对应本地卡库名称交叉核对。

API 的主 `id` 有时采用异画资源号，不能直接等同规范内核密码。例如黑魔术师的 API 主号 `46986420`，在本地 `datas.alias` 指向 `46986414`。本工具仅当源号和 alias 目标的中文名称、完整当前中文效果描述、类型/攻守/等级/种族/属性均相同时才跟随 alias，避免将规则视同名的不同卡误合并。映射表保留 `api_primary_id`、`api_artwork_ids`、`mdpro3_resource_id`、`engine_passcode` 及 `alias_resolution` 的全部证据。

`verified_two_sources` 表示上述联合核验通过。它是有交叉证据的第三方映射，不能称作 KONAMI 官方密码接口。`api_identifier_needs_name_review`、`conflicting_api_passcodes`、`api_identifier_not_in_mdpro3` 等不能自动投入游戏。仅凭名称匹配得到的结果全部放进候选表，状态 `candidate_not_verified`。

本地 MDPro3 数据来源路径和 SHA-256 见 `mapping_coverage.json`。`engine_cards` 保存的是当前安装版本的字段，不是 2017 年效果文本历史快照。使用现代脚本/勘误还是历史脚本，需要由游戏实现计划另外明确。

中文名称和当前效果描述来自本地中文 `cards.cdb.texts`，映射表中的 `name_zh_cn` / `description_zh_cn` 与官方原文列分开保存。未确认卡号的卡牌不猜译。

## 示例查询

按官方产品查收录卡、中文名称与规范密码（日本 MACR 为例）：

```sql
SELECT p.name AS product, pc.row_ordinal, pc.konami_cid,
       pc.card_name AS official_name, m.engine_passcode,
       m.name_zh_cn, m.description_zh_cn, m.status AS mapping_status
FROM products p
JOIN product_cards pc ON pc.product_id = p.product_id
LEFT JOIN card_passcode_mapping m ON m.konami_cid = pc.konami_cid
WHERE p.product_id = 'ja:1119003'
ORDER BY pc.row_ordinal;
```

按规范内核密码查询所有历史重印产品（青眼白龙为例）：

```sql
SELECT p.market, p.name, p.release_date, p.date_quality,
       pc.rarity_ids, p.source_url
FROM card_passcode_mapping m
JOIN product_cards pc ON pc.konami_cid = m.konami_cid
JOIN products p ON p.product_id = pc.product_id
WHERE m.engine_passcode = 89631139
  AND m.status = 'verified_two_sources'
ORDER BY p.release_date, p.product_id, pc.row_ordinal;
```

## 复现

Python 3 标准库即可运行，不需要额外安装包。按顺序运行：

```powershell
$python = 'C:\Users\30481\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
& $python .\collect_inventory.py --locales ja en cn
& $python .\build_mapping.py
& $python .\compare_pools.py
& $python .\extract_usage_notices.py
& $python .\verify_inventory.py
& $python .\finalize_report.py
```

采集默认范围为 `ja en cn`，最多 3 个并行请求，全局请求间隔不短于 0.5 秒。已缓存页面自动复用；每 50 个产品导出一次检查点。日英 1450 页及中文目录均已有缓存，本轮重建无需继续补采其他语言。不要一边采集一边运行映射脚本，因为后续检查点会重建基础 SQLite 文件表。离线复核只需运行 `verify_inventory.py`，其按 `coverage.json` 选定目录核验。

第三方映射 JSON 初次取得方式：GET `https://db.ygoprodeck.com/api/v7/cardinfo.php?misc=yes`，保存到本目录 `ygoprodeck-cardinfo-misc.json` 后运行 `build_mapping.py`；映射脚本自动建立 gzip 原始缓存与元数据。此步骤只读取 JSON，从未请求 JSON 内的卡图 URL。

如官方返回访问拒绝或限流，应记录缺口并停止继续请求，不能换身份或绕过限制。需要刷新特定缓存时，先保留旧缓存作为历史快照，再单独重新取页。

## 本轮不生成的配置

本轮不下载卡图或包装图，不修改任何模组 JAR，不改旧存档物品，不生成随机开包概率或预组实体张数，不把官方产品直接全部变成可抽取卡包。源模组旧包的兼容映射、产品投放方式和资源风格适配均属于后续实现阶段。

`status=complete` 的严格含义是“该官方产品页面的收录列表完整抓取且计数一致”。它不证明复合零售包装的嵌套组成已展开。例如 Special Edition 页面列 4 张独有促销卡，而盒内还可能有若干密封补充包；这些补充包作为自己的产品单独盘点，盒内数量/版本关联需要额外来源。铁盒、套装等也按这一边界处理。
