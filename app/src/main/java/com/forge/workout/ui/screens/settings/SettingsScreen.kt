package com.forge.workout.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Scale
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.forge.workout.R
import com.forge.workout.preferences.*
import com.forge.workout.ui.components.ScreenHeader
import com.forge.workout.ui.components.RestAlertSettings
import com.forge.workout.ui.components.ForgeIconTile
import com.forge.workout.ui.components.ForgePanel
import com.forge.workout.ui.components.ForgeSectionHeading
import com.forge.workout.ui.screens.workout.restLabel
import com.forge.workout.viewmodel.BackupUiState
import com.forge.workout.viewmodel.UpdateViewModel
import com.forge.workout.updates.ApkUpdateCoordinator
import com.forge.workout.updates.AvailableUpdate

@Composable internal fun SettingsScreen(
    preferences: UserPreferences,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onUnit: (WeightUnit) -> Unit,
    onRest: (Int) -> Unit,
    onConfirmation: (Boolean) -> Unit,
    backupState: BackupUiState,
    onWriteBackup: (Uri) -> Unit,
    onReadBackup: (Uri) -> Unit,
    onConfirmRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    updates: UpdateViewModel,
    onCheckUpdates: () -> Unit,
    updateDownloads: ApkUpdateCoordinator,
    canInstallUpdate: Boolean,
    onDownloadUpdate: (AvailableUpdate) -> Unit,
) {
    BackHandler(enabled = backupState.busy) { }
    val editable = !backupState.busy && backupState.pending == null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(stringResource(R.string.settings), stringResource(R.string.settings_subtitle), { if (!backupState.busy) onBack() })
        SettingsSection(R.string.settings_display_section)
        SettingsCard(Icons.Rounded.Palette, stringResource(R.string.appearance)) {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    val label = when (mode) {
                        ThemeMode.SYSTEM -> R.string.theme_system
                        ThemeMode.LIGHT -> R.string.theme_light
                        ThemeMode.DARK -> R.string.theme_dark
                    }
                    RadioSetting(stringResource(label), preferences.themeMode == mode, editable) { onTheme(mode) }
                }
            }
        }
        SettingsCard(Icons.Rounded.Scale, stringResource(R.string.weight_unit)) {
            Column(Modifier.selectableGroup()) {
                WeightUnit.entries.forEach { unit ->
                    val label = if (unit == WeightUnit.KG) R.string.kilograms else R.string.pounds
                    RadioSetting(stringResource(label), preferences.weightUnit == unit, editable) { onUnit(unit) }
                }
            }
        }
        SettingsSection(R.string.settings_training_section)
        SettingsCard(Icons.Rounded.Timer, stringResource(R.string.rest_timer)) {
            Text(stringResource(R.string.rest_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                PreferencesRepository.REST_PRESETS.forEach { seconds ->
                    FilterChip(selected = preferences.restSeconds == seconds, onClick = { onRest(seconds) },
                        enabled = editable, label = { Text(restLabel(seconds)) }, shape = MaterialTheme.shapes.medium)
                }
            }
        }
        SettingsCard(Icons.Rounded.Notifications, stringResource(R.string.rest_alert_settings_title)) {
            RestAlertSettings(enabled = editable)
        }
        SettingsSection(R.string.settings_data_section)
        SettingsCard(Icons.Rounded.DeleteOutline, stringResource(R.string.confirm_deletion)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(preferences.confirmDeletion,
                enabled = editable, role = Role.Switch, onValueChange = onConfirmation),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.confirm_deletion_description), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(preferences.confirmDeletion, onCheckedChange = null, enabled = editable)
            }
        }
        SettingsCard(Icons.Rounded.Backup, stringResource(R.string.backup_title)) {
            BackupRestoreContent(backupState, onWriteBackup, onReadBackup, onConfirmRestore, onCancelRestore)
        }
        SettingsSection(R.string.settings_app_section)
        SettingsCard(Icons.Rounded.Info, stringResource(R.string.about)) {
            UpdateSettings(updates, enabled = editable,
                onCheck = onCheckUpdates, downloads = updateDownloads, canInstall = canInstallUpdate, onDownload = onDownloadUpdate)
        }
        Spacer(Modifier.height(24.dp))
    }
}
@Composable
private fun SettingsSection(title: Int) {
    ForgeSectionHeading(stringResource(title), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    ForgePanel(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ForgeIconTile(icon, highlighted = true)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun RadioSetting(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
