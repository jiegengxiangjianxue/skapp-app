package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.SkappViewModel
import com.skapp.app.ui.UiState
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.theme.SkColors

/**
 * AI 双源凭据区：状态 + token 配置。
 *
 * 只在「凭据」入口使用（AI 问答子页已删，这份表单不再有第二处使用者）。
 */
@Composable
fun TokenConfigCard(vm: SkappViewModel, state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("双源状态", fontWeight = FontWeight.SemiBold)
                Text("GitCode：${providerState(state.aiConfigured)}")
                Text("GitHub Models：${providerState(state.githubConfigured)}")
                if (state.aiProviderStatus.isNotBlank()) {
                    Text(
                        state.aiProviderStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
                state.aiLastProvider?.let {
                    Text(
                        "最近调用：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = SkColors.textSecondary,
                    )
                }
            }
        }

        SkCard(contentPadding = PaddingValues(0.dp)) {
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
                    color = if (state.aiConfigured) SkColors.success else SkColors.warn,
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
                    color = if (state.githubConfigured) SkColors.success else SkColors.warn,
                )
            }
        }

        SkCard(contentPadding = PaddingValues(0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("自定义接口（兜底）", fontWeight = FontWeight.SemiBold)
                Text(
                    "OpenAI 兼容协议。Base URL 填到 /v1 一级或直接填完整 /chat/completions 路径都行；" +
                        "仅在两个官方源都不可用时才会走这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkColors.textSecondary,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = state.customBaseUrl,
                    onValueChange = vm::setCustomBaseUrl,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-gateway.example.com/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.customApiKey,
                    onValueChange = vm::setCustomApiKey,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.customModel,
                    onValueChange = vm::setCustomModel,
                    label = { Text("模型（可留空）") },
                    placeholder = { Text("gpt-4o-mini") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = vm::saveCustomNow,
                    enabled = state.customBaseUrl.isNotBlank() && state.customApiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存自定义接口") }
                Text(
                    if (state.customConfigured) "已配置，可用" else "未配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.customConfigured) SkColors.success else SkColors.warn,
                )
            }
        }
    }
}

internal fun providerState(configured: Boolean): String =
    if (configured) "已配置" else "未配置，状态 unknown"
