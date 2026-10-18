# 游戏王 MC 模组重构 —— 初步诊断与目标清单（交接版 v1）

> 交接文档 · 生成于第 1 轮工作结束
> 目标 ID：`goal-51f720ff-9bda-4113-9083-ee71ed55b6a9`
> 状态：**P0 未通过**（有一个明确的、已定位的阻塞点，见第 5 节）

---

## 0. 一句话摘要

规则内核（`ocgcore.dll`）**可以被进程内加载并部分驱动**（`create_duel` / `set_player_info` / `set_script_reader` / `query_field_count` 均已实测成功），但**卡牌无法放入牌堆**（`new_card` 未生效），且 `start_duel` 崩溃。根因已锁定为：**MDPro3 这份 ocgcore 的 FFI 函数签名与公开的 EDOPro/ygopro-core 不一致，必须拿到权威签名**。卡池、日期边界、卡图压缩方案已全部量化完毕，无阻塞。

---

## 1. 任务范围（已由用户拍板）

| 项目 | 决定 |
|---|---|
| 规则内核 | 复用 MDPro3 的 `ocgcore`，**JNI 进程内嵌进 JVM**（不走 sidecar） |
| 卡池边界 | **2017 年，不使用大师规则 4（无额外怪兽区）** → 实测应取 **≤ 2017-03-24** |
| 卡图 | 压缩后**随模组分发**，参考原模组的资源规格 |
| 发布范围 | 自己玩 / 朋友 / **私人服务器**，不公开发布 |
| 目标 MC 版本 | 1.20.1 Forge 47.4.10 |

### 1.1 卡池边界存在一个必须先确认的矛盾

用户说"做到 2017 年"+"不用额外怪兽区"，但这两者在 2017 年正好分叉：

| 边界 | 卡片数 | 怪兽 | **连接怪兽** | 有卡面素材 | 有怪兽立绘 |
|---|---|---|---|---|---|
| **A. ≤ 2017-03-24（Link 登场前）← 推荐** | **8201** | 5458 | **0** | 8201（100%） | 6352 |
| B. ≤ 2017-12-31（2017 全年） | 8730 | 5824 | **85** | 8730 | 6731 |
| C. ≤ 2016-12-31 | 8098 | 5397 | 0 | 8098 | 6285 |

- 实测第一张 Link 怪兽：**2017-03-25「解码语者」(id=1861629)**，同日还有蜜罐机器人、连接蜘蛛。
- **A 方案才与"不用额外怪兽区"自洽**。选 B 会引入 85 张必须依赖额外怪兽区才能正常运作的卡。
- 另有 **763 张卡在日期表中无记录**（多为令牌/特殊卡），需单独决定去留。
- 边界只是一个 SQL 条件，后续改成本极低。**建议先用 A 推进。**

---

## 2. 现状诊断：现有模组（本体）

### 2.1 文件与许可

| 文件 | modId | 大小 | 作者 / 许可 |
|---|---|---|---|
| `yugioh-1.0.0-简中重译.jar` | `yugioh` | 169 MB | Kiki / **All Rights Reserved** |
| `ygostar-1.0.0-forge-1.20.1 (3).jar` | `ygostar` | 45 MB | MCreator 生成 |
| `yugioh-interaction-fix-0.103.2.jar` | `yugioh_interaction_fix` | 90 MB | Codex / **GPL v3** |

`yugioh-interaction-fix` 是一个**已经跑通的"给本体加卡"范例**：它用 Mixin 往本体注入了 693 张新卡、10 个新卡包、新预组和一套新效果表。**这是"能扩展"的实证**，技术手段见 2.3。

### 2.2 本体的卡牌架构

```
com.kiki.yugioh.card.CardDefinition       一张卡的数据模型
com.kiki.yugioh.card.CardDefinition$Builder  monster() / spell() / trap()
com.kiki.yugioh.card.Cards                 697 个 public static final 常量
com.kiki.yugioh.card.CardsExtra            register1()~register5() 五批追加
com.kiki.yugioh.card.Tokens                12 个衍生物
com.kiki.yugioh.card.Expansions            10 个卡包（LOB…IOC）
com.kiki.yugioh.card.StructureDecks        10 个预组
com.kiki.yugioh.registry.ModItems          真正注册成 MC 物品的地方
com.kiki.yugioh.item.CardItem              每张卡一个物品，持有 CardDefinition
com.kiki.yugioh.duel.effect.CardEffect     效果接口（几十个钩子）
com.kiki.yugioh.duel.effect.CardEffects    effectId → CardEffect 注册表
```

