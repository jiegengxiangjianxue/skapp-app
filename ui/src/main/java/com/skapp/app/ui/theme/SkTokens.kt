package com.skapp.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌唯一来源。屏幕/组件里不允许出现 Color(0x...) 与裸 sp/dp 常量。
 *
 * 取值口径：
 *  - 颜色对比度均按 sRGB 相对亮度对 #FFFFFF 计算，正文级 ≥4.5:1；
 *  - 涨跌沿用 A 股红涨绿跌，但加深到可读区间（旧 #F85149 3.35:1 / #3FB950 2.54:1 不合规）；
 *  - 同一语义只允许一个 token，新增 token 需同时给出用途与对比度，禁止为单个组件临时加。
 */
object SkColors {
    // ── 主色与强调 ──
    val primary = Color(0xFF175D9C)          // 6.81:1
    val onPrimary = Color(0xFFFFFFFF)
    val secondary = Color(0xFF50708D)
    val tertiary = Color(0xFF657B42)
    val info = primary

    // ── 涨跌（红涨绿跌）──
    val marketUp = Color(0xFFC62828)         // 5.62:1
    val marketUpContainer = Color(0xFFFDECEC)
    val marketDown = Color(0xFF2E7D32)       // 5.13:1
    val marketDownContainer = Color(0xFFEAF4EA)
    val marketFlat = Color(0xFF4C5D6B)       // 6.81:1
    val limitUp = Color(0xFFB71C1C)          // 6.57:1
    val limitUpContainer = Color(0xFFFFE9E9)

    // ── 状态色 ──
    val warn = Color(0xFF8A5A00)             // 5.93:1
    val warnContainer = Color(0xFFFFF6E0)
    val success = Color(0xFF1F6F43)          // 6.15:1，与 marketDown 分色，避免「绿=跌/绿=成功」歧义
    val errorText = Color(0xFF8B2500)        // 8.89:1
    val errorContainer = Color(0xFFFFEBEE)

    // ── 文字三级 ──
    val textPrimary = Color(0xFF18232D)      // 15.6:1
    val textSecondary = Color(0xFF4C5D6B)    // 6.81:1，替代 Color.Gray(3.54:1)
    val textTertiary = Color(0xFF6B7280)     // 4.83:1

    // ── 表面层级 0-3 ──
    val surface0 = Color(0xFFF4F7FA)         // 页面底
    val surface1 = Color(0xFFFFFFFF)         // 卡片
    val surface2 = Color(0xFFF8FAFC)         // 凹陷块 / 骨架底
    val surface3 = Color(0xFFE8EEF3)         // pill / Tab 槽 / 浮层

    // ── 描边与分隔 ──
    val outlineWeak = Color(0xFFD5E0E8)
    val outlineStrong = Color(0xFFB8C5CF)
    val divider = Color(0xFFE6EDF3)

    // ── 图表 ──
    val chartUp = marketUp
    val chartDown = marketDown
    val chartMa5 = Color(0xFF8A5A00)
    val chartMa20 = Color(0xFF6E40C9)        // 6.53:1
    val chartCrosshair = Color(0x2618232D)   // 15% 深灰，替代白底白线
    val chartGrid = Color(0xFFE6EDF3)

    // ── 骨架 ──
    val skeleton = Color(0xFFEDF2F6)

    /** 按涨跌取色：null 视为平盘，不使用 Color.Unspecified 兜底。 */
    fun pct(value: Double?): Color = when {
        value == null -> marketFlat
        value > 0 -> marketUp
        value < 0 -> marketDown
        else -> marketFlat
    }
}

/** 4dp 基数的间距刻度。 */
object SkSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 6.dp
    val m = 8.dp
    val l = 12.dp
    val xl = 16.dp
    val xxl = 20.dp
    val xxxl = 24.dp
    val huge = 32.dp

    /** 页面水平内边距（唯一口径，替代 12/16 混用） */
    val pageH = 16.dp

    /** 卡片内边距 */
    val cardPad = 12.dp
}

object SkRadius {
    val card = RoundedCornerShape(18.dp)
    val inner = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(50)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val icon = RoundedCornerShape(10.dp)
}

/**
 * 字号阶梯：最小 12sp（中文正文硬下限），行高 1.5-1.7 倍。
 * 数值型文本统一加 tabular 等宽数字，避免刷新时列宽跳动。
 */
object SkType {
    val displayNumber = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    )
    val headline = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val title = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val bodySm = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val number = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")
}

/** 动效时长与曲线。禁用 >300ms 的入场动画，禁止动画驱动 layout 参数。 */
object SkMotion {
    const val FAST = 120
    const val BASE = 180
    const val SLOW = 240
    const val SHIMMER = 1200
    /** CubicBezier(0.2, 0, 0, 1) 的 Compose 等价表达见 SkEasing.standard */
}

object SkSize {
    val chartSm = 160.dp
    val chartMd = 200.dp
    val chartLg = 240.dp
    /** 触达尺寸下限 */
    val touchMin = 48.dp
    val topBarHeight = 56.dp
}

object SkElevation {
    val card = 1.dp
    val sticky = 2.dp
    val sheet = 8.dp
}
