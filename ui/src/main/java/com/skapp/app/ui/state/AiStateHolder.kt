package com.skapp.app.ui.state


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skapp.app.data.DbTables
import com.skapp.app.data.AskEngine
import com.skapp.app.data.ExtendedIndicators
import com.skapp.app.data.AiRouter
import com.skapp.app.data.GitCodeAiClient
import com.skapp.app.data.GitHubModelsClient
import com.skapp.app.data.StockDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** AI 复盘 / 自由问答 / 提问式选股 */
internal class AiStateHolder(shared: SharedVm) : StateHolder(shared) {

    /** 刷新双源状态行（设置页显示用） */
    fun refreshAiProviderStatus() {
        _state.value = _state.value.copy(aiProviderStatus = AiRouter.statusLine())
    }

    fun setAiToken(t: String) {
        GitCodeAiClient.setToken(t)
        _state.value = _state.value.copy(
            aiToken = t,
            aiConfigured = t.isNotBlank(),
        )
        refreshAiProviderStatus()
    }

    /** 设置页 GitHub token 输入框 onValueChange */
    fun setGithubToken(t: String) {
        GitHubModelsClient.setToken(t)
        _state.value = _state.value.copy(
            githubToken = t,
            githubConfigured = GitHubModelsClient.isConfigured(),
        )
        refreshAiProviderStatus()
    }

