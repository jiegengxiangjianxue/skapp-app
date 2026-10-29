package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 板块页 + 资金流/盘口的渲染契约：拿 `testdata/parity/golden_v2.json` 里**已有的**输入向量
 * 喂端上 [FlowEngine] / [TapeEngine] / [RotationEngine]，断言界面会显示的那几个字段可渲染。
 *
 * ## 与其它两个测试的分工
 *
 * - `EngineV2ParityTest`：逐字段与云端参考实现比对**数值**。数值对上不代表界面能显示。
 * - `UiRenderContractTest`：决策页（仓位上限/风险预算/E/波动率闸门）的渲染契约。
 * - 本测试：板块页与资金流/盘口这一层的渲染契约——档位/状态取值、缺数据语义、格式化文案。
 *
 * ## 为什么断言「档位」而不只是「有个数」
 *
 * `EnginesScreen` 把 `flow.tier` / `tape.status` **原样拼进文案**给用户看，
 * `RotationEngine.labelOf` 把轮动分翻成「强/弱/中/无数据」。这几处一旦出现集合外的值，
 * 用户看到的就是一个看不懂的英文 token；而分数字段缺失时若被 0 或 50 顶上，
 * 界面会把「不可得」显示成「中性」——这是口径事故，不是显示瑕疵。
 *
 * ## 覆盖的输入档次（全部来自黄金向量，不自己造数）
 *
 * | 引擎 | 强 | 弱 | 缺数据 |
 * |---|---|---|---|
 * | flow | `flow.small` 600001 bullish | 600004 bearish | 600002 停牌 / 600003 除权异常 |
 * | flow 档位 | `flow.tiers.cloud` / `local` / `local_abs` 三档逐条比对云端参考实现 | | |
 * | tape | `tape_normal` / `tape_one_word` / `tape_auction` | `tape_bearish` | `tape_pre_open` / `tape_illiquid` |
 * | rotation | `rotation_attack` | `rotation_defense` / `rotation_panic` | `rotation_too_few`（判 insufficient） |
 * | rotation 覆盖不足 | — | — | `rotation_low_coverage` 只降级成 unknown/degraded（`status=degraded`），**不计入** `missing`：覆盖率不足是「结论不可信」，不是「没有数据」 |
 *
 * 缺哪一档就用 `fail` 报出来而不是静默跳过：夹具被改窄之后，测试必须红，不能悄悄少测一档。
 *
 * ## 不覆盖什么
 *
 * 布局、颜色、截断、字号——这些只有真机能看出来，别把这条当渲染验收。
 */
class EngineSurfaceRenderContractTest {

    /** 界面直接显示的档位/状态词：多一个未知值界面就会漏出一个用户看不懂的 token。 */
    private val flowTiers = setOf("cloud", "local_main", "local", "local_abs", "none")
    private val tapeStatuses = setOf("ok", "pre_open", "illiquid", "insufficient")
    private val signals = setOf("bullish", "bearish", "neutral", "insufficient")
    private val rotationStates = setOf("attack", "defense", "balanced", "unknown")
    private val rotationStatuses = setOf("ok", "degraded", "insufficient")
    private val sectorLabels = setOf("强", "弱", "中", "无数据")

    private companion object {
        /** 榜单长度：界面按 topN 排版。硬编码在测试里，引擎默认值漂移时立刻红。 */
        const val TOP_N = 3

        /** 强/弱标签阈值，与 `RotationEngine` 的口径契约同值：改一处必须同时改两处。 */
        const val LABEL_BULL = 0.35
        const val LABEL_BEAR = -0.35
    }

