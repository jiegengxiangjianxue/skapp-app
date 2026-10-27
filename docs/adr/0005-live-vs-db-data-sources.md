# ADR-0005 数据来源三分：实时 / 库表 / 历史归档

- 日期：2026-10-27
- 状态：已采纳

## 背景

用户的要求一句话：**本地实时获取实时分析，不要使用快照和云端；只有数据库可以调用云端。**

拆模块（ADR-0004）之后能看清问题在哪：引擎链路里混着三种性质完全不同的数据，但都长得像「JSON」，
于是被一视同仁地当输入喂进算法：

| 性质 | 例子 | 问题 |
|---|---|---|
| 当日行情 | 涨跌幅、量比、资金流 | 走的是云端 `api/market` 快照，收盘后才更新——盘中打开看到的是几小时前甚至昨天 |
| 关系表/参数 | 板块成分、权重标定 | 其实天生该当「库表」用（一天/一周才变），却和行情混在一起读 |
| 历史归档 | engine_v5、decision、close_review | 是**已发生的事实**，却被当成今天的判断依据 |

## 决策：三分法

```
实时（Live）  ── LiveMarket ── 东财 clist 全市场盘口：价/涨跌幅/量比/成交额/换手/主力净流入
                     │
                     ├─→ LivePulse      市场脉搏：涨跌家数、涨跌停、中位涨幅、资金榜 → 相位分
                     ├─→ LocalScreener  全市场筛选 → 逐只日K → LocalEngine 12 引擎
                     └─→ StrategyCard   策略卡候选（pos240 / 非妖股）

库表（Db）    ── Db ────────── 全 App 唯一能碰云端的门：
                     ├─ symbols / stock_names      代码表
                     ├─ sectorTables()             板块成分 / 行业映射
                     ├─ calibration()              权重标定 / walkforward 系数
                     └─ etfShares()                ETF 份额（交易所披露）

归档（Archive）── Db.archive() ─ 云端已发生的产物，只作回看/对照/兜底展示，**不得当今日判断**
```

## 本轮实际改动

1. **`LiveMarket`（:engine-net）**：把原先写在 `LocalScreener.marketSnapshot` 里的 clist 抓取上收
   为全 App 唯一的实时行情源，带 5s/60s 分级 TTL。原先选股、策略卡、界面各拉一遍全市场
   （3×十几页请求，且三处时点不一致），现在一次刷新只拉一份。涨停判定按板块上限
   （10/20/30cm、ST 5cm），余量按上限的 3.5% 取——写死 0.35 会让 30cm 板永远判不出涨停。

2. **`LivePulse`（:engine-algo）**：市场脉搏纯函数。宽度项（涨跌家数占比 ±60）+ 幅度项
   （中位涨幅 ×8，夹 ±20）+ 涨停项（涨停×1.2、跌停×2，各封顶 20）→ -100..100 相位分。
   用中位数不用均值：一只妖股不该把全市场的情绪读数带偏。跌停权重是涨停的两倍——
   同样家数，跌停对情绪的杀伤更大。连板高度/晋级率/封板率需要历史涨停序列，**实时拿不到就不给**，
   不猜。

3. **`MarketStateEngine.computeLive()`**：市场状态改走实时路径（phase 来自 LivePulse、global 来自
   实时外围指数）。缺的部件如实进 `missingInputs`，并且**权重按可用部件归一到 1**——
   旧实现缺部件时按 0 算，等于把那一份权重永久抹掉，E 系统性偏保守且无法解释。
   `compute()` 保留为遗留云端路径（对照用）。

4. **`Db`（:engine-net）**：云端调用收口到这一个对象。`CloudFeed.` 现在只出现在
   `CloudFeed.kt`（实现）和 `Db.kt`（门）两个文件里；`SectorLink` / `SignalCalibration` /
   `SmartMoney` / `BanProb` / `EngineFeed` / 界面的 `SkappViewModel` 都改成经 `Db` 取。

5. **`tools/layer_source_guard.py`**：把约束变成 CI 里的可执行规则——
   `CloudFeed.` 只许出现在 Db 两个文件；`engine-algo` / `engine-runtime` 出现 `SkappApi.` 直接红。

## 诚实交代：还没迁完的部分

- **界面层还有 41 处**云端调用点（`SkappViewModel` / `EnginesScreen`：决策卡、池页、主题报告、
  收盘复盘的展示）。守卫对它们只报数量不失败，作为「待迁移清单」逐轮收敛。
- **`EngineFeed`** 仍是云端归档解析器（engine_v5 / rps / strategy / phase / global / aipick）。
  它的**入口已经收进 Db**，但「当日引擎池」还需要一段本地装配（LocalScreener 结果 + LivePulse
  → 同构 payload）。这是下一步，不是已经完成。
- **cycle_v2（板块周期池）** 需要板块历史序列，实时接口给不了，目前如实标 missing。

## 后续落地（第二轮：引擎池 / 心跳 / 首屏）

按用户指定的 a→c→b 顺序推进，三项完成度：

