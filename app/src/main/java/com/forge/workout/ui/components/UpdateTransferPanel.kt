package com.forge.workout.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.forge.workout.R
import com.forge.workout.updates.ApkUpdateCoordinator
import com.forge.workout.updates.ApkUpdateState
import com.forge.workout.updates.GitHubReleaseSource
import com.forge.workout.updates.UpdateFailure
import com.forge.workout.updates.UpdatePhase
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Progress is observed here, never by the workout list or its parent navigation. */
@Composable
internal fun UpdateTransferPanel(manager: ApkUpdateCoordinator, enabled: Boolean, canInstall: Boolean) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var settingsUnavailable by remember { mutableStateOf(false) }
    var migrationLinkUnavailable by remember { mutableStateOf(false) }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.refresh()
    }
    UpdateTransferContent(state, enabled, canInstall,
        onCancel = { manager.cancelDownload() },
        onRetryDownload = { manager.retryDownload() },
        onInstall = { manager.install() },
        onOpenSettings = {
            settingsUnavailable = false
            try {
                settings.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()))
            } catch (_: ActivityNotFoundException) { settingsUnavailable = true }
            catch (_: SecurityException) { settingsUnavailable = true }
        },
        onDiscard = { manager.discard() },
        onMigrationGuide = { migrationLinkUnavailable = !openUpdateLink(uriHandler, GitHubReleaseSource.SIGNING_GUIDE_URL) },
        settingsUnavailable = settingsUnavailable,
        migrationLinkUnavailable = migrationLinkUnavailable)
}

