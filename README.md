# skapp

选股推股股票软件（Android，Kotlin + Jetpack Compose）。

## 功能
- **行情**：自选股实时报价（腾讯行情，30s 自动刷新）+ 全市场搜索
- **推股**：引擎推股清单（指标共振 signals / 多维评分 values），含评分与理由
- **决策**：市场状态 + 决策卡（入场/止损/目标/持有天数/风险）
- **提问**：自然语言问个股，答案只来自云端真实数据 + 预生成 AI 问答
- **个股详情**（底部抽屉）：日 K 线图（前复权）、技术参数（MA/MACD/RSI/量比）、
  财务摘要、引擎评分、所属板块、AI 问答、数据源与时点

## 数据链
app 只读 `raw.githubusercontent.com` 静态 JSON（无服务端）：

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

## 数据纪律
所有字段缺失即显示「暂无数据」或 `insufficient`，**绝不编造**。
