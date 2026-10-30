package com.skapp.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skapp.app.ui.theme.SkColors
import com.skapp.app.ui.theme.SkRadius
import com.skapp.app.ui.theme.SkSpace
import com.skapp.app.ui.theme.SkType
import java.util.Locale

/**
 * 口径条 —— 把「这个分数是什么口径」直接摆在分数旁边。
 *
 * 端上分数（`LocalEngine.centralScore`）与云端终审 S 不是一把尺：
 * 前者是 12 引擎均值、天然聚在 50，后者是 `100 × gate × Σv·D`。同屏并列两个来源的分数
 * 而不说明口径，用户没法判断 62 分是「今天最强」还是「矮子里的高个」。
 * 这条只做三件事：环境闸门、横截面分位、口径出处。
 *
 * 渲染约束（改这个文件时别破坏）：
 *  - 入参全是值类型且标 [Immutable]：父级重组时只有字段真变了才重绘；
 *  - 文案在 [remember] 里拼一次：`String.format` 每帧跑一遍是这类小条最常见的浪费；
 *  - 不接收 lambda：避免父级传匿名函数导致每次都是新实例、跳过失效。
 */
@Immutable
data class ParityView(
    /** 环境档：多头 / 震荡 / 空头；null = 指数序列不可得。 */
    val regimeLabel: String? = null,
    /** 云端 gate 系数：1.0 / 0.8 / 0.5。 */
    val gate: Double? = null,
    /** 折价后的分数（`50 + (score-50) × gate`）。 */
    val gatedScore: Double? = null,
    /** 横截面分位 0..1。 */
    val percentile: Double? = null,
    /** 7 档标签：极弱…极强。 */
    val tier: String? = null,
    /** 口径出处，一句话。 */
    val basis: String = "",
) {
    val hasAny: Boolean get() = regimeLabel != null || percentile != null || basis.isNotBlank()
}

/**
 * 从推股产物 `metadata` 直接构造口径条（字段由 [LocalPicks.build] 写入）。
 *
 * UI 只认 metadata 的键，不 import 引擎类：模块依赖方向是 ui → engine，反着引会把
 * 引擎的编译单元拖进界面层，也会让「口径」变成界面自己算的东西。
 */
fun parityViewOfArtifact(metadata: Map<String, Any?>): ParityView {
    val gate = (metadata["gate"] as? Number)?.toDouble()
    val gateLabel = metadata["gateLabel"] as? String
    val coldStart = metadata["coldStart"] as? Boolean
    val walk = metadata["walkforward"] as? Map<*, *>
    val verdict = walk?.get("verdict")?.toString()?.takeIf { it.isNotBlank() }
    val unmapped = (metadata["weightUnmapped"] as? List<*>)?.size ?: 0
    val regime = gateLabel
        ?.substringBefore(" gate")
        ?.takeIf { it.isNotBlank() && it != "gate 1.0（无指数数据，不折价）" }
    val basis = buildString {
        append(if (coldStart == true) "权重 cold_start" else "权重 云端标定")
        if (unmapped > 0) append(" · $unmapped 项无端上对应")
        if (verdict != null) append(" · 回测 $verdict")
    }
    return ParityView(
        regimeLabel = regime,
        gate = gate,
        gatedScore = (metadata["gated"] as? Number)?.toDouble(),
        percentile = null,
        tier = null,
        basis = basis,
    )
}

@Composable
fun ParityStrip(view: ParityView, modifier: Modifier = Modifier) {
    if (!view.hasAny) return
    val regimeText = remember(view.regimeLabel, view.gate) {
        val label = view.regimeLabel ?: return@remember null
        val gate = view.gate?.let { String.format(Locale.US, " gate %.1f", it) } ?: ""
        "$label$gate"
    }
    val pctText = remember(view.percentile, view.tier) {
        val p = view.percentile ?: return@remember view.tier
        String.format(Locale.US, "分位 %.0f%%", p * 100)
    }
    val gatedText = remember(view.gatedScore) {
        view.gatedScore?.let { String.format(Locale.US, "折价后 %.1f", it) }
    }

    Row(
        modifier = modifier.padding(vertical = SkSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(SkSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        regimeText?.let { Token(it, SkColors.surface3, SkColors.textSecondary) }
        pctText?.let { Token(it, SkColors.surface3, SkColors.textSecondary) }
        gatedText?.let { Token(it, SkColors.surface1, SkColors.primary) }
        if (view.basis.isNotBlank()) {
            Text(
                view.basis,
                style = SkType.caption,
                color = SkColors.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/** 单个方形 token：不用 SkChip 是因为它带 onClick（本处不可点，给可点语义会误导）。 */
@Composable
private fun Token(text: String, background: Color, content: Color) {
    Box(
        Modifier
            .background(background, SkRadius.chip)
            .padding(horizontal = SkSpace.s, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = SkType.label, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}
