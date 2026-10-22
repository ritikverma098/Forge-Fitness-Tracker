package com.forge.workout.rest

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Vibrator
import com.forge.workout.MainActivity
import com.forge.workout.R
import com.forge.workout.model.RestTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidRestAlertsHostTest {
    private lateinit var application: Application
    private lateinit var alarms: AlarmManager
    private lateinit var notifications: NotificationManager
    private lateinit var alerts: AndroidRestAlerts
    private val timer = RestTimer(workoutId = 7L, deadline = 2_000_000_000_000L, workoutName = "Private workout name")

    @Before fun setUp() {
        application = RuntimeEnvironment.getApplication()
        alarms = application.getSystemService(AlarmManager::class.java)
        notifications = application.getSystemService(NotificationManager::class.java)
        alerts = AndroidRestAlerts(application)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.VIBRATE)
        shadowOf(notifications).setNotificationsEnabled(true)
    }

    @Test fun `allowed exact alarm is immutable explicit and carries its persisted deadline`() {
        alerts.schedule(timer)

        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertTrue(alerts.exactAlarmsAllowed())
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(timer.deadline, alarm.triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
        val operation = shadowOf(alarm.operation)
        assertTrue(operation.isBroadcastIntent)
        assertTrue(operation.isImmutable)
        assertEquals(RestAlarmReceiver::class.java.name, operation.savedIntent.component!!.className)
        assertEquals(AndroidRestAlerts.ACTION_REST_ALARM, operation.savedIntent.action)
        assertEquals(timer.workoutId, operation.savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 0))
        assertEquals(timer.deadline, operation.savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_DEADLINE, 0))
    }

    @Test fun `rescheduling replaces one alarm and updates extras while clearing old feedback`() {
        alerts.schedule(timer)
        val firstOperation = shadowOf(alarms).scheduledAlarms.single().operation
        alerts.backgroundFeedback(timer)
        assertNotNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))

        val replacement = timer.copy(workoutId = 12L, deadline = timer.deadline + 15_000)
        alerts.schedule(replacement)

        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(firstOperation, alarm.operation)
        assertEquals(replacement.deadline, alarm.triggerAtMs)
        assertEquals(replacement.workoutId, shadowOf(alarm.operation).savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 0))
        assertEquals(replacement.deadline, shadowOf(alarm.operation).savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_DEADLINE, 0))
        assertNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
    }

    @Test fun `denied exact access schedules an idle-capable inexact alarm`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        alerts.schedule(timer)

        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertFalse(alerts.exactAlarmsAllowed())
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertEquals(timer.deadline, alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
    }

    @Test
    @Config(shadows = [RevokedExactAlarmShadow::class])
    fun `exact permission revocation between check and scheduling falls back safely`() {
        alerts.schedule(timer)

        val alarm = shadowOf(alarms).scheduledAlarms.single()
        assertTrue(alerts.exactAlarmsAllowed())
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
        assertTrue(alarm.isAllowWhileIdle)
    }

    @Test fun `cancelling clears the alarm but preserves already delivered completion`() {
        alerts.schedule(timer)
        alerts.backgroundFeedback(timer)
        alerts.cancel()
        alerts.cancel()

        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertNotNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
    }

    @Test fun `notification channel has gentle sound vibration and public generic completion`() {
        alerts.createChannel()
        val channel = notifications.getNotificationChannel(AndroidRestAlerts.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertEquals(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), channel.sound)
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        assertFalse(channel.canShowBadge())

        alerts.backgroundFeedback(timer)
        val notification = shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID)!!
        assertEquals(AndroidRestAlerts.CHANNEL_ID, notification.channelId)
        assertEquals(Notification.CATEGORY_ALARM, notification.category)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertNull(notification.fullScreenIntent)
        assertEquals(application.getString(R.string.rest_alert_complete_title), notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals(application.getString(R.string.rest_alert_complete_body), notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertFalse(notification.extras.toString().contains(timer.workoutName))
        val content = shadowOf(notification.contentIntent)
        assertTrue(content.isActivityIntent)
        assertTrue(content.isImmutable)
        assertEquals(MainActivity::class.java.name, content.savedIntent.component!!.className)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test fun `denied runtime permission and globally blocked notifications post nothing`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(alerts.notificationsEnabled())
        alerts.backgroundFeedback(timer)
        assertEquals(0, shadowOf(notifications).size())

        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(alerts.notificationsEnabled())
        alerts.backgroundFeedback(timer)
        assertEquals(0, shadowOf(notifications).size())
    }

    @Test fun `channel blocked by the user is preserved and does not post feedback`() {
        notifications.createNotificationChannel(NotificationChannel(AndroidRestAlerts.CHANNEL_ID, "User setting", NotificationManager.IMPORTANCE_NONE))
        alerts.createChannel()
        assertFalse(alerts.notificationsEnabled())
        alerts.backgroundFeedback(timer)
        assertEquals(NotificationManager.IMPORTANCE_NONE, notifications.getNotificationChannel(AndroidRestAlerts.CHANNEL_ID).importance)
        assertEquals(0, shadowOf(notifications).size())
    }

    @Test
    @Config(sdk = [26])
    fun `older phones can schedule exact alarms and receive a short foreground vibration`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        alerts.schedule(timer)
        assertTrue(alerts.exactAlarmsAllowed())
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, shadowOf(alarms).scheduledAlarms.single().windowLengthMs)
        val vibrator = application.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        alerts.foregroundFeedback()
        assertTrue(shadowOf(vibrator).isVibrating)
        assertEquals(50L, shadowOf(vibrator).milliseconds)
    }

    @Test
    @Config(sdk = [26])
    fun `foreground vibration respects the existing channel vibration preference`() {
        notifications.createNotificationChannel(NotificationChannel(AndroidRestAlerts.CHANNEL_ID, "User setting", NotificationManager.IMPORTANCE_DEFAULT).apply {
            enableVibration(false)
        })
        val vibrator = application.getSystemService(Vibrator::class.java)
        shadowOf(vibrator).setHasVibrator(true)
        alerts.foregroundFeedback()
        assertFalse(shadowOf(vibrator).isVibrating)
        assertFalse(notifications.getNotificationChannel(AndroidRestAlerts.CHANNEL_ID).shouldVibrate())
    }
}

@Implements(AlarmManager::class)
class RevokedExactAlarmShadow : ShadowAlarmManager() {
    @Implementation
    override fun setExactAndAllowWhileIdle(type: Int, triggerAtMillis: Long, operation: PendingIntent) {
        throw SecurityException("Exact alarm permission was revoked")
    }
}
