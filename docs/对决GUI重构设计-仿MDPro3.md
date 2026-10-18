# 对决 GUI 重构设计（仿 MDPro3）

依据：`generated/mdpro3-ui-source/分析-GameCard交互.md`、`分析-OcgCore流程.md`（上游 SethPDA/MDPro3 源码，逐条带 文件:行号）。
实现位置：`addon/src/main/java/dev/codex/yugiohmdpro/client/`、`.../client/ui/`（附属模组；不改原模组 inputs）。

## 1. 目标与边界

- 只重做**交互层**：连锁、攻击、召唤/盖放、发动效果、表示形式、区域与卡片选择。棋盘/卡图/立体投影/LP 条仍是原模组渲染（用户未要求改画面）。
- 保持原模组全部旧物品、卡牌、卡包、注册名与存档兼容（附属模组 + mixin，不替换原类）。
- 规则仍是大师规则 3、卡池 2017-03-24。

## 2. 从 MDPro3 照搬的交互规则（有出处）

| 规则 | 出处 | 本实现 |
|---|---|---|
| 询问-应答串行：一次只问一个问题 | CoreWrapper.cs:1056-1062 | 已有（DuelBridge.Game.prompt） |
| 单击卡片**不提交动作**，只显示详情并展开该卡的动作 | GameCard.cs:141-156 | 单击卡 → 卡详情 + 卡旁动作菜单（第二次点击才提交） |
| 动作按钮顺序按 ButtonType 枚举排序 | DuelButton.cs:11-26、GameCard.cs:2757 | `ChoiceModel.actionRank`：发动→攻击→变更表示→特召→召唤→盖魔陷→盖怪 |
| 可用性完全由内核过滤，前端不猜灰显 | OcgCore.cs:5249-5346 | 只列出 choices 里真实存在的动作 |
| 有 Activate/SpSummon 等 → 黄色高亮；否则蓝色 | GameCard.cs:2770-2783 | 场上/手牌可动卡：黄框=可发动/可特召，蓝框=可选择 |
| 多选：点卡即选中，提示 `n/max`，确认/取消两个固定位按钮 | OcgCore.cs:7115、7119-7139 | 底部栏：左「不选/取消」，右「确定 (n)」；选中卡加金框 + 顺序角标 |
| 候选全在场→场上点选，否则→弹窗列表 | OcgCore.cs:5614-5624 | `allOnBoard` 判定；否则自动打开卡片列表（pick 模式） |
| 多选达上限自动提交 | OcgCore.cs:7104/7090 | 仅当 min==max（强制张数）或计数/等级条件已满足时自动提交 |
| 连锁：可放弃→「是否连锁？」；强制→「请选择效果发动。」 | OcgCore.cs:5555-5567 | 连锁弹窗标题/「不连锁」按钮随 cancelable 变化 |
| 连锁链 ≥2 才叠堆显示并带序号 | OcgCore.cs:7845、7852 | 侧栏连锁栈（序号 + 卡名），链长 <2 不显示 |
| 放置区域：直接点高亮格子，不弹窗 | OcgCore.cs:5841-5873 | 候选区域绿框脉动，点击即答 |
| 表示形式：2～3 个按钮，不可取消；单一取值自动应答 | OcgCore.cs:5874-5906、7255 | 中央弹窗只列允许的表示；服务器端单一取值直接代答 |
| SelectOption 只有 1 项时自动回 0 | OcgCore.cs:5834-5839 | 服务器端自动代答 |
| 宣言卡名：先输入关键字再选结果 | OcgCore.cs:5233-1249 | 弹窗内 EditBox + 结果列表；只在内核允许的卡号里搜 |
| 右键/Esc = 关闭详情或走取消 | OcgCore.cs:322-328 | 右键/Esc：依次关弹窗→关菜单→取消当前选择 |
| 答案格式：动作 `(组号<<16)+序号`、选择用候选下标 | OcgCore.cs:5263-5341、7160-7178 | 沿用服务器 CorePrompt.response，客户端只发下标 |

## 3. 有意偏离 MDPro3 的三处（2D GUI 适配，需在报告中标明）

1. **动作按钮落点**：MDPro3 是一排水平按钮压在卡上方（间距 160px，DuelButton.cs:193）。原棋盘行距 47px、卡高 44px，只有 3px 间隙，水平排在 2D 里必然压住相邻卡。改为**卡旁锚定的纵向菜单**（同名动作、同顺序、同样由内核过滤），语义一致、落点适配。
2. **达上限自动提交**：仅在 min==max（数量被固定）时自动提交；min<max（例如"选 1～5 张"）保留玩家的确定按钮，避免"选到 5 张就被替玩家提交"。
3. **浏览列表**：MDPro3 是单列、行高 140、无分页无搜索（CardList.cs:77）。2D 里棋盘高度只有 234px，改为一屏 8 列的卡图网格 + 分页 + 搜索框，并支持 pick 模式选中。

## 4. 服务端新增的状态字段（DuelBridge.snapshot）

只增不改，旧字段全部保留：

