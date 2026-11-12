package com.skapp.app.data

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnomalyWatchTest {
    private val t = Instant.parse("2026-11-12T02:00:00Z").toEpochMilli()
    private fun quote(at: Long = t, price: Double = 9.97, volume: Double? = 100.0) =
        AnomalyWatch.Quote("600000", "浦发银行", price, 1.0, 10.0, 9.5, volume, 2.0, null, at, "fixture/public")
    private fun armed(): AnomalyWatch.State {
        val first = AnomalyWatch.update(AnomalyWatch.State(), listOf(quote()), t)
        return AnomalyWatch.update(first, listOf(quote(t + 30_000, volume = 110.0)), t + 30_000)
    }

    @Test fun `trigger freezes target and duplicate never extends deadline`() {
        val state = armed()
        val alert = state.records.single()
        assertEquals("pending", alert.status)
        assertEquals(10.0, alert.frozenBreakoutPrice)
        val duplicate = AnomalyWatch.update(state, listOf(quote(t + 30_000, price = 10.1, volume = 120.0)), t + 40_000)
        assertEquals(state, duplicate)
        val done = AnomalyWatch.update(state, listOf(quote(t + 60_000, price = 10.1, volume = 120.0).copy(high = 10.1)), t + 60_000)
        assertEquals("fulfilled", done.records.single().status)
        assertEquals(alert.triggerAtMs, done.records.single().triggerAtMs)
        assertEquals(alert.expiresAtMs, done.records.single().expiresAtMs)
        assertEquals(done.records, AnomalyWatch.update(done, listOf(quote(t + 90_000, price = 9.0)), t + 90_000).records)
    }

    @Test fun `invalidating low wins before apparent breakout`() {
        val state = armed()
        val q = quote(t + 60_000, 10.1, 120.0).copy(high = 10.1, low = 9.4)
        assertEquals("invalidated", AnomalyWatch.update(state, listOf(q), q.sourceAtMs).records.single().status)
    }

    @Test fun `late advance never changes expiry to success and off board quote still checks`() {
        val state = armed()
        val late = quote(t + 400_000, 10.2, 150.0).copy(high = 10.2)
        val expired = AnomalyWatch.update(state, listOf(late), late.sourceAtMs)
        assertEquals("expired", expired.records.single().status)
        assertEquals("fulfilled", AnomalyWatch.update(state, listOf(quote(t + 60_000, 10.1, 120.0)), t + 60_000).records.single().status)
        assertEquals("expired", AnomalyWatch.update(expired, listOf(late.copy(sourceAtMs = t + 430_000)), t + 430_000).records.single().status)
    }

    @Test fun `out of order future stale different date and missing evidence cannot trigger`() {
        val first = AnomalyWatch.update(AnomalyWatch.State(), listOf(quote()), t)
        for (q in listOf(quote(t - 1), quote(t + 60_000), quote(t - 400_000), quote(t - 86_400_000))) {
            assertTrue(AnomalyWatch.update(first, listOf(q), t).records.isEmpty())
        }
        for (q in listOf(quote(t + 30_000, volume = null), quote(t + 30_000, volume = 90.0),
            quote(t + 30_000, 10.1, 110.0), quote(t + 30_000, volume = 110.0).copy(pctChg = -1.0),
            quote(t + 30_000, volume = 110.0).copy(volRatio = null, mainNet = null),
            quote(t + 30_000, volume = 110.0).copy(source = ""))) {
            assertTrue(AnomalyWatch.update(first, listOf(q), t + 30_000).records.isEmpty())
        }
        val nextDay = quote(t + 86_400_000, volume = 200.0)
        assertTrue(AnomalyWatch.update(first, listOf(nextDay), nextDay.sourceAtMs).records.isEmpty())
    }

    @Test fun `missing source quotes expire without rewriting trigger evidence`() {
        val state = armed()
        val alert = state.records.single()
        val expired = AnomalyWatch.update(state, emptyList(), alert.expiresAtMs + 1)
        assertEquals("expired", expired.records.single().status)
        assertEquals(alert.triggerPrice, expired.records.single().triggerPrice)
        assertEquals(alert.frozenBreakoutPrice, expired.records.single().frozenBreakoutPrice)
        assertEquals(alert.invalidBelow, expired.records.single().invalidBelow)
        val backwards = quote(t + 20_000, 10.5, 120.0)
        assertEquals(state, AnomalyWatch.update(state, listOf(backwards), t + 40_000))
    }

    @Test fun `zero nonfinite nonmain ST and source switches do not arm`() {
        val first = AnomalyWatch.update(AnomalyWatch.State(), listOf(quote()), t)
        for (q in listOf(quote(t + 30_000, 0.0, 110.0), quote(t + 30_000, Double.NaN, 110.0),
            quote(t + 30_000, volume = 110.0).copy(name = "ST测试"),
            quote(t + 30_000, volume = 110.0).copy(pctChg = 9.6),
            quote(t + 30_000, volume = 110.0).copy(high = 10.1),
            quote(t + 30_000, volume = 110.0).copy(source = "another/public"))) {
            assertTrue(AnomalyWatch.update(first, listOf(q), t + 30_000).records.isEmpty())
        }
        val nonMain = quote().copy(code = "300001")
        val a = AnomalyWatch.update(AnomalyWatch.State(), listOf(nonMain), t)
        assertTrue(AnomalyWatch.update(a, listOf(nonMain.copy(sourceAtMs = t + 30_000, volume = 120.0)), t + 30_000).records.isEmpty())
        assertEquals("unproven", AnomalyWatch.update(armed(), listOf(quote(t + 60_000, 10.1, 120.0).copy(low = null)), t + 60_000).records.single().status)
    }

    @Test fun `unknown stock names cannot establish non ST eligibility`() {
        for (name in listOf("", "  ", "null", "NULL", "--")) {
            val q = quote().copy(name = name)
            val first = AnomalyWatch.update(AnomalyWatch.State(), listOf(q), t)
            val next = q.copy(sourceAtMs = t + 30_000, volume = 110.0)
            assertTrue(AnomalyWatch.update(first, listOf(next), next.sourceAtMs).records.isEmpty())
        }
    }

    @Test fun `lunch and closing horizons are explicitly unproven`() {
        val lunch = Instant.parse("2026-11-12T03:29:00Z").toEpochMilli()
        val first = AnomalyWatch.update(AnomalyWatch.State(), listOf(quote(lunch)), lunch)
        val state = AnomalyWatch.update(first, listOf(quote(lunch + 30_000, volume = 110.0)), lunch + 30_000)
        assertEquals("unproven", state.records.single().status)
        assertTrue(state.records.single().resultReason.contains("sessiongap"))
    }
}
