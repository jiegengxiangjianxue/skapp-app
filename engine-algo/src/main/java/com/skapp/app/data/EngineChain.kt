package com.skapp.app.data

/**
 * 端上引擎链：把「规格投票 → 资金流 → 盘口 → 市况闸门」串成一次分析。
 *
 * ## 口径
 *
 * - 主分来自 [CanonicalEngine]（按 `engine_spec` 的 21 个引擎投票聚合），外部票由
 *   [FlowEngine]（资金流）与 [TapeEngine]（盘口/量价）注入，权重见 [W_CENTRAL] 等。
 * - 缺项的引擎**按剩余权重归一**，不补 0：把「没算出来」当 0 分等于凭空看空。
 *   全缺时返回 null，宁可显示「数据不足」也不产出一张中性票把结论拉平。
 * - 市况闸门 [RegimeGate] 只缩放不减分：多头 1.0、震荡 0.8、空头 0.5。
 *
 * ## 降级必须可见
 *
 * 每个缺席的部件都写进 [ChainResult.degraded]，界面据此解释「为什么只有 3 张外部票」。
 * 静默降级是这个项目最容易犯的错：分数照出，但没人知道它少了什么。
 */
object EngineChain {

    /**
     * 三路合成权重的兜底值：**只用于规格缺 `blend` 段时**。
     *
     * 2026-10-31 改：原来写死 0.88 / 0.07 / 0.05。问题不在数值好不好，而在它不受规格控制，
     * 且与中枢内 flow/tape 票（`weights.flow=0.07 / weights.tape=0.05`）叠加成双计。
     * 现在主路径读 [EngineSpec.blend]，默认中枢独占（1/0/0）；此处只保留缺段提示。
     */
    private val FALLBACK_BLEND = EngineSpec.BlendWeights()

    /**
     * 三层书籍信号（反转/涨停/订单流）在中枢内的权重来源：**只从云端规格取**。
     *
     * 2026-10-30 改：曾经硬编码 0.6。问题不在这个数值好不好，而在它**不受云端控制**：
     * 调权重得发版，回滚也得发版，等于把「书籍票抬多少分」交给了 APK。现在的口径：
     *   - 规格 `weights` 里有 `book` 键 → 用它（云端改权重，端上下次拉 spec 即生效）；
     *   - 没有该键 → **0**，即不计票、也不进权重分母（不是靠 0 权重稀释，见 [externalVotes]）。
     *
     * 注意不能用「把键省掉、让 [CanonicalEngine] 走默认值」的方式实现 0：
     * `CanonicalEngine.score` 对缺失键 fallback 到 **1.0**，省键等于把权重放大到 1.0。
     */
    private fun bookWeightOf(spec: EngineSpec): Double = spec.weights["book"] ?: 0.0

    /** 资金流/盘口低于此分视为「弱」，两者同时弱才置 riskFlag（一票否决）。
     *  规格没有给 flow/tape 单独的弱分界，取 aggregation.signalBearish（看空阈值）作为唯一可用口径。 */
    private const val RISK_WEAK_SCORE = 35.0

    data class Inputs(
        val bars: List<KBar>,
        /** 实时快照；详情页拿不到时为空，资金流会降成「无横截面样本」 */
        val row: LiveMarket.Row? = null,
        /** 已算过的资金流结果（扫描时复用，避免同一只票算两遍） */
        val precomputedFlow: FlowEngine.Result? = null,
        val breadth: Double? = null,
        /**
         * 市况判据用的**指数收盘序列**（升序，≥21 根，见 [RegimeGate]）。
         *
         * 不能拿被评个股自己的 K 线顶替：那样每只票各自判出一个市况，闸门就从
         * 「全市场统一折价」变成「个股趋势的二次惩罚」——趋势已在中枢票里算过一遍。
         * 调用方拉一次指数序列传入即可（`IndexDaily.closes`）；拿不到就传 null，
         * 闸门按 1.0 并记 degraded，不允许猜。
         */
        val regimeCloses: List<Double>? = null,
        /**
         * 板块聚合结果。**当前只透传、不参与链内计算**（板块强度加权要等 stock_sector_map 落地，
         * 见 docs/ISSUES.md）；保留此入口是为了调用方不必改签名。
         */
        val sectors: LocalSectors.Result? = null,
        val turnScale: Double? = null,
        val nowMs: Long = System.currentTimeMillis(),
    )