**注册时序**（`ModItems.<clinit>` 反编译所得）：

```java
ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "yugioh");
// ... 注册 deck_box / duel_disk / booster / duel_table
Cards.bootstrap();                                    // 一次性建立全部卡牌定义
for (CardDefinition def : Cards.all()) {              // 遍历后逐张注册成物品
    CARDS.put(def.getId(), ITEMS.register(def.getId(), () -> new CardItem(def)));
}
```

**两道私有 API 门**（附属必须绕过）：
- `Cards.register(Builder)` —— 包级私有
- `CardEffects.register(String, CardEffect)` —— 包级私有

**绕法**（`yugioh-interaction-fix` 的实际做法，可直接照抄）：

```java
interface CardsAccess {                       // @Invoker
    @Invoker("register")
    static CardDefinition fix$register(CardDefinition.Builder b) { throw new AssertionError(); }
}

@Mixin(Cards.class)
class CardsBootstrapMixin {
    @Inject(method = "bootstrap", at = @At("RETURN"))   // 必须在 bootstrap 内部
    private static void fix$registerAll(CallbackInfo ci) { /* 注册新卡 */ }
}
```

注入点必须在 `Cards.bootstrap()` **内部**——`ModItems` 静态块紧接着就遍历 `Cards.all()` 建物品，晚一步新卡就没有物品。

### 2.3 本体的资源规格（压缩目标基准，实测）

| 项目 | 实测值 |
|---|---|
| 卡图目录 | `assets/yugioh/textures/card/<id>.png` |
| 卡图数量 / 总大小 | 1248 张 / **147.2 MB** |
| 卡图分辨率 | **176×257**（1234 张，绝大多数） |
| 卡图色彩模式 | RGBA |
| 平均单张 | **120.8 KB** |
| 物品模型 | `assets/yugioh/models/item/<id>.json`（1266 个，`builtin/entity`） |
| 物品贴图 | `assets/yugioh/textures/item/<id>.png` |
| 语言键 | `item.yugioh.<id>`（名称）、`yugioh.card.<id>.desc`（描述） |
| 卡图路径常量 | `yugioh:textures/card/<id>.png`、`yugioh:textures/item/<id>.png` |
| 悬浮放大机制 | 已存在：`CardZoomHandler` + `CardItemRenderer` + `CardArtTextures`（含 `MAX_LIVE_TEXTURES` LRU 缓存） |

> **注意**：本体 176×257 RGBA 用了 120.8 KB/张，属于**未优化**的 PNG。同样的分辨率改用调色板 PNG 可降到 34 KB（见 4.2）。

---

## 3. 现状诊断：内核来源（MDPro3）

### 3.1 关键前提：`F:\Minecarft\游戏王mod工作区\inputs\mdpro3` 是**编译成品**，不是源码

```
F:\Minecarft\游戏王mod工作区\inputs\mdpro3\
├── MDPro3.exe / UnityPlayer.dll              Unity 游戏
├── MDPro3_Data\Managed\Assembly-CSharp.dll   6.18 MB 托管 IL（带 .pdb，可反编译）
├── MDPro3_Data\Plugins\x86_64\
│   ├── ocgcore.dll       1.61 MB  ← 规则内核，PE machine=0x8664，CLR 目录 RVA=0（纯原生非托管）
│   ├── ygoserver.dll     3.25 MB
│   └── sqlite3.dll       1.13 MB
├── Data\
│   ├── locales\zh-CN\cards.cdb    卡库（SQLite）
│   ├── pack\pack.db               卡包 + 发行日期（SQLite）
│   └── script.zip                 13540 个 Lua 卡脚本
└── Picture\
    ├── Art\        15012 张 jpg（卡面）
    ├── Closeup\    10956 张 png（怪兽立绘，透明底）
    └── OverFrame\  95 张
```

