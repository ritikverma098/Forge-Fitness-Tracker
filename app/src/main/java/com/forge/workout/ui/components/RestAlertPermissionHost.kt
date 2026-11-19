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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.preferences.PreferencesRepository
import com.forge.workout.preferences.RestNotificationPromptState
import com.forge.workout.rest.AndroidRestAlerts
import java.io.IOException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

internal data class RestAlertPermissionStatus(
    val hasRuntimePermission: Boolean,
    val runtimeGranted: Boolean,
    val appEnabled: Boolean,
    val channelEnabled: Boolean,
    val exactTimingEnabled: Boolean,
    val shouldExplain: Boolean = false,
) {
    val notificationsEnabled: Boolean get() = runtimeGranted && appEnabled && channelEnabled
    val canOfferStartup: Boolean get() = hasRuntimePermission && !runtimeGranted && channelEnabled && !shouldExplain
}

internal enum class NotificationPermissionAction { REQUEST, APP_SETTINGS, CHANNEL_SETTINGS, NONE }

internal fun RestAlertPermissionStatus.enableAction(prompt: RestNotificationPromptState): NotificationPermissionAction = when {
    notificationsEnabled -> NotificationPermissionAction.NONE
    runtimeGranted && appEnabled && !channelEnabled -> NotificationPermissionAction.CHANNEL_SETTINGS
    hasRuntimePermission && !runtimeGranted && channelEnabled &&
        (prompt != RestNotificationPromptState.RUNTIME_HANDLED || shouldExplain) -> NotificationPermissionAction.REQUEST
    else -> NotificationPermissionAction.APP_SETTINGS
}

internal data class RestAlertActions(
    val status: RestAlertPermissionStatus,
    val notificationAction: NotificationPermissionAction,
    val settingsUnavailable: Boolean,
    val enableNotifications: () -> Unit,
    val openTimingSettings: () -> Unit,
    val openChannelSettings: () -> Unit,
)

private data class RestAlertSession(
    val actions: RestAlertActions,
    val prompt: RestNotificationPromptState?,
    val resumed: Boolean,
    val requestInProgress: Boolean,
)

private val LocalRestAlertActions = compositionLocalOf<RestAlertActions?> { null }

@Composable
internal fun RestAlertPermissionHost(
    preferences: PreferencesRepository,
    canOfferStartup: Boolean,
    stillSafeToOffer: () -> Boolean,
    content: @Composable (permissionUiActive: Boolean) -> Unit,
) {
    val session = rememberRestAlertSession(preferences)
    val stillSafe by rememberUpdatedState(stillSafeToOffer)
    var ownsOffer by rememberSaveable { mutableStateOf(false) }
    var claimFailed by remember { mutableStateOf(false) }
    var claimingOffer by remember { mutableStateOf(false) }
    val safe = session.resumed && canOfferStartup && session.actions.status.canOfferStartup
    val offerEligible = safe && !claimFailed && session.prompt == RestNotificationPromptState.UNSEEN
    val eligible by rememberUpdatedState(offerEligible)
    LaunchedEffect(preferences) {
        snapshotFlow { eligible }.filter { it }.collect {
            if (!stillSafe()) return@collect
            claimingOffer = true
            try {
                if (preferences.claimRestNotificationOffer()) ownsOffer = true
            } catch (_: IOException) {
                claimFailed = true
            } finally {
                claimingOffer = false
            }
        }
    }
    val showExplanation = safe && ownsOffer && session.prompt != RestNotificationPromptState.RUNTIME_HANDLED &&
        !session.requestInProgress
    CompositionLocalProvider(LocalRestAlertActions provides session.actions) {
        content(offerEligible || (safe && claimingOffer) || showExplanation || session.requestInProgress)
    }
    if (showExplanation) {
        AlertDialog(
            onDismissRequest = { ownsOffer = false },
            modifier = Modifier.testTag("rest_startup_prompt"),
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            icon = { ForgeIconTile(Icons.Rounded.Notifications, highlighted = true) },
            title = { Text(stringResource(R.string.rest_startup_title)) },
            text = {
                Text(stringResource(R.string.rest_startup_explanation),
                    modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
            },
            dismissButton = {
                TextButton(onClick = { ownsOffer = false }, modifier = Modifier.testTag("rest_startup_later")) {
                    Text(stringResource(R.string.rest_startup_not_now))
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (stillSafe()) {
                        ownsOffer = false
                        session.actions.enableNotifications()
                    }
                }, modifier = Modifier.testTag("rest_startup_enable")) {
                    Text(stringResource(R.string.rest_startup_enable))
                }
            },
        )
    }
}

