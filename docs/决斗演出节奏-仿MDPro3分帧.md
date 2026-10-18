# 对决演出节奏：把"一帧跳完"改成 MDPro3 式的分帧演出

日期：2026-10-17。目标：让每个阶段、每次操作都看得见——尤其是 AI 对局。

## 1. 问题与实测

部署实例里留着一整局真实对局的宿主 trace
（`MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mdpro-reference/sessions/5ef6ca9a-…/host.jsonl`）。
用 `generated/tmp/analyze_host_trace.py`、`analyze_host_prompts.py`、`analyze_host_runs.py` 量化后：

| 指标 | 实测值 |
|---|---|
| 内核消息总数 | 5559（UpdateData 4513、SelectChain 316 属噪声） |
| 带场面快照的"可见动作" | **226** |
| 发给 Java 的询问（prompt 事件） | **99**，全部 `player=1`（人类座位） |
| 宿主替 AI（seat 0）直接应答 | **208** 次：连锁窗口 157、主要阶段 21、选区域 11、选卡 7、战斗 5、效果是否 3、选项 2、表示形式 1 |
| 改动前的界面刷新次数 | **99**（= 玩家自己的询问数）：AI 的 208 次决定产生 **0** 次刷新 |
| 一次刷新吞掉的可见动作 | max **21**，p50 1，p90 4；39 次刷新显示 0 个新动作 |
| 最糟的一段 | `NewPhase → NewTurn → Draw → Move → Summoning → Summoned → PosChange → Move → Set → NewPhase×3 → NewTurn → Draw → NewPhase×2`，即**一次刷新跨过 AI 的整个回合** |
| 快照粒度 | 226 条可见消息里 **186 条**挤在"快照完全相同"的连续段（同一次内核 `process()` 已全部应用），例如 `Move → Summoning → Summoned` 共用一张棋盘 |

根因三条：

1. `DuelBridge.Game.pump()`（`addon/…/duel/DuelBridge.java`）一次 tick 循环最多 128 步把宿主排队的消息全部消费，只有 `batch.waiting()`（要玩家回答）才 `revision++; publish()`。中间状态从不发给客户端。
2. 宿主 [DuelHostProgram.decompiled.cs:255-278](../generated/mdpromod-host-source/DuelHostProgram.decompiled.cs) 对 AI 座位在进程内 `DrainResponses/ApplyAiResponse` 后 `continue`，**不发 prompt 事件**；`ReferenceHost.process()` 只认 `prompt/message/snapshot/ended`，`ai/autoPass` 事件被静默丢弃。所以 AI 回合对界面而言是一帧。
3. 客户端把攒下的东西一次放：`NativeDuelScreen.receive` 对 `sounds` 列表整帧循环播放；`attackEvent` 只保留最后一次攻击；fix 模组的立绘动画靠 `DuelAnimationEvents.select(before,now)` 对快照做差分，一次 publish 只挑一个 clip；日志只记 40/70/74 三类消息。

## 2. MDPro3 的做法（上游 SethPDA/MDPro3，源码见 `generated/mdpro3-ui-source/`）

| 机制 | 出处 | 作用 |
|---|---|---|
| 消息队列，`Sibyl()` 每次 Update **最多处理一条** | `OcgCore.cs:1821-1851` | 动作天然一条一条来 |
| `messagePass` 演出闸门：要放动画的消息置 `false`，动画结束回调置 `true` | `OcgCore.cs:2768→2817`、`3324→3843`、`4889` | **询问绝不与演出并行** |
| 重要消息最小间隔：`Sleep(n)` 设 `MessageBeginTime = TimePassed()+n*10ms`，`Sibyl` 时间没到就 `break` | `OcgCore.cs:1653-1657`、`1834-1836`、重要消息表 `2267-2314` | 动画再短也不连播 |
| 间隔量级取自演出时长：`Sleep((int)(card.Move(to)*100))`、召唤 `Sleep(100)`、连锁 `Sleep(100)`、抽卡 `Sleep((int)(sleep*100))` | `3029`、`3138`、`3291`、`4517` | 0.2–1.0 s/条 |
| 速度档位按模式分开：`DuelAcc`/`WatchAcc`/`ReplayAcc` + 加速按钮 | `1186-1221` | 对局/观战/回放各自倍速 |
| 阶段与回合有专门演出 | `PhaseBanner` `4711-4730`、`TurnChangeBanner` `4693` | 阶段变化看得见 |
| **关键差异**：不查内核状态，而是维护自己的场面模型，`GCS_Get`/`card.Move` 逐条消息改这份模型 | `3009-3053` 等 | 即使一次 `process()` 已推进完整批状态，仍能一条一条演出 |