**源码在 GitHub 上**（不必反编译 C#）：
- Unity 层：`https://github.com/SethPDA/MDPro3`
- 内核：`https://github.com/Fluorohydride/ygopro-core`（及 `edo9300/ygopro-core`）
- 卡脚本：`https://github.com/ProjectIgnis/CardScripts`

C# 侧对内核的封装在 `MDPro3.Servant.OcgCore` / `CoreWrapper`（类名从 IL 字符串中确认）。

### 3.2 卡库（实测）

| 项目 | 实测值 |
|---|---|
| 路径 | `Data\locales\zh-CN\cards.cdb`（SQLite，7.68 MB） |
| 总卡数 | **14981** |
| `datas` 表字段 | `id, ot, alias, setcode, type, atk, def, level, race, attribute, category` |
| `texts` 表字段 | `id, name, desc, str1..str16`（**中文名+中文描述齐全，可直接用**） |
| 卡型分布 | 怪兽 9948 / 魔法 2932 / 陷阱 2099 |
| 召唤类型 | 融合 615、仪式 240、同调 562、超量 630、灵摆 399、**连接 506** |

> 卡池数据层是"白送"的：ocgcore 本来就吃这个库，**规则层面"全卡"零成本**。真正的瓶颈在 MC 侧（见 6.1）。

### 3.3 发行日期表（2017 边界的数据来源）

| 项目 | 实测值 |
|---|---|
| 路径 | `Data\pack\pack.db`（SQLite） |
| 表 | `pack(id, pack_id, pack, rarity, date)` |
| 行数 | 14218（**每卡一行 = 首发卡包**） |
| 日期范围 | 1999-02-04 ～ 2026-08-21 |
| 不同卡包 | 907 |
| 缺日期的卡 | 763 张 |

> **注意**：`pack` 表在 `pack.db` 里，**不在** `cards.cdb`。SQL 查询需 `ATTACH DATABASE`。

### 3.4 卡脚本（实测）

| 项目 | 实测值 |
|---|---|
| 路径 | `Data\script.zip` |
| 条目数 | **13540** |
| 命名 | `script/c<卡id>.lua` |
| 公共库 | `constant.lua`（**48412 字节，已成功读出**）、`utility.lua`、`procedure.lua` |
| 许可 | `script/LICENSE` = **GNU GPL v2** |

### 3.5 卡图素材（实测，**这条改变了悬浮方案**）

| 目录 | 数量 | 格式/分辨率 | 平均 | 总计 |
|---|---|---|---|---|
| `Picture\Art` | 15012 | jpg，主流 624×624 | 91 KB | **2085 MB** |
| `Picture\Closeup` | 10956 | **png，透明底**，约 228×266 ～ 300×300 | **23 KB** | **280 MB** |
| `Picture\OverFrame` | 95 | — | — | 47 MB |

**关键发现**：`Closeup` **不是卡面，而是去背的怪兽立绘切口**——正是"怪兽卡图悬浮"需要的素材。对比图见 `_ygo-analysis\art_vs_closeup.png`（左 Art 完整卡面 / 右 Closeup 透明立绘）。

两个额外利好：
1. `Closeup` 是 **PNG**，MC 的 `NativeImage` 原生支持；`Art` 是 jpg，反而要额外引 JPEG 解码库。
2. 立绘总量只有 280 MB，**不需要打包 2 GB**。

---

## 4. 关键方案已量化（无阻塞）

### 4.1 卡池规模

见 1.1。A 方案：**8201 张卡 / 5458 怪兽 / 6352 张立绘 / 卡面 100% 覆盖**。

### 4.2 卡图压缩实测（40 张随机样本，Pillow LANCZOS）

| 目标规格 | 每张 | 8201 张总量 |
|---|---|---|
| 卡面 → 176×257 **RGBA**（本体现在的做法） | 103.0 KB | **825 MB** |
| 卡面 → 176×257 **调色板 256 色** | **34.2 KB** | **274 MB** |
| 卡面 → 256×374 调色板 256 | 62.4 KB | 500 MB |
| 卡面 → 原图 jpg（不压缩） | 142.9 KB | 1145 MB |

| 立绘（6352 张） | 每张 | 总量 |
|---|---|---|
| 原尺寸 | 23.0 KB | 143 MB |
| **256×256 调色板** | **19.0 KB** | **118 MB** |
| 176×257 | 14.5 KB | 90 MB |

