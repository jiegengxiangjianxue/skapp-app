package com.skapp.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.EngineScore
import com.skapp.app.data.ExtendedIndicators
import com.skapp.app.data.KBar
import com.skapp.app.data.MarketDataClient
import com.skapp.app.data.LocalEngineResult
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)
private val BLUE = Color(0xFF58A6FF)

/**
 * 个股详情（本地引擎 + 多源数据）：
 *   实时报价 / 日K图(前·后复权切换) / 分时 / 历史资金流 / 所属板块 /
 *   本地多引擎评分与理由 / 技术参数 / 云端财务摘要 / AI 问答 / 数据来源
 */
@Composable
fun StockDetailSheet(vm: SkappViewModel, state: UiState) {
    val d = state.detail
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${d?.name ?: d?.code ?: "加载中"}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = vm::closeDetail) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        }

        if (state.detailLoading || d == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("正在本地拉取多源数据并计算引擎…")
            }
            return@Column
        }

        // ── 实时报价 ──
        val q = state.liveQuote
        val row = state.quotes.firstOrNull { it.code == d.code }
        val price = q?.price ?: row?.price ?: d.detail?.quote?.get("price")
        val pct = q?.pctChg ?: row?.pctChg ?: d.detail?.quote?.get("pctChg")
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("代码 ${d.code}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text(
                    "现价 ${price?.let { String.format("%.2f", it) } ?: "暂无数据"}" +
                        (pct?.let { "　${String.format("%.2f%%", it)}" } ?: ""),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        pct == null -> Color.Gray
                        pct > 0 -> UP
                        pct < 0 -> DOWN
                        else -> Color.Gray
                    },
                )
            }
        }

        // ── 日 K 图 + 复权切换 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("日 K 线", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AdjustChip("前复权", state.detailAdjust == "qfq") { vm.switchAdjust("qfq") }
                        AdjustChip("后复权", state.detailAdjust == "hfq") { vm.switchAdjust("hfq") }
                        AdjustChip("不复权", state.detailAdjust == "none") { vm.switchAdjust("none") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (state.kline.isEmpty()) {
                    Text("暂无数据：日 K 接口未返回（腾讯/东财均不可达）。", color = Color.Gray)
                } else {
                    KlineChart(state.kline, Modifier.fillMaxWidth().height(200.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.kline.first().date} → ${state.kline.last().date}　共 ${state.kline.size} 根" +
                            "　最新收 ${String.format("%.2f", state.kline.last().close)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    // 逐日明细（可展开），让每个点位的时间与数值可读
                    var showTable by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().clickable { showTable = !showTable },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            if (showTable) "收起逐日明细 ▲" else "展开逐日明细（日期/开高低收/涨跌幅）▼",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (showTable) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth()) {
                            listOf("日期", "开", "高", "低", "收", "涨跌%").forEach {
                                Text(
                                    it,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            }
                        }
                        state.kline.asReversed().take(60).forEachIndexed { i, b ->
                            val prev = state.kline.getOrNull(state.kline.size - 1 - i - 1)
                            val chg = prev?.let { if (it.close > 0) (b.close - it.close) / it.close * 100 else null }
                            Row(Modifier.fillMaxWidth()) {
                                Text(b.date, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                                Text(String.format("%.2f", b.open), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(String.format("%.2f", b.high), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = UP)
                                Text(String.format("%.2f", b.low), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = DOWN)
                                Text(String.format("%.2f", b.close), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    chg?.let { String.format("%.2f", it) } ?: "—",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        chg == null -> Color.Gray
                                        chg > 0 -> UP
                                        chg < 0 -> DOWN
                                        else -> Color.Gray
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 分时（盘中/盘后）──
        if (state.minuteTrend.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(12.dp)) {
                    Text("分时走势", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    MinuteChart(state.minuteTrend, Modifier.fillMaxWidth().height(140.dp))
                    Text(
                        "共 ${state.minuteTrend.size} 个 tick　来源：腾讯 minute / 东财 trends2",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }
        }

        // ── 本地多引擎评分 ──
        val eng = state.localEngine
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("本地引擎评分（12 引擎）", fontWeight = FontWeight.SemiBold)
                    eng?.composite?.let {
                        Text(
                            "综合 ${String.format("%.1f", it)}",
                            fontWeight = FontWeight.Bold,
                            color = scoreColor(it),
                        )
                    }
                }
                Text(
                    "在设备上直接计算，不依赖服务端；基于 ${eng?.barsUsed ?: 0} 根日 K，" +
                        "有效引擎 ${eng?.nValid ?: 0}/12",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                if (eng == null || eng.engines.isEmpty()) {
                    Text("暂无数据：K 线不足，无法计算指标。", color = Color.Gray)
                } else {
                    eng.engines.forEach { e -> EngineRow(e) }
                }
            }
        }

        // ── 技术参数 ──
        SectionCard("技术参数") {
            val t = d.detail?.technical
            if (t.isNullOrEmpty()) {
                Text("暂无数据", color = Color.Gray)
            } else {
                t.entries.filter { it.value != null }.forEach { (k, v) ->
                    Text("${techLabel(k)}：${String.format("%.2f", v!!)}")
                }
            }
        }

        // ── 历史资金流 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("历史资金流", fontWeight = FontWeight.SemiBold)
                Text(
                    "主力净额（东财 push2his）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(6.dp))
                val flows = state.fundFlow
                if (flows.isEmpty()) {
                    Text("暂无数据：资金流接口未返回。", color = Color.Gray)
                } else {
                    FundFlowChart(flows.takeLast(30), Modifier.fillMaxWidth().height(120.dp))
                    Spacer(Modifier.height(4.dp))
                    flows.takeLast(5).reversed().forEach { f ->
                        val main = (f["main"] as? Number)?.toDouble()
                        Text(
                            "${f["date"]}　主力 ${main?.let { String.format("%.0f 万", it / 1e4) } ?: "暂无"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (main != null && main >= 0) UP else DOWN,
                        )
                    }
                }
            }
        }

        // ── 所属板块 ──
        SectionCard("所属板块") {
            val boards = state.boardsOf.mapNotNull { it["name"]?.toString() }
            val cloudBoards = d.boards
            if (boards.isEmpty() && cloudBoards.isEmpty()) {
                Text("暂无数据", color = Color.Gray)
            } else {
                (boards + cloudBoards).distinct().forEach {
                    Text("· $it")
                }
            }
        }

        // ── 云端财务摘要 ──
        SectionCard("财务摘要（云端 bundle）") {
            val fin = d.detail?.financialLatest
            if (fin.isNullOrEmpty()) {
                Text("暂无数据：分片 bundle 未覆盖该股。", color = Color.Gray)
            } else {
                fin.entries.take(12).forEach { (k, v) ->
                    Text("${finLabel(k)}：${v ?: "暂无数据"}")
                }
            }
        }

        // ── 云端引擎评分（站点产物）──
        SectionCard("云端引擎评分（站点 CI 产物）") {
            if (d.engineScores.isEmpty()) {
                Text("暂无数据：该股未进入今日云端推股池。", color = Color.Gray)
            } else {
                d.engineScores.entries.forEach { (k, v) ->
                    Text("$k：${v?.let { String.format("%.2f", it) } ?: "暂无数据"}")
                }
            }
        }

        // ── AI 问答 ──
        SectionCard("AI 问答（基于真实数据）") {
            val qa = d.aiQa
            if (qa == null || qa.status != "ok") {
                Text("暂无数据：该股尚未生成 AI 问答（${qa?.reason ?: "未生成"}）。", color = Color.Gray)
            } else {
                if (!qa.why.isNullOrBlank()) Text("为何关注：${qa.why}")
                if (!qa.risk.isNullOrBlank()) Text("主要风险：${qa.risk}")
                if (!qa.focus.isNullOrBlank()) Text("观察点：${qa.focus}")
                Text(
                    "模型：${qa.model ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }

        // ── 29 因子库 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("因子库（29 因子，本机计算）", fontWeight = FontWeight.SemiBold)
                Text(
                    "对应站点 factor_lib.py",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                if (state.kline.size < 30) {
                    Text("暂无数据：K 线不足 30 根。", color = Color.Gray)
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

        // ── 数据来源 ──
        SectionCard("数据来源") {
            Text("实时报价：腾讯 qt.gtimg.cn（兜底 东财 push2 / 新浪 hq.sinajs）")
            Text("日 K：腾讯 web.ifzq fqkline（兜底 东财 push2his kline）")
            Text("分时：腾讯 minute/query（兜底 东财 push2his trends2）")
            Text("资金流：东财 push2his fflow（兜底 push2delay）")
            Text("所属板块：东财 push2 stock/get")
            Text("技术指标与评分：本机计算（MA/MACD/RSI/KDJ/BOLL/ATR/量比）")
            Text("同花顺：d/basic/news/zx 四域（日K/F10/快讯/强势股）")
            Text("问财选股：iwencai.com（自然语言）")
            Text("东财：F10 财务 / 龙虎榜 / 板块资金 / 全球指数")
            Text("新浪：hq.sinajs.cn / vip.stock.finance.sina.com.cn")
            Text("雪球热搜：stock.xueqiu.com")
            Text("云端 bundle / AI 问答：qwerzxcva/stocks-ai-view + 镜像")
        }
    }
}

@Composable
private fun EngineRow(e: EngineScore) {
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
                    Text("insufficient", color = Color(0xFFD29922),
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
                            else -> Color.Gray
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            e.reasons.take(3).forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
            }
        }
    }
}

@Composable
private fun AdjustChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** 日期轴标签（首/中/末） */
private fun dateAxisLabel(dates: List<String>, idx: Int): String? =
    when (idx) {
        0, dates.size - 1 -> dates.getOrNull(idx)
        dates.size / 4, dates.size / 2, dates.size * 3 / 4 -> dates.getOrNull(idx)
        else -> null
    }

/**
 * 日 K 图：K 线蜡烛（开高低收）+ MA5/MA20 叠加 + 日期轴。
 * 下方列出「最近 10 个交易日的日期/收盘/涨跌幅」，让每个点位的时间可读。
 */
@Composable
private fun KlineChart(bars: List<KBar>, modifier: Modifier = Modifier) {
    if (bars.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = Color.Gray)
        }
        return
    }
    val lows = bars.map { it.low }
    val highs = bars.map { it.high }
    val minP = lows.min()
    val maxP = highs.max()
    val range = (maxP - minP).takeIf { it > 0 } ?: 1.0
    val stepX = modifierSize(bars.size)

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val w = size.width
            val h = size.height
            val bw = maxOf(1.5f, (w / bars.size) * 0.62f)
            fun yOf(v: Double) = (h - ((v - minP) / range) * h).toFloat()

            bars.forEachIndexed { i, b ->
                val cx = i * (w / (bars.size - 1))
                // 影线
                drawLine(
                    color = if (b.close >= b.open) UP else DOWN,
                    start = Offset(cx, yOf(b.high)),
                    end = Offset(cx, yOf(b.low)),
                    strokeWidth = 1.2f,
                )
                // 实体
                val yO = yOf(b.open)
                val yC = yOf(b.close)
                val top = minOf(yO, yC)
                val bh = maxOf(1.5f, kotlin.math.abs(yC - yO))
                drawRect(
                    color = if (b.close >= b.open) UP else DOWN,
                    topLeft = Offset(cx - bw / 2, top),
                    size = androidx.compose.ui.geometry.Size(bw, bh),
                )
            }

            // MA5 / MA20
            listOf(5 to Color(0xFFD29922), 20 to Color(0xFFA371F7)).forEach { (n, col) ->
                val ma = MarketDataClient.ma(bars, n)
                val path = Path()
                var started = false
                bars.forEachIndexed { i, _ ->
                    val v = (ma.getOrNull(i) as? Double)
                    if (v != null) {
                        val x = i * (w / (bars.size - 1))
                        val y = yOf(v)
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                }
                if (started) drawPath(path, color = col, style = Stroke(width = 1.6f))
            }
        }
        // 日期轴
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val mid = bars[bars.size / 2].date
            Text(bars.first().date, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(mid, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(bars.last().date, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

private fun modifierSize(n: Int) = n

/** 分时图 */
@Composable
private fun MinuteChart(points: List<Map<String, Any?>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val vals = points.mapNotNull { (it["price"] as? Number)?.toDouble() }
        if (vals.size < 2) return@Canvas
        val min = vals.min(); val max = vals.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (vals.size - 1).toFloat()
        val path = Path()
        vals.forEachIndexed { i, v ->
            val x = i * stepX
            val y = (size.height - ((v - min) / range) * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = BLUE, style = Stroke(width = 2.5f))
    }
}

/** 资金流柱状图（主力净额，正红负绿） */
@Composable
private fun FundFlowChart(flows: List<Map<String, Any?>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val vals = flows.mapNotNull { (it["main"] as? Number)?.toDouble() }
        if (vals.isEmpty()) return@Canvas
        val maxAbs = maxOf(vals.maxOf { kotlin.math.abs(it) }, 1.0)
        val bw = size.width / vals.size
        val midY = size.height / 2
        vals.forEachIndexed { i, v ->
            val h = (kotlin.math.abs(v) / maxAbs * (size.height / 2)).toFloat()
            val x = i * bw
            if (v >= 0) {
                drawRect(UP, topLeft = Offset(x, midY - h), size = androidx.compose.ui.geometry.Size(bw * 0.8f, h))
            } else {
                drawRect(DOWN, topLeft = Offset(x, midY), size = androidx.compose.ui.geometry.Size(bw * 0.8f, h))
            }
        }
        drawLine(
            color = Color.Gray,
            start = Offset(0f, midY),
            end = Offset(size.width, midY),
            strokeWidth = 1f,
        )
    }
}

private fun engineName(k: String): String = when (k) {
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

private fun factorLabel(k: String): String = when (k) {
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

private fun scoreColor(s: Double): Color = when {
    s >= 65 -> UP
    s <= 35 -> DOWN
    else -> Color.Gray
}

private fun techLabel(k: String): String = when (k) {
    "ma5" -> "MA5"; "ma10" -> "MA10"; "ma20" -> "MA20"; "ma60" -> "MA60"
    "rsi14" -> "RSI14"; "macd" -> "MACD柱"; "vol_ratio" -> "量比"; "pos20" -> "20日位置"
    "k" -> "KDJ-K"; "d" -> "KDJ-D"; "j" -> "KDJ-J"; "atr14" -> "ATR14"
    "boll_lower" -> "布林下轨"; "boll_mid" -> "布林中轨"; "boll_upper" -> "布林上轨"
    else -> k.uppercase()
}

private fun finLabel(k: String): String = when (k) {
    "roe" -> "ROE"; "roa" -> "ROA"; "revenue" -> "营收"; "net_profit" -> "净利润"
    "eps" -> "EPS"; "pe" -> "市盈率"; "pb" -> "市净率"; "report_date" -> "报告期"
    "total_assets" -> "总资产"; "net_assets" -> "净资产"
    else -> k
}
