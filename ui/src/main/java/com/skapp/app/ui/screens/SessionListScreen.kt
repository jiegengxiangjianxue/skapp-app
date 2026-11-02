package com.skapp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.components.SkCard
import com.skapp.app.ui.components.SurfaceLevel
import com.skapp.app.ui.theme.SkColors
import com.skapp.app.ui.theme.SkSpace
import com.skapp.app.ui.theme.SkType

data class Session(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
)

/** 会话列表启动页：显示最近会话，支持新建。 */
@Composable
fun SessionListScreen(
    onNewSession: () -> Unit,
    onSelectSession: (Session) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mock data for now — real storage can be added later
    val sessions = listOf(
        Session("s1", "今日市场复盘", "大盘指数震荡上行，科技股领涨...", 1730500000000L),
        Session("s2", "候选池分析", "连续3天筛选结果对比，资金流向...", 1730400000000L),
        Session("s3", "板块动量观察", "半导体、新能源板块轮动分析...", 1730300000000L),
        Session("s4", "历史回测验证", "策略胜率回测，参数敏感性分析...", 1730200000000L),
        Session("s5", "新闻事件解读", "政策面、消息面影响评估...", 1730100000000L),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(SkSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "识股",
            style = SkType.headline,
            color = SkColors.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "选择或新建一个会话继续分析",
            style = SkType.bodySm,
            color = SkColors.textSecondary,
            modifier = Modifier.padding(top = SkSpace.xs),
        )

        Spacer(Modifier.height(32.dp))

        // 新建会话按钮
        SkCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNewSession() },
            level = SurfaceLevel.Two,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", style = MaterialTheme.typography.headlineMedium, color = SkColors.primary)
                Spacer(Modifier.width(SkSpace.m))
                Text("新建会话", style = SkType.title, color = SkColors.primary)
            }
        }

        Spacer(Modifier.height(SkSpace.l))
        Text("最近会话", style = SkType.caption, color = SkColors.textTertiary)
        Spacer(Modifier.height(SkSpace.s))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SkSpace.s),
        ) {
            items(sessions) { session ->
                SkCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectSession(session) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.title,
                                style = SkType.title,
                                color = SkColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(SkSpace.xs))
                            Text(
                                text = session.preview,
                                style = SkType.bodySm,
                                color = SkColors.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = formatTime(session.updatedAt),
                            style = SkType.caption,
                            color = SkColors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000}分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000}小时前"
        else -> "${diff / 86_400_000}天前"
    }
}
