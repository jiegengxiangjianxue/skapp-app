package com.skapp.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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
    fun judgementSetIsExposedConsistently() {
        assertEquals(CloudFeed.Doc.JUDGEMENT_DOCS, Db.judgementDocs)
        assertTrue(CloudFeed.Doc.DECISION.judgement)
        assertFalse(CloudFeed.Doc.RPS_ROTATION.judgement)
        // 判断类不得为空：空了等于闸门形同虚设
        assertTrue(CloudFeed.Doc.JUDGEMENT_DOCS.isNotEmpty())
    }
}