**A. 当日引擎池本地装配 `EngineFeedLive`（已完成）**
`LiveMarket` → `LivePulse` + `LocalScreener` → 与云端 payload **同构**的
engine_v5 / market_phase / global_markets / decision，`EngineFeeds.source` 标 `live`。
拿不到的（cycle_pool / rps_rotation / strategy_card / ai_pick / 连板高度·晋级率·封板率）
进 `missing` 并写明原因，不用 0 或旧值冒充。界面 `loadEngineFeeds()` 已切到这条路径，
不再有「云端 engine_v5 当今日结论」。

**C. 盘中心跳 `MarketHeartbeat`（已完成）**
把「实时」从「点一下刷新」变成「一直在跑」：30s 一轮全股盘口（约 11 页批量请求）→ 相位/涨跌停/资金榜；
5 分钟一轮候选池深算（≤30 只日 K + 12 引擎）。节拍抽成 `HeartbeatPolicy` 纯类可测——
这段最容易被改回「每轮循环都拉一遍」，测试里看不出、线上只会表现成接口被封。
与原有 `startMarketPulseLoop` 合并为一个循环，不再双开。

**B. 界面云端调用迁移（已完成，38 → 0 处）**
按「池页+板块 → 消息面」顺序收敛：

| 界面取数 | 迁移后 |
|---|---|
| 首屏推股 / 市场概览 / 决策 | `LocalSnapshot`（LocalPicks 产物 + LivePulse + 本地候选） |
| 看板（涨跌榜/涨停池/板块热度/宽度） | `LocalSectors`（实时快照 × 库里的成分映射现算） |
| 板块强弱（industries） | `LocalSectors.compute()`，映射缺失才回退 `DbTables.sectors()` 并标回看 |
| 自选/推股/决策补价 | `LiveQuote`（先吃全市场快照，零额外请求；覆盖不到的才补拉） |
| 代码归一化 / 行情前缀 / 直查 | `Code`（纯字符串规则，从网络层下沉到 core） |
| 复盘/胜率/消息/主题/简评/资金流归档 | `DbTables`（库表门面，只回看） |
| 单票档案 / 财务 / 问答 | `DbTables`（按需单票查询） |
| 消息面 | 抓取走设备本地多源（东财/爱股票/金融界），影响计算 `NewsCalcEngine` 本地算，云端库表只作补充 |

守卫同步收紧：界面层云端调用从「只报数量」改为**硬失败**（0 处），且只统计 **调用**
不统计类型引用（`SiteBoardClient.Pool` 这类渲染用类型不误伤）。

## 代价

- 多了一次全市场实时抓取的依赖：`LiveMarket` 拿不到数据时，选股/相位/策略卡会同时空缺
  （宁缺毋假）。缓存策略是「拉不到给上一份并保留真实抓取时间」，上层可以标「陈旧」。
- `Db` 成了一个热点对象，新增云端库表必须在这里登记——这是刻意的：**加一个库表要动一个文件**
  才看得见「我们又多依赖了一份云端数据」。

## 补记：A 阶段报「不可得」的四块，重新核对原料后补掉三块半

A 阶段把 `cycle_pool` / `rps_rotation` / `strategy_card` / `ai_pick` 整块列进 `missing`，
理由是「需要全市场长周期序列」。复核数据后发现有一半是**漏算**，不是算不了：

| 块 | 原料 | 现状 |
|---|---|---|
| `rps_rotation` | 东财 clist 的 **f24 60 日涨幅 / f25 年初至今**（与现有批量同一请求） | `rps60` **本地已算**（全市场百分位）；`rps20/120/250` 仍需逐票日 K → 留 null 并写明「不是 0」 |
| `strategy_card` | 现成的 `StrategyCard.scan()`（本来就是本地：快照预筛 + 候选逐只日 K 算 pos240） | **直接接上** |
| `ai_pick` | `LocalScreener` 评分与候选数 | 能给数量与候选，但 `basis=local-score`，**明标「不是 LLM」**，不与云端 LLM 产物比较 |
| `cycle_pool` | `LocalSectors` 板块强弱 + 成交额占比 | 能给**当日横截面**分档（主升/修复/退潮/杀跌/震荡）；历史周期序列仍缺，`note` 写清「不是周期走到第几天」 |
| `market_phase.seal` | clist f15 最高价 + f18 昨收 → 涨停价 | **封板率本地已算** = 涨停 /（涨停 + 炸板）；`firstBoard/ge2/height/promo` 需多日全市场涨停序列，仍缺 |

`missing` 也从「整块写死」改成按实际产出动态计算：能算出来的不再报缺，
只报真正缺的子字段（例：`rps_rotation.rps20/120/250 (需逐票日 K)`），
免得界面把一个「只缺 250 日」的块显示成整块不可用。

## 构建环境注意（ARM 机器）

AGP 只分发 x86_64 版 aapt2，ARM64 容器里会报 `AAPT2 Daemon #0: Daemon startup failed`。
`tools/aapt2-qemu/aapt2` 用 qemu-user 包一层解决：

```sh
./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=$PWD/tools/aapt2-qemu/aapt2
```

x86_64 CI 不需要这层包装。