    data class ChainResult(
        /** 最终分（已过市况闸门） */
        val canonicalScore: Double?,
        /** 规格投票原始结果（含共振/多空票数） */
        val canonical: CanonicalEngine.Result?,
        val flow: FlowEngine.Result?,
        val tape: TapeEngine.Result?,
        val regime: RegimeGate.Result?,
        /** 注入到中枢的外部票 */
        val votes: List<CanonicalEngine.Vote>,
        val specVersion: String?,
        /** 内置规格与代码预期版本不一致 */
        val specDrift: Boolean,
        /** 缺席的部件及原因 */
        val degraded: List<String>,
        /** 一句话风控提示，没有则为 null */
        val riskFlag: String?,
        val status: String,
        /**
         * 三层书籍信号（反转/涨停/订单流）。
         *
         * 放在末尾并给默认值：现有调用点全用位置参数构造，插在中间会静默错位。
         */
        val book: BookSignalEngine.Result? = null,
        /** 权重来源（规格 `weights_source`）：`equal` 表示未校准、按等权跑，界面必须标出来。 */
        val weightsSource: String? = null,
    )

    private val lastRegime = java.util.concurrent.atomic.AtomicReference<Pair<List<Double>, RegimeGate.Result?>>()

    private fun regimeOf(closes: List<Double>?): RegimeGate.Result? {
        if (closes.isNullOrEmpty()) return null
        val cached = lastRegime.get()
        if (cached != null && cached.first === closes) return cached.second
        val result = runCatching { RegimeGate.judge(closes) }.getOrNull()
        lastRegime.set(closes to result)
        return result
    }

