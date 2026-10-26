package com.skapp.app.data

import android.util.Log
import org.json.JSONObject

/**
 * 数值数据验证引擎（移植自 numeric_validator.py）
 *
 * 字段级规则校验，拦截会污染全链路的脏数据：
 *   price    ∈ (0, 100000)
 *   chg_pct  ∈ [-35, 35]（A 股 20cm/30cm 涨跌停都覆盖）
 *   volume   ≥ 0
 *   turnover ∈ [0, 1e7]
 *   score    ∈ [0,100] 或 [0,1] 两种量纲都认
 *   K 线 OHLC 逻辑：high ≥ max(open,close)，low ≤ min(open,close)
 *
 * 全部通过返回 true；任何 critical 项失败返回 false 并记日志。
 */
object DataValidator {

    private const val TAG = "DataValidator"

    fun validateQuote(quote: JSONObject): Boolean {
        val code = quote.optString("code", "?")

        val price = quote.optDouble("price", Double.NaN)
        if (!price.isNaN() && (price <= 0 || price >= 100_000)) {
            Log.w(TAG, "[$code] price=$price 越界")
            return false
        }

        val chg = quote.optDouble("chg_pct", Double.NaN)
        if (!chg.isNaN() && (chg < -35.0 || chg > 35.0)) {
            Log.w(TAG, "[$code] chg_pct=$chg 越界")
            return false
        }

        val vol = quote.optLong("volume", -1)
        if (vol < 0) {
            Log.w(TAG, "[$code] volume=$vol < 0")
            return false
        }

        val tv = quote.optDouble("turnover", Double.NaN)
        if (!tv.isNaN() && (tv < 0 || tv > 1e7)) {
            Log.w(TAG, "[$code] turnover=$tv 越界")
            return false
        }

        val score = quote.optDouble("score", Double.NaN)
        if (!score.isNaN()) {
            val onHundredScale = score > 1.0
            val hi = if (onHundredScale) 100.0 else 1.0
            if (score < 0 || score > hi) {
                Log.w(TAG, "[$code] score=$score 越界")
                return false
            }
        }

        return true
    }

    /**
     * K 线单根校验：价格全正 + 高低包络成立。
     * 任一字段缺失（NaN）时跳过该项，不算失败。
     */
    fun validateKbar(open: Double, high: Double, low: Double, close: Double): Boolean {
        if (open <= 0 || high <= 0 || low <= 0 || close <= 0) return false
        if (high < low) return false
        if (high < open || high < close) return false
        if (low > open || low > close) return false
        return true
    }
}
