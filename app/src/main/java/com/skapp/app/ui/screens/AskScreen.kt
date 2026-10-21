package com.skapp.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState

private val SUGGESTIONS = listOf(
    "600519 现在能买吗",
    "宁德时代 有什么风险",
    "300750 技术面怎么样",
    "600941 财务如何",
    "腾讯控股 属于什么板块",
)

/** 提问 Tab：自然语言问个股，答案只来自真实数据 + 预生成 AI 问答 */
@Composable
fun AskScreen(vm: SkappViewModel, state: UiState) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("提问选股", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "用自然语言问个股，答案基于云端真实数据与引擎产出。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }

        item {
            OutlinedTextField(
                value = state.askQuestion,
                onValueChange = vm::onQuestionChange,
                label = { Text("输入你的问题") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::ask, enabled = !state.askLoading) {
                    Text(if (state.askLoading) "分析中…" else "提问")
                }
                if (state.askLoading) CircularProgressIndicator()
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("试试这些：", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                SUGGESTIONS.forEach { s ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .clickable { vm.onQuestionChange(s) },
                        colors = CardDefaults.cardColors(),
                    ) {
                        Text(s, Modifier.padding(12.dp))
                    }
                }
            }
        }

        state.askAnswer?.let { ans ->
            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("回答", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(ans, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (state.askMatches.isNotEmpty()) {
                item {
                    Text(
                        "命中 ${state.askMatches.size} 只，点击任意一张卡查看完整详情",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                items(state.askMatches, key = { it.code }) { d ->
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
    }
}
