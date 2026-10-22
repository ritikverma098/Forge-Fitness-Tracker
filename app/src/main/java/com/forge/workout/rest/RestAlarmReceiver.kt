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

class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isAlarm = action == AndroidRestAlerts.ACTION_REST_ALARM
        val isRefresh = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        if (!isAlarm && !isRefresh) return
        val workoutId = intent.getLongExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 0)
        val deadline = intent.getLongExtra(AndroidRestAlerts.EXTRA_DEADLINE, 0)
        if (isAlarm && (workoutId <= 0 || deadline <= 0)) return
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
                    if (isAlarm) application.restTimers.onAlarm(workoutId, deadline)
                    else application.restTimers.refresh()
                }
            } catch (failure: Exception) {
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
