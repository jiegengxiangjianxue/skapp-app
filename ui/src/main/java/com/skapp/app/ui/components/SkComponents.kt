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

/** 语义色调：屏幕里只允许通过 Tone 取色，禁止直接写 Color(0x...)。 */
enum class Tone { Up, Down, Flat, Warn, Success, Info, Neutral }

fun toneOf(value: Double?): Tone = when {
    value == null -> Tone.Flat
    value > 0 -> Tone.Up
    value < 0 -> Tone.Down
    else -> Tone.Flat
}

fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Up -> SkColors.marketUp
    Tone.Down -> SkColors.marketDown
    Tone.Flat -> SkColors.marketFlat
    Tone.Warn -> SkColors.warn
    Tone.Success -> SkColors.success
    Tone.Info -> SkColors.primary
    Tone.Neutral -> SkColors.textSecondary
}

fun toneContainer(tone: Tone): Color = when (tone) {
    Tone.Up -> SkColors.marketUpContainer
    Tone.Down -> SkColors.marketDownContainer
    Tone.Warn -> SkColors.warnContainer
    Tone.Success -> SkColors.marketDownContainer
    Tone.Info -> SkColors.surface3
    Tone.Flat, Tone.Neutral -> SkColors.surface3
}

/** 颜色不是唯一通道：符号必须与颜色同时出现。 */
fun toneSymbol(tone: Tone): String = when (tone) {
    Tone.Up -> "▲"
    Tone.Down -> "▼"
    Tone.Warn -> "!"
    else -> ""
}

/** 数值格式化唯一入口，替代散落的 String.format("%+.2f%%")。 */
object SkFormat {
    fun pct(value: Double?, digits: Int = 2, sign: Boolean = true): String {
        if (value == null) return "—"
        val fmt = (if (sign) "%+." else "%.") + digits + "f%%"
        return String.format(Locale.US, fmt, value)
    }

    fun num(value: Double?, digits: Int = 2): String {
        if (value == null) return "—"
        return String.format(Locale.US, "%." + digits + "f", value)
    }

    fun signed(value: Double?, digits: Int = 2): String {
        if (value == null) return "—"
        return String.format(Locale.US, "%+." + digits + "f", value)
    }
}

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

/** 区块标题（对齐 BoardScreen 与 DashboardScreen 两份 private 实现）。 */
@Composable
fun SkSectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = SkType.title, color = SkColors.textPrimary)
        action?.invoke()
    }
}

/** 指标块：标签 + 数值，数值走 Tone 取色。 */
@Composable
fun MetricCell(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
    valueStyle: TextStyle = SkType.displayNumber,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = SkType.caption, color = SkColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(SkSpace.xxs))
        Text(
            value ?: "—",
            style = valueStyle,
            color = toneColor(tone),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 百分比文本：符号 + 数值 + 颜色三通道，null 显示「—」而不是 0。 */
@Composable
fun PercentText(
    value: Double?,
    modifier: Modifier = Modifier,
    digits: Int = 2,
    sign: Boolean = true,
    style: TextStyle = SkType.number,
) {
    val tone = toneOf(value)
    Text(
        text = if (value == null) "—" else toneSymbol(tone) + SkFormat.pct(value, digits, sign),
        style = style,
        color = toneColor(tone),
        maxLines = 1,
        modifier = modifier,
    )
}

/** 涨跌/警示 chip：文字 + 符号 + 底色，触达高度 ≥48dp。 */
@Composable
fun ChangeChip(
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val bg = if (filled) toneContainer(tone) else Color.Transparent
    val click = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Box(
        modifier
            .defaultMinSize(minHeight = SkSize.touchMin)
            .clip(SkRadius.chip)
            .background(bg)
            .then(click)
            .padding(horizontal = SkSpace.m, vertical = SkSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text("${toneSymbol(tone)}$text", style = SkType.label, color = toneColor(tone), maxLines = 1)
    }
}

/** 表格单元格：文本 + 色调 + 是否加粗（表头）。 */
data class TableCell(
    val text: String,
    val tone: Tone = Tone.Neutral,
    val bold: Boolean = false,
)

/**
 * 表格行：表头与数据行共用，权重由调用方给（现有看板/指数/选股跟踪三处写法收敛到这里）。
 */
@Composable
fun TableRow(
    cells: List<TableCell>,
    weights: List<Float>,
    modifier: Modifier = Modifier,
    header: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val click = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (onClick != null) SkSize.touchMin else 0.dp)
            .then(click)
            .padding(vertical = if (header) SkSpace.xs else 2.dp),
    ) {
        cells.forEachIndexed { i, cell ->
            Text(
                cell.text,
                Modifier.weight(weights.getOrElse(i) { 1f }),
                style = if (header) SkType.caption else SkType.number,
                fontWeight = if (cell.bold || header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) SkColors.textSecondary else toneColor(cell.tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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

/** 骨架块：不驱动布局变化，只做透明度呼吸。 */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier, shape: Shape = SkRadius.inner) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SkMotion.SHIMMER, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(SkColors.skeleton.copy(alpha = alpha)),
    )
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

/** 图表容器：统一高度令牌 + 标题区，内部只放 Canvas/图例。 */
@Composable
fun ChartCard(
    title: String,
    height: Dp,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    SkSectionCard(title = title, modifier = modifier, subtitle = subtitle, action = action) {
        Box(Modifier.fillMaxWidth().height(height)) { content() }
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
