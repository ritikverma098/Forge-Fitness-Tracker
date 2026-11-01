package com.forge.workout.rest

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import com.forge.workout.model.RestTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
// These compatibility fields have no pending-intent accessor in Robolectric 4.17.
@Suppress("DEPRECATION")
class RestAlarmRetryHostTest {
    private lateinit var application: Application
    private lateinit var alarms: AlarmManager
    private lateinit var notifications: NotificationManager
    private lateinit var alerts: AndroidRestAlerts
    private val timer = RestTimer(7, 2_000_000_000_000L, "Private workout")
    private val now get() = timer.deadline + 8_000

    @Before fun prepare() {
        application = RuntimeEnvironment.getApplication()
        alarms = application.getSystemService(AlarmManager::class.java)
        notifications = application.getSystemService(NotificationManager::class.java)
        alerts = AndroidRestAlerts(application)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowAlarmManager.setAutoSchedule(false)
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
    }

    @Test fun `retry is an immutable separate alarm carrying the original deadline and bounded attempt`() {
        alerts.schedule(timer)
        val original = shadowOf(alarms).scheduledAlarms.single().operation
        assertTrue(alerts.scheduleRetry(timer.workoutId, timer.deadline, 0, now))
        val scheduled = shadowOf(alarms).scheduledAlarms
        assertEquals(2, scheduled.size)
        val primary = scheduled.single { shadowOf(it.operation).savedIntent.action == AndroidRestAlerts.ACTION_REST_ALARM }
        val retry = scheduled.single { shadowOf(it.operation).savedIntent.action == AndroidRestAlerts.ACTION_REST_RETRY }
        assertEquals(original, primary.operation)
        assertEquals(timer.deadline, primary.triggerAtMs)
        assertNotEquals(primary.operation, retry.operation)
        assertEquals(now + 5_000, retry.triggerAtMs)
        assertTrue(retry.isAllowWhileIdle)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, retry.windowLengthMs)
        val operation = shadowOf(retry.operation)
        assertTrue(operation.isBroadcastIntent)
        assertTrue(operation.isImmutable)
        assertEquals(RestAlarmReceiver::class.java.name, operation.savedIntent.component!!.className)
        assertEquals(timer.workoutId, operation.savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 0))
        assertEquals(timer.deadline, operation.savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_DEADLINE, 0))
        assertEquals(1, operation.savedIntent.getIntExtra(AndroidRestAlerts.EXTRA_RETRY_ATTEMPT, 0))
    }

    @Test fun `a late old retry cannot replace a new primary or remove visible completion`() {
        val replacement = timer.copy(workoutId = 9, deadline = timer.deadline + 60_000)
        alerts.schedule(replacement)
        val primary = shadowOf(alarms).scheduledAlarms.single().operation
        alerts.backgroundFeedback(timer)
        assertNotNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
        alerts.scheduleRetry(timer.workoutId, timer.deadline, 0, now)
        val remainingPrimary = shadowOf(alarms).scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == AndroidRestAlerts.ACTION_REST_ALARM
        }
        assertEquals(primary, remainingPrimary.operation)
        assertEquals(replacement.deadline, remainingPrimary.triggerAtMs)
        assertEquals(replacement.workoutId,
            shadowOf(remainingPrimary.operation).savedIntent.getLongExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 0))
        assertNotNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
    }

    @Test fun `starting a replacement cancels the pending retry and schedules only its own deadline`() {
        alerts.scheduleRetry(timer.workoutId, timer.deadline, 0, now)
        val replacement = timer.copy(deadline = timer.deadline + 90_000)
        alerts.schedule(replacement)
        val only = shadowOf(alarms).scheduledAlarms.single()
        assertEquals(AndroidRestAlerts.ACTION_REST_ALARM, shadowOf(only.operation).savedIntent.action)
        assertEquals(replacement.deadline, only.triggerAtMs)
    }

    @Test fun `skip cancels a lone retry even when the regular alarm is absent`() {
        alerts.scheduleRetry(timer.workoutId, timer.deadline, 0, now)
        alerts.backgroundFeedback(timer)
        alerts.cancel()
        alerts.cancel()
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        assertNotNull(shadowOf(notifications).getNotification(AndroidRestAlerts.NOTIFICATION_ID))
    }

    @Test fun `denied exact access uses the existing idle capable fallback for retries`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertTrue(alerts.scheduleRetry(timer.workoutId, timer.deadline, 0, now))
        val retry = shadowOf(alarms).scheduledAlarms.single()
        assertTrue(retry.isAllowWhileIdle)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, retry.windowLengthMs)
        assertEquals(now + 5_000, retry.triggerAtMs)
    }

    @Test
    @Config(shadows = [RevokedExactAlarmShadow::class])
    fun `retry scheduling falls back if exact access is revoked during the call`() {
        assertTrue(alerts.scheduleRetry(timer.workoutId, timer.deadline, 0, now))
        val retry = shadowOf(alarms).scheduledAlarms.single()
        assertTrue(retry.isAllowWhileIdle)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, retry.windowLengthMs)
    }

    @Test fun `invalid future exhausted old and overflowing retries do not schedule any work`() {
        val rejected = listOf(
            longArrayOf(0, timer.deadline, 0, now),
            longArrayOf(timer.workoutId, 0, 0, now),
            longArrayOf(timer.workoutId, timer.deadline, -1, now),
            longArrayOf(timer.workoutId, timer.deadline, 12, now),
            longArrayOf(timer.workoutId, timer.deadline, 0, timer.deadline - 1),
            longArrayOf(timer.workoutId, timer.deadline, 0, timer.deadline + 180_001),
            longArrayOf(timer.workoutId, timer.deadline, 0, timer.deadline + 179_999),
            longArrayOf(timer.workoutId, Long.MAX_VALUE - 1_000, 0, Long.MAX_VALUE - 900),
        )
        rejected.forEach { values ->
            assertFalse(alerts.scheduleRetry(values[0], values[1], values[2].toInt(), values[3]))
        }
        assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
    }

    @Test fun `retry chain replaces only its own delivery alarm and stops at the attempt bound`() {
        alerts.schedule(timer)
        for (attempt in 0 until 12) assertTrue(alerts.scheduleRetry(timer.workoutId, timer.deadline, attempt, now))
        assertFalse(alerts.scheduleRetry(timer.workoutId, timer.deadline, 12, now))
        val scheduled = shadowOf(alarms).scheduledAlarms
        assertEquals(2, scheduled.size)
        val retry = scheduled.single { shadowOf(it.operation).savedIntent.action == AndroidRestAlerts.ACTION_REST_RETRY }
        assertEquals(12, shadowOf(retry.operation).savedIntent.getIntExtra(AndroidRestAlerts.EXTRA_RETRY_ATTEMPT, 0))
        assertTrue(RestAlarmRetryPolicy.eligible(timer.workoutId, timer.deadline, 12, now))
        assertFalse(RestAlarmRetryPolicy.eligible(timer.workoutId, timer.deadline, 13, now))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = RestReceiverProbeApplication::class)
