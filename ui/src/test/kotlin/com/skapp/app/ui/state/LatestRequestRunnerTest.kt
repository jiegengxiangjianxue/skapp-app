package com.skapp.app.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestRequestRunnerTest {
    @Test fun latestSessionRunsAfterActiveWithoutOverlap() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val runner = LatestRequestRunner<String>(scope) { key ->
                calls += "start:$key"
                if (key == "morning") gate.await()
                calls += "end:$key"
            }
            runner.submit("morning")
            runner.submit("midday")
            runner.submit("realtime")
            assertEquals(listOf("start:morning"), calls)
            gate.complete(Unit)
            assertEquals(listOf("start:morning", "end:morning", "start:realtime", "end:realtime"), calls)
        } finally { scope.cancel() }
    }

    @Test fun forceDuringRunIsRetainedAndNormalDuplicatesCoalesce() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            var runs = 0
            val runner = LatestRequestRunner<String>(scope) { runs++; if (runs == 1) gate.await() }
            runner.submit("same")
            runner.submit("same")
            runner.submit("same", force = true)
            runner.submit("same")
            assertEquals(1, runs)
            gate.complete(Unit)
            assertEquals(2, runs)
        } finally { scope.cancel() }
    }

    @Test fun switchingBackToActiveSessionDiscardsObsoletePendingSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val runner = LatestRequestRunner<String>(scope) { calls += it; gate.await() }
            runner.submit("morning")
            runner.submit("midday")
            runner.submit("morning")
            gate.complete(Unit)
            assertEquals(listOf("morning"), calls)
        } finally { scope.cancel() }
    }

    @Test fun cancellationDiscardsPendingAndPreventsLatePublication() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val runner = LatestRequestRunner<String>(scope) { gate.await(); published += it }
        runner.submit("old")
        runner.submit("new")
        scope.cancel()
        gate.complete(Unit)
        runner.submit("after-cancel")
        assertEquals(emptyList<String>(), published)
    }
}