@Composable
internal fun UpdateTransferContent(
    state: ApkUpdateState,
    enabled: Boolean,
    canInstall: Boolean,
    onCancel: () -> Unit,
    onRetryDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onDiscard: () -> Unit,
    settingsUnavailable: Boolean = false,
    onMigrationGuide: () -> Unit = {},
    migrationLinkUnavailable: Boolean = false,
) {
    if (state.phase == UpdatePhase.IDLE || (state.phase == UpdatePhase.PREPARING && state.version == null)) return
    val downloading = state.phase in setOf(UpdatePhase.PREPARING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    val waiting = state.phase in setOf(UpdatePhase.HANDED_TO_INSTALLER, UpdatePhase.APPROVAL_READY)
    val retrying = state.phase in setOf(UpdatePhase.CANCELLED, UpdatePhase.FAILED)
    val status = when (state.phase) {
        UpdatePhase.PREPARING -> R.string.update_transfer_preparing
        UpdatePhase.DOWNLOADING -> R.string.update_transfer_downloading
        UpdatePhase.VERIFYING -> R.string.update_transfer_verifying
        UpdatePhase.READY -> R.string.update_transfer_ready
        UpdatePhase.PERMISSION_REQUIRED -> R.string.update_transfer_permission
        UpdatePhase.STAGING -> R.string.update_transfer_staging
        UpdatePhase.HANDED_TO_INSTALLER, UpdatePhase.APPROVAL_READY -> R.string.update_transfer_handed
        UpdatePhase.INSTALLED -> R.string.update_transfer_installed
        UpdatePhase.CANCELLED -> if (state.verified) R.string.update_transfer_install_cancelled else R.string.update_transfer_cancelled
        else -> R.string.update_transfer_failed
    }
    Column(Modifier.fillMaxWidth().testTag("update_transfer_panel"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ForgePanel(Modifier.fillMaxWidth(), emphasized = state.phase == UpdatePhase.READY) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val icon = when (state.phase) {
                    UpdatePhase.FAILED -> Icons.Rounded.ErrorOutline
                    UpdatePhase.READY, UpdatePhase.INSTALLED -> Icons.Rounded.CheckCircle
                    else -> Icons.Rounded.SystemUpdate
                }
                ForgeIconTile(icon, highlighted = state.phase != UpdatePhase.FAILED)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(status), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleSmall)
                    if (state.version != null) Text(stringResource(R.string.update_transfer_version, state.version),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.phase == UpdatePhase.DOWNLOADING) {
            DownloadProgress(state)
        } else if (state.phase in setOf(UpdatePhase.PREPARING, UpdatePhase.VERIFYING, UpdatePhase.STAGING)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.phase == UpdatePhase.FAILED) Text(stringResource(failureMessage(state.failure)),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        if (downloading) {
            TextButton(onClick = onCancel, enabled = enabled) { Text(stringResource(R.string.cancel)) }
        } else if (state.phase == UpdatePhase.FAILED && state.failure == UpdateFailure.SIGNATURE_MISMATCH) {
            TextButton(onClick = onMigrationGuide, enabled = enabled) {
                Text(stringResource(R.string.update_transfer_migration_guide))
            }
        } else if (state.phase == UpdatePhase.PERMISSION_REQUIRED) {
            Text(stringResource(R.string.update_transfer_permission_explanation))
            Button(onClick = onOpenSettings, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_transfer_allow))
            }
        } else if (state.phase == UpdatePhase.READY || (retrying && state.verified) || waiting) {
            if (waiting) Text(stringResource(R.string.update_transfer_handed_explanation))
            Button(onClick = onInstall, enabled = enabled && canInstall, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (retrying || waiting) R.string.update_transfer_retry_install else R.string.update_transfer_install))
            }
            if (!canInstall) Text(stringResource(R.string.update_transfer_install_later), style = MaterialTheme.typography.bodySmall)
        } else if (retrying && state.version != null) {
            Button(onClick = onRetryDownload, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.update_transfer_retry_download))
            }
        } else if (state.phase == UpdatePhase.FAILED) {
            Text(stringResource(R.string.update_transfer_check_again))
        }
        if (state.verified || (state.phase == UpdatePhase.FAILED && state.version == null)) {
            TextButton(onClick = onDiscard, enabled = enabled && state.phase != UpdatePhase.STAGING) {
                Text(stringResource(R.string.update_transfer_discard))
            }
        }
        if (settingsUnavailable) Text(stringResource(R.string.update_transfer_settings_failed),
            color = MaterialTheme.colorScheme.error)
        if (migrationLinkUnavailable) Text(stringResource(R.string.update_open_link_failed),
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DownloadProgress(state: ApkUpdateState) {
    val context = LocalContext.current
    val total = state.total?.takeIf { it > 0 }
    val bytes = state.bytes.coerceAtLeast(0)
    if (total != null) {
        val fraction = (bytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        val percent = (fraction * 100).toInt()
        val description = stringResource(R.string.update_transfer_progress_accessible,
            Formatter.formatFileSize(context, bytes), Formatter.formatFileSize(context, total), percent)
        ForgeProgressBar(progress = fraction, modifier = Modifier.testTag("update_download_progress")
            .semantics { contentDescription = description })
        Text(stringResource(R.string.update_transfer_progress, Formatter.formatFileSize(context, bytes),
            Formatter.formatFileSize(context, total), percent))
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update_download_progress"))
        Text(stringResource(R.string.update_transfer_progress_unknown, Formatter.formatFileSize(context, bytes)))
    }
}

@Composable
internal fun UpdateTransferDialog(
    manager: ApkUpdateCoordinator,
    show: Boolean,
    canInstall: Boolean,
    hiddenAttempt: String? = null,
    onHideAttempt: ((String) -> Unit)? = null,
) {
    val attempt by remember(manager) {
        manager.state.map { it.visibleTransferAttempt() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    UpdateTransferDialogFrame(attempt, show, hiddenAttempt, onHideAttempt) {
        UpdateTransferPanel(manager, enabled = true, canInstall = canInstall)
    }
}

internal fun ApkUpdateState.visibleTransferAttempt(): String? =
    version?.takeUnless { phase == UpdatePhase.IDLE }?.let { "$it:$attempt" }

@Composable internal fun UpdateTransferDialogFrame(state: ApkUpdateState, show: Boolean, content: @Composable () -> Unit) {
    UpdateTransferDialogFrame(state.visibleTransferAttempt(), show, null, null, content)
}

@Composable
private fun UpdateTransferDialogFrame(
    attempt: String?,
    show: Boolean,
    hiddenAttempt: String?,
    onHideAttempt: ((String) -> Unit)?,
    content: @Composable () -> Unit,
) {
    var localHiddenAttempt by rememberSaveable { mutableStateOf<String?>(null) }
    val hidden = if (onHideAttempt == null) localHiddenAttempt else hiddenAttempt
    if (!show || attempt == null || hidden == attempt) return
    val dismiss = { if (onHideAttempt == null) localHiddenAttempt = attempt else onHideAttempt(attempt) }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.update_transfer_title)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                content()
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.update_later)) }
        },
    )
}

private fun failureMessage(failure: UpdateFailure?) = when (failure) {
    UpdateFailure.NETWORK -> R.string.update_transfer_network_error
    UpdateFailure.MISSING_CHECKSUM -> R.string.update_transfer_checksum_error
    UpdateFailure.INVALID_RELEASE -> R.string.update_transfer_release_error
    UpdateFailure.INTEGRITY -> R.string.update_transfer_integrity_error
    UpdateFailure.INVALID_APK -> R.string.update_transfer_apk_error
    UpdateFailure.SIGNATURE_MISMATCH -> R.string.update_transfer_signature_error
    UpdateFailure.STORAGE -> R.string.update_transfer_storage_error
    UpdateFailure.RECOVERY -> R.string.update_transfer_recovery_error
    else -> R.string.update_transfer_install_error
}
