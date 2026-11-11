package com.skapp.app.data

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubDataRoutingTest {
    private val raw = "https://raw.githubusercontent.com/qwerzxcva/stocks-db/main/decision.json"
    private val api = "https://api.github.com/repos/qwerzxcva/stocks-db/contents/decision.json?ref=main"

    private class FakeConnection(url: URL, private val code: Int) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream("controlled-body".toByteArray())
    }

    private fun withConnections(
        token: String?,
        response: (URL) -> Int = { 200 },
        check: (MutableList<FakeConnection>) -> Unit,
    ) {
        val previous = Http.openConnection
        val previousFault = Http.fault
        val requests = mutableListOf<FakeConnection>()
        try {
            Http.fault = null
            Http.openConnection = { url -> FakeConnection(url, response(url)).also { requests += it } }
            DbTables.configureGithubToken(token)
            check(requests)
        } finally {
            DbTables.configureGithubToken(null)
            Http.openConnection = previous
            Http.fault = previousFault
        }
    }

    @Test
    fun anonymousRawSuccessDoesNotCallApiOrSendToken() = withConnections(null) { requests ->
        assertEquals("controlled-body", runBlocking { SkappApi.fetchInputText(raw) })
        assertEquals(listOf(raw), requests.map { it.url.toString() })
        assertNull(requests.single().getRequestProperty("Authorization"))
        assertEquals(3000, requests.single().connectTimeout)
        assertEquals(3000, requests.single().readTimeout)
    }

    @Test
    fun authenticatedReadPrefersContentsWithExactRepositoryPathAndRef() = withConnections("TEST-routing") { requests ->
        assertEquals("controlled-body", runBlocking { SkappApi.fetchInputText(raw) })
        assertEquals(listOf(api), requests.map { it.url.toString() })
        assertEquals("Bearer TEST-routing", requests.single().getRequestProperty("Authorization"))
        assertEquals(false, requests.single().instanceFollowRedirects)
        assertEquals("application/vnd.github.raw", requests.single().getRequestProperty("Accept"))
        assertTrue(requests.single().connectTimeout <= 10000)
        assertTrue(requests.single().readTimeout <= 10000)
    }

    @Test
    fun authenticatedApiFailureFallsBackToPublicRawWithoutToken() = withConnections(
        "TEST-fallback", response = { if (it.host == "api.github.com") 403 else 200 },
    ) { requests ->
        assertEquals("controlled-body", runBlocking { SkappApi.fetchInputText(raw) })
        assertEquals(listOf(api, raw), requests.map { it.url.toString() })
        assertEquals("Bearer TEST-fallback", requests.first().getRequestProperty("Authorization"))
        assertNull(requests.last().getRequestProperty("Authorization"))
        assertTrue(requests.sumOf { it.connectTimeout + it.readTimeout } <= 30000)
    }

    @Test
    fun anonymousRawFailureUsesContentsAndPreservesAnyBranchAndNestedPath() {
        for (branch in listOf("main", "master", "release-v2")) {
            withConnections(null, response = { if (it.host == "raw.githubusercontent.com") 404 else 200 }) { requests ->
                val input = "https://raw.githubusercontent.com/owner/repo/$branch/nested/input.json"
                assertEquals("controlled-body", runBlocking { SkappApi.fetchInputText(input) })
                assertEquals(listOf(input, "https://api.github.com/repos/owner/repo/contents/nested/input.json?ref=$branch"),
                    requests.map { it.url.toString() })
                assertTrue(requests.all { it.getRequestProperty("Authorization") == null })
                assertTrue(requests.sumOf { it.connectTimeout + it.readTimeout } <= 30000)
            }
        }
    }

    @Test
    fun otherOriginsNeverReceiveTokenOrBecomeContentsUrls() {
        val urls = listOf(
            "https://qt.gtimg.cn/q=sh600000",
            "https://api.github.com.evil/repos/owner/repo/contents/input.json",
            "https://api.github.com@evil.example/input.json",
            "http://api.github.com/repos/owner/repo/contents/input.json",
            "https://api.github.com:444/repos/owner/repo/contents/input.json",
            "https://raw.githubusercontent.com.evil/owner/repo/main/input.json",
            "https://example.com/raw.githubusercontent.com/owner/repo/main/input.json",
            "https://raw.githubusercontent.com@evil.example/owner/repo/main/input.json",
            "http://raw.githubusercontent.com/owner/repo/main/input.json",
            "https://raw.githubusercontent.com:444/owner/repo/main/input.json",
            raw,
        )
        for (url in urls) {
            withConnections("TEST-no-leak", response = { 404 }) { requests ->
                assertNull(runBlocking { SkappApi.fetchInputText(url) })
                val external = requests.filter { it.url.host != "api.github.com" }
                assertTrue(external.all { it.getRequestProperty("Authorization") == null })
                if (url != raw) {
                    assertEquals(listOf(url), requests.map { it.url.toString() })
                    assertNull(requests.single().getRequestProperty("Authorization"))
                }
            }
        }
    }

    @Test
    fun directContentsOnlyUsesTokenOnStandardHttpsOrigin() {
        for (url in listOf(api, api.replace("api.github.com/", "api.github.com:443/"))) {
            withConnections("TEST-direct") { requests ->
                assertEquals("controlled-body", runBlocking { SkappApi.fetchInputText(url) })
                assertEquals(listOf(url), requests.map { it.url.toString() })
                assertEquals("Bearer TEST-direct", requests.single().getRequestProperty("Authorization"))
            }
        }
    }

    @Test
    fun clearingTokenRestoresAnonymousRouting() = withConnections("TEST-clear") { requests ->
        DbTables.configureGithubToken("")
        assertEquals("controlled-body", runBlocking { SkappApi.fetchInputText(raw) })
        assertEquals(listOf(raw), requests.map { it.url.toString() })
        assertNull(requests.single().getRequestProperty("Authorization"))
    }
}
