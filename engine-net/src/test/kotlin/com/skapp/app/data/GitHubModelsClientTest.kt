package com.skapp.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubModelsClientTest {
    private val originalConnection = GitHubModelsClient.openConnection
    private val pending = ArrayDeque<FakeConnection>()
    private val opened = mutableListOf<FakeConnection>()
    private val messages = listOf("user" to "test")

    @BeforeTest
    fun setUp() {
        GitHubModelsClient.setToken(null)
        GitHubModelsClient.openConnection = {
            check(pending.isNotEmpty()) { "Unexpected request" }
            pending.removeFirst().also { opened.add(it) }
        }
    }

    @AfterTest
    fun tearDown() {
        GitHubModelsClient.setToken(null)
        GitHubModelsClient.openConnection = originalConnection
    }

    private fun respond(vararg codes: Int) {
        codes.forEach { pending.add(FakeConnection(it)) }
    }

    @Test
    fun singleTokenRemainsCompatible() {
        GitHubModelsClient.setToken("test-one")
        respond(200)
        assertEquals("ok", GitHubModelsClient.chat(messages, "test-model"))
        assertEquals("Bearer test-one", opened.single().getRequestProperty("Authorization"))
        assertEquals(200, GitHubModelsClient.lastHttpCode)
        assertTrue(opened.single().disconnected)
    }

    @Test
    fun trimsDropsBlankLinesAndDuplicatesWithoutSendingMultilineHeader() {
        GitHubModelsClient.setToken(" \r\n test-one \r\n\n test-one\n test-two \n")
        respond(401, 401)
        assertNull(GitHubModelsClient.chat(messages, "test-model"))
        assertEquals(listOf("Bearer test-one", "Bearer test-two"), opened.map { it.getRequestProperty("Authorization") })
        assertTrue(opened.all { it.disconnected })
    }

    @Test
    fun credentialFailuresTryNextToken() {
        for (code in listOf(401, 403, 429)) {
            opened.clear()
            pending.clear()
            GitHubModelsClient.setToken("test-one\ntest-two")
            respond(code, 200)
            assertEquals("ok", GitHubModelsClient.chat(messages, "test-model"))
            assertEquals(listOf("Bearer test-one", "Bearer test-two"), opened.map { it.getRequestProperty("Authorization") })
            assertEquals(200, GitHubModelsClient.lastHttpCode)
            assertTrue(opened.all { it.disconnected })
        }
    }

    @Test
    fun mixedFailuresRetainRateLimitRegardlessOfOrder() {
        for (codes in listOf(listOf(429, 401), listOf(401, 429))) {
            GitHubModelsClient.setToken("test-one\ntest-two")
            respond(*codes.toIntArray())
            assertNull(GitHubModelsClient.chat(messages, "test-model"))
            assertEquals(codes.last(), GitHubModelsClient.lastHttpCode)
            assertTrue(GitHubModelsClient.lastRateLimited)
        }
        GitHubModelsClient.setToken("test-one\ntest-two")
        respond(429, 401, 401, 401)
        assertNull(GitHubModelsClient.chatDeep(messages))
        assertTrue(GitHubModelsClient.lastRateLimited)
    }

    @Test
    fun serverErrorDoesNotSwitchToken() {
        GitHubModelsClient.setToken("test-one\ntest-two")
        respond(500, 200)
        assertNull(GitHubModelsClient.chat(messages, "test-model"))
        assertEquals(1, opened.size)
        assertEquals("Bearer test-one", opened.single().getRequestProperty("Authorization"))
        assertEquals(500, GitHubModelsClient.lastHttpCode)
    }

    @Test
    fun allFailuresKeepLastHttpCode() {
        GitHubModelsClient.setToken("test-one\ntest-two\ntest-three")
        respond(401, 403, 429)
        assertNull(GitHubModelsClient.chat(messages, "test-model"))
        assertEquals(3, opened.size)
        assertEquals(429, GitHubModelsClient.lastHttpCode)
        assertTrue(opened.all { it.disconnected })
    }

    @Test
    fun networkErrorDoesNotSwitchOrErasePreviousHttpFailure() {
        GitHubModelsClient.setToken("test-one\ntest-two\ntest-three")
        respond(429, 0, 200)
        assertNull(GitHubModelsClient.chat(messages, "test-model"))
        assertEquals(2, opened.size)
        assertEquals(429, GitHubModelsClient.lastHttpCode)
        assertTrue(opened.all { it.disconnected })
    }

    @Test
    fun clearingConfigurationPreventsRequestsAndResetsStatus() {
        GitHubModelsClient.setToken("test-one")
        respond(429)
        assertNull(GitHubModelsClient.chat(messages, "test-model"))
        for (empty in listOf(null, "", " \r\n\t")) {
            GitHubModelsClient.setToken(empty)
            assertFalse(GitHubModelsClient.isConfigured())
            assertNull(GitHubModelsClient.chat(messages, "test-model"))
            assertEquals(0, GitHubModelsClient.lastHttpCode)
        }
        assertEquals(1, opened.size)
    }

    @Test
    fun rejectsEmbeddedWhitespaceAndControlCharacters() {
        GitHubModelsClient.setToken("test one\ntest\u0000two\n\ttest-valid ")
        respond(200)
        assertEquals("ok", GitHubModelsClient.chat(messages, "test-model"))
        assertEquals("Bearer test-valid", opened.single().getRequestProperty("Authorization"))
    }

    private class FakeConnection(private val code: Int) : HttpURLConnection(URL(GitHubModelsClient.ENDPOINT)) {
        var disconnected = false
        private val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = output
        override fun getResponseCode(): Int {
            if (code == 0) throw IOException("Simulated connection failure")
            return code
        }
        override fun getInputStream() = ByteArrayInputStream(
            """{"choices":[{"message":{"content":"ok"}}]}""".toByteArray()
        )
    }
}