**推荐组合：卡面 176×257 调色板 + 立绘 256×256 调色板 ≈ 392 MB**，私服分发给朋友完全合理（对比：本体 1251 张就占了 147 MB）。

> **必须实测验证的风险点**：MC 的 `NativeImage`（底层 STB image）能否正确读取**调色板 PNG**。这决定 274 MB vs 825 MB。若不行，回退到 RGBA（825 MB）或改用一个 sidecar 图集方案。

### 4.3 大师规则 3 的开关

- PE 导出表里**没有**设置大师规则的函数。
- 但内核二进制中存在 Lua 侧绑定 **`GetMasterRule`**，说明内核内部有该状态。
- 在公开的 EDOPro API 中，它由 `OCG_DuelOptions` 结构体承载（`create_duel_v2_ex` 那一族）。
- **未确认**：本 DLL 如何设置。因为 `create_duel_v2_ex` 实测崩溃、`create_duel_ex(seed, NULL)` 可用，需在拿到权威签名后一并解决。

---

## 5. 阻塞点：P0 内核桥接（**当前唯一卡点**）

### 5.1 已证实可用（实测）

| 能力 | 结论 |
|---|---|
| DLL 加载 | ✅ 64 位 Python `ctypes.WinDLL` 直接加载成功 |
| 导出函数解析 | ✅ **35/35** 全部可解析 |
| `create_duel(uint32_t seed)` 按值 | ✅ **成功**，返回有效 handle |
| `create_duel_ex(uint32_t, NULL)` / `(uint32, 零缓冲)` | ✅ 成功 |
| `create_duel_v2(uint64_t*)` 传**指针** | ✅ 成功 |
| `set_player_info(duel, player, lp, startcount, drawcount)` | ✅ 成功 |
| `set_script_reader(cb)` legacy 签名 `byte*(*)(const char*, int*)` | ✅ **内核真实调用了它**（已调用 3 次，含 `constant.lua` 48412 字节） |
| `query_field_count(duel, team, loc)` | ✅ 可调用并返回 |
| `new_card(duel, 结构体指针)` 2 参数形式 | ✅ 调用不崩（但见 5.2） |
| `end_duel(duel)` | ✅ 可调用 |

### 5.2 已证实**不可用 / 未生效**（实测）

| 问题 | 现象 |
|---|---|
| `new_card` 放卡 | ❌ 调用不崩，但 `query_field_count` 始终为 **0** → **卡根本没进牌堆**（两种候选结构体布局均如此） |
| `card_reader` 回调 | ❌ **从未被调用**（0 次） |
| `start_duel(duel, 0)` | ❌ 四种布局组合**全部崩溃**，`0xC0000409` |
| `create_duel_v2(uint64_t)` 按值 | ❌ 崩溃（access violation，读 `0x1` / `0xFFFFFFFFFFFFFFFF`） |
| `create_duel_v2_ex(uint64_t, ptr)` | ❌ 崩溃 |
| `new_card` 7 参数 legacy 形式 | ❌ 第一张卡就崩溃 |

### 5.3 崩溃码记录

| 退出码 | 含义 | 触发场景 |
|---|---|---|
| `-1073740791` = `0xC0000409` | STATUS_STACK_BUFFER_OVERRUN / `__fastfail`（MSVC GS 栈保护失败） | `new_card` 7 参数形式、`start_duel` |
| `-1073741819` = `0xC0000005` | Access Violation | `create_duel_v2` 按值传递 |

### 5.4 根因判断（**本诊断的核心结论**）

**这份 `ocgcore.dll` 是 legacy 与现代 API 混合的 fork，其 FFI 签名与公开的 EDOPro/ygopro-core 不一致。**

证据链：
1. **导出名全是 legacy**（`create_duel` / `new_card` / `start_duel`），**没有任何 `OCG_*` 前缀函数**。
2. 但 `new_card` **legacy 7 参数形式崩溃**，反而 **2 参数结构体形式不崩** → 说明它已经改成了结构体传递。
3. `create_duel` 按值可用，但 `create_duel_v2` 需要**指针** → 与公开文档不符。
4. `set_script_reader` 用 legacy 签名**反而工作正常**。
5. 我们还观察到 MDPro3 有自己的 `ygoserver.dll`，说明作者对内核做过改造。