@Composable
internal fun rememberRestAlertActions(): RestAlertActions {
    LocalRestAlertActions.current?.let { return it }
    // A screen hosted on its own for a preview or a component test still has manual controls.
    return rememberRestAlertSession(preferences = null).actions
}

@Composable
private fun rememberRestAlertSession(preferences: PreferencesRepository?): RestAlertSession {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    fun readStatus(): RestAlertPermissionStatus {
        val status = restAlertPermissionStatus(context)
        val shouldExplain = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !status.runtimeGranted && activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        return status.copy(shouldExplain = shouldExplain)
    }

    val prompt = preferences?.restNotificationPrompt?.collectAsStateWithLifecycle(initialValue = null)?.value
    val currentPrompt by rememberUpdatedState(prompt)
    var status by remember(context, activity) { mutableStateOf(readStatus()) }
    var runtimeHandled by rememberSaveable { mutableStateOf(false) }
    var requestInProgress by rememberSaveable { mutableStateOf(false) }
    var settingsUnavailable by rememberSaveable { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    fun recordRuntimeHandled() {
        runtimeHandled = true
        if (preferences != null && currentPrompt != RestNotificationPromptState.RUNTIME_HANDLED) {
            scope.launch {
                try { preferences.markRestNotificationRuntimeHandled() } catch (_: IOException) { }
            }
        }
    }
    val permissionContract = remember { ActivityResultContracts.RequestPermission() }
    val launcher = rememberLauncherForActivityResult(permissionContract) {
        requestInProgress = false
        recordRuntimeHandled()
        status = readStatus()
    }
    LifecycleResumeEffect(context, activity) {
        status = readStatus()
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    // Record a previous grant or denial as soon as Android exposes evidence of it.
    LaunchedEffect(preferences, status, prompt) {
        if (status.hasRuntimePermission && (status.runtimeGranted || status.shouldExplain) &&
            prompt != RestNotificationPromptState.RUNTIME_HANDLED && !runtimeHandled) {
            recordRuntimeHandled()
        }
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
    val previousAttempt = if (runtimeHandled) RestNotificationPromptState.RUNTIME_HANDLED
        else prompt ?: RestNotificationPromptState.UNSEEN
    val actions = remember(context, activity, launcher, status, settingsUnavailable, previousAttempt) {
        RestAlertActions(
            status = status,
            notificationAction = status.enableAction(previousAttempt),
            settingsUnavailable = settingsUnavailable,
            enableNotifications = enable@{
                if (requestInProgress || activity == null || !resumed) return@enable
                settingsUnavailable = false
                status = readStatus()
                val remembered = if (runtimeHandled) RestNotificationPromptState.RUNTIME_HANDLED
                    else currentPrompt ?: RestNotificationPromptState.UNSEEN
                when (status.enableAction(remembered)) {
                    NotificationPermissionAction.REQUEST -> {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@enable
                        requestInProgress = true
                        try {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            recordRuntimeHandled()
                        } catch (_: ActivityNotFoundException) {
                            requestInProgress = false
                            openAppNotifications()
                        } catch (_: SecurityException) {
                            requestInProgress = false
                            openAppNotifications()
                        }
                    }
                    NotificationPermissionAction.APP_SETTINGS -> openAppNotifications()
                    NotificationPermissionAction.CHANNEL_SETTINGS -> openChannelNotifications()
                    NotificationPermissionAction.NONE -> Unit
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
    return RestAlertSession(actions, if (runtimeHandled) RestNotificationPromptState.RUNTIME_HANDLED
        else prompt, resumed, requestInProgress)
}

internal fun restAlertPermissionStatus(context: Context): RestAlertPermissionStatus {
    val hasRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val runtimeGranted = !hasRuntimePermission ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val notifications = context.getSystemService(NotificationManager::class.java)
    val channelEnabled = notifications?.getNotificationChannel(AndroidRestAlerts.CHANNEL_ID)?.importance !=
        NotificationManager.IMPORTANCE_NONE
    val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || try {
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    } catch (_: SecurityException) { false }
    return RestAlertPermissionStatus(hasRuntimePermission, runtimeGranted,
        NotificationManagerCompat.from(context).areNotificationsEnabled(), channelEnabled, exact)
}
