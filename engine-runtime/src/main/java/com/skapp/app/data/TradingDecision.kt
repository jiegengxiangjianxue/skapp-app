package com.skapp.app.data

/** Pure decision projection. It never places orders or turns a score into a probability. */
object TradingDecision {
    enum class Action(val label: String) {
        BUY("条件买入"), DO_NOT_BUY("不新增仓位"), WAIT("等待核验"),
        EXIT("条件清仓"), TRIM("条件减仓"), HOLD("条件持有"),
    }

    data class Position(val quantity: Int, val sellableQuantity: Int?, val cost: Double?)

    data class Result(
        val withoutPosition: Action,
        val withPosition: Action,
        val reasons: List<String>,
        val missing: List<String>,
        val conditions: List<String>,
        val entryLow: Double?,
        val entryHigh: Double?,
        val stop: Double?,
        val target: Double?,
        val validUntilMs: Long?,
        val sellQuantity: Int?,
        val quoteSource: String?,
        val quoteAtMs: Long?,
        val evidenceSource: String?,
    ) {
        fun summary(): String = buildString {
            append("无仓：${withoutPosition.label}；有仓：${withPosition.label}（条件建议，不执行交易）")
            append("；理由：${reasons.joinToString("；")}")
            if (missing.isNotEmpty()) append("；缺项：${missing.joinToString("、")}")
            append("；入场区 ${entryLow ?: "暂无"}～${entryHigh ?: "暂无"}；止损 ${stop ?: "暂无"}；目标/压力 ${target ?: "暂无"}")
            append("；有效至 ${validUntilMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "未就绪"}")
            append("；行情源 ${quoteSource ?: "未知"} · 源时点 ${quoteAtMs?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "未知"}")
            append("；财务/复权来源 ${evidenceSource ?: "未知"}")
            append("；${conditions.joinToString("；")}")
        }
    }

    fun evidenceOf(input: StockAnalysisInputs, nowMs: Long): LocalScreener.DataEvidence =
        LocalScreener.DataEvidence(input.eps, input.dps, input.reportPeriod, input.adjustmentEvidence,
            input.source, input.snapshotAtMs, input.expiresAtMs, input.code, input.barsFingerprint, input.gaps(nowMs))