**未确认的权威定义**（来自 `ocgcore-ffi`，声明"mirrors the C API exactly"，但那是 `edo9300/ygopro-core` 的现代 API，**不能假定与本 DLL 一致**）：

```c
typedef struct OCG_NewCardInfo {          // 24 字节
    uint8_t  team; uint8_t duelist;
    uint32_t code;
    uint8_t  con;
    uint32_t loc; uint32_t seq; uint32_t pos;
} OCG_NewCardInfo;

typedef struct OCG_CardData {             // 64 字节
    uint32_t code; uint32_t alias;
    uint16_t* setcodes;                   // ← 是指针，不是值！
    uint32_t type; uint32_t level; uint32_t attribute;
    uint64_t race;                        // ← 是 u64
    int32_t attack; int32_t defense;
    uint32_t lscale; uint32_t rscale; uint32_t link_marker;
} OCG_CardData;

typedef struct OCG_Player { uint32_t starting_lp, starting_draw_count, draw_count_per_turn; } OCG_Player;

typedef struct OCG_DuelOptions {
    uint64_t seed[4]; uint64_t flags;
    OCG_Player team1, team2;
    OCG_DataReader  cardReader;   void* payload1;
    OCG_ScriptReader scriptReader; void* payload2;
    OCG_LogHandler  logHandler;   void* payload3;
    OCG_DataReaderDone cardReaderDone; void* payload4;
    uint8_t enableUnsafeLibraries;
} OCG_DuelOptions;

typedef struct OCG_QueryInfo { uint32_t flags; uint8_t con; uint32_t loc, seq, overlay_seq; } OCG_QueryInfo;
```

> 我第一版实现里 `team`/`duelist` 误当 `u32`（导致每张卡 `code` 实际读到 0）、`setcodes` 误当 `u64` 值（把一个数值当指针写进去，内核一解引用就炸）。**这两个错误已修正，但仍未跑通**，说明还有其他不一致。

### 5.5 P0 剩余的**唯一任务**

> **拿到 `ocgcore.dll` 的权威函数签名与结构体布局。**

三条路径（按推荐度排序）：

**路径 1（最权威，推荐）**：解析 `Assembly-CSharp.dll` 的 .NET 元数据，提取所有 `[DllImport]` 声明与相关结构体字段布局。
- 理由：MDPro3 用这份 DLL 跑得通，**它的声明就是 ground truth**。
- 实现方式（择一）：
  - `dotnet` + `System.Reflection.Metadata` 写个小 C# 工具（最省事）
  - `monodis` / `ikdasm`（Unity 自带 Mono，在 `MonoBleedingEdge` 里，**未确认是否存在**）
  - 自己写 Python ECMA-335 元数据解析器（纯标准库可行，工作量约 200–300 行）

**路径 2**：从 GitHub 取 `SethPDA/MDPro3` 源码。
- 本机对 `github.com` 的直接抓取**失败**；
- `data.jsdelivr.com` 对该仓库返回 **403（仓库 > 50 MB 超出 CDN 限制）**；
- 可尝试：jsdelivr **单文件路径** `https://cdn.jsdelivr.net/gh/SethPDA/MDPro3@main/<具体路径>`，或换镜像源（gitee / ghproxy 等）。需要先知道文件路径，可从 IL 字符串里挖 `OcgCore.cs` 附近的线索。

**路径 3（成本高，最后手段）**：二分法暴力试结构体布局。
- 已有 4 种组合的测试脚本（`step8_layouts.py`），可扩展成参数化搜索。
- 缺点：搜索空间大、每轮要起子进程、且无法区分"没生效"与"布局错"。

### 5.6 工具链现状（部分未确认）

| 工具 | 状态 |
|---|---|
| JDK 17 | ✅ `C:\Program Files\jdk-17.0.19+10`，含 `jdk.incubator.foreign@17.0.19` |
| MinGW-w64 | ✅ `C:\mingw64\bin\gcc.exe` / `g++.exe` |
| Visual Studio 2022 | ✅ 目录存在（`C:\Program Files\Microsoft Visual Studio\2022`），但 **`cl.exe` 不在 PATH** |
| Windows Kits 10 | ✅ 存在 |
| Python | ✅ 3.12.14（64 位），含 Pillow 12.3.0 |
| `dotnet` / `monodis` / `ikdasm` | ⚠️ **未确认**（探测命令被中断） |

