package com.forge.workout.backup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreStartupGateTest {
    @Test fun `cancelled activity or receiver waiter cannot cancel shared startup recovery`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val release = CompletableDeferred<Unit>()
        var recoveries = 0
        val gate = RestoreStartupGate(scope, { recoveries++; release.await() })
        try {
            val first = launch { gate.awaitReady() }
            val second = async { gate.awaitReady() }
            runCurrent()
            first.cancel()
            runCurrent()
            assertEquals(1, recoveries)
            assertFalse(second.isCompleted)
            assertEquals(RestoreStartupState.LOADING, gate.state.value)
            release.complete(Unit)
            second.await()
            assertEquals(RestoreStartupState.READY, gate.state.value)
            assertEquals(1, recoveries)
        } finally { scope.cancel() }
    }

    @Test fun `failed recovery stays gated and an explicit retry is shared by all entry points`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var attempts = 0
        val release = CompletableDeferred<Unit>()
        val gate = RestoreStartupGate(scope, {
            attempts++
            if (attempts == 1) throw IOException("DataStore unavailable")
            release.await()
        })
        try {
            val first = async { runCatching { gate.awaitReady() } }
            assertTrue(first.await().exceptionOrNull() is IOException)
            assertEquals(RestoreStartupState.FAILED, gate.state.value)
            assertTrue(runCatching { gate.awaitReady() }.exceptionOrNull() is IOException)
            assertEquals(1, attempts)
            gate.retry()
            gate.retry()
            val activity = async { gate.awaitReady() }
            val receiver = async { gate.awaitReady() }
            runCurrent()
            assertEquals(2, attempts)
            assertFalse(activity.isCompleted)
            release.complete(Unit)
            activity.await()
            receiver.await()
            assertEquals(RestoreStartupState.READY, gate.state.value)
        } finally { scope.cancel() }
    }

    @Test fun `post restore compensation failure gates the running app until retry completes cleanup`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var unavailable = false
        var paused = false
        var cleanupCount = 0
        val gate = RestoreStartupGate(scope, {
            if (unavailable) throw IOException("Recovery write failed")
            if (paused) { paused = false; cleanupCount++ }
        })
        try {
            gate.awaitReady()
            paused = true
            unavailable = true
            assertTrue(runCatching { gate.verifyRecovery() }.exceptionOrNull() is IOException)
            assertEquals(RestoreStartupState.FAILED, gate.state.value)
            assertTrue(paused)
            assertEquals(0, cleanupCount)
            unavailable = false
            gate.retry()
            gate.awaitReady()
            assertEquals(RestoreStartupState.READY, gate.state.value)
            assertFalse(paused)
            assertEquals(1, cleanupCount)
            gate.verifyRecovery()
            assertEquals(RestoreStartupState.READY, gate.state.value)
            assertEquals(1, cleanupCount)
        } finally { scope.cancel() }
    }
}
