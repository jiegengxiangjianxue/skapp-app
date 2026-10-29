package com.skapp.app.data

/**
 * 新鲜度阈值的单一来源（S4）。
 *
 * 此前同一件事三处各自写死、互不联动：
 *  - 云端日更文档：TTL 10 分钟、36 小时判 stale（CloudFeed）；
 *  - 引擎池缓存：45 秒、快照 5 分钟判 stale（EngineFeedLive）；
 *  - 全市场快照：5 分钟（LocalScreener.snapshotStatus）。
 * 改一处不影响另一处，就会出现「云端数据早就 stale、端上还当新鲜」的假象。
 * 这里的值与原常量逐字一致，只是把口径集中，**不发明新阈值**。
 */
object FreshnessPolicy {

    /** 云端日更文档的内存缓存时长。 */
    const val CLOUD_TTL_MS = 10 * 60 * 1000L

    /** 云端日更文档超过这个年龄标记 stale（日更任务没能跑成）。 */
    const val CLOUD_STALE_HOURS = 36.0

    /** 引擎池装配结果缓存：一次装配要拉几十只日 K。 */
    const val ENGINE_POOL_TTL_MS = 45_000L

    /** 全市场快照的新鲜度阈值，与 `LocalScreener.snapshotStatus` 同口径。 */
    const val SNAPSHOT_STALE_MS = 5 * 60_000L

    /**
     * 快照新鲜度判定：`ts` 距今超过 [SNAPSHOT_STALE_MS] 记 `stale`。
     *
     * `ts <= 0` 视为拿不到时间戳，沿用旧行为不算 stale（宁可不判新鲜也不误报）。
     */
    fun snapshotStatus(ts: Long, now: Long): String =
        if (ts > 0 && now - ts > SNAPSHOT_STALE_MS) "stale" else "live"
}
