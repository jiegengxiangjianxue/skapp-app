package com.skapp.app.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 「全部功能」总览 —— 网页版功能的模块入口，不再依赖底栏塞下所有页面。
 *
 * 信息架构与网页版一致：
 *   行情看板 / 推股池 / 板块题材 / 个股决策 / 引擎模型 / 资讯 AI / 系统自检。
 * onNavigate 使用 MainScreen 的统一模块编号，所有模块共用一份 ViewModel 数据。
 */
@Composable
fun HubScreen(vm: SkappViewModel, state: UiState, onNavigate: (Int) -> Unit) {
    val modules = listOf(
        ModuleSpec("决策卡", "大盘状态 · 仓位建议 · 个股买卖点", "决策", 6,
            "${state.decision.cards.size} 张决策卡", Color(0xFF58A6FF)),
        ModuleSpec("板块地图", "行业 / 概念 / 板块成分股", "板块", 8,
            "${state.sectors.industries.size + state.sectors.concepts.size} 个板块", Color(0xFFBC8CFF)),
        ModuleSpec("多池选股", "好股票 / 板块池 / 周期池 / 低位池", "多池", 7,
            "${state.pools.size} 个选股池", Color(0xFF3FB950)),
        ModuleSpec("全球与自选", "A股指数 / 全球股市 / 自选行情", "行情", 5,
            "${state.aShareIndices.size + state.globalIndices.size} 个指数", Color(0xFF39C5CF)),
        ModuleSpec("每日总结", "盘前 / 盘中 / 盘后自动复盘", "复盘", 10,
            "日报与复盘", Color(0xFFD29922)),
        ModuleSpec("引擎产出", "v5 排行 / 周期池 / RPS 轮动 / 策略卡", "引擎", 9,
            "本地与云端引擎", Color(0xFFF778BA)),
        ModuleSpec("AI 复盘", "基于真实数据的市场解读", "AI", 11,
            "AI 分析", Color(0xFF79C0FF)),
        ModuleSpec("AI 问答", "GitCode AI + GitHub Models 双源", "问答", 13,
            "双源问答", Color(0xFF56D364)),
        ModuleSpec("数据源自检", "逐项探测 HTTP 状态与解析数量", "系统", 12,
            "链路诊断", Color(0xFF8B949E)),
    )

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "网页版全部功能已拆成独立模块；每页都有独立加载与错误说明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        items(modules.size / 2 + modules.size % 2) { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (col in 0..1) {
                    val idx = row * 2 + col
                    Box(Modifier.weight(1f)) {
                        if (idx < modules.size) ModuleCard(modules[idx], onNavigate)
                        else Spacer(Modifier.fillMaxWidth())
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private data class ModuleSpec(
    val title: String,
    val description: String,
    val category: String,
    val index: Int,
    val meta: String,
    val accent: Color,
)

@Composable
private fun ModuleCard(m: ModuleSpec, onNavigate: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onNavigate(m.index) },
        colors = CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 4.dp, height = 34.dp)
                        .background(m.accent, MaterialTheme.shapes.small)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    m.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = m.accent,
                    modifier = Modifier.weight(1f),
                )
                Text("›", color = m.accent, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(
                m.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Text(m.meta, style = MaterialTheme.typography.labelSmall, color = m.accent)
        }
    }
}
