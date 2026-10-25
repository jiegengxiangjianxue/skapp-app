package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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

/** 唯一 AI 总结入口：双源状态、个股复盘、市场总结和数据问答。 */
@Composable
fun UnifiedAskScreen(vm: SkappViewModel, state: UiState) {
    val aiAvailable = state.aiConfigured || state.githubConfigured
    val detail = state.detail
    val summaryText = state.summary["text"]?.toString()?.takeIf { it.isNotBlank() }
    val summaryStamp = listOfNotNull(
        state.summary["date"]?.toString(),
        state.summary["session"]?.toString(),
        state.summary["ts"]?.toString(),
    ).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "时点 unknown" }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("AI 总结", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "GitCode 优先、GitHub Models 备份；所有结论仅基于已拉取的真实数据。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
        )

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("双源状态", fontWeight = FontWeight.SemiBold)
                Text("GitCode：${providerState(state.aiConfigured)}")
                Text("GitHub Models：${providerState(state.githubConfigured)}")
                if (state.aiProviderStatus.isNotBlank()) {
                    Text(state.aiProviderStatus, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                state.aiLastProvider?.let {
                    Text("最近调用：$it", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("token 配置", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = state.aiToken,
                    onValueChange = vm::setAiToken,
                    label = { Text("GitCode token") },
                    placeholder = { Text("gitcode.com 个人设置 → 访问令牌") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = vm::saveTokenNow,
                    enabled = state.aiToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存 GitCode token") }
                Text(
                    if (state.aiConfigured) "已配置，可用" else "未配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.aiConfigured) Color(0xFF3FB950) else Color(0xFFD29922),
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.githubToken,
                    onValueChange = vm::setGithubToken,
                    label = { Text("GitHub token") },
                    placeholder = { Text("github.com → Settings → Personal access token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = vm::saveGithubTokenNow,
                    enabled = state.githubToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存 GitHub token") }
                Text(
                    if (state.githubConfigured) "已配置，可用" else "未配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.githubConfigured) Color(0xFF3FB950) else Color(0xFFD29922),
                )
            }
        }

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("个股复盘", fontWeight = FontWeight.SemiBold)
                Text(
                    "使用当前详情页的实时行情、12 引擎、29 因子、技术面、财务、新闻和板块数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(6.dp))
                if (detail == null) {
                    Text("暂无数据：请先打开一只股票的详情页。", color = Color(0xFFD29922))
                } else {
                    Text("当前股票：${detail.name ?: detail.code}", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = vm::aiReviewCurrent,
                        enabled = aiAvailable && !state.aiReviewLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.aiReviewLoading) "复盘中…" else "复盘这只股票") }
                    if (state.aiReviewLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("AI 正在基于真实数据分析…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("市场总结", fontWeight = FontWeight.SemiBold)
                Text("来源：实时总结接口；时点：$summaryStamp", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text(summaryText ?: "暂无数据：市场总结未返回有效内容。", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp)) {
                Text("数据问答", fontWeight = FontWeight.SemiBold)
                Text(
                    "可追问行情、推股、决策、消息、多池和当前详情股；缺少真实数据时返回 unknown。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.aiQuestion,
                    onValueChange = vm::onAiQuestionChange,
                    label = { Text("你的问题") },
                    placeholder = { Text("例：今天市场情绪怎么样？") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                Button(
                    onClick = vm::aiAsk,
                    enabled = aiAvailable && !state.aiAnswerLoading && state.aiQuestion.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.aiAnswerLoading) "回答中…" else "提问") }
            }
        }

        state.aiReview?.let { AiOutputBlock("个股复盘结果", it) }
        state.aiAnswer?.let { AiOutputBlock("数据问答结果", it) }

        if (!aiAvailable) {
            Text("请至少配置一个 AI token；未配置时 AI 结果为 unknown。", color = Color.Gray,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun providerState(configured: Boolean): String = if (configured) "已配置" else "未配置，状态 unknown"

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