> **架构决策的技术补充**：用户选了"JNI 进程内嵌"。落地时建议用 **JNA** 而不是手写 JNI：
> - 不需要 C 编译工具链，不需要为每个平台编桥接 DLL
> - 不需要玩家加 JVM 启动参数（`jdk.incubator.foreign` 需要 `--enable-preview --add-modules`，模组无法可靠要求）
> - JNA 的 `Callback` 对 `set_script_reader` / `set_card_reader` 这类回调支持良好
> - **本质仍是"进程内原生调用"，符合用户选择**。若坚持裸 JNI，MinGW/MSVC 均可用。

---

## 6. 架构诊断：为什么这是"重写"而不是"重构"

### 6.1 三个必须接受的事实

1. **本体的 `com.kiki.yugioh.duel.*` 整包作废**
   包括 `Duel`、`DuelManager`、`DuelAi`，以及 `duel/effect/` 下 **400+ 个手写效果类**（`ArchfiendEffects`、`SpellCounterEffects`…）。**这些正是本体只有 1251 张卡的根因**——每张卡的效果是手写 Java。用 ocgcore 后全部由 13540 个 Lua 脚本接管。

2. **UI 模型不兼容**
   本体 `DuelScreen` 是"点卡 → 选目标 → 立即结算"的直连模型；ocgcore 是**询问-应答**（引擎吐消息 → 宿主回 `set_responsei`/`set_responseb`）。UI 必须重写成事件驱动。

3. **不能基于本体改造**
   本体是 All Rights Reserved 的编译产物，无源码。所以"换掉内核"的实际含义是：**抛弃本体，新起一个 mod，把 ocgcore 当后端**。

### 6.2 可以保留的部分

- 卡图/贴图资产的组织思路
- `CardItemRenderer` + `CardZoomHandler` + `CardArtTextures` 的渲染与悬浮方案
- `ModNetwork` + `S2CDuelStatePacket` / `C2SDuelActionPacket` 的"服务端权威 + 客户端快照"网络层思路（内容从自研状态换成 ocgcore 快照）
- `CardCatalogScreen` 卡牌图鉴（搜索/筛选），可用于 8201 张卡的卡组构建

### 6.3 全卡扩展的真正瓶颈（不在卡池，在 MC 物品模型）

本体是"一张卡 = 一个 Forge 注册物品"（1251 张 → 1266 个模型 JSON）。8201 张就是：
- 8201 个 `DeferredRegister` 条目
- 8201 个模型 JSON + 8201 条语言 key
- 创意栏 / JEI 塞 8201 条目

**正确解法**：改成**单一 `CardItem` + 数据组件（1.20.1 用 NBT）存 card id**，注册表只有 1 个条目，`CardDefinition` 运行时从 `cards.cdb` 查。

---

## 7. 许可与合规

| 组件 | 许可 | 影响 |
|---|---|---|
| 卡脚本 `script/LICENSE` | **GNU GPL v2**（已读取原文确认） | 有传染性 |
| `ocgcore` 内核 | 上游 `edo9300/ygopro-core`（另见 `ocgcore-ffi` 标注 AGPL-3.0-only） | 有传染性 |
| 卡牌数据 / 卡图 | Konami 版权 | 不可公开分发 |
| 本体 `yugioh` mod | **All Rights Reserved**（Kiki） | 不可基于其分发改动版 |
| MDPro3 Unity 层 | GitHub 有源码，另有各自许可 | — |

**结论**：用户选择"私人服务器 + 朋友 + 不公开发布"，**合规风险可控**。但要注意：
- GPL 传染性意味着**如果将来公开发布，整个 mod 需以 GPL 兼容许可开源**。
- **不要重新分发本体 jar**；新 mod 应独立，不包含 Kiki 的代码/资源。

---

## 8. 目标清单（可勾选模板）

