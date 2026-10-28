package com.skapp.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首屏三张卡的**本地产出**：推股（today_picks）、市场快照（market）、决策（decision）。
 *
 * ## 背景
 *
 * 这三个模型原来是云端 CI 的产物，`load()` 一进来就 `SkappApi.fetchTodayPicks/fetchMarket/fetchDecision`
 * 各拉一份。盘中打开看到的往往是上一轮收盘的结论，甚至隔天。这里改成用端上已有的东西拼：
 *
 * ```
 * LocalPicks 产物（本地深算推股）        → TodayPicks
 * LivePulse（全市场实时盘口算的相位）    → MarketSnapshot
 * LivePulse + LocalScreener 候选          → Decision（含仓位建议/卡片/回避）
 * ```
 *
 * 输出的仍然是 core 里那几个模型（界面零改动），但字段里 `sources` / `engine` 会标 `local`，
 * 让界面能如实说明「这是本机实时算的」。
 */
object LocalSnapshot {

    /** 本地推股产物 → TodayPicks。没有产物时返回空结构并说明原因，不拿云端旧推荐顶替。 */
    fun todayPicks(
        artifact: StructuredPickArtifact?,
        fallbackNote: String = "本地深算尚未完成",
    ): TodayPicks {
        val ts = CstTime.iso()
        if (artifact == null) {
            return TodayPicks(
                ts = ts,
                date = null,
                session = null,
                signals = emptyList(),
                values = emptyList(),
                limitUp = emptyList(),
                sources = mapOf("source" to "local", "status" to "pending", "note" to fallbackNote),
            )
        }
        val observed = (artifact.metadata["observed"] as? List<*>).orEmpty()
            .mapNotNull { row ->
                val m = row as? Map<*, *> ?: return@mapNotNull null
                val code = m["code"]?.toString() ?: return@mapNotNull null
                PickSignal(
                    code = code,
                    name = m["name"]?.toString(),
                    score = (m["score"] as? Number)?.toDouble(),
                    // 快照新鲜度透传：这里硬编码 live 会把陈旧行情标成实时（LiveMarket 拉不到时回退旧缓存）
                    snapshotStatus = m["snapshotStatus"]?.toString() ?: "live",
                    engine = artifact.engine ?: "中枢引擎",
                    calculationTime = artifact.calculationTime,
                    reason = if (artifact.status == "defensive") {
                        "弱市无达标标的：仅作观察，建议轻仓或空仓"
                    } else {
                        "未达达标线（评分 < ${LocalPicks.MIN_SCORE.toInt()}）"
                    },
                    why = listOf("仅作观察，不构成推荐"),
                )
            }
        return TodayPicks(
            ts = ts,
            date = artifact.date,
            session = artifact.session,
            signals = artifact.picks,
            values = observed,
            limitUp = emptyList(),
            sources = mapOf(
                "source" to "local",
                "status" to artifact.status,
                "reason" to artifact.reason,
                "generatedAt" to artifact.generatedAt,
                "metadata" to artifact.metadata,
                // defensive 与 insufficient 必须分开说：前者是「算完了，今天不该追」，
                // 后者是「还算不出来」。界面把两者混成一类就会报假状态。
                "note" to when (artifact.status) {
                    "defensive" -> "本地深算已完成：无达标标的，下列为观察名单，建议轻仓或空仓"
                    "insufficient" -> "本地深算未得到可用结果：${artifact.reason}"
                    else -> "本地深算达标 ${artifact.picks.size} 只"
                },
            ),
        )
    }

    /** 实时脉搏 → 市场快照（涨跌家数/涨跌停/成交额/资金），并如实标出不可得项。 */
    fun market(pulse: LivePulse?, hotNews: List<Map<String, Any?>> = emptyList()): MarketSnapshot {
        val ts = CstTime.iso()
        if (pulse == null || pulse.total == 0) {
            return MarketSnapshot(
                ts = ts,
                market = mapOf(
                    "source" to "local",
                    "status" to "unavailable",
                    "note" to "全市场实时行情不可达，未使用云端旧快照",
                ),
            )
        }
        return MarketSnapshot(
            ts = ts,
            market = mapOf(
                "source" to "local",
                "total" to pulse.total,
                "gainers" to pulse.up,
                "losers" to pulse.down,
                "flat" to pulse.flat,
                "limitUp" to pulse.limitUp,
                "limitDown" to pulse.limitDown,
                "medianPct" to pulse.medianPct,
                "breadth" to pulse.breadth,
                "amount" to pulse.totalAmount,
                "mainNet" to pulse.mainNetTotal,
            ),
            phase = mapOf(
                "label" to pulse.label,
                "score" to pulse.phaseScore,
                "source" to "local",
            ),
            hotNews = hotNews,
        )
    }

