# skapp

选股推股股票软件（Android，Kotlin + Jetpack Compose）。

## 功能
- **行情**：自选股实时报价（腾讯行情，30s 自动刷新）+ 全市场搜索
- **推股**：引擎推股清单（指标共振 signals / 多维评分 values），含评分与理由
- **决策**：市场状态 + 决策卡（入场/止损/目标/持有天数/风险）
- **提问**：自然语言问个股，答案只来自云端真实数据 + 预生成 AI 问答
- **个股详情**（底部抽屉）：日 K 线图（前复权）、技术参数（MA/MACD/RSI/量比）、
  财务摘要、引擎评分、所属板块、AI 问答、数据源与时点

## 数据来源（实时优先）

分析链路的输入只有一种：**盘中实时数据**（东财 clist 全市场盘口 → 日 K → 本地 12 引擎）。
云端只扮演「库」的角色，且只能经 `Db` 这一个门进来：

| 类别 | 入口 | 说明 |
|---|---|---|
| 实时行情 | `LiveMarket` | 全市场盘口（价/涨跌幅/量比/成交额/主力净流入），盘中 5s 缓存，全局共享一份 |
| 市场脉搏 | `LivePulse` | 涨跌家数 / 涨跌停 / 中位涨幅 / 资金榜 → 相位分（-100..100） |
| 库：代码表 | `Db.symbols()` / `Db.stockNames()` | 一天一变，实时接口给不全 |
| 库：映射表 | `Db.sectorTables()` | 板块成分 / 行业映射（关系表，不是当日行情） |
| 库：标定参数 | `Db.calibration()` | 5~6 万样本回归出来的权重系数 |
| 库：披露数据 | `Db.etfShares()` | ETF 份额 |
| 归档（只回看） | `Db.archive()` | engine_v5 / decision / close_review：**已发生的事实，不当今日判断** |
| 引擎池（本地装配） | `EngineFeedLive` | 实时脉搏 + 本地候选 → 与云端同构的 engine_v5/phase/decision，`source=live` |
| 首屏三张卡 | `LocalSnapshot` | 推股 / 市场概览 / 决策均由本地实时数据生成，不再读云端快照 |
| 盘中心跳 | `MarketHeartbeat` | 30s 全股盘口（相位/涨跌停/资金）+ 5min 候选池深算，节拍由 `HeartbeatPolicy` 管 |
| 板块强弱 / 看板 | `LocalSectors` | 实时快照 × 库里成分映射现算板块涨幅/热度/涨停，概念板块需归属表（本地不可得，明标） |
| 批量报价 | `LiveQuote` | 自选/推股/决策补价先吃全市场快照，覆盖不到才补拉 |
| 板块周期 / RPS / 策略卡 / AI 精选 | `LiveExtras` | 板块横截面分档、60 日涨幅百分位、本地策略卡、本地评分精选（`basis=local-score`，非 LLM） |
| 库表门面 | `DbTables` | 界面唯一能碰云端的入口：归档类只回看、配置表类读库、明细类按需查 |

守卫：`python3 tools/layer_source_guard.py`（CI 自动跑）——`CloudFeed.` 只允许出现在
`CloudFeed.kt` 与 `Db.kt`；`engine-algo` / `engine-runtime` 出现 `SkappApi.` 直接失败。
决策见 `docs/adr/0005-live-vs-db-data-sources.md`。

## 云端静态产物清单（库/归档用途）
以下文件由云端 CI 生成，App 侧仅经 `Db` 读取，且不参与当日实时分析：

| 文件 | 仓库 | 说明 |
|---|---|---|
| `data/data/decision.json` | qwerzxcva/stocks-ai-view（主）· dufuwuxin/stocks-ai-view-c（镜像） | 市场决策 |
| `data/data/today_picks.json` | 同上 | 当日推股 |
| `data/data/stock_names.json` | 同上 | 6027 只代码→名称 |
| `data/data/search/symbols.json` | jiegengxiangjianxue/skapp-data | 5575 条全市场代码表 |
| `data/api/stocks/<code>.json` | stocks-ai-view | 个股详情（bundle + 板块 + 评分） |
| `data/api/qa/<code>.json` | stocks-ai-view | AI 问答（GitCode 免费 AI） |

上述文件由 `stocks-ai-view` 的 CI（`.github/workflows/skapp-app-data.yml`，10 分钟一次）
与 `skapp-data` 的 `skapp-symbols.yml` 自动生成。

## 构建
```bash
./gradlew :app:assembleDebug
```
产物为 **arm64-v8a** APK（纯 Kotlin/Compose，无 native 代码；构建在 GitHub
`ubuntu-24.04-arm` runner 上完成，APK 同样可在 arm64 设备运行）。

### 模块分层（引擎按「链路 / 算法 / 产出」拆开单独构建）

依赖方向单向，反向依赖会直接编译失败：

```
:engine-core     数据模型 / 交易时段 / JSON 扁平化 / 进程健康核心（零依赖）
    ↑
:engine-net      数据链路：云端 JSON、行情快照、日 K、资金流、消息抓取
    ↑
:engine-algo     算法：指标、因子标定、12 引擎、情景与校准（纯计算）
    ↑
:engine-runtime  产出：缓存、全市场筛选、盘前/午盘推股
    ↑
:engine          聚合层（只 api() 暴露下面四层，:ui 的唯一入口）
    ↑
:ui → :app
```

一层一层验证，坏了立刻知道是哪一层：

```bash
./gradlew :engine-core:test            # 模型 / 时段 / JSON
./gradlew :engine-net:compileKotlin    # 数据链路（不打网络的编译层验证）
./gradlew :engine-algo:test            # 指标 / 因子 / 12 引擎（跑真实 K 线 fixture）
./gradlew :engine-runtime:test         # 缓存 / 筛选 / 推股产出
./gradlew :engine:test                 # 组合冒烟：四层串成一条链路
./gradlew :ui:compileDebugKotlin       # 界面层接上聚合层
```

测试 fixture 统一在仓库根 `testdata/`（各模块通过 `skapp.fixtures` 系统属性定位），
不再每个模块抄一份。分层决策与代价见 `docs/adr/0004-engine-layer-split.md`。

## 数据纪律
所有字段缺失即显示「暂无数据」或 `insufficient`，**绝不编造**。

### 在 ARM 机器上构建 APK

AGP 只发 x86_64 版 aapt2，ARM64 环境会报 `AAPT2 Daemon #0: Daemon startup failed`：

```sh
./gradlew :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$PWD/tools/aapt2-qemu/aapt2
```

依赖 `qemu-user-binfmt` + `libc6:amd64 libstdc++6:amd64 zlib1g:amd64`。x86_64 CI 直接用原生 aapt2。