- `phase`(int)、`turn`(int)、`turnPlayer`(int)：阶段按钮与回合显示。
- `chain`(ListTag)：本连锁已发动的卡（`index` 序号 + `code`），来自 MSG_CHAINING(70)，MSG_CHAIN_END(74) 与新回合清空。
- 每个 choice 增加：`action`（idle 命令的动作组号 0..5 / 引擎命令值）、`command`（是否引擎指令而非卡）、`note`（结构化动词）、`detail`（效果文本）、`weight`（素材等级 / 指示物容量）。
- `target`、`mode`（SelectSum 的目标合计与溢出模式；SelectCounter 的目标数量）。

客户端**不再解析任何人类可读 label**：卡名走 `DuelCatalog.name(code)`，动作名走 `note`。

服务端自动代答（减少无效点击，与 MDPro3 一致）：
- SelectOption(14) 只有 1 项 → 直接回 0。
- SelectPosition(19) 只允许 1 种表示形式 → 直接回该值。
- SelectCard(15) min==1 且只有 1 个候选 → 直接选它。
- SelectPlace(18) 引擎 min==0 → 标记为可取消（对应 MDPro3「min==0 才允许取消」）。

## 5. 版式（虚拟坐标，随窗口只缩放不重排）

虚拟空间固定约 690×486（`scale = min(1, (width-8)/690, (height-10)/(uiH+14))`），因此立绘（占两侧各 145px，DuelPuppets.java:119-129）与棋盘永不重叠。

```
y=top            ┌ 棋盘 228×234 ┐ ┌ 侧栏 86 ┐
y=top+14..173    │ 4 行卡区      │ │ 回合/阶段 │
y=top+234        └──────────────┘ │ 阶段按钮  │
y=top+242..280   手牌行            │ 提示/连锁栈│
y=top+286..302   底部操作栏        │ 投降      │
```

- 阶段按钮：抽卡/准备/主1/战斗/主2/结束，一格一行；只有内核给出对应命令时「战斗/主2/结束」可点，当前阶段金底。
- 底部栏：左侧状态与提示（`已选 n/max`、等级合计等），左侧按钮「取消/不选」，右侧按钮「确定 (n)」。
- 卡旁动作菜单：右侧优先，越界翻转并夹紧到 UI 边界；标题=卡名，含卡图预览，每行 = 动作角标 + 动作名（发动行第二行显示效果文本）。
- 中央弹窗：是/否、效果选择、表示形式、种族/属性/数字宣言、猜拳、连锁列表。
- 卡片列表：8 列网格 + 分页 + 搜索；pick 模式显示选中顺序角标，查看模式点击看详情。
- 悬停/点击卡片 → 右侧或左侧空白处显示放大卡面 + 攻守/种类/效果文本。

## 6. 验证方式

1. `compileJava` 通过。
2. 新增 `-PuiSmoke` 视觉夹具（`DuelUiSmoke`）：用合成状态标签驱动真实 `NativeDuelScreen`，逐帧模拟点击并截图，断言：
   - 卡菜单能打开且行数 = 该卡可选动作数；
   - 多选选中/取消/确定计数正确，达上限行为符合上表；
   - 连锁弹窗在 forced 时无「不连锁」按钮、非 forced 时有一按即回 -1；
   - 放置区域候选格子数 = choices 中该 location 的条目数；
   - 表示形式弹窗按钮数 = 允许的位；
   - 阶段按钮可用性 = 命令条目；
   - 全程无异常，并输出截图供人工核对。
3. 原有 `visualSmoke`、6 个 GameTest、nativeSmoke 回归。
4. 部署到游戏需用户另行明确指示。

## 7. 未验证 / 风险

- 冻结内核的 MSG_SELECT_UNSELECT_CARD(26) 应答语义沿用既有服务器实现，未改动；本轮只改其界面。
- `weight` 对 SelectSum 的高低两值（`low/high`）只用于提示与按钮可用性；最终判据仍是内核。
- MSG_CHAINING(70) 只取 code（既有代码已在真实对局中验证该偏移），连锁栈的归属方/区域不做展示，避免误读。

## 8. 实现清单

新增：

| 文件 | 作用 |
|---|---|
| `client/ui/DuelLayout.java` | 棋盘几何、格子命中、区域/阶段/表示形式名称 |
| `client/ui/ChoiceModel.java` | 解析服务端状态为结构化交互模型（kind、动作组、效果文本、连锁链、合计目标） |
| `client/ui/DuelUi.java` | 主题色与绘制原语（面板/按钮/行/高亮框/角标/换行）、带标签的可点控件 |
| `client/ui/DuelWidgets.java` | 卡旁锚定菜单、中央弹窗（是/否、效果、表示形式、连锁列表） |
| `visual/client/DuelUiSmoke.java` | 仅开发用交互夹具（`-PuiSmoke`） |

改写：`client/NativeDuelScreen.java`（交互层全部替换；棋盘渲染仍继承原 `DuelScreen`）。

引擎侧小改：`engine/CorePrompt.java`（Entry 增加 `value/note/detail`，SelectPlace 的 min==0 可取消，宣言卡名读取允许卡号）、`duel/DuelBridge.java`（快照新增 phase/turn/chain/action/command/note/detail/weight/target/mode、单一答案自动代答、按允许卡号搜索）、`engine/DuelCatalog.java`（`search(String, Set)` 重载）。

