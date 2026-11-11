package com.skapp.app.ui.state

import com.skapp.app.ui.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SectionLoaderTest {
    @Test
    fun fastSectionPublishesWhileAnotherWaits() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = MutableStateFlow(UiState())
            val loader = SectionLoader(state, scope)
            val gate = CompletableDeferred<String>()
            val slow = loader.load("slow", fetch = { gate.await() }) { s, v -> s.copy(quoteTs = v) }
            loader.load("fast", fetch = { "ready" }) { s, v -> s.copy(summary = mapOf("text" to v)) }.join()
            assertEquals("ready", state.value.summary["text"])
            assertTrue(slow.isActive)
            gate.complete("quote")
            slow.join()
            assertEquals("ready", state.value.summary["text"])
            assertEquals("quote", state.value.quoteTs)
        } finally { scope.cancel() }
    }

    @Test
    fun timeoutEndsLoadingAndDoesNotPublishLateResponse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = MutableStateFlow(UiState(summary = mapOf("text" to "cached")))
            val loader = SectionLoader(state, scope)
            loader.load("slow", timeoutMs = 20, fetch = { delay(200); "late" }) { s, v ->
                s.copy(summary = mapOf("text" to v))
            }.join()
            assertTrue(state.value.loadingSections.isEmpty())
            assertTrue(state.value.dataGaps.containsKey("slow"))
            delay(250)
            assertEquals("cached", state.value.summary["text"])
        } finally { scope.cancel() }
    }

    @Test
    fun failureDoesNotEraseOtherSection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = MutableStateFlow(UiState(quoteTs = "ready"))
            val loader = SectionLoader(state, scope)
            loader.load<String>("bad", fetch = { error("offline") }) { s, v -> s.copy(quoteTs = v) }.join()
            assertEquals("ready", state.value.quoteTs)
            assertEquals("offline", state.value.dataGaps["bad"])
        } finally { scope.cancel() }
    }
    @Test
    fun failedSectionsRetryWithoutReloadingHealthySections() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = MutableStateFlow(UiState())
            val loader = SectionLoader(state, scope)
            var healthyCalls = 0
            var failedCalls = 0
            loader.load("healthy", fetch = { healthyCalls++; "ok" }) { s, v ->
                s.copy(summary = mapOf("text" to v))
            }.join()
            loader.load<String>("failed", fetch = {
                failedCalls++
                if (failedCalls == 1) error("offline") else "recovered"
            }) { s, v -> s.copy(quoteTs = v) }.join()
            assertEquals("offline", state.value.dataGaps["failed"])
            assertTrue(loader.retryFailed(exclude = setOf("failed")).isEmpty())
            assertEquals("交给专用入口刷新的模块不得重复执行", 1, failedCalls)

            loader.retryFailed().forEach { it.join() }

            assertEquals("健康模块不应因局部重试重复请求", 1, healthyCalls)
            assertEquals("失败模块必须真正重新执行", 2, failedCalls)
            assertEquals("recovered", state.value.quoteTs)
            assertFalse("failed" in state.value.dataGaps)
        } finally { scope.cancel() }
    }

    @Test
    fun forcedRefreshRejectsOldBlockingResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = MutableStateFlow(UiState())
            val loader = SectionLoader(state, scope)
            val entered = java.util.concurrent.CountDownLatch(1)
            val old = loader.load("same", fetch = {
                entered.countDown()
                Thread.sleep(150)
                "old"
            }) { s, v -> s.copy(quoteTs = v) }
            entered.await()
            loader.load("same", force = true, fetch = { "new" }) { s, v -> s.copy(quoteTs = v) }.join()
            old.join()
            delay(180)
            assertEquals("new", state.value.quoteTs)
            assertFalse("same" in state.value.loadingSections)
        } finally { scope.cancel() }
    }

}
