package com.skapp.app.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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

private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)
private val BLUE = Color(0xFF58A6FF)
private val ORANGE = Color(0xFFE3B341)

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
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("代码 ${d.code}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("价格来源：$priceSrc", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                val bid = q?.bid1
                val ask = q?.ask1
                if (bid != null || ask != null) {
                    Text(
                        "买一 ${bid?.let { String.format("%.2f", it) } ?: "暂无"}（${q?.bid1Vol?.let { String.format("%.0f", it) } ?: "暂无"}手）" +
                            "　卖一 ${ask?.let { String.format("%.2f", it) } ?: "暂无"}（${q?.ask1Vol?.let { String.format("%.0f", it) } ?: "暂无"}手）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("买一/卖一：暂无数据（行情源未返回盘口）", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
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
                    Column {
                        Text("日 K 线", fontWeight = FontWeight.SemiBold)
                        Text(
                            "当日K实时价 ${state.liveQuote?.ts ?: "暂无时点"} · ${state.liveQuote?.price?.let { "%.2f".format(it) } ?: "暂无价格"}",
                            style = MaterialTheme.typography.labelSmall, color = Color.Gray,
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
                    color = if (state.klineSource != null) Color.Gray else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(6.dp))
                if (state.kline.isEmpty()) {
                    Text("暂无数据：日 K 接口未返回（腾讯/东财均不可达）。", color = Color.Gray)
                } else {
                    // 区间切换 chip 行（60 / 120 / 250 / 全部）
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("区间：", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
                    val visibleBars = state.kline.takeLast(klineCount)
                    KlineChart(visibleBars, Modifier.fillMaxWidth().height(200.dp))
                    Spacer(Modifier.height(4.dp))
                    val first = visibleBars.first()
                    val last = visibleBars.last()
                    Text(
                        "${first.date} → ${last.date}　共 ${visibleBars.size} 根"
                            + "　最新收 ${String.format("%.2f", last.close)}"
                            + (if (visibleBars.size == state.kline.size) "" else "（后 $klineCount 根）"),
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

        // ── 分时（盘中/盘后都展示当日完整分时）──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("分时走势", fontWeight = FontWeight.SemiBold)
                    Text(
                        state.minuteDate?.let { "交易日 $it" } ?: "交易日未知",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (state.minuteTrend.size < 2) {
                    Text(
                        "暂无分时数据：腾讯 minute/query 与东财 trends2 均未返回有效 tick；不伪造曲线。",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                } else {
                    MinuteChart(
                        points = state.minuteTrend,
                        preClose = state.minutePreClose,
                        Modifier.fillMaxWidth().height(240.dp),
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
                            chg == null -> Color.Gray
                            chg > 0 -> UP
                            chg < 0 -> DOWN
                            else -> Color.Gray
                        },
                    )
                    Text(
                        "源：${state.minuteSource ?: "未知"} · ${state.minuteTrend.size} 个 tick · 长按/拖动查看任意时刻；红线上方=涨、下方=跌，橙线为均价",
                        style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    )
                }
            }
        }

        // ── 中枢引擎总结与多引擎明细 ──
        val eng = state.localEngine
        Card(colors = CardDefaults.cardColors()) {
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
                            ?: (eng?.centralScore ?: eng?.weightedComposite ?: eng?.composite)?.let(::scoreColor) ?: Color.Gray,
                    )
                }
                Text("中枢总结：${state.centralEngineSummary?.takeIf { it.isNotBlank() } ?: "暂无数据：中枢总结未返回。"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("共振：${state.centralEngineResonance?.let { String.format("%.0f%%", it * 100) } ?: "insufficient"} · 状态：${state.centralEngineStatus}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text(
                    "基于 ${eng?.barsUsed ?: 0} 根日 K，有效引擎 ${eng?.nValid ?: 0}/12",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                HorizontalDivider()
                if (eng == null || eng.engines.isEmpty()) {
                    Text("暂无数据：K 线不足，无法计算指标。", color = Color.Gray)
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
                color = Color(0xFF3FB950),
            )
        }

        // ── AI 复盘按钮 ──
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                val aiAvailable = state.aiConfigured || state.githubConfigured
                Text("AI 二次总结", fontWeight = FontWeight.SemiBold)
                Text(
                    "中枢引擎先生成底稿；配置 AI 凭据后再基于本页真实数据进行二次总结。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
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
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                    Text("当日实时资金流：暂无数据（非交易时段或接口未返回）", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text(
                    "来源：当日 东财 fflow klt=1（兜底 push2delay）；历史 push2his fflow · 覆盖：${if (state.fundFlow.isNotEmpty()) "ok" else "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(6.dp))
                val flows = state.fundFlow
                if (flows.isEmpty()) {
                    Text("暂无数据：资金流接口未返回。", color = Color.Gray)
                } else {
                    val shown = flows.takeLast(30)
                    FundFlowChart(shown, Modifier.fillMaxWidth().height(170.dp))
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
            val cloudBoards = d.boards
            Text(
                "来源：东财 stock/get · 覆盖：${if (boards.isNotEmpty() || cloudBoards.isNotEmpty()) "ok" else "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            if (boards.isEmpty() && cloudBoards.isEmpty()) {
                Text("暂无数据", color = Color.Gray)
            } else {
                (boards + cloudBoards).distinct().forEach {
                    Text("· $it")
                }
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

        // ── 云端财务摘要 ──
        SectionCard("财务摘要（云端 bundle · 仅作补充，本机引擎在上）") {
            val fin = d.detail?.financialLatest
            Text(
                "来源：cloud bundle · 覆盖：${d.detail?.status ?: "unknown"} · 时点：${d.detail?.ts ?: "unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            if (fin.isNullOrEmpty()) {
                Text("暂无数据：分片 bundle 未覆盖该股。", color = Color.Gray)
            } else {
                fin.entries.take(12).forEach { (k, v) ->
                    Text("${finLabel(k)}：${v ?: "暂无数据"}")
                }
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
private fun AdjustChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(),
    ) {
        Text(
            text,
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun PeriodChip(text: String, selected: Boolean, onClick: () -> Unit) {
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

/**
 * 日 K 图：K 线蜡烛（开高低收）+ MA5/MA20 叠加 + 稀疏日期轴 + 十字线点选。
 * 默认选中最后一根，因此「最新日期 + 收盘价」始终可见；点按或左右拖动可查看任意一天的 OHLC。
 */
@Composable
private fun KlineChart(bars: List<KBar>, modifier: Modifier = Modifier) {
    if (bars.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = Color.Gray)
        }
        return
    }
    val minP = bars.minOf { it.low }
    val maxP = bars.maxOf { it.high }
    val range = (maxP - minP).takeIf { it > 0 } ?: 1.0
    var selIdx by remember(bars) { mutableStateOf(bars.lastIndex) }
    val sel = bars.getOrNull(selIdx) ?: bars.last()
    val prevClose = bars.getOrNull(selIdx - 1)?.close
    val selChg = prevClose?.let { if (it > 0) (sel.close - it) / it * 100 else null }

    Column(modifier) {
        // 选中条的日期 + OHLC 浮层（默认即最新一根，始终可见）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sel.date, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text("开 ${String.format("%.2f", sel.open)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text("高 ${String.format("%.2f", sel.high)}", style = MaterialTheme.typography.bodySmall, color = UP)
            Text("低 ${String.format("%.2f", sel.low)}", style = MaterialTheme.typography.bodySmall, color = DOWN)
            Text("收 ${String.format("%.2f", sel.close)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            selChg?.let {
                Text(
                    "涨跌 " + String.format("%+.2f%%", it),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it > 0) UP else if (it < 0) DOWN else Color.Gray,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .pointerInput(bars.size) {
                    // 长按/拖动/点击都可选中某根K线（用户反馈：长按无反应、看不到时间）
                    fun pick(x: Float, width: Float) {
                        val n = bars.size
                        if (width <= 0f || n == 0) return
                        selIdx = ((x / width) * (n - 1)).toInt().coerceIn(0, n - 1)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pick(down.position.x, size.width.toFloat())
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            pick(change.position.x, size.width.toFloat())
                            change.consume()
                            if (!change.pressed) break
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val n = bars.size
            val step = if (n > 1) w / (n - 1) else w
            val bw = maxOf(1.5f, (w / n) * 0.62f)
            fun yOf(v: Double) = (h - ((v - minP) / range) * h).toFloat()

            bars.forEachIndexed { i, b ->
                val cx = i * step
                drawLine(
                    color = if (b.close >= b.open) UP else DOWN,
                    start = Offset(cx, yOf(b.high)),
                    end = Offset(cx, yOf(b.low)),
                    strokeWidth = 1.2f,
                )
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
            listOf(5 to Color(0xFFD29922), 20 to Color(0xFFA371F7)).forEach { (p, col) ->
                val ma = MarketDataClient.ma(bars, p)
                val path = Path()
                var started = false
                bars.forEachIndexed { i, _ ->
                    val v = (ma.getOrNull(i) as? Double)
                    if (v != null) {
                        val x = i * step
                        val y = yOf(v)
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                }
                if (started) drawPath(path, color = col, style = Stroke(width = 1.6f))
            }

            // 十字线：选中条竖线 + 水平收盘线
            val sx = selIdx * step
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(sx, 0f),
                end = Offset(sx, h),
                strokeWidth = 1f,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(0f, yOf(sel.close)),
                end = Offset(w, yOf(sel.close)),
                strokeWidth = 1f,
            )
        }

        // 稀疏日期轴（4-6 个标签，跨年度用 yy-MM-dd，否则用 MM-dd）
        val withYear = bars.first().date.take(4) != bars.last().date.take(4)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val n = bars.size
            val idxs = if (n < 5) bars.indices.toList()
            else listOf(0, n / 4, n / 2, n * 3 / 4, n - 1)
            idxs.forEach { i ->
                Text(
                    axisDateLabel(bars[i].date, withYear),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
            }
        }
    }
}

/** 格式化日期为轴标签：跨年显示 yy-MM-dd，否则 MM-dd */
private fun axisDateLabel(date: String, withYear: Boolean): String = when {
    !withYear -> date.takeLast(5)
    date.length >= 10 -> date.drop(2)
    else -> date
}

/** 分时横坐标：交易分钟数（09:30=0、11:30=120、13:00=120、15:00=240），午休不开缺口。 */
private fun minuteAxis(t: String): Float {
    val hh = t.take(2).toIntOrNull() ?: return 0f
    val mm = t.drop(2).take(2).toIntOrNull() ?: return 0f
    val m = hh * 60 + mm
    return when {
        m <= 690 -> 0f
        m <= 690 + 120 -> (m - 690).toFloat()
        m < 780 -> 120f
        else -> (120 + (m - 780)).coerceAtMost(240).toFloat()
    }
}

private fun minuteTimeLabel(t: String): String =
    if (t.length >= 4) "${t.take(2)}:${t.drop(2).take(2)}" else t

private fun minutePickIndex(x: Float, width: Float, times: List<String>): Int {
    if (width <= 0f || times.isEmpty()) return 0
    val axis = (x / width * 240f).coerceIn(0f, 240f)
    var best = 0
    var bestD = Float.MAX_VALUE
    times.forEachIndexed { i, t ->
        val d = kotlin.math.abs(minuteAxis(t) - axis)
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}

/**
 * 分时图（A 股标准形态）：
 *   以昨收为基准的上下对称坐标 + 昨收虚线；价格线（涨红跌绿）+ 均价线（橙）；
 *   下方成交量柱；左侧价格刻度、右侧涨跌幅刻度；底部时间轴；长按/拖动十字线读数。
 */
@Composable
private fun MinuteChart(
    points: List<Map<String, Any?>>,
    preClose: Double?,
    modifier: Modifier = Modifier,
) {
    val prices = remember(points) { points.mapNotNull { (it["price"] as? Number)?.toDouble() } }
    if (prices.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("暂无分时数据", color = Color.Gray) }
        return
    }
    val times = remember(points) { points.map { it["time"]?.toString() ?: "" } }
    val avgs = remember(points) { points.map { (it["avg"] as? Number)?.toDouble() } }
    val cumVols = remember(points) { points.map { (it["vol"] as? Number)?.toDouble() ?: 0.0 } }
    val volSteps = remember(points) {
        cumVols.mapIndexed { i, v -> if (i == 0) v else (v - cumVols[i - 1]).coerceAtLeast(0.0) }
    }
    var selIdx by remember(points) { mutableStateOf(prices.lastIndex) }
    val idx = selIdx.coerceIn(0, prices.lastIndex)
    val selPrice = prices[idx]
    val base = preClose ?: prices.first()
    val selChg = if (base > 0) (selPrice - base) / base * 100 else null
    val selAvg = avgs.getOrNull(idx)

    // 以昨收为中心的对称区间：分时图惯例，涨跌幅上下等幅
    val dev = maxOf(
        prices.maxOf { kotlin.math.abs(it - base) },
        avgs.filterNotNull().maxOfOrNull { kotlin.math.abs(it - base) } ?: 0.0,
    ).takeIf { it > 1e-9 } ?: 1.0
    val hi = base + dev
    val lo = base - dev
    val maxVol = volSteps.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    Column(modifier) {
        // 选中点读数（默认最后一根，始终可见）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(minuteTimeLabel(times.getOrNull(idx) ?: ""), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                String.format("%.2f", selPrice),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (selChg != null && selChg < 0) DOWN else UP,
            )
            Text(
                selChg?.let { String.format("%+.2f%%", it) } ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = if (selChg != null && selChg < 0) DOWN else UP,
            )
            Text(
                selAvg?.let { String.format("均价 %.2f", it) } ?: "均价 —",
                style = MaterialTheme.typography.bodySmall, color = ORANGE,
            )
        }
        Spacer(Modifier.height(2.dp))
        Canvas(
            Modifier.fillMaxWidth().weight(1f)
                .pointerInput(points) {
                    detectTapGestures { off ->
                        selIdx = minutePickIndex(off.x, size.width.toFloat(), times)
                    }
                }
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { off -> selIdx = minutePickIndex(off.x, size.width.toFloat(), times) },
                        onDrag = { change, _ ->
                            selIdx = minutePickIndex(change.position.x, size.width.toFloat(), times)
                        },
                    )
                }
        ) {
            val priceH = size.height * 0.72f
            val volTop = priceH + 6f
            val volH = (size.height - volTop).coerceAtLeast(1f)

            fun xx(t: String) = minuteAxis(t) / 240f * size.width
            fun yy(v: Double) = (priceH - ((v - lo) / (hi - lo)).toFloat() * priceH)

            // 网格：昨收虚线 + 上下限参考线
            val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 7f))
            drawLine(
                Color.Gray.copy(alpha = 0.55f), Offset(0f, yy(base)), Offset(size.width, yy(base)),
                strokeWidth = 1.2f, pathEffect = dash,
            )
            drawLine(Color.Gray.copy(alpha = 0.2f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)

            // 成交量柱（单分钟增量）
            val barW = (size.width / 240f * 0.9f).coerceAtLeast(1f)
            volSteps.forEachIndexed { i, v ->
                val x = xx(times.getOrNull(i) ?: "")
                val h = (v / maxVol * volH).toFloat()
                drawRect(
                    color = (if (i == 0) UP else if (prices[i] >= prices[i - 1]) UP else DOWN).copy(alpha = 0.6f),
                    topLeft = Offset(x - barW / 2f, volTop + volH - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                )
            }

            // 价格线 + 均价线
            val lineColor = if (selPrice < base) DOWN else UP
            val pricePath = Path()
            val avgPath = Path()
            prices.forEachIndexed { i, v ->
                val x = xx(times.getOrNull(i) ?: "")
                val y = yy(v)
                if (i == 0) pricePath.moveTo(x, y) else pricePath.lineTo(x, y)
                avgs.getOrNull(i)?.let { a ->
                    val ay = yy(a)
                    if (i == 0) avgPath.moveTo(x, ay) else avgPath.lineTo(x, ay)
                }
            }
            drawPath(avgPath, color = ORANGE, style = Stroke(width = 1.4f))
            drawPath(pricePath, color = lineColor, style = Stroke(width = 2.2f))

            // 十字线 + 选中点
            val cx = xx(times.getOrNull(idx) ?: "")
            drawLine(Color.Gray.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f)
            drawLine(Color.Gray.copy(alpha = 0.8f), Offset(0f, yy(selPrice)), Offset(size.width, yy(selPrice)), strokeWidth = 1f)
            drawCircle(lineColor, radius = 4f, center = Offset(cx, yy(selPrice)))

            // 坐标文字：左侧价格、右侧涨跌幅
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 20f
                color = 0xFF8B949E.toInt()
            }
            val nc = drawContext.canvas.nativeCanvas
            paint.textAlign = android.graphics.Paint.Align.LEFT
            nc.drawText(String.format("%.2f", hi), 2f, 20f, paint)
            nc.drawText(String.format("%.2f", base), 2f, yy(base) - 4f, paint)
            nc.drawText(String.format("%.2f", lo), 2f, priceH - 4f, paint)
            paint.textAlign = android.graphics.Paint.Align.RIGHT
            nc.drawText(String.format("%+.2f%%", dev / base * 100), size.width - 2f, 20f, paint)
            nc.drawText("0.00%", size.width - 2f, yy(base) - 4f, paint)
            nc.drawText(String.format("%.2f%%", -dev / base * 100), size.width - 2f, priceH - 4f, paint)
        }
        // 时间轴（首/中/末 + 午休分界）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("09:30", "10:30", "11:30/13:00", "14:00", "15:00").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

/** 资金流柱状图：柱体直接标注当日金额 + 底部时间轴（不再把数字单独列成一块）。 */
@Composable
private fun FundFlowChart(flows: List<Map<String, Any?>>, modifier: Modifier = Modifier) {
    val vals = flows.mapNotNull { (it["main"] as? Number)?.toDouble() }
    val dates = flows.map { it["date"]?.toString()?.takeLast(5) ?: "" }
    Canvas(modifier) {
        if (vals.isEmpty()) return@Canvas
        val maxAbs = maxOf(vals.maxOf { kotlin.math.abs(it) }, 1.0)
        val bw = size.width / vals.size
        val topPad = 20f
        val bottomPad = 24f
        val midY = topPad + (size.height - topPad - bottomPad) / 2f
        val half = (size.height - topPad - bottomPad) / 2f

        vals.forEachIndexed { i, v ->
            val h = (kotlin.math.abs(v) / maxAbs * half).toFloat()
            val x = i * bw
            if (v >= 0) {
                drawRect(UP, topLeft = Offset(x, midY - h), size = androidx.compose.ui.geometry.Size(bw * 0.8f, h))
            } else {
                drawRect(DOWN, topLeft = Offset(x, midY), size = androidx.compose.ui.geometry.Size(bw * 0.8f, h))
            }
        }
        drawLine(Color.Gray.copy(alpha = 0.6f), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)

        // 金额标注：只标绝对值最大的 8 根，避免文字重叠
        val labelled = vals.withIndex()
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(8).map { it.index }.toSet()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        vals.forEachIndexed { i, v ->
            if (i !in labelled) return@forEachIndexed
            paint.color = if (v >= 0) 0xFFE53935.toInt() else 0xFF2E7D32.toInt()
            val h = (kotlin.math.abs(v) / maxAbs * half).toFloat()
            val cx = i * bw + bw * 0.4f
            val ty = if (v >= 0) (midY - h - 5f).coerceAtLeast(14f)
            else (midY + h + 18f).coerceAtMost(size.height - 6f)
            drawContext.canvas.nativeCanvas.drawText(fundFlowText(v), cx, ty, paint)
        }

        // 时间轴：首 / 中 / 末
        paint.color = 0xFF9E9E9E.toInt()
        paint.textSize = 18f
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textAlign = android.graphics.Paint.Align.LEFT
        dates.firstOrNull()?.let { drawContext.canvas.nativeCanvas.drawText(it, 2f, size.height - 5f, paint) }
        if (dates.size > 2) {
            paint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(dates[dates.size / 2], size.width / 2f, size.height - 5f, paint)
        }
        paint.textAlign = android.graphics.Paint.Align.RIGHT
        dates.lastOrNull()?.let { drawContext.canvas.nativeCanvas.drawText(it, size.width - 2f, size.height - 5f, paint) }
    }
}

private fun fundFlowText(v: Double): String = when {
    kotlin.math.abs(v) >= 1e8 -> String.format("%.1f亿", v / 1e8)
    kotlin.math.abs(v) >= 1e4 -> String.format("%.0f万", v / 1e4)
    else -> String.format("%.0f", v)
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