## 9. 验证结果（实际运行）

`-PuiSmoke runClient`：31 步、全部断言通过（`generated/ui-smoke-result.txt` = PASS），产出 9 张截图：

| 截图 | 覆盖的交互 |
|---|---|
| `ui-board.png` | 主要阶段：可动卡黄/蓝框、阶段按钮可用性、底栏提示、整理手牌 |
| `ui-card-menu.png` | 单击怪兽 → 卡旁动作菜单（发动 + 效果文本） |
| `ui-hand-menu.png` | 单击手牌 → 召唤/盖放两个动作，按 MDPro3 顺序排序 |
| `ui-chain.png` | 连锁弹窗（2 个可连锁效果 + 不连锁）、侧栏连锁链 |
| `ui-yesno.png` | 是否发动弹窗（发动为主按钮） |
| `ui-pick.png` / `ui-pick-selected.png` | 墓地多选列表、选中顺序角标、确定/取消/隐藏 |
| `ui-place.png` | 放置区域高亮与点击即答（5 个合法怪兽区） |
| `ui-declare.png` | 宣言卡名的结果列表与搜索入口 |

夹具抓到的两个真实缺陷（已修）：

1. **pick 模式下点击卡片列表的卡片不会选中** —— 鼠标处理只查了控件，漏掉网格单元；现改为按格子命中并走与场上点击相同的 `choose()` 路径。
2. **宣言卡名只回了下标、没回卡号** —— 列表点击绕过了 `choose()`，被自动提交成 `[i]`；修正后回 `[i]|<code>`。

另修：侧栏与原模组右侧灵摆区/除外堆重叠（`SIDE_GAP` 由 8 改为 `PENDULUM_OUT+8`，缩放预算 690→720）；列表底部按钮互相重叠；弹窗开启时底栏仍显示为可用的按钮；阶段箭头在不可点时也显示。

## 10. 其它回归（本次实际运行）

| 检查 | 结果 | 证据 |
|---|---|---|
| `compileJava` | 通过 | `generated/ui-compile.log`（errors=0） |
| `-PuiSmoke runClient` | PASS（31 步断言 + 原视觉 2 项断言） | `generated/ui-smoke-result.txt` |
| `nativeSmoke` | PASS（ABI=76、MR3、turns=6、requests=20） | `generated/ui-regression-engine.log` |
| `gameplaySmoke` | PASS（148 事件、49 询问、7 机制） | 同上 |
| `historicalPoolSmoke` | PASS（8231 张全部初始化；鹰身变体规则名永久保留） | 同上 |
| `runGameTestServer` | PASS（All 10 required tests passed） | `generated/ui-regression-gametest.log` |
| `-PvisualSmoke runClient` | **失败，但不是本次改动** | 并行开发的内容侧在 `ClientVisualSmoke.java:55-59` 新增“所有导入卡的中文译名必须已加载”的前置断言，`lava_golem` 未加载即抛错；属于内容/翻译工作，与交互层无关 |

为把“原模组视觉未被破坏”变成可归属的证据，`DuelUiSmoke` 在最后一步额外断言：

- `DuelScreenHolograms.panelsLastFrame() >= 2` —— 两张怪兽的立体投影仍在绘制；
- `DuelPuppets.puppetsLastFrame() == 2` —— 双方动画立绘仍在绘制。

两项均通过。

## 11. 第二轮：用户实测反馈的两个缺陷（已修，0.6.3-alpha）

用户截图反馈两点：右侧有"奇怪的长线条"；表示形式（攻击/守备）弹窗里没有任何按钮、无法选择。两处都是我的实现缺陷，用像素扫描 + 夹具复现定位并修复。

### 11.1 长线条：把"两个角点"当成"宽高"传给了 `outline`

`DuelUi.outline(g,x,y,w,h,color)` 收的是**宽高**，而 `GuiGraphics.fill` 收的是**两个角点**。`drawSidePanel` 的相位行写成：

```java
g.fill(x+2,y,x+w-2,y+13,fill);                    // 角点，正确
DuelUi.outline(g,x+2,y,x+w-2,y+13,edge);          // 错误：w 被当成 566，h 被当成 y+13
```

于是每一行的边框变成：顶边 `fill(482,y,1048,y+1)`（从面板左缘一直画到屏幕外），底边落在 `y+h-1 = 2*y+12`。六行分别落在虚拟 y=272/300/328/356/384/412，正好对应用户截图里的多条长线。

定位方法：对 1653×1202 截图做逐行扫描，找"超过 430px 的连续同色横线"，读出的颜色 `0x2B3646`/`0x8A6A24`/`0x5B7BA6` 正是我相位行的禁用/当前/可用边框色，y 值与 `2*y+12` 完全吻合。

修复：新增角点语义的 `DuelUi.box(g,x1,y1,x2,y2,color)`，相位行改用它，避免两种参数顺序再被混淆。修复后同一扫描只剩底栏自身的上下边框（设计内），长线条清零。

### 11.2 表示形式弹窗没有按钮：弹窗高度没算进选项

