package com.forge.workout.rest

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Build
import android.util.Log
import com.forge.workout.ForgeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isRetry = action == AndroidRestAlerts.ACTION_REST_RETRY
        val isAlarm = action == AndroidRestAlerts.ACTION_REST_ALARM || isRetry
        val isRefresh = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!isAlarm && !isRefresh) return
        val workoutId = intent.getLongExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 0)
        val deadline = intent.getLongExtra(AndroidRestAlerts.EXTRA_DEADLINE, 0)
        val attempt = if (isRetry) intent.getIntExtra(AndroidRestAlerts.EXTRA_RETRY_ATTEMPT, 0) else 0
        if (isAlarm && (workoutId <= 0 || deadline <= 0)) return
        if (isRetry && !RestAlarmRetryPolicy.eligible(workoutId, deadline, attempt, System.currentTimeMillis())) return
        val application = context.applicationContext as? ForgeApplication ?: return
        val pendingResult = goAsync()
        val wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.packageName}:rest-alarm")
        try {
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10_000)
        } catch (_: SecurityException) {
            // Delivery can still complete if device policy prevents acquiring a wake lock.
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    application.awaitStartup()
                    if (isAlarm) application.restTimers.onAlarm(workoutId, deadline)
                    else application.restTimers.refresh()
                }
            } catch (failure: Exception) {
                if (isAlarm && (failure !is CancellationException || failure is TimeoutCancellationException)) {
                    try { AndroidRestAlerts(context).scheduleRetry(workoutId, deadline, attempt) }
                    catch (schedulingFailure: Exception) { Log.w("ForgeRestAlarm", "Rest alarm retry could not be scheduled", schedulingFailure) }
                }
                Log.w("ForgeRestAlarm", "Rest alarm delivery could not complete", failure)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
