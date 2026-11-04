package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * S3 判断类文件闸门：默认拒供、显式放行、清单与拒供口径一致。
 *
 * 拒供判定是纯函数，不触网；[Db.archive] 的拒供路径在发请求前返回，可离线跑。
 */
class CloudFeedJudgementTest {

    @Test
    fun judgementDocIsRefusedByDefault() = runBlocking {
        val got = Db.archive(CloudFeed.Doc.DECISION)
        assertNull(got.json, "判断类文件不该拿到 json")
        assertFalse(got.ok, "判断类文件默认必须拒供")
        assertEquals(CloudFeed.ERROR_JUDGEMENT_REFUSED, got.error)
    }

    @Test
    fun everyJudgementDocIsRefused() {
        CloudFeed.Doc.JUDGEMENT_DOCS.forEach { doc ->
            assertTrue(
                CloudFeed.shouldRefuseJudgement(doc, allowJudgement = false),
                "${doc.file} 属判断类，应默认拒供",
            )
        }
    }

    @Test
    fun allowJudgementOpensTheGate() {
        CloudFeed.Doc.JUDGEMENT_DOCS.forEach { doc ->
            assertFalse(
                CloudFeed.shouldRefuseJudgement(doc, allowJudgement = true),
                "${doc.file} 对照面板显式放行后不应再拒",
            )
        }
    }

    @Test
    fun dataDocsAreNeverRefused() {
        listOf(
            CloudFeed.Doc.RPS_ROTATION,
            CloudFeed.Doc.MARKET_BREADTH,
            CloudFeed.Doc.ETF_SHARES,
            CloudFeed.Doc.GLOBAL_MARKETS,
            CloudFeed.Doc.PEAD_EVENTS,
            CloudFeed.Doc.LIMIT_HISTORY,
        ).forEach { doc ->
            assertFalse(
                CloudFeed.shouldRefuseJudgement(doc, allowJudgement = false),
                "${doc.file} 是数据类文件，不该走判断类拒供",
            )
        }
    }

    @Test
    fun cacheHitRecomputesDocumentAge() = runBlocking {
        val at = System.currentTimeMillis()
        val cached = CloudFeed.CloudDoc(
            doc = CloudFeed.Doc.ENGINE_MANIFEST,
            json = JSONObject(),
            fetchedAt = at - 3_600_000L,
            ageHours = FreshnessPolicy.CLOUD_STALE_HOURS - 0.5,
        )
        val got = withCached(cached) {
            val first = CloudFeed.load(cached.doc)
            val second = CloudFeed.load(cached.doc)
            assertEquals(first.ageHours!!, second.ageHours!!, 0.01, "重复读取不能重复累加整段缓存年龄")
            second
        }

        assertTrue(got.fromCache)
        assertTrue(got.ageHours!! >= FreshnessPolicy.CLOUD_STALE_HOURS + 0.5)
        assertTrue(got.stale, "缓存中的时间必须继续流逝，不能永远停在写入时")
        assertEquals(cached.fetchedAt, got.fetchedAt)
    }

    @Test
    fun unknownTimestampStaysUnknownOnCacheHit() = runBlocking {
        val cached = CloudFeed.CloudDoc(
            doc = CloudFeed.Doc.ENGINE_MANIFEST,
            json = JSONObject(),
            fetchedAt = System.currentTimeMillis() - 3_600_000L,
            ageHours = null,
        )
        val got = withCached(cached) { CloudFeed.load(cached.doc) }

        assertTrue(got.fromCache)
        assertNull(got.ageHours)
        assertEquals("时间未知", got.tsText)
    }

    @Test
    fun offlineFallbackRecomputesAgeWithoutReplacingTheCache() = runBlocking {
        val previousFault = Http.fault
        Http.fault = Http.Fault.OFFLINE
        try {
            for (age in listOf(FreshnessPolicy.CLOUD_STALE_HOURS - 0.5, null)) {
                val cached = CloudFeed.CloudDoc(
                    doc = CloudFeed.Doc.ENGINE_MANIFEST,
                    json = JSONObject(),
                    fetchedAt = System.currentTimeMillis() - 3_600_000L,
                    ageHours = age,
                )
                withCached(cached) {
                    val first = CloudFeed.load(cached.doc, force = true)
                    val second = CloudFeed.load(cached.doc, force = true)
                    assertTrue(second.fromCache)
                    assertTrue(second.ok)
                    assertEquals("FaultInjectedOffline", second.error)
                    assertEquals(cached.fetchedAt, second.fetchedAt)
                    if (age == null) {
                        assertNull(first.ageHours)
                        assertNull(second.ageHours)
                    } else {
                        assertEquals(age + 1.0, second.ageHours!!, 0.01)
                        assertEquals(first.ageHours!!, second.ageHours!!, 0.01)
                        assertTrue(second.stale)
                    }
                    second
                }
            }
        } finally {
            Http.fault = previousFault
        }
    }

    @Test
    fun documentAgeUsesTheSuppliedSamplingTime() {
        val sampledAt = System.currentTimeMillis() / 1000L * 1000L - 15_000L
        val json = JSONObject().put("ts", java.time.Instant.ofEpochMilli(sampledAt - 3_600_000L).toString())
        val method = CloudFeed::class.java.getDeclaredMethod("ageHoursOf", JSONObject::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }
        val age = method.invoke(CloudFeed, json, sampledAt) as Double
        assertEquals(1.0, age, 1e-9)
    }

    private suspend fun withCached(
        doc: CloudFeed.CloudDoc,
        read: suspend () -> CloudFeed.CloudDoc,
    ): CloudFeed.CloudDoc {
        CloudFeed.invalidate()
        val entryClass = CloudFeed::class.java.declaredClasses.single { it.simpleName == "Entry" }
        val constructor = entryClass.getDeclaredConstructor(CloudFeed.CloudDoc::class.java, java.lang.Long.TYPE)
            .apply { isAccessible = true }
        val entry = constructor.newInstance(doc, System.currentTimeMillis() + 60_000L)
        val cacheField = CloudFeed::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(CloudFeed) as MutableMap<CloudFeed.Doc, Any>
        synchronized(cache) { cache[doc.doc] = entry }
        return try {
            read()
        } finally {
            CloudFeed.invalidate()
        }
    }

    @Test
    fun judgementSetIsExposedConsistently() {
        assertEquals(CloudFeed.Doc.JUDGEMENT_DOCS, Db.judgementDocs)
        assertTrue(CloudFeed.Doc.DECISION.judgement)
        assertFalse(CloudFeed.Doc.RPS_ROTATION.judgement)
        // 判断类不得为空：空了等于闸门形同虚设
        assertTrue(CloudFeed.Doc.JUDGEMENT_DOCS.isNotEmpty())
    }
}