    fun analyze(inputs: Inputs, specOverride: EngineSpec? = null): ChainResult {
        val degraded = ArrayList<String>()

        // 调用方已传规格时不再读资源——它已是进程内缓存，但跳过一层调用也省一次分支
        val loaded = if (specOverride == null) {
            runCatching { EngineSpecLoader.loadBundled() }
                .getOrElse {
                    degraded += "规格不可用：${it.message}"
                    return ChainResult(null, null, null, null, null, emptyList(), null, true, degraded, null, "insufficient")
                }
        } else {
            null
        }
        val spec = specOverride ?: loaded!!.spec
        if (loaded != null && loaded.drift) degraded += loaded.reason
        if (spec.weightsSource == "equal") degraded += "权重：规格未带校准权重，按等权跑"
        if (inputs.bars.size < spec.runtime.minBars) {
            degraded += "K 线 ${inputs.bars.size} < ${spec.runtime.minBars}"
            return ChainResult(null, null, null, null, null, emptyList(), spec.specVersion, loaded?.drift ?: false, degraded, null, "insufficient")
        }

        val code = inputs.row?.code ?: ""

        // ── 资金流：优先复用，其次现算；都拿不到就是缺席（不插值） ──
        val flow = inputs.precomputedFlow
            ?: inputs.row?.let { row ->
                runCatching {
                    FlowEngine.compute(
                        row = row,
                        turnScale = inputs.turnScale ?: 1.0,
                        p = flowParams(spec),
                    )
                }.getOrNull()
            }
        if (flow?.score == null) degraded += "资金流：无横截面样本"

        // ── 盘口：只需日 K，拿不到就缺席 ──
        val tape = runCatching {
            TapeEngine.compute(inputs.bars, inputs.row, inputs.nowMs, tapeParams(spec))
        }.getOrNull()
        if (tape?.score == null) degraded += "盘口：${tape?.status ?: "计算失败"}"

        // ── 三层书籍信号（反转 MTR / 涨停结构 / 订单流近似）──
        // 只影响中枢外部票，不进 blend：blend 三路是不同数据源，book 与中枢同源于日 K。
        val book = runCatching { BookSignalEngine.compute(inputs.bars, code) }.getOrNull()
        if (book == null) degraded += "书籍信号：计算失败"
        else if (!book.hasEvidence) degraded += "书籍信号：三层无过检标签"

        // ── 外部票注入后重算中枢 ──
        // 书籍层权重显式写入（含 0.0）：省键会让 CanonicalEngine fallback 到 1.0。
        val bookWeight = bookWeightOf(spec)
        val externals = externalVotes(flow, tape, book, spec)
        val weights = HashMap(spec.weights)
        weights["book"] = bookWeight
        if (book?.hasEvidence == true && bookWeight <= 0.0) {
            degraded += "书籍层：规格将 book 权重设为 0（未校准），已停用不计票"
        }
        val canonical = runCatching {
            CanonicalEngine.score(code, inputs.bars, spec, weights = weights, externalVotes = externals)
        }.getOrNull()
        if (canonical == null) degraded += "中枢：K 线不足或规格引擎全缺"
        canonical?.missing?.takeIf { it.isNotEmpty() }?.let {
            degraded += "权重缺键按等权 1.0：${it.joinToString(",")}（规格未登记即含权 1.0）"
        }

        // ── 市况闸门：只认指数序列，不许用个股 K 线顶替 ──
        // 全市场扫描时同一份序列会被每只票重复判一次（~0.2ms/只），按引用相等复用上一次结果：
        // 调用方传的是同一个 List 实例，引用相等即内容不变，不会误用其他序列。
        val regime = regimeOf(inputs.regimeCloses)
        if (regime == null) {
            degraded += if (inputs.regimeCloses.isNullOrEmpty()) {
                "市况：未提供指数序列，闸门按 1.0（不缩放）"
            } else {
                "市况：指数序列不足，闸门按 1.0（不缩放）"
            }
        }

        val blended = blend(canonical?.score, flow?.score, tape?.score, spec.blend)
        if (spec.blend.central <= 0.0 && spec.blend.flow <= 0.0 && spec.blend.tape <= 0.0) {
            degraded += "规格 blend 权重全为 0，合成层无输入"
        }
        val gated = RegimeGate.applyGate(blended, regime?.gate ?: 1.0)

        val riskFlag = run {
            // 资金与盘口双弱 = 一票否决（消费方 LocalScreener.Params.vetoByRiskFlag 的语义前提）：
            // riskFlag 非 null 就会被剔出候选池，所以大盘强弱一律走 regime 字段，不塞进这里。
            val f = flow?.score
            val t = tape?.score
            if (f != null && t != null && f < RISK_WEAK_SCORE && t < RISK_WEAK_SCORE) {
                "资金与盘口双弱（资金流 ${fmt1(f)} / 盘口 ${fmt1(t)}）"
            } else null
        }

        val status = when {
            gated == null -> "insufficient"
            degraded.isEmpty() -> "ok"
            else -> "partial"
        }
        return ChainResult(
            canonicalScore = gated,
            canonical = canonical,
            flow = flow,
            tape = tape,
            regime = regime,
            votes = externals,
            specVersion = spec.specVersion,
            specDrift = loaded?.drift ?: false,
            degraded = degraded,
            riskFlag = riskFlag,
            status = status,
            book = book,
            weightsSource = spec.weightsSource,
        )
    }

    /**
     * 三路合成。缺项按剩余权重重归一，全缺为 null。
     *
     * 不补 0 是硬要求：0 分在这套口径里是「极弱」，而缺席只是「没算」。
     * 两者混同会让一只数据不全的票看起来像看空标的。
     */
    fun blend(
        central: Double?,
        flow: Double?,
        tape: Double?,
        w: EngineSpec.BlendWeights = FALLBACK_BLEND,
    ): Double? {
        var sum = 0.0
        var weight = 0.0
        if (central != null && w.central > 0.0) {
            sum += w.central * central
            weight += w.central
        }
        if (flow != null && w.flow > 0.0) {
            sum += w.flow * flow
            weight += w.flow
        }
        if (tape != null && w.tape > 0.0) {
            sum += w.tape * tape
            weight += w.tape
        }
        if (weight <= 0.0) return null
        return sum / weight
    }

    /** 从规格读资金流参数；规格没写的回落默认值。 */
    fun flowParams(spec: EngineSpec): FlowEngine.Params {
        val d = FlowEngine.Params()
        return d.copy(
            pctSd = spec.param("flow", "pct_sd", d.pctSd),
            minPeerN = spec.intParam("flow", "min_peer_n", d.minPeerN),
            bullThreshold = spec.aggregation.signalBullish,
            bearThreshold = spec.aggregation.signalBearish,
        )
    }

