package com.forge.workout.rest

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.forge.workout.preferences.RestNotificationPromptState
import com.forge.workout.ui.components.NotificationPermissionAction
import com.forge.workout.ui.components.enableAction
import com.forge.workout.ui.components.restAlertPermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RestPermissionPlatformHostTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val unseen = RestNotificationPromptState.UNSEEN
    private val handled = RestNotificationPromptState.RUNTIME_HANDLED

    @Test
    @Config(sdk = [26, 32])
    fun preRuntimeApiNeverPromptsAndChecksAppNotificationSwitch() {
        AndroidRestAlerts(app).createChannel()
        shadowOf(notifications).setNotificationsEnabled(true)
        assertTrue(restAlertPermissionStatus(app).notificationsEnabled)
        assertFalse(restAlertPermissionStatus(app).canOfferStartup)
        assertEquals(NotificationPermissionAction.NONE, restAlertPermissionStatus(app).enableAction(unseen))
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(restAlertPermissionStatus(app).canOfferStartup)
        assertEquals(NotificationPermissionAction.APP_SETTINGS, restAlertPermissionStatus(app).enableAction(unseen))
    }

    @Test
    @Config(sdk = [32, 35])
    fun blockedRestChannelUsesTheChannelSettingsEvenWhenAppNotificationsAreEnabled() {
        notifications.createNotificationChannel(NotificationChannel(AndroidRestAlerts.CHANNEL_ID, "Rest",
            NotificationManager.IMPORTANCE_NONE))
        if (Build.VERSION.SDK_INT >= 33) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(true)
        val blocked = restAlertPermissionStatus(app)
        assertFalse(blocked.channelEnabled)
        assertFalse(blocked.notificationsEnabled)
        assertFalse(blocked.canOfferStartup)
        assertEquals(NotificationPermissionAction.CHANNEL_SETTINGS, blocked.enableAction(unseen))
    }

    @Test
    @Config(sdk = [33])
    fun runtimePermissionIsIndependentFromAppAndChannelChecks() {
        AndroidRestAlerts(app).createChannel()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notifications).setNotificationsEnabled(false)
        val missing = restAlertPermissionStatus(app)
        assertTrue(missing.canOfferStartup)
        assertEquals(NotificationPermissionAction.REQUEST, missing.enableAction(unseen))
        assertEquals(NotificationPermissionAction.REQUEST, missing.enableAction(RestNotificationPromptState.OFFERED))
        assertEquals(NotificationPermissionAction.APP_SETTINGS, missing.enableAction(handled))
        val previouslyDenied = missing.copy(shouldExplain = true)
        assertFalse(previouslyDenied.canOfferStartup)
        assertEquals(NotificationPermissionAction.REQUEST, previouslyDenied.enableAction(handled))
    }

    @Test
    @Config(sdk = [32, 35])
    fun preciseAlarmAccessCanChangeIndependentlyFromNotificationAccess() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(restAlertPermissionStatus(app).exactTimingEnabled)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(restAlertPermissionStatus(app).exactTimingEnabled)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(restAlertPermissionStatus(app).exactTimingEnabled)
    }
}
