package com.forge.workout.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.forge.workout.R

@Composable
fun RestAlertSettings(modifier: Modifier = Modifier, enabled: Boolean = true) {
    val actions = rememberRestAlertActions()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.rest_alert_settings_description), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ForgeIconTile(
                if (actions.status.notificationsEnabled) Icons.Rounded.Notifications else Icons.Rounded.NotificationsOff,
                highlighted = actions.status.notificationsEnabled,
            )
            Text(stringResource(if (actions.status.notificationsEnabled) R.string.rest_alert_notifications_enabled
                else R.string.rest_alert_notifications_disabled),
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleSmall)
        }
        if (!actions.status.notificationsEnabled) {
            val settingsMessage = when (actions.notificationAction) {
                NotificationPermissionAction.CHANNEL_SETTINGS -> R.string.rest_alert_channel_disabled
                NotificationPermissionAction.APP_SETTINGS -> R.string.rest_alert_application_disabled
                else -> null
            }
            settingsMessage?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = actions.enableNotifications, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("rest_enable_notifications")) {
                Text(stringResource(if (settingsMessage == null) R.string.rest_alert_enable_notifications
                    else R.string.rest_alert_open_notification_settings))
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
                    Text(stringResource(if (actions.notificationAction == NotificationPermissionAction.REQUEST)
                        R.string.rest_alert_workout_allow else R.string.settings))
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
