package com.forge.workout.ui.components

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.forge.workout.R
import com.forge.workout.rest.AndroidRestAlerts

/** Android permissions are requested only from an explicit button press. */
@Composable
fun RestAlertSettings(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val actions = rememberRestAlertActions()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.rest_alert_settings_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (actions.status.notificationsEnabled) R.string.rest_alert_notifications_enabled
            else R.string.rest_alert_notifications_disabled), style = MaterialTheme.typography.bodyMedium)
        if (!actions.status.notificationsEnabled) {
            OutlinedButton(onClick = actions.enableNotifications, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rest_enable_notifications")) {
                Text(stringResource(R.string.rest_alert_enable_notifications))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text(stringResource(if (actions.status.exactTimingEnabled) R.string.rest_alert_exact_enabled
                else R.string.rest_alert_inexact_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = actions.openTimingSettings, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rest_allow_exact_timing")) {
                Text(stringResource(if (actions.status.exactTimingEnabled) R.string.rest_alert_timing_settings
                    else R.string.rest_alert_allow_exact_timing))
            }
        }
        TextButton(onClick = actions.openChannelSettings, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("rest_channel_settings")) {
            Text(stringResource(R.string.rest_alert_sound_vibration))
        }
        Text(stringResource(R.string.rest_alert_channel_settings_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actions.settingsUnavailable) {
            Text(stringResource(R.string.rest_alert_settings_unavailable), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The caller shows this beside an active timer; denied permission never interrupts logging. */
@Composable
fun RestNotificationPermissionHint(modifier: Modifier = Modifier) {
    val actions = rememberRestAlertActions()
    if (!actions.status.notificationsEnabled) {
        Column(modifier.fillMaxWidth().testTag("rest_notification_hint")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rest_alert_workout_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.enableNotifications, modifier = Modifier.testTag("rest_hint_enable_notifications")) {
                    Text(stringResource(R.string.rest_alert_workout_allow))
                }
            }
            if (actions.settingsUnavailable) {
                Text(stringResource(R.string.rest_alert_settings_unavailable), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !actions.status.exactTimingEnabled) {
        Row(modifier.fillMaxWidth().testTag("rest_timing_hint"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rest_alert_workout_inexact), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.openTimingSettings, modifier = Modifier.testTag("rest_hint_allow_exact_timing")) {
                Text(stringResource(R.string.rest_alert_workout_timing))
            }
        }
    }
}

private data class RestAlertPermissionStatus(
    val runtimeGranted: Boolean,
    val appEnabled: Boolean,
    val channelEnabled: Boolean,
    val exactTimingEnabled: Boolean,
) {
    val notificationsEnabled: Boolean get() = runtimeGranted && appEnabled && channelEnabled
}

private data class RestAlertActions(
    val status: RestAlertPermissionStatus,
    val settingsUnavailable: Boolean,
    val enableNotifications: () -> Unit,
    val openTimingSettings: () -> Unit,
    val openChannelSettings: () -> Unit,
)

@Composable
private fun rememberRestAlertActions(): RestAlertActions {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var status by remember(context) { mutableStateOf(restAlertPermissionStatus(context)) }
    var requestedInSession by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestedInSession = true
        status = restAlertPermissionStatus(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        status = restAlertPermissionStatus(context)
    }
    fun openSystemSettings(intent: Intent) {
        settingsUnavailable = false
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            settingsUnavailable = true
        } catch (_: SecurityException) {
            settingsUnavailable = true
        }
    }
    fun openAppNotifications() = openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    fun openChannelNotifications() = openSystemSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, AndroidRestAlerts.CHANNEL_ID))
    return RestAlertActions(
        status = status,
        settingsUnavailable = settingsUnavailable,
        enableNotifications = {
            settingsUnavailable = false
            val current = restAlertPermissionStatus(context)
            status = current
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !current.runtimeGranted && activity != null &&
                (!requestedInSession || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
                requestedInSession = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (current.runtimeGranted && current.appEnabled && !current.channelEnabled) {
                openChannelNotifications()
            } else {
                openAppNotifications()
            }
        },
        openTimingSettings = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSystemSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()))
            }
        },
        openChannelSettings = ::openChannelNotifications,
    )
}

private fun restAlertPermissionStatus(context: Context): RestAlertPermissionStatus {
    val runtime = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notifications = context.getSystemService(NotificationManager::class.java)
    val channelEnabled = notifications?.getNotificationChannel(AndroidRestAlerts.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    return RestAlertPermissionStatus(runtime, NotificationManagerCompat.from(context).areNotificationsEnabled(), channelEnabled, exact)
}