### 阶段 P0 —— 可行性验证（**当前卡在这里**）
- [ ] **P0.1** 拿到 ocgcore 权威函数签名与结构体布局（路径 1/2/3 见 5.5）
- [ ] P0.2 用正确签名跑通：`create_duel` → `set_player_info` → `new_card ×80` → `start_duel` → `process` 循环
- [ ] P0.3 确认 `card_reader` 被调用且卡数据正确填入
- [ ] P0.4 确认收到 `MSG_DRAW` / `MSG_NEW_TURN`（对局真正推进）
- [ ] P0.5 确认大师规则 3 的设置方式（无额外怪兽区）
- [ ] P0.6 用 ≤2017-03-24 卡池（8201 张）跑通，确认 Lua 脚本无缺失
- [ ] **P0 出口判据**：能 dump 出一场完整对局（至少 3 个回合）的消息流

### 阶段 P1 —— 数据层
- [ ] `cards.cdb` → Java 数据模型（对齐 `OCG_CardData` 字段）
- [ ] 2017 边界过滤（`ATTACH pack.db` 的 SQL 已就绪）
- [ ] 单一 `CardItem` + NBT 存 card id，替换"一卡一物品"
- [ ] 中文名/描述从 `texts` 表注入语言系统

### 阶段 P2 —— 内核接管
- [ ] JNA/JNI 桥接层（`create_duel` / `new_card` / `start_duel` / `process` / `get_message` / `set_response*`）
- [ ] 回调注册（script_reader 从 `script.zip` 取、card_reader 从 `cards.cdb` 取）
- [ ] std 消息协议解析（上百种消息类型）
- [ ] 决斗状态同步到 MC 客户端

### 阶段 P3 —— UI
- [ ] 决斗界面改造成"询问-应答"事件驱动
- [ ] 卡组构建界面（支持 8201 张卡的搜索/筛选，可复用 `CardCatalogScreen` 思路）

### 阶段 P4 —— 卡图管线
- [ ] 压缩管线：卡面 → 176×257 调色板 PNG；立绘 → 256×256 调色板 PNG
- [ ] **验证 MC `NativeImage` 能否读调色板 PNG**（决定 274 MB vs 825 MB）
- [ ] 卡面加载（按需或打包）
- [ ] **全怪兽立绘悬浮**（Closeup 按需加载 + LRU 缓存，复用本体 `CardArtTextures` 思路）

### 阶段 P5 —— 联机
- [ ] 私服服务端权威（ocgcore 只在服务端跑）
- [ ] 客户端状态同步 / 反作弊边界
- [ ] 卡组合法性校验（8201 卡池 + 禁卡表）

---

## 9. 交接物清单

### 9.1 探测脚本（可直接运行）
目录：`F:\Minecarft\游戏王mod工作区\mdpro3-p0\`

| 文件 | 作用 |
|---|---|
| `step1_load.py` | 加载 DLL、验证 35 个导出、`create_duel_v2` 初测 |
| `step2_callbacks.py` | 注册 3 个回调、自测 `script_reader` |
| `step3_probe_sig.py` | **子进程隔离**探测 `create_duel*` 真实签名（8 种形态） |
| `step4_duel.py` | 完整对局尝试（含消息类型表） |
| `step5_carddata.py` | card_data 写入大小二分（**该实验因 new_card 未生效而无效**） |
| `step6_minimal.py` | 逐行插桩，精确定位崩在哪次原生调用 |
| `step7_duel_full.py` | 结构体 new_card + 40 张牌 + 消息循环 |
| `step8_layouts.py` | **四种结构体布局组合实测**（参数：`ncA/ncB` `cdA/cdB`） |
| `step9_verify.py` | **用 `query_field_count` 验证 new_card 是否真的生效**（关键诊断工具） |
| `*.log` / `probe_*.log` / `sz_*.log` / `L_*.log` / `v_*.log` | 全部实测日志 |

**运行方式**：
```powershell
$env:PYTHONIOENCODING='utf-8'
$py='C:\Users\30481\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
& $py -u 'F:\Minecarft\游戏王mod工作区\mdpro3-p0\step9_verify.py' ncA
```
> 子进程隔离是必须的：原生崩溃会带走整个 Python 进程，`step3`/`step5`/`step8` 都是为此设计的。

### 9.2 分析产物
| 路径 | 内容 | 大小 |
|---|---|---|
| `...\versions\1.20.1-Forge_47.4.10\_ygo-analysis\base\` | 本体 jar 完整解包（4740 文件） | ~255 MB |
| `...\_ygo-analysis\fix\` | interaction-fix jar 完整解包（3289 文件） | |
| `...\_ygo-analysis\art_vs_closeup.png` | Art 卡面 vs Closeup 立绘对比图 | |
| `...\versions\1.20.1-Forge_47.4.10\acl-recovery\` | 文件权限修复的备份与回滚脚本（**建议保留**） | 0.1 MB |

### 9.3 关键外部路径
```
本体 mod      F:\Minecarft\游戏王mod工作区\inputs\mods\
MDPro3        F:\Minecarft\游戏王mod工作区\inputs\mdpro3\
  ├ ocgcore.dll    MDPro3_Data\Plugins\x86_64\ocgcore.dll
  ├ cards.cdb      Data\locales\zh-CN\cards.cdb
  ├ pack.db        Data\pack\pack.db
  ├ script.zip     Data\script.zip
  └ 卡图           Picture\Art\  Picture\Closeup\  Picture\OverFrame\
