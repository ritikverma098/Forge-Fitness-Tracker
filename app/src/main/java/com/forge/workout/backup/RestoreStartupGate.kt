package com.forge.workout.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RestoreStartupState { LOADING, READY, FAILED }

/** Entry-point cancellation never cancels the application's shared recovery attempt. */
internal class RestoreStartupGate(
    private val scope: CoroutineScope,
    private val recover: suspend () -> Unit,
    private val onError: (Exception) -> Unit = {},
) {
    private val _state = MutableStateFlow(RestoreStartupState.LOADING)
    val state = _state.asStateFlow()
    private var attempt: Deferred<Unit>? = null

    @Synchronized fun start(): Deferred<Unit> {
        val current = attempt
        if (current != null) return current
        val created = scope.async(start = CoroutineStart.LAZY) {
            try {
                recover()
                _state.value = RestoreStartupState.READY
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _state.value = RestoreStartupState.FAILED
                onError(failure)
                throw failure
            }
        }
        attempt = created
        created.start()
        return created
    }

    suspend fun awaitReady() { start().await() }

    @Synchronized fun retry() {
        if (_state.value != RestoreStartupState.FAILED) return
        attempt = null
        _state.value = RestoreStartupState.LOADING
        start()
    }

    /** Normal restore keeps the mounted app intact; failed compensation gates all entry points. */
    suspend fun verifyRecovery() {
        try { recover() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            synchronized(this) {
                attempt = CompletableDeferred<Unit>().apply { completeExceptionally(failure) }
                _state.value = RestoreStartupState.FAILED
            }
            onError(failure)
            throw failure
        }
    }
}
