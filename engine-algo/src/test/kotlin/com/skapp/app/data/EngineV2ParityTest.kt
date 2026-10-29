package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * flow / tape / rotation 三引擎的双端一致性执法。
 *
 * 夹具 `testdata/parity/golden_v2.json` 由云端参考实现
 * `stocks-ai-view/scripts/engines_v2.py --golden` 生成（同一批确定性输入算出的期望值）。
 * 这里把同一批输入喂给端上 [FlowEngine] / [TapeEngine] / [RotationEngine]，
 * 逐字段比对档位、状态与分数。
 *
 * ## 为什么要比对「档位」而不只是分数
 *
 * 这三个引擎的产物直接进决策展示：`tier=cloud/local/local_abs` 决定「这个数是真值还是代理」，
 * `status=pre_open/illiquid/insufficient` 决定界面能不能显示分。分数对上了但档位漂了，
 * 界面就会把代理量标成真资金流——这比分数差 1 分严重得多，所以档位是严格相等。
 *
 * ## 大样本不塞 JSON
 *
 * 320 行横截面数据不进夹具，而是由 `flow_synth_v1` / `rotation_synth_v1` 生成器在两端各自
 * 用纯整数算术生成。生成器参数存在夹具里，任何一端改了生成逻辑都会立刻对不上——
 * 这是刻意的：夹具本身也必须可复现。
 */
class EngineV2ParityTest {

    private fun golden(): JSONObject {
        val fixtures = System.getProperty("skapp.fixtures")
        val f = File(fixtures ?: "testdata", "parity/golden_v2.json")
        if (!f.isFile) fail("缺少 v2 黄金向量：${f.absolutePath}（先跑 scripts/engines_v2.py --golden）")
        return JSONObject(f.readText())
    }

    private fun assertClose(expected: Double, actual: Double, what: String) {
        val tol = maxOf(1e-6, kotlin.math.abs(expected) * 1e-12)
        assertTrue(
            kotlin.math.abs(expected - actual) <= tol,
            "$what 不一致：云端 $expected / 端上 $actual（容差 $tol）",
        )
    }

    private fun assertCloseNullable(expected: Double?, actual: Double?, what: String) {
        if (expected == null) {
            assertEquals(null, actual, "$what 云端不可得但端上给了值 $actual")
        } else {
            assertTrue(actual != null, "$what 云端有值 $expected 端上却是 null")
            assertClose(expected, actual!!, what)
        }
    }

    // ── 夹具 → 端上模型 ────────────────────────────────────────────
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

    // ── flow 生成器（与 Python flow_synth_rows 逐行等价）────────────
    private fun flowSynthRows(p: JSONObject): List<LiveMarket.Row> {
        val n = p.getInt("n")
        val base = p.getInt("codeBase")
        val amountBase = p.getDouble("amountBase")
        val amountStep = p.getDouble("amountStep")
        val turnDiv = p.getDouble("turnDiv")
        val pctMod = p.getInt("pctMod")
        return (0 until n).map { i ->
            val code = "%06d".format(base + i)
            val pct = ((i * 37) % pctMod) / 10.0 - 20.0
            val amount = amountBase + ((i * 7919) % 500) * amountStep
            val turn = ((i * 13) % 300) / turnDiv
            val vr = 0.5 + ((i * 17) % 40) / 10.0
            val mainNet = amount * (((i * 23) % 21) - 10) / 100.0
            val prevClose = 10.0 + (i % 50) * 0.1
            val price = prevClose * (1 + pct / 100.0)
            LiveMarket.Row(
                code = code, name = "S%03d".format(i), price = price, pctChg = pct,
                volumeLots = 1000.0 + (i * 3) % 500, amount = amount,
                amplitude = kotlin.math.abs(pct) + 1.0, turnoverRate = turn, pe = null,
                volRatio = vr, mv = null, mainNet = mainNet, ts = 0L,
                high = maxOf(price, prevClose), low = minOf(price, prevClose), open = prevClose,
                prevClose = prevClose,
            )
        }
    }

    private fun flowSynthCloud(p: JSONObject): Map<String, FlowEngine.CloudFlow> {
        val base = p.getInt("codeBase")
        val n = p.getInt("cloudN")
        return (0 until n).associate { i ->
            "%06d".format(base + i) to FlowEngine.CloudFlow(
                pctMain = ((i * 11) % 41 - 20) / 200.0,
                m5Ratio = ((i * 7) % 31 - 15) / 200.0,
            )
        }
    }