P0 项目       F:\Minecarft\游戏王mod工作区\mdpro3-p0\
JDK           C:\Program Files\jdk-17.0.19+10
MinGW         C:\mingw64\bin\
```

### 9.4 可直接复用的 SQL
```sql
-- 卡池过滤（A 方案：Link 登场前，纯大师规则 3）
ATTACH DATABASE 'F:\Minecarft\游戏王mod工作区\inputs\mdpro3\Data\pack\pack.db' AS pk;
SELECT d.id, t.name, t.desc, d.type, d.atk, d.def, d.level, d.race, d.attribute
FROM datas d
JOIN texts  t ON d.id = t.id
JOIN pk.pack p ON d.id = p.id
WHERE p.date <= '2017-03-24'
  AND NOT (d.type & 0x4000000)      -- 排除连接怪兽（A 方案下应为 0 行）
ORDER BY p.date;
```
类型位参考：`1`=怪兽 `2`=魔法 `4`=陷阱 `0x10`=通常 `0x20`=效果 `0x40`=融合 `0x80`=仪式 `0x2000`=同调 `0x800000`=超量 `0x1000000`=灵摆 `0x4000000`=连接

---

## 10. 给接手人的第一件事

**只做一件事：拿到 `ocgcore.dll` 的权威函数签名。**

推荐从**路径 1** 入手（解析 `Assembly-CSharp.dll` 的 `[DllImport]` 声明）——MDPro3 用这份 DLL 跑得通，它的声明就是唯一可信来源，能一次性解决 `new_card`、`card_data`、`start_duel`、大师规则四个未知数。

拿到后回到 `step9_verify.py`，先用 `query_field_count` 确认 `new_card` 生效（这一步**不需要**跑通 `start_duel`，是最快的验证闭环），再推进到 `start_duel`。

**建议的判停条件**：若路径 1 与路径 2 都拿不到签名，且路径 3 的暴力搜索在 2 天内无进展，则应考虑**更换内核来源**——直接用上游 `edo9300/ygopro-core` 自行编译一份干净的 `ocgcore.dll`，从而回到公开且有文档的 API。这比逆向一个改造过的 fork 更划算。

---

## 11. 未确认事项（诚实标注）

1. `dotnet` / `monodis` / `ikdasm` 是否可用 —— **探测命令被中断，未确认**
2. `Assembly-CSharp.dll` 的 P/Invoke 声明内容 —— 未提取
3. `OCG_NewCardInfo` / `OCG_CardData` 是否与 `ocgcore-ffi` 文档一致 —— **实测不一致，未解**
4. `start_duel` 的第二个参数真实类型 —— 未确认
5. 大师规则 3 的设置方式 —— 未确认
6. MC `NativeImage` 能否读调色板 PNG —— **未验证**（决定卡图 274 MB vs 825 MB）
7. 8201 张卡对应的 Lua 脚本是否全部存在于 `script.zip`（13540 个）—— 未逐卡核对
8. 本体 `CardArtTextures` 的 `MAX_LIVE_TEXTURES` 具体数值 —— 未提取
