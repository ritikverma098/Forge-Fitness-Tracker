package com.forge.workout.rest

import com.forge.workout.model.RestTimer
import com.forge.workout.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room's absolute deadline is shared by foreground expiry and the Android alarm receiver. */
class RestTimerCoordinator(
    private val repository: WorkoutRepository,
    private val alerts: RestAlerts,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val screenUsable: () -> Boolean = { true },
    private val onError: (Exception) -> Unit = {},
) {
    private val mutex = Mutex()
    private var observation: Job? = null
    private var foregroundExpiry: Job? = null
    private var scheduled: RestTimer? = null
    private var restoring = false
    @Volatile private var resumedAt: Long? = null

    fun start() {
        if (observation?.isActive == true) return
        observation = launchSafely {
            repository.observeRestTimer().distinctUntilChanged { old, new ->
                old?.workoutId == new?.workoutId && old?.deadline == new?.deadline
            }.collect {
                mutex.withLock {
                    if (!restoring) reconcile(repository.restTimer(), fromLiveChange = true)
                }
            }
        }
    }

    fun setForeground(resumed: Boolean) {
        // Update before dispatch so a receiver cannot mistake an old expiry for a resumed timer.
        resumedAt = if (resumed) clock() else null
        foregroundExpiry?.cancel()
        if (resumed) {
            start()
            launchSafely { refresh() }
        }
    }

    /** Reconcile after resume, boot, package update, permission changes or restore. */
    suspend fun refresh() = mutex.withLock {
        if (!restoring) reconcile(repository.restTimer(), fromLiveChange = false, forceSchedule = true)
    }

    suspend fun pauseForRestore() = mutex.withLock {
        restoring = true
        foregroundExpiry?.cancel()
        foregroundExpiry = null
        scheduled = null
        cancelPendingAlarm()
    }

    suspend fun resumeAfterRestore() {
        try {
            mutex.withLock {
                restoring = false
                reconcile(repository.restTimer(), fromLiveChange = false, forceSchedule = true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    suspend fun onAlarm(workoutId: Long, expectedDeadline: Long) = mutex.withLock {
        if (restoring) return@withLock
        val timer = repository.restTimer() ?: return@withLock
        if (timer.workoutId != workoutId || timer.deadline != expectedDeadline) return@withLock
        if (timer.deadline > clock()) {
            reconcile(timer, fromLiveChange = false, forceSchedule = true)
        } else {
            consume(timer, allowFeedback = true)
        }
    }

    private suspend fun reconcile(timer: RestTimer?, fromLiveChange: Boolean, forceSchedule: Boolean = false) {
        val previous = scheduled
        foregroundExpiry?.cancel()
        foregroundExpiry = null
        if (timer == null) {
            scheduled = null
            cancelPendingAlarm()
            return
        }
        if (timer.deadline <= clock()) {
            // A deliberate -15 adjustment may end a live timer now. Imported/old deadlines are silent.
            val liveAdjustment = fromLiveChange && previous?.workoutId == timer.workoutId &&
                timer.deadline != previous.deadline && canGiveForegroundFeedback(timer)
            // A cold-started alarm receiver must be allowed to claim its due alarm before any
            // observer clears it. Resume/boot/restore reconciliation can discard old deadlines.
            if (liveAdjustment || !fromLiveChange || resumedAt != null) {
                consume(timer, allowFeedback = liveAdjustment)
            } else scheduled = timer
            return
        }
        scheduled = timer
        if (forceSchedule || previous?.workoutId != timer.workoutId || previous.deadline != timer.deadline) {
            try { alerts.schedule(timer) }
            catch (failure: Exception) { onError(failure) }
        }
        if (resumedAt != null && screenUsable()) {
            foregroundExpiry = launchSafely {
                delay((timer.deadline - clock()).coerceAtLeast(0))
                onAlarm(timer.workoutId, timer.deadline)
            }
        }
    }

    private suspend fun consume(timer: RestTimer, allowFeedback: Boolean) {
        // The conditional UPDATE is the single winner across receiver, UI and lifecycle races.
        if (!repository.consumeRestTimer(timer.workoutId, timer.deadline, clock())) return
        scheduled = null
        cancelPendingAlarm()
        if (!allowFeedback) return
        if (canGiveForegroundFeedback(timer)) alerts.foregroundFeedback()
        else if (resumedAt == null || !screenUsable()) alerts.backgroundFeedback(timer)
        // Returning to Forge after expiry never delivers an old foreground vibration.
    }

    private fun canGiveForegroundFeedback(timer: RestTimer): Boolean {
        val since = resumedAt ?: return false
        val now = clock()
        return screenUsable() && timer.deadline >= since && now >= timer.deadline &&
            now - timer.deadline <= MAX_FOREGROUND_LATENESS_MS
    }

    private fun launchSafely(action: suspend () -> Unit): Job = scope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onError(failure) }
    }

    private fun cancelPendingAlarm() {
        try { alerts.cancel() }
        catch (failure: Exception) { onError(failure) }
    }

    private companion object { const val MAX_FOREGROUND_LATENESS_MS = 2_000L }
}
