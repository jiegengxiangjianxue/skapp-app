# ADR-0004 引擎拆成四层模块，逐层构建再组合

- 日期：2026-10-27
- 状态：已采纳

## 背景

`:engine` 原本是一个 34 文件 / 8783 行的单模块，里面混着四种完全不同的东西：
数据模型、云端抓取、指标算法、选股产出。后果很具体：

- 改一个指标的字符串，整包重编；出问题也分不清是「拉数据拉错了」还是「算错了」还是「组装错了」。
- 想单独验证某个算法，只能跑整个模块的测试；测试失败时看不出是哪一层挂了。
- 分层只是注释里的口头约定，没任何东西拦得住「算法层偷偷去读屏幕缓存」。

用户诉求原话：**把每个算法、引擎、链路拆分开来构建，慢慢组合构建起来。**

## 决策

按数据流方向切四层，单向依赖，`api()` 逐级上浮：

```
:engine-core     模型 / 交易时段 / JSON 扁平化 / 进程健康核心      （零依赖）
    ↑
:engine-net      云端 JSON、行情快照、日 K、资金流、消息抓取        （只取数，不下判断）
    ↑
:engine-algo     指标、因子标定、12 引擎、情景与校准                （纯计算，不联网、不缓存）
    ↑
:engine-runtime  缓存、全市场筛选、盘前/午盘推股产出                （唯一允许「组合」的层）
    ↑
:engine          聚合层：只 api() 暴露四层，:ui / :app 的唯一入口
```

| 层 | 文件 | 判定标准 |
|---|---|---|
| core | Models, MarketHours, JsonPlain, Code, health/ProcessHealthCore | 不 import 协程调度/网络/上层 |
| net | RemoteSources, SkappApi, HoloClient, MarketDataClient, ExtendedDataClient, KlineApi, SiteBoardClient, CloudFeed | 只把真实数据取回来并解析 |
| algo | ExtendedIndicators, FactorWeightStrategy, SignalCalibration, LocalEngine, MarketStateEngine, ScenarioEngine, SectorLink, SmartMoney, AskEngine, KeyLevels, RecoupCalc, MysticEngine, CloudEngines, SampleBalancer | 输入 K 线/标定，输出分数，无 IO |
| runtime | DataCache, LocalScreener, LocalPicks, NewsCalcEngine, EngineFeed, StrategyCard, BanProb, FactorBoard | 允许同时用 net + algo 组装产物 |

每层可独立验证，`:engine` 只跑「组合冒烟」（`LayerCombineTest`）。

## 拆分时被迫修掉的两个真问题

1. **模型层倒挂依赖网络层**：`SymbolIndex`（core）调用 `SkappApi.normalize`（net）。
   归一化是纯字符串处理，抽成 core 的 `Code.normalize`，`SkappApi.normalize` 保留同名入口委托过去。
   这个问题不拆模块永远看不见——同一模块里反向依赖编译得过。
2. **`JsonPlain` 的 `internal` 扩展跨不了模块**：`toPlainMap/toPlainList/toPlainMapList`
   被 net 里四个客户端共用，`internal` 在模块拆分后立刻变成「未解析引用」，改为 public。

顺带暴露的一类编译错误：跨模块后 `x.foo != null` 不能再智能转换 `x.foo`（`foo` 是别的模块的
public 属性），`AskEngine` / `NewsCalcEngine` / `EngineFeed` 三处改成先落局部值再判空。
其中 `AskEngine` 与 `NewsCalcEngine` 的「标题→股票代码」逻辑完全同构，是下一轮可以合并的重复代码。

## 代价

- 模块数从 3 变 7，构建配置多 4 份（每份 ~35 行，内容刻意重复而不是抽公共插件：
  层边界是要被看见的，越显眼越好）。
- `:engine` 变成无源码的聚合层，`:engine/build/classes` 为空，org.json 守卫要改为逐层扫。
- 测试 fixture 从 `engine/src/test/resources/` 挪到仓库根 `testdata/`，各模块通过
  `skapp.fixtures` 系统属性定位——避免每层抄一份、也避免路径写死在测试代码里。

## 守护

CI（`.github/workflows/android-build.yml` 的 `engine-kotlin` job）：

- 四层 + 聚合层一律不得 import `android.*` / `androidx.*`，不得依赖 `:ui`；
- 依赖方向用脚本校验（反向依赖直接红），不靠人记；
- 按层执行：core:test → net:compile → algo:test → runtime:test → 聚合 test。
