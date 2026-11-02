package com.skapp.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.theme.SkColors
import com.skapp.app.ui.theme.SkType

/** 时点格式化（复用 SessionListScreen 中的同名逻辑）。 */
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

/** 会话详情页：展示会话摘要；"今日实时看盘" 会话会跳转到对应主页面。 */
@Composable
fun SessionDetailScreen(session: Session) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = session.title,
            style = SkType.headline,
            color = SkColors.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = session.preview.ifEmpty { "暂无摘要" },
            style = SkType.bodySm,
            color = SkColors.textSecondary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "时点 ${formatTime(session.updatedAt)}",
            style = SkType.caption,
            color = SkColors.textTertiary,
        )
        Spacer(Modifier.weight(1f))
        // 底部占位提示：告诉用户该会话已就绪
        Text(
            text = if (session.id == "today_board") {
                "切换到「今日看盘」tab 查看实时数据"
            } else {
                "会话内容待加载"
            },
            style = MaterialTheme.typography.bodySmall,
            color = SkColors.textSecondary,
        )
    }
}