## 3. 本实现

新增三个类，全部不依赖 Minecraft，可独立验证：

| 文件 | 作用 |
|---|---|
| `addon/…/duel/DuelPacing.java` | 节奏策略：可见消息集合（取自适配器 `Adapter.cs:71` 的快照表）、叙述性消息集合（对应 `IfMessageImportant`）、每类消息的停留 tick 表、四档速度、AI 决策延时 |
| `addon/…/duel/DuelPacer.java` | 节拍状态机：`beat(kind,fingerprint)` 决定"现在发布并停多久"，同状态消息折叠进上一拍 |
| `addon/…/duel/DuelLog.java` | 消息→人话日志行；**只记录公开信息**（手牌、抽卡、盖放、里侧卡不记卡名） |

改动：

- `DuelBridge.Java`：`pump()` 保留为极速/测试用的一次性排空；新增 `step()` = 每 tick 最多推进到"下一次可见变化"→ `revision++; publish()` → 按消息类型挂起 `pacer`。`tick()` 先查 `pacer.ready()`，请求到达时立即停止推进（对应 `messagePass` 闸门）。
- `ReferenceHost`：新增 `stateFingerprint()`（field + 全部区域查询 + 计数器）与 `queuedEvents()`（背压证据）。
- `NativeBoard`：新增 uid→卡号映射与 `nameOf`、`uidAtPacked`，供日志取名。
- `EngineCommands`：`/mdpro pace [slow|normal|fast|instant]`（OP），`/mdpro status` 显示当前档位。
- 停留表（NORMAL，单位 tick，20 tick/s）：Win 20、NewTurn 16、NewPhase 10、Move 8、PosChange 6、Set 8、Swap 8、Summoning 14、Summoned 4、SpSummoning 14、SpSummoned 4、FlipSummoning 10、FlipSummoned 4、Draw 6、Damage 10、Recover 6、LpUpdate 4、PayLpCost 4、计数器 4、Attack 10、Battle 12、其它 6。档位：慢速 ×1.75、正常 ×1.0、快速 ×0.4、极速 = 0（旧行为）。
- 折叠规则：可见但场面指纹未变、且不属于叙述性消息（回合/阶段/攻击/战斗/胜利）的消息不占拍，但仍进日志。这样 `Move → Summoning → Summoned` 只占一拍，不会白等。

AI 决策延时也改为随档位变化（极速 1 / 快速 6 / 正常 12 / 慢速 20 tick），取代原来硬编码的 12 tick。

## 4. 验证

`gradle -p addon --no-daemon --offline pacingSmoke`（新增任务，跑真实宿主，不依赖 Minecraft）：

```
PASS pacing policy: 22 visible kinds, hold table, speed ordering, fold/beat rules
PASS duel log: frozen ABI offsets and the public-information rule
  human vs human: events=2598 visible=135 prompts=60 beats=115 folded=20 distinctStates=98 holdTicks=1146 pacedSeconds~93.3 maxQueued=184 maxBeatsPerRequest=6@1
PASS paced human duel: 115 beats for 60 requests (60 updates before this change)
  human vs Windbot seat 0: events=3177 visible=164 prompts=40 beats=140 folded=24 distinctStates=119 holdTicks=1396 pacedSeconds~93.8 maxQueued=256 maxBeatsPerRequest=20@8
PASS paced AI duel: 17 of 40 request windows now show 2+ beats, worst window 20 beats
PASS duel pacing smoke
```