`Dialog.layout` 里 `contentH` 只对 `listMode` 与 `grid` 两种模式累加选项高度，而表示形式（kind 19）与猜拳（132）走的是默认按钮网格分支 → `contentH` 只算了 50px 的卡图，`buttonTop` 偏小；`render` 里 `if(ry+16>y+buttonTop) break;` 在第一行就成立，**一个选项都没画**。

修复：默认网格分支同样把选项行计入 `contentH`；没有按钮时不再预留按钮行高度；表示形式改用整行列表（`rows()`），并把选项文案改为来自 label 的 `optionText()`——原来用 `DuelChoices.name` 会显示成怪兽卡名（青眼白龙）而不是"表侧攻击表示"。

### 11.3 本轮新增的夹具覆盖

`-PuiSmoke` 增加表示形式两例（mask 0x5 → 2 行；0xD → 3 行），断言弹窗选项数量、点击第二行回答 choice 1、并截图 `ui-position.png`/`ui-position3.png`；夹具窗口改为与用户相同的 1653×1202，并单独使用 `run-ui/` 作为游戏目录（`run-client/mods` 被并行会话放入了整合包 JAR，其中重复打包 geckolib 会导致开发客户端在构造期崩溃）。

## 12. 构建与部署（0.6.3-alpha，已执行）

并行会话把交付形态改成了**整合包 JAR**（`integratedRelease` 任务 → `python addon/tools/package_integrated.py build`，把 ygostar、yugioh 本体、interaction-fix 以 Forge Jar-in-Jar 嵌套进附属模组 JAR，并校验 outer JAR 的 SHA256）。

| 项 | 值 |
|---|---|
| 版本 | `0.6.3-alpha`（`addon/build.gradle`、`addon/content/integrated-release.json`） |
| 附属产物 | `addon/build/libs/yugioh-mdpro-addon-0.6.3-alpha.jar`，563,525,218 字节，sha256 `d714ff2f55bfce04f1d8a155f710c366289ea5ad1148571ca2ccf3cb644c921e` |
| 整合产物 | `addon/build/libs/yugioh-mdpro-integrated-0.6.3-alpha.jar`，867,164,935 字节，sha256 `97d144486d4e8689a349abf112f306731f07f89218880a96e7f3c640415f6f7b` |
| 已部署 | `MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods/yugioh-mdpro-integrated-0.6.3-alpha.jar`（同哈希；mods 内 yugioh-mdpro 包仅此一个） |
| 旧 0.6.2 备份 | `generated/deployment-backup/integration-0.6.2-alpha/yugioh-mdpro-integrated-0.6.2-alpha.jar`（sha256 `ca554086…4849c0`，与替换前一致） |

校验（本轮全部实跑）：`nativeSmoke` / `gameplaySmoke` / `historicalPoolSmoke` PASS，`runGameTestServer` **All 12 required tests passed**（`generated/ui-checks-0.6.3.log`），`-PuiSmoke` PASS（`generated/ui-smoke-result.txt`）。部署包内容已逐项核对：`DuelUi.box`、`ChoiceModel$Choice.optionText`、`NativeDuelScreen.fixture$layout`、`CorePrompt` 的"offering all four"回退均在 jar 内。

## 13. 第三轮：立绘裁剪、信息面板、ESC、去掉语音房间（0.6.4-alpha）

### 13.1 立绘只保留上半身

fix 模组自己的 `bustPixels` 裁剪在实机里**没有生效**（对它自己的截图量过：对手立绘从头到鞋约 276 虚拟像素全都画了出来）。因此改为在自己的附属模组用 **scissor 硬裁剪**，新增 `mixin/DuelPuppetsMixin`：在 `DuelPuppets.drawOne` 的 HEAD/RETURN 上按 `mine` 参数开/关裁剪，裁剪带取各自 LP 板的下沿——

- 对手（右上）：只保留 `y ≤ 138`；
- 自己（左下）：只保留 `y ≤ virtualHeight - 30`。

这样下半身正好止于自己的生命值面板边缘，读起来像"人物站在面板后面只露上半身"，双腿不再出现。选 scissor 而不是改它的裁剪数学，是因为 scissor 对决胜盘/手套等挂件同样生效，也不会被"模型比预期高"击穿。夹具复验：自己 399..531、对手 30..163 虚拟像素（只含上半身与面板）。

### 13.2 卡面信息移到左上，日志移到右侧信息栏

- 原模组的悬浮 tooltip（MC tooltip 样式、和原生说明重复的那个紫框）由 `DuelScreenMixin` 注入 `renderHoverTooltip` 取消。
- 新增 `drawCardPanel`：固定钉在**屏幕左上角**（MDPro3 的位置），卡图在上、名字/攻守/效果文本在下，全宽排版（避免"ATK 2500 / DEF 2100"被截成"ATK 250"）。面板内容取"当前悬浮的卡"，没有悬浮时保留上一张（sticky），鼠标移动不会闪烁。
- 日志从左上角移到右侧信息栏，画在**日志开关按钮上方**（`drawLogBlock`，最近 7 条 + 标题），默认显示；`renderNativeLog` 变为空实现以免重复。

### 13.3 人性化：子界面里实时看效果