class RestAlarmRetryInputHostTest {
    @Test fun `malformed exhausted future and stale retry broadcasts return before goAsync`() {
        val application = RuntimeEnvironment.getApplication() as RestReceiverProbeApplication
        val now = System.currentTimeMillis()
        val invalid = listOf(
            longArrayOf(0, now, 1), longArrayOf(1, 0, 1), longArrayOf(1, now, 0),
            longArrayOf(1, now, -1), longArrayOf(1, now, 13),
            longArrayOf(1, now + 60_000, 1), longArrayOf(1, now - 180_001, 1),
        )
        invalid.forEach { values ->
            val receiver = RestAlarmReceiver()
            receiver.onReceive(application, Intent(AndroidRestAlerts.ACTION_REST_RETRY)
                .putExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, values[0])
                .putExtra(AndroidRestAlerts.EXTRA_DEADLINE, values[1])
                .putExtra(AndroidRestAlerts.EXTRA_RETRY_ATTEMPT, values[2].toInt()))
            assertFalse(shadowOf(receiver).wentAsync())
        }
        val wrongType = RestAlarmReceiver()
        wrongType.onReceive(application, Intent(AndroidRestAlerts.ACTION_REST_RETRY)
            .putExtra(AndroidRestAlerts.EXTRA_WORKOUT_ID, 1L).putExtra(AndroidRestAlerts.EXTRA_DEADLINE, now)
            .putExtra(AndroidRestAlerts.EXTRA_RETRY_ATTEMPT, "not an attempt"))
        assertFalse(shadowOf(wrongType).wentAsync())
        assertFalse(application.consumeEntered.isCompleted)
    }
}
