package com.forge.workout.rest

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delivery belongs to the app, while a broadcast is only a short-lived waiter. */
@OptIn(ExperimentalCoroutinesApi::class)
class RestAlarmDeliveryTest {
    @Test fun `receiver await timeout does not cancel the database claim or its feedback`() = runTest {
        withApplicationScope { scope ->
            val release = CompletableDeferred<Unit>()
            var operationExited = false
            var feedbackCount = 0
            val delivery = RestAlarmDelivery(scope, operation = { _, _ ->
                try {
                    release.await()
                    feedbackCount++
                } finally { operationExited = true }
            })
            val pending = delivery.deliver(1, 10_000)
            var receiverTimedOut = false
            val receiver = launch {
                try { withTimeout(8_000) { pending.await() } }
                catch (_: TimeoutCancellationException) { receiverTimedOut = true }
            }
            runCurrent()

            advanceTimeBy(8_000)
            runCurrent()
            assertTrue(receiverTimedOut)
            assertTrue(receiver.isCompleted)
            assertTrue("The app-owned operation must survive the receiver", pending.isActive)
            assertFalse(operationExited)
            assertEquals(0, feedbackCount)

            release.complete(Unit)
            runCurrent()
            pending.await()
            assertTrue(operationExited)
            assertEquals(1, feedbackCount)
        }
    }

    @Test fun `duplicate broadcasts share one active delivery for the same deadline`() = runTest {
        withApplicationScope { scope ->
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            val delivery = RestAlarmDelivery(scope, operation = { _, _ ->
                attempts++
                release.await()
            })
            val first = delivery.deliver(7, 20_000)
            val duplicate = delivery.deliver(7, 20_000)
            assertSame(first, duplicate)
            runCurrent()
            assertEquals(1, attempts)

            release.complete(Unit)
            runCurrent()
            first.await()
            duplicate.await()
            assertEquals(1, attempts)
        }
    }

    @Test fun `a replacement deadline has an independent delivery identity`() = runTest {
        withApplicationScope { scope ->
            val release = CompletableDeferred<Unit>()
            val attempts = mutableListOf<Pair<Long, Long>>()
            val delivery = RestAlarmDelivery(scope, operation = { id, deadline ->
                attempts += id to deadline
                release.await()
            })
            val previous = delivery.deliver(7, 20_000)
            val replacement = delivery.deliver(7, 35_000)
            assertNotSame(previous, replacement)
            runCurrent()
            assertEquals(setOf(7L to 20_000L, 7L to 35_000L), attempts.toSet())

            release.complete(Unit)
            runCurrent()
            previous.await()
            replacement.await()
            assertEquals(2, attempts.size)
        }
    }

    @Test fun `completed delivery does not leave a stale cached result`() = runTest {
        withApplicationScope { scope ->
            var attempts = 0
            val delivery = RestAlarmDelivery(scope, operation = { _, _ -> attempts++ })
            val first = delivery.deliver(7, 20_000)
            runCurrent()
            first.await()
            val next = delivery.deliver(7, 20_000)
            assertNotSame(first, next)
            runCurrent()
            next.await()
            assertEquals(2, attempts)
        }
    }

    @Test fun `failure is reported after all receiver waiters have departed`() = runTest {
        withApplicationScope { scope ->
            val release = CompletableDeferred<Unit>()
            val failure = IOException("Controlled delivery failure")
            val errors = mutableListOf<Exception>()
            val delivery = RestAlarmDelivery(scope, operation = { _, _ ->
                release.await()
                throw failure
            }, onError = { errors += it })
            val pending = delivery.deliver(7, 20_000)
            runCurrent()
            // No coroutine awaits this Deferred when it fails.
            release.complete(Unit)
            runCurrent()
            assertTrue(pending.isCompleted)
            assertEquals(1, errors.size)
            assertTrue(errors.single() is IOException)
            assertEquals(failure.message, errors.single().message)
            val propagated = runCatching { pending.await() }.exceptionOrNull()
            assertTrue(propagated is IOException)
            assertEquals(failure.message, propagated?.message)
        }
    }

    @Test fun `the app-owned delivery itself still has a finite lifetime`() = runTest {
        withApplicationScope { scope ->
            val neverReleased = CompletableDeferred<Unit>()
            val errors = mutableListOf<Exception>()
            var operationCancelled = false
            val delivery = RestAlarmDelivery(scope, operation = { _, _ ->
                try { neverReleased.await() }
                finally { operationCancelled = true }
            }, onError = { errors += it }, timeoutMillis = 20_000)
            val pending = delivery.deliver(7, 20_000)
            runCurrent()
            advanceTimeBy(19_999)
            runCurrent()
            assertTrue(pending.isActive)
            assertTrue(errors.isEmpty())

            advanceTimeBy(1)
            runCurrent()
            assertTrue(operationCancelled)
            assertTrue(pending.isCancelled)
            assertEquals(1, errors.size)
            assertTrue(errors.single() is TimeoutCancellationException)
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is TimeoutCancellationException)
        }
    }

    private suspend fun TestScope.withApplicationScope(block: suspend (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try { block(scope) }
        finally { scope.cancel(); runCurrent() }
    }
}