- 卡片列表（pick/查看）：悬浮即把该卡送进左上详情面板（原本就有，现在位置固定更好读）。
- 卡旁动作菜单：悬浮某一行 → 左上显示该动作对应的卡（`PopupMenu.choiceAt`）。
- 弹窗（连锁列表、效果选择、宣言卡名结果、表示形式、猜拳）：悬浮某一行 → 左上显示该卡全文（新增 `Dialog.hoverIndex`）。连锁列表因此可以"一行一行读效果再决定连锁哪张"。

### 13.4 ESC 临时退出对决界面

`NativeDuelScreen` 覆写 `shouldCloseOnEsc()` 返回 true，`onClose()` 记录当前 `revision` 后 `setScreen(null)` 并给玩家一条提示。返回方式两种：

1. 引擎下一次需要你操作（`canAct` 且 `revision` 变化）时自动回到对决界面；
2. 再次使用决斗台（服务端下发 `open=true`）时立即回到对决界面。

`keyPressed` 的 ESC 仍是"逐层关闭"：先关弹窗 → 再关列表 → 再关菜单 → 有可取消询问时取消 → 都没有才退出界面。

### 13.5 去掉语音房间功能

fix 模组带 `ServerVoiceRooms` / `PrivateVoiceRooms` / `DuelVoicechatPlugin`（Simple Voice Chat 集成），且客户端的"私密语音房间"规则**默认是开**。按用户要求从我的附属模组中和：

- `mixin/VoiceRoomMixin`：取消 `ServerVoiceRooms.start(Duel)` 与 `stop(Duel)` → 任何对局都不会创建/加入语音房间，即使日后装了 Simple Voice Chat 也不会；
- `mixin/ClientDuelRulesMixin`：`privateVoiceRoom()` 恒定返回 false、`flipPrivateVoiceRoom()` 变为空操作 → 规则行的值永远是"关"且点不动，发给服务端的值也是 false。

两处注入都写了 `require=0`，万一 fix 模组升级导致签名变化只会跳过并留下 mixin 警告，不会崩游戏。**未改动** `generated/deps/yugioh-interaction-fix-0.103.2-mdpro.jar`（它在 `integrated-release.json` 里有审核过的 SHA256）。

### 13.6 关于"运行的语音播报"

查过实际产物：整合包内 **0 个 .ogg、0 个 sounds.json**；fix 模组内 **20 个 .ogg**，全部是音效（`ui_click`/`card_select`/`draw`/`normal_summon`/`attack`/`damage`/`victory`…），**没有任何语音或 TTS 资源，也没有朗读代码**；语音房间在没有 Simple Voice Chat 时根本不会发声（`enter()` 里有 `rooms != null` 保护）。所以：

- 实机里只会听到那 20 个音效，不会有人声播报；
- 我在跑夹具时会模拟点击，因此会触发 `ui_click`/`card_select` 音效，另外开发客户端自己会放原版标题音乐——这是我能归属的全部声音；如果是**念文字**的人声，它来自游戏之外（例如 DSH 网页端或其他程序），与这个模组无关。

### 13.8 日志换行、手牌悬浮、卡面信息与 MC 原生 tooltip 共存（0.6.6-alpha）

用户实测三点：右侧日志被截断（"回合 1: Duel Bo"）、手牌悬浮不显示信息（要预选发动/召唤才有）、左上卡面信息效果文本显示不全。

1. **日志**：右侧信息栏 `SIDE_W` 86 → **112**，同时缩放除数由固定 720 改为 `720 + (SIDE_W-86)`，虚拟画布宽度不变（仍是 ~750），所以加宽信息栏不会挤压棋盘；日志不再用 `label`（内部会 `plainSubstrByWidth` 截断）而是先 `DuelUi.wrap` 换行，最多取最近 6 条、每条最多 2 行、总行数上限 9，块底固定在日志按钮上方。
2. **`DuelUi.wrap` 断行改进**：换行时若当前行的最后一个空格后面是很短的拉丁词（≤9 字符且空格位置在后 2/3 之后），就把整个词移到下一行，于是 "回合 1：Duel Bot" 断成 "回合 1：Duel" / "Bot" 而不是 "Duel Bo" / "t"；中文无空格仍按字断行。
3. **手牌悬浮即显示信息**：`hoveredCard` 原来只遍历 `cells()`，而 `cells()` 不含手牌，所以手牌悬浮永远拿不到卡 → 新增手牌槽位遍历（`layout.handSlot(i,count)`），现在悬浮手牌立刻把该卡送进左上信息面板，不需要先预选发动/召唤。夹具新增 `fixture$hoverAt` / `fixture$handSlotCentre` 两个观测口断言这一点。
4. **卡面信息与 MC 原生 tooltip 共存**：原模组的"紫框"其实就是 `graphics.renderTooltip(font, stackFor(definition), mx, my)`，即 **MC 原生物品 tooltip**，也是唯一能完整显示长效果文本的渲染器。因此改为共存而不是互斥——
   - 左上信息面板只保留**卡图 + 名字 + 攻守**（紧凑、不截断）；
   - 完整的**效果文本由 MC 原生 tooltip 显示**（跟随鼠标、自动换行）；
   - 只有弹窗/卡片列表打开时（`suppressNativeTooltip()`）才在面板里补上效果文本（最多 10 行），因为那时 `renderHoverTooltip` 本身不会给出该行的卡。

