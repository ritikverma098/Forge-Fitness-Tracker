package com.forge.workout.rest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/** A broadcast may stop waiting without cancelling a database claim that is already executing. */
internal class RestAlarmDelivery(
    private val scope: CoroutineScope,
    private val operation: suspend (Long, Long) -> Unit,
    private val onError: (Exception) -> Unit = {},
    private val timeoutMillis: Long = RestAlarmRetryPolicy.MAX_LATENESS_MS,
) {
    private data class Key(val workoutId: Long, val deadline: Long)
    private val lock = Any()
    private val active = mutableMapOf<Key, Deferred<Unit>>()

    fun deliver(workoutId: Long, expectedDeadline: Long): Deferred<Unit> = synchronized(lock) {
        val key = Key(workoutId, expectedDeadline)
        active[key]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                withTimeout(timeoutMillis) { operation(workoutId, expectedDeadline) }
            } catch (failure: Exception) {
                if (failure !is CancellationException || failure is TimeoutCancellationException) onError(failure)
                throw failure
            }
        }.also { delivery ->
            active[key] = delivery
            delivery.invokeOnCompletion {
                synchronized(lock) { if (active[key] === delivery) active.remove(key) }
            }
            delivery.start()
        }
    }
}