    /**
     * 仓位建议的统一入口。
     *
     * 决策页只有脉搏（宽度/相位/涨跌停），拿不到连板高度与晋级率，所以趋势项取中性；
     * 引擎池那边有情绪序列，会走 [PositionCap] 的完整三因子。**同一天两处上限可能差一档**，
     * 差别的原因是「这里缺情绪序列」，理由里会写明，不假装两边一致。
     */
    fun positionAdviceOf(pulse: LivePulse): Map<String, Any?> {
        val cap = PositionCap.compute(
            breadth = pulse.breadth,
            phaseScore = pulse.phaseScore,
            limitUp = pulse.limitUp,
            limitDown = pulse.limitDown,
        )
        return mapOf(
            "source" to "local",
            "level" to cap.cap,
            "cap" to cap.cap,
            "label" to cap.label,
            "reason" to cap.reasons.joinToString("；"),
            "gates" to cap.gates,
            "note" to "决策页缺连板高度/晋级率，趋势项取中性；引擎池页为完整三因子",
        )
    }

    /** 实时脉搏 + 本地候选 → 决策卡（市场状态、仓位建议、可看标的、回避名单）。 */
    fun decision(
        pulse: LivePulse?,
        screened: List<LocalScreener.Screened>,
        ts: Long = System.currentTimeMillis(),
    ): Decision {
        val tsText = CstTime.iso(ts)
        if (pulse == null || pulse.total == 0) {
            return Decision(
                ts = tsText,
                engine = "local-live",
                marketRegime = null,
                regimeDetail = "全市场实时行情不可达，未生成决策（不使用云端旧结论）",
                sources = mapOf("source" to "local", "status" to "unavailable"),
            )
        }

        val scored = screened.filter { (it.centralScore ?: 0.0) > 0.0 }
            .sortedByDescending { it.centralScore ?: 0.0 }

        val cards = scored.take(10).map { s ->
            val eng = s.engine
            DecisionCard(
                code = s.code,
                name = s.name,
                price = s.price,
                pctChg = s.pctChg,
                composite = s.centralScore,
                reasons = buildList {
                    eng?.let {
                        add("12 引擎：看多 ${it.bullCount} / 看空 ${it.bearCount}")
                        it.resonanceRatio?.let { r -> add("共振 ${"%.0f".format(r * 100)}%") }
                    }
                    s.volRatio?.let { add("量比 ${"%.2f".format(it)}") }
                    s.pctChg?.let { add("涨幅 ${"%+.2f%%".format(it)}") }
                },
                action = when {
                    s.buyable != true -> "avoid"
                    (s.centralScore ?: 0.0) >= 65 -> "buy"
                    (s.centralScore ?: 0.0) >= LocalPicks.MIN_SCORE -> "watch"
                    else -> "hold"
                },
                confidence = (s.resonanceRatio ?: 0.0).coerceIn(0.0, 1.0),
                dims = eng?.engines.orEmpty().mapNotNull { e -> e.score?.let { e.engine to it } }.toMap(),
                why = mapOf(
                    "source" to "local",
                    "centralScore" to (s.centralScore ?: 0.0),
                    "bull" to (eng?.bullCount ?: 0),
                    "bear" to (eng?.bearCount ?: 0),
                ),
            )
        }

        return Decision(
            ts = tsText,
            engine = "local-live",
            marketRegime = when (pulse.label) {
                "强势", "偏强" -> "offense"
                "偏弱", "弱势" -> "defense"
                else -> "neutral"
            },
            regimeDetail = "本地实时相位 ${pulse.label}（${"%.0f".format(pulse.phaseScore)}）：" +
                "${pulse.up} 涨 / ${pulse.down} 跌，涨停 ${pulse.limitUp} / 跌停 ${pulse.limitDown}，" +
                "中位涨幅 ${pulse.medianPct?.let { "%.2f%%".format(it) } ?: "不可得"}",
            positionAdvice = positionAdviceOf(pulse),
            cards = cards,
            avoid = screened.filter { it.buyable != true }.map { it.code }.distinct().take(10),
            sources = mapOf(
                "source" to "local",
                "phaseScore" to pulse.phaseScore,
                "analyzed" to screened.size,
            ),
        )
    }
}