    fun evaluate(
        chain: EngineChain.ChainResult?, code: String, price: Double?, pctChg: Double?,
        quoteAtMs: Long?, quoteSource: String?, bars: List<KBar>, evidence: LocalScreener.DataEvidence?,
        levels: KeyLevels.Levels?, exit: ExitPlan.Result?, nowMs: Long,
        position: Position? = null,
    ): Result {
        val missing = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val quoteValid = price?.let { it.isFinite() && it > 0 } == true &&
            quoteAtMs != null && nowMs - quoteAtMs in 0..300_000L && !quoteSource.isNullOrBlank()
        if (price?.let { it.isFinite() && it > 0 } != true) missing += "有效价格"
        if (quoteAtMs == null) missing += "行情源时间"
        else if (nowMs - quoteAtMs !in 0..300_000L) missing += "行情过期或未来时间"
        if (quoteSource.isNullOrBlank()) missing += "行情来源"
        if (pctChg?.isFinite() != true) missing += "实时涨跌幅"
        if (evidence?.eps?.isFinite() != true) missing += "EPS"
        if (evidence?.dps?.isFinite() != true) missing += "DPS"
        if (evidence?.reportPeriod.isNullOrBlank()) missing += "财报报告期"
        if (evidence?.adjustmentEvidence?.startsWith("cnfin:hfq_factor:") != true) missing += "cnfin同源复权证据"
        if (evidence?.source.isNullOrBlank()) missing += "财务来源"
        if (evidence?.snapshotAtMs == null || evidence.snapshotAtMs !in 1..nowMs) missing += "财务源时间"
        if (evidence?.expiresAtMs == null || evidence.expiresAtMs < nowMs) missing += "财务有效期"
        val evidenceValid = evidence?.validFor(code, bars, nowMs) == true && evidence.missing.isEmpty() && bars.isNotEmpty()
        if (!evidenceValid) missing += "财务/复权与当前股票K线绑定核验"
        missing += evidence?.missing.orEmpty()
        val canonical = chain?.canonical
        val flow = chain?.flow
        val chainComplete = chain != null && chain.status == "ok" && !chain.specDrift &&
            chain.degraded.isEmpty() && chain.canonicalScore?.isFinite() == true &&
            canonical != null && canonical.code == code && canonical.missing.isEmpty() &&
            flow != null && flow.code == code && flow.score?.isFinite() == true &&
            chain.tape?.score?.isFinite() == true && chain.regime != null && !chain.specVersion.isNullOrBlank()
        if (!chainComplete) missing += "完整多输入规格链路"
        chain?.degraded?.let { missing += it }
        chain?.riskFlag?.let { reasons += "链路风控：$it" }
        fun positive(value: Double?): Double? = value?.takeIf { it.isFinite() && it > 0 }
        // Levels are only tradable when bound to verified bars and a current quote.
        val trustedLevels = quoteValid && evidenceValid
        val low = if (trustedLevels) positive(levels?.support) else null
        val high = if (trustedLevels) positive(levels?.pullback)?.takeIf { low != null && it >= low } else null
        val stop = if (trustedLevels) positive(exit?.stopLine) else null
        val target = if (trustedLevels) positive(levels?.pressure)?.takeIf { high != null && it > high } else null
        val geometryValid = low != null && high != null && stop != null && stop < low && target != null
        if (!geometryValid) missing += "有效支撑/回调/止损/压力价位"
        val liveStopConflict = quoteValid && evidenceValid && stop != null && price != null && price < stop &&
            exit?.action != ExitPlan.ACT_CLEAR
        if (liveStopConflict) reasons += "实时价已跌破已核止损线，止损触发待重算/核可卖数量；旧退出结果不能继续持有，不代表已下单"
        val exitTriggered = exit?.action in setOf(ExitPlan.ACT_CLEAR, ExitPlan.ACT_TRIM)
        val signal = chain?.canonical?.signal == "bullish"
        val mainboard = Code.isMainBoard(code)
        val limitOk = pctChg?.let { it.isFinite() && it < MarketLimits.LIMIT_UP_PCT } == true
        if (!mainboard) reasons += "非主板，不买入"
        if (!limitOk) reasons += "涨停附近或涨跌幅未知，不追买"
        if (!signal) reasons += "现有规格策略未触发bullish信号"
        if (exitTriggered) reasons += exit?.reasons.orEmpty().ifEmpty { listOf("退出纪律已触发") }
        val inEntry = geometryValid && price != null && price >= low!! && price <= high!!
        if (geometryValid && !inEntry) reasons += "现价不在真实支撑—回调入场区，等待条件"
        val canBuy = quoteValid && evidenceValid && chainComplete && chain?.riskFlag == null &&
            mainboard && limitOk && signal && !exitTriggered && !liveStopConflict && geometryValid && inEntry
        val flat = when {
            !quoteValid -> Action.WAIT
            canBuy -> Action.BUY
            else -> Action.DO_NOT_BUY
        }
        if (canBuy) reasons += "既有规格bullish信号触发，财务/复权与链路过门，现价在真实价位区间"
        if (missing.isNotEmpty()) reasons += "证据不足，禁止新增仓位"
        val held = when {
            !quoteValid || !evidenceValid || liveStopConflict -> Action.WAIT
            exit?.action == ExitPlan.ACT_CLEAR -> Action.EXIT
            exit?.action == ExitPlan.ACT_TRIM -> Action.TRIM
            exit?.action == ExitPlan.ACT_HOLD && chainComplete -> Action.HOLD
            else -> Action.WAIT
        }
        val conditions = buildList {
            add("策略周期未指定；20日退出观察窗不等于持有期，5分钟仅报价有效期")
            add("T+1：只处理已结算可卖数量；当日新买不可卖；未确认持仓不执行")
            if (position?.cost?.let { it.isFinite() && it > 0 } != true) add("持仓成本未知，不计算盈亏或收益率")
            if (position?.sellableQuantity == null) add("可卖数量未知，清仓/减仓仅为条件建议")
            else if (position.sellableQuantity == 0) add("当前可卖为0，等待T+1解锁")
            add("行情超5分钟、证据到期、信号反转或退出条件触发即失效；重新核验")
        }
        val validUntil = if (quoteValid && evidenceValid) minOf(quoteAtMs!! + 300_000L, evidence!!.expiresAtMs!!) else null
        val sellQuantity = if (position != null && position.quantity >= 0 && position.sellableQuantity != null &&
            position.sellableQuantity in 0..position.quantity && held in setOf(Action.EXIT, Action.TRIM)) {
            if (held == Action.EXIT || position.sellableQuantity == 0) position.sellableQuantity else null
        } else null
        return Result(flat, held, reasons.distinct(), missing.distinct(), conditions,
            low, high, stop, target, validUntil, sellQuantity, quoteSource, quoteAtMs, evidence?.source)
    }
}
