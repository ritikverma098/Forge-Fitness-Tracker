package com.forge.workout.rest

import com.forge.workout.model.RestTimer

interface RestAlerts {
    fun schedule(timer: RestTimer)
    /** Cancels the pending alarm, without removing a notification already delivered. */
    fun cancel()
    fun foregroundFeedback()
    fun backgroundFeedback(timer: RestTimer)
}