### 13.13 世界里的怪兽立绘：全部改用 2D 立绘、并 100% 不透明（0.6.9-alpha）

用户实测：世界里的悬浮立绘**高度被压扁**、而且是**半透明**的；随后明确要求"去掉全部 3D 建模，只保留 2D 立绘"。

先把事实查清（在**部署包**里查，而不是 `generated/deps` 里的旧 JAR）：

- `assets/yugioh_interaction_fix/textures/cardart/` 有 **1361 张 2D 立绘**（绝大多数是 300×300 的方形画布）；
- `assets/yugioh_interaction_fix/3d/` 有 **293 个 GeckoLib 模型**（`*.geo.json`）。

`InWorldBoardRenderer` 的取用顺序是：**先看有没有 3D 模型**（`Hologram3DPack.of(cardId)`），有就走 3D 网格路径；没有才用 2D 立绘（`HologramArt.of`），再没有才退化成卡图窗口。所以"被压扁"的那张走的是 **3D 网格**路径（网格几何/单位与预期不符），而半透明来自 2D 路径写入顶点时的 **alpha=190**（`InWorldBoardRenderer.holo` 里的 `.color(255,255,255,190)`）。

修复（两个都很小、都不引用 MC 成员，避免 remap 风险）：

- `mixin/Hologram3DPackMixin`：`Hologram3DPack.of` 恒返回 null → **所有卡都走 2D 立绘**，3D 网格路径再也进不去。它只有一个调用点（世界立绘），决斗盘用的是另一套 `DuelDiskPack`，不受影响。
- `mixin/InWorldBoardRendererMixin`：`@ModifyConstant` 把 `holo` 里的 `190` 改成 `255` → 立绘完全不透明（纹理自身的透明边仍会裁出轮廓）。

验证：夹具新增断言 `Hologram3DPack.of("7_colored_fish")==null`（该卡在部署包里确实有 3D 模型，没钩子就会返回非 null）✓ PASS；`@ModifyConstant` 用 `expect=1` 声明，若常量没匹配上会打警告，实跑日志无警告 → 190 已被替换 ✓。（世界内渲染无法在夹具里跑，因为夹具没有世界；这两条是钩子层面的验证，最终观感仍需实机确认。）

### 13.12 立绘裁剪不再剪掉决斗盘（0.6.8-alpha）

用户实测：模型中手上的**决斗盘不见了**，怀疑没有复刻原模组。

结论：决斗盘功能是有的——它是**副手物品的 3D 模型**（`DuelDiskItem`），由 fix 模组的 `PuppetDiskRenderer.draw(...)` 挂在傀儡手骨上：本地玩家用真实副手，对手用 `ClientDuelPublicInfo.current().opponentOffHand()`。它在 `drawOne` 里紧跟在身体网格之后绘制，而我为了"只留上半身"开的 scissor 一直盖到 `drawOne` 结束，于是把盘（和手套）一起剪掉了。

修复：

- 裁剪带改为由 `client/PuppetClip` 统一管理：`DuelPuppetsMixin` 在 `drawOne` HEAD 开带、RETURN 兜底收带；
- 新增 `mixin/PuppetDiskRendererMixin`，在 `PuppetDiskRenderer.draw` 的 HEAD 处**提前收带**（`endForAttachments`），所以决斗盘与随后的手套都在带外绘制 ✓ 建模照旧只露上半身；
- `PuppetClip.attachmentCount()` 记录"为附件解除裁剪"的次数，夹具断言 `>=2`（两张立绘各一次）——这样"钩子没装上"会直接变成夹具失败，而不是静默地把盘藏起来。夹具 PASS。

### 13.11 决斗中止（No pending request）、信息框粘住与遮挡（0.6.8-alpha）

用户实测三点：**经常**出现"MDPro 决斗中止：No pending request"并判平局；信息框鼠标移开不消失、很挡视野；临时退出的聊天提示要去掉。

1. **决斗中止（真 bug，最严重）**：`DuelBridge.Game.pump()` 里"空连锁询问自动放弃"这一支是

   ```java
   lastPrompt=prompt; prompt=null; session.respond(-1); continue;   // 旧代码
   ```

   `session.respond()` 之后引擎的 `waiting` 已变 false，但代码**继续用同一个旧 batch** 往下走，最后 `if(batch.waiting())` 仍为 true，于是 `prompt` 被填成旧数据并 publish；此后玩家或机器人再答一次，`MdproRuntime.requireWaiting()` 就抛 `No pending request` → `fail()` → 判平局。空连锁询问（kind 16、无候选且可取消）在对局中非常常见，所以"经常"触发。同一支的自动应答还写成 `byte[] reply=...; reply(prompt,reply)`，把已置 null 的 `prompt` 传了进去（应为 `lastPrompt`）。

   修复：任何在 batch 内部发出的应答都**立刻 `continue` 回到外层**重新 `process()`，不再读旧 batch 剩余事件；两条自动路径（空连锁放弃 / 唯一答案）都改为**先判定、再应答、不 publish 这个瞬时 prompt**，`lastPrompt` 保留用于 kind 1 重试。