    // ── rotation 生成器（与 Python rotation_synth_sectors 等价）─────
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

    // ── 测试 ───────────────────────────────────────────────────────
    @Test
    fun `flow 小样本逐字段一致`() {
        val root = golden()
        val case = root.getJSONObject("flow").getJSONObject("small")
        val rowsJson = case.getJSONArray("rows")
        val rows = (0 until rowsJson.length()).map { rowOf(rowsJson.getJSONObject(it)) }
        val amount5d = doubleMap(case.optJSONObject("amount5d"))
        val breadth = if (case.has("breadth") && !case.isNull("breadth")) case.getDouble("breadth") else null
        val got = FlowEngine.screen(rows, amount5dOf = amount5d, breadth = breadth)
        compareFlow(case.getJSONArray("expect"), got, "flow.small")
    }

    @Test
    fun `flow 全市场横截面（大样本生成器）一致`() {
        val root = golden()
        val flow = root.getJSONObject("flow")
        for (name in listOf("synth", "synth_bear")) {
            val case = flow.getJSONObject(name)
            assertEquals("flow_synth_v1", case.getString("generator"))
            val params = case.getJSONObject("params")
            val rows = flowSynthRows(params)
            val breadth = case.getDouble("breadth")
            val cloud = if (case.optString("cloud") == "flow_synth_cloud_v1") flowSynthCloud(params) else emptyMap()
            val got = FlowEngine.screen(rows, cloud = cloud, breadth = breadth)
            compareFlow(case.getJSONArray("expect"), got, "flow.$name")
        }
    }

    /**
     * flow 三档置信（`local` / `local_abs` / `cloud`）。
     *
     * 这三档由云端 `engines_v2.flow_tiers` 生成、期望值由云端参考实现算出；之前夹具存在但**没人断言**，
     * 等于「看起来有覆盖」。档位直接决定界面标「真资金流」还是「代理量」
     * （`EnginesScreen` 原样拼 `flow.tier`），所以这里逐档比 tier 与全部数值字段。
     */
    @Test
    fun `flow 三档置信逐字段一致`() {
        val root = golden()
        val tiers = root.getJSONObject("flow").getJSONObject("tiers")
        for (name in listOf("local", "local_abs", "cloud")) {
            val c = tiers.getJSONObject(name)
            val rowsJson = c.getJSONArray("rows")
            val rows = (0 until rowsJson.length()).map { rowOf(rowsJson.getJSONObject(it)) }
            val cloud = cloudOf(c.optJSONObject("cloud"))
            val breadth = c.optDoubleOrNull("breadth")
            val turnScale = c.optDouble("turn_scale", 1.0)
            val got = FlowEngine.screen(
                rows = rows, cloud = cloud, breadth = breadth,
                turnScale = turnScale, p = flowParamsOf(c.getJSONObject("params")),
            )
            compareFlow(c.getJSONArray("expect"), got, "flow.tiers.$name")
            val expectedTier = c.getJSONArray("expect").getJSONObject(0).getString("tier")
            assertEquals(name, expectedTier, "夹具自检：$name 段期望的档位应是 $name")
            assertTrue(got.all { it.tier == expectedTier }, "flow.tiers.$name 有结果没落到该档")
        }
    }

