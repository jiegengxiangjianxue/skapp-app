# skapp v2 架构 — 模块化联动引擎 + 中枢判断 (2026-10-22)

## 核心原则 (用户定调)
1. **模块内联动**: 每个模块 (消息/选股/情绪/玄学/异动...) 以本模块引擎为最高权重, 其他引擎为次要权重联动
2. **推送独占中枢**: 只有"推股"由全部 12 引擎供结果, 中枢引擎 (orchestrator) 判断推不推
3. **双端**: 软件 (Android) 实时运转引擎, 网页 (GitHub Pages) 静态展示

## 模块设计 (每模块 = 本职引擎(高权重) + 全局联动(低权重))

### 1. 消息模块 (news_module)
- 主引擎: news_impact (消息冲击评分 1.0)
- 联动: sentiment(0.4) + moneyflow(0.3) — 消息发酵看情绪和资金是否跟随
- 数据源: 东财新闻/财联社电报/同花顺 (爬虫), gitcode collect_news_* 可直接复用

### 2. 选股多池模块 (pools_module)
- 主引擎: orchestrator 三池 (防守/轮动/进攻, 1.0)
- 联动: factor117(0.5) + ml_ensemble(0.4) + lowpos_cyq(0.4) + acc_detect(0.3)
- 输出: 各池 top20 + 池间迁移信号

### 3. 情绪模块 (sentiment_module)
- 主引擎: sentiment 温度计 (1.0) + ban_prob 连板实证 (0.8)
- 联动: market_phase(0.5) + calendar(0.3)
- 输出: 冰点/分歧/亢奋阶段 + 仓位建议

### 4. 异动模块 (alert_module)
- 主引擎: acc_detect v2 吸筹/派发 (1.0) + dual_trend 突破 (0.7)
- 联动: moneyflow(0.4) + intraday 量比 (爬虫实时)
- 输出: 实时异动推送 (软件端)

### 5. 玄学模块 (mystic_module)
- 主引擎: calendar_alpha 日历 (1.0) + ban_prob (0.6)
- 联动: sentiment(0.3)
- 诚实边界: 只收数据验证过的 (周二/2月效应), 尾数8已证伪不收

### 6. 推股模块 (picks_module) — 中枢独占
- 输入: 全部 12 引擎结果
- 中枢: orchestrator fuse → 12 票加权 → ≥4 票 high 才推
- 附加闸门: sentiment 冰点→自动降仓 + 反弹剧本判定

## 数据层 (三源)
1. 直接 API: 腾讯行情/东财涨停池 (现有)
2. GitHub 爬虫: db-fin-0(财务)/db-min-0(分钟)/analysis-hub(ai_picks) 定时拉取
3. 本地采集: 软件端每 3 分钟轮询, 收盘 EOD 回填

## 端
- **Android (skapp-app 14 屏为基)**: 实时引擎运转 + 异动推送
- **网页 (GitHub Pages)**: 收盘后静态快照 (json 生成), 每 10 分钟 pages-data-sync