    /** 从规格读盘口参数；规格没写的回落默认值。 */
    fun tapeParams(spec: EngineSpec): TapeEngine.Params {
        val d = TapeEngine.Params()
        return d.copy(
            bullThreshold = spec.aggregation.signalBullish,
            bearThreshold = spec.aggregation.signalBearish,
        )
    }

    /**
     * 把资金流/盘口转成中枢可消费的外部票。
     *
     * 缺分（null）一律 drop：产出一张中性票会把中枢共振拉平，等于用「不知道」稀释已知结论。
     */
    fun externalVotes(
        flow: FlowEngine.Result?,
        tape: TapeEngine.Result?,
        book: BookSignalEngine.Result?,
        spec: EngineSpec,
    ): List<CanonicalEngine.Vote> {
        val out = ArrayList<CanonicalEngine.Vote>(3)
        val agg = spec.aggregation

        fun voteOf(indicator: String, score: Double?, why: String): CanonicalEngine.Vote? {
            if (score == null) return null
            val strength = ((score - agg.scoreCenter) / agg.scoreScale).coerceIn(-1.0, 1.0)
            val vote = when {
                score >= agg.signalBullish -> 1
                score <= agg.signalBearish -> -1
                else -> 0
            }
            return CanonicalEngine.Vote(indicator, vote, kotlin.math.abs(strength), why)
        }

        if (spec.deviceCanVote("flow")) {
            voteOf("flow", flow?.score, "资金流 ${"%.1f".format(flow?.score ?: 0.0)}（${flow?.signal ?: "?"}）")?.let { out += it }
        }
        if (spec.deviceCanVote("tape")) {
            voteOf("tape", tape?.score, "盘口 ${"%.1f".format(tape?.score ?: 0.0)}（${tape?.signal ?: "?"}）")?.let { out += it }
        }

        // 书籍信号：只投 +1 或不投，从不投 -1 也不投 0。
        // -1 不可投：三层判据（折底反转/涨停动能/卖压反转）实测无一条显著为负，
        //   拿「没证据」当「看空」会让每只票都被压分。
        // 0 不可投：0 票会进 den 权重却不贡献分子，等于稀释其它票（与 missing_vote_policy=drop 同理）。
        // 准入：`device_engines` 里没有 "book"（也没有 flow/tape），照抄 deviceCanVote 会成死代码
        //   （见 docs/ISSUES.md 的「externalVotes 恒空」条目）。准入口径改为「规格登记了权重」：
        //   `weights.book` 存在且 > 0 才计票，缺键即停用 —— 权重可云端调、也可云端一行关掉。
        val bScore = book?.score
        val bWeight = bookWeightOf(spec)
        if (bWeight > 0.0 && bScore != null && bScore >= agg.signalBullish) {
            val strength = ((bScore - agg.scoreCenter) / agg.scoreScale).coerceIn(0.0, 1.0)
            out += CanonicalEngine.Vote("book", 1, strength, "三层信号 ${"%.1f".format(bScore)}")
        }

        return out
    }

    // ── 自检 ────────────────────────────────────────────────

    data class SelfCheck(
        val checks: Int,
        val ok: Boolean,
        val violations: List<String>,
    )