    private fun cloudOf(o: JSONObject?): Map<String, FlowEngine.CloudFlow> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { code ->
            val v = o.getJSONObject(code)
            FlowEngine.CloudFlow(pctMain = v.getDouble("pctMain"), m5Ratio = v.optDoubleOrNull("m5Ratio"))
        }
    }

    private fun flowParamsOf(o: JSONObject): FlowEngine.Params {
        val d = FlowEngine.Params()
        return FlowEngine.Params(
            pctSd = o.optDouble("pctSd", d.pctSd),
            tanhK = o.optDouble("tanhK", d.tanhK),
            turnoverFloor = o.optDouble("turnoverFloor", d.turnoverFloor),
            turnoverHigh = o.optDouble("turnoverHigh", d.turnoverHigh),
            lowTurnWeight = o.optDouble("lowTurnWeight", d.lowTurnWeight),
            highTurnWeight = o.optDouble("highTurnWeight", d.highTurnWeight),
            volRatioK = o.optDouble("volRatioK", d.volRatioK),
            volRatioCap = o.optDouble("volRatioCap", d.volRatioCap),
            wCross = o.optDouble("wCross", d.wCross),
            wAbs = o.optDouble("wAbs", d.wAbs),
            gatedWCross = o.optDouble("gatedWCross", d.gatedWCross),
            minPeerN = o.optInt("minPeerN", d.minPeerN),
            limitAmountRatio = o.optDouble("limitAmountRatio", d.limitAmountRatio),
            limitWeight = o.optDouble("limitWeight", d.limitWeight),
            mainUnit = o.optDouble("mainUnit", d.mainUnit),
            bullThreshold = o.optDouble("bullThreshold", d.bullThreshold),
            bearThreshold = o.optDouble("bearThreshold", d.bearThreshold),
            bullBreadthGate = o.optDouble("bullBreadthGate", d.bullBreadthGate),
            bearBreadthGate = o.optDouble("bearBreadthGate", d.bearBreadthGate),
            minCloudN = o.optInt("minCloudN", d.minCloudN),
            minAmountYuan = o.optDouble("minAmountYuan", d.minAmountYuan),
        )
    }

    private fun compareFlow(expect: JSONArray, got: List<FlowEngine.Result>, tag: String) {
        assertEquals(expect.length(), got.size, "$tag 返回条数不一致")
        for (i in 0 until expect.length()) {
            val e = expect.getJSONObject(i)
            val g = got[i]
            assertEquals(e.getString("code"), g.code, "$tag[$i] 顺序不一致")
            assertEquals(e.getString("tier"), g.tier, "$tag ${g.code} 档位不一致（该字段决定代理/真值）")
            assertEquals(e.getString("signal"), g.signal, "$tag ${g.code} 信号不一致")
            assertCloseNullable(e.optDoubleOrNull("score"), g.score, "$tag ${g.code} score")
            assertCloseNullable(e.optDoubleOrNull("fsiAbs"), g.fsiAbs, "$tag ${g.code} fsiAbs")
            assertCloseNullable(e.optDoubleOrNull("fsiPct"), g.fsiPct, "$tag ${g.code} fsiPct")
            assertCloseNullable(e.optDoubleOrNull("pctMain"), g.pctMain, "$tag ${g.code} pctMain")
            assertCloseNullable(e.optDoubleOrNull("m5Ratio"), g.m5Ratio, "$tag ${g.code} m5Ratio")
            assertCloseNullable(e.optDoubleOrNull("netProxyYuan"), g.netProxyYuan, "$tag ${g.code} 净额代理")
            assertCloseNullable(e.optDoubleOrNull("scoreDelta"), g.scoreDelta, "$tag ${g.code} scoreDelta")
            assertClose(e.getDouble("confidence"), g.confidence, "$tag ${g.code} 置信度")
            assertClose(e.getDouble("liquidityWeight"), g.liquidityWeight, "$tag ${g.code} 流动性权重")
            assertClose(e.getDouble("volRatioFactor"), g.volRatioFactor, "$tag ${g.code} 量比因子")
        }
    }

    @Test
    fun `tape 各场景逐字段一致`() {
        val root = golden()
        val cases = root.getJSONObject("tape").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val bars = barsOf(c.getJSONArray("bars"))
            val row = if (c.has("row") && !c.isNull("row")) rowOf(c.getJSONObject("row")) else null
            val got = TapeEngine.compute(bars, row, c.getLong("now_ms"))
            val e = c.getJSONObject("expect")
            assertEquals(e.getString("status"), got.status, "$id 状态不一致")
            assertEquals(e.getString("signal"), got.signal, "$id 信号不一致")
            assertCloseNullable(e.optDoubleOrNull("score"), got.score, "$id score")
            assertCloseNullable(e.optDoubleOrNull("buyProxy"), got.buyProxy, "$id 主动买代理")
            assertCloseNullable(e.optDoubleOrNull("divergence"), got.divergence, "$id 量价背离")
            assertCloseNullable(e.optDoubleOrNull("upperShadow"), got.upperShadow, "$id 上影")
            assertCloseNullable(e.optDoubleOrNull("lowerShadow"), got.lowerShadow, "$id 下影")
            assertCloseNullable(e.optDoubleOrNull("gapPct"), got.gapPct, "$id 缺口")
            assertCloseNullable(e.optDoubleOrNull("closePos5"), got.closePos5, "$id 5日位置")
            assertClose(e.getInt("usedWeight").toDouble(), got.usedWeight.toDouble(), "$id 可用权重")
            assertClose(e.getDouble("confidence"), got.confidence, "$id 置信度")
            val expFilled = if (e.isNull("gapFilled")) null else e.getBoolean("gapFilled")
            assertEquals(expFilled, got.gapFilled, "$id 缺口回补判定")
        }
    }

    @Test
    fun `rotation 五个场景逐板块一致`() {
        val root = golden()
        val rot = root.getJSONObject("rotation")
        assertEquals("rotation_synth_v1", rot.getString("generator"))
        val (rows, mom5, amt) = rotationSynth(rot.getJSONObject("params"))
        val cases = rot.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val sectors = sectorsOf(c.getJSONObject("sectors"))
            val e = c.getJSONObject("expect")
            val got = RotationEngine.compute(
                sectors = sectors,
                mom5Pct = mom5,
                amtShareDeltaPp = amt,
                marketE = e.optDoubleOrNull("e") ?: c.optDoubleOrNull("market_e"),
                breadth = c.optDoubleOrNull("breadth"),
            )
            assertEquals(e.getString("marketState"), got.marketState, "$id 轮动状态不一致")
            assertEquals(e.getString("status"), got.status, "$id 状态档不一致")
            assertCloseNullable(e.optDoubleOrNull("marketRot"), got.marketRot, "$id 中位轮动分")
            assertCloseNullable(e.optDoubleOrNull("dispersion"), got.dispersion, "$id 离散度")
            assertClose(e.getDouble("confidence"), got.confidence, "$id 置信度")
            assertClose(e.getDouble("momCoverage"), got.momCoverage, "$id 动量覆盖率")
            val expRanked = e.getJSONArray("ranked")
            assertEquals(expRanked.length(), got.ranked.size, "$id 板块数不一致")
            for (k in 0 until expRanked.length()) {
                val er = expRanked.getJSONObject(k)
                val gr = got.ranked[k]
                assertEquals(er.getString("name"), gr.name, "$id 排名第 ${k + 1} 位的板块不一致")
                assertCloseNullable(er.optDoubleOrNull("rot"), gr.rot.takeIf { it.isFinite() }, "$id ${gr.name} rot")
            }
            assertEquals(
                (0 until e.getJSONArray("strongest").length()).map { e.getJSONArray("strongest").getJSONObject(it).getString("name") },
                got.strongest.map { it.name },
                "$id 最强板块名单不一致",
            )
            assertEquals(
                (0 until e.getJSONArray("weakest").length()).map { e.getJSONArray("weakest").getJSONObject(it).getString("name") },
                got.weakest.map { it.name },
                "$id 最弱板块名单不一致",
            )
        }
    }

    @Test
    fun `端上参数默认值与云端参考实现一致`() {
        val root = golden()
        val params = root.getJSONObject("params")
        val flow = params.getJSONObject("flow")
        val fp = FlowEngine.Params()
        assertEquals(flow.getDouble("minPeerN").toInt(), fp.minPeerN)
        assertEquals(flow.getDouble("mainUnit"), fp.mainUnit)
        assertEquals(flow.getDouble("bullThreshold"), fp.bullThreshold)
        assertEquals(flow.getDouble("limitAmountRatio"), fp.limitAmountRatio)
        assertEquals(flow.getDouble("minCloudN").toInt(), fp.minCloudN)
        val tape = params.getJSONObject("tape")
        val tp = TapeEngine.Params()
        assertEquals(tape.getDouble("openMinute").toInt(), tp.openMinute)
        assertEquals(tape.getDouble("closeAuctionMinute").toInt(), tp.closeAuctionMinute)
        assertEquals(tape.getDouble("minUsedWeight").toInt(), tp.minUsedWeight)
        assertEquals(tape.getDouble("bullThreshold"), tp.bullThreshold)
        val rot = params.getJSONObject("rotation")
        val rp = RotationEngine.Params()
        assertEquals(rot.getDouble("minSectors").toInt(), rp.minSectors)
        assertEquals(rot.getDouble("dispGate"), rp.dispGate)
        assertEquals(rot.getDouble("minCoverage"), rp.minCoverage)
        assertEquals(rot.getDouble("minUsableWeight"), rp.minUsableWeight)
    }
}