    // ── 夹具 ───────────────────────────────────────────────────────
    private fun golden(): JSONObject {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "parity/golden_v2.json")
        if (!f.isFile) fail("缺少 v2 黄金向量：${f.absolutePath}（先跑 scripts/engines_v2.py --golden）")
        return JSONObject(f.readText())
    }

    private fun rowOf(o: JSONObject): LiveMarket.Row = LiveMarket.Row(
        code = o.getString("code"),
        name = o.optString("name").takeIf { it.isNotEmpty() },
        price = o.optDoubleOrNull("price"),
        pctChg = o.optDoubleOrNull("pctChg"),
        volumeLots = o.optDoubleOrNull("volumeLots"),
        amount = o.optDoubleOrNull("amount"),
        amplitude = o.optDoubleOrNull("amplitude"),
        turnoverRate = o.optDoubleOrNull("turnoverRate"),
        pe = null,
        volRatio = o.optDoubleOrNull("volRatio"),
        mv = null,
        mainNet = o.optDoubleOrNull("mainNet"),
        ts = 0L,
        high = o.optDoubleOrNull("high"),
        low = o.optDoubleOrNull("low"),
        open = o.optDoubleOrNull("open"),
        prevClose = o.optDoubleOrNull("prevClose"),
    )

    private fun barsOf(arr: JSONArray): List<KBar> = (0 until arr.length()).map { i ->
        val b = arr.getJSONObject(i)
        KBar(
            date = b.optString("date", "2026-01-01"),
            open = b.getDouble("open"),
            close = b.getDouble("close"),
            high = b.getDouble("high"),
            low = b.getDouble("low"),
            vol = b.optDouble("vol", 0.0),
        )
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun doubleMap(o: JSONObject?): Map<String, Double> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { k -> o.getDouble(k) }
    }

    private fun cloudOf(o: JSONObject?): Map<String, FlowEngine.CloudFlow> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associate { k ->
            val v = o.getJSONObject(k)
            k to FlowEngine.CloudFlow(
                pctMain = v.getDouble("pctMain"),
                m5Ratio = v.optDoubleOrNull("m5Ratio"),
            )
        }
    }

    /** 用例自带参数：小样本要靠改小 `minPeerN` / `minCloudN` 才能走到横截面档与云端档。 */
    private fun flowParams(o: JSONObject): FlowEngine.Params = FlowEngine.Params(
        pctSd = o.getDouble("pctSd"),
        tanhK = o.getDouble("tanhK"),
        turnoverFloor = o.getDouble("turnoverFloor"),
        turnoverHigh = o.getDouble("turnoverHigh"),
        lowTurnWeight = o.getDouble("lowTurnWeight"),
        highTurnWeight = o.getDouble("highTurnWeight"),
        volRatioK = o.getDouble("volRatioK"),
        volRatioCap = o.getDouble("volRatioCap"),
        wCross = o.getDouble("wCross"),
        wAbs = o.getDouble("wAbs"),
        gatedWCross = o.getDouble("gatedWCross"),
        minPeerN = o.getDouble("minPeerN").toInt(),
        limitAmountRatio = o.getDouble("limitAmountRatio"),
        limitWeight = o.getDouble("limitWeight"),
        mainUnit = o.getDouble("mainUnit"),
        bullThreshold = o.getDouble("bullThreshold"),
        bearThreshold = o.getDouble("bearThreshold"),
        bullBreadthGate = o.getDouble("bullBreadthGate"),
        bearBreadthGate = o.getDouble("bearBreadthGate"),
        minCloudN = o.getDouble("minCloudN").toInt(),
        minAmountYuan = o.getDouble("minAmountYuan"),
    )

    /** 与 Python `rotation_synth_sectors` 逐行等价的生成器（夹具只存参数，不存大样本）。 */
    private fun rotationSynth(p: JSONObject): Triple<List<LocalSectors.Row>, Map<String, Double>, Map<String, Double>> {
        val n = p.getInt("n")
        val membersBase = p.getInt("membersBase")
        val membersStep = p.getInt("membersStep")
        val rows = (0 until n).map { i ->
            val name = "板块%02d".format(i)
            val members = membersBase + (i * membersStep) % 17
            val up = (i * 5) % (members + 1)
            LocalSectors.Row(
                name = name, n = members,
                chg1d = ((i * 13) % 90) / 10.0 - 4.0,
                heat = 1.0e9 + ((i * 37) % 200) * 1.0e8,
                up = up, down = members - up, limitUp = (i * 3) % 5,
                limitDown = 0, leader = null, leaderPct = null,
                strength = ((i * 29) % 100).toDouble(),
            )
        }
        val mom5 = rows.mapIndexed { i, r -> r.name to ((i * 17) % 60) / 10.0 - 3.0 }.toMap()
        val amt = rows.mapIndexed { i, r -> r.name to ((i * 23) % 40) / 10.0 - 2.0 }.toMap()
        return Triple(rows, mom5, amt)
    }

    private fun sectorsOf(o: JSONObject): LocalSectors.Result {
        val coverage = o.getDouble("coverage")
        val total = 100
        val mapped = Math.round(coverage * total).toInt()
        val arr = o.getJSONArray("rows")
        val parsed = (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            LocalSectors.Row(
                name = r.getString("name"),
                n = r.getInt("n"),
                chg1d = r.optDoubleOrNull("chg1d"),
                heat = r.getDouble("heat"),
                up = r.getInt("up"),
                down = r.getInt("down"),
                limitUp = r.getInt("limitUp"),
                limitDown = r.optInt("limitDown", 0),
                leader = null,
                leaderPct = null,
                strength = r.optDoubleOrNull("strength"),
            )
        }
        return LocalSectors.Result(rows = parsed, mapped = mapped, total = total,
            unmapped = total - mapped, ts = "fixture")
    }

    // ── 渲染口径 ───────────────────────────────────────────────────
    /** 界面句式：有值才格式化，缺失一律占位符——不许落成 0 / 50。 */
    private fun scoreText(v: Double?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: "—"

    private fun assertRenderable(label: String, text: String) {
        assertTrue(text.isNotBlank(), "$label 渲染为空")
        for (bad in listOf("null", "NaN", "Infinity")) {
            assertTrue(!text.contains(bad), "$label 含不可渲染片段「$bad」：$text")
        }
    }

    private fun assertRange(label: String, v: Double, lo: Double, hi: Double) {
        assertTrue(v.isFinite() && v >= lo && v <= hi, "$label 越界：$v（期望 $lo..$hi）")
    }

    private fun assertFiniteIfPresent(label: String, v: Double?) {
        if (v != null) assertTrue(v.isFinite(), "$label 非有限值：$v")
    }

    private fun assertReasonsRenderable(label: String, reasons: List<String>) {
        assertTrue(reasons.isNotEmpty(), "$label 没有理由文案，板块页/详情页会显示一片空白")
        reasons.forEachIndexed { i, r -> assertRenderable("$label 理由#$i", r) }
    }

    // ── flow：资金流 ───────────────────────────────────────────────
    @Test
    fun `资金流强中弱与缺数据四种情况在界面上都可渲染`() {
        val case = golden().getJSONObject("flow").getJSONObject("small")
        val rowsJson = case.getJSONArray("rows")
        val rows = (0 until rowsJson.length()).map { rowOf(rowsJson.getJSONObject(it)) }
        val got = FlowEngine.screen(
            rows = rows,
            amount5dOf = doubleMap(case.optJSONObject("amount5d")),
            cloud = cloudOf(case.optJSONObject("cloud")),
            breadth = case.optDoubleOrNull("breadth"),
        )

        assertEquals(rows.size, got.size, "每只票都要有一条结果：界面按行渲染，少一条就少一行")
        var strong = 0
        var weak = 0
        var missing = 0
        for (r in got) {
            assertTrue(r.tier in flowTiers, "${r.code} 档位不在界面允许集合：${r.tier}")
            assertTrue(r.signal in signals, "${r.code} 信号不在允许集合：${r.signal}")
            assertRenderable("${r.code} 档位文案", r.tier)
            assertRange("${r.code} 置信度", r.confidence, 0.0, 1.0)
            assertTrue(r.liquidityWeight >= 0.0 && r.liquidityWeight.isFinite(), "${r.code} 流动性权重异常：${r.liquidityWeight}")
            assertTrue(r.volRatioFactor > 0.0 && r.volRatioFactor.isFinite(), "${r.code} 量比因子异常：${r.volRatioFactor}")
            assertReasonsRenderable("${r.code}", r.reasons)

            val score = r.score
            assertEquals(score == null, r.signal == "insufficient",
                "${r.code} 缺分必须是 insufficient 语义，否则界面会把「不可得」显示成中性")
            if (score == null) {
                missing++
                assertEquals("none", r.tier, "${r.code} 无分却给了档位 ${r.tier}，界面会把代理量当真值")
                assertTrue(r.netProxyYuan == null && r.fsiAbs == null && r.pctMain == null &&
                    r.fsiPct == null && r.m5Ratio == null && r.scoreDelta == null,
                    "${r.code} 无分却带着半截数字，界面会显示孤立的净额/占比/增量")
            } else {
                assertRange("${r.code} score", score, 0.0, 100.0)
                assertRenderable("${r.code} 分数文案", scoreText(score))
                when (r.signal) {
                    "bullish" -> strong++
                    "bearish" -> weak++
                }
            }
            // 有值就必须是有限数：NaN/Infinity 会以「NaN」字面量出现在文案里。
            assertFiniteIfPresent("${r.code} netProxyYuan", r.netProxyYuan)
            assertFiniteIfPresent("${r.code} fsiAbs", r.fsiAbs)
            assertFiniteIfPresent("${r.code} fsiPct", r.fsiPct)
            assertFiniteIfPresent("${r.code} pctMain", r.pctMain)
            assertFiniteIfPresent("${r.code} m5Ratio", r.m5Ratio)
            assertFiniteIfPresent("${r.code} scoreDelta", r.scoreDelta)
            r.netProxyYuan?.let { assertRenderable("${r.code} 净额文案", String.format(Locale.US, "%.0f", it)) }
            r.pctMain?.let { assertRenderable("${r.code} 主力占比文案", String.format(Locale.US, "%.1f%%", it * 100)) }
        }

        assertTrue(strong > 0, "flow 黄金向量里没有 bullish 用例，强档渲染未覆盖")
        assertTrue(weak > 0, "flow 黄金向量里没有 bearish 用例，弱档渲染未覆盖")
        assertTrue(missing > 0, "flow 黄金向量里没有缺数据用例，不可得语义未覆盖")
    }

    // ── flow：三档（cloud / local / local_abs）──────────────────────
    @Test
    fun `资金流 cloud local local_abs 三档在界面上都可渲染`() {
        val tiers = golden().getJSONObject("flow").getJSONObject("tiers")
        val seen = LinkedHashSet<String>()
        for (name in listOf("local", "local_abs", "cloud")) {
            val case = tiers.getJSONObject(name)
            val rowsJson = case.getJSONArray("rows")
            val rows = (0 until rowsJson.length()).map { rowOf(rowsJson.getJSONObject(it)) }
            val got = FlowEngine.screen(
                rows = rows,
                amount5dOf = doubleMap(case.optJSONObject("amount5d")),
                cloud = cloudOf(case.optJSONObject("cloud")),
                breadth = case.optDoubleOrNull("breadth"),
                p = flowParams(case.getJSONObject("params")),
            )
            val expect = case.getJSONArray("expect")
            assertEquals(expect.length(), got.size, "flow.tiers.$name 返回条数不一致")
            for (i in 0 until expect.length()) {
                val e = expect.getJSONObject(i)
                val r = got[i]
                assertEquals(e.getString("code"), r.code, "flow.tiers.$name[$i] 顺序不一致")
                // 档位决定界面把代理量当真值还是估值，必须与云端参考实现逐字相等。
                assertEquals(e.getString("tier"), r.tier, "flow.tiers.$name ${r.code} 档位与云端不一致")
                assertTrue(r.tier in flowTiers, "flow.tiers.$name ${r.code} 档位不在界面允许集合：${r.tier}")
                assertEquals(e.getString("signal"), r.signal, "flow.tiers.$name ${r.code} 信号不一致")
                assertTrue(r.signal in signals, "flow.tiers.$name ${r.code} 信号不在允许集合：${r.signal}")
                assertRenderable("flow.tiers.$name ${r.code} 档位文案", r.tier)
                assertReasonsRenderable("flow.tiers.$name ${r.code}", r.reasons)
                val expScore = e.optDoubleOrNull("score")
                val score = r.score
                if (expScore == null) {
                    assertTrue(score == null, "flow.tiers.$name ${r.code} 云端不可得但端上给了 $score")
                } else {
                    assertTrue(score != null, "flow.tiers.$name ${r.code} 云端有分端上却是 null")
                    assertTrue(kotlin.math.abs(expScore - score) <= 1e-6,
                        "flow.tiers.$name ${r.code} 分数不一致：云端 $expScore / 端上 $score")
                    assertRenderable("flow.tiers.$name ${r.code} 分数文案", scoreText(score))
                }
                seen.add(r.tier)
            }
        }
        assertEquals(setOf("local", "local_abs", "cloud"), seen,
            "夹具里三档没有全部走到：少哪档就等于该档的渲染没被验证")
    }

    // ── tape：盘口 ─────────────────────────────────────────────────
    @Test
    fun `盘口各状态与结构分项在界面上都可渲染`() {
        val cases = golden().getJSONObject("tape").getJSONArray("cases")
        var ok = 0
        var strong = 0
        var weak = 0
        var missing = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val bars = barsOf(c.getJSONArray("bars"))
            val row = if (c.has("row") && !c.isNull("row")) rowOf(c.getJSONObject("row")) else null
            val res = TapeEngine.compute(bars, row, c.getLong("now_ms"))

            assertTrue(res.status in tapeStatuses, "$id 状态不在界面允许集合：${res.status}")
            assertTrue(res.signal in signals, "$id 信号不在允许集合：${res.signal}")
            assertRenderable("$id 状态文案", res.status)
            assertRange("$id 置信度", res.confidence, 0.0, 1.0)
            assertTrue(res.usedWeight in 0..38, "$id 可用权重越界：${res.usedWeight}")
            assertReasonsRenderable(id, res.reasons)

            // 出不了分必须如实标成非 ok：界面就是靠 status 解释「为什么这行没有分」。
            val score = res.score
            assertEquals(score == null, res.status != "ok",
                "$id 无分却报 ok（或反之），界面会把不可得的分当成 0 分渲染")
            if (score == null) {
                missing++
                assertEquals("insufficient", res.signal, "$id 无分时信号必须是 insufficient")
                // empty() 必须把结构分项与置信度全部清掉：留着半截数字，界面会渲染孤立的影线/缺口/偏离。
                assertTrue(res.buyProxy == null && res.divergence == null && res.upperShadow == null &&
                    res.lowerShadow == null && res.gapPct == null && res.closePos5 == null &&
                    res.confidence == 0.0,
                    "$id 无分却带着结构分项或置信度（界面会显示孤立数字）")
            } else {
                ok++
                assertRange("$id score", score, 0.0, 100.0)
                assertRenderable("$id 分数文案", scoreText(score))
                when (res.signal) {
                    "bullish" -> strong++
                    "bearish" -> weak++
                    else -> assertEquals("neutral", res.signal, "$id 出分后信号必须落在三档内")
                }
            }
            // 结构分项允许 null（一字板/无缺口/日 K 不足），但给了值就必须能格式化。
            res.buyProxy?.let { assertRange("$id 主动买代理", it, 0.0, 1.0) }
            res.upperShadow?.let { assertRange("$id 上影", it, 0.0, 1.0) }
            res.lowerShadow?.let { assertRange("$id 下影", it, 0.0, 1.0) }
            res.closePos5?.let { assertRange("$id 5日位置", it, 0.0, 1.0) }
            assertFiniteIfPresent("$id 量价背离", res.divergence)
            assertFiniteIfPresent("$id 缺口", res.gapPct)
            res.buyProxy?.let { assertRenderable("$id 主动买文案", String.format(Locale.US, "%.0f%%", it * 100)) }
            res.closePos5?.let { assertRenderable("$id 5日位置文案", String.format(Locale.US, "%.0f%%", it * 100)) }
            res.gapPct?.let { assertRenderable("$id 缺口文案", String.format(Locale.US, "%+.1f%%", it)) }
        }

        assertTrue(ok > 0, "tape 黄金向量里没有可出分用例")
        assertTrue(missing > 0, "tape 黄金向量里没有缺数据用例，不可得语义未覆盖")
        if (weak == 0) {
            fail("tape 黄金向量里没有 bearish 用例：弱档渲染会静默失守（夹具需先补弱档场景）")
        }
    }

    // ── rotation：板块轮动 ─────────────────────────────────────────
    @Test
    fun `板块轮动状态与强弱排名在界面上都可渲染`() {
        val root = golden()
        val rot = root.getJSONObject("rotation")
        val (_, mom5, amt) = rotationSynth(rot.getJSONObject("params"))
        val cases = rot.getJSONArray("cases")
        var attack = 0
        var defense = 0
        var missing = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val sectors = sectorsOf(c.getJSONObject("sectors"))
            val res = RotationEngine.compute(
                sectors = sectors,
                mom5Pct = mom5,
                amtShareDeltaPp = amt,
                marketE = c.optDoubleOrNull("market_e"),
                breadth = c.optDoubleOrNull("breadth"),
            )

            assertTrue(res.marketState in rotationStates, "$id 轮动状态不在界面允许集合：${res.marketState}")
            assertTrue(res.status in rotationStatuses, "$id 状态档不在允许集合：${res.status}")
            assertRenderable("$id 轮动状态文案", res.marketState)
            assertEquals(sectors.coverage, res.coverage, 0.0,
                "$id 行业覆盖被改写：覆盖率必须原样透出，否则界面把「覆盖不足」显示成「覆盖充分」")
            assertRange("$id 动量覆盖", res.momCoverage, 0.0, 1.0)
            assertRange("$id 置信度", res.confidence, 0.0, 1.0)
            assertReasonsRenderable(id, res.reasons)
            when (res.marketState) {
                "attack" -> attack++
                "defense" -> defense++
            }

            if (res.status == "insufficient") {
                missing++
                assertTrue(res.ranked.isEmpty() && res.strongest.isEmpty() && res.weakest.isEmpty(),
                    "$id 判为 insufficient 却给了排名，界面会显示一份不该有的榜单")
                assertTrue(res.marketRot == null && res.dispersion == null,
                    "$id 判为 insufficient 却给了轮动分/离散度")
            } else {
                val mr = res.marketRot ?: fail("$id 已出状态却没有中位轮动分")
                assertTrue(mr.isFinite(), "$id 中位轮动分非有限值：$mr")
                assertRange("$id 中位轮动分", mr, -1.0, 1.0)
                assertRenderable("$id 轮动分文案", scoreText(mr))
                assertEquals(1.0, res.momCoverage, 1e-12,
                    "$id 夹具里每个板块都有 5 日动量，动量覆盖必须是 1.0（缺项按剩余权重归一，不许虚报覆盖）")
                val disp = res.dispersion ?: fail("$id 已出状态却没有离散度")
                assertTrue(disp.isFinite() && disp >= 0.0, "$id 离散度不可渲染：$disp")
                assertRenderable("$id 离散度文案", String.format(Locale.US, "%.2f", disp))
            }

            for (s in res.ranked) {
                assertTrue(s.name.isNotBlank(), "$id 排名里有空板块名，界面会显示一个空行")
                val label = RotationEngine.labelOf(s)
                assertTrue(label in sectorLabels, "$id 板块「${s.name}」标签不在允许集合：$label")
                assertRenderable("$id 板块「${s.name}」标签", label)
                val expectedLabel = when {
                    !s.rot.isFinite() -> "无数据"
                    s.rot >= LABEL_BULL -> "强"
                    s.rot <= LABEL_BEAR -> "弱"
                    else -> "中"
                }
                assertEquals(expectedLabel, label,
                    "$id 板块「${s.name}」rot=${s.rot} 应标 $expectedLabel（强≥$LABEL_BULL / 弱≤$LABEL_BEAR），实际 $label")
                if (s.rot.isFinite()) assertRange("$id 板块「${s.name}」rot", s.rot, -1.0, 1.0)
            }
            for (s in res.strongest + res.weakest) {
                assertTrue(s.name.isNotBlank(), "$id 最强/最弱榜里有空板块名")
                assertTrue(s.rot.isFinite(), "$id 最强/最弱榜混进了无数据板块「${s.name}」")
            }
            assertTrue(res.strongest.size <= TOP_N, "$id 最强榜 ${res.strongest.size} 条超过界面排版上限 $TOP_N")
            assertTrue(res.weakest.size <= TOP_N, "$id 最弱榜 ${res.weakest.size} 条超过界面排版上限 $TOP_N")
        }

        assertTrue(attack > 0, "rotation 黄金向量里没有 attack 用例，强档渲染未覆盖")
        assertTrue(defense > 0, "rotation 黄金向量里没有 defense 用例，弱档渲染未覆盖")
        assertTrue(missing > 0, "rotation 黄金向量里没有 insufficient 用例，不可得语义未覆盖")
    }
}