断言的可验证性质：

1. 可见消息集合与适配器快照表逐项一致（防止将来改坏）。
2. 每一类可见消息在四档速度下都满足 `极速=0`、`快速≤正常≤慢速`、正常档 1..60 tick。
3. 状态变化一定成拍：`beats ≥ distinctStates`（不丢动作）。
4. 同状态消息不占拍：`folded > 0` 且 `hold == 0`。
5. 叙述性消息（阶段/回合）即使场面不变也成拍。
6. 日志：公开信息规则（里侧/手牌/抽卡不记卡名）+ 冻结 ABI 偏移（50=17 字节、53=10、54=9、90=3+4n、91=6）+ 截断消息不抛异常。
7. 宿主事件队列未溢出（`maxQueued=419 < 4096`），说明当前消费速度不需要额外背压。

回归：`referenceHostSmoke` 全部 8 项通过；`nativeSmoke` 通过；`runGameTestServer` **All 16 required tests passed**（含 `DuelReliabilityGameTests` 中直接调用 `pump()/answer()/HostTestAwait` 的路径，说明极速/夹具路径未被节拍器破坏）；`-PuiSmoke runClient` **PASS: 89 steps**（新增两条断言：阶段变化必须出现「战斗阶段」横幅、回合变化必须出现「第 4 回合」横幅，截图 `ui-phase-banner.png`）。

客户端侧（与节拍配套的两件事，已实现并已通过上面那条 89 步夹具）：

- **阶段/回合横幅**：`NativeDuelScreen` 在 revision 变化时比较 turn/phase，回合切换显示「第 N 回合 · 主要阶段 1」0.9 s，阶段切换显示「主要阶段 1」0.6 s。**位置固定在屏幕最顶端**（`y=4`，居中、夹在左右边缘内）：最初放在画面高度 14% 处，玩家实测会盖住对方的场面与手牌——正是节拍停顿时最该看的地方。夹具现在断言横幅矩形整条落在顶部 44 px 内、且底边不高于对方怪兽行顶边，并产出 `generated/ui-phase-banner.png`、`generated/ui-turn-banner.png` 两张实拍。
- **音效逐条出队**：`receive()` 不再整帧播放本次 beat 累积的 cue，而是入队 + 每 180 ms 放一条（`drainSounds()`），与 fix 模组自身的 cue 冷却同量级，修掉"整条连锁音效同时炸响"。

## 5. 部署（0.6.30-alpha）

| 项 | 值 |
|---|---|
| 附属产物 | `addon/build/libs/yugioh-mdpro-addon-0.6.30-alpha.jar`，465,589,876 字节，sha256 `5874ad5db86fb18627e5612ab89ef7a2c10ad98db921a2274d1da3fac4b7a895` |
| 整合产物 | `addon/build/libs/yugioh-mdpro-integrated-0.6.30-alpha.jar`，769,229,593 字节，sha256 `3a08d4dbea6a15737fba0c74b1c2c8d86245c49b7e0ef15ac356e988fc21c074` |
| 已部署 | `MCYoGiOh/.minecraft/versions/1.20.1-Forge_47.4.28/mods/yugioh-mdpro-integrated-0.6.30-alpha.jar`（安装后复核同 sha256） |
| 被替换版本 | 0.6.28-alpha（sha256 `5fd3d72e3012df26816cce33be1b06590c79a9cdc89a35dffa540d2ed926acae`），备份在 `generated/deployment-backup/integration-0.6.30-alpha/` |
| 同目录其它 mod | `geckolib-4.8.4.jar`（哈希核对通过）、`[车万女仆] touhoulittlemaid-1.5.3-forge+mc1.20.1.jar`（用户自装，未改动）；mods 内仍只有一个 yugioh-mdpro 包 |
| 部署记录 | `generated/pacing-deployment-0.6.30-alpha.json` |
| 打包校验 | 24,080 个原条目逐字节一致、3 个嵌套 JAR 哈希未变、mod id 未变；`DuelPacing/DuelPacer/DuelLog` 与 `NativeDuelScreen` 的顶部横幅代码均在包内（部署前读取 class 字节断言 `bannerBox` 存在） |

