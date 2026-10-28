package com.skapp.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 盘中推股名单 → `开盘推股历史数据/` 的一条轮次记录。
 *
 * 存的是「当时到底推荐了什么」，不是「事后看起来对不对」：云端午盘脚本要用它重建
 * 11:40 那个时点用户看到的名单，收盘复盘要用它算推送质量。所以字段只保留决策依据，
 * 不写事后才知道的涨跌。
 */
object IntradayPicks {

    fun toRound(
        artifact: StructuredPickArtifact,
        ts: String,
        session: String = "intraday",
    ): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("session", session)
        put("status", artifact.status)
        artifact.reason?.let { put("reason", it) }
        artifact.engine?.let { put("engine", it) }
        put("picks", JSONArray().also { arr ->
            artifact.picks.forEach { p ->
                arr.put(JSONObject().apply {
                    put("code", p.code)
                    p.name?.let { put("name", it) }
                    p.score?.let { put("score", it) }
                    p.price?.let { put("price", it) }
                    p.pctChg?.let { put("pctChg", it) }
                    p.buyable?.let { put("buyable", it) }
                    if (p.why.isNotEmpty()) put("why", JSONArray(p.why.take(3)))
                })
            }
        })
        if (artifact.metadata.isNotEmpty()) {
            put("meta", JSONObject().also { meta ->
                artifact.metadata.forEach { (k, v) ->
                    runCatching { meta.put(k, v) }
                }
            })
        }
    }
}
