package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

/**
 * 提问 + AI 合并页：一个入口，两个引擎。
 * - 本地规则引擎（AskEngine）：免费、快、无 token，基于云端数据规则匹配
 * - GitCode AI：深度分析、复盘、自由问答（需 token）
 */
@Composable
fun UnifiedAskScreen(vm: SkappViewModel, state: UiState) {
    var mode by remember { mutableStateOf(0) }  // 0=本地规则 1=GitCode AI

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("智能问答", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        // 模式切换
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModeChip("本地规则", selected = mode == 0) { mode = 0 }
            ModeChip("GitCode AI", selected = mode == 1) { mode = 1 }
        }

        when (mode) {
            0 -> LocalAskMode(vm, state)
            1 -> AiMode(vm, state)
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

// ── 本地规则模式（原 AskScreen）──────────────────────────────

@Composable
private fun LocalAskMode(vm: SkappViewModel, state: UiState) {
    Text(
        "本地规则引擎：免费、快速、无需 token。基于云端真实数据做规则匹配。",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
    )

    OutlinedTextField(
        value = state.askQuestion,
        onValueChange = vm::onQuestionChange,
        label = { Text("输入你的问题") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::ask, enabled = !state.askLoading) {
            Text(if (state.askLoading) "分析中…" else "提问")
        }
        if (state.askLoading) CircularProgressIndicator(Modifier.height(20.dp))
    }

    // 建议问题
    Text("试试这些：", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    val suggestions = listOf(
        "今天市场情绪怎么样？",
        "哪些股票在放量上涨？",
        "推荐几只低估值股票",
        "今天有什么主线题材？",
        "帮我分析一下茅台",
    )
    suggestions.forEach { s ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .clickable { vm.onQuestionChange(s) },
            colors = CardDefaults.cardColors(),
        ) {
            Text(s, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }

    // 回答
    state.askAnswer?.let { ans ->
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("回答", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(ans, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // 命中股票
    if (state.askMatches.isNotEmpty()) {
        Text(
            "命中 ${state.askMatches.size} 只，点击任意一张卡查看完整详情",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )
        state.askMatches.forEach { d ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { vm.openDetail(d.code) },
                colors = CardDefaults.cardColors(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(d.name ?: d.code, fontWeight = FontWeight.Medium)
                    Text(d.code, color = Color.Gray)
                }
            }
        }
    }
}

// ── GitCode AI 模式（原 AiScreen）────────────────────────────

@Composable
private fun AiMode(vm: SkappViewModel, state: UiState) {
    Text(
        "GitCode AI：deepseek-ai/DeepSeek-V4-Flash · 免费额度 · 只基于真实数据回答",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
    )

    // token 配置
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("token 配置", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = state.aiToken,
                onValueChange = vm::setAiToken,
                label = { Text("GitCode token") },
                placeholder = { Text("在 gitcode.com 个人设置 → 访问令牌") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.saveTokenNow() },
                enabled = state.aiToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并启用")
            }
            Text(
                if (state.aiConfigured) "已配置，可用" else "未配置",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.aiConfigured) Color(0xFF3FB950) else Color(0xFFD29922),
            )
        }
    }

    // AI 复盘
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("AI 复盘", fontWeight = FontWeight.SemiBold)
            Text(
                "对当前详情页的股票做全面复盘（实时行情 + 12 引擎 + 29 因子 + holo 技术面 + 财务 + 新闻 + 板块）。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Spacer(Modifier.height(6.dp))
            if (state.detail == null) {
                Text("先打开一只股票的详情页，再点下方按钮。", color = Color(0xFFD29922))
            } else {
                Text(
                    "当前股票：${state.detail?.name ?: state.detail?.code}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = vm::aiReviewCurrent,
                    enabled = state.aiConfigured && !state.aiReviewLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.aiReviewLoading) "AI 复盘中…" else "复盘这只股票")
                }
                if (state.aiReviewLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(20.dp))
                        Spacer(Modifier.padding(4.dp))
                        Text("AI 正在基于真实数据分析…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // AI 问答
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text("自由提问", fontWeight = FontWeight.SemiBold)
            Text(
                "AI 会看到 app 当前拉到的全部上下文：行情 / 推股 / 决策 / 看板 / 消息情绪 / 多池 / 当前详情股的完整本地分析。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.aiQuestion,
                onValueChange = vm::onAiQuestionChange,
                label = { Text("你的问题") },
                placeholder = { Text("例：今天市场情绪怎么样？哪只推股最值得关注？") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = vm::aiAsk,
                enabled = state.aiConfigured && !state.aiAnswerLoading && state.aiQuestion.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.aiAnswerLoading) "AI 回答中…" else "提问")
            }
        }
    }

    // AI 输出
    state.aiReview?.let {
        AiOutputBlock("AI 复盘结果", it)
    }
    state.aiAnswer?.let {
        AiOutputBlock("AI 回答", it)
    }

    if (!state.aiConfigured) {
        Card(colors = CardDefaults.cardColors()) {
            Text(
                "提示：token 是一次性配置，app 会记住；拉取逻辑与站点 CI 的 ai_review / bull_bear_debate 同一套端点。",
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun AiOutputBlock(title: String, text: String) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
