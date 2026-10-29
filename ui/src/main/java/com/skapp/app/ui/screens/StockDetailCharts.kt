package com.skapp.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import com.skapp.app.data.KBar
import com.skapp.app.data.MarketDataClient
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.theme.SkColors

private val UP = SkColors.marketUp
private val DOWN = SkColors.marketDown
private val BLUE = SkColors.primary
private val ORANGE = SkColors.chartMa5

/*
 * 详情页的三张图（日 K / 三图联动条 / 分时 / 资金流）从 StockDetailSheet.kt 拆出来。
 *
 * 为什么拆：原文件 1448 行、14 处 Canvas 与绘制调用，改一处布局要在同一文件里翻 600 行；
 * 拆开后图表只依赖 bars/quotes 这类纯数据，重绘边界清楚了，也能单独量耗时与耗时回归。
 * 可见性 private → internal：同包不同文件，private 跨文件不可见。
 */

/**
 * 日 K 图：K 线蜡烛（开高低收）+ MA5/MA20 叠加 + 稀疏日期轴 + 十字线点选。
 * 默认展示最后一根，因此「最新日期 + 收盘价」始终可见；点按或左右拖动可查看任意一天的 OHLC。
 *
 * 联动：选中日期由顶层传入（与资金流共享），外部改 selectedDate 时十字线跟到同一天；
 * 用户点选时通过 onSelectDate 回报，使资金流能高亮同一天的柱子。
 */
