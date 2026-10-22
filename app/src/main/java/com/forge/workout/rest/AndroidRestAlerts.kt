package com.forge.workout.rest

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.RestTimer

class AndroidRestAlerts(context: Context) : RestAlerts {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)

    @SuppressLint("ScheduleExactAlarm") // Permission is checked; a revocation race falls back below.
    override fun schedule(timer: RestTimer) {
        createChannel()
        notifications.cancel(NOTIFICATION_ID)
        val operation = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE,
            alarmIntent().putExtra(EXTRA_WORKOUT_ID, timer.workoutId).putExtra(EXTRA_DEADLINE, timer.deadline),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (exactAlarmsAllowed()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.deadline, operation)
                return
            } catch (_: SecurityException) {
                // Settings can revoke exact alarm access between the check and scheduling.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.deadline, operation)
    }

    override fun cancel() {
        val operation = PendingIntent.getBroadcast(
            context, ALARM_REQUEST_CODE, alarmIntent(),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(operation)
        operation.cancel()
        // Consuming a timer emits null from Room; keep its newly delivered notification visible.
    }

    override fun foregroundFeedback() {
        createChannel()
        val channel = notifications.getNotificationChannel(CHANNEL_ID)
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE || channel?.shouldVibrate() == false) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else context.getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() != true) return
        try {
            val effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else vibrator.vibrate(effect)
        } catch (_: SecurityException) {
            // A device policy may prevent vibration while workout logging remains available.
        }
    }

    @SuppressLint("MissingPermission") // notificationsEnabled checks POST_NOTIFICATIONS; notify may still race revocation.
    override fun backgroundFeedback(timer: RestTimer) {
        createChannel()
        if (!notificationsEnabled()) return
        val contentIntent = PendingIntent.getActivity(
            context, CONTENT_REQUEST_CODE,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_forge)
            .setContentTitle(context.getString(R.string.rest_alert_complete_title))
            .setContentText(context.getString(R.string.rest_alert_complete_body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(timer.deadline)
            .build()
        try {
            notifications.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked while a completion is being delivered.
        }
    }

    fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun exactAlarmsAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || try {
        alarms.canScheduleExactAlarms()
    } catch (_: SecurityException) { false }

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.rest_alert_channel_name), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.rest_alert_channel_description)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 80, 70, 80)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun alarmIntent() = Intent(context, RestAlarmReceiver::class.java).setAction(ACTION_REST_ALARM)

    companion object {
        const val CHANNEL_ID = "rest_completion"
        const val ACTION_REST_ALARM = "com.forge.workout.action.REST_COMPLETE"
        const val EXTRA_WORKOUT_ID = "workout_id"
        const val EXTRA_DEADLINE = "deadline"
        const val NOTIFICATION_ID = 2041
        private const val ALARM_REQUEST_CODE = 2040
        private const val CONTENT_REQUEST_CODE = 2041
    }
}
