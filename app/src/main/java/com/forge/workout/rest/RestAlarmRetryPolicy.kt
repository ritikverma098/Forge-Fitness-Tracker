package com.forge.workout.rest

internal data class RestAlarmRetry(val workoutId: Long, val deadline: Long, val attempt: Int, val triggerAtMillis: Long)

/** Retry delivery of the original deadline; this never creates or extends a workout's rest. */
internal object RestAlarmRetryPolicy {
    const val DELAY_MS = 5_000L
    const val MAX_ATTEMPTS = 12
    const val MAX_LATENESS_MS = 180_000L

    fun eligible(workoutId: Long, deadline: Long, attempt: Int, now: Long): Boolean =
        attempt in 1..MAX_ATTEMPTS && recentDeadline(workoutId, deadline, now)

    fun next(workoutId: Long, deadline: Long, previousAttempt: Int, now: Long): RestAlarmRetry? {
        if (previousAttempt !in 0 until MAX_ATTEMPTS || !recentDeadline(workoutId, deadline, now) ||
            now > Long.MAX_VALUE - DELAY_MS) return null
        val triggerAt = now + DELAY_MS
        if (triggerAt - deadline > MAX_LATENESS_MS) return null
        return RestAlarmRetry(workoutId, deadline, previousAttempt + 1, triggerAt)
    }

    private fun recentDeadline(workoutId: Long, deadline: Long, now: Long): Boolean =
        workoutId > 0 && deadline > 0 && now >= deadline && now - deadline <= MAX_LATENESS_MS
}
