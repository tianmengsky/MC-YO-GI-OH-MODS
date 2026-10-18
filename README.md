# 游戏王模组独立工作区

工作区：`F:\Minecarft\游戏王mod工作区`。开发与原 Minecraft 安装隔离，迁移完成状态和逐文件校验以 `migration/` 中的清单与核验报告为准。

## 目录

| 目录 | 内容 |
| --- | --- |
| `_ygo-analysis/execution-plan/` | 详细任务步骤书、基线记录、独立数据审计 |
| `_ygo-analysis/source-data/pack-inventory/` | 日英官方产品与卡片关系、中文卡库映射、SQLite、采集缓存及复现脚本 |
| `_ygo-analysis/compatibility-plan/` | 兼容扩展方案及早期官方来源研究 |
| `_ygo-analysis/base/`、`fix/` | 原模组及交互修复模组的提取资料 |
| `docs/` | 初步诊断与目标清单；属于历史交接记录，技术结论以较新的步骤书和 ABI 核查为准 |
| `mdpro3-p0/` | 历史原生引擎探测、失败日志及 C# ABI 提取；P0 尚未通过 |
| `inputs/mods/` | 三个相关已安装模组 JAR 的校验副本 |
| `inputs/mdpro3/` | MDPro3 开发输入快照：Data、Picture、Managed、Plugins、expansions、Deck、Puzzle |
| `archives/acl-recovery/` | 原始权限恢复证据，保持原字节及恢复目标路径 |
| `migration/` | 来源与目标映射、SHA256 清单、路径改写前备份及最终核验 |
| `addon/` | 后续附属模组工程位置，尚未创建 Gradle 工程 |
| `generated/` | 后续生成的资源与中间产物 |
| `run-client/`、`run-server/` | 后续隔离测试目录，尚未安装测试环境 |

MDPro3 快照不是完整可运行的 Unity 程序，也不是其源码仓库；后续引擎源码、许可证和构建方式仍需核实。图片为已有完整输入快照，尚未按年代筛选或制成模组卡面。游戏和 MDPro3 原程序均保留原位。

## 当前基线与下一步

保留全部旧物品、卡片、卡包、注册身份和存档，以附属模组接入 MDPro3 所用引擎和 Lua。目标截止 2017-03-24、连接时代前、大师规则 3。正式源库采用日文、英文官方目录及中文本地卡库交叉验证；其他语言缓存仅作历史参考。

现有源数据包含 1,450 条产品/语言发行记录、36,091 条产品—卡片关系、8,261 个官方 CID，其中 8,231 个已映射内核密码。尚未解决的包装图、零售复合品组成、预组实体数量、封入概率等边界详见源库报告。

本次只完成工作区隔离。后续从详细步骤书的原生引擎 P0 验证开始；旧探测脚本不代表可用集成，不自动运行。当前没有向游戏部署新模组。

迁移后的主要入口：

- `_ygo-analysis/execution-plan/游戏王扩展-详细任务计划步骤书-v1.md`
- `_ygo-analysis/source-data/pack-inventory/README-字段与复现.md`
- `_ygo-analysis/source-data/pack-inventory/盘点覆盖与缺口报告.md`
- `migration/verification.json`（迁移完成后生成）

如在 Codex 中继续开发，请将项目目录设置为本工作区；本次文件迁移不会自动更改聊天所绑定的项目根目录。

## 版本管理

本工作区由 Git 管理，远端为 <https://github.com/tianmengsky/MC-YO-GI-OH-MODS>（分支 `main`）。

入库内容：`addon/` 附属模组工程（源码、Gradle 配置、`tools/` 脚本、`content/` 数据、卡面资源）、`docs/`、`migration/` 文本与清单、`mdpro3-p0/` 历史探测笔记与日志、`_ygo-analysis/` 的执行计划、兼容方案与源库复现脚本。

不入库内容（保留本地，见 `.gitignore`）：`inputs/`、`MCYoGiOh/`、`generated/`、`run-*/`、`archives/`、`logs/`、`_ygo-analysis/base|fix/` 提取件、源库 SQLite、抓取缓存与 23 MB 级 JSON 快照、Gradle `build/` 与 `.gradle/`、JVM `*.hprof`、`releases/` 中的发布 JAR。这些可按 `inputs/mods` 中的 JAR 或 `_ygo-analysis/source-data/pack-inventory/` 的脚本重新生成。

### 发布件

当前发布件为 `releases/0.6.33-alpha/yugioh-mdpro-integrated-0.6.33-alpha.jar`，SHA-256 `6662d14a52316b1d6f59235937fd40f804e40ffbe00ce23aad0437d9b97440ee`，与工作区实例 `MCYoGiOh` 中已部署的 JAR 逐字节一致。同目录 `SHA256SUMS.txt` 记录该值；Gradle 构建产物仍输出到 `addon/build/libs/`，两种位置以 SHA-256 校验为准。

### 磁盘清理（2026-10-18）

已删除 82,352 MB（80.4 GB，按 1 GB = 1024 MB 计）共 48,329 个历史文件：`addon/build/`（42,612 MB，中间产物与 68 个旧版 JAR）、`generated/deployment-backup/`（27,924 MB，49 个 0.1–0.6.32 部署备份）、`generated/release-snapshot/`（8,908 MB，20 个旧发行 JAR）、`generated/tmp/`（707 MB），以及 `generated/gui-redesign-0.6.27/`、`gui-refinement-0.6.28/`、`gui-crash-20261017/` 内的旧版 JAR（各 733 MB，截图与日志证据保留）。工作区由约 93.8 GB 降至约 14.5 GB。

`run-client`、`run-debug` 的旧版 JAR 已替换为 0.6.33-alpha（复制后按 SHA-256 校验通过再删除旧件），`run-server` 无模组 JAR 无需处理。

保留：`releases/` 当前发布件、`MCYoGiOh` 实例已部署件、`inputs/` 冻结输入、`generated/gradle-home`（离线构建缓存）、`generated/deps`、`pack-art`、`content` 等打包依赖，以及 `run-*` 隔离运行环境。旧版 JAR 可依据 Git 历史与 `addon/tools/package_integrated.py` 重新构建。


常用操作：

```powershell
git status
git add -A
git commit -m "说明"
git push origin main
```

`.gitattributes` 设为 `* -text`，不做换行符与编码改写，中英日混合及历史编码文件按原字节保存。
