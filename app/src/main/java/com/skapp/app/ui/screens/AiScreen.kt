package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
 * AI Tab —— GitCode 免费 AI。
 *   1) 单股 AI 复盘（把当前详情页的全部真实数据给 AI）
 *   2) 自由提问（把 app 当前全部上下文给 AI）
 *
 * token 由用户在下方输入框填入（存 SharedPreferences；本类不内置）。
 */
@Composable
fun AiScreen(vm: SkappViewModel, state: UiState) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("GitCode AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "模型：deepseek-ai/DeepSeek-V4-Flash（备选 Qwen3-30B） · 免费额度 · 只基于真实数据回答",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        // ── token 配置 ──
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

        // ── AI 复盘（当前个股）──
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
                            CircularProgressIndicator()
                            Spacer(Modifier.padding(4.dp))
                            Text("AI 正在基于真实数据分析…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // ── AI 问答 ──
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

        // ── AI 输出 ──
        state.aiReview?.let {
            AiOutput("AI 复盘结果", it)
        }
        state.aiAnswer?.let {
            AiOutput("AI 回答", it)
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
}

@Composable
private fun AiOutput(title: String, text: String) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