    /**
     * 链路不变量自检。
     *
     * 这些断言守的都是「看着能跑、实际会骗人」的坑：缺项被当成 0、闸门方向反了、
     * 缺分引擎照样产票、外部票强度没上限。每条都能在 CI 里跑，不依赖网络与真实行情。
     */
    fun selfCheck(): SelfCheck {
        val bad = ArrayList<String>()
        var n = 0

        fun expect(name: String, cond: Boolean, detail: String) {
            n++
            if (!cond) bad += "$name：$detail"
        }

        expect("全缺为 null", blend(null, null, null) == null, "blend 全缺应返回 null")
        expect("单路取原值", blend(60.0, null, null)?.let { kotlin.math.abs(it - 60.0) < 1e-9 } == true, "单路应等于原值")
        val w3 = EngineSpec.BlendWeights(central = 0.88, flow = 0.07, tape = 0.05)
        expect(
            "三路加权",
            blend(60.0, 40.0, 40.0, w3)?.let { kotlin.math.abs(it - 57.6) < 1e-9 } == true,
            "0.88*60+0.07*40+0.05*40 应为 57.6",
        )
        expect(
            "缺项归一",
            blend(60.0, 40.0, null, w3)?.let { kotlin.math.abs(it - (0.88 * 60.0 + 0.07 * 40.0) / 0.95) < 1e-9 } == true,
            "缺一项应按剩余权重归一",
        )
        expect(
            "全零权重为 null",
            blend(60.0, 40.0, 40.0, EngineSpec.BlendWeights(0.0, 0.0, 0.0)) == null,
            "规格把三路权重全置 0 时必须报缺，不得用 0 除",
        )

        val spec = EngineSpecLoader.loadBundled().spec
        expect("缺分不产票", externalVotes(null, null, null, spec).isEmpty(), "缺分必须 drop")

        // 书籍权重：只认规格。缺键必须停用 —— 曾经硬编码 0.6，等于这个权重不受云端控制。
        val strongBook = BookSignalEngine.Result(
            score = 80.0, expected5d = 1.8, tags = listOf("limit:first_board"),
            layers = emptyMap(), basis = null, basisT = null, basisN = null,
            degraded = emptyList(), reason = "test",
        )
        expect(
            "书籍票不产 0 也不产 -1",
            externalVotes(null, null, strongBook, spec.copy(weights = spec.weights + ("book" to 0.6)))
                .all { it.vote == 1 },
            "书籍票只能是 +1",
        )
        expect(
            "书籍弱分不产票",
            externalVotes(null, null, strongBook.copy(score = 50.0), spec.copy(weights = spec.weights + ("book" to 0.6))).isEmpty(),
            "低于看多阈值不得产票（0 票会稀释其它票）",
        )
        expect(
            "书籍权重缺键即停用",
            externalVotes(null, null, strongBook, spec.copy(weights = spec.weights - "book")).isEmpty(),
            "规格未登记 book 权重时不得计票（硬编码 0.6 已移除）",
        )
        expect(
            "书籍权重登记即生效",
            externalVotes(null, null, strongBook, spec.copy(weights = spec.weights + ("book" to 0.6)))
                .singleOrNull()?.vote == 1,
            "规格登记 book 权重后应产 +1 票",
        )

        val agg = spec.aggregation
        expect("闸门单调", RegimeGate.applyGate(80.0, 0.5)?.let { it <= 80.0 } == true, "空头闸门不得放大分数")
        expect(
            "闸门不越界",
            RegimeGate.applyGate(80.0, 0.5)?.let { it >= 0.0 && it <= 100.0 } == true,
            "过闸后必须仍在 0~100",
        )
        expect(
            "盘口分钟口径",
            TapeEngine.minuteOfDay(0L) in 0..1439,
            "nowMs 换算必须落在当日分钟区间",
        )
        expect(
            "市况只认指数序列",
            run {
                val bars = List(40) { i ->
                    KBar(
                        date = "20260101",
                        open = 10.0 + i * 0.1,
                        close = 10.0 + i * 0.1,
                        high = 10.0 + i * 0.1,
                        low = 10.0 + i * 0.1,
                        vol = 1000.0,
                    )
                }
                val r = analyze(Inputs(bars = bars), spec)
                r.degraded.any { it.startsWith("市况：") } && r.regime == null
            },
            "未传指数序列时必须记 degraded 且 regime 为 null，不得拿个股 K 线充数",
        )
        expect(
            "中枢阈值为正",
            agg.signalBullish > agg.scoreCenter && agg.signalBearish < agg.scoreCenter,
            "看多阈值必须高于中位、看空阈值必须低于中位",
        )
        expect(
            "双弱阈值低于中位",
            RISK_WEAK_SCORE < agg.scoreCenter,
            "riskFlag 是一票否决，阈值高于中位会把普通票全部剔出候选池",
        )

        return SelfCheck(checks = n, ok = bad.isEmpty(), violations = bad)
    }

    private fun fmt1(v: Double): String = String.format("%.1f", v)
}