2. **信息框只在真正悬浮时出现**：之前用 `detail` 兜底（sticky），鼠标移开后仍留在屏幕上。现在只读 `hover`，移开即消失；夹具加 `fixture$clearHover` 与断言 `!fixture$tooltipReady()`。
3. **信息框不压卡片、不压信息栏**：改成对四个位置（右/左/下/上）打分选取，计分含"是否越界、是否压住交互栏（+60）、是否压住这张卡本身（+120）"，并按**实际绘制矩形**（含内边距）判定，最终夹在窗口内。另外绘制时 `translate(0,0,200)` 并 `flush()`，保证不会被卡片列表或信息栏盖住。
4. **去掉临时退出的聊天提示**：`onClose()` 不再 `displayClientMessage`，静默退出。

### 13.10 原生卡片信息框：列表里也有、并贴到卡片旁边（0.6.7-alpha）

用户实测两点：额外卡组/墓地等列表里的卡**没有** MC 原生信息框；板上那张的原生信息框**离卡片太远**。

排查结论：

1. 原模组只在 `openList == null` 时才调 `renderHoverTooltip`（`DuelScreen.java:213-217`），所以一旦打开卡组/额外/墓地列表，原生 tooltip 根本不会被调用；而且它只扫描手牌与场上区域，从不看列表行。
2. 它把鼠标坐标直接传给 `graphics.m_280153_(font, stack, mouseX, mouseY)`，而这个调用发生在**缩放过的棋盘 pose** 里：MC 的 tooltip 定位器按未缩放窗口尺寸测量，于是框被挪到离卡片很远的地方。

修复（`NativeDuelScreen.renderNativeTooltip`，由 `DuelScreenMixin` 取消原方法后在 `render()` 末尾调用）：

- **内容**用游戏自己的 `stack.getTooltipLines(player, TooltipFlag.NORMAL)`，也就是原生那一套（平卡/类型/属性/等级/攻击力·守备力/效果全文/密码·CID），保证和原版信息一致；
- **锚点**用悬浮卡的矩形（`hoverRect`，板上的格子、手牌槽、列表格子都会写入），画在卡片右侧；卡片位于窗口右半时自动翻到左侧，并夹在窗口内；
- 在**未缩放**的屏幕坐标里绘制，框是全尺寸、不受棋盘缩放影响；
- 列表（额外卡组/墓地/除外/卡组）里的行同样走这条路，因此这些页面现在也有原生信息。

实现细节：一开始直接调 `GuiGraphics.renderTooltip`，结果**框画出来了但文字不渲染**（诊断日志显示内容有 10 行、坐标也对），所以改为用同样的 10 行文本自己画框（近黑底 + 紫边，首行白色），视觉上与 MC tooltip 一致且可验证。夹具新增 `fixture$hover` / `fixture$tooltipReady` / `fixture$tooltipAnchor`，`ui-detail.png` 可直接看到框贴在 M1 卡右侧且文字完整。

### 13.9 卡组/额外/墓地/除外列表可点击可悬浮（0.6.6-alpha）

用户实测：这些页面里卡片既不能点击发动/召唤，悬浮也不显示信息。根因是我把旧的 `zoom` 面板换成 `detail` 面板时漏改浏览器代码——`renderBrowser` 悬浮时仍只写 `zoom`（换面板后没有读者），点击非选择模式的卡也只写 `zoom`，完全没有走行动作路径。

修复：

1. **悬浮**：`renderBrowser` 命中格子时改为写 `detail`（同时保留 `zoom`），于是左上信息面板立刻显示该卡；因为浏览器里 `suppressNativeTooltip()` 为真，面板还会带上完整效果文本。
2. **点击**：非选择模式的列表点击现在走与棋盘一致的行动路径——新增 `actionsForCard(CardView)`，按**卡号**把列表里的卡与当前请求的 `Choice` 关联（列表只有 `CardView`，没有位置/序号），
   - 只有一个动作 → 直接提交（例如额外卡组的特殊召唤）；
   - 多个动作 → 打开行动菜单（例如手牌的召唤/放置）；
   - 没有动作 → 只固定显示该卡信息（观战/查看）。
3. 夹具新增 `fixture$openPile` / `fixture$detailName` 观测口，并加一例"打开手牌列表 → 点击第一张"：断言信息面板有内容，且要么提交了应答、要么弹出了行动菜单（`ui-pile.png` / `ui-pile-action.png`）。

注：夹具的鼠标恒在 (0,0)，所以"悬浮即显示"这条由代码路径（写入 `detail`，与点击写入同一字段）加上点击用例共同覆盖，未做真实鼠标移动。

### 13.7 卡背换成 mdpro3 的经典卡背（0.6.5-alpha）

**素材来源**：mdpro3 的 `Assets/Texture/Texture_Other/DefaultProtector.png`（通过 `TextureContainer.cardBackDefault` 与 `GameCard.cs` 里的 `myProtector`/`opProtector` 材质定位；卡背在 mdpro3 里是"卡套/protector"）。原图 500×717、671 KB。GitHub API 直接下载会截断（IncompleteRead），最终用用户提供的同一张图入库为 `generated/card-back-source/user-card-back.png`。

