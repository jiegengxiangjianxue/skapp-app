package com.skapp.app.ui.state

import com.skapp.app.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「缺数据写 unknown，不得静默」的机制测试：失败要留缺口、成功要自动清缺口、
 * 取消不能被当成业务失败吞掉（否则协程取消会污染诊断页）。
 */
class DataGapTest {

    private class ProbeHolder(shared: SharedVm) : StateHolder(shared) {
        suspend fun <T> call(label: String, unknown: () -> T, block: suspend () -> T): T =
            gap(label, unknown, block)
    }

    private fun holder(): Pair<ProbeHolder, MutableStateFlow<UiState>> {
        val flow = MutableStateFlow(UiState())
        return ProbeHolder(SharedVm(flow, CoroutineScope(Dispatchers.Unconfined))) to flow
    }

    @Test
    fun `失败时给出未知值并留下缺口`() = runBlocking {
        val (h, flow) = holder()
        val value = h.call("最新报价(600519)", { null }) { error("connect timeout") }
        assertNull("未知值必须原样返回给调用方", value)
        val gap = flow.value.dataGaps["最新报价(600519)"]
        assertTrue("缺口原因应包含异常信息，实际=$gap", gap != null && gap.contains("connect timeout"))
        assertTrue("缺口应带时间戳，实际=$gap", gap!!.contains("@"))
    }

    @Test
    fun `恢复成功后自动清掉同名缺口`() = runBlocking {
        val (h, flow) = holder()
        h.call("分时(600519)", { emptyList<String>() }) { error("boom") }
        assertEquals(1, flow.value.dataGaps.size)
        val ok = h.call("分时(600519)", { emptyList<String>() }) { listOf("09:30 12.3") }
        assertEquals(listOf("09:30 12.3"), ok)
        assertTrue("成功一次后缺口必须消失", flow.value.dataGaps.isEmpty())
    }

    @Test
    fun `取消原样重抛且不写缺口`() = runBlocking {
        val (h, flow) = holder()
        try {
            h.call("日K(600519)", { emptyList<String>() }) { throw CancellationException("cancelled") }
            throw AssertionError("CancellationException 必须重抛")
        } catch (expected: CancellationException) {
            assertTrue("取消不是业务失败，不该记缺口", flow.value.dataGaps.isEmpty())
        }
    }
}