    /** AI 复盘当前详情页的股票：先生成中枢引擎总结，有凭据才请求云端二次总结。 */
    fun aiReviewCurrent() {
        val d = _state.value.detail ?: return
        if (_state.value.aiReviewLoading) return
        viewModelScope.launch {
            val central = _state.value.centralEngineSummary
                ?: "中枢引擎：数据不足，无法形成有效总结。"
            _state.value = _state.value.copy(aiReviewLoading = true, aiReview = central)
            if (AiRouter.availableProviders().isEmpty()) {
                _state.value = _state.value.copy(
                    aiReviewLoading = false,
                    aiReview = central,
                    aiLastProvider = null,
                    aiProviderStatus = AiRouter.statusLine(),
                )
                return@launch
            }
            val data = buildStockReviewData(d)
            val prompt = """你是 A 股复盘分析师。以下是该股 ${d.code} ${d.name} 的**真实实时数据**，你只允许基于这些数据回答，禁止编造任何数字或事实。数据里没有的就说「数据不足」。

中枢引擎原始总结：
$central

$data

请输出 3 部分（每部分 1-2 句，总 300 字内）：
1. 【当前状态】技术面+资金面+消息面综合评价
2. 【风险】主要风险点
3. 【观察点】后续该盯什么
直接输出文本，不要 markdown 代码块。"""
            val r = try {
                AiRouter.askDeep(listOf("user" to prompt))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val out = r?.takeIf { !it.failed && !it.text.isNullOrBlank() }?.let {
                "[via ${it.provider ?: "?"}${it.model?.let { model -> " · $model" } ?: ""}]\n\n${it.text}"
            }
            _state.value = _state.value.copy(
                aiReviewLoading = false,
                aiReview = out ?: central,
                aiLastProvider = r?.provider,
                aiProviderStatus = AiRouter.statusLine(),
            )
        }
    }

    /** 拼复盘用的真实数据底稿（原 GitCodeAiClient.reviewStock 的数据段，现归 ViewModel） */
    private fun buildStockReviewData(d: StockDetail): String {
        val s = _state.value
        return buildString {
            append("【基础】代码=${d.code} 名称=${d.name}")
            _state.value.liveQuote?.let { q ->
                append(" 现价=${q.price ?: "暂无"} 涨跌=${q.pctChg?.let { "%.2f%%".format(it) } ?: "暂无"}")
            }
            appendLine()
            appendLine("【中枢引擎】综合=${s.centralEngineScore?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "insufficient"} 共振=${s.centralEngineResonance?.takeIf { it.isFinite() }?.let { "%.0f%%".format(it * 100) } ?: "insufficient"}（按启用规格聚合） 状态=${s.centralEngineStatus} 规格=${s.engineSpecVersion ?: "unknown"}")
            s.localEngine?.let { e ->
                appendLine("【旧12引擎技术对照，非中枢评分】（${e.nValid}/${e.engines.size} 有效）")
                e.engines.forEach { en ->
                    if (en.score != null) {
                        appendLine("  · ${en.engine}=${"%.1f".format(en.score)} ${en.signal}（${en.reasons.take(2).joinToString("，")}）")
                    }
                }
            }
            val factors = ExtendedIndicators.allFactors(_state.value.kline)
            val validFactors = factors.entries.filter { it.value != null }.take(15)
            if (validFactors.isNotEmpty()) {
                appendLine("【因子】" + validFactors.joinToString(" ") { "${it.key}=${"%.4f".format(it.value!!)}" })
            }
            _state.value.holo?.technical?.entries?.filter { it.value != null }?.take(10)?.let { t ->
                appendLine("【holo 技术】" + t.joinToString(" ") { "${it.key}=${"%.2f".format(it.value!!)}" })
            }
            _state.value.holo?.finance?.entries?.take(8)?.let { f ->
                appendLine("【财务】" + f.joinToString(" ") { "${it.key}=${it.value ?: "暂无"}" })
            }
            val news = _state.value.holo?.news ?: emptyList()
            if (news.isNotEmpty()) {
                appendLine("【该股新闻】")
                news.take(6).forEach { n ->
                    appendLine("  · ${n["title"] ?: "无标题"}")
                }
            }
            val boards = _state.value.boardsOf
            if (boards.isNotEmpty()) {
                appendLine("【板块】" + boards.mapNotNull { it["name"]?.toString() }.joinToString("、"))
            }
        }
    }

    /** 自由提问（把当前上下文全部给 AI） */
    fun aiAsk() {
        val q = _state.value.aiQuestion.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            if (AiRouter.availableProviders().isEmpty()) {
                _state.value = _state.value.copy(
                    aiAnswerLoading = false,
                    aiAnswer = _state.value.centralEngineSummary
                        ?: "中枢引擎：暂无可用于回答的数据。",
                    aiLastProvider = null,
                    aiProviderStatus = AiRouter.statusLine(),
                )
                return@launch
            }
            _state.value = _state.value.copy(aiAnswerLoading = true, aiAnswer = null)
            val ctx = buildAiContext()
            val prompt = """你是 A 股数据分析助手。用户提问：$q

以下是你可调用的**实时数据底稿**（app 刚从数据源拉到的真实值）：

$ctx

规则：
- 只允许基于上面这些数据回答；数据里没有的就明说「未拉取到相关数据」
- 结论要有依据，引用具体数字（如「量比 2.3」「中枢引擎 71.2」）
- 300 字内，直接回答，不要寒暄、不要免责声明"""
            val r = try {
                AiRouter.askDeep(listOf("user" to prompt))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val out = r?.takeIf { !it.failed && !it.text.isNullOrBlank() }?.let {
                "[via ${it.provider ?: "?"}${it.model?.let { model -> " · $model" } ?: ""}]\n\n${it.text}"
            }
            _state.value = _state.value.copy(
                aiAnswerLoading = false,
                aiAnswer = out ?: _state.value.centralEngineSummary ?: "中枢引擎：暂无可用于回答的数据。",
                aiLastProvider = r?.provider,
                aiProviderStatus = AiRouter.statusLine(),
            )
        }
    }

    fun onAiQuestionChange(q: String) {
        _state.value = _state.value.copy(aiQuestion = q)
    }

    /** 把当前 app 已拉到的全部数据序列化成 AI 底稿 */
    private fun buildAiContext(): String {
        val s = _state.value
        val sb = StringBuilder()
        sb.appendLine("【市场时段】${s.marketPhase}")
        s.centralEngineSummary?.let { sb.appendLine("【中枢引擎原始总结】$it") }
        sb.appendLine("【行情】${s.quotes.joinToString("；") { "${it.name} ${it.price} ${it.pctChg?.let { "%.2f%%".format(it) } ?: ""}" }}")
        val p = s.picks
        sb.appendLine("【推股】signals=${p.signals.size} values=${p.values.size} 涨停=${p.limitUp.size}")
        p.signals.take(8).forEach { sb.appendLine("  · ${it.name ?: it.code} 分=${it.score} ${it.engine ?: ""} 价=${it.price}") }
        val d = s.decision
        sb.appendLine("【决策】${d.marketRegime} ${d.regimeDetail ?: ""} 仓位=${d.positionAdvice}")
        val m = s.market
        sb.appendLine("【看板】${m.market.entries.joinToString(" ") { "${it.key}=${it.value}" }.take(200)}")
        val n = s.news
        sb.appendLine("【消息情绪】${n.digest["global_mood"]} 评分=${n.digest["global_score"]} 共 ${n.digest["total"]} 条")
        val sec = s.sectors
        sb.appendLine("【板块】行业 ${sec.industries.size} 概念 ${sec.concepts.size}")
        val ps = s.pools
        sb.appendLine("【多池】" + ps.joinToString("；") { "${it.name}${it.items.size}只" })
        // 若当前打开了某只股，带上它的完整本地分析
        s.detail?.let { dd ->
            sb.appendLine("【当前详情】${dd.name ?: dd.code}（${dd.code}）")
            sb.appendLine("【中枢引擎】综合=${s.centralEngineScore?.takeIf { it.isFinite() }?.let { "%.1f".format(it) } ?: "insufficient"} 共振=${s.centralEngineResonance?.takeIf { it.isFinite() }?.let { "%.0f%%".format(it * 100) } ?: "insufficient"}（按启用规格聚合） 状态=${s.centralEngineStatus} 规格=${s.engineSpecVersion ?: "unknown"}")
            s.localEngine?.let { e ->
                sb.appendLine("【旧12引擎技术对照，非中枢评分】（${e.nValid}/${e.engines.size} 有效）")
                e.engines.filter { it.score != null }.forEach {
                    sb.appendLine("    · ${it.engine}=${"%.1f".format(it.score!!)} ${it.signal}")
                }
            }
        }
        return sb.toString()
    }

    fun onQuestionChange(q: String) {
        _state.value = _state.value.copy(askQuestion = q)
    }

    /** 提问式选股 */
    fun ask() {
        val q = _state.value.askQuestion.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(askLoading = true, askAnswer = null, askMatches = emptyList())
            val engine = shared.askEngine ?: AskEngine(_state.value.symbols, emptyMap()).also { shared.askEngine = it }
            val codes = engine.resolveStocks(q)
            val details = codes.mapNotNull { DbTables.stockDetail(it) }
            val answer = engine.answer(q, details)
            _state.value = _state.value.copy(
                askLoading = false,
                askAnswer = answer,
                askMatches = details,
            )
        }
    }
}