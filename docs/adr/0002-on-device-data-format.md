# ADR-0002：端上数据交换格式（JSON 仍是默认，二进制/列式须满足触发条件）

- **状态**：Accepted
- **日期**：2026-10-27
- **决策者**：项目负责人 + 开发
- **相关**：ADR-0001（换实现前先排除数据格式与调度）、`engine/src/test/kotlin/com/skapp/app/data/PerfProbeTest.kt`（新增 `JSON 解析耗时` 探针）、`RemoteSources.kt`（产物地址）、`SiteBoardClient.pools()`（并发拉取消费方）

## 背景

云端产物目前 100% 是 JSON：Python 直出、GitHub raw / CDN 直取、端上用 `org.json` 解析。好处是可读、可手改、云端不用为端上多构建一套产物。

问题是**解析比计算贵得多**。2026-10-27 在本机（8 核 aarch64 / JVM 17）实测：

| 探针 | 结果 | 出处 |
|---|---|---|
| 单只票 16 指标体系打分（140 根日 K） | **0.167 ms**，8 只合计 1.3 ms | `PerfProbeTest.单只票打分耗时` |
| JSON 解析 | **24.9 ms/MB**（`stock_sector_map.json` 0.08 MB → 1.954 ms/次） | `PerfProbeTest.JSON 解析耗时` |
| 多池选股首屏并发拉取的产物合计 | **3.3 MB**（`api/sectors.json` 1.27 MB、`good_stock_pool.json` 639 KB、`api/news.json` 313 KB、`engine_v5.json` 229 KB、`news_impact.json` 216 KB…） | 逐文件 `Content-Length` 实测 |

按 24.9 ms/MB 折算，多池首屏光解析就约 **82 ms**（桌面 JVM；真机通常更慢）。而同一页面真正需要的计算是「几只票 × 0.167 ms」量级。**解析开销比计算高两个数量级**，所以性能问题的处理顺序应当是：减少/压缩传输 → 改数据格式 → 最后才轮到「算法/语言」。

这里有个已经踩过的误判要记下来：曾把端上卡顿归因为「Kotlin 算 16 指标太慢，该上 Rust/NDK」，实测后发现计算只有 0.2 ms，真因是计算跑在 `viewModelScope`（Main）上阻塞主线程被判 ANR。**换语言从来没解决过这类问题**（详见 ADR-0001）。

另一个已存在的正向先例：预测归档 `engine_v5_<YYYYMM>.jsonl.gz` 用 JSONL + gzip，端上不需要把整月数据当一个大 JSON 解析。

## 决策

1. **默认继续用 JSON**。可读性、云端零额外构建、CDN 直取、出问题能肉眼比对——这些收益远大于当前的解析开销。不为性能提前换格式。
2. **先做传输层，再谈格式**。HTTP gzip 是成本最低的一档（历史坑：`HttpURLConnection` 默认不请求 gzip，东财按 gzip 返回却没解压，表现为乱码→空数据）。能在传输层压掉的体积，不要靠换二进制格式解决。
3. **大产物用 JSONL + 流式**，不要整包 `JSONObject(text)`。已落地：预测归档；后续新增大产物沿用。
4. **引入二进制/列式格式（Parquet / Arrow / CBOR / MessagePack 等）必须同时满足下方触发条件**，并且单独再立一条 ADR——因为代价是架构级的：云端要多产出一套产物并双写过渡、端上要加解析依赖、`:engine` 的「纯 Kotlin/JVM、不依赖 Android」守卫要重新复核。
5. **端上禁止「全市场 JSON 整包解析」**。需要全市场范围的结论一律在云端算完再下发（与 ADR-0001 决策 1/3 同源）。

### 触发条件（任一条被实测数据满足才启动格式迁移）

| # | 触发条件 | 当前实测 | 判定方式 |
|---|---|---|---|
| 1 | 单次进入页面**解析量 > 3 MB**，或**解析耗时 > 200 ms**（真机） | 多池 3.3 MB / 桌面约 82 ms | `PerfProbeTest` 的 ms/MB × 实拉体积；真机用 Profiler 复核 |
| 2 | 单个产物 **> 5 MB** 且属于每次启动必拉的路径 | 最大 1.27 MB（`api/sectors.json`） | 产物体积巡检（HTTP `Content-Length`） |
| 3 | profile 显示**解析占端上卡顿 > 30%** | 未测 | Android Studio Profiler / systrace |
| 4 | gzip 压缩后仍 **> 2 MB** 且处于热路径 | 否 | 拉取体积统计 |

附加约束：

- 条件 1 需**连续两个版本**满足才动手，排除机型抖动与单次网络抖动。
- 动手前必须先排除更便宜的解法：**gzip → 拆文件（按行业/概念拆分而非一个大 sectors.json）→ JSONL 流式**。这三步的成本比引入列式格式低一个数量级。
- `:engine` 若引入第三方解析库，必须仍是纯 JVM 依赖（不得带 `android.*`），CI 的模块守卫会拦。

## 后果

**正面**

- 端上保持零额外解析依赖，`:engine` 继续「纯 Kotlin/JVM 可独立构建」。
- 解析开销有了可复核口径（`PerfProbeTest.JSON 解析耗时` 打印 ms/MB），乘线上实际体积即可判断该不该动手，不靠体感。
- 明确挡住「性能不行就换格式/换语言」的跳跃式结论。

**负面 / 已知取舍**

- JSON 体积仍偏大：多池首屏一次 3.3 MB，弱网下首屏慢。按当前触发条件还不该换格式，但**拆文件**是随时可做且低风险的改进（`api/sectors.json` 1.27 MB 是最该瘦身的单个产物）。
- `org.json` 不支持流式，大文件只能整包驻留内存。弱内存机型上「3.3 MB × 解析放大」可能触发 `OutOfMemoryError`，而 `runCatching` 抓不住 `Error`——这是 2026-10-27 端上闪退排查中的待验证假设，不是结论。
- 只打印不断言：解析劣化不会让 CI 变红，需要人工定期看日志。

## 参考

- 基线探针：`engine/src/test/kotlin/com/skapp/app/data/PerfProbeTest.kt`（`./gradlew :engine:test`，已纳入 CI `engine-kotlin` 阶段）
- 产物地址登记：`engine/src/main/java/com/skapp/app/data/RemoteSources.kt`
- 并发拉取消费方：`SiteBoardClient.pools()`（18 个 `async(Dispatchers.IO)`）
- 已用 JSONL+gzip 的先例：`engine_v5_<YYYYMM>.jsonl.gz`
- 语言选型与调度优先级：ADR-0001
