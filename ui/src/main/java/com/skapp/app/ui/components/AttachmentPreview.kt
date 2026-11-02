package com.skapp.app.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skapp.app.ui.theme.SkColors
import com.skapp.app.ui.theme.SkType

/**
 * 附件预览卡片：显示图片或文件图标，支持删除。
 */
@Composable
fun AttachmentPreview(
    uri: Uri,
    label: String? = null,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(80.dp)
            .clip(RoundedCornerShape(8.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 缩略图区域（用占位背景）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(SkColors.surface3),
            contentAlignment = Alignment.Center,
        ) {
            Text("📎", fontSize = 24.sp)
            // 删除按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x99000000))
                    .clickable { onRemove() }
                    .width(16.dp)
                    .height(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = Color.White, fontSize = 10.sp)
            }
        }
        // 标签
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = SkType.caption.copy(fontSize = 10.sp),
                color = SkColors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