@Composable
internal fun KlineChart(
    bars: List<KBar>,
    selectedDate: String?,
    onSelectDate: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bars.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = SkColors.textSecondary)
        }
        return
    }
    val minP = remember(bars) { bars.minOf { it.low } }
    val maxP = remember(bars) { bars.maxOf { it.high } }
    val range = remember(minP, maxP) { (maxP - minP).takeIf { it > 0 } ?: 1.0 }
    var selIdx by remember(bars) { mutableStateOf(bars.lastIndex) }
    // 外部选中日期（来自资金流点柱）优先于图内索引：找不到该日期（不在可见区间）时沿用图内索引，不闪回
    val extIdx = remember(bars, selectedDate) {
        selectedDate?.let { d -> bars.indexOfFirst { it.date == d } } ?: -1
    }
    val effIdx = if (extIdx >= 0) extIdx else selIdx.coerceIn(0, bars.lastIndex)
    val sel = bars.getOrNull(effIdx) ?: bars.last()
    val prevClose = bars.getOrNull(effIdx - 1)?.close
    val selChg = prevClose?.let { if (it > 0) (sel.close - it) / it * 100 else null }
    // MA 序列在组合层算一次；原实现放在 Canvas draw scope 内，每次重绘重算 2×N
    val maLines = remember(bars) { listOf(5, 20).map { p -> p to MarketDataClient.ma(bars, p) } }
    // 绘制几何（蜡烛/影线/均线路径）只在「数据或画布尺寸」变化时重建。
    // 原来的写法在 draw scope 里逐根 drawLine+drawRect：250 根 = 500 次 draw 调用，
    // 每帧重放；换成 6 条 Path 后逐帧只重放 6 次 drawPath。
    // key 用「行数 + 末根日期 + 末根收盘按 0.05 元量化」，避免每秒 tick 都重建整张图，
    // 又不会漏掉盘中价格变化（超过 5 分钱必然重建）。
    val geoKey = remember(bars) {
        val last = bars.lastOrNull()
        Triple(bars.size, last?.date, ((last?.close ?: 0.0) / 0.05).toLong())
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val geo = remember(geoKey, box, minP, range, maLines) {
        if (box.width <= 0 || box.height <= 0) null
        else buildKlineGeo(bars, box.width.toFloat(), box.height.toFloat(), minP, range, maLines)
    }

    Column(modifier) {
        // 选中条的日期 + OHLC 浮层（默认即最新一根，始终可见）
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sel.date, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text("开 ${String.format("%.2f", sel.open)}", style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
            Text("高 ${String.format("%.2f", sel.high)}", style = MaterialTheme.typography.bodySmall, color = UP)
            Text("低 ${String.format("%.2f", sel.low)}", style = MaterialTheme.typography.bodySmall, color = DOWN)
            Text("收 ${String.format("%.2f", sel.close)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            selChg?.let {
                Text(
                    "涨跌 " + String.format("%+.2f%%", it),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it > 0) UP else if (it < 0) DOWN else SkColors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                // clip 让越界的蜡烛毛刺不画到卡片外；同时把画布提成独立图层，
                // 上层（每秒刷新的报价）重组时不会连带重绘整张 K 线。
                .graphicsLayer { clip = true }
                .onSizeChanged { box = it }
                .pointerInput(bars) {
                    // 长按/拖动/点击都可选中某根K线（用户反馈：长按无反应、看不到时间）
                    fun pick(x: Float, width: Float) {
                        val n = bars.size
                        if (width <= 0f || n == 0) return
                        val i = ((x / width) * (n - 1)).toInt().coerceIn(0, n - 1)
                        selIdx = i
                        // 上报选中日期：资金流据此高亮同一根柱子（松手不再回调 null，保持选中不闪回）
                        onSelectDate(bars[i].date)
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
            fun yOf(v: Double) = (h - ((v - minP) / range) * h).toFloat()

            // 蜡烛：4 条缓存路径（涨/跌 各一条影线 + 一条实体），画的时候只有 drawPath
            val g = geo
            if (g != null) {
                drawPath(g.upWick, UP, style = Stroke(width = 1.2f))
                drawPath(g.downWick, DOWN, style = Stroke(width = 1.2f))
                drawPath(g.upBody, UP)
                drawPath(g.downBody, DOWN)
                g.ma5?.let { drawPath(it, SkColors.chartMa5, style = Stroke(width = 1.6f)) }
                g.ma20?.let { drawPath(it, SkColors.chartMa20, style = Stroke(width = 1.6f)) }
            }

            // 均线兜底：画布尺寸尚未就绪时不至于空白（首帧）
            if (g == null) maLines.forEach { (p, ma) ->
                val col = if (p == 5) SkColors.chartMa5 else SkColors.chartMa20
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

            // 十字线：选中条竖线 + 水平收盘线（选中条即联动日期对应的那根）
            val sx = effIdx * step
            drawLine(
                color = SkColors.chartCrosshair,
                start = Offset(sx, 0f),
                end = Offset(sx, h),
                strokeWidth = 1f,
            )
            drawLine(
                color = SkColors.chartCrosshair,
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
                    color = SkColors.textSecondary,
                )
            }
        }
    }
}

/**
 * 三图联动信息条：把「K 线选中的某天」「资金流选中的某天」「分时选中的某分钟」的读数汇到一处。
 *
 * 为什么要联动：三张图是同一只股的不同维度（日 K 价格、日级主力资金、分钟级价格），
 * 以前各自维护选中索引，用户看到「K 线某天大涨」却无法立刻知道那天主力是流入还是流出；
 * 把选中态提到 StockDetailSheet 顶层后，任一图点选都能驱动其余两图与这条条，来回对照不用记数字。
 * 取不到的字段一律显示「—」，不插值也不编造。
 */
@Composable
internal fun LinkageBar(
    selectedDate: String?,
    selectedMinute: String?,
    bars: List<KBar>,
    flows: List<Map<String, Any?>>,
    minutePoints: List<Map<String, Any?>>,
    preClose: Double?,
) {
    val bar = remember(bars, selectedDate) {
        selectedDate?.let { d -> bars.firstOrNull { it.date == d } }
    }
    val flow = remember(flows, selectedDate) {
        selectedDate?.let { d -> flows.firstOrNull { it["date"]?.toString() == d } }
    }
    val main = (flow?.get("main") as? Number)?.toDouble()
    val mpt = remember(minutePoints, selectedMinute) {
        selectedMinute?.let { t -> minutePoints.firstOrNull { (it["time"] ?: it["t"])?.toString() == t } }
    }
    val mPrice = (mpt?.get("price") as? Number)?.toDouble()
    val mAvg = (mpt?.get("avg") as? Number)?.toDouble()
    val mChg = if (preClose != null && preClose > 0 && mPrice != null) (mPrice - preClose) / preClose * 100 else null

    SkCard(contentPadding = PaddingValues(0.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("三图联动", fontWeight = FontWeight.SemiBold)
            if (selectedDate == null && selectedMinute == null) {
                Text(
                    "长按图表查看同一天/同一分钟的联动数据（K 线长按/拖动选日、资金流点柱选日、分时点选选分钟）",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                return@Column
            }
            selectedDate?.let { d ->
                Text(d, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                Text(
                    "K线 " + (bar?.let {
                        "开 ${String.format("%.2f", it.open)}" +
                            " 高 ${String.format("%.2f", it.high)}" +
                            " 低 ${String.format("%.2f", it.low)}" +
                            " 收 ${String.format("%.2f", it.close)}"
                    } ?: "—（不在当前 K 线区间）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bar == null) SkColors.textSecondary else Color.Unspecified,
                )
                Text(
                    "资金流 主力净额 " + (main?.let { fundFlowText(it) } ?: "—（该日无资金流数据）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        main == null -> SkColors.textSecondary
                        main >= 0 -> UP
                        else -> DOWN
                    },
                )
            }
            selectedMinute?.let { t ->
                Text(
                    "分时 ${minuteTimeLabel(t)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "价格 " + (mPrice?.let { String.format("%.2f", it) } ?: "—") +
                        "　涨跌 " + (mChg?.let { String.format("%+.2f%%", it) } ?: "—") +
                        "　均价 " + (mAvg?.let { String.format("%.2f", it) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        mChg == null -> SkColors.textSecondary
                        mChg > 0 -> UP
                        mChg < 0 -> DOWN
                        else -> SkColors.textSecondary
                    },
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
 *
 * 联动：选中分钟由顶层传入/回报（selectedTime 即点位里的 time 字段，如 "0935"），
 * 这样联动信息条能与分时图显示同一个分钟的价/均价。
 */
@Composable
internal fun MinuteChart(
    points: List<Map<String, Any?>>,
    preClose: Double?,
    selectedTime: String?,
    onSelectTime: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val prices = remember(points) { points.mapNotNull { (it["price"] as? Number)?.toDouble() } }
    if (prices.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("暂无分时数据", color = SkColors.textSecondary) }
        return
    }
    val times = remember(points) { points.map { it["time"]?.toString() ?: "" } }
    val avgs = remember(points) { points.map { (it["avg"] as? Number)?.toDouble() } }
    val cumVols = remember(points) { points.map { (it["vol"] as? Number)?.toDouble() ?: 0.0 } }
    val volSteps = remember(points) {
        cumVols.mapIndexed { i, v -> if (i == 0) v else (v - cumVols[i - 1]).coerceAtLeast(0.0) }
    }
    var selIdx by remember(points) { mutableStateOf(prices.lastIndex) }
    // 外部选中分钟优先（与联动条双向一致）；找不到（如换股/换日）则沿用图内索引
    val extMinuteIdx = remember(times, selectedTime) {
        selectedTime?.let { t -> times.indexOfFirst { it == t } } ?: -1
    }
    val idx = (if (extMinuteIdx >= 0) extMinuteIdx else selIdx).coerceIn(0, prices.lastIndex)
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
            Text(minuteTimeLabel(times.getOrNull(idx) ?: ""), style = MaterialTheme.typography.bodySmall, color = SkColors.textSecondary)
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
                        val i = minutePickIndex(off.x, size.width.toFloat(), times)
                        selIdx = i
                        times.getOrNull(i)?.let { t -> onSelectTime(t) }
                    }
                }
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val i = minutePickIndex(off.x, size.width.toFloat(), times)
                            selIdx = i
                            times.getOrNull(i)?.let { t -> onSelectTime(t) }
                        },
                        onDrag = { change, _ ->
                            val i = minutePickIndex(change.position.x, size.width.toFloat(), times)
                            selIdx = i
                            times.getOrNull(i)?.let { t -> onSelectTime(t) }
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
                SkColors.textSecondary.copy(alpha = 0.55f), Offset(0f, yy(base)), Offset(size.width, yy(base)),
                strokeWidth = 1.2f, pathEffect = dash,
            )
            drawLine(SkColors.textSecondary.copy(alpha = 0.2f), Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f)

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
            drawLine(SkColors.textSecondary.copy(alpha = 0.8f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f)
            drawLine(SkColors.textSecondary.copy(alpha = 0.8f), Offset(0f, yy(selPrice)), Offset(size.width, yy(selPrice)), strokeWidth = 1f)
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
                Text(it, style = MaterialTheme.typography.labelSmall, color = SkColors.textSecondary)
            }
        }
    }
}

/**
 * 资金流柱状图：柱体直接标注当日金额 + 底部时间轴（不再把数字单独列成一块）。
 * 联动：selectedDate 与 K 线共享，同一天的柱子加亮白边；点击/长按/拖动柱子把日期回报给顶层。
 */
@Composable
internal fun FundFlowChart(
    flows: List<Map<String, Any?>>,
    selectedDate: String?,
    onSelectDate: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 金额与日期成对取，避开两个列表因缺值而错位；fullDates 保留完整日期（yyyy-MM-dd）用于与 K 线比对
    val pairs = remember(flows) {
        flows.mapNotNull { f ->
            val v = (f["main"] as? Number)?.toDouble() ?: return@mapNotNull null
            Pair(f["date"]?.toString(), v)
        }
    }
    val vals = pairs.map { it.second }
    val fullDates = pairs.map { it.first }
    val dates = fullDates.map { it?.takeLast(5) ?: "" }
    Canvas(
        modifier.pointerInput(pairs.size) {
            // 柱子横向均分，点哪根选哪天（长按/拖动同样生效）
            fun hit(x: Float) {
                if (vals.isEmpty() || size.width <= 0) return
                val bwf = size.width.toFloat() / vals.size
                val i = (x / bwf).toInt().coerceIn(0, vals.lastIndex)
                fullDates.getOrNull(i)?.let { d -> onSelectDate(d) }
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                hit(down.position.x)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull() ?: break
                    hit(ch.position.x)
                    ch.consume()
                    if (!ch.pressed) break
                }
            }
        }
    ) {
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
            val rectTopLeft = if (v >= 0) Offset(x, midY - h) else Offset(x, midY)
            val rectSize = androidx.compose.ui.geometry.Size(bw * 0.8f, h)
            drawRect(if (v >= 0) UP else DOWN, topLeft = rectTopLeft, size = rectSize)
            // 联动高亮：与选中日期相同的柱子加白色描边，K 线选中的那天在这里一眼能定位
            if (selectedDate != null && fullDates.getOrNull(i) == selectedDate) {
                drawRect(
                    Color.White.copy(alpha = 0.9f),
                    topLeft = rectTopLeft,
                    size = rectSize,
                    style = Stroke(width = 2.5f),
                )
            }
        }
        drawLine(SkColors.textSecondary.copy(alpha = 0.6f), Offset(0f, midY), Offset(size.width, midY), strokeWidth = 1f)

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

internal fun fundFlowText(v: Double): String = when {
    kotlin.math.abs(v) >= 1e8 -> String.format("%.1f亿", v / 1e8)
    kotlin.math.abs(v) >= 1e4 -> String.format("%.0f万", v / 1e4)
    else -> String.format("%.0f", v)
}


/**
 * K 线绘制几何：蜡烛影线/实体与均线全部预生成为 Path。
 *
 * 为什么不在 draw scope 里现算：draw lambda 每帧执行一次，逐根 drawLine + drawRect 是
 * 2×N 次绘制命令（250 根 = 500 次）。改成 6 条 Path 后每帧固定 6 次 drawPath，
 * 且 Path 只在数据/尺寸变化时重建——盘中每秒的报价 tick 不再触发整图重建。
 */
private class KlineGeo(
    val upWick: Path,
    val downWick: Path,
    val upBody: Path,
    val downBody: Path,
    val ma5: Path?,
    val ma20: Path?,
)

private fun buildKlineGeo(
    bars: List<KBar>,
    w: Float,
    h: Float,
    minP: Double,
    range: Double,
    maLines: List<Pair<Int, List<*>>>,
): KlineGeo {
    val n = bars.size
    val step = if (n > 1) w / (n - 1) else w
    val bw = maxOf(1.5f, (w / maxOf(n, 1)) * 0.62f)
    fun yOf(v: Double) = (h - ((v - minP) / range) * h).toFloat()
    val upWick = Path()
    val downWick = Path()
    val upBody = Path()
    val downBody = Path()
    bars.forEachIndexed { i, b ->
        val cx = i * step
        val rising = b.close >= b.open
        val yH = yOf(b.high)
        val yL = yOf(b.low)
        val wick = if (rising) upWick else downWick
        wick.moveTo(cx, yH)
        wick.lineTo(cx, yL)
        val yO = yOf(b.open)
        val yC = yOf(b.close)
        val top = minOf(yO, yC)
        val bh = maxOf(1.5f, kotlin.math.abs(yC - yO))
        val body = if (rising) upBody else downBody
        body.addRect(Rect(left = cx - bw / 2, top = top, right = cx + bw / 2, bottom = top + bh))
    }
    fun maPath(period: Int): Path? {
        val ma = maLines.firstOrNull { it.first == period }?.second ?: return null
        val path = Path()
        var started = false
        bars.forEachIndexed { i, _ ->
            val v = ma.getOrNull(i) as? Double
            if (v != null) {
                val x = i * step
                val y = yOf(v)
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }
        }
        return if (started) path else null
    }
    return KlineGeo(upWick, downWick, upBody, downBody, maPath(5), maPath(20))
}
