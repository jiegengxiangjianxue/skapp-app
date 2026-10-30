package com.skapp.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.theme.SkColors
import com.skapp.app.ui.theme.SkElevation
import com.skapp.app.ui.theme.SkMotion
import com.skapp.app.ui.theme.SkRadius
import com.skapp.app.ui.theme.SkSize
import com.skapp.app.ui.theme.SkSpace
import com.skapp.app.ui.theme.SkType
import java.util.Locale

enum class SurfaceLevel { Zero, One, Two, Three }

private fun surfaceColor(level: SurfaceLevel): Color = when (level) {
    SurfaceLevel.Zero -> SkColors.surface0
    SurfaceLevel.One -> SkColors.surface1
    SurfaceLevel.Two -> SkColors.surface2
    SurfaceLevel.Three -> SkColors.surface3
}

/**
 * 唯一卡片容器：18dp 圆角 + 1dp 描边 + 1dp elevation，内边距统一 12dp。
 * 替代全库 41 处 Card(colors = CardDefaults.cardColors())。
 */
@Composable
fun SkCard(
    modifier: Modifier = Modifier,
    level: SurfaceLevel = SurfaceLevel.One,
    onClick: (() -> Unit)? = null,
    /** 内容内边距；默认 12dp。迁移旧卡片时传 `PaddingValues(0.dp)`，
     *  让内容自带的 padding 生效，做到内边距与迁移前逐位一致。 */
    contentPadding: PaddingValues = PaddingValues(SkSpace.cardPad),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Card(
        modifier = modifier.then(clickable),
        shape = SkRadius.card,
        colors = CardDefaults.cardColors(containerColor = surfaceColor(level)),
        border = BorderStroke(1.dp, SkColors.outlineWeak),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (level == SurfaceLevel.One) SkElevation.card else 0.dp,
        ),
        content = { Column(Modifier.padding(contentPadding), content = content) },
    )
}

/** 带标题的区块卡：标题 + 可选副标题 + 可选操作 + 分隔线。 */
@Composable
fun SkSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    SkCard(modifier = modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = SkType.title, color = SkColors.textPrimary)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = SkType.caption, color = SkColors.textSecondary)
                }
            }
            action?.invoke()
        }
        HorizontalDivider(color = SkColors.divider, thickness = 1.dp)
        Spacer(Modifier.height(SkSpace.s))
        content()
    }
}

enum class UiStateKind { Loading, Empty, Error }

/** 空/错/加载三态唯一入口。 */
@Composable
fun StateView(
    kind: UiStateKind,
    message: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val container = if (kind == UiStateKind.Error) SkColors.errorContainer else SkColors.surface2
    val textColor = if (kind == UiStateKind.Error) SkColors.errorText else SkColors.textSecondary
    Column(
        modifier
            .fillMaxWidth()
            .clip(SkRadius.inner)
            .background(container)
            .padding(SkSpace.l),
        verticalArrangement = Arrangement.spacedBy(SkSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (kind == UiStateKind.Loading) {
                CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(SkSpace.m))
            }
            Text(message, style = SkType.bodySm, color = textColor)
        }
        if (!detail.isNullOrBlank()) {
            Text(detail, style = SkType.caption, color = SkColors.textTertiary)
        }
        if (onRetry != null && kind == UiStateKind.Error) {
            TextButton(onClick = onRetry) { Text("重试", style = SkType.label) }
        }
    }
}

/** 选择 chip（复权/区间/池子），选中态靠颜色 + 加粗 + 底座，不只靠颜色。 */
@Composable
fun SkChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .defaultMinSize(minHeight = SkSize.touchMin)
            .clip(SkRadius.chip)
            .background(if (selected) SkColors.surface1 else SkColors.surface3)
            .clickable { onClick() }
            .padding(horizontal = SkSpace.m, vertical = SkSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = SkType.label,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) SkColors.primary else SkColors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 分段 Tab：容器 surface3 + 选中块 surface1。 */
@Composable
fun SkTabBar(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(SkRadius.chip)
            .background(SkColors.surface3)
            .padding(SkSpace.xxs),
        horizontalArrangement = Arrangement.spacedBy(SkSpace.xxs),
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = SkSize.touchMin)
                    .clip(SkRadius.chip)
                    .background(if (active) SkColors.surface1 else Color.Transparent)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = SkType.label,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) SkColors.primary else SkColors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** RowScope 内使用的轻量分隔点，避免每处手写 " · " 拼接。 */
@Composable
fun RowScope.DotSep() {
    Text("·", style = SkType.caption, color = SkColors.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
}
