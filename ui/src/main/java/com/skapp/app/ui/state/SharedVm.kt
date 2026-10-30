package com.skapp.app.ui.state

import com.skapp.app.data.AllSourcesClient
import com.skapp.app.data.AskEngine
import com.skapp.app.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/** 拆分前后共用的 SharedPreferences 文件名；SkappViewModel 的 companion 常量指向这里，改一处即两处生效。 */
internal const val SKAPP_PREFS = "skapp_prefs"

/**
 * 四个 StateHolder 共用的上下文：UiState 流、协程作用域，以及原本挂在 SkappViewModel 上的跨切面私有字段。
 *
 * 只由 SkappViewModel 创建一份，holder 持有引用而不是各自再存一份——否则「搜索词」「Context」
 * 这类跨 holder 的状态会出现两份不同步的副本。
 */
internal class SharedVm(
    val flow: MutableStateFlow<UiState>,
    val scope: CoroutineScope,
) {
    /** 代码→名称（DbTables.stockNames 结果），搜索与详情名称兜底用 */
    var stockNames: Map<String, String> = emptyMap()

    /** 最近一次搜索词：names/symbols 异步到位后要按它重放一次 search */
    var searchQuery: String = ""

    /** 提问式选股的 AskEngine：符号表到位后构建一次，复用 */
    var askEngine: AskEngine? = null

    /** 应用 Context（MainActivity 经 loadSavedToken 注入）；holder 落盘/读档用 */
    var appContext: android.content.Context? = null

    /** 上一次算名单时池是否没取到（没算过=false）。池缺失时当天要允许重算，否则加权永久为 0。 */
    var poolsMissingForPicks: Boolean = false

    /** 云端 190 源合并产物客户端（行情兜底 + 数据源健康度） */
    val allSources = AllSourcesClient()
}

/**
 * StateHolder 共同底座。
 *
 * 保留原 ViewModel 内 `_state` / `viewModelScope` 的调用写法，使搬移的方法体逐字不变——
 * 搬移类重构里最容易出错的正是这种机械改写。
 */
internal abstract class StateHolder(protected val shared: SharedVm) {

    protected val _state: MutableStateFlow<UiState> get() = shared.flow

    protected val viewModelScope: CoroutineScope get() = shared.scope

    /**
     * 取数兜底 + 记录缺口：拿不到数据时给调用方一个明确的未知值（null / 空表），
     * 同时把「为什么没有」写进 UiState.dataGaps 供诊断页展示。
     *
     * 存在意义是把 `runCatching{…}.getOrDefault(emptyList())` 这类静默兜底换掉：
     * 静默时「请求失败」和「确实没有数据」在界面上长得一样，用户只能看到「暂无数据」。
     * 下次成功会清掉同名缺口，所以它反映的是「当前是否缺」，不是历史日志。
     */
    protected suspend fun <T> gap(label: String, unknown: () -> T, block: suspend () -> T): T = try {
        val value = block()
        if (label in _state.value.dataGaps) {
            _state.value = _state.value.copy(dataGaps = _state.value.dataGaps - label)
        }
        value
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val why = (e.message ?: e::class.simpleName ?: "unknown").take(80)
        val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _state.value = _state.value.copy(dataGaps = _state.value.dataGaps + (label to "$why @$at"))
        unknown()
    }
}

/**
 * 把一次挂起调用收敛成 Result：异常不外抛，但 CancellationException 必须原样重抛，
 * 否则协程取消会被当成业务失败吞掉。
 *
 * 门面（SkappViewModel）与四个 holder 共用这一份实现，不各写一遍。
 */
internal suspend fun <T> requestResult(block: suspend () -> T): Result<T> = try {
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