**压缩**：128×184（≈2× 最大实际绘制尺寸 59×86）、LANCZOS 缩放、自适应 256 色调色板、`optimize=True` → **22,976 字节**（原图 671 KB，约 −96.6%）。同时留了 148×212(29 KB)/118×169(19 KB)/96×138(13 KB) 备选在 `generated/card-back-source/`。原占位卡背是 59×86、328 字节。

**生效方式**（不改任何 review 过的 JAR）：素材放在自己的命名空间 `addon/src/main/resources/assets/yugioh_mdpro_addon/textures/card/card_back.png`，再用两个 mixin 把两处查找精确重定向：

- `DuelScreenMixin` 对 `DuelScreen.load` 里的 `new ResourceLocation("yugioh", path)` 做 `@Redirect`（`@At(value="NEW", target="(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;")`）→ 覆盖棋盘上的卡组/额外卡组/里侧卡；
- `CardArtTexturesMixin` 在 `CardArtTextures.back()` 的 HEAD 注入并返回自己的 `ResourceLocation` → 覆盖卡片列表、物品渲染与放大面板。

之所以不直接把 `yugioh:textures/card/card_back.png` 覆盖掉：那取决于各个 mod 资源包的优先级（与模组加载顺序有关），输了就会静默保留旧图；也不去改 `generated/deps/yugioh-1.0.0.jar`，因为它在 `integrated-release.json` 里有审核过的 SHA256。

**验证**：夹具断言 `CardArtTextures.back()` 的命名空间是 `yugioh_mdpro_addon`（PASS），并且 `ui-board.png` 里棋盘四角的卡组/额外卡组格子已经显示经典漩涡卡背（替换前是棕色菱形占位图）。

## 14. 构建与部署（0.6.9-alpha）

| 项 | 值 |
|---|---|
| 版本 | `0.6.9-alpha`（`addon/build.gradle`、`addon/content/integrated-release.json`） |
| 附属产物（快照） | `generated/release-snapshot/yugioh-mdpro-addon-0.6.9-alpha.jar`，563,567,544 字节，sha256 `1a742b51ef0d5fa7388d3e3dac35203e33cd7d6ff3fbe5183aba30bdba740366` |
| 整合产物 | `addon/build/libs/yugioh-mdpro-integrated-0.6.9-alpha.jar`，867,207,261 字节，sha256 `5f8aa6e7ca0fa510aa1b207b869764d2069676ff547bd40456a53b64c11ea866` |
| 已部署 | `MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods/yugioh-mdpro-integrated-0.6.9-alpha.jar`（同哈希；mods 内 yugioh-mdpro 包仅此一个） |
| 旧 0.6.8 备份 | `generated/deployment-backup/integration-0.6.8-alpha/` |

校验：`nativeSmoke` / `gameplaySmoke` / `historicalPoolSmoke` PASS，`runGameTestServer` **All 12 required tests passed**，`-PuiSmoke` PASS（含卡背、手牌悬浮、列表点击、信息框不遮卡/移开消失、决斗盘钩子、3D 立绘关闭等断言）。

**打包竞态与处理**：并行会话在反复重新导入 `generated/content/resources`，每次都会重建同名附属 jar（`addon/build/libs/yugioh-mdpro-addon-0.6.9-alpha.jar`），于是"同步 `outer.sha256` → 打包"之间哈希就变了（连续两次 `AssertionError: Unreviewed input`）。处理办法：先用 `generated/tmp/pin_outer_snapshot.py` 把已校验的附属 jar 复制到 `generated/release-snapshot/`、顺带断言所有新 mixin 都在包里并已注册，然后把 `integrated-release.json` 的 `outer.source` 指向该快照并记录其 SHA256，再打包——快照不会被别人改写，竞态消失。**今后若要与并行会话同时出包，沿用这个流程。**

## 15. 仍需用户验收

- 真实对局中的手感与全部询问类型；夹具只覆盖合成状态。
- ESC 退出后"回到世界"这一条在夹具里只能验证到"离开对决界面"（夹具没有世界，MC 会用标题界面顶替 `setScreen(null)`）；世界内的返回依赖 `open=true` 路径，夹具已验证。
- 语音房间那一行在匹配格式界面仍占一行（显示"关"、点不动）。若要把这一行也藏起来，需要在 fix 模组的注入调用点上做 `@Redirect`，属于跨模组改写它的界面，需要你确认是否要做。
- 卡背如果觉得 128×184 不够清晰或想换另外几档尺寸，改 `generated/card-back-source/` 里选一张复制成 `addon/src/main/resources/assets/yugioh_mdpro_addon/textures/card/card_back.png` 重新打包即可。
- 若并行会话之后重新构建并覆盖 mods，注意**不要**让两个同 mod id 的包同时存在（整合包名也算）。
- `generated/content/resources` 正被并行会话反复重新导入，附属 JAR 的 SHA256 会随之变化；重打包前先跑 `generated/tmp/sync_outer_sha.py` 同步 `outer.sha256`。
