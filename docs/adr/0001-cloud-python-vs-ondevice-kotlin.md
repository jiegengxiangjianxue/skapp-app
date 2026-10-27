# ADR-0001：云端 Python 重计算 / 端上 Kotlin 轻量应用的分工

- **状态**：Accepted
- **日期**：2026-10-27
- **决策者**：项目负责人 + 开发
- **相关**：`docs/RELEASE.md`（模块边界）、`engine/src/test/kotlin/com/skapp/app/data/PerfProbeTest.kt`（性能基线）、`stocks-ai-view`（云端仓）

## 背景

系统由两个仓组成，职责边界长期靠口头约定，导致过两次返工：

- **`stocks-ai-view`**（云端）：368 个 Python 脚本，负责全市场数据抓取、板块/成分股管线、模型标定、题材挖掘、AI 总结等重计算，产物以 JSON 落地（raw/CDN 可直取）。
- **`skapp-app`**（端上）：Kotlin，三模块 `:app → :ui → :engine`。`:engine` 是纯 JVM 引擎（禁止 `import android.*`、禁止依赖 `:ui`，CI 有守卫）。

历史踩坑：早期端上尝试「云端引擎评分」——把重计算搬到端上再展示，结果是数据错、启动空、指标共振/多维评分变成 CI 快照。根因不是算得慢，而是**端上根本不该承担需要全市场数据和频繁重标定的计算**。

另一侧的误判：进决策页容易闪退，最初怀疑「Kotlin 算 16 指标太慢，该换 Rust/NDK」。加了 `PerfProbeTest` 实测后发现**单只票打分仅 0.2ms**（8 只合计约 1.6ms），纯计算远不是瓶颈；真实原因是那段计算跑在 `viewModelScope`（Main）上阻塞主线程、被判 ANR。改成离开主线程后问题消失，换语言完全解决不了。

## 决策

1. **重计算留云端**：需要全市场扫描、跨标的聚合、模型标定、长窗口回测、LLM 调用的，一律在 `stocks-ai-view` 出产物，端上只消费 JSON。
2. **端上只做轻量即时计算**：单票 16 指标打分、本地规则筛选、涨幅/涨停过滤、排序等 O(单票) / O(百级) 计算，Kotlin 直算，不引入 NDK。
3. **端上禁止云端兜底式重算**：不得以「云端拿不到就端上重算一遍」为由把全市场管线搬进 APK。
4. **卡顿优先查调度与 IO，不查语言**：任何「性能不行」的结论必须先用 `PerfProbeTest` 或 profile 证明是 CPU 密集，才允许讨论换实现。

## 上 Rust / NDK 的触发条件

以下条件**任一条被实测数据满足**才启动 Rust 评估，否则一律留在 Kotlin。判定必须用数据，不靠体感：

| # | 触发条件 | 当前实测 | 余量 | 判定方式 |
|---|---|---|---|---|
| 1 | 单票 16 指标打分 **> 5ms** | 0.2ms | 25× | `PerfProbeTest` 输出 |
| 2 | 决策页 8 只票计算总耗时 **> 50ms** | ≈1.6ms | 30× | `PerfProbeTest` × 目标数 |
| 3 | 需端上扫描 **> 1000 只**且要求 **< 1s** | 不做（云端负责） | — | 需求评审 |
| 4 | profile 显示 **CPU 计算占端上卡顿 > 30%** | 卡顿来自线程调度/网络 | — | Android Studio Profiler / systrace |

附加约束：

- 条件 1/2 必须**连续两个版本**超标才动手，排除机型抖动。
- 引入 Rust 需同时接受：APK 体积与 ABI 分裂（arm64 only 政策见 `docs/RELEASE.md`）、CI 增加交叉编译链、`:engine` 不再「纯 Kotlin/JVM 可独立构建」——这会破坏现有模块守卫，属于**架构级代价**，须单独立 ADR。
- 若瓶颈在网络（如 8 次日 K 串行），正确解法是协程并发，**不是**换语言。

## 后果

**正面**

- 端上 APK 保持小体积、纯 Kotlin、CI 单链可验。
- 重计算可独立迭代（Python 侧改完不用发版），模型标定与端上解耦。
- `PerfProbeTest` 作为长期基线，给「要不要上 Rust」提供可复核的数据而非猜测。

**负面 / 已知取舍**

- 端上能力受云端产物新鲜度限制；云端停更会直接反映为端上数据陈旧（`CloudFeed.healthText` 暴露健康度）。
- 离线场景只能靠端上已有缓存 + 轻量计算，无法给出全市场级结论。
- `PerfProbeTest` 只打印不断言（故意），因此 CI **不会因性能劣化变红**，需人工定期看日志——这是为了不误伤慢机型，代价是回归靠自觉。

## 参考

- 基线用例：`engine/src/test/kotlin/com/skapp/app/data/PerfProbeTest.kt`（`./gradlew :engine:test` 执行，已纳入 CI `engine-kotlin` 阶段）
- 模块守卫：CI「Guard - engine module must stay Android-free」
- 发版与模块边界：`docs/RELEASE.md`
