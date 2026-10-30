# 网站 vs 软件 功能对照 (2026-10-23 凌晨盘点)

| 功能 | 网站 | 软件 (skapp) | 差距动作 |
|---|---|---|---|
| 实时行情 | 10分钟 pages-data-sync | 30s 轮询直连腾讯 | 软件 ✓ 更实时 |
| 选股 | 云端快照 (旧) | 本地实时筛选 v2 (腾讯批量+并发) | 软件 ✓ |
| 技术指标 | 网页渲染 | LocalEngine 11 引擎本地算 | 软件 ✓ |
| 回本率 | 无 | RecoupCalc 实时算 (22/60日) | 软件 ✓ 独有 |
| 十关键位 | 无 | KeyLevels.kt 端侧 (已推待接 UI) | 明早挂载 |
| 中枢投票 | 无 | CloudEngines 15 引擎 | 明早挂载 |
| AI 问答 | 云端 | GitCodeAiClient (用户 token) | ✓ |
| 龙虎榜 | 无 | 云端 dc_engines → 软件待接 | 明早 |
| 双主线 | 无 | 云端 dual_theme → 软件待接 | 明早 |
| 涨停池 | 无 | 同花顺 dataapi 已通 | 明早接 UI |
| 本地 LLM | 无 | 计划 Qwen3-0.6B | 排期 |

## 明早必做 (CI 绿之后)
1. PicksScreen 加"关键位卡" (KeyLevels.compute → 十位显示)
2. PicksScreen 加"中枢投票卡" (LocalScreener.screenV2 → 已有 engine 数据)
3. 龙虎榜/涨停池/双主线 → EngineFeed 三个新 feed (走 GitHub data/ JSON 直读, 零 token)
