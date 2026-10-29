package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.EngineScore
import com.skapp.app.data.ExtendedIndicators
import com.skapp.app.data.StockDetail
import androidx.compose.foundation.layout.PaddingValues
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.UiState
import com.skapp.app.ui.theme.SkColors

private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown
private val BLUE = SkColors.primary
private val ORANGE = SkColors.chartMa5

/*
 * 详情页「本地计算段」与所有行/卡片辅助 composable，从 StockDetailSheet.kt 拆出。
 *
 * 抽成 ``ColumnScope`` 扩展函数而不是普通 composable：段内用了 ColumnScope 才有的
 * 对齐/权重语义，换成普通 @Composable 会丢作用域、只能到处显式传 Modifier。
 * 拆出去的收益是主文件从 1448 → 450 行，改样式不用在 600 行的 Column 里上下翻。
 */

@Composable
internal fun ColumnScope.DetailTailSections(
    state: UiState,
    d: StockDetail?,
    selectedDate: String?,
    onSelectedDate: (String?) -> Unit,
) {
    // 详情为空时整段不出：这里 return 掉比让每个 SectionCard 各自判空更清楚
    val dd = d ?: return
        // ── 十关键位 + 回本率（端侧引擎，随 K 线本地算，不依赖云端）──
        val kl = state.keyLevels
        val rc = state.recoup
        if (kl != null || rc != null) {
            SectionCard("十关键位与回本率（本地计算）") {
                Column {
                    if (kl != null) {
                        Text(
                            "现价 ${String.format("%.2f", kl.cur)} · ${kl.trade}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        val cells = listOf(
                            "阻力位" to kl.resistance, "压力位" to kl.pressure, "承压位" to kl.bearPressure,
                            "反弹位" to kl.rebound, "筹码位" to kl.chip, "资金位" to kl.moneyCost,
                            "回调位" to kl.pullback, "支撑位" to kl.support, "抄底位" to kl.dipBuy,
                        )
                        cells.chunked(3).forEach { rowCells ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowCells.forEach { (label, value) ->
                                    Column(Modifier.weight(1f)) {
                                        Text(label, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                                        Text(
                                            String.format("%.2f", value),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                                repeat(3 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        if (kl.ladderBelow.isNotEmpty() || kl.ladderAbove.isNotEmpty()) {
                            Text(
                                "台阶下方：" + kl.ladderBelow.take(3).joinToString(" / ") { String.format("%.0f", it) } +
                                    " · 上方：" + kl.ladderAbove.take(3).joinToString(" / ") { String.format("%.0f", it) },
                                style = MaterialTheme.typography.bodySmall,
                                color = SkColors.textSecondary,
                            )
                        }
                    }
                    if (rc != null) {
                        if (kl != null) {
                            HorizontalDivider()
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            "回本率（口径：任意历史日买入，22/60 交易日内最高价≥买入价）",
                            style = MaterialTheme.typography.bodySmall,
                            color = SkColors.textSecondary,
                        )
                        Text(
                            "22 日 " + (rc.recoup22?.let { String.format("%.1f%%", it) } ?: "—") +
                                "　·　60 日 " + (rc.recoup60?.let { String.format("%.1f%%", it) } ?: "—") +
                                "　·　均最高涨幅 " + (rc.avgMaxGain22?.let { String.format("%.2f%%", it) } ?: "—") +
                                "　·　一年位置 " + (rc.posInYear?.let { String.format("%.0f%%", it) } ?: "—"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "样本 ${rc.sample} · 门槛 " +
                                if (com.skapp.app.data.RecoupCalc.gatePass(rc)) "通过" else "不通过（回本率<70% 或 高位且最高涨幅<2%）",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (com.skapp.app.data.RecoupCalc.gatePass(rc)) SkColors.success else SkColors.warn,
                        )
                    }
                }
            }
        }

        // ── 退出纪律（端侧 ExitPlan）──
        // 全市场都在教人买，几乎没人管卖。这块只放机械规则：破位清仓、回吐过半减仓、长下影观察。
        val ep = state.exitPlan
        if (ep != null) {
            SectionCard("退出纪律（本地机械规则，不是预测）") {
                Column {
                    val tone = when (ep.action) {
                        com.skapp.app.data.ExitPlan.ACT_CLEAR -> SkColors.marketUp
                        com.skapp.app.data.ExitPlan.ACT_TRIM -> SkColors.warn
                        com.skapp.app.data.ExitPlan.ACT_WATCH -> SkColors.primary
                        else -> SkColors.marketDown
                    }
                    Text(
                        "今日处置：${ep.action}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = tone,
                    )
                    Spacer(Modifier.height(4.dp))
                    val cells = listOfNotNull(
                        ep.stopLine?.let { "清仓线" to String.format("%.2f", it) },
                        ep.stopDistPct?.let { "距清仓线" to String.format("%+.1f%%", it) },
                        ep.trailLine?.let { "止盈线" to String.format("%.2f", it) },
                        ep.peakDrawdownPct?.let { "距高点回撤" to String.format("%.1f%%", it) },
                        ep.rangePos?.let { "区间位置" to String.format("%.0f%%", it * 100) },
                        ep.lowerShadow?.let { "下影占比" to String.format("%.0f%%", it * 100) },
                    )
                    cells.chunked(3).forEach { rowCells ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowCells.forEach { (label, value) ->
                                Column(Modifier.weight(1f)) {
                                    Text(label, style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            repeat(3 - rowCells.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    ep.reasons.forEach { r ->
                        Text("· $r", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                    }
                    Text(
                        "节奏提示：${ep.timeHint ?: "历史不足，时间窗未算"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
            }
        }

        // ── 技术参数 ──
        SectionCard("技术参数") {
            val t = dd.detail?.technical
            if (t.isNullOrEmpty()) {
                Text("暂无数据", color = SkColors.textSecondary)
            } else {
                t.entries.filter { it.value != null }.forEach { (k, v) ->
                    Text("${techLabel(k)}：${String.format("%.2f", v!!)}")
                }
            }
        }

        // ── 历史资金流 ──
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("资金流", fontWeight = FontWeight.SemiBold)
                val liveLast = state.fundFlowLive.lastOrNull()
                val liveMain = (liveLast?.get("main_net") as? Number)?.toDouble()
                if (liveLast != null) {
                    Text(
                        "当日主力净流入 ${liveMain?.let { String.format("%.2f 亿", it / 1e8) } ?: "暂无"} · ${liveLast["time"]}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (liveMain != null && liveMain >= 0) UP else DOWN,
                    )
                } else {
                    Text("当日实时资金流：暂无数据（非交易时段或接口未返回）", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
                Text(
                    "来源：当日 东财 fflow klt=1（兜底 push2delay）；历史 push2his fflow · 覆盖：${if (state.fundFlow.isNotEmpty()) "ok" else "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                val flows = state.fundFlow
                if (flows.isEmpty()) {
                    Text("暂无数据：资金流接口未返回。", color = SkColors.textSecondary)
                } else {
                    val shown = flows.takeLast(30)
                    FundFlowChart(
                        flows = shown,
                        selectedDate = selectedDate,
                        onSelectDate = onSelectedDate,
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                    )
                    val sumMain = shown.mapNotNull { (it["main"] as? Number)?.toDouble() }.sum()
                    Text(
                        "近 ${shown.size} 日主力净额合计 ${String.format("%.2f 亿", sumMain / 1e8)}（红=净流入，绿=净流出，柱顶为当日金额）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sumMain >= 0) UP else DOWN,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        // ── 所属板块 ──
        SectionCard("所属板块") {
            val boards = state.boardsOf.mapNotNull { it["name"]?.toString() }
            val cloudBoards = dd.boards
            Text(
                "来源：东财 stock/get · 覆盖：${if (boards.isNotEmpty() || cloudBoards.isNotEmpty()) "ok" else "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
            if (boards.isEmpty() && cloudBoards.isEmpty()) {
                Text("暂无数据", color = SkColors.textSecondary)
            } else {
                (boards + cloudBoards).distinct().forEach {
                    Text("· $it")
                }
            }
        }

        // ── 29 因子库 ──
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("因子库（29 因子，本机计算）", fontWeight = FontWeight.SemiBold)
                Text(
                    "对应站点 factor_lib.py",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                if (state.kline.size < 30) {
                    Text("暂无数据：K 线不足 30 根。", color = SkColors.textSecondary)
                } else {
                    ExtendedIndicators.allFactors(state.kline).entries.forEach { (k, v) ->
                        Text(
                            "${factorLabel(k)}：${v?.let { String.format("%.4f", it) } ?: "暂无"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ── 云端财务摘要 ──
        SectionCard("财务摘要（云端 bundle · 仅作补充，本机引擎在上）") {
            val fin = dd.detail?.financialLatest
            Text(
                "来源：cloud bundle · 覆盖：${dd.detail?.status ?: "unknown"} · 时点：${dd.detail?.ts ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.textSecondary,
            )
            if (fin.isNullOrEmpty()) {
                Text("暂无数据：分片 bundle 未覆盖该股。", color = SkColors.textSecondary)
            } else {
                fin.entries.take(12).forEach { (k, v) ->
                    Text("${finLabel(k)}：${v ?: "暂无数据"}")
                }
            }
        }

        // ── 财报后漂移（只展示，不参与评分）──
        dd.detail?.pead?.let { pead ->
            SectionCard("财报漂移（PEAD · 云端事件表）") {
                Text(
                    com.skapp.app.data.PeadEvents.caveatOf(pead),
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                Text(
                    "最近公告 ${pead.announceDate} · SUE " +
                        (pead.sue?.let { "%.2f".format(it) } ?: "暂无数据") +
                        "（${pead.nQuarters} 个季度）",
                )
                Text(
                    "公告后 5/10/20 日：" + listOf(pead.car5d, pead.car10d, pead.car20d)
                        .joinToString(" / ") { v -> v?.let { "%+.2f%%".format(it * 100) } ?: "暂无数据" },
                )
                Text(
                    "本市场样本里最高 SUE 分位 10 日胜率 49.75%、最低分位 54.90%，方向与教科书相反，因此不参与评分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
            }
        }

        // ── 数据来源 ──
        SectionCard("数据来源") {
            Text("实时报价：腾讯 qt.gtimg.cn（兜底 东财 push2 / 新浪 hq.sinajs）")
            Text("日 K：后复权=东财 push2his fqt=2（HTTP 兜底）→ 同花顺 v6/line 02 档 → 腾讯 hfq；前复权/不复权=腾讯 fqkline → 东财 → 同花顺")
            Text("复权口径提示：三家复权基准日不同（东财/腾讯 hfq 比值恒约 0.892），绝对价不可跨源比较，同一张图只用一家")
            Text("指数：中证指数官网 csindex-home/perf/index-perf；公告：巨潮资讯 cninfo（POST）；代码表：上交所/深交所官网")
            Text("分时：腾讯 minute/query（兜底 东财 push2his trends2）")
            Text("资金流：东财 push2his fflow（兜底 push2delay）")
            Text("所属板块：东财 push2 stock/get")
            Text("技术指标与评分：本机计算（MA/MACD/RSI/KDJ/BOLL/ATR/量比）")
            Text("同花顺：d/basic/news/zx 四域（日K/F10/快讯/强势股）")
            Text("问财选股：iwencai.com（自然语言）")
            Text("东财：F10 财务 / 龙虎榜 / 板块资金 / 全球指数")
            Text("新浪：hq.sinajs.cn / vip.stock.finance.sina.com.cn")
            Text("雪球热搜：stock.xueqiu.com")
            Text("云端 bundle：qwerzxcva/stocks-ai-view + 镜像；AI 总结请从唯一入口进入")
        }
}

@Composable
internal fun EngineRow(e: EngineScore) {
    val score = e.score
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(engineName(e.engine), fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                if (score == null) {
                    Text("insufficient", color = SkColors.warn,
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        String.format("%.1f", score),
                        color = scoreColor(score),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when (e.signal) {
                            "bullish" -> "偏多"
                            "bearish" -> "偏空"
                            "neutral" -> "中性"
                            else -> "数据不足"
                        },
                        color = when (e.signal) {
                            "bullish" -> UP
                            "bearish" -> DOWN
                            else -> SkColors.textSecondary
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            e.reasons.take(3).forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = SkColors.textTertiary)
            }
        }
    }
}

@Composable
internal fun AdjustChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else SkColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
internal fun PeriodChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else SkColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}


internal fun engineName(k: String): String = when (k) {
    "trend" -> "趋势"
    "momentum" -> "动量"
    "volume" -> "量能"
    "boll" -> "布林"
    "macd" -> "MACD"
    "supertrend" -> "SuperTrend"
    "keltner" -> "Keltner"
    "choppiness" -> "震荡指数"
    "mfi" -> "MFI资金流"
    "vortex" -> "涡旋"
    "obv" -> "能量潮"
    "position" -> "位置分位"
    else -> k
}

internal fun factorLabel(k: String): String = when (k) {
    "momentum_5d" -> "5日动量"; "momentum_10d" -> "10日动量"
    "momentum_20d" -> "20日动量"; "momentum_60d" -> "60日动量"
    "ret_1d" -> "日涨跌幅"; "ma20_bias" -> "MA20乖离"; "ma60_bias" -> "MA60乖离"
    "rsi_14" -> "RSI14"; "macd_hist_pct" -> "MACD柱强度"
    "vol_ratio_5d" -> "5日量比"; "vol_trend_5_10" -> "量能趋势"
    "obv_trend_20d" -> "量能潮20日"; "amplitude" -> "日振幅"
    "vol_20d" -> "20日波动率"; "downside_vol_20d" -> "下行波动率"
    "close_position" -> "收盘位置"; "gap_return" -> "跳空收益"
    "intraday_return" -> "日内收益"; "dist_high_60d" -> "距60日高点"
    "position_240d" -> "240日分位"; "ret_skew_20d" -> "20日偏度"
    "max_ret_20d" -> "20日最大涨幅"; "up_days_20d" -> "20日上涨天数"
    "overnight_20d" -> "隔夜收益"; "intraday_20d" -> "日内收益20日"
    "amihud_20d" -> "Amihud非流动性"; "vol_price_corr_20d" -> "量价相关"
    else -> k
}

internal fun scoreColor(s: Double): Color = when {
    s >= 65 -> UP
    s <= 35 -> DOWN
    else -> SkColors.textSecondary
}

internal fun techLabel(k: String): String = when (k) {
    "ma5" -> "MA5"; "ma10" -> "MA10"; "ma20" -> "MA20"; "ma60" -> "MA60"
    "rsi14" -> "RSI14"; "macd" -> "MACD柱"; "vol_ratio" -> "量比"; "pos20" -> "20日位置"
    "k" -> "KDJ-K"; "d" -> "KDJ-D"; "j" -> "KDJ-J"; "atr14" -> "ATR14"
    "boll_lower" -> "布林下轨"; "boll_mid" -> "布林中轨"; "boll_upper" -> "布林上轨"
    else -> k.uppercase()
}

internal fun finLabel(k: String): String = when (k) {
    "roe" -> "ROE"; "roa" -> "ROA"; "revenue" -> "营收"; "net_profit" -> "净利润"
    "eps" -> "EPS"; "pe" -> "市盈率"; "pb" -> "市净率"; "report_date" -> "报告期"
    "total_assets" -> "总资产"; "net_assets" -> "净资产"
    else -> k
}
