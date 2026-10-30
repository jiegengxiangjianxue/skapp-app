# 数据与仓库映射

## GitHub 数据库对接
| 仓库 | 内容 |
|---|---|
| db-daily-0..5 | A股日线分片 wh/daily/{YYYY}.parquet (zstd) |
| db-fin-0 | 财务全史 |
| db-min-0/1 | 5分钟线月分区 |
| db-hk-0 | 港股日线 |
| db-cnflow-0 | 资金流 |

算法 canonical 源：jiegengxiangjianxue/algo-core、jiegengxiangjianxue/engine-core。
本仓 `skapp_pkg/` 为集成层（import 或 vendored），不重复实现因子。

## 数据源（实时）
- 腾讯 fqkline / qt：A 股 + 港股（hk 前缀）实时，限速 ≤4 并发 0.25s 间隔，501 时切 ifzq.gtimg.cn
- 新浪：日线主源（需 Referer: finance.sina.com.cn，GBK），全市场列表 Market_Center.getHQNodeData
- 东财 push2：部分出口 IP 403，一律备新浪 fallback

## 收盘后快照流程（Actions）
1. 15:35 后触发（人工 dispatch 兜底）
2. skapp_pkg/pipeline/eod_sync.py 生成当日快照：行情快照 + 消息分析 + 推股 + 推股复盘（用前一日快照结算）
3. 写入 `data/snapshots/YYYY-MM-DD.json`（幂等）
4. 删除 `data/live/` 当日明细，仅保留快照

## API（供 AI 接入）
- `GET /api/v1/quote/{symbol}` 实时行情（A股+港股）
- `GET /api/v1/analysis/{symbol}` 综合分析（引擎+因子）
- `GET /api/v1/picks` 当日推股
- `GET /api/v1/review/{date}` 推股复盘
- `GET /api/v1/snapshot/{date}` 快照
- `POST /api/v1/analyze` 自定义股票列表批量分析
