package com.skapp.app.ui.screens

import com.skapp.app.ui.theme.SkColors

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.skapp.app.ui.components.SkCard
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import com.skapp.app.ui.Watchlist
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.EngineScore
import com.skapp.app.data.ExtendedIndicators
import com.skapp.app.data.KBar
import com.skapp.app.data.MarketDataClient
import com.skapp.app.data.LocalEngineResult
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown
private val BLUE = SkColors.primary
private val ORANGE = SkColors.chartMa5

/**
 * 个股详情（本地引擎 + 多源数据）：
 *   实时报价 / 日K图(前·后复权切换) / 分时 / 历史资金流 / 所属板块 /
 *   本地多引擎评分与理由 / 技术参数 / 云端财务摘要 / 数据来源
 */
@Composable
fun StockDetailSheet(vm: SkappViewModel, state: UiState) {
    val d = state.detail
    // 控制图表显示区间；state.kline 已含全历史，这里只裁剪展示，无需改 ViewModel
    var klineCount by remember { mutableStateOf(320) }
    // 三图共享的选中态：提在 StockDetailSheet 顶层而不是各自图内部，
    // 是因为要让「K 线选的某天 = 资金流高亮的某天」和「分时选的某分钟」互相驱动，
    // 状态若留在每个图内部，跨图就无法读取彼此的选择。
    var selectedDate by remember { mutableStateOf<String?>(null) }   // K 线 / 资金流共享的日期
    var selectedMinute by remember { mutableStateOf<String?>(null) } // 分时选中的分钟（如 0935）
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = vm::closeDetail) {
                Text("‹ 返回")
            }
            Text(
                "${d?.name ?: d?.code ?: "加载中"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                d?.code?.let { code ->
                    val ctx = LocalContext.current
                    val inWatch = Watchlist.contains(code)
                    TextButton(onClick = {
                        if (inWatch) Watchlist.remove(ctx, code) else Watchlist.add(ctx, code)
                    }) {
                        Text(if (inWatch) "移出自选" else "加自选",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = vm::closeDetail) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
        val holoPrice = (state.holo?.holo?.get("price") as? Number)?.toDouble()
        val holoPct = (state.holo?.holo?.get("pctChg") as? Number)?.toDouble()
        val price = q?.price ?: row?.price ?: holoPrice ?: d.detail?.quote?.get("price")
        val pct = q?.pctChg ?: row?.pctChg ?: holoPct ?: d.detail?.quote?.get("pctChg")
        val priceSrc = if (q != null) "实时" else if (row != null) "实时" else "holo/快照"
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("代码 ${d.code}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                Text("价格来源：$priceSrc", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                Text(
                    "现价 ${price?.let { String.format("%.2f", it) } ?: "暂无数据"}" +
                        (pct?.let { "　${String.format("%.2f%%", it)}" } ?: ""),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        pct == null -> SkColors.textSecondary
                        pct > 0 -> UP
                        pct < 0 -> DOWN
                        else -> SkColors.textSecondary
                    },
                )
                val bid = q?.bid1
                val ask = q?.ask1
                if (bid != null || ask != null) {
                    Text(
                        "买一 ${bid?.let { String.format("%.2f", it) } ?: "暂无"}（${q?.bid1Vol?.let { String.format("%.0f", it) } ?: "暂无"}手）" +
                            "　卖一 ${ask?.let { String.format("%.2f", it) } ?: "暂无"}（${q?.ask1Vol?.let { String.format("%.0f", it) } ?: "暂无"}手）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("买一/卖一：暂无数据（行情源未返回盘口）", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                }
            }
        }

        // ── 日 K 图 + 复权切换 ──
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("日 K 线", fontWeight = FontWeight.SemiBold)
                        Text(
                            "当日K实时价 ${state.liveQuote?.ts ?: "暂无时点"} · ${state.liveQuote?.price?.let { "%.2f".format(it) } ?: "暂无价格"}",
                            style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "刷新",
                            Modifier.clickable { vm.refreshDetailLive() }.padding(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                // 复权切换单独一行、三项等分。
                // 之前这一行里多写了一个「不复权」（共四项），把「后复权」挤出了屏幕，
                // 用户看到的就是「后复权不见了 / 切了没反应」。
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AdjustChip("不复权", state.detailAdjust == "none", Modifier.weight(1f)) { vm.switchAdjust("none") }
                    AdjustChip("前复权", state.detailAdjust == "qfq", Modifier.weight(1f)) { vm.switchAdjust("qfq") }
                    AdjustChip("后复权", state.detailAdjust == "hfq", Modifier.weight(1f)) { vm.switchAdjust("hfq") }
                }
                Text(
                    "当前口径：" + when (state.detailAdjust) {
                        "hfq" -> "后复权"
                        "qfq" -> "前复权"
                        else -> "不复权"
                    } + "　数据源：" + (state.klineSource ?: "未生效（接口未返回，未用其他口径顶替）"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.klineSource != null) SkColors.textSecondary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                if (state.kline.isEmpty()) {
                    Text("暂无数据：日 K 接口未返回（腾讯/东财均不可达）。", color = SkColors.textSecondary)
                } else {
                    // 区间切换 chip 行（60 / 120 / 250 / 全部）
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("区间：", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                        PeriodChip("60 日", klineCount == 60) {
                            klineCount = 60
                            // state.kline 已由 VM 拉取全量（≥250），这里只裁剪展示
                        }
                        PeriodChip("120 日", klineCount == 120) {
                            klineCount = 120
                        }
                        PeriodChip("250 日", klineCount == 250) {
                            klineCount = 250
                        }
                        PeriodChip("全部", klineCount == 320) {
                            klineCount = 320
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val visibleBars = remember(state.kline, klineCount) { state.kline.takeLast(klineCount) }
                    KlineChart(
                        bars = visibleBars,
                        selectedDate = selectedDate,
                        onSelectDate = { selectedDate = it },
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    val first = visibleBars.first()
                    val last = visibleBars.last()
                    Text(
                        "${first.date} → ${last.date}　共 ${visibleBars.size} 根"
                            + "　最新收 ${String.format("%.2f", last.close)}"
                            + (if (visibleBars.size == state.kline.size) "" else "（后 $klineCount 根）"),
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
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
                                    color = SkColors.textSecondary,
                                )
                            }
                        }
                        visibleBars.asReversed().take(60).forEachIndexed { i, b ->
                            val prev = visibleBars.getOrNull(visibleBars.size - 1 - i - 1)
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
                                        chg == null -> SkColors.textSecondary
                                        chg > 0 -> UP
                                        chg < 0 -> DOWN
                                        else -> SkColors.textSecondary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 三图联动信息条（K 线 / 资金流 / 分时 同一时点的读数汇总）──
        LinkageBar(
            selectedDate = selectedDate,
            selectedMinute = selectedMinute,
            bars = remember(state.kline, klineCount) { state.kline.takeLast(klineCount) },
            flows = state.fundFlow,
            minutePoints = state.minuteTrend,
            preClose = state.minutePreClose,
        )

        // ── 分时（盘中/盘后都展示当日完整分时）──
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("分时走势", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.minuteDate?.let { "交易日 $it" } ?: "交易日未知",
                        style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (state.minuteTrend.size < 2) {
                    Text(
                        "暂无分时数据：腾讯 minute/query 与东财 trends2 均未返回有效 tick；不伪造曲线。",
                        style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary,
                    )
                } else {
                    MinuteChart(
                        points = state.minuteTrend,
                        preClose = state.minutePreClose,
                        selectedTime = selectedMinute,
                        onSelectTime = { selectedMinute = it },
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    val pre = state.minutePreClose
                    val lastPrice = (state.minuteTrend.lastOrNull()?.get("price") as? Number)?.toDouble()
                    val lastAvg = (state.minuteTrend.lastOrNull()?.get("avg") as? Number)?.toDouble()
                    val chg = if (pre != null && pre > 0 && lastPrice != null) (lastPrice - pre) / pre * 100 else null
                    Text(
                        "昨收 ${pre?.let { String.format("%.2f", it) } ?: "暂无"}"
                            + "　最新 ${lastPrice?.let { String.format("%.2f", it) } ?: "暂无"}"
                            + "　涨跌 ${chg?.let { String.format("%+.2f%%", it) } ?: "暂无"}"
                            + "　均价 ${lastAvg?.let { String.format("%.2f", it) } ?: "暂无"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            chg == null -> SkColors.textSecondary
                            chg > 0 -> UP
                            chg < 0 -> DOWN
                            else -> SkColors.textSecondary
                        },
                    )
                    Text(
                        "源：${state.minuteSource ?: "未知"} · ${state.minuteTrend.size} 个 tick · 长按/拖动查看任意时刻；红线上方=涨、下方=跌，橙线为均价",
                        style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary,
                    )
                }
            }
        }

        // ── 中枢引擎总结与多引擎明细 ──
        val eng = state.localEngine
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("中枢引擎", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.centralEngineScore?.let { "中枢综合 ${String.format("%.1f", it)}" }
                            ?: eng?.centralScore?.let { "中枢综合 ${String.format("%.1f", it)}" }
                            ?: eng?.weightedComposite?.let { "中枢综合 ${String.format("%.1f", it)}" }
                            ?: eng?.composite?.let { "中枢综合 ${String.format("%.1f", it)}" }
                            ?: "insufficient",
                        fontWeight = FontWeight.Bold,
                        color = state.centralEngineScore?.let(::scoreColor)
                            ?: (eng?.centralScore ?: eng?.weightedComposite ?: eng?.composite)?.let(::scoreColor) ?: SkColors.textSecondary,
                    )
                }
                Text("中枢总结：${state.centralEngineSummary?.takeIf { it.isNotBlank() } ?: "暂无数据：中枢总结未返回。"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("族级共振：${state.centralEngineResonance?.let { String.format("%.0f%%", it * 100) } ?: "insufficient"}（趋势/动量/通道/量能/波动/位置 6 族，同族不重复计票）· 状态：${state.centralEngineStatus}",
                    style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
                Text(
                    "基于 ${eng?.barsUsed ?: 0} 根日 K，有效引擎 ${eng?.nValid ?: 0}/${eng?.engines?.size ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                HorizontalDivider()
                if (eng == null || eng.engines.isEmpty()) {
                    Text("暂无数据：K 线不足，无法计算指标。", color = SkColors.textSecondary)
                } else {
                    eng.engines.forEach { e -> EngineRow(e) }
                }
            }
        }

        // ── holo 覆盖状态 ──
        state.holo?.meta?.let { m ->
            Text(
                "holo 仓：已覆盖（${m["ts"] ?: m["generated_at"] ?: "时点未知"}）",
                style = MaterialTheme.typography.bodySmall,
                color = SkColors.success,
            )
        }

        // ── AI 复盘按钮 ──
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                val aiAvailable = state.aiConfigured || state.githubConfigured
                Text("AI 二次总结", fontWeight = FontWeight.SemiBold)
                Text(
                    "中枢引擎先生成底稿；配置 AI 凭据后再基于本页真实数据进行二次总结。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                if (aiAvailable) {
                    Button(
                        onClick = { vm.aiReviewCurrent() },
                        enabled = !state.aiReviewLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.aiReviewLoading) "AI 复盘中…" else "AI 复盘这只股票")
                    }
                    if (state.aiReviewLoading) {
                        CircularProgressIndicator()
                    }
                    state.aiReview?.let {
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(
                        state.centralEngineSummary ?: "中枢引擎：暂无有效数据。",
                        color = SkColors.textSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        DetailTailSections(state, d, selectedDate) { selectedDate = it }
        }
    }
}
