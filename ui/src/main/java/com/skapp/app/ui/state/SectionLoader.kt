package com.skapp.app.ui.state

import com.skapp.app.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class SectionLoader(
    private val state: MutableStateFlow<UiState>,
    private val scope: CoroutineScope,
) {
    private val jobs = mutableMapOf<String, Job>()

    fun <T> load(
        label: String,
        timeoutMs: Long = 30_000,
        force: Boolean = false,
        fetch: suspend () -> T,
        publish: (UiState, T) -> UiState,
    ): Job {
        val old = jobs[label]
        if (old?.isActive == true && !force) return old
        old?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            state.update { it.copy(loadingSections = it.loadingSections + label, dataGaps = it.dataGaps - label) }
            // 网络库有阻塞式IO；独立请求不能让它的取消等待拖住页面的超时终态。
            val request = scope.async(Dispatchers.IO) { fetch() }
            try {
                val value = withTimeout(timeoutMs) { request.await() }
                currentCoroutineContext().ensureActive()
                state.update { publish(it, value).copy(dataGaps = it.dataGaps - label) }
            } catch (e: TimeoutCancellationException) {
                state.update { it.copy(dataGaps = it.dataGaps + (label to "请求超时，已保留上次结果；可重试")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(dataGaps = it.dataGaps + (label to (e.message ?: "数据源不可达").take(160))) }
            } finally {
                request.cancel()
                if (jobs[label] == currentCoroutineContext()[Job]) {
                    state.update { it.copy(loadingSections = it.loadingSections - label) }
                }
            }
        }
        jobs[label] = job
        job.start()
        return job
    }
}
