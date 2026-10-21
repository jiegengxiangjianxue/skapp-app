package com.skapp.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.data.KBar
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private val UP = Color(0xFFF85149)
private val DOWN = Color(0xFF3FB950)

/**
 * 个股详情（原 app 缺失的功能）：实时报价 + 日 K 图 + 技术面 + 财务摘要 +
 * 引擎评分 + 所属板块 + AI 问答 + 数据源/时点。
 * 所有字段缺失即显示「暂无数据」/ insufficient。
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
                Text("正在拉取个股数据…")
            }
            return@Column
        }

        // 实时报价（自选列表行已刷新，这里直接引用其报价）
        val row = state.quotes.firstOrNull { it.code == d.code }
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("代码 ${d.code}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                val pct = row?.pctChg
                Text(
                    "现价 ${row?.price?.let { String.format("%.2f", it) } ?: "暂无数据"}" +
                        (pct?.let { "　${String.format("%.2f%%", it)}" } ?: ""),
                    style = MaterialTheme.typography.headlineSmall,
                    color = when {
                        pct == null -> Color.Gray
                        pct > 0 -> UP
                        pct < 0 -> DOWN
                        else -> Color.Gray
                    },
                )
            }
        }

        // 日 K 图
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("日 K 线（前复权）", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (state.klineLoading) {
                    CircularProgressIndicator()
                } else if (state.kline.isEmpty()) {
                    Text("暂无数据：K 线接口未返回。", color = Color.Gray)
                } else {
                    KlineChart(state.kline, Modifier.fillMaxWidth().height(180.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.kline.first().date} → ${state.kline.last().date}　共 ${state.kline.size} 根",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }
        }

        // 技术面
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

        // 财务摘要
        SectionCard("财务摘要") {
            val fin = d.detail?.financialLatest
            if (fin.isNullOrEmpty()) {
                Text("暂无数据", color = Color.Gray)
            } else {
                fin.entries.take(12).forEach { (k, v) ->
                    Text("${finLabel(k)}：${v ?: "暂无数据"}")
                }
            }
        }

        // 引擎评分
        SectionCard("引擎评分") {
            if (d.engineScores.isEmpty()) {
                Text("暂无数据", color = Color.Gray)
            } else {
                d.engineScores.entries.forEach { (k, v) ->
                    Text("$k：${v?.let { String.format("%.2f", it) } ?: "暂无数据"}")
                }
            }
        }

        // 板块
        SectionCard("所属板块") {
            if (d.boards.isEmpty()) Text("暂无数据", color = Color.Gray)
            else Text(d.boards.joinToString("、"))
        }

        // AI 问答
        SectionCard("AI 问答（基于真实数据）") {
            val qa = d.aiQa
            if (qa == null || qa.status != "ok") {
                Text(
                    "暂无数据：该股尚未生成 AI 问答（${qa?.reason ?: "未生成"}）。",
                    color = Color.Gray,
                )
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

        // 数据来源与时点
        SectionCard("数据来源") {
            val ts = d.detail?.ts
            Text("数据时点：${ts ?: "暂无数据"}")
            val src = state.picks.sources
            val upstream = src["upstream"] as? List<*>
            if (upstream.isNullOrEmpty()) {
                Text("来源清单：暂无数据", color = Color.Gray)
            } else {
                Text(
                    "来源清单（${upstream.size} 项，节选）：",
                    style = MaterialTheme.typography.bodySmall,
                )
                upstream.take(8).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
            val status = d.detail?.status
            if (status != null && status != "ok") {
                Text("覆盖状态：insufficient（${d.detail?.reason ?: "数据缺失"}）", color = Color(0xFFD29922))
            }
        }
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

/** 简易日 K 折线图（收盘价） */
@Composable
private fun KlineChart(bars: List<KBar>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (bars.size < 2) return@Canvas
        val closes = bars.map { it.close }
        val min = closes.min()
        val max = closes.max()
        val range = (max - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (bars.size - 1)
        val path = Path()
        bars.forEachIndexed { i, b ->
            val x = i * stepX
            val y = (size.height - ((b.close - min) / range) * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color(0xFF58A6FF), style = Stroke(width = 3f))
        // 最后一点
        val last = bars.last()
        val lx = size.width
        val ly = (size.height - ((last.close - min) / range) * size.height).toFloat()
        drawCircle(Color(0xFF58A6FF), radius = 5f, center = Offset(lx, ly))
    }
}

private fun techLabel(k: String): String = when (k) {
    "ma5" -> "MA5"; "ma20" -> "MA20"; "ma60" -> "MA60"
    "rsi14" -> "RSI14"; "macd" -> "MACD"; "vol_ratio" -> "量比"; "pos20" -> "20日位置"
    else -> k.uppercase()
}

private fun finLabel(k: String): String = when (k) {
    "roe" -> "ROE"; "roa" -> "ROA"; "revenue" -> "营收"; "net_profit" -> "净利润"
    "eps" -> "EPS"; "pe" -> "市盈率"; "pb" -> "市净率"; "report_date" -> "报告期"
    "total_assets" -> "总资产"; "net_assets" -> "净资产"
    else -> k
}