这一版同时包含：服务端节拍器、日志扩容、四档节奏命令、客户端阶段/回合横幅（**已按玩家实测改到画面最顶端**）、音效逐条出队。

打包走项目既有流程（`integrated-release.json` 审核哈希 + `package_integrated.py build` 的逐条目校验），但**没有用** `package_integrated.py deploy`：它要求存在与本包 sha256 完全对应的客户端验收记录，且当 mods 内已有整合包时直接拒绝。因此按它同样的步骤手工部署并逐项校验（`generated/tmp/deploy_integrated.py`）：审核输入 → 备份已安装版本并核对哈希 → 暂存 `.partial` + 校验 → 替换 → 复核 mods 内 jar 列表与部署包哈希，任一步失败即回滚。

### 5.1 与并行会话的协调（本轮实际踩到的坑）

本工作区多会话共用一棵源码树和一个实例目录，本轮交接时出现了三次碰撞，处理方式如下，供后续参考：

1. **版本号被回退**：我为了给横幅补丁出包把 `build.gradle` 升到 0.6.29，但另一会话同时在用它，随后它又改回 0.6.28 并自己出了一版 0.6.29（外层 jar 用的是我 10:03 构建的快照 `c669c877`，因此**不含**横幅位置修正）。最终我升到 0.6.30 单独出包，**没有动**它 `generated/release-snapshot/` 下钉住的 0.6.29 外层 jar 与它的 0.6.29 整合包。
2. **构建互斥**：出包前先等对方的 Forge GameTest JVM 退出（`generated/tmp` 里的等待脚本按命令行匹配 `gradle|BootstrapLauncher|enabledGameTestNamespaces`，不动玩家自己的游戏进程），避免两边同时写 `build/libs`。
3. **审核哈希手抄错误**：`previous_integrated.sha256` 被我抄成 `…33eb1…`（正确是 `…33be1…`），部署脚本因此拒绝执行。这类哈希一律用 `hashlib.file_digest` 直接算并写回，不要手抄。

对方若继续出包，需要沿用 `generated/tmp/sync_outer_sha.py` 或 `pin_outer_snapshot.py` 同步 `outer.sha256`，并按上面的做法先把 `previous_integrated` 更新为当前已安装的 0.6.30（sha `3a08d4db…`）。

## 6. 已知边界与后续

- **批次粒度**：一次内核 `process()` 已经把整批状态应用完，所以 `Move → Summoning → Summoned` 这类同批消息只能共用一张棋盘快照（实测 186/226 条在这种段里）。要做到 MDPro3 那种"逐条演出"，需要像它一样在服务端维护影子场面、按消息流改模型，而不是每次 publish 去查内核。`NativeBoard` 已有 uid 映射，是自然扩展点。
- **节奏档位是服务器级全局设置**，不随存档持久化（重启回到 normal）。
- **部署与并发会话的协调**：本工作区多会话共用一棵源码树与一个实例目录。每次出包前应先确认没有其他会话正在构建/验收（`generated/*.log` 的写入时间、运行中的 JVM），并沿用 `integrated-release.json` 的审核哈希流程。

## 7. 复现命令

```text
python generated/tmp/analyze_host_trace.py <host.jsonl>     # 改动前的量化
gradle -p addon --no-daemon --offline pacingSmoke           # 节拍与日志
gradle -p addon --no-daemon --offline referenceHostSmoke    # 引擎回归
gradle -p addon --no-daemon --offline -PgameTestHeap=2G runGameTestServer
```
